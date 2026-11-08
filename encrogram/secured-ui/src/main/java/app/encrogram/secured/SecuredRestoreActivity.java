package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.encrogram.core.api.BackupInfo;
import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredResult;

/**
 * "Restore with 12 words": one field that splits on spaces, BIP39 suggestions from the engine, a look-up of the backup
 * (its date) before anything is replaced, then the restore and the plain-language limits. Restoring only works while
 * the engine has no profile ({@code NEEDS_PROFILE}); the screen starts the engine itself when it was not started yet.
 */
final class SecuredRestoreActivity extends SecuredBackupScreen {

    private final boolean fromWelcome;   // opened from the welcome screen: on success replace it with the Secured list

    private EngineState engineState = EngineState.NOT_STARTED;
    private boolean listening;
    private List<String> words;   // set once the 12 words were accepted by findBackup

    private EditText input;
    private TextView counter, hint, findButton;
    private LinearLayout chips;
    private HorizontalScrollView chipScroll;

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onStateChanged(EngineState state) {
            engineState = state;
        }
    };

    SecuredRestoreActivity(boolean fromWelcome) {
        this.fromWelcome = fromWelcome;
    }

    @Override
    int titleRes() {
        return R.string.sec_rs_title;
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (engine != null && !listening) {
                listening = true;
                engine.addListener(listener);   // replays the state at once
            }
            if (engine != null && engine.state() == EngineState.NOT_STARTED) {
                engine.start();   // from the welcome screen nothing has started the engine yet
            }
        } catch (Throwable ignored) {
            engineState = EngineState.FAILED;
        }
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
        words = null;
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (words != null && input != null && content.indexOfChild(input) < 0) {
            // On the "found" or "done" step: Back returns to typing the words (not after a finished restore).
            if (invoked) showInput(fragmentView.getContext());
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ---- step 1: type the words ------------------------------------------------------------------------------

    @Override
    void onBuild(Context c) {
        showInput(c);
    }

    private void showInput(Context c) {
        words = null;
        final TextView title = heading(c, c.getString(R.string.sec_rs_title));
        final TextView text = para(c, c.getString(R.string.sec_rs_text));

        input = new EditText(c);
        input.setHint(R.string.sec_rs_hint);
        input.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        input.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        // Multi-line, lower case, no suggestions or autocorrect; the keyboard must not learn the words.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 12));

        counter = Kit.text(c, c.getString(R.string.sec_rs_count, 0), 13, Kit.color(Theme.key_windowBackgroundWhiteGrayText), false);
        chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll = new HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        hint = errorView(c);
        findButton = Kit.button(c, c.getString(R.string.sec_rs_find), true);
        findButton.setEnabled(false);
        findButton.setAlpha(0.6f);
        findButton.setOnClickListener(v -> find(c));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable s) {
                onWordsEdited(c);
            }
        });

        showStep(title, lp(text, 8), lp(input, 20), lp(counter, 6), lp(chipScroll, 8), lp(hint, 8), lp(findButton, 16));
        input.requestFocus();
        onWordsEdited(c);
    }

    private static View lp(View v, int topDp) {
        v.setLayoutParams(wide(topDp));
        return v;
    }

    private static List<String> tokens(String text) {
        final List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void onWordsEdited(Context c) {
        final String text = input.getText().toString();
        final List<String> tokens = tokens(text);
        counter.setText(c.getString(R.string.sec_rs_count, tokens.size()));
        final boolean typing = !text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1));
        chips.removeAllViews();
        String bad = null;
        try {
            // Suggestions for the word being typed.
            if (typing && !tokens.isEmpty() && engine != null) {
                final String prefix = tokens.get(tokens.size() - 1);
                for (String w : engine.phraseSuggestions(prefix, 4)) addChip(c, w, prefix);
            }
            // A finished word that is not in the list is flagged (only when the engine has a list at all).
            if (engine != null && !engine.phraseSuggestions("a", 1).isEmpty()) {
                final int finished = typing ? tokens.size() - 1 : tokens.size();
                for (int i = 0; i < finished && bad == null; i++) {
                    final String w = tokens.get(i);
                    if (!engine.phraseSuggestions(w, 2048).contains(w)) bad = w;
                }
            }
        } catch (Throwable ignored) {
        }
        chipScroll.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        setError(hint, bad != null ? c.getString(R.string.sec_rs_bad_word, bad) : null);
        final boolean ready = tokens.size() == 12 && bad == null;
        findButton.setEnabled(ready);
        findButton.setAlpha(ready ? 1f : 0.6f);
    }

    private void addChip(Context c, String word, String prefix) {
        final TextView chip = Kit.text(c, word, 16, SecuredResourcesProvider.accentText(), true);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(Kit.roundRect(androidx.core.graphics.ColorUtils.setAlphaComponent(SecuredResourcesProvider.accentFill(), 0x22), 16));
        chip.setOnClickListener(v -> {
            final String text = input.getText().toString();
            final int cut = text.length() - prefix.length();
            if (cut >= 0) {
                input.setText(text.substring(0, cut) + word + " ");
                input.setSelection(input.getText().length());
            }
        });
        chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
    }

    // ---- step 2: look the backup up --------------------------------------------------------------------------

    private void find(Context c) {
        if (engine == null) {
            setError(hint, c.getString(R.string.sec_rs_engine_failed));
            return;
        }
        final List<String> typed = tokens(input.getText().toString());
        if (typed.size() != 12) return;
        setError(hint, null);
        findButton.setEnabled(false);
        findButton.setText(R.string.sec_rs_finding);
        AndroidUtilities.hideKeyboard(input);
        try {
            engine.findBackup(new ArrayList<>(typed), result -> ui(() -> {
                if (destroyed) return;
                findButton.setEnabled(true);
                findButton.setText(R.string.sec_rs_find);
                if (result instanceof SecuredResult.Ok) {
                    words = typed;
                    showFound(c, ((SecuredResult.Ok<BackupInfo>) result).getValue());
                } else {
                    setError(hint, errorText(c, ((SecuredResult.Err) result).getError()));
                }
            }));
        } catch (Throwable t) {
            findButton.setEnabled(true);
            findButton.setText(R.string.sec_rs_find);
            setError(hint, c.getString(R.string.sec_bk_err_unknown));
        }
    }

    private void showFound(Context c, BackupInfo info) {
        final TextView title = heading(c, c.getString(R.string.sec_rs_found_title));
        final TextView text = bodyText(c, c.getString(R.string.sec_rs_found_text, when(c, info.getCreatedAtMs())));
        final View warning = point(c, c.getString(R.string.sec_rs_found_warning));
        final TextView error = errorView(c);
        final TextView restore = Kit.button(c, c.getString(R.string.sec_rs_restore), true);
        final TextView change = Kit.button(c, c.getString(R.string.sec_rs_change_words), false);
        restore.setOnClickListener(v -> restore(c, info, restore, change, error));
        change.setOnClickListener(v -> showInput(c));
        showStep(title, lp(text, 12), lp(warning, 20), lp(error, 12), lp(restore, 20), lp(change, 10));
    }

    // ---- step 3: restore -------------------------------------------------------------------------------------

    private void restore(Context c, BackupInfo info, TextView restore, TextView change, TextView error) {
        if (engine == null || words == null) return;
        // The engine restores only onto a phone with no Secured profile.
        switch (engineState) {
            case NEEDS_PROFILE:
                break;
            case RUNNING:
                setError(error, c.getString(R.string.sec_rs_has_profile));
                return;
            case FAILED:
                setError(error, c.getString(R.string.sec_rs_engine_failed));
                return;
            default:
                setError(error, c.getString(R.string.sec_rs_starting));
                return;
        }
        setError(error, null);
        restore.setEnabled(false);
        change.setEnabled(false);
        restore.setText(R.string.sec_rs_restoring);
        try {
            engine.restore(new ArrayList<>(words), result -> ui(() -> {
                if (destroyed) return;
                restore.setEnabled(true);
                change.setEnabled(true);
                restore.setText(R.string.sec_rs_restore);
                if (result instanceof SecuredResult.Ok) {
                    showDone(c, info);
                } else {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    setError(error, errorText(c, e));
                }
            }));
        } catch (Throwable t) {
            restore.setEnabled(true);
            change.setEnabled(true);
            restore.setText(R.string.sec_rs_restore);
            setError(error, c.getString(R.string.sec_bk_err_unknown));
        }
    }

    private void showDone(Context c, BackupInfo info) {
        words = null;   // a finished restore: Back must not return to the words
        input = null;
        final TextView title = heading(c, c.getString(R.string.sec_rs_done_title));
        final TextView back = bodyText(c, c.getString(R.string.sec_rs_done_back, when(c, info.getCreatedAtMs())));
        final View resync = point(c, c.getString(R.string.sec_rs_done_resync));
        final View verify = point(c, c.getString(R.string.sec_rs_done_verify));
        final View oldPhone = point(c, c.getString(R.string.sec_rs_done_old_phone));
        final TextView next = Kit.button(c, c.getString(R.string.sec_rs_continue), true);
        next.setOnClickListener(v -> leave());
        showStep(title, lp(back, 12), lp(resync, 20), lp(verify, 12), lp(oldPhone, 12), lp(next, 24));
    }

    private void leave() {
        if (!fromWelcome) {
            finishFragment();   // the Secured page underneath follows the engine state to the chat list
            return;
        }
        // From the welcome screen: replace both this screen and the welcome with the Secured list.
        final List<BaseFragment> stack = getParentLayout() != null ? new ArrayList<>(getParentLayout().getFragmentStack()) : new ArrayList<>();
        SecuredUi.setSecuredOnly();
        for (BaseFragment f : stack) {
            if (f instanceof SecuredWelcomeActivity) getParentLayout().removeFragmentFromStack(f);
        }
        presentFragment(new SecuredDialogsActivity(), true);
    }
}
