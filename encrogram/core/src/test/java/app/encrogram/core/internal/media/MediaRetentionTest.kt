package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.MediaStorage
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.PreparedMedia
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.ErrorMapper
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.prep.Preparer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Executor

/** "Keep media" and "clear media" (A7): the age rule, every file that must stay, what the user sees after, storage. */
class MediaRetentionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var data: File
    private lateinit var files: File
    private lateinit var media: File
    private lateinit var native: FakeNativeFiles
    private lateinit var vault: MediaVault
    private lateinit var ledger: MediaLedger
    private lateinit var host: FakeMediaHost
    private lateinit var transport: FakeTransport
    private lateinit var coordinator: MediaCoordinator

    /** The fake clock: the host's and the vault's. */
    private var now = NOW

    /** The fake phone's elapsedRealtime: moves with [tick]; a wall clock set by hand ([setWall]) leaves it. */
    private var elapsed = HOUR

    private var queueRead = false
    private val readQueue = ArrayDeque<Runnable>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { logs += it }
        data = tmp.newFolder("encrogram-secured")
        build()
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
    }

    /** A coordinator over [data]; a second call is a process restart (same folder, new objects). */
    private fun build(config: MediaPolicyConfig = MediaPolicy.DEFAULT) {
        files = File(data, "files")
        media = File(data, "media")
        native = FakeNativeFiles()
        vault = MediaVault(files, media, native, clock = { now })
        ledger = MediaLedger(File(data, "media_ledger"))
        host = FakeMediaHost(now).also { it.elapsed = elapsed }
        transport = FakeTransport()
        coordinator = MediaCoordinator(
            host, transport, vault, ledger,
            Preparer { Prepared(MediaKind.IMAGE, "IMG_abcdefgh.jpg", "image/jpeg", MediaSamples.PNG_URL, null, false, vault.sealBytes(PLAIN)) },
            DescriptionSamples.ourServers,
            Executor { it.run() },
            Executor { if (queueRead) readQueue.add(it) else it.run() },
            config,
        )
        host.coordinator = coordinator
    }

    /** Time passes: the wall clock and elapsedRealtime move together. */
    private fun tick(ms: Long) {
        now += ms
        host.now = now
        elapsed += ms
        host.elapsed = elapsed
    }

    /** The wall clock is set to [wallMs] (by hand, or a phone booting with it unset); elapsedRealtime doesn't move. */
    private fun setWall(wallMs: Long) {
        now = wallMs
        host.now = now
    }

    /** The phone restarts after [offMs] switched off: elapsedRealtime starts again; the wall clock is [wallMs]. */
    private fun reboot(offMs: Long, wallMs: Long = now + offMs) {
        elapsed = MIN
        host.elapsed = elapsed
        setWall(wallMs)
    }

    // ---- samples ------------------------------------------------------------------------------------------------

    /** A received file, complete, stored under [name] and last written [ageMs] ago; registered as the store's item. */
    private fun received(itemId: Long, name: String, ageMs: Long, kind: MediaKind = MediaKind.IMAGE, plain: ByteArray = PLAIN, chat: Long = CHAT): CoreItem {
        val key = FakeNativeFiles.newKey()
        files.mkdirs()
        val f = File(files, name)
        f.writeBytes(FakeNativeFiles.seal(plain, key))
        assertTrue(f.setLastModified(now - ageMs))
        val item = CoreItem(
            chatId = chat, itemId = itemId, outgoing = false, isMessage = true, text = "", timeMs = now - ageMs,
            statusTag = "rcvRead", status = MessageStatus.RECEIVED, unread = false,
            msgContentType = if (kind == MediaKind.VIDEO) "video" else "image", shared = true, sharedMsgId = "bXNn$itemId",
            file = CoreFile(itemId + 1000, kind, "IMG_x.jpg", plain.size.toLong(), "rcvComplete", "xftp", name, key),
            preview = MediaSamples.PNG_URL, durationSeconds = if (kind == MediaKind.VIDEO) 3 else null,
        )
        host.store[chat to itemId] = item
        return item
    }

    private fun age(name: String, ageMs: Long) = assertTrue(File(files, name).setLastModified(now - ageMs))

    private fun size(name: String): Long = File(files, name).length()

    private fun present(name: String) = size(name) > 0

    private fun stateOf(item: CoreItem) = host.toMessage(item).media!!

    private class Box<T> : SecuredCallback<T> {
        val results: MutableList<SecuredResult<T>> = Collections.synchronizedList(ArrayList())
        override fun onResult(result: SecuredResult<T>) {
            results += result
        }
        val value: T get() = (results.single() as SecuredResult.Ok<T>).value
        val error: SecuredError get() = (results.single() as SecuredResult.Err).error
        val isOk: Boolean get() = results.single() is SecuredResult.Ok
    }

    private fun prepareOne(): PreparedMedia = Box<PreparedMedia>().also { coordinator.prepare(MediaInput.Picked("content://picker/1"), it) }.value

    private fun sendOne(): CoreItem {
        val box = Box<SecuredMessage>()
        coordinator.send(CHAT, ChatRef.contact(CHAT), prepareOne(), "", null, box)
        assertTrue(box.isOk)
        return host.added.last()
    }

    private fun event(type: String, item: CoreItem): JSONObject {
        val f = item.file!!
        val json = MediaSamples.item(
            item.itemId, "directSnd", MediaSamples.content("image", image = MediaSamples.PNG_URL),
            MediaSamples.file(f.fileId, f.name, f.sizeBytes, MediaSamples.status(type), "xftp"), "sndSent",
        )
        return JSONObject(MediaSamples.event(eventTypeFor(type), json, "chatItem"))
    }

    private fun eventTypeFor(status: String) = when (status) {
        "sndComplete" -> "sndFileCompleteXFTP"
        "sndError" -> "sndFileError"
        else -> "sndFileCancelled"
    }

    private fun setDays(days: Int?) = assertNull(coordinator.setRetentionDays(days))

    // ---- the setting --------------------------------------------------------------------------------------------

    @Test
    fun theSettingIsForeverByDefaultAndSurvivesARestart() {
        assertNull(coordinator.retentionDays())
        assertFalse(File(data, MediaCoordinator.RETENTION_FILE).exists())

        setDays(7)
        assertEquals(7, coordinator.retentionDays())
        assertEquals("7", File(data, MediaCoordinator.RETENTION_FILE).readText())
        build()
        assertEquals(7, coordinator.retentionDays())

        setDays(null)
        assertNull(coordinator.retentionDays())
        assertFalse(File(data, MediaCoordinator.RETENTION_FILE).exists())
        build()
        assertNull(coordinator.retentionDays())
    }

    @Test
    fun badDaysAreRefusedAndABadFileMeansForever() {
        for (bad in listOf(0, -1, MediaRetention.MAX_DAYS + 1)) {
            val e = coordinator.setRetentionDays(bad)
            assertNotNull(e)
            assertEquals(SecuredError.Kind.UNKNOWN, e!!.kind)
        }
        assertNull(coordinator.retentionDays())

        for (junk in listOf("x", "0", "-3", "99999", "")) {
            File(data, MediaCoordinator.RETENTION_FILE).writeText(junk)
            build()
            assertNull(junk, coordinator.retentionDays())
        }
    }

    @Test
    fun aSettingThatCantBeSavedSaysSoAndChangesNothing() {
        val blocked = File(tmp.root, "blocked")
        blocked.writeText("a file where the folder should be")
        val setting = RetentionSetting(File(blocked, "media_retention"))
        assertFalse(setting.set(3))
        assertNull(setting.days())
    }

    @Test
    fun aStoreReplacedReadsTheSettingAgain() {
        setDays(30)
        File(data, MediaCoordinator.RETENTION_FILE).delete()   // a reset deletes the whole folder
        coordinator.onStoreReplaced()
        assertNull(coordinator.retentionDays())
    }

    // ---- the age rule -------------------------------------------------------------------------------------------

    @Test
    fun onlyFilesOlderThanTheLimitGo() {
        setDays(3)
        val old = received(1, "old.bin", 3 * DAY + MIN)
        val edge = received(2, "edge.bin", 3 * DAY)
        val young = received(3, "young.bin", 3 * DAY - MIN)
        val fresh = received(4, "fresh.bin", MIN)

        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("old.bin"))
        assertTrue(File(files, "old.bin").exists())   // emptied, its name kept
        assertTrue(present("edge.bin") && present("young.bin") && present("fresh.bin"))
        assertEquals(MediaFailure.NOT_ON_DEVICE, stateOf(old).failure)
        assertEquals(MediaState.READY, stateOf(edge).state)
        assertEquals(MediaState.READY, stateOf(young).state)
        assertEquals(MediaState.READY, stateOf(fresh).state)

        // A day later the next pass takes what has aged; running it again changes nothing.
        tick(DAY)
        assertEquals(2, coordinator.applyRetention())
        assertFalse(present("edge.bin") || present("young.bin"))
        assertTrue(present("fresh.bin"))
        assertEquals(0, coordinator.applyRetention())
    }

    @Test
    fun foreverRemovesNothing() {
        received(1, "ancient.bin", 3650 * DAY)
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("ancient.bin"))
    }

    @Test
    fun aFileDatedInTheFutureStays() {
        setDays(1)
        received(1, "future.bin", -DAY)
        assertEquals(0, coordinator.applyRetention())
        assertEquals(0, coordinator.clearAll())
        assertTrue(present("future.bin"))
    }

    // ---- the clock retention trusts (review of 8c89dfd6, finding 2) ----------------------------------------------

    @Test
    fun aWallClockSetAheadAgesNothing() {
        setDays(7)
        received(1, "old.bin", 8 * DAY)
        received(2, "young.bin", 3 * DAY)
        assertEquals(1, coordinator.applyRetention())

        // Set a month ahead by hand: only an hour really passed, so nothing is older than it was.
        setWall(now + 30 * DAY)
        tick(HOUR)
        received(3, "after.bin", 0)   // dated by the clock set ahead
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("young.bin") && present("after.bin"))
        // Kept across a restart of the app (the trusted time is on disk).
        build()
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("young.bin"))

        // Five real days later young.bin is eight days old and goes; the file dated ahead stays until its own time.
        tick(5 * DAY)
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("young.bin"))
        assertTrue(present("after.bin"))
    }

    @Test
    fun aWallClockSetBackRemovesNothingAndTheTrustedTimeNeverGoesBack() {
        setDays(7)
        received(1, "aging.bin", 6 * DAY)
        assertEquals(0, coordinator.applyRetention())

        // Set a year back, then two real days pass: the pass counts from the earlier clock, so it removes less, never more.
        setWall(now - 365 * DAY)
        tick(2 * DAY)
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("aging.bin"))
        // The trusted time kept counting, and didn't follow the wall clock back.
        assertEquals(RetentionClock.State(NOW + 2 * DAY, HOUR + 2 * DAY), RetentionClock.parse(clockFile().readText()))

        // Set right again: aging.bin is eight days old by real time and goes.
        setWall(now + 365 * DAY)
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("aging.bin"))
    }

    @Test
    fun aPhoneThatBootsAt1970DatesNothingDecadesBack() {
        setDays(7)
        received(1, "aging.bin", 6 * DAY + 12 * HOUR)
        assertEquals(0, coordinator.applyRetention())

        // Restarted after a day off with its clock unset: what is written now is dated 1970, and no pass runs on it.
        reboot(DAY, wallMs = 86_400_000L)
        received(2, "booted.bin", 0)
        assertTrue(File(files, "booted.bin").lastModified() < RetentionClock.FLOOR_MS)
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("booted.bin") && present("aging.bin"))

        // Network time sets it right. aging.bin is seven and a half days old and goes; booted.bin is dated now, first
        // seen, and kept.
        setWall(NOW + DAY)
        tick(MIN)
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("aging.bin"))
        assertTrue(present("booted.bin"))
        assertEquals(now, File(files, "booted.bin").lastModified())
        assertTrue(logs.contains("MEDIA_WIPED kind=redated n=1"))

        // It goes only once it is really older than the setting, counted from then.
        tick(6 * DAY)
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("booted.bin"))
        tick(2 * DAY)
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("booted.bin"))
    }

    @Test
    fun theTrustedTimeFollowsRealTime() {
        val start = RetentionClock.step(null, NOW, HOUR)
        assertEquals(NOW, start.nowMs)
        val s = start.state!!
        // Same boot: exactly the elapsed time, whatever the wall clock says.
        val ahead = RetentionClock.step(s, NOW + 30 * DAY, HOUR + DAY)
        assertEquals(NOW + DAY, ahead.nowMs)
        assertEquals(RetentionClock.State(NOW + DAY, HOUR + DAY), ahead.state)
        val back = RetentionClock.step(s, NOW - 365 * DAY, HOUR + DAY)
        assertEquals(NOW - 365 * DAY, back.nowMs)
        assertEquals(NOW + DAY, back.state!!.trustedMs)
        // A restart (elapsedRealtime below the stored one): the wall clock's step, at most two days, at least the uptime.
        assertEquals(NOW + 9 * HOUR, RetentionClock.step(s, NOW + 9 * HOUR, MIN).nowMs)
        assertEquals(NOW + 2 * DAY, RetentionClock.step(s, NOW + 30 * DAY, MIN).nowMs)
        assertEquals(NOW + 3 * DAY, RetentionClock.step(s.copy(elapsedMs = 9 * DAY), NOW + 30 * DAY, 3 * DAY).nowMs)
        // An unset clock: nothing removed, nothing stored.
        val unset = RetentionClock.step(s, 86_400_000L, HOUR + DAY)
        assertNull(unset.nowMs)
        assertEquals(s, unset.state)
        assertNull(RetentionClock.step(null, 0L, HOUR).state)
        // A state file that can't be read starts again.
        for (junk in listOf("", "x", "1 2", "${NOW}", "$NOW -1", "$NOW 1 2")) assertNull(junk, RetentionClock.parse(junk))
        assertEquals(s, RetentionClock.parse("$NOW $HOUR\n"))
    }

    private fun clockFile() = File(data, MediaCoordinator.CLOCK_FILE)

    @Test
    fun settingTheLimitAppliesItAtOnceWhileRunning() {
        received(1, "old.bin", 10 * DAY)
        host.running = false
        setDays(7)
        assertTrue(present("old.bin"))   // not running: the pass waits for the start

        host.running = true
        setDays(7)
        assertFalse(present("old.bin"))
    }

    @Test
    fun theSelectionRuleOnItsOwn() {
        val list = listOf(
            StoredFile("a", 10, NOW - 2 * DAY - 1),
            StoredFile("b", 10, NOW - 2 * DAY),
            StoredFile("empty", 0, NOW - 9 * DAY),
            StoredFile("kept", 10, NOW - 9 * DAY),
            StoredFile("fresh", 10, NOW - MediaRetention.FRESH_MS + 1),
            StoredFile("justNotFresh", 10, NOW - MediaRetention.FRESH_MS),
        )
        assertEquals(listOf("a"), MediaRetention.select(list, NOW, 2) { it == "kept" }.map { it.name })
        assertEquals(listOf("a", "b", "justNotFresh"), MediaRetention.select(list, NOW, null) { it == "kept" }.map { it.name })
        assertTrue(MediaRetention.valid(null) && MediaRetention.valid(1) && MediaRetention.valid(MediaRetention.MAX_DAYS))
        assertFalse(MediaRetention.valid(0) || MediaRetention.valid(MediaRetention.MAX_DAYS + 1))
    }

    // ---- what the user sees afterwards ---------------------------------------------------------------------------

    @Test
    fun aRemovedFileFailsCleanlyAndIsNeverFetchedAgain() {
        setDays(1)
        val item = received(1, "gone.bin", 2 * DAY)
        assertEquals(1, coordinator.applyRetention())

        val m = stateOf(item)
        assertEquals(MediaState.FAILED, m.state)
        assertEquals(MediaFailure.NOT_ON_DEVICE, m.failure)
        assertFalse(m.readable)
        assertTrue(coordinator.fileGone(item))

        val bytes = Box<ByteArray>()
        coordinator.readBytes(item, bytes)
        assertEquals(ErrorMapper.MEDIA_NOT_ON_DEVICE, bytes.error.message)
        val play = Box<MediaPlayback>()
        coordinator.openPlayback(item, play)
        assertEquals(ErrorMapper.MEDIA_NOT_ON_DEVICE, play.error.message)
        assertEquals(0, native.reads + native.decrypts)

        // A tap, a catch-up and auto-download never ask the core for it again.
        val tap = Box<Unit>()
        coordinator.accept(item, tap)
        assertEquals(ErrorMapper.MEDIA_NOT_ON_DEVICE, tap.error.message)
        coordinator.catchUp()
        assertTrue(transport.receives.isEmpty())
        assertEquals(0, vault.playbacks.count())
    }

    @Test
    fun theVaultRefusesAnEmptiedFileEvenWhenTheStateSaysReadable() {
        val item = received(1, "gone.bin", 2 * DAY)
        File(files, "gone.bin").writeBytes(ByteArray(0))
        val key = item.file!!.key!!
        val e1 = runCatching { vault.readBytes("gone.bin", key, MIB) }.exceptionOrNull() as VaultException
        assertEquals(MediaFailure.NOT_ON_DEVICE, e1.failure)
        val e2 = runCatching { vault.decryptToView("gone.bin", key) }.exceptionOrNull() as VaultException
        assertEquals(MediaFailure.NOT_ON_DEVICE, e2.failure)
        assertTrue(File(media, MediaVault.VIEW).list().isNullOrEmpty())
        assertEquals(0, native.reads + native.decrypts)
    }

    @Test
    fun aRemovedNameIsNeverHandedToANewerFile() {
        setDays(1)
        received(1, "IMG_abcdefgh.jpg", 2 * DAY)
        coordinator.applyRetention()
        // The next send under the same name gets another one: deleting the old message can never delete the new file.
        val sent = sendOne()
        assertEquals("IMG_abcdefgh_1.jpg", sent.file!!.path)
        assertTrue(File(files, "IMG_abcdefgh.jpg").exists())
    }

    // ---- never removed ------------------------------------------------------------------------------------------

    @Test
    fun anUploadThatHasntEndedStaysUntilItDoes() {
        setDays(1)
        val sent = sendOne()
        val name = sent.file!!.path!!
        // Committed now: dated now, whatever the sealing time.
        assertEquals(now, File(files, name).lastModified())
        tick(5 * DAY)
        assertEquals(0, coordinator.applyRetention())
        assertEquals(0, coordinator.clearAll())
        assertTrue(present(name))

        // Kept across a restart too (the pin is on disk).
        build()
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present(name))

        coordinator.onEvent("sndFileCompleteXFTP", event("sndComplete", sent))
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present(name))
    }

    @Test
    fun failedAndCancelledUploadsReleaseTheirFile() {
        setDays(1)
        val a = sendOne()
        val b = sendOne()
        tick(2 * DAY)
        coordinator.onEvent("sndFileError", event("sndError", a))
        transport.cancelReply = { TransportReply.Item(b.copy(file = b.file!!.copy(statusTag = "sndCancelled"))) }
        val c = Box<Unit>()
        coordinator.cancel(b, c)
        assertTrue(c.isOk)
        assertEquals(2, coordinator.applyRetention())
    }

    @Test
    fun anUploadFromBeforePinsIsPinnedWhenSeenAndAnEndedOneReleased() {
        setDays(1)
        files.mkdirs()
        val key = FakeNativeFiles.newKey()
        File(files, "old_upload.bin").writeBytes(FakeNativeFiles.seal(PLAIN, key))
        age("old_upload.bin", 3 * DAY)
        val uploading = CoreItem(
            chatId = CHAT, itemId = 77, outgoing = true, isMessage = true, text = "", timeMs = 0, statusTag = "sndNew",
            status = MessageStatus.SENDING, unread = false, msgContentType = "image", shared = true, sharedMsgId = "eA",
            file = CoreFile(1077, MediaKind.IMAGE, "IMG_x.jpg", PLAIN.size.toLong(), "sndTransfer", "xftp", "old_upload.bin", key),
            preview = MediaSamples.PNG_URL,
        )
        coordinator.annotate(listOf(uploading))
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present("old_upload.bin"))

        // Its end event was missed (engine not running): the next sight of it, or the pass itself, releases it.
        host.store[CHAT to 77L] = uploading.copy(file = uploading.file!!.copy(statusTag = "sndComplete"))
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("old_upload.bin"))
    }

    @Test
    fun aSeenEndedUploadReleasesItsPinWithoutACoreCall() {
        setDays(1)
        val sent = sendOne()
        tick(2 * DAY)
        coordinator.annotate(listOf(sent.copy(file = sent.file!!.copy(statusTag = "sndComplete"))))
        host.running = false   // no core to ask
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun anUploadWhoseMessageIsUnknownStaysForGood() {
        setDays(1)
        transport.sendReply = { TransportReply.Failed(ErrorMapper.engine("invalid")) }
        val box = Box<SecuredMessage>()
        coordinator.send(CHAT, ChatRef.contact(CHAT), prepareOne(), "", null, box)
        assertEquals(1, files.list()!!.size)
        val name = files.list()!!.single()
        tick(30 * DAY)
        assertEquals(0, coordinator.applyRetention())
        assertEquals(0, coordinator.clearAll())
        assertTrue(present(name))

        // Until its chat goes.
        coordinator.onChatRemoved(CHAT)
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun aMessageTheCoreCantFindKeepsItsPin() {
        setDays(1)
        val sent = sendOne()
        tick(2 * DAY)
        host.lookup = { _, _ -> null }
        assertEquals(0, coordinator.applyRetention())
        assertTrue(present(sent.file!!.path!!))
        // A deleted message releases it.
        coordinator.onRemoved(CHAT, listOf(sent.itemId))
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun aDownloadsPlaceholderAndAFreshWriteStay() {
        setDays(1)
        files.mkdirs()
        // The core's empty placeholder while a download runs (filled in one go at the end).
        File(files, "placeholder.bin").createNewFile()
        age("placeholder.bin", 30 * DAY)
        // The agent's final write: being written now.
        received(2, "being_written.bin", MediaRetention.FRESH_MS - 1)
        assertEquals(0, coordinator.clearAll())
        assertEquals(0, coordinator.applyRetention())
        assertTrue(File(files, "placeholder.bin").exists())
        assertTrue(present("being_written.bin"))
    }

    @Test
    fun anOpenPlaybackKeepsItsFileUntilClosedAndLeavesNoPlaintext() {
        build(MediaPolicyConfig(readBytesLimit = 16))   // large: decrypted into a view/ file
        setDays(1)
        val item = received(1, "video.bin", 2 * DAY, MediaKind.VIDEO)
        val box = Box<MediaPlayback>()
        coordinator.openPlayback(item, box)
        val playback = box.value
        assertEquals(0, coordinator.applyRetention())
        assertEquals(0, coordinator.clearAll())
        assertTrue(present("video.bin"))
        val buf = ByteArray(PLAIN.size)
        assertEquals(PLAIN.size, playback.reader.readAt(0, buf, 0, buf.size))
        assertArrayEquals(PLAIN, buf)

        playback.close()
        assertEquals(1, coordinator.applyRetention())
        assertFalse(present("video.bin"))
        assertTrue(File(media, MediaVault.VIEW).list().isNullOrEmpty())
    }

    @Test
    fun aReadInFlightKeepsItsFile() {
        setDays(1)
        val item = received(1, "photo.bin", 2 * DAY)
        queueRead = true
        val bytes = Box<ByteArray>()
        val play = Box<MediaPlayback>()
        coordinator.readBytes(item, bytes)
        coordinator.openPlayback(item, play)
        assertEquals(0, coordinator.applyRetention())
        assertEquals(0, coordinator.clearAll())

        readQueue.poll()!!.run()
        assertArrayEquals(PLAIN, bytes.value)
        // The playback still opening holds it; once open, the playback does.
        assertEquals(0, coordinator.applyRetention())
        readQueue.poll()!!.run()
        assertEquals(0, coordinator.applyRetention())
        play.value.close()
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun aRefusedReadReleasesItsHold() {
        setDays(1)
        val item = received(1, "photo.bin", 2 * DAY)
        coordinator = MediaCoordinator(
            host, transport, vault, ledger, Preparer { error("unused") }, DescriptionSamples.ourServers,
            Executor { it.run() }, Executor { throw java.util.concurrent.RejectedExecutionException() },
        )
        host.coordinator = coordinator
        val bytes = Box<ByteArray>()
        coordinator.readBytes(item, bytes)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, bytes.error.kind)
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun aPreparedAttachmentIsUntouchedAndCanStillBeSent() {
        val pm = prepareOne()
        received(1, "old.bin", 9 * DAY)
        assertEquals(1, coordinator.clearAll())
        assertEquals(1, File(media, MediaVault.PREP).list()!!.size)
        val box = Box<SecuredMessage>()
        coordinator.send(CHAT, ChatRef.contact(CHAT), pm, "", null, box)
        assertTrue(box.isOk)
        val sent = transport.sends.single()
        assertArrayEquals(PLAIN, FakeNativeFiles.open(File(files, sent.fileName).readBytes(), sent.key))
    }

    // ---- clear everything ---------------------------------------------------------------------------------------

    @Test
    fun clearAllRemovesEveryFileNotInUseWhateverItsAge() {
        val a = received(1, "a.bin", 2 * DAY)
        received(2, "b.bin", HOUR)
        received(3, "c.bin", MediaRetention.FRESH_MS)
        val sent = sendOne()
        tick(HOUR)
        assertNull(coordinator.retentionDays())

        assertEquals(3, coordinator.clearAll())
        assertFalse(present("a.bin") || present("b.bin") || present("c.bin"))
        assertTrue(present(sent.file!!.path!!))
        assertEquals(MediaFailure.NOT_ON_DEVICE, stateOf(a).failure)
        assertEquals(MediaStorage(size(sent.file!!.path!!), 1), coordinator.storage())
        // The setting is untouched.
        assertNull(coordinator.retentionDays())
    }

    // ---- Saved Messages and what open chats see ------------------------------------------------------------------

    @Test
    fun savedMessagesFilesAreNeverRemoved() {
        // Lead decision (9 November 2026): the user's own copies, which can't be downloaded again.
        setDays(1)
        val note = received(1, "note.bin", 30 * DAY, chat = SAVED_MESSAGES_CHAT_ID)
        received(2, "other.bin", 30 * DAY)
        host.saved = setOf("note.bin")

        assertEquals(1, coordinator.applyRetention())
        assertTrue(present("note.bin"))
        assertFalse(present("other.bin"))
        assertEquals(0, coordinator.clearAll())
        assertTrue(present("note.bin"))
        assertEquals(MediaState.READY, stateOf(note).state)
        assertFalse(coordinator.fileGone(note))
    }

    @Test
    fun whenSavedMessagesCantBeListedNothingIsRemoved() {
        setDays(1)
        received(1, "a.bin", 30 * DAY)
        received(2, "b.bin", 30 * DAY)
        host.saved = null
        assertNull(coordinator.applyRetention())
        assertNull(coordinator.clearAll())
        assertTrue(present("a.bin") && present("b.bin"))
        assertTrue(logs.any { it.contains("saved_unlisted") })

        host.saved = emptySet()
        assertEquals(2, coordinator.clearAll())
    }

    @Test
    fun shownMessagesAreSentAgainOnceTheirFileGoes() {
        setDays(1)
        val seen = received(1, "seen.bin", 2 * DAY)
        received(2, "unseen.bin", 2 * DAY)
        val young = received(3, "young.bin", HOUR)
        assertEquals(MediaState.READY, stateOf(seen).state)
        assertEquals(MediaState.READY, stateOf(young).state)
        host.changed.clear()

        assertEquals(2, coordinator.applyRetention())
        // Only the message the UI was shown, as the store holds it now; a kept file sends nothing.
        val told = host.changed.single()
        assertEquals(seen.itemId, told.messageId)
        assertEquals(MediaFailure.NOT_ON_DEVICE, told.media!!.failure)

        // Told once: the next pass has nothing new to say.
        host.changed.clear()
        assertEquals(0, coordinator.applyRetention())
        assertTrue(host.changed.isEmpty())

        // "Clear media" tells the same way.
        assertEquals(1, coordinator.clearAll())
        assertEquals(listOf(young.itemId), host.changed.map { it.messageId })
        assertEquals(MediaFailure.NOT_ON_DEVICE, host.changed.single().media!!.failure)
    }

    @Test
    fun savedMessagesPagesAreReadForEveryFileName() {
        fun row(id: Long, file: String?) = """{"chatDir":{"type":"localSnd"},"meta":{"itemId":$id},
            "content":{"type":"sndMsgContent","msgContent":{"type":"text","text":""}}${
                if (file == null) "" else ",\"file\":{\"fileId\":$id,\"fileSource\":{\"filePath\":\"$file\"}}"}}"""
        val body = JSONObject("""{"type":"apiChat","chat":{"chatInfo":{"type":"local"},"chatItems":[
            ${row(40, "IMG_1.jpg")},${row(12, null)},${row(31, "/data/files/doc.pdf")},${row(50, "")}]}}""")
        val page = CoreJson.filePage(body)!!
        assertEquals(4, page.rows)
        assertEquals(12L, page.oldestId)
        // Whatever the content type (a text row with a file counts), plus a full path's last segment.
        assertEquals(setOf("IMG_1.jpg", "/data/files/doc.pdf", "doc.pdf"), page.paths)
        assertFalse(page.toString().contains("IMG"))
        assertNull(CoreJson.filePage(JSONObject("""{"type":"apiChat","chat":{}}""")))
    }

    // ---- storage ------------------------------------------------------------------------------------------------

    @Test
    fun storageSumsTheEncryptedFilesOnly() {
        assertEquals(MediaStorage(0, 0), coordinator.storage())
        received(1, "one.bin", DAY, plain = ByteArray(100))
        received(2, "two.bin", DAY, plain = ByteArray(1000))
        File(files, "placeholder.bin").createNewFile()
        File(files, "folder").mkdirs()
        File(File(files, "folder"), "inner.bin").writeBytes(ByteArray(50))
        Files.createSymbolicLink(File(files, "link.bin").toPath(), File(files, "two.bin").toPath())
        // Staging and plaintext folders are not media on the phone.
        File(media, MediaVault.PREP).mkdirs()
        File(File(media, MediaVault.PREP), "staged").writeBytes(ByteArray(70))

        val expected = (100 + FakeNativeFiles.TAG) + (1000 + FakeNativeFiles.TAG).toLong()
        assertEquals(MediaStorage(expected, 2), coordinator.storage())

        setDays(1)
        tick(DAY)
        assertEquals(2, coordinator.applyRetention())
        assertEquals(MediaStorage(0, 0), coordinator.storage())
        // The link was neither followed nor touched.
        assertTrue(Files.isSymbolicLink(File(files, "link.bin").toPath()))
    }

    @Test
    fun expireRefusesAFileThatChangedSinceItWasListed() {
        received(1, "x.bin", 2 * DAY)
        val listed = vault.storedFiles().single()
        File(files, "x.bin").appendBytes(byteArrayOf(1))
        assertFalse(vault.expire(listed))
        assertTrue(present("x.bin"))
        assertFalse(vault.expire(StoredFile("missing.bin", 10, 0)))
        assertFalse(vault.expire(StoredFile("../escape", 10, 0)))
    }

    // ---- pins, lifecycle, privacy -------------------------------------------------------------------------------

    @Test
    fun pinsHoldNoNamesAndGoWithTheStore() {
        val sent = sendOne()
        val pinsFile = File(data, MediaCoordinator.PINS_FILE)
        val text = pinsFile.readText()
        assertFalse(text.contains(sent.file!!.path!!))
        assertFalse(text.contains("IMG"))
        val pin = UploadPins.parse(text.lines().single())!!
        assertEquals(CHAT, pin.chatId)
        assertEquals(sent.itemId, pin.itemId)
        assertEquals(MediaRetention.nameHash(sent.file!!.path!!), pin.nameHash)

        coordinator.onStoreReplaced()
        assertEquals("", pinsFile.readText())
        setDays(1)
        tick(2 * DAY)
        assertEquals(1, coordinator.applyRetention())
    }

    @Test
    fun badPinLinesAreSkipped() {
        assertNull(UploadPins.parse("1|2"))
        assertNull(UploadPins.parse("1|2|NOTHEX0000000000"))
        assertNull(UploadPins.parse("1|x|0123456789abcdef"))
        assertNull(UploadPins.parse("1|2|0123"))
        assertEquals(UploadPins.Pin(1, -1, "0123456789abcdef"), UploadPins.parse("1|-1|0123456789abcdef"))
    }

    @Test
    fun noLogLineCarriesAName() {
        setDays(1)
        received(1, "CANARY-retention-name.bin", 2 * DAY)
        sendOne()
        coordinator.applyRetention()
        coordinator.clearAll()
        coordinator.storage()
        assertTrue(logs.any { it.startsWith("MEDIA_WIPED kind=retention") })
        for (l in logs) assertFalse(l, l.contains("CANARY") || l.contains("IMG_"))
        // Nothing on disk holds a name in clear except the files folder's own entries.
        for (f in data.walkTopDown().filter { it.isFile && it.parentFile != files }) {
            assertFalse(f.name, f.readText(Charsets.ISO_8859_1).contains("CANARY-retention"))
        }
    }

    companion object {
        const val CHAT = 5L
        const val MIB = 1024L * 1024L
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = MediaRetention.DAY_MS
        const val NOW = 1_800_000_000_000L
        val PLAIN: ByteArray = "CANARY_PLAINTEXT_retention".toByteArray() + ByteArray(200) { (it * 7).toByte() }
    }
}
