package app.encrogram.core.internal

import app.encrogram.core.BuildConfig

/**
 * Main thread: listener and callback calls, each on its own (A10). A screen that throws neither crashes the app nor keeps
 * the listeners after it (the service's notifier) from hearing the event. Debug builds rethrow, so the bug stays loud.
 */
internal object SafeDelivery {

    /** Tests switch it to see the release behaviour. */
    @Volatile
    var rethrow: Boolean = BuildConfig.DEBUG

    fun one(call: () -> Unit) {
        try {
            call()
        } catch (t: Throwable) {
            // Out of memory or a stack overflow: the process is in trouble, let it crash.
            if (rethrow || t is VirtualMachineError) throw t
            SecuredLog.crash(SecuredLog.Ev.LISTENER_FAILED, t)
        }
    }

    fun <L> each(listeners: Iterable<L>, call: (L) -> Unit) {
        for (l in listeners) one { call(l) }
    }
}
