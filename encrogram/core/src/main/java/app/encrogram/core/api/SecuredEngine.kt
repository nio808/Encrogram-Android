package app.encrogram.core.api

/**
 * The contract between the Secured UI and the Secured engine.
 *
 * Rules (see AGENTS.md, "The wall"):
 * - Nothing here knows about Telegram. The UI module talks to Secured only through this interface.
 * - Every callback and listener method is delivered on the Android main thread.
 * - Errors are reported as [SecuredError], never as raw engine JSON or exceptions.
 *
 * MVP scope: one profile, one-time invite links, 1:1 text chats, delivery status.
 * Later: groups, files, voice, typing, backup, background receiving.
 */
interface SecuredEngine {

    /** Starts the engine if needed. Safe to call repeatedly. Progress arrives via [SecuredListener.onStateChanged]. */
    fun start()

    /** Stops the engine and closes its database. */
    fun stop()

    fun state(): EngineState

    fun addListener(listener: SecuredListener)
    fun removeListener(listener: SecuredListener)

    /** The local Secured profile, or null before [createProfile] has succeeded. */
    fun profile(): SecuredProfile?

    /** Creates the one local profile. The name is shown to contacts; it is not the Telegram name. */
    fun createProfile(displayName: String, callback: SecuredCallback<SecuredProfile>)

    /** Current chat list, newest activity first. Cheap: served from the engine-side cache. */
    fun chats(): List<ChatSummary>

    /** Loads the newest [limit] messages of a chat, oldest first. */
    fun messages(chatId: Long, limit: Int, callback: SecuredCallback<List<SecuredMessage>>)

    /** Creates a one-time invite link on our relay (for example https://encrogram.nytmanager.com/i#...). */
    fun createInvite(callback: SecuredCallback<SecuredInvite>)

    /** Checks a pasted or tapped link before connecting. Never contacts a server. */
    fun checkLink(link: String): LinkCheck

    /** Connects using a link that [checkLink] approved. The new chat appears via [SecuredListener.onChatsChanged]. */
    fun connect(link: String, callback: SecuredCallback<Unit>)

    fun sendText(chatId: Long, text: String, callback: SecuredCallback<SecuredMessage>)

    fun markRead(chatId: Long)
}

enum class EngineState { NOT_STARTED, STARTING, NEEDS_PROFILE, RUNNING, FAILED }

interface SecuredListener {
    fun onStateChanged(state: EngineState) {}
    fun onChatsChanged() {}
    fun onMessageReceived(message: SecuredMessage) {}
    fun onMessageStatusChanged(chatId: Long, messageId: Long, status: MessageStatus) {}
    fun onError(error: SecuredError) {}
}

fun interface SecuredCallback<T> {
    fun onResult(result: SecuredResult<T>)
}

sealed class SecuredResult<out T> {
    data class Ok<T>(val value: T) : SecuredResult<T>()
    data class Err(val error: SecuredError) : SecuredResult<Nothing>()
}

/** A user-readable failure. [message] is safe to show; it never contains message text or keys. */
data class SecuredError(val kind: Kind, val message: String) {
    enum class Kind { ENGINE_NOT_RUNNING, NO_PROFILE, UNAPPROVED_RELAY, INVALID_LINK, NETWORK, ENGINE, UNKNOWN }
}

data class SecuredProfile(val displayName: String)

data class ChatSummary(
    val chatId: Long,
    val displayName: String,
    val lastMessageText: String?,
    val lastMessageTimeMs: Long,
    val unreadCount: Int,
    val verified: Boolean,
    /** False while the contact has not finished connecting. */
    val ready: Boolean,
)

data class SecuredMessage(
    val chatId: Long,
    val messageId: Long,
    val outgoing: Boolean,
    val text: String,
    val timeMs: Long,
    val status: MessageStatus,
)

/** Honest delivery states. There is no "read" state in the MVP. */
enum class MessageStatus { SENDING, SENT_TO_SERVER, DELIVERED, FAILED, RECEIVED }

data class SecuredInvite(val link: String)

sealed class LinkCheck {
    object Approved : LinkCheck()
    data class UnapprovedRelay(val host: String) : LinkCheck()
    object Malformed : LinkCheck()
}
