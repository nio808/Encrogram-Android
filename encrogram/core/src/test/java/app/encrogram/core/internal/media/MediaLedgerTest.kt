package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLedgerTest {
    private val dir: File = Files.createTempDirectory("ledger").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "media_ledger")

    private fun entry(id: Long, chat: Long = 1, item: Long = id) =
        LedgerEntry(id, chat, item, MediaKind.IMAGE, 1234, Verdict.PASSED, receivedMs = 99)

    @Test
    fun roundTripThroughReload() {
        val l = MediaLedger(file)
        assertTrue(l.put(entry(1), "msg-1"))
        l.markWanted(1); l.markAccepted(1)
        l.put(entry(2).copy(kind = MediaKind.VIDEO, verdict = null), "msg-2")
        l.markCancelled(2)

        val r = MediaLedger(file)
        val e1 = r.get(1, "msg-1")!!
        assertEquals(Verdict.PASSED, e1.verdict)
        assertTrue(e1.wanted); assertTrue(e1.accepted); assertFalse(e1.cancelled)
        assertEquals(1234, e1.sizeBytes)
        assertEquals(99, e1.receivedMs)
        val e2 = r.get(2, "msg-2")!!
        assertEquals(MediaKind.VIDEO, e2.kind)
        assertNull(e2.verdict)
        assertTrue(e2.cancelled)
        assertEquals(2, r.entries().size)
    }

    @Test
    fun hashMismatchGivesNull() {
        val l = MediaLedger(file)
        l.put(entry(1), "msg-1")
        assertNull(l.get(1, "other"))
        assertNull(l.get(1, null))
        assertNotNull(l.get(1, "msg-1"))
        assertNull(MediaLedger(file).get(1, "other"))
    }

    @Test
    fun withoutSharedMsgIdItStaysInMemory() {
        val l = MediaLedger(file)
        l.put(entry(1), null)
        assertNotNull(l.get(1, null))
        assertNull(l.get(1, "x"))
        assertFalse(file.isFile && file.readText().isNotEmpty())
        assertNull(MediaLedger(file).get(1, null))
    }

    @Test
    fun corruptLinesAndStrayTmpAreIgnored() {
        val l = MediaLedger(file)
        l.put(entry(1), "a")
        File(dir, "media_ledger.tmp").writeText("garbage")
        file.appendText("\nnot a line\n1|2|3|NOPE|5|-|0|0|0|1|abcdefabcdef\n9|1|9|IMAGE|x|-|0|0|0|1|abcdefabcdef\n")
        val r = MediaLedger(file)
        assertEquals(1, r.entries().size)
        assertNotNull(r.get(1, "a"))
    }

    @Test
    fun removeAndClear() {
        val l = MediaLedger(file)
        l.put(entry(1, chat = 5, item = 10), "a")
        l.put(entry(2, chat = 5, item = 11), "b")
        l.put(entry(3, chat = 6, item = 10), "c")
        l.removeForChat(5, listOf(10))
        assertEquals(listOf(2L, 3L), MediaLedger(file).entries().map { it.fileId })
        l.remove(2)
        assertEquals(listOf(3L), MediaLedger(file).entries().map { it.fileId })
        l.clear()
        assertTrue(MediaLedger(file).entries().isEmpty())
    }

    @Test
    fun capDropsOldest() {
        val l = MediaLedger(file)
        for (i in 1L..(MediaLedger.MAX_ENTRIES + 3L)) l.put(entry(i), "m$i")
        assertEquals(MediaLedger.MAX_ENTRIES, l.entries().size)
        assertNull(l.get(1, "m1"))
        assertNotNull(l.get(MediaLedger.MAX_ENTRIES + 3L, "m${MediaLedger.MAX_ENTRIES + 3L}"))
    }

    @Test
    fun fileHoldsOnlyIdsEnumsSizesAndHashes() {
        val l = MediaLedger(file)
        l.put(entry(1), "SECRET-MSG-ID")
        val text = file.readText()
        assertFalse(text.contains("SECRET"))
        assertTrue(Regex("1\\|1\\|1\\|IMAGE\\|1234\\|PASSED\\|0\\|0\\|0\\|99\\|[0-9a-f]{12}").matches(text.trim()))
    }
}
