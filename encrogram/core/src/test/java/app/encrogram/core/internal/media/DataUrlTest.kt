package app.encrogram.core.internal.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DataUrlTest {

    @Test
    fun roundTrip() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, -1, -128)
        val url = DataUrl.encodeJpeg(bytes)
        assertTrue(url.startsWith("data:image/jpg;base64,"))
        assertArrayEquals(bytes, DataUrl.decode(url))
        assertTrue(DataUrl.encodePng(bytes).startsWith("data:image/png;base64,"))
        assertArrayEquals(bytes, DataUrl.decode(DataUrl.encodePng(bytes)))
    }

    @Test
    fun jpgJpegAndPngAreAccepted() {
        assertNotNull(DataUrl.decode("data:image/jpg;base64,QUJD"))
        assertNotNull(DataUrl.decode("data:image/jpeg;base64,QUJD"))
        assertNotNull(DataUrl.decode("data:image/png;base64,QUJD"))
    }

    @Test
    fun everythingElseIsRefused() {
        assertNull(DataUrl.decode("data:image/svg+xml;base64,QUJD"))
        assertNull(DataUrl.decode("data:text/html;base64,QUJD"))
        assertNull(DataUrl.decode("data:image/gif;base64,QUJD"))
        assertNull(DataUrl.decode("data:image/webp;base64,QUJD"))
        assertNull(DataUrl.decode("data:image/jpg,QUJD"))
        assertNull(DataUrl.decode("DATA:image/jpg;base64,QUJD"))
        assertNull(DataUrl.decode("data:image/jpg;base64,"))
        assertNull(DataUrl.decode("data:image/jpg;base64,!!!notbase64"))
        assertNull(DataUrl.decode("data:image/jpg;base64,QU JD"))
        assertNull(DataUrl.decode(""))
        assertNull(DataUrl.decode(null))
    }

    @Test
    fun overTwentyThousandCharactersIsRefused() {
        val ok = "data:image/jpg;base64," + "A".repeat(DataUrl.MAX_CHARS - 22)
        assertEquals(DataUrl.MAX_CHARS, ok.length)
        assertNotNull(DataUrl.decode(ok))
        assertNull(DataUrl.decode(ok + "AAAA"))
        // 16 KB of bytes is about 21,800 characters
        assertNull(DataUrl.decode(DataUrl.encodeJpeg(ByteArray(16 * 1024 + 1))))
    }

    @Test
    fun placeholderIsAValidSmallGreyJpegWithNoMetadata() {
        val bytes = DataUrl.decode(DataUrl.PLACEHOLDER)!!
        assertEquals(ImageHeaders.Format.JPEG, ImageHeaders.format(bytes))
        assertEquals(ImageHeaders.Size(16, 16), ImageHeaders.size(bytes))
        assertFalse(ImageHeaders.hasMetadata(bytes))
        assertTrue(DataUrl.PLACEHOLDER.length < 2000)
        assertArrayEquals(bytes, Base64.getDecoder().decode(DataUrl.PLACEHOLDER.removePrefix(DataUrl.JPEG_PREFIX)))
    }

    @Test
    fun prefixCheck() {
        assertTrue(DataUrl.hasAcceptedPrefix("data:image/png;base64,xx"))
        assertFalse(DataUrl.hasAcceptedPrefix("data:image/svg+xml;base64,xx"))
        assertFalse(DataUrl.hasAcceptedPrefix(null))
    }
}
