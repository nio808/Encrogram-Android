package app.encrogram.secured;

import org.telegram.ui.Cells.DialogCell;

import java.util.ArrayList;
import java.util.List;

import app.encrogram.core.api.ChatKind;
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

    private static final long SAVED_MESSAGES_CHAT_ID = app.encrogram.core.api.SecuredEngineKt.SAVED_MESSAGES_CHAT_ID;

    private ChatRowMapper() {}

    /** Honest delivery states for the list. There is no read state. */
    enum Tick { NONE, CLOCK, SENT_TO_SERVER, DELIVERED, FAILED }

    /** Text the mapper needs from resources, passed in so the mapper stays testable without Android. */
    static final class Labels {
        final String connecting;
        final String noMessages;
        /** Telegram's own localized "Saved Messages": the engine gives that chat no name. */
        final String savedMessages;
        /** Groups: row text of a chat you are invited to but have not joined. */
        String invitation = "Invitation to join";
        /** Groups: "Name: message" with %1$s = sender and %2$s = text. */
        String senderFormat = "%1$s: %2$s";
        /** Groups: words a service row ("Ann joined") for the preview; null leaves "No messages yet". */
        EventWords events;
        /** A contact or a group member is on a relay Encrogram does not run: sending is paused. */
        String pausedDirect = "Paused", pausedGroup = "Paused";

        interface EventWords {
            String text(app.encrogram.core.api.GroupEvent event, app.encrogram.core.api.ChatKind kind);
        }

        /** Media: words the preview ("🖼 Photo", "🖼 caption", "📎 report.pdf"); null shows the text alone. */
        MediaWords media;

        interface MediaWords {
            String text(app.encrogram.core.api.MediaKind kind, String text);
        }

        /** Group wording (secured_group_strings.xml); the contact labels above are unchanged. */
        Labels withGroup(String invitation, String senderFormat) {
            this.invitation = invitation;
            this.senderFormat = senderFormat;
            return this;
        }

        Labels(String connecting, String noMessages, String savedMessages) {
            this.connecting = connecting;
            this.noMessages = noMessages;
            this.savedMessages = savedMessages;
        }

        Labels(String connecting, String noMessages) {
            this(connecting, noMessages, "Saved Messages");
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
        /** Saved Messages: Telegram's saved-messages avatar, no lock badge, no unread count, no "You:" and no tick. */
        boolean saved;
        /** Group or channel: avatar from the name, no verified badge, "Name: message" preview. */
        boolean group;
        boolean invited;
        boolean channel;

        /** Same everything: the chat list's DiffUtil leaves such a row alone (H2/P1). */
        boolean sameAs(Row o) {
            return o != null && chatId == o.chatId && outgoing == o.outgoing && tick == o.tick && pinned == o.pinned
                    && muted == o.muted && unread == o.unread && dateSeconds == o.dateSeconds && archived == o.archived
                    && saved == o.saved && group == o.group && invited == o.invited && channel == o.channel
                    && java.util.Objects.equals(name, o.name) && java.util.Objects.equals(message, o.message);
        }
    }

    static Row map(ChatSummary c, Labels labels, long nowMs) {
        final Row r = new Row();
        r.chatId = c.getChatId();
        r.saved = r.chatId == SAVED_MESSAGES_CHAT_ID;
        r.group = c.getKind() != ChatKind.DIRECT || app.encrogram.core.api.SecuredEngineKt.isGroupChatId(r.chatId);
        r.channel = c.getKind() == ChatKind.CHANNEL;
        r.invited = r.group && c.getInvited();
        r.name = r.saved ? labels.savedMessages : c.getDisplayName() != null ? c.getDisplayName() : "";
        r.pinned = c.getPinned();
        r.muted = c.getMuted();
        r.archived = c.getArchived();
        r.unread = r.saved ? 0 : Math.max(0, c.getUnreadCount());
        final long t = c.getLastMessageTimeMs() > 0 ? c.getLastMessageTimeMs() : nowMs;
        r.dateSeconds = (int) (t / 1000L);

        String text = c.getLastMessageText();
        if (c.getLastMessageMedia() != null && labels.media != null) {
            // A photo without a caption has no text: the kind is the preview (never "No messages yet").
            text = labels.media.text(c.getLastMessageMedia(), text);
        }
        if (r.invited) {
            r.message = labels.invitation;   // no preview of anything before you join
        } else if (c.getForeignRelay() && !r.saved) {
            r.message = r.group ? labels.pausedGroup : labels.pausedDirect;   // honest status instead of a preview
        } else if (!c.getReady() && !r.saved) {
            r.message = labels.connecting;
        } else if (text == null && c.getLastEvent() != null && labels.events != null) {
            r.message = labels.events.text(c.getLastEvent(), c.getKind());
        } else if (text == null) {
            r.message = labels.noMessages;
        } else {
            r.message = text;
            r.outgoing = c.getLastMessageOutgoing() && !r.saved;
            if (r.outgoing) {
                r.tick = tickFor(c.getLastMessageStatus());
            } else if (r.group && c.getLastMessageSender() != null && !c.getLastMessageSender().isEmpty()) {
                // Plain text, all grey: DialogCell's coloured sender name would need an upstream hook.
                r.message = String.format(labels.senderFormat, c.getLastMessageSender(), text);
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
        d.id = colorId(r.chatId);   // groups: mixed in, so they never share a hash with a contact
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

    /**
     * Folds the 64-bit chat id into an int so different chats rarely share an avatar colour. Group ids are
     * GROUP_CHAT_ID_BASE + g, so the plain fold (g ^ 256) would land on contact id g ^ 256: groups mix that in.
     */
    static int colorId(long chatId) {
        final int h = (int) (chatId ^ (chatId >>> 32));
        return app.encrogram.core.api.SecuredEngineKt.isGroupChatId(chatId) ? (h ^ 0x5bd1e995) * 31 + 7 : h;
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
        final long gBase = app.encrogram.core.api.SecuredEngineKt.GROUP_CHAT_ID_BASE;
        check(bad, "group colour id differs from the contact it used to fold onto", colorId(gBase + 5) != colorId(5) && colorId(gBase + 5) != colorId(5 ^ 256));

        ChatSummary g = new ChatSummary(gBase + 1, "Team", "hello", 1000, 0, false, true, false, null, false, false, false, null,
                ChatKind.GROUP, 3, app.encrogram.core.api.MemberRole.MEMBER, true, "Ann", false);
        r = map(g, labels, now);
        check(bad, "group preview is Name: message", "Ann: hello".equals(r.message) && r.group && !r.outgoing);
        check(bad, "group row never verified", !toCustomDialog(r).verified);
        final app.encrogram.core.api.GroupEvent joined = new app.encrogram.core.api.GroupEvent(app.encrogram.core.api.GroupEvent.Kind.JOINED, "Bo");
        g = new ChatSummary(gBase + 2, "Team", null, 1000, 0, false, true, false, null, false, false, false, null,
                ChatKind.GROUP, 3, app.encrogram.core.api.MemberRole.MEMBER, true, null, false, joined);
        r = map(g, labels, now);
        check(bad, "service-row preview without words falls back", "No messages yet".equals(r.message));
        labels.events = (e, k) -> e.getKind() + ":" + e.getMemberName();
        r = map(g, labels, now);
        check(bad, "service-row preview is worded", "JOINED:Bo".equals(r.message) && !r.outgoing);
        labels.events = null;
        final app.encrogram.core.api.GroupEvent viaLink = new app.encrogram.core.api.GroupEvent(app.encrogram.core.api.GroupEvent.Kind.JOINED_BY_LINK, null);
        g = new ChatSummary(gBase + 3, "Team", null, 1000, 0, false, true, false, null, false, false, false, null,
                ChatKind.GROUP, 3, app.encrogram.core.api.MemberRole.MEMBER, true, null, false, viaLink);
        labels.events = (e, k) -> e.getKind() + ":" + e.getMemberName();
        r = map(g, labels, now);
        check(bad, "link join preview is worded", "JOINED_BY_LINK:null".equals(r.message));
        labels.events = null;

        final ChatSummary photo = new ChatSummary(4, "Di", null, 1000, 0, false, true, false, null, false, false, false, null,
                ChatKind.DIRECT, null, null, true, null, false, null, false, app.encrogram.core.api.MediaKind.IMAGE);
        labels.media = (k, t) -> k + ":" + (t == null ? "" : t);
        r = map(photo, labels, now);
        check(bad, "media without caption is worded, not empty", "IMAGE:".equals(r.message));
        labels.media = null;
        check(bad, "avatar colour key is the name, not an id", Kit.colorKey(" Ann ") == Kit.colorKey("Ann") && Kit.colorKey("Ann") != Kit.colorKey("Bob"));
        g = new ChatSummary(gBase + 2, "Club", "secret", 1000, 1, false, true, false, null, false, false, false, null,
                ChatKind.GROUP, 3, app.encrogram.core.api.MemberRole.MEMBER, false, "Ann", true);
        r = map(g, labels, now);
        check(bad, "invited row: invitation text, no preview", "Invitation to join".equals(r.message) && r.invited);
        return bad;
    }

    private static void check(List<String> bad, String what, boolean ok) {
        if (!ok) bad.add(what);
    }
}
