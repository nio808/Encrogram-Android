package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.encrogram.core.api.ChatAction;
import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredProfile;
import app.encrogram.core.api.SecuredResult;

/**
 * The Secured page of the Chats tab: first-run profile, engine state, the list of Secured chats and the two ways
 * to start one (create an invite, connect with a link). Rows are Telegram's own DialogCell (through
 * {@link SecuredDialogCell}) fed through its CustomDialog path, which carries only the strings and numbers we put in.
 * The list has a local search row, long-press selection with pin, mute, mark read, archive and delete, swipe to
 * archive, an "Archived chats" row and Telegram's floating button. Everything is wired to {@link SecuredEngine} only.
 */
public class SecuredDialogsActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private enum Mode { STARTING, FAILED, PROFILE, LIST }

    private final EncrogramChatsHost host;
    private SecuredEngine engine;
    private boolean listening;
    private boolean destroyed;
    private boolean engineThrew;   // an engine call threw: show FAILED, never let it reach Telegram's Chats tab
    private boolean resumed;
    private String lastError;
    private String pendingLink;
    private Mode mode = Mode.STARTING;

    private FrameLayout root;
    private FrameLayout body;
    private View startingView, failedView, profileView, listFrame;
    private TextView failedText, profileError;
    private EditText nameInput;
    private TextView profileButton;
    private RecyclerListView listView;
    private LinearLayout emptyView;
    private FragmentFloatingButton fab;
    private final List<ChatSummary> chats = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    // ---- list state (local only; never persisted, never sent anywhere) ----
    private static final int T_SEARCH = 0, T_CHAT = 1, T_ARCHIVE = 2, T_TEXT = 3;
    private static final int TEXT_BACK = 1, TEXT_NO_RESULTS = 2;
    private static final int ID_PIN = 1, ID_MUTE = 2, ID_ARCHIVE = 3, ID_DELETE = 4, ID_READ = 5;
    private static final String ACTION_MODE_TAG = "secured_list";

    /** One row of the list. */
    private static final class Item {
        int type;
        long id;
        ChatRowMapper.Row row;   // T_CHAT and T_ARCHIVE
        int textKind;            // T_TEXT
    }

    private final List<Item> items = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final Set<Long> hidden = new HashSet<>();   // removed from view while an engine command is in flight
    private boolean showingArchived;
    private String query = "";
    private FrameLayout searchRow;
    private FragmentSearchField searchField;
    private NumberTextView selectedCount;
    private ActionBarMenuItem pinItem, muteItem, archiveItem, otherItem;
    private ImageView actionModeClose;
    private ChatRowMapper.Labels labels;

    SecuredDialogsActivity(EncrogramChatsHost host) {
        super();
        this.host = host;
    }

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return SecuredResourcesProvider.get();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    // ---- engine ----------------------------------------------------------------------------------------------

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onStateChanged(EngineState state) {
            try {
                updateMode();
            } catch (Throwable t) {
                engineFailed(t);
            }
        }

        @Override
        public void onChatsChanged() {
            refreshChats();
        }

        @Override
        public void onMessageReceived(SecuredMessage message) {
            refreshChats();
        }

        @Override
        public void onMessageStatusChanged(long chatId, long messageId, MessageStatus status) {
            refreshChats();
        }

        @Override
        public void onError(SecuredError error) {
            try {
                lastError = error.getMessage();
                if (safeState() == EngineState.FAILED) {
                    updateMode();
                }
            } catch (Throwable t) {
                engineFailed(t);
            }
        }
    };

    /** An engine call threw. Keep only the class name, then show the FAILED screen. */
    private void engineFailed(Throwable t) {
        engineThrew = true;
        Log.w("SecuredUi", "engine call failed: " + t.getClass().getName());
        try {
            if (!destroyed) showMode(Mode.FAILED);
        } catch (Throwable ignored) {
            // the screen itself is not ready; the next updateMode shows FAILED
        }
    }

    /** The engine state, or FAILED if the engine is missing or throws. */
    private EngineState safeState() {
        final SecuredEngine e = engine;
        if (e == null || engineThrew) return EngineState.FAILED;
        try {
            return e.state();
        } catch (Throwable t) {
            engineFailed(t);
            return EngineState.FAILED;
        }
    }

    private void ensureEngine() {
        try {
            if (engine == null) {
                engine = SecuredUi.engine();
            }
            if (engine != null && !listening) {
                listening = true;
                engine.addListener(listener);   // replays the state synchronously; views exist by now
            }
            if (engine != null && safeState() == EngineState.NOT_STARTED) {
                engine.start();   // the engine starts when Secured is first shown, not at app launch
            }
        } catch (Throwable t) {
            engineFailed(t);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        ensureEngine();
        applyInsets();
        updateMode();
        if (mode == Mode.PROFILE) {
            AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (engine != null && listening) {
            listening = false;
            try {
                engine.removeListener(listener);
            } catch (Throwable t) {
                Log.w("SecuredUi", "engine call failed: " + t.getClass().getName());
            }
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) listView.smoothScrollToPosition(0);
    }

    // ---- view ------------------------------------------------------------------------------------------------

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);
        actionBar.setCastShadows(false);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        for (int i = actionBar.getChildCount() - 1; i >= 0; i--) {
            if (actionBar.getChildAt(i) instanceof ChatsSwitcherView) actionBar.removeViewAt(i);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id >= ID_PIN && id <= ID_READ) onSelectionAction(id);
            }
        });
        labels = new ChatRowMapper.Labels(context.getString(R.string.sec_connecting), context.getString(R.string.sec_no_messages_yet));
        final ChatsSwitcherView switcher = new ChatsSwitcherView(context, host);
        actionBar.addView(switcher, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.LEFT | Gravity.BOTTOM, 22, 0, 0, 4));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        body = new FrameLayout(context);
        column.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        startingView = buildStarting(context);
        failedView = buildFailed(context);
        profileView = buildProfile(context);
        listFrame = buildList(context);
        body.addView(startingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        body.addView(failedView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        body.addView(profileView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        body.addView(listFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fab = buildFab(context);
        body.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());

        root = new FrameLayout(context);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        applyInsets();
        showMode(mode);
        return root;
    }

    private void applyInsets() {
        // The floating bottom bar of the tabs sits over this page: keep the list and the button clear of it.
        final int bottom = AndroidUtilities.navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom + dp(72));
        }
        if (fab != null) {
            fab.setTranslationY(-bottom);
        }
        if (failedView != null) failedView.setPadding(0, 0, 0, bottom);
        if (startingView != null) startingView.setPadding(0, 0, 0, bottom);
        if (profileView instanceof ScrollView) {
            profileView.setPadding(0, 0, 0, bottom);
        }
        if (emptyView != null) emptyView.setPadding(dp(32), 0, dp(32), bottom);
    }

    private View buildStarting(Context context) {
        final LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        final RadialProgressView p = new RadialProgressView(context, SecuredResourcesProvider.get());
        p.setSize(dp(36));
        p.setProgressColor(SecuredResourcesProvider.accentFill());
        l.addView(p, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL));
        final TextView t = Kit.text(context, context.getString(R.string.sec_engine_starting), 16, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        l.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        return l;
    }

    private View buildFailed(Context context) {
        final LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(32), 0, dp(32), 0);
        l.addView(Kit.text(context, context.getString(R.string.sec_engine_failed_title), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        failedText = Kit.text(context, "", 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        failedText.setGravity(Gravity.CENTER);
        l.addView(failedText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        final TextView retry = Kit.button(context, context.getString(R.string.sec_try_again), true);
        retry.setOnClickListener(v -> retry());
        l.addView(retry, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));
        return l;
    }

    private void retry() {
        lastError = null;
        engineThrew = false;
        showMode(Mode.STARTING);
        if (engine == null) {
            ensureEngine();   // maybe the engine can be created now
        } else {
            try {
                engine.start();
            } catch (Throwable t) {
                engineFailed(t);
            }
        }
        updateMode();
    }

    private View buildProfile(Context context) {
        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        final LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(28), dp(32), dp(28), dp(24));
        scroll.addView(l, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        l.addView(new Kit.LockBadge(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        final TextView title = Kit.text(context, context.getString(R.string.sec_profile_title), 22, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true);
        l.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView explain = Kit.text(context, context.getString(R.string.sec_profile_explain), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        explain.setGravity(Gravity.CENTER);
        l.addView(explain, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        final TextView hint = Kit.text(context, context.getString(R.string.sec_profile_hint), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        hint.setGravity(Gravity.CENTER);
        l.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        nameInput = new EditText(context);
        nameInput.setHint(R.string.sec_profile_name_hint);
        nameInput.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        nameInput.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        nameInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        nameInput.setSingleLine(true);
        nameInput.setMaxLines(1);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        nameInput.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 12));
        nameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                createProfile();
                return true;
            }
            return false;
        });
        l.addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        profileError = Kit.text(context, "", 14, Kit.color(Theme.key_text_RedBold), false);
        profileError.setVisibility(View.GONE);
        l.addView(profileError, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        profileButton = Kit.button(context, context.getString(R.string.sec_profile_continue), true);
        profileButton.setOnClickListener(v -> createProfile());
        l.addView(profileButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        return scroll;
    }

    private void createProfile() {
        if (engine == null) return;
        final String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) return;
        profileError.setVisibility(View.GONE);
        profileButton.setEnabled(false);
        profileButton.setText(R.string.sec_profile_creating);
        try {
            engine.createProfile(name, result -> {
                if (destroyed) return;
                profileButton.setEnabled(true);
                profileButton.setText(R.string.sec_profile_continue);
                if (result instanceof SecuredResult.Err) {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    profileError.setText(e.getMessage());
                    profileError.setVisibility(View.VISIBLE);
                } else {
                    AndroidUtilities.hideKeyboard(nameInput);
                    updateMode();
                }
            });
        } catch (Throwable t) {
            engineFailed(t);
        }
    }

    private View buildList(Context context) {
        final FrameLayout frame = new FrameLayout(context);

        listView = new RecyclerListView(context, SecuredResourcesProvider.get());
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        searchRow = buildSearchRow(context);
        adapter = new ChatsAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        listView.setOnItemLongClickListener((view, position) -> onRowLongClick(view, position));
        new ItemTouchHelper(new ArchiveSwipe()).attachToRecyclerView(listView);
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        emptyView.addView(new Kit.LockBadge(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        emptyView.addView(Kit.text(context, context.getString(R.string.sec_empty_title), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView sub = Kit.text(context, context.getString(R.string.sec_empty_text), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        sub.setGravity(Gravity.CENTER);
        emptyView.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        final TextView create = Kit.button(context, context.getString(R.string.sec_create_invite), true);
        create.setOnClickListener(v -> SecuredSheets.showInvite(this, engine));
        emptyView.addView(create, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));
        final TextView connect = Kit.button(context, context.getString(R.string.sec_connect_link), false);
        connect.setOnClickListener(v -> SecuredSheets.showConnect(this, engine, null));
        emptyView.addView(connect, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return frame;
    }

    private FragmentFloatingButton buildFab(Context context) {
        // Telegram's own floating button (reuse level 1). Its colours come from our provider (Encro Indigo).
        final FragmentFloatingButton b = new FragmentFloatingButton(context, SecuredResourcesProvider.get());
        b.setImageResource(org.telegram.messenger.R.drawable.msg_add);
        b.setContentDescription(context.getString(R.string.sec_new_chat));
        b.setOnClickListener(x -> showFabMenu());
        return b;
    }

    /** Telegram's search field as the first row of the list, so it scrolls away and comes back like any row. */
    private FrameLayout buildSearchRow(Context context) {
        final FrameLayout row = new FrameLayout(context);
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        searchField = new FragmentSearchField(context, SecuredResourcesProvider.get());
        searchField.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchField.setHintText(context.getString(R.string.sec_list_search_hint), false);
        searchField.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                final String next = s == null ? "" : s.toString();
                if (!next.equals(query)) {
                    query = next;   // local only: filters the list below, goes nowhere else
                    rebuildAndNotify();
                }
            }
        });
        searchField.editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                AndroidUtilities.hideKeyboard(searchField.editText);
                return true;
            }
            return false;
        });
        searchField.setCloseButtonOnClickListener(() -> {
            searchField.editText.getText().clear();
            AndroidUtilities.hideKeyboard(searchField.editText);
            searchField.editText.clearFocus();
        });
        row.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return row;
    }

    private void showFabMenu() {
        final Context context = getParentActivity();
        if (context == null || engine == null) return;
        final BottomSheet.Builder b = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        b.setItems(
                new CharSequence[]{context.getString(R.string.sec_create_invite), context.getString(R.string.sec_connect_link)},
                new int[]{org.telegram.messenger.R.drawable.msg_link2, org.telegram.messenger.R.drawable.msg_add},
                (d, which) -> {
                    if (which == 0) SecuredSheets.showInvite(this, engine);
                    else SecuredSheets.showConnect(this, engine, null);
                });
        showDialog(b.create());
    }

    // ---- state -> UI -----------------------------------------------------------------------------------------

    private void updateMode() {
        if (destroyed || fragmentView == null) return;
        final Mode next;
        switch (safeState()) {
            case NEEDS_PROFILE:
                next = Mode.PROFILE;
                break;
            case RUNNING:
                next = Mode.LIST;
                break;
            case FAILED:
                next = Mode.FAILED;
                break;
            default:
                next = Mode.STARTING;
        }
        showMode(next);
        if (next == Mode.LIST) {
            refreshChats();
            showPendingLinkIfReady();
        }
        if (next == Mode.PROFILE && resumed) {
            AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        }
    }

    private void showMode(Mode m) {
        mode = m;
        if (m != Mode.LIST) clearSelection();
        if (fragmentView == null || startingView == null || failedView == null || profileView == null
                || listFrame == null || fab == null || failedText == null) return;
        final Context c = fragmentView.getContext();
        startingView.setVisibility(m == Mode.STARTING ? View.VISIBLE : View.GONE);
        failedView.setVisibility(m == Mode.FAILED ? View.VISIBLE : View.GONE);
        profileView.setVisibility(m == Mode.PROFILE ? View.VISIBLE : View.GONE);
        listFrame.setVisibility(m == Mode.LIST ? View.VISIBLE : View.GONE);
        fab.setVisibility(m == Mode.LIST ? View.VISIBLE : View.GONE);
        if (m == Mode.FAILED) {
            String message = lastError;
            if (message == null && engine == null) {
                message = SecuredUi.engineError() != null
                        ? c.getString(R.string.sec_engine_missing) + " (" + SecuredUi.engineError() + ")"
                        : c.getString(R.string.sec_engine_missing);
            }
            failedText.setText(message != null ? message : c.getString(R.string.sec_engine_failed_unknown));
        }
    }

    private void refreshChats() {
        if (destroyed || engine == null || adapter == null) return;
        if (emptyView == null || listView == null) return;
        final List<ChatSummary> fresh;
        try {
            fresh = engine.chats();
        } catch (Throwable t) {
            engineFailed(t);
            return;
        }
        chats.clear();
        chats.addAll(fresh);
        rebuildAndNotify();
        emptyView.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(chats.isEmpty() ? View.GONE : View.VISIBLE);
        int unread = 0;
        for (ChatSummary c : chats) {
            if (!c.getMuted() && !c.getArchived()) unread += c.getUnreadCount();
        }
        host.setSecuredUnread(unread);
    }

    // ---- list model ------------------------------------------------------------------------------------------

    private ChatSummary find(long chatId) {
        for (ChatSummary c : chats) {
            if (c.getChatId() == chatId) return c;
        }
        return null;
    }

    private boolean matches(ChatSummary c, String q) {
        final String name = c.getDisplayName();
        final String text = c.getLastMessageText();
        return (name != null && name.toLowerCase(Locale.ROOT).contains(q))
                || (text != null && text.toLowerCase(Locale.ROOT).contains(q));
    }

    private Item chatItem(ChatSummary c) {
        final Item it = new Item();
        it.type = T_CHAT;
        it.id = c.getChatId();
        it.row = ChatRowMapper.map(c, labels, System.currentTimeMillis());
        return it;
    }

    private void rebuildItems() {
        items.clear();
        final Item search = new Item();
        search.type = T_SEARCH;
        search.id = -1;
        items.add(search);

        final String q = query.trim().toLowerCase(Locale.ROOT);
        int archivedCount = 0, archivedUnread = 0;
        long archivedTime = 0;
        for (ChatSummary c : chats) {
            if (c.getArchived() && !hidden.contains(c.getChatId())) {
                archivedCount++;
                archivedUnread += c.getUnreadCount();
                archivedTime = Math.max(archivedTime, c.getLastMessageTimeMs());
            }
        }
        if (archivedCount == 0) showingArchived = false;

        if (!q.isEmpty()) {
            boolean any = false;
            for (ChatSummary c : chats) {
                if (hidden.contains(c.getChatId()) || !matches(c, q)) continue;
                items.add(chatItem(c));
                any = true;
            }
            if (!any) items.add(textItem(TEXT_NO_RESULTS));
        } else if (showingArchived) {
            items.add(textItem(TEXT_BACK));
            for (ChatSummary c : chats) {
                if (c.getArchived() && !hidden.contains(c.getChatId())) items.add(chatItem(c));
            }
        } else {
            if (archivedCount > 0) {
                final Context ctx = fragmentView != null ? fragmentView.getContext() : null;
                final Item a = new Item();
                a.type = T_ARCHIVE;
                a.id = -2;
                final ChatRowMapper.Row r = new ChatRowMapper.Row();
                r.chatId = -2;
                r.name = ctx != null ? ctx.getString(R.string.sec_list_archived) : "";
                r.message = ctx != null ? ctx.getString(R.string.sec_list_archived_count, archivedCount) : "";
                r.unread = archivedUnread;
                r.dateSeconds = (int) ((archivedTime > 0 ? archivedTime : System.currentTimeMillis()) / 1000L);
                a.row = r;
                items.add(a);
            }
            for (ChatSummary c : chats) {
                if (!c.getArchived() && !hidden.contains(c.getChatId())) items.add(chatItem(c));
            }
        }

        // Selection only keeps chats that are still listed.
        if (!selected.isEmpty()) {
            final Set<Long> live = new HashSet<>();
            for (ChatSummary c : chats) {
                if (!hidden.contains(c.getChatId())) live.add(c.getChatId());
            }
            selected.retainAll(live);
        }
    }

    private Item textItem(int kind) {
        final Item t = new Item();
        t.type = T_TEXT;
        t.id = -10 - kind;
        t.textKind = kind;
        return t;
    }

    private void rebuildAndNotify() {
        if (adapter == null) return;
        rebuildItems();
        adapter.notifyDataSetChanged();
        updateActionMode();
    }

    private void setShowingArchived(boolean value) {
        showingArchived = value;
        rebuildAndNotify();
        if (listView != null) listView.scrollToPosition(0);
    }

    private void onRowClick(View view, int position) {
        if (position < 0 || position >= items.size()) return;
        final Item it = items.get(position);
        switch (it.type) {
            case T_CHAT:
                if (!selected.isEmpty()) {
                    toggleSelected(it.id, view);
                } else {
                    // Open like a native chat: in the right-hand pane on tablets, not the floating layers window.
                    presentFragment(new INavigationLayout.NavigationParams(
                            new SecuredChatActivity(it.id)).forceRightLayout());
                }
                break;
            case T_ARCHIVE:
                if (selected.isEmpty()) setShowingArchived(true);
                break;
            case T_TEXT:
                if (it.textKind == TEXT_BACK && selected.isEmpty()) setShowingArchived(false);
                break;
            default:
                break;
        }
    }

    private boolean onRowLongClick(View view, int position) {
        if (position < 0 || position >= items.size()) return false;
        final Item it = items.get(position);
        if (it.type != T_CHAT) return false;
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        toggleSelected(it.id, view);
        return true;
    }

    // ---- selection mode --------------------------------------------------------------------------------------

    private void toggleSelected(long chatId, View view) {
        if (!selected.remove(chatId)) selected.add(chatId);
        if (view instanceof DialogCell) ((DialogCell) view).setChecked(selected.contains(chatId), true);
        updateActionMode();
    }

    private void ensureActionMode() {
        if (actionBar.actionModeIsExist(ACTION_MODE_TAG)) return;
        final Context context = actionBar.getContext();
        final ActionBarMenu mode = actionBar.createActionMode(false, ACTION_MODE_TAG);

        // Same construction as Telegram's chat list action mode (DialogsActivity.createActionMode), our items only.
        actionModeClose = new ImageView(context);
        actionModeClose.setScaleType(ImageView.ScaleType.CENTER);
        actionModeClose.setImageDrawable(new BackDrawable(true));
        actionModeClose.setColorFilter(new android.graphics.PorterDuffColorFilter(getThemedColor(Theme.key_actionBarActionModeDefaultIcon), android.graphics.PorterDuff.Mode.MULTIPLY));
        actionModeClose.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarActionModeDefaultSelector)));
        actionModeClose.setOnClickListener(v -> clearSelection());
        mode.addView(actionModeClose, LayoutHelper.createLinear(54, 54, Gravity.CENTER_VERTICAL));

        selectedCount = new NumberTextView(context);
        selectedCount.setTextSize(18);
        selectedCount.setTypeface(AndroidUtilities.bold());
        selectedCount.setTextColor(getThemedColor(Theme.key_actionBarActionModeDefaultIcon));
        mode.addView(selectedCount, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 18, 0, 0, 0));
        selectedCount.setOnTouchListener((v, event) -> true);

        pinItem = mode.addItemWithWidth(ID_PIN, org.telegram.messenger.R.drawable.msg_pin, dp(48), context.getString(R.string.sec_list_pin));
        muteItem = mode.addItemWithWidth(ID_MUTE, org.telegram.messenger.R.drawable.msg_mute, dp(48), context.getString(R.string.sec_list_mute));
        archiveItem = mode.addItemWithWidth(ID_ARCHIVE, org.telegram.messenger.R.drawable.msg_archive, dp(48), context.getString(R.string.sec_list_archive));
        mode.addItemWithWidth(ID_DELETE, org.telegram.messenger.R.drawable.msg_delete, dp(48), context.getString(R.string.sec_list_delete));
        otherItem = mode.addItemWithWidth(0, org.telegram.messenger.R.drawable.ic_ab_other, dp(48), context.getString(R.string.sec_list_more));
        otherItem.addSubItem(ID_READ, org.telegram.messenger.R.drawable.msg_markread, context.getString(R.string.sec_list_mark_read));
    }

    private void updateActionMode() {
        if (destroyed || actionBar == null) return;
        if (selected.isEmpty()) {
            if (actionBar.isActionModeShowed(ACTION_MODE_TAG)) actionBar.hideActionMode();
            if (fab != null && !fab.getButtonVisible()) fab.setButtonVisible(true, true);
            return;
        }
        ensureActionMode();
        selectedCount.setNumber(selected.size(), actionBar.isActionModeShowed(ACTION_MODE_TAG));
        boolean allPinned = true, allMuted = true, anyUnread = false;
        for (long id : selected) {
            final ChatSummary c = find(id);
            if (c == null) continue;
            allPinned &= c.getPinned();
            allMuted &= c.getMuted();
            anyUnread |= c.getUnreadCount() > 0;
        }
        final Context ctx = actionBar.getContext();
        pinItem.setIcon(allPinned ? org.telegram.messenger.R.drawable.msg_unpin : org.telegram.messenger.R.drawable.msg_pin);
        pinItem.setContentDescription(ctx.getString(allPinned ? R.string.sec_list_unpin : R.string.sec_list_pin));
        muteItem.setIcon(allMuted ? org.telegram.messenger.R.drawable.msg_unmute : org.telegram.messenger.R.drawable.msg_mute);
        muteItem.setContentDescription(ctx.getString(allMuted ? R.string.sec_list_unmute : R.string.sec_list_mute));
        archiveItem.setIcon(showingArchived ? org.telegram.messenger.R.drawable.msg_unarchive : org.telegram.messenger.R.drawable.msg_archive);
        archiveItem.setContentDescription(ctx.getString(showingArchived ? R.string.sec_list_unarchive : R.string.sec_list_archive));
        if (anyUnread) otherItem.showSubItem(ID_READ);
        else otherItem.hideSubItem(ID_READ);
        if (!actionBar.isActionModeShowed(ACTION_MODE_TAG)) actionBar.showActionMode(true);
        if (fab != null && fab.getButtonVisible()) fab.setButtonVisible(false, true);
    }

    private void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View v = listView.getChildAt(i);
                if (v instanceof DialogCell) ((DialogCell) v).setChecked(false, true);
            }
        }
        updateActionMode();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selected.isEmpty()) {
            if (invoked) clearSelection();
            return false;
        }
        if (!query.isEmpty() && searchField != null) {
            if (invoked) searchField.editText.getText().clear();
            return false;
        }
        if (showingArchived) {
            if (invoked) setShowingArchived(false);
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void onSelectionAction(int id) {
        final List<Long> ids = new ArrayList<>(selected);
        if (ids.isEmpty()) return;
        switch (id) {
            case ID_PIN: {
                boolean all = true;
                for (long c : ids) {
                    final ChatSummary s = find(c);
                    all &= s != null && s.getPinned();
                }
                for (long c : ids) act(c, new ChatAction.SetPinned(!all), false);
                break;
            }
            case ID_MUTE: {
                boolean all = true;
                for (long c : ids) {
                    final ChatSummary s = find(c);
                    all &= s != null && s.getMuted();
                }
                for (long c : ids) act(c, new ChatAction.SetMuted(!all), false);
                break;
            }
            case ID_ARCHIVE:
                for (long c : ids) act(c, new ChatAction.SetArchived(!showingArchived), true);
                break;
            case ID_READ:
                for (long c : ids) markRead(c);
                break;
            case ID_DELETE:
                confirmDelete(ids);
                return;   // the selection stays until the user confirms
            default:
                return;
        }
        clearSelection();
    }

    // ---- engine commands (every call is guarded: nothing may crash Telegram's Chats tab) ----------------------

    /** Sends one chat command. {@code hide} removes the row at once and restores it if the engine says no. */
    private void act(long chatId, ChatAction action, boolean hide) {
        final SecuredEngine e = engine;
        if (e == null || destroyed) return;
        if (hide) {
            hidden.add(chatId);
            rebuildAndNotify();
        }
        try {
            e.chatAction(chatId, action, result -> {
                hidden.remove(chatId);
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    toast(((SecuredResult.Err) result).getError().getMessage());
                }
                refreshChats();
            });
        } catch (Throwable t) {
            hidden.remove(chatId);
            engineFailed(t);
        }
    }

    private void markRead(long chatId) {
        final SecuredEngine e = engine;
        if (e == null || destroyed) return;
        try {
            e.markRead(chatId);
            refreshChats();
        } catch (Throwable t) {
            engineFailed(t);
        }
    }

    private void toast(String message) {
        final Context c = getParentActivity();
        if (c == null || message == null) return;
        Toast.makeText(c, c.getString(R.string.sec_list_action_failed, message), Toast.LENGTH_LONG).show();
    }

    private void confirmDelete(List<Long> ids) {
        final Context context = getParentActivity();
        if (context == null || engine == null) return;
        final boolean one = ids.size() == 1;
        final AlertDialog.Builder b = new AlertDialog.Builder(context, SecuredResourcesProvider.get());
        b.setTitle(one ? context.getString(R.string.sec_list_delete_title) : context.getString(R.string.sec_list_delete_title_n, ids.size()));
        b.setMessage(context.getString(one ? R.string.sec_list_delete_text : R.string.sec_list_delete_text_n));
        final CheckBoxCell also = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, SecuredResourcesProvider.get());
        also.setText(context.getString(R.string.sec_list_delete_also), "", false, false);
        also.setOnClickListener(v -> also.setChecked(!also.isChecked(), true));
        final FrameLayout holder = new FrameLayout(context);
        holder.addView(also, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 8, 0, 8, 0));
        b.setView(holder);
        b.setNegativeButton(context.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(context.getString(R.string.sec_list_delete_confirm), (d, w) -> {
            final boolean notify = also.isChecked();
            for (long id : ids) act(id, new ChatAction.Delete(notify), true);
            clearSelection();
        });
        final AlertDialog dialog = b.create();
        showDialog(dialog);
        final View ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (ok instanceof TextView) ((TextView) ok).setTextColor(Theme.getColor(Theme.key_text_RedBold, SecuredResourcesProvider.get()));
    }

    // ---- swipe to archive (callback copied from DialogsActivity.SwipeController, archive only) ---------------

    private final class ArchiveSwipe extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (!selected.isEmpty() || viewHolder.getItemViewType() != T_CHAT || !(viewHolder.itemView instanceof DialogCell)) {
                return 0;
            }
            ((DialogCell) viewHolder.itemView).setSliding(true);
            return makeMovementFlags(0, ItemTouchHelper.LEFT);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            if (viewHolder == null) return;
            final int position = viewHolder.getAdapterPosition();
            if (position < 0 || position >= items.size() || items.get(position).type != T_CHAT) return;
            final long chatId = items.get(position).id;
            // Take the row out right away so the swiped view does not stay half open, then let the engine decide.
            hidden.add(chatId);   // act() clears it when the engine answers
            items.remove(position);
            adapter.notifyItemRemoved(position);
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed) rebuildAndNotify();   // archive row count, empty states
            }, 300);
            act(chatId, new ChatAction.SetArchived(!showingArchived), false);
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (viewHolder != null) {
                listView.hideSelector(false);
                if (viewHolder.itemView instanceof DialogCell) ((DialogCell) viewHolder.itemView).swipeCanceled = false;
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public long getAnimationDuration(RecyclerView recyclerView, int animationType, float animateDx, float animateDy) {
            if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) return 200;
            return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy);
        }

        @Override
        public float getSwipeThreshold(RecyclerView.ViewHolder viewHolder) {
            return 0.45f;
        }

        @Override
        public float getSwipeEscapeVelocity(float defaultValue) {
            return 3500;
        }

        @Override
        public float getSwipeVelocityThreshold(float defaultValue) {
            return Float.MAX_VALUE;
        }
    }

    // ---- deep links ------------------------------------------------------------------------------------------

    void setPendingLink(String link) {
        pendingLink = link;
    }

    void showPendingLinkIfReady() {
        if (pendingLink == null || !resumed || engine == null || safeState() != EngineState.RUNNING) {
            return;
        }
        final String link = pendingLink;
        pendingLink = null;
        SecuredSheets.showConnect(this, engine, link);
    }

    // ---- adapter ---------------------------------------------------------------------------------------------

    private final class ChatsAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ChatsAdapter(Context context) {
            this.context = context;
            setHasStableIds(true);   // keeps the search field (and its keyboard focus) alive while the rows below change
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != T_SEARCH;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View v;
            if (viewType == T_SEARCH) {
                AndroidUtilities.removeFromParent(searchRow);
                v = searchRow;
            } else if (viewType == T_TEXT) {
                final TextView t = Kit.text(context, "", 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(22), 0, dp(22), 0);
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
                v = t;
            } else {
                // Telegram's own row, with no fragment and our theme overlay. It only ever sees the CustomDialog.
                v = new SecuredDialogCell(context);
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Item it = items.get(position);
            switch (it.type) {
                case T_CHAT: {
                    final SecuredDialogCell cell = (SecuredDialogCell) holder.itemView;
                    cell.bind(it.row, showingArchived, true);
                    cell.setChecked(selected.contains(it.id), false);
                    break;
                }
                case T_ARCHIVE:
                    ((SecuredDialogCell) holder.itemView).bind(it.row, false, false);
                    break;
                case T_TEXT: {
                    final TextView t = (TextView) holder.itemView;
                    if (it.textKind == TEXT_BACK) {
                        t.setText(R.string.sec_list_archived_back);
                        t.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlueText));
                    } else {
                        t.setText(R.string.sec_list_no_results);
                        t.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
}
