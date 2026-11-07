package app.encrogram.core.internal

import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.MessageStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun chats() = CoreJson.parseChats((CoreJson.parseReply(Samples.API_CHATS_V2) as CoreReply.Result).body)

    @Test
    fun rowFieldsFromTheEngine() {
        val rows = chats().associate { it.contact.contactId to MessageMapper.summaryOf(it, archived = false) }

        val bob = rows.getValue(5)
        assertTrue(bob.lastMessageOutgoing)
        assertEquals(MessageStatus.DELIVERED, bob.lastMessageStatus)
        assertEquals("hi", bob.lastMessageText)
        assertFalse(bob.pinned)
        assertFalse(bob.muted)
        assertNull(bob.disappearingTtlSeconds)

        val ann = rows.getValue(7)
        assertTrue(ann.pinned)
        assertTrue(ann.muted)
        assertEquals(86400, ann.disappearingTtlSeconds)
        assertFalse(ann.lastMessageOutgoing)
        assertNull("no ticks on a received message", ann.lastMessageStatus)

        // Timer set on our side but refused by the contact: not active, so no timer is shown.
        val cat = rows.getValue(8)
        assertNull(cat.disappearingTtlSeconds)
        // The last message was deleted: its text never reaches the list.
        assertNull(cat.lastMessageText)
        assertEquals(1, cat.unreadCount)
    }

    @Test
    fun timedPreferenceFlags() {
        val byId = chats().associate { it.contact.contactId to it.contact }
        assertTrue(byId.getValue(7).timedEnabled)
        assertFalse(byId.getValue(8).timedEnabled)
        assertFalse(byId.getValue(5).timedEnabled)
    }

    @Test
    fun pinnedFirstThenNewest() {
        val rows = chats().map { MessageMapper.summaryOf(it, archived = false) }
        // Ann is pinned with the oldest message; then Cat (13:00) before Bob (12:00).
        assertEquals(listOf(7L, 8L, 5L), MessageMapper.sortChats(rows).map { it.chatId })

        fun row(id: Long, time: Long, pinned: Boolean = false) =
            ChatSummary(id, "n$id", null, time, 0, verified = false, ready = true, pinned = pinned)
        val sorted = MessageMapper.sortChats(listOf(row(1, 100), row(2, 300), row(3, 50, pinned = true), row(4, 200, pinned = true), row(5, 300)))
        assertEquals(listOf(4L, 3L, 5L, 2L, 1L), sorted.map { it.chatId })
    }

    @Test
    fun newItemUpdatesTheRow() {
        val bob = MessageMapper.summaryOf(chats().first { it.contact.contactId == 5L }, archived = false)
        val incoming = CoreJson.parseChatItem(5, JSONObject(Samples.item(70, "directRcv", "rcvMsgContent", "yo", "rcvNew", ts = "2026-11-07T14:00:00Z")))!!
        val r = MessageMapper.withNewItem(bob, incoming)
        assertEquals("yo", r.lastMessageText)
        assertFalse(r.lastMessageOutgoing)
        assertNull(r.lastMessageStatus)
        assertEquals(bob.unreadCount + 1, r.unreadCount)
        assertEquals(incoming.timeMs, r.lastMessageTimeMs)
    }

    @Test
    fun archiveFlagIsLocalAndPersistent() {
        val file = File(tmp.root, "encrogram-secured/archived_chats")
        val store = ArchiveStore(file)
        assertFalse(store.isArchived(5))
        assertTrue(store.set(5, true))
        assertTrue(store.set(7, true))
        assertTrue(store.isArchived(5))
        // Survives a restart (a new store over the same file).
        val again = ArchiveStore(file)
        assertEquals(setOf(5L, 7L), again.all())
        assertTrue(again.set(5, false))
        assertEquals(setOf(7L), ArchiveStore(file).all())
        // Setting the current state again is a no-op.
        assertTrue(again.set(7, true))
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun archiveDropsChatsThatAreGone() {
        val store = ArchiveStore(File(tmp.root, "archived_chats"))
        store.set(5, true)
        store.set(9, true)
        assertTrue(store.retainOnly(setOf(5L, 7L)))
        assertEquals(setOf(5L), store.all())
    }

    @Test
    fun archiveFlagReachesTheRow() {
        val chat = chats().first { it.contact.contactId == 5L }
        assertTrue(MessageMapper.summaryOf(chat, archived = true).archived)
        assertFalse(MessageMapper.summaryOf(chat, archived = false).archived)
    }

    @Test
    fun badLinesInTheArchiveFileAreSkipped() {
        val file = File(tmp.root, "archived_chats").apply { writeText("5\nnot-a-number\n\n9") }
        assertEquals(setOf(5L, 9L), ArchiveStore(file).all())
    }

    @Test
    fun settingsUpdateKeepsOtherFields() {
        val contact = JSONObject(Samples.contact(5, "Bob", "ready", favorite = false, ntfs = "all"))
        val pinned = CoreJson.chatSettingsUpdate(contact) { it.put("favorite", true) }
        assertEquals(true, pinned.getBoolean("favorite"))
        assertEquals("all", pinned.getString("enableNtfs"))
        assertEquals(true, pinned.getBoolean("sendRcpts"))
        val muted = CoreJson.chatSettingsUpdate(contact) { it.put("enableNtfs", "none") }
        assertEquals(false, muted.getBoolean("favorite"))
        assertEquals("none", muted.getString("enableNtfs"))
        // The contact's own JSON is not changed (a copy is edited).
        assertEquals(false, contact.getJSONObject("chatSettings").getBoolean("favorite"))
        // No settings at all: the core's defaults, then the change.
        contact.remove("chatSettings")
        val fresh = CoreJson.chatSettingsUpdate(contact) { it.put("favorite", true) }
        assertEquals("all", fresh.getString("enableNtfs"))
        assertEquals(true, fresh.getBoolean("favorite"))
    }

    @Test
    fun disappearingPrefsKeepOtherFeatures() {
        val contact = JSONObject(Samples.contact(5, "Bob", "ready", userPrefs = """{"voice":{"allow":"no"},"timedMessages":{"allow":"no"}}"""))
        val on = CoreJson.contactPrefsUpdate(contact, 600)
        assertEquals("yes", on.getJSONObject("timedMessages").getString("allow"))
        assertEquals(600, on.getJSONObject("timedMessages").getInt("ttl"))
        assertEquals("no", on.getJSONObject("voice").getString("allow"))
        val off = CoreJson.contactPrefsUpdate(contact, null)
        assertEquals("yes", off.getJSONObject("timedMessages").getString("allow"))
        assertFalse(off.getJSONObject("timedMessages").has("ttl"))
        assertEquals("no", off.getJSONObject("voice").getString("allow"))
    }
}
