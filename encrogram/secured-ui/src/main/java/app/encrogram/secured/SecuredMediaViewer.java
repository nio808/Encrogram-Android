package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.Collection;

import app.encrogram.core.api.MediaKind;
import app.encrogram.core.api.MediaPlayback;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredMedia;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * Full-screen viewer for a Secured photo, GIF or video (task MU5). One at a time, FLAG_SECURE, no menu: no save, share,
 * forward or "open in" (Media v1). Pictures come from MediaBitmaps, video from the engine's random-access reader through
 * android.media.MediaPlayer. Every way of closing ends in {@link #cleanup()}, which stops the player and releases the
 * playback (that closes the reader and deletes the private decrypted copy).
 *
 * Reuse: window, bars and controls are new code (level 4); ic_ab_back is Telegram's drawable (level 1); the zoom view is
 * SecuredZoomImageView. No PhotoViewer, VideoPlayer, ImageReceiver or loader is involved.
 */
final class SecuredMediaViewer {

    @Nullable
    private static SecuredMediaViewer current;

    /**
     * {@code caption}: the strict text mapper's output (or null). A tap on a link in it closes the viewer and hands the
     * span to {@code onLink}; the span's own onClick is never called.
     */
    static void open(BaseFragment fragment, SecuredEngine engine, MediaBitmaps bitmaps, SecuredMessage m, String title,
                     Theme.ResourcesProvider resources, @Nullable CharSequence caption,
                     @Nullable java.util.function.Consumer<android.text.style.CharacterStyle> onLink) {
        if (fragment == null || engine == null || bitmaps == null || m == null || m.getMedia() == null) return;
        final Context context = fragment.getParentActivity();
        if (context == null) return;
        final MediaKind kind = m.getMedia().getKind();
        if (kind != MediaKind.IMAGE && kind != MediaKind.GIF && kind != MediaKind.VIDEO) return;
        if (current != null) current.close();
        final SecuredMediaViewer v = new SecuredMediaViewer(context, engine, bitmaps, m, title, caption, onLink);
        current = v;
        if (Kit.show(fragment, v.dialog) == null) {
            // Not shown (a screen transition is running): nothing was loaded yet, so there is nothing to release.
            v.closed = true;
            if (current == v) current = null;
            return;
        }
        v.start();
    }

    /** A shown message was deleted. */
    static void closeFor(long chatId, Collection<Long> messageIds) {
        final SecuredMediaViewer v = current;
        if (v != null && v.chatId == chatId && messageIds != null && messageIds.contains(v.messageId)) v.close();
    }

    /** The chat screen is destroyed. */
    static void closeAll() {
        final SecuredMediaViewer v = current;
        if (v != null) v.close();
    }

    /** The chat screen paused (Home, lock). */
    static void pauseAll() {
        final SecuredMediaViewer v = current;
        if (v != null) v.pauseVideo();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private final Context context;
    private final SecuredEngine engine;
    private final MediaBitmaps bitmaps;
    private final long chatId, messageId;
    private final SecuredMedia media;
    private final MediaKind kind;
    private final int screenW, screenH;
    private final Dialog dialog;

    private final FrameLayout root;
    private final SecuredZoomImageView zoom;
    private final LinearLayout topBar;
    private final TextView errorText;
    @Nullable
    private View captionView;
    @Nullable
    private TextureView texture;
    @Nullable
    private LinearLayout bottomBar;
    @Nullable
    private PlayPauseView playButton;
    @Nullable
    private SeekBar seekBar;
    @Nullable
    private TextView timeText;

    private int insetBottom;
    private boolean closed;
    private boolean fullShown;
    private boolean barsVisible = true;

    // video
    @Nullable
    private MediaPlayback playback;
    @Nullable
    private ReaderDataSource dataSource;
    @Nullable
    private MediaPlayer player;
    @Nullable
    private Surface surface;
    private boolean prepared, completed, dragging, videoFailed;
    /** Cleared when the screen pauses before the video is ready, so it does not start behind the lock screen. */
    private boolean mayPlay = true;
    private int videoW, videoH, durationMs;
    private final Runnable progressTick = this::tick;

    private SecuredMediaViewer(Context context, SecuredEngine engine, MediaBitmaps bitmaps, SecuredMessage m, String title,
                               @Nullable CharSequence caption, @Nullable java.util.function.Consumer<android.text.style.CharacterStyle> onLink) {
        this.context = context;
        this.engine = engine;
        this.bitmaps = bitmaps;
        this.chatId = m.getChatId();
        this.messageId = m.getMessageId();
        this.media = m.getMedia();
        this.kind = media.getKind();
        this.screenW = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
        this.screenH = Math.max(1, context.getResources().getDisplayMetrics().heightPixels);

        // Every dismissal (back, arrow, cancel, replaced by another dialog, screen destroyed) ends in onStop. We cannot
        // rely on a dismiss listener: BaseFragment.showDialog installs its own.
        dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar) {
            @Override
            protected void onStop() {
                super.onStop();
                cleanup();
            }
        };

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        zoom = new SecuredZoomImageView(context);
        zoom.setOnTap(this::toggleBars);
        root.addView(zoom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (kind == MediaKind.VIDEO) {
            buildVideoViews();
        }

        errorText = Kit.text(context, context.getString(R.string.secmedia_open_failed), 14, Color.WHITE, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(16), dp(8), dp(16), dp(8));
        errorText.setBackground(Kit.roundRect(0x99000000, 16));
        errorText.setVisibility(View.GONE);
        final FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        elp.bottomMargin = dp(kind == MediaKind.VIDEO ? 120 : 64);
        root.addView(errorText, elp);

        topBar = buildTopBar(title, m.getTimeMs());
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        if (caption != null && caption.length() > 0) buildCaption(caption, onLink);

        final int baseTop = topBar.getPaddingTop();
        final int baseBottom = bottomBar != null ? bottomBar.getPaddingBottom() : 0;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            topBar.setPadding(topBar.getPaddingLeft(), baseTop + insets.getSystemWindowInsetTop(), topBar.getPaddingRight(), topBar.getPaddingBottom());
            if (bottomBar != null) {
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(), bottomBar.getPaddingRight(),
                        baseBottom + insets.getSystemWindowInsetBottom());
            }
            insetBottom = insets.getSystemWindowInsetBottom();
            placeCaption();
            return insets;
        });

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        final Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            // Draw behind the system bars; the bars above pad themselves with the insets.
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                final WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        }
    }

    // ---- views ----------------------------------------------------------------------------------------------------

    private LinearLayout buildTopBar(String title, long timeMs) {
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(16), dp(12));
        final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000});
        bar.setBackground(g);

        final ImageView back = new ImageView(context);
        back.setImageResource(org.telegram.messenger.R.drawable.ic_ab_back);
        back.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        back.setContentDescription(context.getString(R.string.secmedia_acc_close));
        back.setOnClickListener(v -> close());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        final TextView t = Kit.text(context, title == null ? "" : title, 18, Color.WHITE, true);
        t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView sub = Kit.text(context, subtitle(timeMs), 13, 0xCCFFFFFF, false);
        sub.setSingleLine();
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    private static String subtitle(long timeMs) {
        try {
            return LocaleController.formatString("formatDateAtTime", org.telegram.messenger.R.string.formatDateAtTime,
                    LocaleController.formatDateChat(timeMs / 1000),
                    LocaleController.getInstance().getFormatterDay().format(timeMs));
        } catch (Throwable t) {
            return "";
        }
    }

    private void buildVideoViews() {
        final TextureView tv = new TextureView(context);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
                if (closed) return;
                surface = new Surface(st);
                startPlayerIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                final MediaPlayer p = player;
                if (p != null) {
                    try {
                        p.setSurface(null);
                    } catch (Throwable ignored) {
                        // player already released
                    }
                }
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
            }
        });
        tv.setOnClickListener(v -> toggleBars());
        texture = tv;
        // Transparent until the first frame, so the preview behind it stays visible meanwhile.
        root.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), dp(16), dp(8));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));

        final PlayPauseView pp = new PlayPauseView(context);
        pp.setContentDescription(context.getString(R.string.secmedia_acc_play));
        pp.setOnClickListener(v -> togglePlay());
        playButton = pp;
        bar.addView(pp, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final SeekBar sb = new SeekBar(context);
        sb.setMax(1000);
        sb.setEnabled(false);
        sb.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        sb.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) updateTime((int) ((long) progress * durationMs / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                final MediaPlayer p = player;
                if (p != null && prepared && durationMs > 0) {
                    try {
                        p.seekTo((int) ((long) s.getProgress() * durationMs / 1000));
                        completed = false;
                    } catch (Throwable ignored) {
                        // player released meanwhile
                    }
                }
            }
        });
        seekBar = sb;
        bar.addView(sb, new LinearLayout.LayoutParams(0, dp(48), 1f));

        final int total = media.getDurationSeconds() != null ? media.getDurationSeconds() : 0;
        final TextView tt = Kit.text(context, MediaText.duration(0) + " / " + MediaText.duration(total), 13, Color.WHITE, false);
        tt.setSingleLine();
        timeText = tt;
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(8);
        bar.addView(tt, tlp);

        bottomBar = bar;
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    /** Telegram's PhotoViewer look: white 16sp text on a translucent black band at the bottom, scrolling after a third of the screen. */
    private void buildCaption(CharSequence caption, @Nullable java.util.function.Consumer<android.text.style.CharacterStyle> onLink) {
        final TextView tv = new TextView(context);
        tv.setText(inertLinks(caption));
        tv.setTextColor(Color.WHITE);
        tv.setLinkTextColor(0xFF8CC8FF);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        // No selection, no copy, no share of the caption; no LinkMovementMethod (it would call the span's own onClick).
        tv.setTextIsSelectable(false);
        tv.setLongClickable(false);
        tv.setMovementMethod(null);
        final android.view.GestureDetector gd = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(android.view.MotionEvent e) {
                final android.text.style.CharacterStyle span = linkAt(tv, e.getX(), e.getY());
                if (span != null && onLink != null) {
                    close();
                    AndroidUtilities.runOnUIThread(() -> onLink.accept(span));
                } else {
                    toggleBars();
                }
                return true;
            }
        });
        tv.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        final int maxH = screenH / 3;
        final android.widget.ScrollView sv = new android.widget.ScrollView(context) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            }
        };
        sv.setBackgroundColor(0x99000000);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(tv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        captionView = sv;
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        if (bottomBar != null) bottomBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeCaption());
        placeCaption();
    }

    /** The caption sits above the video controls, or above the system bar when there are none. */
    private void placeCaption() {
        final View c = captionView;
        if (c == null) return;
        final int bottom = bottomBar != null && bottomBar.getHeight() > 0 ? bottomBar.getHeight() : insetBottom;
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.getLayoutParams();
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            c.setLayoutParams(lp);
        }
    }

    /** The link span under a touch, found by hand so no span's own onClick (Telegram's browser path) can run. */
    @Nullable
    private static android.text.style.CharacterStyle linkAt(TextView tv, float x, float y) {
        final android.text.Layout layout = tv.getLayout();
        final CharSequence text = tv.getText();
        if (layout == null || !(text instanceof android.text.Spanned)) return null;
        final float px = x - tv.getTotalPaddingLeft() + tv.getScrollX();
        final float py = y - tv.getTotalPaddingTop() + tv.getScrollY();
        if (py < 0 || py > layout.getHeight()) return null;
        final int line = layout.getLineForVertical((int) py);
        if (px < layout.getLineLeft(line) || px > layout.getLineRight(line)) return null;
        final int off = layout.getOffsetForHorizontal(line, px);
        final InertLink[] spans = ((android.text.Spanned) text).getSpans(off, off, InertLink.class);
        return spans.length > 0 ? spans[0].original : null;
    }

    /**
     * A copy of the caption in which every ClickableSpan is swapped for an {@link InertLink}. A real ClickableSpan in a
     * TextView can be clicked without touch (TalkBack's links menu sends ACTION_CLICKABLE_SPAN, which calls the span's
     * own onClick: Telegram's browser path, rule 1). The bubble draws its own layout and never exposes spans.
     */
    static CharSequence inertLinks(CharSequence caption) {
        if (!(caption instanceof android.text.Spanned)) return caption;
        final android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(caption);
        for (android.text.style.ClickableSpan span : b.getSpans(0, b.length(), android.text.style.ClickableSpan.class)) {
            final int start = b.getSpanStart(span), end = b.getSpanEnd(span), flags = b.getSpanFlags(span);
            b.removeSpan(span);
            b.setSpan(new InertLink(span), start, end, flags);
        }
        return b;
    }

    /** Looks like a link and remembers the mapper's span, but is no ClickableSpan: only our tap handler acts on it. */
    static final class InertLink extends android.text.style.CharacterStyle {
        final android.text.style.ClickableSpan original;

        InertLink(android.text.style.ClickableSpan original) {
            this.original = original;
        }

        @Override
        public void updateDrawState(android.text.TextPaint tp) {
            tp.setColor(tp.linkColor);
        }
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        if (captionView != null) animateBar(captionView, barsVisible);
        animateBar(topBar, barsVisible);
        if (bottomBar != null) animateBar(bottomBar, barsVisible);
    }

    private static void animateBar(View bar, boolean show) {
        bar.animate().cancel();
        if (show) {
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).setDuration(180).start();
        } else {
            bar.animate().alpha(0f).setDuration(180).withEndAction(() -> bar.setVisibility(View.GONE)).start();
        }
    }

    // ---- loading --------------------------------------------------------------------------------------------------

    private void start() {
        showPreview();
        final boolean readable = media.getReadable();
        if (!readable) return; // only the preview: the chat screen normally won't open it
        switch (kind) {
            case IMAGE:
                bitmaps.loadFull(chatId, messageId, screenW, screenH, bitmap -> {
                    if (closed) return;
                    if (bitmap == null) {
                        showError();
                    } else {
                        fullShown = true;
                        zoom.setDrawable(new BitmapDrawable(context.getResources(), bitmap));
                    }
                });
                break;
            case GIF:
                bitmaps.loadAnimated(chatId, messageId, screenW, screenH, (animated, still) -> {
                    if (closed) return;
                    if (animated != null) {
                        fullShown = true;
                        zoom.setDrawable(animated);
                        if (Build.VERSION.SDK_INT >= 28 && animated instanceof AnimatedImageDrawable) {
                            ((AnimatedImageDrawable) animated).start();
                        }
                    } else if (still != null) {
                        fullShown = true;
                        zoom.setDrawable(new BitmapDrawable(context.getResources(), still));
                    } else {
                        showError();
                    }
                });
                break;
            case VIDEO:
                engine.openMediaForPlayback(chatId, messageId, result -> ui(() -> {
                    if (result instanceof SecuredResult.Ok) {
                        final MediaPlayback pb = ((SecuredResult.Ok<MediaPlayback>) result).getValue();
                        if (closed) {
                            // The viewer closed while this was opening: give it back at once.
                            releaseQuietly(pb);
                        } else {
                            playback = pb;
                            startPlayerIfReady();
                        }
                    } else if (!closed) {
                        showError();
                    }
                }));
                break;
            default:
                break;
        }
    }

    private void showPreview() {
        final Bitmap cached = bitmaps.cachedPreview(chatId, messageId);
        if (cached != null) {
            zoom.setDrawable(new BitmapDrawable(context.getResources(), cached));
        } else if (media.getPreview() != null) {
            bitmaps.loadPreview(chatId, messageId, media.getPreview(), screenW / 2, screenH / 2, true, bitmap -> {
                // The full picture may already be there: never replace it with the blur.
                if (closed || fullShown || bitmap == null) return;
                zoom.setDrawable(new BitmapDrawable(context.getResources(), bitmap));
            });
        }
    }

    private void showError() {
        errorText.setVisibility(View.VISIBLE);
    }

    // ---- video ----------------------------------------------------------------------------------------------------

    private void startPlayerIfReady() {
        if (closed || player != null || videoFailed || playback == null || surface == null) return;
        try {
            final MediaPlayer p = new MediaPlayer();
            player = p;
            final AudioAttributes.Builder ab = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Other apps may not record what a Secured video plays.
                ab.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
            }
            p.setAudioAttributes(ab.build());
            dataSource = new ReaderDataSource(playback.getReader());
            p.setDataSource(dataSource);
            p.setSurface(surface);
            p.setOnPreparedListener(mp -> {
                if (closed || mp != player) return;
                prepared = true;
                durationMs = Math.max(0, mp.getDuration());
                if (seekBar != null) seekBar.setEnabled(durationMs > 0);
                if (!mayPlay) {
                    updatePlayIcon();
                    return;
                }
                try {
                    mp.start();
                } catch (Throwable t) {
                    failVideo();
                    return;
                }
                updatePlayIcon();
                AndroidUtilities.runOnUIThread(progressTick, 250);
            });
            p.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (closed || mp != player || w <= 0 || h <= 0) return;
                videoW = w;
                videoH = h;
                layoutVideo();
            });
            p.setOnInfoListener((mp, what, extra) -> {
                if (!closed && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    // First frame is on screen: the preview behind is no longer needed.
                    zoom.setDrawable(null);
                    zoom.setVisibility(View.INVISIBLE);
                }
                return false;
            });
            p.setOnCompletionListener(mp -> {
                if (closed || mp != player) return;
                completed = true;
                updatePlayIcon();
                updateTime(durationMs);
                if (seekBar != null && !dragging) seekBar.setProgress(1000);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (!closed && mp == player) failVideo();
                return true;
            });
            p.prepareAsync();
        } catch (Throwable t) {
            failVideo();
        }
    }

    private void failVideo() {
        videoFailed = true;
        showError();
        updatePlayIcon();
    }

    private void layoutVideo() {
        final TextureView tv = texture;
        if (tv == null || videoW <= 0 || videoH <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        final float s = Math.min((float) root.getWidth() / videoW, (float) root.getHeight() / videoH);
        final int w = Math.max(1, Math.round(videoW * s)), h = Math.max(1, Math.round(videoH * s));
        final ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            tv.setLayoutParams(lp);
        }
    }

    private boolean isPlaying() {
        final MediaPlayer p = player;
        if (p == null || !prepared || completed) return false;
        try {
            return p.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private void togglePlay() {
        final MediaPlayer p = player;
        if (p == null || !prepared || videoFailed) return;
        try {
            if (p.isPlaying()) {
                p.pause();
            } else {
                if (completed) {
                    p.seekTo(0);
                    completed = false;
                }
                mayPlay = true;
                p.start();
                AndroidUtilities.cancelRunOnUIThread(progressTick);
                AndroidUtilities.runOnUIThread(progressTick, 250);
            }
        } catch (Throwable t) {
            failVideo();
            return;
        }
        updatePlayIcon();
    }

    private void pauseVideo() {
        mayPlay = false;
        final MediaPlayer p = player;
        if (p == null || !prepared || closed) return;
        try {
            if (p.isPlaying()) p.pause();
        } catch (Throwable ignored) {
            // released
        }
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        final boolean playing = isPlaying();
        if (playButton != null) {
            playButton.setPlaying(playing);
            playButton.setContentDescription(context.getString(playing ? R.string.secmedia_acc_pause : R.string.secmedia_acc_play));
        }
        root.setKeepScreenOn(playing);
    }

    private void tick() {
        if (closed) return;
        final MediaPlayer p = player;
        if (p != null && prepared) {
            try {
                final int pos = p.getCurrentPosition();
                if (!dragging) {
                    updateTime(pos);
                    if (seekBar != null && durationMs > 0) seekBar.setProgress((int) ((long) pos * 1000 / durationMs));
                }
            } catch (Throwable ignored) {
                // released
            }
            if (isPlaying()) AndroidUtilities.runOnUIThread(progressTick, 250);
        }
    }

    private void updateTime(int posMs) {
        if (timeText == null) return;
        final int total = durationMs > 0 ? durationMs / 1000 : (media.getDurationSeconds() != null ? media.getDurationSeconds() : 0);
        timeText.setText(MediaText.duration(Math.max(0, posMs) / 1000) + " / " + MediaText.duration(total));
    }

    // ---- closing --------------------------------------------------------------------------------------------------

    /** Closes the window; onStop does the cleanup. Also cleans up directly in case the window was never showing. */
    private void close() {
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // window already gone
        }
        cleanup();
    }

    /** Idempotent. Stops and releases everything the viewer holds. */
    private void cleanup() {
        if (closed && player == null && playback == null && dataSource == null && surface == null) {
            if (current == this) current = null;
            return;
        }
        closed = true;
        if (current == this) current = null;
        AndroidUtilities.cancelRunOnUIThread(progressTick);
        final MediaPlayer p = player;
        player = null;
        if (p != null) {
            p.setOnPreparedListener(null);
            p.setOnErrorListener(null);
            p.setOnInfoListener(null);
            p.setOnCompletionListener(null);
            p.setOnVideoSizeChangedListener(null);
            try {
                p.stop();
            } catch (Throwable ignored) {
                // not started yet
            }
            try {
                p.release();
            } catch (Throwable ignored) {
                // already released
            }
        }
        if (texture != null) texture.setSurfaceTextureListener(null);
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
        final MediaPlayback pb = playback;
        playback = null;
        if (pb != null) releaseQuietly(pb);
        zoom.release();
        zoom.setOnTap(null);
        root.setKeepScreenOn(false);
    }

    private void releaseQuietly(MediaPlayback pb) {
        try {
            engine.releasePlayback(pb);
        } catch (Throwable t) {
            try {
                pb.close();
            } catch (Throwable ignored) {
                // nothing more to do
            }
        }
    }

    private static void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else AndroidUtilities.runOnUIThread(r);
    }

    // ---- play / pause button ----------------------------------------------------------------------------------------

    /** A round translucent button with a drawn triangle or two bars (no resource needed). */
    private static final class PlayPauseView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private boolean playing;

        PlayPauseView(Context context) {
            super(context);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
            setClickable(true);
            setFocusable(true);
        }

        void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(12);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            if (playing) {
                final float bw = r * 0.5f, gap = r * 0.35f;
                c.drawRect(cx - gap - bw, cy - r, cx - gap, cy + r, paint);
                c.drawRect(cx + gap, cy - r, cx + gap + bw, cy + r, paint);
            } else {
                triangle.rewind();
                triangle.moveTo(cx - r * 0.7f, cy - r);
                triangle.lineTo(cx - r * 0.7f, cy + r);
                triangle.lineTo(cx + r, cy);
                triangle.close();
                c.drawPath(triangle, paint);
            }
        }
    }
}
