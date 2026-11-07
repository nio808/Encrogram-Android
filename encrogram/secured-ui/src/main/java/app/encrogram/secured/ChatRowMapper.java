package app.encrogram.secured;

import org.telegram.ui.Cells.DialogCell;

import java.util.ArrayList;
import java.util.List;

import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.MessageStatus;

/**
 * The one place that turns a {@link ChatSummary} into what a chat-list row shows. Reuse level 4 (nothing in Telegram
 * maps our data). It makes no Telegram lookup: {@link #map} is pure Java, and only {@link #toCustomDialog} builds
 * DialogCell's plain CustomDialog (strings and numbers).
 *
 * Rules enforced here: no media row ever ({@code isMedia} stays false, so the "type 1 with media" crash is impossible);
 * type is 0 or 1 only (never 2, Telegram's secret-chat look); the verified mark is never set from here (Telegram's
 * badge means "official account", which Secured cannot claim).
 */
final class ChatRowMapper {

    private ChatRowMapper() {}

    /** Honest delivery states for the list. There is no read state. */
    enum Tick { NONE, CLOCK, SENT_TO_SERVER, DELIVERED, FAILED }

    /** Text the mapper needs from resources, passed in so the mapper stays testable without Android. */
    static final class Labels {
        final String connecting;
        final String noMessages;

        Labels(String connecting, String noMessages) {
            this.connecting = connecting;
            this.noMessages = noMessages;
        }
    }

    /** Everything a row shows. Plain data. */
    static final class Row {
        long chatId;
        String name;
        String message;
        /** True: DialogCell draws "You: " before the message. */
        boolean outgoing;
        Tick tick = Tick.NONE;
        boolean pinned;
        boolean muted;
        int unread;
        int dateSeconds;
        boolean archived;
    }

    static Row map(ChatSummary c, Labels labels, long nowMs) {
        final Row r = new Row();
        r.chatId = c.getChatId();
        r.name = c.getDisplayName() != null ? c.getDisplayName() : "";
        r.pinned = c.getPinned();
        r.muted = c.getMuted();
        r.archived = c.getArchived();
        r.unread = Math.max(0, c.getUnreadCount());
        final long t = c.getLastMessageTimeMs() > 0 ? c.getLastMessageTimeMs() : nowMs;
        r.dateSeconds = (int) (t / 1000L);

        final String text = c.getLastMessageText();
        if (!c.getReady()) {
            r.message = labels.connecting;
        } else if (text == null) {
            r.message = labels.noMessages;
        } else {
            r.message = text;
            r.outgoing = c.getLastMessageOutgoing();
            if (r.outgoing) {
                r.tick = tickFor(c.getLastMessageStatus());
            }
        }
        return r;
    }

    static Tick tickFor(MessageStatus s) {
        if (s == null) return Tick.NONE;
        switch (s) {
            case SENDING:
                return Tick.CLOCK;
            case SENT_TO_SERVER:
                return Tick.SENT_TO_SERVER;
            case DELIVERED:
                return Tick.DELIVERED;
            case FAILED:
                return Tick.FAILED;
            default:
                return Tick.NONE;   // RECEIVED and anything unknown: no tick
        }
    }

    /** A row for DialogCell. The id is only used for the avatar colour; the cell never looks it up. */
    static DialogCell.CustomDialog toCustomDialog(Row r) {
        final DialogCell.CustomDialog d = new DialogCell.CustomDialog();
        d.name = r.name;
        d.message = r.message != null ? r.message : "";
        d.id = colorId(r.chatId);
        d.unread_count = r.unread;
        d.pinned = r.pinned;
        d.muted = r.muted;
        d.date = r.dateSeconds;
        d.verified = false;
        d.isMedia = false;
        d.type = r.outgoing ? 1 : 0;
        switch (r.tick) {
            case CLOCK:
                d.sent = DialogCell.SENT_STATE_PROGRESS;
                break;
            case SENT_TO_SERVER:
                d.sent = DialogCell.SENT_STATE_SENT;
                break;
            case DELIVERED:
                // DialogCell has no "delivered" look; SecuredDialogCell swaps the double tick for our outline one.
                d.sent = DialogCell.SENT_STATE_READ;
                break;
            default:
                d.sent = DialogCell.SENT_STATE_NOTHING;   // FAILED is drawn by SecuredDialogCell
        }
        return d;
    }

    /** Folds the 64-bit chat id into an int so different chats rarely share an avatar colour. */
    static int colorId(long chatId) {
        return (int) (chatId ^ (chatId >>> 32));
    }

    // ---- self check (the module has no unit-test setup; the lead can call this from a debug hook) -----------------

    /** Returns the failed checks; an empty list means all passed. Makes no engine call and no Telegram lookup. */
    static List<String> selfCheck() {
        final List<String> bad = new ArrayList<>();
        final Labels labels = new Labels("Connecting…", "No messages yet");
        final long now = 1_700_000_000_000L;

        Row r = map(new ChatSummary(1, "Ann", "hi", 1000, 2, false, true, true, MessageStatus.SENDING, true, true, false, null), labels, now);
        check(bad, "sending: clock, You:", r.outgoing && r.tick == Tick.CLOCK);
        check(bad, "pinned and muted carried", r.pinned && r.muted && r.unread == 2);

        r = map(new ChatSummary(1, "Ann", "hi", 1000, 0, false, true, true, MessageStatus.SENT_TO_SERVER, false, false, false, null), labels, now);
        check(bad, "sent to server: one tick", r.tick == Tick.SENT_TO_SERVER);
        r = map(new ChatSummary(1, "Ann", "hi", 1000, 0, false, true, true, MessageStatus.DELIVERED, false, false, false, null), labels, now);
        check(bad, "delivered: outline double tick", r.tick == Tick.DELIVERED);
        r = map(new ChatSummary(1, "Ann", "hi", 1000, 0, false, true, true, MessageStatus.FAILED, false, false, false, null), labels, now);
        check(bad, "failed: error tick", r.tick == Tick.FAILED);

        r = map(new ChatSummary(1, "Ann", "hi", 1000, 1, false, true, false, MessageStatus.DELIVERED, false, false, false, null), labels, now);
        check(bad, "incoming: no tick, no You:", !r.outgoing && r.tick == Tick.NONE);
        r = map(new ChatSummary(1, "Ann", "hi", 1000, 1, false, true, true, MessageStatus.RECEIVED, false, false, false, null), labels, now);
        check(bad, "RECEIVED never ticks", r.tick == Tick.NONE);

        r = map(new ChatSummary(2, "Bob", "secret", 0, 0, true, false, true, MessageStatus.SENDING, false, false, false, null), labels, now);
        check(bad, "not ready: Connecting…, no preview text, no tick", "Connecting…".equals(r.message) && !r.outgoing && r.tick == Tick.NONE);
        check(bad, "no time: uses now", r.dateSeconds == (int) (now / 1000L));
        r = map(new ChatSummary(3, "Cy", null, 0, 0, false, true, false, null, false, false, true, null), labels, now);
        check(bad, "empty chat text", "No messages yet".equals(r.message) && r.archived);

        for (Tick t : Tick.values()) {
            final Row x = new Row();
            x.tick = t;
            x.outgoing = t != Tick.NONE;
            final DialogCell.CustomDialog d = toCustomDialog(x);
            check(bad, "never media, type 0 or 1: " + t, !d.isMedia && (d.type == 0 || d.type == 1) && !d.verified);
        }
        check(bad, "colour id differs for ids that share low bits", colorId(1L) != colorId(1L + (1L << 32)));
        return bad;
    }

    private static void check(List<String> bad, String what, boolean ok) {
        if (!ok) bad.add(what);
    }
}
