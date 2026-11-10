package app.encrogram.core.internal

import app.encrogram.core.internal.FakeCore.Companion.newItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Audit round 2: the receive wake (N-A1) and the cleanup of the core's migration copies (N-A9). */
class EngineHousekeepingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        SecuredLog.enabled = false
    }

    @Test
    fun anArrivedEventHoldsTheWakeAndAnEmptyWaitDoesNot() {
        val holds = AtomicInteger()
        EngineHarness(tmp.newFolder("nobackup"), WakeGuard { holds.incrementAndGet() }).use { h ->
            h.startRunning()
            Thread.sleep(50) // a few empty waits of the fake core's receive loop
            val before = holds.get()
            assertEquals("no event yet, no hold", 0, before)

            h.core.push(newItems(5, 130))
            h.core.push(newItems(5, 131))
            h.awaitIdle()
            assertEquals(2, holds.get())
        }
    }

    @Test
    fun aStartThatReachesRunningDeletesTheMigrationCopies() {
        val dir = tmp.newFolder("nobackup")
        val data = File(dir, "encrogram-secured").apply { mkdirs() } // SimplexSecuredEngine.DATA_DIR
        val copies = listOf("secured_chat.db.bak", "secured_agent.db.bak", "secured_chat.db.exported")
            .map { File(data, it).apply { writeText("old store") } }
        val unrelated = File(data, "secured_chat.db.bak.keep").apply { writeText("not ours to delete") }

        EngineHarness(dir).use { h -> h.startRunning() }

        assertTrue("copies left: ${copies.filter { it.exists() }.map { it.name }}", copies.none { it.exists() })
        assertTrue(unrelated.exists())
    }

    @Test
    fun aFailedStartKeepsTheMigrationCopies() {
        val dir = tmp.newFolder("nobackup")
        val data = File(dir, "encrogram-secured").apply { mkdirs() }
        val copy = File(data, "secured_chat.db.bak").apply { writeText("old store") }

        EngineHarness(dir).use { h ->
            h.core.migrateTag = "errorMigration"
            h.engine.start()
            h.awaitIdle()
        }

        assertTrue("a store that never ran proved nothing: keep its copy", copy.exists())
    }
}
