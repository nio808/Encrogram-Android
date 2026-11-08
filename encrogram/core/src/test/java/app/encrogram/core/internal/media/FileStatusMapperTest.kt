package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStatusMapperTest {

    private fun file(
        tag: String,
        protocol: String = "xftp",
        size: Long = 1000,
        done: Long = 0,
        total: Long = 0,
        errorTag: String? = null,
        hint: FileErrorHint? = null,
        local: LocalFacts = LocalFacts.NONE,
    ) = CoreFile(7, MediaKind.FILE, "n.bin", size, tag, protocol, null, null, done, total, errorTag, hint, local)

    private fun inc(f: CoreFile) = FileStatusMapper.map(f, outgoing = false)
    private fun out(f: CoreFile) = FileStatusMapper.map(f, outgoing = true)
    private val here = LocalFacts(present = true)

    private fun assertFailed(m: FileStatusMapper.Mapped, f: MediaFailure) {
        assertEquals(MediaState.FAILED, m.state)
        assertEquals(f, m.failure)
        assertFalse(m.readable)
    }

    // ---- the fifteen statuses ----------------------------------------------------------------------------------------

    @Test
    fun sndStored() {
        val m = out(file("sndStored"))
        assertEquals(MediaState.UPLOADING, m.state)
        assertTrue(m.readable)
        assertEquals(0L, m.transferredBytes)
    }

    @Test
    fun sndTransferScalesToPlaintext() {
        val m = out(file("sndTransfer", size = 1000, done = 500, total = 2000))
        assertEquals(MediaState.UPLOADING, m.state)
        assertEquals(250L, m.transferredBytes)
        assertTrue(m.readable)
    }

    @Test
    fun sndWarningKeepsUploading() = assertEquals(MediaState.UPLOADING, out(file("sndWarning", errorTag = "relay")).state)

    @Test
    fun sndComplete() {
        val m = out(file("sndComplete", local = here))
        assertEquals(MediaState.READY, m.state)
        assertEquals(1000L, m.transferredBytes)
        assertTrue(m.readable)
        assertFailed(out(file("sndComplete")), MediaFailure.NOT_ON_DEVICE)
    }

    @Test
    fun sndCancelled() = assertFailed(out(file("sndCancelled")), MediaFailure.CANCELLED)

    @Test
    fun sndErrorTags() {
        assertFailed(out(file("sndError", errorTag = "auth")), MediaFailure.SEND_FAILED)
        assertFailed(out(file("sndError", errorTag = "blocked")), MediaFailure.SEND_FAILED)
        assertFailed(out(file("sndError", errorTag = "relay")), MediaFailure.SEND_FAILED)
        assertFailed(out(file("sndError", errorTag = "noFile")), MediaFailure.SEND_FAILED)
        assertFailed(out(file("sndError", errorTag = "other")), MediaFailure.SEND_FAILED)
        assertFailed(out(file("sndError", errorTag = "other", hint = FileErrorHint.QUOTA)), MediaFailure.SERVER_FULL)
        assertFailed(out(file("sndError", errorTag = "other", hint = FileErrorHint.SIZE)), MediaFailure.TOO_LARGE)
        assertFailed(out(file("sndError")), MediaFailure.SEND_FAILED)
    }

    @Test
    fun rcvInvitationWaitsAndAutoDownloads() {
        assertEquals(MediaState.WAITING, inc(file("rcvInvitation")).state)
        val auto = inc(file("rcvInvitation", local = LocalFacts(auto = true)))
        assertEquals(MediaState.DOWNLOADING, auto.state)
        assertEquals(0L, auto.transferredBytes)
        assertEquals(MediaState.DOWNLOADING, inc(file("rcvInvitation", local = LocalFacts(wanted = true))).state)
        assertEquals(MediaState.DOWNLOADING, inc(file("rcvInvitation", local = LocalFacts(verdict = Verdict.PASSED, accepted = true))).state)
    }

    @Test
    fun cancelledGoesBackToWaiting() {
        assertEquals(MediaState.WAITING, inc(file("rcvInvitation", local = LocalFacts(auto = true, cancelled = true))).state)
    }

    @Test
    fun verdictsOverrideWaiting() {
        fun v(x: Verdict, auto: Boolean = false) = inc(file("rcvInvitation", local = LocalFacts(verdict = x, auto = auto)))
        assertFailed(v(Verdict.FOREIGN_SERVER), MediaFailure.FOREIGN_SERVER)
        assertFailed(v(Verdict.FOREIGN_SERVER, auto = true), MediaFailure.FOREIGN_SERVER)
        assertFailed(v(Verdict.REDIRECT), MediaFailure.CHECK_FAILED)
        assertFailed(v(Verdict.MALFORMED), MediaFailure.CHECK_FAILED)
        assertFailed(v(Verdict.SIZE_MISMATCH), MediaFailure.CHECK_FAILED)
        assertFailed(v(Verdict.NOT_RECIPIENT), MediaFailure.CHECK_FAILED)
        assertFailed(v(Verdict.TOO_LARGE), MediaFailure.TOO_LARGE)
        assertFailed(v(Verdict.PROTOCOL), MediaFailure.UNSUPPORTED)
        assertEquals(MediaState.WAITING, v(Verdict.PASSED).state)
    }

    @Test
    fun overdueDescriptionExpires() {
        assertFailed(inc(file("rcvInvitation", local = LocalFacts(overdue = true))), MediaFailure.EXPIRED)
        // a checked file is not overdue
        assertEquals(MediaState.WAITING, inc(file("rcvInvitation", local = LocalFacts(overdue = true, verdict = Verdict.PASSED))).state)
    }

    @Test
    fun smpAndOverCapAreRefused() {
        assertFailed(inc(file("rcvInvitation", protocol = "smp")), MediaFailure.UNSUPPORTED)
        assertFailed(inc(file("rcvInvitation", protocol = "")), MediaFailure.UNSUPPORTED)
        assertFailed(inc(file("rcvInvitation", size = 100L * 1024 * 1024 + 1)), MediaFailure.TOO_LARGE)
        assertEquals(MediaState.WAITING, inc(file("rcvInvitation", size = 100L * 1024 * 1024)).state)
    }

    @Test
    fun rcvAcceptedAndWarningDownload() {
        assertEquals(MediaState.DOWNLOADING, inc(file("rcvAccepted")).state)
        assertEquals(MediaState.DOWNLOADING, inc(file("rcvWarning", errorTag = "relay")).state)
    }

    @Test
    fun rcvTransferScalesAndNeverExceedsTheFile() {
        val m = inc(file("rcvTransfer", size = 1000, done = 250, total = 1000))
        assertEquals(MediaState.DOWNLOADING, m.state)
        assertEquals(250L, m.transferredBytes)
        assertFalse(m.readable)
        assertEquals(1000L, inc(file("rcvTransfer", size = 1000, done = 5000, total = 1000)).transferredBytes)
        assertEquals(0L, inc(file("rcvTransfer", size = 1000, done = 5, total = 0)).transferredBytes)
        assertEquals(50L, FileStatusMapper.scaleProgress(100, 1, 2))
    }

    @Test
    fun rcvComplete() {
        val m = inc(file("rcvComplete", local = here))
        assertEquals(MediaState.READY, m.state)
        assertTrue(m.readable)
        assertEquals(1000L, m.transferredBytes)
        assertFailed(inc(file("rcvComplete")), MediaFailure.NOT_ON_DEVICE)
    }

    @Test
    fun rcvCancelledAndAborted() {
        assertFailed(inc(file("rcvCancelled")), MediaFailure.CANCELLED)
        assertFailed(inc(file("rcvAborted")), MediaFailure.FOREIGN_SERVER)
    }

    @Test
    fun rcvErrorTags() {
        assertFailed(inc(file("rcvError", errorTag = "auth")), MediaFailure.EXPIRED)
        assertFailed(inc(file("rcvError", errorTag = "noFile")), MediaFailure.EXPIRED)
        assertFailed(inc(file("rcvError", errorTag = "blocked")), MediaFailure.RECEIVE_FAILED)
        assertFailed(inc(file("rcvError", errorTag = "relay")), MediaFailure.RECEIVE_FAILED)
        assertFailed(inc(file("rcvError", errorTag = "other")), MediaFailure.RECEIVE_FAILED)
        assertFailed(inc(file("rcvError", errorTag = "other", hint = FileErrorHint.DIGEST)), MediaFailure.DAMAGED)
        assertFailed(inc(file("rcvError", errorTag = "other", hint = FileErrorHint.SIZE)), MediaFailure.TOO_LARGE)
        assertFailed(inc(file("rcvError", errorTag = "other", hint = FileErrorHint.QUOTA)), MediaFailure.SERVER_FULL)
        assertFailed(inc(file("rcvError")), MediaFailure.RECEIVE_FAILED)
    }

    @Test
    fun invalidAndUnknown() {
        assertFailed(inc(file("invalid")), MediaFailure.RECEIVE_FAILED)
        assertFailed(out(file("invalid")), MediaFailure.SEND_FAILED)
        assertFailed(inc(file("somethingNew")), MediaFailure.RECEIVE_FAILED)
        assertEquals(MediaState.UPLOADING, out(file("somethingNew")).state)
    }

    @Test
    fun notesAreHereAndNeverMove() {
        assertEquals(MediaState.READY, out(file("sndStored", protocol = "local", local = here)).state)
        assertEquals(MediaState.READY, out(file("rcvComplete", protocol = "local", local = here)).state)
        assertFailed(out(file("rcvComplete", protocol = "local")), MediaFailure.NOT_ON_DEVICE)
    }

    @Test
    fun noFailureWithoutFailedState() {
        for (t in listOf("sndStored", "rcvInvitation", "rcvAccepted", "sndTransfer")) assertNull(inc(file(t)).failure)
    }

    // ---- the tick cap --------------------------------------------------------------------------------------------------

    @Test
    fun outgoingStatusIsCappedAtSendingUntilSndComplete() {
        for (tag in listOf("sndStored", "sndTransfer", "sndWarning")) {
            assertEquals(tag, MessageStatus.SENDING, FileStatusMapper.cappedStatus(MessageStatus.SENT_TO_SERVER, file(tag), true))
            assertEquals(tag, MessageStatus.SENDING, FileStatusMapper.cappedStatus(MessageStatus.DELIVERED, file(tag), true))
            assertEquals(tag, MessageStatus.SENDING, FileStatusMapper.cappedStatus(MessageStatus.SENDING, file(tag), true))
        }
        assertEquals(MessageStatus.SENT_TO_SERVER, FileStatusMapper.cappedStatus(MessageStatus.SENT_TO_SERVER, file("sndComplete"), true))
        assertEquals(MessageStatus.DELIVERED, FileStatusMapper.cappedStatus(MessageStatus.DELIVERED, file("sndComplete"), true))
        assertEquals(MessageStatus.FAILED, FileStatusMapper.cappedStatus(MessageStatus.SENDING, file("sndError"), true))
        assertEquals(MessageStatus.FAILED, FileStatusMapper.cappedStatus(MessageStatus.SENT_TO_SERVER, file("sndCancelled"), true))
        assertEquals(MessageStatus.FAILED, FileStatusMapper.cappedStatus(MessageStatus.FAILED, file("sndStored"), true))
    }

    @Test
    fun theCapLeavesIncomingNotesAndTextAlone() {
        assertEquals(MessageStatus.RECEIVED, FileStatusMapper.cappedStatus(MessageStatus.RECEIVED, file("rcvInvitation"), false))
        assertEquals(MessageStatus.DELIVERED, FileStatusMapper.cappedStatus(MessageStatus.DELIVERED, file("sndStored", protocol = "local"), true))
        assertEquals(MessageStatus.DELIVERED, FileStatusMapper.cappedStatus(MessageStatus.DELIVERED, null, true))
    }
}
