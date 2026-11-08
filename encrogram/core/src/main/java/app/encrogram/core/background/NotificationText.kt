package app.encrogram.core.background

/**
 * Every text decision of a Secured notification, pure so a JVM test can pin it down.
 * HIDDEN mode only: nothing here takes a message, a contact or group name or a text. Counts are all it knows.
 */
internal object NotificationText {

    const val TITLE = "Secured"
    const val SERVICE_TITLE = "Encrogram Secured"
    const val SERVICE_TEXT = "Receiving Secured messages in the background"  // not "connected": shown in every engine state
    const val GROUP_NAME = "Encrogram Secured"
    const val MESSAGES_CHANNEL_NAME = "Secured messages"
    const val SERVICE_CHANNEL_NAME = "Secured connection"
    const val HIDE_ACTION = "Hide"

    /**
     * The one combined text. [total] unseen messages over [chats] chats; the text never says who or what.
     * "1 new message", "5 new messages", "5 new messages in 3 chats".
     */
    fun combinedText(total: Int, chats: Int): String {
        val n = if (total < 1) 1 else total
        val base = if (n == 1) "1 new message" else "$n new messages"
        return if (chats > 1) "$base in $chats chats" else base
    }

    /** Lock-screen version: no counts at all. */
    const val PUBLIC_TEXT = "New Secured message"

    /** Id of the single combined message notification (posted with our own tag, so Telegram's untagged ids cannot clash). */
    const val MESSAGES_ID = 1

    /** Id of the foreground-service notification (untagged, large so it stays clear of Telegram's small ids). */
    const val SERVICE_ID = 0x454E43
}
