package app.encrogram.core.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** B7: connect, link creation and link deletion retry a relay timeout or network error with longer core timeouts. */
class NetRetryTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun error(raw: String) = CoreJson.parseReply(raw) as CoreReply.Error

    private val timeout = error("""{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent","agentError":{"type":"BROKER","brokerAddress":"smp://x@h","brokerErr":{"type":"TIMEOUT"}}}}}""")
    private val network = error("""{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent","agentError":{"type":"BROKER","brokerAddress":"smp://x@h","brokerErr":{"type":"NETWORK","networkError":{"type":"connectError","connectError":"refused"}}}}}}""")
    private val host = error("""{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent","agentError":{"type":"BROKER","brokerAddress":"smp://x@h","brokerErr":{"type":"HOST"}}}}}""")
    private val auth = error("""{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent","agentError":{"type":"SMP","serverAddress":"smp://x@h","smpErr":{"type":"AUTH"}}}}}""")
    private val ok = CoreReply.Result("sentConfirmation", org.json.JSONObject())

    @Test
    fun onlyTimeoutsAndNetworkErrorsAreRetried() {
        assertTrue(NetRetry.retryable(timeout.error))
        assertTrue(NetRetry.retryable(network.error))
        assertFalse(NetRetry.retryable(host.error))
        assertFalse("a used link stays used", NetRetry.retryable(auth.error))
        assertFalse(NetRetry.retryable(CoreError("error", "chatNotStarted", null)))
    }

    @Test
    fun retriesTwiceWithGrowingRetryNumbers() {
        val tries = ArrayList<Int>()
        val r = NetRetry.run({ true }) { n -> tries.add(n); if (n < 2) timeout else ok }
        assertSame(ok, r)
        assertEquals(listOf(0, 1, 2), tries)

        tries.clear()
        assertSame(network, NetRetry.run({ true }) { n -> tries.add(n); network })
        assertEquals("never more than two retries", listOf(0, 1, 2), tries)
    }

    @Test
    fun finalErrorsAndNoNetworkAreNotRetried() {
        val tries = ArrayList<Int>()
        assertSame(auth, NetRetry.run({ true }) { n -> tries.add(n); auth })
        assertEquals(listOf(0), tries)

        tries.clear()
        assertSame(timeout, NetRetry.run({ false }) { n -> tries.add(n); timeout })
        assertEquals("offline: the core is waiting for the network anyway", listOf(0), tries)

        tries.clear()
        assertSame(ok, NetRetry.run({ true }) { n -> tries.add(n); ok })
        assertEquals(listOf(0), tries)
    }
}
