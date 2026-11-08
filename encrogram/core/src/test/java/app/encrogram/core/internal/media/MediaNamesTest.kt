package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamesTest {
    private val b32 = "[a-z2-7]{8}"

    @Test
    fun neutralNamesAreRandomAndShaped() {
        assertTrue(Regex("IMG_$b32\\.jpg").matches(MediaNames.neutral(MediaKind.IMAGE)))
        assertTrue(Regex("GIF_$b32\\.gif").matches(MediaNames.neutral(MediaKind.GIF, Sniffed.GIF)))
        assertTrue(Regex("GIF_$b32\\.webp").matches(MediaNames.neutral(MediaKind.GIF, Sniffed.WEBP_ANIMATED)))
        assertTrue(Regex("video_$b32\\.mp4").matches(MediaNames.neutral(MediaKind.VIDEO, Sniffed.MP4)))
        assertTrue(Regex("video_$b32\\.mov").matches(MediaNames.neutral(MediaKind.VIDEO, Sniffed.MOV)))
        assertTrue(Regex("voice_$b32\\.m4a").matches(MediaNames.neutral(MediaKind.VOICE)))
        val set = (1..200).map { MediaNames.neutral(MediaKind.IMAGE) }.toSet()
        assertTrue(set.size > 195)
    }

    @Test
    fun neutralNamesCarryNoDigitsOfTheClock() {
        // No yyyyMMdd or HHmmss pattern: the only digits possible are base32's 2-7 inside the random part.
        assertFalse(Regex("\\d{8}").containsMatchIn(MediaNames.neutral(MediaKind.IMAGE).substringBefore('.')))
        assertFalse(MediaNames.neutral(MediaKind.IMAGE).contains(java.time.Year.now().toString()))
    }

    @Test
    fun sanitizeStripsPathControlAndBidi() {
        assertEquals("..etcpasswd", MediaNames.sanitizeDocumentName("../etc/passwd"))
        assertEquals("abc.txt", MediaNames.sanitizeDocumentName("a/b\\c\u0000.txt"))
        assertEquals("ab.pdf", MediaNames.sanitizeDocumentName("a\u202Eb\u200F.pdf"))
        assertEquals("fdp.exe", MediaNames.sanitizeDocumentName("f\u202Ed\u0007p.exe"))
        assertEquals("ab", MediaNames.sanitizeDocumentName("a\nb\r\u0085"))
        assertEquals("a.png", MediaNames.sanitizeDocumentName("a\u2066\u2069.png"))
    }

    @Test
    fun sanitizeKeepsBmpOnlyAndFallsBack() {
        assertEquals("ab", MediaNames.sanitizeDocumentName("a😀b"))
        assertEquals("файл.pdf", MediaNames.sanitizeDocumentName("файл.pdf"))
        assertEquals("file", MediaNames.sanitizeDocumentName(""))
        assertEquals("file", MediaNames.sanitizeDocumentName("///"))
        assertEquals("file", MediaNames.sanitizeDocumentName("😀"))
        assertEquals("file", MediaNames.sanitizeDocumentName(".."))
    }

    @Test
    fun sanitizeLimitsBytesKeepingExtension() {
        val long = "a".repeat(300) + ".pdf"
        val out = MediaNames.sanitizeDocumentName(long)
        assertEquals(200, out.toByteArray(Charsets.UTF_8).size)
        assertTrue(out.endsWith(".pdf"))
        val cjk = "漢".repeat(100) + ".docx"
        val out2 = MediaNames.sanitizeDocumentName(cjk)
        assertTrue(out2.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(out2.endsWith(".docx"))
        assertEquals("a".repeat(196) + ".pdf", MediaNames.sanitizeDocumentName("a".repeat(196) + ".pdf"))
    }

    @Test
    fun uniqueAddsSuffixBeforeExtension() {
        val taken = mutableSetOf("a.pdf", "a_1.pdf")
        assertEquals("b.pdf", MediaNames.unique("b.pdf") { it in taken })
        assertEquals("a_2.pdf", MediaNames.unique("a.pdf") { it in taken })
        val t2 = mutableSetOf("noext", "noext_1")
        assertEquals("noext_2", MediaNames.unique("noext") { it in t2 })
        assertNotEquals("a.pdf", MediaNames.unique("a.pdf") { true })
    }

    @Test
    fun jniSafe() {
        assertTrue(MediaNames.jniSafe("/data/files/файл.pdf"))
        assertFalse(MediaNames.jniSafe("a\u0000b"))
        assertFalse(MediaNames.jniSafe("a😀b"))
        assertFalse(MediaNames.jniSafe("a\uD800b"))
    }
}
