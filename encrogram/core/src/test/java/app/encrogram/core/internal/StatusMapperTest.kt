package app.encrogram.core.internal

import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.internal.media.CoreFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMapperTest {

    @Test
    fun outgoing() {
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndNew", true))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndSent", true))
        assertEquals(MessageStatus.DELIVERED, StatusMapper.map("sndRcvd", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("sndError", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("sndErrorAuth", true))
        // A warning is a temporary problem; the engine keeps retrying, so the clock stays.
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndWarning", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("invalid", true))
    }

    @Test
    fun incoming() {
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("rcvNew", false))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("rcvRead", false))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("invalid", false))
    }

    @Test
    fun unknownStatesNeverClaimProgress() {
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndSomethingNew", true))
        assertEquals(MessageStatus.SENDING, StatusMapper.map(null, true))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map(null, false))
    }

    // ---- NoRecipientCheck: our own group messages sent with nobody to send them to --------------------------------

    private val groupChat = CoreJson.groupChatId(3)
    private val sentAtMs = 1_000_000L

    /** A fake core: counts the item info asks per item and answers from [replies] (Invalid when there is none). */
    private class FakeCore(var nowMs: Long) {
        val asked = ArrayList<Long>()
        val replies = HashMap<Long, CoreReply>()
        val check = NoRecipientCheck({ asked.add(it.itemId); replies[it.itemId] ?: CoreReply.Invalid }, { nowMs })
    }

    private fun ownGroupItem(itemId: Long, statusTag: String = "sndNew", chatId: Long = groupChat, outgoing: Boolean = true, file: CoreFile? = null) =
        CoreItem(
            chatId = chatId, itemId = itemId, outgoing = outgoing, isMessage = true, text = "hi", timeMs = sentAtMs,
            statusTag = statusTag, status = StatusMapper.map(statusTag, outgoing, group = true), unread = false, file = file,
        )

    private fun info(itemId: Long, statuses: String? = null, status: String = "sndNew") =
        CoreJson.parseReply(Samples.chatItemInfo(itemId, statuses, status))

    @Test
    fun itemInfoReadsWhetherAnyoneWasSentTheMessage() {
        val nobody = CoreJson.parseItemInfo((info(7) as CoreReply.Result).body)
        assertNotNull(nobody)
        assertEquals(groupChat to 7L, nobody!!.first.chatId to nobody.first.itemId)
        assertEquals(true, nobody.second)
        assertEquals(true, CoreJson.parseItemInfo((info(7, "[]") as CoreReply.Result).body)!!.second)
        assertEquals(true, CoreJson.parseItemInfo((info(7, "null") as CoreReply.Result).body)!!.second)
        val pending = info(7, "[${Samples.memberStatus(4, "new")}]") as CoreReply.Result
        assertEquals(false, CoreJson.parseItemInfo(pending.body)!!.second)
        // Any shape we don't know counts as recipients: the clock stays.
        assertEquals(false, CoreJson.parseItemInfo((info(7, "{}") as CoreReply.Result).body)!!.second)
    }

    @Test
    fun messageWithNoRecipientsShowsOneTick() {
        val core = FakeCore(sentAtMs + 11_000)
        core.replies[7] = info(7)
        val item = ownGroupItem(7)
        assertEquals(MessageStatus.SENDING, item.status)
        val due = core.check.take(listOf(item))
        assertEquals(listOf(item), due.now)
        assertTrue(due.later.isEmpty())
        val changed = core.check.check(due.now)
        assertEquals(listOf(7L), core.asked)
        assertEquals(listOf(groupChat to 7L), changed.map { it.chatId to it.itemId })
        assertEquals(MessageStatus.SENT_TO_SERVER, changed.single().status)
        // Every later mapping of the item (pages, rows, edits) shows the tick, with no further ask.
        assertEquals(MessageStatus.SENT_TO_SERVER, core.check.apply(listOf(item)).single().status)
        assertEquals(listOf(7L), core.asked)
    }

    @Test
    fun pendingRecipientsKeepTheClock() {
        val core = FakeCore(sentAtMs + 60_000)
        // Our phone offline: the member rows are `new`. An invited member not connected yet: `inactive`.
        core.replies[7] = info(7, "[${Samples.memberStatus(4, "new")}]")
        core.replies[8] = info(8, "[${Samples.memberStatus(4, "inactive")},${Samples.memberStatus(5, "new")}]")
        val items = listOf(ownGroupItem(7), ownGroupItem(8))
        assertTrue(core.check.check(core.check.take(items).now).isEmpty())
        assertEquals(listOf(7L, 8L), core.asked)
        assertEquals(listOf(MessageStatus.SENDING, MessageStatus.SENDING), core.check.apply(items).map { it.status })
    }

    @Test
    fun eachItemIsAskedAboutOncePerRun() {
        val core = FakeCore(sentAtMs + 11_000)
        core.replies[7] = info(7)
        core.replies[8] = info(8, "[${Samples.memberStatus(4, "new")}]")
        val items = listOf(ownGroupItem(7), ownGroupItem(8), ownGroupItem(9))
        val first = core.check.take(items)
        // A reload while the asks are queued hands out nothing again.
        assertTrue(core.check.take(items).now.isEmpty())
        core.check.check(first.now)
        core.check.check(first.now)
        assertTrue(core.check.take(items).now.isEmpty())
        assertEquals(listOf(7L, 8L, 9L), core.asked)
        // A new run asks again.
        core.check.clear()
        core.check.check(core.check.take(items).now)
        assertEquals(listOf(7L, 8L, 9L, 7L, 8L, 9L), core.asked)
    }

    @Test
    fun youngItemsAreNotAskedAbout() {
        val core = FakeCore(sentAtMs + 2_000)
        val young = ownGroupItem(7)
        val due = core.check.take(listOf(young))
        assertTrue(due.now.isEmpty())
        assertEquals(listOf(young), due.later)
        assertEquals(8_000L, due.laterDelayMs)
        // A normal send: on our server within a second, so the delayed check finds nothing to ask.
        core.check.settled(listOf(ownGroupItem(7, statusTag = "sndSent")))
        core.nowMs = sentAtMs + 10_000
        assertTrue(core.check.check(due.later).isEmpty())
        assertTrue(core.asked.isEmpty())
        // Still sndNew once old enough (no status ever came): asked then, once.
        val other = ownGroupItem(8)
        core.nowMs = sentAtMs + 3_000
        val later = core.check.take(listOf(other)).later
        core.replies[8] = info(8)
        core.nowMs = sentAtMs + 10_000
        assertEquals(MessageStatus.SENT_TO_SERVER, core.check.check(later).single().status)
        assertEquals(listOf(8L), core.asked)
        // A timestamp ahead of the phone's clock waits no longer than the age itself.
        core.nowMs = sentAtMs - 60_000
        assertEquals(NoRecipientCheck.AGE_MS, core.check.take(listOf(ownGroupItem(9))).laterDelayMs)
    }

    @Test
    fun failedAskKeepsTheClock() {
        val core = FakeCore(sentAtMs + 11_000)
        core.replies[7] = CoreReply.Error(CoreError("errorStore", "chatItemNotFound", null))
        val items = listOf(ownGroupItem(7), ownGroupItem(8))   // 8: an invalid reply
        assertTrue(core.check.check(core.check.take(items).now).isEmpty())
        assertEquals(listOf(MessageStatus.SENDING, MessageStatus.SENDING), core.check.apply(items).map { it.status })
        // Not repeated in this run.
        core.check.check(core.check.take(items).now)
        assertEquals(listOf(7L, 8L), core.asked)
    }

    @Test
    fun onlyOurOwnStuckGroupMessagesAreAskedAbout() {
        val core = FakeCore(sentAtMs + 60_000)
        val others = listOf(
            ownGroupItem(1, statusTag = "sndSent"),
            ownGroupItem(2, statusTag = "rcvNew", outgoing = false),
            ownGroupItem(3, chatId = 5),   // a 1:1 chat
            ownGroupItem(4).copy(local = true),
            ownGroupItem(5).copy(deleted = true),
            ownGroupItem(6).copy(isMessage = false),
        )
        assertTrue(core.check.take(others).now.isEmpty())
        // An answer about another item, or one the core has moved on, is not trusted.
        core.replies[7] = info(70)
        core.replies[8] = info(8, status = "sndSent")
        val items = listOf(ownGroupItem(7), ownGroupItem(8))
        assertTrue(core.check.check(core.check.take(items).now).isEmpty())
        assertEquals(listOf(MessageStatus.SENDING, MessageStatus.SENDING), core.check.apply(items).map { it.status })
    }

    @Test
    fun aFileStillUploadingKeepsTheClock() {
        val core = FakeCore(sentAtMs + 11_000)
        core.replies[7] = info(7)
        val uploading = CoreFile(1, MediaKind.IMAGE, "a.jpg", 10, "sndTransfer", "xftp")
        val item = ownGroupItem(7, file = uploading)
        core.check.check(core.check.take(listOf(item)).now)
        assertEquals(listOf(7L), core.asked)
        assertEquals(MessageStatus.SENDING, core.check.apply(listOf(item)).single().status)
        val done = item.copy(file = uploading.copy(statusTag = "sndComplete"))
        assertEquals(MessageStatus.SENT_TO_SERVER, core.check.apply(listOf(done)).single().status)
    }
}
