package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaState
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.MediaSamples.NAME
import app.encrogram.core.internal.media.MediaSamples.PATH
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class MediaMapperTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun item(
        content: String,
        file: String?,
        dir: String = "directRcv",
        status: String = "rcvNew",
        itemText: String = "",
        deleted: Boolean = false,
    ): CoreItem = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, dir, content, file, status, itemText, deleted)))!!

    @Test
    fun imageWithPreviewAndWaitingState() {
        val i = item(
            MediaSamples.content("image", "look", MediaSamples.PNG_URL),
            MediaSamples.file(name = "IMG_ABCDEFGH.jpg", size = 200_000),
        )
        val m = MediaMapper.map(i)!!
        assertEquals(MediaKind.IMAGE, m.kind)
        assertEquals(MediaState.WAITING, m.state)
        assertNull(m.failure)
        assertEquals(200_000L, m.sizeBytes)
        assertEquals("image/jpeg", m.mimeType)
        assertEquals(Base64.getDecoder().decode(MediaSamples.PNG_B64).toList(), m.preview!!.bytes().toList())
        assertFalse(m.readable)
    }

    @Test
    fun gifVideoVoiceAndFile() {
        assertEquals(MediaKind.GIF, MediaMapper.map(item(MediaSamples.content("image"), MediaSamples.file(name = "GIF_ABCDEFGH.gif")))!!.kind)
        val v = MediaMapper.map(item(MediaSamples.content("video", "", MediaSamples.PNG_URL, 14), MediaSamples.file(name = "video_ABCDEFGH.mp4")))!!
        assertEquals(MediaKind.VIDEO, v.kind)
        assertEquals(14, v.durationSeconds)
        assertNotNull(v.preview)
        assertEquals("video/mp4", v.mimeType)
        val voice = MediaMapper.map(item(MediaSamples.content("voice", "", null, 12), MediaSamples.file(name = "voice_ABCDEFGH.m4a")))!!
        assertEquals(MediaKind.VOICE, voice.kind)
        assertEquals(12, voice.durationSeconds)
        assertNull(voice.preview)
        val f = MediaMapper.map(item(MediaSamples.content("file", "cap"), MediaSamples.file()))!!
        assertEquals(MediaKind.FILE, f.kind)
        assertEquals(NAME, f.name)
        assertNull(f.mimeType)
        assertNull(f.preview)
    }

    @Test
    fun aVoicePreviewFieldIsIgnored() {
        val m = MediaMapper.map(item(MediaSamples.content("voice", "", MediaSamples.PNG_URL, 3), MediaSamples.file(name = "voice_ABCDEFGH.m4a")))!!
        assertNull(m.preview)
    }

    @Test
    fun noMediaForTextDeletedOrServiceItems() {
        val text = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, "directRcv", MediaSamples.content("text", "hi"), null, "rcvNew")))!!
        assertNull(MediaMapper.map(text))
        val deleted = item(MediaSamples.content("image", "gone", MediaSamples.PNG_URL), MediaSamples.file(), deleted = true)
        assertNull(deleted.file)
        assertNull(deleted.preview)
        assertEquals("", deleted.text)
        assertNull(MediaMapper.map(deleted))
        assertNull(MediaMapper.kindOf(deleted))
    }

    @Test
    fun aMediaMessageWithoutItsFileShowsNotOnThisPhone() {
        val m = MediaMapper.map(item(MediaSamples.content("image", "x"), null))!!
        assertEquals(MediaKind.IMAGE, m.kind)
        assertEquals(MediaState.FAILED, m.state)
        assertEquals(MediaFailure.NOT_ON_DEVICE, m.failure)
    }

    @Test
    fun badPreviewsAreDropped() {
        fun preview(url: String) = MediaMapper.map(item(MediaSamples.content("image", "", url), MediaSamples.file(name = "a.jpg")))!!.preview
        assertNotNull(preview(MediaSamples.PNG_URL))
        assertNull(preview("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="))
        assertNull(preview("data:text/html;base64,PGh0bWw+"))
        assertNull(preview("data:image/jpg;base64,!!!"))
        assertNull("not an image behind a jpg prefix", preview("data:image/jpg;base64,QUJDREVGR0hJSktM"))
        assertNull("more than 16 KB", preview(DataUrl.encodeJpeg(ByteArray(17_000))))
        // a PNG header that claims 30000 x 30000
        val big = Base64.getDecoder().decode(MediaSamples.PNG_B64).copyOf(33)
        big[18] = 0x75; big[19] = 0x30; big[22] = 0x75; big[23] = 0x30
        assertNull(preview(DataUrl.encodePng(big)))
        // a WebP behind a png prefix is not a preview either
        val webp = "RIFF".toByteArray() + byteArrayOf(40, 0, 0, 0) + "WEBPVP8X".toByteArray() + ByteArray(30)
        assertNull(preview(DataUrl.encodePng(webp)))
    }

    @Test
    fun displayNamesAreSafe() {
        assertEquals("evilexe.txt", MediaMapper.displayName("evil‮exe.txt"))
        assertEquals("x", MediaMapper.displayName("../../x"))
        assertEquals("x", MediaMapper.displayName("..\\..\\x"))
        assertEquals("ab", MediaMapper.displayName("a\u0000b"))
        assertEquals("file", MediaMapper.displayName(""))
        assertEquals("file", MediaMapper.displayName(".."))
        assertEquals("file", MediaMapper.displayName("‮\u0000"))
        assertEquals("Фото 😀.jpg", MediaMapper.displayName("Фото 😀.jpg"))
        val long = MediaMapper.displayName("é".repeat(300) + ".pdf")
        assertTrue(long.toByteArray().size <= 200)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun displayNameReachesTheContract() {
        val m = MediaMapper.map(item(MediaSamples.content("file"), MediaSamples.file(name = "a\\u202Eb.txt")))!!
        assertEquals("ab.txt", m.name)
    }

    @Test
    fun quoteTextStripsOurNeutralNames() {
        assertEquals("", MediaMapper.quoteText("IMG_ABCD2345.jpg"))
        assertEquals("", MediaMapper.quoteText("GIF_ABCD2345.gif"))
        assertEquals("", MediaMapper.quoteText("GIF_ABCD2345.webp"))
        assertEquals("", MediaMapper.quoteText("video_ABCD2345.mp4"))
        assertEquals("", MediaMapper.quoteText("voice_ABCD2345.m4a"))
        assertEquals("", MediaMapper.quoteText("  IMG_ABCD2345.jpg "))
        // someone else's names and real text stay
        assertEquals("IMG_1234.jpg", MediaMapper.quoteText("IMG_1234.jpg"))
        assertEquals("IMG_ABCD2345.jpg is mine", MediaMapper.quoteText("IMG_ABCD2345.jpg is mine"))
        assertEquals("IMG_ABCD2341.jpg", MediaMapper.quoteText("IMG_ABCD2341.jpg"))
        assertEquals("report.pdf", MediaMapper.quoteText("report.pdf"))
        assertEquals("hello", MediaMapper.quoteText("hello"))
    }

    @Test
    fun toStringNeverShowsNameOrPath() {
        val i = item(MediaSamples.content("file"), MediaSamples.file(status = MediaSamples.status("rcvComplete"), source = true))
        val m = MediaMapper.map(i)!!
        for (s in listOf(m.toString(), i.file.toString(), i.file!!.key.toString())) {
            for (secret in listOf(NAME, PATH, MediaSamples.KEY, MediaSamples.NONCE)) assertFalse("$s has $secret", s.contains(secret))
        }
    }
}
