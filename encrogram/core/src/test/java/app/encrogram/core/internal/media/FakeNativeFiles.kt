package app.encrogram.core.internal.media

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * A stand-in for the core's file functions, for JVM tests (MediaVault, and later MediaCoordinator).
 *
 * - "Encryption" is a reversible XOR with a keystream from the key, plus a 16-byte tail tag (like the core's files),
 *   so a sealed file never contains its plaintext and a wrong key or a tampered file fails the read.
 * - Failures are injected per call ([failWrite], [failEncrypt], [failRead], [failDecrypt]). On purpose, a failing
 *   fake leaves its partial output behind and does NOT delete it, so the tests prove the vault cleans up even when an
 *   implementation breaks the NativeFiles contract. A failing decrypt writes real plaintext bytes first, as a failed
 *   chatDecryptFile does.
 * - Every path it is handed is recorded ([paths]), so tests can check JNI only ever sees ASCII ([nonAsciiPaths]).
 */
internal class FakeNativeFiles : NativeFiles {

    enum class Fail {
        NONE,
        /** Throw NativeFileException (what a real failure looks like), output left behind. */
        NATIVE,
        /** Throw a RuntimeException whose message holds a canary (core text must not travel), output left behind. */
        RUNTIME,
        /** Throw an Error (OutOfMemoryError), output left behind. */
        ERROR,
    }

    @Volatile var failWrite = Fail.NONE
    @Volatile var failEncrypt = Fail.NONE
    @Volatile var failRead = Fail.NONE
    @Volatile var failDecrypt = Fail.NONE

    val paths: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    @Volatile var lastBuffer: ByteBuffer? = null
    @Volatile var writes = 0
    @Volatile var encrypts = 0
    @Volatile var reads = 0
    @Volatile var decrypts = 0

    val nonAsciiPaths: List<String> get() = synchronized(paths) { paths.filterNot { SimplexNativeFiles.pathOk(it) } }

    override fun writeEncrypted(target: File, data: ByteBuffer): FileKey {
        writes++
        record(target)
        lastBuffer = data
        check(data.isDirect) { "not direct" }
        val plain = ByteArray(data.remaining())
        data.duplicate().get(plain)
        try {
            fail(failWrite) { target.writeBytes(byteArrayOf(9, 9, 9)) }
            val key = newKey()
            target.writeBytes(seal(plain, key))
            return key
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    override fun encryptFile(from: File, to: File): FileKey {
        encrypts++
        record(from)
        record(to)
        fail(failEncrypt) { to.writeBytes(byteArrayOf(9, 9, 9)) }
        val plain = from.readBytes()
        val key = newKey()
        to.writeBytes(seal(plain, key))
        Arrays.fill(plain, 0)
        return key
    }

    override fun readDecrypted(file: File, key: FileKey): ByteArray {
        reads++
        record(file)
        fail(failRead) {}
        return open(file.readBytes(), key) ?: throw NativeFileException("read")
    }

    override fun decryptFile(from: File, key: FileKey, to: File) {
        decrypts++
        record(from)
        record(to)
        val cipher = from.readBytes()
        // Like chatDecryptFile: plaintext goes out chunk by chunk before the tag is checked.
        fail(failDecrypt) { to.writeBytes(xor(cipher.copyOf(maxOf(0, cipher.size - TAG)), key)) }
        val plain = open(cipher, key) ?: run {
            to.writeBytes(xor(cipher.copyOf(maxOf(0, cipher.size - TAG)), key))
            throw NativeFileException("decrypt")
        }
        to.writeBytes(plain)
    }

    private fun record(f: File) {
        paths.add(f.absolutePath)
    }

    private inline fun fail(mode: Fail, leaveBehind: () -> Unit) {
        if (mode == Fail.NONE) return
        leaveBehind()
        when (mode) {
            Fail.NATIVE -> throw NativeFileException("fake")
            Fail.RUNTIME -> throw IllegalStateException(CORE_TEXT)
            Fail.ERROR -> throw OutOfMemoryError(CORE_TEXT)
            Fail.NONE -> Unit
        }
    }

    companion object {
        const val TAG = 16
        /** What a core error message might carry; must never reach a log or an exception message we make. */
        const val CORE_TEXT = "CANARY_CORE_TEXT /files/CANARY_NAME.jpg"
        private val random = SecureRandom()

        fun newKey(): FileKey {
            val k = ByteArray(32).also { random.nextBytes(it) }
            val n = ByteArray(24).also { random.nextBytes(it) }
            val enc = java.util.Base64.getUrlEncoder()
            return FileKey(enc.encodeToString(k), enc.encodeToString(n))
        }

        private fun stream(key: FileKey, size: Int): ByteArray {
            val out = ByteArray(size)
            var block = 0
            var pos = 0
            while (pos < size) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(key.key.toByteArray())
                md.update(key.nonce.toByteArray())
                md.update(block.toString().toByteArray())
                val h = md.digest()
                val n = minOf(h.size, size - pos)
                System.arraycopy(h, 0, out, pos, n)
                pos += n
                block++
            }
            return out
        }

        fun xor(data: ByteArray, key: FileKey): ByteArray {
            val s = stream(key, data.size)
            return ByteArray(data.size) { (data[it].toInt() xor s[it].toInt()).toByte() }
        }

        private fun tag(plain: ByteArray, key: FileKey): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(key.key.toByteArray())
            md.update(plain)
            return md.digest().copyOf(TAG)
        }

        fun seal(plain: ByteArray, key: FileKey): ByteArray = xor(plain, key) + tag(plain, key)

        /** The plaintext, or null when the key is wrong or the file was changed. */
        fun open(cipher: ByteArray, key: FileKey): ByteArray? {
            if (cipher.size < TAG) return null
            val plain = xor(cipher.copyOf(cipher.size - TAG), key)
            val t = cipher.copyOfRange(cipher.size - TAG, cipher.size)
            return if (MessageDigest.isEqual(t, tag(plain, key))) plain else null
        }
    }
}
