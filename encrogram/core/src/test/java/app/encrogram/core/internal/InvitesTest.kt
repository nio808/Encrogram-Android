package app.encrogram.core.internal

import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredInvite
import app.encrogram.core.internal.FakeCore.Companion.USER
import app.encrogram.core.internal.FakeCore.Companion.contact
import app.encrogram.core.internal.FakeCore.Companion.group
import app.encrogram.core.internal.FakeCore.Companion.groupInfo
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

/**
 * H5: one-time invites nobody used, counted and revoked from the core's pending connections (`/_get chats 1 pcc=on`);
 * H4: a cut-off invite never reaches the core, so the whole link still works.
 */
class InvitesTest {

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

    private val stats = """{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}"""

    /**
     * A `contactConnection` chat as the core sends it: PendingContactConnection (Types.hs:1931, defaultJSON, so absent
     * Maybe fields are left out), ConnStatus as a tagged object, CreatedConnLink {connFullLink, connShortLink?}.
     */
    private fun pcc(id: Long, status: String = """{"type":"new"}""", link: Boolean = true, extra: String = "") =
        """{"chatInfo":{"type":"contactConnection","contactConnection":{"pccConnId":$id,"pccAgentConnId":"YWdlbnQ$id",
           "pccConnStatus":$status,"viaContactUri":false$extra,
           ${if (link) """"connLinkInv":{"connFullLink":"simplex:/invitation#/?v=2-7","connShortLink":"https://encrogram.nytmanager.com/i#a/b"},""" else ""}
           "localAlias":"","createdAt":"2026-11-10T18:00:00Z","updatedAt":"2026-11-10T18:00:00Z"}},
           "chatItems":[],"chatStats":$stats}"""

    /** Ours and unused: 12 and 13. Every other row is someone's join in progress, or not a one-time invite at all. */
    private val pending = """{"result":{"type":"apiChats","user":$USER,"chats":[
        ${contact(5, "Bob", last = rcv(40))},
        ${group(groupInfo(id = 3))},
        ${pcc(12)},
        ${pcc(13)},
        ${pcc(14, status = """{"type":"joined"}""")},
        ${pcc(15, status = """{"type":"prepared"}""")},
        ${pcc(16, status = """{"type":"requested"}""")},
        ${pcc(17, status = """{"type":"sndReady"}""")},
        ${pcc(18, status = """{"type":"failed","connError":"x"}""")},
        ${pcc(19, link = false)},
        ${pcc(20).replace("\"viaContactUri\":false", "\"viaContactUri\":true")},
        ${pcc(21, extra = ",\"viaUserContactLink\":4")},
        ${pcc(22, extra = ",\"groupLinkId\":\"Z2xpbms=\"")}
    ]}}"""

    @Test
    fun onlyOurUnusedOneTimeInvitesCount() {
        val body = (CoreJson.parseReply(pending) as CoreReply.Result).body
        assertEquals(listOf(12L, 13L), CoreJson.unusedInviteIds(body))
        // ConnStatus written as a plain string (single-field JSON) reads the same.
        val plain = JSONObject(pcc(30).replace("""{"type":"new"}""", "\"new\""))
        assertEquals(listOf(30L), CoreJson.unusedInviteIds(JSONObject().put("chats", org.json.JSONArray().put(plain))))
        // An explicit null is as good as absent.
        val nulls = JSONObject(pcc(31, extra = ",\"viaUserContactLink\":null,\"groupLinkId\":null"))
        assertEquals(listOf(31L), CoreJson.unusedInviteIds(JSONObject().put("chats", org.json.JSONArray().put(nulls))))
        assertEquals(emptyList<Long>(), CoreJson.unusedInviteIds(JSONObject("{}")))
    }

    @Test
    fun aNewInviteCarriesItsConnectionId() {
        h.startRunning()
        core.exact("/_connect 1", Samples.INVITATION)
        val invite = Answer<SecuredInvite>()
        h.engine.createInvite(invite)
        h.awaitIdle()
        assertEquals(12L, invite.value().id)
        assertFalse(invite.value().toString().contains("encrogram"))
    }

    @Test
    fun countLeavesOutTheInviteOnScreenAndNeverDeletes() {
        h.startRunning()
        core.exact("/_get chats 1 pcc=on", pending)
        val all = Answer<Int>()
        val others = Answer<Int>()
        h.engine.unusedInviteCount(null, all)
        h.engine.unusedInviteCount(SecuredInvite("https://encrogram.nytmanager.com/i#a/b", 12L), others)
        h.awaitIdle()
        assertEquals(2, all.value())
        assertEquals(1, others.value())
        assertEquals(setOf("test-main"), (all.threads + others.threads).toSet())
        assertTrue(core.sent("/_delete").isEmpty())
        assertEquals(setOf("test-engine"), core.sent("/_get chats 1 pcc=on").map { it.thread }.toSet())
    }

    @Test
    fun revokeDeletesOnlyUnusedInvitesAndCountsWhatWasDeleted() {
        h.startRunning()
        core.exact("/_get chats 1 pcc=on", pending)
        core.prefix("/_delete :", result("contactConnectionDeleted"))
        val revoked = Answer<Int>()
        h.engine.revokeUnusedInvites(SecuredInvite("x", 12L), revoked)
        h.awaitIdle()
        assertEquals(1, revoked.value())
        assertEquals(listOf("/_delete :13 full notify=off"), core.sent("/_delete").map { it.cmd })

        // No exception: both go. One that turned into a contact meanwhile is refused by the core and not counted.
        core.exact("/_delete :13 full notify=off", storeError("pendingConnectionNotFound"))
        val again = Answer<Int>()
        h.engine.revokeUnusedInvites(null, again)
        h.awaitIdle()
        assertEquals(1, again.value())
        assertEquals(listOf(":13", ":12", ":13"), core.sent("/_delete").map { it.cmd.split(' ')[1] })
    }

    @Test
    fun aGroupLinkOrNoInviteExcludesNothing() {
        h.startRunning()
        core.exact("/_get chats 1 pcc=on", pending)
        val count = Answer<Int>()
        h.engine.unusedInviteCount(SecuredInvite("https://encrogram.nytmanager.com/g#k"), count)
        h.awaitIdle()
        assertEquals(2, count.value())
    }

    @Test
    fun aCutOffInviteIsRefusedBeforeTheCoreSeesIt() {
        h.startRunning()
        core.prefix("/_connect plan 1 ", FakeCore.PLAN_OK)
        val whole = FakeCore.SHORT_INVITE
        val key = whole.substringAfterLast('/').substringBefore('?')
        val cut = whole.substringBefore(key) + key.dropLast(4)
        val done = Answer<Unit>()
        h.engine.connect(cut, done)
        h.awaitIdle()
        assertEquals(SecuredError.Kind.INVALID_LINK, done.error().kind)
        assertEquals(ErrorMapper.LINK_DAMAGED, done.error().message)
        assertTrue(core.sent("/_connect plan").isEmpty())
        assertTrue(core.sent("/connect").isEmpty())
        // The whole link then goes ahead as usual.
        core.prefix("/connect ", result("sentConfirmation"))
        val ok = Answer<Unit>()
        h.engine.connect(whole, ok)
        h.awaitIdle()
        ok.value()
        assertEquals(1, core.sent("/_connect plan").size)
    }

    @Test
    fun linkDataTheCoreCantOpenSaysDamagedNotEngineError() {
        h.startRunning()
        core.prefix("/_connect plan 1 ", """{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent",
            "agentError":{"type":"AGENT","agentErr":{"type":"A_CRYPTO","cryptoErr":{"type":"DECRYPT_CB"}}}}}}""")
        val done = Answer<Unit>()
        h.engine.connect(FakeCore.SHORT_INVITE, done)
        h.awaitIdle()
        assertEquals(ErrorMapper.LINK_DAMAGED, done.error().message)
    }
}
