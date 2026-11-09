package app.encrogram.secured;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.messenger.ChatMessageSharedResources;

import java.util.List;

import app.encrogram.core.api.MessageStatus;

/**
 * Telegram's ChatMessageCell with an honest spoken status (reuse level 2: a subclass, no upstream edit).
 * <p>
 * Why: the text mapper marks a delivered outgoing message as "read" so the cell draws the double tick, and the cell's
 * TalkBack text then ends with Telegram's "Seen" ("Sent at 9:16 AM, Seen"). Encrogram has no read receipts. This
 * subclass wraps the cell's accessibility node provider and swaps that last status word for our own: Delivered,
 * Sent to server, Sending or Failed to send. In Saved Messages (no ticks) the status is dropped altogether.
 * Visible drawing is untouched.
 */
final class SecuredMessageCell extends ChatMessageCell {

    private MessageStatus status;   // null for incoming messages
    private boolean notes;

    SecuredMessageCell(Context context, int account, ChatMessageSharedResources shared, Theme.ResourcesProvider provider) {
        super(context, account, false, shared, provider);
    }

    /** Called on every bind. {@code status} is null for an incoming message. */
    void setSecuredStatus(MessageStatus status, boolean notes) {
        this.status = status;
        this.notes = notes;
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        final AccessibilityNodeProvider base = super.getAccessibilityNodeProvider();
        if (base == null || status == null) return base;
        return new AccessibilityNodeProvider() {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                final AccessibilityNodeInfo info = base.createAccessibilityNodeInfo(virtualViewId);
                if (info != null && virtualViewId == HOST_VIEW_ID) {
                    fix(info);
                }
                return info;
            }

            @Override
            public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                return base.performAction(virtualViewId, action, arguments);
            }

            @Override
            public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text, int virtualViewId) {
                return base.findAccessibilityNodeInfosByText(text, virtualViewId);
            }

            @Override
            public AccessibilityNodeInfo findFocus(int focus) {
                return base.findFocus(focus);
            }
        };
    }

    private void fix(AccessibilityNodeInfo info) {
        final CharSequence text = info.getText();
        if (text == null) return;
        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        // Replace only the word Telegram's cell produced for this status (the mapper sets read/unread/sending/error
        // from our status), so another word that happens to contain it is never touched.
        final int theirs = telegramWordFor(status);
        if (theirs == 0) return;
        final String word = LocaleController.getString(theirs);
        if (word == null || word.isEmpty()) return;
        final String all = out.toString();
        final int at = all.lastIndexOf(word);
        final int len = word.length();
        if (at < 0) return;
        final Context c = getContext();
        if (notes) {
            // No status at all: drop the word and the ", " in front of it.
            final int from = at >= 2 && all.startsWith(", ", at - 2) ? at - 2 : at;
            out.delete(from, at + len);
        } else {
            out.replace(at, at + len, c.getString(wordFor(status)));
        }
        info.setText(out); // text only, as Telegram does: a content description would hide link targets from TalkBack
    }

    /** The word ChatMessageCell speaks for the flags our mapper sets per status; 0 for incoming messages. */
    private static int telegramWordFor(MessageStatus s) {
        if (s == null) return 0;
        switch (s) {
            case DELIVERED:
                return org.telegram.messenger.R.string.AccDescrMsgRead;
            case SENT_TO_SERVER:
                return org.telegram.messenger.R.string.AccDescrMsgUnread;
            case SENDING:
                return org.telegram.messenger.R.string.AccDescrMsgSending;
            case FAILED:
                return org.telegram.messenger.R.string.AccDescrMsgSendingError;
            default:
                return 0;
        }
    }

    /** Also read by the media bubbles' TalkBack text (AR12). */
    static int wordFor(MessageStatus s) {
        switch (s) {
            case SENDING:
                return R.string.secchat_status_sending;
            case FAILED:
                return R.string.secchat_status_failed;
            case DELIVERED:
                return R.string.secchat_status_delivered;
            default:
                return R.string.secchat_status_sent;
        }
    }
}
