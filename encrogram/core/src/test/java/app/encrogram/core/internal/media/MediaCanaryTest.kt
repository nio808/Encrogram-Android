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
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.ErrorMapper
import app.encrogram.core.internal.Samples
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.prep.FakeCodec
import app.encrogram.core.internal.media.prep.FakeOpener
import app.encrogram.core.internal.media.prep.FakeSource
import app.encrogram.core.internal.media.prep.FakeVideoTool
import app.encrogram.core.internal.media.prep.MediaPreparer
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
import java.nio.ByteBuffer
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executor

/**
 * MD9: the end-to-end media canary (docs/media-plan.md §6, §7 MD9). A whole fake flow runs through the real
 * MediaCoordinator, MediaPreparer (FakeMediaKit), MediaVault (FakeNativeFiles), MediaLedger, FileDescriptionCheck,
 * MediaJson / CoreJson parsing and MessageMapper. `CANARY` values are seeded everywhere a secret or user content can
 * be: the picked display name, the picked Uri, captions, the source photo's EXIF block, the sealed file's key and nonce,
 * a received file's key, nonce, name and path, the description's key, nonce and replica id and key, a foreign server
 * (key hash and host), the core's error text in fileStatus and in the event bodies, a quote, and the preview.
 *
 * Then every log line ([SecuredLog.testSink]), every toString() of what crossed a seam (Prepared, PreparedMedia,
 * SecuredMedia, MediaPreview, CoreFile, LocalFacts, MediaEvent, TransportReply, SecuredError, FileKey, SealedFile,
 * MediaPlayback, LedgerEntry, exceptions with their stack traces) and every SecuredError.message delivered must be
 * free of any canary, and so must the ledger file and every file's content on disk. `view/`, `prep/` and `link/` are
 * empty after release and after stop.
 *
 * SecuredMessage and CoreItem are plain data classes whose generated toString prints the caption, a quote and the
 * preview by design. They are held to "no secret canary" in the flows, and to "no canary at all" in
 * [dataClassesThatCrossTheSeamsPrintNoUserText], which records the logging-risk finding of the MD9 review.
 */
class MediaCanaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var data: File
    private lateinit var files: File
    private lateinit var media: File
    private lateinit var fake: FakeNativeFiles
    private lateinit var vault: MediaVault
    private lateinit var ledger: MediaLedger
    private lateinit var host: FakeMediaHost
    private lateinit var transport: FakeTransport
    private lateinit var coordinator: MediaCoordinator

    private val opener = FakeOpener()
    private val codec = FakeCodec()
    private val video = FakeVideoTool()

    /** 1 KiB in memory at most: the received video below opens through a `view/` file. */
    private val config = MediaPolicyConfig(readBytesLimit = 1024)

    private var queuePrep = false
    private var queueRead = false
    private val prepQueue = ArrayDeque<Runnable>()
    private val readQueue = ArrayDeque<Runnable>()

    /** Runs inside the preparer seam after the work is done (a stop racing a prepare). */
    private var afterPrepare: () -> Unit = {}
    private val preparedSeen = ArrayList<Prepared>()

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Everything that must never hold a canary: toString of seam objects, exceptions, error messages. */
    private val strict = ArrayList<Pair<String, String>>()

    /** toString of SecuredMessage, CoreItem, QuotePreview, CoreQuote: user text by design, secrets never. */
    private val userTextHolders = ArrayList<Pair<String, String>>()

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

    private fun build(keepState: Boolean = false) {
        files = File(data, "files")
        media = File(data, "media")
        if (!keepState) fake = FakeNativeFiles()
        vault = MediaVault(files, media, CanaryKeyFiles(fake) { thrown("native", it) })
        ledger = MediaLedger(File(data, "media_ledger"))
        if (!keepState) {
            host = FakeMediaHost(ITEM_TS + 3_600_000L)
            transport = FakeTransport()
        }
        val preparer = MediaPreparer(opener, codec, video, vault, config)
        coordinator = MediaCoordinator(
            host, transport, vault, ledger,
            Preparer { input ->
                val p = try {
                    preparer.prepare(input)
                } catch (t: Throwable) {
                    thrown("preparer", t)
                    throw t
                }
                preparedSeen += p
                see("Prepared", p)
                see("SealedFile", p.sealed)
                see("FileKey", p.sealed.key)
                afterPrepare()
                p
            },
            DescriptionSamples.ourServers,
            Executor { if (queuePrep) prepQueue.add(it) else it.run() },
            Executor { if (queueRead) readQueue.add(it) else it.run() },
            config,
        )
        host.coordinator = coordinator
        // Every reply the fake transport gives crosses the seam too.
        val send = transport.sendReply
        transport.sendReply = { s -> send(s).also { seeReply(it); see("OutgoingContent", s.content); see("FileKey", s.key) } }
    }

    // =============================================================================================================
    // 1. Sending: prepare (EXIF photo, document) -> send -> upload events -> completion, error, cancel, and a stop
    // =============================================================================================================

    @Test
    fun sendingLeaksNothing() {
        // A photo whose EXIF block carries GPS, picked under a telling name and Uri.
        opener.source = FakeSource(EXIF_PHOTO, displayName = PICKED_NAME, mimeType = "image/jpeg")
        val photo = prepare(PICKED_URI)
        assertEquals(MediaKind.IMAGE, photo.kind)
        assertTrue(photo.name, Regex("IMG_[a-z2-7]{8}\\.jpg").matches(photo.name))

        // The sealed copy in prep/ is the codec's clean re-encode, never the source: no EXIF, raw or decrypted.
        val sealed = preparedSeen.last().sealed
        assertEquals(MediaVault.PREP, sealed.file.parentFile!!.name)
        assertFalse(holds(sealed.file.readBytes(), EXIF_CANARY))
        val sealedPlain = FakeNativeFiles.open(sealed.file.readBytes(), CanaryKeyFiles.unwrap(sealed.key))!!
        assertArrayEquals(codec.outputs.first(), sealedPlain)
        assertFalse(holds(sealedPlain, EXIF_CANARY))
        assertFalse(holds(sealedPlain, "CANARY"))
        assertTrue(sealed.key.key.startsWith(CanaryKeyFiles.K))   // the canary key really travels

        // Send it with a caption, as a reply to a received message whose caption is a canary too.
        val quoted = parse(rcv(90, 900, "image", "IMG_quotedab.jpg", 300, status("rcvComplete")))
        val sent = send(photo, CAPTION, quoted)
        assertTrue(sent.isOk)
        val wire = transport.sends.last()
        assertEquals(photo.name, wire.fileName)
        assertEquals(90L, wire.quotedItemId)
        val committed = File(files, wire.fileName)
        assertFalse(holds(committed.readBytes(), EXIF_CANARY))
        assertArrayEquals(sealedPlain, FakeNativeFiles.open(committed.readBytes(), CanaryKeyFiles.unwrap(wire.key)))
        assertEquals(MediaState.UPLOADING, sent.value.media!!.state)
        assertTrue(listed(MediaVault.PREP).isEmpty())

        // Upload: progress, a warning carrying core text, completion.
        val out = host.added.last()
        fire("sndFileProgressXFTP", ev("sndFileProgressXFTP", snd(out, status("sndTransfer", """"sndProgress":500,"sndTotal":1000""")), extra = """"sentSize":500,"totalSize":1000"""))
        fire("sndFileWarning", ev("sndFileWarning", snd(out, status("sndWarning", """"sndFileError":{"type":"relay","srvError":{"type":"host","CANARY":"$FOREIGN"}}"""))))
        fire("sndFileProgressXFTP", ev("sndFileProgressXFTP", snd(out, status("sndTransfer", """"sndProgress":1000,"sndTotal":1000""")), extra = """"sentSize":1000,"totalSize":1000"""))
        fire("sndFileCompleteXFTP", ev("sndFileCompleteXFTP", snd(out, status("sndComplete")), key = "chatItem"))
        assertEquals(MediaState.READY, host.changed.last().media!!.state)
        assertTrue(host.progress.isNotEmpty())

        // A document, sent unchanged under its own (sanitized) name, fails with the core's error text.
        opener.source = FakeSource(DOC_BYTES, displayName = DOC_NAME, mimeType = "application/pdf")
        val doc = prepare(PICKED_URI + "/doc", asFile = true)
        assertEquals(MediaKind.FILE, doc.kind)
        assertTrue(doc.unchanged)
        assertTrue(send(doc, "").isOk)
        val docOut = host.added.last()
        fire(
            "sndFileError",
            ev("sndFileError", snd(docOut, status("sndError", """"sndFileError":{"type":"other","fileError":"$SND_CORE_ERROR"}""")), extra = """"errorMessage":"$SND_EVENT_ERROR""""),
        )
        assertEquals(MediaFailure.SERVER_FULL, host.changed.last().media!!.failure)

        // A second photo: cancelled while uploading.
        opener.source = FakeSource(EXIF_PHOTO, displayName = PICKED_NAME, mimeType = "image/jpeg")
        assertTrue(send(prepare(PICKED_URI + "/2"), CAPTION).isOk)
        val third = host.added.last()
        transport.cancelReply = { TransportReply.Item(third.copy(file = third.file!!.copy(statusTag = "sndCancelled"))).also(::seeReply) }
        assertTrue(cancel(third).isOk)
        fire("sndFileCancelled", ev("sndFileCancelled", snd(third, status("sndCancelled"))))
        assertEquals(MediaFailure.CANCELLED, host.changed.last().media!!.failure)

        // A caption too long and a send that the core refuses: fixed sentences only.
        opener.source = FakeSource(EXIF_PHOTO, displayName = PICKED_NAME, mimeType = "image/jpeg")
        val fourth = prepare(PICKED_URI + "/4")
        assertEquals(ErrorMapper.MEDIA_CAPTION_TOO_LONG, send(fourth, CAPTION.repeat(60)).error.message)
        transport.sendReply = { TransportReply.Refused(MediaFailure.TOO_LARGE).also(::seeReply) }
        assertEquals(ErrorMapper.MEDIA_TOO_LARGE, send(fourth, CAPTION).error.message)
        assertTrue(listed(MediaVault.PREP).isEmpty())

        // A stop in the middle: one prepared and unsent, one finishing after the stop, one still queued.
        opener.source = FakeSource(EXIF_PHOTO, displayName = PICKED_NAME, mimeType = "image/jpeg")
        val waiting = prepare(PICKED_URI + "/5")
        assertEquals(1, listed(MediaVault.PREP).size)
        afterPrepare = { coordinator.onStop() }
        val late = Box<PreparedMedia>()
        coordinator.prepare(MediaInput.Picked(PICKED_URI + "/6"), late)
        afterPrepare = {}
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, late.error.kind)
        queuePrep = true
        val queued = Box<PreparedMedia>()
        coordinator.prepare(MediaInput.Picked(PICKED_URI + "/7"), queued)
        coordinator.onStop()
        coordinator.afterClose()
        assertTransientEmpty()
        while (prepQueue.isNotEmpty()) prepQueue.poll()!!.run()
        queuePrep = false
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, queued.error.kind)
        assertEquals(ErrorMapper.MEDIA_PREPARED_GONE, send(waiting, CAPTION).error.message)
        coordinator.onStoreOpened()
        assertTransientEmpty()

        // The source's EXIF reached no file at all, raw or as the sealed photo's plaintext.
        for (f in data.walkTopDown().filter { it.isFile }) assertFalse(f.name, holds(f.readBytes(), EXIF_CANARY))

        // Not vacuous: the caption really travelled through the objects checked below.
        assertTrue(userTextHolders.any { CAPTION in it.second })

        assertNoLeaks()
    }

    // =============================================================================================================
    // 2. Receiving: description (pass, foreign server, redirect) -> accept -> progress -> completion -> read ->
    //    playback -> release, a failure with core text, a cancel, and a stop in the middle with a process restart
    // =============================================================================================================

    @Test
    fun receivingLeaksNothing() {
        transport.receiveReply = { id -> TransportReply.Item(parse(rcvById(id, "rcvAccepted"))).also(::seeReply) }

        // A: a small photo with a canary caption and preview, downloaded by itself once our check passes.
        val a = rcvById(31, "rcvInvitation")
        newItem(a)
        fire("rcvFileDescrReady", descr(a, 31, passing()))
        assertEquals(listOf(31L), transport.receives)
        fire("rcvFileStart", ev("rcvFileStart", rcvById(31, "rcvAccepted")))
        fire("rcvFileProgressXFTP", ev("rcvFileProgressXFTP", rcvById(31, "rcvTransfer"), extra = """"receivedSize":400,"totalSize":1000"""))
        store(PATH_A, PLAIN_A)
        fire("rcvFileComplete", ev("rcvFileComplete", rcvById(31, "rcvComplete"), key = "chatItem"))
        assertEquals(MediaState.READY, host.changed.last().media!!.state)
        val bytes = Box<ByteArray>()
        coordinator.readBytes(parse(rcvById(31, "rcvComplete")), bytes)
        assertArrayEquals(PLAIN_A, bytes.value)
        assertTransientEmpty()   // the non-ASCII path went through an ASCII hard link in link/, now gone

        // B: stored on a foreign server; F: redirected. Never received, even after a tap.
        val b = rcvById(32, "rcvInvitation")
        newItem(b)
        fire("rcvFileDescrReady", descr(b, 32, passing(server = FOREIGN)))
        assertEquals(MediaFailure.FOREIGN_SERVER, host.changed.last().media!!.failure)
        val tapB = accept(parse(b))
        assertEquals(SecuredError.Kind.UNAPPROVED_RELAY, tapB.error.kind)
        assertEquals(ErrorMapper.MEDIA_FOREIGN, tapB.error.message)
        val f = rcvById(36, "rcvInvitation")
        newItem(f)
        fire("rcvFileDescrReady", descr(f, 36, passing() + "redirect:\n  digest: $REDIRECT_CANARY\n  size: 1mb\n"))
        assertEquals(MediaFailure.CHECK_FAILED, host.changed.last().media!!.failure)
        assertEquals(listOf(31L), transport.receives)

        // C: a video (preview, duration), downloaded by itself, then played through a view/ file and released.
        val c = rcvById(33, "rcvInvitation")
        newItem(c)
        fire("rcvFileDescrReady", descr(c, 33, passing()))
        assertEquals(listOf(31L, 33L), transport.receives)
        fire("rcvFileProgressXFTP", ev("rcvFileProgressXFTP", rcvById(33, "rcvTransfer"), extra = """"receivedSize":1000,"totalSize":1000"""))
        store(PATH_C, PLAIN_C)
        fire("rcvFileComplete", ev("rcvFileComplete", rcvById(33, "rcvComplete"), key = "chatItem"))
        val opened = Box<MediaPlayback>()
        coordinator.openPlayback(parse(rcvById(33, "rcvComplete")), opened)
        val playback = opened.value
        see("MediaPlayback", playback)
        see("MediaReader", playback.reader)
        assertEquals(MediaKind.VIDEO, playback.kind)
        assertEquals(1, listed(MediaVault.VIEW).size)
        val buf = ByteArray(PLAIN_C.size)
        assertEquals(PLAIN_C.size, playback.reader.readAt(0, buf, 0, buf.size))
        assertArrayEquals(PLAIN_C, buf)
        playback.close()
        assertTransientEmpty()

        // A stop in the middle: an open playback, a queued read and a queued open; then the process dies and restarts.
        val again = Box<MediaPlayback>()
        coordinator.openPlayback(parse(rcvById(33, "rcvComplete")), again)
        assertEquals(1, listed(MediaVault.VIEW).size)
        queueRead = true
        val queuedRead = Box<ByteArray>()
        val queuedOpen = Box<MediaPlayback>()
        coordinator.readBytes(parse(rcvById(31, "rcvComplete")), queuedRead)
        coordinator.openPlayback(parse(rcvById(33, "rcvComplete")), queuedOpen)
        coordinator.onStop()
        coordinator.afterClose()
        assertEquals(-1, again.value.reader.readAt(0, ByteArray(4), 0, 4))
        assertTransientEmpty()
        while (readQueue.isNotEmpty()) readQueue.poll()!!.run()
        queueRead = false
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, queuedRead.error.kind)
        assertEquals(SecuredError.Kind.ENGINE_NOT_RUNNING, queuedOpen.error.kind)
        build(keepState = true)   // a new ledger instance reads the file back
        coordinator.onStoreOpened()
        coordinator.catchUp()
        assertTransientEmpty()

        // D: a document with a bidi-spoofed name, tapped before its description: queued, then fetched once it passed;
        //    the download then fails with the core's text (a digest failure).
        val d = rcvById(34, "rcvInvitation")
        newItem(d)
        assertTrue(accept(parse(d)).isOk)
        assertEquals(listOf(31L, 33L), transport.receives)
        assertEquals(MediaState.DOWNLOADING, host.changed.last().media!!.state)
        fire("rcvFileDescrReady", descr(d, 34, passing()))
        assertEquals(listOf(31L, 33L, 34L), transport.receives)
        val shownD = host.changed.last().media!!
        assertFalse("bidi control left in a received name", shownD.name.any { it in '‪'..'‮' || it in '⁦'..'⁩' })
        fire(
            "rcvFileError",
            ev("rcvFileError", rcvById(34, "rcvError", """"rcvFileError":{"type":"other","fileError":"$RCV_CORE_ERROR"}"""),
                extra = """"agentError":{"type":"XFTP","xftpErr":{"type":"INTERNAL"},"CANARY_AGENT":"$FOREIGN"}"""),
        )
        assertEquals(MediaFailure.DAMAGED, host.changed.last().media!!.failure)

        // E: a document tapped, fetched, and cancelled while it moves: back to "tap to download".
        val e = rcvById(35, "rcvInvitation")
        newItem(e)
        fire("rcvFileDescrReady", descr(e, 35, passing()))
        assertTrue(accept(parse(e)).isOk)
        assertEquals(listOf(31L, 33L, 34L, 35L), transport.receives)
        transport.cancelReply = { TransportReply.Item(parse(rcvById(35, "rcvInvitation"))).also(::seeReply) }
        assertTrue(cancel(parse(rcvById(35, "rcvTransfer", """"rcvProgress":10,"rcvTotal":100"""))).isOk)
        assertEquals(MediaState.WAITING, host.changed.last().media!!.state)

        // Reading what isn't here, and the ledger itself.
        assertEquals(ErrorMapper.MEDIA_NOT_READY, Box<ByteArray>().also { coordinator.readBytes(parse(e), it) }.error.message)
        for (entry in ledger.entries()) see("LedgerEntry", entry)
        see("MediaLedger", ledger)
        see("MediaVault", vault)
        see("MediaCoordinator", coordinator)

        coordinator.onStop()
        coordinator.afterClose()
        assertTransientEmpty()
        // Not vacuous: the received caption, quote and preview really travelled through the objects checked below.
        assertTrue(userTextHolders.any { RCV_CAPTION in it.second && PREVIEW_TOKEN in it.second })
        assertTrue(userTextHolders.any { QUOTE_TEXT in it.second })
        assertNoLeaks()
    }

    // =============================================================================================================
    // 3. The transport's replies: the core's error text, server addresses and paths never leave it
    // =============================================================================================================

    @Test
    fun transportRepliesLeakNothing() {
        val commands = ArrayList<String>()
        var next = ""
        val t = SimplexMediaTransport { cmd -> commands += cmd; CoreJson.parseReply(next) }
        val checked = CheckedFile.of(31, Verdict.PASSED)!!
        see("CheckedFile", checked)
        val content = OutgoingContent(MediaKind.IMAGE, CAPTION, PREVIEW_URL, null)
        val key = FileKey(RCV_KEY, RCV_NONCE)

        fun receive(raw: String): TransportReply { next = raw; return t.receive(checked).also(::seeReply) }
        fun send(raw: String): TransportReply { next = raw; return t.send(ChatRef.contact(5), "IMG_abcdefgh.jpg", key, content, null).also(::seeReply) }
        fun cancel(raw: String): TransportReply { next = raw; return t.cancel(31).also(::seeReply) }

        val aItem = Samples.aChatItem(5, rcvById(31, "rcvAccepted"))
        val refused = receive("""{"result":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"fileNotApproved","fileId":31,"unknownServers":["$FOREIGN"]}}}}""")
        assertEquals(MediaFailure.FOREIGN_SERVER, (refused as TransportReply.Refused).failure)
        receive("""{"error":{"type":"errorAgent","agentError":{"type":"XFTP","CANARY_SERVER":"$FOREIGN","xftpErr":{"type":"AUTH"}}}}""")
        receive("""{"error":{"type":"error","errorType":{"type":"commandError","message":"$CORE_TEXT"}}}""")
        receive("""{"error":{"type":"errorStore","storeError":{"type":"rcvFileDescrNotFound","fileId":31}}}""")
        receive("""{"error":{"type":"error","errorType":{"type":"fileAlreadyReceiving","message":"$CORE_TEXT"}}}""")
        receive("""{"error":{"type":"error","errorType":{"type":"fileRcvChunk","message":"$CORE_TEXT"}}}""")
        receive("""{"result":{"type":"rcvFileAcceptedSndCancelled","rcvFileTransfer":{"fileId":31,"CANARY":"$CORE_TEXT"}}}""")
        assertTrue(receive("""{"result":{"type":"rcvFileAccepted","chatItem":$aItem}}""") is TransportReply.Item)
        send("""{"error":{"type":"error","errorType":{"type":"fileWrite","filePath":"$PATH_A","message":"$CORE_TEXT"}}}""")
        send("""{"error":{"type":"error","errorType":{"type":"fileSize","filePath":"$PATH_A"}}}""")
        send("""{"result":{"type":"newChatItems","chatItems":[${Samples.aChatItem(5, snd(sample(), status("sndStored")))}]}}""")
        send("""{"result":{"type":"cmdOk","CANARY":"$CORE_TEXT"}}""")
        send("CANARY not json $CORE_TEXT")
        cancel("""{"error":{"type":"error","errorType":{"type":"fileCancel","fileId":31,"message":"$CORE_TEXT"}}}""")
        cancel("""{"result":{"type":"rcvFileCancelled","chatItem_":$aItem,"rcvFileTransfer":{"fileId":31}}}""")
        cancel("""{"error":{"type":"errorAgent","agentError":{"type":"BROKER","brokerAddress":"$FOREIGN","brokerErr":{"type":"TIMEOUT"}}}}""")

        // The receive command is the one fixed form, whatever came back before.
        val receives = commands.filter { it.startsWith("/freceive") }
        assertTrue(receives.isNotEmpty())
        assertTrue(receives.all { it == "/freceive 31 approved_relays=off encrypt=on inline=off" })
        assertTrue(commands.none { it.contains("approved_relays=on") || it.contains("_set_file_to_receive") })
        assertNoLeaks(minLogs = 0)   // the transport itself never logs
    }

    // =============================================================================================================
    // 4. The core's file functions failing with their own text: fixed tags only, no partial plaintext left
    // =============================================================================================================

    @Test
    fun nativeFailuresLeakNothing() {
        val calls = object : CoreFileCalls {
            var errorBytes: ByteArray? = null
            override fun writeFile(ctrl: Long, path: String, buffer: ByteBuffer): String =
                """{"type":"error","writeError":"$CORE_TEXT $path"}"""
            override fun readFile(path: String, key: String, nonce: String): Array<out Any?> =
                arrayOf(1, "$CORE_TEXT $path".toByteArray().also { errorBytes = it })
            override fun encryptFile(ctrl: Long, fromPath: String, toPath: String): String {
                File(toPath).writeText("CANARY_PARTIAL_CIPHERTEXT")
                return """{"type":"error","writeError":"$CORE_TEXT"}"""
            }
            override fun decryptFile(fromPath: String, key: String, nonce: String, toPath: String): String {
                File(toPath).writeText("CANARY_PARTIAL_PLAINTEXT unauthenticated")
                return "$CORE_TEXT $toPath"
            }
        }
        val nativeFiles = SimplexNativeFiles(calls, CtrlGate()) { 7L }
        see("SimplexNativeFiles", nativeFiles)
        val v = MediaVault(File(data, "files"), File(data, "media"), nativeFiles)

        fun expectFailure(what: String, block: () -> Unit) {
            try {
                block()
                throw AssertionError("$what: expected a failure")
            } catch (t: AssertionError) {
                throw t
            } catch (t: Throwable) {
                thrown(what, t)
                if (t is NativeFileException) assertNull("$what: a native failure carries no cause", t.cause)
            }
        }
        expectFailure("sealBytes") { v.sealBytes("CANARY_PLAINTEXT".toByteArray(), PICKED_NAME) }
        expectFailure("sealFile") { v.sealFile(v.newViewFile().apply { writeText("CANARY_STAGED_PLAINTEXT") }) }
        store(PATH_A, PLAIN_A)
        val key = FileKey(RCV_KEY, RCV_NONCE)
        expectFailure("readBytes") { v.readBytes(PATH_A, key, 1L shl 20) }
        assertTrue("the core's error bytes are zeroed", calls.errorBytes!!.all { it == 0.toByte() })
        expectFailure("decryptToView") { v.decryptToView(PATH_A, key) }
        expectFailure("missing") { v.readBytes("CANARY_MISSING.bin", key, 1L shl 20) }
        expectFailure("traversal") { v.readBytes("../CANARY_OUTSIDE", key, 1L shl 20) }
        expectFailure("commit") { v.commit(SealedFile(File(data, "CANARY_x"), key, 1), "CANARY/../x") }
        expectFailure("command") { MediaCommands.send(ChatRef.contact(5), "CANARY/$DOC_NAME", key, OutgoingContent(MediaKind.FILE, CAPTION, null, null), null) }
        assertTransientEmpty()

        // The same failures through the coordinator reach the caller as fixed sentences.
        files = File(data, "files")
        media = File(data, "media")
        vault = v
        coordinator = MediaCoordinator(host, transport, v, ledger, Preparer { throw IllegalStateException(CORE_TEXT) }, DescriptionSamples.ourServers,
            Executor { it.run() }, Executor { it.run() }, config)
        host.coordinator = coordinator
        val small = Box<ByteArray>()
        coordinator.readBytes(parse(rcvById(31, "rcvComplete")), small)
        assertEquals(ErrorMapper.MEDIA_OPEN_FAILED, small.error.message)
        store(PATH_C, PLAIN_C)
        val big = Box<MediaPlayback>()
        coordinator.openPlayback(parse(rcvById(33, "rcvComplete")), big)
        assertEquals(ErrorMapper.MEDIA_OPEN_FAILED, big.error.message)
        val prep = Box<PreparedMedia>()
        coordinator.prepare(MediaInput.Picked(PICKED_URI), prep)
        assertEquals(SecuredError.Kind.ENGINE, prep.error.kind)
        assertTransientEmpty()
        assertNoLeaks()
    }

    // =============================================================================================================
    // 5. The finding: SecuredMessage and CoreItem print user text in their generated toString
    // =============================================================================================================

    /**
     * Expected to FAIL until SecuredMessage and CoreItem override toString (MD9 review, logging risk). Nothing logs
     * them today, but one `"…" + message` in the UI or a future log call would print the caption, a quote or the
     * preview. Every class the media code made redacts itself; these two plain data classes don't.
     */
    @Test
    fun dataClassesThatCrossTheSeamsPrintNoUserText() {
        opener.source = FakeSource(EXIF_PHOTO, displayName = PICKED_NAME, mimeType = "image/jpeg")
        val quoted = parse(rcv(90, 900, "image", "IMG_quotedab.jpg", 300, status("rcvComplete"), quote = QUOTE_JSON))
        val sent = send(prepare(PICKED_URI), CAPTION, quoted).value
        val received = host.toMessage(quoted)
        val leaks = listOf(
            "SecuredMessage(sent)" to sent.toString(),
            "SecuredMessage(received)" to received.toString(),
            "CoreItem(sent)" to host.added.last().toString(),
            "CoreItem(received)" to quoted.toString(),
        ).filter { hasCanary(it.second) }.map { it.first }
        assertTrue("user text in toString of: $leaks", leaks.isEmpty())
    }

    // =============================================================================================================
    // Plumbing
    // =============================================================================================================

    private class Box<T> : SecuredCallback<T> {
        val results: MutableList<SecuredResult<T>> = Collections.synchronizedList(ArrayList())
        override fun onResult(result: SecuredResult<T>) {
            results += result
        }
        val value: T get() = (results.single() as SecuredResult.Ok<T>).value
        val error: SecuredError get() = (results.single() as SecuredResult.Err).error
        val isOk: Boolean get() = results.single() is SecuredResult.Ok
    }

    private fun prepare(uri: String, asFile: Boolean = false): PreparedMedia {
        val box = Box<PreparedMedia>()
        coordinator.prepare(MediaInput.Picked(uri, asFile), box)
        return box.value
    }

    private fun send(pm: PreparedMedia, caption: String, quoted: CoreItem? = null): Box<SecuredMessage> {
        val box = Box<SecuredMessage>()
        coordinator.send(CHAT, ChatRef.contact(CHAT), pm, caption, quoted, box)
        return box
    }

    private fun accept(i: CoreItem) = Box<Unit>().also { coordinator.accept(i, it) }
    private fun cancel(i: CoreItem) = Box<Unit>().also { coordinator.cancel(i, it) }

    private fun listed(dir: String): List<String> = File(media, dir).list()?.toList().orEmpty()

    private fun assertTransientEmpty() {
        for (dir in listOf(MediaVault.PREP, MediaVault.VIEW, MediaVault.LINK)) assertEquals(dir, emptyList<String>(), listed(dir))
    }

    /** A complete received file in the core's files folder, encrypted with the received canary key. */
    private fun store(path: String, plain: ByteArray) {
        files.mkdirs()
        File(files, path).writeBytes(FakeNativeFiles.seal(plain, FileKey(RCV_KEY, RCV_NONCE)))
    }

    /** A newChatItems item as the engine maps it: parsed, annotated, mapped. */
    private fun newItem(json: String) {
        seeMessage("newChatItems", host.toMessage(parse(json)))
    }

    private fun parse(json: String): CoreItem = CoreJson.parseChatItem(CHAT, JSONObject(json))!!.also { seeItem("parsed", it) }

    /** Decodes [body] as the transport does (captured), then hands it to the coordinator as the engine does. */
    private fun fire(type: String, body: String) {
        val o = JSONObject(body)
        when (val ev = transport.decode(type, o)) {
            null -> Unit
            is MediaEvent.DescriptionReady -> { see("MediaEvent", ev); seeItem("event", ev.item) }
            is MediaEvent.Changed -> { see("MediaEvent", ev); ev.item?.let { seeItem("event", it) } }
            is MediaEvent.Progress -> see("MediaEvent", ev)
        }
        if (type == "rcvFileDescrReady") coordinator.recordDescription(o) else coordinator.onEvent(type, o)
    }

    private fun ev(type: String, item: String, key: String = "chatItem_", extra: String = "") = MediaSamples.event(type, item, key, extra)

    private fun descr(item: String, fileId: Long, text: String) = MediaSamples.descrReady(item, fileId, text = text)

    private fun status(type: String, extra: String = "") = MediaSamples.status(type, extra)

    /** A description of a 1 MiB upload on [server], with canary key, nonce and replica id and key. */
    private fun passing(server: String = DescriptionSamples.OURS): String = listOf(
        "chunkSize: 1mb",
        "digest: YWJj",
        "key: $DESCR_KEY",
        "nonce: $DESCR_NONCE",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:$REPLICA_ID:$REPLICA_KEY:Z2hp",
        "  server: $server",
        "size: 1mb",
    ).joinToString("\n", postfix = "\n")

    /** CIFile with an optional fileSource carrying the received canary key. */
    private fun fileJson(id: Long, name: String, size: Long, status: String, path: String?, key: FileKey?): String {
        val source = if (path == null || key == null) "" else
            ""","fileSource":{"filePath":${JSONObject.quote(path)},"cryptoArgs":{"fileKey":"${key.key}","fileNonce":"${key.nonce}"}}"""
        return """{"fileId":$id,"fileName":${JSONObject.quote(name)},"fileSize":$size,"fileStatus":$status,"fileProtocol":"xftp"$source}"""
    }

    /** An incoming media item with the received canaries (caption, preview, key, nonce; path once complete). */
    private fun rcv(itemId: Long, fileId: Long, type: String, name: String, size: Long, status: String, path: String? = null, quote: String? = null) =
        MediaSamples.item(
            itemId, "directRcv",
            MediaSamples.content(
                type, RCV_CAPTION,
                image = if (type == "image" || type == "video") PREVIEW_URL else null,
                duration = if (type == "video" || type == "voice") 7 else null,
            ),
            fileJson(fileId, name, size, status, path, FileKey(RCV_KEY, RCV_NONCE)),
            "rcvNew",
            quote = quote,
        )

    /** The received items of [receivingLeaksNothing], by file id, in core status [statusType]. */
    private fun rcvById(fileId: Long, statusType: String, statusExtra: String = ""): String {
        val st = status(statusType, statusExtra)
        val complete = statusType == "rcvComplete"
        return when (fileId) {
            31L -> rcv(20, 31, "image", RCV_NAME_IMAGE, PLAIN_A.size.toLong(), st, if (complete) PATH_A else null)
            32L -> rcv(21, 32, "image", RCV_NAME_IMAGE, PLAIN_A.size.toLong(), st)
            33L -> rcv(22, 33, "video", "video_canarycc.mp4", PLAIN_C.size.toLong(), st, if (complete) PATH_C else null, quote = QUOTE_JSON)
            34L -> rcv(23, 34, "file", RCV_NAME_DOC, 300_000, st)
            35L -> rcv(24, 35, "file", RCV_NAME_DOC, 300_000, st)
            36L -> rcv(25, 36, "image", RCV_NAME_IMAGE, PLAIN_A.size.toLong(), st)
            else -> throw AssertionError("no sample for file $fileId")
        }
    }

    /** An outgoing item as JSON (for upload events), with the file the coordinator committed and its sealed canary key. */
    private fun snd(out: CoreItem, status: String): String {
        val f = out.file!!
        val type = when (f.kind) {
            MediaKind.IMAGE, MediaKind.GIF -> "image"
            MediaKind.VIDEO -> "video"
            MediaKind.VOICE -> "voice"
            MediaKind.FILE -> "file"
        }
        return MediaSamples.item(
            out.itemId, "directSnd",
            MediaSamples.content(type, out.text, image = out.preview),
            fileJson(f.fileId, f.name, f.sizeBytes, status, f.path, f.key),
            "sndNew",
        )
    }

    private fun sample() = CoreItem(
        chatId = CHAT, itemId = 77, outgoing = true, isMessage = true, text = CAPTION, timeMs = 0, statusTag = "sndNew",
        status = app.encrogram.core.api.MessageStatus.SENDING, unread = false, msgContentType = "image", shared = true,
        file = CoreFile(77, MediaKind.IMAGE, "IMG_abcdefgh.jpg", 1000, "sndStored", "xftp", "IMG_abcdefgh.jpg", FileKey(RCV_KEY, RCV_NONCE)),
        preview = PREVIEW_URL,
    )

    // ---- capture ------------------------------------------------------------------------------------------------

    private fun see(where: String, o: Any?) {
        if (o != null) strict += where to o.toString()
    }

    /**
     * An exception's class and message, its causes and suppressed ones. (Not the stack frames: they name this test
     * class, MediaCanaryTest, which would match the canary by itself.)
     */
    private fun thrown(where: String, t: Throwable) {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 8) {
            strict += "$where exception" to c.toString()
            for (s in c.suppressed) strict += "$where suppressed" to s.toString()
            c = c.cause
        }
    }

    private fun seeReply(r: TransportReply) {
        see("TransportReply", r)
        when (r) {
            is TransportReply.Item -> r.item?.let { seeItem("reply", it) }
            is TransportReply.Refused -> see("MediaFailure", r.failure)
            is TransportReply.Failed -> seeError("reply", r.error)
        }
    }

    private fun seeError(where: String, e: SecuredError) {
        see("$where SecuredError", e)
        strict += "$where message" to e.message
    }

    private fun seeItem(where: String, i: CoreItem) {
        see("$where CoreItem", i)   // its toString is ids and status only (MD9)
        userTextHolders += "$where CoreItem fields" to "${i.text} ${i.preview.orEmpty()}"
        i.quote?.let { userTextHolders += "$where CoreQuote" to it.toString() }
        i.file?.let { f ->
            see("$where CoreFile", f)
            see("$where LocalFacts", f.local)
            f.key?.let { see("$where FileKey", it) }
        }
    }

    private fun seeMessage(where: String, m: SecuredMessage) {
        see("$where SecuredMessage", m)   // its toString is ids and status only (MD9)
        userTextHolders += "$where SecuredMessage fields" to m.text
        m.replyTo?.let { userTextHolders += "$where QuotePreview" to it.toString() }
        m.media?.let { md ->
            see("$where SecuredMedia", md)
            md.preview?.let { see("$where MediaPreview", it) }
            see("$where MediaState", md.state)
        }
    }

    /** Everything the fake host received: callback results (values and errors), changed messages, added items. */
    private fun collectHost() {
        for (r in synchronized(host.delivered) { ArrayList(host.delivered) }) {
            when (r) {
                is SecuredResult.Err -> seeError("delivered", r.error)
                is SecuredResult.Ok -> when (val v = r.value) {
                    is SecuredMessage -> seeMessage("delivered", v)
                    is PreparedMedia -> { see("PreparedMedia", v); v.preview?.let { see("PreparedMedia preview", it) } }
                    is MediaPlayback -> { see("MediaPlayback", v); see("MediaReader", v.reader) }
                    is ByteArray, is Unit -> Unit   // plaintext handed to the UI on purpose
                    else -> see("delivered", v)
                }
            }
        }
        for (m in host.changed) seeMessage("changed", m)
        for (i in host.added) seeItem("added", i)
    }

    private fun hasCanary(s: String): Boolean = s.contains("canary", ignoreCase = true)

    private fun holds(bytes: ByteArray, token: String): Boolean = String(bytes, Charsets.ISO_8859_1).contains(token, ignoreCase = true)

    /** User text that SecuredMessage, CoreItem, QuotePreview and CoreQuote print by design (see test 5). */
    private fun withoutUserText(s: String): String =
        listOf(CAPTION, RCV_CAPTION, QUOTE_TEXT, PREVIEW_TOKEN).fold(s) { acc, t -> acc.replace(t, "") }

    private fun assertNoLeaks(minLogs: Int = 3) {
        collectHost()
        val leaks = ArrayList<String>()
        for ((where, text) in strict) if (hasCanary(text)) leaks += "$where: $text"
        for (line in logs) if (hasCanary(line)) leaks += "log: $line"
        for ((where, text) in userTextHolders) if (hasCanary(withoutUserText(text))) leaks += "$where (secret): $text"
        val ledgerFile = File(data, "media_ledger")
        if (ledgerFile.isFile && hasCanary(ledgerFile.readText())) leaks += "ledger file"
        // No file's content holds a canary in clear: sealed files are ciphertext, the ledger holds ids only.
        for (f in data.walkTopDown().filter { it.isFile }) if (holds(f.readBytes(), "canary")) leaks += "file content: ${f.relativeTo(data)}"
        assertTrue("canary leaks:\n" + leaks.joinToString("\n"), leaks.isEmpty())
        // Not vacuous: the flows went through the logger and the seams.
        assertTrue(logs.size >= minLogs)
        assertTrue(strict.size >= 10)
    }

    /**
     * FakeNativeFiles with canary keys: every key it hands out is prefixed with a canary, and the prefix is taken off
     * again before the fake decrypts. Received files keep their own (canary) keys, which have no prefix.
     */
    private class CanaryKeyFiles(private val inner: FakeNativeFiles, private val thrown: (Throwable) -> Unit) : NativeFiles {
        override fun writeEncrypted(target: File, data: ByteBuffer): FileKey = watch { wrap(inner.writeEncrypted(target, data)) }
        override fun encryptFile(from: File, to: File): FileKey = watch { wrap(inner.encryptFile(from, to)) }
        override fun readDecrypted(file: File, key: FileKey): ByteArray = watch { inner.readDecrypted(file, unwrap(key)) }
        override fun decryptFile(from: File, key: FileKey, to: File) = watch { inner.decryptFile(from, unwrap(key), to) }

        private inline fun <T> watch(block: () -> T): T = try {
            block()
        } catch (t: Throwable) {
            thrown(t)
            throw t
        }

        companion object {
            const val K = "CANARY_SEALKEY_"
            const val N = "CANARY_SEALNONCE_"
            fun wrap(k: FileKey) = FileKey(K + k.key, N + k.nonce)
            fun unwrap(k: FileKey) = FileKey(k.key.removePrefix(K), k.nonce.removePrefix(N))
        }
    }

    companion object {
        const val CHAT = 5L
        val ITEM_TS: Long = Instant.parse("2026-11-08T10:20:30.123456Z").toEpochMilli()

        // ---- user content -------------------------------------------------------------------------------------------
        const val PICKED_NAME = "CANARY_PICKED_NAME_holiday_2026.jpg"
        const val PICKED_URI = "content://CANARY_URI/photos"
        const val DOC_NAME = "CANARY_DOC_NAME_contract.pdf"
        const val CAPTION = "CANARY_CAPTION_meet_at_noon"
        const val RCV_CAPTION = "CANARY_RCV_CAPTION_hello"
        const val QUOTE_TEXT = "CANARY_QUOTE_TEXT"
        const val RCV_NAME_IMAGE = "CANARY_RCV_NAME_beach.jpg"
        /** A right-to-left override hides the real extension ("…exe.jpg" shown as "…gpj.exe"). */
        const val RCV_NAME_DOC = "CANARY_RCV_NAME_‮gpj.exe"
        const val EXIF_CANARY = "CANARY_EXIF_GPS_51.5074N_0.1278W"
        val DOC_BYTES: ByteArray = "%PDF-1.4 CANARY_DOC_CONTENT author=CANARY".toByteArray()
        val PLAIN_A: ByteArray = "CANARY_PLAINTEXT_photo".toByteArray() + ByteArray(300) { (it * 7).toByte() }
        val PLAIN_C: ByteArray = "CANARY_PLAINTEXT_video".toByteArray() + ByteArray(4000) { (it * 11).toByte() }
        val QUOTE_JSON = MediaSamples.quote(19, "text", QUOTE_TEXT)

        /** A JPEG whose base64 spells [PREVIEW_TOKEN] (a COM segment aligned on a base64 group): a visible preview canary. */
        const val PREVIEW_TOKEN = "CANARYPREVIEWAAA"
        val PREVIEW_URL: String = run {
            val base = DataUrl.decode(DataUrl.PLACEHOLDER)!!
            val payload = Base64.getDecoder().decode(PREVIEW_TOKEN)
            val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0, (payload.size + 2).toByte())
            DataUrl.JPEG_PREFIX + Base64.getEncoder().encodeToString(head + payload + base.copyOfRange(2, base.size))
        }

        /** The source photo: a JPEG with an APP1 Exif block carrying GPS. */
        val EXIF_PHOTO: ByteArray = run {
            val base = DataUrl.decode(DataUrl.PLACEHOLDER)!!
            val payload = "Exif\u0000\u0000$EXIF_CANARY".toByteArray(Charsets.ISO_8859_1)
            val len = payload.size + 2
            base.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + base.copyOfRange(2, base.size)
        }

        // ---- secrets ------------------------------------------------------------------------------------------------
        const val RCV_KEY = "CANARY_RCV_FILEKEY_AAAA"
        const val RCV_NONCE = "CANARY_RCV_FILENONCE_BBBB"
        /** Not ASCII: reaches the core through a hard link in link/. */
        const val PATH_A = "CANARY_RCV_PATH_ПУТЬ_a.bin"
        const val PATH_C = "CANARY_RCV_PATH_c.bin"
        /** Exactly 32 and 24 bytes as base64url, as the grammar requires. */
        val DESCR_KEY = "CANARY_DESCR_KEY_" + "A".repeat(26) + "="
        val DESCR_NONCE = "CANARY_DESCR_NONCE_" + "A".repeat(13)
        const val REPLICA_ID = "CANARY_REPLICA_ID_AA"
        const val REPLICA_KEY = "CANARY_REPLICA_KEY_A"
        const val REDIRECT_CANARY = "CANARY_REDIRECT_DIGEST"
        const val FOREIGN = "xftp://CANARYFOREIGNKEYHASH=@canary-foreign-host.example:443"
        const val CORE_TEXT = "CANARY_CORE_TEXT /data/user/0/app/files/CANARY_NAME.jpg xftp://CANARYKEY=@canary-core-host.example"
        const val SND_CORE_ERROR = "CANARY_SND_FILE_ERROR XFTP QUOTA xftp://CANARYKEY=@canary-error-host.example"
        const val SND_EVENT_ERROR = "CANARY_SND_EVENT_ERROR from canary-error-host.example"
        const val RCV_CORE_ERROR = "CANARY_RCV_FILE_ERROR file digest xftp://CANARYKEY=@canary-error-host.example"
    }
}
