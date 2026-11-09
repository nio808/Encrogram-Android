package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.function.IntConsumer;

/**
 * The two small sheets of the Secured chat header menu: disappearing-message timer and security code.
 * Same BottomSheet style as {@link SecuredSheets} (reuse level 2: Telegram's BottomSheet, our colours).
 * Never logs or stores the code or the contact's name.
 */
final class SecuredChatSheets {

    private SecuredChatSheets() {}

    private static final int[] TTL_SECONDS = {0, 24 * 3600, 7 * 24 * 3600, 30 * 24 * 3600};
    private static final int[] TTL_LABELS = {R.string.secchat_disappear_off, R.string.secchat_disappear_1d,
            R.string.secchat_disappear_1w, R.string.secchat_disappear_1m};

    private static LinearLayout column(Context context) {
        final LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(16));
        return l;
    }

    /** [current] is our timer in seconds, or null when off. [onPick] gets seconds, 0 for off. */
    static void showDisappearing(BaseFragment fragment, String name, Integer current, IntConsumer onPick) {
        final Context context = fragment.getParentActivity();
        if (context == null) return;
        showTimerSheet(fragment, context.getString(R.string.secchat_disappear_note, name), current, onPick);
    }

    /** A group's or channel's timer, set by its owner for every member: Telegram's own wording for it. */
    static void showGroupDisappearing(BaseFragment fragment, boolean channel, Integer current, IntConsumer onPick) {
        showTimerSheet(fragment, org.telegram.messenger.LocaleController.getString(channel
                ? org.telegram.messenger.R.string.AutoDeleteAlertChannelInfo : org.telegram.messenger.R.string.AutoDeleteAlertGroupInfo), current, onPick);
    }

    private static void showTimerSheet(BaseFragment fragment, CharSequence note, Integer current, IntConsumer onPick) {
        final Context context = fragment.getParentActivity();
        if (context == null) return;
        final LinearLayout box = column(context);
        box.addView(Kit.text(context, context.getString(R.string.secchat_disappear), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true));
        box.addView(Kit.text(context, note, 14, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        builder.setCustomView(box);
        final BottomSheet sheet = builder.create();
        final int now = current == null ? 0 : current;
        for (int i = 0; i < TTL_SECONDS.length; i++) {
            final int ttl = TTL_SECONDS[i];
            final FrameLayout row = new FrameLayout(context);
            row.setBackground(Theme.createSelectorDrawable(Kit.color(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            row.addView(Kit.text(context, context.getString(TTL_LABELS[i]), 17, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false),
                    LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 14, 4, 14));
            if (ttl == now) {
                row.addView(Kit.text(context, "✓", 18, SecuredResourcesProvider.accentText(), true),
                        LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 4, 0, 8, 0));
            }
            row.setOnClickListener(v -> {
                sheet.dismiss();
                if (ttl != now) onPick.accept(ttl);
            });
            box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        Kit.show(fragment, sheet);
    }

    /** [onAction] gets true for "Mark as verified" (with the code shown here) and false for "Clear verification". */
    static void showVerify(BaseFragment fragment, String name, String code, boolean verified, java.util.function.Consumer<Boolean> onAction) {
        final Context context = fragment.getParentActivity();
        if (context == null) return;
        final LinearLayout box = column(context);
        box.addView(Kit.text(context, context.getString(R.string.secchat_verify_title), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true));
        box.addView(Kit.text(context, context.getString(R.string.secchat_verify_text, name), 14, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final TextView digits = Kit.text(context, groups(code), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        digits.setTypeface(Typeface.MONOSPACE);
        digits.setGravity(Gravity.CENTER);
        digits.setLineSpacing(dp(6), 1f);
        digits.setTextIsSelectable(false);   // a selection toolbar would bypass our sensitive-copy handling
        digits.setPadding(dp(14), dp(16), dp(14), dp(16));
        digits.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 10));
        box.addView(digits, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        if (verified) {
            box.addView(Kit.text(context, context.getString(R.string.secchat_verify_is_verified, name), 14, SecuredResourcesProvider.accentText(), false),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }
        final TextView button = Kit.button(context, context.getString(verified ? R.string.secchat_verify_clear : R.string.secchat_verify_mark), !verified);
        box.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 4));

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        builder.setCustomView(box);
        final BottomSheet sheet = builder.create();
        button.setOnClickListener(v -> {
            sheet.dismiss();
            onAction.accept(!verified);
        });
        Kit.show(fragment, sheet);
    }

    /** Digits in groups of five, three groups a line; anything unexpected is shown as the core returned it. */
    private static String groups(String code) {
        final String d = code == null ? "" : code.replaceAll("\\D", "");
        if (d.isEmpty()) return code == null ? "" : code;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length(); i += 5) {
            if (i > 0) sb.append(i % 15 == 0 ? '\n' : ' ');
            sb.append(d, i, Math.min(d.length(), i + 5));
        }
        return sb.toString();
    }
}
