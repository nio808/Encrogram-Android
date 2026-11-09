package app.encrogram.core.internal

import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.backup.RepairKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** b1: queue servers outside our relay config (ConnectionStats, simplexmq Agent/Protocol.hs:686-717). */
class ForeignRelaysTest {

    // Our config writes the fingerprint unpadded base64url; the core writes it padded (strEncode = U.encode).
    private val ours = listOf(ServerAddress.parse("smp://AbC-_dEf@relay.example.org,relay2.example.org:5223")!!)
    private val gone = setOf("contactNotFound", "groupMemberNotActive")

    private fun served(addr: String) = ServerAddress.parse(addr)!!.servedBy(ours)

    @Test
    fun whichServersAreOurs() {
        assertTrue(served("smp://AbC-_dEf@relay.example.org:5223"))
        assertTrue("padded", served("smp://AbC-_dEf=@relay.example.org"))
        assertTrue("another port to the same relay", served("smp://AbC-_dEf@relay.example.org:443"))
        assertTrue("host order", served("smp://AbC-_dEf@relay2.example.org,relay.example.org"))
        assertTrue("case and trailing dot", served("smp://AbC-_dEf@RELAY.example.org."))
        assertFalse("another key", served("smp://XyZ@relay.example.org"))
        assertFalse("key case matters", served("smp://abc-_def@relay.example.org"))
        assertFalse("another host", served("smp://AbC-_dEf@smp.other.example"))
        assertFalse("an extra host we don't list", served("smp://AbC-_dEf@relay.example.org,x.onion"))
        assertFalse("not an SMP relay", served("xftp://AbC-_dEf@relay.example.org"))
    }

    private fun stats(rcv: List<String>, snd: List<String>) = JSONObject().apply {
        put("connAgentVersion", 7)
        put("rcvQueuesInfo", org.json.JSONArray(rcv.map { JSONObject().put("rcvServer", it).put("status", "active").put("canAbortSwitch", false) }))
        put("sndQueuesInfo", org.json.JSONArray(snd.map { JSONObject().put("sndServer", it).put("status", "active") }))
        put("ratchetSyncState", "ok")
    }

    private val mine = "smp://AbC-_dEf=@relay.example.org:5223"
    private val theirs = "smp://XyZ=@smp.other.example"

    @Test
    fun queuesOnAnotherRelay() {
        assertEquals(listOf(mine, theirs), CoreJson.queueServers(stats(listOf(mine), listOf(theirs))))
        assertFalse(CoreJson.foreignQueues(stats(listOf(mine), listOf(mine)), ours))
        assertTrue("their inbox elsewhere", CoreJson.foreignQueues(stats(listOf(mine), listOf(theirs)), ours))
        // A switch under way lists the new queue next to the old one.
        assertTrue(CoreJson.foreignQueues(stats(listOf(mine), listOf(mine, theirs)), ours))
        assertTrue("unreadable counts as foreign", CoreJson.foreignQueues(stats(listOf(mine), listOf("smp://@@")), ours))
        assertTrue("no relays of ours at all", CoreJson.foreignQueues(stats(listOf(mine), listOf(mine)), emptyList()))
        assertFalse("no queues: nothing to judge", CoreJson.foreignQueues(stats(emptyList(), emptyList()), ours))
    }

    private fun info(type: String, s: JSONObject?) =
        CoreReply.Result(type, JSONObject().put("contact", JSONObject().put("contactId", 4)).apply { if (s != null) put("connectionStats_", s) })

    @Test
    fun verdictOfAnInfoReply() {
        assertEquals(false, ForeignRelays.verdict(info("contactInfo", stats(listOf(mine), listOf(mine))), ours, gone))
        assertEquals(true, ForeignRelays.verdict(info("contactInfo", stats(listOf(mine), listOf(theirs))), ours, gone))
        assertEquals(true, ForeignRelays.verdict(info("groupMemberInfo", stats(listOf(theirs), listOf(mine))), ours, gone))
        assertEquals("no active connection", false, ForeignRelays.verdict(info("contactInfo", null), ours, gone))
        assertEquals("gone for good", false, ForeignRelays.verdict(CoreReply.Error(CoreError("errorStore", "contactNotFound", null)), ours, gone))
        assertNull("can't tell: the mark stays", ForeignRelays.verdict(CoreReply.Error(CoreError("errorAgent", "SMP", "AUTH")), ours, gone))
        assertNull(ForeignRelays.verdict(CoreReply.Invalid, ours, gone))
        assertNull(ForeignRelays.verdict(info("cmdOk", stats(listOf(mine), listOf(theirs))), ours, gone))
    }

    @Test
    fun switchEventsBringTheirStats() {
        val body = JSONObject().put("contact", JSONObject().put("contactId", 4))
            .put("switchProgress", JSONObject().put("queueDirection", "snd").put("switchPhase", "started").put("connectionStats", stats(listOf(mine), listOf(mine, theirs))))
        assertEquals(true, ForeignRelays.switchVerdict(body, ours))
        body.getJSONObject("switchProgress").put("switchPhase", "completed").put("connectionStats", stats(listOf(mine), listOf(mine)))
        assertEquals("back on our relay", false, ForeignRelays.switchVerdict(body, ours))
        assertNull(ForeignRelays.switchVerdict(JSONObject(), ours))
    }

    @Test
    fun marksAndRefusals() {
        val contact = ForeignRelays.mark(RepairKey.contact(4))!!
        val member = ForeignRelays.mark(RepairKey.member(3, 7))!!
        assertEquals(4L, contact)
        assertTrue(ForeignRelays.isMemberMark(member))
        assertFalse(ForeignRelays.isMemberMark(contact))
        assertNull(ForeignRelays.mark(RepairKey.group(3)))
        assertNull(ForeignRelays.mark(RepairKey.contact(1L shl 32)))
        val marks = setOf(contact, member)
        assertTrue(ForeignRelays.groupMarked(marks, 3))
        assertFalse(ForeignRelays.groupMarked(marks, 4))
        assertTrue(ForeignRelays.memberMarked(marks, 3, 7))
        assertFalse(ForeignRelays.memberMarked(marks, 3, 8))
        assertEquals(ErrorMapper.FOREIGN_RELAY_CONTACT, ForeignRelays.refusal(marks, 4))
        assertEquals(ErrorMapper.FOREIGN_RELAY_GROUP, ForeignRelays.refusal(marks, GROUP_CHAT_ID_BASE + 3))
        assertNull(ForeignRelays.refusal(marks, 5))
        assertNull(ForeignRelays.refusal(marks, GROUP_CHAT_ID_BASE + 4))
        assertNull(ForeignRelays.refusal(marks, SAVED_MESSAGES_CHAT_ID))
        assertNull(ForeignRelays.refusal(emptySet(), 4))
        assertEquals(SecuredErrorKind, ErrorMapper.foreignRelay(ErrorMapper.FOREIGN_RELAY_CONTACT).kind)
        // Pruning: listed contacts and groups we're still in.
        assertTrue(ForeignRelays.keep(contact, setOf(4), emptySet()))
        assertFalse(ForeignRelays.keep(contact, setOf(5), setOf(3)))
        assertTrue(ForeignRelays.keep(member, emptySet(), setOf(3)))
        assertFalse(ForeignRelays.keep(member, setOf(3), emptySet()))
    }

    @Test
    fun whichEventsAreChecked() {
        val g = JSONObject().put("groupId", 3)
        fun m(id: Long) = JSONObject().put("groupMemberId", id)
        assertEquals(RepairKey.contact(4), ForeignRelays.connectionOf("contactConnected", JSONObject().put("contact", JSONObject().put("contactId", 4))))
        assertEquals(RepairKey.contact(4), ForeignRelays.connectionOf("contactSndReady", JSONObject().put("contact", JSONObject().put("contactId", 4))))
        assertEquals(RepairKey.member(3, 7), ForeignRelays.connectionOf("connectedToGroupMember", JSONObject().put("groupInfo", g).put("member", m(7))))
        assertEquals(RepairKey.member(3, 7), ForeignRelays.connectionOf("joinedGroupMember", JSONObject().put("groupInfo", g).put("member", m(7))))
        assertEquals(RepairKey.member(3, 2), ForeignRelays.connectionOf("userJoinedGroup", JSONObject().put("groupInfo", g).put("hostMember", m(2))))
        assertNull(ForeignRelays.connectionOf("newChatItems", JSONObject()))
        assertNull(ForeignRelays.connectionOf("contactConnected", JSONObject()))
        assertEquals(RepairKey.member(3, 7), ForeignRelays.departedOf("deletedMember", JSONObject().put("groupInfo", g).put("byMember", m(2)).put("deletedMember", m(7))))
        assertEquals(RepairKey.member(3, 7), ForeignRelays.departedOf("leftMember", JSONObject().put("groupInfo", g).put("member", m(7))))
        assertNull(ForeignRelays.departedOf("leftMemberUser", JSONObject().put("groupInfo", g).put("member", m(7))))
    }

    private companion object {
        val SecuredErrorKind = app.encrogram.core.api.SecuredError.Kind.UNAPPROVED_RELAY
    }
}
