package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Start screen when no Telegram account is activated: two doors. Reads no Telegram data and makes no Telegram
 * calls; its only state is one flag in Secured's own preferences.
 */
final class SecuredWelcomeActivity extends BaseFragment {

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return SecuredResourcesProvider.get();
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), 0, dp(28), dp(16));
        column.setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
        column.setOnTouchListener((v, e) -> true);

        final View spaceTop = new View(context);
        column.addView(spaceTop, LayoutHelper.createLinear(0, 0, 1f));

        final FrameLayout tile = new FrameLayout(context);
        tile.setBackground(Kit.roundRect(SecuredResourcesProvider.accentFill(), 22));
        final ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.encro_welcome_mark);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(mark, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
        column.addView(tile, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));

        final TextView title = Kit.text(context, context.getString(R.string.sec_welcome_title), 28, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));
        final TextView subtitle = Kit.text(context, context.getString(R.string.sec_welcome_subtitle), 16, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        subtitle.setGravity(Gravity.CENTER);
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        final View spaceBottom = new View(context);
        column.addView(spaceBottom, LayoutHelper.createLinear(0, 0, 1f));

        final TextView telegram = Kit.button(context, context.getString(R.string.sec_welcome_telegram), true);
        telegram.setOnClickListener(v -> SecuredUi.openTelegramLogin(this));   // the welcome stays below: Back returns
        column.addView(telegram, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView without = Kit.button(context, context.getString(R.string.sec_welcome_without), false);
        without.setOnClickListener(v -> {
            SecuredUi.setSecuredOnly();
            presentFragment(new SecuredDialogsActivity(), true);
        });
        column.addView(without, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final TextView note = Kit.text(context, context.getString(R.string.sec_welcome_unofficial), 12, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        note.setGravity(Gravity.CENTER);
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        fragmentView = column;
        return fragmentView;
    }
}
