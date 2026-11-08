package app.encrogram.core.internal.media.prep.android

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.encrogram.core.internal.media.prep.Picture
import app.encrogram.core.internal.media.prep.VideoInfo
import app.encrogram.core.internal.media.prep.VideoTool
import java.io.File
import kotlin.math.max

/** Video facts and a first frame via MediaMetadataRetriever, on a staged file. No transcoding, no remux. */
internal class AndroidVideoTool : VideoTool {

    override fun probe(file: File): VideoInfo? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) return null
            return VideoInfo(max(1L, (ms + 500) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), w, h, rot)
        } catch (e: Exception) {
            return null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    override fun frame(file: File, maxSide: Int): Picture? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val longSide = max(bmp.width, bmp.height)
            if (longSide <= maxSide) return BitmapPicture(bmp)
            val f = maxSide.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bmp, max(1, Math.round(bmp.width * f)), max(1, Math.round(bmp.height * f)), true,
            )
            if (scaled !== bmp) bmp.recycle()
            return BitmapPicture(scaled)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }
}
