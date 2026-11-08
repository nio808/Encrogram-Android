package app.encrogram.core.internal.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ImageHeadersTest {

    private val jpeg: ByteArray = DataUrl.decode(DataUrl.PLACEHOLDER)!!
    private val png: ByteArray = Base64.getDecoder().decode(MediaSamples.PNG_B64)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun le32(n: Int) = bytes(n and 0xFF, (n shr 8) and 0xFF, (n shr 16) and 0xFF, (n shr 24) and 0xFF)

    private fun riff(chunk: String, payload: ByteArray): ByteArray {
        val body = ascii("WEBP") + ascii(chunk) + le32(payload.size) + payload
        return ascii("RIFF") + le32(body.size) + body
    }

    /** [segment] inserted straight after the SOI marker. */
    private fun withSegmentAfterSoi(segment: ByteArray) = jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)

    private fun app(marker: Int, payload: String): ByteArray {
        val p = ascii(payload)
        val len = p.size + 2
        return bytes(0xFF, marker, len shr 8, len and 0xFF) + p
    }

    // ---- size ----------------------------------------------------------------------------------------------------------

    @Test
    fun jpegSize() {
        assertEquals(ImageHeaders.Format.JPEG, ImageHeaders.format(jpeg))
        assertEquals(ImageHeaders.Size(16, 16), ImageHeaders.size(jpeg))
    }

    @Test
    fun pngSize() {
        assertEquals(ImageHeaders.Format.PNG, ImageHeaders.format(png))
        assertEquals(ImageHeaders.Size(16, 16), ImageHeaders.size(png))
    }

    @Test
    fun hugePngHeaderIsReadNotTrusted() {
        val big = png.copyOf(33)
        // width and height 30000 (0x7530) at offsets 16 and 20
        big[18] = 0x75; big[19] = 0x30; big[22] = 0x75; big[23] = 0x30
        assertEquals(ImageHeaders.Size(30000, 30000), ImageHeaders.size(big))
    }

    @Test
    fun webpLossy() {
        val p = bytes(0, 0, 0, 0x9D, 0x01, 0x2A) + bytes(100, 0, 50, 0)
        assertEquals(ImageHeaders.Size(100, 50), ImageHeaders.size(riff("VP8 ", p + ByteArray(8))))
    }

    @Test
    fun webpLossless() {
        // width 300, height 200: stored minus one in 14 bits each
        val w = 299
        val h = 199
        val b0 = w and 0xFF
        val b1 = ((w shr 8) and 0x3F) or ((h and 0x3) shl 6)
        val b2 = (h shr 2) and 0xFF
        val b3 = (h shr 10) and 0x0F
        val p = bytes(0x2F, b0, b1, b2, b3) + ByteArray(8)
        assertEquals(ImageHeaders.Size(300, 200), ImageHeaders.size(riff("VP8L", p)))
    }

    @Test
    fun webpExtended() {
        val p = bytes(0x10, 0, 0, 0) + bytes(0x2F, 0x01, 0x00) + bytes(0xC7, 0x00, 0x00) + ByteArray(4)
        assertEquals(ImageHeaders.Size(304, 200), ImageHeaders.size(riff("VP8X", p)))
    }

    @Test
    fun cutOrUnknownInputGivesNull() {
        assertNull(ImageHeaders.size(ByteArray(0)))
        assertNull(ImageHeaders.size(jpeg.copyOf(10)))
        assertNull(ImageHeaders.size(png.copyOf(20)))
        assertNull(ImageHeaders.size(ascii("GIF89a000000000000000000")))
        assertNull(ImageHeaders.size(riff("VP8 ", ByteArray(4))))
        assertNull(ImageHeaders.format(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
    }

    // ---- metadata ------------------------------------------------------------------------------------------------------

    @Test
    fun aCleanJpegHasNone() {
        assertFalse(ImageHeaders.hasMetadata(jpeg))
    }

    @Test
    fun exifXmpIptcAndCommentsAreFound() {
        assertTrue(ImageHeaders.hasMetadata(withSegmentAfterSoi(app(0xE1, "Exif\u0000\u0000CANARY"))))
        assertTrue(ImageHeaders.hasMetadata(withSegmentAfterSoi(app(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x/>"))))
        assertTrue(ImageHeaders.hasMetadata(withSegmentAfterSoi(app(0xED, "Photoshop 3.0\u0000"))))
        assertTrue(ImageHeaders.hasMetadata(withSegmentAfterSoi(app(0xFE, "CANARY comment"))))
        // other APP segments (ICC, Adobe) are not metadata we strip
        assertFalse(ImageHeaders.hasMetadata(withSegmentAfterSoi(app(0xE2, "ICC_PROFILE\u0000"))))
    }

    @Test
    fun aCommentAfterTheScanIsFound() {
        val tail = jpeg.copyOfRange(jpeg.size - 2, jpeg.size)
        assertEquals(0xFF.toByte(), tail[0])
        assertEquals(0xD9.toByte(), tail[1])
        val dirty = jpeg.copyOfRange(0, jpeg.size - 2) + app(0xFE, "CANARY") + tail
        assertTrue(ImageHeaders.hasMetadata(dirty))
    }

    @Test
    fun bytesAfterEoiAreFoundButZeroPaddingIsNot() {
        assertTrue(ImageHeaders.hasMetadata(jpeg + ascii("CANARY")))
        assertFalse(ImageHeaders.hasMetadata(jpeg + ByteArray(4)))
    }

    @Test
    fun malformedFailsClosed() {
        assertTrue(ImageHeaders.hasMetadata(ByteArray(0)))
        assertTrue(ImageHeaders.hasMetadata(png))
        assertTrue("cut before EOI", ImageHeaders.hasMetadata(jpeg.copyOf(jpeg.size - 2)))
        assertTrue("cut in the middle", ImageHeaders.hasMetadata(jpeg.copyOf(40)))
        for (n in 3 until jpeg.size - 2 step 7) assertTrue("cut at $n", ImageHeaders.hasMetadata(jpeg.copyOf(n)))
    }
}
