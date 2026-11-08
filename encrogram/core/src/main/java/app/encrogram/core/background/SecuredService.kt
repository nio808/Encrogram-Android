package app.encrogram.core.background

// Portions adapted from SimpleX Chat, apps/multiplatform/android/.../SimplexService.kt, Copyright SimpleX Chat Ltd,
// licensed under AGPL-3.0; modifications by Encrogram. SimplexService itself is based on the robertohuertas.com
// foreground-service tutorial and ntfy-android SubscriberService.kt (Apache-2.0).

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.encrogram.core.api.GroupEvent
import app.encrogram.core.api.SecuredEngine
import app.encrogram.core.api.SecuredListener
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.internal.SecuredLog

/**
 * Keeps the process at foreground priority so the engine's relay connections stay open (the same idea as
 * SimpleX's service), and turns incoming messages into notifications. It starts the engine (idempotent) but never
 * stops it: the UI may be using it.
 */
class SecuredService : Service() {

    private var engine: SecuredEngine? = null
    private var listener: SecuredListener? = null
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startForegroundNow()) return
        val e = SecuredBackground.engineProvider()
        if (e == null) {
            safeStop()
            return
        }
        val l = object : SecuredListener {
            override fun onMessageReceived(message: SecuredMessage) = onIncoming(message, e)
        }
        engine = e
        listener = l
        try {
            e.start()
            e.addListener(l)
            SecuredLog.i(SecuredLog.Ev.BG_SERVICE_STARTED)
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.START_FAILED, t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started service must call startForeground again on every start, or Android may kill the app.
        if (stopped || !startForegroundNow()) return START_NOT_STICKY
        return START_STICKY
    }

    override fun onDestroy() {
        // The engine keeps running: only its listener goes.
        listener?.let { l -> try { engine?.removeListener(l) } catch (_: Throwable) {} }
        listener = null
        super.onDestroy()
    }

    private fun onIncoming(message: SecuredMessage, e: SecuredEngine) {
        if (message.outgoing) return
        // Messages, in 1:1 chats and groups alike. Of the service rows only an invitation to a group notifies;
        // "X joined" and the like don't. Same hidden text either way (NotificationText).
        val event = message.event
        if (event != null && event.kind != GroupEvent.Kind.INVITED) return
        val config = SecuredBackground.config ?: return
        if (SecuredBackground.isChatOnScreen(message.chatId)) return
        if (e.chats().firstOrNull { it.chatId == message.chatId }?.muted == true) return
        SecuredNotifier.onIncoming(applicationContext, config, message.chatId)
    }

    /** Returns false (and stops the service) when Android refuses a foreground start. */
    private fun startForegroundNow(): Boolean {
        val config = SecuredBackground.config
        if (config == null) {
            safeStop()
            return false
        }
        return try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            ServiceCompat.startForeground(this, NotificationText.SERVICE_ID, SecuredNotifier.serviceNotification(this, config), type)
            true
        } catch (t: Throwable) {   // ForegroundServiceStartNotAllowedException and SecurityException
            SecuredLog.crash(SecuredLog.Ev.BG_START_REFUSED, t)
            safeStop()
            false
        }
    }

    private fun safeStop() {
        if (stopped) return
        stopped = true
        stopSelf()
    }
}
