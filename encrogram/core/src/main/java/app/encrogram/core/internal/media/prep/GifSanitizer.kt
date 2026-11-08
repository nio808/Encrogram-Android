package app.encrogram.core.internal.media.prep

/**
 * Removes metadata blocks from a GIF and keeps everything needed to show it (docs/media-plan.md §4.4). Pure Kotlin.
 *
 * Dropped: Comment (0x21 0xFE), Plain Text (0x21 0x01), every Application extension except NETSCAPE2.0 and ANIMEXTS1.0
 * (the loop count), and unknown extensions. Kept byte for byte: header, screen descriptor, colour tables, Graphic Control
 * and all image data. Anything after the trailer is dropped. Malformed or truncated input returns null, never a pass-through.
 */
internal object GifSanitizer {
    fun sanitize(bytes: ByteArray): ByteArray? = try {
        run(bytes)
    } catch (_: RuntimeException) {
        null
    }

    private fun run(b: ByteArray): ByteArray? {
        if (b.size < 13) return null
        val magic = String(b, 0, 6, Charsets.ISO_8859_1)
        if (magic != "GIF87a" && magic != "GIF89a") return null
        val out = java.io.ByteArrayOutputStream(b.size)
        val screenFlags = b[10].toInt() and 0xFF
        var pos = 13
        if (screenFlags and 0x80 != 0) pos += 3 shl ((screenFlags and 7) + 1)
        if (pos > b.size) return null
        out.write(b, 0, pos)
        var frames = 0
        while (true) {
            if (pos >= b.size) return null // no trailer: truncated
            when (b[pos].toInt() and 0xFF) {
                0x3B -> {
                    out.write(0x3B)
                    return if (frames > 0) out.toByteArray() else null
                }
                0x21 -> {
                    if (pos + 2 > b.size) return null
                    val label = b[pos + 1].toInt() and 0xFF
                    val end = skipSubBlocks(b, pos + 2) ?: return null
                    if (keepExtension(b, label, pos + 2)) out.write(b, pos, end - pos)
                    pos = end
                }
                0x2C -> {
                    if (pos + 10 > b.size) return null
                    val flags = b[pos + 9].toInt() and 0xFF
                    var p = pos + 10
                    if (flags and 0x80 != 0) p += 3 shl ((flags and 7) + 1)
                    p += 1 // LZW minimum code size
                    if (p > b.size) return null
                    val end = skipSubBlocks(b, p) ?: return null
                    out.write(b, pos, end - pos)
                    pos = end
                    frames++
                }
                else -> return null
            }
        }
    }

    /** From [start] (the first sub-block length byte), returns the index after the 0 terminator, or null if truncated. */
    private fun skipSubBlocks(b: ByteArray, start: Int): Int? {
        var p = start
        while (true) {
            if (p >= b.size) return null
            val n = b[p].toInt() and 0xFF
            p += 1
            if (n == 0) return p
            p += n
            if (p > b.size) return null
        }
    }

    private fun keepExtension(b: ByteArray, label: Int, first: Int): Boolean = when (label) {
        0xF9 -> true
        0xFF -> {
            // First sub-block: size 11 = 8-byte identifier + 3-byte authentication code.
            if (first + 12 > b.size || (b[first].toInt() and 0xFF) != 11) {
                false
            } else {
                val id = String(b, first + 1, 11, Charsets.ISO_8859_1)
                id == "NETSCAPE2.0" || id == "ANIMEXTS1.0"
            }
        }
        else -> false
    }
}
