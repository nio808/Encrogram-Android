package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredInvite;
import app.encrogram.core.api.SecuredResult;

/**
 * The group invite link sheet. It copies the look of {@link SecuredSheets#showInvite} (Telegram's BottomSheet, the link
 * in a soft box), never LinkActionView or ShareAlert, which reach Telegram. The link is not selectable; Copy uses the
 * sensitive clipboard and Share goes through {@link SecuredSheets#share}, which excludes our Telegram share target.
 * Admins and the owner only; the caller checks the role.
 */
final class SecuredGroupLinkSheet {

    private SecuredGroupLinkSheet() {}

    static void show(BaseFragment fragment, SecuredEngine engine, long chatId, boolean channel) {
        final Context context = fragment.getParentActivity();
        if (context == null || engine == null) return;

        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        box.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(Kit.text(context, context.getString(R.string.secgroup_invite_link), 20, Kit.color(Theme.key_windowBackgroundWhiteBlackText), true));

        final LinearLayout progressRow = new LinearLayout(context);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        final RadialProgressView spinner = new RadialProgressView(context, SecuredResourcesProvider.get());
        spinner.setSize(dp(22));
        spinner.setProgressColor(SecuredResourcesProvider.accentFill());
        progressRow.addView(spinner, LayoutHelper.createLinear(24, 24, 0, 0, 12, 0));
        progressRow.addView(Kit.text(context, context.getString(R.string.secgroup_link_creating), 15, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false));
        box.addView(progressRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 8));

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, SecuredResourcesProvider.get());
        builder.setCustomView(box);
        final BottomSheet sheet = builder.create();
        fragment.showDialog(sheet);

        try {
            engine.groupLink(chatId, result -> {
                if (!sheet.isShowing()) return;
                box.removeView(progressRow);
                try {
                    if (result instanceof SecuredResult.Ok) {
                        showLink(fragment, engine, sheet, context, box, chatId, channel, ((SecuredResult.Ok<SecuredInvite>) result).getValue().getLink());
                    } else {
                        failure(context, box, ((SecuredResult.Err) result).getError().getMessage());
                    }
                } catch (Throwable t) {
                    failure(context, box, t.getClass().getSimpleName());
                }
            });
        } catch (Throwable t) {
            box.removeView(progressRow);
            failure(context, box, t.getClass().getSimpleName());
        }
    }

    private static void failure(Context context, LinearLayout box, String detail) {
        box.addView(Kit.text(context, context.getString(R.string.secgroup_link_failed, detail), 15, Kit.color(Theme.key_text_RedBold), false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 8));
    }

    private static void showLink(BaseFragment fragment, SecuredEngine engine, BottomSheet sheet, Context context, LinearLayout box,
                                 long chatId, boolean channel, String link) {
        final TextView linkView = Kit.text(context, link, 14, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        linkView.setMaxLines(6);
        linkView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        linkView.setTextIsSelectable(false);   // the selection toolbar would bypass the safe Copy and Share buttons
        linkView.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkView.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 10));
        box.addView(linkView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        box.addView(Kit.text(context, context.getString(channel ? R.string.secgroup_link_explain_channel : R.string.secgroup_link_explain_group), 14,
                Kit.color(Theme.key_windowBackgroundWhiteGrayText), false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView copy = Kit.button(context, context.getString(R.string.sec_copy), false);
        final TextView share = Kit.button(context, context.getString(R.string.sec_share), true);
        copy.setOnClickListener(v -> {
            SecuredSheets.copyToClipboard(context, link, true);
            Toast.makeText(context, R.string.sec_link_copied, Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> SecuredSheets.share(context, link));
        buttons.addView(copy, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
        buttons.addView(share, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 6, 0, 0, 0));
        box.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        final TextView delete = Kit.button(context, context.getString(R.string.secgroup_link_delete), false);
        delete.setTextColor(Kit.color(Theme.key_text_RedBold));
        delete.setOnClickListener(v -> confirmDelete(fragment, engine, sheet, context, chatId));
        box.addView(delete, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));
    }

    private static void confirmDelete(BaseFragment fragment, SecuredEngine engine, BottomSheet sheet, Context context, long chatId) {
        final AlertDialog.Builder b = new AlertDialog.Builder(context, SecuredResourcesProvider.get());
        b.setTitle(context.getString(R.string.secgroup_link_delete_title));
        b.setMessage(context.getString(R.string.secgroup_link_delete_text));
        b.setNegativeButton(context.getString(R.string.sec_list_cancel), null);
        final Context appContext = context.getApplicationContext();
        b.setPositiveButton(context.getString(R.string.secgroup_link_delete), (d, w) -> {
            try {
                engine.deleteGroupLink(chatId, result -> {
                    if (result instanceof SecuredResult.Ok) {
                        if (sheet.isShowing()) sheet.dismiss();
                        Toast.makeText(appContext, R.string.secgroup_link_deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(appContext, appContext.getString(R.string.secgroup_action_failed,
                                ((SecuredResult.Err) result).getError().getMessage()), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                Toast.makeText(appContext, appContext.getString(R.string.secgroup_action_failed, t.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
            }
        });
        final AlertDialog dialog = b.create();
        fragment.showDialog(dialog);
        dialog.redPositive();
    }
}
