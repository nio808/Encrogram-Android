package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
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

class MediaJsonTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun obj(s: String) = JSONObject(s)
    private fun parse(file: String, type: String = "file", name: String? = null) =
        MediaJson.parseFile(obj(file), obj(MediaSamples.content(type))).also { assertNotNull(name ?: "x", it) }!!

    private fun withStatus(status: String, source: Boolean = false, protocol: String = "xftp") =
        parse(MediaSamples.file(status = status, source = source, protocol = protocol))

    @Test
    fun allFifteenFileStatusTypes() {
        val simple = listOf(
            "sndStored", "sndCancelled", "sndComplete", "rcvInvitation", "rcvAccepted", "rcvAborted", "rcvComplete",
            "rcvCancelled", "invalid",
        )
        for (t in simple) assertEquals(t, t, withStatus(MediaSamples.status(t)).statusTag)

        val snd = withStatus(MediaSamples.status("sndTransfer", """"sndProgress":40,"sndTotal":100"""))
        assertEquals("sndTransfer", snd.statusTag)
        assertEquals(40L, snd.progressDone)
        assertEquals(100L, snd.progressTotal)

        val rcv = withStatus(MediaSamples.status("rcvTransfer", """"rcvProgress":7,"rcvTotal":9"""))
        assertEquals(7L, rcv.progressDone)
        assertEquals(9L, rcv.progressTotal)

        val sndErr = withStatus(MediaSamples.status("sndError", """"sndFileError":{"type":"auth"}"""))
        assertEquals("sndError", sndErr.statusTag)
        assertEquals("auth", sndErr.errorTag)
        val sndWarn = withStatus(MediaSamples.status("sndWarning", """"sndFileError":{"type":"relay","srvError":{"type":"host"}}"""))
        assertEquals("relay", sndWarn.errorTag)
        val rcvErr = withStatus(MediaSamples.status("rcvError", """"rcvFileError":{"type":"noFile"}"""))
        assertEquals("noFile", rcvErr.errorTag)
        val rcvWarn = withStatus(MediaSamples.status("rcvWarning", """"rcvFileError":{"type":"blocked","server":"smp://x","blockInfo":{}}"""))
        assertEquals("blocked", rcvWarn.errorTag)
        // 9 + 2 + 4 = 15 tags covered
    }

    @Test
    fun protocols() {
        for (p in listOf("xftp", "smp", "local")) assertEquals(p, withStatus(MediaSamples.status("rcvInvitation"), protocol = p).protocol)
    }

    @Test
    fun kindsFromContentType() {
        assertEquals(MediaKind.IMAGE, MediaJson.parseFile(obj(MediaSamples.file(name = "IMG_ABCDEFGH.jpg")), obj(MediaSamples.content("image")))!!.kind)
        assertEquals(MediaKind.GIF, MediaJson.parseFile(obj(MediaSamples.file(name = "GIF_ABCDEFGH.gif")), obj(MediaSamples.content("image")))!!.kind)
        assertEquals(MediaKind.GIF, MediaJson.parseFile(obj(MediaSamples.file(name = "x.WEBP")), obj(MediaSamples.content("image")))!!.kind)
        assertEquals(MediaKind.VIDEO, MediaJson.parseFile(obj(MediaSamples.file()), obj(MediaSamples.content("video", duration = 3)))!!.kind)
        assertEquals(MediaKind.VOICE, MediaJson.parseFile(obj(MediaSamples.file()), obj(MediaSamples.content("voice", duration = 3)))!!.kind)
        assertEquals(MediaKind.FILE, MediaJson.parseFile(obj(MediaSamples.file()), obj(MediaSamples.content("file")))!!.kind)
        assertNull(MediaJson.parseFile(obj(MediaSamples.file()), obj(MediaSamples.content("text"))))
        assertNull(MediaJson.parseFile(obj(MediaSamples.file()), obj(MediaSamples.content("link"))))
        assertNull(MediaJson.parseFile(null, obj(MediaSamples.content("image"))))
        assertNull(MediaJson.parseFile(obj(MediaSamples.file()), null))
    }

    @Test
    fun cryptoArgsBecomeAKeyThatNeverPrints() {
        val f = withStatus(MediaSamples.status("rcvComplete"), source = true)
        assertEquals(PATH, f.path)
        assertEquals(FileKey(MediaSamples.KEY, MediaSamples.NONCE), f.key)
        assertFalse(f.key.toString().contains("CANARY"))
        val s = f.toString()
        for (secret in listOf(NAME, PATH, MediaSamples.KEY, MediaSamples.NONCE)) assertFalse(s, s.contains(secret))
        assertNull(withStatus(MediaSamples.status("rcvInvitation")).path)
        assertNull(withStatus(MediaSamples.status("rcvInvitation")).key)
    }

    @Test
    fun otherErrorsBecomeHintsAndTheTextIsDropped() {
        fun hint(text: String): FileErrorHint? {
            val f = withStatus(MediaSamples.status("sndError", """"sndFileError":{"type":"other","fileError":"$text"}"""))
            assertEquals("other", f.errorTag)
            val s = f.toString()
            assertFalse(s.contains("relay.example"))
            return f.errorHint
        }
        assertEquals(FileErrorHint.QUOTA, hint("XFTP smp://relay.example xftpErr = QUOTA"))
        assertEquals(FileErrorHint.SIZE, hint("xftpErr = SIZE at relay.example"))
        assertEquals(FileErrorHint.DIGEST, hint("file digest"))
        assertNull(hint("connect to relay.example failed"))
        assertNull("SIZE only as a whole word", hint("BIGSIZE and SIZES and QUOTAS"))
        assertNull(MediaJson.hintOf(null))
        // a hint only comes from `other`
        val relay = withStatus(MediaSamples.status("rcvError", """"rcvFileError":{"type":"relay","fileError":"QUOTA"}"""))
        assertNull(relay.errorHint)
    }

    @Test
    fun previewIsOnlyAJpgOrPngDataUrlUpTo20000Chars() {
        fun p(url: String) = MediaJson.preview(obj(MediaSamples.content("image", image = url)))
        assertEquals(MediaSamples.PNG_URL, p(MediaSamples.PNG_URL))
        assertNotNull(p("data:image/jpg;base64,QUJD"))
        assertNotNull(p("data:image/jpeg;base64,QUJD"))
        assertNull(p("data:image/svg+xml;base64,QUJD"))
        assertNull(p("data:text/html;base64,QUJD"))
        assertNull(p("https://example.com/x.jpg"))
        assertNull(p("data:image/jpg;base64," + "A".repeat(20_000)))
        assertNotNull(p("data:image/jpg;base64," + "A".repeat(20_000 - 22)))
        assertNull(MediaJson.preview(obj(MediaSamples.content("image"))))
        assertNull(MediaJson.preview(null))
    }

    @Test
    fun durations() {
        assertEquals(12, MediaJson.duration(obj(MediaSamples.content("voice", duration = 12))))
        assertEquals(0, MediaJson.duration(obj(MediaSamples.content("voice", duration = 0))))
        assertNull(MediaJson.duration(obj(MediaSamples.content("voice"))))
        assertNull(MediaJson.duration(obj("""{"type":"voice","text":"","duration":-5}""")))
        assertNull(MediaJson.duration(null))
    }

    @Test
    fun theFifteenEvents() {
        assertEquals(15, MediaJson.EVENTS.size)
        for (t in MediaJson.EVENTS) assertNotNull(t, MediaJson.event(t, obj(MediaSamples.event(t, MediaSamples.item(3, "directRcv", MediaSamples.content("file"), MediaSamples.file(), "rcvNew")))))
        assertNull(MediaJson.event("newChatItems", obj("""{"type":"newChatItems"}""")))
    }

    private fun anItem(status: String = MediaSamples.status("rcvAccepted")) =
        MediaSamples.item(3, "directRcv", MediaSamples.content("file"), MediaSamples.file(status = status), "rcvNew")

    @Test
    fun progressUsesReceivedOrSentSizeAndTotal() {
        val rcv = MediaJson.event("rcvFileProgressXFTP", obj(MediaSamples.event("rcvFileProgressXFTP", anItem(), extra = """"receivedSize":300,"totalSize":900""")))
        rcv as MediaEvent.Progress
        assertEquals(5L, rcv.chatId)
        assertEquals(3L, rcv.itemId)
        assertEquals(300L, rcv.done)
        assertEquals(900L, rcv.total)

        val snd = MediaJson.event("sndFileProgressXFTP", obj(MediaSamples.event("sndFileProgressXFTP", anItem(), extra = """"sentSize":10,"totalSize":20""")))
        snd as MediaEvent.Progress
        assertEquals(10L, snd.done)
        assertEquals(20L, snd.total)
    }

    @Test
    fun nullChatItemIsIgnored() {
        assertNull(MediaJson.event("rcvFileProgressXFTP", obj("""{"type":"rcvFileProgressXFTP","chatItem_":null,"receivedSize":1,"totalSize":2}""")))
        val e = MediaJson.event("rcvFileError", obj("""{"type":"rcvFileError","chatItem_":null,"agentError":{"type":"x"}}"""))
        e as MediaEvent.Changed
        assertNull(e.item)
        assertEquals("rcvFileError", e.tag)
    }

    @Test
    fun descriptionReadyOnlyWhenCompleteAndForThisFile() {
        val item = MediaSamples.item(3, "directRcv", MediaSamples.content("file"), MediaSamples.file(id = 7), "rcvNew")
        val ready = MediaJson.event("rcvFileDescrReady", obj(MediaSamples.descrReady(item, 7)))
        ready as MediaEvent.DescriptionReady
        assertEquals(7L, ready.fileId)
        assertEquals(1_000_000L, ready.invitedSize)
        assertEquals("xftp", ready.protocol)
        assertTrue(ready.text.startsWith("party: recipient\nsize:"))
        assertFalse(ready.toString().contains("party"))

        assertTrue(MediaJson.event("rcvFileDescrReady", obj(MediaSamples.descrReady(item, 7, complete = false))) is MediaEvent.Changed)
        assertTrue(MediaJson.event("rcvFileDescrReady", obj(MediaSamples.descrReady(item, 8))) is MediaEvent.Changed)
    }

    @Test
    fun otherFileEventsAreChanged() {
        for (t in MediaJson.EVENTS - setOf("rcvFileDescrReady", "rcvFileProgressXFTP", "sndFileProgressXFTP")) {
            val e = MediaJson.event(t, obj(MediaSamples.event(t, anItem(), itemKey = "chatItem")))
            e as MediaEvent.Changed
            assertEquals(t, e.tag)
            assertEquals(3L, e.item!!.itemId)
        }
    }

    @Test
    fun parsedItemCarriesFileAndPreview() {
        val item = MediaSamples.item(
            3, "directRcv", MediaSamples.content("video", "hi", MediaSamples.PNG_URL, 9), MediaSamples.file(), "rcvNew",
        )
        val i = CoreJson.parseChatItem(5, obj(item))!!
        assertEquals(MediaSamples.PNG_URL, i.preview)
        assertEquals(9, i.durationSeconds)
        assertEquals(MediaKind.VIDEO, i.file!!.kind)
    }
}
