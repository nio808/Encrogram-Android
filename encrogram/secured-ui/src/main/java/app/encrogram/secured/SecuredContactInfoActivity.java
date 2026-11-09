package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import app.encrogram.core.api.ChatAction;
import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.SecuredResult;

/**
 * Contact info (U12), opened from the chat header. A plain screen of Telegram cells in the ChannelAdminLogActivity
 * style, never ProfileActivity. Reuse: {@link SecuredGroupScreen} frame, TextCell, TextCheckCell, TextInfoPrivacyCell,
 * {@link SecuredChatSheets} (verify and timer sheets, as the chat menu), AlertDialog; the header look is a small copy of
 * the group info header (level 3). Every action is an existing engine call.
 */
final class SecuredContactInfoActivity extends SecuredGroupScreen {

    private static final int T_PROFILE = 0, T_SHADOW = 1, T_ACTION = 2, T_CHECK = 3, T_INFO = 4;
    private static final int A_SECURITY = 1, A_MUTE = 2, A_DISAPPEAR = 3, A_CLEAR = 4, A_DELETE = 5;

    private static final class Row {
        final int type, id;

        Row(int type, int id) {
            this.type = type;
            this.id = id;
        }
    }

    private final long chatId;
    private ChatSummary summary;
    private final List<Row> rows = new ArrayList<>();
    private RecyclerListView.SelectionAdapter adapter;
    private Header header;

    /** The chat screen this was opened from: it closes too when the contact is deleted. */
    private java.lang.ref.WeakReference<BaseFragment> opener;

    SecuredContactInfoActivity(long chatId) {
        this.chatId = chatId;
    }

    /** Pushes the screen on top of [from]. */
    static void open(BaseFragment from, long chatId) {
        if (from == null) return;
        final SecuredContactInfoActivity f = new SecuredContactInfoActivity(chatId);
        f.opener = new java.lang.ref.WeakReference<>(from);
        from.presentFragment(f);
    }

    @Override
    CharSequence title(Context context) {
        return context.getString(R.string.sec_r2i_contact_title);
    }

    @Override
    public View createView(Context context) {
        refreshSummary();
        final View v = super.createView(context);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            final Row r = rows.get(position);
            if (r.type == T_ACTION || r.type == T_CHECK) onAction(r.id);
        });
        return v;
    }

    @Override
    void onEngineChanged() {
        refreshSummary();
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
            finishFragment();   // deleted: nothing left to show
            return;
        }
        summary = found;
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        rows.add(new Row(T_PROFILE, 0));
        rows.add(new Row(T_SHADOW, 0));
        rows.add(new Row(T_ACTION, A_SECURITY));
        rows.add(new Row(T_CHECK, A_MUTE));
        rows.add(new Row(T_ACTION, A_DISAPPEAR));
        rows.add(new Row(T_SHADOW, 0));
        rows.add(new Row(T_ACTION, A_CLEAR));
        rows.add(new Row(T_ACTION, A_DELETE));
        rows.add(new Row(T_INFO, 0));
        if (header != null) header.bind();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String name() {
        return summary != null && summary.getDisplayName() != null ? summary.getDisplayName().trim() : "";
    }

    // ---- actions ---------------------------------------------------------------------------------------------

    private void onAction(int id) {
        if (summary == null || getParentActivity() == null) return;
        switch (id) {
            case A_SECURITY:
                openVerify();
                break;
            case A_MUTE:
                chatAction(new ChatAction.SetMuted(!summary.getMuted()), null);
                break;
            case A_DISAPPEAR:
                SecuredChatSheets.showDisappearing(this, name(), summary.getDisappearingTtlSeconds(), ttl -> chatAction(
                        new ChatAction.SetDisappearing(ttl == 0 ? null : Integer.valueOf(ttl)), () -> {
                            final Context c = getParentActivity();
                            if (c != null && ttl != 0) {
                                BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip,
                                        c.getString(R.string.secchat_disappear_saved, name())).show();
                            }
                        }));
                break;
            case A_CLEAR:
                confirmClear();
                break;
            case A_DELETE:
                confirmDelete();
                break;
            default:
                break;
        }
    }

    private boolean running() {
        if (engine == null) return false;
        try {
            return engine.state() == app.encrogram.core.api.EngineState.RUNNING;
        } catch (Throwable t) {
            return false;
        }
    }

    private void fail(SecuredResult.Err err) {
        final Context c = getParentActivity();
        showError(c == null ? err.getError().getMessage() : c.getString(R.string.sec_r2i_failed, err.getError().getMessage()));
    }

    private void chatAction(ChatAction action, Runnable onOk) {
        if (!running()) {
            final Context c = getParentActivity();
            if (c != null) showError(c.getString(R.string.sec_r2i_not_running));
            return;
        }
        try {
            engine.chatAction(chatId, action, result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) fail((SecuredResult.Err) result);
                else if (onOk != null) onOk.run();
                refreshSummary();
            }));
        } catch (Throwable t) {
            showThrown(t, R.string.sec_r2i_failed);
        }
    }

    private void openVerify() {
        if (!running()) {
            final Context c = getParentActivity();
            if (c != null) showError(c.getString(R.string.sec_r2i_not_running));
            return;
        }
        try {
            engine.securityCode(chatId, result -> ui(() -> {
                if (destroyed) return;
                if (!(result instanceof SecuredResult.Ok)) {
                    fail((SecuredResult.Err) result);
                    return;
                }
                final String code = ((SecuredResult.Ok<String>) result).getValue();
                SecuredChatSheets.showVerify(this, name(), code, summary != null && summary.getVerified(),
                        mark -> verify(mark ? code : null));
            }));
        } catch (Throwable t) {
            showThrown(t, R.string.sec_r2i_failed);
        }
    }

    private void verify(String code) {
        if (!running()) return;
        try {
            engine.verifyContact(chatId, code, result -> ui(() -> {
                if (destroyed) return;
                final Context c = getParentActivity();
                if (!(result instanceof SecuredResult.Ok)) {
                    fail((SecuredResult.Err) result);
                } else if (c != null) {
                    final boolean verified = ((SecuredResult.Ok<Boolean>) result).getValue();
                    if (code != null && !verified) {
                        showError(c.getString(R.string.secchat_verify_failed));
                    } else {
                        BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.chats_infotip,
                                c.getString(code != null ? R.string.secchat_verify_done : R.string.secchat_verify_cleared)).show();
                    }
                }
                refreshSummary();
            }));
        } catch (Throwable t) {
            showThrown(t, R.string.sec_r2i_failed);
        }
    }

    private void confirmClear() {
        final Activity a = getParentActivity();
        if (a == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(a, SecuredResourcesProvider.get());
        b.setTitle(a.getString(R.string.secchat_clear_title));
        b.setMessage(a.getString(R.string.secchat_clear_text, name()));
        b.setNegativeButton(a.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(a.getString(R.string.secchat_clear_confirm), (d, w) -> chatAction(ChatAction.ClearHistory.INSTANCE, null));
        final AlertDialog dialog = b.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    /** Same wording and choice as the chat menu's delete (direct chat). */
    private void confirmDelete() {
        final Activity a = getParentActivity();
        if (a == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(a, SecuredResourcesProvider.get());
        b.setTitle(a.getString(R.string.sec_list_delete_title));
        b.setMessage(a.getString(R.string.sec_list_delete_text));
        final CheckBoxCell also = new CheckBoxCell(a, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, SecuredResourcesProvider.get());
        also.setText(a.getString(R.string.sec_list_delete_also), "", false, false);
        also.setOnClickListener(v -> also.setChecked(!also.isChecked(), true));
        final FrameLayout holder = new FrameLayout(a);
        holder.addView(also, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 8, 0, 8, 0));
        b.setView(holder);
        b.setNegativeButton(a.getString(R.string.sec_list_cancel), null);
        b.setPositiveButton(a.getString(R.string.sec_list_delete_confirm), (d, w) ->
                chatAction(new ChatAction.Delete(also.isChecked()), this::closeAfterDelete));
        final AlertDialog dialog = b.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    /** After delete: this screen and the chat under it both go. */
    private void closeAfterDelete() {
        final BaseFragment chat = opener != null ? opener.get() : null;
        if (chat != null && parentLayout != null) parentLayout.removeFragmentFromStack(chat);
        finishFragment();
    }

    // ---- list ------------------------------------------------------------------------------------------------

    private static String ttlLabel(Context c, Integer ttl) {
        if (ttl == null || ttl <= 0) return c.getString(R.string.sec_r2i_disappearing_off);
        if (ttl <= 24 * 3600) return c.getString(R.string.secchat_disappear_1d);
        if (ttl <= 7 * 24 * 3600) return c.getString(R.string.secchat_disappear_1w);
        return c.getString(R.string.secchat_disappear_1m);
    }

    @Override
    RecyclerListView.SelectionAdapter buildAdapter(Context context) {
        header = new Header(context);
        header.bind();
        adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                final int t = holder.getItemViewType();
                return t == T_ACTION || t == T_CHECK;
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
                    case T_CHECK:
                        v = new TextCheckCell(context, SecuredResourcesProvider.get());
                        break;
                    case T_INFO:
                        v = new TextInfoPrivacyCell(context, SecuredResourcesProvider.get());
                        break;
                    default:
                        v = new TextCell(context, SecuredResourcesProvider.get());
                        break;
                }
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final Row r = rows.get(position);
                final Context c = holder.itemView.getContext();
                final boolean nextIsRow = position + 1 < rows.size() && (rows.get(position + 1).type == T_ACTION || rows.get(position + 1).type == T_CHECK);
                switch (r.type) {
                    case T_ACTION: {
                        final TextCell cell = (TextCell) holder.itemView;
                        if (r.id == A_SECURITY) {
                            final boolean verified = summary != null && summary.getVerified();
                            cell.setTextAndValueAndIcon(c.getString(R.string.sec_r2i_security_code),
                                    c.getString(verified ? R.string.sec_r2i_security_value_verified : R.string.sec_r2i_security_value_open),
                                    org.telegram.messenger.R.drawable.msg_secret, nextIsRow);
                            cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        } else if (r.id == A_DISAPPEAR) {
                            cell.setTextAndValueAndIcon(c.getString(R.string.sec_r2i_disappearing),
                                    ttlLabel(c, summary != null ? summary.getDisappearingTtlSeconds() : null),
                                    org.telegram.messenger.R.drawable.msg_autodelete, nextIsRow);
                            cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        } else {
                            cell.setTextAndIcon(c.getString(r.id == A_CLEAR ? R.string.sec_r2i_clear : R.string.sec_r2i_delete),
                                    r.id == A_CLEAR ? org.telegram.messenger.R.drawable.msg_clear : org.telegram.messenger.R.drawable.msg_delete, nextIsRow);
                            cell.setColors(Theme.key_text_RedBold, Theme.key_text_RedBold);
                        }
                        break;
                    }
                    case T_CHECK: {
                        final TextCheckCell cell = (TextCheckCell) holder.itemView;
                        // Checked = notifications on.
                        cell.setTextAndCheck(c.getString(R.string.sec_r2i_notifications), summary != null && !summary.getMuted(), nextIsRow);
                        break;
                    }
                    case T_INFO: {
                        final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                        cell.setText(c.getString(R.string.sec_r2i_contact_note));
                        break;
                    }
                    default:
                        break;
                }
            }
        };
        return adapter;
    }

    // ---- header: photo (bounded decode of untrusted bytes), name, verified state -----------------------------

    private final class Header extends LinearLayout {
        private final AvatarDrawable letter = new AvatarDrawable(SecuredResourcesProvider.get());
        private Bitmap photo;
        private final TextView title;
        private final TextView sub;

        Header(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
            setPadding(dp(16), dp(20), dp(16), dp(16));
            final View avatarView = new View(context) {
                @Override
                protected void onDraw(@NonNull Canvas canvas) {
                    final Bitmap p = photo;
                    if (p != null && !p.isRecycled()) {
                        final android.graphics.Path clip = new android.graphics.Path();
                        clip.addCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, android.graphics.Path.Direction.CW);
                        canvas.save();
                        canvas.clipPath(clip);
                        canvas.drawBitmap(p, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), null);
                        canvas.restore();
                    } else {
                        letter.setBounds(0, 0, getWidth(), getHeight());
                        letter.draw(canvas);
                    }
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

        void bind() {
            final Context c = getContext();
            final String shown = name();
            Kit.setAvatar(letter, ChatRowMapper.colorId(chatId), shown);
            photo = summary != null ? MediaBitmaps.decodePreview(summary.getAvatar(), dp(96), dp(96)) : null;
            title.setText(shown);
            if (summary == null) {
                sub.setText("");
            } else if (!summary.getReady()) {
                sub.setText(c.getString(R.string.sec_r2i_connecting));
            } else {
                final boolean v = summary.getVerified();
                sub.setText(c.getString(v ? R.string.sec_r2i_verified : R.string.sec_r2i_not_verified));
                sub.setTextColor(v ? SecuredResourcesProvider.accentText() : Kit.color(Theme.key_windowBackgroundWhiteGrayText));
            }
            invalidate();
            getChildAt(0).invalidate();
        }
    }
}
