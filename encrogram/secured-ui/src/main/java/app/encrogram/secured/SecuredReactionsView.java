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
 * only).
 * <p>
 * Taps: the chips are deliberately NOT clickable views. The row is a RecyclerListView item, and Telegram's list
 * delivers a tap on a non-clickable item through its own item-click pipeline ({@code OnItemClickListenerExtended},
 * with the touch position), the same path that already works for message bubbles. Chips with their own click
 * listeners relied on the touch falling through that pipeline to the child, which did not happen on a device.
 * {@link #onTap} turns the position into a chip and toggles our own reaction through the listener.
 */
final class SecuredReactionsView extends LinearLayout {

    interface Listener {
        void onToggleReaction(SecuredMessage message, String emoji, boolean add);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private SecuredMessage bound;
    private Listener listener;

    SecuredReactionsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setPadding(dp(10), 0, dp(10), dp(4));
        setMinimumHeight(dp(40)); // touch height: the whole row takes the tap (onTap), the 26 dp pills stay as drawn
    }

    void bind(SecuredMessage message, Listener listener) {
        this.bound = message;
        this.listener = listener;
        removeAllViews();
        setGravity((message.getOutgoing() ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        final List<ReactionCount> reactions = message.getReactions();
        for (int i = 0; i < reactions.size(); i++) {
            final ReactionCount r = reactions.get(i);
            if (r.getCount() <= 0) continue;
            final TextView chip = chip(r);
            chip.setTag(r);
            // Taps arrive through the list (onTap); TalkBack, Switch Access and Voice Access get a click action here.
            chip.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                }

                @Override
                public boolean performAccessibilityAction(android.view.View host, int action, android.os.Bundle args) {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK && listener != null && bound != null) {
                        listener.onToggleReaction(bound, r.getEmoji(), !r.getMine());
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            chip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0, 0, 4, 0));
        }
    }

    /**
     * A tap at (x, y) in this row's own coordinates. The hit box is the chip widened by 10 dp on each side and the
     * full row height (40 dp), so a finger does not have to land on the 26 dp pill exactly. Returns true when a chip took it.
     */
    boolean onTap(float x, float y) {
        if (bound == null || listener == null) return false;
        final int slop = dp(10);
        for (int i = 0; i < getChildCount(); i++) {
            final android.view.View chip = getChildAt(i);
            if (x >= chip.getLeft() - slop && x <= chip.getRight() + slop && chip.getTag() instanceof ReactionCount) {
                final ReactionCount r = (ReactionCount) chip.getTag();
                listener.onToggleReaction(bound, r.getEmoji(), !r.getMine());
                return true;
            }
        }
        return false;
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
