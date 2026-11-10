package app.encrogram.core.internal.media.prep

import app.encrogram.core.api.MediaInput
import app.encrogram.core.internal.media.Prepared
import java.io.Closeable
import java.io.File
import java.io.InputStream

/*
 * The Android side of media preparation, behind small interfaces so MediaPreparer is tested on the JVM with fakes
 * (docs/media-plan.md §4.4). The Android adapters live in prep/android/ and use platform APIs only: never Telegram's
 * ImageLoader, FileLoader or MediaController.
 */

/** Opens what the user picked. Accepts content:// only, and never our own app's authority. */
internal interface InputOpener {
    fun open(input: MediaInput.Picked): PickedSource
}

/** One picked item. [stream] counts bytes and throws once past the cap, even when [declaredSize] lies. */
internal interface PickedSource : Closeable {
    /** OpenableColumns.DISPLAY_NAME: user content, never logged. */
    val displayName: String?
    val mimeType: String?
    val declaredSize: Long?

    /** The first [n] bytes (for sniffing the real type). */
    fun head(n: Int): ByteArray

    fun stream(): InputStream
}

/** A decoded image held by the codec (a Bitmap on Android). Release it when done. */
internal interface Picture {
    val width: Int
    val height: Int
    fun release()
}

internal interface ImageCodec {
    /** Sampled decode to at most [maxSide] on the long side, with the EXIF orientation applied. */
    fun decodeForSend(src: PickedSource, maxSide: Int): Picture

    /** First frame of an image or animation in memory, or null. */
    fun decodeFirstFrame(bytes: ByteArray, maxSide: Int): Picture?

    fun scale(picture: Picture, maxSide: Int): Picture

    /** JPEG bytes; Bitmap.compress writes no EXIF. */
    fun jpeg(picture: Picture, quality: Int): ByteArray
}

/** What a video file is. Rotation is the container's own hint (kept: it is needed to show the video upright). */
internal class VideoInfo(val durationSeconds: Int, val width: Int, val height: Int, val rotation: Int)

/**
 * Video facts and a preview frame from a local (staged) file. Metadata is removed separately, in place, by the pure
 * Mp4Meta (hybrid plan: no re-encode, no remux).
 */
internal interface VideoTool {
    fun probe(file: File): VideoInfo?
    fun frame(file: File, maxSide: Int): Picture?
}

/** The coordinator's seam onto preparation (prep thread). Throws PrepException with a MediaFailure-like reason. */
internal fun interface Preparer {
    fun prepare(input: MediaInput): Prepared
}

/**
 * Why preparing failed: maps to SecuredError kinds TOO_LARGE, UNSUPPORTED or STORAGE, or a generic failure. EMPTY: the
 * picked item has no bytes at all (picking it again can't help, unlike UNREADABLE).
 */
internal class PrepException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { TOO_LARGE, UNSUPPORTED, STORAGE, UNREADABLE, CANCELLED, EMPTY }
}
