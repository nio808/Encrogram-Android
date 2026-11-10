package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MediaReader
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.MediaStorage
import app.encrogram.core.api.PreparedMedia
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredError.Kind
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.api.VoiceRecording
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.ErrorMapper
import app.encrogram.core.internal.InputRules
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.SecuredLog.Ev
import app.encrogram.core.internal.ServerAddress
import app.encrogram.core.internal.media.prep.PrepException
import app.encrogram.core.internal.media.prep.Preparer
import java.io.File
import java.util.Arrays
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Runs every Secured media flow between the engine and the media parts (docs/media-plan.md §2, §5). Backend-neutral:
 * the core is reached only through [MediaTransport], [MediaVault] (with its NativeFiles) and [MediaHost].
 *
 * The rules it keeps:
 * - **Never receive before our own check.** [MediaTransport.receive] is called only with a [CheckedFile], only for a
 *   ledger entry whose recorded verdict is PASSED, and only while the engine runs ([acceptPassed] is the one call
 *   site). A tap or an auto-download decision made before the description arrives is only written to the ledger.
 * - **One message, one notification.** File events end in [MediaHost.messageChanged] (states, completion, failures)
 *   or [MediaHost.progress] (throttled), never as a new message.
 * - **No plaintext or secrets in logs or errors.** Only [SecuredLog] with fixed words (kinds, verdicts, failures,
 *   exception class names); errors are ErrorMapper's fixed sentences. Core text never reaches a [SecuredError].
 *
 * Threads:
 * - [prepare] and [discard]: any thread; the work runs on [prepExecutor], the result on main ([MediaHost.deliver]).
 * - [readBytes] and [openPlayback]: engine thread; the reading runs on [readExecutor].
 * - [storage] and [retentionDays]: any thread.
 * - Everything else: the engine thread. The ledger, the upload pins and the progress state are touched only there.
 * - A generation counter, bumped by [onStop], makes queued prep and read work give up; a result that finishes after a
 *   stop is deleted or closed, never registered. [lock] guards the counter and the prepared registry.
 */
internal class MediaCoordinator(
    private val host: MediaHost,
    private val transport: MediaTransport,
    private val vault: MediaVault,
    private val ledger: MediaLedger,
    private val preparer: Preparer,
    /** Our XFTP relays (RelayConfig.xftpServers, parsed): every server in a description must be one of them. */
    private val ourXftp: List<ServerAddress>,
    /** Preparation (decode, strip, seal): one background thread. */
    private val prepExecutor: Executor,
    /** Reads and decrypts for the UI: one background thread. */
    private val readExecutor: Executor,
    private val config: MediaPolicyConfig = MediaPolicy.DEFAULT,
    /** "Keep media for N days": a small file in the engine's no-backup folder, next to the ledger. */
    private val retention: RetentionSetting = RetentionSetting(File(ledger.folder, RETENTION_FILE)),
    /** Our uploads that haven't ended: their files are never removed. Next to the ledger. */
    private val pins: UploadPins = UploadPins(File(ledger.folder, PINS_FILE)),
    /** What retention passes measure file ages against: never ahead of the time that really passed. Next to the ledger. */
    private val retentionClock: RetentionClock = RetentionClock(File(ledger.folder, CLOCK_FILE), host::nowMs, host::elapsedMs),
) {
    private val lock = Any()
    private var generation = 0L
    private val prepared = HashMap<Long, Prepared>()
    private val tokens = AtomicLong(0)

    /** Guarded by [lock]: files handed to the read thread right now (readBytes, openPlayback until registered), by name. */
    private val reading = HashMap<String, Int>()

    // ---- engine thread only ----
    private var throttle = ProgressThrottle(host::nowMs)

    /** Plaintext sizes of files moving now, by (chatId, itemId): progress events carry only the core's padded units. */
    private val movingSizes = HashMap<Pair<Long, Long>, Long>()

    /**
     * Files the UI was last shown as here, by bare name -> (chatId, itemId), most recent [SHOWN_MAX]: after a
     * retention pass or "clear media" those messages are sent again (onMessageChanged) so their bubbles show
     * NOT_ON_DEVICE without a reopen. Anything older is mapped afresh the next time it is loaded. Memory only.
     */
    private val shown = object : LinkedHashMap<String, Pair<Long, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Long>>?) = size > SHOWN_MAX
    }

    // =============================================================================================================
    // Sending
    // =============================================================================================================

    /**
     * Any thread (the engine checked RUNNING). Prepares on the prep thread; the result arrives on main. A recording
     * ([MediaInput.Recorded]) is closed and deleted on every path: by the preparer, or here when it never runs.
     */
    fun prepare(input: MediaInput, callback: SecuredCallback<PreparedMedia>) {
        val gen = currentGeneration()
        launch(prepExecutor, callback, Ev.MEDIA_PREPARE_FAILED, onRejected = { release(input) }) {
            if (!isCurrent(gen)) {
                release(input)
                return@launch fail(callback, stopped(), Ev.MEDIA_PREPARE_FAILED, "stopped")
            }
            val p = try {
                preparer.prepare(input)
            } catch (t: Throwable) {
                release(input)
                val error = if (input is MediaInput.Recorded) voiceError(t) else prepError(t)
                return@launch fail(callback, error, Ev.MEDIA_PREPARE_FAILED, tagOf(t))
            }
            val token = synchronized(lock) {
                if (gen == generation) tokens.incrementAndGet().also { prepared[it] = p } else null
            }
            if (token == null) {
                deleteSealed(p)
                return@launch fail(callback, stopped(), Ev.MEDIA_PREPARE_FAILED, "stopped")
            }
            SecuredLog.i(Ev.MEDIA_PREPARED, p.kind.name)
            val media = PreparedMedia(
                p.kind, p.name, p.sealed.plainSize, p.mimeType, MediaMapper.previewOf(p.previewDataUrl), p.durationSeconds,
                p.unchanged, token,
            )
            host.deliver(callback, SecuredResult.Ok(media))
        }
    }

    /**
     * Engine thread (RUNNING). A new private file in `view/` for one voice recording, opened read-write; STORAGE when
     * there isn't room for the longest voice message. Delivered on main.
     */
    fun startVoiceRecording(callback: SecuredCallback<VoiceRecording>) {
        if (!vault.hasRoomFor(config.voiceMaxBytes, config)) return fail(callback, storageFull(), Ev.MEDIA_PREPARE_FAILED, "voice_room")
        val recording = try {
            vault.newRecording()
        } catch (t: Throwable) {
            return fail(callback, storageFull(), Ev.MEDIA_PREPARE_FAILED, "voice_file")
        }
        ok(callback, recording)
    }

    /** Any thread. Forgets [media] and deletes its sealed copy (on the prep thread). Already sent or unknown: nothing. */
    fun discard(media: PreparedMedia) {
        val p = synchronized(lock) { prepared.remove(media.token) } ?: return
        try {
            prepExecutor.execute { deleteSealed(p) }
        } catch (e: RejectedExecutionException) {
            deleteSealed(p)
        }
    }

    /**
     * Engine thread, RUNNING, after the engine's own checks (chat reference, posting allowed, Saved Messages refused,
     * quote found and not deleted). [media] is used once: from the commit on, it is gone whatever happens. A caption
     * that doesn't fit keeps it, so the user can shorten the caption and send again.
     */
    fun send(chatId: Long, ref: ChatRef, media: PreparedMedia, caption: String, quoted: CoreItem?, callback: SecuredCallback<SecuredMessage>) {
        val p = synchronized(lock) { prepared[media.token] } ?: return fail(callback, preparedGone(), Ev.MEDIA_FAILED, "prepared_gone")
        captionProblem(p, caption, quoted)?.let { return fail(callback, it, Ev.MEDIA_FAILED, "caption") }
        if (synchronized(lock) { prepared.remove(media.token) } == null) return fail(callback, preparedGone(), Ev.MEDIA_FAILED, "prepared_gone")

        val name = try {
            vault.commit(p.sealed, p.name)
        } catch (t: Throwable) {
            deleteSealed(p)
            return fail(callback, prepError(t), Ev.MEDIA_FAILED, "commit")
        }
        val content = OutgoingContent(p.kind, caption, p.previewDataUrl, p.durationSeconds)
        val reply = try {
            transport.send(ref, name, p.sealed.key, content, quoted?.itemId)
        } catch (t: Throwable) {
            // A bad argument (MediaCommands' require) is our bug; nothing reached the core.
            vault.uncommit(name)
            SecuredLog.crash(Ev.MEDIA_FAILED, t)
            return fail(callback, ErrorMapper.engine("internal"))
        }
        when (reply) {
            is TransportReply.Item -> {
                // Sent, but no item of this chat came back: the file stays (the core references it), pinned for good.
                val item = reply.item?.takeIf { it.chatId == chatId } ?: run {
                    pins.add(chatId, UploadPins.UNKNOWN_ITEM, name)
                    return fail(callback, ErrorMapper.engine("noItem"), Ev.MEDIA_FAILED, "send")
                }
                // Until its upload ends, retention and "clear media" leave this file alone.
                pins.add(chatId, item.itemId, name)
                SecuredLog.i(Ev.MEDIA_SENT, p.kind.name)
                host.deliver(callback, SecuredResult.Ok(host.toMessage(item)))
                host.itemsAdded(listOf(item))
            }
            is TransportReply.Refused -> {
                vault.uncommit(name)
                fail(callback, ErrorMapper.mediaFailure(reply.failure), Ev.MEDIA_FAILED, reply.failure.name)
            }
            is TransportReply.Failed -> {
                // An error reply means the core refused it: the file goes. With no readable reply the core may already
                // reference it, so it stays (the core's own delete removes it with the message), pinned for good.
                if (reply.error !in KEEP_COMMITTED) vault.uncommit(name) else pins.add(chatId, UploadPins.UNKNOWN_ITEM, name)
                fail(callback, reply.error, Ev.MEDIA_FAILED, "send")
            }
        }
    }

    /** The caption must be at most 1024 characters, and the whole message (preview, name, quote) must fit one body. */
    private fun captionProblem(p: Prepared, caption: String, quoted: CoreItem?): SecuredError? {
        if (!MediaPolicy.captionLengthOk(caption, config)) return ErrorMapper.invalid(ErrorMapper.MEDIA_CAPTION_TOO_LONG)
        // A media reply's quote carries text only: the quoted caption, or a captionless file's name (qTextOrFile).
        val quoteBytes = quoted?.let { InputRules.encodedTextBytes(it.text.ifEmpty { it.file?.name.orEmpty() }) + InputRules.QUOTE_OVERHEAD_BYTES } ?: 0
        val fits = InputRules.mediaFits(caption, p.previewDataUrl?.length ?: 0, InputRules.encodedTextBytes(p.name), quoteBytes)
        if (fits) return null
        return ErrorMapper.invalid(if (quoted != null) ErrorMapper.MEDIA_REPLY_TOO_LONG else ErrorMapper.MEDIA_CAPTION_TOO_LONG)
    }

    // =============================================================================================================
    // Receiving
    // =============================================================================================================

    /**
     * Engine thread, in ANY engine state: handleEvent calls it for `rcvFileDescrReady` before its RUNNING guard. Our
     * check of the description, recorded in the ledger (the text is checked, then dropped). A command is sent only
     * while the engine runs; otherwise [catchUp] does it.
     */
    fun recordDescription(body: JSONObject) {
        val ev = transport.decode(DESCR_READY, body) as? MediaEvent.DescriptionReady
        if (ev == null) {
            SecuredLog.w(Ev.MEDIA_DESCR, "unreadable")
            return
        }
        record(ev)
    }

    private fun record(ev: MediaEvent.DescriptionReady) {
        val item = ev.item
        val file = item.file ?: return
        if (!file.statusTag.startsWith("rcv")) return
        val verdict = FileDescriptionCheck.check(ev.text, ev.invitedSize, ev.protocol, ourXftp, config.capBytes)
        val existing = ledger.get(file.fileId, item.sharedMsgId)
        val kept = existing?.verdict
        // The first verdict wins, except that a later refusal overrides a pass (a complete description can't change,
        // so a second one is suspicious).
        val recorded = if (kept == null || (kept == Verdict.PASSED && verdict != Verdict.PASSED)) verdict else kept
        val entry = entryFor(item, file, existing).copy(verdict = recorded)
        ledger.put(entry, item.sharedMsgId)
        SecuredLog.i(Ev.MEDIA_DESCR, recorded.name)
        if (!host.running()) return
        if (recorded != Verdict.PASSED) {
            host.messageChanged(listOf(item))
            return
        }
        if (file.statusTag == RCV_INVITATION) fetchIfWanted(entry, item)
    }

    /** Engine thread, RUNNING: tap to download [item] (as the engine's itemOrErr found it). */
    fun accept(item: CoreItem, callback: SecuredCallback<Unit>) {
        val file = item.file
        if (file == null || item.deleted || !file.statusTag.startsWith("rcv")) return fail(callback, ErrorMapper.notAllowed(ErrorMapper.MEDIA_NONE))
        if (file.protocol != XFTP) return fail(callback, ErrorMapper.mediaFailure(MediaFailure.UNSUPPORTED), Ev.MEDIA_FAILED, "protocol")
        if (!MediaPolicy.withinCap(file.sizeBytes, config)) return fail(callback, ErrorMapper.mediaFailure(MediaFailure.TOO_LARGE), Ev.MEDIA_FAILED, "cap")
        if (file.statusTag != RCV_INVITATION) {
            // Already accepted, moving, done or stopped: say what it is.
            val m = FileStatusMapper.map(file, false)
            return if (m.state == MediaState.FAILED) fail(callback, ErrorMapper.mediaFailure(m.failure ?: MediaFailure.RECEIVE_FAILED)) else ok(callback, Unit)
        }
        val entry = ledger.get(file.fileId, item.sharedMsgId)
        val verdict = entry?.verdict
        when {
            verdict == null -> {
                if (MediaPolicy.descriptionOverdue(host.nowMs(), item.timeMs, config)) {
                    return fail(callback, ErrorMapper.mediaFailure(MediaFailure.EXPIRED), Ev.MEDIA_FAILED, "overdue")
                }
                // Not checked yet: only the wish is kept. The download starts when a passing description arrives.
                ledger.put(entryFor(item, file, entry).copy(wanted = true, cancelled = false), item.sharedMsgId)
                SecuredLog.i(Ev.MEDIA_ACCEPTED, "queued")
                ok(callback, Unit)
                host.messageChanged(listOf(item))
            }
            verdict != Verdict.PASSED ->
                fail(callback, ErrorMapper.mediaFailure(FileStatusMapper.verdictFailure(verdict)), Ev.MEDIA_FAILED, verdict.name)
            else -> {
                if (!vault.hasRoomFor(file.sizeBytes, config)) return fail(callback, storageFull(), Ev.MEDIA_FAILED, "space")
                ledger.markWanted(file.fileId)
                val wanted = ledger.get(file.fileId, item.sharedMsgId) ?: entry
                val problem = acceptPassed(wanted, item)
                if (problem == null) ok(callback, Unit) else fail(callback, problem)
            }
        }
    }

    /** PASSED, not accepted or cancelled, and wanted or auto: start it when there is room for it. */
    private fun fetchIfWanted(entry: LedgerEntry, item: CoreItem?) {
        if (!shouldFetch(entry)) return
        if (!vault.hasRoomFor(entry.sizeBytes, config)) {
            // Back to "tap to download"; a tap then says the phone is full.
            ledger.markCancelled(entry.fileId)
            SecuredLog.w(Ev.MEDIA_FAILED, "space")
            item?.let { host.messageChanged(listOf(it)) }
            return
        }
        acceptPassed(entry, item)
    }

    private fun shouldFetch(e: LedgerEntry): Boolean =
        e.verdict == Verdict.PASSED && !e.accepted && !e.cancelled && (e.wanted || MediaPolicy.autoDownload(e.kind, e.sizeBytes, config))

    /**
     * The only place a download starts. [entry] must be the ledger's own (bound to the item's message by the caller).
     * Null when started or already running; otherwise why not.
     */
    private fun acceptPassed(entry: LedgerEntry, item: CoreItem?): SecuredError? {
        val checked = CheckedFile.of(entry.fileId, entry.verdict) ?: return ErrorMapper.mediaFailure(MediaFailure.CHECK_FAILED)
        if (!host.running()) return stopped()
        return when (val r = transport.receive(checked)) {
            is TransportReply.Item -> {
                val got = r.item
                if (got != null && (got.chatId != entry.chatId || got.itemId != entry.itemId)) {
                    // The core started another message's file (a reused id): not what we checked. Stop it at once.
                    transport.cancel(entry.fileId)
                    ledger.remove(entry.fileId)
                    SecuredLog.w(Ev.MEDIA_FAILED, "mismatch")
                    return ErrorMapper.mediaFailure(MediaFailure.CHECK_FAILED)
                }
                ledger.markAccepted(entry.fileId)
                movingSizes[entry.chatId to entry.itemId] = entry.sizeBytes
                SecuredLog.i(Ev.MEDIA_ACCEPTED, entry.kind.name)
                (r.item ?: item)?.let { host.messageChanged(listOf(it)) }
                null
            }
            is TransportReply.Refused -> {
                val v = when (r.failure) {
                    MediaFailure.FOREIGN_SERVER -> Verdict.FOREIGN_SERVER
                    MediaFailure.TOO_LARGE -> Verdict.TOO_LARGE
                    else -> null
                }
                // The core refused what our check passed. With the message known the refusal is recorded as its verdict;
                // without it (catch-up) the entry goes back to "tap to download", and the tap records it.
                if (v != null && item != null) ledger.put(entry.copy(verdict = v, accepted = false), item.sharedMsgId)
                else ledger.markCancelled(entry.fileId)
                SecuredLog.w(Ev.MEDIA_FAILED, r.failure.name)
                item?.let { host.messageChanged(listOf(it)) }
                ErrorMapper.mediaFailure(r.failure)
            }
            is TransportReply.Failed -> {
                // The message (and its file) is gone: nothing left to fetch.
                if (r.error == ErrorMapper.messageGone()) ledger.remove(entry.fileId)
                SecuredLog.w(Ev.MEDIA_FAILED, "accept")
                r.error
            }
        }
    }

    /**
     * Engine thread, RUNNING. Incoming: a queued download is unqueued (this beats auto-download), a running one is
     * stopped with `/fcancel` (the core puts the item back to its invitation); either way it shows WAITING and a later
     * tap starts it again. Outgoing: the upload stops (FAILED, CANCELLED). A finished or failed transfer: nothing to do.
     */
    fun cancel(item: CoreItem, callback: SecuredCallback<Unit>) {
        val file = item.file ?: return fail(callback, ErrorMapper.notAllowed(ErrorMapper.MEDIA_NONE))
        if (!file.statusTag.startsWith("rcv")) {
            if (file.statusTag !in UPLOADING) return ok(callback, Unit)
            return when (val r = transport.cancel(file.fileId)) {
                is TransportReply.Item -> {
                    SecuredLog.i(Ev.MEDIA_CANCELLED, "upload")
                    pins.remove(item.chatId, item.itemId)
                    forget(item.chatId, item.itemId)
                    ok(callback, Unit)
                    r.item?.let { host.messageChanged(listOf(it)) } ?: Unit
                }
                is TransportReply.Refused -> fail(callback, ErrorMapper.mediaFailure(r.failure), Ev.MEDIA_FAILED, "cancel")
                is TransportReply.Failed -> fail(callback, r.error, Ev.MEDIA_FAILED, "cancel")
            }
        }
        val entry = ledger.get(file.fileId, item.sharedMsgId)
        val inCore = file.statusTag in DOWNLOADING || (file.statusTag == RCV_INVITATION && entry?.accepted == true)
        if (inCore) {
            val reset = when (val r = transport.cancel(file.fileId)) {
                is TransportReply.Item -> r.item
                is TransportReply.Refused -> return fail(callback, ErrorMapper.mediaFailure(r.failure), Ev.MEDIA_FAILED, "cancel")
                is TransportReply.Failed -> return fail(callback, r.error, Ev.MEDIA_FAILED, "cancel")
            }
            // No item back: the transfer had already ended, there was nothing to stop.
            if (reset == null) return ok(callback, Unit)
            markCancelled(item, file, entry)
            forget(item.chatId, item.itemId)
            SecuredLog.i(Ev.MEDIA_CANCELLED, "download")
            ok(callback, Unit)
            host.messageChanged(listOf(reset))
            return
        }
        if (file.statusTag != RCV_INVITATION) return ok(callback, Unit)
        markCancelled(item, file, entry)
        SecuredLog.i(Ev.MEDIA_CANCELLED, "queued")
        ok(callback, Unit)
        host.messageChanged(listOf(item))
    }

    private fun markCancelled(item: CoreItem, file: CoreFile, entry: LedgerEntry?) {
        if (entry != null) ledger.markCancelled(file.fileId)
        else ledger.put(entryFor(item, file, null).copy(cancelled = true), item.sharedMsgId)
    }

    /** Engine thread, RUNNING reached: downloads that waited for the engine (recorded during start, or before a restart). */
    fun catchUp() {
        val due = ledger.entries().filter { shouldFetch(it) }
        if (due.isEmpty()) return
        SecuredLog.i(Ev.MEDIA_CATCHUP, null, due.size)
        for (entry in due) {
            if (!host.running()) break
            // A file id can be reused once its message is deleted (the core's files.file_id has no AUTOINCREMENT), so
            // the verdict counts only for the very message it was recorded for, still holding that file (MD9 finding 1).
            val item = host.currentItem(entry.chatId, entry.itemId)
            if (item == null || item.deleted || item.file?.fileId != entry.fileId || ledger.get(entry.fileId, item.sharedMsgId) == null) {
                ledger.remove(entry.fileId)
                continue
            }
            if (item.file?.statusTag == RCV_INVITATION) fetchIfWanted(entry, item)
        }
    }

    // =============================================================================================================
    // Events and annotation
    // =============================================================================================================

    /** Engine thread, RUNNING: handleEvent routes every type in [EVENTS] here (after its guard). */
    fun onEvent(type: String, body: JSONObject) {
        when (val ev = transport.decode(type, body)) {
            null -> Unit
            is MediaEvent.DescriptionReady -> record(ev)
            is MediaEvent.Progress -> progress(ev)
            is MediaEvent.Changed -> changed(ev)
        }
    }

    private fun progress(ev: MediaEvent.Progress) {
        if (!throttle.shouldEmit(ev.chatId, ev.itemId, ev.done, ev.total)) return
        val size = movingSizes[ev.chatId to ev.itemId] ?: ledger.entries().firstOrNull { it.chatId == ev.chatId && it.itemId == ev.itemId }?.sizeBytes
        if (size != null && size > 0) {
            host.progress(ev.chatId, ev.itemId, FileStatusMapper.scaleProgress(size, ev.done, ev.total), size)
        } else {
            // Size unknown (an upload from before a restart, not yet shown): the core's units still give the ratio.
            host.progress(ev.chatId, ev.itemId, ev.done.coerceIn(0L, ev.total), ev.total)
        }
    }

    private fun changed(ev: MediaEvent.Changed) {
        val item = ev.item ?: return
        val file = item.file
        when (ev.tag) {
            "rcvFileComplete", "sndFileCompleteXFTP" -> {
                if (ev.tag == "rcvFileComplete" && file != null) ledger.remove(file.fileId)
                if (ev.tag == "sndFileCompleteXFTP") pins.remove(item.chatId, item.itemId)
                forget(item.chatId, item.itemId)
                SecuredLog.i(Ev.MEDIA_DONE, file?.kind?.name)
            }
            "rcvFileError", "rcvFileSndCancelled", "sndFileError", "sndFileCancelled" -> {
                if (ev.tag.startsWith("rcv") && file != null) ledger.remove(file.fileId)
                if (ev.tag.startsWith("snd")) pins.remove(item.chatId, item.itemId)
                forget(item.chatId, item.itemId)
                // Failure plus the core's error type (auth, blocked, noFile, relay, other): fixed tokens, never its text.
                SecuredLog.w(Ev.MEDIA_FAILED, file?.let { f ->
                    val failure = FileStatusMapper.map(f, !f.statusTag.startsWith("rcv")).failure?.name
                    listOfNotNull(failure, f.errorTag, f.errorWords).joinToString(".")
                } ?: ev.tag)
            }
            // Still moving (a retry): nothing new to show.
            "rcvFileWarning", "sndFileWarning" -> {
                SecuredLog.i(Ev.MEDIA_EVENT, ev.tag)
                return
            }
            // An incomplete description: nothing to record yet.
            DESCR_READY -> return
            else -> SecuredLog.i(Ev.MEDIA_EVENT, ev.tag)
        }
        host.messageChanged(listOf(item))
    }

    /**
     * Engine thread. [items] with what this phone knows about their files ([LocalFacts]): verdict and choices from the
     * ledger (only when bound to the same message), auto-download from [MediaPolicy], overdue, and whether the
     * encrypted file is here (at most one stat per item, only where the state depends on it).
     */
    fun annotate(items: List<CoreItem>): List<CoreItem> {
        if (items.none { it.file != null }) return items
        val now = host.nowMs()
        return items.map { item ->
            val f = item.file
            if (f == null || item.deleted) item else item.copy(file = f.copy(local = factsOf(item, f, now)))
        }
    }

    private fun factsOf(item: CoreItem, f: CoreFile, now: Long): LocalFacts {
        val receiving = f.statusTag.startsWith("rcv")
        val needsStat = f.path != null && (!receiving || f.statusTag == RCV_COMPLETE || f.protocol == LOCAL)
        val present = needsStat && vault.present(f.path)
        if (present) f.path?.let { shown[it] = item.chatId to item.itemId }
        if (f.statusTag in UPLOADING || f.statusTag in DOWNLOADING) movingSizes[item.chatId to item.itemId] = f.sizeBytes
        if (!receiving) {
            trackUpload(item, f)
            return LocalFacts(present = present)
        }
        val e = ledger.get(f.fileId, item.sharedMsgId)
        val verdict = e?.verdict
        return LocalFacts(
            verdict = verdict,
            wanted = e?.wanted == true,
            cancelled = e?.cancelled == true,
            accepted = e?.accepted == true,
            auto = MediaPolicy.autoDownload(f.kind, f.sizeBytes, config),
            present = present,
            overdue = verdict == null && f.statusTag == RCV_INVITATION && MediaPolicy.descriptionOverdue(now, item.timeMs, config),
        )
    }

    /**
     * Keeps [UploadPins] true to what the core shows: an upload still running is pinned (one sent before pins existed,
     * or whose pin was lost), one that has ended is released (its end event came while the engine wasn't running).
     * Writes only when that changes something.
     */
    private fun trackUpload(item: CoreItem, f: CoreFile) {
        if (f.protocol == LOCAL || item.deleted) return
        val path = f.path
        when (f.statusTag) {
            in UPLOADING -> if (path != null && !pins.has(item.chatId, item.itemId)) pins.add(item.chatId, item.itemId, path)
            in UPLOAD_ENDED -> if (!pins.isEmpty() && pins.has(item.chatId, item.itemId)) pins.remove(item.chatId, item.itemId)
        }
    }

    // =============================================================================================================
    // Reading
    // =============================================================================================================

    /** Engine thread, RUNNING: the whole plaintext in memory (up to the 16 MB limit), read on the read thread. */
    fun readBytes(item: CoreItem, callback: SecuredCallback<ByteArray>) {
        val src = source(item, callback) ?: return
        if (!MediaPolicy.readable(src.size, config)) {
            return fail(callback, SecuredError(Kind.TOO_LARGE, ErrorMapper.MEDIA_TOO_LARGE_TO_OPEN), Ev.MEDIA_READ_FAILED, "cap")
        }
        val gen = currentGeneration()
        // Held from here (engine thread, so no retention pass can slip in between) until the read is over.
        hold(src.path)
        launch(readExecutor, callback, Ev.MEDIA_READ_FAILED, onRejected = { unhold(src.path) }) {
            try {
                if (!isCurrent(gen)) return@launch fail(callback, stopped(), Ev.MEDIA_READ_FAILED, "stopped")
                val bytes = try {
                    vault.readBytes(src.path, src.key, config.readBytesLimit)
                } catch (t: Throwable) {
                    return@launch fail(callback, readError(t), Ev.MEDIA_READ_FAILED, tagOf(t))
                }
                if (!isCurrent(gen)) {
                    Arrays.fill(bytes, 0.toByte())
                    return@launch fail(callback, stopped(), Ev.MEDIA_READ_FAILED, "stopped")
                }
                host.deliver(callback, SecuredResult.Ok(bytes))
            } finally {
                unhold(src.path)
            }
        }
    }

    /**
     * Engine thread, RUNNING: random access to a readable file of any size. Up to the memory limit it is read into
     * memory; above it, decrypted into a private `view/` file that closing deletes. Registered so a delete or a stop
     * closes it.
     */
    fun openPlayback(item: CoreItem, callback: SecuredCallback<MediaPlayback>) {
        val src = source(item, callback) ?: return
        val inMemory = MediaPolicy.readable(src.size, config)
        if (!inMemory && vault.freeBytes() < src.size + config.freeSpaceExtraBytes) {
            return fail(callback, storageFull(), Ev.MEDIA_READ_FAILED, "space")
        }
        val gen = currentGeneration()
        // Held until the playback is registered with its source (which then keeps the file until it closes).
        hold(src.path)
        launch(readExecutor, callback, Ev.MEDIA_READ_FAILED, onRejected = { unhold(src.path) }) {
            try {
                if (!isCurrent(gen)) return@launch fail(callback, stopped(), Ev.MEDIA_READ_FAILED, "stopped")
                val reader: MediaReader = try {
                    if (inMemory) ByteArrayMediaReader(vault.readBytes(src.path, src.key, config.readBytesLimit))
                    else FileMediaReader(vault.decryptToView(src.path, src.key))
                } catch (t: Throwable) {
                    return@launch fail(callback, readError(t), Ev.MEDIA_READ_FAILED, tagOf(t))
                }
                val playback = synchronized(lock) {
                    if (gen == generation) {
                        vault.playbacks.open(src.chatId, src.itemId, src.kind, src.mimeType, src.durationSeconds, reader, src.path)
                    } else null
                }
                if (playback == null) {
                    // Finished after a stop: never handed out, and its view/ copy is deleted now.
                    reader.close()
                    return@launch fail(callback, stopped(), Ev.MEDIA_READ_FAILED, "stopped")
                }
                host.deliver(callback, SecuredResult.Ok(playback))
            } finally {
                unhold(src.path)
            }
        }
    }

    private class Source(
        val chatId: Long,
        val itemId: Long,
        val kind: MediaKind,
        val path: String?,
        val key: FileKey,
        val size: Long,
        val mimeType: String?,
        val durationSeconds: Int?,
    ) {
        override fun toString() = "Source($kind)"
    }

    /** The item's file when it can be opened now (engine thread); otherwise the reason goes to [callback] and null. */
    private fun <T> source(item: CoreItem, callback: SecuredCallback<T>): Source? {
        val file = if (item.deleted) null else annotate(listOf(item)).first().file
        if (file == null) {
            fail(callback, ErrorMapper.notAllowed(ErrorMapper.MEDIA_NONE))
            return null
        }
        val m = FileStatusMapper.map(file, !file.statusTag.startsWith("rcv"))
        if (!m.readable) {
            val error = if (m.state == MediaState.FAILED) ErrorMapper.mediaFailure(m.failure ?: MediaFailure.NOT_ON_DEVICE)
            else ErrorMapper.notAllowed(ErrorMapper.MEDIA_NOT_READY)
            fail(callback, error, Ev.MEDIA_READ_FAILED, m.failure?.name ?: m.state.name)
            return null
        }
        // Every file we write or receive is stored encrypted; one without its key can't be opened.
        val key = file.key ?: run {
            fail(callback, ErrorMapper.mediaFailure(MediaFailure.NOT_ON_DEVICE), Ev.MEDIA_READ_FAILED, "no_key")
            return null
        }
        return Source(item.chatId, item.itemId, file.kind, file.path, key, file.sizeBytes, MediaMapper.mimeOf(file.kind, file.name), item.durationSeconds)
    }

    // =============================================================================================================
    // Lifecycle
    // =============================================================================================================

    /** Engine thread, after the store opened and its file paths were set: clears what a crash left in prep/, view/, link/. */
    fun onStoreOpened() {
        vault.wipeTransient()
    }

    /**
     * Engine thread: the store was replaced (restore, reset). File ids start again, so every verdict and upload pin goes
     * too; the retention setting is read again (a reset deletes its file with the folder).
     */
    fun onStoreReplaced() {
        vault.wipeTransient()
        ledger.clear()
        pins.clear()
        retention.forget()
        movingSizes.clear()
        shown.clear()
        throttle = ProgressThrottle(host::nowMs)
    }

    /**
     * Engine thread, before the store closes: queued prep and read work will give up, prepared files are deleted and
     * every playback is closed. Then the engine closes the store through CtrlGate and calls [afterClose].
     */
    fun onStop() {
        val dropped = synchronized(lock) {
            generation++
            ArrayList(prepared.values).also { prepared.clear() }
        }
        for (p in dropped) deleteSealed(p)
        vault.playbacks.closeAll()
        movingSizes.clear()
        shown.clear()
        throttle = ProgressThrottle(host::nowMs)
    }

    /** Engine thread, after CtrlGate.close: no native call can still write, so prep/, view/ and link/ are wiped. */
    fun afterClose() {
        vault.wipeTransient()
    }

    /** Engine thread: messages removed (applyDeletions). Their playbacks close and their ledger rows go. */
    fun onRemoved(chatId: Long, itemIds: Collection<Long>) {
        if (itemIds.isEmpty()) return
        vault.playbacks.closeFor(chatId, itemIds)
        ledger.removeForChat(chatId, itemIds)
        pins.removeItems(chatId, itemIds)
        for (id in itemIds) forget(chatId, id)
    }

    /** Engine thread: a whole chat deleted or cleared. */
    fun onChatRemoved(chatId: Long) {
        vault.playbacks.closeChat(chatId)
        ledger.removeForChat(chatId, ledger.entries().filter { it.chatId == chatId }.map { it.itemId })
        pins.removeChat(chatId)
        movingSizes.keys.removeAll { it.first == chatId }
    }

    // =============================================================================================================
    // Storage and retention ("keep media", A7)
    // =============================================================================================================

    /** Any thread (no core call; lists and stats the files folder): the encrypted media on this phone. */
    fun storage(): MediaStorage = vault.storage()

    /** Any thread: days media is kept, or null for forever (the default). The first call reads one small file. */
    fun retentionDays(): Int? = retention.days()

    /**
     * Engine thread. Saves "keep media for [days]" (null: forever), then, while the engine runs, removes what is
     * already older at once. Null when done; otherwise why not (nothing changed).
     */
    fun setRetentionDays(days: Int?): SecuredError? {
        if (!MediaRetention.valid(days)) return ErrorMapper.invalid(RETENTION_INVALID)
        if (!retention.set(days)) return SecuredError(Kind.STORAGE, ErrorMapper.STORAGE)
        SecuredLog.i(Ev.MEDIA_WIPED, if (days == null) "keep_forever" else "keep_days", days)
        if (days != null && host.running()) applyRetention()
        return null
    }

    /**
     * Engine thread: removes the media files older than the retention setting (nothing when it is forever). Safe to call
     * at every start and once a day; it only lists the files folder and empties what is due. Returns how many files
     * were removed, or null when Saved Messages couldn't be listed (then nothing was). Their messages stay and show
     * NOT_ON_DEVICE; nothing downloads them again (the core's record says complete, and /freceive refuses anything but
     * a new offer).
     *
     * Ages are measured against [RetentionClock] (a wall clock set ahead can't age anything); every pass steps it, even
     * with the setting at forever, so it is current when the user turns retention on. While the wall clock is unset
     * (before [RetentionClock.FLOOR_MS]) nothing is removed. A file dated before that floor was written while it was
     * unset: it is dated now, in place, and counted from this pass ([redateUnset]).
     */
    fun applyRetention(): Int? {
        val now = retentionClock.passNow()
        val days = retention.days() ?: return 0
        if (now == null) {
            SecuredLog.w(Ev.MEDIA_WIPED, "clock_unset")
            return 0
        }
        return removeFiles(now, days, "retention")
    }

    /**
     * Engine thread: removes every media file now, except those in use and Saved Messages' (see [removeFiles]). Returns
     * how many, or null when Saved Messages couldn't be listed (then nothing was removed).
     */
    fun clearAll(nowMs: Long = host.nowMs()): Int? = removeFiles(nowMs, null, "clear")

    /**
     * True when [item]'s file was here and is gone (NOT_ON_DEVICE). Engine thread. For the engine's forward: the core
     * would find the emptied file, fail to copy it and refuse the whole forward, so such items are better refused first.
     */
    fun fileGone(item: CoreItem): Boolean {
        if (item.deleted || item.file == null) return false
        val f = annotate(listOf(item)).first().file ?: return false
        val m = FileStatusMapper.map(f, !f.statusTag.startsWith("rcv"))
        return m.state == MediaState.FAILED && m.failure == MediaFailure.NOT_ON_DEVICE
    }

    /**
     * Empties every listed file that [MediaRetention.select] picks for [days] (null: all), except, whatever its age:
     * - our own upload that hasn't ended ([UploadPins]; first refreshed from the core while it runs);
     * - a file handed to the read thread right now (readMediaBytes, an opening playback) or open for playback;
     * - an empty file (a download's placeholder: the core fills it in one go at the end) and anything written in the
     *   last [MediaRetention.FRESH_MS] (a download's final write, a commit, a forward copy);
     * - a prepared attachment: it lives in `prep/`, never in the files folder;
     * - a file Saved Messages holds ([MediaHost.savedFiles]): the user's own copy, which can't be downloaded again
     *   (lead decision, 9 November 2026). When those can't be listed, nothing at all is removed (null).
     * Plaintext copies need no extra step: a `view/` copy exists only for an open playback or a read in flight, whose
     * source is kept, and leftovers of a crash are wiped at every store open.
     *
     * Then the messages whose files went and that the UI was shown ([shown]) are sent again, so open chats update.
     */
    private fun removeFiles(nowMs: Long, days: Int?, tag: String): Int? {
        val saved = host.savedFiles() ?: run {
            SecuredLog.w(Ev.MEDIA_IO_FAILED, "saved_unlisted")
            return null
        }
        refreshPins()
        val pinned = pins.hashes()
        val inUse = vault.playbacks.sources() + synchronized(lock) { HashSet(reading.keys) }
        val keep: (String) -> Boolean = { name ->
            name in saved || name in inUse || (pinned.isNotEmpty() && MediaRetention.nameHash(name) in pinned)
        }
        val listed = vault.storedFiles()
        val due = MediaRetention.select(if (days == null) listed else redateUnset(listed, nowMs), nowMs, days, keep)
        val gone = due.filter { vault.expire(it) }
        if (gone.size < due.size) SecuredLog.w(Ev.MEDIA_IO_FAILED, "expire", due.size - gone.size)
        SecuredLog.i(Ev.MEDIA_WIPED, tag, gone.size)
        tellRemoved(gone)
        return gone.size
    }

    /**
     * Retention only: [files] without those dated before [RetentionClock.FLOOR_MS]. Such a file was written while the
     * phone's clock was unset (a phone that booted at 1970) and is not decades old: it is dated [nowMs] in place, so this
     * and every later pass count its age from when retention first saw it.
     */
    private fun redateUnset(files: List<StoredFile>, nowMs: Long): List<StoredFile> {
        val (unset, dated) = files.partition { it.bytes > 0 && it.modifiedMs < RetentionClock.FLOOR_MS }
        if (unset.isEmpty()) return files
        val done = unset.count { vault.redate(it, nowMs) }
        SecuredLog.i(Ev.MEDIA_WIPED, "redated", done)
        if (done < unset.size) SecuredLog.w(Ev.MEDIA_IO_FAILED, "redate", unset.size - done)
        return dated
    }

    /** Sends again the shown messages whose files [gone] held, as the store holds them now (NOT_ON_DEVICE). */
    private fun tellRemoved(gone: List<StoredFile>) {
        if (gone.isEmpty() || shown.isEmpty()) return
        val ids = gone.mapNotNullTo(LinkedHashSet()) { shown.remove(it.name) }
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { (chatId, itemId) ->
            try {
                host.currentItem(chatId, itemId)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.MEDIA_IO_FAILED, t)
                null
            }
        }
        if (items.isNotEmpty()) host.messageChanged(items)
    }

    /**
     * Releases pins whose upload has ended, on the core's word (engine running). A message the core can't find, or
     * any doubt, keeps its pin: deleting the message removes its pin (or its file) anyway.
     */
    private fun refreshPins() {
        if (pins.isEmpty() || !host.running()) return
        for (pin in pins.entries()) {
            if (pin.itemId == UploadPins.UNKNOWN_ITEM) continue
            val item = try {
                host.currentItem(pin.chatId, pin.itemId)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.MEDIA_IO_FAILED, t)
                null
            } ?: continue
            val f = item.file
            if (item.deleted || f == null || f.statusTag in UPLOAD_ENDED) pins.remove(pin.chatId, pin.itemId)
        }
    }

    // =============================================================================================================
    // Plumbing
    // =============================================================================================================

    private fun currentGeneration(): Long = synchronized(lock) { generation }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { gen == generation }

    private fun hold(name: String?) {
        if (name == null) return
        synchronized(lock) { reading[name] = (reading[name] ?: 0) + 1 }
    }

    private fun unhold(name: String?) {
        if (name == null) return
        synchronized(lock) {
            val n = (reading[name] ?: 0) - 1
            if (n <= 0) reading.remove(name) else reading[name] = n
        }
    }

    private fun forget(chatId: Long, itemId: Long) {
        movingSizes.remove(chatId to itemId)
        throttle.forget(chatId, itemId)
    }

    /** A ledger row for [item]'s [file], keeping what [existing] already knew. Ids, enums and sizes only. */
    private fun entryFor(item: CoreItem, file: CoreFile, existing: LedgerEntry?): LedgerEntry = LedgerEntry(
        fileId = file.fileId,
        chatId = item.chatId,
        itemId = item.itemId,
        kind = file.kind,
        sizeBytes = file.sizeBytes,
        verdict = existing?.verdict,
        wanted = existing?.wanted == true,
        cancelled = existing?.cancelled == true,
        accepted = existing?.accepted == true,
        receivedMs = item.timeMs,
    )

    private fun deleteSealed(p: Prepared) {
        try {
            val f = p.sealed.file
            if (f.exists() && !f.delete()) SecuredLog.w(Ev.MEDIA_IO_FAILED, "delete")
        } catch (e: SecurityException) {
            SecuredLog.w(Ev.MEDIA_IO_FAILED, "delete")
        }
    }

    /** Runs [task] on [executor]; a crash or a refused task becomes a readable error for [callback]. */
    private fun <T> launch(executor: Executor, callback: SecuredCallback<T>, ev: Ev, onRejected: () -> Unit = {}, task: () -> Unit) {
        val job = Runnable {
            try {
                task()
            } catch (t: Throwable) {
                SecuredLog.crash(ev, t)
                fail(callback, ErrorMapper.engine("internal"))
            }
        }
        try {
            executor.execute(job)
        } catch (e: RejectedExecutionException) {
            onRejected()
            fail(callback, stopped(), ev, "rejected")
        }
    }

    private fun <T> ok(callback: SecuredCallback<T>, value: T) = host.deliver(callback, SecuredResult.Ok(value))

    /** Delivers [error]; with [ev], also logs [tag] (a fixed word). */
    private fun <T> fail(callback: SecuredCallback<T>, error: SecuredError, ev: Ev? = null, tag: String? = null) {
        if (ev != null) SecuredLog.w(ev, tag)
        host.deliver(callback, SecuredResult.Err(error))
    }

    private fun stopped() = SecuredError(Kind.ENGINE_NOT_RUNNING, ErrorMapper.NOT_RUNNING)

    private fun preparedGone() = ErrorMapper.invalid(ErrorMapper.MEDIA_PREPARED_GONE)

    private fun storageFull() = SecuredError(Kind.STORAGE, ErrorMapper.MEDIA_STORAGE_FULL)

    /** A voice recording's refusals in its own words; anything else as for any file. */
    private fun voiceError(t: Throwable): SecuredError = when ((t as? PrepException)?.reason) {
        PrepException.Reason.TOO_LARGE -> SecuredError(Kind.TOO_LARGE, ErrorMapper.VOICE_TOO_LONG)
        PrepException.Reason.UNSUPPORTED, PrepException.Reason.UNREADABLE, PrepException.Reason.EMPTY ->
            SecuredError(Kind.UNSUPPORTED, ErrorMapper.VOICE_UNREADABLE)
        else -> prepError(t)
    }

    /** Failures while preparing or committing. */
    private fun prepError(t: Throwable): SecuredError = when (t) {
        is PrepException -> when (t.reason) {
            PrepException.Reason.TOO_LARGE -> ErrorMapper.mediaFailure(MediaFailure.TOO_LARGE)
            PrepException.Reason.UNSUPPORTED -> SecuredError(Kind.UNSUPPORTED, ErrorMapper.MEDIA_UNSUPPORTED)
            PrepException.Reason.STORAGE -> storageFull()
            PrepException.Reason.UNREADABLE -> ErrorMapper.invalid(ErrorMapper.MEDIA_UNREADABLE)
            PrepException.Reason.EMPTY -> ErrorMapper.invalid(ErrorMapper.MEDIA_EMPTY)
            PrepException.Reason.CANCELLED -> preparedGone()
        }
        is VaultException -> ErrorMapper.mediaFailure(t.failure)
        is NativeFileException -> if (t.tag == CtrlGate.NO_CTRL) stopped() else SecuredError(Kind.STORAGE, ErrorMapper.STORAGE)
        is VaultIoException -> SecuredError(Kind.STORAGE, ErrorMapper.STORAGE)
        is OutOfMemoryError -> SecuredError(Kind.TOO_LARGE, ErrorMapper.MEDIA_TOO_LARGE_TO_PREPARE)
        else -> crashed(t)
    }

    /** Failures while reading or decrypting for the UI. */
    private fun readError(t: Throwable): SecuredError = when (t) {
        is VaultException ->
            if (t.failure == MediaFailure.TOO_LARGE) SecuredError(Kind.TOO_LARGE, ErrorMapper.MEDIA_TOO_LARGE_TO_OPEN)
            else ErrorMapper.mediaFailure(t.failure)
        is NativeFileException -> if (t.tag == CtrlGate.NO_CTRL) stopped() else SecuredError(Kind.ENGINE, ErrorMapper.MEDIA_OPEN_FAILED)
        is MediaReaderException -> SecuredError(Kind.ENGINE, ErrorMapper.MEDIA_OPEN_FAILED)
        is VaultIoException -> SecuredError(Kind.STORAGE, ErrorMapper.STORAGE)
        is OutOfMemoryError -> SecuredError(Kind.TOO_LARGE, ErrorMapper.MEDIA_TOO_LARGE_TO_OPEN)
        else -> crashed(t)
    }

    /** Anything else (an IllegalArgumentException is our bug): the class name is logged, never its message. */
    private fun crashed(t: Throwable): SecuredError {
        SecuredLog.crash(Ev.MEDIA_IO_FAILED, t)
        return ErrorMapper.engine("internal")
    }

    /** A fixed word for the log: a reason, a failure, a native tag or an exception class name; never a message. */
    private fun tagOf(t: Throwable): String = when (t) {
        is PrepException -> t.reason.name
        is VaultException -> t.failure.name
        is NativeFileException -> t.tag
        is VaultIoException -> t.tag
        else -> t.javaClass.simpleName
    }

    override fun toString() = "MediaCoordinator"

    companion object {
        /** A recording handed to prepareMedia that won't be prepared: closed and deleted (idempotent). Anything else: nothing. */
        fun release(input: MediaInput) {
            val recorded = input as? MediaInput.Recorded ?: return
            try {
                recorded.recording.close()
            } catch (e: Exception) {
                SecuredLog.w(Ev.MEDIA_IO_FAILED, "voice_close")
            }
        }

        /** The core events handleEvent routes to [onEvent] (`rcvFileDescrReady` goes to [recordDescription] first). */
        val EVENTS: Set<String> get() = MediaJson.EVENTS

        private const val DESCR_READY = "rcvFileDescrReady"
        private const val XFTP = "xftp"
        private const val LOCAL = "local"
        private const val RCV_INVITATION = "rcvInvitation"
        private const val RCV_COMPLETE = "rcvComplete"
        private val UPLOADING = setOf("sndStored", "sndTransfer", "sndWarning")
        /** An upload that can't move again: its pin goes. (An unknown tag keeps it.) */
        private val UPLOAD_ENDED = setOf("sndComplete", "sndCancelled", "sndError")
        private val DOWNLOADING = setOf("rcvAccepted", "rcvTransfer", "rcvWarning")

        /** Next to the ledger in the engine's no-backup folder (never in `files/`, so never in a backup). */
        const val RETENTION_FILE = "media_retention"
        const val PINS_FILE = "media_upload_pins"
        const val CLOCK_FILE = "media_retention_clock"

        private const val RETENTION_INVALID = "Choose how many days to keep media."

        /** At most this many shown files are remembered for [tellRemoved] (one core lookup each after a removal). */
        private const val SHOWN_MAX = 128

        /**
         * SimplexMediaTransport's errors for a send without a readable reply (CoreReply.Invalid, an unexpected result,
         * newChatItems with no item): the core may already hold the file, so it is not deleted.
         */
        private val KEEP_COMMITTED = setOf(ErrorMapper.engine("invalid"), ErrorMapper.engine("unexpected"), ErrorMapper.engine("noItem"))

        /** A single daemon thread named [name], created on first use: the production prep and read executors. */
        fun lazyThread(name: String): Executor {
            val pool by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } } }
            return Executor { pool.execute(it) }
        }
    }
}
