package app.encrogram.secured;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.URLSpanBrowser;
import org.telegram.ui.Components.URLSpanMono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import app.encrogram.core.api.ChatKind;
import app.encrogram.core.api.GroupEvent;
import app.encrogram.core.api.MemberRole;
import app.encrogram.core.api.MessageSender;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.QuotePreview;
import app.encrogram.core.api.ReactionCount;
import app.encrogram.core.api.SecuredMessage;

/**
 * The ONLY place where a Telegram {@link MessageObject} is built from Secured data (AGENTS.md rule 1,
 * research/android-ui-reuse.md section 3.3). Reuse level 2: Telegram's model, restricted by this mapper.
 * <p>
 * The produced object is a local, never-serialised {@code TL_message} with a fixed text-only shape:
 * <ul>
 * <li>{@code media} is always {@code TL_messageMediaEmpty}; no webpage, document, photo or dice;</li>
 * <li>no reactions, fwd_from, reply_to header, reply markup, views, replies, via-bot, effect, ttl, grouped id,
 * rich message or summary fields (Telegram's reaction layout loads icons from its CDN, fwd_from triggers user
 * lookups, views queue a network request);</li>
 * <li>entities are only {@code TL_messageEntityUrl} (found locally with Android's Linkify),
 * {@code TL_messageEntityItalic} (our own "Forwarded" and "Message deleted" labels) and {@code TL_messageEntityBold}
 * (group mentions the engine reported for the message, {@link MentionText}). Never custom emoji, Telegram mention,
 * hashtag or bot command entities: a mention is bold text, nothing to tap;</li>
 * <li>peer, sender and dialog id are the sentinel {@link #SENTINEL_ID};</li>
 * <li>the subclass {@link SecuredMessageObject} switches off the big animated emoji sticker lookup, the
 * emoji-to-Lottie replacement, avatars and the share button, and removes Telegram's own link spans (mention,
 * hashtag, cashtag, bot command, ...) every time Telegram parses the text ({@link LinkSpans});</li>
 * <li>{@code out}, {@code id} sign, {@code send_state} and {@code unread} follow our honest status mapping:
 * clock while sending, one tick on our server, two ticks when delivered (no "read" state exists), error when
 * failed.</li>
 * </ul>
 * The Secured message id (a long) never becomes a Telegram id: each chat screen numbers its messages with a local
 * counter ({@link #localId(long)}).
 */
public final class SecuredMessageMapper {

    /**
     * Peer, sender and dialog id of every Secured MessageObject. Why this value is safe:
     * <ul>
     * <li>it is about 2.2e18, far above 2^52. Telegram user ids are positive and below 2^40 (Telegram's API rules
     * allow at most 52 significant bits for any peer id), so no Telegram account, bot or service user can ever
     * have it, and {@code MessagesController.getUser(id)} (a plain in-memory map lookup) returns null: the cell
     * finds no name, avatar, premium badge or profile to show (leak catalogue item 5, "wrong identity");</li>
     * <li>bits 61, 62 and 63 are clear, so {@code DialogObject} reads it as an ordinary user dialog: not a folder
     * (bit 61), not a secret chat (bit 62, which would look up an encrypted chat and the current user) and not a
     * group or channel (negative);</li>
     * <li>it is not 0: {@code MessagesController.getUser(0)} returns the logged-in Telegram user;</li>
     * <li>it differs from every special id in {@code UserObject} (777000, REPLY_BOT, VERIFY, ANONYMOUS, OAUTH) and
     * from {@code UserConfig.clientUserId} (below 2^40), so Saved Messages, "replies" and service-notification
     * code paths never trigger, and {@code MediaController.isPlayingMessage} can never match a Telegram
     * message.</li>
     * </ul>
     */
    public static final long SENTINEL_ID = 0x1EC0_0000_0000_0001L;

    private final int account;
    private final String deletedText;
    private final String forwardedLabel;
    private final String quoteFallback;
    private final String emptyText;
    private final String youName;
    private final Context context;

    /** Groups: resolves the sender name of a quoted message (null = unknown). Set by the chat screen. */
    public interface SenderLookup {
        String nameOf(long securedMessageId);
    }

    private ChatKind kind = ChatKind.DIRECT;
    private SenderLookup senderLookup;
    private Theme.ResourcesProvider avatarResources;

    private String peerName = "";
    private String selfName;

    private final HashMap<Long, Integer> localIds = new HashMap<>();
    private int nextLocalId = 1;

    public SecuredMessageMapper(Context context, int account) {
        this.account = account;
        this.context = context;
        this.deletedText = context.getString(R.string.secchat_deleted);
        this.forwardedLabel = context.getString(R.string.secchat_forwarded);
        this.quoteFallback = context.getString(R.string.secchat_quote_fallback);
        this.emptyText = context.getString(R.string.secchat_empty_message);
        this.youName = context.getString(R.string.secchat_you);
        this.selfName = youName;
    }

    /** Display names used for reply headers and {@code customName}. Both are Secured names, never Telegram's. */
    public void setNames(String peerName, String selfName) {
        this.peerName = peerName != null ? peerName : "";
        this.selfName = selfName != null && !selfName.isEmpty() ? selfName : youName;
    }

    /** GROUP and CHANNEL chats get sender names, colours and avatars on incoming bubbles. */
    public void setKind(ChatKind kind) {
        this.kind = kind != null ? kind : ChatKind.DIRECT;
    }

    public void setSenderLookup(SenderLookup lookup) {
        this.senderLookup = lookup;
    }

    public void setAvatarResources(Theme.ResourcesProvider provider) {
        this.avatarResources = provider;
    }

    private boolean isGroup() {
        return kind != ChatKind.DIRECT;
    }

    public String peerName() {
        return peerName;
    }

    public String selfName() {
        return selfName;
    }

    /** A small positive id for a Secured message, stable for the life of this mapper (one chat screen). */
    public int localId(long securedMessageId) {
        Integer id = localIds.get(securedMessageId);
        if (id == null) {
            id = nextLocalId;
            nextLocalId = nextLocalId >= Integer.MAX_VALUE - 1 ? 1 : nextLocalId + 1;
            localIds.put(securedMessageId, id);
        }
        return id;
    }

    // ---- mapping ----------------------------------------------------------------------------------------------

    /** Builds the text-only MessageObject for one Secured message. Main thread only. */
    public MessageObject map(SecuredMessage m) {
        final boolean out = m.getOutgoing();
        final TLRPC.TL_message msg = baseMessage(out, (int) (m.getTimeMs() / 1000L));
        final ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        final String body;
        if (m.getDeleted()) {
            // Never the old text, not even if the engine still sent it: only our label, in italics.
            body = deletedText;
            entities.add(italic(0, body.length()));
        } else {
            String text = m.getText();
            final boolean empty = text == null || text.isEmpty();
            if (empty) {
                text = emptyText;
            }
            final String prefix = m.getForwarded() ? forwardedLabel + "\n" : "";
            body = prefix + text;
            if (!prefix.isEmpty()) {
                entities.add(italic(0, forwardedLabel.length()));
            }
            if (empty) {
                entities.add(italic(prefix.length(), text.length()));
            } else {
                addUrlEntities(body, prefix.length(), entities);
                addMentionEntities(body, prefix.length(), m.getMentions(), entities);
            }
        }
        msg.message = body;
        msg.entities = entities;
        if (!entities.isEmpty()) {
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        if (m.getEdited() && !m.getDeleted()) {
            msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
            msg.edit_date = msg.date; // the contract has no edit time; show the message time
        }
        applyStatus(msg, m);

        MessageObject reply = null;
        final QuotePreview quote = m.getReplyTo();
        if (quote != null && !m.getDeleted()) {
            reply = mapQuote(quote);
        }
        final SecuredMessageObject mo = new SecuredMessageObject(account, msg, reply, true);
        final MessageSender sender = m.getSender();
        if (isGroup() && !out && sender != null) {
            // Group bubble (research/group-ui-reuse.md section 1): Telegram's own name, colour and avatar fields,
            // fed from the member key. The sentinel stays the only peer id; no Telegram user object exists.
            mo.customName = sender.getDisplayName();
            mo.overrideLinkColor = AvatarDrawable.getColorIndex(Kit.colorKey(sender.getDisplayName()));
            final AvatarDrawable avatar = avatarResources != null ? new AvatarDrawable(avatarResources) : new AvatarDrawable();
            Kit.setAvatar(avatar, sender.getMemberId(), sender.getDisplayName());
            mo.customAvatarDrawable = avatar;
            // The text width was measured at construction, before the avatar existed: measure again with the avatar column.
            mo.generateLayout(null);
        } else {
            mo.customName = out ? selfName : isGroup() ? "" : peerName;
        }
        if (reply != null) {
            mo.customReplyName = quote.getOutgoing() ? selfName : quoteAuthor(quote);
        }
        return mo;
    }

    private String quoteAuthor(QuotePreview quote) {
        if (isGroup()) {
            final Long qid = quote.getMessageId();
            final String who = qid != null && senderLookup != null ? senderLookup.nameOf(qid) : null;
            return who != null && !who.isEmpty() ? who : quoteFallback;
        }
        return peerName;
    }

    /** The quoted message of a reply, also text-only. Layout is generated by the cell, not here. */
    private MessageObject mapQuote(QuotePreview quote) {
        final TLRPC.TL_message msg = baseMessage(quote.getOutgoing(), 0);
        final Long qid = quote.getMessageId();
        msg.id = qid != null ? localId(qid) : 0;
        final String text = quote.getText();
        if ((text == null || text.isEmpty()) && quote.getMediaKind() != null) {
            msg.message = MediaText.kind(context, quote.getMediaKind());   // a reply to a photo without a caption: "Photo"
        } else {
            msg.message = text == null || text.isEmpty() ? quoteFallback : text;
        }
        msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
        return new SecuredMessageObject(account, msg, null, false);
    }

    private static TLRPC.TL_message baseMessage(boolean out, int date) {
        final TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.out = out;
        msg.date = date;
        msg.dialog_id = SENTINEL_ID;
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = SENTINEL_ID;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = SENTINEL_ID;
        msg.media = new TLRPC.TL_messageMediaEmpty();
        msg.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        msg.unread = false;
        return msg;
    }

    /** True when both messages draw the same bubble apart from the delivery status (reactions, canEdit, history, unread are not drawn by it). */
    public static boolean sameBubble(SecuredMessage a, SecuredMessage b) {
        return a == b || (a.getMessageId() == b.getMessageId() && a.getChatId() == b.getChatId()
                && a.getOutgoing() == b.getOutgoing() && a.getTimeMs() == b.getTimeMs()
                && a.getEdited() == b.getEdited() && a.getDeleted() == b.getDeleted()
                && a.getForwarded() == b.getForwarded() && a.getMentionsMe() == b.getMentionsMe()
                && java.util.Objects.equals(a.getText(), b.getText())
                && java.util.Objects.equals(a.getReplyTo(), b.getReplyTo())
                && java.util.Objects.equals(a.getExpiresAtMs(), b.getExpiresAtMs())
                && java.util.Objects.equals(a.getSender(), b.getSender())
                && java.util.Objects.equals(a.getEvent(), b.getEvent())
                && java.util.Objects.equals(a.getMedia(), b.getMedia())
                && java.util.Objects.equals(a.getMentions(), b.getMentions()));
    }

    /** A status-only change: patches the send state of an already mapped object, with no new layout. Main thread only. */
    public void applyStatus(MessageObject mo, SecuredMessage m) {
        if (mo.messageOwner instanceof TLRPC.TL_message) applyStatus((TLRPC.TL_message) mo.messageOwner, m);
    }

    /** Honest status: see the class comment. The cell draws ticks only from these four fields. */
    private void applyStatus(TLRPC.TL_message msg, SecuredMessage m) {
        final int lid = localId(m.getMessageId());
        if (!m.getOutgoing()) {
            msg.id = lid;
            msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
            msg.unread = false;
            return;
        }
        final MessageStatus status = m.getStatus();
        if (status == MessageStatus.SENDING) {
            msg.id = -lid;
            msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
            msg.unread = true;
        } else if (status == MessageStatus.FAILED) {
            msg.id = -lid;
            msg.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
            msg.unread = true;
        } else if (status == MessageStatus.DELIVERED) {
            msg.id = lid;
            msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
            msg.unread = false; // two ticks = delivered (Secured has no read receipts)
        } else {
            // SENT_TO_SERVER, or anything we do not know for an outgoing message: claim no more than one tick.
            msg.id = lid;
            msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
            msg.unread = true;
        }
    }

    /** Group mentions (RD2): bold, never Telegram's mention entity, and never over a link. */
    private static void addMentionEntities(String body, int from, java.util.Map<String, Long> mentions, ArrayList<TLRPC.MessageEntity> out) {
        if (mentions == null || mentions.isEmpty()) return;
        final int urls = out.size();
        for (int[] r : MentionText.ranges(body, from, mentions.keySet())) {
            boolean overlaps = false;
            for (int i = 0; i < urls; i++) {
                final TLRPC.MessageEntity e = out.get(i);
                if (r[0] < e.offset + e.length && e.offset < r[1]) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;
            final TLRPC.TL_messageEntityBold b = new TLRPC.TL_messageEntityBold();
            b.offset = r[0];
            b.length = r[1] - r[0];
            out.add(b);
        }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(a.offset, b.offset));
    }

    private static TLRPC.TL_messageEntityItalic italic(int offset, int length) {
        final TLRPC.TL_messageEntityItalic e = new TLRPC.TL_messageEntityItalic();
        e.offset = offset;
        e.length = length;
        return e;
    }

    /** Plain URLs only, found on the device by Android's Linkify. No mentions, hashtags, commands or emails. */
    private static void addUrlEntities(String body, int from, ArrayList<TLRPC.MessageEntity> out) {
        try {
            final SpannableString s = new SpannableString(body);
            if (!Linkify.addLinks(s, Linkify.WEB_URLS)) {
                return;
            }
            for (URLSpan span : s.getSpans(0, s.length(), URLSpan.class)) {
                final int start = s.getSpanStart(span);
                final int end = s.getSpanEnd(span);
                if (start < from || end <= start) continue;
                final TLRPC.TL_messageEntityUrl e = new TLRPC.TL_messageEntityUrl();
                e.offset = start;
                e.length = end - start;
                out.add(e);
            }
        } catch (Throwable ignore) {
            // Fail closed: no link spans at all.
        }
    }

    // ---- service rows -----------------------------------------------------------------------------------------

    /** Our own wording for a group service row ("X joined"). Plain text; shown through ChatActionCell.setCustomText. */
    public String eventText(GroupEvent e) {
        return eventText(context, kind, e);
    }

    /** The same wording for the chat list's preview when the newest item is a service row. */
    static String eventText(Context context, ChatKind kind, GroupEvent e) {
        final boolean channel = kind == ChatKind.CHANNEL;
        final String who = e.getMemberName();
        final String group = e.getGroupName();
        final boolean grouped = group != null && !group.isEmpty();
        final boolean named = who != null && !who.isEmpty();
        switch (e.getKind()) {
            case CREATED:
                return context.getString(channel ? R.string.secgroup_event_created_channel : R.string.secgroup_event_created_group);
            case INVITED:
                if (grouped) {
                    return named ? context.getString(R.string.secgroup_event_invited_to, who, group) : context.getString(R.string.secgroup_event_invited_you_to, group);
                }
                return named ? context.getString(R.string.secgroup_event_invited, who) : context.getString(R.string.secgroup_event_invited_you);
            case JOINED:
                return named ? context.getString(R.string.secgroup_event_joined, who) : context.getString(R.string.secgroup_event_joined_you);
            case JOINED_BY_LINK:
                return named ? context.getString(R.string.secgroup_event_joined_via_link, who) : context.getString(R.string.secgroup_event_joined_via_link_you);
            case LEFT:
                return named ? context.getString(R.string.secgroup_event_left, who) : context.getString(R.string.secgroup_event_left_you);
            case REMOVED:
                return named ? context.getString(R.string.secgroup_event_removed, who) : context.getString(R.string.secgroup_event_removed_unknown);
            case YOU_REMOVED:
                return context.getString(channel ? R.string.secgroup_event_you_removed_channel : R.string.secgroup_event_you_removed_group);
            case ROLE_CHANGED: {
                final String role = roleWithArticle(context, e.getRole());
                return named ? context.getString(R.string.secgroup_event_role, who, role) : context.getString(R.string.secgroup_event_role_you, role);
            }
            case GROUP_DELETED:
                return context.getString(channel ? R.string.secgroup_event_deleted_channel : R.string.secgroup_event_deleted_group);
            case GROUP_UPDATED:
                return context.getString(channel ? R.string.secgroup_event_updated_channel : R.string.secgroup_event_updated_group);
            case DELETED_BY_ADMIN:
                return context.getString(R.string.secgroup_event_deleted_by_admin);
            default:
                return "";
        }
    }

    private static String roleWithArticle(Context context, MemberRole role) {
        if (role == null) return context.getString(R.string.secgroup_role_member_a);
        switch (role) {
            case OWNER:
                return context.getString(R.string.secgroup_role_owner_a);
            case ADMIN:
                return context.getString(R.string.secgroup_role_admin_a);
            case MODERATOR:
                return context.getString(R.string.secgroup_role_moderator_a);
            case OBSERVER:
                return context.getString(R.string.secgroup_role_observer_a);
            default:
                return context.getString(R.string.secgroup_role_member_a);
        }
    }

    // ---- the restricted MessageObject -------------------------------------------------------------------------

    /**
     * Telegram's MessageObject with every automatic Telegram lookup this shape could reach switched off.
     * Overrides read only {@code messageOwner}: they also run inside MessageObject's constructor, before this
     * class's own fields would be set.
     */
    public static final class SecuredMessageObject extends MessageObject {

        SecuredMessageObject(int account, TLRPC.Message message, MessageObject reply, boolean generateLayout) {
            super(account, message, reply, generateLayout, false);
        }

        /** Single emoji stay big text: no MediaDataController.getEmojiAnimatedSticker, no sticker download. */
        @Override
        protected void checkBigAnimatedEmoji() {
            emojiAnimatedSticker = null;
            emojiAnimatedStickerId = null;
            generateLayout(null);
        }

        /** Two or three emoji stay local emoji bitmaps: no Telegram animated-emoji documents. */
        @Override
        public void replaceEmojiToLottieFrame(CharSequence text, int[] emojiOnly) {
        }

        /**
         * 1:1 chat: no avatar next to bubbles (an avatar would resolve the sender through MessagesController).
         * Group chat: only when the mapper attached our own local avatar drawable (no lookup is made for it).
         */
        @Override
        public boolean needDrawAvatar() {
            return customAvatarDrawable != null && messageOwner != null && !messageOwner.out;
        }

        /** No share button: its click path is Telegram's ShareAlert. */
        @Override
        public boolean needDrawShareButton() {
            return false;
        }

        /** Direction comes from the engine only, never from Telegram's self id or a cache the cell resets. */
        @Override
        public boolean isOutOwner() {
            return messageOwner != null && messageOwner.out;
        }

        /**
         * Every text pass of Telegram's {@code applyEntities} ends here, after {@code addLinks} and
         * {@code addUrlsByPattern} and before {@code generateLayout} builds the layout from the same text. That covers
         * construction, re-layout ({@code checkLayout} on resize or font change), {@code applyNewText} and
         * {@code setQuery}. Strip Telegram's link spans here so neither the drawn text nor a tap can reach them.
         */
        @Override
        public boolean addEntitiesToText(CharSequence text, boolean photoViewer, boolean useManualParse) {
            final boolean hasUrls = super.addEntitiesToText(text, photoViewer, useManualParse);
            LinkSpans.strip(text);
            return hasUrls;
        }

        /**
         * Backstop in case an upstream change adds link spans outside {@link #addEntitiesToText}: strip again from the
         * text taps read ({@code messageText}) and the text each layout block draws. It counts what it had to remove,
         * so {@link SecuredMessageMapper#selfCheck} notices when the hook above stops running.
         */
        @Override
        public void generateLayout(TLRPC.User fromUser) {
            super.generateLayout(fromUser);
            int removed = LinkSpans.strip(messageText);
            if (textLayoutBlocks != null) {
                for (int i = 0; i < textLayoutBlocks.size(); i++) {
                    final MessageObject.TextLayoutBlock block = textLayoutBlocks.get(i);
                    if (block != null && block.textLayout != null) {
                        removed += LinkSpans.strip(block.textLayout.getText());
                    }
                }
            }
            backstopRemovals += removed;
        }
    }

    /** Spans the {@code generateLayout} backstop removed because the {@code addEntitiesToText} hook missed them. Main thread. */
    private static int backstopRemovals;

    // ---- link spans -------------------------------------------------------------------------------------------

    /**
     * Keeps Telegram's own link spans out of Secured bubbles (research/gaps-audit.md, L4). Runs in every build.
     * <p>
     * Why: for a pending or failed outgoing bubble (negative id, send state not "sent") Telegram's
     * {@code MessageObject.applyEntities} ignores our entities and re-parses the text ({@code useManualParse}):
     * {@code addLinks} adds plain {@link URLSpan}s for http, https, tg and tonsite addresses, and
     * {@code addUrlsByPattern} adds {@code URLSpanNoUnderline} for @mention, #hashtag and $cashtag and
     * {@code URLSpanBotCommand} for /command. Their own {@code onClick} opens t.me through Telegram's Browser.
     * <p>
     * Rule (an allowlist, so a new upstream span type is refused too): a span that is not clickable (emoji, our italic
     * labels, colours) stays. A clickable span stays only when its class is exactly Android's {@link URLSpan}
     * (Telegram's re-parse) or Telegram's {@link URLSpanBrowser} (our URL entities) and its address is http or https;
     * SecuredChatActivity asks "Open link?" before either opens. Everything else is removed, including
     * {@link URLSpanMono} (tap to copy) and {@link AnimatedEmojiSpan} (Telegram documents). The text itself is never
     * changed and never logged.
     */
    static final class LinkSpans {

        private LinkSpans() {
        }

        /** Whether a span of this class, pointing at this address, may stay in a Secured bubble. Pure. */
        static boolean allowed(Class<?> type, String url) {
            if (type == null) return false;
            if (AnimatedEmojiSpan.class.isAssignableFrom(type) || URLSpanMono.class.isAssignableFrom(type)) return false;
            if (!ClickableSpan.class.isAssignableFrom(type)) return true;
            return (type == URLSpan.class || type == URLSpanBrowser.class) && isWebUrl(url);
        }

        static boolean isWebUrl(String url) {
            return url != null && (url.regionMatches(true, 0, "https://", 0, 8) || url.regionMatches(true, 0, "http://", 0, 7));
        }

        /** The spans of {@code text} that {@link #allowed} refuses; empty for clean or plain text. Never throws. */
        static List<Object> refused(CharSequence text) {
            final ArrayList<Object> out = new ArrayList<>();
            if (!(text instanceof Spanned)) return out;
            try {
                final Spanned s = (Spanned) text;
                for (Object span : s.getSpans(0, s.length(), Object.class)) {
                    if (span != null && !allowedSpan(span)) out.add(span);
                }
            } catch (Throwable ignore) {
                // Unreadable text: nothing to report. The chat screen's didPressUrl still refuses non-web links.
            }
            return out;
        }

        /** Removes the refused spans in place. Returns how many were removed. Never throws, never logs. */
        static int strip(CharSequence text) {
            if (!(text instanceof Spannable)) return 0;
            final Spannable s = (Spannable) text;
            int removed = 0;
            for (Object span : refused(text)) {
                try {
                    s.removeSpan(span);
                    removed++;
                } catch (Throwable ignore) {
                    // Keep going: one bad span must not leave the others in place.
                }
            }
            return removed;
        }

        private static boolean allowedSpan(Object span) {
            try {
                return allowed(span.getClass(), span instanceof URLSpan ? ((URLSpan) span).getURL() : null);
            } catch (Throwable t) {
                return false; // cannot inspect it: remove it
            }
        }
    }

    // ---- self-check -------------------------------------------------------------------------------------------

    /**
     * Builds sample Secured messages and checks the shape rules above. Returns the list of failures (empty when all
     * pass). Needs an Android context (Telegram's MessageObject uses Theme resources), so it runs on a device or
     * emulator, on the main thread, for example from a debug menu: {@code SecuredMessageMapper.selfCheck(ctx, account)}.
     * Debug builds only (SecuredUi.debugSelfCheck): it builds sample objects and reloads Theme chat resources. The
     * protection it checks, {@link LinkSpans}, runs in every build and fails safe on its own (strip and continue).
     * Run it after every upstream merge: the L4 block fails if Telegram's text parse changed under the hook.
     */
    public static List<String> selfCheck(Context context, int account) {
        final ArrayList<String> failures = new ArrayList<>();
        try {
            Theme.createChatResources(context, false);
            final int backstopBefore = backstopRemovals;
            final SecuredMessageMapper mapper = new SecuredMessageMapper(context, account);
            mapper.setNames("Peer", "Me");
            final String canary = "secured-canary-7f3a";
            final List<ReactionCount> reactions = Collections.singletonList(new ReactionCount("❤", 2, true));
            final QuotePreview quote = new QuotePreview(1L, "quoted " + canary, true);

            // Sentinel properties.
            check(failures, SENTINEL_ID > (1L << 52), "sentinel above 2^52");
            check(failures, DialogObject.isUserDialog(SENTINEL_ID), "sentinel is a plain user dialog");
            check(failures, !DialogObject.isEncryptedDialog(SENTINEL_ID), "sentinel is not a secret chat");
            check(failures, !DialogObject.isFolderDialogId(SENTINEL_ID), "sentinel is not a folder");
            check(failures, SENTINEL_ID != UserConfig.getInstance(account).getClientUserId(), "sentinel is not the Telegram self id");
            check(failures, SENTINEL_ID != UserObject.REPLY_BOT && SENTINEL_ID != UserObject.VERIFY
                    && SENTINEL_ID != UserObject.ANONYMOUS && SENTINEL_ID != 777000L, "sentinel is not a Telegram service id");

            final SecuredMessage delivered = sample(1, true, "hello " + canary + " https://example.com/x", MessageStatus.DELIVERED, null, false, false, reactions);
            final SecuredMessage server = sample(2, true, "one tick", MessageStatus.SENT_TO_SERVER, null, false, false, reactions);
            final SecuredMessage sending = sample(3, true, LINK_SAMPLE, MessageStatus.SENDING, null, false, false, reactions);
            final SecuredMessage failed = sample(4, true, "not sent", MessageStatus.FAILED, null, false, false, reactions);
            final SecuredMessage emoji1 = sample(5, false, "😀", MessageStatus.RECEIVED, null, false, false, reactions);
            final SecuredMessage emoji2 = sample(6, false, "😀😂", MessageStatus.RECEIVED, null, false, false, reactions);
            final SecuredMessage reply = sample(7, false, "a reply", MessageStatus.RECEIVED, quote, false, false, reactions);
            final SecuredMessage forwarded = sample(8, false, "forwarded body", MessageStatus.RECEIVED, null, true, false, reactions);
            // A deleted message that (wrongly) still carries text, a quote and the forwarded flag: none may show.
            final SecuredMessage deleted = sample(9, true, canary, MessageStatus.DELIVERED, quote, true, true, reactions);

            final SecuredMessage[] all = {delivered, server, sending, failed, emoji1, emoji2, reply, forwarded, deleted};
            final MessageObject[] mapped = new MessageObject[all.length];
            for (int i = 0; i < all.length; i++) {
                mapped[i] = mapper.map(all[i]);
                checkShape(failures, "msg" + all[i].getMessageId(), mapped[i], all[i].getOutgoing());
                if (mapped[i].replyMessageObject != null) {
                    checkShape(failures, "reply of msg" + all[i].getMessageId(), mapped[i].replyMessageObject, quote.getOutgoing());
                }
            }

            check(failures, mapped[0].isSent() && !mapped[0].isUnread() && !mapped[0].isSending(), "delivered = two ticks");
            check(failures, mapped[1].isSent() && mapped[1].isUnread(), "sent to server = one tick");
            check(failures, mapped[2].isSending() && !mapped[2].isSent(), "sending = clock");
            check(failures, mapped[3].isSendError(), "failed = error");
            check(failures, !mapped[4].isOutOwner() && mapped[4].isSent(), "incoming is not outgoing");
            check(failures, mapped[6].replyMessageObject != null && "Me".equals(mapped[6].customReplyName), "reply keeps a text quote with our name");
            check(failures, mapped[7].messageOwner.message.startsWith(context.getString(R.string.secchat_forwarded) + "\n")
                    && mapped[7].messageOwner.fwd_from == null, "forwarded is a plain label, no fwd_from");

            final MessageObject d = mapped[8];
            check(failures, context.getString(R.string.secchat_deleted).equals(d.messageOwner.message), "deleted shows only the label");
            check(failures, !d.messageOwner.message.contains(canary) && (d.messageText == null || !d.messageText.toString().contains(canary)),
                    "deleted message has no old text");
            check(failures, d.replyMessageObject == null, "deleted message has no quote");
            check(failures, !mapped[0].messageOwner.message.isEmpty() && hasUrlEntity(mapped[0]), "plain URL becomes a URL entity");

            // L4: pending and failed bubbles make Telegram re-parse the text; sent and incoming ones use our entities.
            // In every state, and after a re-layout, only the https link may stay clickable.
            final MessageStatus[] states = {MessageStatus.SENDING, MessageStatus.FAILED, MessageStatus.SENT_TO_SERVER,
                    MessageStatus.DELIVERED, MessageStatus.RECEIVED};
            for (int i = 0; i < states.length; i++) {
                final boolean outgoing = states[i] != MessageStatus.RECEIVED;
                final MessageObject lm = mapper.map(sample(20 + i, outgoing, LINK_SAMPLE, states[i], null, false, false, reactions));
                final String label = "links " + states[i];
                checkShape(failures, label, lm, outgoing);
                checkOnlyWebLink(failures, label, lm);
                lm.generateLayout(null); // what checkLayout does on a resize or font-size change
                checkOnlyWebLink(failures, label + " after re-layout", lm);
            }
            check(failures, backstopRemovals == backstopBefore, "link spans are removed in addEntitiesToText (backstop unused)");

            // Group bubble: member name, colour and avatar fields only; the sentinel stays the only peer id.
            final SecuredMessageMapper group = new SecuredMessageMapper(context, account);
            group.setKind(ChatKind.GROUP);
            final SecuredMessage gm = new SecuredMessage(GROUP_CHAT_ID_BASE_FOR_CHECK, 1, false, "hi " + canary, 1_700_000_000_000L,
                    MessageStatus.RECEIVED, null, false, false, false, false, false, Collections.emptyList(), null,
                    new MessageSender(7L, "Alice", MemberRole.ADMIN), null);
            final MessageObject g = group.map(gm);
            checkShape(failures, "group msg", g, false, true);
            check(failures, "Alice".equals(g.customName) && g.overrideLinkColor == AvatarDrawable.getColorIndex(Kit.colorKey("Alice"))
                    && g.customAvatarDrawable != null && g.needDrawAvatar(), "group bubble: name, colour, avatar");
            check(failures, g.messageOwner.from_id.user_id == SENTINEL_ID, "group bubble: sentinel sender");
        } catch (Throwable t) {
            failures.add("self-check crashed: " + t.getClass().getSimpleName());
        }
        return failures;
    }

    private static final long GROUP_CHAT_ID_BASE_FOR_CHECK = app.encrogram.core.api.SecuredEngineKt.GROUP_CHAT_ID_BASE + 1;

    /** Everything Telegram's parse links (mention, hashtag, cashtag, bot command) plus one real web link. */
    static final String LINK_SAMPLE = "@durov #tag $USD /start https://example.com";
    static final String LINK_SAMPLE_URL = "https://example.com";

    /** The L4 sample keeps exactly one clickable span: the https link, of a kind our "Open link?" flow handles. */
    private static void checkOnlyWebLink(List<String> failures, String label, MessageObject mo) {
        if (!(mo.messageText instanceof Spanned)) {
            failures.add(label + ": no link span (text not spanned)");
            return;
        }
        final Spanned s = (Spanned) mo.messageText;
        final ClickableSpan[] links = s.getSpans(0, s.length(), ClickableSpan.class);
        final String url = links.length == 1 && links[0] instanceof URLSpan ? ((URLSpan) links[0]).getURL() : null;
        check(failures, url != null && LINK_SAMPLE_URL.equals(url) && LinkSpans.allowed(links[0].getClass(), url),
                label + ": only the https link is clickable");
    }

    /** No refused span in the text taps read or in the text any layout block draws. */
    private static void checkNoTelegramSpans(List<String> failures, String label, MessageObject mo) {
        check(failures, LinkSpans.refused(mo.messageText).isEmpty(), label + ": no Telegram link spans");
        if (mo.textLayoutBlocks == null) return;
        for (int i = 0; i < mo.textLayoutBlocks.size(); i++) {
            final MessageObject.TextLayoutBlock b = mo.textLayoutBlocks.get(i);
            check(failures, b == null || b.textLayout == null || LinkSpans.refused(b.textLayout.getText()).isEmpty(),
                    label + ": no Telegram link spans in the drawn text");
        }
    }

    private static boolean hasUrlEntity(MessageObject mo) {
        for (TLRPC.MessageEntity e : mo.messageOwner.entities) {
            if (e instanceof TLRPC.TL_messageEntityUrl) return true;
        }
        return false;
    }

    private static SecuredMessage sample(long id, boolean out, String text, MessageStatus status, QuotePreview quote,
                                         boolean forwarded, boolean deleted, List<ReactionCount> reactions) {
        return new SecuredMessage(42L, id, out, text, 1_700_000_000_000L + id * 1000L, status, quote,
                true, deleted, out, out, forwarded, reactions, null);
    }

    private static void checkShape(List<String> failures, String label, MessageObject mo, boolean out) {
        checkShape(failures, label, mo, out, false);
    }

    private static void checkShape(List<String> failures, String label, MessageObject mo, boolean out, boolean group) {
        final TLRPC.Message o = mo.messageOwner;
        check(failures, mo instanceof SecuredMessageObject, label + ": restricted subclass");
        check(failures, o != null && o.getClass() == TLRPC.TL_message.class, label + ": plain TL_message");
        if (o == null) return;
        check(failures, o.media instanceof TLRPC.TL_messageMediaEmpty && o.media.webpage == null
                && o.media.document == null && o.media.photo == null, label + ": no media, no webpage");
        check(failures, o.reactions == null, label + ": no Telegram reactions");
        check(failures, o.fwd_from == null && o.reply_to == null && o.reply_markup == null && o.replies == null
                && o.via_bot_id == 0 && o.grouped_id == 0 && o.ttl == 0 && o.effect == 0 && o.rich_message == null
                && o.summary_from_language == null && o.restriction_reason.isEmpty(), label + ": no Telegram-only fields");
        check(failures, (o.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) == 0, label + ": no views (no views request)");
        for (TLRPC.MessageEntity e : o.entities) {
            check(failures, e instanceof TLRPC.TL_messageEntityUrl || e instanceof TLRPC.TL_messageEntityItalic
                            || e instanceof TLRPC.TL_messageEntityBold,
                    label + ": only URL, italic and bold (mention) entities");
        }
        if (mo.messageText instanceof Spanned) {
            final Spanned s = (Spanned) mo.messageText;
            check(failures, s.getSpans(0, s.length(), AnimatedEmojiSpan.class).length == 0, label + ": no custom or animated emoji");
        }
        checkNoTelegramSpans(failures, label, mo);
        check(failures, mo.emojiAnimatedSticker == null && mo.emojiAnimatedStickerId == null, label + ": no emoji sticker");
        check(failures, mo.getDocument() == null && (mo.photoThumbs == null || mo.photoThumbs.isEmpty()), label + ": no document or thumbs");
        check(failures, mo.type == MessageObject.TYPE_TEXT || mo.type == MessageObject.TYPE_EMOJIS, label + ": text type");
        check(failures, mo.getDialogId() == SENTINEL_ID, label + ": sentinel dialog id");
        check(failures, o.peer_id != null && o.peer_id.user_id == SENTINEL_ID && o.from_id != null
                && o.from_id.user_id == SENTINEL_ID, label + ": sentinel peer and sender");
        check(failures, !mo.isSaved && (group || !mo.needDrawAvatar()) && !mo.needDrawShareButton(), label + ": no saved, avatar or share");
        check(failures, mo.isOutOwner() == out, label + ": direction from the engine");
    }

    private static void check(List<String> failures, boolean ok, String what) {
        if (!ok) failures.add(what);
    }
}
