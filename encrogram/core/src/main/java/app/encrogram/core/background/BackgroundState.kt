package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.content.Context
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the background service keeps, as small files in the engine's no-backup folder (not SharedPreferences:
 * Telegram's manifest has allowBackup=true, and a restored "on" flag would start the service and load SimpleX on a
 * phone where Secured was never opened). No message data.
 */
internal object BackgroundState {

    private fun file(context: Context, name: String) = File(context.noBackupFilesDir, "encrogram-secured/$name")

    /**
     * The flag as this process last read or wrote it; null until then (H11: setEnabled runs on the main thread, on
     * every Secured screen). The file changes only through this object and "Reset Secured", which deletes the whole
     * folder and then calls [forget].
     */
    @Volatile private var enabled: Boolean? = null

    fun isEnabled(context: Context): Boolean {
        enabled?.let { return it }
        return synchronized(this) {
            enabled ?: try {
                file(context, "background_on").isFile.also { enabled = it }
            } catch (t: Throwable) {
                false
            }
        }
    }

    /** Touches the disk only when [value] differs from what this process last knew. */
    fun setEnabled(context: Context, value: Boolean) {
        if (enabled == value) return
        synchronized(this) {
            if (enabled == value) return
            try {
                val f = file(context, "background_on")
                if (value) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                } else {
                    f.delete()
                }
                enabled = value
            } catch (t: Throwable) {
                enabled = null
                SecuredLog.crash(SecuredLog.Ev.BG_START_REFUSED, t)
            }
        }
    }

    /** "Reset Secured" deleted the folder with the flag in it: the next call reads or writes the disk again. */
    fun forget() {
        synchronized(this) { enabled = null }
    }

    @Volatile private var token: String? = null

    /**
     * A random per-install token our notification tap intents carry. LaunchActivity is exported, so without it any
     * app could put a Secured chat on screen by sending our action with a guessed chat id.
     */
    @Synchronized
    fun openToken(context: Context): String {
        token?.let { return it }
        val f = file(context, "open_token")
        val t = try {
            if (f.isFile) f.readText().trim().takeIf { it.length == 32 } else null
        } catch (_: Throwable) {
            null
        } ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also {
            try {
                f.parentFile?.mkdirs()
                f.writeText(it)
            } catch (e: Throwable) {
                SecuredLog.crash(SecuredLog.Ev.BG_START_REFUSED, e)
            }
        }
        token = t
        return t
    }

    fun isOpenToken(context: Context, candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), openToken(context).toByteArray())
}
