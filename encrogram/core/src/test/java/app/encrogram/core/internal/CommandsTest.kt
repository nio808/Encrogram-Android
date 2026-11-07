package app.encrogram.core.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun createUser() {
        val cmd = Commands.createUser("Zoë 🌸")
        assertTrue(ascii(cmd))
        val json = JSONObject(cmd.removePrefix("/_create user "))
        assertEquals("Zoë 🌸", json.getJSONObject("profile").getString("displayName"))
        assertEquals("", json.getJSONObject("profile").getString("fullName"))
        assertEquals(false, json.getBoolean("pastTimestamp"))
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
