package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormat;
import java.util.Date;

import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.SecuredMessage;

/**
 * One text bubble, drawn from scratch with Telegram's metrics and theme keys. It uses no Telegram message classes,
 * so nothing here can download, cache or look anything up. Text is shown as plain text: no link previews, no
 * link handling, no stickers or emoji lookups.
 *
 * Ticks (outgoing only): clock = sending, one tick = on our server, outline double tick = delivered, red ! = failed.
 */
final class BubbleView extends View {

    private static final DateFormat TIME = DateFormat.getTimeInstance(DateFormat.SHORT);

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private SecuredMessage message;
    private StaticLayout layout;
    private String timeText = "";
    private float bubbleLeft, bubbleRight, bubbleTop, bubbleBottom;
    private float textX, textY, timeRight, timeBaseline;

    BubbleView(Context context) {
        super(context);
        textPaint.setTextSize(dp(16));
        timePaint.setTextSize(dp(12));
        setWillNotDraw(false);
        setLongClickable(true);
    }

    SecuredMessage message() {
        return message;
    }

    void set(SecuredMessage m) {
        message = m;
        timeText = TIME.format(new Date(m.getTimeMs()));
        setContentDescription(describe());
        requestLayout();
        invalidate();
    }

    private String describe() {
        final Context c = getContext();
        final String when = timeText;
        if (message.getOutgoing()) {
            return c.getString(R.string.sec_message_out, message.getText(), when + ", " + statusLabel(c, message.getStatus()));
        }
        return c.getString(R.string.sec_message_in, message.getText(), when);
    }

    static String statusLabel(Context c, MessageStatus s) {
        switch (s) {
            case SENDING:
                return c.getString(R.string.sec_tick_sending);
            case SENT_TO_SERVER:
                return c.getString(R.string.sec_tick_sent);
            case DELIVERED:
                return c.getString(R.string.sec_tick_delivered);
            case FAILED:
                return c.getString(R.string.sec_tick_failed);
            default:
                return "";
        }
    }

    private int tickWidth() {
        return message.getOutgoing() ? dp(20) : 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (message == null) {
            setMeasuredDimension(width, dp(8));
            return;
        }
        final boolean out = message.getOutgoing();
        final int sideGap = dp(8);
        final int maxBubble = Math.min(width - dp(48), (int) (width * 0.82f));
        final int padH = dp(10);
        final int maxText = Math.max(dp(40), maxBubble - padH * 2);

        textPaint.setColor(Kit.color(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn));
        timePaint.setColor(Kit.color(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText));

        layout = StaticLayout.Builder.obtain(message.getText(), 0, message.getText().length(), textPaint, maxText)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        float textW = 0;
        for (int i = 0; i < layout.getLineCount(); i++) {
            textW = Math.max(textW, layout.getLineWidth(i));
        }
        final float lastLineW = layout.getLineCount() > 0 ? layout.getLineWidth(layout.getLineCount() - 1) : 0;
        final float metaW = timePaint.measureText(timeText) + tickWidth() + dp(2);
        final float gap = dp(8);
        float innerW;
        float innerH = layout.getHeight();
        final boolean metaOnLastLine = lastLineW + gap + metaW <= maxText;
        if (metaOnLastLine) {
            innerW = Math.max(textW, lastLineW + gap + metaW);
        } else {
            innerW = Math.max(textW, metaW);
            innerH += dp(14);
        }
        final float bubbleW = innerW + padH * 2;
        final float bubbleH = innerH + dp(7) + dp(7);
        final int vGap = dp(2);

        if (out) {
            bubbleRight = width - sideGap;
            bubbleLeft = bubbleRight - bubbleW;
        } else {
            bubbleLeft = sideGap;
            bubbleRight = bubbleLeft + bubbleW;
        }
        bubbleTop = vGap;
        bubbleBottom = bubbleTop + bubbleH;
        textX = bubbleLeft + padH;
        textY = bubbleTop + dp(7);
        timeRight = bubbleRight - padH;
        timeBaseline = bubbleBottom - dp(7) - dpf2(3);
        setMeasuredDimension(width, (int) (bubbleBottom + vGap));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (message == null) return;
        if (layout == null) return;
        final boolean out = message.getOutgoing();
        final float r = dp(17), small = dp(5);

        // Bubble: Telegram's rounded corners with one small corner on the outer bottom edge.
        rect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
        path.rewind();
        final float[] radii = out
                ? new float[]{r, r, r, r, small, small, r, r}
                : new float[]{r, r, r, r, r, r, small, small};
        path.addRoundRect(rect, radii, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Kit.color(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
        canvas.drawPath(path, paint);

        // Text.
        textPaint.setColor(Kit.color(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn));
        canvas.save();
        canvas.translate(textX, textY);
        layout.draw(canvas);
        canvas.restore();

        // Time and ticks.
        timePaint.setColor(Kit.color(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText));
        final float tw = tickWidth();
        canvas.drawText(timeText, timeRight - tw - timePaint.measureText(timeText), timeBaseline, timePaint);
        if (out) {
            drawStatus(canvas, timeRight - dp(14), timeBaseline - dp(4), message.getStatus());
        }
    }

    /** Draws a status mark in a 14 x 10 dp box ending at {@code right}, vertically centred on {@code cy}. */
    private void drawStatus(Canvas canvas, float right, float cy, MessageStatus status) {
        final int color = Kit.color(Theme.key_chat_outSentCheck);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        switch (status) {
            case SENDING: {
                final float cx = right + dp(7), rad = dpf2(4.6f);
                paint.setStrokeWidth(dpf2(1.3f));
                canvas.drawCircle(cx, cy, rad, paint);
                canvas.drawLine(cx, cy, cx, cy - rad * 0.65f, paint);
                canvas.drawLine(cx, cy, cx + rad * 0.5f, cy, paint);
                break;
            }
            case SENT_TO_SERVER:
                paint.setStrokeWidth(dpf2(1.6f));
                drawCheck(canvas, right + dp(7), cy);
                break;
            case DELIVERED:
                // Outline style (thin strokes): never the filled "read" look, because there are no read receipts.
                paint.setStrokeWidth(dpf2(1.2f));
                drawCheck(canvas, right + dp(7) - dpf2(3.6f), cy);
                drawCheck(canvas, right + dp(7) + dpf2(1.6f), cy);
                break;
            case FAILED: {
                final float cx = right + dp(7), rad = dpf2(5.2f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Kit.color(Theme.key_text_RedBold));
                canvas.drawCircle(cx, cy, rad, paint);
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf2(1.4f));
                canvas.drawLine(cx, cy - rad * 0.55f, cx, cy + rad * 0.1f, paint);
                canvas.drawPoint(cx, cy + rad * 0.52f, paint);
                break;
            }
            default:
                break;
        }
    }

    private void drawCheck(Canvas canvas, float cx, float cy) {
        final float w = dpf2(4.2f), h = dpf2(3.2f);
        path.rewind();
        path.moveTo(cx - w, cy);
        path.lineTo(cx - w * 0.3f, cy + h);
        path.lineTo(cx + w * 1.1f, cy - h * 1.1f);
        canvas.drawPath(path, paint);
    }
}
