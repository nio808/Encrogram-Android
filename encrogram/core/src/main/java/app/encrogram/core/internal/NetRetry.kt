package app.encrogram.core.internal

/**
 * Network-bound commands (connect, link creation, link deletion) tried again after a relay timeout or network error,
 * like the official app's retry (SimpleXAPI.kt sendCmdWithRetry and retryableNetworkErrorAlert, :752-839), without its
 * dialog. The retry runs with the core's retry number 1, which lengthens its timeouts 1.5x (simplexmq Client.hs
 * netTimeoutInt). Sends are asynchronous in the core and never come here.
 *
 * One retry only (review of 4bce1db8, #1): the whole chain holds the user waiting. It runs on the engine's network
 * thread (A6), so the engine thread stays free meanwhile. [run]'s `beforeRetry` lets the engine read the network now and
 * reconnect first, so the retry doesn't go out on the connection that just timed out.
 */
internal object NetRetry {

    const val MAX_RETRIES = 1

    /**
     * BROKER TIMEOUT or NETWORK: the relay didn't answer in time or couldn't be reached. Anything else is final, and so
     * is a NETWORK error that is a certificate mismatch (unknownCAError, NetworkError in simplexmq Protocol.hs:1691-1697):
     * the relay isn't the one its address names, which a retry can't fix (the official app doesn't retry it either,
     * SimpleXAPI.kt:809). Fail closed.
     */
    fun retryable(e: CoreError): Boolean =
        e.type == "errorAgent" && e.detail == "BROKER" &&
            (e.subDetail == "TIMEOUT" || (e.subDetail == "NETWORK" && e.networkError != UNKNOWN_CA))

    /**
     * [attempt] with retry number 0, then once more with 1 while it fails [retryable] and [beforeRetry] allows it (the
     * phone has a usable network; the engine reconnects there first).
     */
    fun run(beforeRetry: () -> Boolean, attempt: (retryNum: Int) -> CoreReply): CoreReply {
        var retryNum = 0
        while (true) {
            val r = attempt(retryNum)
            if (retryNum == MAX_RETRIES || r !is CoreReply.Error || !retryable(r.error) || !beforeRetry()) return r
            retryNum++
            SecuredLog.w(SecuredLog.Ev.CMD_RETRY, r.error.tag, retryNum)
        }
    }

    /** NetworkError NEUnknownCAError as JSON (sumTypeJSON, dropPrefix "NE"). */
    const val UNKNOWN_CA = "unknownCAError"
}
