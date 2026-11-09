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
 * reactions, forward, search, disappearing messages and chat flags; groups, roles and group links (step 6).
 * Photos, GIFs, video, voice and files: see the media section below and SecuredMedia.kt (docs/media-plan.md).
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

    /**
     * Current chat list, pinned first, then newest activity first. Cheap: served from the engine-side cache.
     * Always contains Saved Messages ([SAVED_MESSAGES_CHAT_ID]) once a profile exists.
     */
    fun chats(): List<ChatSummary>

    /** Loads the newest [limit] messages of a chat, oldest first. Same as `messages(chatId, MessagePage(limit))`. */
    fun messages(chatId: Long, limit: Int, callback: SecuredCallback<List<SecuredMessage>>)

    /** Loads one page of a chat (older, newer, around an id, or a text search), oldest first. */
    fun messages(chatId: Long, page: MessagePage, callback: SecuredCallback<List<SecuredMessage>>)

    /** Creates a one-time invite link on our relay (for example https://encrogram.nytmanager.com/i#...). */
    fun createInvite(callback: SecuredCallback<SecuredInvite>)

    /** Checks a pasted or tapped link before connecting. Never contacts a server. */
    fun checkLink(link: String): LinkCheck

    /**
     * Connects using a link that [checkLink] approved. The new chat appears via [SecuredListener.onChatsChanged].
     * Our own invite fails with [SecuredError.Kind.OWN_LINK] (notes to yourself go to Saved Messages).
     */
    fun connect(link: String, callback: SecuredCallback<Unit>)

    /** Sends text. Same as `sendMessage(chatId, text, null, callback)`. */
    fun sendText(chatId: Long, text: String, callback: SecuredCallback<SecuredMessage>)

    /** Sends text, optionally as a reply to [replyToId]. The returned message is also the start of its status updates. */
    fun sendMessage(chatId: Long, text: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>)

    /**
     * [sendMessage] with group mentions: [mentions] maps each [SecuredMentions.MentionToken.key] in [text] to the member's
     * [GroupMember.memberId]. Only keys that still appear as mentions in the text are sent, each member once, at most
     * [SecuredMentions.MAX_MENTIONS]; outside groups they are ignored. If the core refuses the mentions, the text goes
     * without them.
     */
    fun sendMessage(chatId: Long, text: String, replyToId: Long?, mentions: Map<String, Long>, callback: SecuredCallback<SecuredMessage>) =
        sendMessage(chatId, text, replyToId, callback)

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

    // ---- Backup with 12 words (docs/recovery-phrase-plan.md) -----------------------------------------------------
    // Defaults fail with NOT_ALLOWED so callers compile before an engine implements them.

    /** Current backup state. Cheap. Changes arrive through [SecuredListener.onBackupStateChanged]. */
    fun backupState(): BackupState = BackupState()

    /** 12 new BIP39 English words for this profile. Nothing is stored or uploaded until [enableBackup]. */
    fun newPhrase(callback: SecuredCallback<List<String>>) = notYet(callback)

    /**
     * The user wrote the words down and confirmed some of them: stores them on this phone (behind the screen lock),
     * turns automatic backup on and uploads the first backup.
     */
    fun enableBackup(words: List<String>, callback: SecuredCallback<Unit>) = notYet(callback)

    /**
     * The 12 words again. The UI must first confirm the phone's screen lock (KeyguardManager) and call this within
     * 30 seconds; otherwise it fails with [SecuredError.Kind.NEEDS_UNLOCK].
     */
    fun showPhrase(callback: SecuredCallback<List<String>>) = notYet(callback)

    /** Uploads a fresh backup now (pauses receiving for a second or two), even when [BackupState.otherPhone] is set. */
    fun backupNow(callback: SecuredCallback<Unit>) = notYet(callback)

    /** Turns backup off: deletes the server copy, then the words and keys stored on this phone. */
    fun disableBackup(callback: SecuredCallback<Unit>) = notYet(callback)

    /**
     * Checks [words] and looks the backup up on the server without restoring, so the UI can show its date.
     * Fails with [SecuredError.Kind.WRONG_PHRASE] (not 12 valid words) or [SecuredError.Kind.NO_BACKUP].
     */
    fun findBackup(words: List<String>, callback: SecuredCallback<BackupInfo>) = notYet(callback)

    /**
     * Restores the backup for [words] on a phone with no Secured profile yet ([EngineState.NEEDS_PROFILE]), then
     * re-syncs each chat's encryption and moves it to new relay addresses as contacts come online. Backup stays on.
     */
    fun restore(words: List<String>, callback: SecuredCallback<Unit>) = notYet(callback)

    /** Up to [limit] BIP39 English words starting with [prefix] (lowercase), for typing suggestions. Local only. */
    fun phraseSuggestions(prefix: String, limit: Int): List<String> = emptyList()

    // ---- Groups and channels (docs/groups-plan.md, first release) -----------------------------------------------
    // Group chat ids are GROUP_CHAT_ID_BASE + the core's groupId. messages, sendMessage, itemAction, chatAction and
    // markRead accept them. ChatAction.Delete on a group: the owner with notifyContact=true deletes it for everyone;
    // otherwise you leave and it is deleted on this phone. DeleteForEveryone: your own message is deleted for all;
    // someone else's needs admin and shows "deleted by admin". Status DELIVERED in a group means every member got it.

    /** Creates a group; [channel] = only admins can post and people joining by link are observers. Result: chat id. */
    fun createGroup(name: String, channel: Boolean, callback: SecuredCallback<Long>) = notYet(callback)

    /** Invites Secured contacts (admins and the owner only). */
    fun addMembers(chatId: Long, contactChatIds: List<Long>, callback: SecuredCallback<Unit>) = notYet(callback)

    /** Accepts an invitation ([ChatSummary.invited]). Declining = [chatAction] Delete. */
    fun joinGroup(chatId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    fun members(chatId: Long, callback: SecuredCallback<List<GroupMember>>) = notYet(callback)

    fun setMemberRole(chatId: Long, memberId: Long, role: MemberRole, callback: SecuredCallback<Unit>) = notYet(callback)

    fun removeMember(chatId: Long, memberId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    /** The group's invite link on our relay, created if needed (joiners: member, or observer in a channel). */
    fun groupLink(chatId: Long, callback: SecuredCallback<SecuredInvite>) = notYet(callback)

    fun deleteGroupLink(chatId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    /** Leaves the group; the chat stays until deleted. */
    fun leaveGroup(chatId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    // ---- media (docs/media-plan.md). Handles are (chatId, messageId); nothing here names a server or protocol. ----
    // File events never reach onMessageReceived (one message, one notification): states arrive as onMessageChanged,
    // progress as onMediaProgress.

    /** Strips metadata, makes the preview and stores the file encrypted, off the main thread. Errors: TOO_LARGE, UNSUPPORTED, STORAGE. */
    fun prepareMedia(input: MediaInput, callback: SecuredCallback<PreparedMedia>) = notYet(callback)

    /** Gives back a prepared file that won't be sent (its encrypted copy is deleted). */
    fun discardMedia(prepared: PreparedMedia) {}

    fun sendMedia(chatId: Long, prepared: PreparedMedia, callback: SecuredCallback<SecuredMessage>) =
        sendMedia(chatId, prepared, "", null, callback)

    /**
     * Sends [prepared] once, with [caption] (up to 1024 characters, may be empty) in the same message; the UI shows the
     * caption as its own bubble. Returns the message (media UPLOADING). Saved Messages: NOT_ALLOWED in v1.
     */
    fun sendMedia(chatId: Long, prepared: PreparedMedia, caption: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>) =
        notYet(callback)

    /** Tap to download. Before the file can be checked it is queued (DOWNLOADING, 0 bytes). */
    fun acceptMedia(chatId: Long, messageId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    /** Incoming: stops the download, back to WAITING. Outgoing: stops the upload (FAILED, CANCELLED). */
    fun cancelMedia(chatId: Long, messageId: Long, callback: SecuredCallback<Unit>) = notYet(callback)

    /** The plain bytes in memory (photos, GIFs, voice, small video). Over 16 MB: Kind.TOO_LARGE, use [openMediaForPlayback]. */
    fun readMediaBytes(chatId: Long, messageId: Long, callback: SecuredCallback<ByteArray>) = notYet(callback)

    /** Random access to a readable file of any size. Close it (or [releasePlayback]) when the player closes. */
    fun openMediaForPlayback(chatId: Long, messageId: Long, callback: SecuredCallback<MediaPlayback>) = notYet(callback)

    fun releasePlayback(playback: MediaPlayback) {
        playback.close()
    }

    // ---- network ----------------------------------------------------------------------------------------------

    /** False while the phone has no usable network; screens then say "Waiting for network…" like Telegram's. */
    fun isOnline(): Boolean = true
}

/** Groups start here so their chat ids never collide with contacts (small positive) or Saved Messages (-1). */
const val GROUP_CHAT_ID_BASE: Long = 1L shl 40

fun isGroupChatId(chatId: Long): Boolean = chatId >= GROUP_CHAT_ID_BASE

enum class ChatKind { DIRECT, GROUP, CHANNEL }

/** SimpleX roles we show (relay/author map to observer/member). */
enum class MemberRole { OBSERVER, MEMBER, MODERATOR, ADMIN, OWNER }

enum class MemberStatus { INVITED, CONNECTING, CONNECTED, LEFT, REMOVED }

/** [memberId] is the core's groupMemberId: stable per member in this group, never a Telegram id. */
data class MessageSender(val memberId: Long, val displayName: String, val role: MemberRole)

/** [contactChatId]: this member's 1:1 Secured chat id when you have them as a contact (core memberContactId), else null. */
data class GroupMember @JvmOverloads constructor(
    val memberId: Long,
    val displayName: String,
    val role: MemberRole,
    val status: MemberStatus,
    val isMe: Boolean,
    val contactChatId: Long? = null,
)

/** A service row. [memberName] is who it is about (null when it is you or the group itself). */
/** [groupName]: for [Kind.INVITED] rows in a 1:1 chat, the group you were invited to (else null). */
data class GroupEvent @JvmOverloads constructor(
    val kind: Kind,
    val memberName: String? = null,
    val role: MemberRole? = null,
    val groupName: String? = null,
) {
    /** [JOINED_BY_LINK]: [memberName] joined through the group's invite link (Telegram: "X joined the group via invite link"). */
    enum class Kind { CREATED, INVITED, JOINED, LEFT, REMOVED, YOU_REMOVED, ROLE_CHANGED, GROUP_DELETED, GROUP_UPDATED, DELETED_BY_ADMIN, JOINED_BY_LINK }
}

private fun <T> notYet(callback: SecuredCallback<T>) =
    callback.onResult(SecuredResult.Err(SecuredError(SecuredError.Kind.NOT_ALLOWED, "This isn't available in this version yet.")))

/**
 * [enabled]: the words exist on this phone and backups upload automatically. [lastBackupMs]: the last successful
 * upload. [lastError]: a readable reason the last automatic upload failed, or null. [running]: an upload is under way.
 * [otherPhone]: another phone uploaded a backup with the same words after this phone's last upload, so automatic
 * uploads are paused here (they would overwrite it). [SecuredEngine.backupNow] uploads anyway and makes this phone
 * the backup phone again.
 */
data class BackupState(
    val enabled: Boolean = false,
    val lastBackupMs: Long? = null,
    val lastError: String? = null,
    val running: Boolean = false,
    val otherPhone: Boolean = false,
)

/** What [SecuredEngine.findBackup] found: when that backup was made. */
data class BackupInfo(val createdAtMs: Long)

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
    /** [SecuredEngine.backupState] changed. */
    fun onBackupStateChanged() {}
    /** [SecuredEngine.isOnline] changed. */
    fun onConnectionChanged(online: Boolean) {}
    /**
     * A file is moving: live progress for one message's media, throttled (about 4 a second, always the final value).
     * Never sent together with onMessageChanged per step: a cell updates its own progress ring, the list doesn't rebuild.
     */
    fun onMediaProgress(chatId: Long, messageId: Long, transferredBytes: Long, totalBytes: Long) {}
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
    /** Media: [TOO_LARGE] (over 100 MB, or over 16 MB for readMediaBytes), [UNSUPPORTED] (send it as a file), [STORAGE] (phone full). */
    enum class Kind { ENGINE_NOT_RUNNING, NO_PROFILE, UNAPPROVED_RELAY, INVALID_LINK, OWN_LINK, WRONG_PHRASE, NO_BACKUP, NEEDS_UNLOCK, NETWORK, ENGINE, NOT_ALLOWED, UNKNOWN, TOO_LARGE, UNSUPPORTED, STORAGE }
}

data class SecuredProfile(val displayName: String)

data class ChatSummary @JvmOverloads constructor(
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
    val kind: ChatKind = ChatKind.DIRECT,
    /** Groups: current members including you. */
    val memberCount: Int? = null,
    /** Groups: your role. */
    val myRole: MemberRole? = null,
    /** False for observers (a channel's subscribers) and while an invitation is not accepted. */
    val canPost: Boolean = true,
    /** Groups: who sent the last message (null when it was you or a service row). */
    val lastMessageSender: String? = null,
    /** Groups: you were invited and have not joined yet ([SecuredEngine.joinGroup] or delete to decline). */
    val invited: Boolean = false,
    /** Set when the newest item is a service row ("Ann joined"): the UI words it as the preview; [lastMessageText] is then null. */
    val lastEvent: GroupEvent? = null,
    /** Groups: you are no longer a member (removed, left, or the group was deleted). The chat stays until deleted. */
    val gone: Boolean = false,
    /** The last message carries media: the UI shows "Photo", "Video"... then [lastMessageText] (the caption, or a file's name). */
    val lastMessageMedia: MediaKind? = null,
)

data class SecuredMessage @JvmOverloads constructor(
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
    /** Groups: who sent it (null for 1:1 chats and for your own messages). */
    val sender: MessageSender? = null,
    /** Groups: a service row ("X joined") instead of a message; [text] is empty. */
    val event: GroupEvent? = null,
    /** Photo, GIF, video, voice or file; [text] is then its caption (may be empty). Null for text, service rows and deleted messages. */
    val media: SecuredMedia? = null,
    /**
     * Groups: one of the earlier messages the group sent when you joined. Shown like any other message, but never
     * notifies and never counts as unread (it was already read in the engine).
     */
    val history: Boolean = false,
    /** Groups: each `@name` in [text] that names a member -> that member's [GroupMember.memberId] (members known on this phone). */
    val mentions: Map<String, Long> = emptyMap(),
    /**
     * Groups: someone else's message that mentions you or replies to one of your messages. It notifies even in a muted
     * group, as in SimpleX and Telegram; the UI may highlight it.
     */
    val mentionsMe: Boolean = false,
) {
    /** Ids and status only: text and quotes are user content and must never reach a log. */
    override fun toString() = "SecuredMessage($chatId/$messageId, $status${media?.let { ", $it" } ?: ""})"
}

/** [mediaKind]: the quoted message carries media (the UI shows "Photo"... when [text] is empty). */
data class QuotePreview @JvmOverloads constructor(
    val messageId: Long?,
    val text: String,
    val outgoing: Boolean,
    val mediaKind: MediaKind? = null,
)

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
    /** Muting a group still lets messages that mention you notify ([SecuredMessage.mentionsMe]). */
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

/**
 * Saved Messages: the user's own notes (SimpleX's local note folder). Text only, stored on this phone, never sent
 * to any relay. Works with [SecuredEngine.messages], [SecuredEngine.sendMessage] and [ItemAction] Edit, Delete and
 * ForwardTo (both ways); not with reactions, replies, disappearing messages, verification or mute. Its messages are
 * outgoing with status [MessageStatus.DELIVERED]; the UI shows no ticks for them. [ChatAction.ClearHistory] clears all notes (the row stays); Delete, pin and the rest fail with NOT_ALLOWED.
 * [ChatSummary.displayName] is empty; the UI shows its own localized name.
 */
const val SAVED_MESSAGES_CHAT_ID: Long = -1L

data class SecuredInvite(val link: String)

sealed class LinkCheck {
    object Approved : LinkCheck()
    data class UnapprovedRelay(val host: String) : LinkCheck()
    object Malformed : LinkCheck()
}
