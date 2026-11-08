package app.encrogram.core.internal.media.prep

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GifSanitizerTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun ints(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val head = cat(ascii("GIF89a"), ints(1, 0, 1, 0, 0x80, 0, 0), ints(0, 0, 0, 255, 255, 255))
    private val netscape = cat(ints(0x21, 0xFF, 0x0B), ascii("NETSCAPE2.0"), ints(3, 1, 0, 0, 0))
    private val gce = ints(0x21, 0xF9, 4, 0, 10, 0, 0, 0)
    private val image = cat(ints(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0), ints(2, 2, 0x4C, 0x01, 0))
    private val comment = cat(ints(0x21, 0xFE, 21), ascii("CANARY-GPS-51.5074 xx"), ints(0))
    private val plain = cat(ints(0x21, 0x01, 3, 1, 2, 3, 0))
    private val xmp = cat(ints(0x21, 0xFF, 0x0B), ascii("XMP DataXMP"), ints(14), ascii("CANARY-CAMERA!"), ints(0))
    private val trailer = ints(0x3B)

    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)

    @Test
    fun removesCommentPlainTextAndXmpKeepsLoopAndImage() {
        val input = cat(head, comment, netscape, plain, xmp, gce, image, trailer)
        val out = GifSanitizer.sanitize(input)!!
        assertArrayEquals(cat(head, netscape, gce, image, trailer), out)
        assertFalse(has(out, "CANARY"))
    }

    @Test
    fun keepsAnimexts() {
        val anim = cat(ints(0x21, 0xFF, 0x0B), ascii("ANIMEXTS1.0"), ints(3, 1, 0, 0, 0))
        val input = cat(head, anim, gce, image, trailer)
        assertArrayEquals(input, GifSanitizer.sanitize(input))
    }

    @Test
    fun multipleFramesAndLocalColourTable() {
        val local = cat(ints(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x80), ints(1, 2, 3, 4, 5, 6), ints(2, 1, 0x44, 0))
        val input = cat(head, gce, image, comment, gce, local, trailer)
        assertArrayEquals(cat(head, gce, image, gce, local, trailer), GifSanitizer.sanitize(input))
    }

    @Test
    fun malformedIsNull() {
        assertNull(GifSanitizer.sanitize(ByteArray(0)))
        assertNull(GifSanitizer.sanitize(ascii("NOTAGIF89a0000000000000")))
        assertNull(GifSanitizer.sanitize(cat(head, ints(0x55), trailer))) // unknown block
        assertNull(GifSanitizer.sanitize(cat(head, trailer))) // no frames
        assertNull(GifSanitizer.sanitize(cat(head, gce, image))) // no trailer
    }

    @Test
    fun truncationsNeverThrow() {
        val input = cat(head, comment, netscape, xmp, gce, image, trailer)
        for (n in 0..input.size) {
            val r = GifSanitizer.sanitize(input.copyOf(n))
            if (n < input.size) assertNull("n=$n", r) else assertNotNull(r)
        }
    }
}
