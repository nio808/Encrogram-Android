package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindRulesTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun ftyp(brand: String, vararg compat: String): ByteArray =
        byteArrayOf(0, 0, 0, 24) + ascii("ftyp") + ascii(brand) + ByteArray(4) + compat.fold(ByteArray(0)) { a, c -> a + ascii(c) }
    private fun webp(vp8x: Boolean, flags: Int): ByteArray =
        ascii("RIFF") + ByteArray(4) + ascii("WEBP") + if (vp8x) ascii("VP8X") + ByteArray(4) + byteArrayOf(flags.toByte(), 0, 0, 0) else ascii("VP8 ") + ByteArray(8)

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)

    @Test
    fun sniffsEachFormat() {
        assertEquals(Sniffed.JPEG, MediaKindRules.sniff(jpeg))
        assertEquals(Sniffed.PNG, MediaKindRules.sniff(png))
        assertEquals(Sniffed.GIF, MediaKindRules.sniff(ascii("GIF87a....")))
        assertEquals(Sniffed.GIF, MediaKindRules.sniff(ascii("GIF89a....")))
        assertEquals(Sniffed.WEBP, MediaKindRules.sniff(webp(false, 0)))
        assertEquals(Sniffed.WEBP, MediaKindRules.sniff(webp(true, 0x10)))
        assertEquals(Sniffed.WEBP_ANIMATED, MediaKindRules.sniff(webp(true, 0x12)))
        assertEquals(Sniffed.HEIC, MediaKindRules.sniff(ftyp("heic")))
        assertEquals(Sniffed.HEIC, MediaKindRules.sniff(ftyp("mif1")))
        assertEquals(Sniffed.MP4, MediaKindRules.sniff(ftyp("isom")))
        assertEquals(Sniffed.MP4, MediaKindRules.sniff(ftyp("mp42")))
        assertEquals(Sniffed.MP4, MediaKindRules.sniff(ftyp("zzzz", "isom")))
        assertEquals(Sniffed.MOV, MediaKindRules.sniff(ftyp("qt  ")))
        assertEquals(Sniffed.M4A, MediaKindRules.sniff(ftyp("M4A ")))
        assertEquals(Sniffed.UNKNOWN, MediaKindRules.sniff(ascii("<svg xmlns=")))
        assertEquals(Sniffed.UNKNOWN, MediaKindRules.sniff(ascii("<!DOCTYPE html>")))
        assertEquals(Sniffed.UNKNOWN, MediaKindRules.sniff(ByteArray(0)))
        assertEquals(Sniffed.UNKNOWN, MediaKindRules.sniff(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun kindForSending() {
        assertEquals(MediaKind.IMAGE, MediaKindRules.kindForSending(jpeg, false))
        assertEquals(MediaKind.IMAGE, MediaKindRules.kindForSending(ftyp("heic"), false))
        assertEquals(MediaKind.GIF, MediaKindRules.kindForSending(ascii("GIF89a"), false))
        assertEquals(MediaKind.GIF, MediaKindRules.kindForSending(webp(true, 0x02), false))
        assertEquals(MediaKind.VIDEO, MediaKindRules.kindForSending(ftyp("isom"), false))
        assertEquals(MediaKind.VIDEO, MediaKindRules.kindForSending(ftyp("qt  "), false))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForSending(ascii("<svg>"), false))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForSending(jpeg, true))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForSending(ftyp("isom"), true))
    }

    @Test
    fun kindForReceiving() {
        assertEquals(MediaKind.IMAGE, MediaKindRules.kindForReceiving("image", "a.jpg"))
        assertEquals(MediaKind.GIF, MediaKindRules.kindForReceiving("image", "a.GIF"))
        assertEquals(MediaKind.GIF, MediaKindRules.kindForReceiving("image", "a.webp", webp(true, 0x02)))
        assertEquals(MediaKind.IMAGE, MediaKindRules.kindForReceiving("image", "a.webp", webp(false, 0)))
        assertEquals(MediaKind.VIDEO, MediaKindRules.kindForReceiving("video", "a.mp4"))
        assertEquals(MediaKind.VOICE, MediaKindRules.kindForReceiving("voice", "a.m4a"))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForReceiving("file", "a.gif"))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForReceiving("text", "a"))
        assertEquals(MediaKind.FILE, MediaKindRules.kindForReceiving(null, "a"))
    }

    @Test
    fun mimeMapIsFixed() {
        assertEquals("image/jpeg", MediaKindRules.mimeFor("x.JPG"))
        assertEquals("video/quicktime", MediaKindRules.mimeFor("x.mov"))
        assertEquals("audio/mp4", MediaKindRules.mimeFor("v.m4a"))
        assertEquals("application/octet-stream", MediaKindRules.mimeFor("x.svg"))
        assertEquals("application/octet-stream", MediaKindRules.mimeFor("x.html"))
        assertEquals("application/octet-stream", MediaKindRules.mimeFor("noext"))
    }
}
