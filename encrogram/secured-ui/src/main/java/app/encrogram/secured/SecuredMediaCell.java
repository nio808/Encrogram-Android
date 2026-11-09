package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CheckBoxBase;
import org.telegram.ui.Components.MsgClockDrawable;

import app.encrogram.core.api.MediaKind;
import app.encrogram.core.api.MediaState;
import app.encrogram.core.api.MessageSender;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.QuotePreview;
import app.encrogram.core.api.SecuredMedia;
import app.encrogram.core.api.SecuredMessage;

/**
 * The shared part of every Secured media bubble (task MU0). Subclasses draw the content: {@link SecuredPhotoCell}
 * (photo, GIF, video), {@link SecuredFileCell} and {@link SecuredVoiceCell}.
 * <p>
 * Reuse levels: the bubble is Telegram's {@link MessageDrawable} from our chat ResourcesProvider (1); the
 * checkbox is {@link CheckBoxBase} (1); the clock is {@link MsgClockDrawable} and the ticks are the provider's tick
 * drawables, or private tinted copies of Telegram's white media ticks (2); the geometry numbers are copied from
 * ChatMessageCell (3, research/media-ui.md); the rest is ours (4).
 * <p>
 * The wall (AGENTS.md rule 1): this view never builds a MessageObject and never touches ImageReceiver, ImageLoader,
 * FileLoader or MediaController. It draws only what the engine reported: no tick beyond the message's status, no
 * "ready" before the media state says READY.
 */
abstract class SecuredMediaCell extends View {

    /** What a tap at (x, y) in this cell means. The chat screen decides what each means for the message's state. */
    static final int HIT_NONE = 0;
    /** The media itself (open, download, cancel, depending on the state). */
    static final int HIT_CONTENT = 1;
    /** The round action button (download, cancel, play). */
    static final int HIT_BUTTON = 2;
    /** The reply header: jump to the quoted message. */
    static final int HIT_QUOTE = 3;
    /** Voice waveform: seek to {@link #seekFraction()}. */
    static final int HIT_SEEK = 4;

    /** What the cell needs from its chat screen. Main thread only. */
    interface Host {
        /** The chat screen's bitmap decoder and memory cache (MD11). */
        MediaBitmaps bitmaps();

        /** The chat screen's one voice player (null until a voice cell needs it). */
        SecuredAudioPlayer audio();

        /** The name shown over a reply quote: "You", the contact, or a group member. */
        String quoteAuthor(SecuredMessage m);
    }

    protected final Theme.ResourcesProvider resources;
    protected final Host host;

    protected SecuredMessage message;
    /** Never null while bound. */
    protected SecuredMedia media;
    protected boolean out;
    protected boolean pinnedTop;
    /** Joined to the bubble below (its caption, or the same sender's next message): no avatar, joined corners. */
    protected boolean pinnedBottom;
    /** A caption bubble is joined under this one: no time here, square bottom corners. */
    protected boolean captionBelow;
    protected boolean group;
    protected boolean notes;
    protected long progressDone;
    protected long progressTotal;

    /** The visible bubble body (no tail, no shadow padding), in cell coordinates. */
    protected final RectF body = new RectF();
    /** Where the subclass draws, below the header. */
    protected final RectF content = new RectF();

    private final CheckBoxBase checkBox;
    private boolean checkBoxVisible;
    private boolean checked;
    private boolean highlighted;

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint forwardPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint quoteNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint quoteTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint errorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint();
    private final RectF tmp = new RectF();
    private final Rect bounds = new Rect();

    private StaticLayout nameLayout;
    private StaticLayout forwardLayout;
    private StaticLayout quoteNameLayout;
    private StaticLayout quoteTextLayout;
    private final RectF quoteRect = new RectF();
    private int nameColor;
    private String timeText = "";
    private int timeWidth;

    private final AvatarDrawable avatar;
    private boolean drawAvatar;

    private final MsgClockDrawable clock = new MsgClockDrawable();
    private Drawable mediaCheck;
    private Drawable mediaHalfCheck;
    private SecuredDialogCell.OutlineTick mediaOutline1;
    private SecuredDialogCell.OutlineTick mediaOutline2;

    SecuredMediaCell(Context context, Theme.ResourcesProvider resources, Host host) {
        super(context);
        this.resources = resources;
        this.host = host;
        checkBox = new CheckBoxBase(this, 21, resources);
        checkBox.setColor(-1, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        checkBox.setDrawUnchecked(true);
        checkBox.setBackgroundType(0);
        avatar = new AvatarDrawable(resources);
        namePaint.setTypeface(AndroidUtilities.bold());
        namePaint.setTextSize(dp(14));
        forwardPaint.setTextSize(dp(14));
        quoteNamePaint.setTypeface(AndroidUtilities.bold());
        quoteNamePaint.setTextSize(dp(14));
        quoteTextPaint.setTextSize(dp(14));
        timePaint.setTextSize(dp(12));
        errorPaint.setColor(color(Theme.key_chat_sentError));
        // Secured text is never offered to autofill services.
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
    }

    // ---- binding ---------------------------------------------------------------------------------------------

    /**
     * Binds a media message. {@code captionBelow}: its caption is shown as a joined text bubble under this one.
     * {@code pinnedBottom}: joined to the next bubble of the same run (always true with a caption below).
     * {@code group}: incoming bubbles get the avatar column and the sender's name.
     */
    final void bind(SecuredMessage m, boolean pinnedTop, boolean pinnedBottom, boolean captionBelow, boolean group, boolean notes) {
        final boolean sameMessage = message != null && m != null && message.getMessageId() == m.getMessageId();
        final boolean sameFile = sameMessage && media != null && m.getMedia() != null && media.getState() == m.getMedia().getState();
        message = m;
        media = m.getMedia();
        out = m.getOutgoing();
        this.pinnedTop = pinnedTop;
        this.captionBelow = captionBelow;
        this.pinnedBottom = pinnedBottom || captionBelow;
        this.group = group;
        this.notes = notes;
        if (!sameFile && media != null) {
            // A new snapshot: start from the engine's own count; live updates follow through setProgress.
            progressDone = media.getTransferredBytes();
            progressTotal = media.getSizeBytes();
        }
        timeText = LocaleController.getInstance().getFormatterDay().format(m.getTimeMs());
        timeWidth = (int) Math.ceil(timePaint.measureText(timeText));
        final MessageSender sender = m.getSender();
        drawAvatar = group && !out && sender != null;
        if (drawAvatar) {
            Kit.setAvatar(avatar, sender.getMemberId(), sender.getDisplayName());
            nameColor = color(Theme.keys_avatar_nameInMessage[AvatarDrawable.getColorIndex(Kit.colorKey(sender.getDisplayName()))]);
        }
        onBind(sameMessage);
        requestLayout();
        invalidate();
    }

    /** Live progress from SecuredListener.onMediaProgress: redraws this cell only. */
    final void setProgress(long done, long total) {
        progressDone = done;
        progressTotal = total;
        onProgress();
        invalidate();
    }

    final SecuredMessage message() {
        return message;
    }

    final long messageId() {
        return message != null ? message.getMessageId() : Long.MIN_VALUE;
    }

    void setCheckBoxVisible(boolean visible, boolean animated) {
        if (visible == checkBoxVisible) return;
        checkBoxVisible = visible;
        requestLayout();
        invalidate();
    }

    void setChecked(boolean value, boolean animated) {
        checked = value;
        checkBox.setChecked(value, animated);
        invalidate();
    }

    void setHighlighted(boolean value) {
        if (highlighted == value) return;
        highlighted = value;
        invalidate();
    }

    /** Called after every bind. {@code sameMessage}: the same message again (a new state or status), not a recycle. */
    protected abstract void onBind(boolean sameMessage);

    /** Live progress changed ({@link #progressDone} of {@link #progressTotal}). */
    protected void onProgress() {
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    /** The bubble's body width for this message, at most {@code maxWidth} (the room left in this row). */
    protected abstract int bodyWidth(int maxWidth);

    /** Content height for a body of {@code bodyWidth}. */
    protected abstract int contentHeight(int bodyWidth);

    /** Insets of the content inside the body: left, top (under the header), right, bottom. */
    protected abstract void contentInsets(Rect out);

    /** Photo and video: the time sits on a dark pill over the media. File and voice: in the bubble's corner. */
    protected abstract boolean timeOverContent();

    /** Media-type bubble (no tail): photos always, files and voice only when a caption is joined below. */
    protected boolean mediaBubble() {
        return timeOverContent() || captionBelow;
    }

    /** A name, "Forwarded" or a reply quote sits above the content (photos then square their top corners). */
    protected final boolean hasHeader() {
        return nameLayout != null || forwardLayout != null || quoteNameLayout != null;
    }

    /** Width of the time text alone (no ticks). */
    protected final int timeWidth() {
        return timeWidth;
    }

    /** The narrowest a body may be so the time and ticks fit. */
    protected final int minBodyWidthForTime() {
        return timeWidth + dp(SharedConfig.bubbleRadius >= 10 ? 22 : 18) + (out ? dp(20) : 0);
    }

    /** The widest a bubble may be in this row (Telegram's rule for files and voice: 270dp, minus the margins). */
    protected final int maxFileBodyWidth(int rowWidth) {
        return Math.min(rowWidth - dp(50) - (drawAvatar || groupIncoming() ? dp(52) : 0), dp(270));
    }

    private boolean groupIncoming() {
        return group && !out;
    }

    private int leftOffset() {
        return (groupIncoming() ? dp(48) : 0) + (checkBoxVisible && !out ? dp(35) : 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        if (message == null || media == null) {
            setMeasuredDimension(width, dp(1));
            return;
        }
        final int room = width - leftOffset() - dp(22);
        int bw = Math.max(dp(100), Math.min(bodyWidth(room), room));
        final int textWidth = bw - dp(20);
        buildHeader(textWidth);
        final Rect insets = new Rect();
        contentInsets(insets);
        final int header = headerHeight();
        final int top = pinnedTop ? dp(1) : dp(4);
        final int bottomGap = pinnedBottom ? dp(1) : dp(4);
        final int contentH = contentHeight(bw);
        final int bodyH = header + insets.top + contentH + insets.bottom;
        final float left = out ? width - dp(11) - bw : leftOffset() + dp(11);
        body.set(left, top, left + bw, top + bodyH);
        content.set(body.left + insets.left, body.top + header + insets.top, body.right - insets.right, body.top + header + insets.top + contentH);
        setMeasuredDimension(width, top + bodyH + bottomGap);
    }

    private void buildHeader(int width) {
        nameLayout = null;
        forwardLayout = null;
        quoteNameLayout = null;
        quoteTextLayout = null;
        if (width <= 0) return;
        if (drawAvatar && !pinnedTop) {
            nameLayout = oneLine(message.getSender().getDisplayName(), namePaint, width);
        }
        if (message.getForwarded()) {
            forwardLayout = oneLine(getContext().getString(R.string.secchat_forwarded), forwardPaint, width);
        }
        final QuotePreview q = message.getReplyTo();
        if (q != null && !message.getDeleted()) {
            final String who = host != null ? host.quoteAuthor(message) : "";
            quoteNameLayout = oneLine(who != null ? who : "", quoteNamePaint, width - dp(10));
            String text = q.getText();
            if (text == null || text.isEmpty()) {
                text = q.getMediaKind() != null ? MediaText.kind(getContext(), q.getMediaKind()) : getContext().getString(R.string.secchat_quote_fallback);
            }
            final CharSequence withEmoji = Emoji.replaceEmoji(text.replace('\n', ' '), quoteTextPaint.getFontMetricsInt(), false);
            quoteTextLayout = oneLine(withEmoji, quoteTextPaint, width - dp(10));
        }
    }

    private int headerHeight() {
        int h = 0;
        if (nameLayout != null) h += dp(22);
        if (forwardLayout != null) h += dp(20);
        if (quoteNameLayout != null) h += dp(44);
        return h == 0 ? 0 : h + dp(4);
    }

    private static StaticLayout oneLine(CharSequence text, TextPaint paint, int width) {
        final CharSequence s = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END);
        return new StaticLayout(s, paint, Math.max(1, width), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    // ---- drawing ---------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (message == null || media == null) return;
        if (checked || highlighted) {
            highlightPaint.setColor(color(Theme.key_chat_selectedBackground));
            canvas.drawRect(0, 0, getWidth(), getHeight(), highlightPaint);
        }
        drawBubble(canvas);
        drawHeader(canvas);
        drawContent(canvas);
        if (!captionBelow) drawTime(canvas);
        if (drawAvatar && !pinnedBottom) {
            // Only the last bubble of a sender's run gets the avatar (a joined caption bubble draws it instead).
            final int size = dp(42);
            final int bottom = (int) body.bottom;
            avatar.setBounds(dp(6) + (checkBoxVisible ? dp(35) : 0), bottom - size, dp(6) + (checkBoxVisible ? dp(35) : 0) + size, bottom);
            avatar.draw(canvas);
        }
        if (checkBoxVisible) {
            final int size = dp(21);
            checkBox.setBounds(dp(10), (int) (body.bottom - size - dp(4)), size, size);
            checkBox.draw(canvas);
        }
    }

    /** Draws the media content inside {@link #content}. */
    protected abstract void drawContent(Canvas canvas);

    private void drawBubble(Canvas canvas) {
        final boolean media = mediaBubble();
        final String key;
        if (out) {
            key = media ? (checked ? Theme.key_drawable_msgOutMediaSelected : Theme.key_drawable_msgOutMedia)
                    : (checked ? Theme.key_drawable_msgOutSelected : Theme.key_drawable_msgOut);
        } else {
            key = media ? (checked ? Theme.key_drawable_msgInMediaSelected : Theme.key_drawable_msgInMedia)
                    : (checked ? Theme.key_drawable_msgInSelected : Theme.key_drawable_msgIn);
        }
        final Drawable d = resources.getDrawable(key);
        if (!(d instanceof MessageDrawable)) return;
        final MessageDrawable bubble = (MessageDrawable) d;
        final int pad = dp(2);
        final int tail = dp(8);
        if (media) {
            bounds.set((int) body.left - pad, (int) body.top - pad, (int) body.right + pad, (int) body.bottom + pad);
        } else if (out) {
            bounds.set((int) body.left - pad, (int) body.top - pad, (int) body.right + tail, (int) body.bottom + pad);
        } else {
            bounds.set((int) body.left - tail, (int) body.top - pad, (int) body.right + pad, (int) body.bottom + pad);
        }
        final View parent = getParent() instanceof View ? (View) getParent() : null;
        final int parentW = parent != null ? parent.getMeasuredWidth() : getMeasuredWidth();
        final int parentH = parent != null ? parent.getMeasuredHeight() : getMeasuredHeight();
        bubble.setTop(getTop(), parentW, parentH, pinnedTop, pinnedBottom);
        bubble.setBounds(bounds);
        bubble.draw(canvas);
        final Drawable shadow = bubble.getShadowDrawable();
        if (shadow != null) {
            shadow.setBounds(bounds);
            shadow.draw(canvas);
        }
    }

    private void drawHeader(Canvas canvas) {
        float y = body.top + dp(6);
        final float x = body.left + dp(10);
        if (nameLayout != null) {
            namePaint.setColor(nameColor);
            canvas.save();
            canvas.translate(x, y);
            nameLayout.draw(canvas);
            canvas.restore();
            y += dp(22);
        }
        if (forwardLayout != null) {
            forwardPaint.setColor(color(out ? Theme.key_chat_outForwardedNameText : Theme.key_chat_inForwardedNameText));
            canvas.save();
            canvas.translate(x, y);
            forwardLayout.draw(canvas);
            canvas.restore();
            y += dp(20);
        }
        if (quoteNameLayout != null) {
            quoteRect.set(x - dp(2), y, body.right - dp(8), y + dp(40));
            linePaint.setColor(color(out ? Theme.key_chat_outReplyLine : Theme.key_chat_inReplyLine));
            tmp.set(x, y + dp(2), x + dp(2), y + dp(38));
            canvas.drawRoundRect(tmp, dp(1), dp(1), linePaint);
            quoteNamePaint.setColor(color(out ? Theme.key_chat_outReplyNameText : Theme.key_chat_inReplyNameText));
            quoteTextPaint.setColor(color(out ? Theme.key_chat_outReplyMessageText : Theme.key_chat_inReplyMessageText));
            canvas.save();
            canvas.translate(x + dp(8), y + dp(2));
            quoteNameLayout.draw(canvas);
            canvas.translate(0, dp(19));
            if (quoteTextLayout != null) quoteTextLayout.draw(canvas);
            canvas.restore();
        } else {
            quoteRect.setEmpty();
        }
    }

    private void drawTime(Canvas canvas) {
        final boolean showStatus = out && !notes;
        final int statusW = showStatus ? dp(20) : 0;
        if (timeOverContent()) {
            // Telegram's pill over a photo (ChatMessageCell.drawTimeInternal): dark, 60% of its theme alpha.
            final boolean big = SharedConfig.bubbleRadius >= 10;
            final float right = content.right - dp(6);
            final float bottom = content.bottom - dp(6);
            final float w = timeWidth + dp(big ? 12 : 8) + statusW;
            final float h = Math.max(dp(17), timePaint.getTextSize() + dp(5));
            tmp.set(right - w, bottom - h, right, bottom);
            pillPaint.setColor(color(Theme.key_chat_mediaTimeBackground));
            pillPaint.setAlpha((int) (pillPaint.getAlpha() * 0.6f));
            final float r = Math.min(dp(8), h / 2f);
            canvas.drawRoundRect(tmp, r, r, pillPaint);
            timePaint.setColor(color(Theme.key_chat_mediaTimeText));
            final float textX = tmp.left + dp(big ? 6 : 4);
            final float baseline = tmp.centerY() - (timePaint.descent() + timePaint.ascent()) / 2f;
            canvas.drawText(timeText, textX, baseline, timePaint);
            if (showStatus) drawStatus(canvas, tmp.right - dp(big ? 6 : 4), tmp.centerY(), true);
        } else {
            timePaint.setColor(color(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText));
            final float right = body.right - dp(out ? 8 : 10);
            final float baseline = body.bottom - dp(8);
            canvas.drawText(timeText, right - statusW - timeWidth, baseline, timePaint);
            if (showStatus) drawStatus(canvas, right, baseline - dp(4.5f), false);
        }
    }

    /** Clock, one tick, two outline ticks or the error mark, right-aligned at {@code right}, centred on {@code cy}. */
    private void drawStatus(Canvas canvas, float right, float cy, boolean onMedia) {
        final MessageStatus s = message.getStatus();
        if (s == MessageStatus.SENDING) {
            clock.setColor(color(onMedia ? Theme.key_chat_mediaSentClock : Theme.key_chat_outSentClock));
            place(clock, right, cy);
            clock.draw(canvas);
            return;
        }
        if (s == MessageStatus.FAILED) {
            final float r = dp(7);
            canvas.drawCircle(right - r, cy, r, errorPaint);
            timePaint.setColor(color(Theme.key_chat_sentErrorIcon));
            final float bang = timePaint.measureText("!");
            canvas.drawText("!", right - r - bang / 2f, cy - (timePaint.descent() + timePaint.ascent()) / 2f, timePaint);
            return;
        }
        final boolean delivered = s == MessageStatus.DELIVERED;
        if (onMedia) {
            ensureMediaTicks();
            if (delivered) {
                // Two outline ticks, as in the chat list: delivered, never Telegram's filled "read" look.
                final int w = mediaOutline1.getIntrinsicWidth();
                place(mediaOutline1, right, cy);
                mediaOutline1.draw(canvas);
                place(mediaOutline2, right - w * 0.45f, cy);
                mediaOutline2.draw(canvas);
            } else if (mediaCheck != null) {
                place(mediaCheck, right, cy);
                mediaCheck.draw(canvas);
            }
        } else {
            if (delivered) {
                final Drawable full = tick(checked ? Theme.key_drawable_msgOutCheckReadSelected : Theme.key_drawable_msgOutCheckRead);
                final Drawable half = tick(checked ? Theme.key_drawable_msgOutHalfCheckSelected : Theme.key_drawable_msgOutHalfCheck);
                if (half != null) {
                    place(half, right, cy);
                    half.draw(canvas);
                }
                if (full != null) {
                    place(full, right - dp(4), cy);
                    full.draw(canvas);
                }
            } else {
                final Drawable one = tick(checked ? Theme.key_drawable_msgOutCheckSelected : Theme.key_drawable_msgOutCheck);
                if (one != null) {
                    place(one, right, cy);
                    one.draw(canvas);
                }
            }
        }
    }

    private Drawable tick(String key) {
        final Drawable d = resources.getDrawable(key);
        return d != null ? d : Theme.getThemeDrawable(key);
    }

    private void ensureMediaTicks() {
        final int tint = color(Theme.key_chat_mediaSentCheck);
        if (mediaCheck == null && Theme.chat_msgMediaCheckDrawable != null && Theme.chat_msgMediaCheckDrawable.getConstantState() != null) {
            mediaCheck = Theme.chat_msgMediaCheckDrawable.getConstantState().newDrawable().mutate();
        }
        if (mediaCheck != null) mediaCheck.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY));
        if (mediaOutline1 == null) {
            final Drawable ref = Theme.chat_msgMediaCheckDrawable;
            final int w = ref != null ? ref.getIntrinsicWidth() : dp(12);
            final int h = ref != null ? ref.getIntrinsicHeight() : dp(9);
            mediaOutline1 = new SecuredDialogCell.OutlineTick(w, h);
            mediaOutline2 = new SecuredDialogCell.OutlineTick(w, h);
        }
        mediaOutline1.setColor(tint);
        mediaOutline2.setColor(tint);
    }

    private static void place(Drawable d, float right, float cy) {
        final int w = d.getIntrinsicWidth();
        final int h = d.getIntrinsicHeight();
        d.setBounds((int) (right - w), (int) (cy - h / 2f), (int) right, (int) (cy - h / 2f) + h);
    }

    // ---- taps ------------------------------------------------------------------------------------------------

    /** What a tap at (x, y), in this cell's coordinates, hits. */
    int hitTest(float x, float y) {
        if (!quoteRect.isEmpty() && quoteRect.contains(x, y)) return HIT_QUOTE;
        final int sub = contentHit(x, y);
        if (sub != HIT_NONE) return sub;
        return body.contains(x, y) ? HIT_CONTENT : HIT_NONE;
    }

    /** The subclass's own hit areas (button, waveform); HIT_NONE to let the bubble count as content. */
    protected int contentHit(float x, float y) {
        return HIT_NONE;
    }

    /** After {@link #HIT_SEEK}: where on the waveform, 0..1. */
    float seekFraction() {
        return 0f;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    protected final int color(int key) {
        return Theme.getColor(key, resources);
    }

    /** 0..1 progress of the current transfer, from live updates. */
    protected final float progressFraction() {
        if (progressTotal <= 0) return 0f;
        return Math.max(0f, Math.min(1f, progressDone / (float) progressTotal));
    }

    protected final boolean moving() {
        return media != null && (media.getState() == MediaState.DOWNLOADING || media.getState() == MediaState.UPLOADING);
    }

    protected final MediaKind kind() {
        return media != null ? media.getKind() : MediaKind.FILE;
    }

    /** One spoken line: kind, state and time; never the file name or caption (those are in the caption bubble). */
    protected String accessibilityText() {
        final StringBuilder sb = new StringBuilder(MediaText.kind(getContext(), kind()));
        if (media.getState() == MediaState.FAILED) {
            sb.append(", ").append(MediaText.failureShort(getContext(), media.getFailure()));
        } else if (moving()) {
            sb.append(", ").append(MediaText.progress(getContext(), progressDone, progressTotal));
        }
        sb.append(", ").append(timeText);
        return sb.toString();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (message != null && media != null) {
            info.setText(accessibilityText());
            info.setClickable(true);
            info.setLongClickable(true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        checkBox.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        checkBox.onDetachedFromWindow();
    }

    /** RecyclerView recycles this cell for another message; drop any per-message resources (decoders, players). */
    void onRecycled() {
    }

    static ViewGroup.LayoutParams listParams() {
        return new androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
