package app.encrogram.core.internal

import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.internal.media.CoreFile
import app.encrogram.core.internal.media.MediaCoordinator
import app.encrogram.core.internal.media.MediaSamples
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM-testable parts of SimplexSecuredEngine's media wiring (MD8): the text-reply budget and the event routes. */
class EngineMediaWiringTest {

    private fun quoted(text: String, file: CoreFile? = null, preview: String? = null) = CoreItem(
        chatId = 3, itemId = 40, outgoing = false, isMessage = true, text = text, timeMs = 0, statusTag = "rcvRead",
        status = MessageStatus.RECEIVED, unread = false, file = file, preview = preview,
    )

    private fun file(kind: MediaKind, name: String) = CoreFile(7, kind, name, 1_000_000, "rcvComplete", "xftp")

    /** The longest reply text (ASCII) that still fits next to a quote costing [quoteBytes] (text, name, preview). */
    private fun budget(quoteBytes: Int) = InputRules.MAX_TEXT_BYTES - InputRules.QUOTE_OVERHEAD_BYTES - quoteBytes

    @Test
    fun textQuoteBudgetIsUnchanged() {
        for (q in listOf("", "hi", "x".repeat(5_000), "é".repeat(2_000))) {
            for (len in listOf(0, 1, 7_000, 12_700, 13_000)) {
                val text = "a".repeat(len)
                assertEquals("quote ${q.length}, text $len", InputRules.replyFits(text, q), textReplyFits(text, quoted(q)))
            }
        }
    }

    @Test
    fun captionlessPhotoCountsItsNameAndPreview() {
        val preview = "data:image/jpeg;base64," + "A".repeat(6_000)
        val name = "IMG_ABCD2345.jpg"
        val q = quoted("", file(MediaKind.IMAGE, name), preview)
        val room = budget(name.length + preview.length)
        assertTrue(textReplyFits("a".repeat(room), q))
        assertFalse(textReplyFits("a".repeat(room + 1), q))
        // Without the preview in the count, a reply the core would refuse looked fine.
        assertTrue(InputRules.replyFits("a".repeat(room + 1), q.text))
    }

    @Test
    fun captionedVideoCountsTheCaptionNotTheName() {
        val preview = MediaSamples.PNG_URL
        val q = quoted("look at this", file(MediaKind.VIDEO, "video_ABCD2345.mp4"), preview)
        val room = budget("look at this".length + preview.length)
        assertTrue(textReplyFits("a".repeat(room), q))
        assertFalse(textReplyFits("a".repeat(room + 1), q))
    }

    @Test
    fun captionlessFileWithoutPreviewCountsItsName() {
        val name = "é".repeat(100) + ".pdf"
        val q = quoted("", file(MediaKind.FILE, name))
        val room = budget(InputRules.encodedTextBytes(name))
        assertTrue(textReplyFits("a".repeat(room), q))
        assertFalse(textReplyFits("a".repeat(room + 1), q))
    }

    @Test
    fun aQuoteParsedFromTheCoreCarriesItsPreview() {
        val json = MediaSamples.item(
            12, "directRcv", MediaSamples.content("image", image = MediaSamples.PNG_URL),
            MediaSamples.file(status = MediaSamples.status("rcvComplete")), "rcvRead",
        )
        val item = CoreJson.parseAChatItem(JSONObject(Samples.aChatItem(5, json)))!!
        assertEquals(MediaSamples.PNG_URL, item.preview)
        val room = budget(InputRules.encodedTextBytes(MediaSamples.NAME) + MediaSamples.PNG_URL.length)
        assertTrue(textReplyFits("a".repeat(room), item))
        assertFalse(textReplyFits("a".repeat(room + 1), item))
    }

    /** handleEvent routes these itself; a file event among them would reach onMessageReceived or the list reload. */
    @Test
    fun fileEventsNeverShareARouteWithMessages() {
        val engineRoutes = setOf(
            "newChatItems", "chatItemsStatusesUpdated", "chatItemUpdated", "chatItemReaction", "chatItemsDeleted",
            "chatErrors", "messageError", "chatCleared", "contactUpdated", "groupUpdated",
        )
        assertTrue(MediaCoordinator.EVENTS.intersect(engineRoutes).isEmpty())
        // Recorded above handleEvent's RUNNING guard; every other file event is routed after it.
        assertTrue("rcvFileDescrReady" in MediaCoordinator.EVENTS)
        assertTrue(MediaCoordinator.EVENTS.all { it.startsWith("rcvFile") || it.startsWith("sndFile") })
    }
}
