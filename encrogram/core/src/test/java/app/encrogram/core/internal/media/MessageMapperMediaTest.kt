package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.internal.CoreChat
import app.encrogram.core.internal.CoreContact
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.InputRules
import app.encrogram.core.internal.MessageMapper
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.MediaSamples.NAME
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageMapperMediaTest {

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
        editable: Boolean = false,
        quote: String? = null,
    ): CoreItem = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, dir, content, file, status, itemText, deleted, editable, quote)))!!

    private fun contact() = CoreContact(5, "Bob", ready = true, verified = false, isContactCard = false, removed = false, activityTimeMs = 1L)
    private fun row(i: CoreItem) = MessageMapper.summaryOf(CoreChat(contact(), i, 0), archived = false)

    // ---- messages ----------------------------------------------------------------------------------------------------

    @Test
    fun anIncomingPhotoCarriesMediaAndItsCaption() {
        val m = MessageMapper.toMessage(item(MediaSamples.content("image", "sunset", MediaSamples.PNG_URL), MediaSamples.file(name = "IMG_ABCDEFGH.jpg")))
        assertEquals("sunset", m.text)
        val media = m.media!!
        assertEquals(MediaKind.IMAGE, media.kind)
        assertEquals(MediaState.WAITING, media.state)
        assertNotNull(media.preview)
        assertFalse(m.canEdit)
    }

    @Test
    fun aVoiceMessageWithoutCaptionHasNoText() {
        // The core's itemText for a captionless voice message is "voice message 0:12": never a caption.
        val i = item(MediaSamples.content("voice", "", null, 12), MediaSamples.file(name = "voice_ABCDEFGH.m4a"), itemText = "voice message 0:12")
        assertEquals("", i.text)
        val m = MessageMapper.toMessage(i)
        assertEquals("", m.text)
        assertEquals(MediaKind.VOICE, m.media!!.kind)
        assertEquals(12, m.media!!.durationSeconds)
        assertNull("no preview line may show it either", row(i).lastMessageText?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun everyMediaKindIgnoresItemText() {
        for ((type, name) in listOf("image" to "IMG_ABCDEFGH.jpg", "video" to "video_ABCDEFGH.mp4", "file" to "a.bin")) {
            val i = item(MediaSamples.content(type, "", null, null), MediaSamples.file(name = name), itemText = "SHOULD NOT SHOW")
            assertEquals(type, "", i.text)
        }
        // text content still falls back to itemText as before
        val t = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, "directRcv", """{"type":"text","text":""}""", null, "rcvNew", "fallback")))!!
        assertEquals("fallback", t.text)
    }

    @Test
    fun aDeletedMediaItemHasNoMedia() {
        val i = item(MediaSamples.content("image", "bye", MediaSamples.PNG_URL), MediaSamples.file(), deleted = true)
        val m = MessageMapper.toMessage(i)
        assertNull(m.media)
        assertEquals("", m.text)
        assertTrue(m.deleted)
        assertNull(row(i).lastMessageMedia)
        assertNull(row(i).lastMessageText)
    }

    @Test
    fun anOutgoingMessageIsCappedWhileUploading() {
        fun status(fileStatus: String, msgStatus: String = "sndSent") =
            item(MediaSamples.content("image", "", MediaSamples.PNG_URL), MediaSamples.file(name = "IMG_ABCDEFGH.jpg", status = fileStatus, protocol = "xftp"), dir = "directSnd", status = msgStatus)

        assertEquals(MessageStatus.SENDING, status(MediaSamples.status("sndStored")).status)
        assertEquals(MessageStatus.SENDING, status(MediaSamples.status("sndTransfer", """"sndProgress":1,"sndTotal":2""")).status)
        assertEquals(MessageStatus.SENDING, status(MediaSamples.status("sndStored"), "sndNew").status)
        // after the upload completes the message's own status shows
        assertEquals(MessageStatus.SENT_TO_SERVER, status(MediaSamples.status("sndComplete")).status)
        assertEquals(MessageStatus.FAILED, status(MediaSamples.status("sndError", """"sndFileError":{"type":"auth"}""")).status)
        assertEquals(MessageStatus.FAILED, status(MediaSamples.status("sndCancelled")).status)

        val uploading = MessageMapper.toMessage(status(MediaSamples.status("sndTransfer", """"sndProgress":1,"sndTotal":2""")))
        assertEquals(MediaState.UPLOADING, uploading.media!!.state)
        assertEquals(MessageStatus.SENDING, uploading.status)
        assertFalse("a photo can't be edited", uploading.canEdit)
    }

    @Test
    fun anIncomingMessageStatusIsUntouched() {
        val m = MessageMapper.toMessage(item(MediaSamples.content("file"), MediaSamples.file()))
        assertEquals(MessageStatus.RECEIVED, m.status)
    }

    @Test
    fun textStaysEditableAndNeverHasMedia() {
        val t = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, "directSnd", """{"type":"text","text":"hi"}""", null, "sndSent", "hi", editable = true)))!!
        val m = MessageMapper.toMessage(t)
        assertTrue(m.canEdit)
        assertNull(m.media)
    }

    // ---- quotes --------------------------------------------------------------------------------------------------------

    @Test
    fun aReplyToAPhotoKnowsItsKindAndDropsOurNeutralName() {
        val q = MediaSamples.quote(2, "image", "IMG_ABCD2345.jpg")
        val m = MessageMapper.toMessage(item("""{"type":"text","text":"nice"}""", null, quote = q))
        assertEquals(MediaKind.IMAGE, m.replyTo!!.mediaKind)
        assertEquals("", m.replyTo!!.text)
        assertEquals(2L, m.replyTo!!.messageId)
    }

    @Test
    fun aReplyKeepsRealCaptionsAndFileNames() {
        val cap = MessageMapper.toMessage(item("""{"type":"text","text":"x"}""", null, quote = MediaSamples.quote(2, "video", "my holiday")))
        assertEquals(MediaKind.VIDEO, cap.replyTo!!.mediaKind)
        assertEquals("my holiday", cap.replyTo!!.text)
        val doc = MessageMapper.toMessage(item("""{"type":"text","text":"x"}""", null, quote = MediaSamples.quote(2, "file", "report.pdf")))
        assertEquals(MediaKind.FILE, doc.replyTo!!.mediaKind)
        assertEquals("report.pdf", doc.replyTo!!.text)
        val text = MessageMapper.toMessage(item("""{"type":"text","text":"x"}""", null, quote = MediaSamples.quote(2, "text", "plain")))
        assertNull(text.replyTo!!.mediaKind)
    }

    @Test
    fun parseQuoteFillsTheContentType() {
        val i = item("""{"type":"text","text":"x"}""", null, quote = MediaSamples.quote(2, "voice", ""))
        assertEquals("voice", i.quote!!.msgContentType)
    }

    // ---- chat rows -----------------------------------------------------------------------------------------------------

    @Test
    fun theRowShowsTheKindAndCaption() {
        val r = row(item(MediaSamples.content("image", "sunset", MediaSamples.PNG_URL), MediaSamples.file(name = "IMG_ABCDEFGH.jpg")))
        assertEquals(MediaKind.IMAGE, r.lastMessageMedia)
        assertEquals("sunset", r.lastMessageText)
    }

    @Test
    fun aCaptionlessFileShowsItsName() {
        val r = row(item(MediaSamples.content("file"), MediaSamples.file()))
        assertEquals(MediaKind.FILE, r.lastMessageMedia)
        assertEquals(NAME, r.lastMessageText)
        val withCaption = row(item(MediaSamples.content("file", "see attached"), MediaSamples.file()))
        assertEquals("see attached", withCaption.lastMessageText)
    }

    @Test
    fun aCaptionlessPhotoHasEmptyTextNotItsNeutralName() {
        val r = row(item(MediaSamples.content("image"), MediaSamples.file(name = "IMG_ABCDEFGH.jpg")))
        assertEquals(MediaKind.IMAGE, r.lastMessageMedia)
        assertEquals("", r.lastMessageText)
    }

    @Test
    fun textRowsHaveNoMedia() {
        val t = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(3, "directRcv", """{"type":"text","text":"hi"}""", null, "rcvNew", "hi")))!!
        assertNull(row(t).lastMessageMedia)
    }

    @Test
    fun withNewItemSetsAndClearsTheMedia() {
        val base = row(CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(2, "directRcv", """{"type":"text","text":"hi"}""", null, "rcvNew", "hi")))!!)
        val photo = item(MediaSamples.content("image", "p", MediaSamples.PNG_URL), MediaSamples.file(name = "IMG_ABCDEFGH.jpg"))
        val withPhoto = MessageMapper.withNewItem(base, photo)
        assertEquals(MediaKind.IMAGE, withPhoto.lastMessageMedia)
        assertEquals("p", withPhoto.lastMessageText)

        val doc = MessageMapper.withNewItem(base, item(MediaSamples.content("file"), MediaSamples.file()))
        assertEquals(NAME, doc.lastMessageText)

        val text = CoreJson.parseChatItem(5, JSONObject(MediaSamples.item(4, "directRcv", """{"type":"text","text":"ok"}""", null, "rcvNew", "ok")))!!
        assertNull(MessageMapper.withNewItem(withPhoto, text).lastMessageMedia)

        val gone = item(MediaSamples.content("image", "x"), MediaSamples.file(), deleted = true)
        assertNull(MessageMapper.withNewItem(withPhoto, gone).lastMessageMedia)
    }

    @Test
    fun failedFilesMapToTheContract() {
        val m = MessageMapper.toMessage(item(MediaSamples.content("file"), MediaSamples.file(protocol = "smp"))).media!!
        assertEquals(MediaState.FAILED, m.state)
        assertEquals(MediaFailure.UNSUPPORTED, m.failure)
    }

    @Test
    fun theMessageToStringNeverHoldsTheName() {
        val m = MediaMapper.map(item(MediaSamples.content("file"), MediaSamples.file(status = MediaSamples.status("rcvComplete"), source = true)))!!
        assertFalse(m.toString().contains("CANARY"))
    }

    // ---- limits --------------------------------------------------------------------------------------------------------

    @Test
    fun replyFitsCountsTheQuotedPreviewAndKeepsTheOldSignature() {
        assertTrue(InputRules.replyFits("hi", "quoted"))
        assertTrue(InputRules.replyFits("hi", "quoted", 0))
        val room = InputRules.MAX_TEXT_BYTES - InputRules.QUOTE_OVERHEAD_BYTES - 2 - 6
        assertTrue(InputRules.replyFits("hi", "quoted", room))
        assertFalse(InputRules.replyFits("hi", "quoted", room + 1))
        assertFalse(InputRules.replyFits("hi", "quoted", 12_800))
    }

    @Test
    fun mediaFitsBudget() {
        // preview + caption + name + quote + 600 <= 13,000
        assertTrue(InputRules.mediaFits("a".repeat(1024), 8_000, 30))
        assertTrue(InputRules.mediaFits("я".repeat(1024), 8_000, 30))
        assertTrue("emoji caption of 1024 characters", InputRules.mediaFits("😀".repeat(1024), 8_000, 30))
        assertTrue(InputRules.mediaFits("", 12_400, 0))
        assertFalse(InputRules.mediaFits("", 12_401, 0))
        assertTrue(InputRules.mediaFits("hi", 8_000, 200, quoteBytes = 300 + 2_000))
        assertFalse(InputRules.mediaFits("hi", 8_000, 200, quoteBytes = 5_000))
        assertFalse(InputRules.mediaFits("a".repeat(5_000), 8_000, 0))
    }
}
