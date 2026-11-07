package app.encrogram.android;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

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

        super.onCreate();
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
    }
}
