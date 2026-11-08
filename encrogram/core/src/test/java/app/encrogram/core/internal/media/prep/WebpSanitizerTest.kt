package app.encrogram.core.internal.media.prep

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpSanitizerTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun chunk(tag: String, payload: ByteArray): ByteArray =
        cat(ascii(tag), le(payload.size), payload, if (payload.size % 2 == 1) ByteArray(1) else ByteArray(0))
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = cat(ascii("WEBP"), *chunks)
        return cat(ascii("RIFF"), le(body.size), body)
    }
    private fun vp8x(flags: Int) = chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0))
    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)

    private val image = chunk("VP8 ", ByteArray(11) { (it + 1).toByte() }) // odd length: padded
    private val exif = chunk("EXIF", ascii("CANARY-GPS-51.5074"))
    private val xmp = chunk("XMP ", ascii("CANARY-CAMERA<x/>"))
    private val icc = chunk("ICCP", ascii("profile-bytes"))

    @Test
    fun dropsMetadataChunksClearsFlagsAndFixesSize() {
        val input = riff(vp8x(0x08 or 0x04 or 0x20 or 0x10), icc, image, exif, xmp)
        val out = WebpSanitizer.sanitize(input)!!
        assertArrayEquals(riff(vp8x(0x10), image), out)
        assertFalse(has(out, "CANARY"))
        val riffSize = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or ((out[6].toInt() and 0xFF) shl 16)
        assertEquals(out.size - 8, riffSize)
        assertNotNull(WebpSanitizer.sanitize(out)) // still parseable
    }

    @Test
    fun keepsAnimationAndAlphaChunks() {
        val anim = chunk("ANIM", ByteArray(6))
        val anmf = chunk("ANMF", ByteArray(20) { 7 })
        val alph = chunk("ALPH", ByteArray(4))
        val input = riff(vp8x(0x02 or 0x10 or 0x08), anim, anmf, alph, image, exif)
        val out = WebpSanitizer.sanitize(input)!!
        assertArrayEquals(riff(vp8x(0x02 or 0x10), anim, anmf, alph, image), out)
        assertTrue(WebpSanitizer.isAnimated(out))
        assertFalse(WebpSanitizer.isAnimated(riff(image)))
        assertTrue(WebpSanitizer.isAnimated(riff(vp8x(0x02), image)))
    }

    @Test
    fun plainLosslessWithoutMetadataIsUnchanged() {
        val input = riff(chunk("VP8L", ByteArray(9)))
        assertArrayEquals(input, WebpSanitizer.sanitize(input))
    }

    @Test
    fun malformedIsNull() {
        assertNull(WebpSanitizer.sanitize(ByteArray(0)))
        assertNull(WebpSanitizer.sanitize(ascii("RIFF0000WAVEfmt 0000000000000000")))
        assertNull(WebpSanitizer.sanitize(riff(vp8x(0), exif))) // no image chunk
        val bad = riff(image).also { it[4] = 0x7F } // size claims more than the data
        assertNull(WebpSanitizer.sanitize(bad))
        assertFalse(WebpSanitizer.isAnimated(bad))
    }

    @Test
    fun truncationsNeverThrow() {
        val input = riff(vp8x(0x2C), icc, image, exif, xmp)
        for (n in 0 until input.size) {
            assertNull("n=$n", WebpSanitizer.sanitize(input.copyOf(n)))
            WebpSanitizer.isAnimated(input.copyOf(n))
        }
    }
}
