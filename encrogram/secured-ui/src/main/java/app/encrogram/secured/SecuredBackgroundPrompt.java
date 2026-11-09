package app.encrogram.secured;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.background.BatteryHelper;
import app.encrogram.core.background.SecuredBackground;

/**
 * Background receiving, first use. Shared by the Chats host and the standalone Secured list: once the engine is
 * RUNNING with a profile, start the service and, once ever, ask for notifications and battery.
 */
final class SecuredBackgroundPrompt {

    private static final int REQUEST_POST_NOTIFICATIONS = 7301;   // Telegram's codes: 2..22, 104, 150..211
    private static final String KEY_BG_ASKED = "background_asked";
    private static final String KEY_NOTIF_ASKED = "notifications_asked";

    private final BaseFragment fragment;
    private SecuredEngine bgEngine;
    private SecuredListener bgListener;

    SecuredBackgroundPrompt(BaseFragment fragment) {
        this.fragment = fragment;
    }

    /**
     * Called whenever the Secured list comes on screen (host or standalone). Clears the combined Secured notification,
     * then waits (through a listener, which replays the state at once) for the engine to be RUNNING with a profile.
     */
    void arm() {
        SecuredBackground.clearAll();
        if (bgListener != null) {
            return;
        }
        try {
            final SecuredEngine engine = SecuredUi.engine();
            if (engine == null) {
                return;
            }
            bgEngine = engine;
            bgListener = new SecuredListener() {
                @Override
                public void onStateChanged(EngineState state) {
                    if (state == EngineState.RUNNING && engine.profile() != null) {
                        // Not inside the engine's own callback (it replays the state while addListener runs).
                        AndroidUtilities.runOnUIThread(() -> {
                            if (bgListener != null) {
                                stop();
                                setUp();
                            }
                        });
                    }
                }
            };
            engine.addListener(bgListener);
        } catch (Throwable t) {
            stop();
        }
    }

    void stop() {
        try {
            if (bgEngine != null && bgListener != null) {
                bgEngine.removeListener(bgListener);
            }
        } catch (Throwable ignored) {
        }
        bgEngine = null;
        bgListener = null;
    }

    /** Starts the service (every time, it is idempotent) and, once ever, asks for notifications and battery. */
    private void setUp() {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            SecuredBackground.start(activity);
            final SharedPreferences prefs = activity.getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_BG_ASKED, false)) {
                // Older installs, or a prompt dismissed without a button: still ask for notifications, once.
                requestNotificationPermission(activity);
                return;
            }
            prefs.edit().putBoolean(KEY_BG_ASKED, true).apply();
            if (BatteryHelper.isIgnoringBatteryOptimizations(activity)) {
                requestNotificationPermission(activity);
                return;
            }
            final AlertDialog.Builder b = new AlertDialog.Builder(activity, SecuredResourcesProvider.get());
            b.setTitle(activity.getString(R.string.sec_bg_title));
            b.setMessage(activity.getString(R.string.sec_bg_text));
            b.setPositiveButton(activity.getString(R.string.sec_bg_allow), (d, w) -> {
                // The notification request first; two system screens at once swallow one of them.
                final boolean asked = requestNotificationPermission(activity);
                final Runnable battery = () -> {
                    try {
                        activity.startActivity(BatteryHelper.requestIgnoreIntent(activity));
                    } catch (Throwable ignored) {
                    }
                };
                if (asked) {
                    AndroidUtilities.runOnUIThread(battery, 1200);
                } else {
                    battery.run();
                }
            });
            b.setNegativeButton(activity.getString(R.string.sec_bg_not_now), null);
            // Any way out of the dialog (button, tap outside, back) leads to the notification request, once.
            b.setOnDismissListener(d -> requestNotificationPermission(activity));
            Kit.show(fragment, b.create());
        } catch (Throwable ignored) {
        }
    }

    /** Android's own request, at most once ever (no nagging after a refusal). Returns true when the system dialog was started. */
    private static boolean requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 33
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        final SharedPreferences prefs = activity.getSharedPreferences(SecuredUi.UI_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) {
            return false;
        }
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        return true;
    }
}
