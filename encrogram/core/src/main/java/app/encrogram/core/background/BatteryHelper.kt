package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Battery checks (SimpleX's SimplexService.kt, same calls). Java callers use the static methods. */
object BatteryHelper {

    @JvmStatic
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** The user restricted background use in system settings (API 28+); false below. */
    @JvmStatic
    fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

    /** Opens the system's one-tap "let this app ignore battery optimisation" request. */
    @SuppressLint("BatteryLife")
    @JvmStatic
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
