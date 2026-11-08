package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;

/**
 * Shared frame of the three 12-word backup screens: Telegram's ActionBar with a back arrow, a scrolling column,
 * FLAG_SECURE while visible (Recents thumbnails and screenshots stay blank), and the small view helpers they share.
 * Reads no Telegram data and makes no Telegram calls; all data goes through the engine. The words only ever live in
 * fields of the subclasses and are never logged.
 */
abstract class SecuredBackupScreen extends BaseFragment implements SecuredScreen {

    protected SecuredEngine engine;
    protected boolean destroyed;
    protected LinearLayout content;
    private ScrollView scroll;
    private FlagSecureReason flagSecure;

    abstract int titleRes();

    /** Fill {@link #content}. */
    abstract void onBuild(Context context);

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return SecuredResourcesProvider.get();
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            engine = SecuredUi.engine();
        } catch (Throwable t) {
            engine = null;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(context.getString(titleRes()));
        actionBar.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && onBackPressed(true)) finishFragment();   // false = a step handled it
            }
        });

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // The 12 words must never reach a password manager's "save password" offer.
        column.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        fragmentView = column;
        onBuild(context);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        SecuredUi.nudgeLink();   // a link that waited (passcode, transition) shows now that this screen is resumed
        // Same protection as the other Secured screens: out of Recents thumbnails and screenshots.
        if (flagSecure == null && getParentActivity() != null) {
            flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
        }
        if (flagSecure != null) flagSecure.attach();
    }

    // Kept through onPause (Home, Recents snapshot, a translucent screen on top); dropped only when covered or gone,
    // as Telegram's ChatActivity does.
    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (flagSecure != null) flagSecure.detach();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (flagSecure != null) flagSecure.detach();
        super.onFragmentDestroy();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Replaces the screen's content with [views] (top to bottom) and scrolls to the top. */
    protected void showStep(View... views) {
        content.removeAllViews();
        for (View v : views) content.addView(v);
        scroll.scrollTo(0, 0);
    }

    protected static void ui(Runnable r) {
        AndroidUtilities.runOnUIThread(r);
    }

    protected static LinearLayout.LayoutParams wide(int topDp) {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, topDp, 0, 0);
    }

    protected static TextView heading(Context c, CharSequence s) {
        return Kit.text(c, s, 24, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true);
    }

    protected static TextView para(Context c, CharSequence s) {
        final TextView t = Kit.text(c, s, 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    protected static TextView bodyText(Context c, CharSequence s) {
        final TextView t = Kit.text(c, s, 16, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    protected static TextView errorView(Context c) {
        final TextView t = Kit.text(c, "", 14, Kit.color(Theme.key_text_RedBold), false);
        t.setVisibility(View.GONE);
        return t;
    }

    protected static void setError(TextView view, CharSequence message) {
        view.setText(message);
        view.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    /** One point of the honest-limits list: a small accent dot and a sentence. */
    protected static View point(Context c, CharSequence s) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final View dot = new View(c);
        dot.setBackground(Kit.roundRect(SecuredResourcesProvider.accentFill(), 3));
        row.addView(dot, LayoutHelper.createLinear(6, 6, Gravity.TOP, 0, 9, 12, 0));
        row.addView(bodyText(c, s), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    /** Readable text for an engine failure; the engine's own message is safe to show (it holds no words or keys). */
    protected static String errorText(Context c, SecuredError e) {
        switch (e.getKind()) {
            case WRONG_PHRASE:
                return c.getString(R.string.sec_bk_err_wrong_phrase);
            case NO_BACKUP:
                return c.getString(R.string.sec_bk_err_no_backup);
            case NEEDS_UNLOCK:
                return c.getString(R.string.sec_bk_err_needs_unlock);
            case NETWORK:
                return c.getString(R.string.sec_bk_err_network);
            default:
                final String m = e.getMessage();
                return m == null || m.isEmpty() ? c.getString(R.string.sec_bk_err_unknown) : m;
        }
    }

    protected static String when(Context c, long ms) {
        return DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR);
    }

    /** A rounded single-line input for words: no suggestions, no autocorrect, and the keyboard does not learn them. */
    protected static EditText wordInput(Context c, int imeAction) {
        final EditText e = new EditText(c);
        e.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        e.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setImeOptions(imeAction | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        e.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 12));
        return e;
    }

    /** The 12 words as a numbered two-column grid: 1 to 6 on the left, 7 to 12 on the right. Not selectable. */
    protected static View wordGrid(Context c, List<String> words) {
        final LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        final int perColumn = (words.size() + 1) / 2;
        for (int col = 0; col < 2; col++) {
            final LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            for (int row = 0; row < perColumn; row++) {
                final int index = col * perColumn + row;
                if (index >= words.size()) break;
                final String number = (index + 1) + "  ";
                final SpannableStringBuilder sb = new SpannableStringBuilder(number + words.get(index));
                sb.setSpan(new ForegroundColorSpan(Kit.color(Theme.key_windowBackgroundWhiteGrayText)), 0, number.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                final TextView cell = Kit.text(c, sb, 17, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
                cell.setPadding(dp(14), dp(11), dp(14), dp(11));
                cell.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 10));
                cell.setTextIsSelectable(false);
                cell.setLongClickable(false);
                cell.setSingleLine(true);
                column.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, row == 0 ? 0 : 8, 0, 0));
            }
            grid.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, col == 0 ? 0 : 4, 0, col == 0 ? 4 : 0, 0));
        }
        return grid;
    }
}
