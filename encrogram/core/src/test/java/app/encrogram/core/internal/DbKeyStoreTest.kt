package app.encrogram.core.internal

import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * A3: only a key that can never decrypt means "key lost" at once; other Keystore errors, a missing key too, are retried
 * (review of 4bce1db8, #3), then FAILED; a "busy" Keystore across a reboot is lost after all (#2).
 */
class DbKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lines = ArrayList<String>()

    @Before
    fun sink() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { lines.add(it) }
    }

    @After
    fun noSink() {
        SecuredLog.testSink = null
    }

    @Test
    fun lostOnlyWhenTheKeyCanNeverDecrypt() {
        // Android 8-11: a failed binder call to a restarting Keystore daemon also reads as "no key".
        assertFalse(DbKeyStore.readLost(DbKeyStore.KeyGone()))
        assertTrue(DbKeyStore.readLost(DbKeyStore.StoredValueBroken()))
        assertTrue(DbKeyStore.readLost(KeyPermanentlyInvalidatedException()))
        assertTrue(DbKeyStore.readLost(AEADBadTagException()))
        // The Keystore daemon restarting, busy right after boot, or an update in progress.
        assertFalse(DbKeyStore.readLost(KeyStoreException("SYSTEM_ERROR")))
        assertFalse(DbKeyStore.readLost(ProviderException("keystore")))
        assertFalse(DbKeyStore.readLost(UnrecoverableKeyException("Failed to obtain information about key")))
        assertFalse(DbKeyStore.readLost(IOException()))
        assertFalse(DbKeyStore.readLost(IllegalStateException()))
        assertFalse(DbKeyStore.readLost(RuntimeException()))
    }

    @Test
    fun aPassingKeystoreErrorIsRetriedWithPauses() {
        val pauses = ArrayList<Long>()
        var calls = 0
        val key = DbKeyStore.readWithRetry({
            if (++calls < 3) throw KeyStoreException("SYSTEM_ERROR") else "passphrase"
        }) { pauses.add(it) }
        assertEquals("passphrase", key)
        assertEquals(listOf(300L, 1_000L), pauses)
    }

    @Test
    fun aKeystoreThatKeepsFailingIsBusyNeverLost() {
        val pauses = ArrayList<Long>()
        var calls = 0
        try {
            DbKeyStore.readWithRetry({
                calls++
                throw ProviderException("secret-canary")
            }) { pauses.add(it) }
            fail()
        } catch (f: DbKeyStore.KeyFailure) {
            assertEquals(DbKeyStore.KEYSTORE_BUSY, f.reason)
        }
        assertEquals(1 + DbKeyStore.RETRY_PAUSES_MS.size, calls)
        assertEquals(DbKeyStore.RETRY_PAUSES_MS.toList(), pauses)
        assertTrue(lines.none { it.contains("secret-canary") })
    }

    @Test
    fun aKeyThatCanNeverDecryptIsLostAtOnce() {
        for (e in listOf(AEADBadTagException(), KeyPermanentlyInvalidatedException(), DbKeyStore.StoredValueBroken())) {
            val pauses = ArrayList<Long>()
            try {
                DbKeyStore.readWithRetry({ throw e }) { pauses.add(it) }
                fail()
            } catch (f: DbKeyStore.KeyFailure) {
                assertEquals("key_unreadable", f.reason)
            }
            assertTrue(pauses.isEmpty())
        }
    }

    @Test
    fun aMissingKeyIsRetriedAndLostOnlyIfStillMissing() {
        val pauses = ArrayList<Long>()
        var calls = 0
        assertEquals("passphrase", DbKeyStore.readWithRetry({ if (++calls < 2) throw DbKeyStore.KeyGone() else "passphrase" }) { pauses.add(it) })
        assertEquals(listOf(300L), pauses)

        pauses.clear()
        calls = 0
        try {
            DbKeyStore.readWithRetry({ calls++; throw DbKeyStore.KeyGone() }) { pauses.add(it) }
            fail()
        } catch (f: DbKeyStore.KeyFailure) {
            assertEquals("key_unreadable", f.reason)
        }
        assertEquals(1 + DbKeyStore.RETRY_PAUSES_MS.size, calls)
        assertEquals(DbKeyStore.RETRY_PAUSES_MS.toList(), pauses)
    }

    @Test
    fun android13SaysWhetherAKeystoreErrorPasses() {
        val permanent = ProviderException("keystore", KeyStoreException("wrapped"))
        // Before Android 13 (and in these tests) the platform says nothing.
        assertEquals(null, DbKeyStore.platformTransient(permanent))
        assertFalse(DbKeyStore.readLost(permanent))
        assertTrue(DbKeyStore.readLost(permanent) { false })
        assertFalse(DbKeyStore.readLost(permanent) { true })

        val pauses = ArrayList<Long>()
        try {
            DbKeyStore.readWithRetry({ throw permanent }, { false }) { pauses.add(it) }
            fail()
        } catch (f: DbKeyStore.KeyFailure) {
            assertEquals("not transient: no point waiting", "key_unreadable", f.reason)
        }
        assertTrue(pauses.isEmpty())
        var calls = 0
        assertEquals("k", DbKeyStore.readWithRetry({ if (++calls < 3) throw permanent else "k" }, { true }) { pauses.add(it) })
    }

    @Test
    fun aBusyStreakIsLostOnlyAfterThreeStartsSpanningAReboot() {
        val file = File(tmp.root, "keystore_busy")
        val streak = DbKeyStore.BusyStreak(file)
        // Busy starts within one boot: never lost, however many.
        repeat(5) { assertFalse(streak.record("c7")) }
        // After a reboot the Keystore daemon started over and it is still busy: lost.
        assertTrue(streak.record("c8"))

        // A start that reads the key ends the streak.
        streak.clear()
        assertFalse(file.exists())
        assertFalse(streak.record("c8"))
        assertFalse("two starts, a reboot between: not yet", streak.record("c9"))
        assertTrue("the third, still in another boot than the first", DbKeyStore.BusyStreak(file).record("c9"))

        // A broken file starts the count again.
        file.writeText("garbage")
        assertFalse(streak.record("c9"))
        assertFalse(streak.record("c10"))
        assertTrue(streak.record("c10"))
    }
}
