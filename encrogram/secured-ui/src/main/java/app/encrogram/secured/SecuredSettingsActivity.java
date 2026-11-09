package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredProfile;
import app.encrogram.core.api.SecuredResult;

/**
 * Secured settings (from the list's menu): the display name (tap to edit) and the recovery phrase screen.
 * Reuse: the backup screen's frame (SecuredBackupScreen) and Telegram's HeaderCell, TextSettingsCell and
 * TextInfoPrivacyCell on the Secured ResourcesProvider. Only engine calls; nothing from Telegram's network.
 */
final class SecuredSettingsActivity extends SecuredBackupScreen {

    private String error;

    @Override
    int titleRes() {
        return R.string.sec_set_title;
    }

    @Override
    void onBuild(Context context) {
        content.setPadding(0, 0, 0, 0);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private String currentName() {
        try {
            final SecuredProfile p = engine != null ? engine.profile() : null;
            return p != null ? p.getDisplayName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void refresh() {
        if (fragmentView == null || content == null) return;
        final Context c = fragmentView.getContext();
        final Theme.ResourcesProvider rp = SecuredResourcesProvider.get();
        content.removeAllViews();

        final HeaderCell profileHeader = new HeaderCell(c, rp);
        profileHeader.setText(c.getString(R.string.sec_set_profile));
        content.addView(profileHeader);

        final TextSettingsCell name = new TextSettingsCell(c, rp);
        name.setTextAndValue(c.getString(R.string.sec_set_name), currentName(), false);
        name.setOnClickListener(v -> editName(c));
        content.addView(name);

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(c, rp);
        info.setText(error != null ? error : c.getString(R.string.sec_set_name_info));
        content.addView(info);

        final TextSettingsCell backup = new TextSettingsCell(c, rp);
        backup.setText(c.getString(R.string.sec_bk_menu), false);
        backup.setOnClickListener(v -> presentFragment(new SecuredBackupSettingsActivity()));
        content.addView(backup);
    }

    private void editName(Context c) {
        if (engine == null) return;
        final EditText input = new EditText(c);
        input.setText(currentName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.sec_profile_name_hint);
        input.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        input.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);   // keyboard doesn't learn Secured names
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 12));
        final FrameLayout box = new FrameLayout(c);
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        box.setPadding(dp(20), dp(4), dp(20), 0);

        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.sec_set_name));
        b.setView(box);
        b.setNegativeButton(c.getString(R.string.sec_bs_cancel), null);
        b.setPositiveButton(c.getString(R.string.sec_set_save), (d, w) -> {
            AndroidUtilities.hideKeyboard(input);
            save(c, input.getText().toString().trim());
        });
        Kit.show(this, b.create());
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void save(Context c, String name) {
        if (engine == null || name.isEmpty() || name.equals(currentName())) return;
        error = null;
        try {
            engine.updateProfile(name, result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    final String m = e.getMessage();
                    error = m == null || m.isEmpty() ? c.getString(R.string.sec_bk_err_unknown) : m;
                }
                refresh();
            }));
        } catch (Throwable t) {
            error = c.getString(R.string.sec_bk_err_unknown);
            refresh();
        }
    }
}
