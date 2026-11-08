package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoreJsonTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun result(raw: String): CoreReply.Result {
        val r = CoreJson.parseReply(raw)
        assertTrue("expected a result, got $r", r is CoreReply.Result)
        return r as CoreReply.Result
    }

    private fun error(raw: String): CoreError {
        val r = CoreJson.parseReply(raw)
        assertTrue("expected an error, got $r", r is CoreReply.Error)
        return (r as CoreReply.Error).error
    }

    @Test
    fun activeUser() {
        val r = result(Samples.ACTIVE_USER)
        assertEquals("activeUser", r.type)
        val user = CoreJson.parseUser(r.body.optJSONObject("user"))
        assertEquals(CoreUser(1, "Alice"), user)
    }

    @Test
    fun errorsAreReducedToTags() {
        assertEquals(CoreError("error", "noActiveUser", null), error(Samples.NO_ACTIVE_USER))
        assertEquals(CoreError("errorAgent", "SMP", "AUTH"), error(Samples.AGENT_SMP_AUTH))
        assertEquals("errorAgent.BROKER.TIMEOUT", error(Samples.AGENT_BROKER_TIMEOUT).tag)
        assertEquals(CoreError("errorStore", "duplicateName", null), error(Samples.STORE_DUPLICATE))
        // WebSocket-style command error wrapped in a result.
        assertEquals(CoreError("error", "chatNotStarted", null), error(Samples.CMD_ERROR_RESULT))
    }

    @Test
    fun invalidReplies() {
        assertTrue(CoreJson.parseReply("") === CoreReply.Invalid)
        assertTrue(CoreJson.parseReply(null) === CoreReply.Invalid)
        assertTrue(CoreJson.parseReply("not json") === CoreReply.Invalid)
        assertTrue(CoreJson.parseReply("""{"something":1}""") === CoreReply.Invalid)
        assertTrue(CoreJson.parseReply("""{"result":{"no":"type"}}""") === CoreReply.Invalid)
    }

    @Test
    fun legacyRespKeyIsAccepted() {
        assertEquals("cmdOk", result("""{"resp":{"type":"cmdOk","user_":null}}""").type)
    }

    @Test
    fun newChatItemsEventKeepsOnlyDirectChats() {
        val r = result(Samples.NEW_CHAT_ITEMS_EVENT)
        assertEquals("newChatItems", r.type)
        val items = CoreJson.parseChatItems(r.body)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(5L, item.chatId)
        assertEquals(42L, item.itemId)
        assertFalse(item.outgoing)
        assertTrue(item.isMessage)
        assertTrue(item.unread)
        assertEquals("hello 👋", item.text)
        assertEquals(MessageStatus.RECEIVED, item.status)
        assertEquals(java.time.Instant.parse("2026-11-07T10:20:30.123456Z").toEpochMilli(), item.timeMs)
    }

    @Test
    fun statusUpdateEvent() {
        val r = result(Samples.STATUS_UPDATED_EVENT)
        assertEquals("chatItemsStatusesUpdated", r.type)
        val item = CoreJson.parseChatItems(r.body).single()
        assertEquals(41L, item.itemId)
        assertTrue(item.outgoing)
        assertEquals("sndRcvd", item.statusTag)
        assertEquals(MessageStatus.DELIVERED, item.status)
    }

    @Test
    fun chatListParsesContactsAndSkipsOtherChatTypes() {
        val chats = CoreJson.parseChats(result(Samples.API_CHATS).body)
        assertEquals(listOf(5L, 6L, 1L), chats.map { it.contact.contactId })

        val bob = chats[0]
        assertEquals("Bob", bob.contact.displayName)
        assertTrue(bob.contact.ready)
        assertTrue(bob.contact.verified)
        assertFalse(bob.contact.isContactCard)
        assertEquals(2, bob.unreadCount)
        assertEquals("hi", bob.lastItem?.text)
        assertEquals(MessageStatus.SENT_TO_SERVER, bob.lastItem?.status)

        val carol = chats[1]
        assertFalse("joined is still connecting", carol.contact.ready)
        assertFalse(carol.contact.verified)
        assertNull(carol.lastItem)
        assertTrue(carol.contact.activityTimeMs > 0)

        val card = chats[2]
        assertTrue("the preset team card is recognised", card.contact.isContactCard)
    }

    @Test
    fun chatPageIsOrderedAndKeepsServiceItemsMarked() {
        val items = CoreJson.parseChatItemsOfChat(result(Samples.API_CHAT).body)
        assertEquals(listOf(39L, 40L, 41L), items.map { it.itemId })
        assertFalse("connection events are not messages", items[0].isMessage)
        assertEquals("first", items[1].text)
        assertEquals(MessageStatus.RECEIVED, items[1].status)
        assertFalse(items[1].unread)
        assertEquals(MessageStatus.FAILED, items[2].status)
    }

    @Test
    fun invitationLinks() {
        val created = CoreJson.parseInvitation(result(Samples.INVITATION).body)
        assertNotNull(created.fullLink)
        assertTrue(created.shortLink!!.startsWith("https://encrogram.nytmanager.com/i#"))
        assertEquals(12L, created.pendingConnId)
    }

    @Test
    fun invitationWithoutShortLink() {
        val raw = Samples.INVITATION.replace(Regex("\"connShortLink\":\"[^\"]*\""), "\"connShortLink\":null")
        val created = CoreJson.parseInvitation(result(raw).body)
        assertNull(created.shortLink)
        assertNotNull(created.fullLink)
    }

    @Test
    fun connectionPlanTag() {
        assertEquals("invitationLink.ownLink", CoreJson.connectionPlanTag(result(Samples.CONNECTION_PLAN_OWN).body))
    }

    @Test
    fun migrationResult() {
        assertEquals("ok", CoreJson.migrationResultTag("""{"type":"ok"}"""))
        assertEquals("errorNotADatabase", CoreJson.migrationResultTag("""{"type":"errorNotADatabase","dbFile":"x"}"""))
        assertEquals("invalid", CoreJson.migrationResultTag("garbage"))
        assertEquals("empty", CoreJson.migrationResultTag(null))
    }

    @Test
    fun nullStringsAreNotTheWordNull() {
        val contact = CoreJson.parseContact(org.json.JSONObject(Samples.contact(9, "Dan", "ready")))!!
        // chatTs is JSON null: the activity time falls back to updatedAt instead of failing.
        assertEquals(java.time.Instant.parse("2026-11-07T09:30:00Z").toEpochMilli(), contact.activityTimeMs)
    }

    @Test
    fun timeParsing() {
        assertEquals(0L, CoreJson.timeMs(null))
        assertEquals(0L, CoreJson.timeMs("yesterday"))
        assertEquals(1_000L, CoreJson.timeMs("1970-01-01T00:00:01Z"))
    }

    @Test
    fun commandErrorKeepsOnlyKnownReactionTags() {
        fun err(message: String) = CoreJson.parseError(
            org.json.JSONObject().put("type", "error")
                .put("errorType", org.json.JSONObject().put("type", "commandError").put("message", message))
        )
        assertEquals("reactionAlreadyRemoved", err("reaction already removed").subDetail)
        assertEquals("reactionAlreadyAdded", err("reaction already added").subDetail)
        assertEquals("tooManyReactions", err("too many reactions").subDetail)
        assertEquals("commandError", err("too many reactions").detail)
        // Any other text never becomes a tag.
        assertNull(err("secret text from somewhere").subDetail)
        assertEquals("error.commandError", err("secret text from somewhere").tag)
    }
}
