package app.encrogram.secured;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

import app.encrogram.core.api.BackupState;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredResult;

/**
 * Backup settings (from the Secured list's menu): last backup or last error, Back up now, Show 12 words (behind the
 * phone's screen lock), Turn off and delete backup. Refreshes on {@code onBackupStateChanged}.
 */
final class SecuredBackupSettingsActivity extends SecuredBackupScreen {

    private static final int REQUEST_CONFIRM_CREDENTIAL = 7302;   // Telegram's codes: 2..22, 104, 150..211

    private boolean listening;
    private boolean busy;   // a Back up now / show / turn off call of ours is in flight
    private String actionError;

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onBackupStateChanged() {
            ui(() -> {
                if (!destroyed) refresh();
            });
        }
    };

    @Override
    int titleRes() {
        return R.string.sec_bs_title;
    }

    @Override
    void onBuild(Context context) {
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (engine != null && !listening) {
            try {
                engine.addListener(listener);
                listening = true;
            } catch (Throwable ignored) {
            }
        }
        refresh();
    }

    @Override
    public void onFragmentDestroy() {
        if (engine != null && listening) {
            try {
                engine.removeListener(listener);
            } catch (Throwable ignored) {
            }
            listening = false;
        }
        super.onFragmentDestroy();
    }

    private BackupState state() {
        try {
            return engine != null ? engine.backupState() : new BackupState();
        } catch (Throwable t) {
            return new BackupState();
        }
    }

    private void refresh() {
        if (fragmentView == null || content == null) return;
        final Context c = fragmentView.getContext();
        final BackupState s = state();
        if (!s.getEnabled()) {
            final TextView off = heading(c, c.getString(R.string.sec_bs_off));
            final TextView text = para(c, c.getString(R.string.sec_bs_off_text));
            final TextView start = Kit.button(c, c.getString(R.string.sec_bs_off_button), true);
            start.setOnClickListener(v -> presentFragment(new SecuredBackupActivity()));
            showStep(off, lp(text, 8), lp(start, 24));
            return;
        }
        final TextView title = heading(c, c.getString(R.string.sec_bs_on));
        final TextView last = bodyText(c, c.getString(s.getOtherPhone() ? R.string.sec_bs_other_phone : R.string.sec_bs_on_text));
        final TextView error = errorView(c);
        final String shown = actionError != null ? actionError
                : s.getLastError() != null && !s.getOtherPhone() ? c.getString(R.string.sec_bs_error) : null;
        setError(error, shown);
        final boolean running = s.getRunning() || busy;
        final TextView now = Kit.button(c, c.getString(running ? R.string.sec_bs_now_busy : R.string.sec_bs_now_take_over), true);
        now.setEnabled(!running);
        now.setAlpha(running ? 0.6f : 1f);
        now.setOnClickListener(v -> backupNow(c));
        now.setVisibility(s.getOtherPhone() ? View.VISIBLE : View.GONE);   // only after a restore elsewhere
        final TextView show = Kit.button(c, c.getString(R.string.sec_bs_show), false);
        show.setOnClickListener(v -> askScreenLock(c));
        final TextView off = Kit.button(c, c.getString(R.string.sec_bs_off_action), false);
        off.setTextColor(Kit.color(Theme.key_text_RedBold));
        off.setOnClickListener(v -> confirmTurnOff(c));
        showStep(title, lp(last, 12), lp(error, 8), lp(now, s.getOtherPhone() ? 20 : 0), lp(show, 12), lp(off, 12));
    }

    private static View lp(View v, int topDp) {
        v.setLayoutParams(wide(topDp));
        return v;
    }

    // ---- back up now -----------------------------------------------------------------------------------------

    private void backupNow(Context c) {
        if (engine == null || busy) return;
        busy = true;
        actionError = null;
        refresh();
        try {
            engine.backupNow(result -> ui(() -> {
                busy = false;
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    actionError = c.getString(R.string.sec_bs_action_failed);
                } else {
                    Toast.makeText(c, R.string.sec_bs_now_done, Toast.LENGTH_SHORT).show();
                }
                refresh();
            }));
        } catch (Throwable t) {
            busy = false;
            actionError = c.getString(R.string.sec_bk_err_unknown);
            refresh();
        }
    }

    // ---- show the words: screen lock first, then showPhrase within 30 s ---------------------------------------

    private void askScreenLock(Context c) {
        final KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        Intent intent = null;
        if (km != null && km.isDeviceSecure()) {
            intent = km.createConfirmDeviceCredentialIntent(c.getString(R.string.sec_bs_unlock_title), c.getString(R.string.sec_bs_unlock_text));
        }
        if (intent == null) {
            needScreenLock(c);
            return;
        }
        try {
            startActivityForResult(intent, REQUEST_CONFIRM_CREDENTIAL);
        } catch (Throwable t) {
            needScreenLock(c);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_CONFIRM_CREDENTIAL || destroyed || fragmentView == null) return;
        final Context c = fragmentView.getContext();
        if (resultCode != Activity.RESULT_OK) return;   // cancelled: nothing to say
        if (engine == null) return;
        try {
            // Straight away: the engine needs the unlock to be less than 30 seconds old.
            engine.showPhrase(result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Ok) {
                    final List<String> words = ((SecuredResult.Ok<List<String>>) result).getValue();
                    presentFragment(SecuredBackupActivity.forViewing(words));
                } else {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    if (e.getKind() == SecuredError.Kind.NEEDS_UNLOCK) {
                        info(c, c.getString(R.string.sec_bs_need_lock_title), c.getString(R.string.sec_bs_unlock_again));
                    } else {
                        info(c, c.getString(R.string.sec_bs_title), errorText(c, e));
                    }
                }
            }));
        } catch (Throwable t) {
            info(c, c.getString(R.string.sec_bs_title), c.getString(R.string.sec_bk_err_unknown));
        }
    }

    private void needScreenLock(Context c) {
        info(c, c.getString(R.string.sec_bs_need_lock_title), c.getString(R.string.sec_bs_need_lock_text));
    }

    private void info(Context c, String title, String message) {
        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(title);
        b.setMessage(message);
        b.setPositiveButton(c.getString(R.string.sec_bs_ok), null);
        showDialog(b.create());
    }

    // ---- turn off --------------------------------------------------------------------------------------------

    private void confirmTurnOff(Context c) {
        if (engine == null || busy) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.sec_bs_confirm_off_title));
        b.setMessage(c.getString(R.string.sec_bs_confirm_off_text));
        b.setNegativeButton(c.getString(R.string.sec_bs_cancel), null);
        b.setPositiveButton(c.getString(R.string.sec_bs_confirm_off_button), (d, w) -> turnOff(c));
        final AlertDialog dialog = b.create();
        showDialog(dialog);
        final View ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (ok instanceof TextView) ((TextView) ok).setTextColor(Theme.getColor(Theme.key_text_RedBold, SecuredResourcesProvider.get()));
    }

    private void turnOff(Context c) {
        busy = true;
        actionError = null;
        refresh();
        try {
            engine.disableBackup(result -> ui(() -> {
                busy = false;
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    // The engine's text here can carry a security fact (an old phrase that still works), so show it.
                    actionError = errorText(c, ((SecuredResult.Err) result).getError());
                } else {
                    SecuredBackupUi.showBannerAgain(c);
                }
                refresh();   // on success the state says "off" and the banner on the list returns
            }));
        } catch (Throwable t) {
            busy = false;
            actionError = c.getString(R.string.sec_bk_err_unknown);
            refresh();
        }
    }
}
