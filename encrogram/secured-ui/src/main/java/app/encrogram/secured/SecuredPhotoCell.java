package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.MediaActionDrawable;

import app.encrogram.core.api.MediaKind;
import app.encrogram.core.api.MediaState;

/**
 * The photo, GIF and video bubble (task MU2). Telegram's look (sizes, corners, centre button, info pill) drawn by
 * hand from the engine's media state; pictures come only from {@link MediaBitmaps}, so no Telegram loader is touched.
 * <p>
 * Reuse levels: {@link MediaActionDrawable} for the button's icons and progress arc (1, configured); the geometry and
 * corner numbers copied from ChatMessageCell (3); the circle, the info pill, the picture drawing and the GIF handling
 * are ours (4).
 */
final class SecuredPhotoCell extends SecuredMediaCell {

    private static final long NONE = Long.MIN_VALUE;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final MediaActionDrawable action = new MediaActionDrawable();
    private final Path clip = new Path();
    private final float[] radii = new float[8];
    private final RectF tmp = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    /** Preview dimensions of the bound message, or null when unknown. */
    private int[] dims;

    private Bitmap preview;
    private Bitmap full;
    private Bitmap still;
    private Drawable animated;
    private long previewReq = NONE;
    private boolean previewReqBlur;
    private long fullReq = NONE;
    private long animReq = NONE;

    private int lastIcon = -1;
    private boolean buttonShown;
    private final RectF buttonRect = new RectF();

    SecuredPhotoCell(Context context, Theme.ResourcesProvider resources, Host host) {
        super(context, resources, host);
        pillText.setTextSize(dp(12));
        action.setDelegate(this::invalidate);
    }

    // ---- binding ---------------------------------------------------------------------------------------------

    @Override
    protected void onBind(boolean sameMessage) {
        dims = MediaBitmaps.previewSize(media.getPreview());
        if (!sameMessage) {
            dropPictures();
            lastIcon = -1;
        } else {
            // Same message, new state: only what the new state no longer allows is dropped.
            if (!media.getReadable()) {
                full = null;
                fullReq = NONE;
                still = null;
                stopAnimated();
                animated = null;
                animReq = NONE;
            }
            // A decode that came back empty is tried again once per new state, never on every draw.
            if (full == null) fullReq = NONE;
            if (animated == null && still == null) animReq = NONE;
            if (preview == null) previewReq = NONE;
        }
        action.setProgress(progressFraction(), false);
    }

    @Override
    protected void onProgress() {
        action.setProgress(progressFraction(), true);
    }

    private void dropPictures() {
        // The cache owns the bitmaps: references are dropped, never recycled.
        preview = null;
        full = null;
        still = null;
        previewReq = NONE;
        fullReq = NONE;
        animReq = NONE;
        stopAnimated();
        animated = null;
    }

    private void stopAnimated() {
        if (animated != null) {
            if (animated instanceof Animatable) ((Animatable) animated).stop();
            animated.setCallback(null);
        }
    }

    @Override
    void onRecycled() {
        dropPictures();
        lastIcon = -1;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animated instanceof Animatable) {
            animated.setCallback(this);
            ((Animatable) animated).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animated instanceof Animatable) ((Animatable) animated).stop();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == animated || super.verifyDrawable(who);
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    @Override
    protected int bodyWidth(int maxWidth) {
        int w;
        if (dims == null) {
            w = dp(150);
        } else {
            if (AndroidUtilities.isTablet()) {
                w = (int) (AndroidUtilities.getMinTabletSide() * 0.7f);
            } else {
                w = (int) (Math.min(maxWidth, AndroidUtilities.displaySize.y) * 0.7f);
            }
            w = Math.min(w, 1280);
            final int h = (int) (dims[1] * (w / (float) dims[0]));
            final int maxH = w + dp(100);
            if (h > maxH) {
                // Too tall: clamp the height and narrow the picture to keep its shape.
                w = (int) (dims[0] * (maxH / (float) dims[1]));
            }
        }
        w = Math.min(w, maxWidth - dp(8));
        return Math.max(w + dp(8), minBodyWidthForTime() + dp(8));
    }

    @Override
    protected int contentHeight(int bodyWidth) {
        final int w = Math.max(1, bodyWidth - dp(8));
        int h;
        if (dims == null) {
            h = w;
        } else {
            h = (int) (dims[1] * (w / (float) dims[0]));
            h = Math.min(h, w + dp(100));
        }
        return Math.max(h, dp(120));
    }

    @Override
    protected void contentInsets(Rect out) {
        out.set(dp(4), dp(4), dp(4), dp(4));
    }

    @Override
    protected boolean timeOverContent() {
        return true;
    }

    // ---- pictures --------------------------------------------------------------------------------------------

    private void ensurePictures() {
        final MediaBitmaps bm = host != null ? host.bitmaps() : null;
        final int tw = (int) content.width();
        final int th = (int) content.height();
        if (bm == null || tw <= 0 || th <= 0) return;
        final long id = messageId();
        final long chat = message.getChatId();
        final boolean readable = media.getReadable();
        final MediaKind k = kind();

        if (k == MediaKind.IMAGE && readable && full == null) {
            final Bitmap cached = bm.cachedFull(chat, id, tw, th);
            if (cached != null) {
                full = cached;
            } else if (fullReq != id) {
                fullReq = id;
                bm.loadFull(chat, id, tw, th, bitmap -> {
                    // The cell may have been rebound while the decode ran.
                    if (messageId() != id || kind() != MediaKind.IMAGE || bitmap == null) return;
                    full = bitmap;
                    invalidate();
                });
            }
        }

        if (k == MediaKind.GIF && readable && animated == null && still == null && animReq != id) {
            animReq = id;
            bm.loadAnimated(chat, id, tw, th, (drawable, bitmap) -> {
                if (messageId() != id || kind() != MediaKind.GIF) return;
                if (drawable != null && Build.VERSION.SDK_INT >= 28 && drawable instanceof AnimatedImageDrawable) {
                    useAnimated(drawable);
                } else {
                    still = bitmap;
                }
                invalidate();
            });
        }

        if (media.getPreview() != null) {
            final boolean blur = !readable;
            if (preview == null) preview = bm.cachedPreview(chat, id);
            if (previewReq != id || previewReqBlur != blur) {
                previewReq = id;
                previewReqBlur = blur;
                bm.loadPreview(chat, id, media.getPreview(), tw, th, blur, bitmap -> {
                    if (messageId() != id || bitmap == null) return;
                    preview = bitmap;
                    invalidate();
                });
            }
        }
    }

    @RequiresApi(28)
    private void useAnimated(Drawable drawable) {
        final AnimatedImageDrawable d = (AnimatedImageDrawable) drawable;
        d.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        animated = d;
        d.setCallback(this);
        if (isAttachedToWindow()) d.start();
    }

    private boolean animating() {
        return animated instanceof Animatable && ((Animatable) animated).isRunning();
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    @Override
    protected void drawContent(Canvas canvas) {
        ensurePictures();
        final float l = content.left, t = content.top, r = content.right, b = content.bottom;

        // Corners: Telegram's rule (research/media-ui.md section 2).
        final float rad = dp(Math.max(0, SharedConfig.bubbleRadius - 2));
        final float minRad = dp(4);
        final float near = Math.min(dp(3), rad);
        float tl = hasHeader() ? Math.min(rad, minRad) : rad;
        float tr = tl;
        float bl = captionBelow ? Math.min(rad, minRad) : rad;
        float br = bl;
        if (pinnedTop) {
            if (out) tr = Math.min(tr, near);
            else tl = Math.min(tl, near);
        }
        radii[0] = tl; radii[1] = tl;
        radii[2] = tr; radii[3] = tr;
        radii[4] = br; radii[5] = br;
        radii[6] = bl; radii[7] = bl;
        clip.reset();
        tmp.set(l, t, r, b);
        clip.addRoundRect(tmp, radii, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clip);
        drawPicture(canvas);
        canvas.restore();

        drawButton(canvas);
        drawPill(canvas);
    }

    private void drawPicture(Canvas canvas) {
        final int w = (int) content.width();
        final int h = (int) content.height();
        dst.set((int) content.left, (int) content.top, (int) content.right, (int) content.bottom);
        if (animated != null && animated.getIntrinsicWidth() > 0 && animated.getIntrinsicHeight() > 0) {
            final int iw = animated.getIntrinsicWidth();
            final int ih = animated.getIntrinsicHeight();
            // Scaled to cover the rect, like a bitmap (the clip trims the overflow).
            final float scale = Math.max(w / (float) iw, h / (float) ih);
            canvas.save();
            canvas.translate(dst.left + (w - iw * scale) / 2f, dst.top + (h - ih * scale) / 2f);
            canvas.scale(scale, scale);
            animated.setBounds(0, 0, iw, ih);
            animated.draw(canvas);
            canvas.restore();
            return;
        }
        Bitmap pic = full != null ? full : still;
        if (pic == null || pic.isRecycled()) pic = preview;
        if (pic != null && !pic.isRecycled()) {
            final int bw = pic.getWidth();
            final int bh = pic.getHeight();
            if (bw > 0 && bh > 0) {
                // Centre-crop: take the part of the bitmap with the rect's shape.
                final float scale = Math.max(w / (float) bw, h / (float) bh);
                final int sw = Math.min(bw, Math.round(w / scale));
                final int sh = Math.min(bh, Math.round(h / scale));
                src.set((bw - sw) / 2, (bh - sh) / 2, (bw - sw) / 2 + sw, (bh - sh) / 2 + sh);
                canvas.drawBitmap(pic, src, dst, bitmapPaint);
                return;
            }
        }
        // No picture yet: a calm neutral fill.
        fillPaint.setColor(color(out ? Theme.key_chat_outMediaIcon : Theme.key_chat_inMediaIcon));
        fillPaint.setAlpha(48);
        canvas.drawRect(dst, fillPaint);
    }

    /** The icon the centre button shows, or -1 for none. */
    private int buttonIcon() {
        final MediaState s = media.getState();
        switch (s) {
            case WAITING:
                return MediaActionDrawable.ICON_DOWNLOAD;
            case DOWNLOADING:
            case UPLOADING:
                return MediaActionDrawable.ICON_CANCEL;
            case READY:
                if (kind() == MediaKind.VIDEO) return MediaActionDrawable.ICON_PLAY;
                if (kind() == MediaKind.GIF && !animating()) return MediaActionDrawable.ICON_GIF;
                return -1;
            default:
                return -1;
        }
    }

    private void drawButton(Canvas canvas) {
        final int icon = buttonIcon();
        buttonShown = icon >= 0;
        if (!buttonShown) {
            buttonRect.setEmpty();
            return;
        }
        final float size = dp(48);
        final float cx = content.centerX();
        final float cy = content.centerY();
        buttonRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        circlePaint.setColor(color(Theme.key_chat_mediaLoaderPhoto));
        canvas.drawCircle(cx, cy, size / 2f, circlePaint);
        if (icon != lastIcon) {
            // No animation on the first icon after a bind: it would flash in on every recycle.
            action.setIcon(icon, lastIcon >= 0);
            lastIcon = icon;
        }
        action.setColor(color(Theme.key_chat_mediaLoaderPhotoIcon));
        action.setBounds((int) buttonRect.left, (int) buttonRect.top, (int) buttonRect.right, (int) buttonRect.bottom);
        action.draw(canvas);
    }

    private String pillString() {
        final Context c = getContext();
        final MediaState s = media.getState();
        if (s == MediaState.FAILED) return MediaText.failureShort(c, media.getFailure());
        final MediaKind k = kind();
        final boolean ready = s == MediaState.READY;
        final String detail;
        if (moving()) {
            detail = MediaText.progress(c, progressDone, progressTotal);
        } else if (!ready) {
            detail = MediaText.size(media.getSizeBytes());
        } else {
            detail = null;
        }
        final Integer secs = media.getDurationSeconds();
        if (k == MediaKind.VIDEO) {
            final String dur = secs != null ? MediaText.duration(secs) : null;
            if (dur != null && detail != null) return dur + ", " + detail;
            return dur != null ? dur : detail;
        }
        if (k == MediaKind.GIF) {
            if (animating()) return null;
            final String gif = MediaText.kind(c, MediaKind.GIF);
            return detail != null ? gif + ", " + detail : gif;
        }
        return detail;
    }

    private void drawPill(Canvas canvas) {
        String text = pillString();
        if (text == null || text.isEmpty()) return;
        final float maxText = content.width() - dp(16) - dp(12);
        if (maxText <= 0) return;
        text = TextUtils.ellipsize(text, pillText, maxText, TextUtils.TruncateAt.END).toString();
        final float tw = pillText.measureText(text);
        final float h = Math.max(dp(17), pillText.getTextSize() + dp(5));
        final float x = content.left + dp(8);
        final float y = content.top + dp(6);
        tmp.set(x, y, x + tw + dp(12), y + h);
        pillPaint.setColor(color(Theme.key_chat_mediaTimeBackground));
        pillPaint.setAlpha((int) (pillPaint.getAlpha() * 0.6f));
        canvas.drawRoundRect(tmp, dp(4), dp(4), pillPaint);
        pillText.setColor(color(Theme.key_chat_mediaTimeText));
        canvas.drawText(text, tmp.left + dp(6), tmp.centerY() - (pillText.descent() + pillText.ascent()) / 2f, pillText);
    }

    // ---- taps ------------------------------------------------------------------------------------------------

    @Override
    protected int contentHit(float x, float y) {
        if (buttonShown && !buttonRect.isEmpty()) {
            final float slop = dp(4);
            final float cx = buttonRect.centerX();
            final float cy = buttonRect.centerY();
            final float r = buttonRect.width() / 2f + slop;
            final float dx = x - cx;
            final float dy = y - cy;
            if (dx * dx + dy * dy <= r * r) return HIT_BUTTON;
        }
        return HIT_NONE;
    }
}
