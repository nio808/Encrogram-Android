package app.encrogram.secured;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.encrogram.core.api.BackupState;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredResult;

/**
 * "Back up Secured": honest explanation, the 12 words in a numbered grid, a check of 3 random positions, then
 * {@code enableBackup}. Also shows the words again (view-only, after the screen-lock check in the settings screen).
 * No copy or share button anywhere; the screen is FLAG_SECURE (base class).
 */
final class SecuredBackupActivity extends SecuredBackupScreen {

    private enum Step { INTRO, WORDS, CONFIRM, DONE }

    private final boolean viewOnly;
    private Step step = Step.INTRO;
    private List<String> words;
    private int[] positions;

    private TextView introButton, introError, confirmButton, confirmError;

    SecuredBackupActivity() {
        viewOnly = false;
    }

    private SecuredBackupActivity(List<String> shownWords) {
        viewOnly = true;
        words = new ArrayList<>(shownWords);
        step = Step.WORDS;
    }

    /** Shows the user's existing 12 words, without the confirm steps. */
    static SecuredBackupActivity forViewing(List<String> words) {
        return new SecuredBackupActivity(words);
    }

    @Override
    int titleRes() {
        return viewOnly ? R.string.sec_bk_words_title : R.string.sec_bk_title;
    }

    @Override
    void onBuild(Context context) {
        render(context);
    }

    @Override
    public void onFragmentDestroy() {
        if (words != null) words = null;   // best effort: don't keep the words in memory after the screen closes
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (step == Step.CONFIRM) {
            if (invoked) goTo(Step.WORDS);
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void goTo(Step next) {
        step = next;
        if (fragmentView != null) render(fragmentView.getContext());
    }

    private void render(Context c) {
        AndroidUtilities.hideKeyboard(fragmentView);
        switch (step) {
            case INTRO:
                renderIntro(c);
                break;
            case WORDS:
                renderWords(c);
                break;
            case CONFIRM:
                renderConfirm(c);
                break;
            default:
                renderDone(c);
        }
    }

    // ---- 1: explanation --------------------------------------------------------------------------------------

    private void renderIntro(Context c) {
        final TextView title = heading(c, c.getString(R.string.sec_bk_title));
        final TextView intro = bodyText(c, c.getString(R.string.sec_bk_intro));
        final View p1 = point(c, c.getString(R.string.sec_bk_point_lose));
        final View p2 = point(c, c.getString(R.string.sec_bk_point_anyone));
        final View p3 = point(c, c.getString(R.string.sec_bk_point_only));
        final View p4 = point(c, c.getString(R.string.sec_bk_point_what));
        introError = errorView(c);
        introButton = Kit.button(c, c.getString(R.string.sec_bk_show_words), true);
        introButton.setOnClickListener(v -> loadNewPhrase(c));
        final TextView later = Kit.button(c, c.getString(R.string.sec_bk_not_now), false);
        later.setOnClickListener(v -> finishFragment());
        showStep(title, wrap(intro, 12), wrap(p1, 20), wrap(p2, 12), wrap(p3, 12), wrap(p4, 12), introError,
                wrap(introButton, 24), wrap(later, 10));
    }

    private void loadNewPhrase(Context c) {
        if (engine == null) {
            setError(introError, c.getString(R.string.sec_bk_err_unknown));
            return;
        }
        setError(introError, null);
        introButton.setEnabled(false);
        introButton.setText(R.string.sec_bk_preparing);
        try {
            engine.newPhrase(result -> ui(() -> {
                if (destroyed) return;
                introButton.setEnabled(true);
                introButton.setText(R.string.sec_bk_show_words);
                if (result instanceof SecuredResult.Ok) {
                    words = new ArrayList<>(((SecuredResult.Ok<List<String>>) result).getValue());
                    goTo(Step.WORDS);
                } else {
                    setError(introError, errorText(c, ((SecuredResult.Err) result).getError()));
                }
            }));
        } catch (Throwable t) {
            introButton.setEnabled(true);
            introButton.setText(R.string.sec_bk_show_words);
            setError(introError, c.getString(R.string.sec_bk_err_unknown));
        }
    }

    // ---- 2: the words ----------------------------------------------------------------------------------------

    private void renderWords(Context c) {
        final TextView title = heading(c, c.getString(R.string.sec_bk_words_title));
        final TextView text = para(c, c.getString(R.string.sec_bk_words_text));
        final View grid = words != null ? wordGrid(c, words) : new View(c);
        final View lose = point(c, c.getString(R.string.sec_bk_point_lose));
        final View anyone = point(c, c.getString(R.string.sec_bk_point_anyone));
        final View only = point(c, c.getString(R.string.sec_bk_point_only));
        final TextView next = Kit.button(c, c.getString(viewOnly ? R.string.sec_bk_done : R.string.sec_bk_wrote_down), true);
        next.setOnClickListener(v -> {
            if (viewOnly) finishFragment();
            else startConfirm(c);
        });
        showStep(title, wrap(text, 8), wrap(grid, 20), wrap(lose, 24), wrap(anyone, 10), wrap(only, 10), wrap(next, 24));
    }

    // ---- 3: confirm three words ------------------------------------------------------------------------------

    private void startConfirm(Context c) {
        if (words == null || words.size() != 12) return;
        // Three distinct random positions, shown in order.
        final SecureRandom random = new SecureRandom();
        final List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) all.add(i);
        positions = new int[3];
        for (int i = 0; i < 3; i++) positions[i] = all.remove(random.nextInt(all.size()));
        Arrays.sort(positions);
        goTo(Step.CONFIRM);
    }

    private void renderConfirm(Context c) {
        final TextView title = heading(c, c.getString(R.string.sec_bk_confirm_title));
        final TextView text = para(c, c.getString(R.string.sec_bk_confirm_text));
        final EditText[] inputs = new EditText[3];
        final View[] views = new View[3];
        for (int i = 0; i < 3; i++) {
            final EditText e = wordInput(c, i == 2 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            e.setHint(c.getString(R.string.sec_bk_confirm_hint, positions[i] + 1));
            inputs[i] = e;
            views[i] = e;
        }
        inputs[2].setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(c, inputs);
                return true;
            }
            return false;
        });
        confirmError = errorView(c);
        confirmButton = Kit.button(c, c.getString(R.string.sec_bk_confirm_button), true);
        confirmButton.setOnClickListener(v -> submit(c, inputs));
        final TextView again = Kit.button(c, c.getString(R.string.sec_bk_confirm_back), false);
        again.setOnClickListener(v -> goTo(Step.WORDS));
        showStep(title, wrap(text, 8), wrap(views[0], 20), wrap(views[1], 12), wrap(views[2], 12), wrap(confirmError, 10),
                wrap(confirmButton, 20), wrap(again, 10));
        inputs[0].requestFocus();
    }

    private void submit(Context c, EditText[] inputs) {
        if (words == null || engine == null) return;
        for (int i = 0; i < 3; i++) {
            final String typed = inputs[i].getText().toString().trim().toLowerCase(Locale.ROOT);
            if (!typed.equals(words.get(positions[i]))) {
                setError(confirmError, c.getString(R.string.sec_bk_confirm_mismatch));
                return;
            }
        }
        setError(confirmError, null);
        AndroidUtilities.hideKeyboard(fragmentView);
        confirmButton.setEnabled(false);
        confirmButton.setText(R.string.sec_bk_confirm_busy);
        try {
            engine.enableBackup(new ArrayList<>(words), result -> ui(() -> {
                if (destroyed) return;
                confirmButton.setEnabled(true);
                confirmButton.setText(R.string.sec_bk_confirm_button);
                if (result instanceof SecuredResult.Ok) {
                    markOffered(c);
                    goTo(Step.DONE);
                } else {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    setError(confirmError, errorText(c, e));
                }
            }));
        } catch (Throwable t) {
            confirmButton.setEnabled(true);
            confirmButton.setText(R.string.sec_bk_confirm_button);
            setError(confirmError, c.getString(R.string.sec_bk_err_unknown));
        }
    }

    // ---- 4: done ---------------------------------------------------------------------------------------------

    private void renderDone(Context c) {
        words = null;   // not needed any more
        long at = System.currentTimeMillis();
        try {
            final BackupState s = engine != null ? engine.backupState() : null;
            if (s != null && s.getLastBackupMs() != null) at = s.getLastBackupMs();
        } catch (Throwable ignored) {
        }
        final android.widget.LinearLayout center = new android.widget.LinearLayout(c);
        center.setOrientation(android.widget.LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.addView(new Kit.LockBadge(c), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        final TextView title = heading(c, c.getString(R.string.sec_bk_done_title));
        center.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView date = bodyText(c, c.getString(R.string.sec_bk_done_when, when(c, at)));
        date.setGravity(Gravity.CENTER);
        center.addView(date, wide(8));
        final TextView text = para(c, c.getString(R.string.sec_bk_done_text));
        text.setGravity(Gravity.CENTER);
        center.addView(text, wide(12));
        final TextView done = Kit.button(c, c.getString(R.string.sec_bk_done), true);
        done.setOnClickListener(v -> finishFragment());
        center.addView(done, wide(28));
        showStep(center);
    }

    /** The one-time offer after profile creation has been dealt with: don't ask again. */
    private static void markOffered(Context c) {
        SecuredBackupUi.markOffered(c);
    }

    private static View wrap(View v, int topDp) {
        v.setLayoutParams(wide(topDp));
        return v;
    }
}
