package app.encrogram.core.internal.media

import chat.simplex.common.platform.chatDecryptFile
import chat.simplex.common.platform.chatEncryptFile
import chat.simplex.common.platform.chatReadFile
import chat.simplex.common.platform.chatWriteFile
import java.io.File
import java.nio.ByteBuffer
import java.util.Arrays
import org.json.JSONObject

/**
 * The SimpleX core's four local file functions (JNI, `Core.kt`), as declared and as the official client calls them
 * (`apps/multiplatform/.../model/CryptoFile.kt`, `src/Simplex/Chat/Mobile/File.hs`).
 *
 * - The core's error text never surfaces: every failure is a [NativeFileException] with a fixed tag, without a cause.
 * - All four calls run under [gate] (read lock): the two that take the controller need it alive, and the two that
 *   don't must not still be writing into `view/` when the engine wipes it after a store close.
 * - Paths must be printable ASCII. JNI passes them through `GetStringUTFChars` (modified UTF-8), which mangles
 *   characters outside the BMP; a mangled output path would leave plaintext where nothing can find it. MediaVault
 *   gives other names an ASCII hard link in `media/link/`; here they are refused.
 *
 * Threads: [writeEncrypted] and [encryptFile] on the prep thread, [readDecrypted] and [decryptFile] on the read
 * thread. Never main.
 */
internal class SimplexNativeFiles internal constructor(
    private val calls: CoreFileCalls,
    private val gate: CtrlGate,
    private val ctrl: () -> Long,
) : NativeFiles {

    /** The production form: the real JNI functions. [ctrl] returns the engine's current controller (0: none). */
    constructor(gate: CtrlGate, ctrl: () -> Long) : this(JniFileCalls, gate, ctrl)

    override fun writeEncrypted(target: File, data: ByteBuffer): FileKey {
        var key: FileKey? = null
        try {
            // The glue reads GetDirectBufferCapacity bytes from the buffer's address, whatever its position and limit.
            if (!data.isDirect || data.capacity() == 0 || data.position() != 0 || data.limit() != data.capacity()) {
                throw NativeFileException(WRITE)
            }
            val path = nativePath(target, WRITE)
            key = gate.use {
                val c = liveCtrl()
                val json: String? = try {
                    calls.writeFile(c, path, data)
                } catch (e: Exception) {
                    null
                }
                parseWriteResult(json) ?: throw NativeFileException(WRITE)
            }
            return key
        } finally {
            // A failed write may leave a partial (ciphertext) file: never keep it.
            if (key == null) target.delete()
        }
    }

    override fun encryptFile(from: File, to: File): FileKey {
        var key: FileKey? = null
        try {
            val fromPath = nativePath(from, ENCRYPT)
            val toPath = nativePath(to, ENCRYPT)
            key = gate.use {
                val c = liveCtrl()
                val json: String? = try {
                    calls.encryptFile(c, fromPath, toPath)
                } catch (e: Exception) {
                    null
                }
                parseWriteResult(json) ?: throw NativeFileException(ENCRYPT)
            }
            return key
        } finally {
            if (key == null) to.delete()
        }
    }

    override fun readDecrypted(file: File, key: FileKey): ByteArray {
        val path = nativePath(file, READ)
        if (!keyOk(key)) throw NativeFileException(READ)
        return gate.use {
            // An OutOfMemoryError (the whole file is loaded) propagates: the coordinator words it as TOO_LARGE.
            val res: Array<out Any?>? = try {
                calls.readFile(path, key.key, key.nonce)
            } catch (e: Exception) {
                throw NativeFileException(READ)
            }
            readResult(res)
        }
    }

    override fun decryptFile(from: File, key: FileKey, to: File) {
        // chatDecryptFile writes plaintext chunk by chunk and checks the tag only at the end: after any failure
        // (error text, exception, even an Error, or a refusal before the call) [to] may hold unauthenticated
        // plaintext. It is deleted before anything is thrown.
        var ok = false
        try {
            val fromPath = nativePath(from, DECRYPT)
            val toPath = nativePath(to, DECRYPT)
            if (!keyOk(key)) throw NativeFileException(DECRYPT)
            gate.use {
                val r: String? = try {
                    calls.decryptFile(fromPath, key.key, key.nonce, toPath)
                } catch (e: Exception) {
                    throw NativeFileException(DECRYPT)
                }
                if (r == null || r.isNotEmpty()) throw NativeFileException(DECRYPT)
            }
            ok = true
        } finally {
            if (!ok) to.delete()
        }
    }

    private fun liveCtrl(): Long {
        val c = ctrl()
        if (c == 0L) throw NativeFileException(CtrlGate.NO_CTRL)
        return c
    }

    override fun toString() = "SimplexNativeFiles"

    companion object {
        const val WRITE = "write"
        const val READ = "read"
        const val ENCRYPT = "encrypt"
        const val DECRYPT = "decrypt"

        private const val MAX_ARG_CHARS = 256

        /** A path the core will see exactly as we do: printable ASCII only (also refuses NUL and control characters). */
        fun pathOk(path: String): Boolean = path.isNotEmpty() && path.all { it in ' '..'~' }

        private fun nativePath(f: File, tag: String): String {
            val p = f.absolutePath
            if (!pathOk(p)) throw NativeFileException(tag)
            return p
        }

        /** The core's strEncode of a key or nonce: base64 (url or standard alphabet), never anything else. */
        internal fun argOk(s: String?): Boolean =
            s != null && s.isNotEmpty() && s.length <= MAX_ARG_CHARS &&
                s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '+' || it == '/' || it == '=' }

        private fun keyOk(k: FileKey) = argOk(k.key) && argOk(k.nonce)

        /**
         * chatWriteFile / chatEncryptFile: `{"type":"result","cryptoArgs":{"fileKey":..,"fileNonce":..}}` gives the key;
         * `{"type":"error","writeError":..}`, anything malformed or a key outside the expected alphabet gives null.
         * The error text is never read.
         */
        internal fun parseWriteResult(json: String?): FileKey? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                if (o.opt("type") != "result") return null
                val args = o.opt("cryptoArgs") as? JSONObject ?: return null
                val key = args.opt("fileKey") as? String
                val nonce = args.opt("fileNonce") as? String
                if (!argOk(key) || !argOk(nonce)) null else FileKey(key!!, nonce!!)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * chatReadFile: `[status: Integer, bytes]`. Status 0: the bytes are the plaintext. Otherwise the bytes are the
         * core's error text: zeroed, then [NativeFileException] `read`. Anything malformed is a failure too.
         */
        internal fun readResult(res: Array<out Any?>?): ByteArray {
            val bytes = res?.getOrNull(1) as? ByteArray
            val status = (res?.getOrNull(0) as? Number)?.toInt()
            if (res == null || res.size != 2 || status != 0 || bytes == null) {
                bytes?.let { Arrays.fill(it, 0.toByte()) }
                throw NativeFileException(READ)
            }
            return bytes
        }
    }
}

/** The JNI file functions, behind a seam so [SimplexNativeFiles] is tested on the JVM without the native library. */
internal interface CoreFileCalls {
    fun writeFile(ctrl: Long, path: String, buffer: ByteBuffer): String?
    fun readFile(path: String, key: String, nonce: String): Array<out Any?>?
    fun encryptFile(ctrl: Long, fromPath: String, toPath: String): String?
    fun decryptFile(fromPath: String, key: String, nonce: String, toPath: String): String?
}

/** The real calls, exactly as declared in `chat/simplex/common/platform/Core.kt`. */
internal object JniFileCalls : CoreFileCalls {
    override fun writeFile(ctrl: Long, path: String, buffer: ByteBuffer): String? = chatWriteFile(ctrl, path, buffer)
    override fun readFile(path: String, key: String, nonce: String): Array<out Any?>? = chatReadFile(path, key, nonce)
    override fun encryptFile(ctrl: Long, fromPath: String, toPath: String): String? = chatEncryptFile(ctrl, fromPath, toPath)
    override fun decryptFile(fromPath: String, key: String, nonce: String, toPath: String): String? =
        chatDecryptFile(fromPath, key, nonce, toPath)
}
