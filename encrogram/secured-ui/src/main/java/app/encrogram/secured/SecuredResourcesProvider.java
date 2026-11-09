package app.encrogram.secured;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Encro Indigo, applied only on Secured screens through Telegram's per-screen theme overlay.
 * Overrides a short list of colour keys and falls back to the user's current theme for everything else,
 * so Telegram's own screens are untouched. No new keys are added to Telegram's Theme.
 *
 * Palette (research/ideas-and-design.md section 3.3): accent text #4A4FD6 light / #A3A6FF dark,
 * accent fill #4A4FD6 light / #5357DD dark, outgoing bubble #E8E7FF light / #4448A8 dark.
 */
public final class SecuredResourcesProvider implements Theme.ResourcesProvider {

    public static final int INDIGO_FILL_LIGHT = 0xFF4A4FD6;
    public static final int INDIGO_FILL_DARK = 0xFF5357DD;
    public static final int INDIGO_TEXT_LIGHT = 0xFF4A4FD6;
    public static final int INDIGO_TEXT_DARK = 0xFFA3A6FF;

    private static final SecuredResourcesProvider INSTANCE = new SecuredResourcesProvider();

    public static SecuredResourcesProvider get() {
        return INSTANCE;
    }

    private SecuredResourcesProvider() {}

    public static boolean dark() {
        return Theme.isCurrentThemeDark();
    }

    /** Fill colour for accent buttons, the selected Secured pill, badges and the FAB. */
    public static int accentFill() {
        return dark() ? INDIGO_FILL_DARK : INDIGO_FILL_LIGHT;
    }

    /** Accent colour for text and icons. */
    public static int accentText() {
        return dark() ? INDIGO_TEXT_DARK : INDIGO_TEXT_LIGHT;
    }

    /** Chat background: the theme's grey with the same faint indigo cast (the "Encro Lattice" wallpaper comes later). */
    public static int chatBackground() {
        return ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundGray), INDIGO_FILL_LIGHT, dark() ? 0.06f : 0.05f);
    }

    private static int tinted(int key) {
        // A barely visible indigo cast on the page and the bar: blend 4 percent (light) or 6 percent (dark).
        return ColorUtils.blendARGB(Theme.getColor(key), INDIGO_FILL_LIGHT, dark() ? 0.06f : 0.04f);
    }

    @Override
    public int getColor(int key) {
        final boolean d = dark();
        if (key == Theme.key_windowBackgroundWhite || key == Theme.key_actionBarDefault) {
            return tinted(key);
        } else if (key == Theme.key_chats_actionBackground
                || key == Theme.key_chats_unreadCounter
                || key == Theme.key_chat_messagePanelSend
                || key == Theme.key_featuredStickers_addButton) {
            return accentFill();
        } else if (key == Theme.key_chats_actionIcon
                || key == Theme.key_chats_unreadCounterText
                || key == Theme.key_featuredStickers_buttonText) {
            return 0xFFFFFFFF;
        } else if (key == Theme.key_windowBackgroundWhiteBlueText
                || key == Theme.key_windowBackgroundWhiteBlueHeader
                || key == Theme.key_windowBackgroundWhiteValueText
                || key == Theme.key_dialogButton) {
            return accentText();
        } else if (key == Theme.key_chat_outBubble) {
            return d ? 0xFF4448A8 : 0xFFE8E7FF;
        } else if (key == Theme.key_chats_sentCheck
                || key == Theme.key_chats_sentReadCheck
                || key == Theme.key_chats_sentClock) {
            return d ? 0xFFC9CBFF : 0xFF5357CC;
        } else if (key == Theme.key_chat_outBubbleSelected) {
            return d ? 0xFF3B3E94 : 0xFFD9D7FF;
        } else if (key == Theme.key_chat_messageTextOut) {
            return d ? 0xFFFAFAFA : 0xFF000000;
        } else if (key == Theme.key_chat_outTimeText
                || key == Theme.key_chat_outSentCheck
                || key == Theme.key_chat_outSentCheckRead
                || key == Theme.key_chat_outSentClock) {
            return d ? 0xFFC9CBFF : 0xFF5357CC;
        }
        return Theme.getColor(key);
    }
}
