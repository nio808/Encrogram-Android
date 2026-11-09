package app.encrogram.android;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.BuildVars;
import org.telegram.ui.LaunchActivity;

import android.content.Intent;
import android.graphics.Color;

import app.encrogram.core.background.NotificationConfig;
import app.encrogram.core.api.SecuredDiagnostics;
import app.encrogram.core.background.SecuredBackground;
import app.encrogram.secured.SecuredUi;

/**
 * Encrogram's Application. Replaces upstream's ApplicationLoaderImpl (which lives in TMessagesProj_App).
 *
 * Startup order matters:
 *  1. make Telegram's static context available (BuildVars' static initialiser reads preferences through it),
 *  2. set identity values that Telegram reads later (API id/hash are non-final static fields),
 *  3. install the Secured UI hook registry, then
 *  4. let Telegram initialise.
 */
public class EncrogramApplication extends ApplicationLoader {

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public void onCreate() {
        // Same two assignments ApplicationLoader.onCreate() makes first; needed before BuildVars is touched.
        applicationLoaderInstance = this;
        applicationContext = getApplicationContext();

        applyIdentity();
        SecuredUi.install(this, EngineBootstrap::create);
        SecuredBackground.install(this, SecuredUi::engine, new NotificationConfig(
                R.drawable.notification, Color.parseColor("#4A4FD6"), this::openChatIntent));
        try {
            if (SecuredBackground.isEnabled(this)) SecuredBackground.start(this);
        } catch (Throwable ignored) {
            // Android may refuse a foreground start from the background; the next launch or boot retries.
        }

        super.onCreate();
        installDiagnostics();

        if (BuildConfig.DEBUG) {
            // A13: log slow or leaky work in debug builds only.
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().penaltyLog().build());
        }
    }

    /**
     * A5: opt-in crash lines. Installed after Telegram's own setup so we wrap whatever handler is there. It only
     * writes (class names and our own frames, never messages) when the tester turned Diagnostics on, and always
     * hands the crash on to the previous handler.
     */
    private void installDiagnostics() {
        try {
            SecuredDiagnostics.INSTANCE.install(this);
        } catch (Throwable ignored) {
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final android.content.Context app = this;
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (SecuredDiagnostics.INSTANCE.isEnabled(app)) SecuredDiagnostics.INSTANCE.recordCrash(app, throwable);
            } catch (Throwable ignored) {
                // Never let diagnostics hide the real crash.
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    /** Tap intent of a Secured notification: only the local chat id, no text. Same CLEAR_TOP style as Telegram's own. */
    private Intent openChatIntent(Long chatId) {
        final Intent intent = new Intent(this, LaunchActivity.class);
        intent.setAction("app.encrogram.secured.OPEN_CHAT");
        if (chatId != null) intent.putExtra("secured_chat_id", chatId.longValue());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private static void applyIdentity() {
        // Our own Telegram API id/hash, when the owner has put them in encrogram/local.properties.
        // Otherwise upstream's sample values stay (local testing only; never ship that).
        if (BuildConfig.TELEGRAM_API_ID > 0 && !BuildConfig.TELEGRAM_API_HASH.isEmpty()) {
            BuildVars.APP_ID = BuildConfig.TELEGRAM_API_ID;
            BuildVars.APP_HASH = BuildConfig.TELEGRAM_API_HASH;
        }
        // These only work for upstream's official package names.
        BuildVars.SUPPORTS_PASSKEYS = false;
        BuildVars.IS_BILLING_UNAVAILABLE = true;
        // No Play Billing connection at start (L11): Telegram's startConnection() binds the Play Store's billing service
        // and its library logs every result to Google (firebaselogging.googleapis.com, ~40 s after launch). Our package
        // has no Play products anyway; this makes useInvoiceBilling() true, so startConnection() skips the bind.
        BillingController.billingClientEmpty = true;
        // Debug builds of Telegram show a "Test Backend" box on login and write request logs to disk; Encrogram wants neither.
        BuildVars.DEBUG_VERSION = false;
        BuildVars.LOGS_ENABLED = false;
        // Also turns off Telegram's ?enablelogs / ?sendlogs deep links, which debug builds otherwise keep.
        BuildVars.DEBUG_PRIVATE_VERSION = false;
    }
}
