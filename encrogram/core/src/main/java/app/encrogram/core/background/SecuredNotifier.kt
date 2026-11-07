package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.encrogram.core.internal.SecuredLog

/**
 * Posts Secured's own notifications. Hidden mode only: title "Encrogram", text "New Secured message", never a
 * sender or message text (see [NotificationText]). It uses only the chat id and a count; it cannot see Telegram's
 * NotificationsController and Telegram cannot see it.
 */
internal object SecuredNotifier {

    private const val TAG = "encrogram_secured"
    private const val GROUP_ID = "encrogram_secured"
    const val MESSAGES_CHANNEL = "encrogram_secured_messages"
    const val SERVICE_CHANNEL = "encrogram_secured_service"
    private const val GROUP_KEY = "app.encrogram.secured.MESSAGES"
    private const val EXTRA_COUNT = "encrogram_count"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, NotificationText.GROUP_NAME))
        nm.createNotificationChannel(
            NotificationChannel(MESSAGES_CHANNEL, NotificationText.MESSAGES_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                .apply { group = GROUP_ID },
        )
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, NotificationText.SERVICE_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                group = GROUP_ID
                setShowBadge(false)
            },
        )
    }

    private fun tap(context: Context, config: NotificationConfig, chatId: Long?, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            config.openIntent(chatId).putExtra(SecuredBackground.EXTRA_OPEN_TOKEN, BackgroundState.openToken(context)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Silent, ongoing, no counts or names. "Hide" opens the channel's system settings (SimpleX's pattern). */
    fun serviceNotification(context: Context, config: NotificationConfig): Notification {
        ensureChannels(context)
        val hide = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, SERVICE_CHANNEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(config.smallIconRes)
            .setColor(config.accentColor)
            .setContentTitle(NotificationText.SERVICE_TITLE)
            .setContentText(NotificationText.SERVICE_TEXT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tap(context, config, null, NotificationText.SERVICE_ID))
            .addAction(0, NotificationText.HIDE_ACTION, hide)
            .build()
    }

    /** One more unseen incoming message in [chatId]. The count lives in the posted notification's own extras. */
    fun onIncoming(context: Context, config: NotificationConfig, chatId: Long) {
        try {
            ensureChannels(context)
            val id = NotificationText.notificationId(chatId)
            val previous = manager(context).activeNotifications
                .firstOrNull { it.tag == TAG && it.id == id }?.notification?.extras?.getInt(EXTRA_COUNT, 0) ?: 0
            val count = previous + 1

            val generic = NotificationText.messageText(1)
            val publicVersion = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
                .setSmallIcon(config.smallIconRes)
                .setColor(config.accentColor)
                .setContentTitle(NotificationText.TITLE)
                .setContentText(generic)
                .build()
            val message = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
                .setSmallIcon(config.smallIconRes)
                .setColor(config.accentColor)
                .setContentTitle(NotificationText.TITLE)
                .setContentText(NotificationText.messageText(count))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setGroup(GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .addExtras(android.os.Bundle().apply { putInt(EXTRA_COUNT, count) })
                .setContentIntent(tap(context, config, chatId, id))
                .build()
            val summary = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
                .setSmallIcon(config.smallIconRes)
                .setColor(config.accentColor)
                .setContentTitle(NotificationText.TITLE)
                .setContentText(generic)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(tap(context, config, null, NotificationText.SUMMARY_ID))
                .build()
            val nm = NotificationManagerCompat.from(context)
            nm.notify(TAG, id, message)
            nm.notify(TAG, NotificationText.SUMMARY_ID, summary)
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.BG_NOTIFY_FAILED, t)
        }
    }

    /** Cancels one chat's notification, and the summary when no chat notification is left. */
    fun clear(context: Context, chatId: Long) {
        try {
            val nm = manager(context)
            nm.cancel(TAG, NotificationText.notificationId(chatId))
            if (nm.activeNotifications.none { it.tag == TAG && it.id != NotificationText.SUMMARY_ID }) {
                nm.cancel(TAG, NotificationText.SUMMARY_ID)
            }
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.BG_NOTIFY_FAILED, t)
        }
    }
}
