package app.encrogram.secured;

import android.app.Activity;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.function.Consumer;

/**
 * The delete / leave dialog for a Secured group or channel, shared by the chat screen and the chat list
 * (reuse level 1: Telegram's AlertDialog, only our wording). The owner can delete for everyone or only on this
 * phone; anyone else leaves and the chat is deleted on this phone.
 */
final class SecuredGroupDialogs {

    private SecuredGroupDialogs() {}

    /** {@code onChoose.accept(true)} = ChatAction.Delete(notifyContact = true), {@code false} = Delete(false). */
    static void confirmDelete(BaseFragment fragment, Theme.ResourcesProvider provider, String name, boolean owner,
                              boolean channel, Consumer<Boolean> onChoose) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, provider);
        if (owner) {
            b.setTitle(activity.getString(channel ? R.string.secgroup_delete_title_channel : R.string.secgroup_delete_title));
            b.setMessage(activity.getString(channel ? R.string.secgroup_delete_owner_text_channel : R.string.secgroup_delete_owner_text));
            b.setPositiveButton(activity.getString(R.string.secgroup_delete_everyone), (d, w) -> onChoose.accept(true));
            b.setNeutralButton(activity.getString(R.string.secgroup_delete_phone_only), (d, w) -> onChoose.accept(false));
        } else {
            b.setTitle(activity.getString(channel ? R.string.secgroup_leave_title_channel : R.string.secgroup_leave_title));
            b.setMessage(activity.getString(channel ? R.string.secgroup_leave_text_channel : R.string.secgroup_leave_text));
            b.setPositiveButton(activity.getString(R.string.secgroup_leave_confirm), (d, w) -> onChoose.accept(false));
        }
        b.setNegativeButton(activity.getString(R.string.sec_list_cancel), null);
        final AlertDialog dialog = b.create();
        Kit.show(fragment, dialog);
        dialog.redPositive();
    }
}
