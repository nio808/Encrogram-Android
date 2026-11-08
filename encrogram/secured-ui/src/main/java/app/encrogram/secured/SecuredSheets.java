package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.LaunchActivity;

import app.encrogram.core.api.LinkCheck;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredEngineKt;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredInvite;
import app.encrogram.core.api.SecuredResult;

/**
 * The invite sheet and the connect sheet. Plain views on Telegram's BottomSheet; all data goes through the engine.
 * Nothing here uses Telegram's network, link preview or browser code.
 */
final class SecuredSheets {

    private SecuredSheets() {}

    // ---- invite ----------------------------------------------------------------------------------------------

    static void showInvite(BaseFragment fragment, SecuredEngine engine) {
        final Context context = fragment.getParentActivity();
        if (context == null || engine == null) return;

        final LinearLayout box = column(context);
        box.addView(title(context, R.string.sec_invite_title));

        final LinearLayout progressRow = new LinearLayout(context);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        final RadialProgressView spinner = new RadialProgressView(context, SecuredResourcesProvider.get());
        spinner.setSize(dp(22));
        spinner.setProgressColor(SecuredResourcesProvider.accentFill());
        progressRow.addView(spinner, LayoutHelper.createLinear(24, 24, 0, 0, 12, 0));
        progressRow.addView(Kit.text(context, context.getString(R.string.sec_invite_creating), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false));
        box.addView(progressRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 8));

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        builder.setCustomView(box);
        final BottomSheet sheet = builder.create();
        fragment.showDialog(sheet);

        try {
            engine.createInvite(result -> {
                if (!sheet.isShowing()) return;
                box.removeView(progressRow);
                try {
                    if (result instanceof SecuredResult.Ok) {
                        final SecuredInvite invite = ((SecuredResult.Ok<SecuredInvite>) result).getValue();
                        showInviteLink(context, box, invite.getLink());
                    } else {
                        final SecuredError e = ((SecuredResult.Err) result).getError();
                        showInviteFailure(context, box, e.getMessage());
                    }
                } catch (Throwable t) {
                    showInviteFailure(context, box, t.getClass().getSimpleName());
                }
            });
        } catch (Throwable t) {
            box.removeView(progressRow);
            showInviteFailure(context, box, t.getClass().getSimpleName());
        }
    }

    private static void showInviteFailure(Context context, LinearLayout box, String detail) {
        box.addView(Kit.text(context, context.getString(R.string.sec_invite_failed, detail), 15,
                Kit.color(Theme.key_text_RedBold), false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 8));
    }

    private static void showInviteLink(Context context, LinearLayout box, String link) {
        final TextView linkView = Kit.text(context, link, 14, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        linkView.setMaxLines(6);
        linkView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        linkView.setTextIsSelectable(false); // selection toolbar would bypass the safe Copy and Share buttons
        linkView.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkView.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 10));
        box.addView(linkView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        box.addView(Kit.text(context, context.getString(R.string.sec_invite_explain), 14, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView copy = Kit.button(context, context.getString(R.string.sec_copy), false);
        final TextView share = Kit.button(context, context.getString(R.string.sec_share), true);
        copy.setOnClickListener(v -> {
            copyToClipboard(context, link, true);
            Toast.makeText(context, R.string.sec_link_copied, Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> share(context, link));
        buttons.addView(copy, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
        buttons.addView(share, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 0, 0));
        box.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 4));
    }

    static void copyToClipboard(Context context, String text, boolean sensitive) {
        final ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        final ClipData clip = ClipData.newPlainText("Encrogram", text);
        if (sensitive) {
            final PersistableBundle extras = new PersistableBundle();
            // Same key as ClipDescription.EXTRA_IS_SENSITIVE (API 33); the string works on every level.
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
    }

    /** Android's own share sheet. Encrogram's Telegram share target is excluded so the link cannot slip into Telegram by accident. */
    static void share(Context context, String link) {
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, link);
        final Intent chooser = Intent.createChooser(send, context.getString(R.string.sec_share_chooser));
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(context, LaunchActivity.class)});
        context.startActivity(chooser);
    }

    // ---- connect ---------------------------------------------------------------------------------------------

    static void showConnect(BaseFragment fragment, SecuredEngine engine, String prefill) {
        final Context context = fragment.getParentActivity();
        if (context == null || engine == null) return;

        final LinearLayout box = column(context);
        box.addView(title(context, R.string.sec_connect_title));

        final EditText input = new EditText(context);
        input.setHint(R.string.sec_connect_hint);
        input.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        input.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 10));
        if (prefill != null) input.setText(prefill);
        box.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        final TextView paste = Kit.button(context, context.getString(R.string.sec_paste), false);
        paste.setMinHeight(dp(40));
        paste.setOnClickListener(v -> {
            final ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            final ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                input.setText(clip.getItemAt(0).getText().toString().trim());
                input.setSelection(input.getText().length());
            }
        });
        box.addView(paste, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 0, 8, 0, 0));

        final TextView error = Kit.text(context, "", 14, Kit.color(Theme.key_text_RedBold), false);
        error.setVisibility(View.GONE);
        box.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final TextView connect = Kit.button(context, context.getString(R.string.sec_connect), true);
        box.addView(connect, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 4));

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, true, SecuredResourcesProvider.get());
        builder.setCustomView(box);
        final BottomSheet sheet = builder.create();

        connect.setOnClickListener(v -> {
            final String link = input.getText().toString().trim();
            LinkCheck check;
            try {
                check = engine.checkLink(link);
            } catch (Throwable t) {
                check = null; // fail closed: a throwing check counts as invalid
            }
            if (check == null || check instanceof LinkCheck.Malformed) {
                showError(error, context.getString(R.string.sec_connect_malformed));
                return;
            }
            if (check instanceof LinkCheck.UnapprovedRelay) {
                showError(error, context.getString(R.string.sec_connect_unapproved, ((LinkCheck.UnapprovedRelay) check).getHost()));
                return;
            }
            error.setVisibility(View.GONE);
            connect.setEnabled(false);
            connect.setText(R.string.sec_connecting);
            final Context appContext = context.getApplicationContext();
            try {
            engine.connect(link, result -> {
                if (!sheet.isShowing()) {
                    // Sheet closed before the result arrived: never drop a failure silently.
                    if (result instanceof SecuredResult.Err && appContext != null) {
                        final SecuredError late = ((SecuredResult.Err) result).getError();
                        if (late.getKind() == SecuredError.Kind.OWN_LINK) {
                            Toast.makeText(appContext, late.getMessage(), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(appContext, appContext.getString(R.string.sec_connect_failed, late.getMessage()), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (result instanceof SecuredResult.Ok) {
                    sheet.dismiss();
                    Toast.makeText(context, R.string.sec_connect_sent, Toast.LENGTH_LONG).show();
                } else {
                    connect.setEnabled(true);
                    connect.setText(R.string.sec_connect);
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    if (e.getKind() == SecuredError.Kind.OWN_LINK) {
                        // Our own invite: say so, then go to Saved Messages (notes to yourself).
                        sheet.dismiss();
                        Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                        if (fragment instanceof SecuredDialogsActivity) {
                            ((SecuredDialogsActivity) fragment).openSavedMessages();
                        } else {
                            fragment.presentFragment(new SecuredChatActivity(SecuredEngineKt.SAVED_MESSAGES_CHAT_ID));
                        }
                        return;
                    }
                    showError(error, context.getString(R.string.sec_connect_failed, e.getMessage()));
                }
            });
            } catch (Throwable t) {
                connect.setEnabled(true);
                connect.setText(R.string.sec_connect);
                showError(error, context.getString(R.string.sec_connect_failed, t.getClass().getSimpleName()));
            }
        });
        fragment.showDialog(sheet);
        if (prefill == null) {
            input.requestFocus();
        }
    }

    private static void showError(TextView view, String message) {
        view.setText(message);
        view.setVisibility(View.VISIBLE);
    }

    // ---- shared bits -----------------------------------------------------------------------------------------

    private static LinearLayout column(Context context) {
        final LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(16));
        l.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private static TextView title(Context context, int res) {
        return Kit.text(context, context.getString(res), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true);
    }
}
