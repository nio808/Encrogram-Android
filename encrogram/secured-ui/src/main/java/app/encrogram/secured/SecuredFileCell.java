package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.MediaActionDrawable;

import java.util.Locale;

import app.encrogram.core.api.MediaState;

/**
 * A file bubble (task MU3): round button, name (two lines, ellipsized in the middle) and an info line, laid out like
 * Telegram's document bubble. Used for MediaKind.FILE and for any kind the chat can't show otherwise.
 * <p>
 * Reuse: {@link MediaActionDrawable} configured, with its own animated progress arc (2); the circle, text layouts
 * and geometry are small copies of ChatMessageCell's document drawing (3); the state logic is ours (4).
 */
final class SecuredFileCell extends SecuredMediaCell {

    private static final int BUTTON = 44;

    private final MediaActionDrawable actionDrawable = new MediaActionDrawable();
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint infoPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private String nameText = "";
    private StaticLayout nameLayout;
    private int nameLayoutWidth = -1;
    private String infoFull = "";
    private String infoShown = "";
    private int infoWidth = -1;
    private boolean infoIsError;
    private int nameLines = 1;

    SecuredFileCell(Context context, Theme.ResourcesProvider resources, Host host) {
        super(context, resources, host);
        namePaint.setTypeface(AndroidUtilities.bold());
        namePaint.setTextSize(dp(15));
        infoPaint.setTextSize(dp(12));
        actionDrawable.setDelegate(this::invalidate);
    }

    // ---- binding ---------------------------------------------------------------------------------------------

    @Override
    protected void onBind(boolean sameMessage) {
        final String name = media.getName();
        final String shown = TextUtils.isEmpty(name) ? MediaText.kind(getContext(), media.getKind()) : name.replace('\n', ' ');
        if (!shown.equals(nameText)) {
            nameText = shown;
            nameLayout = null; // rebuilt in contentHeight, which knows the width
            nameLayoutWidth = -1;
        }
        final MediaState state = media.getState();
        int icon;
        if (state == MediaState.WAITING) {
            icon = MediaActionDrawable.ICON_DOWNLOAD;
        } else if (moving()) {
            icon = MediaActionDrawable.ICON_CANCEL;
        } else {
            icon = MediaActionDrawable.ICON_FILE;
        }
        actionDrawable.setIcon(icon, sameMessage);
        actionDrawable.setProgress(moving() ? progressFraction() : 0f, false);
        updateInfo();
    }

    @Override
    protected void onProgress() {
        if (media == null) return;
        actionDrawable.setProgress(progressFraction(), true);
        updateInfo();
    }

    private void updateInfo() {
        final MediaState state = media.getState();
        infoIsError = false;
        if (state == MediaState.FAILED) {
            infoFull = MediaText.failureShort(getContext(), media.getFailure());
            infoIsError = true;
        } else if (moving()) {
            infoFull = MediaText.progress(getContext(), progressDone, progressTotal);
        } else if (state == MediaState.READY) {
            final String ext = extension(media.getName());
            infoFull = ext == null ? MediaText.size(media.getSizeBytes()) : MediaText.size(media.getSizeBytes()) + " · " + ext;
        } else {
            infoFull = MediaText.size(media.getSizeBytes());
        }
        infoShown = fitInfo();
    }

    private String fitInfo() {
        if (infoWidth <= 0) return infoFull;
        return TextUtils.ellipsize(infoFull, infoPaint, infoWidth, TextUtils.TruncateAt.END).toString();
    }

    /** Upper-case extension when it is short and plain (1-5 letters or digits), else null. */
    private static String extension(String name) {
        if (name == null) return null;
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        final int len = name.length() - dot - 1;
        if (len > 5) return null;
        for (int i = dot + 1; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (!(c < 128 && Character.isLetterOrDigit(c))) return null;
        }
        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    @Override
    protected int bodyWidth(int maxWidth) {
        final int wanted = Math.min(maxWidth, dp(270));
        return Math.min(maxWidth, Math.max(wanted, minBodyWidthForTime() + dp(80)));
    }

    private int textLeftOffset() {
        return dp(12 + (out ? 0 : 6) + BUTTON + 9);
    }

    @Override
    protected int contentHeight(int bodyWidth) {
        final int nameWidth = Math.max(dp(40), bodyWidth - textLeftOffset() - dp(12));
        if (nameLayout == null || nameLayoutWidth != nameWidth) {
            nameLayout = buildName(nameWidth);
            nameLayoutWidth = nameWidth;
        }
        nameLines = Math.max(1, Math.min(2, nameLayout.getLineCount()));
        // Keep the info line clear of the time and ticks in the bottom right corner.
        final int newInfoWidth = Math.max(dp(40), bodyWidth - textLeftOffset() - timeWidth() - dp(30));
        if (newInfoWidth != infoWidth) {
            infoWidth = newInfoWidth;
            infoShown = fitInfo();
        }
        return dp(70) + (nameLines - 1) * dp(16);
    }

    private StaticLayout buildName(int width) {
        namePaint.setColor(color(out ? Theme.key_chat_outFileNameText : Theme.key_chat_inFileNameText));
        CharSequence text = nameText;
        // Ellipsize in the middle so the extension stays visible; the slack covers word-wrap waste on line 1.
        text = TextUtils.ellipsize(text, namePaint, width * 2f - dp(12), TextUtils.TruncateAt.MIDDLE);
        text = Emoji.replaceEmoji(text, namePaint.getFontMetricsInt(), false);
        return StaticLayout.Builder.obtain(text, 0, text.length(), namePaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END) // safety net if wrapping still needed a third line
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build();
    }

    @Override
    protected void contentInsets(Rect out) {
        out.set(0, 0, 0, 0);
    }

    @Override
    protected boolean timeOverContent() {
        return false;
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    private float buttonLeft() {
        return content.left + dp(12 + (out ? 0 : 6));
    }

    private float buttonTop() {
        return content.top + dp(13);
    }

    @Override
    protected void drawContent(Canvas canvas) {
        final boolean failed = media.getState() == MediaState.FAILED;
        final float bl = buttonLeft();
        final float bt = buttonTop();
        final float r = dp(BUTTON) / 2f;

        circlePaint.setColor(color(out ? Theme.key_chat_outLoader : Theme.key_chat_inLoader));
        circlePaint.setAlpha(failed ? 110 : 255);
        canvas.drawCircle(bl + r, bt + r, r, circlePaint);

        actionDrawable.setColor(color(out ? Theme.key_chat_outMediaIcon : Theme.key_chat_inMediaIcon));
        actionDrawable.setOverrideAlpha(failed ? 0.5f : 1f);
        actionDrawable.setBounds((int) bl, (int) bt, (int) (bl + dp(BUTTON)), (int) (bt + dp(BUTTON)));
        actionDrawable.draw(canvas);

        final float textLeft = content.left + textLeftOffset();
        if (nameLayout != null) {
            namePaint.setColor(color(out ? Theme.key_chat_outFileNameText : Theme.key_chat_inFileNameText));
            canvas.save();
            canvas.translate(textLeft, content.top + dp(6));
            nameLayout.draw(canvas);
            canvas.restore();
        }

        infoPaint.setColor(infoIsError ? color(Theme.key_text_RedRegular)
                : color(out ? Theme.key_chat_outFileInfoText : Theme.key_chat_inFileInfoText));
        final float infoTop = content.top + dp(27) + (nameLines - 1) * dp(16);
        // drawText wants the baseline: top of a 12dp line plus its ascent.
        canvas.drawText(infoShown, textLeft, infoTop - infoPaint.getFontMetrics().ascent, infoPaint);
    }

    @Override
    protected int contentHit(float x, float y) {
        final float r = dp(BUTTON) / 2f;
        final float dx = x - (buttonLeft() + r);
        final float dy = y - (buttonTop() + r);
        return dx * dx + dy * dy <= r * r ? HIT_BUTTON : HIT_NONE;
    }
}
