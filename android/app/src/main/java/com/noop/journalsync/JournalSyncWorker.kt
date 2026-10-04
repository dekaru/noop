package com.noop.journalsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.WorkerParameters
import com.noop.data.JournalEntry
import com.noop.data.WhoopDatabase
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Journal Sync worker (spec zhoop-journal-sync-SPEC.md §3.4, condition C of Jorge's verdict:
 * fully isolated from SelfHostedPushWorker — own unique work names, own settings, own state).
 */
class JournalSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_PERIODIC = "journal-sync-periodic"
        const val UNIQUE_NOW = "journal-sync-now"
        const val PERIOD_HOURS = 6L

        /** ZJS-F4: input-data key; only the launch sync sets it true (never the 6h worker). */
        const val KEY_NOTIFY_PENDING = "notifyPending"

        /** Own attempt cap (same idea as PUSH_MAX_ATTEMPTS, independent constant). */
        const val JOURNAL_SYNC_MAX_ATTEMPTS = 16

        internal fun shouldRetry(runAttemptCount: Int): Boolean =
            runAttemptCount + 1 < JOURNAL_SYNC_MAX_ATTEMPTS
    }

    override suspend fun doWork(): Result {
        val settings = JournalSyncSettings.from(applicationContext)
        val snapshot = settings.snapshot()
        // Disabled / not configured: succeed without touching the network.
        if (!snapshot.ready) return Result.success()
        val endpoint = snapshot.endpoint ?: return Result.success()
        val token = settings.token() ?: return Result.success()

        settings.recordRunning()
        val api = OkHttpJournalSyncApi(
            baseUrl = endpoint.url,
            token = token,
            cfClientId = settings.cfClientId(),
            cfClientSecret = settings.cfClientSecret(),
        )
        val database = WhoopDatabase.get(applicationContext)
        val store = object : JournalStore {
            override suspend fun upsert(rows: List<JournalEntry>) {
                database.whoopDao().upsertJournal(rows)
            }
        }
        val catalogProvider: suspend () -> List<JournalSyncProtocol.CatalogQuestion> = {
            JournalSyncRunner.catalogFromItems(
                com.noop.ui.loadJournalCatalogItems(applicationContext),
            )
        }
        val runner = JournalSyncRunner(api, store, catalogProvider)

        val outcome = try {
            runner.run()
        } catch (error: IOException) {
            return finishRetry(settings, "io: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return finishRetry(settings, "unexpected: ${error.javaClass.simpleName}")
        }

        return when (outcome) {
            is SyncOutcome.Success -> {
                settings.recordSuccess()
                // ZJS-F6: adopt the server's SSOT catalog (only while sync is enabled; local wins,
                // customs/hidden never deleted). Skipped when the server hash did not change.
                val remoteCatalog = outcome.remoteCatalog
                if (snapshot.enabled && remoteCatalog != null &&
                    !CatalogAdoption.alreadyApplied(applicationContext, remoteCatalog.hash)
                ) {
                    runCatching {
                        CatalogAdoption.applyRemoteCatalog(applicationContext, remoteCatalog)
                    }
                }
                // ZJS-F4: only the launch-triggered sync may notify about pending asks.
                if (inputData.getBoolean(KEY_NOTIFY_PENDING, false)) {
                    runCatching { api.getPending() }.onSuccess { result ->
                        if (result is ApiResult.Ok) {
                            val page = result.page
                            if (JournalPendingNotifier.shouldNotify(page)) {
                                JournalPendingNotifier.notifyPending(
                                    applicationContext,
                                    page!!.asks.size,
                                )
                            }
                        }
                    }
                }
                Result.success()
            }
            is SyncOutcome.AuthFailed -> {
                settings.recordError("token rejected (${outcome.code})", JournalSyncSettings.SyncState.AUTH_FAILED)
                // Never hammer the server with bad credentials.
                Result.success()
            }
            is SyncOutcome.Retry -> finishRetry(settings, outcome.message)
            is SyncOutcome.Fatal -> {
                settings.recordError(outcome.message)
                Result.failure()
            }
        }
    }

    private fun finishRetry(settings: JournalSyncSettings, message: String): Result {
        settings.recordError(message)
        return if (shouldRetry(runAttemptCount)) Result.retry() else Result.failure()
    }
}

/** Own scheduler object; MainActivity/settings wiring happens in a later child task. */
object JournalSyncScheduler {
    /** ZJS-F8: minimum interval between two foreground (launch/resume) triggered syncs. */
    const val RESUME_SYNC_THROTTLE_MS: Long = 5L * 60_000L

    /**
     * ZJS-F8: pure decision for a foreground sync (launch or ON_RESUME).
     * Launch fires right after create; resume fires right after launch. The throttle on the
     * shared prefs marker (written by BOTH launch and resume) prevents the double-sync.
     */
    fun shouldSyncOnForeground(lastAt: Long, now: Long, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (lastAt <= 0L) return true // never synced before (fresh install / cleared prefs)
        return now - lastAt >= RESUME_SYNC_THROTTLE_MS
    }
    internal val NETWORK = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enablePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<JournalSyncWorker>(JournalSyncWorker.PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            JournalSyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disable(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(JournalSyncWorker.UNIQUE_PERIODIC)
        manager.cancelUniqueWork(JournalSyncWorker.UNIQUE_NOW)
    }

    fun syncNow(context: Context, notifyPending: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<JournalSyncWorker>()
            .setConstraints(NETWORK)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(workDataOf(JournalSyncWorker.KEY_NOTIFY_PENDING to notifyPending))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            JournalSyncWorker.UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
