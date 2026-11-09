package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

import java.util.List;

import app.encrogram.core.api.SecuredDiagnostics;

/**
 * Opt-in diagnostics (A5): a switch, the last log lines in monospace, Copy and Clear. Reuse: the Secured backup
 * screen frame (FLAG_SECURE, ActionBar), Telegram's TextCheckCell and TextSettingsCell on the Secured provider, and
 * SecuredSheets.copyToClipboard (sensitive clip, cleared after a minute). The log comes only from SecuredDiagnostics.
 */
final class SecuredDiagnosticsActivity extends SecuredBackupScreen {

    @Override
    int titleRes() {
        return R.string.sec_r2d_title;
    }

    @Override
    void onBuild(Context context) {
        content.setPadding(0, 0, 0, 0);
        refresh();
    }

    private void refresh() {
        if (fragmentView == null || content == null) return;
        final Context c = fragmentView.getContext();
        final Theme.ResourcesProvider rp = SecuredResourcesProvider.get();
        final boolean on = SecuredDiagnostics.INSTANCE.isEnabled(c);
        content.removeAllViews();

        final TextCheckCell toggle = new TextCheckCell(c, rp);
        toggle.setTextAndCheck(c.getString(R.string.sec_r2d_toggle), on, false);
        toggle.setOnClickListener(v -> {
            SecuredDiagnostics.INSTANCE.setEnabled(c, !SecuredDiagnostics.INSTANCE.isEnabled(c));
            refresh();
        });
        content.addView(toggle);

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(c, rp);
        info.setText(c.getString(R.string.sec_r2d_info));
        content.addView(info);

        final List<String> lines = SecuredDiagnostics.INSTANCE.lines(c);
        final String text = android.text.TextUtils.join("\n", lines);

        final TextView log = Kit.text(c, text.isEmpty()
                ? c.getString(on ? R.string.sec_r2d_empty : R.string.sec_r2d_off) : text,
                11, Kit.color(Theme.key_windowBackgroundWhiteBlackText), false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.addView(log, wide(0));

        if (!lines.isEmpty()) {
            final TextSettingsCell copy = new TextSettingsCell(c, rp);
            copy.setText(c.getString(R.string.sec_r2d_copy), true);
            copy.setOnClickListener(v -> {
                SecuredSheets.copyToClipboard(c, text, true);
                Toast.makeText(c, R.string.sec_r2d_copied, Toast.LENGTH_SHORT).show();
            });
            content.addView(copy);

            final TextSettingsCell clear = new TextSettingsCell(c, rp);
            clear.setText(c.getString(R.string.sec_r2d_clear), false);
            clear.setTextColor(Kit.color(Theme.key_text_RedBold));
            clear.setOnClickListener(v -> {
                SecuredDiagnostics.INSTANCE.clear(c);
                refresh();
            });
            content.addView(clear);
        }
    }
}
