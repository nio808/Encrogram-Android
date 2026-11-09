package app.encrogram.core.internal

import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Round 2, task 4 (A8 phase 1): an edit or a "marked deleted" of a row's last message patches that row in place. The
 * patched row must equal what a full reload would build from the same item; anything a patch can't work out alone
 * (a service row, a new time and so a new place in the list) is left to the reload.
 */
class RowPatchTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun contactRow(item: CoreItem, unread: Int = 2): ChatSummary {
        val bob = CoreJson.parseContact(JSONObject(Samples.contact(5, "Bob", "ready", favorite = true)))!!
        return MessageMapper.summaryOf(CoreChat(bob, item, unread), archived = true)
    }

    private fun direct(text: String, dir: String = "directRcv", status: String = "rcvNew", deleted: Boolean = false, ts: String = "2026-11-09T09:00:00Z") =
        CoreJson.parseChatItem(5, JSONObject(Samples.item(41, dir, if (dir.endsWith("Snd")) "sndMsgContent" else "rcvMsgContent", text, status, ts = ts, deleted = deleted, edited = true)))!!

    @Test
    fun anEditedLastMessageInAContactRow() {
        val row = contactRow(direct("before"))
        val edited = direct("after")
        val patched = MessageMapper.withLastItemChanged(row, edited)!!
        assertEquals("after", patched.lastMessageText)
        // Exactly what a reload builds: unread count, pin, archive, name all kept.
        assertEquals(contactRow(edited), patched)
        assertEquals(2, patched.unreadCount)

        val ours = direct("mine", dir = "directSnd", status = "sndRcvd")
        assertEquals(contactRow(ours), MessageMapper.withLastItemChanged(contactRow(direct("x", dir = "directSnd", status = "sndSent")), ours))
    }

    @Test
    fun aLastMessageMarkedDeleted() {
        val row = contactRow(direct("secret words"))
        val marked = direct("secret words", deleted = true)
        val patched = MessageMapper.withLastItemChanged(row, marked)!!
        assertNull(patched.lastMessageText)
        assertEquals(contactRow(marked), patched)
    }

    @Test
    fun whatAPatchDoesNotCover() {
        val row = contactRow(direct("before"))
        // A different time would move the row: reload.
        assertNull(MessageMapper.withLastItemChanged(row, direct("after", ts = "2026-11-09T09:30:00Z")))
        // Another chat's item.
        val other = CoreJson.parseChatItem(6, JSONObject(Samples.item(41, "directRcv", "rcvMsgContent", "x", "rcvNew", ts = "2026-11-09T09:00:00Z")))!!
        assertNull(MessageMapper.withLastItemChanged(row, other))
        // A service row (a moderated message becomes "deleted by admin").
        val moderated = JSONObject(Samples.item(41, "groupRcv", "rcvModerated", "", "rcvRead", ts = "2026-11-09T09:00:00Z"))
        val service = CoreJson.parseChatItem(CoreJson.groupChatId(3), moderated)!!
        assertNull(MessageMapper.withLastItemChanged(row.copy(chatId = CoreJson.groupChatId(3)), service))
    }

    @Test
    fun groupRowsKeepTheirSenderAndInvitedRowsShowNothing() {
        val member = """{"groupMemberId":6,"memberId":"bWVt","memberRole":"member","memberStatus":"connected",
            "memberProfile":{"displayName":"Carol","fullName":"","localAlias":""}}"""
        fun item(text: String) = CoreJson.parseChatItem(
            CoreJson.groupChatId(3),
            JSONObject(Samples.item(80, "groupRcv", "rcvMsgContent", text, "rcvNew", ts = "2026-11-09T09:00:00Z", edited = true))
                .put("chatDir", JSONObject("""{"type":"groupRcv","groupMember":$member}""")),
        )!!
        val group = CoreGroup(3, "Team", app.encrogram.core.api.MemberRole.MEMBER, "connected", 4, activityTimeMs = 1L)
        val row = MessageMapper.groupSummary(CoreGroupChat(group, item("before"), 5), archived = false)
        val patched = MessageMapper.withLastItemChanged(row, item("after"))!!
        assertEquals(MessageMapper.groupSummary(CoreGroupChat(group, item("after"), 5), archived = false), patched)
        assertEquals("Carol", patched.lastMessageSender)

        val invited = group.copy(myStatus = "invited")
        val invitedRow = MessageMapper.groupSummary(CoreGroupChat(invited, item("before"), 0), archived = false)
        assertEquals(MessageMapper.groupSummary(CoreGroupChat(invited, item("after"), 0), archived = false), MessageMapper.withLastItemChanged(invitedRow, item("after")))
    }

    @Test
    fun savedMessages() {
        fun note(text: String) = CoreJson.parseChatItem(
            SAVED_MESSAGES_CHAT_ID, JSONObject(Samples.item(90, "localSnd", "sndMsgContent", text, "sndNew", ts = "2026-11-09T09:00:00Z", edited = true)),
        )!!
        val folder = CoreNoteFolder(2, favorite = false, chatTsMs = 1L)
        val row = MessageMapper.notesSummary(CoreNoteChat(folder, note("before")))
        assertEquals(MessageMapper.notesSummary(CoreNoteChat(folder, note("after"))), MessageMapper.withLastItemChanged(row, note("after")))
    }
}
