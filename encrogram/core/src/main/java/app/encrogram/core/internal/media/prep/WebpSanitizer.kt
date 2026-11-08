package app.encrogram.core.internal.media.prep

/**
 * Removes metadata chunks from a WebP (docs/media-plan.md §4.4). Pure Kotlin.
 *
 * Dropped: EXIF, XMP and ICCP. In VP8X the EXIF (0x08), XMP (0x04) and ICC (0x20) flag bits are cleared. Kept untouched:
 * ANIM, ANMF, ALPH, VP8 and VP8L (and any other chunk). The RIFF size is rewritten and odd chunks are padded.
 * Malformed input returns null.
 */
internal object WebpSanitizer {
    private const val FLAG_ANIM = 0x02
    private const val CLEAR_MASK = 0x08 or 0x04 or 0x20

    private class Chunk(val tag: String, val start: Int, val size: Int) // start = payload offset

    fun sanitize(bytes: ByteArray): ByteArray? = try {
        run(bytes)
    } catch (_: RuntimeException) {
        null
    }

    fun isAnimated(bytes: ByteArray): Boolean = try {
        val chunks = parse(bytes)
        chunks != null && chunks.any { it.tag == "ANIM" || it.tag == "ANMF" } ||
            (chunks?.firstOrNull()?.let { it.tag == "VP8X" && it.size >= 10 && bytes[it.start].toInt() and FLAG_ANIM != 0 } == true)
    } catch (_: RuntimeException) {
        false
    }

    private fun parse(b: ByteArray): List<Chunk>? {
        if (b.size < 20) return null
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        val riff = le32(b, 4)
        if (riff < 12 || riff + 8L > b.size) return null
        val end = (riff + 8L).toInt()
        val list = ArrayList<Chunk>()
        var pos = 12
        while (pos < end) {
            if (end - pos < 8) return null
            val tag = String(b, pos, 4, Charsets.ISO_8859_1)
            val size = le32(b, pos + 4)
            if (size < 0 || size > end - pos - 8L) return null
            list.add(Chunk(tag, pos + 8, size.toInt()))
            pos += 8 + size.toInt() + (size.toInt() and 1)
            if (pos > end) {
                // A final odd chunk may lack its pad byte only if the file ends exactly there.
                if (pos - 1 != end) return null
                break
            }
        }
        return list
    }

    private fun run(b: ByteArray): ByteArray? {
        val chunks = parse(b) ?: return null
        if (chunks.none { it.tag == "VP8 " || it.tag == "VP8L" || it.tag == "ANMF" }) return null
        val keep = chunks.filter { it.tag != "EXIF" && it.tag != "XMP " && it.tag != "ICCP" }
        var total = 4 // "WEBP"
        for (c in keep) total += 8 + c.size + (c.size and 1)
        val out = ByteArray(total + 8)
        "RIFF".toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        putLe32(out, 4, total)
        "WEBP".toByteArray(Charsets.ISO_8859_1).copyInto(out, 8)
        var pos = 12
        for (c in keep) {
            c.tag.toByteArray(Charsets.ISO_8859_1).copyInto(out, pos)
            putLe32(out, pos + 4, c.size)
            System.arraycopy(b, c.start, out, pos + 8, c.size)
            if (c.tag == "VP8X") {
                if (c.size < 10) return null
                out[pos + 8] = (out[pos + 8].toInt() and CLEAR_MASK.inv()).toByte()
            }
            pos += 8 + c.size + (c.size and 1) // pad byte stays 0
        }
        return out
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte()
        b[o + 3] = (v ushr 24).toByte()
    }
}
