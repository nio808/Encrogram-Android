package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.Emoji;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.encrogram.core.api.GroupMember;
import app.encrogram.core.api.MemberStatus;

/**
 * The "@" member list above a group's composer, in the look of Telegram's mention list (MentionCell: 36 dp rows, a
 * 28 dp avatar, the name at 55 dp). Telegram's MentionCell itself loads avatars through ImageReceiver and takes
 * Telegram user objects, so the row is our own small copy drawing our own AvatarDrawable (reuse level 3).
 */
@SuppressLint("ViewConstructor")
final class SecuredMentionPicker extends FrameLayout {

    interface Listener {
        void onPick(GroupMember member);
    }

    private static final int ROW_DP = 36;

    private final Theme.ResourcesProvider resources;
    private final Listener listener;
    private final RecyclerListView list;
    private final ArrayList<GroupMember> all = new ArrayList<>();
    private final ArrayList<GroupMember> shown = new ArrayList<>();

    SecuredMentionPicker(Context context, Theme.ResourcesProvider resources, Listener listener) {
        super(context);
        this.resources = resources;
        this.listener = listener;
        setBackground(Theme.createRoundRectDrawable(dp(12), color(Theme.key_chat_messagePanelBackground)));
        setElevation(dp(2));
        list = new RecyclerListView(context, resources);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new Adapter());
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < shown.size()) listener.onPick(shown.get(position));
        });
        addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 0, 4, 0, 4));
        setVisibility(GONE);
    }

    /** Everyone who can be mentioned: not you, not people who left or were removed. */
    void setMembers(List<GroupMember> members) {
        all.clear();
        if (members == null) return;
        for (GroupMember m : members) {
            if (m.isMe() || m.getStatus() == MemberStatus.LEFT || m.getStatus() == MemberStatus.REMOVED) continue;
            all.add(m);
        }
    }

    /** Shows the members matching {@code query} (names that start with it first). False, and hidden, when none match. */
    @SuppressLint("NotifyDataSetChanged")
    boolean show(String query) {
        final String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        final ArrayList<GroupMember> starts = new ArrayList<>();
        final ArrayList<GroupMember> contains = new ArrayList<>();
        for (GroupMember m : all) {
            final String name = m.getDisplayName() == null ? "" : m.getDisplayName().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.startsWith(q) || name.contains(" " + q)) {
                starts.add(m);
            } else if (name.contains(q)) {
                contains.add(m);
            }
        }
        shown.clear();
        shown.addAll(starts);
        shown.addAll(contains);
        list.getAdapter().notifyDataSetChanged();
        if (shown.isEmpty()) {
            hide();
            return false;
        }
        final ViewGroup.LayoutParams lp = getLayoutParams();
        final int h = (int) (Math.min(shown.size(), 4.5f) * dp(ROW_DP)) + dp(8);
        if (lp != null && lp.height != h) {
            lp.height = h;
            setLayoutParams(lp);
        }
        setVisibility(VISIBLE);
        return true;
    }

    void hide() {
        setVisibility(GONE);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    // ---- rows ----------------------------------------------------------------------------------------------------

    private final class Row extends View {
        private final AvatarDrawable avatar = new AvatarDrawable(resources);
        private final TextPaint namePaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        private CharSequence name = "";
        private CharSequence drawn;
        private android.graphics.Bitmap photo;
        private final android.graphics.Path clip = new android.graphics.Path();
        private final android.graphics.Paint photoPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

        Row(Context context) {
            super(context);
            namePaint.setTextSize(dp(15));
            namePaint.setColor(color(Theme.key_windowBackgroundWhiteBlackText));
            avatar.setTextSize(dp(12));
            setBackground(Theme.getSelectorDrawable(false, resources));
        }

        void set(GroupMember m) {
            final String n = m.getDisplayName() == null ? "" : m.getDisplayName();
            Kit.setAvatar(avatar, m.getMemberId(), n);
            photo = AvatarPhotos.bitmap(m.getAvatar());   // null or broken: letters
            name = Emoji.replaceEmoji(n, namePaint.getFontMetricsInt(), false);
            drawn = null;
            setContentDescription(n);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(ROW_DP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int size = dp(28);
            final int top = (getHeight() - size) / 2;
            if (photo != null) {
                clip.reset();
                clip.addCircle(dp(12) + size / 2f, top + size / 2f, size / 2f, android.graphics.Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawBitmap(photo, null, new android.graphics.Rect(dp(12), top, dp(12) + size, top + size), photoPaint);
                canvas.restore();
            } else {
                avatar.setBounds(dp(12), top, dp(12) + size, top + size);
                avatar.draw(canvas);
            }
            if (drawn == null) {
                drawn = TextUtils.ellipsize(name, namePaint, Math.max(0, getWidth() - dp(55) - dp(12)), TextUtils.TruncateAt.END);
            }
            final float y = getHeight() / 2f - (namePaint.descent() + namePaint.ascent()) / 2f;
            canvas.drawText(drawn, 0, drawn.length(), dp(55), y, namePaint);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            drawn = null;
        }
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return shown.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Row row = new Row(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).set(shown.get(position));
        }
    }

    private int color(int key) {
        return Theme.getColor(key, resources);
    }
}
