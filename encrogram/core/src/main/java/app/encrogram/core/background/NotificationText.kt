package app.encrogram.core.background

import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.isGroupChatId

/**
 * Every text decision of a Secured notification, pure so a JVM test can pin it down.
 * HIDDEN mode only: nothing here takes a message, a contact or group name or a text. Counts and ids are all it knows.
 */
internal object NotificationText {

    const val TITLE = "Encrogram"
    const val SERVICE_TITLE = "Encrogram Secured"
    const val SERVICE_TEXT = "Receiving Secured messages in the background"  // not "connected": shown in every engine state
    const val GROUP_NAME = "Encrogram Secured"
    const val MESSAGES_CHANNEL_NAME = "Secured messages"
    const val SERVICE_CHANNEL_NAME = "Secured connection"
    const val HIDE_ACTION = "Hide"

    /** [count] is how many unseen messages that chat has; the text never says who or what. */
    fun messageText(count: Int): String =
        if (count <= 1) "New Secured message" else "$count new Secured messages"

    /**
     * Notification id of a chat, from our own engine's local chatId only (never a Telegram dialog id; posted with our
     * tag, so Telegram's untagged ids cannot clash with it). The chat kind is mixed in so a group and a contact never
     * share an id: a plain Long.hashCode folds group chat id `(1 shl 40) + g` onto contact id `g xor 256`.
     *  - contacts: the contact id itself (0 .. 2^31-1; the core's ids start at 1, so this is what earlier builds used);
     *  - groups: `Int.MIN_VALUE + (g and 0x3FFFFFFF)`, always below -1, so never a contact id or [SUMMARY_ID]
     *    (two groups share an id only when their ids differ by a multiple of 2^30).
     */
    fun notificationId(chatId: Long): Int =
        if (isGroupChatId(chatId)) Int.MIN_VALUE + ((chatId - GROUP_CHAT_ID_BASE) and 0x3FFFFFFFL).toInt()
        else (chatId and 0x7FFFFFFFL).toInt()

    /** Id of the group summary. Contact ids map to 0 and above, groups below -1, so this id meets neither. */
    const val SUMMARY_ID = -1

    /** Id of the foreground-service notification (untagged, large so it stays clear of Telegram's small ids). */
    const val SERVICE_ID = 0x454E43
}
