package app.encrogram.core.api

import java.io.Closeable

/*
 * Secured media in the contract (docs/media-plan.md). Media is addressed only by (chatId, messageId) plus the opaque
 * [PreparedMedia] and [MediaPlayback]: no file id, path, key, server, description or protocol name ever appears here,
 * so the storage and transport underneath can be replaced without touching the UI.
 */

/** What a message carries. Incoming: from the message type and file name (a .gif or animated .webp image is GIF). */
enum class MediaKind { IMAGE, GIF, VIDEO, VOICE, FILE }

enum class MediaState {
    /** Incoming, not on this phone: tap to download ([SecuredEngine.acceptMedia]). */
    WAITING,
    /** Incoming, downloading, or queued until the file can be checked ([SecuredMedia.transferredBytes] 0). */
    DOWNLOADING,
    /** Outgoing, uploading. Already readable here. The message shows the clock until the upload is complete. */
    UPLOADING,
    /** On this phone (and, when outgoing, on the server). */
    READY,
    /** Stopped; [SecuredMedia.failure] says why. */
    FAILED,
}

/** Why a file is [MediaState.FAILED]. The UI words each one (research/gaps-audit.md §3.3). */
enum class MediaFailure {
    /** No longer on the server (files are kept 48 hours). */
    EXPIRED,
    /** The Secured file server refused the upload. */
    SERVER_FULL,
    /** Stored on a server outside Encrogram: never downloaded. */
    FOREIGN_SERVER,
    /** Over 100 MB, or larger than announced. */
    TOO_LARGE,
    /** Our check of how the file is stored failed (malformed, redirected, size mismatch): never downloaded. */
    CHECK_FAILED,
    /** Cancelled here or by the sender. */
    CANCELLED,
    SEND_FAILED,
    RECEIVE_FAILED,
    /** Arrived but failed its integrity check. */
    DAMAGED,
    /** A transfer type Encrogram doesn't accept (not through our file server). */
    UNSUPPORTED,
    /** The message exists but its file isn't on this phone (for example after a restore). */
    NOT_ON_DEVICE,
}

/** A small JPEG or PNG preview that travelled inside the message. Untrusted: decode in memory only, with bounds. */
class MediaPreview(bytes: ByteArray) {
    private val data = bytes.copyOf()
    val size: Int get() = data.size
    fun bytes(): ByteArray = data.copyOf()
    override fun equals(other: Any?) = other is MediaPreview && other.data.contentEquals(data)
    override fun hashCode() = data.contentHashCode()
    override fun toString() = "MediaPreview(${data.size} bytes)"
}

/** The media part of a [SecuredMessage]. Never log it: [name] is user content (toString leaves it out). */
data class SecuredMedia @JvmOverloads constructor(
    val kind: MediaKind,
    /** The file's name as sent. Shown for [MediaKind.FILE]; other kinds carry neutral names. */
    val name: String,
    val sizeBytes: Long,
    val state: MediaState,
    val failure: MediaFailure? = null,
    /** A snapshot; live updates come through [SecuredListener.onMediaProgress]. */
    val transferredBytes: Long = 0L,
    /** [SecuredEngine.readMediaBytes] / [SecuredEngine.openMediaForPlayback] can open it now. */
    val readable: Boolean = false,
    val mimeType: String? = null,
    val preview: MediaPreview? = null,
    val durationSeconds: Int? = null,
) {
    override fun toString() = "SecuredMedia($kind, $state${failure?.let { ", $it" } ?: ""})"
}

/** What the user picked. */
sealed class MediaInput {
    /** A content:// Uri from the photo picker or the document picker. [asFile]: send unchanged, as a document. */
    data class Picked @JvmOverloads constructor(val uri: String, val asFile: Boolean = false) : MediaInput() {
        override fun toString() = "Picked(asFile=$asFile)"
    }
    /** A finished recording from [SecuredEngine.startVoiceRecording]; [durationMs] as the recorder measured it. prepareMedia takes ownership (closes and deletes it on every path). */
    class Recorded(val recording: VoiceRecording, val durationMs: Long) : MediaInput() {
        override fun toString() = "Recorded"
    }
}

/**
 * A voice message being recorded into a private file the engine owns (in the vault's view/, so it is wiped at
 * start and stop). The UI only writes to it through MediaRecorder.setOutputFile(fileDescriptor) and never sees a path.
 */
interface VoiceRecording : java.io.Closeable {
    /** Seekable, read-write, for android.media.MediaRecorder.setOutputFile(FileDescriptor). Valid until close or prepareMedia. */
    val fileDescriptor: java.io.FileDescriptor
    /** Discard: closes the descriptor and deletes the file. Idempotent. */
    override fun close()
}

/**
 * Made ready to send by [SecuredEngine.prepareMedia]: metadata removed, preview made, stored encrypted. Send it once
 * with [SecuredEngine.sendMedia], or hand it back with [SecuredEngine.discardMedia].
 */
class PreparedMedia internal constructor(
    val kind: MediaKind,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val preview: MediaPreview?,
    val durationSeconds: Int?,
    /** Documents are sent exactly as they are (the UI may say so). */
    val unchanged: Boolean,
    internal val token: Long,
) {
    override fun toString() = "PreparedMedia($kind)"
}

/** Random-access plaintext. Never call it on the main thread. Today memory or a private file; later chunk streaming. */
interface MediaReader : Closeable {
    fun size(): Long
    /** Bytes readable from the start right now; equal to [size] until streaming exists. */
    fun available(): Long
    /** Like android.media.MediaDataSource.readAt: the bytes read, or -1 at the end (or after close). May block later. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** An opened video or voice message. [close] releases it (a private decrypted copy is deleted at once). Idempotent. */
interface MediaPlayback : Closeable {
    val kind: MediaKind
    val mimeType: String?
    val durationSeconds: Int?
    val reader: MediaReader
}
