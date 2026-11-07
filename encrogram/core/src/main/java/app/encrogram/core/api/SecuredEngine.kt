package app.encrogram.core.api

/**
 * The contract between the Secured UI and the Secured engine.
 *
 * Rules (see AGENTS.md, "The wall"):
 * - Nothing here knows about Telegram. The UI module talks to Secured only through this interface.
 * - Every callback and listener method is delivered on the Android main thread.
 * - Errors are reported as [SecuredError], never as raw engine JSON or exceptions.
 *
 * v2 (docs/v2-plan.md): one profile, one-time invites, 1:1 text chats with reply, edit, delete,
 * reactions, forward, search, disappearing messages and chat flags. Later: groups, files, voice.
 * Changes caused by our own actions and by the peer arrive the same way: through [SecuredListener].
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

    /** Changes the display name; sent to every contact. */
    fun updateProfile(displayName: String, callback: SecuredCallback<SecuredProfile>)

    /** Current chat list, pinned first, then newest activity first. Cheap: served from the engine-side cache. */
    fun chats(): List<ChatSummary>

    /** Loads the newest [limit] messages of a chat, oldest first. Same as `messages(chatId, MessagePage(limit))`. */
    fun messages(chatId: Long, limit: Int, callback: SecuredCallback<List<SecuredMessage>>)

    /** Loads one page of a chat (older, newer, around an id, or a text search), oldest first. */
    fun messages(chatId: Long, page: MessagePage, callback: SecuredCallback<List<SecuredMessage>>)

    /** Creates a one-time invite link on our relay (for example https://encrogram.nytmanager.com/i#...). */
    fun createInvite(callback: SecuredCallback<SecuredInvite>)

    /** Checks a pasted or tapped link before connecting. Never contacts a server. */
    fun checkLink(link: String): LinkCheck

    /** Connects using a link that [checkLink] approved. The new chat appears via [SecuredListener.onChatsChanged]. */
    fun connect(link: String, callback: SecuredCallback<Unit>)

    /** Sends text. Same as `sendMessage(chatId, text, null, callback)`. */
    fun sendText(chatId: Long, text: String, callback: SecuredCallback<SecuredMessage>)

    /** Sends text, optionally as a reply to [replyToId]. The returned message is also the start of its status updates. */
    fun sendMessage(chatId: Long, text: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>)

    /**
     * Acts on messages of one chat. Results arrive through the listener: [SecuredListener.onMessageChanged] for
     * edits, reactions and "marked deleted", [SecuredListener.onMessagesRemoved] for deletes,
     * [SecuredListener.onMessageReceived] for the forwarded copy in the target chat.
     * [ItemAction.React] and [ItemAction.Edit] take exactly one id.
     */
    fun itemAction(chatId: Long, messageIds: List<Long>, action: ItemAction, callback: SecuredCallback<Unit>)

    /** Acts on a whole chat. The new state arrives through [SecuredListener.onChatsChanged]. */
    fun chatAction(chatId: Long, action: ChatAction, callback: SecuredCallback<Unit>)

    fun markRead(chatId: Long)

    /** The security code to compare with the contact in person (shown as text or QR). */
    fun securityCode(chatId: Long, callback: SecuredCallback<String>)

    /** Marks the contact verified when [code] matches; null clears verification. Result: verified or not. */
    fun verifyContact(chatId: Long, code: String?, callback: SecuredCallback<Boolean>)
}

enum class EngineState { NOT_STARTED, STARTING, NEEDS_PROFILE, RUNNING, FAILED }

interface SecuredListener {
    fun onStateChanged(state: EngineState) {}
    fun onChatsChanged() {}
    /** A new message in a chat: incoming, or our own forwarded copy. */
    fun onMessageReceived(message: SecuredMessage) {}
    fun onMessageStatusChanged(chatId: Long, messageId: Long, status: MessageStatus) {}
    /** An existing message changed: edited, reaction, marked deleted, or timer started. */
    fun onMessageChanged(message: SecuredMessage) {}
    /** Messages gone from a chat: deleted (by either side) or expired. */
    fun onMessagesRemoved(chatId: Long, messageIds: List<Long>) {}
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
    enum class Kind { ENGINE_NOT_RUNNING, NO_PROFILE, UNAPPROVED_RELAY, INVALID_LINK, NETWORK, ENGINE, NOT_ALLOWED, UNKNOWN }
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
    /** True when the last message is ours; then [lastMessageStatus] says how far it got. */
    val lastMessageOutgoing: Boolean = false,
    val lastMessageStatus: MessageStatus? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** Local only: never sent to the contact. */
    val archived: Boolean = false,
    /** Disappearing-message timer agreed for this chat, or null when off. */
    val disappearingTtlSeconds: Int? = null,
)

data class SecuredMessage(
    val chatId: Long,
    val messageId: Long,
    val outgoing: Boolean,
    /** Empty when [deleted]: the UI shows "Message deleted" and never the old text. */
    val text: String,
    val timeMs: Long,
    val status: MessageStatus,
    val replyTo: QuotePreview? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val canEdit: Boolean = false,
    val canDeleteForEveryone: Boolean = false,
    val forwarded: Boolean = false,
    val reactions: List<ReactionCount> = emptyList(),
    /** When a disappearing message will be removed, or null. */
    val expiresAtMs: Long? = null,
)

data class QuotePreview(val messageId: Long?, val text: String, val outgoing: Boolean)

data class ReactionCount(val emoji: String, val count: Int, val mine: Boolean)

/** Honest delivery states. There is no "read" state: the protocol has no read receipts. */
enum class MessageStatus { SENDING, SENT_TO_SERVER, DELIVERED, FAILED, RECEIVED }

/** At most one of [beforeId], [afterId], [aroundId] is set. [search] filters by text within the chat. */
data class MessagePage(
    val count: Int,
    val beforeId: Long? = null,
    val afterId: Long? = null,
    val aroundId: Long? = null,
    val search: String? = null,
)

sealed class ItemAction {
    data class Edit(val newText: String) : ItemAction()
    object DeleteForMe : ItemAction()
    object DeleteForEveryone : ItemAction()
    /** [emoji] must be one of [SECURED_REACTIONS]. A user can have at most 3 reactions on one message. */
    data class React(val emoji: String, val add: Boolean) : ItemAction()
    data class ForwardTo(val toChatId: Long) : ItemAction()
}

sealed class ChatAction {
    data class SetPinned(val pinned: Boolean) : ChatAction()
    data class SetMuted(val muted: Boolean) : ChatAction()
    /** Local only. */
    data class SetArchived(val archived: Boolean) : ChatAction()
    object ClearHistory : ChatAction()
    data class Delete(val notifyContact: Boolean) : ChatAction()
    /** Null turns disappearing messages off. */
    data class SetDisappearing(val ttlSeconds: Int?) : ChatAction()
}

/** The only reactions the SimpleX protocol accepts. The heart is U+2764 without a variation selector. */
val SECURED_REACTIONS: List<String> = listOf("👍", "👎", "😀", "😂", "😢", "❤", "🚀", "✅")

data class SecuredInvite(val link: String)

sealed class LinkCheck {
    object Approved : LinkCheck()
    data class UnapprovedRelay(val host: String) : LinkCheck()
    object Malformed : LinkCheck()
}
