package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.List;

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
 * to start one (create an invite, connect with a link). Rows are Telegram's own DialogCell fed through its
 * CustomDialog path, which carries only the strings and numbers we put in.
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
    private View fab;
    private final List<ChatSummary> chats = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

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
        body.addView(fab, LayoutHelper.createFrame(56, 56, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 16, 16));

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
        adapter = new ChatsAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < chats.size()) {
                // Open like a native chat: in the right-hand pane on tablets, not the floating layers window.
                presentFragment(new INavigationLayout.NavigationParams(
                        new SecuredChatActivity(chats.get(position).getChatId())).forceRightLayout());
            }
        });
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

    private View buildFab(Context context) {
        final View v = new View(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(11);
                p.setColor(Kit.color(Theme.key_chats_actionIcon));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - r, cy, cx + r, cy, p);
                canvas.drawLine(cx, cy - r, cx, cy + r, p);
            }
        };
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Kit.color(Theme.key_chats_actionBackground));
        v.setBackground(bg);
        v.setElevation(dp(6));
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setContentDescription(context.getString(R.string.sec_new_chat));
        v.setClickable(true);
        v.setOnClickListener(x -> showFabMenu());
        return v;
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
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(chats.isEmpty() ? View.GONE : View.VISIBLE);
        int unread = 0;
        for (ChatSummary c : chats) unread += c.getUnreadCount();
        host.setSecuredUnread(unread);
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
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            // Telegram's own row, with no fragment and our theme overlay. It only ever sees the CustomDialog below.
            final DialogCell cell = new DialogCell(null, context, false, false, org.telegram.messenger.UserConfig.selectedAccount, SecuredResourcesProvider.get());
            cell.useSeparator = true;
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final ChatSummary c = chats.get(position);
            final DialogCell.CustomDialog d = new DialogCell.CustomDialog();
            d.name = c.getDisplayName();
            if (!c.getReady()) {
                d.message = context.getString(R.string.sec_waiting_contact);
            } else if (c.getLastMessageText() != null) {
                d.message = c.getLastMessageText();
            } else {
                d.message = context.getString(R.string.sec_no_messages_yet);
            }
            d.id = (int) c.getChatId();
            d.unread_count = c.getUnreadCount();
            final long t = c.getLastMessageTimeMs() > 0 ? c.getLastMessageTimeMs() : System.currentTimeMillis();
            d.date = (int) (t / 1000L);
            d.type = 0;     // never 1 with isMedia (that combination crashes DialogCell) and never 2 (Telegram secret-chat look)
            d.sent = -1;
            ((DialogCell) holder.itemView).setDialog(d);
        }
    }
}
