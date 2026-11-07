package app.encrogram.secured;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

/**
 * Theme for the Secured chat screen: Encro Indigo colours from {@link SecuredResourcesProvider}, plus the bubble
 * and tick drawables that Telegram's ChatMessageCell asks its provider for. Without these, the cell would draw
 * Telegram's shared global bubbles in Telegram's colours. Reuse level 2 (Telegram's MessageDrawable and tick
 * icons, given our colours), the same pattern ThemePreviewActivity and ChannelColorActivity use.
 * <p>
 * Ticks: "delivered" uses the same colour as "on our server" (SecuredResourcesProvider maps
 * {@code key_chat_outSentCheckRead} to the sent colour), because Secured has no read receipts.
 */
final class SecuredChatResources implements Theme.ResourcesProvider {

    private final Theme.ResourcesProvider base = SecuredResourcesProvider.get();

    private MessageDrawable msgIn, msgInSelected, msgInMedia, msgInMediaSelected;
    private MessageDrawable msgOut, msgOutSelected, msgOutMedia, msgOutMediaSelected;
    private final TintedIcon check = new TintedIcon(Theme.key_drawable_msgOutCheck, Theme.key_chat_outSentCheck);
    private final TintedIcon checkSelected = new TintedIcon(Theme.key_drawable_msgOutCheckSelected, Theme.key_chat_outSentCheckSelected);
    // "Delivered" (Telegram draws its read pair here) uses our outline ticks, as in the chat list: never the filled read look.
    private final OutlineIcon checkRead = new OutlineIcon(Theme.key_drawable_msgOutCheckRead, Theme.key_chat_outSentCheckRead);
    private final OutlineIcon checkReadSelected = new OutlineIcon(Theme.key_drawable_msgOutCheckReadSelected, Theme.key_chat_outSentCheckReadSelected);
    private final OutlineIcon halfCheck = new OutlineIcon(Theme.key_drawable_msgOutHalfCheck, Theme.key_chat_outSentCheckRead);
    private final OutlineIcon halfCheckSelected = new OutlineIcon(Theme.key_drawable_msgOutHalfCheckSelected, Theme.key_chat_outSentCheckReadSelected);

    @Override
    public int getColor(int key) {
        if (key == Theme.key_chat_outBubbleGradient1 || key == Theme.key_chat_outBubbleGradient2
                || key == Theme.key_chat_outBubbleGradient3 || key == Theme.key_chat_outBubbleGradientAnimated) {
            return 0; // solid Encro Indigo bubbles, even when the Telegram theme uses gradient bubbles
        }
        if (key == Theme.key_chat_outReplyLine || key == Theme.key_chat_outReplyNameText || key == Theme.key_chat_messageLinkOut) {
            return SecuredResourcesProvider.accentText();
        }
        return base.getColor(key);
    }

    @Override
    public boolean hasGradientService() {
        // The screen draws the user's cached Telegram wallpaper, so service pills follow Telegram's service shader.
        return Theme.hasGradientService();
    }

    @Override
    public Drawable getDrawable(String key) {
        if (Theme.key_drawable_msgOut.equals(key)) {
            if (msgOut == null) msgOut = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, false, this);
            return msgOut;
        } else if (Theme.key_drawable_msgOutSelected.equals(key)) {
            if (msgOutSelected == null) msgOutSelected = new MessageDrawable(MessageDrawable.TYPE_TEXT, true, true, this);
            return msgOutSelected;
        } else if (Theme.key_drawable_msgOutMedia.equals(key)) {
            if (msgOutMedia == null) msgOutMedia = new MessageDrawable(MessageDrawable.TYPE_MEDIA, true, false, this);
            return msgOutMedia;
        } else if (Theme.key_drawable_msgOutMediaSelected.equals(key)) {
            if (msgOutMediaSelected == null) msgOutMediaSelected = new MessageDrawable(MessageDrawable.TYPE_MEDIA, true, true, this);
            return msgOutMediaSelected;
        } else if (Theme.key_drawable_msgIn.equals(key)) {
            if (msgIn == null) msgIn = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, this);
            return msgIn;
        } else if (Theme.key_drawable_msgInSelected.equals(key)) {
            if (msgInSelected == null) msgInSelected = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, true, this);
            return msgInSelected;
        } else if (Theme.key_drawable_msgInMedia.equals(key)) {
            if (msgInMedia == null) msgInMedia = new MessageDrawable(MessageDrawable.TYPE_MEDIA, false, false, this);
            return msgInMedia;
        } else if (Theme.key_drawable_msgInMediaSelected.equals(key)) {
            if (msgInMediaSelected == null) msgInMediaSelected = new MessageDrawable(MessageDrawable.TYPE_MEDIA, false, true, this);
            return msgInMediaSelected;
        } else if (Theme.key_drawable_msgOutCheck.equals(key)) {
            return check.get();
        } else if (Theme.key_drawable_msgOutCheckSelected.equals(key)) {
            return checkSelected.get();
        } else if (Theme.key_drawable_msgOutCheckRead.equals(key)) {
            return checkRead.get();
        } else if (Theme.key_drawable_msgOutCheckReadSelected.equals(key)) {
            return checkReadSelected.get();
        } else if (Theme.key_drawable_msgOutHalfCheck.equals(key)) {
            return halfCheck.get();
        } else if (Theme.key_drawable_msgOutHalfCheckSelected.equals(key)) {
            return halfCheckSelected.get();
        }
        return null; // callers fall back to Telegram's global theme drawable (local resources only)
    }

    /** A private, mutated copy of one of Telegram's tick icons, tinted with our colour for its key. */
    private final class TintedIcon {
        private final String drawableKey;
        private final int colorKey;
        private Drawable drawable;
        private int tint;

        TintedIcon(String drawableKey, int colorKey) {
            this.drawableKey = drawableKey;
            this.colorKey = colorKey;
        }

        Drawable get() {
            if (drawable == null) {
                final Drawable global = Theme.getThemeDrawable(drawableKey);
                if (global == null || global.getConstantState() == null) {
                    return null;
                }
                drawable = global.getConstantState().newDrawable().mutate();
                tint = 0;
            }
            final int color = getColor(colorKey);
            if (color != tint || tint == 0) {
                tint = color;
                drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            }
            return drawable;
        }
    }

    /** Our outline tick in the size of Telegram's tick drawable for {@code drawableKey}, coloured by {@code colorKey}. */
    private final class OutlineIcon {
        private final String drawableKey;
        private final int colorKey;
        private SecuredDialogCell.OutlineTick drawable;

        OutlineIcon(String drawableKey, int colorKey) {
            this.drawableKey = drawableKey;
            this.colorKey = colorKey;
        }

        Drawable get() {
            if (drawable == null) {
                final Drawable global = Theme.getThemeDrawable(drawableKey);
                if (global == null) {
                    return null;
                }
                drawable = new SecuredDialogCell.OutlineTick(global.getIntrinsicWidth(), global.getIntrinsicHeight());
            }
            drawable.setColor(getColor(colorKey));
            return drawable;
        }
    }
}
