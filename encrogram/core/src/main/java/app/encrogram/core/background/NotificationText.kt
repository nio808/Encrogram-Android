package app.encrogram.core.background

/**
 * Every text decision of a Secured notification, pure so a JVM test can pin it down.
 * HIDDEN mode only: nothing here takes a message, a contact name or a text. Counts and ids are all it knows.
 */
internal object NotificationText {

    const val TITLE = "Encrogram"
    const val SERVICE_TITLE = "Encrogram Secured"
    const val SERVICE_TEXT = "Secured is connected"
    const val GROUP_NAME = "Encrogram Secured"
    const val MESSAGES_CHANNEL_NAME = "Secured messages"
    const val SERVICE_CHANNEL_NAME = "Secured connection"
    const val HIDE_ACTION = "Hide"

    /** [count] is how many unseen messages that chat has; the text never says who or what. */
    fun messageText(count: Int): String =
        if (count <= 1) "New Secured message" else "$count new Secured messages"

    /**
     * Notification id of a chat: the 32-bit hash of our own engine's local chatId (Long.hashCode, which is the
     * chatId itself for ids below 2^31). Never a Telegram dialog id. Posted with a tag, so Telegram's untagged ids
     * cannot clash with it.
     */
    fun notificationId(chatId: Long): Int = chatId.hashCode()

    /** Id of the group summary. Chat ids are positive, so a negative id cannot meet a chat id. */
    const val SUMMARY_ID = -1

    /** Id of the foreground-service notification (untagged, large so it stays clear of Telegram's small ids). */
    const val SERVICE_ID = 0x454E43
}
