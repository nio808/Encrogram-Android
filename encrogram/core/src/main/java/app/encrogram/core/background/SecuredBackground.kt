package app.encrogram.core.background

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.encrogram.core.api.SecuredEngine
import app.encrogram.core.internal.SecuredLog

/**
 * What the app supplies so core never names a Telegram class: the notification's small icon, its accent colour,
 * and how to build the tap intent ([openIntent] gets the local chat id, or null for "just open the app").
 * The intent must carry nothing but that id.
 */
class NotificationConfig(
    val smallIconRes: Int,
    val accentColor: Int,
    val openIntent: (Long?) -> Intent,
)

/** Entry point of background receiving. One call from Application.onCreate, one from the UI when Secured is first used. */
object SecuredBackground {

    /** Extra on our notification tap intents; [isOwnTapIntent] checks it. */
    const val EXTRA_OPEN_TOKEN = "secured_open_token"

    /** True only for intents built by our own notifications (they carry this install's random token). */
    @JvmStatic
    fun isOwnTapIntent(context: Context, intent: Intent?): Boolean =
        intent != null && BackgroundState.isOpenToken(context, intent.getStringExtra(EXTRA_OPEN_TOKEN))

    internal var engineProvider: () -> SecuredEngine? = { null }
        private set
    internal var config: NotificationConfig? = null
        private set

    private var appContext: Context? = null
    @Volatile private var openChatId: Long? = null
    @Volatile private var resumedActivities = 0

    @JvmStatic
    fun install(app: Application, engine: () -> SecuredEngine?, config: NotificationConfig) {
        appContext = app
        engineProvider = engine
        this.config = config
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { if (resumedActivities > 0) resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = BackgroundState.isEnabled(context)

    /**
     * Saves "background on" and starts the foreground service. Safe to call repeatedly. When Android refuses (app
     * in the background; ForegroundServiceStartNotAllowedException is an IllegalStateException) the flag stays on
     * and the next boot, update or app launch tries again.
     */
    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        BackgroundState.setEnabled(app, true)
        try {
            ContextCompat.startForegroundService(app, Intent(app, SecuredService::class.java))
        } catch (t: IllegalStateException) {
            SecuredLog.w(SecuredLog.Ev.BG_START_REFUSED, t.javaClass.simpleName)
        }
    }

    /** The Secured chat on screen right now (null for none). Its notifications are skipped while the app is resumed. */
    @JvmStatic
    fun setOpenChat(chatId: Long?) {
        openChatId = chatId
    }

    /** Takes that chat out of the combined notification (call when the user has seen the chat). */
    @JvmStatic
    fun clearChat(chatId: Long) {
        SecuredNotifier.clear(appContext ?: return, config, chatId)
    }

    /** Cancels the combined notification (call when the Secured chat list comes on screen). */
    @JvmStatic
    fun clearAll() {
        SecuredNotifier.clearAll(appContext ?: return)
    }

    internal fun isChatOnScreen(chatId: Long): Boolean = resumedActivities > 0 && openChatId == chatId
}
