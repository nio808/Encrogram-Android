package app.encrogram.core.backup

import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.backup.RestoreRepair.Action
import app.encrogram.core.backup.RestoreRepair.ConnStats
import app.encrogram.core.internal.CoreGroup
import app.encrogram.core.internal.CoreMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRepairTest {

    private val fresh = BackupStore.Pending()
    private val proven = BackupStore.Pending(proven = true, forced = true)
    private val switched = BackupStore.Pending(proven = true, switched = true, forced = true)

    private fun stats(state: String, supported: Boolean = true, switching: Boolean = false) = ConnStats(state, supported, switching)

    @Test
    fun syncWhenTheCoreSaysAllowedOrRequired() {
        for (p in listOf(fresh, proven, switched)) {
            assertEquals(Action.SYNC, RestoreRepair.decide(stats("required"), true, p))
            assertEquals(Action.SYNC, RestoreRepair.decide(stats("allowed"), true, p))
            // Even while not ready or switching: the ratchet comes first.
            assertEquals(Action.SYNC, RestoreRepair.decide(stats("required", switching = true), false, p))
        }
    }

    @Test
    fun dropWhenTheConnectionCantSync() {
        assertEquals(Action.DROP, RestoreRepair.decide(stats("required", supported = false), true, fresh))
        assertEquals(Action.DROP, RestoreRepair.decide(stats("allowed", supported = false), true, proven))
    }

    @Test
    fun waitWhileOurSyncRuns() {
        for (p in listOf(proven, RestoreRepair.syncAccepted(fresh, nowMs = 1_000))) {
            assertEquals(Action.WAIT, RestoreRepair.decide(stats("started"), true, p, nowMs = 2_000))
            assertEquals(Action.WAIT, RestoreRepair.decide(stats("agreed"), true, p, nowMs = 2_000))
        }
        for (p in listOf(fresh, proven)) assertEquals(Action.WAIT, RestoreRepair.decide(stats("something-new"), true, p))
    }

    @Test
    fun aRestoredSyncStateIsForcedAtTheFirstCheck() {
        // R6.1: the backup itself held `started`/`agreed`. Plain /_sync is refused there and decrypt errors raise no
        // event, so only a forced sync unblocks it; it is sent at the first check, ready or not.
        for (state in listOf("started", "agreed")) {
            assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats(state), true, fresh))
            assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats(state), false, fresh))
            assertEquals(Action.WAIT, RestoreRepair.decide(stats(state, supported = false), true, fresh))
        }
        // Seeing the stale state before our forced sync is not a sync under way.
        assertEquals(fresh, RestoreRepair.observe(fresh, "started"))
        assertEquals(fresh, RestoreRepair.observe(fresh, "agreed"))
    }

    @Test
    fun stuckForHoursForcesOnceMore() {
        val t0 = 1_700_000_000_000L
        val forced = RestoreRepair.syncAccepted(fresh, nowMs = t0)
        val later = t0 + RestoreRepair.REFORCE_AFTER_MS
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("started"), true, forced, nowMs = later - 1))
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("started"), true, forced, nowMs = later))
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("agreed"), true, forced, nowMs = later))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("started", supported = false), true, forced, nowMs = later))
        // The second force is the last one.
        val again = RestoreRepair.syncAccepted(forced, nowMs = later)
        assertTrue(again.reforced)
        assertEquals(later, again.forcedAtMs)
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("started"), true, again, nowMs = later * 2))
        // A list written before forcedAtMs existed never re-forces.
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("started"), true, proven, nowMs = later * 2))
    }

    @Test
    fun oneForcedSyncRightAfterRestore() {
        // `ok` but not proven: the one forced sync (finding 1).
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("ok"), true, fresh))
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("ok"), false, fresh))
        // Never twice, never on top of a running sync, never on an agent that can't sync.
        val forced = RestoreRepair.syncAccepted(fresh, nowMs = 5)
        assertTrue(forced.forced && forced.syncing)
        assertFalse(forced.reforced)
        assertEquals(5L, forced.forcedAtMs)
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok"), true, forced.copy(syncing = false)))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok"), true, fresh.copy(syncing = true)))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok", supported = false), true, fresh))
        // R6.1: any accepted sync of a restored connection, plain ones too, counts as the forced one.
        val plain = RestoreRepair.syncAccepted(fresh.copy(proven = true), nowMs = 9)
        assertTrue(plain.forced && plain.syncing)
    }

    @Test
    fun anIncomingMessageAloneIsNoProof() {
        // The 8 Nov failure: a decrypted message only proves the contact-to-us direction.
        val afterMessage = RestoreRepair.received(fresh)
        assertEquals(fresh, afterMessage)
        assertFalse(afterMessage.proven)
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("ok"), true, afterMessage))
        // Seeing `ok` without a sync under way proves nothing either.
        assertEquals(fresh, RestoreRepair.observe(fresh, "ok"))
    }

    @Test
    fun aCompletedSyncIsProof() {
        var p = RestoreRepair.syncAccepted(fresh, nowMs = 1)      // reply: started
        assertFalse(p.proven)
        p = RestoreRepair.observe(p, "started")
        assertFalse(p.proven)
        p = RestoreRepair.observe(p, "agreed")
        assertFalse("agreed alone is not proof", p.proven)
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("agreed"), true, p))
        p = RestoreRepair.observe(p, "ok")
        assertTrue(p.proven)
        assertFalse(p.syncing)
        assertEquals(Action.SWITCH, RestoreRepair.decide(stats("ok"), true, p))
    }

    @Test
    fun aSyncByTheContactCountsOnlyAfterOurs() {
        // Before our forced sync, `agreed` may be the backup's stale state (R6.1): no proof.
        assertFalse(RestoreRepair.observe(RestoreRepair.observe(fresh, "agreed"), "ok").proven)
        // After it, their sync (straight to agreed via newRatchetKey) finishing in ok is proof.
        val ours = RestoreRepair.syncAccepted(fresh, nowMs = 1).copy(syncing = false)
        assertTrue(RestoreRepair.observe(RestoreRepair.observe(ours, "agreed"), "ok").proven)
    }

    @Test
    fun aBrokenSyncCancelsTheProof() {
        var p = RestoreRepair.observe(RestoreRepair.syncAccepted(fresh, nowMs = 1), "agreed")
        p = RestoreRepair.observe(p, "required")
        assertFalse(p.syncing)
        // Back to ok by a decrypted message alone: still not proven.
        p = RestoreRepair.observe(p, "ok")
        assertFalse(p.proven)
        // The plain sync for `required` runs and completes: proven.
        p = RestoreRepair.observe(RestoreRepair.observe(RestoreRepair.syncAccepted(p, nowMs = 2), "agreed"), "ok")
        assertTrue(p.proven)
    }

    @Test
    fun onlyReceiptsForMessagesSentAfterTheRestoreAreProof() {
        val since = 500L
        assertFalse(RestoreRepair.delivered(fresh, 499, since).proven)
        assertFalse(RestoreRepair.delivered(fresh, 500, since).proven)
        assertTrue(RestoreRepair.delivered(fresh, 501, since).proven)
        // Unknown starting point (older list): no receipt counts.
        assertFalse(RestoreRepair.delivered(fresh, 1_000_000, Long.MAX_VALUE).proven)
    }

    @Test
    fun switchOnlyOnceProven() {
        assertEquals(Action.SWITCH, RestoreRepair.decide(stats("ok"), true, proven))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok"), false, proven))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok", switching = true), true, proven))
    }

    @Test
    fun switchedContactsLeaveWhenTheSwitchIsOver() {
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok", switching = true), true, switched))
        assertEquals(Action.DONE, RestoreRepair.decide(stats("ok"), true, switched))
    }

    @Test
    fun repairKeysForContactsAndMembers() {
        val c = RepairKey.contact(12)
        val m = RepairKey.member(3, 12)
        assertFalse(c.isMember)
        assertTrue(m.isMember)
        assertEquals("12", c.encode())
        assertEquals("#3:12", m.encode())
        // Same number, different connection.
        assertFalse(c == m)
        assertEquals(c, RepairKey.decode("12"))
        assertEquals(m, RepairKey.decode("#3:12"))
        assertEquals(m.hashCode(), RepairKey.decode("#3:12").hashCode())
        for (bad in listOf("", "x", "0", "-4", "#3", "#0:2", "#3:0", "#a:2", "#3:b")) assertNull(bad, RepairKey.decode(bad))
        // Never a number in logs.
        assertEquals("member", m.toString())
        assertEquals("contact", c.toString())
    }

    // ---- groups after a restore: a placeholder per group, expanded once the chat runs (G7) ----

    @Test
    fun groupPlaceholderKeys() {
        val g = RepairKey.group(3)
        assertTrue(g.isGroupPlaceholder)
        assertFalse(g.isMember)
        assertFalse(RepairKey.member(3, 12).isGroupPlaceholder)
        assertFalse(RepairKey.contact(3).isGroupPlaceholder)
        assertEquals("#3:*", g.encode())
        assertEquals(g, RepairKey.decode("#3:*"))
        assertEquals(g.hashCode(), RepairKey.decode("#3:*").hashCode())
        // Never the same key as a member or a contact with the same numbers.
        assertFalse(g == RepairKey.member(3, 12))
        assertFalse(g == RepairKey.contact(3))
        for (bad in listOf("#3:", "#0:*", "#-3:*", "#:*", "#3:**", "#3:*1", "*", "#3*")) assertNull(bad, RepairKey.decode(bad))
        assertEquals("group", g.toString())
    }

    @Test
    fun placeholdersOnlyForGroupsWeAreIn() {
        fun g(id: Long, status: String) = CoreGroup(id, "G$id", MemberRole.MEMBER, status, 3)
        val groups = listOf(
            g(1, "creator"), g(2, "connected"), g(3, "complete"), g(4, "invited"), g(5, "left"), g(6, "removed"),
            g(7, "rejected"), g(8, "deleted"), g(9, "accepted"),
        )
        // Invited only, left or removed: no member connections of ours. Still joining (accepted): listed.
        assertEquals(listOf(1L, 2L, 3L, 9L).map { RepairKey.group(it) }, RestoreRepair.groupPlaceholders(groups))
        assertTrue(RestoreRepair.groupPlaceholders(emptyList()).isEmpty())
    }

    @Test
    fun aPlaceholderExpandsIntoItsWorkingMemberConnections() {
        fun m(id: Long, status: MemberStatus, active: Boolean, isMe: Boolean = false) =
            CoreMember(id, "n$id", MemberRole.MEMBER, status, isMe, null, active = active)
        val members = listOf(
            m(1, MemberStatus.CONNECTED, active = true, isMe = true),
            m(5, MemberStatus.CONNECTED, active = true),
            m(6, MemberStatus.CONNECTING, active = false),
            m(7, MemberStatus.CONNECTED, active = true),
            m(8, MemberStatus.LEFT, active = false),
        )
        val keys = RestoreRepair.memberKeys(3, members)
        assertEquals(listOf(RepairKey.member(3, 5), RepairKey.member(3, 7)), keys)

        val contact = RepairKey.contact(12)
        val other = RepairKey.group(4)
        val known = RepairKey.member(3, 7)
        val map = linkedMapOf(contact to proven, RepairKey.group(3) to fresh, other to fresh, known to switched)
        val out = RestoreRepair.expand(map, RepairKey.group(3), keys)
        // The placeholder is gone; its members are listed fresh, one already listed keeps its state; the rest stays.
        assertEquals(listOf(contact, other, known, RepairKey.member(3, 5)), out.keys.toList())
        assertEquals(proven, out[contact])
        assertEquals(switched, out[known])
        assertEquals(fresh, out[RepairKey.member(3, 5)])
        assertEquals(fresh, out[other])
        // A group with nobody else connected: just the placeholder goes.
        assertEquals(listOf(contact), RestoreRepair.expand(linkedMapOf(contact to fresh, RepairKey.group(3) to fresh), RepairKey.group(3), emptyList()).keys.toList())
        // Keys of another group never land under this one.
        assertEquals(setOf(contact), RestoreRepair.expand(linkedMapOf(contact to fresh, RepairKey.group(3) to fresh), RepairKey.group(3),
            listOf(RepairKey.member(9, 5), RepairKey.contact(5))).keys)
    }

    @Test
    fun noConnectionNoWork() {
        assertEquals(Action.DROP, RestoreRepair.decide(null, false, fresh))
    }

    @Test
    fun plainSyncOnEvents() {
        assertTrue(RestoreRepair.syncOnEvent("required", restoredContact = false))
        assertTrue(RestoreRepair.syncOnEvent("required", restoredContact = true))
        assertTrue(RestoreRepair.syncOnEvent("allowed", restoredContact = true))
        assertFalse(RestoreRepair.syncOnEvent("allowed", restoredContact = false))
        for (s in listOf("ok", "started", "agreed")) assertFalse(RestoreRepair.syncOnEvent(s, true))
    }

    @Test
    fun syncAllowedMirrorsTheAgent() {
        assertTrue(RestoreRepair.syncAllowed(stats("allowed")))
        assertTrue(RestoreRepair.syncAllowed(stats("required")))
        assertFalse(RestoreRepair.syncAllowed(stats("ok")))
        assertFalse(RestoreRepair.syncAllowed(stats("started")))
        assertFalse(RestoreRepair.syncAllowed(stats("agreed")))
        assertFalse(RestoreRepair.syncAllowed(stats("required", supported = false)))
    }

    // ---- restore commit ordering (finding 8) ----

    @Test
    fun markerIsClearedLastAndOnlyAfterEverythingElse() {
        val order = ArrayList<String>()
        val failed = RestoreRepair.commitRestore(
            saveRepairList = { order.add("list"); true },
            storeKeys = { order.add("keys"); true },
            clearMarker = { order.add("marker"); true },
        )
        assertNull(failed)
        assertEquals(listOf("list", "keys", "marker"), order)
    }

    @Test
    fun aFailedStepLeavesTheMarker() {
        for (failAt in listOf("list", "keys")) {
            val order = ArrayList<String>()
            val failed = RestoreRepair.commitRestore(
                saveRepairList = { order.add("list"); failAt != "list" },
                storeKeys = { order.add("keys"); failAt != "keys" },
                clearMarker = { order.add("marker"); true },
            )
            assertEquals(if (failAt == "list") "repair_list" else "keys", failed)
            assertFalse("the marker must stay so the next start wipes the unfinished restore", "marker" in order)
        }
    }

    @Test
    fun aMarkerThatWontGoFailsTheRestore() {
        assertEquals("marker", RestoreRepair.commitRestore({ true }, { true }, { false }))
    }
}
