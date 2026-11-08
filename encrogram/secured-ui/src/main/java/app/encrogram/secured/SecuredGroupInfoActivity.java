package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.encrogram.core.api.ChatAction;
import app.encrogram.core.api.ChatKind;
import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.GroupMember;
import app.encrogram.core.api.MemberRole;
import app.encrogram.core.api.MemberStatus;
import app.encrogram.core.api.SecuredResult;

/**
 * Group and channel info, opened from the chat header. Built on the ChannelAdminLogActivity pattern (a plain screen
 * of Telegram cells), never on ProfileActivity, which is Telegram-bound.
 *
 * Reuse: HeaderCell, TextCell, TextInfoPrivacyCell, ShadowSectionCell, ItemOptions, AlertDialog and AvatarDrawable as
 * they are; {@link SecuredMemberRow} for people; {@link SecuredGroupDialogs#confirmDelete} for leave and delete; the
 * profile header look is a small copy ({@link ProfileHeader}).
 */
final class SecuredGroupInfoActivity extends SecuredGroupScreen {

    private static final int T_PROFILE = 0, T_SHADOW = 1, T_ACTION = 2, T_INFO = 3, T_HEADER = 4, T_MEMBER = 5;
    private static final int A_ADD = 1, A_LINK = 2, A_LEAVE = 3, A_DELETE = 4;
    private static final int I_FACTS = 1, I_MEMBERS_ERROR = 2;

    private static final class Row {
        final int type;
        final int id;

        Row(int type, int id) {
            this.type = type;
            this.id = id;
        }
    }

    private final long chatId;
    private ChatSummary summary;
    private final List<GroupMember> members = new ArrayList<>();
    private String membersError;
    private final List<Row> rows = new ArrayList<>();
    private RecyclerListView.SelectionAdapter adapter;
    private ProfileHeader header;
    private boolean busy;

    SecuredGroupInfoActivity(long chatId) {
        this.chatId = chatId;
    }

    @Override
    long watchedChatId() {
        return chatId;
    }

    // ---- state -----------------------------------------------------------------------------------------------

    private boolean channel() {
        return summary != null && summary.getKind() == ChatKind.CHANNEL;
    }

    private MemberRole myRole() {
        return summary != null && summary.getMyRole() != null ? summary.getMyRole() : MemberRole.MEMBER;
    }

    private boolean owner() {
        return myRole() == MemberRole.OWNER;
    }

    private boolean admin() {
        return myRole() == MemberRole.OWNER || myRole() == MemberRole.ADMIN;
    }

    @Override
    CharSequence title(Context context) {
        return context.getString(R.string.secgroup_info_group);
    }

    @Override
    public View createView(Context context) {
        refreshSummary();
        final View v = super.createView(context);
        actionBar.setTitle(context.getString(channel() ? R.string.secgroup_info_channel : R.string.secgroup_info_group));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            final Row r = rows.get(position);
            if (r.type == T_ACTION) onAction(r.id);
            else if (r.type == T_MEMBER) onMember(view, r.id);
        });
        loadMembers();
        return v;
    }

    @Override
    void onEngineChanged() {
        refreshSummary();
        loadMembers();
    }

    private void refreshSummary() {
        ChatSummary found = null;
        try {
            for (ChatSummary c : engine.chats()) {
                if (c.getChatId() == chatId) {
                    found = c;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (found == null && summary != null && !destroyed) {
            // Deleted, or we were removed and the chat is gone: nothing left to show.
            finishFragment();
            return;
        }
        summary = found;
        rebuild();
    }

    private void loadMembers() {
        if (engine == null) return;
        try {
            engine.members(chatId, result -> ui(() -> {
                if (destroyed) return;
                members.clear();
                if (result instanceof SecuredResult.Ok) {
                    membersError = null;
                    for (GroupMember m : ((SecuredResult.Ok<List<GroupMember>>) result).getValue()) {
                        if (m.getStatus() != MemberStatus.LEFT && m.getStatus() != MemberStatus.REMOVED) members.add(m);
                    }
                    Collections.sort(members, (a, b) -> {
                        if (a.getRole() != b.getRole()) return b.getRole().ordinal() - a.getRole().ordinal();   // owner first
                        return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                    });
                } else {
                    membersError = errorText((SecuredResult.Err) result);
                }
                rebuild();
            }));
        } catch (Throwable t) {
            membersError = t.getClass().getSimpleName();
            rebuild();
        }
    }

    private void rebuild() {
        rows.clear();
        rows.add(new Row(T_PROFILE, 0));
        rows.add(new Row(T_SHADOW, 0));
        if (admin()) {
            rows.add(new Row(T_ACTION, A_ADD));
            rows.add(new Row(T_ACTION, A_LINK));
            rows.add(new Row(T_SHADOW, 0));
        }
        rows.add(new Row(T_HEADER, 0));
        rows.add(new Row(T_INFO, I_FACTS));
        rows.add(new Row(T_SHADOW, 0));
        rows.add(new Row(T_HEADER, 1));
        if (membersError != null) {
            rows.add(new Row(T_INFO, I_MEMBERS_ERROR));
        } else {
            for (int i = 0; i < members.size(); i++) rows.add(new Row(T_MEMBER, i));
        }
        rows.add(new Row(T_SHADOW, 0));
        rows.add(new Row(T_ACTION, A_LEAVE));
        if (owner()) rows.add(new Row(T_ACTION, A_DELETE));
        rows.add(new Row(T_SHADOW, 0));
        if (header != null) header.bind(displayName(), subtitle(header.getContext()));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String displayName() {
        return summary != null ? summary.getDisplayName() : "";
    }

    private CharSequence subtitle(Context c) {
        final boolean channel = channel();
        Integer n = summary != null ? summary.getMemberCount() : null;
        if (n == null && !members.isEmpty()) n = members.size();
        if (n == null) return c.getString(channel ? R.string.secgroup_subtitle_channel : R.string.secgroup_subtitle_group);
        return c.getResources().getQuantityString(channel ? R.plurals.secgroup_subscribers_count : R.plurals.secgroup_members_count, n, n);
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    private void onAction(int id) {
        if (engine == null || destroyed || busy) return;
        if (id == A_ADD) {
            presentFragment(new SecuredNewGroupActivity(chatId, channel()));
        } else if (id == A_LINK) {
            SecuredGroupLinkSheet.show(this, engine, chatId, channel());
        } else if (id == A_LEAVE) {
            confirmLeaveOrDelete(false);
        } else if (id == A_DELETE) {
            confirmLeaveOrDelete(true);
        }
    }

    /** [deleteForAll] = the owner's "Delete group for everyone" row (the dialog also offers phone-only). */
    private void confirmLeaveOrDelete(boolean deleteForAll) {
        SecuredGroupDialogs.confirmDelete(this, SecuredResourcesProvider.get(), displayName(), deleteForAll && owner(), channel(), everyone -> {
            busy = true;
            try {
                engine.chatAction(chatId, new ChatAction.Delete(everyone), result -> ui(() -> {
                    busy = false;
                    if (destroyed) return;
                    if (result instanceof SecuredResult.Err) {
                        showError(getParentActivity().getString(R.string.secgroup_action_failed, errorText((SecuredResult.Err) result)));
                    } else {
                        closeChatAndSelf();
                    }
                }));
            } catch (Throwable t) {
                busy = false;
                showThrown(t, R.string.secgroup_action_failed);
            }
        });
    }

    /** The chat screen below us has nothing left to show: close it too, then this screen. */
    private void closeChatAndSelf() {
        try {
            if (parentLayout != null) {
                final List<BaseFragment> stack = parentLayout.getFragmentStack();
                final int me = stack.indexOf(this);
                if (me > 0 && stack.get(me - 1) instanceof SecuredChatActivity) {
                    stack.get(me - 1).removeSelfFromStack();
                }
            }
        } catch (Throwable ignored) {
        }
        finishFragment();
    }

    private void onMember(View row, int index) {
        if (index < 0 || index >= members.size() || busy) return;
        final GroupMember m = members.get(index);
        final MemberRole mine = myRole();
        final MemberRole theirs = m.getRole();
        if (m.isMe() || !admin() || theirs == MemberRole.OWNER) return;
        if (mine == MemberRole.ADMIN && theirs == MemberRole.ADMIN) return;   // admins cannot touch each other
        final Context c = row.getContext();
        final boolean connected = m.getStatus() == MemberStatus.CONNECTED;
        final boolean channel = channel();
        final ItemOptions o = ItemOptions.makeOptions(this, row);
        if (connected) {
            if (mine == MemberRole.OWNER) {
                if (theirs == MemberRole.ADMIN) {
                    final MemberRole back = channel ? MemberRole.OBSERVER : MemberRole.MEMBER;
                    o.add(org.telegram.messenger.R.drawable.msg_admins, c.getString(R.string.secgroup_remove_admin), false, () -> setRole(m, back));
                } else {
                    o.add(org.telegram.messenger.R.drawable.msg_admins, c.getString(R.string.secgroup_make_admin), false, () -> setRole(m, MemberRole.ADMIN));
                }
            }
            if (!channel && theirs != MemberRole.ADMIN) {
                if (theirs != MemberRole.MEMBER) {
                    o.add(org.telegram.messenger.R.drawable.msg_contacts, c.getString(R.string.secgroup_make_member), false, () -> setRole(m, MemberRole.MEMBER));
                }
                if (theirs != MemberRole.OBSERVER) {
                    o.add(org.telegram.messenger.R.drawable.msg_user_remove, c.getString(R.string.secgroup_make_observer), false, () -> setRole(m, MemberRole.OBSERVER));
                }
            }
        }
        o.add(org.telegram.messenger.R.drawable.msg_remove, c.getString(channel ? R.string.secgroup_remove_member_channel : R.string.secgroup_remove_member), true, () -> confirmRemove(m));
        o.show();
    }

    private void setRole(GroupMember m, MemberRole role) {
        try {
            engine.setMemberRole(chatId, m.getMemberId(), role, result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    showError(getParentActivity().getString(R.string.secgroup_action_failed, errorText((SecuredResult.Err) result)));
                }
                loadMembers();
            }));
        } catch (Throwable t) {
            showThrown(t, R.string.secgroup_action_failed);
        }
    }

    private void confirmRemove(GroupMember m) {
        final Context c = getParentActivity();
        if (c == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.secgroup_remove_title, m.getDisplayName()));
        b.setMessage(c.getString(channel() ? R.string.secgroup_remove_text_channel : R.string.secgroup_remove_text));
        b.setNegativeButton(c.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(c.getString(R.string.secgroup_remove_confirm), (d, w) -> {
            try {
                engine.removeMember(chatId, m.getMemberId(), result -> ui(() -> {
                    if (destroyed) return;
                    if (result instanceof SecuredResult.Err) {
                        showError(c.getString(R.string.secgroup_action_failed, errorText((SecuredResult.Err) result)));
                    }
                    loadMembers();
                }));
            } catch (Throwable t) {
                showThrown(t, R.string.secgroup_action_failed);
            }
        });
        final AlertDialog dialog = b.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    // ---- list ------------------------------------------------------------------------------------------------

    private CharSequence memberSub(Context c, GroupMember m) {
        final String role;
        switch (m.getRole()) {
            case OWNER:
                role = c.getString(R.string.secgroup_role_owner);
                break;
            case ADMIN:
                role = c.getString(R.string.secgroup_role_admin);
                break;
            case MODERATOR:
                role = c.getString(R.string.secgroup_role_moderator);
                break;
            case OBSERVER:
                role = c.getString(R.string.secgroup_role_observer);
                break;
            default:
                role = c.getString(R.string.secgroup_role_member);
                break;
        }
        final String status = m.getStatus() == MemberStatus.INVITED ? c.getString(R.string.secgroup_member_invited)
                : m.getStatus() == MemberStatus.CONNECTING ? c.getString(R.string.secgroup_member_connecting) : null;
        final String first = status != null ? status : role;
        return m.isMe() ? c.getString(R.string.secgroup_member_sub, c.getString(R.string.secgroup_member_you), first) : first;
    }

    private String facts(Context c) {
        final StringBuilder sb = new StringBuilder();
        if (channel()) {
            sb.append(c.getString(R.string.secgroup_fact_channel_size));
        } else {
            sb.append(c.getString(R.string.secgroup_fact_sent_each)).append('\n').append(c.getString(R.string.secgroup_fact_ticks));
        }
        sb.append('\n').append(c.getString(R.string.secgroup_fact_admins_add));
        sb.append('\n').append(c.getString(R.string.secgroup_fact_history));
        sb.append('\n').append(c.getString(R.string.secgroup_fact_names_visible));
        return sb.toString();
    }

    @Override
    RecyclerListView.SelectionAdapter buildAdapter(Context context) {
        header = new ProfileHeader(context);
        header.bind(displayName(), subtitle(context));
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                final int t = holder.getItemViewType();
                return t == T_ACTION || t == T_MEMBER;
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position).type;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                final View v;
                switch (viewType) {
                    case T_PROFILE:
                        org.telegram.messenger.AndroidUtilities.removeFromParent(header);
                        v = header;
                        break;
                    case T_SHADOW:
                        v = new ShadowSectionCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_ACTION:
                        v = new TextCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_INFO:
                        v = new TextInfoPrivacyCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_HEADER:
                        v = new HeaderCell(context, SecuredResourcesProvider.get());
                        break;
                    default:
                        v = new SecuredMemberRow(context, false);
                        break;
                }
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final Row r = rows.get(position);
                final Context c = holder.itemView.getContext();
                switch (r.type) {
                    case T_ACTION: {
                        final TextCell cell = (TextCell) holder.itemView;
                        final boolean nextIsAction = position + 1 < rows.size() && rows.get(position + 1).type == T_ACTION;
                        final boolean ch = channel();
                        if (r.id == A_ADD) {
                            cell.setTextAndIcon(c.getString(ch ? R.string.secgroup_add_subscribers : R.string.secgroup_add_members), org.telegram.messenger.R.drawable.msg_addcontact, nextIsAction);
                            cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                        } else if (r.id == A_LINK) {
                            cell.setTextAndIcon(c.getString(R.string.secgroup_invite_link), org.telegram.messenger.R.drawable.msg_link2, nextIsAction);
                            cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                        } else {
                            final int label = r.id == A_LEAVE ? (ch ? R.string.secgroup_leave_row_channel : R.string.secgroup_leave_row_group)
                                    : (ch ? R.string.secgroup_delete_row_channel : R.string.secgroup_delete_row_group);
                            cell.setTextAndIcon(c.getString(label), r.id == A_LEAVE ? org.telegram.messenger.R.drawable.msg_leave : org.telegram.messenger.R.drawable.msg_delete, nextIsAction);
                            cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedBold);
                        }
                        break;
                    }
                    case T_INFO: {
                        final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                        cell.setText(r.id == I_FACTS ? facts(c) : c.getString(R.string.secgroup_action_failed, membersError));
                        cell.setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
                        break;
                    }
                    case T_HEADER:
                        ((HeaderCell) holder.itemView).setText(c.getString(r.id == 0 ? R.string.secgroup_facts_header
                                : channel() ? R.string.secgroup_members_header_channel : R.string.secgroup_members_header_group));
                        break;
                    case T_MEMBER: {
                        final GroupMember m = members.get(r.id);
                        final boolean last = r.id == members.size() - 1;
                        ((SecuredMemberRow) holder.itemView).set(m.getMemberId(), m.getDisplayName(), memberSub(c, m), false, !last);
                        break;
                    }
                    default:
                        break;
                }
            }
        };
        return adapter;
    }

    // ---- profile header (small copy of the look of ProfileActivity's header) ----------------------------------

    private static final class ProfileHeader extends LinearLayout {
        private final AvatarDrawable avatar = new AvatarDrawable(SecuredResourcesProvider.get());
        private final TextView title;
        private final TextView sub;

        ProfileHeader(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
            setPadding(dp(16), dp(20), dp(16), dp(16));
            final View avatarView = new View(context) {
                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    avatar.setBounds(0, 0, getWidth(), getHeight());
                    avatar.draw(canvas);
                }
            };
            addView(avatarView, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL));
            title = Kit.text(context, "", 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
            sub = Kit.text(context, "", 14, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
            sub.setGravity(Gravity.CENTER);
            addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(String name, CharSequence subtitle) {
            final String shown = name == null ? "" : name.trim();
            final int sp = shown.lastIndexOf(' ');
            avatar.setInfo(1, sp > 0 ? shown.substring(0, sp).trim() : shown, sp > 0 ? shown.substring(sp + 1) : null);
            title.setText(shown);
            sub.setText(subtitle);
            invalidate();
        }
    }
}
