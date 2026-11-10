package app.encrogram.core.internal.media.prep

import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.DataUrl
import app.encrogram.core.internal.media.ImageHeaders
import app.encrogram.core.internal.media.MediaKindRules
import app.encrogram.core.internal.media.MediaNames
import app.encrogram.core.internal.media.MediaPolicy
import app.encrogram.core.internal.media.MediaPolicyConfig
import app.encrogram.core.internal.media.MediaVault
import app.encrogram.core.internal.media.Prepared
import app.encrogram.core.internal.media.SealedFile
import app.encrogram.core.internal.media.Sniffed
import app.encrogram.core.internal.media.VoiceFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Arrays

/**
 * Turns one picked item into a sealed, metadata-free file (docs/media-plan.md §4.4 as amended). Pure JVM: everything
 * Android sits behind [InputOpener], [ImageCodec] and [VideoTool]. Runs on the prep thread.
 *
 * - IMAGE: decoded and re-encoded in memory (no EXIF can survive), asserted clean, sealed from memory.
 * - GIF and animated WebP: sanitized in memory (20 MiB at most), sealed from memory.
 * - VIDEO (MP4, MOV): staged in `view/`, metadata zeroed in place, sealed, staging file deleted on every path.
 * - FILE: byte for byte. In memory up to the policy's seal limit, otherwise staged in `view/` and deleted.
 * - VOICE: the engine's own recording ([MediaInput.Recorded], already in `view/`): size, length and ftyp checked,
 *   metadata zeroed in place, sealed; the recording is closed and deleted on every path.
 *
 * Every temporary plaintext file is deleted on success and failure, every [Picture] is released exactly once, and
 * plaintext arrays are zeroed here (the vault zeroes only its own direct buffer). The log gets a kind or a reason
 * name, never a name, path, picked MIME type or bytes.
 */
internal class MediaPreparer(
    private val opener: InputOpener,
    private val images: ImageCodec,
    private val videos: VideoTool,
    private val vault: MediaVault,
    private val config: MediaPolicyConfig = MediaPolicy.DEFAULT,
) : Preparer {

    override fun prepare(input: MediaInput): Prepared {
        try {
            if (input is MediaInput.Recorded) {
                return prepareVoice(input).also { SecuredLog.i(SecuredLog.Ev.MEDIA_PREPARED, it.kind.name) }
            }
            val picked = input as? MediaInput.Picked ?: throw PrepException(PrepException.Reason.UNSUPPORTED)
            val src = try {
                opener.open(picked)
            } catch (e: PrepException) {
                throw e
            } catch (e: Exception) {
                throw PrepException(PrepException.Reason.UNREADABLE)
            }
            val prepared = src.use { s ->
                val head = try {
                    s.head(MediaKindRules.HEAD_BYTES)
                } catch (e: PrepException) {
                    throw e
                } catch (e: Exception) {
                    throw PrepException(PrepException.Reason.UNREADABLE)
                }
                // Read to the end of the stream and nothing came: an empty file, as a photo or as a file (H7).
                if (head.isEmpty()) throw PrepException(PrepException.Reason.EMPTY)
                val declared = s.declaredSize
                if (declared != null && declared > config.capBytes) throw PrepException(PrepException.Reason.TOO_LARGE)
                requireRoom(maxOf(declared ?: 0L, 0L))

                val sniffed = MediaKindRules.sniff(head)
                val k = MediaKindRules.kindForSending(head, picked.asFile)
                // Fail closed: a picture or video we can't process must not slip through as a document with its
                // metadata. The picker's MIME type only ever turns this into a refusal, never into a kind.
                if (k == MediaKind.FILE && !picked.asFile && isMediaMime(s.mimeType)) {
                    throw PrepException(PrepException.Reason.UNSUPPORTED)
                }
                when (k) {
                    MediaKind.IMAGE -> prepareImage(s, sniffed)
                    MediaKind.GIF -> prepareGif(s, sniffed)
                    MediaKind.VIDEO -> prepareVideo(s, sniffed)
                    else -> prepareFile(s)
                }
            }
            SecuredLog.i(SecuredLog.Ev.MEDIA_PREPARED, prepared.kind.name)
            return prepared
        } catch (e: PrepException) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_PREPARE_FAILED, e.reason.name)
            throw e
        } catch (e: OutOfMemoryError) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_PREPARE_FAILED, PrepException.Reason.UNSUPPORTED.name)
            throw PrepException(PrepException.Reason.UNSUPPORTED)
        } catch (e: Exception) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_PREPARE_FAILED, PrepException.Reason.UNREADABLE.name)
            throw PrepException(PrepException.Reason.UNREADABLE)
        }
    }

    // ---- image -------------------------------------------------------------------------------------------------

    private fun prepareImage(src: PickedSource, sniffed: Sniffed): Prepared {
        val pic = try {
            images.decodeForSend(src, IMAGE_SIDE)
        } catch (e: PrepException) {
            throw e
        } catch (e: IOException) {
            throw PrepException(PrepException.Reason.UNREADABLE)
        } catch (e: RuntimeException) {
            throw PrepException(PrepException.Reason.UNSUPPORTED)
        }
        val (jpeg, preview) = try {
            val j = images.jpeg(pic, IMAGE_QUALITY)
            try {
                j to previewOf(pic)
            } catch (t: Throwable) {
                zero(j)
                throw t
            }
        } catch (e: RuntimeException) {
            throw PrepException(PrepException.Reason.UNSUPPORTED)
        } finally {
            pic.release()
        }
        try {
            // The final assert: a re-encoded picture must carry no metadata block. Fail closed.
            if (ImageHeaders.hasMetadata(jpeg)) throw PrepException(PrepException.Reason.UNSUPPORTED)
            requireRoom(jpeg.size.toLong())
            val name = MediaNames.neutral(MediaKind.IMAGE, sniffed)
            val sealed = sealBytes(jpeg, name)
            return Prepared(MediaKind.IMAGE, name, "image/jpeg", preview, null, false, sealed)
        } finally {
            zero(jpeg)
        }
    }

    // ---- GIF and animated WebP ---------------------------------------------------------------------------------

    private fun prepareGif(src: PickedSource, sniffed: Sniffed): Prepared {
        val raw = readLimited(src.stream(), GIF_MAX_BYTES, exact = true)
        if (!raw.complete || raw.size == 0) {
            zero(raw.bytes)
            throw PrepException(if (raw.size == 0) PrepException.Reason.UNREADABLE else PrepException.Reason.UNSUPPORTED)
        }
        val clean: ByteArray?
        try {
            clean = if (sniffed == Sniffed.GIF) GifSanitizer.sanitize(raw.bytes) else WebpSanitizer.sanitize(raw.bytes)
        } finally {
            zero(raw.bytes)
        }
        if (clean == null) throw PrepException(PrepException.Reason.UNSUPPORTED)
        try {
            val pic = try {
                images.decodeFirstFrame(clean, PreviewBudget.START_SIDE)
            } catch (e: RuntimeException) {
                null
            }
            val preview = if (pic == null) DataUrl.PLACEHOLDER else try { previewOf(pic) } finally { pic.release() }
            requireRoom(clean.size.toLong())
            val name = MediaNames.neutral(MediaKind.GIF, sniffed)
            val mime = if (sniffed == Sniffed.GIF) "image/gif" else "image/webp"
            val sealed = sealBytes(clean, name)
            return Prepared(MediaKind.GIF, name, mime, preview, null, false, sealed)
        } finally {
            zero(clean)
        }
    }

    // ---- video -------------------------------------------------------------------------------------------------

    private fun prepareVideo(src: PickedSource, sniffed: Sniffed): Prepared {
        val view = try {
            vault.newViewFile()
        } catch (e: Exception) {
            throw PrepException(PrepException.Reason.STORAGE)
        }
        try {
            val size = stage(view, null, src.stream())
            requireRoom(size)
            val clean = try {
                RandomAccessFile(view, "rw").use { Mp4Meta.neutralize(it) }
            } catch (e: IOException) {
                false
            }
            if (!clean) throw PrepException(PrepException.Reason.UNSUPPORTED)
            val info = try {
                videos.probe(view)
            } catch (e: RuntimeException) {
                null
            } ?: throw PrepException(PrepException.Reason.UNSUPPORTED)
            val pic = try {
                videos.frame(view, PreviewBudget.START_SIDE)
            } catch (e: RuntimeException) {
                null
            }
            val preview = if (pic == null) DataUrl.PLACEHOLDER else try { previewOf(pic) } finally { pic.release() }
            val name = MediaNames.neutral(MediaKind.VIDEO, sniffed)
            val mime = if (sniffed == Sniffed.MOV) "video/quicktime" else "video/mp4"
            val sealed = sealStaged(view)
            return Prepared(MediaKind.VIDEO, name, mime, preview, maxOf(info.durationSeconds, 0), false, sealed)
        } finally {
            deleteQuietly(view)
        }
    }

    // ---- voice -------------------------------------------------------------------------------------------------

    /**
     * Only our own [VoiceFile] is accepted (anything else is closed and refused). Empty: UNREADABLE; over the policy's
     * size or length: TOO_LARGE; not an MPEG-4 `ftyp` file, or metadata that can't be neutralized: UNSUPPORTED.
     */
    private fun prepareVoice(input: MediaInput.Recorded): Prepared {
        val recording = input.recording as? VoiceFile ?: run {
            try {
                input.recording.close()
            } catch (e: Exception) {
                // not ours; nothing more to do
            }
            throw PrepException(PrepException.Reason.UNSUPPORTED)
        }
        val file = recording.finish() ?: throw PrepException(PrepException.Reason.UNREADABLE)
        try {
            val size = file.length()
            if (size <= 0L) throw PrepException(PrepException.Reason.UNREADABLE)
            if (size > config.voiceMaxBytes || MediaPolicy.voiceTooLong(input.durationMs, config)) {
                throw PrepException(PrepException.Reason.TOO_LARGE)
            }
            val head = try {
                RandomAccessFile(file, "r").use { f ->
                    val b = ByteArray(minOf(size, MediaKindRules.HEAD_BYTES.toLong()).toInt())
                    f.readFully(b)
                    b
                }
            } catch (e: IOException) {
                throw PrepException(PrepException.Reason.UNREADABLE)
            }
            val sniffed = MediaKindRules.sniff(head)
            if (sniffed != Sniffed.MP4 && sniffed != Sniffed.M4A) throw PrepException(PrepException.Reason.UNSUPPORTED)
            requireRoom(size)
            val clean = try {
                RandomAccessFile(file, "rw").use { Mp4Meta.neutralize(it) }
            } catch (e: IOException) {
                false
            }
            if (!clean) throw PrepException(PrepException.Reason.UNSUPPORTED)
            val name = MediaNames.neutral(MediaKind.VOICE)
            val sealed = sealStaged(file)
            return Prepared(MediaKind.VOICE, name, "audio/mp4", null, MediaPolicy.voiceSeconds(input.durationMs, config), false, sealed)
        } finally {
            deleteQuietly(file)
        }
    }

    // ---- file --------------------------------------------------------------------------------------------------

    private fun prepareFile(src: PickedSource): Prepared {
        val name = MediaNames.sanitizeDocumentName(src.displayName ?: "")
        val mime = MediaKindRules.mimeFor(name)
        val first = readLimited(src.stream(), config.memorySealLimit, exact = true)
        if (first.complete) {
            try {
                if (first.size == 0) throw PrepException(PrepException.Reason.UNREADABLE)
                requireRoom(first.size.toLong())
                val sealed = sealBytes(first.bytes, name)
                return Prepared(MediaKind.FILE, name, mime, null, null, true, sealed)
            } finally {
                zero(first.bytes)
            }
        }
        // Larger than the memory limit: what was read goes to a staging file first, then the rest of the stream.
        val view = try {
            vault.newViewFile()
        } catch (e: Exception) {
            zero(first.bytes)
            throw PrepException(PrepException.Reason.STORAGE)
        }
        try {
            val size = stage(view, first, first.rest!!)
            requireRoom(size)
            val sealed = sealStaged(view)
            return Prepared(MediaKind.FILE, name, mime, null, null, true, sealed)
        } finally {
            deleteQuietly(view)
        }
    }

    // ---- shared pieces -----------------------------------------------------------------------------------------

    /** Not enough room for a file of [bytes] and its sealed copy (the policy's rule) is STORAGE. */
    private fun requireRoom(bytes: Long) {
        if (!vault.hasRoomFor(bytes, config)) throw PrepException(PrepException.Reason.STORAGE)
    }

    private fun sealBytes(bytes: ByteArray, name: String): SealedFile = try {
        vault.sealBytes(bytes, name)
    } catch (e: Exception) {
        throw PrepException(PrepException.Reason.STORAGE)
    }

    /** [MediaVault.sealFile] deletes the staged file whatever happens. */
    private fun sealStaged(view: File): SealedFile = try {
        vault.sealFile(view)
    } catch (e: Exception) {
        throw PrepException(PrepException.Reason.STORAGE)
    }

    /**
     * The best preview of [pic] within the character budget, as a data URL. [pic] stays the caller's to release;
     * a scaled copy is released here, but never [pic] itself (the Android codec returns it when it already fits).
     */
    private fun previewOf(pic: Picture): String {
        val bytes = try {
            PreviewBudget.fit(config.previewChars) { side, quality ->
                val scaled = images.scale(pic, side)
                try {
                    images.jpeg(scaled, quality)
                } finally {
                    if (scaled !== pic) scaled.release()
                }
            }
        } catch (e: RuntimeException) {
            null
        }
        return if (bytes == null) DataUrl.PLACEHOLDER else DataUrl.encodeJpeg(bytes)
    }

    /** Result of [readLimited]: [rest] continues the stream when [complete] is false. */
    private class Chunk(val bytes: ByteArray, val size: Int, val complete: Boolean, val rest: InputStream?)

    /**
     * Reads at most [limit] bytes into one array (grown by doubling; replaced arrays are zeroed). With [exact] the
     * array has exactly the data's length; [complete] says the stream ended within the limit. Never reads more than
     * the global cap either, whatever the source claims.
     */
    private fun readLimited(stream: InputStream, limit: Long, exact: Boolean): Chunk {
        val max = minOf(limit, config.capBytes, MAX_ARRAY.toLong()).toInt()
        var buf = ByteArray(minOf(INITIAL_BUFFER, max))
        var n = 0
        try {
            while (true) {
                if (n == buf.size) {
                    if (n >= max) {
                        // At the limit: one more byte tells whether the stream really ended.
                        val extra = stream.read()
                        if (extra < 0) break
                        val joined = SequenceInputStream2(byteArrayOf(extra.toByte()), stream)
                        return Chunk(trim(buf, n, exact), n, false, joined)
                    }
                    val bigger = buf.copyOf(minOf(buf.size.toLong() * 2, max.toLong()).toInt())
                    zero(buf)
                    buf = bigger
                }
                val r = stream.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        } catch (e: PrepException) {
            zero(buf)
            throw e
        } catch (e: IOException) {
            zero(buf)
            throw PrepException(PrepException.Reason.UNREADABLE)
        } catch (e: RuntimeException) {
            zero(buf)
            throw PrepException(PrepException.Reason.UNREADABLE)
        }
        return Chunk(trim(buf, n, exact), n, true, null)
    }

    private fun trim(buf: ByteArray, n: Int, exact: Boolean): ByteArray {
        if (!exact || buf.size == n) return buf
        val out = buf.copyOf(n)
        zero(buf)
        return out
    }

    /**
     * Writes [first] (when given; zeroed afterwards) and then [rest] into [out], counting every byte against the
     * global cap whatever the source declared. Returns the byte count. Read failures are UNREADABLE, write failures STORAGE.
     */
    private fun stage(out: File, first: Chunk?, rest: InputStream): Long {
        var total = 0L
        val chunk = ByteArray(STAGE_BUFFER)
        try {
            val sink = try {
                FileOutputStream(out)
            } catch (e: IOException) {
                throw PrepException(PrepException.Reason.STORAGE)
            }
            sink.use { os ->
                try {
                    if (first != null) {
                        total += first.size
                        if (total > config.capBytes) throw PrepException(PrepException.Reason.TOO_LARGE)
                        os.write(first.bytes, 0, first.size)
                        zero(first.bytes)
                    }
                } catch (e: IOException) {
                    throw PrepException(PrepException.Reason.STORAGE)
                }
                while (true) {
                    val r = try {
                        rest.read(chunk)
                    } catch (e: PrepException) {
                        throw e
                    } catch (e: IOException) {
                        throw PrepException(PrepException.Reason.UNREADABLE)
                    }
                    if (r < 0) break
                    total += r
                    if (total > config.capBytes) throw PrepException(PrepException.Reason.TOO_LARGE)
                    try {
                        os.write(chunk, 0, r)
                    } catch (e: IOException) {
                        throw PrepException(PrepException.Reason.STORAGE)
                    }
                }
            }
        } finally {
            first?.let { zero(it.bytes) }
            zero(chunk)
        }
        if (total == 0L) throw PrepException(PrepException.Reason.UNREADABLE)
        return total
    }

    /** One byte already taken from [tail], put back in front of it. */
    private class SequenceInputStream2(private val head: ByteArray, private val tail: InputStream) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < head.size) head[pos++].toInt() and 0xFF else tail.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos < head.size) {
                val n = minOf(len, head.size - pos)
                System.arraycopy(head, pos, b, off, n)
                pos += n
                return n
            }
            return tail.read(b, off, len)
        }

        override fun close() = tail.close()
    }

    private fun deleteQuietly(f: File) {
        try {
            if (!f.delete() && f.exists()) SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
        } catch (e: SecurityException) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
        }
    }

    private fun zero(b: ByteArray) = Arrays.fill(b, 0.toByte())

    private fun isMediaMime(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return false
        return m.startsWith("image/") || m.startsWith("video/")
    }

    private companion object {
        const val IMAGE_SIDE = 1280
        const val IMAGE_QUALITY = 82
        const val GIF_MAX_BYTES = 20L * 1024 * 1024
        const val INITIAL_BUFFER = 64 * 1024
        const val STAGE_BUFFER = 64 * 1024
        const val MAX_ARRAY = Int.MAX_VALUE - 16
    }
}
