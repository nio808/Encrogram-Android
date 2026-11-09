package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.Date;

import app.encrogram.core.api.MemberDelivery;
import app.encrogram.core.api.MessageInfo;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.MessageVersion;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredResult;

/**
 * Message details (U11): times, honest status, per-member delivery in groups, earlier versions after edits. A BottomSheet
 * in the style of {@link SecuredChatSheets} (level 2), shown through {@link Kit#show} so FLAG_SECURE applies. Never says
 * "read" or "seen": there are no read receipts. Message text (versions) is shown but never logged or stored.
 */
final class SecuredMessageDetailsSheet {

    private SecuredMessageDetailsSheet() {}

    static void show(BaseFragment from, SecuredEngine engine, long chatId, long messageId) {
        final Context context = from == null ? null : from.getParentActivity();
        if (context == null || engine == null) return;

        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.addView(Kit.text(context, context.getString(R.string.sec_r2i_details_title), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true));
        final TextView body = Kit.text(context, context.getString(R.string.sec_r2i_loading), 14, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        box.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        box.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        builder.setCustomView(scroll);
        final BottomSheet sheet = builder.create();
        Kit.show(from, sheet);

        try {
            engine.messageInfo(chatId, messageId, result -> AndroidUtilities.runOnUIThread(() -> {
                if (!sheet.isShowing()) return;
                if (result instanceof SecuredResult.Ok) {
                    body.setVisibility(View.GONE);
                    fill(context, content, ((SecuredResult.Ok<MessageInfo>) result).getValue());
                } else {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    // The engine's default "not yet" answer is shown as a plain unavailable note.
                    body.setText(e.getKind() == SecuredError.Kind.UNSUPPORTED || e.getKind() == SecuredError.Kind.UNKNOWN
                            ? context.getString(R.string.sec_r2i_unavailable)
                            : context.getString(R.string.sec_r2i_error, e.getMessage()));
                }
            }));
        } catch (Throwable t) {
            body.setText(context.getString(R.string.sec_r2i_error, t.getClass().getSimpleName()));
        }
    }

    private static void fill(Context c, LinearLayout out, MessageInfo info) {
        row(c, out, R.string.sec_r2i_sent, time(info.getSentAtMs()));
        // Only incoming messages were received on this phone; for ones you sent show just Sent and the status.
        if (info.getStatus() == MessageStatus.RECEIVED) {
            row(c, out, R.string.sec_r2i_received, time(info.getReceivedAtMs()));
        }
        row(c, out, R.string.sec_r2i_status, c.getString(word(info.getStatus())));

        if (!info.getDeliveries().isEmpty()) {
            header(c, out, c.getString(R.string.sec_r2i_delivery_header));
            for (MemberDelivery d : info.getDeliveries()) {
                line(c, out, d.getDisplayName(), c.getString(word(d.getStatus())));
            }
        }
        if (!info.getVersions().isEmpty()) {
            header(c, out, c.getString(R.string.sec_r2i_versions_header));
            for (MessageVersion v : info.getVersions()) {   // newest first, as the engine returns them
                final TextView t = Kit.text(c, v.getText(), 15, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
                t.setTextIsSelectable(false);
                out.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
                out.addView(Kit.text(c, time(v.getTimeMs()), 12, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false));
            }
        }
    }

    /** Honest words only: nothing reads "read". Incoming messages are "Received". */
    private static int word(MessageStatus s) {
        return s == MessageStatus.RECEIVED ? R.string.sec_r2i_status_received : SecuredMessageCell.wordFor(s);
    }

    private static String time(long ms) {
        return ms <= 0 ? "-" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(ms));
    }

    private static void row(Context c, LinearLayout out, int label, String value) {
        line(c, out, c.getString(label), value);
    }

    private static void line(Context c, LinearLayout out, String label, String value) {
        final LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        final TextView a = Kit.text(c, label, 15, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        final TextView b = Kit.text(c, value, 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        b.setGravity(Gravity.END);
        l.addView(a, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        l.addView(b, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.END, 12, 0, 0, 0));
        out.addView(l, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
    }

    private static void header(Context c, LinearLayout out, String text) {
        out.addView(Kit.text(c, text, 14, SecuredResourcesProvider.accentText(), true),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));
    }
}
