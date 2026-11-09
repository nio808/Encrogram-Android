package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.MediaActionDrawable;

import app.encrogram.core.api.MediaState;

/**
 * The voice bubble (task MU4). Reuse levels: base bubble, time and ticks come from {@link SecuredMediaCell} (1);
 * the button icons are Telegram's {@link MediaActionDrawable}, configured (2); the geometry and the bar pitch are
 * copied from ChatMessageCell and SeekBarWaveform (3); the circle, the progress ring and the flat bars are ours (4).
 * <p>
 * No waveform travels with SimpleX voice messages, so the bars are flat: nothing invented. Everything drawn comes
 * from the media state and from {@link Host#audio()}; the chat screen invalidates this cell when the player reports.
 */
final class SecuredVoiceCell extends SecuredMediaCell {

    private static final int BUTTON = 44;
    private static final int BAR_PITCH = 3;
    private static final int BAR_WIDTH = 2;
    private static final int BAR_HEIGHT = 3;

    private final MediaActionDrawable actionDrawable = new MediaActionDrawable();
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint durationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private final RectF button = new RectF();
    private final RectF wave = new RectF();
    private float seekFraction;

    SecuredVoiceCell(Context context, Theme.ResourcesProvider resources, Host host) {
        super(context, resources, host);
        durationPaint.setTextSize(dp(12));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeWidth(dp(2));
    }

    @Override
    protected void onBind(boolean sameMessage) {
    }

    @Override
    protected void onProgress() {
        // The ring is drawn from progressFraction() each frame; the base invalidates after this call.
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    @Override
    protected int bodyWidth(int maxWidth) {
        final int seconds = media != null && media.getDurationSeconds() != null ? media.getDurationSeconds() : 0;
        final int durationWidth = (int) Math.ceil(durationPaint.measureText(MediaText.duration(seconds)));
        final int wanted = dp(174) + timeWidth() + durationWidth + (out ? dp(20) : 0);
        final int width = Math.min(Math.min(maxWidth, dp(270)), wanted);
        return Math.max(width, minBodyWidthForTime() + dp(120));
    }

    @Override
    protected int contentHeight(int bodyWidth) {
        return dp(70);
    }

    @Override
    protected void contentInsets(Rect rect) {
        rect.set(0, 0, 0, 0);
    }

    @Override
    protected boolean timeOverContent() {
        return false;
    }

    private void layoutParts() {
        final float left = content.left + dp(12) + (out ? 0 : dp(6));
        final float top = content.top + dp(13);
        button.set(left, top, left + dp(BUTTON), top + dp(BUTTON));
        final float waveLeft = button.right + dp(13);
        final float waveWidth = Math.max(dp(BAR_PITCH), content.width() - dp(92));
        wave.set(waveLeft, content.top + dp(13), waveLeft + waveWidth, content.top + dp(13) + dp(30));
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    @Override
    protected void drawContent(Canvas canvas) {
        layoutParts();
        final long chat = message.getChatId();
        final long id = message.getMessageId();
        final SecuredAudioPlayer audio = host != null ? host.audio() : null;
        final boolean playing = audio != null && audio.isPlaying(chat, id);
        final float played = audio != null ? audio.progress(chat, id) : 0f;

        drawButton(canvas, playing);
        drawBars(canvas, played);
        drawDuration(canvas, audio, playing, played);
    }

    private void drawButton(Canvas canvas, boolean playing) {
        final float cx = button.centerX();
        final float cy = button.centerY();
        circlePaint.setColor(color(out ? Theme.key_chat_outLoader : Theme.key_chat_inLoader));
        canvas.drawCircle(cx, cy, dp(BUTTON) / 2f, circlePaint);

        final MediaState state = media.getState();
        final int icon;
        float alpha = 1f;
        if (state == MediaState.WAITING) {
            icon = MediaActionDrawable.ICON_DOWNLOAD;
        } else if (state == MediaState.DOWNLOADING || state == MediaState.UPLOADING) {
            icon = MediaActionDrawable.ICON_CANCEL;
        } else if (state == MediaState.FAILED) {
            icon = MediaActionDrawable.ICON_PLAY;
            alpha = 0.5f;
        } else {
            icon = playing ? MediaActionDrawable.ICON_PAUSE : MediaActionDrawable.ICON_PLAY;
        }
        actionDrawable.setColor(color(out ? Theme.key_chat_outMediaIcon : Theme.key_chat_inMediaIcon));
        actionDrawable.setIcon(icon, false);
        actionDrawable.setOverrideAlpha(alpha);
        actionDrawable.setBounds((int) button.left, (int) button.top, (int) button.right, (int) button.bottom);
        actionDrawable.draw(canvas);

        if (moving()) {
            // Our own ring: the real fraction, never an invented one (a queued file shows only a short stub).
            ringPaint.setColor(color(out ? Theme.key_chat_outFileProgress : Theme.key_chat_inFileProgress));
            final float inset = dp(3);
            tmp.set(button.left + inset, button.top + inset, button.right - inset, button.bottom - inset);
            canvas.drawArc(tmp, -90f, Math.max(4f, 360f * progressFraction()), false, ringPaint);
        }
    }

    private void drawBars(Canvas canvas, float played) {
        final int count = Math.max(1, (int) (wave.width() / dp(BAR_PITCH)));
        final int fill = color(out ? Theme.key_chat_outAudioSeekbarFill : Theme.key_chat_inAudioSeekbarFill);
        final int rest = color(out ? Theme.key_chat_outAudioSeekbar : Theme.key_chat_inAudioSeekbar);
        final float cy = wave.centerY();
        final float half = dp(BAR_HEIGHT) / 2f;
        final float radius = dp(BAR_WIDTH) / 2f;
        for (int i = 0; i < count; i++) {
            final float x = wave.left + i * dp(BAR_PITCH);
            barPaint.setColor(i * dp(BAR_PITCH) < played * wave.width() ? fill : rest);
            tmp.set(x, cy - half, x + dp(BAR_WIDTH), cy + half);
            canvas.drawRoundRect(tmp, radius, radius, barPaint);
        }
    }

    private void drawDuration(Canvas canvas, SecuredAudioPlayer audio, boolean playing, float played) {
        final String text;
        if (media.getState() == MediaState.FAILED) {
            text = MediaText.failureShort(getContext(), media.getFailure());
        } else if (moving()) {
            text = MediaText.progress(getContext(), progressDone, progressTotal);
        } else if (audio != null && (playing || played > 0f)) {
            text = MediaText.duration(audio.positionSeconds(message.getChatId(), message.getMessageId()));
        } else {
            text = MediaText.duration(media.getDurationSeconds() != null ? media.getDurationSeconds() : 0);
        }
        durationPaint.setColor(color(out ? Theme.key_chat_outAudioDurationText : Theme.key_chat_inAudioDurationText));
        final CharSequence shown = TextUtils.ellipsize(text, durationPaint, Math.max(dp(40), wave.width()), TextUtils.TruncateAt.END);
        final float baseline = content.top + dp(44) - durationPaint.ascent();
        canvas.drawText(shown, 0, shown.length(), wave.left, baseline, durationPaint);
    }

    // ---- taps ------------------------------------------------------------------------------------------------

    @Override
    protected int contentHit(float x, float y) {
        layoutParts();
        final float dx = x - button.centerX();
        final float dy = y - button.centerY();
        final float r = dp(BUTTON) / 2f + dp(4);
        if (dx * dx + dy * dy <= r * r) return HIT_BUTTON;
        if (x >= wave.left && x <= wave.right && y >= wave.top - dp(6) && y <= wave.bottom + dp(6)) {
            seekFraction = Math.max(0f, Math.min(1f, (x - wave.left) / wave.width()));
            return HIT_SEEK;
        }
        return HIT_NONE;
    }

    @Override
    float seekFraction() {
        return seekFraction;
    }
}
