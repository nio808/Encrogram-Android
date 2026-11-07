package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.ActionBar.SimpleTextView;

import java.util.List;

/**
 * The "Telegram | Secured" control: a pill with two segments, tap only (no swipe), 36 dp visual height inside a
 * 48 dp touch area. The Secured segment carries our lock and the indigo accent.
 *
 * It extends ImageView only because Telegram's DialogStoriesCell keeps its title in an ImageView field, so the
 * same class can take that slot. The image is never used.
 */
public class ChatsSwitcherView extends ImageView {

    public static final int NETWORK_TELEGRAM = 0;
    public static final int NETWORK_SECURED = 1;

    /** What the control needs from whoever owns the two pages. */
    public interface Owner {
        int selectedNetwork();

        void selectNetwork(int network);

        /** Telegram's connection state text ("Connecting...") or null when connected. */
        String telegramStatus();

        int securedUnread();

        void attachSwitcher(ChatsSwitcherView view);

        void detachSwitcher(ChatsSwitcherView view);
    }

    private static final int VISUAL_HEIGHT_DP = 36;
    private static final int TOUCH_HEIGHT_DP = 48;

    private final Owner owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF pill = new RectF();
    private final RectF tmp = new RectF();

    private final String telegramLabel;
    private final String securedLabel;
    private final A11y a11y;

    private float position;            // 0 = Telegram selected, 1 = Secured selected
    private int selected;
    private ValueAnimator animator;
    private String telegramStatus;
    private int securedUnread;

    // When set, the view lays itself out on top of this bar's title (Telegram's action bar lays titles out itself).
    private ActionBar anchor;
    // Stories cell: Telegram positions its 22 dp logo; our control is taller, so shift it to the same centre line.
    private int translationYShiftPx;
    private final ViewTreeObserver.OnPreDrawListener anchorSync = () -> {
        syncToAnchor();
        return true;
    };

    public ChatsSwitcherView(Context context, Owner owner) {
        super(context);
        this.owner = owner;
        telegramLabel = context.getString(R.string.sec_segment_telegram);
        securedLabel = context.getString(R.string.sec_segment_secured);
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(dp(14));
        setClickable(true);
        setFocusable(true);
        a11y = new A11y(this);
        ViewCompat.setAccessibilityDelegate(this, a11y);
        setSelectedImmediately(owner.selectedNetwork());
    }

    /** Telegram's own code calls this on the slot's view; the control describes itself per segment. */
    @Override
    public void setContentDescription(CharSequence contentDescription) {
    }

    /** Lays this view over the title of the given bar (used inside Telegram's action bar title container). */
    public void anchorTo(ActionBar bar) {
        this.anchor = bar;
        if (isAttachedToWindow()) {
            getViewTreeObserver().addOnPreDrawListener(anchorSync);
        }
    }

    /** For the stories cell: Telegram sets translationY for a 22 dp tall logo; centre our taller view on it. */
    public void shiftForStoriesLogo() {
        translationYShiftPx = dp(TOUCH_HEIGHT_DP / 2 - 11);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY - translationYShiftPx);
    }

    private void syncToAnchor() {
        final SimpleTextView title = anchor != null ? anchor.getTitleTextView() : null;
        if (title == null) return;
        final int w = totalWidth();
        final int h = dp(TOUCH_HEIGHT_DP);
        final int cy = (title.getTop() + title.getBottom()) / 2;
        final int l = title.getLeft();
        final int t = cy - h / 2;
        if (getMeasuredWidth() != w || getMeasuredHeight() != h) {
            measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        if (getLeft() != l || getTop() != t || getRight() != l + w || getBottom() != t + h) {
            layout(l, t, l + w, t + h);
        }
    }

    /** Width the title text must reserve so Telegram's emoji status still follows the control. */
    public static int widthFor(Context context) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(AndroidUtilities.bold());
        p.setTextSize(dp(14));
        return segmentWidth(p, context.getString(R.string.sec_segment_telegram), false)
                + segmentWidth(p, context.getString(R.string.sec_segment_secured), true);
    }

    private static int segmentWidth(Paint p, String label, boolean lock) {
        return Math.round(p.measureText(label)) + dp(28) + (lock ? dp(18) : 0);
    }

    private int telegramWidth() {
        return segmentWidth(textPaint, telegramLabel, false);
    }

    private int securedWidth() {
        return segmentWidth(textPaint, securedLabel, true);
    }

    private int totalWidth() {
        return telegramWidth() + securedWidth();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(totalWidth(), dp(TOUCH_HEIGHT_DP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        owner.attachSwitcher(this);
        setSelectedImmediately(owner.selectedNetwork());
        setTelegramStatus(owner.telegramStatus());
        setSecuredUnread(owner.securedUnread());
        if (anchor != null) {
            getViewTreeObserver().addOnPreDrawListener(anchorSync);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        owner.detachSwitcher(this);
        if (animator != null) animator.cancel();
        getViewTreeObserver().removeOnPreDrawListener(anchorSync);
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public void setSelectedImmediately(int network) {
        if (animator != null) animator.cancel();
        selected = network;
        position = network == NETWORK_SECURED ? 1f : 0f;
        invalidate();
        a11y.invalidateRoot();
    }

    public void setSelectedAnimated(int network) {
        if (network == selected) return;
        selected = network;
        a11y.invalidateRoot();
        if (animator != null) animator.cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
            position = network == NETWORK_SECURED ? 1f : 0f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(position, network == NETWORK_SECURED ? 1f : 0f);
        animator.setDuration(220);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setTelegramStatus(String status) {
        if (!TextUtils.equals(status, telegramStatus)) {
            telegramStatus = status;
            invalidate();
            a11y.invalidateRoot();
        }
    }

    public void setSecuredUnread(int count) {
        if (securedUnread != count) {
            securedUnread = count;
            invalidate();
            a11y.invalidateRoot();
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();
        final float vh = dp(VISUAL_HEIGHT_DP);
        final float top = (h - vh) / 2f;
        final float tgW = telegramWidth();
        final float inset = dp(3);
        final float r = vh / 2f;

        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int accentFill = SecuredResourcesProvider.accentFill();
        final int accentText = SecuredResourcesProvider.accentText();
        final int trackColor = ColorUtils.setAlphaComponent(text, 0x14);

        // Track.
        track.set(0, top, w, top + vh);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        canvas.drawRoundRect(track, r, r, paint);

        // Sliding pill: Telegram segment, then Secured segment.
        final float l0 = inset, r0 = tgW - inset;
        final float l1 = tgW + inset, r1 = w - inset;
        pill.set(lerp(l0, l1, position), top + inset, lerp(r0, r1, position), top + vh - inset);
        paint.setColor(ColorUtils.blendARGB(surface, accentFill, position));
        canvas.drawRoundRect(pill, r - inset, r - inset, paint);

        final float cy = top + vh / 2f;
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float baseline = cy - (fm.ascent + fm.descent) / 2f;

        // "Telegram" label.
        textPaint.setColor(ColorUtils.blendARGB(text, gray, position));
        final float tgTextW = textPaint.measureText(telegramLabel);
        final float tgTextX = (tgW - tgTextW) / 2f;
        canvas.drawText(telegramLabel, tgTextX, baseline, textPaint);
        if (!TextUtils.isEmpty(telegramStatus)) {
            // Telegram is not connected: a small amber dot, never a claim about Secured.
            paint.setColor(0xFFE5A23B);
            canvas.drawCircle(tgTextX + tgTextW + dpf2(5), cy - dpf2(6), dpf2(3), paint);
        }

        // Lock + "Secured" label.
        final float secW = w - tgW;
        final float lockW = LockIcon.widthFor(LockIcon.defaultSize());
        final float secTextW = textPaint.measureText(securedLabel);
        final float groupW = lockW + dp(4) + secTextW;
        final float groupX = tgW + (secW - groupW) / 2f;
        final int secColor = ColorUtils.blendARGB(accentText, 0xFFFFFFFF, position);
        // The keyhole shows the background behind the lock: the track when idle, the indigo pill when selected.
        final int hole = ColorUtils.blendARGB(ColorUtils.compositeColors(trackColor, surface), accentFill, position);
        LockIcon.draw(canvas, paint, groupX + lockW / 2f, cy, LockIcon.defaultSize(), secColor, hole);
        textPaint.setColor(secColor);
        canvas.drawText(securedLabel, groupX + lockW + dp(4), baseline, textPaint);

        // Unread badge on the Secured segment while Telegram is showing.
        if (securedUnread > 0 && position < 0.99f) {
            final String s = securedUnread > 99 ? "99+" : String.valueOf(securedUnread);
            final float oldSize = textPaint.getTextSize();
            textPaint.setTextSize(dp(10));
            final float bw = Math.max(dp(16), textPaint.measureText(s) + dp(8));
            final float bh = dp(16);
            final float bx = w - dpf2(6) - bw;
            final float by = top - dpf2(4);
            tmp.set(bx, by, bx + bw, by + bh);
            final int a = (int) (255 * (1f - position));
            paint.setColor(ColorUtils.setAlphaComponent(accentFill, a));
            canvas.drawRoundRect(tmp, bh / 2f, bh / 2f, paint);
            textPaint.setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF, a));
            final Paint.FontMetrics sfm = textPaint.getFontMetrics();
            canvas.drawText(s, bx + (bw - textPaint.measureText(s)) / 2f, by + bh / 2f - (sfm.ascent + sfm.descent) / 2f, textPaint);
            textPaint.setTextSize(oldSize);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---- touch -----------------------------------------------------------------------------------------------

    private int segmentAt(float x) {
        return x < telegramWidth() ? NETWORK_TELEGRAM : NETWORK_SECURED;
    }

    private float downX = -1;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_UP:
                if (downX >= 0 && Math.abs(e.getX() - downX) < dp(12)) {
                    choose(segmentAt(e.getX()));
                }
                downX = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                downX = -1;
                return true;
            default:
                return true;
        }
    }

    private void choose(int network) {
        if (network == selected) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        owner.selectNetwork(network);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    // ---- accessibility: two virtual tabs ----------------------------------------------------------------------

    private final class A11y extends ExploreByTouchHelper {
        A11y(ChatsSwitcherView view) {
            super(view);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            return segmentAt(x);
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
            virtualViewIds.add(NETWORK_TELEGRAM);
            virtualViewIds.add(NETWORK_SECURED);
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            final Context c = getContext();
            final int tg = telegramWidth();
            final int h = getMeasuredHeight();
            final Rect bounds = id == NETWORK_TELEGRAM ? new Rect(0, 0, tg, h) : new Rect(tg, 0, getMeasuredWidth(), h);
            CharSequence desc;
            if (id == NETWORK_TELEGRAM) {
                desc = c.getString(selected == NETWORK_TELEGRAM ? R.string.sec_a11y_telegram_selected : R.string.sec_a11y_telegram);
                if (!TextUtils.isEmpty(telegramStatus)) {
                    desc = desc + c.getString(R.string.sec_a11y_telegram_connecting);
                }
            } else {
                desc = c.getString(selected == NETWORK_SECURED ? R.string.sec_a11y_secured_selected : R.string.sec_a11y_secured);
                if (securedUnread > 0) {
                    desc = desc + c.getString(R.string.sec_a11y_secured_unread, securedUnread);
                }
            }
            node.setContentDescription(desc);
            node.setBoundsInParent(bounds);
            node.setSelected(id == selected);
            node.setClickable(true);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                choose(id);
                return true;
            }
            return false;
        }
    }
}
