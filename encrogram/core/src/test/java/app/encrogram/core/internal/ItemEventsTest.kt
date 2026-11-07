package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.ReactionCount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v2 reply and event shapes (Controller.hs CRChatItem*, CEvtChatItem*; Messages.hs ChatItem) and their mapping. */
class ItemEventsTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun result(raw: String): CoreReply.Result {
        val r = CoreJson.parseReply(raw)
        assertTrue("expected a result, got $r", r is CoreReply.Result)
        return r as CoreReply.Result
    }

    private fun item(json: String): CoreItem = CoreJson.parseChatItem(5, JSONObject(json))!!

    @Test
    fun replyCarriesItsQuote() {
        val i = item(Samples.item(45, "directSnd", "sndMsgContent", "me too", "sndSent", quote = Samples.quote(40, "first")))
        assertEquals(CoreQuote(40, "first", false), i.quote)
        val m = MessageMapper.toMessage(i)
        assertEquals(40L, m.replyTo?.messageId)
        assertEquals("first", m.replyTo?.text)
        assertFalse(m.replyTo!!.outgoing)
        // A quote of a message this device never received has no id; a quote of our own message is outgoing.
        val q = item(Samples.item(46, "directRcv", "rcvMsgContent", "yes", "rcvNew", quote = Samples.quote(null, "mine", "directSnd")))
        assertEquals(CoreQuote(null, "mine", true), q.quote)
    }

    @Test
    fun longQuotesArePreviewedShort() {
        val long = "x".repeat(InputRules.QUOTE_PREVIEW_CHARS + 50)
        val m = MessageMapper.toMessage(item(Samples.item(45, "directRcv", "rcvMsgContent", "ok", "rcvNew", quote = Samples.quote(40, long))))
        assertEquals(InputRules.QUOTE_PREVIEW_CHARS, m.replyTo!!.text.length)
    }

    @Test
    fun metaFlags() {
        val own = item(Samples.item(41, "directSnd", "sndMsgContent", "hi", "sndRcvd", edited = true, editable = true, deletable = true))
        val m = MessageMapper.toMessage(own)
        assertTrue(m.edited)
        assertTrue(m.canEdit)
        assertTrue(m.canDeleteForEveryone)
        assertFalse(m.forwarded)
        assertNull(m.expiresAtMs)
        assertEquals(MessageStatus.DELIVERED, m.status)

        // Older than 24 h: the core reports editable=false and deletable=false.
        val old = MessageMapper.toMessage(item(Samples.item(41, "directSnd", "sndMsgContent", "hi", "sndRcvd")))
        assertFalse(old.canEdit)
        assertFalse(old.canDeleteForEveryone)

        // Still sending (no shared msg id yet): nothing can act on it over the wire.
        val sending = MessageMapper.toMessage(item(Samples.item(42, "directSnd", "sndMsgContent", "hi", "sndNew", editable = true, deletable = true, shared = false)))
        assertFalse(sending.canEdit)
        assertFalse(sending.canDeleteForEveryone)

        // Received messages are never ours to edit, whatever the flags say.
        val theirs = MessageMapper.toMessage(item(Samples.item(43, "directRcv", "rcvMsgContent", "hi", "rcvNew", editable = true, deletable = true)))
        assertFalse(theirs.canEdit)
        assertFalse(theirs.canDeleteForEveryone)

        val fwd = MessageMapper.toMessage(item(Samples.item(44, "directRcv", "rcvMsgContent", "look", "rcvRead", forwarded = true)))
        assertTrue(fwd.forwarded)

        val timed = MessageMapper.toMessage(item(Samples.item(47, "directRcv", "rcvMsgContent", "soon gone", "rcvRead", timedDeleteAt = "2026-11-08T10:00:00Z")))
        assertEquals(java.time.Instant.parse("2026-11-08T10:00:00Z").toEpochMilli(), timed.expiresAtMs)
    }

    @Test
    fun reactionsKeepOnlyTheKnownEight() {
        val m = MessageMapper.toMessage(item(Samples.item(41, "directRcv", "rcvMsgContent", "hi", "rcvRead", reactions = Samples.REACTIONS)))
        assertEquals(listOf(ReactionCount("👍", 2, true), ReactionCount("❤", 1, false)), m.reactions)
    }

    @Test
    fun chatItemUpdatedEvent() {
        val r = result(Samples.chatItemUpdated(Samples.item(41, "directRcv", "rcvMsgContent", "edited text", "rcvRead", edited = true)))
        assertEquals("chatItemUpdated", r.type)
        val i = CoreJson.parseAChatItem(r.body.optJSONObject("chatItem"))!!
        assertEquals(5L, i.chatId)
        assertEquals(41L, i.itemId)
        assertEquals("edited text", i.text)
        assertTrue(i.edited)
    }

    @Test
    fun chatItemReactionReplyAndEvent() {
        val r = result(Samples.chatItemReaction(Samples.item(41, "directSnd", "sndMsgContent", "hi", "sndRcvd", reactions = Samples.REACTIONS)))
        assertEquals("chatItemReaction", r.type)
        val i = CoreJson.parseReactionItem(r.body)!!
        assertEquals(5L, i.chatId)
        assertEquals(41L, i.itemId)
        assertEquals(2, i.reactions.size)
        // Not a direct chat: ignored.
        val group = JSONObject(Samples.chatItemReaction(Samples.item(41, "groupRcv", "rcvMsgContent", "hi", "rcvRead")))
            .getJSONObject("result")
        group.getJSONObject("reaction").put("chatInfo", JSONObject("""{"type":"group","groupInfo":{"groupId":3}}"""))
        assertNull(CoreJson.parseReactionItem(group))
    }

    @Test
    fun fullDeleteRemovesTheRow() {
        val gone = Samples.item(41, "directSnd", "sndMsgContent", "regret", "sndRcvd")
        val r = result(Samples.chatItemsDeleted(listOf(gone to null), byUser = true))
        assertEquals("chatItemsDeleted", r.type)
        val d = CoreJson.parseDeletions(r.body).single()
        assertEquals(CoreDeletion(5, 41, null), d)
    }

    @Test
    fun markedDeleteNeverCarriesText() {
        val before = Samples.item(41, "directRcv", "rcvMsgContent", "secret words", "rcvRead", reactions = Samples.REACTIONS,
            quote = Samples.quote(40, "quoted secret"))
        // The core keeps the content (and its text) in the JSON when it only marks an item deleted.
        val after = Samples.item(41, "directRcv", "rcvMsgContent", "secret words", "rcvRead", deleted = true,
            reactions = Samples.REACTIONS, quote = Samples.quote(40, "quoted secret"))
        val d = CoreJson.parseDeletions(result(Samples.chatItemsDeleted(listOf(before to after))).body).single()
        assertNotNull(d.replacement)
        val now = d.replacement!!
        assertTrue(now.deleted)
        assertEquals("", now.text)
        val m = MessageMapper.toMessage(now)
        assertTrue(m.deleted)
        assertEquals("", m.text)
        assertNull(m.replyTo)
        assertTrue(m.reactions.isEmpty())
        assertFalse(m.canEdit)
        assertFalse(m.canDeleteForEveryone)
        assertFalse(m.toString().contains("secret"))
    }

    @Test
    fun deletedItemsInAPageNeverCarryText() {
        val page = """{"result":{"type":"apiChat","chat":{"chatInfo":{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}},
            "chatItems":[${Samples.item(40, "directRcv", "rcvMsgContent", "secret words", "rcvRead", deleted = true)},
              {"chatDir":{"type":"directRcv"},"meta":{"itemId":41,"itemTs":"2026-11-07T10:00:00Z","itemText":"secret legacy",
               "itemStatus":{"type":"rcvRead"},"itemEdited":false,"userMention":false,"hasLink":false,"deletable":false,
               "editable":false,"showGroupAsSender":false,"createdAt":"2026-11-07T10:00:00Z","updatedAt":"2026-11-07T10:00:00Z"},
               "content":{"type":"rcvDeleted","deleteMode":"broadcast"},"mentions":{},"reactions":[]}],
            "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}}}"""
        val msgs = CoreJson.parseChatItemsOfChat(result(page).body).map { MessageMapper.toMessage(it) }
        assertEquals(listOf(40L, 41L), msgs.map { it.messageId })
        for (m in msgs) {
            assertTrue(m.deleted)
            assertEquals("", m.text)
        }
    }

    @Test
    fun timedExpiryRemovesTheRow() {
        val gone = Samples.item(47, "directRcv", "rcvMsgContent", "soon gone", "rcvRead", timedDeleteAt = "2026-11-08T10:00:00Z")
        val r = result(Samples.chatItemsDeleted(listOf(gone to null), byUser = true, timed = true))
        assertTrue(r.body.getBoolean("timed"))
        assertEquals(listOf(CoreDeletion(5, 47, null)), CoreJson.parseDeletions(r.body))
    }

    @Test
    fun deletionsOfServiceItemsAreIgnored() {
        val service = """{"chatDir":{"type":"directSnd"},"meta":{"itemId":39,"itemTs":"2026-11-07T09:59:00Z","itemText":"connected",
            "itemStatus":{"type":"sndNew"},"itemEdited":false,"userMention":false,"hasLink":false,"deletable":false,
            "editable":false,"showGroupAsSender":false,"createdAt":"2026-11-07T09:59:00Z","updatedAt":"2026-11-07T09:59:00Z"},
            "content":{"type":"sndConnEvent","sndConnEvent":{"type":"switchQueue","phase":"started"}},"mentions":{},"reactions":[]}"""
        assertTrue(CoreJson.parseDeletions(result(Samples.chatItemsDeleted(listOf(service to null))).body).isEmpty())
    }

    @Test
    fun securityCodeAndVerification() {
        val code = result(Samples.CONTACT_CODE)
        assertEquals("contactCode", code.type)
        assertEquals("12345 67890 12345 67890", CoreJson.contactCode(code.body))
        assertNotNull(CoreJson.parseContact(code.body.optJSONObject("contact")))

        val ok = result(Samples.CONNECTION_VERIFIED)
        assertEquals("connectionVerified", ok.type)
        assertTrue(CoreJson.connectionVerified(ok.body))
        assertFalse(CoreJson.connectionVerified(result(Samples.CONNECTION_NOT_VERIFIED).body))
    }

    @Test
    fun actionReplyTypes() {
        val user = """"user":{"userId":1,"profile":{"displayName":"Alice","fullName":""}}"""
        assertEquals("cmdOk", result("""{"result":{"type":"cmdOk",$user}}""").type)
        assertEquals("chatItemNotChanged", result("""{"result":{"type":"chatItemNotChanged",$user,"chatItem":{}}}""").type)
        assertEquals("chatCleared", result("""{"result":{"type":"chatCleared",$user,"chatInfo":{"type":"direct"}}}""").type)
        assertEquals("contactDeleted", result("""{"result":{"type":"contactDeleted",$user,"contact":{}}}""").type)
        assertEquals("contactPrefsUpdated", result("""{"result":{"type":"contactPrefsUpdated",$user,"fromContact":{},"toContact":{}}}""").type)
        assertEquals("userProfileUpdated", result("""{"result":{"type":"userProfileUpdated",$user,"fromProfile":{},"toProfile":{},
            "updateSummary":{"updateSuccesses":1,"updateFailures":0,"changedContacts":[]}}}""").type)
        assertEquals("userProfileNoChange", result("""{"result":{"type":"userProfileNoChange",$user}}""").type)
    }

    @Test
    fun actionErrorsMapToNotAllowed() {
        fun mapped(type: String, detail: String, op: ErrorMapper.Op) = ErrorMapper.fromCore(CoreError(type, detail, null), op).kind
        val notAllowed = app.encrogram.core.api.SecuredError.Kind.NOT_ALLOWED
        assertEquals(notAllowed, mapped("error", "invalidChatItemUpdate", ErrorMapper.Op.EDIT))
        assertEquals(notAllowed, mapped("error", "invalidChatItemDelete", ErrorMapper.Op.DELETE))
        assertEquals(notAllowed, mapped("error", "commandError", ErrorMapper.Op.REACT))
        assertEquals(notAllowed, mapped("errorStore", "invalidQuote", ErrorMapper.Op.SEND))
        // A command error outside reactions is not a policy refusal.
        assertEquals(app.encrogram.core.api.SecuredError.Kind.ENGINE, mapped("error", "commandError", ErrorMapper.Op.CHAT))
        assertEquals(ErrorMapper.TOO_LONG, ErrorMapper.fromCore(CoreError("errorStore", "largeMsg", null), ErrorMapper.Op.SEND).message)
    }
}
