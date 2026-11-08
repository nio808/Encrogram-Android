package app.encrogram.core.internal.media.prep.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import app.encrogram.core.internal.media.prep.ImageCodec
import app.encrogram.core.internal.media.prep.Picture
import app.encrogram.core.internal.media.prep.PickedSource
import app.encrogram.core.internal.media.prep.PrepException
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** A Bitmap held for the codec. [release] recycles it; it is safe to call twice. */
internal class BitmapPicture(val bitmap: Bitmap) : Picture {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** ImageCodec on android.graphics. Platform APIs only; JPEG output carries no EXIF (Bitmap.compress writes none). */
internal class AndroidImageCodec : ImageCodec {

    override fun decodeForSend(src: PickedSource, maxSide: Int): Picture {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            src.stream().use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw PrepException(PrepException.Reason.UNSUPPORTED)
            if (w.toLong() * h > MAX_PIXELS) throw PrepException(PrepException.Reason.UNSUPPORTED)

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(w, h, maxSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = src.stream().use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw PrepException(PrepException.Reason.UNSUPPORTED)

            val orientation = try {
                src.stream().use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (e: PrepException) {
                throw e
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            return BitmapPicture(transform(decoded, orientation, maxSide))
        } catch (e: PrepException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PrepException(PrepException.Reason.UNREADABLE)
        } catch (e: Exception) {
            throw PrepException(PrepException.Reason.UNREADABLE)
        }
    }

    override fun decodeFirstFrame(bytes: ByteArray, maxSide: Int): Picture? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?.let { BitmapPicture(transform(it, ExifInterface.ORIENTATION_NORMAL, maxSide)) }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    /** Returns [picture] itself when it already fits; otherwise a new picture and [picture] is left for the caller. */
    override fun scale(picture: Picture, maxSide: Int): Picture {
        val bmp = (picture as BitmapPicture).bitmap
        if (max(bmp.width, bmp.height) <= maxSide) return picture
        return try {
            val f = maxSide.toFloat() / max(bmp.width, bmp.height)
            BitmapPicture(
                Bitmap.createScaledBitmap(bmp, max(1, Math.round(bmp.width * f)), max(1, Math.round(bmp.height * f)), true),
            )
        } catch (e: OutOfMemoryError) {
            throw PrepException(PrepException.Reason.UNREADABLE)
        }
    }

    override fun jpeg(picture: Picture, quality: Int): ByteArray {
        val bmp = (picture as BitmapPicture).bitmap
        var flat: Bitmap? = null
        try {
            // JPEG has no alpha: put transparent images on white instead of black.
            val source = if (bmp.hasAlpha()) {
                flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                flat
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            if (!source.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                throw PrepException(PrepException.Reason.UNREADABLE)
            }
            return out.toByteArray()
        } catch (e: OutOfMemoryError) {
            throw PrepException(PrepException.Reason.UNREADABLE)
        } finally {
            flat?.recycle()
        }
    }

    /** Applies the EXIF orientation and an exact scale down to [maxSide] in one pass; recycles [src] if replaced. */
    private fun transform(src: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        val longSide = max(src.width, src.height)
        if (longSide > maxSide) {
            val f = maxSide.toFloat() / longSide
            m.postScale(f, f)
        }
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Largest power of two that keeps the long side at or above [maxSide] (the exact scale follows). */
    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    private companion object {
        const val MAX_PIXELS = 50_000_000L
    }
}
