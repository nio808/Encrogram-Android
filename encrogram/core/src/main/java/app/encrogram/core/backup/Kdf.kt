package app.encrogram.core.backup

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation for the 12-word backup (docs/recovery-phrase-plan.md, "How it works"). No new library:
 *
 * - BIP39 seed: PBKDF2-HMAC-SHA512(NFKD(mnemonic), "mnemonic" + NFKD(passphrase), 2048 iterations, 64 bytes).
 *   The platform's `PBKDF2WithHmacSHA512` is used for ASCII input (English words always are), because providers
 *   differ in how they turn a `char[]` password into bytes for anything else; [pbkdf2HmacSha512] (by hand over
 *   `Mac("HmacSHA512")`, RFC 8018 §5.2) covers non-ASCII input and providers without the algorithm.
 * - HKDF-SHA256 (RFC 5869) by hand over `Mac("HmacSHA256")`.
 *
 * THESE DERIVATIONS ARE A STORAGE FORMAT: the iOS app must derive the same id and key from the same words.
 * Never change an info string or a length; add a v2 instead.
 */
internal object Kdf {

    const val BIP39_ITERATIONS = 2048
    const val SEED_BYTES = 64

    /** HKDF info for the backup id (the file name on the server). */
    const val INFO_ID = "encrogram/backup/id/v1"

    /** HKDF info for the AES-256-GCM backup key (never leaves the phone). */
    const val INFO_KEY = "encrogram/backup/key/v1"

    /** The standard BIP39 seed. [passphrase] is empty in Encrogram; the official test vectors use "TREZOR". */
    fun bip39Seed(mnemonic: String, passphrase: String = ""): ByteArray {
        val m = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val salt = "mnemonic" + Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
        if (isAscii(m) && isAscii(salt)) {
            platformPbkdf2(m, salt.toByteArray(Charsets.US_ASCII), BIP39_ITERATIONS, SEED_BYTES)?.let { return it }
        }
        val password = m.toByteArray(Charsets.UTF_8)
        return try {
            pbkdf2HmacSha512(password, salt.toByteArray(Charsets.UTF_8), BIP39_ITERATIONS, SEED_BYTES)
        } finally {
            password.fill(0)
        }
    }

    /** `PBKDF2WithHmacSHA512` from the platform, or null when the provider doesn't have it. ASCII passwords only. */
    internal fun platformPbkdf2(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray? {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, bytes * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded?.takeIf { it.size == bytes }
        } catch (e: Exception) {
            null
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    /** PBKDF2 (RFC 8018 §5.2) with HMAC-SHA512, written out: the fallback for [bip39Seed]. */
    fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        require(iterations >= 1 && bytes >= 1)
        val mac = Mac.getInstance("HmacSHA512")
        // An empty HMAC key is legal in HMAC but refused by SecretKeySpec; BIP39 passwords are never empty.
        require(password.isNotEmpty()) { "empty password" }
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength
        val out = ByteArray(bytes)
        var block = 1
        var offset = 0
        while (offset < bytes) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(hLen, bytes - offset)
            System.arraycopy(t, 0, out, offset, n)
            t.fill(0)
            u.fill(0)
            offset += n
            block++
        }
        return out
    }

    /** HKDF-Extract (RFC 5869 §2.2). Null or empty [salt] means HashLen zero bytes. */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val s = if (salt == null || salt.isEmpty()) ByteArray(mac.macLength) else salt
        mac.init(SecretKeySpec(s, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand (RFC 5869 §2.3). */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        require(length in 1..255 * mac.macLength) { "bad HKDF length" }
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            counter++
        }
        t.fill(0)
        return out
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return try {
            hkdfExpand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }

    /**
     * backupId = hex(HKDF-SHA256(IKM = seed, salt = none, info = [INFO_ID], 32)): 64 lower-case hex chars.
     * backupKey = HKDF-SHA256(IKM = seed, salt = none, info = [INFO_KEY], 32).
     */
    fun backupSecrets(seed: ByteArray): BackupSecrets {
        require(seed.size == SEED_BYTES) { "BIP39 seed is 64 bytes" }
        val id = hkdfSha256(seed, null, INFO_ID.toByteArray(Charsets.US_ASCII), 32)
        val key = hkdfSha256(seed, null, INFO_KEY.toByteArray(Charsets.US_ASCII), 32)
        return try {
            BackupSecrets(Bip39.hex(id), key)
        } finally {
            id.fill(0)
        }
    }

    /** Words -> secrets, or null when they aren't 12 valid BIP39 words (checksum included). The seed is wiped. */
    fun secretsFromWords(words: List<String>): BackupSecrets? {
        Bip39.toEntropy(words)?.fill(0) ?: return null
        val seed = bip39Seed(Bip39.sentence(words))
        return try {
            backupSecrets(seed)
        } finally {
            seed.fill(0)
        }
    }

    private fun isAscii(s: String): Boolean = s.all { it.code < 0x80 }
}

/**
 * What the words open: [id] names the backup on the server (it is the read, write and delete permission, so it is
 * as secret as the key), [key] seals it. Never log, print or put either in an exception message.
 */
internal class BackupSecrets(val id: String, val key: ByteArray) {
    init {
        require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }) { "bad backup id" }
        require(key.size == 32) { "bad backup key" }
    }

    fun wipe() = key.fill(0)

    override fun toString(): String = "BackupSecrets(redacted)"
}
