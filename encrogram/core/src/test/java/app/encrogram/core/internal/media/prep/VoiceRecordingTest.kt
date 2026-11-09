package app.encrogram.core.internal.media.prep

import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.PreparedMedia
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.api.VoiceRecording
import app.encrogram.core.internal.ErrorMapper
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.DescriptionSamples
import app.encrogram.core.internal.media.FakeMediaHost
import app.encrogram.core.internal.media.FakeNativeFiles
import app.encrogram.core.internal.media.FakeTransport
import app.encrogram.core.internal.media.MediaCoordinator
import app.encrogram.core.internal.media.MediaLedger
import app.encrogram.core.internal.media.MediaPolicy
import app.encrogram.core.internal.media.MediaPolicyConfig
import app.encrogram.core.internal.media.MediaVault
import app.encrogram.core.internal.media.Prepared
import app.encrogram.core.internal.media.VoiceFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Voice messages (task 5): the engine-owned recording file, its checks before sealing, and the duration sent. */
class VoiceRecordingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var media: File
    private lateinit var vault: MediaVault
    private val logs = ArrayList<String>()

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val p = cat(*payload)
        return cat(be32(8L + p.size), ascii(type), p)
    }

    /** What MediaRecorder (MPEG_4 + AAC) writes, in miniature, with a canary in udta and a creation time in mvhd. */
    private fun m4a(brand: String = "M4A ", withMoov: Boolean = true): ByteArray {
        val ftyp = box("ftyp", ascii(brand), be32(0), ascii("isom"))
        val mvhd = box("mvhd", byteArrayOf(0, 0, 0, 0), byteArrayOf(0x11, 0x22, 0x33, 0x44), byteArrayOf(0x11, 0x22, 0x33, 0x44), ByteArray(20) { 9 })
        val moov = box("moov", mvhd, box("udta", box("©xyz", ascii("CANARY-GPS-51.5074"))))
        return cat(ftyp, if (withMoov) moov else ByteArray(0), box("mdat", ByteArray(96) { (it * 7 + 3).toByte() }))
    }

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { logs.add(it) }
        media = File(tmp.root, "media")
        vault = MediaVault(File(tmp.root, "files"), media, FakeNativeFiles())
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
    }

    private fun preparer(config: MediaPolicyConfig = MediaPolicy.DEFAULT) = MediaPreparer(FakeOpener(), FakeCodec(), FakeVideoTool(), vault, config)

    private fun list(name: String): List<String> = File(media, name).list()?.toList() ?: emptyList()

    /** A recording the "recorder" filled through the descriptor only, as MediaRecorder does. */
    private fun recorded(bytes: ByteArray): VoiceFile {
        val rec = vault.newRecording()
        FileOutputStream(rec.fileDescriptor).write(bytes)
        return rec
    }

    private fun refused(bytes: ByteArray, durationMs: Long, reason: PrepException.Reason, config: MediaPolicyConfig = MediaPolicy.DEFAULT) {
        val rec = recorded(bytes)
        try {
            preparer(config).prepare(MediaInput.Recorded(rec, durationMs))
            fail("expected $reason")
        } catch (e: PrepException) {
            assertEquals(reason, e.reason)
        }
        assertGone(rec)
        assertEquals("nothing sealed", emptyList<String>(), list("prep"))
    }

    /** The plaintext file is deleted and the descriptor closed. */
    private fun assertGone(rec: VoiceFile) {
        assertEquals(emptyList<String>(), list("view"))
        assertFalse(rec.fileDescriptor.valid())
        assertNull(rec.finish())
    }

    @Test
    fun aRecordingIsCheckedCleanedSealedAndDeleted() {
        val rec = recorded(m4a())
        assertEquals(1, list("view").size)
        val p: Prepared = preparer().prepare(MediaInput.Recorded(rec, 12_400))
        assertEquals(MediaKind.VOICE, p.kind)
        assertTrue(p.name, Regex("voice_[a-z2-7]{8}\\.m4a").matches(p.name))
        assertEquals("audio/mp4", p.mimeType)
        assertNull(p.previewDataUrl)
        assertEquals(12, p.durationSeconds)
        assertFalse(p.unchanged)
        val plain = FakeNativeFiles.open(p.sealed.file.readBytes(), p.sealed.key)!!
        assertFalse("metadata zeroed", String(plain, Charsets.ISO_8859_1).contains("CANARY"))
        assertEquals(m4a().size, plain.size)
        assertGone(rec)
        assertEquals(1, list("prep").size)
        // An MP4-branded recording (what some phones write) is fine too.
        assertEquals(MediaKind.VOICE, preparer().prepare(MediaInput.Recorded(recorded(m4a("mp42")), 3_000)).kind)
        assertEquals(emptyList<String>(), list("view"))
        assertFalse(logs.any { it.contains("CANARY") || it.contains(media.path) })
    }

    @Test
    fun sizeLengthAndFormatRefusals() {
        refused(ByteArray(0), 1_000, PrepException.Reason.UNREADABLE)
        refused(m4a(), 1_000, PrepException.Reason.TOO_LARGE, MediaPolicyConfig(voiceMaxBytes = m4a().size - 1L))
        refused(m4a(), 300_500, PrepException.Reason.TOO_LARGE)
        refused(ascii("OggS") + ByteArray(200), 1_000, PrepException.Reason.UNSUPPORTED)
        refused(m4a("qt  "), 1_000, PrepException.Reason.UNSUPPORTED)
        refused(m4a("heic"), 1_000, PrepException.Reason.UNSUPPORTED)
        // Not finished by the recorder (no moov): Mp4Meta can't vouch for it.
        refused(m4a(withMoov = false), 1_000, PrepException.Reason.UNSUPPORTED)
        // Exactly the limits pass.
        val p = preparer(MediaPolicyConfig(voiceMaxBytes = m4a().size.toLong())).prepare(MediaInput.Recorded(recorded(m4a()), 300_499))
        assertEquals(300, p.durationSeconds)
    }

    @Test
    fun onlyOurOwnRecordingIsTaken() {
        var closed = 0
        val foreign = object : VoiceRecording {
            override val fileDescriptor: FileDescriptor = FileDescriptor()
            override fun close() {
                closed++
            }
        }
        try {
            preparer().prepare(MediaInput.Recorded(foreign, 1_000))
            fail("foreign recording")
        } catch (e: PrepException) {
            assertEquals(PrepException.Reason.UNSUPPORTED, e.reason)
        }
        assertEquals(1, closed)
        // A recording already discarded can't be prepared.
        val rec = recorded(m4a())
        rec.close()
        rec.close()
        try {
            preparer().prepare(MediaInput.Recorded(rec, 1_000))
            fail("discarded recording")
        } catch (e: PrepException) {
            assertEquals(PrepException.Reason.UNREADABLE, e.reason)
        }
        assertGone(rec)
    }

    @Test
    fun durationAsSent() {
        val cases = mapOf(
            -5L to 1, 0L to 1, 499L to 1, 1_499L to 1, 1_500L to 2, 12_400L to 12, 12_500L to 13,
            299_499L to 299, 300_000L to 300, 300_499L to 300, 10_000_000L to 300, Long.MAX_VALUE to 300,
        )
        for ((ms, s) in cases) assertEquals("$ms ms", s, MediaPolicy.voiceSeconds(ms))
        assertFalse(MediaPolicy.voiceTooLong(300_499))
        assertTrue(MediaPolicy.voiceTooLong(300_500))
        assertTrue(MediaPolicy.voiceTooLong(Long.MAX_VALUE))
        assertFalse(MediaPolicy.voiceTooLong(-1))
    }

    @Test
    fun nothingIsPrinted() {
        val rec = vault.newRecording()
        val input = MediaInput.Recorded(rec, 4_000)
        for (s in listOf(rec.toString(), input.toString())) {
            assertFalse(s, s.contains(media.path) || s.contains("view") || s.contains("4000"))
        }
        assertEquals("Recorded", input.toString())
        assertEquals("VoiceRecording", rec.toString())
        rec.close()
        assertEquals(emptyList<String>(), list("view"))
    }

    // ---- through the coordinator ----------------------------------------------------------------------------------

    private class Capture<T> : app.encrogram.core.api.SecuredCallback<T> {
        var result: SecuredResult<T>? = null
        override fun onResult(result: SecuredResult<T>) {
            this.result = result
        }
        val error: SecuredError? get() = (result as? SecuredResult.Err)?.error
        val value: T? get() = (result as? SecuredResult.Ok)?.value
    }

    private val prepQueue = ArrayDeque<Runnable>()

    private fun coordinator(config: MediaPolicyConfig = MediaPolicy.DEFAULT): MediaCoordinator {
        val host = FakeMediaHost(0L)
        val c = MediaCoordinator(
            host, FakeTransport(), vault, MediaLedger(File(tmp.root, "media_ledger")), preparer(config), DescriptionSamples.ourServers,
            Executor { prepQueue.add(it) }, Executor { it.run() }, config,
        )
        host.coordinator = c
        return c
    }

    @Test
    fun theCoordinatorStartsRecordingsAndNeverLeavesOneBehind() {
        val c = coordinator()
        val started = Capture<VoiceRecording>()
        c.startVoiceRecording(started)
        val rec = started.value as VoiceFile
        assertEquals(1, list("view").size)

        // Stopped before the prep thread got to it: the recording still goes.
        FileOutputStream(rec.fileDescriptor).write(m4a())
        val prepared = Capture<PreparedMedia>()
        c.prepare(MediaInput.Recorded(rec, 2_000), prepared)
        c.onStop()
        prepQueue.poll()!!.run()
        assertEquals(app.encrogram.core.api.SecuredError.Kind.ENGINE_NOT_RUNNING, prepared.error!!.kind)
        assertGone(rec)

        // A refusal gets the voice wording.
        val c2 = coordinator()
        val tooLong = Capture<PreparedMedia>()
        c2.prepare(MediaInput.Recorded(recorded(m4a()), 400_000), tooLong)
        prepQueue.poll()!!.run()
        assertEquals(ErrorMapper.VOICE_TOO_LONG, tooLong.error!!.message)
        assertEquals(emptyList<String>(), list("view"))

        // A success: kind, neutral name and duration reach the UI.
        val ok = Capture<PreparedMedia>()
        c2.prepare(MediaInput.Recorded(recorded(m4a()), 7_200), ok)
        prepQueue.poll()!!.run()
        val pm = ok.value!!
        assertEquals(MediaKind.VOICE, pm.kind)
        assertEquals(7, pm.durationSeconds)
        assertEquals("PreparedMedia(VOICE)", pm.toString())

        // No room for the longest voice message: STORAGE, and no file made.
        val full = Capture<VoiceRecording>()
        coordinator(MediaPolicyConfig(voiceMaxBytes = Long.MAX_VALUE / 8)).startVoiceRecording(full)
        assertEquals(app.encrogram.core.api.SecuredError.Kind.STORAGE, full.error!!.kind)
        assertEquals(emptyList<String>(), list("view"))

        // Not prepared at all (the engine wasn't running): released.
        val idle = vault.newRecording()
        MediaCoordinator.release(MediaInput.Recorded(idle, 1_000))
        assertGone(idle)
    }
}
