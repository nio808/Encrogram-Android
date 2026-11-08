package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;

/** Small view helpers for the Secured screens. Colours come from our provider, so they follow the theme. */
final class Kit {

    private Kit() {}

    /** A dialog or sheet is its own window, so the activity's FLAG_SECURE does not cover it: no screenshots, blank in Recents. */
    static void secure(Dialog d) {
        if (d != null && d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /**
     * Telegram's story viewer and payment screen clear the activity's FLAG_SECURE directly, behind FlagSecureReason's back.
     * Call when a Secured screen becomes visible again: puts the flag back only while some Secured screen holds a reason.
     */
    static void reassertSecure(android.app.Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        if (org.telegram.messenger.FlagSecureReason.isSecuredNow(activity.getWindow())) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** The one way a Secured screen shows a dialog or sheet: FLAG_SECURE first, then Telegram's showDialog (null during a screen transition). */
    static Dialog show(BaseFragment fragment, Dialog d) {
        secure(d);
        return fragment.showDialog(d);
    }

    /**
     * A PopupWindow (message menu, "more" menu) is its own window too and has no getWindow(): once its content is attached,
     * add FLAG_SECURE to the window's layout params. Safe to call before or after the popup shows; it re-applies on every show.
     */
    static void securePopup(View content) {
        if (content == null) return;
        if (content.isAttachedToWindow()) {
            secureRootWindow(content);
        }
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.post(() -> secureRootWindow(v));
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });
    }

    static void securePopup(PopupWindow popup) {
        if (popup != null) securePopup(popup.getContentView());
    }

    private static void secureRootWindow(View v) {
        try {
            final View root = v.getRootView();
            final ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (!(lp instanceof WindowManager.LayoutParams)) return;
            final WindowManager.LayoutParams p = (WindowManager.LayoutParams) lp;
            if ((p.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) return;
            p.flags |= WindowManager.LayoutParams.FLAG_SECURE;
            final WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.updateViewLayout(root, p);
        } catch (Throwable ignored) {
            // popup already gone
        }
    }

    static int color(int key) {
        return SecuredResourcesProvider.get().getColor(key);
    }

    /**
     * Avatar and name colour input: a stable hash of the trimmed display name (String.hashCode is specified, so it is the
     * same on every phone), never a local chat or member id. Telegram's AvatarDrawable colour set picks from it.
     */
    static long colorKey(String name) {
        return name == null ? 0 : name.trim().hashCode();
    }

    /**
     * The one place that sets a Secured avatar's colour and initials. {@code colorKey} is {@link ChatRowMapper#colorId}
     * of the chat id (or a member id), so a chat has the same colour in the list, the header and its info screen.
     * AvatarDrawable takes initials from first and last name, and its single-string path mis-reads a two-word title
     * ("Team Test" gives "TE"), so split at the last space ("TT").
     */
    static void setAvatar(AvatarDrawable d, long ignoredLocalId, String name) {
        final String shown = name == null ? "" : name.trim();
        final long colorKey = colorKey(shown);
        final int sp = shown.lastIndexOf(' ');
        d.setInfo(colorKey, sp > 0 ? shown.substring(0, sp).trim() : shown, sp > 0 ? shown.substring(sp + 1) : null);
    }

    static TextView text(Context context, CharSequence s, float sp, int color, boolean bold) {
        final TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    static GradientDrawable roundRect(int fill, float radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** A rounded button: filled with the accent colour, or text-only in the accent colour. */
    static TextView button(Context context, CharSequence label, boolean filled) {
        final TextView b = text(context, label, 16, filled ? 0xFFFFFFFF : SecuredResourcesProvider.accentText(), true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setClickable(true);
        b.setFocusable(true);
        final int ripple = ColorUtils.setAlphaComponent(filled ? 0xFFFFFFFF : SecuredResourcesProvider.accentFill(), 0x33);
        final GradientDrawable shape = filled ? roundRect(SecuredResourcesProvider.accentFill(), 10) : roundRect(0x00000000, 10);
        if (!filled) {
            shape.setStroke(dp(1), ColorUtils.setAlphaComponent(SecuredResourcesProvider.accentFill(), 0x80));
        }
        final GradientDrawable mask = roundRect(0xFFFFFFFF, 10);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask));
        return b;
    }

    /** A large lock in a soft indigo disc, for empty and onboarding states. */
    static final class LockBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockBadge(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(96), dp(96));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final int accent = SecuredResourcesProvider.accentFill();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(accent, 0x22));
            canvas.drawCircle(cx, cy, dp(48), paint);
            LockIcon.draw(canvas, paint, cx, cy, dp(44), SecuredResourcesProvider.accentText(),
                    ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 0x22), Kit.color(Theme.key_windowBackgroundWhite)));
        }
    }
}
