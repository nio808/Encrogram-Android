package app.encrogram.core.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * BIP39 English mnemonics, 12 words only (128 bits of entropy + a 4-bit SHA-256 checksum).
 * Spec: https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki. Checked against the official Trezor
 * vectors in Bip39Test.
 *
 * The word list is the official `bip-0039/english.txt` (MIT, notice next to it), packaged as a Java resource so
 * the same file serves the app and the JVM tests. Its SHA-256 is checked on load: a damaged list never produces
 * or accepts words.
 */
internal object Bip39 {

    const val WORD_COUNT = 12
    const val ENTROPY_BYTES = 16
    private const val CHECKSUM_BITS = ENTROPY_BYTES * 8 / 32   // 4
    const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
    private const val RESOURCE = "/app/encrogram/core/backup/bip39-english.txt"

    class WordListError(message: String) : IllegalStateException(message)

    /** The 2048 words in order. Throws [WordListError] when the packaged list is missing or not the official one. */
    val words: List<String> by lazy { load() }

    private val index: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }

    private fun load(): List<String> {
        val bytes = Bip39::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw WordListError("word list missing")
        if (hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != WORDLIST_SHA256) throw WordListError("word list damaged")
        val list = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        if (list.size != 2048) throw WordListError("word list damaged")
        return list
    }

    /** 16 fresh random bytes. */
    fun newEntropy(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) }

    /** 16 bytes of entropy -> 12 words. */
    fun toWords(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "12 words need 16 bytes of entropy" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF
        val out = ArrayList<String>(WORD_COUNT)
        for (w in 0 until WORD_COUNT) {
            var value = 0
            for (b in 0 until 11) {
                val bit = w * 11 + b
                value = (value shl 1) or bitAt(entropy, checksum, bit)
            }
            out.add(words[value])
        }
        return out
    }

    /**
     * 12 words -> their 16 bytes of entropy, or null when there aren't exactly 12 words from the list or the
     * checksum doesn't match. Words are compared after [normalize].
     */
    fun toEntropy(input: List<String>): ByteArray? {
        val normalized = normalize(input)
        if (normalized.size != WORD_COUNT) return null
        val bits = BooleanArray(WORD_COUNT * 11)
        for ((w, word) in normalized.withIndex()) {
            val value = index[word] ?: return null
            for (b in 0 until 11) bits[w * 11 + b] = (value shr (10 - b)) and 1 == 1
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES * 8) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        val expected = (MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF) ushr (8 - CHECKSUM_BITS)
        var actual = 0
        for (i in 0 until CHECKSUM_BITS) actual = (actual shl 1) or (if (bits[ENTROPY_BYTES * 8 + i]) 1 else 0)
        if (actual != expected) {
            entropy.fill(0)
            return null
        }
        return entropy
    }

    /** Trimmed, lower-case (Locale.ROOT), NFKD (BIP39 normalises the mnemonic with NFKD). Blank entries dropped. */
    fun normalize(input: List<String>): List<String> =
        input.map { Normalizer.normalize(it.trim().lowercase(Locale.ROOT), Normalizer.Form.NFKD) }.filter { it.isNotEmpty() }

    /** The mnemonic sentence used for the seed: the normalised words joined by single ASCII spaces. */
    fun sentence(input: List<String>): String = normalize(input).joinToString(" ")

    /** Up to [limit] words starting with [prefix] (trimmed, lower-cased), in list order. Empty prefix: none. */
    fun suggestions(prefix: String, limit: Int): List<String> {
        val p = prefix.trim().lowercase(Locale.ROOT)
        if (p.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<String>(minOf(limit, 16))
        for (w in words) {
            if (w.startsWith(p)) {
                out.add(w)
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun bitAt(entropy: ByteArray, checksum: Int, bit: Int): Int =
        if (bit < ENTROPY_BYTES * 8) (entropy[bit / 8].toInt() ushr (7 - bit % 8)) and 1
        else (checksum ushr (7 - (bit - ENTROPY_BYTES * 8))) and 1

    internal fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
