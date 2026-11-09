package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaStorage
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.io.IOException
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Arrays

/**
 * Where Secured media lives on this phone, and the only code that touches those files (docs/media-plan.md §4.2,
 * §5, §6). Backend-neutral: the encryption itself is [NativeFiles].
 *
 * - [filesDir] is the core's files folder: encrypted files only, under bare names.
 * - [mediaDir] holds three transient folders, all wiped at start and stop ([wipeTransient]):
 *   - `prep/`: sealed (encrypted) files waiting to be committed into [filesDir];
 *   - `view/`: the only place plaintext is ever written (a staged video, a decrypted copy for playback);
 *   - `link/`: ASCII hard links (or ciphertext copies) for files whose path JNI can't carry.
 *
 * Nothing is written outside these two roots. Paths, names and keys are never logged (fixed tags and counts only),
 * and no exception thrown from here carries a path.
 *
 * Threads: [wipeTransient], [commit], [uncommit], [resolve], [present] and [expire] on the engine thread; [sealBytes]
 * and [sealFile] on the prep thread; [readBytes] and [decryptToView] on the read thread; the rest anywhere.
 */
internal class MediaVault(
    filesDir: File,
    mediaDir: File,
    private val native: NativeFiles,
    /** Wall clock: a committed file is dated when it enters the files folder (retention counts from there). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Makes a hard link [link] → [existing]. Tests replace it to force the copy fallback. */
    private val hardLink: (link: Path, existing: Path) -> Unit = { link, existing -> Files.createLink(link, existing) },
) {
    private val files: File = filesDir.absoluteFile.normalize()
    private val media: File = mediaDir.absoluteFile.normalize()
    private val prepDir = File(media, PREP)
    private val viewDir = File(media, VIEW)
    private val linkDir = File(media, LINK)
    private val random = SecureRandom()

    /** Every open playback; [wipeTransient] closes them before deleting their files. */
    val playbacks = PlaybackRegistry()

    init {
        require(files != media && !files.startsWith(media) && !media.startsWith(files)) { "media and files folders overlap" }
    }

    // ---- transient folders -------------------------------------------------------------------------------------

    /**
     * Closes every registered playback, then deletes everything in `prep/`, `view/` and `link/` (links are removed,
     * never followed). Engine thread, at start (before anything is prepared) and at stop (after CtrlGate.close, so
     * no native call is still writing). Returns how many entries were deleted.
     */
    fun wipeTransient(): Int {
        playbacks.closeAll()
        var deleted = 0
        var failed = 0
        for (dir in listOf(prepDir, viewDir, linkDir)) {
            val p = dir.toPath()
            try {
                if (Files.isSymbolicLink(p)) {
                    Files.deleteIfExists(p)
                    deleted++
                    continue
                }
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.deleteIfExists(p)) deleted++
                    continue
                }
                Files.newDirectoryStream(p).use { entries ->
                    for (e in entries) {
                        val r = deleteTree(e)
                        deleted += r.first
                        failed += r.second
                    }
                }
            } catch (e: IOException) {
                failed++
            } catch (e: SecurityException) {
                failed++
            }
        }
        if (failed > 0) SecuredLog.e(SecuredLog.Ev.MEDIA_IO_FAILED, "wipe", failed)
        if (deleted > 0) SecuredLog.i(SecuredLog.Ev.MEDIA_WIPED, null, deleted)
        return deleted
    }

    /** A fresh, not yet existing file in `view/` for a preparer to stage plaintext in (sealed with [sealFile]). */
    fun newViewFile(): File = newFile(ensureDir(viewDir))

    /**
     * A new empty file in `view/` (created exclusively) opened read-write for the voice recorder. Wiped with `view/` at
     * start and stop like any staging file; [VoiceFile.close] deletes it sooner.
     */
    fun newRecording(): VoiceFile {
        val f = newViewFile()
        Files.createFile(f.toPath())
        val raf = try {
            java.io.RandomAccessFile(f, "rw")
        } catch (e: IOException) {
            deleteQuietly(f)
            throw e
        }
        return VoiceFile(f, raf)
    }

    // ---- sealing (prep thread) ---------------------------------------------------------------------------------

    /**
     * Stores [bytes] encrypted in `prep/` through a direct buffer that is zeroed afterwards, success or not.
     * [bytes] stays the caller's to clear. [nameHint] is never written to disk: staging names are always random.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sealBytes(bytes: ByteArray, nameHint: String? = null): SealedFile {
        val out = newFile(ensureDir(prepDir))
        var buf: ByteBuffer? = null
        var sealed: SealedFile? = null
        try {
            val b = ByteBuffer.allocateDirect(bytes.size)
            buf = b
            b.put(bytes)
            rewind(b)
            val key = native.writeEncrypted(out, b)
            sealed = SealedFile(out, key, bytes.size.toLong())
            return sealed
        } finally {
            buf?.let { zero(it) }
            if (sealed == null) deleteQuietly(out)
        }
    }

    /**
     * Encrypts [plain] into `prep/`, then deletes [plain] whatever happens. [plain] must be a regular file inside
     * the media folder (a staged copy from [newViewFile]); anything else is refused and left alone.
     */
    fun sealFile(plain: File): SealedFile {
        require(insideMedia(plain)) { "staging file outside the media folder" }
        try {
            val size = plain.length()
            val out = newFile(ensureDir(prepDir))
            var sealed: SealedFile? = null
            try {
                val key = withNativePath(plain) { native.encryptFile(it, out) }
                sealed = SealedFile(out, key, size)
                return sealed
            } finally {
                if (sealed == null) deleteQuietly(out)
            }
        } finally {
            deleteQuietly(plain)
        }
    }

    // ---- the core's files folder (engine thread) ---------------------------------------------------------------

    /**
     * Moves [sealed] from `prep/` into the files folder under [name], or name_1, name_2 ... when taken. Never
     * replaces an existing file. Returns the bare name used (what `/_send` references).
     */
    fun commit(sealed: SealedFile, name: String): String {
        require(bareName(name)) { "not a bare name" }
        require(isChild(sealed.file, prepDir)) { "not a staged file" }
        val src = sealed.file.toPath()
        val dir = ensureDir(files)
        repeat(COMMIT_TRIES) {
            val candidate = MediaNames.unique(name) { File(dir, it).exists() || Files.isSymbolicLink(File(dir, it).toPath()) }
            if (!bareName(candidate)) throw VaultIoException("commit")
            try {
                // No REPLACE_EXISTING: fails rather than overwrite a file the core wrote in the meantime.
                val target = File(dir, candidate)
                Files.move(src, target.toPath())
                // A move keeps the sealing time; an attachment that waited in the composer counts from its send.
                try {
                    target.setLastModified(clock())
                } catch (e: SecurityException) {
                    SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "touch")
                }
                return candidate
            } catch (e: FileAlreadyExistsException) {
                // Taken between the check and the move: try the next suffix.
            } catch (e: IOException) {
                throw VaultIoException("commit")
            } catch (e: SecurityException) {
                throw VaultIoException("commit")
            } catch (e: InvalidPathException) {
                throw VaultIoException("commit")
            }
        }
        throw VaultIoException("commit")
    }

    /** Deletes a file we committed but couldn't send. False when there was nothing (inside the files folder) to delete. */
    fun uncommit(name: String): Boolean {
        val f = resolve(name) ?: return false
        return f.isFile && f.delete()
    }

    /**
     * The file in the core's files folder for [path] as the core reports it (`fileSource.filePath`). Only a bare
     * name is accepted, and only when it really is a direct child of the files folder: no separators, no `.` or
     * `..`, no NUL, and no symbolic link (not even one pointing inside). Otherwise null.
     */
    fun resolve(path: String?): File? {
        if (path == null || !bareName(path)) return null
        val f = File(files, path)
        return try {
            if (Files.isSymbolicLink(f.toPath())) return null
            val root = files.canonicalFile
            if (f.canonicalFile != File(root, path)) null else f
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: InvalidPathException) {
            null
        }
    }

    /**
     * A complete encrypted file for [path] can be here: a regular file inside the files folder, at least as long as
     * the core's tail tag. (The core creates an empty placeholder when a download is accepted, so a file merely
     * existing says nothing; readability still comes from the core's file status.)
     */
    fun present(path: String?): Boolean = complete(resolve(path))

    private fun complete(f: File?): Boolean = f != null && f.isFile && f.length() >= AUTH_TAG_BYTES

    // ---- retention (engine thread; [storedFiles] and [storage] anywhere) -----------------------------------------

    /**
     * Every regular file directly in the files folder (no links, no folders), with its size and last write. Entries
     * that can't be read are skipped (counted in the log). Nothing is opened or changed.
     */
    fun storedFiles(): List<StoredFile> {
        val dir = files.toPath()
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val out = ArrayList<StoredFile>()
        var failed = 0
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (p in entries) {
                    try {
                        val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        val name = p.fileName?.toString() ?: continue
                        if (a.isRegularFile && bareName(name)) out += StoredFile(name, a.size(), a.lastModifiedTime().toMillis())
                    } catch (e: IOException) {
                        failed++
                    } catch (e: SecurityException) {
                        failed++
                    }
                }
            }
        } catch (e: IOException) {
            failed++
        } catch (e: SecurityException) {
            failed++
        }
        if (failed > 0) SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "list", failed)
        return out
    }

    /** The encrypted media on this phone: bytes and count of the non-empty files (an emptied file is gone media). */
    fun storage(): MediaStorage {
        var bytes = 0L
        var count = 0
        for (f in storedFiles()) {
            if (f.bytes <= 0) continue
            bytes += f.bytes
            count++
        }
        return MediaStorage(bytes, count)
    }

    /**
     * Removes the media in [f] from this phone: the file is emptied in place (truncated to 0 bytes; it is ciphertext,
     * so nothing needs scrubbing) and keeps its name, so the core never hands that name to a newer file that deleting
     * the old message would then delete (the core frees names by existence, Files.hs uniqueCombine). An empty file is
     * not [present], so its message shows NOT_ON_DEVICE and every read refuses it.
     *
     * Only when it is still the very file that was listed: a regular file, not a link, same size and same last write.
     * False when it changed, vanished or couldn't be emptied.
     */
    fun expire(f: StoredFile): Boolean {
        if (f.bytes <= 0) return false
        val file = resolve(f.name) ?: return false
        val p = file.toPath()
        return try {
            val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!a.isRegularFile || a.size() != f.bytes || a.lastModifiedTime().toMillis() != f.modifiedMs) return false
            FileChannel.open(p, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.truncate(0) }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
    }

    // ---- reading (read thread) ---------------------------------------------------------------------------------

    /**
     * The whole plaintext of [path] in memory. Refused before reading when it's not here or was removed
     * ([MediaFailure.NOT_ON_DEVICE]) or its plaintext is over [cap] bytes ([MediaFailure.TOO_LARGE]). Native failures
     * throw [NativeFileException].
     */
    fun readBytes(path: String?, key: FileKey, cap: Long): ByteArray {
        val f = resolve(path)?.takeIf { complete(it) } ?: throw VaultException(MediaFailure.NOT_ON_DEVICE)
        if (f.length() - AUTH_TAG_BYTES > cap) throw VaultException(MediaFailure.TOO_LARGE)
        val bytes = withNativePath(f) { native.readDecrypted(it, key) }
        if (bytes.size > cap) {
            Arrays.fill(bytes, 0.toByte())
            throw VaultException(MediaFailure.TOO_LARGE)
        }
        return bytes
    }

    /**
     * Decrypts [path] into a new file in `view/` (random name, no extension) and returns it; the caller owns it
     * (a FileMediaReader deletes it on close). On any failure the output is deleted before the error goes up: a
     * failed chatDecryptFile leaves unauthenticated plaintext behind.
     */
    fun decryptToView(path: String?, key: FileKey): File {
        val f = resolve(path)?.takeIf { complete(it) } ?: throw VaultException(MediaFailure.NOT_ON_DEVICE)
        val out = newFile(ensureDir(viewDir))
        var ok = false
        try {
            withNativePath(f) { native.decryptFile(it, key, out) }
            if (!out.isFile) throw NativeFileException(SimplexNativeFiles.DECRYPT)
            ok = true
            return out
        } finally {
            if (!ok) deleteQuietly(out)
        }
    }

    // ---- space -------------------------------------------------------------------------------------------------

    /** Usable bytes on the media volume (0 when unknown). */
    fun freeBytes(): Long = try {
        val probe = if (media.isDirectory) media else if (files.isDirectory) files else media.parentFile ?: media
        maxOf(0L, probe.usableSpace)
    } catch (e: SecurityException) {
        0L
    }

    /** There is room to receive, seal or decrypt a file of [bytes] (MediaPolicy's free-space rule). */
    fun hasRoomFor(bytes: Long, config: MediaPolicyConfig = MediaPolicy.DEFAULT): Boolean =
        bytes >= 0 && MediaPolicy.hasFreeSpace(bytes, freeBytes(), config)

    override fun toString() = "MediaVault"

    // ---- internals ---------------------------------------------------------------------------------------------

    /**
     * Runs [block] with a path JNI can carry for [f]: [f] itself when its path is printable ASCII, otherwise a hard
     * link in `link/` under a random ASCII name (or, if links fail, a copy; [f] is ciphertext either way). The link
     * or copy is deleted afterwards, whatever happens.
     */
    private fun <T> withNativePath(f: File, block: (File) -> T): T {
        if (nativeSafe(f.absolutePath)) return block(f)
        val link = newFile(ensureDir(linkDir))
        if (!nativeSafe(link.absolutePath)) throw VaultIoException("link")
        try {
            try {
                hardLink(link.toPath(), f.toPath())
            } catch (e: Exception) {
                // Links can be refused (filesystem, SELinux): a ciphertext copy works the same.
                deleteQuietly(link)
                try {
                    Files.copy(f.toPath(), link.toPath())
                } catch (c: Exception) {
                    throw VaultIoException("link")
                }
            }
            return block(link)
        } finally {
            deleteQuietly(link)
        }
    }

    private fun ensureDir(dir: File): File {
        try {
            val p = dir.toPath()
            if (Files.isSymbolicLink(p)) Files.delete(p)
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(p)
                Files.createDirectories(p)
            }
        } catch (e: IOException) {
            throw VaultIoException("dirs")
        } catch (e: SecurityException) {
            throw VaultIoException("dirs")
        }
        return dir
    }

    /** A fresh random name in [dir] that doesn't exist yet (16 base32 characters, no extension). */
    private fun newFile(dir: File): File {
        while (true) {
            val sb = StringBuilder(RAND_CHARS)
            repeat(RAND_CHARS) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            val f = File(dir, sb.toString())
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return f
        }
    }

    /** True when [f]'s real location is a direct child of [dir] (no link in the last step). */
    private fun isChild(f: File, dir: File): Boolean = try {
        !Files.isSymbolicLink(f.toPath()) && f.canonicalFile.parentFile == dir.canonicalFile
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** True when [f] really lives somewhere under the media folder and is not a link. */
    private fun insideMedia(f: File): Boolean = try {
        val root = media.canonicalFile
        !Files.isSymbolicLink(f.toPath()) && f.canonicalFile.let { c -> c != root && c.startsWith(root) }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Deletes [p] without following links; (deleted, failed). */
    private fun deleteTree(p: Path): Pair<Int, Int> {
        var deleted = 0
        var failed = 0
        try {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(p).use { entries ->
                    for (e in entries) {
                        val r = deleteTree(e)
                        deleted += r.first
                        failed += r.second
                    }
                }
            }
            if (Files.deleteIfExists(p)) deleted++
        } catch (e: IOException) {
            failed++
        } catch (e: SecurityException) {
            failed++
        }
        return deleted to failed
    }

    private fun deleteQuietly(f: File) {
        try {
            if (!f.delete() && (f.exists() || Files.isSymbolicLink(f.toPath()))) {
                SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
            }
        } catch (e: SecurityException) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
        }
    }

    companion object {
        const val PREP = "prep"
        const val VIEW = "view"
        const val LINK = "link"

        /** The core's at-rest files carry a 16-byte tail tag (simplexmq Crypto/File.hs, C.authTagSize). */
        const val AUTH_TAG_BYTES = 16L

        private const val COMMIT_TRIES = 8
        private const val RAND_CHARS = 16
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
        private const val MAX_NAME_BYTES = 255

        /**
         * A path JNI hands to the core unchanged: printable ASCII only. Stricter than modified UTF-8 needs (that
         * only breaks outside the BMP and on NUL) on purpose: it doesn't depend on the core's C-string encoding.
         */
        fun nativeSafe(path: String): Boolean = SimplexNativeFiles.pathOk(path)

        /** One path component: not empty, not `.` or `..`, no `/`, `\` or NUL, at most 255 UTF-8 bytes. */
        fun bareName(name: String): Boolean {
            if (name.isEmpty() || name == "." || name == "..") return false
            if (name.any { it == '/' || it == '\\' || it == '\u0000' }) return false
            return name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES
        }

        private fun zero(b: ByteBuffer) {
            clear(b)
            val z = ByteArray(minOf(b.capacity(), 64 * 1024))
            while (b.hasRemaining()) b.put(z, 0, minOf(z.size, b.remaining()))
            clear(b)
        }

        // Called through Buffer on purpose: newer SDKs declare ByteBuffer overrides with covariant returns that
        // API 26-27 devices don't have (NoSuchMethodError).
        private fun rewind(b: Buffer) {
            b.rewind()
        }

        private fun clear(b: Buffer) {
            b.clear()
        }
    }
}

/** A refusal the UI can word: [MediaFailure.NOT_ON_DEVICE] or [MediaFailure.TOO_LARGE]. */
internal class VaultException(val failure: MediaFailure) : Exception(failure.name)

/** Local storage failed (disk, permissions). [tag] is a fixed word (dirs, commit, link); never a path. */
internal class VaultIoException(val tag: String) : IOException(tag)
