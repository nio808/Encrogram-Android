package app.encrogram.core.internal

import app.encrogram.core.api.LinkCheck
import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberRole.ADMIN
import app.encrogram.core.api.MemberRole.MEMBER
import app.encrogram.core.api.MemberRole.MODERATOR
import app.encrogram.core.api.MemberRole.OBSERVER
import app.encrogram.core.api.MemberRole.OWNER
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.api.SecuredError.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

/**
 * G3: roles and removal (the core's rule, checked first), group links on our relay (shape, compaction, hostile
 * links) and the join-by-link plan verdict. Shapes from reference/simplex-chat 7.0.0.12 and simplexmq.
 */
class GroupLinksTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    // ---- 1. the role rule (Commands.hs:2877-2890) ----

    private fun m(id: Long, role: MemberRole, status: MemberStatus = MemberStatus.CONNECTED, isMe: Boolean = false, pending: Boolean = false) =
        CoreMember(id, "n$id", role, status, isMe, null, active = status == MemberStatus.CONNECTED, pending = pending)

    private fun me(role: MemberRole, status: MemberStatus = MemberStatus.CONNECTED) = m(1, role, status, isMe = true)

    @Test
    fun roleChangesTheCoreAllows() {
        // (my role, their role, new role)
        val allowed = listOf(
            Triple(ADMIN, MEMBER, ADMIN), Triple(ADMIN, ADMIN, MEMBER), Triple(ADMIN, MEMBER, OBSERVER),
            Triple(ADMIN, OBSERVER, MEMBER), Triple(ADMIN, MEMBER, MODERATOR), Triple(ADMIN, ADMIN, ADMIN),
            Triple(OWNER, MEMBER, OWNER), Triple(OWNER, OWNER, ADMIN), Triple(OWNER, ADMIN, OBSERVER), Triple(OWNER, OBSERVER, OWNER),
        )
        for ((mine, theirs, new) in allowed) {
            assertNull("$mine sets $theirs to $new", GroupRules.roleChangeRefusal(me(mine), m(5, theirs), new))
        }
    }

    @Test
    fun roleChangesTheCoreRefuses() {
        fun refusal(mine: MemberRole, theirs: MemberRole, new: MemberRole) = GroupRules.roleChangeRefusal(me(mine), m(5, theirs), new)
        // Below admin: never.
        for (mine in listOf(OBSERVER, MEMBER, MODERATOR)) assertEquals(ErrorMapper.ONLY_ADMINS_ROLES, refusal(mine, OBSERVER, MEMBER))
        // Only owners touch owners, either way.
        assertEquals(ErrorMapper.ONLY_OWNERS_OWNERS, refusal(ADMIN, OWNER, MEMBER))
        assertEquals(ErrorMapper.ONLY_OWNERS_OWNERS, refusal(ADMIN, MEMBER, OWNER))
        assertEquals(ErrorMapper.ONLY_OWNERS_OWNERS, refusal(ADMIN, OWNER, OWNER))
        // Never your own role, even as the owner.
        assertEquals(ErrorMapper.OWN_ROLE, GroupRules.roleChangeRefusal(me(OWNER), me(OWNER), ADMIN))
        assertEquals(ErrorMapper.OWN_ROLE, GroupRules.roleChangeRefusal(me(OWNER), m(1, OWNER), ADMIN))
        // Someone gone or unknown, someone waiting for approval.
        assertEquals(ErrorMapper.MEMBER_GONE, GroupRules.roleChangeRefusal(me(OWNER), null, ADMIN))
        assertEquals(ErrorMapper.MEMBER_GONE, GroupRules.roleChangeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.LEFT), ADMIN))
        assertEquals(ErrorMapper.MEMBER_GONE, GroupRules.roleChangeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.REMOVED), ADMIN))
        assertEquals(ErrorMapper.MEMBER_PENDING, GroupRules.roleChangeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.CONNECTING, pending = true), ADMIN))
        // Invited and connecting members can be changed (the core re-sends the invitation).
        assertNull(GroupRules.roleChangeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.INVITED), OBSERVER))
        assertNull(GroupRules.roleChangeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.CONNECTING), ADMIN))
        // We must be a connected member ourselves.
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.roleChangeRefusal(null, m(5, MEMBER), ADMIN))
        assertEquals(ErrorMapper.NOT_JOINED, GroupRules.roleChangeRefusal(me(OWNER, MemberStatus.INVITED), m(5, MEMBER), ADMIN))
        assertEquals(ErrorMapper.GROUP_CONNECTING, GroupRules.roleChangeRefusal(me(ADMIN, MemberStatus.CONNECTING), m(5, MEMBER), ADMIN))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.roleChangeRefusal(me(ADMIN, MemberStatus.REMOVED), m(5, MEMBER), ADMIN))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.roleChangeRefusal(me(ADMIN, MemberStatus.LEFT), m(5, MEMBER), ADMIN))
    }

    @Test
    fun removalRule() {
        assertNull(GroupRules.removeRefusal(me(ADMIN), m(5, MEMBER)))
        assertNull(GroupRules.removeRefusal(me(ADMIN), m(5, ADMIN)))
        assertNull(GroupRules.removeRefusal(me(ADMIN), m(5, OBSERVER, MemberStatus.INVITED)))
        assertNull(GroupRules.removeRefusal(me(ADMIN), m(5, MEMBER, MemberStatus.CONNECTING, pending = true)))
        assertNull(GroupRules.removeRefusal(me(OWNER), m(5, OWNER)))
        assertEquals(ErrorMapper.ONLY_ADMINS_REMOVE, GroupRules.removeRefusal(me(MODERATOR), m(5, MEMBER)))
        assertEquals(ErrorMapper.ONLY_ADMINS_REMOVE, GroupRules.removeRefusal(me(MEMBER), m(5, OBSERVER)))
        assertEquals(ErrorMapper.ONLY_OWNERS_REMOVE_OWNERS, GroupRules.removeRefusal(me(ADMIN), m(5, OWNER)))
        assertEquals(ErrorMapper.REMOVE_SELF, GroupRules.removeRefusal(me(OWNER), me(OWNER)))
        assertEquals(ErrorMapper.MEMBER_GONE, GroupRules.removeRefusal(me(OWNER), m(5, MEMBER, MemberStatus.REMOVED)))
        assertEquals(ErrorMapper.MEMBER_GONE, GroupRules.removeRefusal(me(OWNER), null))
        assertEquals(ErrorMapper.NOT_JOINED, GroupRules.removeRefusal(me(OWNER, MemberStatus.INVITED), m(5, MEMBER)))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.removeRefusal(me(ADMIN, MemberStatus.REMOVED), m(5, MEMBER)))
    }

    @Test
    fun linkRuleIsAdminsAndConnected() {
        fun g(role: MemberRole, status: String) = CoreGroup(3, "Team", role, status, 3)
        assertNull(GroupRules.linkRefusal(g(OWNER, "creator")))
        assertNull(GroupRules.linkRefusal(g(ADMIN, "complete")))
        assertNull(GroupRules.linkRefusal(g(ADMIN, "connected")))
        assertEquals(ErrorMapper.ONLY_ADMINS_LINK, GroupRules.linkRefusal(g(MODERATOR, "connected")))
        assertEquals(ErrorMapper.ONLY_ADMINS_LINK, GroupRules.linkRefusal(g(MEMBER, "connected")))
        assertEquals(ErrorMapper.NOT_JOINED, GroupRules.linkRefusal(g(ADMIN, "invited")))
        assertEquals(ErrorMapper.GROUP_CONNECTING, GroupRules.linkRefusal(g(ADMIN, "accepted")))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.linkRefusal(g(OWNER, "left")))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.linkRefusal(g(OWNER, "somethingNew")))
    }

    @Test
    fun pendingMembersAreRead() {
        val b = JSONObject("""{"group":{"groupInfo":{"groupId":3,"membership":{"groupMemberId":1,"memberRole":"owner",
            "memberStatus":"creator","memberProfile":{"displayName":"Me"}}},"members":[
            {"groupMemberId":5,"memberRole":"member","memberStatus":"pending_approval","memberProfile":{"displayName":"Ann"}},
            {"groupMemberId":6,"memberRole":"member","memberStatus":"connected","memberProfile":{"displayName":"Bo"}}]}}""")
        val ms = CoreJson.parseMembers(b)
        assertTrue(ms[1].pending)
        assertEquals(false, ms[2].pending)
    }

    // ---- 2. command strings (Commands.hs:5512, 5514, 5634-5637, 5658) ----

    @Test
    fun commandStrings() {
        assertEquals("/_member role #3 12 admin", Commands.setMemberRole(3, 12, ADMIN))
        assertEquals("/_member role #3 12 owner", Commands.setMemberRole(3, 12, OWNER))
        assertEquals("/_member role #3 12 moderator", Commands.setMemberRole(3, 12, MODERATOR))
        assertEquals("/_member role #3 12 member", Commands.setMemberRole(3, 12, MEMBER))
        assertEquals("/_member role #3 12 observer", Commands.setMemberRole(3, 12, OBSERVER))
        assertEquals("/_remove #3 12 messages=off", Commands.removeMember(3, 12))
        assertEquals("/_create link #3 member", Commands.createGroupLink(3, Commands.ROLE_MEMBER))
        assertEquals("/_create link #3 observer", Commands.createGroupLink(3, Commands.ROLE_OBSERVER))
        assertEquals("/_set link role #3 observer", Commands.setGroupLinkRole(3, Commands.ROLE_OBSERVER))
        assertEquals("/_get link #3", Commands.getGroupLink(3))
        assertEquals("/_delete link #3", Commands.deleteGroupLink(3))
        assertEquals("/_connect 1 simplex:/contact#/?v=2-7&smp=x", Commands.connectResolved(1, "simplex:/contact#/?v=2-7&smp=x", null))
        assertEquals("/_connect 1 simplex:/contact#/?v=2-7&smp=x https://h/g#k?p=1&c=f",
            Commands.connectResolved(1, "simplex:/contact#/?v=2-7&smp=x", "https://h/g#k?p=1&c=f"))
        refused { Commands.setMemberRole(3, 0, ADMIN) }
        refused { Commands.removeMember(3, -1) }
        refused { Commands.setMemberRole(0, 5, ADMIN) }
        refused { Commands.createGroupLink(3, "admin") }
        refused { Commands.setGroupLinkRole(3, "owner") }
        refused { Commands.connectResolved(1, "has space", null) }
        refused { Commands.connectResolved(1, "simplex:/contact#/?v=1", "") }
        refused { Commands.connectResolved(1, "simplex:/contact#/?v=1", "two words") }
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---- 3. group links on our relay: shape, compaction, hostile links ----

    private val host = "encrogram.nytmanager.com"
    private val fp = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"
    private val allowed = setOf(host, "engrogramapi.nytmanager.com")
    private val smp = listOf(Samples.SMP) // ...@encrogram.nytmanager.com:5223
    private val linkKey = "x7Yk_9-Qw3ErTyUiOpAsDfGhJkLzXcVbNm123456789"
    private val coreGroupLink = "https://$host/g#$linkKey?p=5223&c=$fp"
    private val compactGroupLink = "https://$host/g#$linkKey"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A full group link as crEncode writes it: a contact request whose data names the group link (Commands.hs:3248). */
    private fun fullGroupLink(server: String = "$host:5223", keyHash: String = fp) =
        "simplex:/contact#/?v=2-7&smp=${enc("smp://$keyHash@$server/q1q2q3q4#/?v=1-4&dh=MCowBQYDK2VuAyEA")}" +
            "&data=${enc("""{"groupLinkId":"AAECAwQFBgcICQoLDA0ODw=="}""")}"

    private fun check(link: String) = LinkChecker.check(LinkChecker.expand(link, smp), allowed, smp)

    @Test
    fun ourGroupLinkIsApprovedAndCompacted() {
        assertEquals(LinkCheck.Approved, check(coreGroupLink))
        assertEquals(LinkCheck.Approved, check("simplex:/g#$linkKey?h=$host&p=5223&c=$fp"))
        assertEquals(LinkCheck.Approved, check(fullGroupLink()))
        assertEquals(LinkCheck.Approved, check(fullGroupLink(server = host))) // no port: 5223
        // Compacted like our invites, and expanded back to exactly the core's link.
        assertEquals(compactGroupLink, LinkChecker.compact(coreGroupLink, smp))
        assertEquals(coreGroupLink, LinkChecker.expand(compactGroupLink, smp))
        assertEquals(LinkCheck.Approved, check(compactGroupLink))
        val analysis = LinkChecker.analyze(LinkChecker.expand("HTTPS://Encrogram.NytManager.com/g#$linkKey", smp), allowed, smp)
        assertEquals(LinkCheck.Approved, analysis.result)
        assertEquals("https://$host/g#$linkKey?p=5223&c=$fp", analysis.normalized)
        // Full links and app-scheme links are handed out and taken as they are.
        assertEquals(fullGroupLink(), LinkChecker.compact(fullGroupLink(), smp))
        assertEquals("simplex:/g#$linkKey?h=$host&p=5223&c=$fp", LinkChecker.compact("simplex:/g#$linkKey?h=$host&p=5223&c=$fp", smp))
    }

    @Test
    fun hostileGroupLinksAreRefused() {
        val other = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val unapproved = mapOf(
            "https://smp8.simplex.im/g#$linkKey" to "smp8.simplex.im",                          // another host
            "https://$host.evil.example/g#$linkKey" to "$host.evil.example",                    // look-alike host
            "https://$host/g#$linkKey?h=relay.evil.example&p=5223&c=$fp" to "relay.evil.example", // extra host in h=
            "https://$host/g#$linkKey?p=5223&c=$other" to host,                                 // wrong fingerprint
            "https://$host/g#$linkKey?p=443&c=$fp" to host,                                     // wrong port
            "simplex:/g#$linkKey?h=evil.example" to "evil.example",                              // app scheme elsewhere
            fullGroupLink(server = "smp11.simplex.im") to "smp11.simplex.im",                    // full link elsewhere
            fullGroupLink(keyHash = other) to host,                                              // full link, wrong key
            fullGroupLink(server = "$host,abcdef.onion:5223") to "abcdef.onion",                 // a second host
            "https://engrogramapi.nytmanager.com/g#$linkKey" to "engrogramapi.nytmanager.com",  // allowed, but not SMP
        )
        for ((link, h) in unapproved) assertEquals(link, LinkCheck.UnapprovedRelay(h), check(link))
        val malformed = listOf(
            "https://$host/g#$linkKey?h=$host;h=evil.example",           // ';' smuggling
            "https://$host/g#$linkKey?p=5223&c=$fp&x=1",                 // a query key the core never writes
            "https://$host/g#$linkKey?x=1",                              // ...even alone (no p=/c= put back)
            "https://$host/g#$linkKey?%68=evil.example",                 // encoded parameter name
            "https://$host/g#$linkKey?h=evil.example&h=$host",           // repeated parameter
            "https://encrogram%2Enytmanager.com/g#$linkKey",             // encoded host
            "https://user@$host/g#$linkKey",                             // user info
            "https://$host:5223/g#$linkKey",                             // port in the authority
            "https://$host/g#$linkKey extra",                            // a space would split the command
            "https://$host/G#$linkKey",                                  // the core writes lower case only
            "https://$host/g#short",                                     // not a key
            "https://$host/c#$linkKey",                                  // relay channel
            "https://$host/r#$linkKey",                                  // chat relay
            "https://$host/g#$linkKey".replace("encrogram", "еncrogram"), // Cyrillic e
        )
        for (link in malformed) assertEquals(link, LinkCheck.Malformed, check(link))
        // Never compacted either.
        val extra = "https://$host/g#$linkKey?p=5223&c=$fp&x=1"
        assertEquals(extra, LinkChecker.compact(extra, smp))
    }

    @Test
    fun groupLinkReplies() {
        val created = JSONObject("""{"type":"groupLinkCreated","groupInfo":{"groupId":3},"groupLink":{"userContactLinkId":7,
            "connLinkContact":{"connFullLink":"${fullGroupLink()}","connShortLink":"$coreGroupLink"},
            "shortLinkDataSet":true,"shortLinkLargeDataSet":true,"groupLinkId":"AAECAwQFBgcICQoLDA0ODw==","acceptMemberRole":"observer"}}""")
        val l = CoreJson.parseGroupLink(created)!!
        assertEquals(fullGroupLink(), l.fullLink)
        assertEquals(coreGroupLink, l.shortLink)
        assertEquals(OBSERVER, l.role)
        // An old group without a short link: the full link is what we hand out.
        val noShort = CoreJson.parseGroupLink(JSONObject("""{"groupLink":{"connLinkContact":{"connFullLink":"${fullGroupLink()}"},"acceptMemberRole":"member"}}"""))!!
        assertNull(noShort.shortLink)
        assertEquals(MEMBER, noShort.role)
        assertNull(CoreJson.parseGroupLink(JSONObject("{}")))
        // No link yet / readable store error.
        assertEquals("This group has no link yet.", ErrorMapper.fromCore(CoreError("errorStore", "groupLinkNotFound", null), ErrorMapper.Op.INVITE).message)
    }

    // ---- 4. joining by link: the plan verdict (Controller.hs:1104-1134) ----

    private fun plan(planJson: String, full: String? = fullGroupLink(), short: String? = coreGroupLink): JSONObject {
        val link = if (full == null) "" else """"connLink":{"connFullLink":"$full"${if (short != null) ""","connShortLink":"$short"""" else ""}},"""
        return JSONObject("""{"type":"connectionPlan","user":{"userId":1},$link"connectionPlan":$planJson}""")
    }

    private fun groupPlan(type: String, extra: String = "") = """{"type":"groupLink","groupLinkPlan":{"type":"$type"$extra}}"""

    private val okDirect = groupPlan("ok", ""","groupSLinkInfo_":{"direct":true,"groupRelays":[]},
        "groupSLinkData_":{"groupProfile":{"displayName":"Team","fullName":""}},"ownerVerification":null""")

    private fun verdict(body: JSONObject) = ConnectPlan.verdict(body, allowed, smp)

    private fun refusal(body: JSONObject): ConnectPlan.Verdict.Refuse =
        verdict(body) as? ConnectPlan.Verdict.Refuse ?: throw AssertionError("not refused: ${verdict(body)}")

    @Test
    fun aDirectGroupOnOurRelayIsJoinedWithTheCheckedLinks() {
        assertEquals(ConnectPlan.Verdict.JoinGroup(fullGroupLink(), coreGroupLink), verdict(plan(okDirect)))
        // A full link pasted: no short link.
        assertEquals(ConnectPlan.Verdict.JoinGroup(fullGroupLink(), null), verdict(plan(groupPlan("ok"), short = null)))
        // A stuck earlier request may be repeated (same request id, the admin's phone dedupes).
        assertEquals(ConnectPlan.Verdict.JoinGroup(fullGroupLink(), coreGroupLink), verdict(plan(groupPlan("connectingConfirmReconnect"))))
    }

    @Test
    fun groupPlansThatAreRefused() {
        fun r(type: String) = refusal(plan(groupPlan(type))).error
        assertEquals(Kind.INVALID_LINK, r("ownLink").kind)
        assertEquals(ErrorMapper.OWN_GROUP_LINK, r("ownLink").message)
        assertEquals(ErrorMapper.ALREADY_IN_GROUP, r("known").message)
        assertEquals(ErrorMapper.ALREADY_JOINING, r("connectingProhibit").message)
        assertEquals(Kind.NOT_ALLOWED, r("noRelays").kind)
        assertEquals(ErrorMapper.UNSUPPORTED_GROUP_LINK, r("updateRequired").message)
        // A plan this version doesn't know: refused, never joined.
        assertEquals(ErrorMapper.LINK_INVALID, r("somethingNew").message)
        // The same words if /connect ever answers with one of these plans.
        assertEquals(ErrorMapper.ALREADY_IN_GROUP, ErrorMapper.fromConnectionPlan("groupLink.known").message)
        assertEquals(ErrorMapper.OWN_GROUP_LINK, ErrorMapper.fromConnectionPlan("groupLink.ownLink").message)
        // Never OWN_LINK (that kind sends the UI to Saved Messages).
        for (t in listOf("ownLink", "known", "connectingProhibit", "noRelays", "updateRequired")) assertTrue(r(t).kind != Kind.OWN_LINK)
    }

    @Test
    fun relayChannelsAreRefused() {
        val relayLinks = listOf(
            groupPlan("ok", ""","groupSLinkInfo_":{"direct":false,"groupRelays":[]}"""),
            groupPlan("ok", ""","groupSLinkInfo_":{"direct":"false","groupRelays":[]}"""),
            groupPlan("ok", ""","groupSLinkInfo_":{"direct":true,"groupRelays":["https://relay.evil.example/r#$linkKey"]}"""),
            groupPlan("ok", ""","groupSLinkData_":{"groupProfile":{"displayName":"C","fullName":""},"publicGroupData":{"publicMemberCount":5}}"""),
            groupPlan("ok", ""","groupSLinkData_":{"groupProfile":{"displayName":"C","fullName":"","publicGroup":{"groupType":"channel"}}}"""),
        )
        for (p in relayLinks) {
            val r = refusal(plan(p))
            assertEquals(p, Kind.NOT_ALLOWED, r.error.kind)
            assertEquals("relay_group", r.logTag)
        }
    }

    @Test
    fun theRequestBehindTheLinkMustBeOnOurRelay() {
        // An attacker's short link on our relay whose data holds a request on another relay, or with another key.
        val elsewhere = refusal(plan(okDirect, full = fullGroupLink(server = "smp11.simplex.im")))
        assertEquals(Kind.UNAPPROVED_RELAY, elsewhere.error.kind)
        assertTrue(elsewhere.error.message.contains("smp11.simplex.im"))
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(okDirect, full = fullGroupLink(keyHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))).error.kind)
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(okDirect, full = fullGroupLink(server = "$host:443"))).error.kind)
        // ...or hides a second host.
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(okDirect, full = fullGroupLink(server = "$host,abcdef.onion:5223"))).error.kind)
        // The short link the core would store, too.
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(okDirect, short = "https://smp8.simplex.im/g#$linkKey")).error.kind)
        // No request at all, or one we can't read: refused.
        assertEquals(Kind.INVALID_LINK, refusal(plan(okDirect, full = null)).error.kind)
        assertEquals(Kind.INVALID_LINK, refusal(plan(okDirect, full = "simplex:/contact#/?v=2-7")).error.kind)
        // Checked before the plan itself: even "own link" or "known" never passes an unapproved request.
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(groupPlan("known"), full = fullGroupLink(server = "evil.example"))).error.kind)
    }

    @Test
    fun oneToOnePlansStillGoThroughConnect() {
        val inviteFull = "simplex:/invitation#/?v=2-7&smp=${enc("smp://$fp@$host:5223/q1q2q3q4#/?v=1-4&dh=MCowBQYDK2VuAyEA")}&e2e=${enc("v=2-3&x3dh=a,b")}"
        val inviteShort = "https://$host/i#AbCdEfGhIjKlMnOpQrStUvWxYz012345/$linkKey?p=5223&c=$fp"
        fun invPlan(type: String) = """{"type":"invitationLink","invitationLinkPlan":{"type":"$type"}}"""
        assertEquals(ConnectPlan.Verdict.Connect, verdict(plan(invPlan("ok"), inviteFull, inviteShort)))
        assertEquals(ConnectPlan.Verdict.Connect, verdict(plan(invPlan("known"), inviteFull, inviteShort)))
        assertEquals(ConnectPlan.Verdict.Connect, verdict(plan("""{"type":"contactAddress","contactAddressPlan":{"type":"ok"}}""", fullGroupLink(), "https://$host/a#$linkKey?p=5223&c=$fp")))
        assertEquals(Kind.OWN_LINK, refusal(plan(invPlan("ownLink"), inviteFull, inviteShort)).error.kind)
        // The same resolved-request check: a one-time invite whose data points at another relay is refused too.
        val evil = inviteFull.replace(host, "smp4.simplex.im")
        assertEquals(Kind.UNAPPROVED_RELAY, refusal(plan(invPlan("ok"), evil, inviteShort)).error.kind)
        // The core's own sample invitation passes.
        val sample = CoreJson.parseInvitation((CoreJson.parseReply(Samples.INVITATION) as CoreReply.Result).body)
        val sampleServers = listOf("smp://fp@$host:5223")
        assertEquals(ConnectPlan.Verdict.Connect,
            ConnectPlan.verdict(plan(invPlan("ok"), sample.fullLink, null), allowed, sampleServers))
    }
}
