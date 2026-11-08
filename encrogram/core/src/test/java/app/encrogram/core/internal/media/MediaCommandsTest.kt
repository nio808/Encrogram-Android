package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.ChatRef
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaCommandsTest {

    private val key = FileKey("a2V5", "bm9uY2U")
    private val chat = ChatRef.contact(5)
    private val preview = "data:image/jpg;base64,/9j/AAAA"

    private fun ascii(s: String) = s.all { it.code in 0x20..0x7E }

    private fun composed(cmd: String, prefix: String = "/_send @5 json "): JSONObject {
        assertTrue(cmd.startsWith(prefix))
        assertTrue("pure ASCII for the JNI glue", ascii(cmd))
        return JSONArray(cmd.removePrefix(prefix)).getJSONObject(0)
    }

    private fun content(kind: MediaKind, caption: String = "", dur: Int? = null, prev: String? = preview) = OutgoingContent(kind, caption, prev, dur)

    private fun refuses(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun receiveExactString() {
        val c = CheckedFile.of(42, Verdict.PASSED)!!
        assertEquals("/freceive 42 approved_relays=off encrypt=on inline=off", MediaCommands.receive(c))
    }

    @Test
    fun cancelExactStringAndRequiresPositiveId() {
        assertEquals("/fcancel 42", MediaCommands.cancel(42))
        refuses { MediaCommands.cancel(0) }
        refuses { MediaCommands.cancel(-3) }
    }

    @Test
    fun sendShapeForAnImage() {
        val cmd = MediaCommands.send(chat, "IMG_ABCD2345.jpg", key, content(MediaKind.IMAGE, "hi"), null)
        assertTrue(cmd.startsWith("/_send @5 json [{"))
        assertTrue(cmd.endsWith("}]"))
        assertTrue(composed(cmd).has("fileSource"))
        assertEquals(1, JSONArray(cmd.removePrefix("/_send @5 json ")).length())
    }

    @Test
    fun sendJsonForEveryKind() {
        val img = composed(MediaCommands.send(chat, "a.jpg", key, content(MediaKind.IMAGE, "c"), null))
        assertEquals("image", img.getJSONObject("msgContent").getString("type"))
        assertEquals(preview, img.getJSONObject("msgContent").getString("image"))
        assertEquals("c", img.getJSONObject("msgContent").getString("text"))
        assertEquals("a.jpg", img.getJSONObject("fileSource").getString("filePath"))
        val ca = img.getJSONObject("fileSource").getJSONObject("cryptoArgs")
        assertEquals("a2V5", ca.getString("fileKey"))
        assertEquals("bm9uY2U", ca.getString("fileNonce"))
        assertEquals(0, img.getJSONObject("mentions").length())
        assertFalse(img.has("quotedItemId"))

        val gif = composed(MediaCommands.send(chat, "g.gif", key, content(MediaKind.GIF), null)).getJSONObject("msgContent")
        assertEquals("image", gif.getString("type"))

        val video = composed(MediaCommands.send(chat, "v.mp4", key, content(MediaKind.VIDEO, "", 12), null)).getJSONObject("msgContent")
        assertEquals("video", video.getString("type"))
        assertEquals(12, video.getInt("duration"))
        assertEquals(preview, video.getString("image"))

        val voice = composed(MediaCommands.send(chat, "v.m4a", key, content(MediaKind.VOICE, "", 7, null), null)).getJSONObject("msgContent")
        assertEquals("voice", voice.getString("type"))
        assertEquals(7, voice.getInt("duration"))
        assertFalse(voice.has("image"))

        val file = composed(MediaCommands.send(chat, "doc.pdf", key, content(MediaKind.FILE, "x", null, null), null)).getJSONObject("msgContent")
        assertEquals("file", file.getString("type"))
        assertEquals("x", file.getString("text"))
        assertEquals(2, file.length())
    }

    @Test
    fun sendQuotedItemAndGroupRef() {
        val cmd = MediaCommands.send(ChatRef.group(3), "a.jpg", key, content(MediaKind.IMAGE), 77)
        assertEquals(77, composed(cmd, "/_send #3 json ").getLong("quotedItemId"))
    }

    @Test
    fun nonAsciiCaptionAndNameAreEscaped() {
        val caption = "Привет 👋 \"q\" \\ end"
        val name = "отчёт 📄.pdf"
        val cmd = MediaCommands.send(chat, name, key, content(MediaKind.FILE, caption, null, null), null)
        assertTrue(ascii(cmd))
        val c = composed(cmd)
        assertEquals(caption, c.getJSONObject("msgContent").getString("text"))
        assertEquals(name, c.getJSONObject("fileSource").getString("filePath"))
    }

    @Test
    fun sendRefusals() {
        refuses { MediaCommands.send(ChatRef.notes(1), "a.jpg", key, content(MediaKind.IMAGE), null) }
        for (bad in listOf("", ".", "..", "a/b.jpg", "/abs.jpg", "../x")) {
            refuses { MediaCommands.send(chat, bad, key, content(MediaKind.IMAGE), null) }
        }
        refuses { MediaCommands.send(chat, "a.jpg", key, content(MediaKind.IMAGE, prev = null), null) }
        refuses { MediaCommands.send(chat, "a.gif", key, content(MediaKind.GIF, prev = null), null) }
        refuses { MediaCommands.send(chat, "a.mp4", key, content(MediaKind.VIDEO, prev = null, dur = 3), null) }
    }

    @Test
    fun approvedRelaysOnAppearsNowhereInTheMainSource() {
        var dir: File? = File("").absoluteFile
        // Gradle runs unit tests from the module folder; walk up to find src/main if started elsewhere.
        while (dir != null && !File(dir, "src/main/java/app/encrogram/core").isDirectory) {
            val sub = File(dir, "encrogram/core")
            if (File(sub, "src/main/java/app/encrogram/core").isDirectory) { dir = sub; break }
            dir = dir.parentFile
        }
        val root = File(requireNotNull(dir) { "module folder not found" }, "src/main/java")
        var scanned = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            scanned++
            val text = f.readText()
            assertFalse("approved_relays=on in ${f.name}", Regex("approved_relays\\s*=\\s*on").containsMatchIn(text))
            assertFalse("_set_file_to_receive in ${f.name}", text.contains("_set_file_to_receive"))
        }
        assertTrue("source tree was read", scanned > 20)
    }
}
