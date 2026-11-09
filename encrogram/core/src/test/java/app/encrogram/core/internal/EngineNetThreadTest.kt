package app.encrogram.core.internal

import app.encrogram.core.api.ChatAction
import app.encrogram.core.api.EngineState
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredInvite
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.FakeCore.Companion.CLOSE
import app.encrogram.core.internal.FakeCore.Companion.chatsReply
import app.encrogram.core.internal.FakeCore.Companion.contact
import app.encrogram.core.internal.FakeCore.Companion.groupChat
import app.encrogram.core.internal.FakeCore.Companion.group
import app.encrogram.core.internal.FakeCore.Companion.groupInfo
import app.encrogram.core.internal.FakeCore.Companion.newItems
import app.encrogram.core.internal.FakeCore.Companion.rcv
import app.encrogram.core.internal.FakeCore.Companion.result
import app.encrogram.core.internal.FakeCore.Companion.storeError
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

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
        // Read and created in one network-thread task (review of 8c89dfd6, finding 3).
        assertEquals("test-net", core.sent("/_get link #3").single().thread)
        assertEquals(setOf("test-net"), netCalls("/_create link #3"))
        assertEquals(setOf("test-net"), netCalls("/_delete link #3"))
        assertTrue(core.indexOf("/_delete link #3") > core.indexOf("/_create link #3"))
    }

    // ---- group links: read and create as one task (review of 8c89dfd6, finding 3) ---------------------------------

    private val fp = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"
    private val groupChatId = CoreJson.groupChatId(3)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A full group link on our relay (GroupLinksTest's shape), told apart by its [queue]. */
    private fun ourGroupLink(queue: String) =
        "simplex:/contact#/?v=2-7&smp=${enc("smp://$fp@encrogram.nytmanager.com:5223/$queue#/?v=1-4&dh=MCowBQYDK2VuAyEA")}" +
            "&data=${enc("""{"groupLinkId":"AAECAwQFBgcICQoLDA0ODw=="}""")}"

    private fun linkReply(type: String, link: String) =
        result(type, """"groupLink":{"connLinkContact":{"connFullLink":"$link"},"acceptMemberRole":"member"}""")

    /** `/_get link #3`, `/_create link #3 member` and `/_delete link #3` over one stored link, as the core keeps it. */
    private fun linkStore(initial: String?): AtomicReference<String?> {
        val stored = AtomicReference(initial)
        core.exact("/_get link #3") { stored.get()?.let { linkReply("groupLink", it) } ?: storeError("groupLinkNotFound") }
        core.exact("/_create link #3 member") {
            val made = ourGroupLink("q${core.sent("/_create link #3").size}q2q3q4")
            stored.set(made)
            linkReply("groupLinkCreated", made)
        }
        core.exact("/_delete link #3") {
            stored.set(null)
            result("groupLinkDeleted")
        }
        return stored
    }

    @Test
    fun twoGroupLinkCallsCloseTogetherMakeOneLink() {
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(groupInfo(id = 3)))
        h.startRunning()
        linkStore(null)
        val slow = core.hold("/_create link #3")
        val first = Answer<SecuredInvite>()
        val second = Answer<SecuredInvite>()
        h.engine.groupLink(groupChatId, first)
        slow.awaitEntered()
        h.engine.groupLink(groupChatId, second)
        h.waitFor("the second call queued behind the first") { h.netThread.pending.get() == 2 }
        assertEquals("nothing read while the first link is being made", 1, core.sent("/_get link #3").size)
        slow.release()
        h.awaitIdle()

        assertEquals("one queue on our relay", 1, core.sent("/_create link #3").size)
        assertTrue(first.value().link.contains("q1q2q3q4"))
        assertEquals(first.value().link, second.value().link)
        assertEquals(setOf("test-net"), netCalls("/_get link #3"))
        assertTrue(core.calls.indexOfLast { it.cmd == "/_get link #3" } > core.indexOf("/_create link #3"))
    }

    @Test
    fun aGroupLinkAskedForWhileADeleteIsUnderWayIsANewOne() {
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(groupInfo(id = 3)))
        h.startRunning()
        linkStore(ourGroupLink("old1old2"))
        val slow = core.hold("/_delete link #3")
        val deleted = Answer<Unit>()
        val link = Answer<SecuredInvite>()
        h.engine.deleteGroupLink(groupChatId, deleted)
        slow.awaitEntered()
        h.engine.groupLink(groupChatId, link)
        h.waitFor("the link call queued behind the delete") { h.netThread.pending.get() == 2 }
        assertTrue("the link being deleted is never read", core.sent("/_get link #3").isEmpty())
        slow.release()
        h.awaitIdle()

        deleted.value()
        assertFalse(link.value().link.contains("old1old2"))
        assertTrue(link.value().link.contains("q1q2q3q4"))
        assertTrue(core.indexOf("/_delete link #3") < core.indexOf("/_get link #3"))
        assertTrue(core.indexOf("/_get link #3") < core.indexOf("/_create link #3"))
    }

    // ---- group profile updates (review of 8c89dfd6, finding 1) --------------------------------------------------

    @Test
    fun groupProfileUpdatesWaitOnTheNetworkThreadOneAfterTheOther() {
        val info = groupInfo(id = 3)
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(info))
        core.exact("/_get chat #3 count=1", groupChat(info))
        h.startRunning()
        core.groupProfiles(3, info, result("groupUpdated"))
        val slow = core.hold("/_group_profile #3 ")
        val photo = Answer<Unit>()
        h.engine.setGroupPhoto(groupChatId, AvatarsTest.JPEG, photo)
        slow.awaitEntered()
        val timer = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(86400), timer)

        // While the relay is slow: a page loads, and the timer waits its turn.
        val page = Answer<List<SecuredMessage>>()
        h.engine.messages(5, 20, page)
        h.waitFor("a page while the photo waits") { page.results.isNotEmpty() }
        h.waitFor("the timer queued behind the photo") { h.netThread.pending.get() == 2 }
        assertTrue(photo.results.isEmpty() && timer.results.isEmpty())
        slow.release()
        h.awaitIdle()

        photo.value()
        timer.value()
        assertEquals(setOf("test-net"), netCalls("/_group_profile #3 "))
        assertEquals(setOf("test-net"), netCalls("/_get chat #3 count=1"))
        // The timer's update read the profile after the photo was stored: it keeps the photo.
        val sent = core.sent("/_group_profile #3 ").map { JSONObject(it.cmd.removePrefix("/_group_profile #3 ")) }
        assertEquals(2, sent.size)
        assertEquals(Avatars.dataUrl(AvatarsTest.JPEG), sent[1].getString("image"))
        assertEquals(86400, sent[1].getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))
        assertEquals(86400, h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
    }

    @Test
    fun aGroupPhotoThatTimedOutIsSentAgainOnRetry() {
        val info = groupInfo(id = 3)
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(info))
        core.exact("/_get chat #3 count=1", groupChat(info))
        h.startRunning()
        core.groupProfiles(3, info, Samples.AGENT_BROKER_TIMEOUT, Samples.AGENT_BROKER_TIMEOUT, result("groupUpdated"))
        val failed = Answer<Unit>()
        h.engine.setGroupPhoto(groupChatId, AvatarsTest.JPEG, failed)
        h.awaitIdle()
        assertTrue(failed.results.single() is SecuredResult.Err)
        assertEquals(listOf(0, 1), core.sent("/_group_profile #3 ").map { it.retryNum })
        val reloads = core.calls.count { it.cmd == "/_get chats 1" }

        // This phone kept the photo; the same photo again still goes out, and reaches the members this time.
        val retried = Answer<Unit>()
        h.engine.setGroupPhoto(groupChatId, AvatarsTest.JPEG, retried)
        h.awaitIdle()
        retried.value()
        assertEquals(3, core.sent("/_group_profile #3 ").size)
        assertEquals(Avatars.dataUrl(AvatarsTest.JPEG), JSONObject(core.sent("/_group_profile #3 ").last().cmd.removePrefix("/_group_profile #3 ")).getString("image"))
        assertTrue(core.calls.count { it.cmd == "/_get chats 1" } > reloads)
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
