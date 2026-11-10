package app.encrogram.secured;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.EncrogramHooks;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.messenger.LocaleController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LoginActivity;

import app.encrogram.core.api.SecuredEngine;

/**
 * Entry point for the app module. Installs the hook registry and owns the single engine instance.
 *
 * The engine is created lazily, the first time the Secured page needs it, so a phone that never opens Secured
 * never loads the SimpleX libraries.
 */
public final class SecuredUi {

    /** Builds the engine (relay addresses and allowlist come from the app module). */
    public interface EngineProvider {
        SecuredEngine create(Context applicationContext);
    }

    /** Told when a deep link arrives while the Chats page may be on screen. */
    public interface LinkListener {
        void onSecuredLink();
    }

    /** Told when a Secured notification was tapped while the Chats page may be on screen. */
    public interface OpenChatListener {
        void onSecuredOpenChat();
    }

    /** Action and extra of a Secured notification's tap intent (built in the app module). */
    public static final String ACTION_OPEN_CHAT = "app.encrogram.secured.OPEN_CHAT";
    public static final String EXTRA_CHAT_ID = "secured_chat_id";
    /** "Open the Secured tab, no particular chat" (the notification summary). */
    static final long NO_CHAT = Long.MIN_VALUE;

    private static Application app;
    private static SecuredHooks hooks;
    private static EngineProvider provider;
    private static SecuredEngine engine;
    private static String engineError;

    private static String pendingLink;
    private static LinkListener linkListener;
    private static Long pendingOpenChat;
    private static OpenChatListener openChatListener;

    private SecuredUi() {}

    /** Called once from Application.onCreate(), before Telegram initialises. */
    public static void install(Application application, EngineProvider engineProvider) {
        app = application;
        provider = engineProvider;
        hooks = new SecuredHooks();
        EncrogramHooks.install(hooks);
    }

    /** The engine, or null when it cannot be created ({@link #engineError()} then says why). */
    public static synchronized SecuredEngine engine() {
        if (engine == null && provider != null && app != null) {
            try {
                engine = provider.create(app);
                engineError = null;
            } catch (Throwable t) {
                // Never log the throwable through Telegram's FileLog: keep only a short, content-free reason.
                engineError = t.getClass().getSimpleName();
                engine = null;
            }
        }
        return engine;
    }

    public static synchronized String engineError() {
        return engineError;
    }

    // ---- deep links ------------------------------------------------------------------------------------------

    /** Called by the app's link activity. The link is kept in memory only. */
    public static void deliverLink(String link) {
        synchronized (SecuredUi.class) {
            pendingLink = link;
        }
        final LinkListener l = linkListener;
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(l::onSecuredLink);
        }
    }

    /** Re-offers a waiting link to the listener; called when a Secured screen resumes (after the passcode). */
    static void nudgeLink() {
        final LinkListener l = linkListener;
        if (l != null && hasPendingLink()) {
            new Handler(Looper.getMainLooper()).post(l::onSecuredLink);
        }
    }

    private static synchronized boolean hasPendingLink() {
        return pendingLink != null;
    }

    static synchronized String takePendingLink() {
        final String l = pendingLink;
        pendingLink = null;
        return l;
    }

    static void setLinkListener(LinkListener listener) {
        linkListener = listener;
    }

    /** Clears the link listener only if it is still {@code listener} (a newer owner may have replaced it). */
    static void clearLinkListener(LinkListener listener) {
        if (linkListener == listener) linkListener = null;
    }

    // ---- notification taps -----------------------------------------------------------------------------------

    /** Called by the intent hook. The chat id is kept in memory only; {@link #NO_CHAT} means just the Secured tab. */
    public static void deliverOpenChat(long chatId) {
        synchronized (SecuredUi.class) {
            pendingOpenChat = chatId;
        }
        final OpenChatListener l = openChatListener;
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(l::onSecuredOpenChat);
        }
    }

    static synchronized Long takePendingOpenChat() {
        final Long id = pendingOpenChat;
        pendingOpenChat = null;
        return id;
    }

    static void setOpenChatListener(OpenChatListener listener) {
        openChatListener = listener;
    }

    /** Clears the open-chat listener only if it is still {@code listener}. */
    static void clearOpenChatListener(OpenChatListener listener) {
        if (openChatListener == listener) openChatListener = null;
    }

    /** True while a Secured screen is alive to consume a notification tap. */
    static boolean hasOpenChatListener() {
        return openChatListener != null;
    }

    // ---- drafts (AR9) ----------------------------------------------------------------------------------------

    /** An unsent message. Memory only: never written to disk or a log (the wall forbids Secured text in preferences). */
    static final class Draft {
        final String text;
        final Long replyId;

        Draft(String text, Long replyId) {
            this.text = text;
            this.replyId = replyId;
        }
    }

    private static final java.util.HashMap<Long, Draft> drafts = new java.util.HashMap<>();
    private static String draftLabel = "Draft: ";
    private static Runnable draftListener;

    /** Keeps (or, for blank text, drops) the draft of one chat. {@code label} is the localized "Draft: " for the list row. */
    static synchronized void setDraft(long chatId, String text, Long replyId, String label) {
        if (label != null) draftLabel = label;
        final Draft old = drafts.get(chatId);
        final boolean blank = text == null || text.trim().isEmpty();
        if (blank) {
            if (drafts.remove(chatId) == null) return;
        } else {
            if (old != null && old.text.equals(text) && java.util.Objects.equals(old.replyId, replyId)) return;
            drafts.put(chatId, new Draft(text, replyId));
        }
        final Runnable l = draftListener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);   // the list refreshes its row
    }

    /** Reset or restore: drafts belong to the old profile (Saved Messages keeps the same id across profiles). */
    static synchronized void clearDrafts() {
        drafts.clear();
    }

    /** Drops drafts of chats that no longer exist (deleted; ids can be reused by the core). */
    static synchronized void pruneDrafts(java.util.Set<Long> liveChatIds) {
        drafts.keySet().retainAll(liveChatIds);
    }

    static synchronized Draft draft(long chatId) {
        return drafts.get(chatId);
    }

    static synchronized String draftLabel() {
        return draftLabel;
    }

    /** The chat list registers a refresh here (one owner at a time). */
    static synchronized void setDraftListener(Runnable listener) {
        draftListener = listener;
    }

    static synchronized void clearDraftListener(Runnable listener) {
        if (draftListener == listener) draftListener = null;
    }

    // ---- logged-out doors ------------------------------------------------------------------------------------

    /** Secured's own preferences file (never Telegram's). */
    static final String UI_PREFS = "encrogram_secured_ui";
    private static final String KEY_SECURED_ONLY = "secured_only";

    /** True once the user chose "Continue without Telegram". One SharedPreferences read; safe in onCreate. */
    static boolean securedOnly() {
        try {
            return app != null && app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SECURED_ONLY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void setSecuredOnly() {
        try {
            if (app != null) {
                app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SECURED_ONLY, true).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Telegram's own login (the phone-number screen), on top of {@code from} so Back returns. It skips Telegram's
     * IntroActivity, whose first page draws Telegram's official logo (AGENTS.md rule 6). LoginActivity's intro
     * transition is disabled upstream, so it opens fine on its own. A fresh instance each time.
     */
    static void openTelegramLogin(BaseFragment from) {
        from.presentFragment(new SecuredLoginEntry());
        // What IntroActivity did before login: fetch DC settings and the language list. The wake-up hook
        // (willPresentFragment) has already started Telegram's network by this point.
        try {
            final int account = from.getCurrentAccount();
            ConnectionsManager.getInstance(account).updateDcSettings();
            LocaleController.getInstance().loadRemoteLanguages(account);
        } catch (Throwable ignored) {
        }
    }

    /**
     * HT-2: the Telegram segment of the Secured-only list asks first, in Telegram's dialog: signing in connects to
     * Telegram's servers. Only "Sign in" opens the login (the welcome screen's explicit button opens it directly).
     */
    static void askTelegramLogin(BaseFragment from) {
        final Context context = from.getParentActivity();
        if (context == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(context, SecuredResourcesProvider.get());
        b.setTitle(context.getString(R.string.sec_tg_signin_title));
        b.setMessage(context.getString(R.string.sec_tg_signin_text));
        b.setNegativeButton(context.getString(R.string.sec_tg_signin_cancel), null);
        b.setPositiveButton(context.getString(R.string.sec_tg_signin_ok), (d, w) -> openTelegramLogin(from));
        Kit.show(from, b.create());
    }

    /** HT-2: a Secured root screen is fully visible again: Telegram goes back to sleep if nothing uses it. Never throws. */
    static void telegramMaybeSleep() {
        final SecuredHooks h = hooks;
        if (h != null) h.sleepTelegramIfUnused();
    }

    /**
     * Telegram's LoginActivity unchanged (reuse level 2: a subclass), only told when it closes, so Telegram can go back to
     * sleep also on tablets, where the login opens in a layer over our screen and our screen gets no visibility callback.
     * Checked after the stack has settled; the check itself refuses while an account is active or a login is half done.
     */
    static final class SecuredLoginEntry extends LoginActivity {
        @Override
        public void onFragmentDestroy() {
            super.onFragmentDestroy();
            AndroidUtilities.runOnUIThread(SecuredUi::telegramMaybeSleep);
        }
    }

    // ---- debug self-check -------------------------------------------------------------------------------------

    private static boolean selfChecked;

    /**
     * Debug builds only: runs the row and message mapper self-checks once per process and logs PASS or the failed
     * check names to logcat (tag EncrogramSelfCheck). The checks use made-up text, never user data.
     */
    static void debugSelfCheck(Context context, int account) {
        if (selfChecked || context == null
                || (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        selfChecked = true;
        final java.util.ArrayList<String> failed = new java.util.ArrayList<>(ChatRowMapper.selfCheck());
        failed.addAll(SecuredMessageMapper.selfCheck(context, account));
        android.util.Log.i("EncrogramSelfCheck", failed.isEmpty() ? "PASS" : "FAIL " + failed);
    }
}
