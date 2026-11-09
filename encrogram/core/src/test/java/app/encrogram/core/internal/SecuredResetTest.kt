package app.encrogram.core.internal

import app.encrogram.core.api.EngineState
import app.encrogram.core.api.SecuredError
import app.encrogram.core.internal.media.CtrlGate
import app.encrogram.core.internal.media.NativeFileException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** "Reset Secured" (b3): the folder wipe, and the order of the steps across the engine, phrase and backup threads. */
class SecuredResetTest {

    private lateinit var root: File
    private val pools = ArrayList<ExecutorService>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("reset").toFile()
    }

    @After
    fun tearDown() {
        pools.forEach { it.shutdownNow() }
        root.deleteRecursively()
    }

    private fun pool(name: String) = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }.also { pools.add(it) }

    private fun write(f: File, text: String = "x") = f.apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun noSecuredFileRemainsAndNothingElseIsTouched() {
        val data = File(root, "encrogram-secured")
        for (name in listOf(
            "secured_chat.db", "secured_agent.db", "secured_chat.db.bak", "secured_agent.db-journal", "files/abc", "media/prep/1",
            "media/view/2", "media/link/3", "media_ledger", "archived_chats", "link_joins", "foreign_relays", "first_run_lockdown",
            "restore_in_progress", "backup/keys.bin", "backup/phrase.bin", "backup/state", "backup/pending", "tmp/xftp/chunk",
            "assets/a", "remote_hosts/r", "background_on", "open_token",
        )) write(File(data, name))
        // Telegram's side of the same app folder, and links from our folder into it.
        val telegram = write(File(root, "telegram/cache4.db"), "keep")
        val telegramDir = telegram.parentFile!!
        Files.createSymbolicLink(File(data, "media/view/dirlink").toPath(), telegramDir.toPath())
        Files.createSymbolicLink(File(data, "filelink").toPath(), telegram.toPath())

        assertTrue(SecuredReset.deleteTree(data))

        assertFalse(data.exists())
        assertTrue("links are deleted, never followed", telegram.isFile)
        assertEquals("keep", telegram.readText())
        assertEquals(listOf("cache4.db"), telegramDir.list()!!.toList())
        assertTrue("nothing there: already done", SecuredReset.deleteTree(data))
    }

    /** Records each step with its thread; the fake engine state ends where the real one does. */
    private open inner class Recorder : SecuredReset.Steps {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ended = CountDownLatch(1)
        @Volatile var state = EngineState.RUNNING
        @Volatile var result: SecuredError? = null
        var resetting = false

        private fun note(step: String) {
            log.add(step + "@" + Thread.currentThread().name)
        }

        override fun begin(): Boolean {
            note("begin")
            if (resetting) return false
            resetting = true
            return true
        }

        override fun stopEngine() {
            note("stop")
            state = EngineState.STARTING
        }

        override fun wipePhrase() = note("phrase")
        override fun wipeBackup() = note("backup")
        override fun wipeFiles(): Boolean {
            note("files")
            return true
        }

        override fun reopen(): SecuredError? {
            note("reopen")
            state = EngineState.NEEDS_PROFILE
            return null
        }

        override fun end(error: SecuredError?) {
            note("end")
            resetting = false
            result = error
            if (error != null) state = EngineState.FAILED
            ended.countDown()
        }
    }

    private val engine by lazy { pool("engine") }
    private val phrase by lazy { pool("phrase") }
    private val worker by lazy { pool("worker") }

    private fun reset(steps: SecuredReset.Steps) =
        SecuredReset({ engine.execute(it) }, { phrase.execute(it) }, { worker.execute(it) }, steps)

    @Test
    fun eachStepRunsOnItsThreadInOrder() {
        val r = Recorder()
        reset(r).run()
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("begin@engine", "stop@engine", "phrase@phrase", "backup@worker", "files@engine", "reopen@engine", "end@engine"),
            r.log.toList(),
        )
        assertNull(r.result)
        assertEquals(EngineState.NEEDS_PROFILE, r.state)
    }

    @Test
    fun workInFlightFinishesFirstAndItsFilesGoToo() {
        val data = File(root, "encrogram-secured")
        val phraseHeld = CountDownLatch(1)
        val workerHeld = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        // "Store 12 words" and an upload still writing when the reset starts.
        phrase.execute { phraseHeld.await(5, TimeUnit.SECONDS); write(File(data, "backup/phrase.bin")); order.add("phrase_write") }
        worker.execute { workerHeld.await(5, TimeUnit.SECONDS); write(File(data, "backup/state")); order.add("worker_write") }
        val r = object : Recorder() {
            override fun wipePhrase() { order.add("phrase_wipe"); super.wipePhrase() }
            override fun wipeBackup() { order.add("backup_wipe"); super.wipeBackup() }
            override fun wipeFiles(): Boolean { super.wipeFiles(); return SecuredReset.deleteTree(data) }
        }
        reset(r).run()
        phraseHeld.countDown()
        workerHeld.countDown()
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))
        assertEquals(4, order.size)
        assertTrue(order.indexOf("phrase_write") < order.indexOf("phrase_wipe"))
        assertTrue(order.indexOf("worker_write") < order.indexOf("backup_wipe"))
        assertFalse(data.exists())
        assertNull(r.result)
    }

    @Test
    fun aResetDuringStartWaitsForItAndForNativeFileCalls() {
        val gate = CtrlGate()
        var generation = 0L
        val startHeld = CountDownLatch(1)
        val r = object : Recorder() {
            override fun stopEngine() {
                // The engine's stop: media generation bumped, then the store closed inside the gate.
                generation++
                gate.close { log.add("store_closed") }
                super.stopEngine()
            }
        }
        r.state = EngineState.STARTING
        // A start in progress on the engine thread, and a media decrypt holding the gate on its own thread.
        engine.execute { startHeld.await(5, TimeUnit.SECONDS); r.log.add("start_done"); r.state = EngineState.RUNNING }
        val media = pool("media")
        val callIn = CountDownLatch(1)
        val callHeld = CountDownLatch(1)
        val genAtCall = generation
        media.execute { gate.use { callIn.countDown(); callHeld.await(5, TimeUnit.SECONDS); r.log.add("native_call_done") } }
        assertTrue(callIn.await(5, TimeUnit.SECONDS))

        reset(r).run()
        startHeld.countDown()
        // The stop now waits for the decrypt in flight.
        Thread.sleep(100)
        assertFalse(r.log.contains("store_closed"))
        callHeld.countDown()
        assertTrue(r.ended.await(5, TimeUnit.SECONDS))

        val log = r.log.toList()
        assertTrue(log.indexOf("start_done") < log.indexOf("begin@engine"))
        assertTrue(log.indexOf("native_call_done") < log.indexOf("store_closed"))
        assertTrue(log.indexOf("store_closed") < log.indexOf("files@engine"))
        assertTrue("queued media work gives up", genAtCall != generation)
        try {
            gate.use { fail("no native call after the close") }
        } catch (e: NativeFileException) {
            assertEquals(CtrlGate.NO_CTRL, e.tag)
        }
        assertEquals(EngineState.NEEDS_PROFILE, r.state)
    }

    @Test
    fun failuresStillEndTheReset() {
        // The stop failed: nothing is deleted.
        val stopFails = object : Recorder() {
            override fun stopEngine() = throw IllegalStateException("boom")
        }
        reset(stopFails).run()
        assertTrue(stopFails.ended.await(5, TimeUnit.SECONDS))
        assertFalse(stopFails.log.any { it.startsWith("files") || it.startsWith("phrase") })
        assertEquals(SecuredError.Kind.ENGINE, stopFails.result!!.kind)

        // The words couldn't be deleted on their thread: the folder wipe still takes them.
        val phraseFails = object : Recorder() {
            override fun wipePhrase() = throw IllegalStateException("boom")
        }
        reset(phraseFails).run()
        assertTrue(phraseFails.ended.await(5, TimeUnit.SECONDS))
        assertNull(phraseFails.result)
        assertEquals(EngineState.NEEDS_PROFILE, phraseFails.state)

        // A file stayed: no reopen over it, and the reset says so.
        val filesStay = object : Recorder() {
            override fun wipeFiles(): Boolean = false
        }
        reset(filesStay).run()
        assertTrue(filesStay.ended.await(5, TimeUnit.SECONDS))
        assertFalse(filesStay.log.any { it.startsWith("reopen") })
        assertEquals(SecuredError.Kind.STORAGE, filesStay.result!!.kind)
        assertEquals(EngineState.FAILED, filesStay.state)

        // The new store didn't open: that error.
        val reopenFails = object : Recorder() {
            override fun reopen(): SecuredError = ErrorMapper.keyLost()
        }
        reset(reopenFails).run()
        assertTrue(reopenFails.ended.await(5, TimeUnit.SECONDS))
        assertEquals(SecuredError.Kind.KEY_LOST, reopenFails.result!!.kind)
    }

    @Test
    fun aSecondResetWhileOneRunsDoesNothing() {
        val r = Recorder()
        r.resetting = true
        reset(r).run()
        engine.submit {}.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("begin@engine"), r.log.toList())
        assertEquals(1L, r.ended.count)
    }

    @Test
    fun keyLostIsItsOwnKind() {
        val e = ErrorMapper.keyLost()
        assertEquals(SecuredError.Kind.KEY_LOST, e.kind)
        assertTrue(e.message.contains("12 words"))
        assertFalse(e.message.contains("backup", ignoreCase = true))
    }
}
