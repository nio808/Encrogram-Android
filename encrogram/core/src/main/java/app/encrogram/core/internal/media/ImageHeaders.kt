package app.encrogram.core.internal.media

/**
 * Image facts read from header bytes only (no decoding): format, width and height of JPEG, PNG and WebP, and whether a
 * JPEG carries metadata. Pure JVM. Every read is bounds checked; malformed input gives null (never throws).
 */
internal object ImageHeaders {

    enum class Format { JPEG, PNG, WEBP }

    data class Size(val width: Int, val height: Int)

    fun format(b: ByteArray): Format? = when {
        b.size >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF -> Format.JPEG
        b.size >= 8 && u(b, 0) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            u(b, 4) == 0x0D && u(b, 5) == 0x0A && u(b, 6) == 0x1A && u(b, 7) == 0x0A -> Format.PNG
        b.size >= 16 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP") -> Format.WEBP
        else -> null
    }

    /** Width and height from the header, both above zero; null when the format is unknown or the header is cut or odd. */
    fun size(b: ByteArray): Size? {
        val s = when (format(b)) {
            Format.JPEG -> jpegSize(b)
            Format.PNG -> pngSize(b)
            Format.WEBP -> webpSize(b)
            null -> null
        }
        return s?.takeIf { it.width > 0 && it.height > 0 }
    }

    // ---- JPEG ------------------------------------------------------------------------------------------------------

    private fun jpegSize(b: ByteArray): Size? {
        var i = 2
        while (i + 4 <= b.size) {
            if (u(b, i) != 0xFF) return null
            val m = u(b, i + 1)
            if (m == 0xFF) { i++; continue }
            if (m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xD9 || m == 0xDA) return null // end or scan data before any frame header
            val len = (u(b, i + 2) shl 8) or u(b, i + 3)
            if (len < 2 || i + 2 + len > b.size) return null
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (len < 8) return null
                return Size((u(b, i + 7) shl 8) or u(b, i + 8), (u(b, i + 5) shl 8) or u(b, i + 6))
            }
            i += 2 + len
        }
        return null
    }

    /**
     * True when the JPEG has an APP1 segment (Exif, XMP), an APP13 segment (Photoshop/IPTC) or a COM comment, or non-zero bytes after EOI, anywhere
     * in the file (also between progressive scans). Fails closed: input that is not a well-formed JPEG counts as
     * "has metadata", so it can never pass as clean.
     */
    fun hasMetadata(jpeg: ByteArray): Boolean {
        if (format(jpeg) != Format.JPEG) return true
        var i = 2
        while (true) {
            // find the next marker
            if (i + 2 > jpeg.size) return true // cut before EOI
            if (u(jpeg, i) != 0xFF) return true
            val m = u(jpeg, i + 1)
            if (m == 0xFF) { i++; continue }
            if (m == 0x00) return true
            if (m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xD9) return (i + 2 until jpeg.size).any { jpeg[it].toInt() != 0 } // bytes after EOI hide data
            if (m == 0xE1 || m == 0xED || m == 0xFE) return true
            if (i + 4 > jpeg.size) return true
            val len = (u(jpeg, i + 2) shl 8) or u(jpeg, i + 3)
            if (len < 2 || i + 2 + len > jpeg.size) return true
            i += 2 + len
            if (m == 0xDA) {
                // entropy-coded data: skip to the next real marker (not 00 stuffing, not RSTn, not fill)
                while (true) {
                    if (i + 2 > jpeg.size) return true
                    if (u(jpeg, i) == 0xFF) {
                        val n = u(jpeg, i + 1)
                        if (n != 0x00 && n != 0xFF && n !in 0xD0..0xD7) break
                        i += if (n == 0xFF) 1 else 2
                    } else {
                        i++
                    }
                }
            }
        }
    }

    // ---- PNG -------------------------------------------------------------------------------------------------------

    private fun pngSize(b: ByteArray): Size? {
        if (b.size < 24 || be32(b, 8) != 13L || !ascii(b, 12, "IHDR")) return null
        val w = be32(b, 16)
        val h = be32(b, 20)
        if (w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
        return Size(w.toInt(), h.toInt())
    }

    // ---- WebP ------------------------------------------------------------------------------------------------------

    private fun webpSize(b: ByteArray): Size? {
        if (b.size < 30) return null
        return when {
            ascii(b, 12, "VP8 ") -> {
                // frame tag (3 bytes), start code 9D 01 2A, 14-bit width and height
                if (u(b, 23) != 0x9D || u(b, 24) != 0x01 || u(b, 25) != 0x2A) return null
                Size(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
            }
            ascii(b, 12, "VP8L") -> {
                if (u(b, 20) != 0x2F) return null
                val b0 = u(b, 21); val b1 = u(b, 22); val b2 = u(b, 23); val b3 = u(b, 24)
                Size(1 + (b0 or ((b1 and 0x3F) shl 8)), 1 + ((b1 shr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10)))
            }
            ascii(b, 12, "VP8X") -> Size(1 + le24(b, 24), 1 + le24(b, 27))
            else -> null
        }
    }

    // ---- bytes -----------------------------------------------------------------------------------------------------

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int): Int = u(b, i) or (u(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int): Int = u(b, i) or (u(b, i + 1) shl 8) or (u(b, i + 2) shl 16)
    private fun be32(b: ByteArray, i: Int): Long =
        (u(b, i).toLong() shl 24) or (u(b, i + 1).toLong() shl 16) or (u(b, i + 2).toLong() shl 8) or u(b, i + 3).toLong()

    private fun ascii(b: ByteArray, at: Int, s: String): Boolean {
        if (at + s.length > b.size) return false
        for (k in s.indices) if (b[at + k] != s[k].code.toByte()) return false
        return true
    }
}
