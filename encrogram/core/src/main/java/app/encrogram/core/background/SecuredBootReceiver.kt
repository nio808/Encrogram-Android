package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after boot or an app update, but only if the user had background receiving on. */
class SecuredBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (shouldStart(intent?.action, BackgroundState.isEnabled(context))) {
            SecuredBackground.start(context)
        }
    }

    internal companion object {
        /** Exported receiver: anything but the two system actions is ignored. */
        fun shouldStart(action: String?, enabled: Boolean): Boolean =
            enabled && (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
