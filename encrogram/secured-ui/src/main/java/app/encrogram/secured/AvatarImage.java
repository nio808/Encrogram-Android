package app.encrogram.secured;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Turns a picked photo into a profile or group photo: decode with bounds, centre-crop to a square, scale to 192 px,
 * re-encode as JPEG at the highest quality that fits the engine's limit. Reads the picked Uri through the platform's
 * ContentResolver only; no Telegram image or file classes. Also a round view that draws the result.
 */
final class AvatarImage {

    static final int SIZE = 192;
    private static final int MAX_SIDE = 12_000, MAX_PIXELS = 60_000_000;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "secured-avatar");
        t.setDaemon(true);
        return t;
    });

    interface Done {
        /** On the main thread. [jpeg] is null when the image could not be used. */
        void onDone(@Nullable byte[] jpeg);
    }

    private AvatarImage() {
    }

    /** The system photo picker for one image (same approach as SecuredAttach, images only). */
    static Intent pickIntent() {
        final Intent intent;
        if (hasPhotoPicker()) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        return intent;
    }

    private static boolean hasPhotoPicker() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        try {
            return Build.VERSION.SDK_INT >= 30
                    && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Decodes and encodes off the main thread; [done] runs on the main thread. */
    static void prepare(Context context, Uri uri, Done done) {
        final Context app = context.getApplicationContext();
        WORK.execute(() -> {
            byte[] out = null;
            try {
                out = encode(app, uri);
            } catch (Throwable ignored) {
            }
            final byte[] result = out;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> done.onDone(result));
        });
    }

    @Nullable
    private static byte[] encode(Context c, Uri uri) throws Exception {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        }
        if (!sizeOk(o.outWidth, o.outHeight)) return null;   // refuse huge or unreadable images
        int rotation = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) rotation = rotationDegrees(new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
        } catch (Throwable ignored) {
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, SIZE);
        final Bitmap decoded;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            decoded = BitmapFactory.decodeStream(in, null, o);
        }
        if (decoded == null) return null;
        final Rect r = cropRect(decoded.getWidth(), decoded.getHeight());
        final Bitmap square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(square);
        canvas.drawColor(0xFFFFFFFF);   // JPEG has no alpha
        final Matrix m = new Matrix();
        m.postTranslate(-r.centerX(), -r.centerY());
        if (rotation != 0) m.postRotate(rotation);
        final float s = SIZE / (float) Math.min(r.width(), r.height());
        m.postScale(s, s);
        m.postTranslate(SIZE / 2f, SIZE / 2f);
        canvas.drawBitmap(decoded, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        decoded.recycle();
        try {
            // Re-encoding writes a fresh JPEG, so EXIF, location and every other tag of the original are gone.
            for (int q = 85; q >= 25; q -= 10) {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream(app.encrogram.core.api.SecuredEngineKt.AVATAR_MAX_BYTES);
                square.compress(Bitmap.CompressFormat.JPEG, q, bos);
                if (fits(bos.size())) return bos.toByteArray();
            }
            return null;
        } finally {
            square.recycle();
        }
    }

    // ---- pure rules (unit-tested) ------------------------------------------------------------------------------

    static boolean sizeOk(int w, int h) {
        return w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE && (long) w * h <= MAX_PIXELS;
    }

    /** Power of two so the decoded short side stays at least [target]. */
    static int sampleSize(int w, int h, int target) {
        int s = 1;
        final int shortSide = Math.min(w, h);
        while (shortSide / (s * 2) >= target) s *= 2;
        return s;
    }

    /** Centred square inside w x h. */
    static Rect cropRect(int w, int h) {
        final int side = Math.min(w, h);
        final int l = (w - side) / 2, t = (h - side) / 2;
        return new Rect(l, t, l + side, t + side);
    }

    static boolean fits(int bytes) {
        return bytes > 0 && bytes <= app.encrogram.core.api.SecuredEngineKt.AVATAR_MAX_BYTES;
    }

    static int rotationDegrees(int exifOrientation) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    // ---- the round view ----------------------------------------------------------------------------------------

    /** Draws a photo as a circle, or the initials drawable when there is none. The bitmap is not recycled here. */
    static final class CircleView extends android.view.View {
        private final org.telegram.ui.Components.AvatarDrawable fallback;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private Bitmap photo;

        CircleView(Context context, org.telegram.ui.Components.AvatarDrawable fallback) {
            super(context);
            this.fallback = fallback;
        }

        void setPhoto(@Nullable Bitmap b) {
            photo = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            if (photo == null) {
                fallback.setBounds(0, 0, w, h);
                fallback.draw(canvas);
                return;
            }
            clip.reset();
            clip.addCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(photo, null, new Rect(0, 0, w, h), paint);
            canvas.restore();
        }
    }
}
