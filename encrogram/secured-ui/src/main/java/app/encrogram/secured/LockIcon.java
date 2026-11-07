package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Our own rounded-shackle lock with a keyhole (not Telegram's secret-chat lock). Drawn, so it follows any colour. */
final class LockIcon {

    private static final RectF rect = new RectF();

    private LockIcon() {}

    /**
     * @param cx,cy     centre in pixels
     * @param size      outer height in pixels
     * @param color     colour of the lock
     * @param holeColor colour of the keyhole (the background behind the lock)
     */
    static void draw(Canvas canvas, Paint paint, float cx, float cy, float size, int color, int holeColor) {
        final float bodyW = size * 0.82f;
        final float bodyH = size * 0.56f;
        final float bodyTop = cy + size / 2f - bodyH;
        final float shackleW = bodyW * 0.62f;
        final float stroke = Math.max(dpf2(1.5f), size * 0.13f);

        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Shackle: an arch standing on the body.
        rect.set(cx - shackleW / 2f, cy - size / 2f + stroke / 2f, cx + shackleW / 2f, cy - size / 2f + stroke / 2f + shackleW);
        canvas.drawArc(rect, 180, 180, false, paint);
        canvas.drawLine(cx - shackleW / 2f, rect.top + shackleW / 2f, cx - shackleW / 2f, bodyTop + stroke, paint);
        canvas.drawLine(cx + shackleW / 2f, rect.top + shackleW / 2f, cx + shackleW / 2f, bodyTop + stroke, paint);

        paint.setStyle(Paint.Style.FILL);
        rect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH);
        canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, paint);

        paint.setColor(holeColor);
        final float holeR = size * 0.075f;
        final float holeCy = bodyTop + bodyH * 0.42f;
        canvas.drawCircle(cx, holeCy, holeR * 1.25f, paint);
        rect.set(cx - holeR * 0.55f, holeCy, cx + holeR * 0.55f, holeCy + bodyH * 0.32f);
        canvas.drawRect(rect, paint);
    }

    static int widthFor(float size) {
        return Math.round(size * 0.82f);
    }

    static float defaultSize() {
        return dp(14);
    }
}
