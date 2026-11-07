package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** Small view helpers for the Secured screens. Colours come from our provider, so they follow the theme. */
final class Kit {

    private Kit() {}

    static int color(int key) {
        return SecuredResourcesProvider.get().getColor(key);
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
