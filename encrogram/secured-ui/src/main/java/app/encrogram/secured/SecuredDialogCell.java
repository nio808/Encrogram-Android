package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

/**
 * Telegram's own chat-list row, driven only through DialogCell.CustomDialog (reuse level 2: a subclass, no upstream
 * edit). It never has a fragment, a dialog id or a Telegram user or chat: the cell sees the strings and numbers that
 * {@link ChatRowMapper} puts in.
 *
 * What the subclass adds:
 * - the lock badge (drawLock2),
 * - our outline double tick for "delivered", swapped in around super.onDraw and restored in finally
 *   (Telegram's own rows never see the swap: drawing is single-threaded on the UI thread),
 * - a small red "!" for a message that failed to send (DialogCell has no error path in CustomDialog mode),
 * - the swipe action fixed to Archive / Unarchive instead of the user's Telegram swipe setting.
 */
final class SecuredDialogCell extends DialogCell {

    private ChatRowMapper.Tick tick = ChatRowMapper.Tick.NONE;
    private boolean lock = true;
    private int unread;
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeMark = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Our outline ticks, created on the first delivered row (Theme's drawables exist by then). UI thread only.
    private static OutlineTick outlineHalf, outlineFull;

    SecuredDialogCell(Context context) {
        super(null, context, false, false, UserConfig.selectedAccount, SecuredResourcesProvider.get());
        useSeparator = true;
        badgeMark.setStyle(Paint.Style.STROKE);
        badgeMark.setStrokeCap(Paint.Cap.ROUND);
        badgeMark.setColor(0xFFFFFFFF);
    }

    /** Binds a chat row. {@code lockBadge} is false for the "Archived chats" row. */
    void bind(ChatRowMapper.Row row, boolean inArchive, boolean lockBadge) {
        tick = row.tick;
        lock = lockBadge;
        unread = row.unread;
        setArchiveSwipe(inArchive);
        setDialog(ChatRowMapper.toCustomDialog(row));
    }

    /** The swipe reveal shows Archive (or Unarchive inside the archive), never the Telegram swipe setting. */
    private void setArchiveSwipe(boolean inArchive) {
        overrideSwipeAction = true;
        if (inArchive) {
            overrideSwipeActionBackgroundColorKey = Theme.key_chats_archivePinBackground;
            overrideSwipeActionRevealBackgroundColorKey = Theme.key_chats_archiveBackground;
            overrideSwipeActionStringKey = "Unarchive";
            overrideSwipeActionStringId = org.telegram.messenger.R.string.Unarchive;
            overrideSwipeActionDrawable = Theme.dialogs_unarchiveDrawable;
        } else {
            overrideSwipeActionBackgroundColorKey = Theme.key_chats_archiveBackground;
            overrideSwipeActionRevealBackgroundColorKey = Theme.key_chats_archivePinBackground;
            overrideSwipeActionStringKey = "Archive";
            overrideSwipeActionStringId = org.telegram.messenger.R.string.Archive;
            overrideSwipeActionDrawable = Theme.dialogs_archiveDrawable;
        }
    }

    @Override
    protected boolean drawLock2() {
        return lock;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Drawable oldHalf = Theme.dialogs_halfCheckDrawable;
        final Drawable oldFull = Theme.dialogs_checkReadDrawable;
        final boolean swap = tick == ChatRowMapper.Tick.DELIVERED && oldHalf != null && oldFull != null;
        if (swap) {
            if (outlineHalf == null) {
                outlineHalf = new OutlineTick(oldHalf.getIntrinsicWidth(), oldHalf.getIntrinsicHeight());
                outlineFull = new OutlineTick(oldFull.getIntrinsicWidth(), oldFull.getIntrinsicHeight());
            }
            final int color = Kit.color(Theme.key_chats_sentReadCheck);
            outlineHalf.setColor(color);
            outlineFull.setColor(color);
            Theme.dialogs_halfCheckDrawable = outlineHalf;
            Theme.dialogs_checkReadDrawable = outlineFull;
        }
        try {
            super.onDraw(canvas);
        } finally {
            if (swap) {
                Theme.dialogs_halfCheckDrawable = oldHalf;
                Theme.dialogs_checkReadDrawable = oldFull;
            }
        }
        if (tick == ChatRowMapper.Tick.FAILED && unread == 0) {
            drawFailedBadge(canvas);
        }
    }

    /** Red circle with "!" where the unread badge would be; follows the row while it is swiped. */
    private void drawFailedBadge(Canvas canvas) {
        final float r = dp(10);
        final float cx = getMeasuredWidth() - dp(16) - r;
        final float cy = getMeasuredHeight() * 0.66f;
        canvas.save();
        canvas.translate(getTranslationX(), 0);
        badgePaint.setColor(Kit.color(Theme.key_text_RedBold));
        canvas.drawCircle(cx, cy, r, badgePaint);
        badgeMark.setStrokeWidth(dpf2(1.8f));
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.1f, badgeMark);
        canvas.drawPoint(cx, cy + r * 0.5f, badgeMark);
        canvas.restore();
    }

    /** A thin-stroke tick in the box DialogCell lays out for its own tick drawable. Honest: not the filled "read" look. */
    static final class OutlineTick extends Drawable {
        private final int w, h;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int alpha = 255;

        OutlineTick(int w, int h) {
            this.w = w;
            this.h = h;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            paint.setColor(color);
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            if (b.isEmpty()) return;
            paint.setAlpha(alpha);
            paint.setStrokeWidth(dpf2(1.2f));
            final float s = Math.min(b.width() / 12f, b.height() / 9f) * 0.9f;
            final float cx = b.exactCenterX(), cy = b.exactCenterY();
            path.rewind();
            path.moveTo(cx - 4.4f * s, cy + 0.2f * s);
            path.lineTo(cx - 1.6f * s, cy + 3.0f * s);
            path.lineTo(cx + 4.6f * s, cy - 3.4f * s);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
