package app.encrogram.core.internal

import app.encrogram.core.api.ChatAction
import app.encrogram.core.api.SecuredError
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
        val on = CoreJson.groupTimerUpdate(current, 86400)!!
        assertEquals("""{"enable":"on","ttl":86400}""", on.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        // Everything else as stored: other preferences, description, admission, name.
        assertEquals("on", on.getJSONObject("groupPreferences").getJSONObject("history").getString("enable"))
        assertEquals("on", on.getJSONObject("groupPreferences").getJSONObject("fullDelete").getString("enable"))
        assertEquals("keep me", on.getString("description"))
        assertEquals("all", on.getJSONObject("memberAdmission").getString("review"))
        assertEquals("Team", on.getString("displayName"))
        assertEquals("the stored profile is not touched", "off", current.getJSONObject("groupPreferences").getJSONObject("timedMessages").getString("enable"))

        val off = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 604800)), null)!!
        assertEquals("""{"enable":"off"}""", off.getJSONObject("groupPreferences").getJSONObject("timedMessages").toString())
        val changed = CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 604800)), 86400)!!
        assertEquals(86400, changed.getJSONObject("groupPreferences").getJSONObject("timedMessages").getInt("ttl"))
    }

    @Test
    fun anUnchangedTimerSendsNothing() {
        assertNull(CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = 86400)), 86400))
        assertNull(CoreJson.groupTimerUpdate(profile(groupInfo(timedTtl = null)), null))
        // No timedMessages at all is the core's default: off.
        val bare = JSONObject("""{"displayName":"Team","fullName":"","groupPreferences":{"history":{"enable":"on"}}}""")
        assertNull(CoreJson.groupTimerUpdate(bare, null))
        val noPrefs = CoreJson.groupTimerUpdate(JSONObject("""{"displayName":"Team"}"""), 3600)!!
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
    fun theOwnerTurnsItOffAndAnUnchangedTimerSendsNothing() {
        startWithGroup("owner", timedTtl = 604800)
        assertEquals(604800, h.engine.chats().single { it.chatId == groupChatId }.disappearingTtlSeconds)
        core.prefix("/_group_profile #3 ", result("groupUpdated"))
        val same = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(604800), same)
        h.awaitIdle()
        same.value()
        assertTrue(core.sent("/_group_profile").isEmpty())

        val off = Answer<Unit>()
        h.engine.chatAction(groupChatId, ChatAction.SetDisappearing(null), off)
        h.awaitIdle()
        off.value()
        val sent = JSONObject(core.sent("/_group_profile #3 ").single().cmd.removePrefix("/_group_profile #3 "))
        assertEquals("off", sent.getJSONObject("groupPreferences").getJSONObject("timedMessages").getString("enable"))
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
