package app.encrogram.secured;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.EncrogramHooks;

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
        EncrogramHooks.install(new SecuredHooks());
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

    static synchronized String takePendingLink() {
        final String l = pendingLink;
        pendingLink = null;
        return l;
    }

    static void setLinkListener(LinkListener listener) {
        linkListener = listener;
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
