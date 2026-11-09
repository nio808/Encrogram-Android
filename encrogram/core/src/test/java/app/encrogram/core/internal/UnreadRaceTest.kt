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
    fun aDeletedTopItemsIdCountsAgainWhenReused() {
        // SQLite gives a deleted top row's id to the next item (chat_items has no AUTOINCREMENT).
        val counted = CountedItems()
        counted.loaded(5, 42)
        counted.deleted(5, 42)
        assertEquals(listOf(42L), counted.fresh(event(42)).map { it.itemId })
        // Deleting an item above the mark changes nothing; another chat's mark is untouched.
        counted.deleted(5, 99)
        counted.deleted(6, 1)
        assertEquals(emptyList<Long>(), counted.fresh(event(42)).map { it.itemId })
        // A new list load replaces the marks.
        counted.clear()
        assertEquals(listOf(42L), counted.fresh(event(42)).map { it.itemId })
    }
}
