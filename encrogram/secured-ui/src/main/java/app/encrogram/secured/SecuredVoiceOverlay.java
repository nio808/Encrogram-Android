package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * What the composer shows while a voice message records, drawn over the chat screen in Telegram's layout: a red dot
 * and the timer on the left, "Slide to cancel" in the middle (moving with the finger), the big circle on the mic
 * button following the finger and pulsing with the level, and the lock above it. Once locked, the circle is a send
 * button and "Cancel" replaces the hint; then this view takes those two taps itself.
 *
 * Pure drawing plus the two locked taps: the recorder ({@link SecuredVoiceRecorder}) owns all state that matters.
 * Telegram's own record views are inner classes of ChatActivityEnterView (one of them sends typing to Telegram), so
 * this is our own small copy of their look (reuse level 4, numbers from ChatActivityEnterView).
 */
@SuppressLint("ViewConstructor")
final class SecuredVoiceOverlay extends View {

    interface Listener {
        void onLockedSend();
        void onLockedCancel();
    }

    /** Finger travel that cancels (left) or locks (up). */
    static final int CANCEL_DP = 120;
    static final int LOCK_DP = 60;

    private final Theme.ResourcesProvider resources;
    private final Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final RectF rect = new RectF();
    private final RectF cancelHit = new RectF();
    private final Drawable micIcon;
    private final Drawable sendIcon;
    private final Drawable lockIcon;
    private final String slideText;
    private final String cancelText;

    private boolean shown;
    private boolean locked;
    private long startedAt;
    private float level;
    /** Composer bar (field plus button) and the mic button's centre, in this view's coordinates. */
    private final RectF bar = new RectF();
    private float micX, micY;
    private float slideX, slideY;

    SecuredVoiceOverlay(Context context, Theme.ResourcesProvider resources, Listener listener) {
        super(context);
        this.resources = resources;
        this.listener = listener;
        timerPaint.setTextSize(dp(15));
        timerPaint.setTypeface(AndroidUtilities.bold());
        hintPaint.setTextSize(dp(15));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.6f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        micIcon = icon(context, org.telegram.messenger.R.drawable.input_mic);
        sendIcon = icon(context, org.telegram.messenger.R.drawable.send_plane_24);
        lockIcon = icon(context, org.telegram.messenger.R.drawable.ic_lock_header);
        slideText = context.getString(R.string.secvoice_slide_cancel);
        cancelText = context.getString(R.string.secvoice_cancel);
        setVisibility(GONE);
    }

    private static Drawable icon(Context context, int res) {
        final Drawable d = ContextCompat.getDrawable(context, res);
        return d != null ? d.mutate() : null;
    }

    /** Starts the recording look at the mic button; {@code bar} is the composer row, both in this view's coordinates. */
    void show(RectF composerBar, float micCenterX, float micCenterY) {
        bar.set(composerBar);
        micX = micCenterX;
        micY = micCenterY;
        slideX = slideY = 0;
        locked = false;
        level = 0;
        startedAt = SystemClock.elapsedRealtime();
        shown = true;
        setVisibility(VISIBLE);
        invalidate();
    }

    void hide() {
        shown = false;
        locked = false;
        setVisibility(GONE);
    }

    /** Finger offset from where it went down (both at most 0). */
    void setSlide(float dx, float dy) {
        slideX = Math.min(0, dx);
        slideY = Math.min(0, dy);
        invalidate();
    }

    void setLocked() {
        locked = true;
        slideX = slideY = 0;
        invalidate();
    }

    /** 0..1, from the recorder's amplitude. */
    void setLevel(float value) {
        level = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        final long elapsed = SystemClock.elapsedRealtime() - startedAt;

        // The bar replaces the composer's field (Telegram draws the panel in the message panel's colour).
        fill.setColor(color(Theme.key_chat_messagePanelBackground));
        rect.set(bar.left, bar.top, micX - dp(30), bar.bottom);
        canvas.drawRoundRect(rect, dp(22), dp(22), fill);

        // Red dot, blinking once a second, and the timer.
        final float cy = rect.centerY();
        final float blink = (elapsed % 1000) / 1000f;
        fill.setColor(color(Theme.key_chat_recordedVoiceDot));
        fill.setAlpha((int) (255 * (blink < 0.5f ? 1f - blink : blink)));
        canvas.drawCircle(rect.left + dp(20), cy, dp(5), fill);
        fill.setAlpha(255);
        final long tenths = elapsed / 10;
        final String timer = AndroidUtilities.formatTimerDurationFast((int) (elapsed / 1000), (int) (tenths % 100));
        timerPaint.setColor(color(Theme.key_chat_recordTime));
        canvas.drawText(timer, rect.left + dp(34), cy + dp(5), timerPaint);

        // Middle: "‹ Slide to cancel", sliding with the finger, or "Cancel" once locked.
        final float timerRight = rect.left + dp(34) + timerPaint.measureText("00:00,0") + dp(12);
        if (locked) {
            hintPaint.setColor(SecuredResourcesProvider.accentText());
            hintPaint.setTypeface(AndroidUtilities.bold());
            final float w = hintPaint.measureText(cancelText);
            final float x = Math.max(timerRight, (rect.left + rect.right - w) / 2f);
            canvas.drawText(cancelText, x, cy + dp(5), hintPaint);
            cancelHit.set(x - dp(12), rect.top, x + w + dp(12), rect.bottom);
        } else {
            hintPaint.setColor(color(Theme.key_chat_recordVoiceCancel));
            hintPaint.setTypeface(null);
            final float w = hintPaint.measureText(slideText) + dp(14);
            final float x = Math.max(timerRight, (rect.left + rect.right - w) / 2f) + slideX * 0.6f;
            final float fade = 1f - Math.min(1f, -slideX / dp(CANCEL_DP));
            hintPaint.setAlpha((int) (255 * fade));
            stroke.setColor(hintPaint.getColor());
            stroke.setAlpha(hintPaint.getAlpha());
            chevron.reset();
            chevron.moveTo(x + dp(5), cy - dp(5));
            chevron.lineTo(x, cy);
            chevron.lineTo(x + dp(5), cy + dp(5));
            canvas.drawPath(chevron, stroke);
            canvas.drawText(slideText, x + dp(14), cy + dp(5), hintPaint);
            hintPaint.setAlpha(255);
        }

        // The lock above the circle (rises as the finger moves up), until locked.
        final float cx = micX + (locked ? 0 : slideX);
        final float ccy = micY + (locked ? 0 : slideY);
        if (!locked) {
            final float lockProgress = Math.min(1f, -slideY / dp(LOCK_DP));
            final float pillH = dp(70) - dp(24) * lockProgress;
            final float pillBottom = micY - dp(52) + slideY * 0.3f;
            rect.set(micX - dp(18), pillBottom - pillH, micX + dp(18), pillBottom);
            fill.setColor(color(Theme.key_chat_messagePanelVoiceLockBackground));
            fill.setShadowLayer(dp(2), 0, dp(1), color(Theme.key_chat_messagePanelVoiceLockShadow));
            canvas.drawRoundRect(rect, dp(18), dp(18), fill);
            fill.clearShadowLayer();
            if (lockIcon != null) {
                final int s = dp(20);
                lockIcon.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_messagePanelVoiceLock), PorterDuff.Mode.SRC_IN));
                lockIcon.setBounds((int) (micX - s / 2f), (int) (rect.top + dp(8)), (int) (micX + s / 2f), (int) (rect.top + dp(8) + s));
                lockIcon.draw(canvas);
            }
            stroke.setColor(color(Theme.key_chat_messagePanelVoiceLock));
            stroke.setAlpha((int) (255 * (1f - lockProgress)));
            chevron.reset();
            chevron.moveTo(micX - dp(5), rect.bottom - dp(13));
            chevron.lineTo(micX, rect.bottom - dp(18));
            chevron.lineTo(micX + dp(5), rect.bottom - dp(13));
            canvas.drawPath(chevron, stroke);
        }

        // The circle: a soft halo that follows the level, the solid circle and its icon.
        final int circleColor = SecuredResourcesProvider.accentFill();
        fill.setColor(circleColor);
        fill.setAlpha(60);
        canvas.drawCircle(cx, ccy, dp(41) + dp(18) * level, fill);
        fill.setAlpha(255);
        canvas.drawCircle(cx, ccy, dp(41), fill);
        final Drawable icon = locked ? sendIcon : micIcon;
        if (icon != null) {
            final int s = locked ? dp(26) : dp(24);
            icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            icon.setBounds((int) (cx - s / 2f), (int) (ccy - s / 2f), (int) (cx + s / 2f), (int) (ccy + s / 2f));
            icon.draw(canvas);
        }

        // About 30 frames a second for the timer, the dot and the level.
        postInvalidateDelayed(33);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!shown || !locked) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            // Locked: the bar and the circle are ours (the message box underneath must not take the tap).
            final boolean onCircle = Math.hypot(e.getX() - micX, e.getY() - micY) <= dp(44);
            return onCircle || bar.contains(e.getX(), e.getY());
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (Math.hypot(e.getX() - micX, e.getY() - micY) <= dp(44)) {
                listener.onLockedSend();
            } else if (cancelHit.contains(e.getX(), e.getY())) {
                listener.onLockedCancel();
            }
            return true;
        }
        return true;
    }

    private int color(int key) {
        return Theme.getColor(key, resources);
    }
}
