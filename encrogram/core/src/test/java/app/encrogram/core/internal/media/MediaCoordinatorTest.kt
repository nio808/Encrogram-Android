package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.PreparedMedia
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreError
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.ErrorMapper
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.prep.PrepException
import app.encrogram.core.internal.media.prep.Preparer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.random.Random

class MediaCoordinatorTest {

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

    /** When set, the prep or read executor queues its tasks until the test runs them. */
    private var queuePrep = false
    private var queueRead = false
    private val prepQueue = ArrayDeque<Runnable>()
    private val readQueue = ArrayDeque<Runnable>()

    /** What the preparer returns; tests swap it. */
    private var prep: Preparer = Preparer { prepared() }

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        data = tmp.newFolder("encrogram-secured")
        build()
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
    }

    private fun build(config: MediaPolicyConfig = MediaPolicy.DEFAULT, nativeFiles: NativeFiles? = null, keepLedger: Boolean = false) {
        files = File(data, "files")
        media = File(data, "media")
        if (!keepLedger || !::native.isInitialized) native = FakeNativeFiles()
        vault = MediaVault(files, media, nativeFiles ?: native)
        ledger = MediaLedger(File(data, "media_ledger"))
        if (!::host.isInitialized || !keepLedger) host = FakeMediaHost(ITEM_TS + 3_600_000L)
        if (!::transport.isInitialized || !keepLedger) transport = FakeTransport()
        coordinator = MediaCoordinator(
            host, transport, vault, ledger, Preparer { prep.prepare(it) }, DescriptionSamples.ourServers,
            Executor { if (queuePrep) prepQueue.add(it) else it.run() },
            Executor { if (queueRead) readQueue.add(it) else it.run() },
            config,
        )
        host.coordinator = coordinator
    }

    // ---- samples ------------------------------------------------------------------------------------------------

    private fun prepared(kind: MediaKind = MediaKind.IMAGE, name: String = "IMG_abcdefgh.jpg", bytes: ByteArray = PLAIN) =
        Prepared(kind, name, "image/jpeg", MediaSamples.PNG_URL, null, false, vault.sealBytes(bytes))

    private fun rcvJson(
        itemId: Long,
        fileId: Long,
        size: Long,
        type: String = "image",
        name: String = if (type == "video") "video_abcdefgh.mp4" else if (type == "file") "report.pdf" else "IMG_abcdefgh.jpg",
        status: String = "rcvInvitation",
        protocol: String = "xftp",
        source: Boolean = false,
    ) = MediaSamples.item(
        itemId, "directRcv",
        MediaSamples.content(
            type,
            image = if (type == "image" || type == "video") MediaSamples.PNG_URL else null,
            duration = if (type == "video" || type == "voice") 3 else null,
        ),
        MediaSamples.file(fileId, name, size, MediaSamples.status(status), protocol, source),
        "rcvNew",
    )

    /** Parses the item and records it as the store's current state of that message (what catch-up looks up). */
    private fun item(json: String): CoreItem =
        CoreJson.parseChatItem(CHAT, JSONObject(json))!!.also { host.store[it.chatId to it.itemId] = it }

    private fun descr(itemJson: String, fileId: Long, text: String): JSONObject {
        item(itemJson)
        return JSONObject(MediaSamples.descrReady(itemJson, fileId, text = text))
    }

    private fun honest(size: Long) = DescriptionSamples.oneChunk(size)

    private fun foreign(size: Long) = DescriptionSamples.oneChunk(size).replace(DescriptionSamples.OURS, DescriptionSamples.FOREIGN)

    private fun event(type: String, itemJson: String, extra: String = "") = JSONObject(MediaSamples.event(type, itemJson, "chatItem", extra))

    /** A complete received file in the core's files folder (MediaSamples' path and key). */
    private fun storeReceived(plain: ByteArray = PLAIN) {
        files.mkdirs()
        File(files, MediaSamples.PATH).writeBytes(FakeNativeFiles.seal(plain, FileKey(MediaSamples.KEY, MediaSamples.NONCE)))
    }

    private class Box<T> : SecuredCallback<T> {
        val results: MutableList<SecuredResult<T>> = Collections.synchronizedList(ArrayList())
        override fun onResult(result: SecuredResult<T>) {
            results += result
        }
        val value: T get() = (results.single() as SecuredResult.Ok<T>).value
        val error: SecuredError get() = (results.single() as SecuredResult.Err).error
        val isOk: Boolean get() = results.single() is SecuredResult.Ok
    }

    private fun prepareOne(): PreparedMedia {
        val box = Box<PreparedMedia>()
        coordinator.prepare(MediaInput.Picked("content://picker/1"), box)
        return box.value
    }

    private fun send(pm: PreparedMedia, caption: String = "", quoted: CoreItem? = null): Box<SecuredMessage> {
        val box = Box<SecuredMessage>()
        coordinator.send(CHAT, ChatRef.contact(CHAT), pm, caption, quoted, box)
        return box
    }

    private fun accept(i: CoreItem) = Box<Unit>().also { coordinator.accept(i, it) }
    private fun cancel(i: CoreItem) = Box<Unit>().also { coordinator.cancel(i, it) }

    private fun listed(dir: String): List<String> = File(media, dir).list()?.toList().orEmpty()
    private fun inFiles(): List<String> = files.list()?.toList().orEmpty()

    // ---- receiving ----------------------------------------------------------------------------------------------

    @Test
    fun passedAndAutoReceivesExactlyOnce() {
        val j = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        assertEquals(listOf(7L), transport.receives)
        assertEquals(Verdict.PASSED, ledger.get(7, item(j).sharedMsgId)!!.verdict)
        assertTrue(ledger.get(7, item(j).sharedMsgId)!!.accepted)
        assertEquals(MediaState.DOWNLOADING, host.changed.last().media!!.state)

        // The same description again (or catch-up) never asks twice.
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        coordinator.catchUp()
        assertEquals(listOf(7L), transport.receives)
    }

    @Test
    fun aBigVideoWaitsForATap() {
        val j = rcvJson(11, 8, 20 * MIB, "video")
        coordinator.recordDescription(descr(j, 8, honest(20 * MIB)))
        coordinator.catchUp()
        assertTrue(transport.receives.isEmpty())
        assertEquals(MediaState.WAITING, host.toMessage(item(j)).media!!.state)

        assertTrue(accept(item(j)).isOk)
        assertEquals(listOf(8L), transport.receives)
    }

    @Test
    fun aTapBeforeTheDescriptionWaitsForOurCheck() {
        val j = rcvJson(11, 8, 20 * MIB, "video")
        assertTrue(accept(item(j)).isOk)
        assertTrue(transport.receives.isEmpty())
        assertEquals(MediaState.DOWNLOADING, host.changed.last().media!!.state)
        assertTrue(ledger.get(8, item(j).sharedMsgId)!!.wanted)

        coordinator.recordDescription(descr(j, 8, honest(20 * MIB)))
        assertEquals(listOf(8L), transport.receives)
    }

    @Test
    fun aRefusedFileIsNeverReceivedEvenAfterATap() {
        val j = rcvJson(12, 9, MIB)
        coordinator.recordDescription(descr(j, 9, foreign(MIB)))
        assertTrue(transport.receives.isEmpty())
        assertEquals(1, host.changed.size)
        assertEquals(MediaState.FAILED, host.changed.single().media!!.state)
        assertEquals(MediaFailure.FOREIGN_SERVER, host.changed.single().media!!.failure)

        val tap = accept(item(j))
        assertEquals(SecuredError.Kind.UNAPPROVED_RELAY, tap.error.kind)
        assertEquals(ErrorMapper.MEDIA_FOREIGN, tap.error.message)

        // A later passing description doesn't undo a refusal; nothing else asks either.
        coordinator.recordDescription(descr(j, 9, honest(MIB)))
        coordinator.catchUp()
        accept(item(j))
        assertTrue(transport.receives.isEmpty())
        assertEquals(Verdict.FOREIGN_SERVER, ledger.get(9, item(j).sharedMsgId)!!.verdict)
    }

    @Test
    fun aPassThenARefusalEndsRefused() {
        val j = rcvJson(13, 9, 20 * MIB, "video")
        coordinator.recordDescription(descr(j, 9, honest(20 * MIB)))
        coordinator.recordDescription(descr(j, 9, "party: recipient\nredirect: x\n"))
        assertEquals(Verdict.REDIRECT, ledger.get(9, item(j).sharedMsgId)!!.verdict)
        assertEquals(SecuredError.Kind.NOT_ALLOWED, accept(item(j)).error.kind)
        assertTrue(transport.receives.isEmpty())
    }

    @Test
    fun recordedWhileStoppedThenCaughtUp() {
        host.running = false
        val j = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        assertTrue(transport.receives.isEmpty())
        assertTrue(host.changed.isEmpty())
        assertEquals(Verdict.PASSED, ledger.get(7, item(j).sharedMsgId)!!.verdict)

        host.running = true
        coordinator.catchUp()
        assertEquals(listOf(7L), transport.receives)
    }

    @Test
    fun catchUpSurvivesAProcessRestart() {
        host.running = false
        val j = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        build(keepLedger = true)   // a new ledger instance reads the same file
        host.running = true
        coordinator.catchUp()
        assertEquals(listOf(7L), transport.receives)
    }

    @Test
    fun catchUpNeverReusesAVerdictForAReusedFileId() {
        host.running = false
        val old = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(old, 7, honest(MIB)))
        // Message 10 is deleted during start and the peer's next file gets the same file id 7 in message 20.
        host.store.remove(CHAT to 10L)
        item(rcvJson(20, 7, MIB))
        host.running = true
        coordinator.catchUp()
        assertTrue(transport.receives.isEmpty())
        assertTrue(ledger.entries().isEmpty())
    }

    @Test
    fun aReceiveThatComesBackForAnotherMessageIsStopped() {
        val j = rcvJson(10, 7, MIB)
        transport.receiveReply = { TransportReply.Item(item(rcvJson(20, 7, MIB, status = "rcvAccepted"))) }
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        assertEquals(listOf(7L), transport.cancels)
        assertTrue(ledger.entries().isEmpty())
    }

    @Test
    fun aCancelWhileQueuedStopsTheAutoDownload() {
        val j = rcvJson(10, 7, MIB)
        assertTrue(cancel(item(j)).isOk)
        assertEquals(MediaState.WAITING, host.changed.last().media!!.state)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        coordinator.catchUp()
        assertTrue(transport.receives.isEmpty())
        assertTrue(transport.cancels.isEmpty())

        assertTrue(accept(item(j)).isOk)
        assertEquals(listOf(7L), transport.receives)
    }

    @Test
    fun aCancelAfterAcceptStopsItAndATapStartsItAgain() {
        val j = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        assertEquals(listOf(7L), transport.receives)

        transport.cancelReply = { TransportReply.Item(item(rcvJson(10, 7, MIB, status = "rcvInvitation"))) }
        assertTrue(cancel(item(rcvJson(10, 7, MIB, status = "rcvTransfer"))).isOk)
        assertEquals(listOf(7L), transport.cancels)
        assertEquals(MediaState.WAITING, host.changed.last().media!!.state)
        coordinator.catchUp()
        assertEquals(1, transport.receives.size)

        assertTrue(accept(item(j)).isOk)
        assertEquals(listOf(7L, 7L), transport.receives)
    }

    @Test
    fun anSmpOfferIsNeverReceived() {
        val j = rcvJson(14, 11, MIB, protocol = "smp")
        coordinator.recordDescription(descr(j, 11, honest(MIB)))
        assertEquals(Verdict.PROTOCOL, ledger.get(11, item(j).sharedMsgId)!!.verdict)
        assertEquals(MediaFailure.UNSUPPORTED, host.changed.single().media!!.failure)
        assertEquals(SecuredError.Kind.UNSUPPORTED, accept(item(j)).error.kind)
        coordinator.catchUp()
        assertTrue(transport.receives.isEmpty())
    }

    @Test
    fun aDescriptionOverdueExpires() {
        val j = rcvJson(15, 12, 20 * MIB, "video")
        host.now = ITEM_TS + MediaPolicy.DEFAULT.descriptionOverdueMs
        assertEquals(MediaFailure.EXPIRED, host.toMessage(item(j)).media!!.failure)
        assertEquals(ErrorMapper.MEDIA_EXPIRED, accept(item(j)).error.message)
    }

    @Test
    fun theCoreRefusingAPassedFileRecordsIt() {
        transport.receiveReply = { TransportReply.Refused(MediaFailure.FOREIGN_SERVER) }
        val j = rcvJson(11, 8, 20 * MIB, "video")
        coordinator.recordDescription(descr(j, 8, honest(20 * MIB)))
        assertEquals(SecuredError.Kind.UNAPPROVED_RELAY, accept(item(j)).error.kind)
        assertEquals(Verdict.FOREIGN_SERVER, ledger.get(8, item(j).sharedMsgId)!!.verdict)
        assertEquals(MediaFailure.FOREIGN_SERVER, host.changed.last().media!!.failure)
        accept(item(j))
        assertEquals(1, transport.receives.size)
    }

    @Test
    fun progressGoesOnlyToProgress() {
        val j = rcvJson(10, 7, MIB)
        coordinator.recordDescription(descr(j, 7, honest(MIB)))
        val changes = host.changed.size
        val moving = rcvJson(10, 7, MIB, status = "rcvTransfer")
        coordinator.onEvent("rcvFileProgressXFTP", event("rcvFileProgressXFTP", moving, """"receivedSize":500,"totalSize":1000"""))
        coordinator.onEvent("rcvFileProgressXFTP", event("rcvFileProgressXFTP", moving, """"receivedSize":600,"totalSize":1000"""))
        coordinator.onEvent("rcvFileProgressXFTP", event("rcvFileProgressXFTP", moving, """"receivedSize":1000,"totalSize":1000"""))
        assertEquals(changes, host.changed.size)
        // Throttled (no time passed): the first and the final value only, scaled to the plaintext size.
        assertEquals(listOf(MIB / 2, MIB), host.progress.map { it.done })
        assertTrue(host.progress.all { it.total == MIB && it.chatId == CHAT && it.itemId == 10L })
    }

    @Test
    fun completionChangesOnceAndPrunesTheLedger() {
        val j = rcvJson(10, 7, 1024)
        coordinator.recordDescription(descr(j, 7, honest(1024)))
        assertEquals(listOf(7L), transport.receives)
        val changes = host.changed.size
        storeReceived(ByteArray(1024) { it.toByte() })
        coordinator.onEvent("rcvFileComplete", event("rcvFileComplete", rcvJson(10, 7, 1024, status = "rcvComplete", source = true)))
        assertEquals(changes + 1, host.changed.size)
        val m = host.changed.last().media!!
        assertEquals(MediaState.READY, m.state)
        assertTrue(m.readable)
        assertTrue(ledger.entries().none { it.fileId == 7L })
        assertTrue(host.progress.isEmpty())
    }

    @Test
    fun warningsAndIncompleteDescriptionsChangeNothing() {
        val moving = rcvJson(10, 7, MIB, status = "rcvWarning")
        coordinator.onEvent("rcvFileWarning", event("rcvFileWarning", moving))
        coordinator.onEvent("rcvFileDescrReady", JSONObject(MediaSamples.descrReady(rcvJson(10, 7, MIB), 7, complete = false, text = honest(MIB))))
        coordinator.recordDescription(JSONObject(MediaSamples.descrReady(rcvJson(10, 7, MIB), 7, complete = false, text = honest(MIB))))
        assertTrue(host.changed.isEmpty())
        assertTrue(ledger.entries().isEmpty())
        assertTrue(transport.receives.isEmpty())
    }

    @Test
    fun aVerdictIsUsedOnlyForTheSameMessage() {
        val j = rcvJson(12, 9, MIB)
        coordinator.recordDescription(descr(j, 9, foreign(MIB)))
        val other = item(j).copy(sharedMsgId = "b3RoZXI")
        val facts = coordinator.annotate(listOf(other)).single().file!!.local
        assertNull(facts.verdict)
        assertTrue(facts.auto)
    }

    @Test
    fun outgoingFilesArePresentOnlyWhenInTheFilesFolder() {
        val pm = prepareOne()
        val sent = send(pm).value
        assertEquals(MediaState.UPLOADING, sent.media!!.state)
        val out = host.added.single()
        val done = out.copy(file = out.file!!.copy(statusTag = "sndComplete"))
        assertEquals(MediaState.READY, host.toMessage(done).media!!.state)
        File(files, out.file!!.path!!).delete()
        assertEquals(MediaFailure.NOT_ON_DEVICE, host.toMessage(done).media!!.failure)
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @Test
    fun readBytesGivesThePlaintext() {
        storeReceived()
        val box = Box<ByteArray>()
        coordinator.readBytes(item(rcvJson(20, 30, PLAIN.size.toLong(), status = "rcvComplete", source = true)), box)
        assertArrayEquals(PLAIN, box.value)
    }

    @Test
    fun readBytesRefusesWhatIsntHereOrIsTooLarge() {
        val waiting = Box<ByteArray>()
        coordinator.readBytes(item(rcvJson(20, 30, PLAIN.size.toLong())), waiting)
        assertEquals(ErrorMapper.MEDIA_NOT_READY, waiting.error.message)

        val missing = Box<ByteArray>()
        coordinator.readBytes(item(rcvJson(20, 30, PLAIN.size.toLong(), status = "rcvComplete", source = true)), missing)
        assertEquals(ErrorMapper.MEDIA_NOT_ON_DEVICE, missing.error.message)

        storeReceived()
        val big = Box<ByteArray>()
        coordinator.readBytes(item(rcvJson(20, 30, 17 * MIB, status = "rcvComplete", source = true)), big)
        assertEquals(SecuredError.Kind.TOO_LARGE, big.error.kind)
        assertEquals(0, native.reads)
    }

    @Test
    fun aLargePlaybackUsesAViewFileThatCloseDeletes() {
        build(MediaPolicyConfig(readBytesLimit = 16))
        storeReceived()
        val box = Box<MediaPlayback>()
        coordinator.openPlayback(item(rcvJson(20, 30, PLAIN.size.toLong(), "video", status = "rcvComplete", source = true)), box)
        val playback = box.value
        assertEquals(1, listed("view").size)
        val buf = ByteArray(PLAIN.size)
        assertEquals(PLAIN.size, playback.reader.readAt(0, buf, 0, buf.size))
        assertArrayEquals(PLAIN, buf)
        assertEquals(MediaKind.VIDEO, playback.kind)
        playback.close()
        assertTrue(listed("view").isEmpty())
        assertEquals(0, vault.playbacks.count())
    }

    @Test
    fun onRemovedClosesTheMatchingPlayback() {
        storeReceived()
        val box = Box<MediaPlayback>()
        coordinator.openPlayback(item(rcvJson(20, 30, PLAIN.size.toLong(), "video", status = "rcvComplete", source = true)), box)
        val playback = box.value as VaultPlayback
        coordinator.onRemoved(CHAT, listOf(21L))
        assertFalse(playback.isClosed)
        coordinator.onRemoved(CHAT, listOf(20L))
        assertTrue(playback.isClosed)
        assertEquals(-1, playback.reader.readAt(0, ByteArray(4), 0, 4))
        assertEquals(0, vault.playbacks.count())
    }

    @Test
    fun onStopClosesEverythingWipesAndQueuedWorkGivesUp() {
        storeReceived()
        val pm = prepareOne()
        val open = Box<MediaPlayback>()
        val readable = item(rcvJson(20, 30, PLAIN.size.toLong(), "video", status = "rcvComplete", source = true))
        coordinator.openPlayback(readable, open)
        val playback = open.value as VaultPlayback
        val readsBefore = native.reads

        queuePrep = true
        queueRead = true
        val bytes = Box<ByteArray>()
        val later = Box<MediaPlayback>()
        val prepLater = Box<PreparedMedia>()
        coordinator.readBytes(readable, bytes)
        coordinator.openPlayback(readable, later)
        coordinator.prepare(MediaInput.Picked("content://picker/2"), prepLater)

        coordinator.onStop()
        coordinator.afterClose()
        assertTrue(playback.isClosed)
        assertTrue(listed("prep").isEmpty() && listed("view").isEmpty() && listed("link").isEmpty())

        while (readQueue.isNotEmpty()) readQueue.poll()!!.run()
        while (prepQueue.isNotEmpty()) prepQueue.poll()!!.run()
        for (b in listOf(bytes, later, prepLater)) assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, b.error.kind)
        assertEquals(readsBefore, native.reads)
        assertEquals(0, vault.playbacks.count())
        assertTrue(listed("prep").isEmpty())

        queuePrep = false
        queueRead = false
        assertEquals(ErrorMapper.MEDIA_PREPARED_GONE, send(pm).error.message)
    }

    @Test
    fun aReaderFinishedAfterAStopIsClosedNotRegistered() {
        val hooked = HookedNative(FakeNativeFiles())
        build(MediaPolicyConfig(readBytesLimit = 16), hooked)
        hooked.before = { coordinator.onStop() }
        files.mkdirs()
        File(files, MediaSamples.PATH).writeBytes(FakeNativeFiles.seal(PLAIN, FileKey(MediaSamples.KEY, MediaSamples.NONCE)))
        val readable = item(rcvJson(20, 30, PLAIN.size.toLong(), "video", status = "rcvComplete", source = true))

        val big = Box<MediaPlayback>()
        coordinator.openPlayback(readable, big)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, big.error.kind)
        assertEquals(0, vault.playbacks.count())
        assertTrue(listed("view").isEmpty())

        build(MediaPolicy.DEFAULT, hooked)
        hooked.before = { coordinator.onStop() }
        val small = Box<MediaPlayback>()
        coordinator.openPlayback(readable, small)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, small.error.kind)
        val bytes = Box<ByteArray>()
        coordinator.readBytes(readable, bytes)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, bytes.error.kind)
        assertEquals(0, vault.playbacks.count())
    }

    private class HookedNative(private val inner: NativeFiles) : NativeFiles by inner {
        var before: () -> Unit = {}
        override fun readDecrypted(file: File, key: FileKey): ByteArray {
            before()
            return inner.readDecrypted(file, key)
        }
        override fun decryptFile(from: File, key: FileKey, to: File) {
            before()
            inner.decryptFile(from, key, to)
        }
    }

    // ---- sending ------------------------------------------------------------------------------------------------

    @Test
    fun sendCommitsSendsAndReportsTheMessage() {
        val pm = prepareOne()
        assertEquals(MediaKind.IMAGE, pm.kind)
        assertEquals(PLAIN.size.toLong(), pm.sizeBytes)
        assertEquals(1, listed("prep").size)

        val box = send(pm, "hello")
        val sent = transport.sends.single()
        assertEquals("IMG_abcdefgh.jpg", sent.fileName)
        assertEquals(listOf(sent.fileName), inFiles())
        assertTrue(listed("prep").isEmpty())
        assertEquals("hello", sent.content.caption)
        assertEquals(MediaSamples.PNG_URL, sent.content.previewDataUrl)
        assertArrayEquals(PLAIN, FakeNativeFiles.open(File(files, sent.fileName).readBytes(), sent.key))
        assertEquals("hello", box.value.text)
        assertEquals(MediaState.UPLOADING, box.value.media!!.state)
        assertEquals(1, host.added.size)
        assertTrue(host.changed.isEmpty())
    }

    @Test
    fun aPreparedFileIsSentOnce() {
        val pm = prepareOne()
        assertTrue(send(pm).isOk)
        assertEquals(ErrorMapper.MEDIA_PREPARED_GONE, send(pm).error.message)
        assertEquals(1, transport.sends.size)
    }

    @Test
    fun anErrorReplyUncommitsTheFile() {
        transport.sendReply = { TransportReply.Failed(ErrorMapper.fromCore(CoreError("error", "fileWrite", null), ErrorMapper.Op.SEND)) }
        assertEquals(SecuredError.Kind.STORAGE, send(prepareOne()).error.kind)
        assertTrue(inFiles().isEmpty())

        transport.sendReply = { TransportReply.Refused(MediaFailure.TOO_LARGE) }
        assertEquals(SecuredError.Kind.TOO_LARGE, send(prepareOne()).error.kind)
        assertTrue(inFiles().isEmpty())
        assertTrue(listed("prep").isEmpty())
    }

    @Test
    fun noReadableReplyKeepsTheFile() {
        transport.sendReply = { TransportReply.Failed(ErrorMapper.engine("invalid")) }
        assertEquals(SecuredError.Kind.ENGINE, send(prepareOne()).error.kind)
        assertEquals(1, inFiles().size)
    }

    @Test
    fun aCaptionTooLongKeepsThePreparedFile() {
        val pm = prepareOne()
        val tooLong = send(pm, "a".repeat(1025))
        assertEquals(ErrorMapper.MEDIA_CAPTION_TOO_LONG, tooLong.error.message)
        assertTrue(transport.sends.isEmpty())
        assertTrue(inFiles().isEmpty())
        assertEquals(1, listed("prep").size)

        val bigQuote = item(rcvJson(3, 4, MIB)).copy(text = "中".repeat(5000))
        assertEquals(ErrorMapper.MEDIA_REPLY_TOO_LONG, send(pm, "ok", bigQuote).error.message)
        assertTrue(transport.sends.isEmpty())

        assertTrue(send(pm, "a".repeat(1024), item(rcvJson(3, 4, MIB))).isOk)
        assertEquals(3L, transport.sends.single().quotedItemId)
    }

    @Test
    fun discardDeletesTheSealedCopy() {
        val pm = prepareOne()
        assertEquals(1, listed("prep").size)
        coordinator.discard(pm)
        assertTrue(listed("prep").isEmpty())
        assertEquals(ErrorMapper.MEDIA_PREPARED_GONE, send(pm).error.message)
        coordinator.discard(pm)   // twice: nothing
    }

    @Test
    fun prepareFailuresAreReadable() {
        fun failure(t: Throwable): SecuredError {
            prep = Preparer { throw t }
            val box = Box<PreparedMedia>()
            coordinator.prepare(MediaInput.Picked("content://picker/3"), box)
            return box.error
        }
        assertEquals(SecuredError.Kind.TOO_LARGE, failure(PrepException(PrepException.Reason.TOO_LARGE)).kind)
        assertEquals(ErrorMapper.MEDIA_UNSUPPORTED, failure(PrepException(PrepException.Reason.UNSUPPORTED)).message)
        assertEquals(SecuredError.Kind.STORAGE, failure(PrepException(PrepException.Reason.STORAGE)).kind)
        assertEquals(SecuredError.Kind.TOO_LARGE, failure(OutOfMemoryError(FakeNativeFiles.CORE_TEXT)).kind)
        assertEquals(SecuredError.Kind.STORAGE, failure(NativeFileException("write")).kind)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, failure(NativeFileException(CtrlGate.NO_CTRL)).kind)
        val bug = failure(IllegalStateException(FakeNativeFiles.CORE_TEXT))
        assertEquals(SecuredError.Kind.ENGINE, bug.kind)
        assertFalse(bug.message.contains("CANARY"))
    }

    @Test
    fun cancellingAnUploadStopsIt() {
        val pm = prepareOne()
        send(pm)
        val out = host.added.single()
        transport.cancelReply = { TransportReply.Item(out.copy(file = out.file!!.copy(statusTag = "sndCancelled"))) }
        assertTrue(cancel(out).isOk)
        assertEquals(listOf(out.file!!.fileId), transport.cancels)
        assertEquals(MediaFailure.CANCELLED, host.changed.last().media!!.failure)

        // A finished upload: nothing to cancel.
        assertTrue(cancel(out.copy(file = out.file!!.copy(statusTag = "sndComplete"))).isOk)
        assertEquals(1, transport.cancels.size)
    }

    // ---- the receive invariant, under random interleavings --------------------------------------------------------

    private class Spec(val fileId: Long, val itemId: Long, val size: Long, val type: String, val protocol: String, val text: String, val passes: Boolean)

    @Test
    fun everyReceiveFollowsARecordedPassForThatFile() {
        val received = ArrayList<Long>()
        for (seed in 1..250) {
            data = tmp.newFolder("seed$seed")
            build()
            val specs = listOf(
                Spec(1, 101, MIB, "image", "xftp", honest(MIB), true),           // auto
                Spec(2, 102, 20 * MIB, "video", "xftp", honest(20 * MIB), true), // tap only
                Spec(3, 103, MIB, "image", "xftp", foreign(MIB), false),
                Spec(4, 104, MIB, "image", "smp", honest(MIB), false),
                Spec(5, 105, MIB, "image", "xftp", "party: recipient\nsize: x\n", false),
            )
            val status = HashMap<Long, String>().apply { specs.forEach { put(it.fileId, "rcvInvitation") } }
            val delivered = HashSet<Long>()
            fun json(s: Spec) = rcvJson(s.itemId, s.fileId, s.size, s.type, protocol = s.protocol, status = status[s.fileId]!!, source = status[s.fileId] == "rcvComplete")
            fun specOf(id: Long) = specs.first { it.fileId == id }

            transport.beforeReceive = { id ->
                assertTrue("seed $seed: receive while stopped", host.running)
                assertTrue("seed $seed: receive of refused $id", specOf(id).passes)
                assertTrue("seed $seed: receive before its description", id in delivered)
                assertTrue("seed $seed: no recorded pass", ledger.entries().any { it.fileId == id && it.verdict == Verdict.PASSED })
            }
            host.lookup = { _, itemId -> specs.firstOrNull { it.itemId == itemId }?.let { item(json(it)) } }
            transport.receiveReply = { id -> status[id] = "rcvAccepted"; TransportReply.Item(item(json(specOf(id)))) }
            transport.cancelReply = { id -> status[id] = "rcvInvitation"; TransportReply.Item(item(json(specOf(id)))) }

            val rnd = Random(seed)
            repeat(80) {
                val s = specs[rnd.nextInt(specs.size)]
                when (rnd.nextInt(9)) {
                    0, 1 -> {
                        if (s.passes) delivered += s.fileId
                        coordinator.recordDescription(descr(json(s), s.fileId, s.text))
                    }
                    2 -> if (host.running) coordinator.accept(item(json(s)), Box())
                    3 -> if (host.running) coordinator.cancel(item(json(s)), Box())
                    4 -> if (host.running && status[s.fileId] == "rcvAccepted") {
                        status[s.fileId] = "rcvComplete"
                        coordinator.onEvent("rcvFileComplete", event("rcvFileComplete", json(s)))
                    }
                    5 -> if (host.running) {
                        coordinator.onStop()
                        coordinator.afterClose()
                        host.running = false
                    }
                    6 -> if (!host.running) {
                        coordinator.onStoreOpened()
                        host.running = true
                        coordinator.catchUp()
                    }
                    7 -> if (!host.running) {
                        build(keepLedger = true)   // process death: the ledger is read back from its file
                        host.running = false
                    }
                    8 -> if (host.running && status[s.fileId] == "rcvAccepted") {
                        coordinator.onEvent("rcvFileProgressXFTP", event("rcvFileProgressXFTP", json(s), """"receivedSize":${rnd.nextInt(100)},"totalSize":100"""))
                    }
                }
            }
            assertTrue("seed $seed", transport.receives.none { !specOf(it).passes })
            received += transport.receives
        }
        // Not vacuous: both passing files were received many times across the seeds.
        assertTrue(received.count { it == 1L } > 50)
        assertTrue(received.count { it == 2L } > 20)
    }

    // ---- logs ---------------------------------------------------------------------------------------------------

    @Test
    fun noLogLineCarriesANameCaptionKeyOrDescription() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        SecuredLog.testSink = { lines += it }

        prep = Preparer { prepared(MediaKind.FILE, "CANARY_NAME_doc.pdf") }
        send(prepareOne(), "CANARY_CAPTION")
        transport.sendReply = { TransportReply.Failed(ErrorMapper.fromCore(CoreError("error", "fileNotApproved", null), ErrorMapper.Op.SEND)) }
        send(prepareOne(), "CANARY_CAPTION")
        transport.sendReply = { TransportReply.Item(transport.outgoing(it)) }
        send(prepareOne(), "x".repeat(2000) + "CANARY_CAPTION")

        val name = MediaSamples.NAME   // CANARY-NAME-report.pdf
        val bad = rcvJson(40, 41, MIB, "file", name)
        coordinator.recordDescription(descr(bad, 41, "party: recipient\nCANARY_DESCR: x\n"))
        accept(item(bad))
        val good = rcvJson(42, 43, MIB, "file", name)
        coordinator.recordDescription(descr(good, 43, honest(MIB)))
        accept(item(good))
        transport.cancelReply = { TransportReply.Item(item(good)) }
        cancel(item(rcvJson(42, 43, MIB, "file", name, status = "rcvTransfer")))
        coordinator.onEvent("rcvFileError", event("rcvFileError", rcvJson(42, 43, MIB, "file", name, status = "rcvError")))
        coordinator.readBytes(item(rcvJson(44, 45, MIB, "file", name, status = "rcvComplete", source = true)), Box())
        prep = Preparer { throw IllegalStateException(FakeNativeFiles.CORE_TEXT) }
        coordinator.prepare(MediaInput.Picked("content://CANARY_URI"), Box())
        coordinator.onStop()
        coordinator.afterClose()

        // The sink saw the coordinator's own lines (fixed words only)...
        for (expected in listOf("MEDIA_SENT kind=FILE", "MEDIA_DESCR kind=MALFORMED", "MEDIA_DESCR kind=PASSED", "MEDIA_ACCEPTED kind=FILE", "MEDIA_CANCELLED kind=download")) {
            assertTrue(expected, lines.contains(expected))
        }
        // ...and none of them carries user content, a key or a description.
        for (l in lines) assertFalse(l, l.contains("CANARY"))
    }

    companion object {
        const val CHAT = 5L
        const val MIB = 1024L * 1024L
        val ITEM_TS: Long = Instant.parse("2026-11-08T10:20:30.123456Z").toEpochMilli()
        val PLAIN: ByteArray = "CANARY_PLAINTEXT_media".toByteArray() + ByteArray(300) { (it * 13).toByte() }
    }
}
