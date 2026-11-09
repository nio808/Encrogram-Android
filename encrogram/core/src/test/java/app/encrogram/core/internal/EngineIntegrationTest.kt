package app.encrogram.core.internal

import app.encrogram.core.api.EngineState
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.internal.FakeCore.Companion.CLOSE
import app.encrogram.core.internal.FakeCore.Companion.chatsReply
import app.encrogram.core.internal.FakeCore.Companion.contact
import app.encrogram.core.internal.FakeCore.Companion.newItems
import app.encrogram.core.internal.FakeCore.Companion.rcv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A11: the engine itself, constructed with a scripted core ([EngineHarness]): start, the fail-closed configuration
 * steps, event dispatch, the A4 unread race through the real queue, and stop ordering.
 */
class EngineIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var h: EngineHarness
    private val logLines = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { logLines.add(it) }
        h = EngineHarness(tmp.newFolder("nobackup"))
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
        h.close()
    }

    private val core get() = h.core

    @Test
    fun startReachesRunningOnlyAfterTheConfigurationIsReadBack() {
        h.startRunning()

        assertEquals(listOf(EngineState.STARTING, EngineState.RUNNING), h.listener.states.filter { it != EngineState.NOT_STARTED })
        assertEquals(listOf(5L), h.engine.chats().map { it.chatId })
        // Fail closed: network set and read back, operators off, chat started, our servers set and read back, then the list.
        val order = listOf("/_network {", "/network", "/_operators [", "/_start", "/_servers 1 [", "/_get chats 1").map { cmd ->
            if (cmd == "/network") core.calls.indexOfFirst { it.cmd == "/network" } else core.indexOf(cmd)
        }
        assertTrue("steps out of order: $order", order.all { it >= 0 } && order == order.sorted())
        val readBack = core.calls.indexOfLast { it.cmd == "/_servers 1" }
        assertTrue(readBack > core.indexOf("/_servers 1 [") && readBack < core.indexOf("/_get chats 1"))
        // Every core command on the engine thread, every listener call on the main thread.
        assertEquals(setOf("test-engine"), core.calls.map { it.thread }.toSet())
        assertEquals(setOf("test-main"), h.listener.threads.toSet())
        assertTrue(core.closedCalls.isEmpty())
    }

    @Test
    fun aServerListThatDoesNotReadBackStopsTheChatAndFails() {
        core.exact("/_servers 1") {
            if (core.serversSet) Samples.userServersConfigured(extraCustomSmp = "smp://abcd@smp.other.example:5223") else Samples.USER_SERVERS_FRESH
        }
        h.engine.addListener(h.listener)
        h.engine.start()
        h.awaitIdle()

        assertEquals(EngineState.FAILED, h.engine.state())
        val error = h.listener.errors.single()
        assertEquals(SecuredError.Kind.ENGINE, error.kind)
        assertTrue(error.message, error.message.contains("only Encrogram's servers"))
        assertEquals(error, h.engine.failure())
        // The chat was started, so it is stopped again; nothing was loaded or shown.
        assertTrue(core.indexOf("/_stop") > core.indexOf("/_start"))
        assertEquals(-1, core.indexOf("/_get chats"))
        assertTrue(h.engine.chats().isEmpty())
        assertFalse(h.listener.log.contains("state:RUNNING"))
    }

    @Test
    fun aNetworkConfigThatDoesNotReadBackFailsBeforeTheChatStarts() {
        // The core keeps a direct fallback for unknown relays: never acceptable.
        core.exact(Commands.GET_NETWORK_CONFIG, Samples.NETWORK_CONFIG_DEFAULT)
        h.engine.addListener(h.listener)
        h.engine.start()
        h.awaitIdle()

        assertEquals(EngineState.FAILED, h.engine.state())
        assertTrue(h.listener.errors.single().message.contains("(network_verify)"))
        assertEquals("the chat never starts", -1, core.indexOf("/_start"))
        assertEquals(-1, core.indexOf("/_operators"))
        assertEquals(-1, core.indexOf("/_servers"))
    }

    @Test
    fun operatorsThatStayEnabledFail() {
        core.prefix("/_operators [", Samples.OPERATORS)
        h.engine.addListener(h.listener)
        h.engine.start()
        h.awaitIdle()

        assertEquals(EngineState.FAILED, h.engine.state())
        assertTrue(h.listener.errors.single().message.contains("(operators_verify)"))
        assertEquals(-1, core.indexOf("/_start"))
    }

    @Test
    fun aFailedStartCanBeStartedAgainOnTheSameStore() {
        core.exact(Commands.GET_NETWORK_CONFIG, Samples.NETWORK_CONFIG_DEFAULT)
        h.engine.addListener(h.listener)
        h.engine.start()
        h.awaitIdle()
        assertEquals(EngineState.FAILED, h.engine.state())

        core.exact(Commands.GET_NETWORK_CONFIG) { core.networkConfig }
        h.engine.start()
        h.awaitIdle()
        assertEquals(EngineState.RUNNING, h.engine.state())
        assertEquals("one store, opened once", 1, core.sent("/set file paths").size)
    }

    @Test
    fun anEventBurstReachesListenersOnceEachInOrder() {
        h.startRunning()
        val ids = (100L until 130L).toList()
        // One event per message, plus one event carrying three at once.
        for (id in ids) core.push(newItems(5, id))
        core.push(newItems(5, 130, 131, 132))
        h.awaitIdle()

        val all = ids + listOf(130L, 131L, 132L)
        assertEquals(all, h.listener.received.map { it.messageId })
        assertTrue(h.listener.received.all { it.chatId == 5L && !it.outgoing })
        val bob = h.engine.chats().single { it.chatId == 5L }
        assertEquals(all.size, bob.unreadCount)
        assertEquals("m132", bob.lastMessageText)
        // Never message text in the log.
        assertTrue(logLines.none { line -> all.any { line.contains("m$it") } })
    }

    @Test
    fun queuedEventsAfterAReloadAreNotCountedTwice() {
        h.startRunning()
        // A reload starts (a contact event) and is held inside the core, while items 41 and 42 arrive behind it.
        val reload = core.hold("/_get chats 1")
        core.push(FakeCore.result("contactUpdated"))
        reload.awaitEntered()
        core.push(newItems(5, 41, 42))
        h.waitFor("events queued behind the reload") { core.eventsPending == 0 }
        // The core already counts both when the reload reads the list.
        core.chats = chatsReply(contact(5, "Bob", unread = 2, last = rcv(42)))
        reload.release()
        h.awaitIdle()

        assertEquals(2, h.engine.chats().single { it.chatId == 5L }.unreadCount)
        assertEquals(listOf(41L, 42L), h.listener.received.map { it.messageId })
        // A later message still counts.
        core.push(newItems(5, 43))
        h.awaitIdle()
        assertEquals(3, h.engine.chats().single { it.chatId == 5L }.unreadCount)
    }

    @Test
    fun nothingReachesListenersOrTheCoreAfterStop() {
        h.startRunning()
        val ctrl = core.calls.first().ctrl
        // The engine thread is busy inside the core; a stop and a send queue behind it, then events arrive.
        val busy = core.hold("/_read chat @5")
        h.engine.markRead(5)
        busy.awaitEntered()
        h.engine.stop()
        val send = Answer<SecuredMessage>()
        h.engine.sendText(5, "after stop", send)
        core.push(newItems(5, 60), newItems(5, 61))
        h.waitFor("events handed to the engine") { core.eventsPending == 0 }
        busy.release()
        h.awaitIdle()

        assertEquals(EngineState.NOT_STARTED, h.engine.state())
        // Only the stop's own list change (the list is now empty).
        assertEquals("listeners heard nothing after the stop", listOf("chats"), h.listener.afterStop())
        assertTrue(h.listener.received.isEmpty())
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, send.error().kind)
        // The chat was stopped before the store closed, and nothing went to the closed controller.
        assertTrue(core.indexOf("/_stop") in 0 until core.indexOf(CLOSE))
        assertEquals(listOf(ctrl), core.closed)
        assertTrue(core.closedCalls.isEmpty())
        assertTrue(h.engine.chats().isEmpty())
    }
}
