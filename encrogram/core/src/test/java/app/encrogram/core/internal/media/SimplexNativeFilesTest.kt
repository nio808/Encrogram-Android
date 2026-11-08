package app.encrogram.core.internal.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * SimplexNativeFiles without the native library: the result parsing, plus the class itself over a fake of the four
 * JNI calls (CoreFileCalls), so delete-on-failure, the gate and the no-core-text rule are proven on the JVM.
 */
class SimplexNativeFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val okJson = """{"type":"result","cryptoArgs":{"fileKey":"a2V5LWtleS1rZXk_-A==","fileNonce":"bm9uY2Utbm9uY2U="}}"""
    private val errJson = """{"type":"error","writeError":"CANARY /data/files/CANARY_NAME.jpg: permission denied"}"""

    /** Records calls; [partial] makes a call write into its output path before answering (as the core may). */
    private class FakeCalls : CoreFileCalls {
        var writeReply: String? = null
        var encryptReply: String? = null
        var readReply: Array<out Any?>? = null
        var decryptReply: String? = ""
        var throwThis: Throwable? = null
        var partial = false
        val calls = ArrayList<String>()
        val ctrls = ArrayList<Long>()

        override fun writeFile(ctrl: Long, path: String, buffer: ByteBuffer): String? {
            calls += "write"
            ctrls += ctrl
            if (partial) File(path).writeBytes(byteArrayOf(1, 2, 3))
            throwThis?.let { throw it }
            return writeReply
        }

        override fun readFile(path: String, key: String, nonce: String): Array<out Any?>? {
            calls += "read"
            throwThis?.let { throw it }
            return readReply
        }

        override fun encryptFile(ctrl: Long, fromPath: String, toPath: String): String? {
            calls += "encrypt"
            ctrls += ctrl
            if (partial) File(toPath).writeBytes(byteArrayOf(1, 2, 3))
            throwThis?.let { throw it }
            return encryptReply
        }

        override fun decryptFile(fromPath: String, key: String, nonce: String, toPath: String): String? {
            calls += "decrypt"
            if (partial) File(toPath).writeText("CANARY unauthenticated plaintext")
            throwThis?.let { throw it }
            return decryptReply
        }
    }

    private val calls = FakeCalls()
    private val gate = CtrlGate()
    @Volatile private var ctrl = 77L
    private val native by lazy { SimplexNativeFiles(calls, gate) { ctrl } }
    private val key = FileKey("a2V5LWtleS1rZXk_-A==", "bm9uY2Utbm9uY2U=")

    private fun direct(n: Int = 8): ByteBuffer = ByteBuffer.allocateDirect(n).also { b ->
        repeat(n) { b.put(it.toByte()) }
        b.rewind()
    }

    private fun assertNoCoreText(e: Throwable) {
        assertFalse(e.message.orEmpty().contains("CANARY"))
        assertNull("no cause can carry core text", e.cause)
    }

    // ---- parse helpers -------------------------------------------------------------------------------------------

    @Test
    fun parseWriteResultTakesOnlyAWellFormedResult() {
        val k = SimplexNativeFiles.parseWriteResult(okJson)
        assertNotNull(k)
        assertEquals("a2V5LWtleS1rZXk_-A==", k!!.key)
        assertEquals("bm9uY2Utbm9uY2U=", k.nonce)
        assertEquals("FileKey(redacted)", k.toString())

        val refused = listOf(
            null, "", "not json", "[]", errJson,
            """{"type":"result"}""",
            """{"type":"result","cryptoArgs":null}""",
            """{"type":"result","cryptoArgs":{"fileKey":"abc"}}""",
            """{"type":"result","cryptoArgs":{"fileKey":null,"fileNonce":"abc"}}""",
            """{"type":"result","cryptoArgs":{"fileKey":"","fileNonce":"abc"}}""",
            """{"type":"result","cryptoArgs":{"fileKey":12,"fileNonce":"abc"}}""",
            """{"type":"result","cryptoArgs":{"fileKey":"a b","fileNonce":"abc"}}""",
            """{"type":"result","cryptoArgs":{"fileKey":"ключ","fileNonce":"abc"}}""",
            """{"type":"Result","cryptoArgs":{"fileKey":"abc","fileNonce":"abc"}}""",
            """{"result":{"cryptoArgs":{"fileKey":"abc","fileNonce":"abc"}}}""",
            """{"type":"result","cryptoArgs":{"fileKey":"${"a".repeat(300)}","fileNonce":"abc"}}""",
        )
        for (j in refused) assertNull(j, SimplexNativeFiles.parseWriteResult(j))
    }

    @Test
    fun readResultGivesPlaintextOnlyForStatusZero() {
        val plain = byteArrayOf(5, 6, 7)
        assertArrayEquals(plain, SimplexNativeFiles.readResult(arrayOf(0, plain)))

        val errorText = "CANARY error text".toByteArray()
        val e = thrown<NativeFileException> { SimplexNativeFiles.readResult(arrayOf(1, errorText)) }
        assertEquals("read", e.tag)
        assertNoCoreText(e)
        assertTrue("the core's error bytes are zeroed", errorText.all { it == 0.toByte() })

        for (bad in listOf<Array<out Any?>?>(null, arrayOf<Any?>(), arrayOf<Any?>(0), arrayOf<Any?>(0, null), arrayOf<Any?>("0", plain.copyOf()), arrayOf<Any?>(0, "x"))) {
            assertEquals("read", thrown<NativeFileException> { SimplexNativeFiles.readResult(bad) }.tag)
        }
        val extra = byteArrayOf(9, 9)
        thrown<NativeFileException> { SimplexNativeFiles.readResult(arrayOf(0, extra, "more")) }
        assertTrue("a malformed result is zeroed too", extra.all { it == 0.toByte() })
    }

    @Test
    fun pathsMustBePrintableAscii() {
        assertTrue(SimplexNativeFiles.pathOk("/data/user/0/app.encrogram.android/no_backup/encrogram-secured/files/IMG_a b.jpg"))
        assertFalse(SimplexNativeFiles.pathOk(""))
        assertFalse(SimplexNativeFiles.pathOk("/files/📄.pdf"))
        assertFalse(SimplexNativeFiles.pathOk("/files/фото.jpg"))
        assertFalse(SimplexNativeFiles.pathOk("/files/a\u0000b"))
        assertFalse(SimplexNativeFiles.pathOk("/files/a\nb"))
    }

    // ---- writeEncrypted / encryptFile ----------------------------------------------------------------------------

    @Test
    fun writeEncryptedReturnsTheKeyAndUsesTheLiveController() {
        calls.writeReply = okJson
        val target = File(tmp.root, "sealed")
        val k = native.writeEncrypted(target, direct())
        assertEquals("a2V5LWtleS1rZXk_-A==", k.key)
        assertEquals(listOf("write"), calls.calls)
        assertEquals(listOf(77L), calls.ctrls)
    }

    @Test
    fun writeEncryptedErrorDeletesThePartialFileAndHidesTheText() {
        calls.partial = true
        val target = File(tmp.root, "sealed")
        for (reply in listOf(errJson, null, "garbage")) {
            calls.writeReply = reply
            val e = thrown<NativeFileException> { native.writeEncrypted(target, direct()) }
            assertEquals("write", e.tag)
            assertNoCoreText(e)
            assertFalse(target.exists())
        }
        calls.throwThis = IllegalStateException("CANARY core exception")
        val e = thrown<NativeFileException> { native.writeEncrypted(target, direct()) }
        assertNoCoreText(e)
        assertFalse(target.exists())
    }

    @Test
    fun writeEncryptedRefusesBuffersTheGlueWouldMisread() {
        calls.writeReply = okJson
        val target = File(tmp.root, "sealed")
        val heap = ByteBuffer.allocate(8)
        val empty = ByteBuffer.allocateDirect(0)
        val consumed = direct().also { it.position(3) }
        val limited = direct().also { it.limit(4) }
        for (b in listOf(heap, empty, consumed, limited)) {
            assertEquals("write", thrown<NativeFileException> { native.writeEncrypted(target, b) }.tag)
        }
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun noControllerOrAClosedGateMeansNoCall() {
        calls.writeReply = okJson
        calls.encryptReply = okJson
        val target = File(tmp.root, "sealed")
        val plain = File(tmp.root, "plain").apply { writeText("x") }

        ctrl = 0L
        assertEquals("no_ctrl", thrown<NativeFileException> { native.writeEncrypted(target, direct()) }.tag)
        assertEquals("no_ctrl", thrown<NativeFileException> { native.encryptFile(plain, target) }.tag)
        assertTrue(calls.calls.isEmpty())

        ctrl = 77L
        gate.close {}
        assertEquals("no_ctrl", thrown<NativeFileException> { native.writeEncrypted(target, direct()) }.tag)
        assertEquals("no_ctrl", thrown<NativeFileException> { native.encryptFile(plain, target) }.tag)
        calls.readReply = arrayOf(0, byteArrayOf(1))
        assertEquals("no_ctrl", thrown<NativeFileException> { native.readDecrypted(plain, key) }.tag)
        val out = File(tmp.root, "view-out").apply { writeText("stale") }
        assertEquals("no_ctrl", thrown<NativeFileException> { native.decryptFile(plain, key, out) }.tag)
        assertFalse("decryptFile deletes its output even when refused", out.exists())
        assertTrue(calls.calls.isEmpty())

        gate.reopen()
        native.writeEncrypted(target, direct())
        assertEquals(listOf("write"), calls.calls)
    }

    @Test
    fun nonAsciiPathsNeverReachTheCore() {
        calls.writeReply = okJson
        calls.encryptReply = okJson
        val bad = File(tmp.root, "📄.bin")
        val good = File(tmp.root, "good").apply { writeText("x") }
        assertEquals("write", thrown<NativeFileException> { native.writeEncrypted(bad, direct()) }.tag)
        assertEquals("encrypt", thrown<NativeFileException> { native.encryptFile(bad, good) }.tag)
        assertEquals("read", thrown<NativeFileException> { native.readDecrypted(bad, key) }.tag)
        assertEquals("decrypt", thrown<NativeFileException> { native.decryptFile(good, key, bad) }.tag)
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun encryptFileErrorDeletesTheOutput() {
        calls.partial = true
        val from = File(tmp.root, "plain").apply { writeText("x") }
        val to = File(tmp.root, "sealed")
        calls.encryptReply = errJson
        val e = thrown<NativeFileException> { native.encryptFile(from, to) }
        assertEquals("encrypt", e.tag)
        assertNoCoreText(e)
        assertFalse(to.exists())

        calls.encryptReply = okJson
        calls.partial = false
        native.encryptFile(from, to)
        assertEquals(listOf("encrypt", "encrypt"), calls.calls)
    }

    // ---- readDecrypted -------------------------------------------------------------------------------------------

    @Test
    fun readDecryptedZeroesTheErrorBytesAndHidesTheText() {
        val f = File(tmp.root, "IMG_x.jpg").apply { writeBytes(byteArrayOf(1)) }
        calls.readReply = arrayOf(0, byteArrayOf(4, 2))
        assertArrayEquals(byteArrayOf(4, 2), native.readDecrypted(f, key))

        val errorBytes = "CANARY invalid auth tag".toByteArray()
        calls.readReply = arrayOf(1, errorBytes)
        val e = thrown<NativeFileException> { native.readDecrypted(f, key) }
        assertEquals("read", e.tag)
        assertNoCoreText(e)
        assertTrue(errorBytes.all { it == 0.toByte() })

        calls.throwThis = IllegalStateException("CANARY")
        assertNoCoreText(thrown<NativeFileException> { native.readDecrypted(f, key) })

        // Out of memory goes up as it is: the coordinator words it as "too large".
        calls.throwThis = OutOfMemoryError()
        thrown<OutOfMemoryError> { native.readDecrypted(f, key) }
    }

    @Test
    fun aKeyOutsideTheExpectedAlphabetIsNeverPassedOn() {
        val f = File(tmp.root, "IMG_x.jpg").apply { writeBytes(byteArrayOf(1)) }
        val out = File(tmp.root, "out")
        val bad = FileKey("key with spaces", "nonce")
        assertEquals("read", thrown<NativeFileException> { native.readDecrypted(f, bad) }.tag)
        assertEquals("decrypt", thrown<NativeFileException> { native.decryptFile(f, bad, out) }.tag)
        assertTrue(calls.calls.isEmpty())
    }

    // ---- decryptFile ---------------------------------------------------------------------------------------------

    @Test
    fun decryptFileKeepsTheOutputOnlyOnAnEmptyResult() {
        val from = File(tmp.root, "IMG_x.jpg").apply { writeBytes(byteArrayOf(1)) }
        val to = File(tmp.root, "view-out")
        calls.partial = true

        calls.decryptReply = ""
        native.decryptFile(from, key, to)
        assertTrue("success keeps the output", to.exists())
        to.delete()

        for (reply in listOf("CANARY invalid auth tag", null, " ")) {
            calls.decryptReply = reply
            val e = thrown<NativeFileException> { native.decryptFile(from, key, to) }
            assertEquals("decrypt", e.tag)
            assertNoCoreText(e)
            assertFalse("unauthenticated plaintext deleted", to.exists())
        }

        calls.decryptReply = ""
        calls.throwThis = RuntimeException("CANARY core exception")
        assertNoCoreText(thrown<NativeFileException> { native.decryptFile(from, key, to) })
        assertFalse(to.exists())

        calls.throwThis = OutOfMemoryError()
        thrown<OutOfMemoryError> { native.decryptFile(from, key, to) }
        assertFalse("even an Error deletes the output first", to.exists())
    }

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.simpleName}, got ${t.javaClass.simpleName}", t)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing thrown")
    }
}
