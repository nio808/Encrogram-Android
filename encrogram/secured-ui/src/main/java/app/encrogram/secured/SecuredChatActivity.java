package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.ChatMessageSharedResources;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ChatUnreadCell;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextSelectionHelper;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.ChatActivityEnterViewAnimatedIconView;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.TimerDrawable;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.recyclerview.ChatListItemAnimator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import app.encrogram.core.background.SecuredBackground;
import app.encrogram.core.api.ChatAction;
import app.encrogram.core.api.ChatKind;
import app.encrogram.core.api.MemberRole;
import app.encrogram.core.api.MessageSender;
import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.GroupMember;
import app.encrogram.core.api.ItemAction;
import app.encrogram.core.api.LinkCheck;
import app.encrogram.core.api.MediaKind;
import app.encrogram.core.api.MediaState;
import app.encrogram.core.api.QuotePreview;
import app.encrogram.core.api.SecuredMedia;
import app.encrogram.core.api.MessagePage;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.ReactionCount;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredEngineKt;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredMentions;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredProfile;
import app.encrogram.core.api.SecuredResult;

/**
 * A Secured conversation on Telegram's real chat parts, following the ChannelAdminLogActivity pattern
 * (research/android-ui-reuse.md section 3.3). It is deliberately not a ChatActivity.
 * <p>
 * Reuse levels (1 = Telegram class as-is, 2 = Telegram class restricted or configured by us, 3 = Telegram logic
 * copied and adapted, 4 = our own):
 * <ul>
 * <li>bubbles: {@link ChatMessageCell} fed only by {@link SecuredMessageMapper}, every click decided by our delegate (2);</li>
 * <li>date pills and floating date: {@link ChatActionCell#setCustomDate} only (1); floating-date logic (3);</li>
 * <li>list animations: {@link ChatListItemAnimator} with a null ChatActivity (1);</li>
 * <li>header: {@link ChatAvatarContainer} with a null fragment, our title, subtitle, letter avatar and lock (2);</li>
 * <li>wallpaper: {@link SizeNotifierFrameLayout} with the already-cached Telegram wallpaper, never loaded by us (1);</li>
 * <li>menu: Telegram's {@link ItemOptions} popup (1) with our 8-emoji reaction row (4);</li>
 * <li>composer: {@link SecuredComposerEditText} (EditTextCaption) and ChatActivityEnterView.SendButton (2), reply strip (4);</li>
 * <li>swipe to reply: simplified from ChatActivity (3); reactions under bubbles: {@link SecuredReactionsView} (4).</li>
 * </ul>
 * Every action goes to {@link SecuredEngine} only. No MessagesController, MessagesStorage, ConnectionsManager,
 * SendMessagesHelper, FileLoader, ImageLoader, NotificationsController, FileLog, MediaDataController or
 * Browser.openUrl calls are made from here.
 */
public class SecuredChatActivity extends BaseFragment implements SecuredScreen {

    private static final int VIEW_MESSAGE = 0;
    private static final int VIEW_DATE = 1;
    private static final int VIEW_REACTIONS = 2;
    private static final int VIEW_EVENT = 3;
    private static final int VIEW_PHOTO = 4;   // photo, GIF, video (SecuredPhotoCell)
    private static final int VIEW_FILE = 5;
    private static final int VIEW_VOICE = 6;
    private static final int VIEW_UNREAD = 7;

    private static final int ROW_DATE = 0;
    private static final int ROW_MESSAGE = 1;
    private static final int ROW_REACTIONS = 2;
    private static final int ROW_EVENT = 3;   // group service row ("X joined")
    private static final int ROW_UNREAD = 5;  // Telegram's "Unread messages" bar above the first unread message
    private static final int ROW_MEDIA = 4;   // the media bubble of a message; its caption is a joined ROW_MESSAGE

    private static final int PAGE_SIZE = 50;
    private static final int GROUP_SECONDS = 5 * 60;
    private static final long DATE_ID_BASE = 0x4000_0000_0000_0000L;
    private static final long UNREAD_ROW_ID = DATE_ID_BASE - 1;
    /** The first page grows to hold this many unread messages at most, so the chat can open at the first of them. */
    private static final int MAX_UNREAD_PAGE = 200;

    private static final int MENU_COPY = 10;
    private static final int MENU_FORWARD = 11;
    private static final int MENU_DELETE = 12;
    private static final int MENU_REPLY = 13;
    private static final int MENU_MUTE = 20;
    private static final int MENU_DISAPPEAR = 21;
    private static final int MENU_VERIFY = 22;
    private static final int MENU_CLEAR = 23;
    private static final int MENU_DELETE_CHAT = 24;
    private static final long SEARCH_DEBOUNCE_MS = 300;

    private final long chatId;
    /** Saved Messages: the user's own notes (text only, on this phone). No ticks, replies, reactions or verification. */
    private final boolean notes;
    /** Group or channel (chat id at or above GROUP_CHAT_ID_BASE). Contact-only features stay off for these. */
    private final boolean groupChat;
    private FlagSecureReason flagSecure;
    private final SecuredChatResources chatResources = new SecuredChatResources();
    private SecuredEngine engine;
    private boolean destroyed;   // isPaused is BaseFragment's, also set in onResume/onPause below

    private SecuredMessageMapper mapper;
    private final ArrayList<SecuredMessage> messages = new ArrayList<>();   // oldest first
    private final ArrayList<Row> rows = new ArrayList<>();
    private static final int OBJECT_CACHE_CAP = 600;
    private final HashMap<Long, MessageObject> objects = new HashMap<>();
    private final HashMap<Long, SecuredMessage> objectSources = new HashMap<>();
    private final LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();

    private boolean initialLoaded;
    private boolean loadingOlder;
    private boolean endReached;
    private long olderFailedAt;
    // AR10: first-load spinner, retry after an error, spinner while older messages load.
    private RadialProgressView loadingView;
    private RadialProgressView olderSpinner;
    private boolean loadFailed;
    // AR13: more search pages may exist; the count pill.
    private boolean searchMore;
    private boolean searchLoadingMore;
    private TextView searchCount;
    // AR9: the reply target of a restored draft, applied once the first page is in.
    private Long draftReplyId;
    // AR12: the header's lock and timer icons.
    private Drawable titleLock;
    private Integer shownTtl;

    private SecuredMessage replyingTo;
    private SecuredMessage editing;
    private long highlightId = Long.MIN_VALUE;

    // Window position: after a jump to an old message (search, quote) newer messages are loaded on scroll.
    private boolean hasNewer;
    private boolean loadingNewer;
    private long newerFailedAt;
    private int epoch;   // bumped whenever the loaded window is replaced, so late page results are dropped
    private int unreadBelow;

    // In-chat search: results replace the list while the search field is open. Local only, never logged.
    private boolean searching;
    private boolean searchDone;
    private int searchSeq;
    private String searchText = "";
    private Long pendingJump;
    private final ArrayList<SecuredMessage> searchResults = new ArrayList<>();
    private final Runnable runSearch = this::runSearch;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuSubItem muteSubItem;
    private View composerView;
    private FrameLayout invitedBar;
    private FrameLayout cantPostBar;
    private FrameLayout deleteBar;
    private TextView invitedTitle;
    private TextView cantPostText;
    private TextView joinButton;
    private TextView declineButton;
    private boolean joining;
    private ChatKind shownKind;
    private TextView jumpCounter;

    private SizeNotifierFrameLayout root;
    private ChatAvatarContainer avatarContainer;
    private AvatarDrawable avatarDrawable;
    private MessagesListView listView;
    private LinearLayoutManager layoutManager;
    private MessagesAdapter adapter;
    private ChatMessageSharedResources sharedResources;
    private ChatActionCell floatingDateView;
    private boolean floatingDateShown;
    private boolean scrollingByUser;
    private FrameLayout jumpButton;
    private boolean jumpShown;
    private TextView emptyView;
    private FrameLayout replyStrip;
    private ImageView replyIcon;
    private TextView replyTitle;
    private TextView replyText;
    private SecuredComposerEditText input;
    private ChatActivityEnterView.SendButton sendButton;
    private boolean sendShown;
    private NumberTextView selectedCountView;
    private ActionBarMenuItem copyItem;
    private ActionBarMenuItem forwardItem;
    private ActionBarMenuItem replyItem;

    private String name = "";
    private String selfNameShown;

    // Media (docs/tasks.md, Media phase 2). Bitmaps live in memory only; one voice player per screen.
    private MediaBitmaps bitmaps;
    private SecuredAudioPlayer audio;
    private SecuredAttach attach;
    private ImageView attachButton;
    // Emoji panel (MU11): our own panel under the composer, swapped with the keyboard.
    private ChatActivityEnterViewAnimatedIconView emojiButton;
    private FrameLayout emojiContainer;
    private SecuredEmojiPanel emojiPanel;
    private boolean emojiShown;
    private boolean hidePanelWhenKeyboardOpens;
    private int keyboardHeightPort, keyboardHeightLand;
    /** When the panel asked for the keyboard: size reports from the keyboard that was still closing are older. */
    private long keyboardRequestedAt;
    // Voice messages (MU10): the mic shares the send button's place while the box is empty.
    private ImageView micButton;
    private SecuredVoiceOverlay voiceOverlay;
    private SecuredVoiceRecorder voice;
    // Group mentions (RD2): the "@" picker, the names inserted in the composer, and mentions of you to jump to.
    private SecuredMentionPicker mentionPicker;
    private final LinkedHashMap<String, Long> mentionMap = new LinkedHashMap<>();
    private int mentionStart = -1;
    private boolean insertingMention;
    private FrameLayout mentionButton;
    private TextView mentionCounter;
    private final ArrayList<Long> unreadMentions = new ArrayList<>();
    // Jump to unread: how many were unread when the chat opened, and the message the "Unread messages" bar sits above.
    private int unreadAtOpen;
    private long unreadDividerBefore = Long.MIN_VALUE;
    /** Live progress from onMediaProgress, so a cell scrolled back into view shows it too. Cleared on each change. */
    private final HashMap<Long, long[]> liveProgress = new HashMap<>();

    private final Runnable hideFloatingDate = () -> showFloatingDate(false);

    private boolean occupyStatusBar = !AndroidUtilities.isTablet();

    /** Called when this chat opens in the tablet's right-hand pane (forceRightLayout). */
    SecuredChatActivity inRightPane() {
        occupyStatusBar = true;
        return this;
    }

    public SecuredChatActivity(long chatId) {
        super();
        this.chatId = chatId;
        this.notes = chatId == SecuredEngineKt.SAVED_MESSAGES_CHAT_ID;
        this.groupChat = SecuredEngineKt.isGroupChatId(chatId);
        chatResources.setHideTicks(notes);
    }

    // ---- engine events ---------------------------------------------------------------------------------------

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onStateChanged(EngineState state) {
            updateHeader();
        }

        @Override
        public void onChatsChanged() {
            updateHeader();
        }

        @Override
        public void onConnectionChanged(boolean online) {
            updateHeader();
        }

        @Override
        public void onMessageReceived(SecuredMessage message) {
            if (message.getChatId() != chatId || destroyed) return;
            if (!message.getOutgoing() && !searching && indexOf(message.getMessageId()) < 0 && (hasNewer || !isAtBottom())) {
                unreadBelow++;
            }
            upsertMessage(message, true);
            if (message.getMentionsMe() && !message.getOutgoing()) onMentionArrived(message.getMessageId());
            if (!message.getOutgoing() && engine != null && isVisibleToUser()) {
                engine.markRead(chatId);
            }
        }

        @Override
        public void onMessageStatusChanged(long cid, long messageId, MessageStatus status) {
            if (cid != chatId || destroyed) return;
            final int idx = indexOf(messageId);
            if (idx < 0) return;
            final SecuredMessage updated = withStatus(messages.get(idx), status);
            messages.set(idx, updated);
            // A tick changes one bubble: rebind that row only (no rebuild, no diff of every loaded row).
            // ENCROGRAM perf (H2/P2)
            if (!patchRowMessage(messageId, updated)) rebuildRows(true);
        }

        @Override
        public void onMessageChanged(SecuredMessage message) {
            if (message.getChatId() != chatId || destroyed) return;
            // A new media state carries its own byte count: forget the live one.
            final long id = message.getMessageId();
            liveProgress.remove(id);
            final int idx = indexOf(id);
            if (idx >= 0 && messages.get(idx).getMedia() != null && (message.getDeleted() || message.getMedia() == null)) {
                // Deleted for everyone (or its file withdrawn): close what still shows or plays it.
                SecuredMediaViewer.closeFor(chatId, java.util.Collections.singletonList(id));
                if (bitmaps != null) bitmaps.forget(chatId, id);
                if (audio != null && audio.isCurrent(chatId, id)) audio.release();
            }
            if (message.getDeleted() && unreadMentions.remove(Long.valueOf(id))) updateMentionButton();
            upsertMessage(message, false);
        }

        @Override
        public void onMessagesRemoved(long cid, List<Long> messageIds) {
            if (cid != chatId || destroyed) return;
            removeMessages(messageIds);
        }

        @Override
        public void onMediaProgress(long cid, long messageId, long transferredBytes, long totalBytes) {
            if (cid != chatId || destroyed) return;
            // Redraws the one visible cell; the list is not rebuilt per step (contract: onMediaProgress).
            liveProgress.put(messageId, new long[]{transferredBytes, totalBytes});
            final SecuredMediaCell cell = mediaCellFor(messageId);
            if (cell != null) cell.setProgress(transferredBytes, totalBytes);
        }

        @Override
        public void onError(SecuredError error) {
        }
    };

    private boolean isVisibleToUser() {
        return fragmentView != null && !isPaused;
    }

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return chatResources;
    }

    @Override
    public boolean onFragmentCreate() {
        engine = SecuredUi.engine();
        if (engine == null) {
            return false;
        }
        engine.addListener(listener);
        NotificationCenter.getGlobalInstance().addObserver(emojiLoaded, NotificationCenter.emojiLoaded);
        return super.onFragmentCreate();
    }

    // Telegram's bundled emoji images load lazily and announce it with this local event (no data, no network):
    // redraw the open screen and the reaction picker, as ChatActivity does, or they stay grey placeholders.
    private View reactionPicker;
    private final NotificationCenter.NotificationCenterDelegate emojiLoaded = (id, account, args) -> {
        if (id != NotificationCenter.emojiLoaded) return;
        invalidateTree(fragmentView);
        invalidateTree(reactionPicker);
    };

    private static void invalidateTree(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    /** Low memory: forget the built bubbles; visible cells keep theirs, the rest are rebuilt when bound (H2). */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        objects.clear();
        objectSources.clear();
        if (bitmaps != null) bitmaps.clear();
    }

    @Override
    public void onFragmentDestroy() {
        saveDraft();
        destroyed = true;
        SecuredDialogsActivity.chatClosed(chatId);   // tablet: the list stops highlighting this chat
        // Media: stop the player and the viewer (both release their decrypted playback), drop prepared attachments
        // and the in-memory bitmaps.
        SecuredMediaViewer.closeAll();
        if (audio != null) audio.release();
        if (attach != null) attach.destroy();
        if (bitmaps != null) bitmaps.clear();
        if (flagSecure != null) flagSecure.detach();
        AndroidUtilities.cancelRunOnUIThread(hideFloatingDate);
        AndroidUtilities.cancelRunOnUIThread(runSearch);
        AndroidUtilities.cancelRunOnUIThread(panelFallback);
        if (emojiPanel != null) emojiPanel.dismissPopups();
        if (voice != null) voice.destroy();
        NotificationCenter.getGlobalInstance().removeObserver(emojiLoaded, NotificationCenter.emojiLoaded);
        if (engine != null) {
            engine.removeListener(listener);
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        Kit.reassertSecure(getParentActivity());
    }

    // FLAG_SECURE stays through onPause (Home, Recents snapshot); dropped only when covered or gone, like ChatActivity.
    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (flagSecure != null) flagSecure.detach();
    }

    // ---- view ------------------------------------------------------------------------------------------------

    @Override
    public View createView(Context context) {
        // Telegram rebuilds a fragment's view on some theme changes: the old recorder and panel belong to the old view.
        saveDraft();   // AR9: the old box is about to go
        if (voice != null) voice.destroy();
        voice = null;
        voiceOverlay = null;
        micButton = null;
        emojiPanel = null;
        emojiShown = false;
        hidePanelWhenKeyboardOpens = false;
        Theme.createChatResources(context, false);
        hasOwnBackground = true;
        sharedResources = new ChatMessageSharedResources(context);
        final ChatSummary summary = summary();
        unreadAtOpen = summary != null && !notes ? Math.max(0, summary.getUnreadCount()) : 0;
        unreadDividerBefore = Long.MIN_VALUE;
        name = notes ? tg(org.telegram.messenger.R.string.SavedMessages) : summary != null ? summary.getDisplayName() : "";
        selfNameShown = selfName();
        mapper = new SecuredMessageMapper(context, currentAccount);
        if (bitmaps == null) bitmaps = new MediaBitmaps(engine);
        if (attach == null) attach = new SecuredAttach(attachHost);
        mapper.setNames(name, selfNameShown);
        mapper.setAvatarResources(chatResources);
        mapper.setSenderLookup(this::senderNameOf);
        shownKind = summary != null ? summary.getKind() : groupChat ? ChatKind.GROUP : ChatKind.DIRECT;
        mapper.setKind(shownKind);

        buildHeader(context);

        root = new SizeNotifierFrameLayout(context) {
            @Override
            protected Drawable getNewDrawable() {
                return safeWallpaper();
            }

            @Override
            protected boolean getNewDrawableMotion() {
                return false;
            }

            // This root starts below the header (not edge-to-edge), so the wallpaper must not skip a header height again.
            @Override
            protected boolean isActionBarVisible() {
                return false;
            }

            @Override
            protected boolean isStatusBarVisible() {
                return false;
            }

            @Override
            protected boolean useRootView() {
                return false;
            }
        };
        final Drawable wallpaper = safeWallpaper();
        if (wallpaper != null) {
            root.setBackgroundImage(wallpaper, false);
        } else {
            root.setBackgroundColor(SecuredResourcesProvider.chatBackground());
        }
        fragmentView = root;

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final FrameLayout listFrame = new FrameLayout(context);
        buildList(context, listFrame);
        if (groupChat) {
            mentionPicker = new SecuredMentionPicker(context, SecuredResourcesProvider.get(), this::insertMention);
            listFrame.addView(mentionPicker, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM, 8, 0, 8, 4));
            loadMembers();
        }
        column.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        column.addView(buildReplyStrip(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 8, 4, 8, 0));
        composerView = buildComposer(context);
        column.addView(composerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (groupChat) {
            invitedBar = buildInvitedBar(context);
            cantPostBar = buildCantPostBar(context);
            column.addView(invitedBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            column.addView(cantPostBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            deleteBar = buildDeleteBar(context);
            column.addView(deleteBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        emojiContainer = new FrameLayout(context);
        emojiContainer.setVisibility(View.GONE);
        column.addView(emojiContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0));
        root.setDelegate(this::onKeyboardSize);
        if (!notes) {
            // Saved Messages takes text only in v1, so it has no recorder.
            voice = new SecuredVoiceRecorder(voiceHost);
            voiceOverlay = new SecuredVoiceOverlay(context, SecuredResourcesProvider.get(), voice);
            root.addView(voiceOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        restoreSavedDraft();
        loadMessages();
        return root;
    }

    /** The user's already-cached Telegram wallpaper, or null. Never starts a load; never a per-chat (downloaded) one. */
    private static Drawable safeWallpaper() {
        final Drawable d = Theme.getCachedWallpaperNonBlocking();
        return d instanceof ChatBackgroundDrawable ? null : d;
    }

    private void buildHeader(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        // Like ChatActivity: under the status bar on phones and in the tablet's right-hand pane (edge-to-edge there);
        // not in the tablet's centred floating window (logged out), which has no status bar above it.
        actionBar.setOccupyStatusBar(occupyStatusBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!selectedIds.isEmpty()) {
                        clearSelection();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_COPY) {
                    copyMessages(selectedMessages());
                    clearSelection();
                } else if (id == MENU_REPLY) {
                    final List<SecuredMessage> one = selectedMessages();
                    clearSelection();
                    if (one.size() == 1) startReply(one.get(0));
                } else if (id == MENU_FORWARD) {
                    pickForwardTarget(selectedMessages());
                } else if (id == MENU_DELETE) {
                    confirmDelete(selectedMessages());
                } else if (id == MENU_MUTE) {
                    toggleMute();
                } else if (id == MENU_DISAPPEAR) {
                    openDisappearing();
                } else if (id == MENU_VERIFY) {
                    openVerify();
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                } else if (id == MENU_DELETE_CHAT) {
                    confirmDeleteChat();
                }
            }
        });

        // Telegram's header with a null fragment: no profile opening, no stories, no Telegram connection observers.
        avatarContainer = new ChatAvatarContainer(context, null, false, chatResources) {
            @Override
            public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                // The title's second icon is our timer, which Telegram's container words as "Notifications muted".
                final CharSequence d = info.getContentDescription();
                if (d != null && shownTtl != null) {
                    info.setContentDescription(d.toString().replace(
                            org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.NotificationsMuted),
                            getContext().getString(R.string.secchat_disappear)));
                }
            }
        };
        avatarContainer.setOccupyStatusBar(occupyStatusBar);
        avatarContainer.setEnabled(false);
        // Right margin leaves room for the two header buttons (search, menu: 2 x 48dp), so taps on them reach them.
        actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 56, 0, 100, 0));
        avatarDrawable = new AvatarDrawable(chatResources);
        titleLock = new LockMarkDrawable();
        shownTtl = null;
        if (groupChat) {
            // A group has no single security code to compare: the header opens group info instead.
            avatarContainer.setTitleIcons(titleLock, null);
            avatarContainer.setEnabled(true);
            avatarContainer.setOnClickListener(v -> openGroupInfo());
        } else if (!notes) {
            avatarContainer.setTitleIcons(titleLock, null);
            // Tapping the header opens contact info (security code, mute, timer, delete), like Telegram's profile.
            avatarContainer.setEnabled(true);
            avatarContainer.setOnClickListener(v -> SecuredContactInfoActivity.open(this, chatId));
        } else {
            avatarContainer.setEnabled(false);
        }

        final ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(0, org.telegram.messenger.R.drawable.outline_header_search, chatResources)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        startSearch();
                    }

                    @Override
                    public void onSearchCollapse() {
                        endSearch();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        scheduleSearch(editText.getText().toString());
                    }
                });
        searchItem.setSearchFieldHint(context.getString(R.string.secchat_search_hint));
        // Telegram's search field lets the keyboard learn; Secured search text must not be learned.
        if (searchItem.getSearchField() != null) {
            searchItem.getSearchField().setImeOptions(searchItem.getSearchField().getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        searchItem.setContentDescription(tg(org.telegram.messenger.R.string.Search));
        final ActionBarMenuItem more = menu.addItem(0, org.telegram.messenger.R.drawable.ic_ab_other, chatResources);
        Kit.securePopup(more.getPopupLayout());
        more.setContentDescription(tg(org.telegram.messenger.R.string.AccDescrMoreOptions));
        if (!notes) {
            muteSubItem = more.addSubItem(MENU_MUTE, org.telegram.messenger.R.drawable.msg_mute, context.getString(R.string.sec_list_mute), chatResources);
            if (!groupChat) {
                // Contact-only for now (docs/groups-plan.md): groups have no single peer to verify or time out.
                more.addSubItem(MENU_DISAPPEAR, org.telegram.messenger.R.drawable.msg_autodelete, context.getString(R.string.secchat_disappear), chatResources);
                more.addSubItem(MENU_VERIFY, org.telegram.messenger.R.drawable.msg_secret, context.getString(R.string.secchat_verify_menu), chatResources);
            }
        }
        more.addSubItem(MENU_CLEAR, org.telegram.messenger.R.drawable.msg_clear, context.getString(R.string.secchat_clear_title), chatResources);
        if (!notes) {
            final ActionBarMenuSubItem deleteChat = more.addSubItem(MENU_DELETE_CHAT, org.telegram.messenger.R.drawable.msg_delete, context.getString(groupChat ? deleteMenuTitle() : R.string.sec_list_delete_title), chatResources);
            final int red = chatResources.getColor(Theme.key_text_RedBold);
            deleteChat.setColors(red, red);
        }

        final ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountView = new NumberTextView(actionMode.getContext());
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(chatResources.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 65, 0, 0, 0));
        replyItem = actionMode.addItemWithWidth(MENU_REPLY, org.telegram.messenger.R.drawable.menu_reply, dp(54), tg(org.telegram.messenger.R.string.Reply));
        copyItem = actionMode.addItemWithWidth(MENU_COPY, org.telegram.messenger.R.drawable.msg_copy, dp(54), tg(org.telegram.messenger.R.string.Copy));
        forwardItem = actionMode.addItemWithWidth(MENU_FORWARD, org.telegram.messenger.R.drawable.msg_forward, dp(54), tg(org.telegram.messenger.R.string.Forward));
        actionMode.addItemWithWidth(MENU_DELETE, org.telegram.messenger.R.drawable.msg_delete, dp(54), tg(org.telegram.messenger.R.string.Delete));

        updateHeader();
        updateBottomBars();
    }

    private void buildList(Context context, FrameLayout listFrame) {
        listView = new MessagesListView(context);
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        // Weak phones skip the move animations, like Telegram's own lists do (H2).
        listView.setItemAnimator(EncrogramChatsHost.lowEnd() ? null : new ChatListItemAnimator(null, listView, chatResources));
        listView.setLayoutAnimation(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        listView.setVerticalScrollBarEnabled(true);
        listView.setSelectorDrawableColor(0);
        // Secured text is never offered to autofill services.
        listView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        adapter = new MessagesAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new RecyclerListView.OnItemClickListenerExtended() {
            @Override
            public void onItemClick(View view, int position, float x, float y) {
                if (view instanceof SecuredReactionsView) {
                    // A tap on a reaction chip (not while selecting or searching): toggles our own reaction.
                    if (selectedIds.isEmpty() && !searching) ((SecuredReactionsView) view).onTap(x, y);
                    return;
                }
                final SecuredMessage m = view instanceof ChatMessageCell || view instanceof SecuredMediaCell ? messageAt(position) : null;
                if (m != null && searching) {
                    openMatch(m);
                } else if (m != null && !selectedIds.isEmpty()) {
                    toggleSelection(m);
                } else if (view instanceof SecuredMediaCell) {
                    onMediaTap((SecuredMediaCell) view, x, y);
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (!(view instanceof ChatMessageCell) && !(view instanceof SecuredMediaCell)) return false;
            onMessageLongPress(view);
            return true;
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    scrollingByUser = true;
                    AndroidUtilities.cancelRunOnUIThread(hideFloatingDate);
                    updateFloatingDate();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scrollingByUser = false;
                    AndroidUtilities.cancelRunOnUIThread(hideFloatingDate);
                    AndroidUtilities.runOnUIThread(hideFloatingDate, 900);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateVisibleParts();
                updateFloatingDate();
                updateJumpButton(true);
                updateMentionButton();
                checkLoadOlder();
                checkLoadNewer();
            }
        });
        listFrame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        floatingDateView = new ChatActionCell(context, false, chatResources);
        floatingDateView.setAlpha(0f);
        floatingDateView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listFrame.addView(floatingDateView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

        emptyView = Kit.text(context, context.getString((notes ? R.string.secnotes_empty : R.string.sec_chat_empty)), 14, chatResources.getColor(Theme.key_chat_serviceText), false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(12), dp(6), dp(12), dp(6));
        emptyView.setBackground(Kit.roundRect(chatResources.getColor(Theme.key_chat_serviceBackground), 14));
        emptyView.setVisibility(View.GONE);
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        // AR10: spinner until the first page arrives, a small one while older messages load.
        loadingView = new RadialProgressView(context, chatResources);
        loadingView.setSize(dp(28));
        loadingView.setVisibility(View.GONE);
        listFrame.addView(loadingView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        olderSpinner = new RadialProgressView(context, chatResources);
        olderSpinner.setSize(dp(20));
        olderSpinner.setVisibility(View.GONE);
        olderSpinner.setContentDescription(context.getString(R.string.sec_au_older_loading));
        listFrame.addView(olderSpinner, LayoutHelper.createFrame(28, 28, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        // AR13: "N found" while searching.
        searchCount = Kit.text(context, "", 13, chatResources.getColor(Theme.key_chat_serviceText), false);
        searchCount.setGravity(Gravity.CENTER);
        searchCount.setPadding(dp(12), dp(5), dp(12), dp(5));
        searchCount.setBackground(Kit.roundRect(chatResources.getColor(Theme.key_chat_serviceBackground), 14));
        searchCount.setVisibility(View.GONE);
        listFrame.addView(searchCount, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        emptyView.setOnClickListener(v -> {
            if (loadFailed) loadMessages();   // "Tap to retry"
        });

        jumpButton = new FrameLayout(context);
        jumpButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(42), chatResources.getColor(Theme.key_chat_goDownButton), chatResources.getColor(Theme.key_listSelector)));
        final ImageView jumpIcon = new ImageView(context);
        jumpIcon.setScaleType(ImageView.ScaleType.CENTER);
        jumpIcon.setImageResource(org.telegram.messenger.R.drawable.pagedown);
        jumpIcon.setColorFilter(new PorterDuffColorFilter(chatResources.getColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
        jumpButton.addView(jumpIcon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        jumpButton.setContentDescription(tg(org.telegram.messenger.R.string.AccDescrPageDown));
        jumpButton.setOnClickListener(v -> scrollToBottom(true));
        jumpButton.setVisibility(View.INVISIBLE);
        jumpButton.setAlpha(0f);
        listFrame.setClipChildren(false);
        listFrame.addView(jumpButton, LayoutHelper.createFrame(42, 42, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));

        // Unread counter of Telegram's go-down button (colours from the same theme keys).
        jumpCounter = Kit.text(context, "", 13, chatResources.getColor(Theme.key_chat_goDownButtonCounter), true);
        jumpCounter.setGravity(Gravity.CENTER);
        jumpCounter.setMinWidth(dp(20));
        jumpCounter.setPadding(dp(6), 0, dp(6), 0);
        jumpCounter.setBackground(Kit.roundRect(chatResources.getColor(Theme.key_chat_goDownButtonCounterBackground), 10));
        jumpCounter.setVisibility(View.GONE);
        jumpButton.setClipChildren(false);
        jumpButton.addView(jumpCounter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, -10, 0, 0));

        // Telegram's "@" button above the go-down button: jumps to the next message that mentions you.
        mentionButton = new FrameLayout(context);
        mentionButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(42), chatResources.getColor(Theme.key_chat_goDownButton), chatResources.getColor(Theme.key_listSelector)));
        final ImageView mentionIcon = new ImageView(context);
        mentionIcon.setScaleType(ImageView.ScaleType.CENTER);
        mentionIcon.setImageResource(org.telegram.messenger.R.drawable.mentionbutton);
        mentionIcon.setColorFilter(new PorterDuffColorFilter(chatResources.getColor(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
        mentionButton.addView(mentionIcon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        mentionButton.setContentDescription(context.getString(R.string.secmention_next));
        mentionButton.setOnClickListener(v -> jumpToMention());
        mentionButton.setVisibility(View.GONE);
        mentionButton.setClipChildren(false);
        mentionCounter = Kit.text(context, "", 13, chatResources.getColor(Theme.key_chat_goDownButtonCounter), true);
        mentionCounter.setGravity(Gravity.CENTER);
        mentionCounter.setMinWidth(dp(20));
        mentionCounter.setPadding(dp(6), 0, dp(6), 0);
        mentionCounter.setBackground(Kit.roundRect(chatResources.getColor(Theme.key_chat_goDownButtonCounterBackground), 10));
        mentionButton.addView(mentionCounter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, -10, 0, 0));
        listFrame.addView(mentionButton, LayoutHelper.createFrame(42, 42, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));
    }

    // ---- header ----------------------------------------------------------------------------------------------

    private ChatSummary summary() {
        if (engine == null) return null;
        try {
            for (ChatSummary c : engine.chats()) {
                if (c.getChatId() == chatId) return c;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private String selfName() {
        try {
            final SecuredProfile p = engine != null ? engine.profile() : null;
            return p != null ? p.getDisplayName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private int deleteMenuTitle() {
        final boolean channel = isOwnerOrChannel(false);
        if (isOwnerOrChannel(true)) return channel ? R.string.secgroup_delete_title_channel : R.string.secgroup_delete_title;
        return channel ? R.string.secgroup_leave_title_channel : R.string.secgroup_leave_title;
    }

    private boolean isOwnerOrChannel(boolean owner) {
        final ChatSummary s = summary();
        if (owner) return s != null && s.getMyRole() == MemberRole.OWNER;
        return s != null && s.getKind() == ChatKind.CHANNEL;
    }

    /** "N members" or "N subscribers"; the kind word while the count is unknown. No verification line for groups. */
    private CharSequence groupSubtitle(Context c, ChatSummary s) {
        final boolean channel = s != null && s.getKind() == ChatKind.CHANNEL;
        if (s != null && s.getMemberCount() != null) {
            final int n = s.getMemberCount();
            return c.getResources().getQuantityString(channel ? R.plurals.secgroup_subscribers_count : R.plurals.secgroup_members_count, n, n);
        }
        return c.getString(channel ? R.string.secgroup_subtitle_channel : R.string.secgroup_subtitle_group);
    }

    private String senderNameOf(long messageId) {
        final int i = indexOf(messageId);
        if (i < 0) return null;
        final MessageSender sender = messages.get(i).getSender();
        return sender != null ? sender.getDisplayName() : null;
    }

    /** Header tap on a group or channel (not while an invitation is pending: you are not a member yet). */
    private void openGroupInfo() {
        if (destroyed || !groupChat || isInvited() || !selectedIds.isEmpty() || searching) return;
        presentFragment(new SecuredGroupInfoActivity(chatId));
    }

    private boolean isInvited() {
        final ChatSummary s = summary();
        return groupChat && s != null && s.getInvited();
    }

    /** Observers (a channel's subscribers) and uninvited members cannot post; direct chats always can. */
    private boolean canPostHere() {
        if (!groupChat) return true;
        final ChatSummary s = summary();
        return s == null || (s.getCanPost() && !s.getInvited());
    }

    // ---- bars that replace the composer (groups) -------------------------------------------------------------

    /** Copied in small from ChatActivity's bottom overlay (private there): panel colour, one text, 48dp. */
    private FrameLayout buildBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        bar.setBackgroundColor(Kit.color(Theme.key_chat_messagePanelBackground));
        bar.setVisibility(View.GONE);
        return bar;
    }

    private FrameLayout buildCantPostBar(Context context) {
        final FrameLayout bar = buildBar(context);
        cantPostText = Kit.text(context, "", 15, Kit.color(Theme.key_chat_fieldOverlayText), false);
        cantPostText.setGravity(Gravity.CENTER);
        bar.addView(cantPostText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 0, 16, 0));
        return bar;
    }

    /** Telegram's "DELETE CHAT" bottom button for a chat you were removed from or left (its own DeleteChat string). */
    private FrameLayout buildDeleteBar(Context context) {
        final FrameLayout bar = buildBar(context);
        final TextView t = Kit.text(context, tg(org.telegram.messenger.R.string.DeleteChatUser).toUpperCase(), 15,
                Kit.color(Theme.key_chat_fieldOverlayText), true);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> confirmDeleteChat());
        bar.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER));
        return bar;
    }

    /** True when you were removed, left, or the group is gone: you can only delete the chat (channel observers are not this). */
    private boolean isRemovedOrLeft() {
        final ChatSummary s = summary();
        if (!groupChat || s == null || s.getInvited() || !s.getGone()) return false;
        return true;
    }

    private FrameLayout buildInvitedBar(Context context) {
        final FrameLayout bar = buildBar(context);
        final LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        invitedTitle = Kit.text(context, "", 15, chatResources.getColor(Theme.key_chat_messagePanelText), true);
        invitedTitle.setGravity(Gravity.CENTER);
        col.addView(invitedTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 4));
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        declineButton = Kit.text(context, context.getString(R.string.secgroup_decline), 15, SecuredResourcesProvider.accentText(), true);
        declineButton.setGravity(Gravity.CENTER);
        declineButton.setOnClickListener(v -> declineInvite());
        joinButton = Kit.text(context, context.getString(R.string.secgroup_join), 15, 0xFFFFFFFF, true);
        joinButton.setGravity(Gravity.CENTER);
        joinButton.setBackground(Kit.roundRect(SecuredResourcesProvider.accentFill(), 20));
        joinButton.setOnClickListener(v -> acceptInvite());
        buttons.addView(declineButton, LayoutHelper.createLinear(0, 40, 1f, 0, 0, 8, 0));
        buttons.addView(joinButton, LayoutHelper.createLinear(0, 40, 1f, 8, 0, 0, 0));
        col.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 12));
        bar.addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return bar;
    }

    /** Shows exactly one of: the composer, the invitation bar, or the can't-post notice. Hidden during search. */
    private void updateBottomBars() {
        if (composerView == null || !groupChat) return;
        final Context c = composerView.getContext();
        final boolean invited = isInvited();
        final boolean canPost = canPostHere();
        final boolean channel = isOwnerOrChannel(false);
        if (invitedTitle != null) {
            invitedTitle.setText(c.getString(channel ? R.string.secgroup_invited_bar_channel : R.string.secgroup_invited_bar));
        }
        if (cantPostText != null) {
            // Not ready yet (members still connecting): say that, not "only admins can post".
            cantPostText.setText(!chatReady() ? c.getString(R.string.secgroup_connecting_bar)
                    : c.getString(channel ? R.string.secgroup_cant_post_channel : R.string.secgroup_cant_post_group));
        }
        if (joinButton != null) joinButton.setAlpha(joining ? 0.5f : 1f);
        composerView.setVisibility(!searching && canPost ? View.VISIBLE : View.GONE);
        if (composerView.getVisibility() != View.VISIBLE) {
            hideEmojiPanel();
            hideMentions();
        }
        if (invitedBar != null) invitedBar.setVisibility(!searching && invited ? View.VISIBLE : View.GONE);
        final boolean removed = isRemovedOrLeft();
        if (cantPostBar != null) cantPostBar.setVisibility(!searching && !canPost && !invited && !removed ? View.VISIBLE : View.GONE);
        if (deleteBar != null) deleteBar.setVisibility(!searching && removed ? View.VISIBLE : View.GONE);
        if (!canPost && replyStrip != null) cancelStrip();
    }

    private void acceptInvite() {
        if (joining) return;
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        joining = true;
        updateBottomBars();
        try {
            engine.joinGroup(chatId, result -> {
                joining = false;
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) showError(((SecuredResult.Err) result).getError());
                updateHeader();   // the chat list event also arrives; this covers a missed one
            });
        } catch (Throwable t) {
            joining = false;
            updateBottomBars();
            showError(null);
        }
    }

    /** Decline = leave and delete on this phone, then close the chat (the contract: chatAction Delete). */
    private void declineInvite() {
        if (joining) return;
        chatAction(new ChatAction.Delete(false), this::finishFragment);
    }

    private boolean chatReady() {
        final ChatSummary s = summary();
        return s == null || s.getReady();
    }

    private void updateHeader() {
        if (avatarContainer == null || destroyed) return;
        final ChatSummary s = summary();
        final Context c = avatarContainer.getContext();
        final String newName = notes ? tg(org.telegram.messenger.R.string.SavedMessages) : s != null ? s.getDisplayName() : name;
        final String newSelf = selfName();
        if (!TextUtils.equals(newName, name) || !TextUtils.equals(newSelf, selfNameShown)) {
            name = newName;
            selfNameShown = newSelf;
            if (mapper != null) {
                mapper.setNames(name, selfNameShown);
                objects.clear();
                objectSources.clear();
                if (adapter != null) adapter.notifyItemRangeChanged(0, rows.size());
            }
        }
        avatarContainer.setTitle(name);
        // AvatarDrawable takes the initials from first and last name: split at the last space ("Night Test" -> NT).
        if (notes) {
            // Telegram's own Saved Messages avatar.
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
        } else {
            Kit.setAvatar(avatarDrawable, ChatRowMapper.colorId(chatId), name);
        }
        avatarContainer.getAvatarImageView().setImageDrawable(avatarDrawable);

        // No last seen, no presence: the subtitle states only what the engine reports.
        final EngineState state = engine != null ? engine.state() : EngineState.FAILED;
        final CharSequence sub;
        if (notes) {
            // Notes never touch a relay, so the engine state does not matter for them.
            sub = c.getString(R.string.secnotes_subtitle);
        } else if (state == EngineState.FAILED) {
            sub = c.getString(R.string.sec_engine_failed_title);
        } else if (state != EngineState.RUNNING) {
            sub = c.getString(R.string.secchat_connecting);
        } else if (!engine.isOnline()) {
            sub = tg(org.telegram.messenger.R.string.WaitingForNetwork);   // Telegram's own header string
        } else if (groupChat) {
            sub = groupSubtitle(c, s);
        } else if (s != null && !s.getReady()) {
            sub = c.getString(R.string.sec_chat_status_waiting, name);
        } else if (s != null && s.getVerified()) {
            sub = c.getString(R.string.sec_chat_status_verified);
        } else {
            sub = c.getString(R.string.sec_chat_status_not_verified);
        }
        avatarContainer.setSubtitle(sub);
        updateTimerIcon(s);
        if (groupChat && s != null && s.getKind() != shownKind && mapper != null) {
            // The kind arrives with the chat list: re-word service rows and re-measure names and avatars.
            shownKind = s.getKind();
            mapper.setKind(shownKind);
            objects.clear();
            objectSources.clear();
            if (adapter != null) adapter.notifyItemRangeChanged(0, rows.size());
        }
        updateBottomBars();
        if (muteSubItem != null && s != null) {
            muteSubItem.setTextAndIcon(c.getString(s.getMuted() ? R.string.sec_list_unmute : R.string.sec_list_mute),
                    s.getMuted() ? org.telegram.messenger.R.drawable.msg_unmute : org.telegram.messenger.R.drawable.msg_mute);
        }
    }

    /** AR12: Telegram's timer icon after the title while the chat has a disappearing timer. */
    private void updateTimerIcon(ChatSummary s) {
        if (avatarContainer == null || notes || titleLock == null) return;
        final Integer ttl = s != null ? s.getDisappearingTtlSeconds() : null;
        if (java.util.Objects.equals(ttl, shownTtl)) return;
        shownTtl = ttl;
        Drawable timer = null;
        if (ttl != null && ttl > 0) {
            final TimerDrawable t = new TimerDrawable(avatarContainer.getContext(), chatResources);
            t.setTime(ttl);
            timer = t;
        }
        avatarContainer.setTitleIcons(titleLock, timer);
    }

    // ---- composer --------------------------------------------------------------------------------------------

    private View buildReplyStrip(Context context) {
        replyStrip = new FrameLayout(context);
        replyStrip.setBackground(Kit.roundRect(chatResources.getColor(Theme.key_chat_messagePanelBackground), 18));
        replyStrip.setVisibility(View.GONE);

        replyIcon = new ImageView(context);
        replyIcon.setScaleType(ImageView.ScaleType.CENTER);
        replyIcon.setColorFilter(new PorterDuffColorFilter(SecuredResourcesProvider.accentText(), PorterDuff.Mode.SRC_IN));
        replyStrip.addView(replyIcon, LayoutHelper.createFrame(44, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 2, 0, 0, 0));

        final View line = new View(context);
        line.setBackground(Kit.roundRect(SecuredResourcesProvider.accentFill(), 1));
        replyStrip.addView(line, LayoutHelper.createFrame(2, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 48, 0, 0, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        replyTitle = Kit.text(context, "", 14, SecuredResourcesProvider.accentText(), true);
        replyTitle.setSingleLine(true);
        replyTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(replyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        replyText = Kit.text(context, "", 14, chatResources.getColor(Theme.key_chat_messagePanelHint), false);
        replyText.setSingleLine(true);
        replyText.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(replyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        replyStrip.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 58, 0, 46, 0));

        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(org.telegram.messenger.R.drawable.input_clear);
        close.setColorFilter(new PorterDuffColorFilter(chatResources.getColor(Theme.key_chat_replyPanelClose), PorterDuff.Mode.SRC_IN));
        close.setContentDescription(tg(org.telegram.messenger.R.string.Cancel));
        close.setOnClickListener(v -> cancelStrip());
        replyStrip.addView(close, LayoutHelper.createFrame(44, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        return replyStrip;
    }

    private View buildComposer(Context context) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(8), dp(6), dp(8), dp(8));

        final FrameLayout field = new FrameLayout(context);
        field.setBackground(Kit.roundRect(Kit.color(Theme.key_chat_messagePanelBackground), 22));
        input = new SecuredComposerEditText(context, SecuredResourcesProvider.get()) {
            @Override
            protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                // Moving the cursor away from "@name" closes the member list (a TextWatcher sees only edits).
                if (input == this && mentionPicker != null && mentionStart >= 0) checkMention(getText());
            }
        };
        input.setHint(context.getString(notes ? R.string.secnotes_hint : R.string.sec_composer_hint));
        input.setHintTextColor(Kit.color(Theme.key_chat_messagePanelHint));
        input.setTextColor(Kit.color(Theme.key_chat_messagePanelText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setMaxLines(6);
        input.setGravity(Gravity.BOTTOM);
        input.setBackgroundDrawable(null);
        input.setPadding(dp(16), dp(11), dp(16), dp(11));
        input.setCursorColor(SecuredResourcesProvider.accentFill());
        input.setCursorSize(dp(20));
        input.setCursorWidth(1.5f);
        input.setHandlesColor(SecuredResourcesProvider.accentFill());
        field.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        // Emoji (MU11): Telegram's smile/keyboard icon on the left of the field, as in Telegram's composer.
        emojiButton = new ChatActivityEnterViewAnimatedIconView(context);
        emojiButton.setScaleType(ImageView.ScaleType.CENTER);
        emojiButton.setColorFilter(new PorterDuffColorFilter(Kit.color(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
        emojiButton.setBackground(new InsetDrawable(Theme.createSelectorDrawable(chatResources.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP), 0, 0, 0, dp(4)));   // AR12: 48 dp hit area, same look
        emojiButton.setPadding(0, 0, 0, dp(4));
        emojiButton.setState(ChatActivityEnterViewAnimatedIconView.State.SMILE, false);
        emojiButton.setContentDescription(context.getString(R.string.secemoji_button));
        emojiButton.setOnClickListener(v -> toggleEmoji());
        field.addView(emojiButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.BOTTOM, 2, 0, 0, 0));
        input.setPadding(dp(50), dp(11), dp(16), dp(11));
        input.setOnTouchListener((v, e) -> {
            // A tap in the box while the panel is up brings the keyboard back (the panel leaves when it is open).
            if (e.getAction() == MotionEvent.ACTION_UP && emojiShown) openKeyboardFromEmoji();
            return false;
        });
        if (!notes) {
            // Attach (Media phase 2): Telegram's paperclip icon, our sheet (system photo and file pickers, no camera).
            // Saved Messages takes text only in v1 (engine: NOT_ALLOWED), so it has no paperclip.
            input.setPadding(dp(50), dp(11), dp(48), dp(11));
            attachButton = new ImageView(context);
            attachButton.setScaleType(ImageView.ScaleType.CENTER);
            attachButton.setImageResource(org.telegram.messenger.R.drawable.msg_input_attach2);
            attachButton.setColorFilter(new PorterDuffColorFilter(Kit.color(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
            attachButton.setContentDescription(context.getString(R.string.secmedia_attach));
            attachButton.setBackground(new InsetDrawable(Theme.createSelectorDrawable(chatResources.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP), 0, 0, 0, dp(4)));   // AR12
            attachButton.setPadding(0, 0, 0, dp(4));
            attachButton.setOnClickListener(v -> openAttach());
            field.addView(attachButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.BOTTOM));
        }
        row.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sendButton = new ChatActivityEnterView.SendButton(context, org.telegram.messenger.R.drawable.send_plane_24, SecuredResourcesProvider.get(), true) {
            @Override
            public boolean shouldDrawBackground() {
                return true;
            }
        };
        sendButton.setContentDescription(context.getString(R.string.sec_send));
        sendButton.setVisibility(View.GONE);
        sendButton.setOnClickListener(v -> send());
        row.addView(sendButton, LayoutHelper.createLinear(52, 44, Gravity.BOTTOM, 0, 0, 0, 0));
        if (!notes) {
            micButton = new ImageView(context);
            micButton.setScaleType(ImageView.ScaleType.CENTER);
            micButton.setImageResource(org.telegram.messenger.R.drawable.input_mic);
            micButton.setColorFilter(new PorterDuffColorFilter(Kit.color(Theme.key_chat_messagePanelIcons), PorterDuff.Mode.SRC_IN));
            micButton.setBackground(Theme.createSelectorDrawable(chatResources.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            micButton.setContentDescription(context.getString(R.string.secvoice_record));
            micButton.setOnTouchListener((v, e) -> voice != null && voice.onMicTouch(v, e));
            row.addView(micButton, LayoutHelper.createLinear(52, 44, Gravity.BOTTOM, 0, 0, 0, 0));
        }

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSendVisibility(s.toString().trim().length() > 0);
                updateMicVisibility();
                checkMention(s);
            }
        });
        return row;
    }

    /** The mic shows where Send would be: an empty box, not while editing, never in Saved Messages. */
    private void updateMicVisibility() {
        if (micButton == null) return;
        final boolean show = !sendShown && editing == null;
        micButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateSendVisibility(boolean show) {
        if (show == sendShown) return;
        sendShown = show;
        if (show) {
            sendButton.setVisibility(View.VISIBLE);
            sendButton.setAlpha(0f);
            sendButton.appear();
            sendButton.animate().alpha(1f).setDuration(140).start();
        } else {
            sendButton.animate().cancel();
            sendButton.setVisibility(View.GONE);
        }
    }

    private void showStrip(int iconRes, CharSequence title, String text) {
        if (replyStrip == null) return;
        replyIcon.setImageResource(iconRes);
        replyTitle.setText(title);
        final String oneLine = text == null ? "" : text.replace('\n', ' ');
        replyText.setText(Emoji.replaceEmoji(oneLine, replyText.getPaint().getFontMetricsInt(), false));
        replyStrip.setVisibility(View.VISIBLE);
    }

    private void cancelStrip() {
        final boolean wasEditing = editing != null;
        replyingTo = null;
        editing = null;
        updateMicVisibility();
        if (replyStrip != null) replyStrip.setVisibility(View.GONE);
        if (wasEditing && input != null) input.setText("");
    }

    private void startReply(SecuredMessage m) {
        if (m == null || m.getDeleted() || input == null) return;
        if (editing != null) {
            editing = null;
            input.setText("");
        }
        replyingTo = m;
        final String who = m.getOutgoing() ? mapper.selfName() : m.getSender() != null ? m.getSender().getDisplayName() : name;
        showStrip(org.telegram.messenger.R.drawable.input_reply, input.getContext().getString(R.string.secchat_reply_to, who), previewText(m));
        openKeyboard();
    }

    private void startEdit(SecuredMessage m) {
        if (m == null || m.getDeleted() || !m.getCanEdit() || input == null) return;
        replyingTo = null;
        editing = m;
        updateMicVisibility();
        showStrip(org.telegram.messenger.R.drawable.msg_edit, tg(org.telegram.messenger.R.string.EditMessage), m.getText());
        input.setText(m.getText());
        input.setSelection(input.length());
        openKeyboard();
    }

    private void openKeyboard() {
        if (input == null) return;
        if (emojiShown) {
            openKeyboardFromEmoji();
            return;
        }
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    // ---- jump to unread ---------------------------------------------------------------------------------------

    /**
     * Finds where the chat opens: 1 = at the first unread message (the bar goes above it), 2 = more unread than
     * loaded (open at the oldest loaded message), 0 = nothing unread (open at the newest). Also counts the unread
     * messages below for the go-down button and keeps the unread mentions of you for the "@" button.
     */
    private int placeUnreadDivider(List<SecuredMessage> page) {
        unreadDividerBefore = Long.MIN_VALUE;
        if (notes || unreadAtOpen <= 0) return 0;
        long first = Long.MIN_VALUE;
        int firstIndex = -1;
        int unread = 0;
        for (int i = 0; i < page.size(); i++) {
            final SecuredMessage m = page.get(i);
            if (!m.getUnread() || m.getOutgoing()) continue;
            if (first == Long.MIN_VALUE) {
                first = m.getMessageId();
                firstIndex = i;
            }
            unread++;
            if (m.getMentionsMe() && !unreadMentions.contains(m.getMessageId())) unreadMentions.add(m.getMessageId());
        }
        if (first == Long.MIN_VALUE) return 0;
        unreadBelow = unread;
        // Older history exists and even the oldest loaded message is unread: the first unread one isn't loaded.
        // (Comparing with the list's unread count would misfire: it can count rows this screen doesn't show.)
        if (!endReached && firstIndex == 0) return 2;
        unreadDividerBefore = first;
        return 1;
    }

    /** The bar near the top, like Telegram: the first unread message right under it. */
    private void scrollToUnreadDivider() {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).kind == ROW_UNREAD) {
                layoutManager.scrollToPositionWithOffset(i, dp(6));
                return;
            }
        }
        scrollToBottom(false);
    }

    /** Sending something yourself means the unread part has been read: the bar goes, as in Telegram. */
    private void clearUnreadDivider() {
        if (unreadDividerBefore == Long.MIN_VALUE) return;
        unreadDividerBefore = Long.MIN_VALUE;
        rebuildRows(true);
    }

    // ---- group mentions (RD2) -----------------------------------------------------------------------------------

    private void loadMembers() {
        if (engine == null || !groupChat) return;
        try {
            engine.members(chatId, result -> {
                if (destroyed || mentionPicker == null || !(result instanceof SecuredResult.Ok)) return;
                mentionPicker.setMembers(((SecuredResult.Ok<List<GroupMember>>) result).getValue());
                if (mentionStart >= 0 && input != null) checkMention(input.getText());
            });
        } catch (Throwable ignored) {
            // No list: the picker simply doesn't open.
        }
    }

    /** "@" at the start of a word before the cursor opens the member list, filtered by what follows it. */
    private void checkMention(Editable s) {
        if (mentionPicker == null || insertingMention || input == null || s == null) return;
        MentionText.prune(s.toString(), mentionMap);
        if (editing != null || mentionMap.size() >= SecuredMentions.MAX_MENTIONS) {
            hideMentions();
            return;
        }
        final int cursor = input.getSelectionEnd();
        if (cursor <= 0 || cursor > s.length() || cursor != input.getSelectionStart()) {
            hideMentions();
            return;
        }
        int i = cursor - 1;
        while (i >= 0) {
            final char c = s.charAt(i);
            if (c == '@') break;
            if (Character.isWhitespace(c) || cursor - i > 32) {
                i = -1;
                break;
            }
            i--;
        }
        if (i < 0 || (i > 0 && !Character.isWhitespace(s.charAt(i - 1)))) {
            hideMentions();
            return;
        }
        final String query = s.subSequence(i + 1, cursor).toString();
        if (query.indexOf('\'') >= 0) {
            hideMentions();
            return;
        }
        // One members() call per "@"; the position stays while nothing matches, so a late list still opens it.
        final boolean opening = mentionStart != i;
        mentionStart = i;
        if (opening) loadMembers();
        mentionPicker.show(query);
    }

    /** Replaces "@query" with the member's token (as the core reads it) and remembers whom it names. */
    private void insertMention(GroupMember member) {
        if (input == null || member == null) return;
        final Editable s = input.getText();
        final int cursor = input.getSelectionEnd();
        if (s == null || mentionStart < 0 || mentionStart >= s.length() || s.charAt(mentionStart) != '@' || cursor < mentionStart) {
            hideMentions();
            return;
        }
        // The same member twice keeps one name; another member with the same name gets "_1", like the official app.
        String key = null;
        for (java.util.Map.Entry<String, Long> e : mentionMap.entrySet()) {
            if (e.getValue() == member.getMemberId()) key = e.getKey();
        }
        final SecuredMentions.MentionToken token = key != null ? SecuredMentions.token(key)
                : SecuredMentions.token(member.getDisplayName(), mentionMap.keySet());
        final String inserted = token.getText() + " ";
        insertingMention = true;
        try {
            s.replace(mentionStart, cursor, inserted);
        } finally {
            insertingMention = false;
        }
        mentionMap.put(token.getKey(), member.getMemberId());
        final int at = Math.min(s.length(), mentionStart + inserted.length());
        input.setSelection(at, at);
        hideMentions();
    }

    private void hideMentions() {
        mentionStart = -1;
        if (mentionPicker != null) mentionPicker.hide();
    }

    /** A message that mentions you: flashed when it is in view, otherwise kept for the "@" button. */
    private void onMentionArrived(long messageId) {
        if (!unreadMentions.contains(messageId) && (hasNewer || !isAtBottom() || !isVisibleToUser())) {
            unreadMentions.add(messageId);
        } else {
            flash(messageId);
        }
        updateMentionButton();
    }

    private void jumpToMention() {
        if (unreadMentions.isEmpty()) return;
        final long id = unreadMentions.remove(0);
        goTo(id);
        updateMentionButton();
    }

    /** Shows the "@" button while mentions of you wait below, above the go-down button when that shows. */
    private void updateMentionButton() {
        if (mentionButton == null) return;
        if (!unreadMentions.isEmpty() && layoutManager != null) {
            // Mentions now on screen are seen.
            final int first = layoutManager.findFirstVisibleItemPosition();
            final int last = layoutManager.findLastVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION) {
                for (int p = first; p <= last && p < rows.size(); p++) {
                    final Row r = rows.get(p);
                    if (r.message != null) unreadMentions.remove(r.message.getMessageId());
                }
            }
        }
        final boolean show = !searching && !unreadMentions.isEmpty();
        mentionButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        mentionButton.setTranslationY(jumpShown ? -dp(52) : 0);
        mentionCounter.setText(unreadMentions.size() > 99 ? "99+" : String.valueOf(unreadMentions.size()));
    }

    /** Telegram's highlight flash on one bubble. */
    private void flash(long messageId) {
        highlightId = messageId;
        setHighlighted(messageId, true);
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || highlightId != messageId) return;
            highlightId = Long.MIN_VALUE;
            setHighlighted(messageId, false);
        }, 1500);
    }

    // ---- emoji panel (MU11) ------------------------------------------------------------------------------------

    private void toggleEmoji() {
        if (emojiShown) {
            openKeyboardFromEmoji();
        } else {
            showEmojiPanel();
        }
    }

    /** The panel takes the keyboard's place, at the keyboard's last height. */
    private void showEmojiPanel() {
        if (emojiContainer == null || input == null || getParentActivity() == null) return;
        if (emojiPanel == null) {
            emojiPanel = new SecuredEmojiPanel(getParentActivity(), SecuredResourcesProvider.get(), new SecuredEmojiPanel.Listener() {
                @Override
                public void onEmoji(String emoji) {
                    insertEmoji(emoji);
                }

                @Override
                public void onBackspace() {
                    if (input != null) input.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                }
            });
            emojiContainer.addView(emojiPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        AndroidUtilities.cancelRunOnUIThread(panelFallback);
        hidePanelWhenKeyboardOpens = false;
        emojiShown = true;
        final ViewGroup.LayoutParams lp = emojiContainer.getLayoutParams();
        lp.height = panelHeight();
        emojiContainer.setLayoutParams(lp);
        emojiContainer.setVisibility(View.VISIBLE);
        emojiPanel.onShow();
        emojiButton.setState(ChatActivityEnterViewAnimatedIconView.State.KEYBOARD, true);
        emojiButton.setContentDescription(emojiButton.getContext().getString(R.string.secemoji_keyboard));
        input.requestFocus();
        AndroidUtilities.hideKeyboard(input);
    }

    /** Back to the keyboard: the panel stays until the keyboard is up, so the chat does not jump. */
    private void openKeyboardFromEmoji() {
        if (input == null) return;
        emojiShown = false;
        setEmojiIcon();
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
        if (AndroidUtilities.usingHardwareInput || AndroidUtilities.isInMultiwindow) {
            closePanelView();
            return;
        }
        hidePanelWhenKeyboardOpens = true;
        keyboardRequestedAt = android.os.SystemClock.elapsedRealtime();
        AndroidUtilities.cancelRunOnUIThread(panelFallback);
        AndroidUtilities.runOnUIThread(panelFallback, 700);
    }

    /** Closes the panel without opening the keyboard (Back, search, a bar replacing the composer). */
    private void hideEmojiPanel() {
        if (!emojiShown && !hidePanelWhenKeyboardOpens) return;
        emojiShown = false;
        setEmojiIcon();
        closePanelView();
    }

    private void closePanelView() {
        hidePanelWhenKeyboardOpens = false;
        AndroidUtilities.cancelRunOnUIThread(panelFallback);
        if (emojiPanel != null) emojiPanel.dismissPopups();
        if (emojiContainer != null) emojiContainer.setVisibility(View.GONE);
    }

    private final Runnable panelFallback = () -> {
        // The keyboard never reported a height (a hardware keyboard, a floating one): close the panel anyway.
        if (hidePanelWhenKeyboardOpens) closePanelView();
    };

    private void setEmojiIcon() {
        if (emojiButton == null) return;
        emojiButton.setState(ChatActivityEnterViewAnimatedIconView.State.SMILE, true);
        emojiButton.setContentDescription(emojiButton.getContext().getString(R.string.secemoji_button));
    }

    private void onKeyboardSize(int keyboardHeight, boolean landscape) {
        if (keyboardHeight <= dp(50)) return;
        if (!AndroidUtilities.isInMultiwindow) {
            final int old = landscape ? keyboardHeightLand : keyboardHeightPort;
            if (old != keyboardHeight) {
                if (landscape) keyboardHeightLand = keyboardHeight;
                else keyboardHeightPort = keyboardHeight;
                final Context c = root != null ? root.getContext().getApplicationContext() : null;
                if (c != null) {
                    c.getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(landscape ? "kbd_h_land" : "kbd_h", keyboardHeight).apply();
                }
            }
        }
        // A report posted while the previous keyboard was still closing must not close the panel early.
        if (hidePanelWhenKeyboardOpens && android.os.SystemClock.elapsedRealtime() - keyboardRequestedAt > 120) closePanelView();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation: a recording's overlay was placed for the old layout, so it stops (and is deleted, never sent);
        // the panel takes the height the keyboard has in the new orientation.
        if (voice != null) voice.onPause();
        if (emojiShown && emojiContainer != null) {
            final ViewGroup.LayoutParams lp = emojiContainer.getLayoutParams();
            lp.height = panelHeight();
            emojiContainer.setLayoutParams(lp);
        }
    }

    private int panelHeight() {
        final boolean landscape = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y;
        int h = landscape ? keyboardHeightLand : keyboardHeightPort;
        if (h <= 0 && root != null) {
            h = root.getContext().getApplicationContext().getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE)
                    .getInt(landscape ? "kbd_h_land" : "kbd_h", 0);
        }
        if (h <= 0) h = dp(landscape ? 200 : 270);
        return Math.max(dp(180), Math.min(h, AndroidUtilities.displaySize.y - dp(220)));
    }

    /** Plain text at the cursor: the composer renders emoji with the system font (its filter drops image spans). */
    private void insertEmoji(String emoji) {
        if (input == null || emoji == null) return;
        final Editable text = input.getText();
        if (text == null) return;
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        if (start > end) {
            final int t = start;
            start = end;
            end = t;
        }
        text.replace(start, end, emoji);
        final int at = Math.min(text.length(), start + emoji.length());
        input.setSelection(at, at);
    }

    private void send() {
        if (engine == null || input == null) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (editing != null) {
            sendEdit(editing, text);
            return;
        }
        clearUnreadDivider();
        final SecuredMessage replyTarget = replyingTo;
        final Long replyId = replyTarget != null ? replyTarget.getMessageId() : null;
        // Mentions still in the text (the engine sends at most SecuredMentions.MAX_MENTIONS of them).
        MentionText.prune(text, mentionMap);
        final HashMap<String, Long> mentions = groupChat && !mentionMap.isEmpty() ? new HashMap<>(mentionMap) : null;
        input.setText("");
        SecuredUi.setDraft(chatId, "", null, null);   // AR9: sending clears the draft
        mentionMap.clear();
        hideMentions();
        cancelStrip();
        final app.encrogram.core.api.SecuredCallback<SecuredMessage> done = result -> {
            if (destroyed) return;
            if (result instanceof SecuredResult.Ok) {
                upsertMessage(((SecuredResult.Ok<SecuredMessage>) result).getValue(), true);
            } else {
                // Fail closed with a readable reason, and give the text (and the reply) back so nothing is lost.
                restoreDraft(text, replyTarget);
                if (mentions != null && input != null && text.equals(input.getText().toString())) mentionMap.putAll(mentions);
                showToast(((SecuredResult.Err) result).getError());
            }
        };
        try {
            if (mentions != null) {
                engine.sendMessage(chatId, text, replyId, mentions, done);
            } else {
                engine.sendMessage(chatId, text, replyId, done);
            }
        } catch (Throwable t) {
            restoreDraft(text, replyTarget);
            if (mentions != null && input != null && text.equals(input.getText().toString())) mentionMap.putAll(mentions);
            showToast(null);
        }
    }

    /** AR9: keeps the unsent text and reply target in memory (SecuredUi), never on disk. An edit in progress is not a draft. */
    private void saveDraft() {
        if (input == null || editing != null) return;
        final Editable e = input.getText();
        SecuredUi.setDraft(chatId, e != null ? e.toString() : "", replyingTo != null ? replyingTo.getMessageId() : null,
                input.getContext().getString(R.string.sec_au_draft) + " ");
    }

    /** AR9: puts a saved draft back into a new composer; the reply target waits for the first page. */
    private void restoreSavedDraft() {
        if (input == null || editing != null) return;
        final SecuredUi.Draft d = SecuredUi.draft(chatId);
        if (d == null) return;
        if (input.getText().length() == 0) {
            input.setText(d.text);
            input.setSelection(d.text.length());
        }
        if (d.replyId != null && replyingTo == null) {
            draftReplyId = d.replyId;
            applyDraftReply();
        }
    }

    private void applyDraftReply() {
        final Long id = draftReplyId;
        if (id == null || replyingTo != null || editing != null || input == null) return;
        final int i = indexOf(id);
        if (i < 0) return;
        draftReplyId = null;
        final SecuredMessage m = messages.get(i);
        if (m.getDeleted()) return;
        replyingTo = m;
        final String who = m.getOutgoing() ? mapper.selfName() : m.getSender() != null ? m.getSender().getDisplayName() : name;
        showStrip(org.telegram.messenger.R.drawable.input_reply, input.getContext().getString(R.string.secchat_reply_to, who), previewText(m));
    }

    private void restoreDraft(String text, SecuredMessage replyTarget) {
        if (input == null) return;
        if (input.getText().length() == 0) {
            input.setText(text);
            input.setSelection(text.length());
        }
        if (replyTarget != null && replyingTo == null && editing == null && indexOf(replyTarget.getMessageId()) >= 0) {
            startReply(replyTarget);
        }
    }

    /** AR11: sends the failed text as a new message; the failed copy is deleted for me only once the new one is accepted. */
    private void sendAgain(SecuredMessage failed) {
        if (engine == null || failed.getText().isEmpty()) return;
        final Long replyId = failed.getReplyTo() != null ? failed.getReplyTo().getMessageId() : null;
        final HashMap<String, Long> mentions = groupChat && !failed.getMentions().isEmpty() ? new HashMap<>(failed.getMentions()) : null;
        final app.encrogram.core.api.SecuredCallback<SecuredMessage> done = result -> {
            if (destroyed) return;
            if (result instanceof SecuredResult.Ok) {
                upsertMessage(((SecuredResult.Ok<SecuredMessage>) result).getValue(), true);
                itemAction(Collections.singletonList(failed.getMessageId()), ItemAction.DeleteForMe.INSTANCE, null);
            } else {
                showToast(((SecuredResult.Err) result).getError());
            }
        };
        try {
            if (mentions != null) {
                engine.sendMessage(chatId, failed.getText(), replyId, mentions, done);
            } else {
                engine.sendMessage(chatId, failed.getText(), replyId, done);
            }
        } catch (Throwable t) {
            showToast(null);
        }
    }

    private void sendEdit(SecuredMessage target, String text) {
        if (text.equals(target.getText())) {
            cancelStrip();
            return;
        }
        editing = null;
        replyingTo = null;
        if (replyStrip != null) replyStrip.setVisibility(View.GONE);
        input.setText("");
        itemAction(Collections.singletonList(target.getMessageId()), new ItemAction.Edit(text), () -> {
            // Failed: put the edit back in the composer.
            if (input != null && input.getText().length() == 0 && indexOf(target.getMessageId()) >= 0) {
                editing = target;
                showStrip(org.telegram.messenger.R.drawable.msg_edit, tg(org.telegram.messenger.R.string.EditMessage), target.getText());
                input.setText(text);
                input.setSelection(text.length());
            }
        });
    }

    private void showToast(SecuredError e) {
        final Context context = getContext();
        if (context == null) return;
        final String reason = e != null ? e.getMessage() : context.getString(R.string.sec_engine_failed_unknown);
        Toast.makeText(context, context.getString(R.string.sec_send_failed, reason), Toast.LENGTH_LONG).show();
    }

    // ---- engine actions --------------------------------------------------------------------------------------

    /** Runs one item action. Results arrive through the listener; failures show a readable bulletin. */
    private void itemAction(List<Long> ids, ItemAction action, Runnable onFailure) {
        if (engine == null || ids.isEmpty()) return;
        try {
            engine.itemAction(chatId, ids, action, result -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    showError(((SecuredResult.Err) result).getError());
                    if (onFailure != null) onFailure.run();
                }
            });
        } catch (Throwable t) {
            showError(null);
            if (onFailure != null) onFailure.run();
        }
    }

    private void showError(SecuredError e) {
        final Context c = getContext();
        if (c == null || destroyed) return;
        final String reason = e != null ? e.getMessage() : c.getString(R.string.sec_engine_failed_unknown);
        BulletinFactory.of(this).createErrorBulletin(c.getString(R.string.secchat_action_failed, reason), chatResources).show();
    }

    /**
     * One reaction per person, like Telegram (the SimpleX core allows 3). Choosing a different reaction first removes
     * our existing ones, then adds the new one; choosing the one we already have removes it. The engine runs item
     * actions in the order we send them. Reactions that peers' apps send (several per person) are shown as they arrive.
     */
    private void react(SecuredMessage m, String emoji, boolean add) {
        if (m == null || m.getDeleted() || notes) return;
        final int idx = indexOf(m.getMessageId());
        final SecuredMessage cur = idx >= 0 ? messages.get(idx) : m;
        final List<Long> id = Collections.singletonList(cur.getMessageId());
        boolean hadSame = false;
        final ArrayList<String> others = new ArrayList<>();
        for (ReactionCount r : cur.getReactions()) {
            if (!r.getMine()) continue;
            if (r.getEmoji().equals(emoji)) hadSame = true;
            else others.add(r.getEmoji());
        }
        if (!add) {
            // Removing: only what we really have.
            if (hadSame) itemAction(id, new ItemAction.React(emoji, false), null);
            return;
        }
        if (hadSame && others.isEmpty()) {
            // The tap meant "add" from a stale chip, but we already have exactly this one: toggle it off, as Telegram does.
            itemAction(id, new ItemAction.React(emoji, false), null);
            return;
        }
        for (String old : others) {
            itemAction(id, new ItemAction.React(old, false), null);
        }
        if (!hadSame) itemAction(id, new ItemAction.React(emoji, true), null);
    }

    private final SecuredReactionsView.Listener reactionListener = this::react;

    private void copyMessages(List<SecuredMessage> list) {
        final Context c = getParentActivity();
        if (c == null) return;
        final StringBuilder sb = new StringBuilder();
        for (SecuredMessage m : list) {
            if (m.getDeleted() || m.getText().isEmpty()) continue;   // a media message without a caption has no text
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.getText());
        }
        if (sb.length() == 0) return;
        // Marked sensitive, like the composer's copy.
        SecuredSheets.copyToClipboard(c, sb.toString(), true);
        BulletinFactory.of(this).createCopyBulletin(tg(org.telegram.messenger.R.string.MessageCopied), chatResources).show();
    }

    private void pickForwardTarget(List<SecuredMessage> list) {
        final Activity activity = getParentActivity();
        if (activity == null || engine == null) return;
        final ArrayList<Long> ids = new ArrayList<>();
        for (SecuredMessage m : list) {
            if (!m.getDeleted()) ids.add(m.getMessageId());
        }
        if (ids.isEmpty()) return;
        final ArrayList<ChatSummary> targets = new ArrayList<>();
        try {
            for (ChatSummary c : engine.chats()) {
                if (c.getChatId() == chatId) continue;
                final boolean saved = c.getChatId() == SecuredEngineKt.SAVED_MESSAGES_CHAT_ID;
                // Saved Messages is first, as in Telegram's share list.
                if (saved) targets.add(0, c);
                else if (c.getReady()) targets.add(c);
            }
        } catch (Throwable ignore) {
        }
        if (targets.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(activity.getString(R.string.secchat_no_forward_targets), chatResources).show();
            return;
        }
        final CharSequence[] names = new CharSequence[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            names[i] = targetName(targets.get(i));
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, chatResources);
        builder.setTitle(tg(org.telegram.messenger.R.string.ForwardTo));
        builder.setItems(names, (dialog, which) -> {
            if (which < 0 || which >= targets.size()) return;
            final ChatSummary target = targets.get(which);
            clearSelection();
            forward(ids, target);
        });
        Kit.show(this, builder.create());
    }

    /** Saved Messages has no name from the engine: Telegram's own localized string. */
    private String targetName(ChatSummary c) {
        return c.getChatId() == SecuredEngineKt.SAVED_MESSAGES_CHAT_ID ? tg(org.telegram.messenger.R.string.SavedMessages) : c.getDisplayName();
    }

    private void forward(List<Long> ids, ChatSummary target) {
        if (engine == null) return;
        try {
            engine.itemAction(chatId, ids, new ItemAction.ForwardTo(target.getChatId()), result -> {
                if (destroyed) return;
                final Context c = getContext();
                if (result instanceof SecuredResult.Ok) {
                    if (c != null) {
                        BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.forward,
                                c.getString(R.string.secchat_forwarded_to, targetName(target))).show();
                    }
                } else {
                    showError(((SecuredResult.Err) result).getError());
                }
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    private void confirmDelete(List<SecuredMessage> list) {
        final Activity activity = getParentActivity();
        if (activity == null || list.isEmpty()) return;
        boolean everyoneAllowed = true;
        final ArrayList<Long> ids = new ArrayList<>();
        for (SecuredMessage m : list) {
            ids.add(m.getMessageId());
            if (m.getDeleted() || !m.getCanDeleteForEveryone()) everyoneAllowed = false;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, chatResources);
        if (list.size() == 1) {
            builder.setTitle(tg(org.telegram.messenger.R.string.DeleteSingleMessagesTitle));
            builder.setMessage(tg(org.telegram.messenger.R.string.AreYouSureDeleteSingleMessage));
        } else {
            builder.setTitle(LocaleController.formatString(org.telegram.messenger.R.string.DeleteMessagesTitle,
                    LocaleController.formatPluralString("messages", list.size())));
            builder.setMessage(tg(org.telegram.messenger.R.string.AreYouSureDeleteFewMessages));
        }
        final CheckBoxCell[] alsoForPeer = new CheckBoxCell[1];
        if (everyoneAllowed) {
            final FrameLayout box = new FrameLayout(activity);
            final CheckBoxCell cell = new CheckBoxCell(activity, 1, chatResources);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(groupChat ? tg(org.telegram.messenger.R.string.DeleteForAll)
                    : LocaleController.formatString(org.telegram.messenger.R.string.DeleteMessagesOptionAlso, name), "", false, false);
            cell.setPadding(LocaleController.isRTL ? dp(16) : dp(8), 0, LocaleController.isRTL ? dp(8) : dp(16), 0);
            cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked(), true));
            box.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));
            builder.setView(box);
            alsoForPeer[0] = cell;
        }
        builder.setPositiveButton(tg(org.telegram.messenger.R.string.Delete), (dialog, which) -> {
            final boolean forEveryone = alsoForPeer[0] != null && alsoForPeer[0].isChecked();
            clearSelection();
            itemAction(ids, forEveryone ? ItemAction.DeleteForEveryone.INSTANCE : ItemAction.DeleteForMe.INSTANCE, null);
        });
        builder.setNegativeButton(tg(org.telegram.messenger.R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    // ---- links -----------------------------------------------------------------------------------------------

    /**
     * One of our own invite or group links (valid or not: Approved, or a SimpleX link to an unapproved relay) never goes to
     * a browser, whose history would keep the key part. It opens the connect sheet, prefilled, so its normal checks and readable
     * errors (expired, own invite, unknown relay) apply. checkLink is a local parse, called on the main thread like the sheet does.
     * Nothing is logged. Returns false for any other URL.
     */
    private boolean openSecuredLink(String url) {
        if (TextUtils.isEmpty(url) || engine == null) return false;
        final LinkCheck check;
        try {
            check = engine.checkLink(url.trim());
        } catch (Throwable t) {
            return false;
        }
        if (check instanceof LinkCheck.Malformed) return false;
        SecuredSheets.showConnect(this, engine, url.trim());
        return true;
    }

    /** Links never go through Telegram's Browser: confirm, then a plain ACTION_VIEW that never targets our own app. */
    private void confirmOpenLink(String url) {
        final Activity activity = getParentActivity();
        if (activity == null || TextUtils.isEmpty(url)) return;
        final Uri uri;
        try {
            uri = Uri.parse(url.contains("://") ? url : "https://" + url);
        } catch (Throwable t) {
            return;
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        final boolean web = "http".equals(scheme) || "https".equals(scheme);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, chatResources);
        builder.setTitle(tg(org.telegram.messenger.R.string.OpenUrlTitle));
        builder.setMessage(LocaleController.formatString(org.telegram.messenger.R.string.OpenUrlAlert2, url)
                + "\n\n" + activity.getString(R.string.secchat_link_outside));
        if (web) {
            builder.setPositiveButton(tg(org.telegram.messenger.R.string.Open), (dialog, which) -> openExternally(uri));
        }
        builder.setNeutralButton(tg(org.telegram.messenger.R.string.CopyLink), (dialog, which) -> SecuredSheets.copyToClipboard(activity, url, true));
        builder.setNegativeButton(tg(org.telegram.messenger.R.string.Cancel), null);
        Kit.show(this, builder.create());
    }

    private void openExternally(Uri uri) {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        final Intent view = new Intent(Intent.ACTION_VIEW, uri);
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            // Our own app also handles t.me and similar links on its Telegram side: never hand a Secured link there.
            final ArrayList<ComponentName> ours = new ArrayList<>();
            final List<ResolveInfo> handlers = activity.getPackageManager().queryIntentActivities(view, 0);
            for (ResolveInfo ri : handlers) {
                if (ri.activityInfo != null && activity.getPackageName().equals(ri.activityInfo.packageName)) {
                    ours.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                }
            }
            if (ours.isEmpty()) {
                activity.startActivity(view);
            } else {
                final Intent chooser = Intent.createChooser(view, null);
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ours.toArray(new ComponentName[0]));
                activity.startActivity(chooser);
            }
        } catch (Throwable t) {
            showError(null);
        }
    }

    // ---- media (docs/tasks.md, Media phase 2) ------------------------------------------------------------------

    private final SecuredMediaCell.Host mediaHost = new SecuredMediaCell.Host() {
        @Override
        public MediaBitmaps bitmaps() {
            return bitmaps;
        }

        @Override
        public SecuredAudioPlayer audio() {
            if (audio == null && engine != null && !destroyed) audio = new SecuredAudioPlayer(engine, audioListener);
            return audio;
        }

        @Override
        public String senderName(SecuredMessage m) {
            if (m.getOutgoing()) return getContext() != null ? getContext().getString(R.string.sec_au_you) : "";
            if (m.getSender() != null) return m.getSender().getDisplayName();
            return name;
        }

        @Override
        public String quoteAuthor(SecuredMessage m) {
            final QuotePreview q = m.getReplyTo();
            if (q == null) return "";
            if (q.getOutgoing()) return mapper != null ? mapper.selfName() : "";
            if (groupChat) {
                final Long qid = q.getMessageId();
                final String who = qid != null ? senderNameOf(qid) : null;
                return who != null && !who.isEmpty() ? who : getContext() != null ? getContext().getString(R.string.secchat_quote_fallback) : "";
            }
            return name;
        }
    };

    private final SecuredAudioPlayer.Listener audioListener = new SecuredAudioPlayer.Listener() {
        @Override
        public void onAudioChanged(long cid, long messageId) {
            if (cid != chatId || destroyed) return;
            final SecuredMediaCell cell = mediaCellFor(messageId);
            if (cell != null) cell.invalidate();
        }

        @Override
        public void onAudioFailed(SecuredError error) {
            if (destroyed) return;
            final Context c = getContext();
            if (c == null) return;
            showError(error != null ? error : new SecuredError(SecuredError.Kind.UNKNOWN, c.getString(R.string.secmedia_open_failed)));
        }
    };

    private final SecuredAttach.Host attachHost = new SecuredAttach.Host() {
        @Override
        public BaseFragment fragment() {
            return SecuredChatActivity.this;
        }

        @Override
        public SecuredEngine engine() {
            return engine;
        }

        @Override
        public long chatId() {
            return chatId;
        }

        @Override
        public Theme.ResourcesProvider resources() {
            return chatResources;
        }

        @Override
        public MediaBitmaps bitmaps() {
            return bitmaps;
        }

        @Override
        public String composerText() {
            return input != null && editing == null ? input.getText().toString().trim() : "";
        }

        @Override
        public Long replyToId() {
            return replyingTo != null ? replyingTo.getMessageId() : null;
        }

        @Override
        public void onMediaSent(SecuredMessage m, boolean usedComposerText) {
            if (destroyed) return;
            if (usedComposerText && input != null && editing == null) input.setText("");
            if (replyingTo != null) cancelStrip();
            upsertMessage(m, true);
        }

        @Override
        public void onMediaFailed(SecuredError e) {
            if (!destroyed) showToast(e);
        }
    };

    private final SecuredVoiceRecorder.Host voiceHost = new SecuredVoiceRecorder.Host() {
        @Override
        public BaseFragment fragment() {
            return SecuredChatActivity.this;
        }

        @Override
        public SecuredEngine engine() {
            return engine;
        }

        @Override
        public long chatId() {
            return chatId;
        }

        @Override
        public Long replyToId() {
            return replyingTo != null ? replyingTo.getMessageId() : null;
        }

        @Override
        public Theme.ResourcesProvider resources() {
            return SecuredResourcesProvider.get();
        }

        @Override
        public SecuredVoiceOverlay overlay() {
            return voiceOverlay;
        }

        @Override
        public View composerBar() {
            return composerView;
        }

        @Override
        public View micButton() {
            return micButton;
        }

        @Override
        public void onRecordingStarts() {
            if (audio != null) audio.pause();
        }

        @Override
        public void onVoiceSent(SecuredMessage m) {
            if (destroyed) return;
            if (replyingTo != null) cancelStrip();
            upsertMessage(m, true);
        }

        @Override
        public void onVoiceFailed(SecuredError e) {
            if (destroyed) return;
            if (e != null) {
                showToast(e);
            } else if (getContext() != null) {
                showMediaNote(getContext().getString(R.string.secvoice_failed));
            }
        }

        @Override
        public void showNote(String text) {
            showMediaNote(text);
        }
    };

    private void openAttach() {
        if (attach == null || engine == null) return;
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        if (!chatReady() || !canPostHere() || isInvited()) {
            showError(null);
            return;
        }
        if (editing != null) cancelStrip();
        hideEmojiPanel();
        AndroidUtilities.hideKeyboard(input);
        attach.showMenu();
    }

    /** A tap on a media bubble: what it does depends on where it landed and on the engine's state for the file. */
    private void onMediaTap(SecuredMediaCell cell, float x, float y) {
        final SecuredMessage m = cell.message();
        final SecuredMedia media = m != null ? m.getMedia() : null;
        if (media == null || engine == null) return;
        final int hit = cell.hitTest(x, y);
        if (hit == SecuredMediaCell.HIT_NONE) return;
        if (hit == SecuredMediaCell.HIT_QUOTE) {
            final Long qid = m.getReplyTo() != null ? m.getReplyTo().getMessageId() : null;
            if (qid == null) showNotLoaded();
            else goTo(qid);
            return;
        }
        final MediaKind kind = media.getKind();
        switch (media.getState()) {
            case WAITING:
                mediaCall(m, true);
                break;
            case DOWNLOADING:
                mediaCall(m, false);
                break;
            case UPLOADING:
                // The X stops the upload; a tap on a readable photo or a voice play opens it as usual.
                if (hit == SecuredMediaCell.HIT_BUTTON || !media.getReadable() || kind == MediaKind.FILE) {
                    mediaCall(m, false);
                } else {
                    openMedia(cell, m, hit);
                }
                break;
            case READY:
                openMedia(cell, m, hit);
                break;
            case FAILED:
            default:
                showMediaNote(MediaText.failureSentence(getContext(), media.getFailure()));
                break;
        }
    }

    private void openMedia(SecuredMediaCell cell, SecuredMessage m, int hit) {
        final SecuredMedia media = m.getMedia();
        if (media == null || !media.getReadable()) return;
        switch (media.getKind()) {
            case IMAGE:
            case GIF:
            case VIDEO:
                SecuredMediaViewer.open(this, engine, bitmaps, m, authorOf(m), chatResources);
                break;
            case VOICE: {
                final SecuredAudioPlayer player = mediaHost.audio();
                if (player == null) return;
                if (hit == SecuredMediaCell.HIT_SEEK) player.seek(m, cell.seekFraction());
                else player.toggle(m);
                break;
            }
            case FILE:
            default:
                // Opening a file in another app would hand its plain bytes out of Secured (no share-out in v1).
                showMediaNote(getContext() != null ? getContext().getString(R.string.secmedia_no_open_file) : null);
                break;
        }
    }

    /** Download (accept) or stop a transfer. The new state arrives through onMessageChanged. */
    private void mediaCall(SecuredMessage m, boolean accept) {
        try {
            final app.encrogram.core.api.SecuredCallback<kotlin.Unit> cb = result -> {
                if (!destroyed && result instanceof SecuredResult.Err) showError(((SecuredResult.Err) result).getError());
            };
            if (accept) engine.acceptMedia(chatId, m.getMessageId(), cb);
            else engine.cancelMedia(chatId, m.getMessageId(), cb);
        } catch (Throwable t) {
            showError(null);
        }
    }

    private String authorOf(SecuredMessage m) {
        if (m.getOutgoing()) return mapper != null ? mapper.selfName() : "";
        if (m.getSender() != null) return m.getSender().getDisplayName();
        return name;
    }

    private void showMediaNote(String text) {
        final Context c = getContext();
        if (c == null || destroyed || text == null) return;
        BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip, text).show();
    }

    // ---- message cell delegate: every click is ours ----------------------------------------------------------

    private final ChatMessageCell.ChatMessageCellDelegate cellDelegate = new ChatMessageCell.ChatMessageCellDelegate() {
        @Override
        public boolean canPerformActions() {
            // In selection mode taps go to the list (toggle), not to links or replies.
            return selectedIds.isEmpty() && !searching;
        }

        @Override
        public void didLongPress(ChatMessageCell cell, float x, float y) {
            onMessageLongPress(cell);
        }

        @Override
        public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
            if (longPress) {
                onMessageLongPress(cell);
                return;
            }
            // Mentions, hashtags, bot commands, cards and code spans mean Telegram actions: they do nothing here.
            if (!(url instanceof URLSpan) || url instanceof URLSpanNoUnderline) return;
            final String tapped = ((URLSpan) url).getURL();
            if (!openSecuredLink(tapped)) confirmOpenLink(tapped);
        }

        @Override
        public void didPressReplyMessage(ChatMessageCell cell, int id, float x, float y, boolean longpress) {
            // The cell still reports quote taps in selection mode; there they toggle selection like any tap.
            final SecuredMessage m = messageFor(cell);
            if (searching) {
                if (m != null) openMatch(m);
            } else if (!selectedIds.isEmpty()) {
                if (m != null) toggleSelection(m);
            } else if (longpress) {
                onMessageLongPress(cell);
            } else {
                jumpToQuote(cell);
            }
        }

        @Override
        public void didPressInstantButton(ChatMessageCell cell, int type) {
            // A group avatar tap falls through to here (the cell has no Telegram user for the sender). Nothing opens.
        }

        @Override
        public void didPressWebPage(ChatMessageCell cell, TLRPC.WebPage webpage, String url, boolean safe) {
            // Never Browser.openUrl. (The mapper never sets a webpage; this guards the default implementation.)
        }

        @Override
        public void needOpenWebView(MessageObject message, String url, String title, String description, String originalUrl, int w, int h) {
        }

        @Override
        public boolean needPlayMessage(ChatMessageCell cell, MessageObject messageObject, boolean muted) {
            return false;
        }

        @Override
        public TextSelectionHelper.ChatListTextSelectionHelper getTextSelectionHelper() {
            // shortcut: partial text selection needs a Secured selection helper without Telegram's translate/share actions.
            return null;
        }
    };

    private void onMessageLongPress(View cell) {
        final SecuredMessage m = messageFor(cell);
        if (m == null || searching) return;
        if (!selectedIds.isEmpty()) {
            toggleSelection(m);
        } else {
            showMessageMenu(cell, m);
        }
    }

    private ItemOptions openMenu;

    private void showMessageMenu(View cell, SecuredMessage m) {
        if (getParentActivity() == null || openMenu != null && openMenu.isShown()) return;
        final boolean deleted = m.getDeleted();
        final boolean ready = chatReady();
        // Media in v1: Copy only when there is a caption; no Forward (the engine's forward path is not checked
        // for files); no Edit (canEdit is false for media).
        final boolean media = m.getMedia() != null;
        final boolean hasText = !media || !m.getText().isEmpty();
        final ItemOptions options = ItemOptions.makeOptions(this, cell);
        if (!deleted && ready && !notes) {
            options.addView(buildReactionPicker(m, options));
            options.addGap();
        }
        options.addIf(!deleted && ready && !notes && canPostHere(), org.telegram.messenger.R.drawable.menu_reply, tg(org.telegram.messenger.R.string.Reply), () -> startReply(m));
        // AR11: a failed text message is sent again as a new one (never "Retry"); media only if its prepared file is
        // still held, which the UI does not keep after sending, so media offers none.
        options.addIf(m.getOutgoing() && m.getStatus() == MessageStatus.FAILED && !deleted && !media && !m.getForwarded() && ready && canPostHere() && m.getEvent() == null,
                org.telegram.messenger.R.drawable.msg_retry, getContext().getString(R.string.sec_au_send_again), () -> sendAgain(m));
        options.addIf(!deleted && hasText, org.telegram.messenger.R.drawable.msg_copy, tg(org.telegram.messenger.R.string.Copy), () -> copyMessages(Collections.singletonList(m)));
        options.addIf(!deleted && m.getCanEdit(), org.telegram.messenger.R.drawable.msg_edit, tg(org.telegram.messenger.R.string.Edit), () -> startEdit(m));
        options.addIf(!deleted && ready && !media, org.telegram.messenger.R.drawable.msg_forward, tg(org.telegram.messenger.R.string.Forward), () -> pickForwardTarget(Collections.singletonList(m)));
        options.add(org.telegram.messenger.R.drawable.msg_select, tg(org.telegram.messenger.R.string.Select), () -> toggleSelection(m));
        options.add(org.telegram.messenger.R.drawable.msg_delete, tg(org.telegram.messenger.R.string.Delete), true, () -> confirmDelete(Collections.singletonList(m)));
        options.addIf(!deleted && m.getEvent() == null && !notes, org.telegram.messenger.R.drawable.msg_info, getContext().getString(R.string.sec_r2i_details_title),
                () -> SecuredMessageDetailsSheet.show(this, engine, chatId, m.getMessageId()));
        options.setGravity(m.getOutgoing() ? Gravity.RIGHT : Gravity.LEFT);
        openMenu = options;
        options.show();
        Kit.securePopup(options.actionBarPopupWindow);
    }

    /** Our 8-emoji picker: only the reactions the SimpleX protocol accepts (SECURED_REACTIONS). */
    private View buildReactionPicker(SecuredMessage m, ItemOptions options) {
        final Context context = getParentActivity();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), 0, dp(3), 0);   // AR12: 40 x 44 dp hit areas, the same look
        final HashSet<String> mine = new HashSet<>();
        for (ReactionCount r : m.getReactions()) {
            if (r.getMine()) mine.add(r.getEmoji());
        }
        for (String emoji : SecuredEngineKt.getSECURED_REACTIONS()) {
            final boolean isMine = mine.contains(emoji);
            final TextView t = new TextView(context);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            t.setText(Emoji.replaceEmoji(emoji, t.getPaint().getFontMetricsInt(), false));
            if (isMine) {
                t.setBackground(new InsetDrawable(Kit.roundRect(ColorUtils.setAlphaComponent(SecuredResourcesProvider.accentFill(), 0x33), 17), dp(3), dp(4), dp(3), dp(4)));
            }
            t.setContentDescription(emoji);
            t.setOnClickListener(v -> {
                options.dismiss();
                react(m, emoji, !isMine);
            });
            row.addView(t, LayoutHelper.createLinear(40, 44));
        }
        reactionPicker = row;
        return row;
    }

    private void jumpToQuote(ChatMessageCell cell) {
        final SecuredMessage m = messageFor(cell);
        if (m == null || m.getReplyTo() == null) return;
        final Long qid = m.getReplyTo().getMessageId();
        if (qid == null) {
            showNotLoaded();
        } else {
            goTo(qid);
        }
    }

    private void showNotLoaded() {
        final Context c = getContext();
        if (c != null && !destroyed) {
            BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip, c.getString(R.string.secchat_quote_not_loaded)).show();
        }
    }

    /** Scrolls to a message and highlights it, first loading the page around it when it is not in the loaded window. */
    private void goTo(long id) {
        if (rowIndexOf(id) >= 0) {
            scrollToMessage(id);
            return;
        }
        if (engine == null) return;
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, null, null, id, null), result -> {
                if (destroyed || searching) return;
                if (!(result instanceof SecuredResult.Ok)) {
                    showError(((SecuredResult.Err) result).getError());
                    return;
                }
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                boolean found = false;
                for (SecuredMessage m : page) {
                    if (m.getMessageId() == id) found = true;
                }
                if (!found) {
                    showNotLoaded();
                    return;
                }
                replaceWindow(page);
                endReached = false;
                hasNewer = true;   // cleared by checkLoadNewer once nothing newer comes back
                rebuildRows(false);
                scrollToMessage(id);
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    private void scrollToMessage(long target) {
        final int idx = rowIndexOf(target);
        if (idx < 0) return;
        highlightId = target;
        layoutManager.scrollToPositionWithOffset(idx, listView.getMeasuredHeight() / 3);
        listView.post(() -> {
            setHighlighted(target, true);
            updateJumpButton(true);
            checkLoadNewer();
        });
        AndroidUtilities.runOnUIThread(() -> {
            if (destroyed || highlightId != target) return;
            highlightId = Long.MIN_VALUE;
            setHighlighted(target, false);
        }, 1500);
    }

    /** Swaps the loaded window for another page; late results of loads started for the old window are dropped. */
    private void replaceWindow(List<SecuredMessage> page) {
        epoch++;
        loadingOlder = false;
        updateSpinners();
        loadingNewer = false;
        clearSelection();
        messages.clear();
        messages.addAll(page);
    }

    private void loadNewest() {
        if (engine == null) return;
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, null, null, null, null), result -> {
                if (destroyed || searching) return;
                if (!(result instanceof SecuredResult.Ok)) {
                    showError(((SecuredResult.Err) result).getError());
                    return;
                }
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                replaceWindow(page);
                endReached = page.size() < PAGE_SIZE;
                hasNewer = false;
                unreadBelow = 0;
                rebuildRows(false);
                scrollToLast(false);
                engine.markRead(chatId);
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    private void checkLoadNewer() {
        if (!hasNewer || loadingNewer || searching || messages.isEmpty() || engine == null || listView == null) return;
        if (System.currentTimeMillis() - newerFailedAt < 5000) return;
        final int last = layoutManager.findLastVisibleItemPosition();
        if (last == RecyclerView.NO_POSITION || last < rows.size() - 7) return;
        loadingNewer = true;
        final int ep = epoch;
        final long afterId = messages.get(messages.size() - 1).getMessageId();
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, null, afterId, null, null), result -> {
                if (destroyed || ep != epoch) return;
                loadingNewer = false;
                if (!(result instanceof SecuredResult.Ok)) {
                    newerFailedAt = System.currentTimeMillis();
                    return;
                }
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                final ArrayList<SecuredMessage> newer = new ArrayList<>();
                for (SecuredMessage m : page) {
                    if (indexOf(m.getMessageId()) < 0) newer.add(m);
                }
                if (page.size() < PAGE_SIZE || newer.isEmpty()) hasNewer = false;
                if (!newer.isEmpty()) {
                    messages.addAll(newer);
                    rebuildRows(true);
                }
                updateJumpButton(true);
            });
        } catch (Throwable t) {
            loadingNewer = false;
            newerFailedAt = System.currentTimeMillis();
        }
    }

    private void setHighlighted(long messageId, boolean value) {
        if (listView == null) return;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ChatMessageCell) {
                final SecuredMessage m = messageFor(child);
                if (m != null && m.getMessageId() == messageId) {
                    ((ChatMessageCell) child).setHighlighted(value);
                }
            } else if (child instanceof SecuredMediaCell && ((SecuredMediaCell) child).messageId() == messageId) {
                ((SecuredMediaCell) child).setHighlighted(value);
            }
        }
    }

    // ---- header menu actions ---------------------------------------------------------------------------------

    private boolean engineRunning() {
        try {
            return engine != null && engine.state() == EngineState.RUNNING;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showNotRunning() {
        final Context c = getContext();
        showError(c == null ? null : new SecuredError(SecuredError.Kind.ENGINE_NOT_RUNNING, c.getString(R.string.secchat_connecting)));
    }

    /** One chat action; [onOk] runs on success, failures show a readable bulletin. The new state also arrives via the listener. */
    private void chatAction(ChatAction action, Runnable onOk) {
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        try {
            engine.chatAction(chatId, action, result -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Ok) {
                    if (onOk != null) onOk.run();
                } else {
                    showError(((SecuredResult.Err) result).getError());
                }
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    private void toggleMute() {
        final ChatSummary s = summary();
        if (s == null) return;
        chatAction(new ChatAction.SetMuted(!s.getMuted()), this::updateHeader);
    }

    private void openDisappearing() {
        final ChatSummary s = summary();
        if (s == null) return;
        SecuredChatSheets.showDisappearing(this, name, s.getDisappearingTtlSeconds(), ttl -> chatAction(
                new ChatAction.SetDisappearing(ttl == 0 ? null : Integer.valueOf(ttl)), () -> {
                    final Context c = getContext();
                    if (c != null && ttl != 0) {
                        BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip, c.getString(R.string.secchat_disappear_saved, name)).show();
                    }
                }));
    }

    private void openVerify() {
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        try {
            engine.securityCode(chatId, result -> {
                if (destroyed) return;
                if (!(result instanceof SecuredResult.Ok)) {
                    showError(((SecuredResult.Err) result).getError());
                    return;
                }
                final String code = ((SecuredResult.Ok<String>) result).getValue();
                final ChatSummary s = summary();
                SecuredChatSheets.showVerify(this, name, code, s != null && s.getVerified(), mark -> verify(mark ? code : null));
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    /** [code] is the code the sheet showed, or null to clear. The header follows the engine's chat list. */
    private void verify(String code) {
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        try {
            engine.verifyContact(chatId, code, result -> {
                if (destroyed) return;
                final Context c = getContext();
                if (!(result instanceof SecuredResult.Ok)) {
                    showError(((SecuredResult.Err) result).getError());
                } else if (c != null) {
                    final boolean verified = ((SecuredResult.Ok<Boolean>) result).getValue();
                    if (code != null && !verified) {
                        showError(new SecuredError(SecuredError.Kind.NOT_ALLOWED, c.getString(R.string.secchat_verify_failed)));
                    } else {
                        BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip,
                                c.getString(code != null ? R.string.secchat_verify_done : R.string.secchat_verify_cleared)).show();
                    }
                }
                updateHeader();
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    private void confirmClear() {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, chatResources);
        b.setTitle(activity.getString(R.string.secchat_clear_title));
        b.setMessage(notes ? activity.getString(R.string.secnotes_clear_text) : activity.getString(R.string.secchat_clear_text, name));
        b.setNegativeButton(activity.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(activity.getString(R.string.secchat_clear_confirm), (d, w) -> chatAction(ChatAction.ClearHistory.INSTANCE, () -> {
            // The engine reports the new list state; the open window is emptied here too.
            replaceWindow(Collections.emptyList());
            endReached = true;
            hasNewer = false;
            unreadBelow = 0;
            unreadMentions.clear();
            unreadDividerBefore = Long.MIN_VALUE;
            updateMentionButton();
            objects.clear();
            objectSources.clear();
            cancelStrip();
            rebuildRows(false);
        }));
        final AlertDialog dialog = b.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    /** Same wording as the chat list's delete dialog. */
    private void confirmDeleteChat() {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        final boolean removed = groupChat && isRemovedOrLeft();   // already out of the group: nothing to leave
        if (groupChat && !removed) {
            SecuredGroupDialogs.confirmDelete(this, chatResources, name, isOwnerOrChannel(true), isOwnerOrChannel(false),
                    everyone -> chatAction(new ChatAction.Delete(everyone), this::finishFragment));
            return;
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, chatResources);
        b.setTitle(activity.getString(R.string.sec_list_delete_title));
        b.setMessage(activity.getString(R.string.sec_list_delete_text));
        final CheckBoxCell also = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, chatResources);
        if (!removed) {
            also.setText(activity.getString(R.string.sec_list_delete_also), "", false, false);
            also.setOnClickListener(v -> also.setChecked(!also.isChecked(), true));
            final FrameLayout holder = new FrameLayout(activity);
            holder.addView(also, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 8, 0, 8, 0));
            b.setView(holder);
        }
        b.setNegativeButton(activity.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(activity.getString(R.string.sec_list_delete_confirm), (d, w) ->
                chatAction(new ChatAction.Delete(also.isChecked()), this::finishFragment));
        final AlertDialog dialog = b.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    // ---- in-chat search --------------------------------------------------------------------------------------

    private void startSearch() {
        searching = true;
        searchDone = false;
        searchSeq++;
        searchText = "";
        searchResults.clear();
        searchMore = false;
        searchLoadingMore = false;
        updateSearchCount();
        updateSpinners();
        clearSelection();
        if (avatarContainer != null) avatarContainer.setVisibility(View.GONE);
        if (composerView != null) composerView.setVisibility(View.GONE);
        hideEmojiPanel();
        hideMentions();
        if (mentionButton != null) mentionButton.setVisibility(View.GONE);
        if (groupChat) updateBottomBars();
        if (emptyView != null) emptyView.setText((notes ? R.string.secnotes_empty : R.string.sec_chat_empty));
        rebuildRows(false);
        updateJumpButton(false);
    }

    private void endSearch() {
        searching = false;
        searchDone = false;
        searchSeq++;
        searchText = "";
        AndroidUtilities.cancelRunOnUIThread(runSearch);
        searchResults.clear();
        searchMore = false;
        searchLoadingMore = false;
        updateSearchCount();
        updateSpinners();
        if (avatarContainer != null) avatarContainer.setVisibility(View.VISIBLE);
        if (composerView != null) composerView.setVisibility(View.VISIBLE);
        if (groupChat) updateBottomBars();
        if (emptyView != null) emptyView.setText((notes ? R.string.secnotes_empty : R.string.sec_chat_empty));
        rebuildRows(false);
        final Long jump = pendingJump;
        pendingJump = null;
        if (jump != null) {
            goTo(jump);
        } else {
            scrollToBottom(false);
        }
    }

    /** Debounced: the query is read after the user stops typing. The text is kept only in this field, never logged. */
    private void scheduleSearch(String text) {
        if (!searching) return;
        AndroidUtilities.cancelRunOnUIThread(runSearch);
        searchSeq++;
        searchText = text == null ? "" : text.trim();
        searchLoadingMore = false;
        if (searchText.isEmpty()) {
            searchResults.clear();
            searchDone = false;
            searchMore = false;
            updateSearchCount();
            updateSpinners();
            rebuildRows(false);
            return;
        }
        AndroidUtilities.runOnUIThread(runSearch, SEARCH_DEBOUNCE_MS);
    }

    private void runSearch() {
        if (!searching || destroyed || searchText.isEmpty()) return;
        if (!engineRunning()) {
            showNotRunning();
            return;
        }
        final int seq = searchSeq;
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, null, null, null, searchText), result -> {
                if (destroyed || !searching || seq != searchSeq) return;
                searchResults.clear();
                searchDone = true;
                searchMore = false;
                if (result instanceof SecuredResult.Ok) {
                    final List<SecuredMessage> found = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                    searchResults.addAll(found);
                    searchMore = found.size() >= PAGE_SIZE;   // a full page: older matches may exist
                    emptyView.setText(R.string.secchat_search_none);
                } else {
                    // Emoji-only queries are refused by the engine: a hint, not an error.
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    emptyView.setText(e.getKind() == SecuredError.Kind.NOT_ALLOWED ? getContext().getString(R.string.secchat_search_emoji) : e.getMessage());
                }
                updateSearchCount();
                rebuildRows(false);
                scrollToLast(false);
            });
        } catch (Throwable t) {
            showError(null);
        }
    }

    /** AR13: "N found", or "N+ found" while older pages may still hold matches (never claims a complete count then). */
    private void updateSearchCount() {
        if (searchCount == null) return;
        if (!searching || !searchDone || searchResults.isEmpty()) {
            searchCount.setVisibility(View.GONE);
            return;
        }
        final Context c = searchCount.getContext();
        searchCount.setText(c.getString(searchMore ? R.string.sec_au_found_more : R.string.sec_au_found, searchResults.size()));
        searchCount.setVisibility(View.VISIBLE);
    }

    /** AR13: scrolling up in the results loads the next older page of matches (beforeId + search). */
    private void checkLoadMoreSearch() {
        if (!searching || !searchDone || !searchMore || searchLoadingMore || searchResults.isEmpty() || engine == null) return;
        if (System.currentTimeMillis() - olderFailedAt < 5000) return;
        final int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first > 6) return;
        searchLoadingMore = true;
        updateSpinners();
        final int seq = searchSeq;
        final String query = searchText;
        final long beforeId = searchResults.get(0).getMessageId();
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, beforeId, null, null, query), result -> {
                if (destroyed || !searching || seq != searchSeq) return;
                searchLoadingMore = false;
                updateSpinners();
                if (!(result instanceof SecuredResult.Ok)) {
                    olderFailedAt = System.currentTimeMillis();
                    return;
                }
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                final ArrayList<SecuredMessage> older = new ArrayList<>();
                for (SecuredMessage m : page) {
                    boolean have = false;
                    for (SecuredMessage x : searchResults) {
                        if (x.getMessageId() == m.getMessageId()) {
                            have = true;
                            break;
                        }
                    }
                    if (!have) older.add(m);
                }
                searchMore = page.size() >= PAGE_SIZE && !older.isEmpty();
                if (!older.isEmpty()) {
                    searchResults.addAll(0, older);
                    rebuildRows(true);
                }
                updateSearchCount();
            });
        } catch (Throwable t) {
            searchLoadingMore = false;
            updateSpinners();
            olderFailedAt = System.currentTimeMillis();
        }
    }

    /** A tapped match: leave search, then load and show the page around it. */
    private void openMatch(SecuredMessage m) {
        pendingJump = m.getMessageId();
        actionBar.closeSearchField();
    }

    // ---- selection -------------------------------------------------------------------------------------------

    private void toggleSelection(SecuredMessage m) {
        final boolean wasSelecting = !selectedIds.isEmpty();
        if (!selectedIds.remove(m.getMessageId())) {
            selectedIds.add(m.getMessageId());
        }
        updateSelectionUi(wasSelecting);
    }

    private void clearSelection() {
        if (selectedIds.isEmpty()) return;
        selectedIds.clear();
        updateSelectionUi(true);
    }

    private List<SecuredMessage> selectedMessages() {
        final ArrayList<SecuredMessage> list = new ArrayList<>();
        for (SecuredMessage m : shown()) {
            if (selectedIds.contains(m.getMessageId())) list.add(m);
        }
        return list;
    }

    private void updateSelectionUi(boolean wasSelecting) {
        final boolean selecting = !selectedIds.isEmpty();
        if (selecting && !actionBar.isActionModeShowed()) {
            actionBar.showActionMode();
        } else if (!selecting && actionBar.isActionModeShowed()) {
            actionBar.hideActionMode();
        }
        if (selecting) {
            selectedCountView.setNumber(selectedIds.size(), wasSelecting);
            boolean anyText = false;
            boolean allLive = true;
            for (SecuredMessage m : selectedMessages()) {
                if (m.getDeleted() || m.getMedia() != null) allLive = false;   // no Forward for media in v1
                if (!m.getDeleted() && !m.getText().isEmpty()) anyText = true;
            }
            // AR11: Reply for exactly one live message, when you can post here.
            final List<SecuredMessage> picked = selectedMessages();
            replyItem.setVisibility(picked.size() == 1 && !picked.get(0).getDeleted() && !searching && !notes && chatReady() && canPostHere()
                    ? View.VISIBLE : View.GONE);
            copyItem.setVisibility(anyText ? View.VISIBLE : View.GONE);
            forwardItem.setVisibility(allLive && chatReady() ? View.VISIBLE : View.GONE);
        }
        if (listView == null) return;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ChatMessageCell) {
                final SecuredMessage m = messageFor(child);
                final boolean sel = m != null && selectedIds.contains(m.getMessageId());
                final ChatMessageCell cell = (ChatMessageCell) child;
                cell.setCheckBoxVisible(selecting, true);
                cell.setChecked(sel, sel, true);
            } else if (child instanceof SecuredMediaCell) {
                final SecuredMediaCell cell = (SecuredMediaCell) child;
                final boolean sel = selectedIds.contains(cell.messageId());
                cell.setCheckBoxVisible(selecting, true);
                cell.setChecked(sel, true);
            }
        }
    }

    // ---- messages --------------------------------------------------------------------------------------------

    /** AR10: first-load spinner and the older-pages spinner follow the loading flags. */
    private void updateSpinners() {
        if (loadingView != null) loadingView.setVisibility(!initialLoaded && !loadFailed && !searching ? View.VISIBLE : View.GONE);
        if (olderSpinner != null) olderSpinner.setVisibility((loadingOlder && !searching) || (searching && searchLoadingMore) ? View.VISIBLE : View.GONE);
    }

    private void loadMessages() {
        if (engine == null) return;
        loadFailed = false;
        if (emptyView != null) {
            emptyView.setClickable(false);
            if (messages.isEmpty()) emptyView.setVisibility(View.GONE);
        }
        updateSpinners();
        // Enough of the newest messages to reach the first unread one (up to MAX_UNREAD_PAGE).
        final int count = unreadAtOpen > PAGE_SIZE - 5 ? Math.min(MAX_UNREAD_PAGE, unreadAtOpen + 10) : PAGE_SIZE;
        try {
            engine.messages(chatId, new MessagePage(count, null, null, null, null), result -> {
                if (destroyed) return;
                if (!(result instanceof SecuredResult.Ok)) {
                    // Show the engine's error, never the "no messages" text: the engine did not report an empty chat.
                    if (emptyView != null && messages.isEmpty()) {
                        showLoadFailed(((SecuredResult.Err) result).getError().getMessage());
                    } else {
                        initialLoaded = true;   // live messages are shown: drop the first-load spinner
                        updateSpinners();
                    }
                    return;
                }
                if (emptyView != null) emptyView.setText(emptyView.getContext().getString((notes ? R.string.secnotes_empty : R.string.sec_chat_empty)));
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                // Keep anything that arrived live while the first page was loading.
                final ArrayList<SecuredMessage> early = new ArrayList<>(messages);
                messages.clear();
                messages.addAll(page);
                for (SecuredMessage m : early) {
                    if (indexOf(m.getMessageId()) < 0) insertSorted(m);
                }
                endReached = page.size() < count;
                initialLoaded = true;
                updateSpinners();
                final int firstUnread = placeUnreadDivider(page);
                rebuildRows(false);
                if (firstUnread == 1) {
                    scrollToUnreadDivider();
                } else if (firstUnread == 2) {
                    // More unread than loaded: start at the oldest loaded message (no bar: the first unread isn't here).
                    layoutManager.scrollToPositionWithOffset(0, 0);
                } else {
                    scrollToBottom(false);
                }
                listView.post(this::updateMentionButton);
                applyDraftReply();
                engine.markRead(chatId);
            });
        } catch (Throwable t) {
            if (emptyView != null) showLoadFailed(emptyView.getContext().getString(R.string.sec_engine_failed_unknown));
        }
    }

    private void showLoadFailed(String reason) {
        loadFailed = true;
        updateSpinners();
        emptyView.setText(reason + "\n" + emptyView.getContext().getString(R.string.sec_au_tap_retry));
        emptyView.setClickable(true);
        emptyView.setVisibility(View.VISIBLE);
    }

    private void checkLoadOlder() {
        if (searching) {
            checkLoadMoreSearch();
            return;
        }
        if (!initialLoaded || loadingOlder || endReached || searching || messages.isEmpty() || engine == null) return;
        if (System.currentTimeMillis() - olderFailedAt < 5000) return;
        final int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first > 6) return;
        loadingOlder = true;
        updateSpinners();
        final long beforeId = messages.get(0).getMessageId();
        final int ep = epoch;
        try {
            engine.messages(chatId, new MessagePage(PAGE_SIZE, beforeId, null, null, null), result -> {
                if (destroyed || ep != epoch) return;
                loadingOlder = false;
                updateSpinners();
                if (!(result instanceof SecuredResult.Ok)) {
                    olderFailedAt = System.currentTimeMillis();
                    return;
                }
                final List<SecuredMessage> page = ((SecuredResult.Ok<List<SecuredMessage>>) result).getValue();
                final ArrayList<SecuredMessage> older = new ArrayList<>();
                for (SecuredMessage m : page) {
                    if (indexOf(m.getMessageId()) < 0) older.add(m);
                }
                if (page.size() < PAGE_SIZE || older.isEmpty()) endReached = true;
                if (older.isEmpty()) return;
                messages.addAll(0, older);
                rebuildRows(true);
            });
        } catch (Throwable t) {
            loadingOlder = false;
            updateSpinners();
            olderFailedAt = System.currentTimeMillis();
        }
    }

    private ArrayList<SecuredMessage> shown() {
        return searching ? searchResults : messages;
    }

    private int indexOf(long messageId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getMessageId() == messageId) return i;
        }
        return -1;
    }

    private void insertSorted(SecuredMessage m) {
        int i = messages.size();
        while (i > 0 && messages.get(i - 1).getTimeMs() > m.getTimeMs()) i--;
        messages.add(i, m);
    }

    /** New or changed message. A change for a message that is not loaded is ignored. */
    private void upsertMessage(SecuredMessage m, boolean mayInsert) {
        final boolean atBottom = isAtBottom();
        final int idx = indexOf(m.getMessageId());
        if (idx >= 0) {
            messages.set(idx, m);
        } else if (mayInsert && hasNewer) {
            // The window ends before the newest messages: do not leave a gap. Our own message jumps to the newest page.
            if (m.getOutgoing() && !searching) loadNewest();
            return;
        } else if (mayInsert) {
            insertSorted(m);
        } else {
            return;
        }
        if (replyingTo != null && replyingTo.getMessageId() == m.getMessageId()) {
            if (m.getDeleted()) cancelStrip();
            else replyingTo = m;
        }
        if (editing != null && editing.getMessageId() == m.getMessageId() && (m.getDeleted() || !m.getCanEdit())) {
            cancelStrip();
        }
        if (searching) {
            for (int i = 0; i < searchResults.size(); i++) {
                if (searchResults.get(i).getMessageId() == m.getMessageId()) searchResults.set(i, m);
            }
        }
        rebuildRows(true);
        if (idx < 0 && !searching && (atBottom || m.getOutgoing())) {
            scrollToBottom(true);
        }
    }

    private void removeMessages(List<Long> ids) {
        final HashSet<Long> gone = new HashSet<>(ids);
        // A removed media message: close its viewer, stop its voice and forget its bitmaps (the engine has already
        // closed its playback and deleted the file).
        SecuredMediaViewer.closeFor(chatId, gone);
        if (unreadMentions.removeAll(gone)) updateMentionButton();
        for (Long id : gone) {
            liveProgress.remove(id);
            if (bitmaps != null) bitmaps.forget(chatId, id);
            if (audio != null && audio.isCurrent(chatId, id)) audio.release();
        }
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            final long id = messages.get(i).getMessageId();
            if (gone.contains(id)) {
                messages.remove(i);
                objects.remove(id);
                objectSources.remove(id);
                changed = true;
            }
        }
        if (replyingTo != null && gone.contains(replyingTo.getMessageId())) cancelStrip();
        if (editing != null && gone.contains(editing.getMessageId())) cancelStrip();
        final boolean wasSelecting = !selectedIds.isEmpty();
        if (selectedIds.removeAll(gone) && wasSelecting) updateSelectionUi(true);
        if (searchResults.removeIf(x -> gone.contains(x.getMessageId()))) changed = true;
        if (changed) rebuildRows(true);
    }

    private static SecuredMessage withStatus(SecuredMessage m, MessageStatus status) {
        return new SecuredMessage(m.getChatId(), m.getMessageId(), m.getOutgoing(), m.getText(), m.getTimeMs(), status,
                m.getReplyTo(), m.getEdited(), m.getDeleted(), m.getCanEdit(), m.getCanDeleteForEveryone(),
                m.getForwarded(), m.getReactions(), m.getExpiresAtMs(), m.getSender(), m.getEvent(), m.getMedia(),
                m.getHistory(), m.getMentions(), m.getMentionsMe(), m.getUnread());
    }

    private MessageObject objectFor(SecuredMessage m) {
        final long id = m.getMessageId();
        MessageObject mo = objects.get(id);
        final SecuredMessage src = objectSources.get(id);
        if (mo == null || src == null || !SecuredMessageMapper.sameBubble(m, src)) {
            mo = mapper.map(m);
            objects.put(id, mo);
            objectSources.put(id, m);
        } else if (m.getStatus() != src.getStatus()) {
            mapper.applyStatus(mo, m);   // AR8: a tick patches the cached object, no new layout
            objectSources.put(id, m);
        }
        return mo;
    }

    // ---- rows ------------------------------------------------------------------------------------------------

    private static final class Row {
        final int kind;
        final long stableId;
        final SecuredMessage message;   // null for date rows; the whole message (media included) for media and caption rows
        final int dateSec;
        boolean pinnedTop;
        boolean pinnedBottom;
        /** ROW_MESSAGE that shows only the caption of a media message, joined under its ROW_MEDIA. */
        boolean caption;

        Row(int kind, long stableId, SecuredMessage message, int dateSec) {
            this.kind = kind;
            this.stableId = stableId;
            this.message = message;
            this.dateSec = dateSec;
        }
    }

    // One Calendar and the last day's [start, end) range, instead of a Calendar per message per rebuild. UI thread only.
    private final Calendar dayCalendar = Calendar.getInstance();
    private int dayRangeStart = Integer.MAX_VALUE;
    private int dayRangeEnd = Integer.MIN_VALUE;
    private java.util.TimeZone dayZone = java.util.TimeZone.getDefault();

    private int dayStart(int sec) {
        if (sec >= dayRangeStart && sec < dayRangeEnd) return dayRangeStart;
        dayCalendar.setTimeInMillis(sec * 1000L);
        dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
        dayCalendar.set(Calendar.MINUTE, 0);
        dayCalendar.set(Calendar.SECOND, 0);
        dayCalendar.set(Calendar.MILLISECOND, 0);
        final int start = (int) (dayCalendar.getTimeInMillis() / 1000L);
        dayCalendar.add(Calendar.DAY_OF_MONTH, 1);   // a day is 23 or 25 hours across a clock change
        dayRangeStart = start;
        dayRangeEnd = (int) (dayCalendar.getTimeInMillis() / 1000L);
        return start;
    }

    /** The time zone or clock can change while the screen is open: forget the cached day. */
    private void resetDayCache() {
        final java.util.TimeZone now = java.util.TimeZone.getDefault();
        if (!now.equals(dayZone)) {
            dayZone = now;
            dayCalendar.setTimeZone(now);
            dayRangeStart = Integer.MAX_VALUE;
            dayRangeEnd = Integer.MIN_VALUE;
        }
    }

    /**
     * A change to one message that moves no row: swaps its Row and rebinds just that cell. False when the message
     * is not a listed row or the change has a row of its own that may appear or go (reactions), so the caller rebuilds.
     */
    private boolean patchRowMessage(long messageId, SecuredMessage updated) {
        final int i = rowIndexOf(messageId);
        if (i < 0 || adapter == null) return false;
        final Row old = rows.get(i);
        if (old.message == null || old.message.getEvent() != null) return false;
        if (updated.getMedia() != null || old.message.getMedia() != null) {
            // A media message has two rows (media and caption): patch both, or rebuild if the shape may change.
            final int j = mediaRowIndexOf(messageId);
            if (j < 0 || updated.getMedia() == null || updated.getDeleted()
                    || old.message.getText().isEmpty() != updated.getText().isEmpty()) return false;
            if (j != i) patchRow(j, updated);
        }
        patchRow(i, updated);
        return true;
    }

    private void patchRow(int i, SecuredMessage updated) {
        final Row old = rows.get(i);
        final Row r = new Row(old.kind, old.stableId, updated, old.dateSec);
        r.pinnedTop = old.pinnedTop;
        r.pinnedBottom = old.pinnedBottom;
        r.caption = old.caption;
        rows.set(i, r);
        adapter.notifyItemChanged(i);
    }

    private int mediaRowIndexOf(long messageId) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            final Row r = rows.get(i);
            if (r.kind == ROW_MEDIA && r.message.getMessageId() == messageId) return i;
        }
        return -1;
    }

    /** The caption of a media message as a plain text message for the one text mapper: no media, quote or label. */
    private static SecuredMessage captionOf(SecuredMessage m) {
        return new SecuredMessage(m.getChatId(), m.getMessageId(), m.getOutgoing(), m.getText(), m.getTimeMs(), m.getStatus(),
                null, m.getEdited(), m.getDeleted(), false, m.getCanDeleteForEveryone(),
                false, m.getReactions(), m.getExpiresAtMs(), m.getSender(), null, null,
                m.getHistory(), m.getMentions(), m.getMentionsMe(), m.getUnread());
    }

    /** One line for the reply strip and quotes: the text, or "Photo", "Video"... for media without a caption. */
    private String previewText(SecuredMessage m) {
        if (m.getMedia() != null && m.getText().isEmpty() && getContext() != null) {
            return MediaText.kind(getContext(), m.getMedia().getKind());
        }
        return m.getText();
    }

    private static long senderKey(SecuredMessage m) {
        return m.getSender() != null ? m.getSender().getMemberId() : Long.MIN_VALUE;
    }

    private static boolean sameGroup(SecuredMessage a, SecuredMessage b) {
        return a.getOutgoing() == b.getOutgoing() && Math.abs(a.getTimeMs() - b.getTimeMs()) <= GROUP_SECONDS * 1000L
                && senderKey(a) == senderKey(b);   // groups: the name shows once, the avatar sits on the last bubble
    }

    private void rebuildRows(boolean animated) {
        resetDayCache();
        if (objects.size() > OBJECT_CACHE_CAP) {   // keep bubbles only for loaded messages, never every one ever built
            final HashSet<Long> live = new HashSet<>();
            for (SecuredMessage m : messages) live.add(m.getMessageId());
            objects.keySet().retainAll(live);
            objectSources.keySet().retainAll(live);
        }
        final ArrayList<Row> next = new ArrayList<>(messages.size() + 8);
        int lastDay = Integer.MIN_VALUE;
        for (SecuredMessage m : shown()) {
            final int sec = (int) (m.getTimeMs() / 1000L);
            final int day = dayStart(sec);
            if (day != lastDay) {
                next.add(new Row(ROW_DATE, DATE_ID_BASE + day, null, day));
                lastDay = day;
            }
            if (m.getMessageId() == unreadDividerBefore && !searching) {
                next.add(new Row(ROW_UNREAD, UNREAD_ROW_ID, null, sec));
            }
            if (m.getEvent() != null) {
                next.add(new Row(ROW_EVENT, m.getMessageId() * 4 + 3, m, sec));
                continue;
            }
            if (m.getMedia() != null && !m.getDeleted()) {
                // The media bubble, then its caption as its own joined text bubble (AGENTS.md "Media v1").
                next.add(new Row(ROW_MEDIA, m.getMessageId() * 4, m, sec));
                if (!m.getText().isEmpty()) {
                    final Row cap = new Row(ROW_MESSAGE, m.getMessageId() * 4 + 1, m, sec);
                    cap.caption = true;
                    next.add(cap);
                }
            } else {
                next.add(new Row(ROW_MESSAGE, m.getMessageId() * 4 + 1, m, sec));
            }
            if (!notes && !m.getDeleted() && !m.getReactions().isEmpty()) {
                next.add(new Row(ROW_REACTIONS, m.getMessageId() * 4 + 2, m, sec));
            }
        }
        for (int i = 0; i < next.size(); i++) {
            final Row r = next.get(i);
            if (!isBubble(r)) continue;
            final Row prev = i > 0 ? next.get(i - 1) : null;
            final Row after = i + 1 < next.size() ? next.get(i + 1) : null;
            // A caption is always joined to its media bubble above it.
            r.pinnedTop = r.caption || prev != null && isBubble(prev) && sameGroup(prev.message, r.message);
            r.pinnedBottom = after != null && (after.caption && after.message == r.message
                    || isBubble(after) && sameGroup(r.message, after.message));
        }

        if (!animated || adapter == null) {
            rows.clear();
            rows.addAll(next);
            if (adapter != null) adapter.notifyDataSetChanged();
        } else {
            final ArrayList<Row> old = new ArrayList<>(rows);
            final DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return next.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).stableId == next.get(n).stableId;
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    final Row a = old.get(o);
                    final Row b = next.get(n);
                    if (a.kind != b.kind || a.dateSec != b.dateSec || a.pinnedTop != b.pinnedTop
                            || a.pinnedBottom != b.pinnedBottom || a.caption != b.caption) return false;
                    if (a.message == null || b.message == null) return a.message == b.message;
                    if (a.kind == ROW_REACTIONS) return a.message.getReactions().equals(b.message.getReactions());
                    // AR8: only what the row draws (reactions have a row of their own)
                    return SecuredMessageMapper.sameBubble(a.message, b.message) && a.message.getStatus() == b.message.getStatus();
                }
            }, false);
            rows.clear();
            rows.addAll(next);
            diff.dispatchUpdatesTo(adapter);
        }
        updateSearchCount();
        if (emptyView != null) {
            emptyView.setVisibility((loadFailed || (searching ? searchDone : initialLoaded)) && rows.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (listView != null) {
            listView.post(() -> {
                updateVisibleParts();
                updateJumpButton(true);
            });
        }
    }

    private static boolean isBubble(Row r) {
        return r.kind == ROW_MESSAGE || r.kind == ROW_MEDIA;
    }

    private SecuredMessage messageAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        final Row r = rows.get(position);
        return isBubble(r) ? r.message : null;
    }

    /** The visible media cell of a message, or null. */
    private SecuredMediaCell mediaCellFor(long messageId) {
        if (listView == null) return null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof SecuredMediaCell && ((SecuredMediaCell) child).messageId() == messageId) {
                return (SecuredMediaCell) child;
            }
        }
        return null;
    }

    private SecuredMessage messageFor(View cell) {
        if (listView == null) return null;
        return messageAt(listView.getChildAdapterPosition(cell));
    }

    /** The message's last bubble row: its text bubble, or a media message's caption, or its media bubble. */
    private int rowIndexOf(long messageId) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            final Row r = rows.get(i);
            if (isBubble(r) && r.message.getMessageId() == messageId) return i;
        }
        return -1;
    }

    // ---- scrolling -------------------------------------------------------------------------------------------

    private boolean isAtBottom() {
        return listView == null || !listView.canScrollVertically(1);
    }

    /** To the newest message; when the loaded window ends earlier, loads the newest page first. */
    private void scrollToBottom(boolean smooth) {
        if (hasNewer && !searching) {
            loadNewest();
        } else {
            scrollToLast(smooth);
        }
    }

    private void scrollToLast(boolean smooth) {
        if (listView == null || rows.isEmpty()) return;
        final int last = rows.size() - 1;
        if (smooth && layoutManager.findLastVisibleItemPosition() >= last - 25) {
            listView.smoothScrollToPosition(last);
        } else {
            layoutManager.scrollToPosition(last);
        }
    }

    private void updateJumpButton(boolean animated) {
        if (jumpButton == null || listView == null) return;
        final int last = layoutManager.findLastVisibleItemPosition();
        if (!hasNewer && !listView.canScrollVertically(1)) unreadBelow = 0;
        if (unreadBelow > 0 && !hasNewer && last != RecyclerView.NO_POSITION) {
            // As in Telegram, the count drops as unread messages scroll into view: at most the received ones still below.
            int below = 0;
            for (int p = last + 1; p < rows.size() && below < unreadBelow; p++) {
                final Row r = rows.get(p);
                if (r.message != null && !r.caption && !r.message.getOutgoing()) below++;
            }
            unreadBelow = below;
        }
        if (jumpCounter != null) {
            jumpCounter.setText(unreadBelow > 99 ? "99+" : String.valueOf(unreadBelow));
            jumpCounter.setVisibility(unreadBelow > 0 ? View.VISIBLE : View.GONE);
        }
        final boolean show = !searching && !rows.isEmpty() && (hasNewer
                || last != RecyclerView.NO_POSITION && last < rows.size() - 2 && listView.canScrollVertically(1));
        if (show == jumpShown) return;
        jumpShown = show;
        updateMentionButton();
        jumpButton.animate().cancel();
        if (show) {
            jumpButton.setVisibility(View.VISIBLE);
            if (animated) {
                jumpButton.setScaleX(0.6f);
                jumpButton.setScaleY(0.6f);
                jumpButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
            } else {
                jumpButton.setAlpha(1f);
            }
        } else if (animated) {
            jumpButton.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> {
                        if (!jumpShown) jumpButton.setVisibility(View.INVISIBLE);
                    }).start();
        } else {
            jumpButton.setAlpha(0f);
            jumpButton.setVisibility(View.INVISIBLE);
        }
    }

    private void updateFloatingDate() {
        if (floatingDateView == null || listView == null || rows.isEmpty()) return;
        final int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first >= rows.size()) return;
        final Row r = rows.get(first);
        floatingDateView.setCustomDate(r.dateSec, false, true);
        // When the day's own pill is at the top of the list, the floating copy is not needed.
        final View firstView = layoutManager.findViewByPosition(first);
        final boolean pillVisible = r.kind == ROW_DATE && firstView != null && firstView.getTop() >= listView.getPaddingTop();
        showFloatingDate(scrollingByUser && !pillVisible);
    }

    private void showFloatingDate(boolean show) {
        if (floatingDateView == null || show == floatingDateShown) return;
        floatingDateShown = show;
        floatingDateView.animate().cancel();
        floatingDateView.animate().alpha(show ? 1f : 0f).setDuration(150).start();
    }

    /** Tells cells where they sit, for wallpaper-matched service colours and gradient bubbles (as ChannelAdminLogActivity). */
    private void updateVisibleParts() {
        if (listView == null || root == null) return;
        final int height = listView.getMeasuredHeight();
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ChatMessageCell) {
                final ChatMessageCell cell = (ChatMessageCell) child;
                final int top = cell.getTop();
                final int viewTop = top >= 0 ? 0 : -top;
                int viewBottom = cell.getMeasuredHeight();
                if (viewBottom > height) viewBottom = viewTop + height;
                cell.setVisiblePart(viewTop, viewBottom - viewTop, root.getHeightWithKeyboard() - listView.getTop(), 0,
                        cell.getY() + listView.getTop() - root.getBackgroundTranslationY(), root.getMeasuredWidth(),
                        root.getBackgroundSizeY(), 0, 0, 0);
            } else if (child instanceof ChatActionCell) {
                ((ChatActionCell) child).setVisiblePart(child.getY() + listView.getTop() - root.getBackgroundTranslationY(), root.getBackgroundSizeY());
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        isPaused = false;
        SecuredUi.nudgeLink();   // a link that waited (passcode, transition) shows now that this screen is resumed
        // Secured screens stay out of Recents thumbnails and screenshots (SPEC: protect screen, on by default).
        if (flagSecure == null && getParentActivity() != null) {
            flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
        }
        if (flagSecure != null) flagSecure.attach();
        Kit.reassertSecure(getParentActivity());
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        if (engine != null) engine.markRead(chatId);
        SecuredBackground.setOpenChat(chatId);
        SecuredBackground.clearChat(chatId);
        updateHeader();
    }

    @Override
    public void onPause() {
        super.onPause();
        isPaused = true;
        saveDraft();
        if (audio != null) audio.pause();
        SecuredMediaViewer.pauseAll();
        if (emojiPanel != null) emojiPanel.dismissPopups();
        if (voice != null) voice.onPause();
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        SecuredBackground.setOpenChat(null);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
        if (voice != null && !destroyed) voice.onPermissionResult(requestCode, grantResults);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (attach != null && !destroyed) attach.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (voice != null && voice.isActive()) {
            if (invoked) voice.onLockedCancel();
            return false;
        }
        if (mentionPicker != null && mentionPicker.isShowing()) {
            if (invoked) hideMentions();
            return false;
        }
        if (emojiShown) {
            if (invoked) hideEmojiPanel();
            return false;
        }
        if (!selectedIds.isEmpty()) {
            if (invoked) clearSelection();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return false;
    }

    private static String tg(int res) {
        return LocaleController.getString(res);
    }

    // ---- adapter ---------------------------------------------------------------------------------------------

    private final class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context context;

        MessagesAdapter(Context context) {
            this.context = context;
            setHasStableIds(true);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public long getItemId(int position) {
            return rows.get(position).stableId;
        }

        @Override
        public int getItemViewType(int position) {
            final Row r = rows.get(position);
            final int kind = r.kind;
            if (kind == ROW_MEDIA) {
                final MediaKind k = r.message.getMedia() != null ? r.message.getMedia().getKind() : MediaKind.FILE;
                return k == MediaKind.IMAGE || k == MediaKind.GIF || k == MediaKind.VIDEO ? VIEW_PHOTO
                        : k == MediaKind.VOICE ? VIEW_VOICE : VIEW_FILE;
            }
            if (kind == ROW_UNREAD) return VIEW_UNREAD;
            return kind == ROW_DATE ? VIEW_DATE : kind == ROW_EVENT ? VIEW_EVENT : kind == ROW_REACTIONS ? VIEW_REACTIONS : VIEW_MESSAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == VIEW_MESSAGE) {
                final ChatMessageCell cell = new SecuredMessageCell(context, currentAccount, sharedResources, chatResources);
                cell.setDelegate(cellDelegate);
                cell.setFullyDraw(true);
                cell.isChat = groupChat;   // groups: Telegram's name and avatar column, fed by the mapper
                // setAllowAssistant stays off: it would hand message text to the system assistant (onProvideStructure).
                cell.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                view = cell;
            } else if (viewType == VIEW_PHOTO) {
                view = new SecuredPhotoCell(context, chatResources, mediaHost);
            } else if (viewType == VIEW_VOICE) {
                view = new SecuredVoiceCell(context, chatResources, mediaHost);
            } else if (viewType == VIEW_FILE) {
                view = new SecuredFileCell(context, chatResources, mediaHost);
            } else if (viewType == VIEW_DATE || viewType == VIEW_EVENT) {
                // Separate view types: a recycled date cell never receives event text and the other way round.
                view = new ChatActionCell(context, false, chatResources);
            } else if (viewType == VIEW_UNREAD) {
                // Telegram's own "Unread messages" bar (theme colours and text only).
                final ChatUnreadCell cell = new ChatUnreadCell(context, chatResources);
                cell.setText(tg(org.telegram.messenger.R.string.UnreadMessages));
                view = cell;
            } else {
                view = new SecuredReactionsView(context, chatResources);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Row r = rows.get(position);
            final View view = holder.itemView;
            if (view instanceof ChatMessageCell) {
                final ChatMessageCell cell = (ChatMessageCell) view;
                cell.setSlidingOffset(0);
                if (cell instanceof SecuredMessageCell) {
                    ((SecuredMessageCell) cell).setSecuredStatus(r.message.getOutgoing() ? r.message.getStatus() : null, notes);
                }
                cell.setMessageObject(objectFor(r.caption ? captionOf(r.message) : r.message), null, r.pinnedBottom, r.pinnedTop, false);
                final boolean selecting = !selectedIds.isEmpty();
                final boolean sel = selectedIds.contains(r.message.getMessageId());
                cell.setCheckBoxVisible(selecting, false);
                cell.setChecked(sel, sel, false);
                cell.setHighlighted(highlightId == r.message.getMessageId());
            } else if (view instanceof SecuredMediaCell) {
                final SecuredMediaCell cell = (SecuredMediaCell) view;
                cell.setSlidingOffset(0);
                final boolean captionBelow = r.pinnedBottom && position + 1 < rows.size() && rows.get(position + 1).caption;
                cell.bind(r.message, r.pinnedTop, r.pinnedBottom, captionBelow, groupChat, notes);
                final long[] live = liveProgress.get(r.message.getMessageId());
                if (live != null) cell.setProgress(live[0], live[1]);
                final boolean selecting = !selectedIds.isEmpty();
                final boolean sel = selectedIds.contains(r.message.getMessageId());
                cell.setCheckBoxVisible(selecting, false);
                cell.setChecked(sel, false);
                cell.setHighlighted(highlightId == r.message.getMessageId());
            } else if (view instanceof ChatActionCell) {
                if (r.kind == ROW_EVENT) {
                    ((ChatActionCell) view).setCustomText(mapper.eventText(r.message.getEvent()));
                } else {
                    ((ChatActionCell) view).setCustomDate(r.dateSec, false, false);
                }
            } else if (view instanceof SecuredReactionsView) {
                ((SecuredReactionsView) view).bind(r.message, reactionListener);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof SecuredMediaCell) ((SecuredMediaCell) holder.itemView).onRecycled();
        }
    }

    // ---- list with swipe-to-reply ----------------------------------------------------------------------------

    /** RecyclerListView plus a simplified version of ChatActivity's swipe-left-to-reply. */
    private final class MessagesListView extends RecyclerListView {
        private final int touchSlop;
        private float downX;
        private float downY;
        private View swipeCell;   // a text bubble (ChatMessageCell) or a media bubble (SecuredMediaCell)
        private boolean swiping;
        private boolean swipeArmed;

        MessagesListView(Context context) {
            super(context, chatResources);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private float slidingOf(View cell) {
            return cell instanceof SecuredMediaCell ? ((SecuredMediaCell) cell).getSlidingOffsetX() : ((ChatMessageCell) cell).getSlidingOffsetX();
        }

        private void slideTo(View cell, float x) {
            if (cell instanceof SecuredMediaCell) ((SecuredMediaCell) cell).setSlidingOffset(x);
            else ((ChatMessageCell) cell).setSlidingOffset(x);
        }

        private boolean canSwipe(View cell) {
            if (notes || searching || !selectedIds.isEmpty() || editing != null || !chatReady() || !canPostHere()) return false;
            final SecuredMessage m = messageFor(cell);
            return m != null && !m.getDeleted();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            final boolean intercepted = super.onInterceptTouchEvent(e);
            if (intercepted) {
                if (!swiping) swipeCell = null;
                return true;
            }
            return trackSwipe(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (swiping) {
                trackSwipe(e);
                return true;
            }
            return super.onTouchEvent(e);
        }

        private boolean trackSwipe(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = e.getX();
                    downY = e.getY();
                    swiping = false;
                    swipeArmed = false;
                    final View v = findChildViewUnder(downX, downY);
                    swipeCell = (v instanceof ChatMessageCell || v instanceof SecuredMediaCell) && canSwipe(v) ? v : null;
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (swipeCell == null) return false;
                    final float dx = e.getX() - downX;
                    final float dy = e.getY() - downY;
                    if (!swiping) {
                        if (dx < -touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            swiping = true;
                            // Cancel the list's own click and long-press tracking for this gesture.
                            final MotionEvent cancel = MotionEvent.obtain(e);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.onInterceptTouchEvent(cancel);
                            cancel.recycle();
                            cancelClickRunnables(true);
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                        if (Math.abs(dy) > touchSlop) swipeCell = null;
                        return false;
                    }
                    final float offset = Math.max(-dp(100), Math.min(0, dx + touchSlop));
                    slideTo(swipeCell, offset);
                    final boolean armed = offset <= -dp(50);
                    if (armed != swipeArmed) {
                        swipeArmed = armed;
                        if (armed) {
                            try {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (swiping) {
                        finishSwipe(e.getActionMasked() == MotionEvent.ACTION_UP && swipeArmed);
                        return true;
                    }
                    swipeCell = null;
                    return false;
                }
                default:
                    return swiping;
            }
        }

        private void finishSwipe(boolean reply) {
            final View cell = swipeCell;
            swiping = false;
            swipeArmed = false;
            swipeCell = null;
            if (cell == null) return;
            final ValueAnimator back = ValueAnimator.ofFloat(slidingOf(cell), 0f);
            back.setDuration(200);
            back.setInterpolator(CubicBezierInterpolator.DEFAULT);
            back.addUpdateListener(a -> {
                slideTo(cell, (float) a.getAnimatedValue());
                invalidate();
            });
            back.start();
            if (reply) {
                startReply(messageFor(cell));
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            drawSenderAvatars(canvas);
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child instanceof ChatMessageCell || child instanceof SecuredMediaCell) {
                    final float offset = slidingOf(child);
                    if (offset < 0) drawReplyIcon(canvas, child, offset);
                }
            }
        }

        /**
         * ChatMessageCell never draws its own avatar: ChatActivity's list draws it (ImageReceiver from
         * getAvatarImage()), so we do the same for the last bubble of a run. The ImageReceiver holds only our local
         * AvatarDrawable; the cell has no user or chat, so a tap on it reaches no delegate call.
         */
        private void drawSenderAvatars(Canvas canvas) {
            if (!groupChat) return;
            final int maxY = getMeasuredHeight() - getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (!(child instanceof ChatMessageCell)) continue;
                final ChatMessageCell cell = (ChatMessageCell) child;
                final org.telegram.messenger.ImageReceiver avatar = cell.getAvatarImage();
                if (avatar == null || cell.drawPinnedBottom()) continue;
                int bottom = (int) child.getY() + child.getPaddingTop() + cell.getLayoutHeight();
                if (bottom > maxY) bottom = maxY;
                final int top = (int) child.getY() + child.getPaddingTop();
                if (bottom - dp(48) < top) bottom = top + dp(48);
                final int cellBottom = (int) child.getY() + child.getMeasuredHeight();
                if (bottom > cellBottom) bottom = cellBottom;
                avatar.setVisible(true, false);
                avatar.setImageY(bottom - dp(44));
                canvas.save();
                canvas.translate(cell.getSlidingOffsetX(), 0);
                avatar.draw(canvas);
                canvas.restore();
            }
        }

        private void drawReplyIcon(Canvas canvas, View cell, float offset) {
            final float progress = Math.max(0f, Math.min(1f, (-offset - dp(20)) / dp(30)));
            if (progress <= 0f) return;
            final float cx = getMeasuredWidth() + offset / 2f;
            final float cy = cell.getTop() + cell.getMeasuredHeight() / 2f;
            final float radius = dp(16) * progress;
            final Paint bg = chatResources.getPaint(Theme.key_paint_chatActionBackground);
            if (bg != null) {
                chatResources.applyServiceShaderMatrix(getMeasuredWidth(), AndroidUtilities.displaySize.y, 0, getY() + cy - radius);
                final int alpha = bg.getAlpha();
                bg.setAlpha((int) (alpha * progress));
                canvas.drawCircle(cx, cy, radius, bg);
                bg.setAlpha(alpha);
            }
            final Drawable icon = Theme.chat_replyIconDrawable;
            if (icon != null) {
                final int hw = (int) (icon.getIntrinsicWidth() / 2f * progress);
                final int hh = (int) (icon.getIntrinsicHeight() / 2f * progress);
                icon.setBounds((int) cx - hw, (int) cy - hh, (int) cx + hw, (int) cy + hh);
                icon.setAlpha((int) (255 * progress));
                icon.draw(canvas);
                icon.setAlpha(255);
            }
        }
    }

    // ---- header lock mark ------------------------------------------------------------------------------------

    /** Our own lock (LockIcon), not Telegram's secret-chat lock, before the title. */
    private final class LockMarkDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size = dp(13);

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            LockIcon.draw(canvas, paint, b.exactCenterX(), b.exactCenterY(), size, SecuredResourcesProvider.accentText(),
                    chatResources.getColor(Theme.key_actionBarDefault));
        }

        @Override
        public int getIntrinsicWidth() {
            return LockIcon.widthFor(size);
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
