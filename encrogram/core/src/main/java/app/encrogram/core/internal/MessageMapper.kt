package app.encrogram.core.internal

import app.encrogram.core.api.ChatKind
import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.GroupEvent
import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MessageSender
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.QuotePreview
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.api.SecuredMessage

/**
 * Turns parsed engine items and chats into the contract's [SecuredMessage] and [ChatSummary]. Pure logic.
 * A deleted message never carries text, a quote or reactions, whatever the engine JSON still holds.
 */
internal object MessageMapper {

    /**
     * [myRole]: our role in the item's group (null for 1:1 chats and notes). Someone else's group message can be
     * deleted for everyone only by a moderator or higher whose role is at least the sender's (Commands.hs:4079-4088).
     */
    fun toMessage(i: CoreItem, myRole: MemberRole? = null): SecuredMessage {
        val deleted = i.deleted
        val event = i.event
        val service = event != null
        val sender = i.sender?.takeIf { i.group && !i.outgoing }
        val moderate = !deleted && !service && i.group && !i.outgoing && i.shared && sender != null &&
            myRole != null && myRole >= MemberRole.MODERATOR && myRole >= sender.role
        return SecuredMessage(
            chatId = i.chatId,
            messageId = i.itemId,
            outgoing = i.outgoing,
            text = if (deleted || service) "" else i.text,
            timeMs = i.timeMs,
            status = i.status,
            replyTo = if (deleted || service) null else i.quote?.let { QuotePreview(it.itemId, InputRules.preview(it.text), it.outgoing) },
            edited = i.edited,
            deleted = deleted,
            // Notes have no shared message id (never sent); the core edits them locally with no time limit
            // (meta.editable: not deleted, not forwarded). "For everyone" has no meaning for a note.
            canEdit = !deleted && !service && i.outgoing && (i.shared || i.local) && i.editable && i.msgContentType == "text",
            canDeleteForEveryone = moderate || (!deleted && !service && !i.local && i.outgoing && i.shared && i.deletable),
            forwarded = i.forwarded,
            reactions = if (deleted || service) emptyList() else i.reactions,
            expiresAtMs = i.expiresAtMs,
            sender = sender?.let { MessageSender(it.memberId, it.displayName, it.role) },
            event = event,
        )
    }

    /**
     * One chat-list row. [archived] is our local flag ([ArchiveStore]); everything else comes from the engine. A
     * service row as the newest item (an invitation row) becomes [ChatSummary.lastEvent], with no message text.
     */
    fun summaryOf(chat: CoreChat, archived: Boolean): ChatSummary {
        val c = chat.contact
        val last = chat.lastItem?.takeIf { it.isMessage }
        val time = last?.timeMs?.takeIf { it > 0 } ?: maxOf(c.activityTimeMs, chat.lastItem?.timeMs ?: 0L)
        return ChatSummary(
            chatId = c.contactId,
            displayName = c.displayName,
            lastMessageText = last?.takeUnless { it.deleted }?.text,
            lastMessageTimeMs = time,
            unreadCount = chat.unreadCount,
            verified = c.verified,
            ready = c.ready,
            lastMessageOutgoing = last?.outgoing == true,
            lastMessageStatus = last?.takeIf { it.outgoing }?.status,
            pinned = c.favorite,
            muted = c.muted,
            archived = archived,
            disappearingTtlSeconds = c.timedTtlSeconds,
            lastEvent = lastEventOf(chat.lastItem),
        )
    }

    /** The newest item's service row, when the newest item is one we show (never for a message). */
    private fun lastEventOf(item: CoreItem?): GroupEvent? = item?.takeIf { !it.isMessage }?.event

    /**
     * A group or channel row. Invited: no preview of anything before joining (the UI shows the invitation), and no
     * posting until [ChatSummary.invited] is accepted. Not ready while still connecting after accepting or joining.
     * A service row as the newest item ("Ann joined", "You were removed") is [ChatSummary.lastEvent].
     */
    fun groupSummary(chat: CoreGroupChat, archived: Boolean): ChatSummary {
        val g = chat.group
        val last = chat.lastItem?.takeIf { it.isMessage }
        val time = last?.timeMs?.takeIf { it > 0 } ?: maxOf(g.activityTimeMs, chat.lastItem?.timeMs ?: 0L)
        return ChatSummary(
            chatId = g.chatId,
            displayName = g.displayName,
            lastMessageText = if (g.invited) null else last?.takeUnless { it.deleted }?.text,
            lastMessageTimeMs = time,
            unreadCount = chat.unreadCount,
            verified = false,
            ready = !CoreJson.groupConnecting(g),
            lastMessageOutgoing = last?.outgoing == true,
            lastMessageStatus = last?.takeIf { it.outgoing }?.status,
            pinned = g.favorite,
            muted = g.muted,
            archived = archived,
            kind = if (g.channel) ChatKind.CHANNEL else ChatKind.GROUP,
            // A deleted group has nobody in it; the core's count still holds the others' rows (CoreGroup.deleted).
            memberCount = if (g.deleted) 0 else g.memberCount,
            myRole = g.myRole,
            canPost = !g.invited && CoreJson.canPost(g),
            lastMessageSender = last?.takeIf { !it.outgoing && !it.deleted }?.sender?.displayName,
            invited = g.invited,
            lastEvent = if (g.invited) null else lastEventOf(chat.lastItem),
            gone = g.gone,
        )
    }

    /**
     * The Saved Messages row ([SAVED_MESSAGES_CHAT_ID]): empty name (the UI shows its own localized one), always
     * ready, never verified, muted, archived or unread; its last message is ours and DELIVERED.
     */
    fun notesSummary(chat: CoreNoteChat): ChatSummary {
        val last = chat.lastItem?.takeIf { it.isMessage }
        val time = last?.timeMs?.takeIf { it > 0 } ?: maxOf(chat.folder.chatTsMs, chat.lastItem?.timeMs ?: 0L)
        return ChatSummary(
            chatId = SAVED_MESSAGES_CHAT_ID,
            displayName = "",
            lastMessageText = last?.takeUnless { it.deleted }?.text,
            lastMessageTimeMs = time,
            unreadCount = 0,
            verified = false,
            ready = true,
            lastMessageOutgoing = last != null,
            lastMessageStatus = last?.let { MessageStatus.DELIVERED },
            pinned = chat.folder.favorite,
        )
    }

    /**
     * A row after a new message, or a new service row we show, in its chat (no reload). A service row becomes
     * [ChatSummary.lastEvent] with no text, sender or ticks; a message clears it.
     */
    fun withNewItem(row: ChatSummary, item: CoreItem): ChatSummary {
        val event = item.takeIf { !it.isMessage }?.event
        return row.copy(
            lastMessageText = if (row.invited || event != null) null else item.takeUnless { it.deleted }?.text,
            lastMessageTimeMs = if (item.timeMs > 0) item.timeMs else row.lastMessageTimeMs,
            unreadCount = row.unreadCount + if (item.unread) 1 else 0,
            lastMessageOutgoing = event == null && item.outgoing,
            lastMessageStatus = if (event == null && item.outgoing) item.status else null,
            lastMessageSender = item.takeIf { event == null && it.group && !it.outgoing && !it.deleted }?.sender?.displayName,
            lastEvent = if (row.invited) null else event,
        )
    }

    /** The contract's order: pinned first, then newest activity first (ties by id, so the order is stable). */
    fun sortChats(rows: Collection<ChatSummary>): List<ChatSummary> = rows.sortedWith(
        compareByDescending<ChatSummary> { it.pinned }
            .thenByDescending { it.lastMessageTimeMs }
            .thenByDescending { it.chatId },
    )
}
