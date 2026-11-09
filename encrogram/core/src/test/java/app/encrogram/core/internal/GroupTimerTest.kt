package app.encrogram.core.internal

import app.encrogram.core.api.ChatAction
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.FakeCore.Companion.chatsReply
import app.encrogram.core.internal.FakeCore.Companion.contact
import app.encrogram.core.internal.FakeCore.Companion.group
import app.encrogram.core.internal.FakeCore.Companion.groupChat
import app.encrogram.core.internal.FakeCore.Companion.groupInfo
import app.encrogram.core.internal.FakeCore.Companion.rcv
import app.encrogram.core.internal.FakeCore.Companion.result
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Group disappearing messages: the owner sets the group preference timedMessages {enable, ttl} through the group
 * profile update that keeps everything else; members are refused; the row's timer is the group's.
 */
class GroupTimerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var h: EngineHarness
    private val core get() = h.core
    private val groupChatId = CoreJson.groupChatId(3)

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        h = EngineHarness(tmp.newFolder("nobackup"))
    }

    @After
    fun tearDown() = h.close()

    private fun profile(info: String) = JSONObject(info).getJSONObject("groupProfile")

    // ---- JSON ----------------------------------------------------------------------------------------------------

    @Test
    fun theTimerChangesOnlyTimedMessages() {
        val current = profile(groupInfo(timedTtl = null))
        val on = CoreJson.groupTimerUpdate(current, 86400)
        assertEquals("""{"enable":"on","ttl":86400}""", on.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        // Everything else as stored: other preferences, description, admission, name.
        assertEquals("on", on.getJSONObject("groupPreferences").getJSONObject("history").getString("enable"))
        assertEquals("on", on.getJSONObject("groupPreferences").getJSONObject("fullDelete").getString("enable"))
        assertEquals("keep me", on.getString("description"))
        assertEquals("all", on.getJSONObject("memberAdmission").getString("review"))
        assertEquals("Team", on.getString("displayName"))
        assertEquals("the stored profile is not touched", "off", current.getJSONObject("groupPreferences").getJSONObject("timedMessages").getString("enable"))

        val off = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 604800)), null)
        assertEquals("""{"enable":"off"}""", off.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        val changed = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 604800)), 86400)
        assertEquals(86400, changed.getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))
    }

    @Test
    fun anUnchangedTimerIsStillBuilt() {
        // Review of 8c89dfd6, finding 1: after a relay timeout the owner's phone holds the timer and the members don't;
        // the retry must send it again.
        val same = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 86400)), 86400)
        assertEquals("""{"enable":"on","ttl":86400}""", same.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        assertTrue(profile(groupInfo(timedTtl = 86400)).similar(same))
        val stillOff = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = null)), null)
        assertEquals("""{"enable":"off"}""", stillOff.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        // No timedMessages at all is the core's default, off: written out as off, everything else kept.
        val bare = JSONObject("""{"displayName":"Team","fullName":"","groupPreferences":{"history":{"enable":"on"}}}""")
        val bareOff = CoreJson.groupTimerUpdate(bare, null).getJSONObject("groupPreferences")
        assertEquals("off", bareOff.getJSONObject("timedMessages").getString("enable"))
        assertEquals("on", bareOff.getJSONObject("history").getString("enable"))
        val noPrefs = CoreJson.groupTimerUpdate(JSONObject("""{"displayName":"Team"}"""), 3600)
        assertEquals("", noPrefs.getString("fullName"))
        assertEquals(3600, noPrefs.getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))
    }

    @Test
    fun theRowShowsTheGroupsTimerWhileItIsOn() {
        val on = CoreJson.parseGroup(JSONObject(groupInfo(timedTtl = 86400)))!!
        assertEquals(86400, on.timedTtlSeconds)
        assertNull(CoreJson.parseGroup(JSONObject(groupInfo(timedTtl = null)))!!.timedTtlSeconds)
        // On without a ttl: the core sets no timer.
        val noTtl = JSONObject(groupInfo()).apply {
            getJSONObject("fullGroupPreferences").put("timedMessages", JSONObject("""{"enable":"on"}"""))
        }
        assertNull(CoreJson.parseGroup(noTtl)!!.timedTtlSeconds)
        val row = MessageMapper.groupSummary(CoreGroupChat(on, null, 0), archived = false)
        assertEquals(86400, row.disappearingTtlSeconds)
    }

    // ---- engine --------------------------------------------------------------------------------------------------

    private fun startWithGroup(myRole: String, timedTtl: Int? = null) {
        val info = groupInfo(myRole = myRole, myStatus = if (myRole == "owner") "creator" else "connected", timedTtl = timedTtl)
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(info))
        core.exact("/_get chat #3 count=1", groupChat(info))
        h.startRunning()
    }

    @Test
    fun theOwnerSetsTheGroupsTimerWithEveryOtherSettingKept() {
        startWithGroup("owner")
        assertNull(h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
        core.prefix("/_group_profile #3 ") {
            // The core stores it: the next list load shows the timer.
            core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(groupInfo(timedTtl = 86400)))
            result("groupUpdated")
        }
        val done = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(86400), done)
        h.awaitIdle()

        done.value()
        val sent = JSONObject(core.sent("/_group_profile #3 ").single().cmd.removePrefix("/_group_profile #3 "))
        val prefs = sent.getJSONObject("groupPreferences")
        assertEquals(86400, prefs.getJSONObject("timedMessages").getInt("ttl"))
        assertEquals("on", prefs.getJSONObject("timedMessages").getString("enable"))
        assertEquals("on", prefs.getJSONObject("history").getString("enable"))
        assertEquals("keep me", sent.getString("description"))
        assertEquals(86400, h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
        assertTrue(h.listener.log.contains("chats"))
    }

    @Test
    fun theOwnerTurnsItOffAndTheSameTimerIsSentAgain() {
        startWithGroup("owner", timedTtl = 604800)
        assertEquals(604800, h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
        core.prefix("/_group_profile #3 ", result("groupUpdated"))
        val same = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(604800), same)
        h.awaitIdle()
        same.value()
        val again = JSONObject(core.sent("/_group_profile #3 ").single().cmd.removePrefix("/_group_profile #3 "))
        assertEquals(604800, again.getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))

        val off = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(null), off)
        h.awaitIdle()
        off.value()
        val sent = JSONObject(core.sent("/_group_profile #3 ").last().cmd.removePrefix("/_group_profile #3 "))
        assertEquals("off", sent.getJSONObject("groupPreferences").getJSONObject("timedMessages").getString("enable"))
    }

    /**
     * Review of 8c89dfd6, finding 1: the core saves the new profile, then times out writing the group link's data on our
     * relay, so the members never get it. The owner sees the error and the row the core kept; a retry sends it again.
     */
    @Test
    fun aRelayTimeoutShowsWhatThisPhoneKeptAndARetrySendsItAgain() {
        startWithGroup("owner")
        core.groupProfiles(3, groupInfo(), Samples.AGENT_BROKER_TIMEOUT)
        val failed = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(86400), failed)
        h.awaitIdle()

        assertTrue(failed.results.single() is SecuredResult.Err)
        // On the network thread, with NetRetry's one retry; the profile read there too.
        assertEquals(listOf(0, 1), core.sent("/_group_profile #3 ").map { it.retryNum })
        assertEquals(setOf("test-net"), core.sent("/_group_profile #3 ").map { it.thread }.toSet())
        assertEquals("test-net", core.sent("/_get chat #3 count=1").last().thread)
        // The list is reloaded after the failure: the row shows the timer this phone stored.
        assertTrue(core.calls.indexOfLast { it.cmd == "/_get chats 1" } > core.calls.indexOfLast { it.cmd.startsWith("/_group_profile #3 ") })
        assertEquals(86400, h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
        assertTrue(h.listener.log.contains("chats"))

        // The relay answers again. The profile already holds the timer, and the owner's retry still sends it.
        core.groupProfiles(3, groupInfo(), result("groupUpdated"))
        val retried = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(86400), retried)
        h.awaitIdle()
        retried.value()
        val sent = core.sent("/_group_profile #3 ")
        assertEquals(3, sent.size)
        assertEquals(86400, JSONObject(sent.last().cmd.removePrefix("/_group_profile #3 "))
            .getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))
    }

    @Test
    fun aMemberIsNotAllowedAndNothingIsSent() {
        for (role in listOf("admin", "member")) {
            h.close()
            h = EngineHarness(tmp.newFolder("nobackup_$role"))
            startWithGroup(role)
            val before = core.calls.size
            val refused = Answer<Unit>()
            h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(86400), refused)
            h.awaitIdle()
            assertEquals(SecuredError.Kind.NOT_ALLOWED, refused.error().kind)
            assertEquals(ErrorMapper.ONLY_OWNERS_TIMER, refused.error().message)
            assertEquals("refused before anything reaches the core", before, core.calls.size)
        }
    }

    @Test
    fun aTimerOutOfRangeIsRefusedBeforeAnything() {
        startWithGroup("owner")
        val bad = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(0), bad)
        h.awaitIdle()
        assertEquals(SecuredError.Kind.UNKNOWN, bad.error().kind)
        assertTrue(core.sent("/_group_profile").isEmpty())
    }

    @Test
    fun readingATimedGroupAsksForItsOwnItems() {
        val info = groupInfo(timedTtl = 86400)
        core.chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(info, unread = 2))
        h.startRunning()
        h.engine.markRead(groupChatId)
        h.awaitIdle()
        val read = core.indexOf("/_read chat #3")
        assertTrue(read >= 0)
        // The started timers are read from the group itself (2 unread + 50), never from a contact with the group's chat id.
        assertTrue(core.calls.indexOfFirst { it.cmd == "/_get chat #3 count=52" } > read)
        assertTrue(core.sent("/_get chat @$groupChatId").isEmpty())
    }

    @Test
    fun contactsKeepTheirOwnTimer() {
        h.startRunning()
        core.prefix("/_set prefs @5 ", result("contactPrefsUpdated"))
        val done = Answer<Unit>()
        h.engine.chatAction(5, ChatAction.SetDisappearing(3600), done)
        h.awaitIdle()
        done.value()
        val prefs = JSONObject(core.sent("/_set prefs @5 ").single().cmd.removePrefix("/_set prefs @5 "))
        assertEquals(3600, prefs.getJSONObject("timedMessages").getInt("ttl"))
        assertTrue(core.sent("/_group_profile").isEmpty())
    }
}
