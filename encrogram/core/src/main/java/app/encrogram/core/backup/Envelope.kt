package app.encrogram.core.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file as stored on the server (docs/recovery-phrase-plan.md):
 *
 * ```
 * "ENCB" | 0x01 | nonce (12 bytes) | AES-256-GCM(backupKey, plaintext, aad = "ENCB" | 0x01)  (ciphertext + 16-byte tag)
 * plaintext = createdAtMs (u64 BE) | counter (u64 BE) | dbKeyLength (u16 BE) | dbKey (ASCII) | archive zip
 * ```
 *
 * The SimpleX archive is a plain zip of the two SQLCipher databases (Archive.hs:55-70), so the database key
 * travels inside the ciphertext: without it a new phone couldn't open them. The counter rises with every upload;
 * [isRollback] refuses a copy older than one this phone has already seen.
 */
internal object Envelope {

    private val MAGIC = byteArrayOf('E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte())
    const val VERSION: Byte = 1
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 5            // magic + version
    private const val FIXED_PLAIN_BYTES = 8 + 8 + 2
    const val MAX_DB_KEY_CHARS = 256

    /** The server's cap (nginx client_max_body_size 20m = 20 MiB). Bigger envelopes are never sent or accepted. */
    const val MAX_ENVELOPE_BYTES = 20 * 1024 * 1024

    /** Largest archive that still fits under [MAX_ENVELOPE_BYTES] with a maximal database key. */
    const val MAX_ARCHIVE_BYTES = MAX_ENVELOPE_BYTES - HEADER_BYTES - NONCE_BYTES - TAG_BITS / 8 - FIXED_PLAIN_BYTES - MAX_DB_KEY_CHARS

    class Payload(val createdAtMs: Long, val counter: Long, val dbKey: String, val archive: ByteArray) {
        override fun toString(): String = "Payload(createdAtMs=$createdAtMs, counter=$counter, redacted)"
    }

    sealed class Opened {
        class Ok(val payload: Payload) : Opened()
        /** Not an Encrogram backup, truncated, or a newer format. */
        object BadFormat : Opened()
        /** The tag didn't verify: wrong key, or the file was changed. */
        object BadKey : Opened()
    }

    /** A database key the core will accept in `/_db encryption` (DBEncryptionKey strP: printable ASCII, no space). */
    fun validDbKey(k: String): Boolean = k.length in 1..MAX_DB_KEY_CHARS && k.all { it.code in 0x21..0x7E }

    fun seal(key: ByteArray, payload: Payload, random: SecureRandom = SecureRandom()): ByteArray {
        require(key.size == 32) { "AES-256 key" }
        require(validDbKey(payload.dbKey)) { "bad database key" }
        require(payload.counter >= 0 && payload.createdAtMs >= 0) { "bad header" }
        require(payload.archive.size <= MAX_ARCHIVE_BYTES) { "archive too large" }
        val dbKey = payload.dbKey.toByteArray(Charsets.US_ASCII)
        val plain = ByteBuffer.allocate(FIXED_PLAIN_BYTES + dbKey.size + payload.archive.size)
            .putLong(payload.createdAtMs)
            .putLong(payload.counter)
            .putShort(dbKey.size.toShort())
            .put(dbKey)
            .put(payload.archive)
            .array()
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        return try {
            val ct = cipher.doFinal(plain)
            ByteBuffer.allocate(HEADER_BYTES + NONCE_BYTES + ct.size).put(aad()).put(nonce).put(ct).array()
        } finally {
            plain.fill(0)
            dbKey.fill(0)
        }
    }

    fun open(key: ByteArray, envelope: ByteArray): Opened {
        if (key.size != 32) return Opened.BadKey
        if (envelope.size < HEADER_BYTES + NONCE_BYTES + TAG_BITS / 8 + FIXED_PLAIN_BYTES) return Opened.BadFormat
        if (envelope.size > MAX_ENVELOPE_BYTES) return Opened.BadFormat
        for (i in MAGIC.indices) if (envelope[i] != MAGIC[i]) return Opened.BadFormat
        if (envelope[4] != VERSION) return Opened.BadFormat
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, envelope, HEADER_BYTES, NONCE_BYTES))
            cipher.updateAAD(envelope, 0, HEADER_BYTES)
            cipher.doFinal(envelope, HEADER_BYTES + NONCE_BYTES, envelope.size - HEADER_BYTES - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            return Opened.BadKey
        } catch (e: java.security.GeneralSecurityException) {
            return Opened.BadKey
        }
        try {
            if (plain.size < FIXED_PLAIN_BYTES) return Opened.BadFormat
            val buf = ByteBuffer.wrap(plain)
            val createdAt = buf.long
            val counter = buf.long
            val keyLen = buf.short.toInt() and 0xFFFF
            if (createdAt < 0 || counter < 0 || keyLen == 0 || keyLen > MAX_DB_KEY_CHARS || buf.remaining() < keyLen) return Opened.BadFormat
            val keyBytes = ByteArray(keyLen).also { buf.get(it) }
            val dbKey = String(keyBytes, Charsets.US_ASCII)
            keyBytes.fill(0)
            if (!validDbKey(dbKey)) return Opened.BadFormat
            val archive = ByteArray(buf.remaining()).also { buf.get(it) }
            return Opened.Ok(Payload(createdAt, counter, dbKey, archive))
        } finally {
            plain.fill(0)
        }
    }

    /** True when [counter] is older than the newest copy this phone has seen for the same backup. */
    fun isRollback(counter: Long, highestSeen: Long?): Boolean = highestSeen != null && counter < highestSeen

    private fun aad(): ByteArray = MAGIC + byteArrayOf(VERSION)
}
