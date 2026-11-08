package app.encrogram.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupClientTest {

    private val base = "https://encrogram.nytmanager.com/v1/b/"
    private val id = "0759aa5ae6344c2e4480462c2b2a64c6f5a0de69109a5ff2aa57645311379e4d"

    @Test
    fun urlIsBasePlusId() {
        assertEquals("https://encrogram.nytmanager.com/v1/b/$id", BackupClient.urlFor(base, id))
        assertEquals("https://example.org:8443/x/$id", BackupClient.urlFor("https://example.org:8443/x/", id))
    }

    @Test
    fun onlyHttpsBasesEndingInSlash() {
        assertTrue(BackupClient.validBase(base))
        assertFalse(BackupClient.validBase("http://encrogram.nytmanager.com/v1/b/"))
        assertFalse(BackupClient.validBase("https://encrogram.nytmanager.com/v1/b"))
        assertFalse(BackupClient.validBase("https:///v1/b/"))
        assertFalse(BackupClient.validBase("https://user:pw@encrogram.nytmanager.com/v1/b/"))
        assertFalse(BackupClient.validBase("https://encrogram.nytmanager.com/v1/b/?x=1/"))
        assertFalse(BackupClient.validBase("https://encrogram.nytmanager.com/v1/b/#f/"))
        assertFalse(BackupClient.validBase("https://encrogram.nytmanager.com/v 1/b/"))
        assertFalse(BackupClient.validBase(""))
        assertNull(BackupClient.create(null))
        assertNull(BackupClient.create("http://encrogram.nytmanager.com/v1/b/"))
        assertNotNull(BackupClient.create(" $base "))
    }

    @Test
    fun onlyLowerHex64Ids() {
        assertNull(BackupClient.urlFor(base, id.uppercase()))
        assertNull(BackupClient.urlFor(base, id.dropLast(1)))
        assertNull(BackupClient.urlFor(base, id + "0"))
        assertNull(BackupClient.urlFor(base, "../" + id.drop(3)))
        assertNull(BackupClient.urlFor(base, id.dropLast(1) + "g"))
        assertNull(BackupClient.urlFor("http://x/", id))
    }

    @Test
    fun statusCodes() {
        assertEquals(BackupClient.Failure.NOT_FOUND, BackupClient.failureFor(404))
        assertEquals(BackupClient.Failure.TOO_LARGE, BackupClient.failureFor(413))
        assertEquals(BackupClient.Failure.BUSY, BackupClient.failureFor(429))
        assertEquals(BackupClient.Failure.BUSY, BackupClient.failureFor(503))
        assertEquals(BackupClient.Failure.SERVER, BackupClient.failureFor(500))
        assertEquals(BackupClient.Failure.SERVER, BackupClient.failureFor(403))
        assertEquals(BackupClient.Failure.SERVER, BackupClient.failureFor(301))
    }

    @Test
    fun failuresNeverCarryTheUrl() {
        val f = BackupClient.HttpFailure(BackupClient.Failure.SERVER, 500)
        assertFalse(f.message.orEmpty().contains("https"))
        assertFalse(f.toString().contains(id))
    }

    @Test
    fun readsAreCapped() {
        val data = ByteArray(100_000) { it.toByte() }
        assertArrayEquals(data, BackupClient.readCapped(ByteArrayInputStream(data), 100_000))
        assertNull(BackupClient.readCapped(ByteArrayInputStream(data), 99_999))
        assertEquals(0, BackupClient.readCapped(ByteArrayInputStream(ByteArray(0)), 10)!!.size)
    }

    // ---- tag check before an upload (review findings 4 and 5) ----

    private val mine = "\"6aefff83-3e8\""
    private val theirs = "\"6aefff85-3e8\""

    @Test
    fun sameTagUploads() {
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Found(mine), mine, skipCheck = false))
        // A weak marker doesn't make it another copy.
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Found("W/$mine"), mine, skipCheck = false))
    }

    @Test
    fun nothingStoredUploads() {
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Missing, mine, skipCheck = false))
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Missing, null, skipCheck = false))
    }

    @Test
    fun aTagThatDoesntProveItMeansReadTheCounter() {
        // R6.2 part B: another tag, or none stored, is settled by the copy's counter, never by uploading blindly.
        assertEquals(BackupClient.Check.VERIFY, BackupClient.tagDecision(BackupClient.Probe.Found(theirs), mine, skipCheck = false))
        assertEquals(BackupClient.Check.VERIFY, BackupClient.tagDecision(BackupClient.Probe.Found(theirs), null, skipCheck = false))
    }

    @Test
    fun backupNowSkipsTheCheck() {
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Found(theirs), mine, skipCheck = true))
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Found(null), null, skipCheck = true))
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.tagDecision(BackupClient.Probe.Missing, null, skipCheck = true))
    }

    @Test
    fun noAnswerNeverPausesTheChat() {
        for (skip in listOf(false, true)) {
            for (f in listOf(BackupClient.Failure.NETWORK, BackupClient.Failure.BUSY, BackupClient.Failure.SERVER)) {
                assertEquals(BackupClient.Check.UNREACHABLE, BackupClient.tagDecision(BackupClient.Probe.Failed(f, 0), mine, skip))
            }
        }
    }

    @Test
    fun aCopyWithoutATagIsCheckedByItsCounter() {
        assertEquals(BackupClient.Check.VERIFY, BackupClient.tagDecision(BackupClient.Probe.Found(null), mine, skipCheck = false))
    }

    @Test
    fun tagsAreCleaned() {
        assertEquals(mine, BackupClient.cleanTag("  $mine "))
        assertNull(BackupClient.cleanTag(null))
        assertNull(BackupClient.cleanTag(""))
        assertNull(BackupClient.cleanTag("\"a\nb\""))
        assertNull(BackupClient.cleanTag("x".repeat(201)))
        assertTrue(BackupClient.sameTag("W/$mine", mine))
        assertFalse(BackupClient.sameTag(theirs, mine))
    }

    // ---- R6.2 part A: our own tag from the Date we send ----

    @Test
    fun theTagIsComputedFromTheDateHeaderAndLength() {
        val sec = 1_793_527_200L
        assertEquals("Sun, 01 Nov 2026 10:00:00 GMT", BackupClient.httpDate(sec))
        assertEquals("\"6ae70da0-3e8\"", BackupClient.etagFor(sec, 1000))
        // Two-digit day, English names and GMT whatever the device locale.
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("Thu, 05 Mar 2026 07:08:09 GMT", BackupClient.httpDate(1_772_694_489L))
        } finally {
            java.util.Locale.setDefault(old)
        }
        assertEquals("\"0-0\"", BackupClient.etagFor(0, 0))
    }

    // ---- R6.2 part B: the counter settles a tag that doesn't prove it ----

    @Test
    fun aCopyWithAHigherCounterIsAnotherPhones() {
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.counterDecision(serverCounter = 41, myCounter = 41))
        assertEquals(BackupClient.Check.UPLOAD, BackupClient.counterDecision(serverCounter = 3, myCounter = 41))
        assertEquals(BackupClient.Check.OTHER_PHONE, BackupClient.counterDecision(serverCounter = 42, myCounter = 41))
    }

    // ---- R6.3: delete only our own copy ----

    @Test
    fun deleteOnlyTheCopyWeUploadedLast() {
        assertEquals(BackupClient.Del.DELETE, BackupClient.deleteDecision(BackupClient.Probe.Found(mine), mine))
        assertEquals(BackupClient.Del.DELETE, BackupClient.deleteDecision(BackupClient.Probe.Found("W/$mine"), mine))
        assertEquals(BackupClient.Del.GONE, BackupClient.deleteDecision(BackupClient.Probe.Missing, mine))
        assertEquals(BackupClient.Del.GONE, BackupClient.deleteDecision(BackupClient.Probe.Missing, null))
        assertEquals(BackupClient.Del.KEEP, BackupClient.deleteDecision(BackupClient.Probe.Found(theirs), mine))
        assertEquals(BackupClient.Del.KEEP, BackupClient.deleteDecision(BackupClient.Probe.Found(mine), null))
        assertEquals(BackupClient.Del.KEEP, BackupClient.deleteDecision(BackupClient.Probe.Found(null), mine))
        val down = BackupClient.Probe.Failed(BackupClient.Failure.NETWORK, 0)
        assertEquals(BackupClient.Del.RETRY, BackupClient.deleteDecision(down, mine))
    }
}
