package app.encrogram.secured;

import android.content.Context;
import android.content.SharedPreferences;

/** Two UI flags for the backup entry points, in Secured's own UI preferences (booleans only, no Secured data). */
final class SecuredBackupUi {

    private static final String KEY_OFFERED = "backup_offered";
    private static final String KEY_BANNER_DISMISSED = "backup_banner_dismissed";

    private SecuredBackupUi() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE);
    }

    /** True once the "back up now?" offer after profile creation was shown or the backup was turned on. */
    static boolean wasOffered(Context c) {
        try {
            return prefs(c).getBoolean(KEY_OFFERED, false);
        } catch (Throwable t) {
            return true;   // when unsure, don't nag
        }
    }

    /** After "Reset Secured": the new profile is offered the backup again, like a first one. */
    static void forget(Context c) {
        try {
            prefs(c).edit().remove(KEY_OFFERED).remove(KEY_BANNER_DISMISSED).apply();
        } catch (Throwable ignored) {
        }
    }

    static void markOffered(Context c) {
        try {
            prefs(c).edit().putBoolean(KEY_OFFERED, true).apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean bannerDismissed(Context c) {
        try {
            return prefs(c).getBoolean(KEY_BANNER_DISMISSED, false);
        } catch (Throwable t) {
            return true;
        }
    }

    static void dismissBanner(Context c) {
        try {
            prefs(c).edit().putBoolean(KEY_BANNER_DISMISSED, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Backup was turned off: the list's banner may offer it again. */
    static void showBannerAgain(Context c) {
        try {
            prefs(c).edit().putBoolean(KEY_BANNER_DISMISSED, false).apply();
        } catch (Throwable ignored) {
        }
    }
}
