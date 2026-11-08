package app.encrogram.core.backup

import app.encrogram.core.internal.SecuredLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupStoreTest {

    private lateinit var dir: File
    private val id = "0759aa5ae6344c2e4480462c2b2a64c6f5a0de69109a5ff2aa57645311379e4d"

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        dir = Files.createTempDirectory("encro-backup-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun metaSurvivesReload() {
        val a = BackupStore(File(dir, "backup"))
        assertEquals(BackupStore.Meta(), a.meta())
        assertTrue(a.update { it.copy(counter = 4, lastBackupMs = 1234, dirty = true, error = "server:503") })
        val b = BackupStore(File(dir, "backup"))
        assertEquals(BackupStore.Meta(4, 1234, true, "server:503"), b.meta())
    }

    @Test
    fun seenCountersAreKeptByHashOnly() {
        val s = BackupStore(dir)
        assertNull(s.highestSeen(id))
        assertTrue(s.noteSeen(id, 9))
        assertTrue(s.noteSeen(id, 3))     // never goes down
        assertEquals(9L, s.highestSeen(id))
        assertEquals(9L, BackupStore(dir).highestSeen(id))
        val text = File(dir, BackupStore.STATE_FILE).readText()
        assertFalse("the id is a server permission: never stored in plain", text.contains(id))
        assertFalse(text.contains(id.substring(0, 16)))
        assertTrue(text.contains("seen." + BackupStore.seenKey(id)))
    }

    @Test
    fun resetKeepsTheRollbackCounters() {
        val s = BackupStore(dir)
        s.update { it.copy(counter = 5, lastBackupMs = 99, dirty = true) }
        s.noteSeen(id, 5)
        assertTrue(s.reset())
        assertEquals(BackupStore.Meta(), BackupStore(dir).meta())
        assertEquals(5L, BackupStore(dir).highestSeen(id))
    }

    @Test
    fun badErrorCodesAreNotStored() {
        val s = BackupStore(dir)
        s.update { it.copy(error = "free text with spaces") }
        assertNull(BackupStore(dir).meta().error)
    }

    @Test
    fun pendingContactsSurviveRestarts() {
        val s = BackupStore(dir)
        assertTrue(s.pending().isEmpty())
        assertEquals(Long.MAX_VALUE, s.repairSince())
        val proven = BackupStore.Pending(proven = true)
        val all = BackupStore.Pending(proven = true, switched = true, forced = true, syncing = true)
        assertTrue(s.startRepair(linkedMapOf(3L to BackupStore.Pending(), 7L to proven, 9L to all), sinceItemId = 120))
        val p = BackupStore(dir).pending()
        assertEquals(listOf(3L, 7L, 9L), p.keys.toList())
        assertEquals(BackupStore.Pending(), p[3L])
        assertEquals(proven, p[7L])
        assertEquals(all, p[9L])
        assertEquals(120L, BackupStore(dir).repairSince())
        assertTrue(s.updatePending { it.remove(3L); it[7L] = it[7L]!!.copy(switched = true) })
        assertEquals(mapOf(7L to BackupStore.Pending(proven = true, switched = true), 9L to all), BackupStore(dir).pending())
        // Later writes keep the restore's starting item id.
        assertTrue(s.setPending(mapOf(9L to all)))
        assertEquals(120L, BackupStore(dir).repairSince())
        assertTrue(s.setPending(emptyMap()))
        assertFalse(File(dir, BackupStore.PENDING_FILE).exists())
        assertEquals(Long.MAX_VALUE, BackupStore(dir).repairSince())
    }

    @Test
    fun oldHeardFlagIsNotProof() {
        // Builds before 8 Nov 2026 wrote 'h' when an incoming message decrypted: that proves one direction only.
        File(dir, BackupStore.PENDING_FILE).writeText("4 h\n5 hs\n")
        val p = BackupStore(dir).pending()
        assertEquals(BackupStore.Pending(), p[4L])
        assertEquals(BackupStore.Pending(switched = true), p[5L])
        assertEquals(Long.MAX_VALUE, BackupStore(dir).repairSince())
    }

    @Test
    fun scheduleStateSurvivesReload() {
        val a = BackupStore(dir)
        val m = BackupStore.Meta(counter = 2, lastBackupMs = 50, dirty = true, error = "other_phone", urgent = true,
            unconditional = true, otherPhone = true, failures = 3, lastAttemptMs = 77)
        assertTrue(a.update { m })
        assertEquals(m, BackupStore(dir).meta())
        assertTrue(a.reset())
        assertEquals(BackupStore.Meta(), BackupStore(dir).meta())
    }

    // ---- backoff and automatic schedule (finding 5) ----

    private val min = 60_000L
    private val hour = 60 * min

    @Test
    fun backoffDoublesFromHalfAnHourToADay() {
        assertEquals(0L, BackupStore.backoffMs(0))
        assertEquals(30 * min, BackupStore.backoffMs(1))
        assertEquals(1 * hour, BackupStore.backoffMs(2))
        assertEquals(2 * hour, BackupStore.backoffMs(3))
        assertEquals(4 * hour, BackupStore.backoffMs(4))
        assertEquals(8 * hour, BackupStore.backoffMs(5))
        assertEquals(16 * hour, BackupStore.backoffMs(6))
        assertEquals(24 * hour, BackupStore.backoffMs(7))
        assertEquals(24 * hour, BackupStore.backoffMs(50))
        assertEquals(24 * hour, BackupStore.backoffMs(Int.MAX_VALUE))
    }

    @Test
    fun autoScheduleFollowsUrgentDailyAndBackoff() {
        val now = 10_000_000_000L
        // Nothing changed: nothing due.
        assertNull(BackupStore.autoDueMs(BackupStore.Meta(lastBackupMs = now - 1), now))
        // Changed: a day after the last backup.
        assertEquals(now - 1 + BackupStore.DAY_MS, BackupStore.autoDueMs(BackupStore.Meta(lastBackupMs = now - 1, dirty = true), now))
        // Owed (restore, switch, contact): now.
        assertEquals(now, BackupStore.autoDueMs(BackupStore.Meta(lastBackupMs = now - 1, urgent = true), now))
        // After 2 failures in a row, even an owed upload waits 1 h from the last attempt.
        val failing = BackupStore.Meta(urgent = true, failures = 2, lastAttemptMs = now - 10 * min)
        assertEquals(now - 10 * min + hour, BackupStore.autoDueMs(failing, now))
        // A success resets the count: due at once again.
        assertEquals(now, BackupStore.autoDueMs(failing.copy(failures = 0), now))
    }

    @Test
    fun tooLargeAndOtherPhoneAreNeverRetriedAutomatically() {
        val now = 5_000L
        val tooLarge = BackupStore.Meta(urgent = true, dirty = true, error = BackupStore.TOO_LARGE, failures = 1, lastAttemptMs = 1)
        assertTrue(BackupStore.autoPaused(tooLarge))
        assertNull(BackupStore.autoDueMs(tooLarge, now))
        assertNull(BackupStore.autoDueMs(tooLarge, now + 100 * BackupStore.DAY_MS))
        val other = BackupStore.Meta(urgent = true, dirty = true, otherPhone = true, error = BackupStore.OTHER_PHONE)
        assertTrue(BackupStore.autoPaused(other))
        assertNull(BackupStore.autoDueMs(other, now))
        // Other errors (network, busy) are retried with backoff.
        assertFalse(BackupStore.autoPaused(BackupStore.Meta(error = "network")))
    }

    // ---- sealed files: server tag and the delete queue (findings 4 and 7) ----

    /** A stand-in for KeyVault.metaBox(): real AES-GCM with a test key. */
    private class TestBox : BackupStore.Box {
        private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        override fun seal(plain: ByteArray): ByteArray {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return c.iv + c.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private val id2 = "1f".repeat(32)
    private val id3 = "2e".repeat(32)
    private val tag = "\"6aefff83-3e8\""

    private fun allFileText(): String = dir.walk().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }

    @Test
    fun serverTagIsSealedAndBoundToTheId() {
        val s = BackupStore(dir, TestBox())
        assertNull(s.serverTag(id))
        assertTrue(s.setServerTag(id, tag))
        assertEquals(tag, BackupStore(dir, TestBox()).serverTag(id))
        assertNull("another phrase's id never sees this tag", s.serverTag(id2))
        assertFalse(allFileText().contains(tag))
        assertFalse(allFileText().contains("6aefff83"))
        assertTrue(s.setServerTag(id, null))
        assertNull(s.serverTag(id))
        // Turning backup off forgets it.
        s.setServerTag(id, tag)
        s.reset()
        assertNull(s.serverTag(id))
        // Without a box nothing is stored in plain.
        val plain = BackupStore(File(dir, "nobox"))
        assertFalse(plain.setServerTag(id, tag))
        assertNull(plain.serverTag(id))
    }

    @Test
    fun pendingDeletesAreSealedOrderedAndSurviveReset() {
        val s = BackupStore(dir, TestBox())
        assertTrue(s.pendingDeletes().isEmpty())
        assertTrue(s.addPendingDelete(id))
        assertTrue(s.addPendingDelete(id2))
        assertTrue(s.addPendingDelete(id))          // no duplicates; moves to the end
        assertEquals(listOf(id2, id), BackupStore(dir, TestBox()).pendingDeletes())
        assertFalse("the ids are server permissions: never in plain", allFileText().contains(id))
        assertFalse(allFileText().contains(id2.substring(0, 16)))
        // "Turn off" must not lose the queue.
        s.reset()
        assertEquals(listOf(id2, id), BackupStore(dir, TestBox()).pendingDeletes())
        assertTrue(s.removePendingDelete(id2))
        assertTrue(s.removePendingDelete(id3))      // not queued: fine
        assertEquals(listOf(id), s.pendingDeletes())
        assertTrue(s.removePendingDelete(id))
        assertTrue(s.pendingDeletes().isEmpty())
        assertFalse(File(dir, BackupStore.DELETE_FILE).exists())
        assertFalse(s.addPendingDelete("not-an-id"))
    }

    @Test
    fun pendingDeleteQueueIsCapped() {
        val s = BackupStore(dir, TestBox())
        val ids = (0 until BackupStore.MAX_PENDING_DELETES + 3).map { i -> "%064x".format(i + 1) }
        for (i in ids) assertTrue(s.addPendingDelete(i))
        assertEquals(ids.takeLast(BackupStore.MAX_PENDING_DELETES), s.pendingDeletes())
    }

    @Test
    fun aBrokenBoxLosesNothingSilently() {
        val broken = object : BackupStore.Box {
            override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("keystore")
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore")
        }
        val s = BackupStore(dir, broken)
        assertFalse(s.addPendingDelete(id))
        assertFalse(s.setServerTag(id, tag))
        assertTrue(s.pendingDeletes().isEmpty())
    }
}
