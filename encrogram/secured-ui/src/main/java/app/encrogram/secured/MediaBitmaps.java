package app.encrogram.secured;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

import org.telegram.messenger.Utilities;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.encrogram.core.api.MediaPreview;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredResult;

/**
 * Bounded, memory-only decoding of Secured images (task MU1/MD11). Every decode reads the header first, checks the
 * limits in {@link MediaBounds} and the magic bytes, then decodes with a sample size so a hostile image can never
 * decode at full size. Nothing is written to disk, nothing is logged, and no Telegram loader is involved (plain
 * BitmapFactory and ImageDecoder only). Public methods are called on the main thread; callbacks run on it too.
 */
final class MediaBitmaps {

    interface Callback {
        void onResult(@Nullable Bitmap bitmap);   // main thread
    }

    interface AnimatedCallback {
        void onResult(@Nullable Drawable animated, @Nullable Bitmap still);   // main thread
    }

    /** Blurred previews are tiny: about 200 px on the long side is plenty. */
    private static final int BLUR_MAX_SIDE = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final SecuredEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SecuredMediaDecode");
        t.setDaemon(true);
        return t;
    });
    private final LruCache<String, Bitmap> cache;
    /** The newest preview key per message, so cachedPreview needs no size. */
    private final Map<String, String> latestPreview = new ConcurrentHashMap<>();
    /** In-flight requests by cache key; main thread only. */
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private volatile int generation;

    MediaBitmaps(SecuredEngine engine) {
        this.engine = engine;
        long max = Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>((int) Math.max(1024 * 1024, max)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getAllocationByteCount());
            }
        };
    }

    // ----- static decoding -------------------------------------------------------------------------------------

    /** {w, h} from the header only, or null when unreadable or not acceptable as a preview. */
    @Nullable
    static int[] previewSize(@Nullable MediaPreview preview) {
        if (preview == null) return null;
        try {
            byte[] data = preview.bytes();
            try {
                return previewBounds(data);
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bounded, unblurred, uncached. Previews are small, so this may run on the main thread. */
    @Nullable
    static Bitmap decodePreview(@Nullable MediaPreview preview, int targetW, int targetH) {
        if (preview == null) return null;
        try {
            byte[] data = preview.bytes();
            try {
                return decodeBounded(data, true, targetW, targetH, false);
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static int[] previewBounds(byte[] data) {
        if (!MediaBounds.previewBytesOk(data.length) || !MediaBounds.previewType(MediaBounds.sniff(data))) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (!MediaBounds.acceptable(o.outWidth, o.outHeight, true)) return null;
        return new int[]{o.outWidth, o.outHeight};
    }

    /** Header first, limits, magic bytes, then a sampled decode. Null on any refusal or failure. */
    @Nullable
    private static Bitmap decodeBounded(byte[] data, boolean preview, int targetW, int targetH, boolean mutable) {
        try {
            if (data == null) return null;
            int type = MediaBounds.sniff(data);
            if (preview) {
                if (!MediaBounds.previewBytesOk(data.length) || !MediaBounds.previewType(type)) return null;
            } else if (!MediaBounds.fullType(type)) {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (!MediaBounds.acceptable(o.outWidth, o.outHeight, preview)) return null;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = MediaBounds.sampleSize(o.outWidth, o.outHeight, targetW, targetH);
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            d.inMutable = mutable;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Throwable t) {   // includes OutOfMemoryError: a refused image is just "no image"
            return null;
        }
    }

    // ----- cache ------------------------------------------------------------------------------------------------

    private static String prefix(long chatId, long messageId) {
        return chatId + ":" + messageId + ":";
    }

    private static int bucket(int targetW, int targetH) {
        int side = Math.max(1, Math.max(targetW, targetH));
        int b = 1;
        while (b < side && b < (1 << 14)) b <<= 1;
        return b;
    }

    private static String previewKey(long chatId, long messageId, int tw, int th, boolean blur) {
        return prefix(chatId, messageId) + "p:" + (blur ? "b" : "s") + ":" + bucket(tw, th);
    }

    private static String fullKey(long chatId, long messageId, int tw, int th) {
        return prefix(chatId, messageId) + "f:" + bucket(tw, th);
    }

    /** The newest preview loaded for this message at any size, blurred or not; null when none is in memory. */
    @Nullable
    Bitmap cachedPreview(long chatId, long messageId) {
        String key = latestPreview.get(prefix(chatId, messageId));
        return key == null ? null : cache.get(key);
    }

    @Nullable
    Bitmap cachedFull(long chatId, long messageId, int targetW, int targetH) {
        return cache.get(fullKey(chatId, messageId, targetW, targetH));
    }

    // ----- loading ----------------------------------------------------------------------------------------------

    void loadPreview(long chatId, long messageId, MediaPreview preview, int targetW, int targetH, boolean blur,
                     Callback cb) {
        String key = previewKey(chatId, messageId, targetW, targetH, blur);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            latestPreview.put(prefix(chatId, messageId), key);
            cb.onResult(hit);
            return;
        }
        if (!enqueue(key, cb)) return;
        final List<Callback> list = pending.get(key);
        final int gen = generation;
        final byte[] data = preview == null ? null : preview.bytes();   // a copy: ours to zero
        try {
            worker.execute(() -> {
                Bitmap result = null;
                try {
                    int tw = blur ? Math.min(targetW <= 0 ? BLUR_MAX_SIDE : targetW, BLUR_MAX_SIDE) : targetW;
                    int th = blur ? Math.min(targetH <= 0 ? BLUR_MAX_SIDE : targetH, BLUR_MAX_SIDE) : targetH;
                    result = decodeBounded(data, true, tw, th, blur);
                    if (blur && result != null) result = blurred(result);
                } catch (Throwable t) {
                    result = null;
                } finally {
                    if (data != null) Arrays.fill(data, (byte) 0);
                }
                finish(key, list, gen, result, prefix(chatId, messageId));
            });
        } catch (Throwable t) {
            if (data != null) Arrays.fill(data, (byte) 0);
            finish(key, list, gen, null, null);
        }
    }

    void loadFull(long chatId, long messageId, int targetW, int targetH, Callback cb) {
        String key = fullKey(chatId, messageId, targetW, targetH);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.onResult(hit);
            return;
        }
        if (!enqueue(key, cb)) return;
        final List<Callback> list = pending.get(key);
        final int gen = generation;
        try {
            engine.readMediaBytes(chatId, messageId, result -> {
                // The engine may answer on any thread: decode on our own one.
                try {
                    worker.execute(() -> {
                        Bitmap bmp = null;
                        byte[] data = null;
                        try {
                            if (result instanceof SecuredResult.Ok) {
                                data = ((SecuredResult.Ok<byte[]>) result).getValue();
                                bmp = decodeBounded(data, false, targetW, targetH, false);
                            }
                        } catch (Throwable t) {
                            bmp = null;
                        } finally {
                            if (data != null) Arrays.fill(data, (byte) 0);
                        }
                        finish(key, list, gen, bmp, null);
                    });
                } catch (Throwable t) {
                    finish(key, list, gen, null, null);
                }
            });
        } catch (Throwable t) {
            finish(key, list, gen, null, null);
        }
    }

    /**
     * GIFs. API 28+: a Drawable from ImageDecoder (an AnimatedImageDrawable when animated; the caller starts it and
     * owns it; the bytes stay alive because it keeps reading them). Below 28: a bounded still only. Not cached.
     */
    void loadAnimated(long chatId, long messageId, int targetW, int targetH, AnimatedCallback cb) {
        final int gen = generation;
        try {
            engine.readMediaBytes(chatId, messageId, result -> {
                try {
                    worker.execute(() -> {
                        Drawable animated = null;
                        Bitmap still = null;
                        byte[] data = null;
                        boolean keepBytes = false;
                        try {
                            if (result instanceof SecuredResult.Ok) {
                                data = ((SecuredResult.Ok<byte[]>) result).getValue();
                                if (Build.VERSION.SDK_INT >= 28) {
                                    Drawable d = decodeDrawable(data, targetW, targetH);
                                    if (d instanceof AnimatedImageDrawable) {
                                        animated = d;
                                        keepBytes = true;
                                    } else if (d instanceof BitmapDrawable) {
                                        animated = d;
                                        still = ((BitmapDrawable) d).getBitmap();
                                    }
                                } else {
                                    still = decodeBounded(data, false, targetW, targetH, false);
                                }
                            }
                        } catch (Throwable t) {
                            animated = null;
                            still = null;
                            keepBytes = false;
                        } finally {
                            if (data != null && !keepBytes) Arrays.fill(data, (byte) 0);
                        }
                        final Drawable a = animated;
                        final Bitmap s = still;
                        MAIN.post(() -> {
                            if (gen != generation) {
                                cb.onResult(null, null);
                            } else {
                                cb.onResult(a, s);
                            }
                        });
                    });
                } catch (Throwable t) {
                    MAIN.post(() -> cb.onResult(null, null));
                }
            });
        } catch (Throwable t) {
            MAIN.post(() -> cb.onResult(null, null));
        }
    }

    /** API 28+: refuses oversize headers by throwing inside the listener, which aborts the decode. */
    @Nullable
    private static Drawable decodeDrawable(byte[] data, int targetW, int targetH) {
        try {
            if (!MediaBounds.fullType(MediaBounds.sniff(data))) return null;
            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                if (!MediaBounds.acceptable(w, h, false)) {
                    throw new IllegalArgumentException();
                }
                decoder.setTargetSampleSize(MediaBounds.sampleSize(w, h, targetW, targetH));
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mutable ARGB_8888 in, blurred in place. A failed blur gives null: never show the sharp one by mistake. */
    @Nullable
    private static Bitmap blurred(Bitmap b) {
        try {
            if (!b.isMutable() || b.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, true);
                b.recycle();
                b = copy;
                if (b == null) return null;
            }
            int radius = Math.max(2, Math.max(b.getWidth(), b.getHeight()) / 25);
            Utilities.stackBlurBitmap(b, radius);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ----- bookkeeping ------------------------------------------------------------------------------------------

    /** Main thread. True when this caller must start the work; false when it joined one in flight. */
    private boolean enqueue(String key, Callback cb) {
        List<Callback> list = pending.get(key);
        if (list != null) {
            list.add(cb);
            return false;
        }
        list = new ArrayList<>();
        list.add(cb);
        pending.put(key, list);
        return true;
    }

    /** Any thread. Delivers on the main thread unless forget() or clear() dropped the request meanwhile. */
    private void finish(String key, List<Callback> list, int gen, @Nullable Bitmap bmp, @Nullable String previewOf) {
        MAIN.post(() -> {
            if (gen != generation || pending.get(key) != list) {
                return;   // the dropper already answered these callbacks with null
            }
            pending.remove(key);
            if (bmp != null) {
                cache.put(key, bmp);
                if (previewOf != null) latestPreview.put(previewOf, key);
            }
            for (Callback c : list) c.onResult(bmp);
        });
    }

    /** Drops every cached and in-flight entry of one message; in-flight callbacks get null. */
    void forget(long chatId, long messageId) {
        String p = prefix(chatId, messageId);
        for (String key : new ArrayList<>(cache.snapshot().keySet())) {
            if (key.startsWith(p)) cache.remove(key);
        }
        latestPreview.remove(p);
        List<List<Callback>> dropped = new ArrayList<>();
        for (Iterator<Map.Entry<String, List<Callback>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Callback>> e = it.next();
            if (e.getKey().startsWith(p)) {
                dropped.add(e.getValue());
                it.remove();
            }
        }
        for (List<Callback> l : dropped) for (Callback c : l) c.onResult(null);
    }

    /** Evicts everything; requests in flight are dropped and their callbacks get null. */
    void clear() {
        generation++;
        cache.evictAll();
        latestPreview.clear();
        List<List<Callback>> dropped = new ArrayList<>(pending.values());
        pending.clear();
        for (List<Callback> l : dropped) for (Callback c : l) c.onResult(null);
    }
}
