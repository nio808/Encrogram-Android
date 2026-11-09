package app.encrogram.core.internal

import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.SecuredMentions
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.background.SecuredService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Group mentions: the `@name` forms the core's markdown reads (Markdown.hs mentionP), what we send (ComposedMessage
 * .mentions, getCIMentions' rules) and what we read back (ChatItem.mentions, meta.userMention). Chat core 7.0.0.12.
 */
class MentionsTest {

    private fun names(text: String) = SecuredMentions.mentionedNames(text)

    @Test
    fun tokensReadBackAsTheirKey() {
        for (name in listOf("Bob", "Bob Smith", "a.b", "Bob!", "Анна", "李雷", "Ann-Marie", "x_y", "😀", "bob.smith", "dots...", "#tag", "@ann")) {
            val t = SecuredMentions.token(name)
            for (text in listOf(t.text, "hi ${t.text}", "hi ${t.text}, how are you", "first line\n${t.text} second", "${t.text}\n")) {
                assertEquals("$name in [$text]", listOf(t.key), names(text))
            }
        }
    }

    @Test
    fun tokenForms() {
        assertEquals("@Bob", SecuredMentions.token("Bob").text)
        assertEquals("@'Bob Smith'", SecuredMentions.token("  Bob \n Smith ").text)
        assertEquals("Bob Smith", SecuredMentions.token("Bob\tSmith").key)
        assertEquals("@'a.b'", SecuredMentions.token("a.b").text)
        assertEquals("@'Bob!'", SecuredMentions.token("Bob!").text)
        assertEquals("tag", SecuredMentions.token("#tag").key)
        assertEquals("OBrien", SecuredMentions.token("O'Brien").key)
        assertEquals("member", SecuredMentions.token(" ' ").key)
        assertEquals("Bob_1", SecuredMentions.token("Bob", setOf("Bob")).key)
        assertEquals("@Bob_2", SecuredMentions.token("Bob", listOf("Bob", "Bob_1")).text)
        assertEquals("@'Bob Smith_1'", SecuredMentions.token("Bob Smith", setOf("Bob Smith")).text)
        assertFalse(SecuredMentions.token("Bob").toString().contains("Bob"))
    }

    @Test
    fun whatTheCoreReadsAsMentions() {
        assertEquals(listOf("bob"), names("@bob, hi"))
        assertEquals(listOf("bob", "ann"), names("@bob @ann @bob"))
        assertEquals(listOf("a", "b"), names("@'a'@'b'"))
        assertEquals(listOf("b"), names("@b"))
        assertEquals(listOf("bob"), names("@bob."))
        // After a formatted span the next fragment starts right away.
        assertEquals(listOf("bob"), names("*hi*@bob"))
        // A phone number is a fragment of its own: '@' right after it starts one too.
        assertEquals(listOf("bob"), names("+12345678@bob"))
        assertEquals(listOf("bob"), names("!x @bob"))
        // A command only counts as the whole message when it is one line.
        assertEquals(listOf("bob"), names("/cmd @bob\nmore"))
        assertEquals(listOf("bob"), names("\t@x\n @bob"))
    }

    @Test
    fun whatTheCoreDoesNotReadAsMentions() {
        for (text in listOf(
            "", "@", " @", "mail@bob", "123@bob", "@ bob", "@#bob", "@'bob", "@''",
            "*hi @bob*", "*hi @bob", "_x @bob_", "~x @bob~", "`x @bob`",
            "#a @bob#", "!1 @bob!", "!red @bob!",
            "/cmd @bob", "  /cmd @bob", "/'a @bob'\nx",
            // A SimpleX name, not a mention.
            "@a.b", "@bob.smith",
            // A link may swallow what follows: unsure, so nothing on that line.
            "[a @bob](https://x.org)", "[x](https://y.org) @bob",
            "a\t@bob",
        )) {
            assertEquals("[$text]", emptyList<String>(), names(text))
        }
    }

    @Test
    fun onlyNamesInTheTextAreSent() {
        val m = linkedMapOf("Bob" to 5L, "Ann" to 6L, "Gone" to 7L)
        assertEquals(mapOf("Ann" to 6L, "Bob" to 5L), SecuredMentions.forSend("@Ann and @Bob", m))
        assertEquals(listOf("Ann", "Bob"), SecuredMentions.forSend("@Ann and @Bob", m).keys.toList())
        // The same member under two names: once (the core refuses repeats).
        assertEquals(mapOf("Bob" to 5L), SecuredMentions.forSend("@Bob @Bobby", mapOf("Bob" to 5L, "Bobby" to 5L)))
        // At most three.
        val four = (1..4).associate { "m$it" to it.toLong() }
        assertEquals(listOf("m1", "m2", "m3"), SecuredMentions.forSend("@m1 @m2 @m3 @m4", four).keys.toList())
        // Not a mention in the core's eyes, or not a real member id: dropped.
        assertTrue(SecuredMentions.forSend("*@Bob*", m).isEmpty())
        assertTrue(SecuredMentions.forSend("@Bob", mapOf("Bob" to 0L)).isEmpty())
        assertTrue(SecuredMentions.forSend("@Bob", emptyMap()).isEmpty())
    }

    @Test
    fun theComposedMessageCarriesThem() {
        val cmd = Commands.sendText(ChatRef.group(3), "hi @'Анна'", null, mapOf("Анна" to 5L))
        assertTrue("non-ASCII stays escaped", cmd.all { it.code in 0x20..0x7E })
        val composed = JSONArray(cmd.removePrefix("/_send #3 json ")).getJSONObject(0)
        assertEquals(5L, composed.getJSONObject("mentions").getLong("Анна"))
        assertEquals("hi @'Анна'", composed.getJSONObject("msgContent").getString("text"))
        // Contacts never get mentions (the core's assertNoMentions).
        try {
            Commands.sendText(ChatRef.contact(4), "@Bob", null, mapOf("Bob" to 5L))
            fail("mentions to a contact")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(0, JSONArray(Commands.sendText(ChatRef.group(3), "x").removePrefix("/_send #3 json ")).getJSONObject(0).getJSONObject("mentions").length())
    }

    private val chat = GROUP_CHAT_ID_BASE + 3

    private fun received(userMention: Boolean, dir: String = "groupRcv", deleted: Boolean = false) = CoreJson.parseChatItem(
        chat,
        JSONObject(
            """{"chatDir":{"type":"$dir"${if (dir == "groupRcv") ""","groupMember":{"groupMemberId":5,"memberId":"Ym9i","memberRole":"member","memberProfile":{"displayName":"Bob"}}""" else ""}},
              "meta":{"itemId":9,"itemTs":"2026-11-08T10:00:00Z","itemText":"hi @Alice @Ghost","itemStatus":{"type":"rcvNew"},
                "itemSharedMsgId":"c2g=","userMention":$userMention,"editable":false,"deletable":false
                ${if (deleted) ""","itemDeleted":{"type":"deleted"}""" else ""}},
              "content":{"type":"${if (dir == "groupRcv") "rcv" else "snd"}MsgContent","msgContent":{"type":"text","text":"hi @Alice @Ghost"}},
              "mentions":{"Alice":{"memberId":"YWxp","memberRef":{"groupMemberId":1,"displayName":"Alice","memberRole":"owner"}},
                          "Ghost":{"memberId":"Z2hv"}},
              "reactions":[]}""",
        ),
    )!!

    @Test
    fun receivedMentionsAndMentionsMe() {
        val item = received(userMention = true)
        assertEquals(mapOf("Alice" to 1L), item.mentions)
        assertTrue(item.mentionsMe)
        val m = MessageMapper.toMessage(item)
        assertEquals(mapOf("Alice" to 1L), m.mentions)
        assertTrue(m.mentionsMe)
        assertFalse(m.toString().contains("Alice"))
        assertFalse(item.toString().contains("Alice"))
        assertFalse(MessageMapper.toMessage(received(userMention = false)).mentionsMe)
        // A deleted message keeps neither; our own message never "mentions me".
        val gone = MessageMapper.toMessage(received(userMention = true, deleted = true))
        assertTrue(gone.mentions.isEmpty())
        assertFalse(gone.mentionsMe)
        assertFalse(MessageMapper.toMessage(received(userMention = true, dir = "groupSnd")).mentionsMe)
    }

    @Test
    fun aMentionStillNotifiesInAMutedGroup() {
        fun msg(chatId: Long, mentionsMe: Boolean) = SecuredMessage(chatId, 1, false, "x", 0, MessageStatus.RECEIVED, mentionsMe = mentionsMe)
        assertTrue(SecuredService.notifiesWhenMuted(msg(chat, true)))
        assertFalse(SecuredService.notifiesWhenMuted(msg(chat, false)))
        assertFalse("contacts have no mentions", SecuredService.notifiesWhenMuted(msg(4, true)))
        // History stays silent even when it mentions us.
        assertFalse(SecuredService.notifies(msg(chat, true).copy(history = true)))
    }
}
