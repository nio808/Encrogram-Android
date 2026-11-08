package app.encrogram.core.backup

import app.encrogram.core.backup.RestoreRepair.Action
import app.encrogram.core.backup.RestoreRepair.ConnStats
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
    fun waitWhileASyncRuns() {
        for (p in listOf(fresh, proven)) {
            assertEquals(Action.WAIT, RestoreRepair.decide(stats("started"), true, p))
            assertEquals(Action.WAIT, RestoreRepair.decide(stats("agreed"), true, p))
            assertEquals(Action.WAIT, RestoreRepair.decide(stats("something-new"), true, p))
        }
    }

    @Test
    fun oneForcedSyncRightAfterRestore() {
        // `ok` but not proven: the one forced sync (finding 1).
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("ok"), true, fresh))
        assertEquals(Action.FORCE_SYNC, RestoreRepair.decide(stats("ok"), false, fresh))
        // Never twice, never on top of a running sync, never on an agent that can't sync.
        val forced = RestoreRepair.syncAccepted(fresh, forced = true)
        assertTrue(forced.forced && forced.syncing)
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok"), true, forced.copy(syncing = false)))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok"), true, fresh.copy(syncing = true)))
        assertEquals(Action.WAIT, RestoreRepair.decide(stats("ok", supported = false), true, fresh))
        // A plain sync that was accepted doesn't count as the forced one, but it is under way.
        val plain = RestoreRepair.syncAccepted(fresh, forced = false)
        assertFalse(plain.forced)
        assertTrue(plain.syncing)
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
        var p = RestoreRepair.syncAccepted(fresh, forced = true)      // reply: started
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
    fun aSyncStartedByTheContactAlsoCounts() {
        // Their forced sync: we go straight to agreed (newRatchetKey), then ok when their next message decrypts.
        val p = RestoreRepair.observe(RestoreRepair.observe(fresh, "agreed"), "ok")
        assertTrue(p.proven)
    }

    @Test
    fun aBrokenSyncCancelsTheProof() {
        var p = RestoreRepair.observe(RestoreRepair.syncAccepted(fresh, forced = true), "agreed")
        p = RestoreRepair.observe(p, "required")
        assertFalse(p.syncing)
        // Back to ok by a decrypted message alone: still not proven.
        p = RestoreRepair.observe(p, "ok")
        assertFalse(p.proven)
        // The plain sync for `required` runs and completes: proven.
        p = RestoreRepair.observe(RestoreRepair.observe(RestoreRepair.syncAccepted(p, forced = false), "agreed"), "ok")
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
