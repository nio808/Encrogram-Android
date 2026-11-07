package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.Emoji;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

import app.encrogram.core.api.ReactionCount;
import app.encrogram.core.api.SecuredMessage;

/**
 * The small row of reaction counts under a Secured bubble ("👍 2"). Our own view (reuse level 4), deliberately
 * not Telegram's ReactionsLayoutInBubble, which resolves reaction icons through MediaDataController and loads
 * them from Telegram's servers. Emoji are drawn from the app's bundled emoji pack (Emoji.replaceEmoji, assets
 * only). Tapping a chip toggles our own reaction through the listener.
 */
final class SecuredReactionsView extends LinearLayout {

    interface Listener {
        void onToggleReaction(SecuredMessage message, String emoji, boolean add);
    }

    private final Theme.ResourcesProvider resourcesProvider;

    SecuredReactionsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setPadding(dp(10), 0, dp(10), dp(4));
    }

    void bind(SecuredMessage message, Listener listener) {
        removeAllViews();
        setGravity((message.getOutgoing() ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        final List<ReactionCount> reactions = message.getReactions();
        for (int i = 0; i < reactions.size(); i++) {
            final ReactionCount r = reactions.get(i);
            if (r.getCount() <= 0) continue;
            final TextView chip = chip(r);
            chip.setOnClickListener(v -> {
                if (listener != null) listener.onToggleReaction(message, r.getEmoji(), !r.getMine());
            });
            addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0, 0, 4, 0));
        }
    }

    private TextView chip(ReactionCount r) {
        final TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, dp(8), 0);
        final boolean mine = r.getMine();
        final int bg = mine ? SecuredResourcesProvider.accentFill() : resourcesProvider.getColor(Theme.key_chat_serviceBackground);
        final int fg = mine ? 0xFFFFFFFF : resourcesProvider.getColor(Theme.key_chat_serviceText);
        t.setTextColor(fg);
        t.setBackground(Kit.roundRect(bg, 13));
        t.setText(Emoji.replaceEmoji(r.getEmoji() + " " + r.getCount(), t.getPaint().getFontMetricsInt(), false));
        t.setContentDescription(getContext().getString(mine ? R.string.secchat_reaction_mine_a11y : R.string.secchat_reaction_a11y,
                r.getEmoji(), r.getCount()));
        return t;
    }
}
