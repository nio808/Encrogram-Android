package app.encrogram.core.internal

import android.security.keystore.KeyPermanentlyInvalidatedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** A3: only a key that is gone or can never decrypt means "key lost"; other Keystore errors are retried, then FAILED. */
class DbKeyStoreTest {

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
    fun lostOnlyWhenTheKeyIsGoneOrCanNeverDecrypt() {
        assertTrue(DbKeyStore.readLost(DbKeyStore.KeyGone()))
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
    fun aGoneKeyIsLostAtOnce() {
        for (e in listOf(DbKeyStore.KeyGone(), AEADBadTagException(), KeyPermanentlyInvalidatedException(), DbKeyStore.StoredValueBroken())) {
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
}
