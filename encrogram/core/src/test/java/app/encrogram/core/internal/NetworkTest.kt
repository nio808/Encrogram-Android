package app.encrogram.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The phone's network as told to the core: what we send, and when (offline held back, coming back at once). */
class NetworkTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    @Test
    fun capabilitiesBecomeTheAgentsNetworkInfo() {
        fun of(internet: Boolean = true, portal: Boolean = false, eth: Boolean = false, wifi: Boolean = false, cell: Boolean = false) =
            NetInfo.of(internet, portal, eth, wifi, cell, handle = 42)
        assertEquals(NetInfo("wifi", true, 42), of(wifi = true))
        assertEquals(NetInfo("cellular", true, 42), of(cell = true))
        assertEquals(NetInfo("ethernet", true, 42), of(eth = true, wifi = true))
        assertEquals(NetInfo("other", true, 42), of())
        // Any default network keeps the core going, validated or not: Android's check can be blocked on a network
        // that works (finding 1). Screens say "Waiting for network" only without internet or behind a captive portal.
        for (info in listOf(of(wifi = true), of(wifi = true, portal = true), of(wifi = true, internet = false))) assertTrue(info.coreOnline)
        assertTrue(of(wifi = true).usable)
        assertFalse(of(wifi = true, portal = true).usable)
        assertFalse(of(wifi = true, internet = false).usable)
        // Only "no network at all" is offline for the core.
        assertFalse(NetInfo.NONE.coreOnline)
        assertFalse(NetInfo.NONE.usable)
        assertTrue("unknown never holds the core back", NetInfo.UNKNOWN.coreOnline && NetInfo.UNKNOWN.usable)
        // Another network of the same type is a different network.
        assertFalse(NetInfo("wifi", true, 1) == NetInfo("wifi", true, 2))
    }

    @Test
    fun theNetworkCommands() {
        for (info in listOf(NetInfo("wifi", true), NetInfo.NONE, NetInfo("cellular", false))) {
            val cmd = Commands.networkInfo(info)
            assertTrue(cmd, cmd.startsWith("/_network info {"))
            // UserNetworkInfo JSON (simplexmq Agent/Client.hs:2899-2901): exactly these two fields.
            val json = JSONObject(cmd.removePrefix("/_network info "))
            assertEquals(setOf("networkType", "online"), json.keySet())
            assertEquals(info.type, json.getString("networkType"))
            assertEquals(info.coreOnline, json.getBoolean("online"))
        }
        assertEquals("/reconnect", Commands.RECONNECT_ALL)
    }

    /** A clock-less scheduler: tasks run only when [fire] is called. */
    private class FakeScheduler {
        val tasks = ArrayList<Pair<Long, Runnable>>()
        fun schedule(delay: Long, task: Runnable) { tasks.add(delay to task) }
        fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
        fun fire() {
            val due = tasks.toList()
            tasks.clear()
            due.forEach { it.second.run() }
        }
    }

    @Test
    fun offlineIsHeldBackAndOnlineIsAtOnce() {
        val s = FakeScheduler()
        val reported = ArrayList<NetInfo>()
        val d = NetworkDebounce(s::schedule, s::cancel) { reported.add(it) }
        val wifi = NetInfo("wifi", true, 1)
        val cell = NetInfo("cellular", true, 2)

        d.update(wifi)
        assertEquals(listOf(wifi), reported)
        d.update(wifi)
        assertEquals("no repeat for the same network", listOf(wifi), reported)

        // Wi-Fi lost, mobile data takes over within the delay: never reported as offline.
        d.update(NetInfo.NONE)
        assertEquals(1, s.tasks.size)
        assertEquals(NetworkDebounce.OFFLINE_DELAY_MS, s.tasks[0].first)
        assertEquals(listOf(wifi), reported)
        d.update(cell)
        assertTrue("the held offline report is dropped", s.tasks.isEmpty())
        assertEquals(listOf(wifi, cell), reported)

        // Really offline: reported once the delay is over.
        d.update(NetInfo.NONE)
        s.fire()
        assertEquals(listOf(wifi, cell, NetInfo.NONE), reported)
        d.update(NetInfo.NONE)
        assertTrue(s.tasks.isEmpty())
        // Back: at once.
        d.update(wifi)
        assertEquals(listOf(wifi, cell, NetInfo.NONE, wifi), reported)

        // After reset (a new store), the same network is reported again.
        d.reset()
        d.update(wifi)
        assertEquals(5, reported.size)
        // A held offline report doesn't survive a reset.
        d.update(NetInfo.NONE)
        d.reset()
        assertTrue(s.tasks.isEmpty())
        // A captive portal is held back like an outage; the next working network replaces it unseen.
        d.update(wifi)
        val before = reported.size
        d.update(NetInfo("wifi", false, 3))
        assertEquals(before, reported.size)
        d.update(NetInfo("wifi", true, 3))
        assertTrue(s.tasks.isEmpty())
        assertEquals(NetInfo("wifi", true, 3), reported.last())
    }
}
