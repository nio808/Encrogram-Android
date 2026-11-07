package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** A centred date pill between messages ("Today", "Yesterday", or a date). Local text only. */
final class DateView extends View {

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String text = "";

    DateView(Context context) {
        super(context);
        textPaint.setTextSize(dp(13));
        textPaint.setTypeface(AndroidUtilities.bold());
    }

    void set(String s) {
        text = s;
        setContentDescription(s);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(34));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int textColor = Kit.color(Theme.key_windowBackgroundWhiteGrayText);
        textPaint.setColor(textColor);
        final float tw = textPaint.measureText(text);
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        rect.set(cx - tw / 2f - dp(10), cy - dp(11), cx + tw / 2f + dp(10), cy + dp(11));
        bg.setColor(ColorUtils.setAlphaComponent(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0x12));
        canvas.drawRoundRect(rect, dp(11), dp(11), bg);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(text, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint);
    }
}
