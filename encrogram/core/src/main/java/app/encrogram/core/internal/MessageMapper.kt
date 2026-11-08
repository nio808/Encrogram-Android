package app.encrogram.core.internal

import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.QuotePreview
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.api.SecuredMessage

/**
 * Turns parsed engine items and chats into the contract's [SecuredMessage] and [ChatSummary]. Pure logic.
 * A deleted message never carries text, a quote or reactions, whatever the engine JSON still holds.
 */
internal object MessageMapper {

    fun toMessage(i: CoreItem): SecuredMessage {
        val deleted = i.deleted
        return SecuredMessage(
            chatId = i.chatId,
            messageId = i.itemId,
            outgoing = i.outgoing,
            text = if (deleted) "" else i.text,
            timeMs = i.timeMs,
            status = i.status,
            replyTo = if (deleted) null else i.quote?.let { QuotePreview(it.itemId, InputRules.preview(it.text), it.outgoing) },
            edited = i.edited,
            deleted = deleted,
            // Notes have no shared message id (never sent); the core edits them locally with no time limit
            // (meta.editable: not deleted, not forwarded). "For everyone" has no meaning for a note.
            canEdit = !deleted && i.outgoing && (i.shared || i.local) && i.editable && i.msgContentType == "text",
            canDeleteForEveryone = !deleted && !i.local && i.outgoing && i.shared && i.deletable,
            forwarded = i.forwarded,
            reactions = if (deleted) emptyList() else i.reactions,
            expiresAtMs = i.expiresAtMs,
        )
    }

    /** One chat-list row. [archived] is our local flag ([ArchiveStore]); everything else comes from the engine. */
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

    /** A row after a new message in its chat (no reload). */
    fun withNewItem(row: ChatSummary, item: CoreItem): ChatSummary = row.copy(
        lastMessageText = item.takeUnless { it.deleted }?.text,
        lastMessageTimeMs = if (item.timeMs > 0) item.timeMs else row.lastMessageTimeMs,
        unreadCount = row.unreadCount + if (item.unread) 1 else 0,
        lastMessageOutgoing = item.outgoing,
        lastMessageStatus = if (item.outgoing) item.status else null,
    )

    /** The contract's order: pinned first, then newest activity first (ties by id, so the order is stable). */
    fun sortChats(rows: Collection<ChatSummary>): List<ChatSummary> = rows.sortedWith(
        compareByDescending<ChatSummary> { it.pinned }
            .thenByDescending { it.lastMessageTimeMs }
            .thenByDescending { it.chatId },
    )
}
