package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.CompoundEmoji;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.EmojiData;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EmojiColorPickerWindow;
import org.telegram.ui.Components.EmojiView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The composer's emoji panel: Telegram's emoji set, category icons, skin-tone picker and look, emoji only.
 *
 * Why not Telegram's {@code EmojiView} itself (research/media-ui.md §6): even emoji-only it creates Telegram's
 * MessagesController, MessagesStorage and MediaDataController for account 0 (waking Telegram in "Continue without
 * Telegram" mode), a visibility change asks Telegram's servers for recent stickers and GIFs, and every emoji used
 * lands in Telegram's shared recent list. Here the pieces below EmojiView are reused as they are (EmojiData,
 * Emoji's asset drawables, EmojiColorPickerWindow, EmojiView.addColorToCode, the category icons and strings), and
 * recents and skin tones live in our own preferences file, which is excluded from backups.
 */
@SuppressLint("ViewConstructor")
final class SecuredEmojiPanel extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    interface Listener {
        void onEmoji(String emoji);
        void onBackspace();
    }

    private static final String KEY_RECENT = "emoji_recent";
    private static final String KEY_TONES = "emoji_tones";
    private static final int MAX_RECENT = 32;
    private static final String[] TONES = {"🏻", "🏼", "🏽", "🏾", "🏿"};

    private static final int[] TAB_ICONS = {
            org.telegram.messenger.R.drawable.msg_emoji_recent,
            org.telegram.messenger.R.drawable.msg_emoji_smiles,
            org.telegram.messenger.R.drawable.msg_emoji_cat,
            org.telegram.messenger.R.drawable.msg_emoji_food,
            org.telegram.messenger.R.drawable.msg_emoji_activities,
            org.telegram.messenger.R.drawable.msg_emoji_travel,
            org.telegram.messenger.R.drawable.msg_emoji_objects,
            org.telegram.messenger.R.drawable.msg_emoji_other,
            org.telegram.messenger.R.drawable.msg_emoji_flags,
    };
    private static final int[] TITLES = {
            org.telegram.messenger.R.string.Emoji1, org.telegram.messenger.R.string.Emoji2,
            org.telegram.messenger.R.string.Emoji3, org.telegram.messenger.R.string.Emoji4,
            org.telegram.messenger.R.string.Emoji5, org.telegram.messenger.R.string.Emoji6,
            org.telegram.messenger.R.string.Emoji7, org.telegram.messenger.R.string.Emoji8,
    };

    private static final int TYPE_HEADER = 0, TYPE_EMOJI = 1;

    private final Theme.ResourcesProvider resources;
    private final Listener listener;
    private final SharedPreferences prefs;

    private final ArrayList<String> recent = new ArrayList<>();
    private final HashMap<String, String> tones = new HashMap<>();

    /** Flat rows: a header (String title, code null) or an emoji (code, base). */
    private final ArrayList<Item> items = new ArrayList<>();
    /** Row of each section's header; section 0 is recent (-1 when empty). */
    private final int[] sectionStart = new int[TAB_ICONS.length];

    private final ImageView[] tabs = new ImageView[TAB_ICONS.length];
    private final GridView grid;
    private final GridLayoutManager layout;
    private final Adapter adapter = new Adapter();
    private final EmojiColorPickerWindow picker;
    private EmojiCell touched;
    private int selectedTab = -1;

    private static final class Item {
        final int type;
        final String text;   // header title, or the emoji as shown (with its tone)
        final String base;   // emoji without a tone (tones are stored under it); null for headers and recents
        final boolean isRecent;

        Item(int type, String text, String base, boolean isRecent) {
            this.type = type;
            this.text = text;
            this.base = base;
            this.isRecent = isRecent;
        }
    }

    SecuredEmojiPanel(Context context, Theme.ResourcesProvider resources, Listener listener) {
        super(context);
        this.resources = resources;
        this.listener = listener;
        this.prefs = context.getApplicationContext().getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE);
        loadPrefs();
        setBackgroundColor(color(Theme.key_chat_emojiPanelBackground));

        // Category icons along the top, as in Telegram's EmojiTabsStrip.
        final HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout tabRow = new LinearLayout(context);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(6), 0, dp(6), 0);
        for (int i = 0; i < TAB_ICONS.length; i++) {
            final ImageView tab = new ImageView(context);
            tab.setScaleType(ImageView.ScaleType.CENTER);
            tab.setImageResource(TAB_ICONS[i]);
            tab.setContentDescription(i == 0 ? context.getString(R.string.secemoji_recent) : context.getString(TITLES[i - 1]));
            final int section = i;
            tab.setOnClickListener(v -> scrollToSection(section));
            tabs[i] = tab;
            tabRow.addView(tab, LayoutHelper.createLinear(36, 36, 0, 1, 2, 1));
        }
        tabScroll.addView(tabRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, 38, Gravity.CENTER_HORIZONTAL));
        addView(tabScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP));

        final View line = new View(context);
        line.setBackgroundColor(color(Theme.key_chat_emojiPanelShadowLine));
        addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, AndroidUtilities.dp(0.5f)), Gravity.TOP));

        picker = EmojiColorPickerWindow.create(context, resources);
        Kit.securePopup(picker);

        grid = new GridView(context);
        layout = new GridLayoutManager(context, 8);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < items.size() && items.get(position).type == TYPE_HEADER ? layout.getSpanCount() : 1;
            }
        });
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.setClipToPadding(false);
        grid.setPadding(dp(5), 0, dp(5), dp(52));
        grid.setVerticalScrollBarEnabled(false);
        grid.setOnItemClickListener((view, position) -> {
            if (!(view instanceof EmojiCell) || position >= items.size()) return;
            final Item it = items.get(position);
            insert(it.text);
        });
        grid.setOnItemLongClickListener((view, position) -> {
            if (!(view instanceof EmojiCell) || position >= items.size()) return false;
            final Item it = items.get(position);
            if (it.isRecent || it.base == null || CompoundEmoji.isCompound(it.base) || !EmojiData.emojiColoredMap.contains(it.base)) {
                return false;
            }
            showPicker((EmojiCell) view, it.base);
            return true;
        });
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateSelectedTab();
            }
        });
        addView(grid, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 38, 0, 0));

        // Backspace floating at the bottom right, as in Telegram's panel; repeats while held.
        final ImageView backspace = new ImageView(context) {
            private boolean held;
            private final Runnable repeat = new Runnable() {
                @Override
                public void run() {
                    if (!held) return;
                    listener.onBackspace();
                    postDelayed(this, 50);
                }
            };

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        held = true;
                        setPressed(true);
                        listener.onBackspace();
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        postDelayed(repeat, 350);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        held = false;
                        setPressed(false);
                        removeCallbacks(repeat);
                        return true;
                }
                return true;
            }
        };
        backspace.setScaleType(ImageView.ScaleType.CENTER);
        backspace.setImageResource(org.telegram.messenger.R.drawable.smiles_tab_clear);
        backspace.setColorFilter(new PorterDuffColorFilter(color(Theme.key_chat_emojiPanelBackspace), PorterDuff.Mode.SRC_IN));
        backspace.setBackground(Theme.createRoundRectDrawable(dp(18), color(Theme.key_chat_emojiPanelBackground)));
        backspace.setElevation(dp(2));
        backspace.setContentDescription(context.getString(R.string.secemoji_backspace));
        addView(backspace, LayoutHelper.createFrame(47, 40, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));

        rebuild();
    }

    // ---- data ------------------------------------------------------------------------------------------------

    private void loadPrefs() {
        recent.clear();
        final String r = prefs.getString(KEY_RECENT, "");
        if (!TextUtils.isEmpty(r)) {
            for (String s : r.split("\n")) {
                if (!s.isEmpty() && recent.size() < MAX_RECENT) recent.add(s);
            }
        }
        tones.clear();
        final String t = prefs.getString(KEY_TONES, "");
        if (!TextUtils.isEmpty(t)) {
            for (String pair : t.split("\n")) {
                final int eq = pair.indexOf('=');
                if (eq > 0 && eq < pair.length() - 1) tones.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
    }

    private void saveRecent() {
        prefs.edit().putString(KEY_RECENT, TextUtils.join("\n", recent)).apply();
    }

    private void saveTones() {
        final StringBuilder b = new StringBuilder();
        for (HashMap.Entry<String, String> e : tones.entrySet()) {
            if (b.length() > 0) b.append('\n');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString(KEY_TONES, b.toString()).apply();
    }

    private void rebuild() {
        items.clear();
        if (recent.isEmpty()) {
            sectionStart[0] = -1;
        } else {
            sectionStart[0] = 0;
            items.add(new Item(TYPE_HEADER, getContext().getString(R.string.secemoji_recent), null, false));
            for (String e : recent) items.add(new Item(TYPE_EMOJI, e, null, true));
        }
        for (int s = 0; s < EmojiData.dataColored.length && s < TITLES.length; s++) {
            sectionStart[s + 1] = items.size();
            items.add(new Item(TYPE_HEADER, getContext().getString(TITLES[s]), null, false));
            for (String base : EmojiData.dataColored[s]) {
                final String tone = tones.get(base);
                items.add(new Item(TYPE_EMOJI, tone != null ? EmojiView.addColorToCode(base, tone) : base, base, false));
            }
        }
        tabs[0].setVisibility(recent.isEmpty() ? GONE : VISIBLE);
        adapter.notifyDataSetChanged();
        updateSelectedTab();
    }

    private void insert(String emoji) {
        listener.onEmoji(emoji);
        recent.remove(emoji);
        recent.add(0, emoji);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        saveRecent();
        // The visible list is not reshuffled under the finger; recents refresh the next time the panel opens.
    }

    /** Called each time the panel is shown: recents from this session appear at the top. */
    void onShow() {
        rebuild();
        if (selectedTab < 0) scrollToSection(recent.isEmpty() ? 1 : 0);
    }

    // ---- tabs ------------------------------------------------------------------------------------------------

    private void scrollToSection(int section) {
        final int row = sectionStart[section];
        if (row < 0) return;
        layout.scrollToPositionWithOffset(row, 0);
        setSelectedTab(section);
    }

    private void updateSelectedTab() {
        final int first = layout.findFirstVisibleItemPosition();
        if (first < 0) return;
        int section = 1;
        for (int s = 0; s < sectionStart.length; s++) {
            if (sectionStart[s] >= 0 && sectionStart[s] <= first) section = s;
        }
        setSelectedTab(section);
    }

    private void setSelectedTab(int section) {
        if (section == selectedTab) return;
        selectedTab = section;
        for (int i = 0; i < tabs.length; i++) {
            final boolean sel = i == section;
            tabs[i].setColorFilter(new PorterDuffColorFilter(color(sel ? Theme.key_chat_emojiPanelIconSelected : Theme.key_chat_emojiPanelIcon), PorterDuff.Mode.SRC_IN));
            tabs[i].setBackground(sel ? Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(color(Theme.key_chat_emojiPanelIconSelected), 0.12f)) : null);
        }
    }

    // ---- skin tones ------------------------------------------------------------------------------------------

    private void showPicker(EmojiCell cell, String base) {
        touched = cell;
        touched.base = base;
        final String tone = tones.get(base);
        int index = 0;
        for (int i = 0; i < TONES.length; i++) if (TONES[i].equals(tone)) index = i + 1;
        picker.setSelection(index);
        picker.setEmoji(base);
        final int popupWidth = picker.getPopupWidth();
        final int popupHeight = picker.getPopupHeight();
        final int emojiSize = dp(AndroidUtilities.isTablet() ? 40 : 32);
        final int[] loc = new int[2];
        cell.getLocationOnScreen(loc);
        int x = emojiSize * index + dp(4 * index - (AndroidUtilities.isTablet() ? 5 : 1));
        if (loc[0] - x < dp(5)) {
            x += (loc[0] - x) - dp(5);
        } else if (loc[0] - x + popupWidth > AndroidUtilities.displaySize.x - dp(5)) {
            x += (loc[0] - x + popupWidth) - (AndroidUtilities.displaySize.x - dp(5));
        }
        final int xOffset = -x;
        final int yOffset = cell.getTop() < 0 ? cell.getTop() : 0;
        picker.setupArrow(dp(AndroidUtilities.isTablet() ? 30 : 22) - xOffset + (int) AndroidUtilities.dpf2(0.5f));
        picker.setFocusable(true);
        picker.showAsDropDown(cell, xOffset, -cell.getMeasuredHeight() - popupHeight + (cell.getMeasuredHeight() - emojiSize) / 2 - yOffset);
        grid.hideSelector(true);
        grid.getParent().requestDisallowInterceptTouchEvent(true);
    }

    /** The finger lifts after a long press: the chosen tone is kept for that emoji and the emoji is inserted. */
    private void finishPicker() {
        if (touched == null) return;
        final String base = touched.base;
        touched = null;
        if (!picker.isShowing()) return;
        picker.dismiss();
        final String tone = picker.getSkinTone(0);
        if (tone != null) {
            tones.put(base, tone);
        } else {
            tones.remove(base);
        }
        saveTones();
        insert(tone != null ? EmojiView.addColorToCode(base, tone) : base);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        rebuild();
    }

    void dismissPopups() {
        touched = null;
        if (picker.isShowing()) picker.dismiss();
    }

    // ---- views -----------------------------------------------------------------------------------------------

    private final class GridView extends RecyclerListView {
        private final int[] loc = new int[2];

        GridView(Context context) {
            super(context, resources);
            setInstantClick(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            final int width = MeasureSpec.getSize(widthSpec);
            final int span = Math.max(1, (width - dp(10)) / dp(AndroidUtilities.isTablet() ? 60 : 45));
            if (layout.getSpanCount() != span) layout.setSpanCount(span);
            super.onMeasure(widthSpec, heightSpec);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (touched != null && picker.isShowing()) {
                final int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    finishPicker();
                } else if (a == MotionEvent.ACTION_MOVE) {
                    getLocationOnScreen(loc);
                    float x = loc[0] + e.getX();
                    picker.pickerView.getLocationOnScreen(loc);
                    picker.onTouchMove((int) (x - loc[0] - dp(3)));
                }
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    private final class EmojiCell extends View {
        String text;
        String base;
        private Drawable drawable;

        EmojiCell(Context context) {
            super(context);
            setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        }

        void set(String emoji) {
            text = emoji;
            drawable = Emoji.getEmojiBigDrawable(emoji);
            setContentDescription(emoji);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, w);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable == null) return;
            final int size = (int) (Math.min(getWidth(), getHeight()) * 0.7f);
            final int l = (getWidth() - size) / 2, t = (getHeight() - size) / 2;
            drawable.setBounds(l, t, l + size, t + size);
            drawable.draw(canvas);
        }
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_EMOJI;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View v;
            if (viewType == TYPE_HEADER) {
                final TextView t = new TextView(parent.getContext());
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                t.setTypeface(AndroidUtilities.bold());
                t.setTextColor(color(Theme.key_chat_emojiPanelStickerSetName));
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(10), dp(8), dp(10), 0);
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
                v = t;
            } else {
                v = new EmojiCell(parent.getContext());
            }
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Item it = items.get(position);
            if (holder.itemView instanceof TextView) {
                ((TextView) holder.itemView).setText(it.text);
            } else {
                ((EmojiCell) holder.itemView).set(it.text);
            }
        }
    }

    // ---- emoji images load from assets in the background -----------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        dismissPopups();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) grid.invalidateViews();
    }

    private int color(int key) {
        return Theme.getColor(key, resources);
    }
}
