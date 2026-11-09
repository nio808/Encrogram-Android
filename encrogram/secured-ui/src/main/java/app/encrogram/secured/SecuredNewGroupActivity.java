package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import app.encrogram.core.api.ChatKind;
import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.GroupMember;
import app.encrogram.core.api.MemberStatus;
import app.encrogram.core.api.SecuredEngineKt;
import app.encrogram.core.api.SecuredResult;

/**
 * New group, new channel, and "add members to this group" (one screen, three modes). A name field and a checkbox list
 * of the user's Secured contacts (direct chats from the engine; no Saved Messages, no groups). Then
 * {@code createGroup} and {@code addMembers}, and the new chat opens like the chat list opens chats.
 *
 * Reuse: Telegram's HeaderCell, TextInfoPrivacyCell, ShadowSectionCell, EditTextBoldCursor and AvatarDrawable as they
 * are; the contact row is our {@link SecuredMemberRow}. Nothing here touches Telegram's contacts or MessagesController.
 */
final class SecuredNewGroupActivity extends SecuredGroupScreen {

    private static final int MENU_DONE = 1;
    private static final int MAX_NAME = 64;

    private static final int T_NAME = 0, T_TEXT = 1, T_SHADOW = 2, T_HEADER = 3, T_CONTACT = 4;

    private final boolean channel;
    /** True when the Secured list is in the tablet's tabs host: the new chat then opens in the right-hand pane. */
    private final boolean rightPane;
    /** Non-zero: add people to this existing group instead of creating one. */
    private final long addToChatId;

    private final List<ChatSummary> contacts = new ArrayList<>();
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private final List<int[]> rows = new ArrayList<>();   // {type, contactIndex}
    private EditTextBoldCursor nameField;
    private FrameLayout nameRow;
    private final AvatarDrawable preview = new AvatarDrawable(SecuredResourcesProvider.get());
    private View previewView;
    private RecyclerListView.SelectionAdapter adapter;
    private boolean busy;

    /** New group or new channel. */
    SecuredNewGroupActivity(boolean channel, boolean rightPane) {
        this.channel = channel;
        this.rightPane = rightPane;
        this.addToChatId = 0;
    }

    /** Add people to the existing group or channel {@code chatId}. */
    SecuredNewGroupActivity(long chatId, boolean channel) {
        this.channel = channel;
        this.rightPane = false;
        this.addToChatId = chatId;
    }

    private boolean adding() {
        return addToChatId != 0;
    }

    @Override
    CharSequence title(Context context) {
        return context.getString(adding() ? (channel ? R.string.secgroup_add_subscribers : R.string.secgroup_add_members)
                : channel ? R.string.secgroup_new_channel : R.string.secgroup_new_group);
    }

    @Override
    public View createView(Context context) {
        loadContacts();
        if (adding()) loadMembers();
        final View v = super.createView(context);
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_DONE, org.telegram.messenger.R.drawable.ic_ab_done, SecuredResourcesProvider.get())
                .setContentDescription(context.getString(adding() ? R.string.secgroup_add : R.string.secgroup_create));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || !(view instanceof SecuredMemberRow)) return;
            final int[] r = rows.get(position);
            if (r[0] != T_CONTACT) return;
            final long id = contacts.get(r[1]).getChatId();
            final boolean now = selected.add(id);
            if (!now) selected.remove(id);
            ((SecuredMemberRow) view).setChecked(now);
            final int header = headerPosition();
            if (header >= 0) adapter.notifyItemChanged(header);
        });
        return v;
    }

    @Override
    void onMenuItem(int id) {
        if (id == MENU_DONE) onDone();
    }

    @Override
    void onEngineChanged() {
        loadContacts();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (nameField != null && !adding() && nameField.getText().length() == 0) {
            nameField.requestFocus();
            AndroidUtilities.showKeyboard(nameField);
        }
    }

    // ---- data ------------------------------------------------------------------------------------------------

    /** Contacts already in the group (by their 1:1 chat id), left out of the "Add members" list. */
    private final HashSet<Long> inGroup = new HashSet<>();

    private void loadMembers() {
        try {
            engine.members(addToChatId, result -> ui(() -> {
                if (destroyed || !(result instanceof SecuredResult.Ok)) return;
                inGroup.clear();
                for (GroupMember m : ((SecuredResult.Ok<List<GroupMember>>) result).getValue()) {
                    final Long contact = m.getContactChatId();
                    if (contact != null && m.getStatus() != MemberStatus.LEFT && m.getStatus() != MemberStatus.REMOVED) inGroup.add(contact);
                }
                loadContacts();
                if (adapter != null) adapter.notifyDataSetChanged();
            }));
        } catch (Throwable ignored) {
        }
    }

    private void loadContacts() {
        contacts.clear();
        try {
            for (ChatSummary c : engine.chats()) {
                if (c.getKind() == ChatKind.DIRECT && c.getChatId() > 0 && c.getChatId() != SecuredEngineKt.SAVED_MESSAGES_CHAT_ID
                        && !SecuredEngineKt.isGroupChatId(c.getChatId()) && c.getReady() && !inGroup.contains(c.getChatId())) {
                    contacts.add(c);
                }
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(contacts, (a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        // A contact that disappeared cannot stay ticked.
        final LinkedHashSet<Long> alive = new LinkedHashSet<>();
        for (ChatSummary c : contacts) alive.add(c.getChatId());
        selected.retainAll(alive);
        buildRows();
    }

    private void buildRows() {
        rows.clear();
        if (!adding()) {
            rows.add(new int[]{T_NAME, 0});
            rows.add(new int[]{T_SHADOW, 0});
        }
        rows.add(new int[]{T_HEADER, 0});
        if (contacts.isEmpty()) {
            rows.add(new int[]{T_TEXT, 1});
        } else {
            for (int i = 0; i < contacts.size(); i++) rows.add(new int[]{T_CONTACT, i});
        }
        rows.add(new int[]{T_SHADOW, 0});
        rows.add(new int[]{T_TEXT, channel && !adding() ? 2 : 0});
    }

    private int headerPosition() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i)[0] == T_HEADER) return i;
        return -1;
    }

    // ---- create / add ----------------------------------------------------------------------------------------

    private void onDone() {
        if (busy || engine == null || destroyed) return;
        final Activity activity = getParentActivity();
        if (activity == null) return;
        if (adding()) {
            if (selected.isEmpty()) {
                finishFragment();
                return;
            }
            addPeople(addToChatId, false);
            return;
        }
        final String name = nameField != null ? nameField.getText().toString().trim() : "";
        if (name.isEmpty()) {
            showError(activity.getString(R.string.secgroup_name_empty));
            return;
        }
        if (!channel) {
            create(name);
            return;
        }
        // The honest notice, before a channel exists (docs/groups-plan.md).
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, SecuredResourcesProvider.get());
        b.setTitle(activity.getString(R.string.secgroup_new_channel));
        b.setMessage(activity.getString(R.string.secgroup_fact_channel_size));
        b.setNegativeButton(activity.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(activity.getString(R.string.secgroup_create_channel_confirm), (d, w) -> create(name));
        Kit.show(this, b.create());
    }

    private void create(String name) {
        setBusy(true);
        try {
            engine.createGroup(name, channel, result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    setBusy(false);
                    showError(getParentActivity().getString(R.string.secgroup_create_failed, errorText((SecuredResult.Err) result)));
                    return;
                }
                final long chatId = ((SecuredResult.Ok<Long>) result).getValue();
                if (selected.isEmpty()) {
                    openChat(chatId);
                } else {
                    addPeople(chatId, true);
                }
            }));
        } catch (Throwable t) {
            setBusy(false);
            showThrown(t, R.string.secgroup_create_failed);
        }
    }

    /** Invites the ticked contacts. After a create the chat opens either way; a failure is shown, never silent. */
    private void addPeople(long chatId, boolean thenOpen) {
        setBusy(true);
        final List<Long> ids = new ArrayList<>(selected);
        final Context appContext = getParentActivity() != null ? getParentActivity().getApplicationContext() : null;
        try {
            engine.addMembers(chatId, ids, result -> ui(() -> {
                if (destroyed) return;
                setBusy(false);
                final boolean failed = result instanceof SecuredResult.Err;
                if (thenOpen) {
                    if (failed && appContext != null) {
                        Toast.makeText(appContext, appContext.getString(R.string.secgroup_add_partial_failed, errorText((SecuredResult.Err) result)), Toast.LENGTH_LONG).show();
                    }
                    openChat(chatId);
                } else if (failed) {
                    showError(getParentActivity().getString(R.string.secgroup_action_failed, errorText((SecuredResult.Err) result)));
                } else {
                    if (appContext != null) Toast.makeText(appContext, R.string.secgroup_add_done, Toast.LENGTH_SHORT).show();
                    finishFragment();
                }
            }));
        } catch (Throwable t) {
            setBusy(false);
            if (thenOpen) openChat(chatId);
            else showThrown(t, R.string.secgroup_action_failed);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        if (actionBar != null && actionBar.createMenu() != null) {
            actionBar.createMenu().setAlpha(b ? 0.5f : 1f);
        }
    }

    /** Opens the new chat the way the chat list does: right-hand pane inside the tablet tabs host. */
    private void openChat(long chatId) {
        final SecuredChatActivity chat = new SecuredChatActivity(chatId);
        if (rightPane) {
            presentFragment(new INavigationLayout.NavigationParams(chat.inRightPane()).forceRightLayout());
            removeSelfFromStack();
        } else {
            presentFragment(chat, true);
        }
    }

    // ---- list ------------------------------------------------------------------------------------------------

    @Override
    RecyclerListView.SelectionAdapter buildAdapter(Context context) {
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == T_CONTACT;
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position)[0];
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                final View v;
                switch (viewType) {
                    case T_NAME:
                        v = nameRow(context);
                        break;
                    case T_HEADER:
                        v = new HeaderCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_SHADOW:
                        v = new ShadowSectionCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_TEXT:
                        v = new TextInfoPrivacyCell(context, SecuredResourcesProvider.get());
                        break;
                    default:
                        v = new SecuredMemberRow(context, true);
                        break;
                }
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final int[] r = rows.get(position);
                final Context c = holder.itemView.getContext();
                switch (r[0]) {
                    case T_HEADER: {
                        String text = c.getString(adding() ? (channel ? R.string.secgroup_add_subscribers : R.string.secgroup_add_members)
                                : channel ? R.string.secgroup_pick_header_channel : R.string.secgroup_pick_header_group);
                        if (!selected.isEmpty()) text += " · " + c.getString(R.string.secgroup_pick_selected, selected.size());
                        ((HeaderCell) holder.itemView).setText(text);
                        break;
                    }
                    case T_TEXT: {
                        final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                        cell.setText(c.getString(r[1] == 1 ? R.string.secgroup_pick_none : r[1] == 2 ? R.string.secgroup_fact_channel_size : R.string.secgroup_pick_hint));
                        cell.setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
                        break;
                    }
                    case T_CONTACT: {
                        final ChatSummary s = contacts.get(r[1]);
                        final boolean last = r[1] == contacts.size() - 1;
                        ((SecuredMemberRow) holder.itemView).set(s.getChatId(), s.getDisplayName(), null, selected.contains(s.getChatId()), !last, s.getAvatar());
                        break;
                    }
                    default:
                        break;
                }
            }
        };
        return adapter;
    }

    /** The name field with a live avatar preview (initials from the typed name). Built once and reused. */
    private View nameRow(Context context) {
        if (nameRow == null) {
            nameRow = new FrameLayout(context);
            nameRow.setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
            previewView = new View(context) {
                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    preview.setBounds(0, 0, getWidth(), getHeight());
                    preview.draw(canvas);
                }
            };
            updatePreview("");
            nameRow.addView(previewView, LayoutHelper.createFrame(56, 56, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 12, 0, 12));

            nameField = new EditTextBoldCursor(context);
            nameField.setHint(context.getString(channel ? R.string.secgroup_name_hint_channel : R.string.secgroup_name_hint_group));
            nameField.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteHintText));
            nameField.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
            nameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            nameField.setBackground(null);
            nameField.setCursorColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
            nameField.setCursorSize(dp(20));
            nameField.setCursorWidth(1.5f);
            nameField.setSingleLine(true);
            nameField.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            // Group names are private: no suggestions, no keyboard learning.
            nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            nameField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_NAME)});
            nameField.setPadding(0, 0, 0, 0);
            nameField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            nameField.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    AndroidUtilities.hideKeyboard(nameField);
                    return true;
                }
                return false;
            });
            nameField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    updatePreview(s.toString());
                }
            });
            nameRow.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.CENTER_VERTICAL, 88, 12, 16, 12));
            AndroidUtilities.runOnUIThread(() -> {
                if (!destroyed && !adding() && nameField != null && nameField.getText().length() == 0) {
                    nameField.requestFocus();
                    AndroidUtilities.showKeyboard(nameField);
                }
            }, 250);
        }
        AndroidUtilities.removeFromParent(nameRow);
        return nameRow;
    }

    private void updatePreview(String text) {
        final String shown = text.trim();
        Kit.setAvatar(preview, 1, shown);   // colour comes from the name hash, so it matches the created group everywhere
        if (previewView != null) previewView.invalidate();
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (nameField != null) AndroidUtilities.hideKeyboard(nameField);
    }
}
