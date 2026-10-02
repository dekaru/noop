package com.noop.journalsync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.appLaunchIntent

/** Pure decision (JVM-testable, no Android types): notify only when there is at least one ask. */
fun shouldNotifyPending(pendingCount: Int): Boolean = pendingCount > 0

/**
 * ZJS-F4 — posts ONE discreet "you have pending asks" notification after a successful
 * sync-on-open. Only called from the launch-triggered sync (the notifyPending flag on the
 * OneTimeWorkRequest): the 6h periodic worker never notifies. One notification total, not
 * one per ask; tapping it opens the app via the normal launch intent.
 */
object JournalPendingNotifier {
    private const val CHANNEL_ID = "journal"
    private const val NOTIF_ID = 4204 // 4201 ongoing connection, 4202 illness, 4203 inactivity

    /** Pure decision (JVM-testable): notify only when there is at least one pending ask. */
    fun shouldNotify(page: JournalSyncProtocol.PendingPage?): Boolean =
        shouldNotifyPending(page?.asks?.size ?: 0)

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun notifyPending(context: Context, count: Int) {
        if (count <= 0) return
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val openApp = PendingIntent.getActivity(
                context, 7,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = context.getString(R.string.journal_sync_pending_notif, count)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(context.getString(R.string.journal_sync_pending_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            // IMPORTANCE_LOW = silent, discreet: no sound, no heads-up.
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Journal",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.journal_sync_channel_description)
                },
            )
        }
    }
}
