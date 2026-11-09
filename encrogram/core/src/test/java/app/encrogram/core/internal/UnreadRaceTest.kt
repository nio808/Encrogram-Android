package app.encrogram.core.internal

import app.encrogram.core.api.ChatSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A4: a list reload takes the core's unread count while `newChatItems` events for the same items still wait in the
 * engine queue behind it. Applying those events must not count the items a second time.
 */
class UnreadRaceTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun rcv(id: Long, text: String = "m$id") =
        Samples.item(id, "directRcv", "rcvMsgContent", text, "rcvNew", ts = "2026-11-07T14:00:${10 + id % 50}Z")

    /** A `newChatItems` event for Bob (contact 5), parsed the way the engine parses it. */
    private fun event(vararg ids: Long): List<CoreItem> {
        val entries = ids.joinToString(",") {
            """{"chatInfo":{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}},"chatItem":${rcv(it)}}"""
        }
        return CoreJson.parseChatItems(JSONObject("""{"type":"newChatItems","chatItems":[$entries]}"""))
    }

    /** What `/_get chats` returned for Bob while items 41 and 42 (both unread) were already stored. */
    private fun loadedBob(): Pair<CoreChat, ChatSummary> {
        val listed = CoreJson.parseChats((CoreJson.parseReply(Samples.API_CHATS_V2) as CoreReply.Result).body)
            .first { it.contact.contactId == 5L }
        val last = CoreJson.parseChatItem(5, JSONObject(rcv(42)))!!
        val chat = listed.copy(lastItem = last, unreadCount = 2)
        return chat to MessageMapper.summaryOf(chat, archived = false)
    }

    /** The engine's applyNewItems row update, with the marks. */
    private fun apply(row: ChatSummary, counted: CountedItems, items: List<CoreItem>): ChatSummary {
        var r = row
        for (i in counted.fresh(items)) if (i.chatId == r.chatId) r = MessageMapper.withNewItem(r, i)
        return r
    }

    @Test
    fun queuedEventsAfterAReloadAreNotCountedTwice() {
        val (chat, row) = loadedBob()
        assertEquals(2, row.unreadCount)

        // The race as it was: both queued items added once more on top of the core's count.
        var naive = row
        for (i in event(41, 42)) naive = MessageMapper.withNewItem(naive, i)
        assertEquals(4, naive.unreadCount)

        val counted = CountedItems()
        counted.loaded(5, chat.lastItem?.itemId)
        val after = apply(row, counted, event(41, 42))
        assertEquals(2, after.unreadCount)
        assertEquals("m42", after.lastMessageText)

        // A message that arrives after the reload counts.
        val next = apply(after, counted, event(43))
        assertEquals(3, next.unreadCount)
        assertEquals("m43", next.lastMessageText)
        // The same item twice (a reply and an event) counts once.
        assertEquals(3, apply(next, counted, event(43)).unreadCount)
    }

    @Test
    fun aBatchInAnyOrderCountsEachNewItemOnce() {
        val counted = CountedItems()
        counted.loaded(5, 42)
        val (_, row) = loadedBob()
        assertEquals(listOf(45L, 44L), counted.fresh(event(45, 42, 44)).map { it.itemId })
        assertEquals(emptyList<Long>(), counted.fresh(event(44, 45)).map { it.itemId })
        assertEquals(3, apply(row, CountedItems().apply { loaded(5, 42) }, event(41, 43)).unreadCount)
    }

    @Test
    fun anEmptyChatAndAnUnknownChatCountEverything() {
        val counted = CountedItems()
        counted.loaded(5, null)
        assertEquals(listOf(1L, 2L), counted.fresh(event(1, 2)).map { it.itemId })
        // No row loaded at all: the items pass, so the engine sees the unknown chat and reloads.
        assertEquals(listOf(7L), CountedItems().fresh(event(7)).map { it.itemId })
    }

    @Test
    fun deletedIdsNeverComeBackSoTheMarkStays() {
        // chat_items.chat_item_id is AUTOINCREMENT (chat_schema.sql:465): a deleted id is never given out again, so a
        // delete leaves the mark alone (review of 4bce1db8, #5) and an item the load counted is never counted again.
        val counted = CountedItems()
        counted.loaded(5, 42)
        assertEquals(emptyList<Long>(), counted.fresh(event(42)).map { it.itemId })
        assertEquals(listOf(43L), counted.fresh(event(43)).map { it.itemId })
        // A new list load replaces the marks.
        counted.clear()
        assertEquals(listOf(42L), counted.fresh(event(42)).map { it.itemId })
    }

    /** Group 3's received item [id] at time [ts], from member Carol. */
    private fun groupItem(id: Long, ts: String, status: String = "rcvNew"): String {
        val item = JSONObject(Samples.item(id, "groupRcv", "rcvMsgContent", "g$id", status, ts = ts))
            .put("chatDir", JSONObject("""{"type":"groupRcv","groupMember":{"groupMemberId":6,"memberId":"bWVt",
                "memberRole":"member","memberStatus":"connected","memberProfile":{"displayName":"Carol","fullName":"","localAlias":""}}}"""))
        return """{"chatInfo":{"type":"group","groupInfo":{"groupId":3}},"chatItem":$item}"""
    }

    @Test
    fun aGroupsCountComesFromTheCoreNeverPlusOne() {
        // The load: the group's row item is its newest by item_ts (60, 10:05), but 61 was stored after it with an older
        // relay time (10:01), so the core's count of 2 already holds 61 while its id is above the mark (review #4).
        val listed = CoreJson.parseGroupChats(JSONObject("""{"chats":[{"chatInfo":{"type":"group","groupInfo":{"groupId":3,
            "groupProfile":{"displayName":"Team","fullName":""},"membership":{"groupMemberId":1,"memberRole":"member",
            "memberStatus":"connected"}}},"chatItems":[${JSONObject(groupItem(60, "2026-11-09T10:05:00Z")).getJSONObject("chatItem")}],
            "chatStats":{"unreadCount":2}}]}""")).single()
        val gid = CoreJson.groupChatId(3)
        val row = MessageMapper.groupSummary(listed, archived = false)
        assertEquals(2, row.unreadCount)
        val counted = CountedItems()
        counted.loaded(gid, listed.lastItem?.itemId)

        // 61's event, queued behind the load: fresh by id, so `+1` would make it 3.
        val events = CoreJson.parseChatItems(JSONObject("""{"chatItems":[${groupItem(61, "2026-11-09T10:01:00Z")}]}"""))
        val fresh = counted.fresh(events)
        assertEquals(listOf(61L), fresh.map { it.itemId })
        assertEquals(3, MessageMapper.withNewItem(row, fresh.single()).unreadCount)
        // So the engine asks the core instead ([Commands.groupUnread]) and sets what it says.
        assertEquals(setOf(gid), counted.recountGroups(fresh))
        assertEquals("/_get chat #3 initial=1", Commands.groupUnread(3))
        val reply = CoreJson.parseReply("""{"result":{"type":"apiChat","user":{"userId":1},"chat":{
            "chatInfo":{"type":"group","groupInfo":{"groupId":3}},"chatItems":[],
            "chatStats":{"unreadCount":2,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":60,"unreadChat":false}},
            "navInfo":{"afterUnread":0,"afterTotal":0}}}""") as CoreReply.Result
        assertEquals(2, CoreJson.unreadCountOf(reply.body))

        // Contacts keep `+1`; read and own items in a group need no recount.
        assertEquals(emptySet<Long>(), counted.recountGroups(event(50)))
        val read = CoreJson.parseChatItems(JSONObject("""{"chatItems":[${groupItem(62, "2026-11-09T10:06:00Z", status = "rcvRead")}]}"""))
        assertEquals(emptySet<Long>(), counted.recountGroups(read))
        // No stats in the reply: the engine keeps the row's `+1`.
        assertEquals(null, CoreJson.unreadCountOf(JSONObject("""{"chat":{"chatItems":[]}}""")))
    }
}
