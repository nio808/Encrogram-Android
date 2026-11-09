package app.encrogram.core.internal

/**
 * Network-bound commands (connect, link creation, link deletion) tried again after a relay timeout or network error,
 * like the official app's retry (SimpleXAPI.kt sendCmdWithRetry and retryableNetworkErrorAlert, :752-839), without its
 * dialog. Retry n runs with the core's retry number n, which lengthens its timeouts 1.5x, then 2.25x (simplexmq
 * Client.hs netTimeoutInt). Sends are asynchronous in the core and never come here.
 */
internal object NetRetry {

    const val MAX_RETRIES = 2

    /** BROKER TIMEOUT or NETWORK: the relay didn't answer in time or couldn't be reached. Anything else is final. */
    fun retryable(e: CoreError): Boolean =
        e.type == "errorAgent" && e.detail == "BROKER" && (e.subDetail == "TIMEOUT" || e.subDetail == "NETWORK")

    /** [attempt] with retry numbers 0, 1, 2 while it fails [retryable] and the phone has a network ([online]). */
    fun run(online: () -> Boolean, attempt: (retryNum: Int) -> CoreReply): CoreReply {
        var retryNum = 0
        while (true) {
            val r = attempt(retryNum)
            if (retryNum == MAX_RETRIES || r !is CoreReply.Error || !retryable(r.error) || !online()) return r
            retryNum++
            SecuredLog.w(SecuredLog.Ev.CMD_RETRY, r.error.tag, retryNum)
        }
    }
}
