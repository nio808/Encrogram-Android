package app.encrogram.core.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import app.encrogram.core.internal.SecuredLog.Ev

/**
 * The phone's default network. [type]: none (no default network at all), cellular, wifi, ethernet or other.
 * [internet]: it offers internet and is not a captive portal; Android's "validated" is NOT required, because its
 * check can be blocked (censored countries, some office Wi-Fi) on a network that works. [handle]: which network,
 * so a switch to another network is a change even when the type stays the same.
 *
 * Only [coreOnline] reaches the core (UserNetworkInfo, simplexmq Agent/Client.hs:495-507): the core is told offline
 * only when the phone has no network at all, so it can never be held back on a working one. [usable] is for screens.
 */
internal data class NetInfo(val type: String, val internet: Boolean, val handle: Long = 0L) {
    val coreOnline: Boolean get() = type != TYPE_NONE
    val usable: Boolean get() = coreOnline && internet

    companion object {
        const val TYPE_NONE = "none"
        val NONE = NetInfo(TYPE_NONE, false)

        /** No ConnectivityManager, no permission or no callback: the agent's own default (Client.hs:529), never offline. */
        val UNKNOWN = NetInfo("other", true)

        fun of(internet: Boolean, captivePortal: Boolean, ethernet: Boolean, wifi: Boolean, cellular: Boolean, handle: Long = 0L) = NetInfo(
            when {
                ethernet -> "ethernet"
                wifi -> "wifi"
                cellular -> "cellular"
                else -> "other"
            },
            internet && !captivePortal,
            handle,
        )
    }
}

/**
 * Reports each real change once. Anything not [NetInfo.usable] is held back [OFFLINE_DELAY_MS], so a hand-over
 * (Wi-Fi off, mobile data taking over) is not an outage; a usable network is reported at once (the official app's
 * NetworkObserver.setNetworkInfo does the same). Not thread-safe: one thread only.
 */
internal class NetworkDebounce(
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val report: (NetInfo) -> Unit,
) {
    private var reported: NetInfo? = null
    private var pending: Runnable? = null

    fun update(info: NetInfo) {
        pending?.let(cancel)
        pending = null
        if (info == reported) return
        if (info.usable) {
            reported = info
            report(info)
            return
        }
        val task = Runnable {
            pending = null
            reported = info
            report(info)
        }
        pending = task
        schedule(OFFLINE_DELAY_MS, task)
    }

    /** Forgets what was reported, so the next [update] is reported whatever it is. */
    fun reset() {
        pending?.let(cancel)
        pending = null
        reported = null
    }

    companion object {
        const val OFFLINE_DELAY_MS = 3_000L
    }
}

/**
 * Watches the default network (ConnectivityManager, ACCESS_NETWORK_STATE is in the app's manifest) and hands each
 * change to [report] on [handler]'s thread. Never throws and never gets stuck offline: without a ConnectivityManager,
 * the permission or a registered callback it reports [NetInfo.UNKNOWN]; while it has reported no network it asks
 * Android again every [RECHECK_MS]. Network values only; nothing about Secured data.
 */
internal class NetworkWatch(
    private val context: Context,
    private val handler: Handler,
    report: (NetInfo) -> Unit,
) {
    private val debounce = NetworkDebounce({ delay, task -> handler.postDelayed(task, delay) }, { handler.removeCallbacks(it) }) { info ->
        report(info)
        handler.removeCallbacks(recheck)
        if (!info.coreOnline) handler.postDelayed(recheck, RECHECK_MS)
    }

    /** Safety net while no network is reported: a missed callback must not leave the core waiting. */
    private val recheck: Runnable = Runnable {
        val now = currentInfo(synchronized(this) { manager } ?: return@Runnable)
        debounce.update(now)
        if (!now.coreOnline) handler.postDelayed(recheck, RECHECK_MS)
    }

    // guarded by this
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** [handler] thread: the default network the last capabilities were for. */
    private var current: Network? = null

    /** Registers the callback and reports the network as it is now. Safe to call again while registered. */
    @Synchronized
    fun start() {
        if (callback != null) return
        val cm = try {
            context.getSystemService(ConnectivityManager::class.java)
        } catch (t: Throwable) {
            null
        }
        if (cm == null) {
            SecuredLog.w(Ev.NETWORK, "no_manager")
            restart(NetInfo.UNKNOWN)
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                current = network
                debounce.update(infoOf(network, caps))
            }

            override fun onLost(network: Network) {
                // A late loss of the previous default network after the switch to a new one is not an outage.
                if (current != null && network != current) return
                current = null
                debounce.update(NetInfo.NONE)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb, handler)
            manager = cm
            callback = cb
            restart(currentInfo(cm))
        } catch (t: Throwable) {
            // SecurityException without the permission, or the per-app callback limit: never report offline then.
            SecuredLog.crash(Ev.NETWORK, t)
            restart(NetInfo.UNKNOWN)
        }
    }

    @Synchronized
    fun stop() {
        val cm = manager
        val cb = callback
        manager = null
        callback = null
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.NETWORK, t)
            }
        }
        handler.post {
            handler.removeCallbacks(recheck)
            current = null
            debounce.reset()
        }
    }

    /**
     * Any thread: the default network as Android sees it right now, not debounced; null while the watch isn't
     * registered. For a caller that can't wait for the next report on [handler]'s thread (NetRetry's one retry).
     */
    fun now(): NetInfo? {
        val cm = synchronized(this) { manager } ?: return null
        return currentInfo(cm)
    }

    /** Starts over on [handler]'s thread with [info] as the state now. */
    private fun restart(info: NetInfo) {
        handler.post {
            current = null
            debounce.reset()
            debounce.update(info)
        }
    }

    private fun currentInfo(cm: ConnectivityManager): NetInfo = try {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (network != null && caps != null) infoOf(network, caps) else NetInfo.NONE
    } catch (t: Throwable) {
        SecuredLog.crash(Ev.NETWORK, t)
        NetInfo.UNKNOWN
    }

    private fun infoOf(network: Network, c: NetworkCapabilities) = NetInfo.of(
        internet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        captivePortal = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        ethernet = c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        cellular = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        handle = network.networkHandle,
    )

    companion object {
        const val RECHECK_MS = 30_000L
    }
}
