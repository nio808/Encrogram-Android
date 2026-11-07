package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * A Secured conversation. It is Telegram scaffolding (BaseFragment, ActionBar, RecyclerListView, EditTextCaption and
 * the send button) wired only to the Secured engine. It is deliberately not a ChatActivity: no MessagesController,
 * SendMessagesHelper, FileLoader, ImageLoader, NotificationsController, link previews, stickers or browser.
 */
public class SecuredChatActivity extends BaseFragment {

    private static final int TYPE_MESSAGE = 0;
    private static final int TYPE_DATE = 1;

    private final long chatId;
    private SecuredEngine engine;
    private boolean destroyed;

    private final List<SecuredMessage> messages = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();   // SecuredMessage or String (date label)

    private ActionBar bar;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView emptyView;
    private SecuredComposerEditText input;
    private ChatActivityEnterView.SendButton sendButton;
    private BackupImageView avatar;
    private AvatarDrawable avatarDrawable;
    private boolean sendShown;

    private String name = "";

    public SecuredChatActivity(long chatId) {
        super();
        this.chatId = chatId;
    }

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onStateChanged(EngineState state) {
        }

        @Override
        public void onChatsChanged() {
            updateHeader();
        }

        @Override
        public void onMessageReceived(SecuredMessage message) {
            if (message.getChatId() != chatId || destroyed) return;
            upsert(message);
            if (!message.getOutgoing() && engine != null && isVisibleToUser()) {
                engine.markRead(chatId);
            }
        }

        @Override
        public void onMessageStatusChanged(long cid, long messageId, MessageStatus status) {
            if (cid != chatId || destroyed) return;
            for (int i = 0; i < messages.size(); i++) {
                final SecuredMessage m = messages.get(i);
                if (m.getMessageId() == messageId) {
                    messages.set(i, new SecuredMessage(m.getChatId(), m.getMessageId(), m.getOutgoing(), m.getText(), m.getTimeMs(), status));
                    rebuildRows();
                    return;
                }
            }
        }

        @Override
        public void onError(SecuredError error) {
        }
    };

    private boolean isVisibleToUser() {
        return fragmentView != null && !isPaused;
    }

    private boolean isPaused = true;

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return SecuredResourcesProvider.get();
    }

    @Override
    public boolean onFragmentCreate() {
        engine = SecuredUi.engine();
        if (engine == null) {
            return false;
        }
        engine.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (engine != null) {
            engine.removeListener(listener);
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        final ChatSummary summary = summary();
        name = summary != null ? summary.getDisplayName() : "";

        bar = actionBar;
        bar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        bar.setAllowOverlayTitle(false);
        bar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        bar.setTitle(name);
        bar.setAdditionalTextLeft(dp(32));
        avatarDrawable = new AvatarDrawable(SecuredResourcesProvider.get());
        avatarDrawable.setInfo(chatId, name, null);
        avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(20));
        avatar.setImageDrawable(avatarDrawable);
        final int top = AndroidUtilities.isTablet() ? 8 : (int) (AndroidUtilities.statusBarHeight / AndroidUtilities.density) + 8;
        bar.addView(avatar, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.LEFT, 56, top, 0, 0));
        updateHeader();

        final int chatBackground = SecuredResourcesProvider.chatBackground();
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(chatBackground);
        fragmentView = root;

        // Message list.
        final FrameLayout listFrame = new FrameLayout(context);
        listView = new RecyclerListView(context, SecuredResourcesProvider.get());
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(8), 0, dp(8));
        listView.setVerticalScrollBarEnabled(true);
        adapter = new MessagesAdapter(context);
        listView.setAdapter(adapter);
        listFrame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView = Kit.text(context, context.getString(R.string.sec_chat_empty), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        root.addView(buildComposer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        loadMessages();
        return root;
    }

    // ---- header ----------------------------------------------------------------------------------------------

    private ChatSummary summary() {
        if (engine == null) return null;
        for (ChatSummary c : engine.chats()) {
            if (c.getChatId() == chatId) return c;
        }
        return null;
    }

    private void updateHeader() {
        if (bar == null || destroyed) return;
        final ChatSummary s = summary();
        final Context c = bar.getContext();
        if (s != null && !s.getDisplayName().equals(name)) {
            name = s.getDisplayName();
            bar.setTitle(name);
            avatarDrawable.setInfo(chatId, name, null);
            avatar.invalidate();
        }
        // No last seen, no presence: the subtitle states the network and the verification the engine reports.
        final CharSequence sub;
        if (s != null && !s.getReady()) {
            sub = c.getString(R.string.sec_chat_status_waiting, name);
        } else if (s != null && s.getVerified()) {
            sub = c.getString(R.string.sec_chat_status_verified);
        } else {
            sub = c.getString(R.string.sec_chat_status_not_verified);
        }
        bar.setSubtitle(sub);
    }

    // ---- composer --------------------------------------------------------------------------------------------

    private View buildComposer(Context context) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(8), dp(6), dp(8), dp(8));

        final FrameLayout field = new FrameLayout(context);
        field.setBackground(Kit.roundRect(Kit.color(Theme.key_chat_messagePanelBackground), 22));
        input = new SecuredComposerEditText(context, SecuredResourcesProvider.get());
        input.setHint(context.getString(R.string.sec_composer_hint));
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
            }
        });
        return row;
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

    private void send() {
        if (engine == null) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        engine.sendText(chatId, text, result -> {
            if (destroyed) return;
            final Context context = getContext();
            if (result instanceof SecuredResult.Ok) {
                upsert(((SecuredResult.Ok<SecuredMessage>) result).getValue());
            } else if (context != null) {
                // Fail closed with a readable reason, and give the text back so nothing is lost.
                final SecuredError e = ((SecuredResult.Err) result).getError();
                if (input != null && input.getText().length() == 0) {
                    input.setText(text);
                    input.setSelection(text.length());
                }
                Toast.makeText(context, context.getString(R.string.sec_send_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- messages --------------------------------------------------------------------------------------------

    private void loadMessages() {
        if (engine == null) return;
        engine.messages(chatId, 200, result -> {
            if (destroyed) return;
            if (!(result instanceof SecuredResult.Ok)) {
                // Show the engine's error, never the "no messages" text: the engine did not report an empty chat.
                if (emptyView != null && messages.isEmpty()) {
                    emptyView.setText(((SecuredResult.Err) result).getError().getMessage());
                    emptyView.setVisibility(View.VISIBLE);
                }
                return;
            }
            if (emptyView != null) emptyView.setText(emptyView.getContext().getString(R.string.sec_chat_empty));
            messages.clear();
            messages.addAll(((SecuredResult.Ok<List<SecuredMessage>>) result).getValue());
            rebuildRows();
            scrollToEnd(false);
            engine.markRead(chatId);
        });
    }

    private void upsert(SecuredMessage m) {
        boolean replaced = false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getMessageId() == m.getMessageId()) {
                messages.set(i, m);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            messages.add(m);
        }
        final boolean nearEnd = layoutManager == null || layoutManager.findLastVisibleItemPosition() >= rows.size() - 2;
        rebuildRows();
        if (!replaced && (nearEnd || m.getOutgoing())) {
            scrollToEnd(true);
        }
    }

    private void rebuildRows() {
        rows.clear();
        String lastDay = null;
        final Context c = getContext();
        for (SecuredMessage m : messages) {
            final String day = dayLabel(c, m.getTimeMs());
            if (!day.equals(lastDay)) {
                rows.add(day);
                lastDay = day;
            }
            rows.add(m);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) {
            emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void scrollToEnd(boolean smooth) {
        if (listView == null || rows.isEmpty()) return;
        if (smooth) listView.smoothScrollToPosition(rows.size() - 1);
        else layoutManager.scrollToPositionWithOffset(rows.size() - 1, 0);
    }

    private static String dayLabel(Context c, long timeMs) {
        final Calendar now = Calendar.getInstance();
        final Calendar t = Calendar.getInstance();
        t.setTimeInMillis(timeMs);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return c != null ? c.getString(R.string.sec_today) : "";
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return c != null ? c.getString(R.string.sec_yesterday) : "";
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(timeMs));
    }

    private void showMessageMenu(SecuredMessage m) {
        final Context context = getParentActivity();
        if (context == null) return;
        final BottomSheet.Builder b = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        b.setItems(new CharSequence[]{context.getString(R.string.sec_copy)},
                new int[]{org.telegram.messenger.R.drawable.msg_copy}, (d, which) -> SecuredSheets.copyToClipboard(context, m.getText(), true));
        showDialog(b.create());
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        isPaused = false;
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        if (engine != null) engine.markRead(chatId);
    }

    @Override
    public void onPause() {
        super.onPause();
        isPaused = true;
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return false;
    }

    // ---- adapter ---------------------------------------------------------------------------------------------

    private final class MessagesAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        MessagesAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? TYPE_DATE : TYPE_MESSAGE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View v = viewType == TYPE_DATE ? new DateView(context) : new BubbleView(context);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Object row = rows.get(position);
            if (row instanceof String) {
                ((DateView) holder.itemView).set((String) row);
            } else {
                final SecuredMessage m = (SecuredMessage) row;
                final BubbleView v = (BubbleView) holder.itemView;
                v.set(m);
                v.setOnLongClickListener(x -> {
                    showMessageMenu(m);
                    return true;
                });
            }
        }
    }
}
