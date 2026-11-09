package app.encrogram.secured;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shows one Drawable fitted to the view with pinch zoom (1x to 5x), pan inside the bounds, double tap to 2.5x and back,
 * and a single-tap callback (task MU5). Reuse level 4: Telegram's CropGestureDetector has no double tap and reports a
 * tap after a pinch, so this uses Android's ScaleGestureDetector and GestureDetector. It only draws what it is given:
 * no loader, cache or network (secured-boundary).
 */
final class SecuredZoomImageView extends View {

    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    @Nullable
    private Drawable drawable;
    private int drawableW, drawableH;
    // Zoom state relative to the fitted size: scale 1 is "fitted", offsets are in view pixels from the centre.
    private float scale = 1f, tx, ty;
    private ValueAnimator animator;
    @Nullable
    private Runnable onTap;

    SecuredZoomImageView(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                final float f = d.getScaleFactor();
                if (Float.isNaN(f) || Float.isInfinite(f)) return false;
                stopAnimation();
                zoomAround(clampScale(scale * f), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress() || scale <= 1f) return false;
                stopAnimation();
                tx -= dx;
                ty -= dy;
                clampOffsets();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (drawable == null) return true;
                if (scale > 1.05f) {
                    animateTo(1f, 0f, 0f);
                } else {
                    final float s = DOUBLE_TAP_SCALE;
                    final float fx = e.getX() - getWidth() / 2f, fy = e.getY() - getHeight() / 2f;
                    animateTo(s, fx - s * fx, fy - s * fy);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (onTap != null) onTap.run();
                return true;
            }
        });
        setFocusable(false);
    }

    void setOnTap(@Nullable Runnable r) {
        onTap = r;
    }

    /**
     * Replaces the picture. The zoom stays only when the new one has the same shape (a preview replaced by the full
     * image keeps the view fitted); otherwise it resets to fitted.
     */
    void setDrawable(@Nullable Drawable d) {
        final Drawable old = drawable;
        if (old == d) return;
        final int w = d != null ? d.getIntrinsicWidth() : 0, h = d != null ? d.getIntrinsicHeight() : 0;
        final boolean sameShape = old != null && d != null && w > 0 && h > 0 && drawableW > 0 && drawableH > 0
                && Math.abs((float) w / h - (float) drawableW / drawableH) < 0.02f * ((float) drawableW / drawableH);
        if (old != null) {
            old.setCallback(null);
            if (old instanceof Animatable) ((Animatable) old).stop();
        }
        stopAnimation();
        drawable = d;
        drawableW = w;
        drawableH = h;
        if (!sameShape) {
            scale = 1f;
            tx = 0f;
            ty = 0f;
        }
        if (d != null) d.setCallback(this);
        invalidate();
    }

    @Nullable
    Drawable getDrawable() {
        return drawable;
    }

    /** Stops animation and lets go of the drawable (the viewer is closing). */
    void release() {
        stopAnimation();
        setDrawable(null);
    }

    boolean isZoomed() {
        return scale > 1.01f;
    }

    // Animated drawables report frames to their callback: it must be this view.
    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == drawable || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        if (who == drawable) invalidate();
        else super.invalidateDrawable(who);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampOffsets();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final Drawable d = drawable;
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        final float fit = fitScale();
        if (fit <= 0f) return;
        final int fw = Math.round(drawableW * fit), fh = Math.round(drawableH * fit);
        canvas.save();
        canvas.translate(getWidth() / 2f + tx, getHeight() / 2f + ty);
        canvas.scale(scale, scale);
        d.setBounds(-fw / 2, -fh / 2, fw - fw / 2, fh - fh / 2);
        d.draw(canvas);
        canvas.restore();
    }

    private float fitScale() {
        if (drawableW <= 0 || drawableH <= 0) return 0f;
        return Math.min((float) getWidth() / drawableW, (float) getHeight() / drawableH);
    }

    private static float clampScale(float s) {
        return Math.max(1f, Math.min(MAX_SCALE, s));
    }

    /** Changes the scale so the point under (fx, fy), in view coordinates, stays where it is. */
    private void zoomAround(float newScale, float fx, float fy) {
        final float px = fx - getWidth() / 2f, py = fy - getHeight() / 2f;
        final float k = newScale / scale;
        tx = px - k * (px - tx);
        ty = py - k * (py - ty);
        scale = newScale;
        clampOffsets();
        invalidate();
    }

    private float[] clamped(float s, float x, float y) {
        final float fit = fitScale();
        final float sw = drawableW * fit * s, sh = drawableH * fit * s;
        final float maxX = Math.max(0f, (sw - getWidth()) / 2f), maxY = Math.max(0f, (sh - getHeight()) / 2f);
        return new float[]{Math.max(-maxX, Math.min(maxX, x)), Math.max(-maxY, Math.min(maxY, y))};
    }

    private void clampOffsets() {
        if (drawable == null) return;
        final float[] o = clamped(scale, tx, ty);
        tx = o[0];
        ty = o[1];
    }

    private void animateTo(float targetScale, float targetX, float targetY) {
        stopAnimation();
        final float[] end = clamped(targetScale, targetX, targetY);
        final float s0 = scale, x0 = tx, y0 = ty;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            final float t = (float) a.getAnimatedValue();
            scale = s0 + (targetScale - s0) * t;
            tx = x0 + (end[0] - x0) * t;
            ty = y0 + (end[1] - y0) * t;
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }
}
