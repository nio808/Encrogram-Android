package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved Messages = SimpleX's local note folder (ChatInfo `local`, chat ref `*<noteFolderId>`), shown as chat id -1.
 * Shapes from Messages.hs (JCInfoLocal, CILocalSnd), Types.hs (NoteFolder) and Commands.hs (createNoteFolderContentItems).
 */
class SavedMessagesTest {

    private val user = """{"userId":1,"agentUserId":"1","userContactId":1,"localDisplayName":"alice",
        "profile":{"profileId":1,"displayName":"Alice","fullName":"","localAlias":""},"activeUser":true}"""

    private fun folder(id: Long = 3, favorite: Boolean = false, chatTs: String = "2026-11-07T14:00:00Z") =
        """{"type":"local","noteFolder":{"noteFolderId":$id,"userId":1,"createdAt":"2026-11-07T09:00:00Z",
           "updatedAt":"2026-11-07T09:00:00Z","chatTs":"$chatTs","favorite":$favorite,"unread":false}}"""

    /** A note as the core stores it: chatDir localSnd, no shared message id, status sndNew, editable. */
    private fun note(
        itemId: Long,
        text: String,
        ts: String = "2026-11-07T14:00:00Z",
        dir: String = "localSnd",
        forwarded: Boolean = false,
        deleted: Boolean = false,
    ) = Samples.item(
        itemId, dir, if (dir == "localRcv") "rcvMsgContent" else "sndMsgContent", text, "sndNew", ts = ts,
        editable = !forwarded && !deleted, deletable = !deleted, shared = false, forwarded = forwarded, deleted = deleted,
    )

    private fun aNote(item: String) = """{"chatInfo":${folder()},"chatItem":$item}"""

    private fun body(json: String) = (CoreJson.parseReply(json) as CoreReply.Result).body

    private fun obj(json: String) = JSONObject(json)

    private val apiChats = """{"result":{"type":"apiChats","user":$user,"chats":[
        {"chatInfo":{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}},
         "chatItems":[${Samples.item(41, "directSnd", "sndMsgContent", "hi", "sndRcvd", ts = "2026-11-07T12:00:00Z")}],
         "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
        {"chatInfo":${folder()},
         "chatItems":[${note(9, "older note", ts = "2026-11-07T13:00:00Z")},${note(11, "buy milk", ts = "2026-11-07T15:00:00Z")}],
         "chatStats":{"unreadCount":4,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":true}},
        {"chatInfo":{"type":"group","groupInfo":{"groupId":3}},"chatItems":[],
         "chatStats":{"unreadCount":9,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}
        ]}}"""

    // ---- chat info and chat ids ------------------------------------------------------------------------------

    @Test
    fun noteFolderIsReadFromLocalChatInfo() {
        val f = CoreJson.noteFolder(obj(folder(id = 7, favorite = true)))!!
        assertEquals(7L, f.noteFolderId)
        assertTrue(f.favorite)
        assertEquals(CoreJson.timeMs("2026-11-07T14:00:00Z"), f.chatTsMs)
        assertNull(CoreJson.noteFolder(obj("""{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}}""")))
        assertNull(CoreJson.noteFolder(obj("""{"type":"local","noteFolder":{"userId":1}}""")))
        assertNull(CoreJson.noteFolder(obj("""{"type":"local","noteFolder":{"noteFolderId":0}}""")))
        assertNull(CoreJson.noteFolder(null))
    }

    @Test
    fun chatIdsMapContactsToTheirIdAndNotesToMinusOne() {
        assertEquals(-1L, SAVED_MESSAGES_CHAT_ID)
        assertEquals(5L, CoreJson.chatIdOf(obj("""{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}}""")))
        assertEquals(SAVED_MESSAGES_CHAT_ID, CoreJson.chatIdOf(obj(folder(id = 3))))
        assertNull(CoreJson.chatIdOf(obj("""{"type":"group","groupInfo":{"groupId":3}}""")))
        assertNull(CoreJson.chatIdOf(obj("""{"type":"contactConnection","contactConnection":{"pccConnId":7}}""")))
    }

    @Test
    fun chatRefsNameContactsAndTheNoteFolder() {
        assertEquals("@5", ChatRef.contact(5).toString())
        assertEquals("*3", ChatRef.notes(3).toString())
        assertTrue(ChatRef.notes(3).isNotes)
        assertFalse(ChatRef.contact(3).isNotes)
        assertEquals(ChatRef.notes(3), ChatRef.notes(3))
        assertNotEquals(ChatRef.notes(3), ChatRef.contact(3))
    }

    // ---- chat list --------------------------------------------------------------------------------------------

    @Test
    fun chatListKeepsContactsAndAddsTheNoteFolderRow() {
        val b = body(apiChats)
        // Direct chats are parsed as before: the note folder and the group are not contacts.
        assertEquals(listOf(5L), CoreJson.parseChats(b).map { it.contact.contactId })
        val notes = CoreJson.parseNoteChat(b)!!
        assertEquals(3L, notes.folder.noteFolderId)
        assertEquals(11L, notes.lastItem!!.itemId)

        val row = MessageMapper.notesSummary(notes)
        assertEquals(SAVED_MESSAGES_CHAT_ID, row.chatId)
        assertEquals("", row.displayName)
        assertEquals("buy milk", row.lastMessageText)
        assertEquals(CoreJson.timeMs("2026-11-07T15:00:00Z"), row.lastMessageTimeMs)
        assertEquals("notes are never unread, whatever the stats say", 0, row.unreadCount)
        assertFalse(row.verified)
        assertTrue(row.ready)
        assertTrue(row.lastMessageOutgoing)
        assertEquals(MessageStatus.DELIVERED, row.lastMessageStatus)
        assertFalse(row.pinned)
        assertFalse(row.muted)
        assertFalse(row.archived)
        assertNull(row.disappearingTtlSeconds)
    }

    @Test
    fun emptyNoteFolderRowUsesTheFolderTime() {
        val json = """{"result":{"type":"apiChats","user":$user,"chats":[{"chatInfo":${folder(favorite = true)},"chatItems":[],
            "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}]}}"""
        val row = MessageMapper.notesSummary(CoreJson.parseNoteChat(body(json))!!)
        assertNull(row.lastMessageText)
        assertFalse(row.lastMessageOutgoing)
        assertNull(row.lastMessageStatus)
        assertEquals(CoreJson.timeMs("2026-11-07T14:00:00Z"), row.lastMessageTimeMs)
        assertTrue("pinned follows the folder's favorite flag", row.pinned)
    }

    @Test
    fun noNoteFolderMeansNoRow() {
        assertNull(CoreJson.parseNoteChat(body(Samples.API_CHATS)))
    }

    @Test
    fun notesRowSortsByActivityLikeOtherRows() {
        val b = body(apiChats)
        val rows = CoreJson.parseChats(b).map { MessageMapper.summaryOf(it, archived = false) } +
            MessageMapper.notesSummary(CoreJson.parseNoteChat(b)!!)
        // The newest note (15:00) is newer than Bob's last message (12:00).
        assertEquals(listOf(SAVED_MESSAGES_CHAT_ID, 5L), MessageMapper.sortChats(rows).map { it.chatId })
    }

    @Test
    fun aNewNoteUpdatesTheRowWithoutUnread() {
        val b = body(apiChats)
        val row = MessageMapper.notesSummary(CoreJson.parseNoteChat(b)!!)
        val item = CoreJson.parseAChatItem(obj(aNote(note(12, "call mum", ts = "2026-11-07T16:00:00Z"))))!!
        val after = MessageMapper.withNewItem(row, item)
        assertEquals("call mum", after.lastMessageText)
        assertEquals(0, after.unreadCount)
        assertEquals(MessageStatus.DELIVERED, after.lastMessageStatus)
    }

    // ---- items --------------------------------------------------------------------------------------------------

    @Test
    fun notesAreOutgoingDeliveredAndNeverUnread() {
        val item = CoreJson.parseAChatItem(obj(aNote(note(11, "buy milk"))))!!
        assertEquals(SAVED_MESSAGES_CHAT_ID, item.chatId)
        assertTrue(item.local)
        assertTrue(item.outgoing)
        assertTrue(item.isMessage)
        assertFalse(item.unread)
        assertEquals("the core tags notes sndNew; they were never sent, so no clock", MessageStatus.DELIVERED, item.status)

        val m = MessageMapper.toMessage(item)
        assertEquals(SAVED_MESSAGES_CHAT_ID, m.chatId)
        assertEquals("buy milk", m.text)
        assertTrue(m.outgoing)
        assertEquals(MessageStatus.DELIVERED, m.status)
        assertTrue("a note has no shared id but can be edited", m.canEdit)
        assertFalse("there is no 'everyone' for a note", m.canDeleteForEveryone)
        assertTrue(m.reactions.isEmpty())
    }

    @Test
    fun localRcvNotesAreStillOurs() {
        val item = CoreJson.parseAChatItem(obj(aNote(note(13, "kept", dir = "localRcv"))))!!
        assertTrue(item.outgoing)
        assertFalse(item.unread)
        assertEquals(MessageStatus.DELIVERED, item.status)
    }

    @Test
    fun forwardedNoteIsNotEditableAndDeletedNoteHasNoText() {
        val fwd = MessageMapper.toMessage(CoreJson.parseAChatItem(obj(aNote(note(14, "from Bob", forwarded = true))))!!)
        assertTrue(fwd.forwarded)
        assertFalse(fwd.canEdit)
        val gone = MessageMapper.toMessage(CoreJson.parseAChatItem(obj(aNote(note(15, "secret", deleted = true))))!!)
        assertTrue(gone.deleted)
        assertEquals("", gone.text)
        assertFalse(gone.canEdit)
    }

    @Test
    fun contactItemsAreUnchanged() {
        // A sent but not yet delivered message to a contact still has its own ticks and no edit without a shared id.
        val item = CoreJson.parseAChatItem(obj(Samples.aChatItem(5, Samples.item(20, "directSnd", "sndMsgContent", "x", "sndSent", editable = true, shared = false))))!!
        assertFalse(item.local)
        assertEquals(MessageStatus.SENT_TO_SERVER, item.status)
        assertFalse(MessageMapper.toMessage(item).canEdit)
    }

    @Test
    fun createNoteReplyRoutesToSavedMessages() {
        val reply = """{"result":{"type":"newChatItems","user":$user,"chatItems":[${aNote(note(16, "new note"))}]}}"""
        val items = CoreJson.parseChatItems(body(reply))
        assertEquals(1, items.size)
        assertEquals(SAVED_MESSAGES_CHAT_ID, items[0].chatId)
        assertEquals("new note", items[0].text)
    }

    @Test
    fun forwardIntoNotesRoutesTheCopiesToSavedMessages() {
        // /_forward *3 @5 41 ttl=default -> newChatItems in the note folder, marked forwarded.
        val reply = """{"result":{"type":"newChatItems","user":$user,"chatItems":[${aNote(note(17, "hi", forwarded = true))}]}}"""
        val copy = CoreJson.parseChatItems(body(reply)).single()
        assertEquals(SAVED_MESSAGES_CHAT_ID, copy.chatId)
        assertTrue(copy.outgoing)
        assertTrue(MessageMapper.toMessage(copy).forwarded)
    }

    @Test
    fun editAndDeleteRepliesRouteToSavedMessages() {
        val updated = """{"result":{"type":"chatItemUpdated","user":$user,"chatItem":${aNote(note(11, "buy oat milk"))}}}"""
        val item = CoreJson.parseAChatItem(body(updated).optJSONObject("chatItem"))!!
        assertEquals(SAVED_MESSAGES_CHAT_ID, item.chatId)
        assertEquals("buy oat milk", item.text)

        // deleteLocalCIs: every deletion has toChatItem = null (the row is gone).
        val deleted = """{"result":{"type":"chatItemsDeleted","user":$user,"chatItemDeletions":[
            {"deletedChatItem":${aNote(note(9, "older note"))},"toChatItem":null},
            {"deletedChatItem":${aNote(note(11, "buy milk"))},"toChatItem":null}],"byUser":true,"timed":false}}"""
        val deletions = CoreJson.parseDeletions(body(deleted))
        assertEquals(listOf(9L, 11L), deletions.map { it.itemId })
        assertTrue(deletions.all { it.chatId == SAVED_MESSAGES_CHAT_ID && it.replacement == null })
    }

    @Test
    fun noteFolderPageIsParsedOldestFirst() {
        val apiChat = """{"result":{"type":"apiChat","user":$user,"chat":{"chatInfo":${folder()},
            "chatItems":[${note(11, "b")},${note(9, "a")}],
            "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
            "navInfo":{"afterUnread":0,"afterTotal":0}}}"""
        val items = CoreJson.parseChatItemsOfChat(body(apiChat))
        assertEquals(listOf(9L, 11L), items.map { it.itemId })
        assertTrue(items.all { it.chatId == SAVED_MESSAGES_CHAT_ID && it.outgoing && it.status == MessageStatus.DELIVERED })
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    @Test
    fun noteFolderCommands() {
        val notes = ChatRef.notes(3)
        assertEquals("/_get chat *3 count=20", Commands.getChat(notes, 20))
        assertEquals("/_get chat *3 around=9 count=1", Commands.getChat(notes, 1, aroundId = 9))
        assertEquals("/_get chat *3 before=9 count=50 search=milk", Commands.getChat(notes, 50, beforeId = 9, search = "milk"))
        assertEquals("/_delete item *3 9,11 internal", Commands.deleteItems(notes, listOf(9, 11), Commands.DeleteMode.INTERNAL))
        assertEquals("/_clear chat *3", Commands.clearChat(notes))
        assertTrue(Commands.updateItem(notes, 11, "x").startsWith("/_update item *3 11 live=off json {"))
        // Both directions of forwarding: notes -> contact and contact -> notes.
        assertEquals("/_forward @5 *3 9,11 ttl=default", Commands.forward(ChatRef.contact(5), notes, listOf(9, 11)))
        assertEquals("/_forward *3 @5 41 ttl=default", Commands.forward(notes, ChatRef.contact(5), listOf(41)))
    }

    @Test
    fun contactCommandsAreUnchanged() {
        assertEquals("/_get chat @7 count=50", Commands.getChat(7, 50))
        assertEquals("/_forward @5 @6 1 ttl=default", Commands.forward(5, 6, listOf(1)))
        assertEquals("/_clear chat @5", Commands.clearChat(5))
        assertEquals("/_delete item @5 1 broadcast", Commands.deleteItems(5, listOf(1), Commands.DeleteMode.BROADCAST))
        assertEquals(Commands.updateItem(5, 1, "x"), Commands.updateItem(ChatRef.contact(5), 1, "x"))
    }

    @Test
    fun createNoteIsAsciiTextOnlyAndNeverAReply() {
        val text = "Note 👋 مرحبا \"q\" \\ \nline2"
        val cmd = Commands.createNote(3, text)
        assertTrue(cmd.startsWith("/_create *3 json ["))
        assertTrue("command must be pure ASCII for the JNI glue", cmd.all { it.code in 0x20..0x7E })
        val composed = JSONArray(cmd.removePrefix("/_create *3 json ")).getJSONObject(0)
        assertEquals("text", composed.getJSONObject("msgContent").getString("type"))
        assertEquals(text, composed.getJSONObject("msgContent").getString("text"))
        assertEquals(0, composed.getJSONObject("mentions").length())
        assertFalse("the core refuses quotes in notes", composed.has("quotedItemId"))
        assertFalse(composed.has("fileSource"))
    }
}
