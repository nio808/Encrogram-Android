package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyTest {
    private val mib = 1024L * 1024L

    @Test
    fun autoDownloadBoundariesAreInclusive() {
        assertTrue(MediaPolicy.autoDownload(MediaKind.IMAGE, 2 * mib))
        assertFalse(MediaPolicy.autoDownload(MediaKind.IMAGE, 2 * mib + 1))
        assertTrue(MediaPolicy.autoDownload(MediaKind.VOICE, 2 * mib))
        assertFalse(MediaPolicy.autoDownload(MediaKind.VOICE, 2 * mib + 1))
        assertTrue(MediaPolicy.autoDownload(MediaKind.GIF, 5 * mib))
        assertFalse(MediaPolicy.autoDownload(MediaKind.GIF, 5 * mib + 1))
        assertTrue(MediaPolicy.autoDownload(MediaKind.VIDEO, 10 * mib))
        assertFalse(MediaPolicy.autoDownload(MediaKind.VIDEO, 10 * mib + 1))
        assertFalse(MediaPolicy.autoDownload(MediaKind.FILE, 1))
    }

    @Test
    fun capIs100MibInclusive() {
        assertTrue(MediaPolicy.withinCap(100 * mib))
        assertFalse(MediaPolicy.withinCap(100 * mib + 1))
        assertFalse(MediaPolicy.withinCap(-1))
        assertFalse(MediaPolicy.autoDownload(MediaKind.VIDEO, 100 * mib + 1))
    }

    @Test
    fun memoryAndReadLimits() {
        assertTrue(MediaPolicy.readable(16 * mib))
        assertFalse(MediaPolicy.readable(16 * mib + 1))
        assertTrue(MediaPolicy.sealInMemory(32 * mib))
        assertFalse(MediaPolicy.sealInMemory(32 * mib + 1))
    }

    @Test
    fun freeSpaceRule() {
        assertEquals(3 * 1000 + 50_000_000L, MediaPolicy.freeSpaceNeeded(1000))
        assertTrue(MediaPolicy.hasFreeSpace(1000, 50_003_000))
        assertFalse(MediaPolicy.hasFreeSpace(1000, 50_002_999))
    }

    @Test
    fun captionLimit() {
        assertTrue(MediaPolicy.captionFits("a".repeat(1024), 8000, "IMG_abcdefgh.jpg", 0))
        assertFalse(MediaPolicy.captionFits("a".repeat(1025), 8000, "IMG_abcdefgh.jpg", 0))
        assertTrue(MediaPolicy.captionFits("漢".repeat(1024), 8000, "IMG_abcdefgh.jpg", 0))
        assertTrue(MediaPolicy.captionFits("😀".repeat(512), 8000, "x", 0))
        assertFalse(MediaPolicy.captionFits("😀".repeat(513), 8000, "x", 0))
        // A large quote leaves no room.
        assertFalse(MediaPolicy.captionFits("漢".repeat(1024), 8000, "x", 4000))
        assertTrue(MediaPolicy.captionFits("hi", 8000, "x", 4000))
    }

    @Test
    fun descriptionOverdueAt72Hours() {
        val h72 = 72L * 3600 * 1000
        assertFalse(MediaPolicy.descriptionOverdue(1_000 + h72 - 1, 1_000))
        assertTrue(MediaPolicy.descriptionOverdue(1_000 + h72, 1_000))
    }
}
