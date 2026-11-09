package app.encrogram.secured;

import android.app.Activity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.function.Consumer;

import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredResult;

/**
 * The "Reset Secured" confirmation for a phone whose key store lost Secured's key (reuse level 1: Telegram's
 * AlertDialog, shown through Kit.show so it carries FLAG_SECURE). Confirming calls {@link SecuredEngine#resetSecured}.
 */
final class SecuredResetSheet {

    private SecuredResetSheet() {}

    /** {@code done.accept(null)} on success (the engine is now in NEEDS_PROFILE), else the readable error. Runs on the UI thread. */
    static void confirm(BaseFragment fragment, SecuredEngine engine, Consumer<String> done) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || engine == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(activity, SecuredResourcesProvider.get());
        b.setTitle(activity.getString(R.string.secpause_reset_title));
        b.setMessage(activity.getString(R.string.secpause_reset_text));
        b.setPositiveButton(activity.getString(R.string.secpause_reset_confirm), (d, w) -> {
            try {
                engine.resetSecured(result -> AndroidUtilities.runOnUIThread(() -> {
                    if (result instanceof SecuredResult.Err) {
                        done.accept(((SecuredResult.Err) result).getError().getMessage());
                        return;
                    }
                    // The UI's own per-profile traces go with the data: backup offer flags, recent emoji.
                    SecuredBackupUi.forget(activity);
                    SecuredEmojiPanel.forgetRecents(activity);
                    done.accept(null);
                }));
            } catch (Throwable t) {
                done.accept(activity.getString(R.string.sec_bk_err_unknown));
            }
        });
        b.setNegativeButton(activity.getString(R.string.sec_list_cancel), null);
        final AlertDialog dialog = b.create();
        Kit.show(fragment, dialog);
        dialog.redPositive();
    }
}
