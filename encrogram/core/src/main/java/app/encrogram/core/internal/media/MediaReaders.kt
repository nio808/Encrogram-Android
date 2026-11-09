package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MediaReader
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Random-access plaintext for the UI (docs/media-plan.md §2 point 7, §5.3). Two sources in phase 1: memory and a
 * private file in media/view/. A later chunk stream becomes a third MediaReader whose readAt may block and whose
 * available() grows; the UI doesn't change.
 *
 * Threads: readAt on the player's thread (never main); close() from any thread, idempotent. readAt and close share
 * one lock, so a close never zeroes or deletes under a read in progress, and every readAt after close returns -1.
 */

/** Checks the MediaDataSource-style arguments (a negative position or a range outside [buffer] is a caller bug). */
private fun checkRange(position: Long, buffer: ByteArray, offset: Int, length: Int) {
    if (position < 0) throw IllegalArgumentException("position")
    if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException("range")
}

/** Plaintext in memory. Takes ownership of [bytes] (no copy); [close] zero-fills it. */
internal class ByteArrayMediaReader(private val bytes: ByteArray) : MediaReader {

    private val closed = AtomicBoolean(false)
    private val lock = Any()

    override fun size(): Long = bytes.size.toLong()

    override fun available(): Long = size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        checkRange(position, buffer, offset, length)
        synchronized(lock) {
            if (closed.get() || position >= bytes.size) return -1
            if (length == 0) return 0
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { Arrays.fill(bytes, 0.toByte()) }
    }

    val isClosed: Boolean get() = closed.get()

    override fun toString() = "ByteArrayMediaReader(${bytes.size} bytes)"
}

/**
 * Plaintext in a private file (a `view/` file from MediaVault.decryptToView). Owns the file: [close] closes it and
 * deletes it. If the file can't be opened it is deleted at once and the constructor throws.
 */
internal class FileMediaReader(private val file: File) : MediaReader {

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val raf: RandomAccessFile
    private val length: Long

    init {
        val (r, l) = openOrDelete(file)
        raf = r
        length = l
    }

    override fun size(): Long = length

    override fun available(): Long = size()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        checkRange(position, buffer, offset, length)
        synchronized(lock) {
            if (closed.get() || position >= this.length) return -1
            if (length == 0) return 0
            val want = minOf(length.toLong(), this.length - position).toInt()
            return try {
                raf.seek(position)
                raf.read(buffer, offset, want)
            } catch (e: IOException) {
                throw MediaReaderException()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            try {
                raf.close()
            } catch (_: IOException) {
            } finally {
                // Close first: on Linux an open descriptor keeps the plaintext's blocks alive after unlink.
                if (file.exists() && !file.delete()) SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "reader_delete")
            }
        }
    }

    val isClosed: Boolean get() = closed.get()

    override fun toString() = "FileMediaReader($length bytes)"

    private companion object {
        fun openOrDelete(file: File): Pair<RandomAccessFile, Long> {
            var opened: RandomAccessFile? = null
            try {
                opened = RandomAccessFile(file, "r")
                return opened to opened.length()
            } catch (t: Throwable) {
                try {
                    opened?.close()
                } catch (_: IOException) {
                }
                if (file.exists() && !file.delete()) SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "reader_delete")
                if (t is IOException) throw MediaReaderException()
                throw t
            }
        }
    }
}

/** A local read failed. No path or system message (those can name the file). */
internal class MediaReaderException : IOException("media_read")

/**
 * An opened video or voice message. [close] closes the reader (deleting a `view/` file at once) and then tells
 * [onClose] (the registry), exactly once, from whichever thread closed it.
 */
internal class VaultPlayback(
    override val kind: MediaKind,
    override val mimeType: String?,
    override val durationSeconds: Int?,
    override val reader: MediaReader,
    private val onClose: ((VaultPlayback) -> Unit)? = null,
) : MediaPlayback {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            reader.close()
        } finally {
            onClose?.invoke(this)
        }
    }

    val isClosed: Boolean get() = closed.get()

    override fun toString() = "VaultPlayback($kind)"
}

/**
 * The open playbacks, keyed by (chatId, messageId), so a deleted message, a wipe or a stop can close them
 * (docs/media-plan.md §5.3 safety nets). A message may have more than one open at a time. Any thread.
 * Playbacks are closed outside the registry's lock, so a playback's own close may call back into [unregister].
 */
internal class PlaybackRegistry {

    private data class Key(val chatId: Long, val messageId: Long)

    private val open = HashMap<Key, MutableList<MediaPlayback>>()

    /** The bare name of the encrypted file each playback was opened from (guarded by [open]); retention keeps those. */
    private val sourceOf = IdentityHashMap<MediaPlayback, String>()

    /** Opens a [VaultPlayback] over [reader] and registers it; closing it unregisters it. [source]: the file it reads. */
    fun open(
        chatId: Long,
        messageId: Long,
        kind: MediaKind,
        mimeType: String?,
        durationSeconds: Int?,
        reader: MediaReader,
        source: String? = null,
    ): VaultPlayback {
        val p = VaultPlayback(kind, mimeType, durationSeconds, reader) { unregister(it) }
        register(chatId, messageId, p, source)
        return p
    }

    fun register(chatId: Long, messageId: Long, playback: MediaPlayback, source: String? = null) {
        synchronized(open) {
            open.getOrPut(Key(chatId, messageId)) { ArrayList(1) }.add(playback)
            if (source != null) sourceOf[playback] = source
        }
    }

    fun unregister(playback: MediaPlayback) {
        synchronized(open) {
            val iter = open.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.value.removeAll { p -> p === playback } && e.value.isEmpty()) iter.remove()
            }
            sourceOf.remove(playback)
        }
    }

    /** Closes every playback of [messageIds] in [chatId] (messages deleted). Returns how many were closed. */
    fun closeFor(chatId: Long, messageIds: Collection<Long>): Int {
        val victims = ArrayList<MediaPlayback>()
        synchronized(open) {
            for (id in messageIds) open.remove(Key(chatId, id))?.let { victims.addAll(it) }
            for (p in victims) sourceOf.remove(p)
        }
        return closeQuietly(victims)
    }

    /** Closes every playback of [chatId] (chat deleted or cleared). */
    fun closeChat(chatId: Long): Int {
        val victims = ArrayList<MediaPlayback>()
        synchronized(open) {
            val iter = open.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.key.chatId == chatId) {
                    victims.addAll(e.value)
                    iter.remove()
                }
            }
            for (p in victims) sourceOf.remove(p)
        }
        return closeQuietly(victims)
    }

    /** Closes everything (stop, wipe). */
    fun closeAll(): Int {
        val victims = ArrayList<MediaPlayback>()
        synchronized(open) {
            for (list in open.values) victims.addAll(list)
            open.clear()
            sourceOf.clear()
        }
        return closeQuietly(victims)
    }

    fun count(): Int = synchronized(open) { open.values.sumOf { it.size } }

    /** The files open playbacks were read from (bare names): never removed while open. */
    fun sources(): Set<String> = synchronized(open) { HashSet(sourceOf.values) }

    private fun closeQuietly(victims: List<MediaPlayback>): Int {
        for (p in victims) {
            try {
                p.close()
            } catch (t: Throwable) {
                SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, t)
            }
        }
        return victims.size
    }

    override fun toString() = "PlaybackRegistry(${count()})"
}
