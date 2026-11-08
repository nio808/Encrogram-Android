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
 * Posts Secured's own notifications. Hidden mode only: title "Secured", one combined notification ("5 new messages in 3 chats"), never a
 * sender or message text (see [NotificationText]). It uses only chat ids and counts; it cannot see Telegram's
 * NotificationsController and Telegram cannot see it.
 */
internal object SecuredNotifier {

    private const val TAG = "encrogram_secured"
    private const val GROUP_ID = "encrogram_secured"
    const val MESSAGES_CHANNEL = "encrogram_secured_messages"
    const val SERVICE_CHANNEL = "encrogram_secured_service"

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

    private val unread = UnreadTracker()

    /** One more unseen incoming message in [chatId]: updates the one combined notification. */
    fun onIncoming(context: Context, config: NotificationConfig, chatId: Long) {
        try {
            ensureChannels(context)
            // Swiped away (or cancelled by the system): start counting again instead of resurrecting old counts.
            if (!isShown(context)) unread.clearAll()
            unread.add(chatId)
            post(context, config, alert = true)
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.BG_NOTIFY_FAILED, t)
        }
    }

    private fun post(context: Context, config: NotificationConfig, alert: Boolean) {
        val publicVersion = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
            .setSmallIcon(config.smallIconRes)
            .setColor(config.accentColor)
            .setContentTitle(NotificationText.TITLE)
            .setContentText(NotificationText.PUBLIC_TEXT)
            .build()
        val message = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
            .setSmallIcon(config.smallIconRes)
            .setColor(config.accentColor)
            .setContentTitle(NotificationText.TITLE)
            .setContentText(NotificationText.combinedText(unread.total(), unread.chats()))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(tap(context, config, unread.singleChat(), NotificationText.MESSAGES_ID))
            .build()
        NotificationManagerCompat.from(context).notify(TAG, NotificationText.MESSAGES_ID, message)
    }

    /** The user has seen [chatId]: drop its share; cancels the notification when nothing unseen is left. */
    fun clear(context: Context, config: NotificationConfig?, chatId: Long) {
        try {
            unread.clear(chatId)
            if (!isShown(context)) unread.clearAll()  // swiped away: never bring it back
            if (unread.total() == 0 || config == null) cancel(context)
            else post(context, config, alert = false)
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.BG_NOTIFY_FAILED, t)
        }
    }

    /** The user opened the Secured list: everything counts as seen. */
    fun clearAll(context: Context) {
        try {
            unread.clearAll()
            cancel(context)
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.BG_NOTIFY_FAILED, t)
        }
    }

    private fun cancel(context: Context) = manager(context).cancel(TAG, NotificationText.MESSAGES_ID)

    private fun isShown(context: Context) =
        manager(context).activeNotifications.any { it.tag == TAG && it.id == NotificationText.MESSAGES_ID }
}
