package app.encrogram.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class EnvelopeTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val dbKey = "q83v/+Ak2mVZbJ1b0sYwF3c6H2gk9wLx4u0vZQ1aBcE="
    private val archive = ByteArray(5000) { (it % 251).toByte() }

    private fun payload(counter: Long = 7, createdAt: Long = 1_762_600_000_000) = Envelope.Payload(createdAt, counter, dbKey, archive.copyOf())

    @Test
    fun sealThenOpen() {
        val sealed = Envelope.seal(key, payload())
        assertEquals("ENCB", String(sealed, 0, 4, Charsets.US_ASCII))
        assertEquals(1.toByte(), sealed[4])
        // magic + version + nonce + (8 + 8 + 2 + key + archive) + tag
        assertEquals(5 + 12 + 18 + dbKey.length + archive.size + 16, sealed.size)
        val opened = Envelope.open(key, sealed) as Envelope.Opened.Ok
        assertEquals(1_762_600_000_000, opened.payload.createdAtMs)
        assertEquals(7, opened.payload.counter)
        assertEquals(dbKey, opened.payload.dbKey)
        assertArrayEquals(archive, opened.payload.archive)
    }

    @Test
    fun nothingReadableInTheEnvelope() {
        val sealed = Envelope.seal(key, payload())
        val text = String(sealed, Charsets.ISO_8859_1)
        assertFalse(text.contains(dbKey))
        assertFalse(text.contains("q83v"))
    }

    @Test
    fun freshNonceEachTime() {
        val a = Envelope.seal(key, payload())
        val b = Envelope.seal(key, payload())
        assertFalse(a.copyOfRange(5, 17).contentEquals(b.copyOfRange(5, 17)))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongKeyIsRefused() {
        val sealed = Envelope.seal(key, payload())
        val other = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertSame(Envelope.Opened.BadKey, Envelope.open(other, sealed))
        assertSame(Envelope.Opened.BadKey, Envelope.open(ByteArray(16), sealed))
    }

    @Test
    fun anyChangedByteIsRefused() {
        val sealed = Envelope.seal(key, payload())
        // Header bytes are format checks; nonce, ciphertext and tag bytes fail authentication.
        for (i in listOf(0, 3, 4)) {
            val t = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertSame("byte $i", Envelope.Opened.BadFormat, Envelope.open(key, t))
        }
        for (i in listOf(5, 16, 17, 40, sealed.size / 2, sealed.size - 17, sealed.size - 1)) {
            val t = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertSame("byte $i", Envelope.Opened.BadKey, Envelope.open(key, t))
        }
    }

    @Test
    fun truncatedOrForeignFilesAreRefused() {
        val sealed = Envelope.seal(key, payload())
        assertSame(Envelope.Opened.BadKey, Envelope.open(key, sealed.copyOf(sealed.size - 1)))
        assertSame(Envelope.Opened.BadFormat, Envelope.open(key, sealed.copyOf(20)))
        assertSame(Envelope.Opened.BadFormat, Envelope.open(key, ByteArray(0)))
        assertSame(Envelope.Opened.BadFormat, Envelope.open(key, "PK\u0003\u0004 a zip, not ours".toByteArray() + ByteArray(64)))
        val v2 = sealed.copyOf().also { it[4] = 2 }
        assertSame(Envelope.Opened.BadFormat, Envelope.open(key, v2))
    }

    @Test
    fun theVersionIsAuthenticated() {
        // Re-labelling a v1 envelope's AAD can't be hidden: v1 is the only accepted version, and the AAD binds it.
        val sealed = Envelope.seal(key, payload())
        val relabelled = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertSame(Envelope.Opened.BadFormat, Envelope.open(key, relabelled))
    }

    @Test
    fun counterRollback() {
        assertFalse(Envelope.isRollback(5, null))
        assertFalse(Envelope.isRollback(5, 5))
        assertFalse(Envelope.isRollback(6, 5))
        assertTrue(Envelope.isRollback(4, 5))
        // The counter is inside the ciphertext: an old copy keeps its old counter.
        val old = Envelope.seal(key, payload(counter = 3))
        val opened = Envelope.open(key, old) as Envelope.Opened.Ok
        assertTrue(Envelope.isRollback(opened.payload.counter, 9))
    }

    @Test
    fun databaseKeyRules() {
        assertTrue(Envelope.validDbKey(dbKey))
        assertFalse(Envelope.validDbKey(""))
        assertFalse(Envelope.validDbKey("has space"))
        assertFalse(Envelope.validDbKey("café"))
        assertFalse(Envelope.validDbKey("x".repeat(Envelope.MAX_DB_KEY_CHARS + 1)))
        try {
            Envelope.seal(key, Envelope.Payload(1, 1, "bad key", archive))
            throw AssertionError("must refuse")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains("bad key "))
        }
    }

    @Test
    fun sizesStayUnderTheServerCap() {
        assertEquals(20 * 1024 * 1024, Envelope.MAX_ENVELOPE_BYTES)
        val worst = 5 + 12 + 18 + Envelope.MAX_DB_KEY_CHARS + Envelope.MAX_ARCHIVE_BYTES + 16
        assertEquals(Envelope.MAX_ENVELOPE_BYTES, worst)
        try {
            Envelope.seal(key, Envelope.Payload(1, 1, dbKey, ByteArray(Envelope.MAX_ARCHIVE_BYTES + 1)))
            throw AssertionError("must refuse")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun emptyArchiveStillRoundTrips() {
        val sealed = Envelope.seal(key, Envelope.Payload(0, 0, "k", ByteArray(0)), SecureRandom())
        val opened = Envelope.open(key, sealed) as Envelope.Opened.Ok
        assertEquals("k", opened.payload.dbKey)
        assertEquals(0, opened.payload.archive.size)
    }
}
