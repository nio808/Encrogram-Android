package app.encrogram.core.internal

import app.encrogram.core.api.EngineState
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredInvite
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.internal.FakeCore.Companion.CLOSE
import app.encrogram.core.internal.FakeCore.Companion.chatsReply
import app.encrogram.core.internal.FakeCore.Companion.contact
import app.encrogram.core.internal.FakeCore.Companion.group
import app.encrogram.core.internal.FakeCore.Companion.groupInfo
import app.encrogram.core.internal.FakeCore.Companion.newItems
import app.encrogram.core.internal.FakeCore.Companion.rcv
import app.encrogram.core.internal.FakeCore.Companion.result
import app.encrogram.core.internal.FakeCore.Companion.storeError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A6: the commands that reach a relay before the core answers run their native call on the network thread; the
 * engine thread stays free, and a stop or a new store never lets an old command reach the core or the listeners.
 */
class EngineNetThreadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var h: EngineHarness
    private val core get() = h.core

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        h = EngineHarness(tmp.newFolder("nobackup"))
    }

    @After
    fun tearDown() = h.close()

    private fun netCalls(prefix: String) = core.sent(prefix).map { it.thread }.toSet()

    @Test
    fun anInviteWaitsOnTheNetworkThreadWhileTheEngineStaysFree() {
        h.startRunning()
        core.exact("/_connect 1", Samples.INVITATION)
        val slow = core.hold("/_connect 1")
        val invite = Answer<SecuredInvite>()
        h.engine.createInvite(invite)
        slow.awaitEntered()

        // While the relay is slow: a page loads, and a message arrives and is counted.
        val page = Answer<List<SecuredMessage>>()
        h.engine.messages(5, 20, page)
        core.push(newItems(5, 41))
        h.waitFor("a page while the invite waits") { page.results.isNotEmpty() }
        h.waitFor("a message while the invite waits") { h.listener.received.isNotEmpty() }
        assertEquals(1, h.engine.chats().single().unreadCount)
        assertTrue(invite.results.isEmpty())

        slow.release()
        h.awaitIdle()
        assertTrue(invite.value().link.startsWith("https://encrogram.nytmanager.com/i#"))
        assertEquals(setOf("test-net"), netCalls("/_connect 1"))
        assertTrue("everything else on the engine thread", core.calls.filter { it.cmd != "/_connect 1" }.all { it.thread == "test-engine" })
        assertEquals(setOf("test-main"), invite.threads.toSet())
    }

    @Test
    fun aRelayTimeoutIsRetriedOnceOnTheNetworkThread() {
        h.startRunning()
        core.exact("/_connect 1") { c -> if (c.retryNum == 0) Samples.AGENT_BROKER_TIMEOUT else Samples.INVITATION }
        val invite = Answer<SecuredInvite>()
        h.engine.createInvite(invite)
        h.awaitIdle()

        invite.value()
        assertEquals(listOf(0, 1), core.sent("/_connect 1").map { it.retryNum })
        assertEquals(setOf("test-net"), netCalls("/_connect 1"))
    }

    @Test
    fun connectAsksForThePlanThenConnectsAndReloadsOnTheEngineThread() {
        h.startRunning()
        core.prefix("/_connect plan 1 ", FakeCore.PLAN_OK)
        core.prefix("/connect ", result("sentConfirmation"))
        val done = Answer<Unit>()
        h.engine.connect(FakeCore.SHORT_INVITE, done)
        h.awaitIdle()

        done.value()
        assertEquals(setOf("test-net"), netCalls("/_connect plan"))
        assertEquals(setOf("test-net"), netCalls("/connect "))
        val reload = core.calls.indexOfLast { it.cmd == "/_get chats 1" }
        assertTrue(reload > core.indexOf("/connect "))
        assertEquals("test-engine", core.calls[reload].thread)
    }

    @Test
    fun groupCommandsRunTheirNativeCallOnTheNetworkThread() {
        core.chats = chatsReply(
            contact(5, "Bob", last = rcv(40)),
            group(groupInfo(id = 3)),
            group(groupInfo(id = 4, myRole = "member", myStatus = "invited")),
        )
        h.startRunning()
        core.prefix("/_add #3 ", result("sentGroupInvitation"))
        core.exact("/_delete link #3", result("groupLinkDeleted"))
        core.exact("/_join #4", result("userAcceptedGroupSent"))
        val added = Answer<Unit>()
        val deleted = Answer<Unit>()
        val joined = Answer<Unit>()
        h.engine.addMembers(CoreJson.groupChatId(3), listOf(5L), added)
        h.engine.deleteGroupLink(CoreJson.groupChatId(3), deleted)
        h.engine.joinGroup(CoreJson.groupChatId(4), joined)
        h.awaitIdle()

        added.value()
        deleted.value()
        joined.value()
        assertEquals(setOf("test-net"), netCalls("/_add #3 5 member"))
        assertEquals(setOf("test-net"), netCalls("/_delete link #3"))
        assertEquals(setOf("test-net"), netCalls("/_join #4"))
    }

    @Test
    fun aGroupLinkOutsideOurRelaysIsDeletedOnTheNetworkThreadAndNeverHandedOut() {
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(groupInfo(id = 3)))
        h.startRunning()
        core.exact("/_get link #3", storeError("groupLinkNotFound"))
        val elsewhere = FakeCore.FULL_INVITE.replace("encrogram.nytmanager.com", "smp11.simplex.im")
        core.exact("/_create link #3 member", result("groupLinkCreated",
            """"groupLink":{"connLinkContact":{"connFullLink":"$elsewhere"},"acceptMemberRole":"member"}"""))
        core.exact("/_delete link #3", result("groupLinkDeleted"))
        val link = Answer<SecuredInvite>()
        h.engine.groupLink(CoreJson.groupChatId(3), link)
        h.awaitIdle()

        assertEquals(SecuredError.Kind.UNAPPROVED_RELAY, link.error().kind)
        assertEquals("test-engine", core.sent("/_get link #3").single().thread)
        assertEquals(setOf("test-net"), netCalls("/_create link #3"))
        assertEquals(setOf("test-net"), netCalls("/_delete link #3"))
        assertTrue(core.indexOf("/_delete link #3") > core.indexOf("/_create link #3"))
    }

    @Test
    fun aStopWaitsForTheCallInFlightThenDropsItsAnswer() {
        h.startRunning()
        core.exact("/_connect 1", Samples.INVITATION)
        val slow = core.hold("/_connect 1")
        val invite = Answer<SecuredInvite>()
        h.engine.createInvite(invite)
        slow.awaitEntered()
        h.engine.stop()
        Thread.sleep(100)
        // The stop waits: the chat isn't stopped and the store isn't closed under the call.
        assertEquals(-1, core.indexOf("/_stop"))
        assertTrue(core.closed.isEmpty())
        assertEquals(EngineState.RUNNING, h.engine.state())

        slow.release()
        h.awaitIdle()
        assertEquals(EngineState.NOT_STARTED, h.engine.state())
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, invite.error().kind)
        assertTrue(core.indexOf("/_connect 1") < core.indexOf("/_stop"))
        assertTrue(core.indexOf("/_stop") < core.indexOf(CLOSE))
        assertTrue(core.closedCalls.isEmpty())
        assertEquals(listOf("chats"), h.listener.afterStop())
    }

    @Test
    fun aQueuedCommandNeverReachesAClosedStoreAndANewStoreWorks() {
        h.startRunning()
        core.exact("/_connect 1", Samples.INVITATION)
        val slow = core.hold("/_connect 1")
        val first = Answer<SecuredInvite>()
        val second = Answer<SecuredInvite>()
        h.engine.createInvite(first)
        slow.awaitEntered()
        h.engine.createInvite(second)
        h.waitFor("the second invite queued on the network thread") { h.netThread.pending.get() == 2 }
        h.engine.stop()
        slow.release()
        h.awaitIdle()

        assertEquals("only the first reached the core", 1, core.sent("/_connect 1").size)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, first.error().kind)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, second.error().kind)
        assertTrue(core.closedCalls.isEmpty())

        // Started again: a new store, and network commands work on it.
        h.engine.start()
        h.awaitIdle()
        assertEquals(EngineState.RUNNING, h.engine.state())
        val third = Answer<SecuredInvite>()
        h.engine.createInvite(third)
        h.awaitIdle()
        third.value()
        val ctrl = core.sent("/_connect 1").last().ctrl
        assertFalse(ctrl in core.closed)
        assertTrue(core.closedCalls.isEmpty())
    }

    @Test
    fun aCommandAfterStopIsRefusedWithoutReachingTheNetworkThread() {
        h.startRunning()
        h.engine.stop()
        h.awaitIdle()
        val invite = Answer<SecuredInvite>()
        h.engine.createInvite(invite)
        h.awaitIdle()
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, invite.error().kind)
        assertTrue(core.sent("/_connect").isEmpty())
        assertEquals(0L, h.netThread.completedTaskCount)
    }
}
