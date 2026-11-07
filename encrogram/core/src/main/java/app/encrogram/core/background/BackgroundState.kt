package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.content.Context

/** The one flag the background service keeps, in its own preferences file (not Telegram's). No message data. */
internal object BackgroundState {

    private const val FILE = "encrogram_secured_bg"
    private const val KEY_ENABLED = "background_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
