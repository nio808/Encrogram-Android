package app.encrogram.core.internal.media.prep

import app.encrogram.core.api.MediaInput
import app.encrogram.core.internal.media.DataUrl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Fakes for the Android side of media preparation (MediaPreparer tests). The codec's output is a fixed, clean JPEG
 * (padded with zeros after EOI to a size that depends on side and quality), so a test can tell it from the source.
 */
internal class FakePicture(override val width: Int, override val height: Int) : Picture {
    var releases = 0
    override fun release() {
        releases++
    }
}

internal class FakeSource(
    private val bytes: ByteArray,
    override val displayName: String? = "photo.jpg",
    override val mimeType: String? = null,
    /** What the picker claims; null means the real size. May lie. */
    declared: Long? = bytes.size.toLong(),
    private val cap: Long = 100L * 1024 * 1024,
    private val failRead: Boolean = false,
) : PickedSource {
    override val declaredSize: Long? = declared
    var closed = false
    var streamsOpened = 0

    override fun head(n: Int): ByteArray = bytes.copyOf(minOf(n, bytes.size))

    override fun stream(): InputStream {
        streamsOpened++
        val inner: InputStream = if (failRead) object : InputStream() {
            override fun read(): Int = throw IOException("fake")
        } else ByteArrayInputStream(bytes)
        return object : FilterInputStream(inner) {
            var count = 0L
            override fun read(): Int {
                val r = super.read()
                if (r >= 0 && ++count > cap) throw PrepException(PrepException.Reason.TOO_LARGE)
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = super.read(b, off, len)
                if (r > 0) {
                    count += r
                    if (count > cap) throw PrepException(PrepException.Reason.TOO_LARGE)
                }
                return r
            }
        }
    }

    override fun close() {
        closed = true
    }
}

internal class FakeOpener(var source: FakeSource? = null, var failure: PrepException? = null) : InputOpener {
    override fun open(input: MediaInput.Picked): PickedSource {
        failure?.let { throw it }
        return source ?: throw PrepException(PrepException.Reason.UNREADABLE)
    }
}

internal class FakeCodec : ImageCodec {
    val pictures = ArrayList<FakePicture>()
    var decodeWidth = 4000
    var decodeHeight = 3000
    /** The main re-encode comes back with an EXIF block (a broken codec): the preparer must refuse. */
    var dirtyOutput = false
    var failDecode: RuntimeException? = null
    var firstFrame = true
    var failFirstFrame = false
    val outputs = ArrayList<ByteArray>()

    private val base: ByteArray = DataUrl.decode(DataUrl.PLACEHOLDER)!!

    private fun make(w: Int, h: Int) = FakePicture(w, h).also { pictures.add(it) }

    override fun decodeForSend(src: PickedSource, maxSide: Int): Picture {
        failDecode?.let { throw it }
        src.stream().use { it.readBytes() } // like the real codec: reads through the counting stream
        val k = minOf(1.0, maxSide.toDouble() / maxOf(decodeWidth, decodeHeight))
        return make((decodeWidth * k).toInt(), (decodeHeight * k).toInt())
    }

    override fun decodeFirstFrame(bytes: ByteArray, maxSide: Int): Picture? {
        if (failFirstFrame) throw IllegalStateException("fake")
        return if (firstFrame) make(maxSide, maxSide) else null
    }

    /** Like AndroidImageCodec: the SAME picture when it already fits. */
    override fun scale(picture: Picture, maxSide: Int): Picture =
        if (maxOf(picture.width, picture.height) <= maxSide) picture else {
            val k = maxSide.toDouble() / maxOf(picture.width, picture.height)
            make((picture.width * k).toInt().coerceAtLeast(1), (picture.height * k).toInt().coerceAtLeast(1))
        }

    override fun jpeg(picture: Picture, quality: Int): ByteArray {
        val side = maxOf(picture.width, picture.height)
        val size = maxOf(base.size, side * side * quality / 1000)
        var out = base.copyOf(size) // zeros after EOI: still clean
        if (dirtyOutput) out = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 8) +
            "EXIF-x".toByteArray() + base.copyOfRange(2, base.size)
        outputs.add(out)
        return out.copyOf()
    }

    val allReleasedOnce: Boolean get() = pictures.all { it.releases == 1 }
}

internal class FakeVideoTool : VideoTool {
    var info: VideoInfo? = VideoInfo(7, 640, 480, 90)
    var frameOk = true
    var frameThrows = false
    val pictures = ArrayList<FakePicture>()
    /** The staged file as it looked when probe and frame ran (the preparer must not have sealed it yet). */
    var probedExisted = false
    var framedExisted = false

    override fun probe(file: File): VideoInfo? {
        probedExisted = file.isFile
        return info
    }

    override fun frame(file: File, maxSide: Int): Picture? {
        framedExisted = file.isFile
        if (frameThrows) throw IllegalStateException("fake")
        return if (frameOk) FakePicture(640, 480).also { pictures.add(it) } else null
    }
}
