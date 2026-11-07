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

    private static Application app;
    private static EngineProvider provider;
    private static SecuredEngine engine;
    private static String engineError;

    private static String pendingLink;
    private static LinkListener linkListener;

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
}
