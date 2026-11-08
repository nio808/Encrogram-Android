package app.encrogram.core.internal

import app.encrogram.core.api.SecuredError.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Own invite -> OWN_LINK. `/_connect plan <userId> <link>` answers `connectionPlan` (Controller.hs ConnectionPlan,
 * sumTypeJSON "CP"/"ILP"/"CAP"). The core's own `/connect` goes ahead on ownLink (connectionPlanProceed), so the
 * engine asks first.
 */
class OwnLinkPlanTest {

    private val user = """{"userId":1,"agentUserId":"1","userContactId":1,"localDisplayName":"alice",
        "profile":{"profileId":1,"displayName":"Alice","fullName":"","localAlias":""},"activeUser":true}"""

    private fun planReply(plan: String) = """{"result":{"type":"connectionPlan","user":$user,
        "connLink":{"connFullLink":"simplex:/invitation#/?v=2-7","connShortLink":"https://encrogram.nytmanager.com/i#a/b"},
        "connectionPlan":$plan}}"""

    private fun tag(plan: String) = CoreJson.connectionPlanTag((CoreJson.parseReply(planReply(plan)) as CoreReply.Result).body)

    @Test
    fun planTags() {
        assertEquals("invitationLink.ownLink", tag("""{"type":"invitationLink","invitationLinkPlan":{"type":"ownLink"}}"""))
        assertEquals("invitationLink.ok", tag("""{"type":"invitationLink","invitationLinkPlan":{"type":"ok","contactSLinkData_":null}}"""))
        assertEquals("invitationLink.known", tag("""{"type":"invitationLink","invitationLinkPlan":{"type":"known","contact":{"contactId":5}}}"""))
        assertEquals("contactAddress.ownLink", tag("""{"type":"contactAddress","contactAddressPlan":{"type":"ownLink"}}"""))
        assertEquals("error.error", tag("""{"type":"error","chatError":{"type":"error","errorType":{"type":"invalidConnReq"}}}"""))
        assertEquals("unknown", CoreJson.connectionPlanTag(org.json.JSONObject("{}")))
    }

    @Test
    fun ownInviteOrAddressIsRefusedWithOwnLink() {
        for (t in listOf("invitationLink.ownLink", "contactAddress.ownLink")) {
            val e = ErrorMapper.planRefusal(t) ?: return fail(t)
            assertEquals(Kind.OWN_LINK, e.kind)
            assertEquals("This is your own invite link. Notes to yourself go in Saved Messages.", e.message)
        }
        // The same verdict if /connect itself ever answers with an ownLink plan.
        assertEquals(Kind.OWN_LINK, ErrorMapper.fromConnectionPlan("invitationLink.ownLink").kind)
    }

    @Test
    fun everyOtherPlanLeavesTheDecisionToConnect() {
        for (t in listOf("invitationLink.ok", "invitationLink.known", "invitationLink.connecting", "contactAddress.ok",
            "contactAddress.known", "contactAddress.contactViaAddress", "groupLink.ok", "error.error", "unknown")) {
            assertNull(t, ErrorMapper.planRefusal(t))
        }
        // ...which refuses known and connecting links as before.
        assertEquals(Kind.INVALID_LINK, ErrorMapper.fromConnectionPlan("invitationLink.known").kind)
    }

    @Test
    fun aFailedPlanStopsTheConnectWithAReadableReason() {
        // G3: no plan, no connect (the request behind a short link must be checked first). The engine reports the
        // plan's own error the way a failed connect would have.
        fun refusal(json: String) = ErrorMapper.fromCore((CoreJson.parseReply(json) as CoreReply.Error).error, ErrorMapper.Op.CONNECT)

        val invalid = refusal("""{"error":{"type":"error","errorType":{"type":"invalidConnReq"}}}""")
        assertEquals(Kind.INVALID_LINK, invalid.kind)
        assertEquals(ErrorMapper.LINK_INVALID, invalid.message)
        // A one-time link already used or expired: the relay says AUTH.
        assertEquals(ErrorMapper.LINK_USED, refusal(Samples.AGENT_SMP_AUTH).message)
        // Network trouble: the network message, not a raw error.
        assertEquals(Kind.NETWORK, refusal(Samples.AGENT_BROKER_TIMEOUT).kind)
        assertEquals(Kind.ENGINE_NOT_RUNNING, refusal("""{"error":{"type":"error","errorType":{"type":"chatNotStarted"}}}""").kind)
    }

    @Test
    fun planCommand() {
        val link = "https://encrogram.nytmanager.com/i#AbCdEfGhIjKlMnOpQrStUvWxYz012345/x7Yk?p=5223&c=abc"
        assertEquals("/_connect plan 1 $link", Commands.connectPlan(1, link))
        for (bad in listOf("", "has space", "tab\there", "nonasciié")) {
            try {
                Commands.connectPlan(1, bad)
                fail("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                // expected: only checked links reach the core
            }
        }
    }
}
