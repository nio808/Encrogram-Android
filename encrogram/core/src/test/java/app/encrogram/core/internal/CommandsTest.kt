package app.encrogram.core.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {

    private fun ascii(s: String) = s.all { it.code in 0x20..0x7E }

    @Test
    fun sendTextIsAsciiAndRoundTrips() {
        val text = "Hi 👋 مرحبا \"quoted\" \\ </script>\nline2\u0000"
        val cmd = Commands.sendText(5, text)
        assertTrue(cmd.startsWith("/_send @5 json ["))
        assertTrue("command must be pure ASCII for the JNI glue", ascii(cmd))
        val composed = JSONArray(cmd.removePrefix("/_send @5 json ")).getJSONObject(0)
        assertEquals("text", composed.getJSONObject("msgContent").getString("type"))
        assertEquals(text, composed.getJSONObject("msgContent").getString("text"))
        assertEquals(0, composed.getJSONObject("mentions").length())
    }

    @Test
    fun itemInfoForGroupItemsOnly() {
        assertEquals("/_get item info #3 7", Commands.itemInfo(ChatRef.group(3), 7))
        assertTrue(runCatching { Commands.itemInfo(ChatRef.contact(5), 7) }.isFailure)
    }

    @Test
    fun createUser() {
        val cmd = Commands.createUser("Zoë 🌸")
        assertTrue(ascii(cmd))
        val json = JSONObject(cmd.removePrefix("/_create user "))
        assertEquals("Zoë 🌸", json.getJSONObject("profile").getString("displayName"))
        assertEquals("", json.getJSONObject("profile").getString("fullName"))
        assertEquals(false, json.getBoolean("pastTimestamp"))
        // New users carry our chat preferences from the start; everything else stays at the core's defaults.
        val prefs = json.getJSONObject("profile").getJSONObject("preferences")
        assertEquals("yes", prefs.getJSONObject("fullDelete").getString("allow"))
        assertEquals("no", prefs.getJSONObject("calls").getString("allow"))
        assertEquals(2, prefs.length())
    }

    @Test
    fun sendReply() {
        val cmd = Commands.sendText(5, "re ❤", quotedItemId = 40)
        assertTrue(ascii(cmd))
        val composed = JSONArray(cmd.removePrefix("/_send @5 json ")).getJSONObject(0)
        assertEquals(40L, composed.getLong("quotedItemId"))
        assertEquals("re ❤", composed.getJSONObject("msgContent").getString("text"))
        assertEquals(0, composed.getJSONObject("mentions").length())
        // Without a reply there is no quotedItemId key at all.
        assertFalse(JSONArray(Commands.sendText(5, "x").removePrefix("/_send @5 json ")).getJSONObject(0).has("quotedItemId"))
    }

    @Test
    fun pagination() {
        assertEquals("/_get chat @7 count=50", Commands.getChat(7, 50))
        assertEquals("/_get chat @7 before=30 count=50", Commands.getChat(7, 50, beforeId = 30))
        assertEquals("/_get chat @7 after=30 count=50", Commands.getChat(7, 50, afterId = 30))
        assertEquals("/_get chat @7 around=30 count=25", Commands.getChat(7, 25, aroundId = 30))
        assertEquals("/_get chat @7 before=30 count=50 search=hello world", Commands.getChat(7, 50, beforeId = 30, search = "hello world"))
        assertEquals("/_get chat @7 count=50 search=مرحبا", Commands.getChat(7, 50, search = "مرحبا"))
        assertTrue(runCatching { Commands.getChat(7, 50, beforeId = 1, afterId = 2) }.exceptionOrNull() is IllegalArgumentException)
        // Plain-text search must be sanitised first (G1): no emoji outside the BMP, no control characters.
        assertTrue(runCatching { Commands.getChat(7, 50, search = "hi 👋") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Commands.getChat(7, 50, search = "a\n/_stop") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun editItem() {
        val cmd = Commands.updateItem(5, 42, "fixed 👋")
        assertTrue(ascii(cmd))
        assertTrue(cmd.startsWith("/_update item @5 42 live=off json {"))
        val json = JSONObject(cmd.removePrefix("/_update item @5 42 live=off json "))
        assertEquals("text", json.getJSONObject("msgContent").getString("type"))
        assertEquals("fixed 👋", json.getJSONObject("msgContent").getString("text"))
        assertEquals(0, json.getJSONObject("mentions").length())
    }

    @Test
    fun deleteItems() {
        assertEquals("/_delete item @5 41,42 internal", Commands.deleteItems(5, listOf(41, 42), Commands.DeleteMode.INTERNAL))
        assertEquals("/_delete item @5 41 broadcast", Commands.deleteItems(5, listOf(41), Commands.DeleteMode.BROADCAST))
        assertTrue(runCatching { Commands.deleteItems(5, emptyList(), Commands.DeleteMode.INTERNAL) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun reaction() {
        val cmd = Commands.reaction(5, 42, true, "❤")
        assertTrue(ascii(cmd))
        assertTrue(cmd.startsWith("/_reaction @5 42 on {"))
        val json = JSONObject(cmd.removePrefix("/_reaction @5 42 on "))
        assertEquals("emoji", json.getString("type"))
        assertEquals("\u2764", json.getString("emoji"))
        assertTrue(cmd.contains("\\u2764"))
        val off = Commands.reaction(5, 42, false, "👍")
        assertTrue(off.startsWith("/_reaction @5 42 off {"))
        assertEquals("👍", JSONObject(off.removePrefix("/_reaction @5 42 off ")).getString("emoji"))
        // The two-code-point heart fails the core's parser (G2): refused before it is sent.
        assertTrue(runCatching { Commands.reaction(5, 42, true, "❤\uFE0F") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun forwardAndChatCommands() {
        assertEquals("/_forward @6 @5 41,42 ttl=default", Commands.forward(6, 5, listOf(41, 42)))
        assertEquals("/_clear chat @5", Commands.clearChat(5))
        assertEquals("/_delete @5 full notify=on", Commands.deleteContact(5, true))
        assertEquals("/_delete @5 full notify=off", Commands.deleteContact(5, false))
        assertEquals("/_get code @5", Commands.getCode(5))
        assertEquals("/_verify code @5 12345 67890", Commands.verifyCode(5, "12345 67890"))
        assertEquals("/_verify code @5", Commands.verifyCode(5, null))
        assertTrue(runCatching { Commands.verifyCode(5, "12345; /_stop") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun settingsPrefsAndProfileCarryWholeObjects() {
        val settings = JSONObject().put("enableNtfs", "none").put("sendRcpts", true).put("favorite", true)
        val s = Commands.chatSettings(5, settings)
        assertTrue(s.startsWith("/_settings @5 {"))
        val sent = JSONObject(s.removePrefix("/_settings @5 "))
        assertEquals("none", sent.getString("enableNtfs"))
        assertEquals(true, sent.getBoolean("sendRcpts"))
        assertEquals(true, sent.getBoolean("favorite"))

        val prefs = JSONObject().put("timedMessages", JSONObject().put("allow", "yes").put("ttl", 600))
        val p = Commands.setContactPrefs(5, prefs)
        assertTrue(p.startsWith("/_set prefs @5 {"))
        assertEquals(600, JSONObject(p.removePrefix("/_set prefs @5 ")).getJSONObject("timedMessages").getInt("ttl"))

        val profile = JSONObject().put("displayName", "Zoë").put("fullName", "")
        val u = Commands.updateProfile(1, profile)
        assertTrue(ascii(u))
        assertTrue(u.startsWith("/_profile 1 {"))
        assertEquals("Zoë", JSONObject(u.removePrefix("/_profile 1 ")).getString("displayName"))
    }

    @Test
    fun fixedCommands() {
        assertEquals("/u", Commands.SHOW_ACTIVE_USER)
        assertEquals("/network socks=off smp-proxy=unknown smp-proxy-fallback=no", Commands.NETWORK_CONFIG)
        assertEquals("/_operators", Commands.GET_OPERATORS)
        assertEquals("/_start main=on", Commands.START_CHAT)
        assertEquals("/_servers 3", Commands.getUserServers(3))
        assertEquals("/_get chats 3", Commands.getChats(3))
        assertEquals("/_get chat @7 count=50", Commands.getChat(7, 50))
        assertEquals("/_connect 3", Commands.addContact(3))
        assertEquals("/_read chat @7", Commands.markRead(7))
        assertEquals("/_delete @1 full notify=off", Commands.deleteContactQuietly(1))
        assertEquals("/_delete :12 full notify=off", Commands.deletePendingConnection(12))
    }

    @Test
    fun filePaths() {
        val cmd = Commands.setFilePaths("/data/f", "/data/t", "/data/a", "/data/r")
        val json = JSONObject(cmd.removePrefix("/set file paths "))
        assertEquals("/data/f", json.getString("appFilesFolder"))
        assertEquals("/data/t", json.getString("appTempFolder"))
        assertEquals("/data/a", json.getString("appAssetsFolder"))
        assertEquals("/data/r", json.getString("appRemoteHostsFolder"))
    }

    @Test
    fun connectRefusesUncheckedText() {
        assertEquals("/connect https://h.example/i#a/b", Commands.connect("https://h.example/i#a/b"))
        val e = runCatching { Commands.connect("https://h.example/i#a/b /_stop") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun asciiJsonLeavesAsciiAlone() {
        assertEquals("""{"a":"b"}""", Commands.asciiJson("""{"a":"b"}"""))
        assertEquals("{\"a\":\"\\u00e9\"}", Commands.asciiJson("{\"a\":\"\u00e9\"}"))
    }

    @Test
    fun logTagsAreSanitized() {
        assertEquals("errorAgent.SMP.AUTH", SecuredLog.sanitizeTag("errorAgent.SMP.AUTH"))
        assertNull(SecuredLog.sanitizeTag("hello world"))
        assertNull(SecuredLog.sanitizeTag("https://encrogram.nytmanager.com/i#x"))
        assertNull(SecuredLog.sanitizeTag("a".repeat(49)))
    }
}
