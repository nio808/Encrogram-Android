package app.encrogram.secured;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.encrogram.core.api.MediaPreview;

/**
 * Profile and group photos from the engine's {@link MediaPreview}: one bounded decode (MediaBitmaps.decodePreview,
 * which sniffs the type and limits the size), scaled to the avatar size, kept in a small in-memory LRU keyed by the
 * content hash. Nothing goes to disk and nothing touches Telegram's ImageLoader or FileLoader. A broken image
 * returns null so the caller keeps the letter avatar. Decoding is cheap (a few KB) and runs on the caller's thread.
 */
final class AvatarPhotos {

    /** Decoded size cap in px; the biggest avatar on screen is about 56 dp. */
    static final int MAX_PX = 128;
    private static final int CACHE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BAD = 64;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount());
        }
    };
    /** Hashes that failed to decode, so a broken photo is not decoded again on every bind. */
    private static final Set<String> BAD = new HashSet<>();

    private AvatarPhotos() {
    }

    /** The decoded photo, or null when there is none or it cannot be used. Never recycle the result. */
    @Nullable
    static synchronized Bitmap bitmap(@Nullable MediaPreview preview) {
        if (preview == null) return null;
        final String key = hashKey(preview);
        if (key == null) return null;
        final Bitmap hit = CACHE.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        if (BAD.contains(key)) return null;
        Bitmap b = MediaBitmaps.decodePreview(preview, MAX_PX, MAX_PX);
        if (b == null) {
            if (BAD.size() >= MAX_BAD) BAD.clear();
            BAD.add(key);
            return null;
        }
        final int longSide = Math.max(b.getWidth(), b.getHeight());
        if (longSide > MAX_PX) {
            final float s = MAX_PX / (float) longSide;
            final Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        CACHE.put(key, b);
        return b;
    }

    /** A drawable over the cached bitmap (shared, so it is not mutated), or null. */
    @Nullable
    static Drawable drawable(@Nullable MediaPreview preview) {
        final Bitmap b = bitmap(preview);
        return b == null ? null : new BitmapDrawable(org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), b);
    }

    /** Content hash (SHA-256, hex) used as the cache key; null for an empty preview. */
    @Nullable
    static String hashKey(MediaPreview preview) {
        byte[] data = null;
        try {
            data = preview.bytes();
            return hashKey(data);
        } catch (Throwable t) {
            return null;
        } finally {
            if (data != null) Arrays.fill(data, (byte) 0);
        }
    }

    @Nullable
    static String hashKey(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            final StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Drops every cached photo (for example on reset). */
    static synchronized void clear() {
        CACHE.evictAll();
        BAD.clear();
    }
}
