package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.InputRules

/** Every media limit in one place (owner decisions, AGENTS.md "Media v1"). Later per-network settings go here. */
internal data class MediaPolicyConfig(
    val autoImageBytes: Long = 2L * MIB,
    val autoVoiceBytes: Long = 2L * MIB,
    val autoGifBytes: Long = 5L * MIB,
    val autoVideoBytes: Long = 10L * MIB,
    /** Largest file we send or receive while on the shared server. */
    val capBytes: Long = 100L * MIB,
    /** Largest file [readMediaBytes] loads into the heap. */
    val readBytesLimit: Long = 16L * MIB,
    /** Largest file sealed or decrypted fully in memory; above this it goes through a staging file. */
    val memorySealLimit: Long = 32L * MIB,
    val freeSpaceFactor: Long = 3,
    val freeSpaceExtraBytes: Long = 50_000_000L,
    val captionChars: Int = 1024,
    val previewChars: Int = 8_000,
    val descriptionOverdueMs: Long = 72L * 3600 * 1000,
) {
    companion object {
        const val MIB = 1024L * 1024L
    }
}

/** Pure functions over [MediaPolicyConfig]; boundaries are inclusive. */
internal object MediaPolicy {

    val DEFAULT = MediaPolicyConfig()

    /** True when a file of [kind] and [sizeBytes] downloads by itself once its description has passed our check. */
    fun autoDownload(kind: MediaKind, sizeBytes: Long, c: MediaPolicyConfig = DEFAULT): Boolean {
        if (sizeBytes < 0 || sizeBytes > c.capBytes) return false
        return when (kind) {
            MediaKind.IMAGE -> sizeBytes <= c.autoImageBytes
            MediaKind.VOICE -> sizeBytes <= c.autoVoiceBytes
            MediaKind.GIF -> sizeBytes <= c.autoGifBytes
            MediaKind.VIDEO -> sizeBytes <= c.autoVideoBytes
            MediaKind.FILE -> false
        }
    }

    /** True when [sizeBytes] is within the cap (sending or receiving). */
    fun withinCap(sizeBytes: Long, c: MediaPolicyConfig = DEFAULT): Boolean = sizeBytes in 0..c.capBytes

    /** `readMediaBytes` refuses anything larger before reading. */
    fun readable(sizeBytes: Long, c: MediaPolicyConfig = DEFAULT): Boolean = sizeBytes in 0..c.readBytesLimit

    /** True: seal or decrypt in memory. False: use a staging file under `view/`. */
    fun sealInMemory(sizeBytes: Long, c: MediaPolicyConfig = DEFAULT): Boolean = sizeBytes in 0..c.memorySealLimit

    fun freeSpaceNeeded(sizeBytes: Long, c: MediaPolicyConfig = DEFAULT): Long =
        c.freeSpaceFactor * sizeBytes + c.freeSpaceExtraBytes

    fun hasFreeSpace(sizeBytes: Long, freeBytes: Long, c: MediaPolicyConfig = DEFAULT): Boolean =
        freeBytes >= freeSpaceNeeded(sizeBytes, c)

    /** Caption length in characters (UTF-16 units, as the composer counts them). */
    fun captionLengthOk(caption: String, c: MediaPolicyConfig = DEFAULT): Boolean = caption.length <= c.captionChars

    /**
     * The caption, the preview, the file name and an optional quote must fit one message body.
     * [quoteCost] is the encoded size of a quoted text (0 for no quote); the quote overhead is added here when it is > 0.
     */
    fun captionFits(caption: String, previewChars: Int, name: String, quoteCost: Int, c: MediaPolicyConfig = DEFAULT): Boolean {
        if (!captionLengthOk(caption, c)) return false
        val quote = if (quoteCost > 0) quoteCost + InputRules.QUOTE_OVERHEAD_BYTES else 0
        val total = InputRules.encodedTextBytes(caption) + previewChars + InputRules.encodedTextBytes(name) + quote
        return total <= InputRules.MAX_TEXT_BYTES
    }

    /** A description that has not arrived 72 hours after the item was received will not arrive. */
    fun descriptionOverdue(nowMs: Long, receivedMs: Long, c: MediaPolicyConfig = DEFAULT): Boolean =
        nowMs - receivedMs >= c.descriptionOverdueMs
}
