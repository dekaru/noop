package com.noop.journalsync

import android.content.Context
import android.content.SharedPreferences
import com.noop.data.SecurePrefs
import com.noop.push.PushEndpointPolicy

/**
 * Configuration for the Journal Sync client (spec zhoop-journal-sync-SPEC.md §3.4).
 *
 * Plain preferences hold non-secret state; the bearer token and the Cloudflare Access
 * service credentials live ONLY in SecurePrefs (condition D of Jorge's verdict) and are
 * never logged. Endpoint validation reuses [PushEndpointPolicy.validate] (read-only).
 */
class JournalSyncSettings private constructor(
    private val prefs: SharedPreferences,
    private val secrets: Lazy<SharedPreferences>,
) {
    enum class SyncState { IDLE, SYNCING, OK, AUTH_FAILED, ERROR }

    data class Snapshot(
        val enabled: Boolean,
        val endpoint: PushEndpointPolicy.ValidEndpoint?,
        val hasToken: Boolean,
        val hasCfCredentials: Boolean,
        val lastSuccessAt: Long?,
        val lastError: String?,
        val state: SyncState,
    ) {
        val ready: Boolean get() = enabled && endpoint != null && hasToken
    }

    fun snapshot(): Snapshot {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val endpoint = (PushEndpointPolicy.validate(endpointText()) as? PushEndpointPolicy.Result.Valid)?.endpoint
        return Snapshot(
            enabled = enabled,
            endpoint = endpoint,
            hasToken = !secrets.value.getString(KEY_TOKEN, null).isNullOrBlank(),
            hasCfCredentials = !secrets.value.getString(KEY_CF_CLIENT_ID, null).isNullOrBlank() &&
                !secrets.value.getString(KEY_CF_CLIENT_SECRET, null).isNullOrBlank(),
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 },
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            state = if (!enabled) SyncState.IDLE else runCatching {
                SyncState.valueOf(prefs.getString(KEY_STATE, SyncState.IDLE.name).orEmpty())
            }.getOrDefault(SyncState.IDLE),
        )
    }

    fun endpointText(): String = prefs.getString(KEY_ENDPOINT, "").orEmpty()

    /** Gate used by the worker before touching Keystore or the network. */
    fun enabledEndpoint(): PushEndpointPolicy.ValidEndpoint? {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        return (PushEndpointPolicy.validate(endpointText()) as? PushEndpointPolicy.Result.Valid)?.endpoint
    }

    fun token(): String? = secrets.value.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    fun cfClientId(): String? = secrets.value.getString(KEY_CF_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
    fun cfClientSecret(): String? = secrets.value.getString(KEY_CF_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() }

    fun saveEndpoint(raw: String): PushEndpointPolicy.Result {
        val validation = PushEndpointPolicy.validate(raw)
        val normalized = (validation as? PushEndpointPolicy.Result.Valid)?.endpoint?.url
            ?: return validation
        prefs.edit().putString(KEY_ENDPOINT, normalized).apply()
        return validation
    }

    fun saveToken(token: String) {
        val trimmed = token.trim()
        secrets.value.edit().let { edit ->
            if (trimmed.isEmpty()) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, trimmed)
        }.apply()
    }

    fun saveCfCredentials(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        secrets.value.edit().let { edit ->
            if (id.isEmpty()) edit.remove(KEY_CF_CLIENT_ID) else edit.putString(KEY_CF_CLIENT_ID, id)
            if (secret.isEmpty()) edit.remove(KEY_CF_CLIENT_SECRET) else edit.putString(KEY_CF_CLIENT_SECRET, secret)
        }.apply()
    }

    fun setEnabled(enabled: Boolean): Boolean = synchronized(statusLock) {
        if (enabled && !snapshot().copy(enabled = true).ready) return@synchronized false
        val edit = prefs.edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) {
            edit.putString(KEY_STATE, SyncState.IDLE.name).remove(KEY_LAST_ERROR)
        }
        check(edit.commit()) { "Could not persist journal sync enabled state" }
        true
    }

    fun recordRunning() = updateWhileEnabled {
        it.remove(KEY_LAST_ERROR).putString(KEY_STATE, SyncState.SYNCING.name)
    }

    fun recordSuccess(atMillis: Long = System.currentTimeMillis()) = updateWhileEnabled {
        it.putLong(KEY_LAST_SUCCESS, atMillis).remove(KEY_LAST_ERROR)
            .putString(KEY_STATE, SyncState.OK.name)
    }

    fun recordError(message: String, state: SyncState = SyncState.ERROR) = updateWhileEnabled {
        check(state == SyncState.ERROR || state == SyncState.AUTH_FAILED) {
            "recordError requires ERROR or AUTH_FAILED"
        }
        it.putString(KEY_LAST_ERROR, message.take(MAX_STATUS_CHARS))
            .putString(KEY_STATE, state.name)
    }

    private inline fun updateWhileEnabled(change: (SharedPreferences.Editor) -> SharedPreferences.Editor) =
        synchronized(statusLock) {
            if (!prefs.getBoolean(KEY_ENABLED, false)) return@synchronized
            check(change(prefs.edit()).commit()) { "Could not persist journal sync status" }
        }

    companion object {
        private const val PREFS = "journal_sync"
        private const val SECRETS = "journal_sync_secrets"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN = "bearer_token"
        private const val KEY_CF_CLIENT_ID = "cf_client_id"
        private const val KEY_CF_CLIENT_SECRET = "cf_client_secret"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_STATE = "state"
        private const val MAX_STATUS_CHARS = 300
        private val statusLock = Any()

        fun from(context: Context) = JournalSyncSettings(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecurePrefs.of(context.applicationContext, SECRETS) },
        )

        internal fun forTest(prefs: SharedPreferences, secrets: SharedPreferences) =
            JournalSyncSettings(prefs, lazyOf(secrets))
    }
}
