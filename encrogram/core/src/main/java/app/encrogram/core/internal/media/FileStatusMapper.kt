package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.MessageStatus

/**
 * The core's file status (plus what this phone knows, [LocalFacts]) as the contract's [MediaState] and [MediaFailure]
 * (docs/media-plan.md §4.6 and the hybrid amendments). Pure logic, no free text: a failure is always one fixed enum value.
 */
internal object FileStatusMapper {

    /** The same ceiling as MediaPolicy's cap (100 MiB while on the shared server). */
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    class Mapped(
        val state: MediaState,
        val failure: MediaFailure?,
        /** Plaintext bytes moved so far (progress scaled from the encrypted, padded units). */
        val transferredBytes: Long,
        /** The plaintext can be opened now. */
        val readable: Boolean,
    ) {
        override fun toString() = "Mapped($state${failure?.let { ", $it" } ?: ""})"
    }

    private fun ok(state: MediaState, bytes: Long = 0L, readable: Boolean = false) = Mapped(state, null, bytes, readable)
    private fun fail(f: MediaFailure) = Mapped(MediaState.FAILED, f, 0L, false)

    fun map(file: CoreFile, outgoing: Boolean): Mapped {
        val local = file.local
        val tag = file.statusTag
        // Files in notes (protocol local) are stored here and never move.
        if (file.protocol == "local" && (tag == "sndStored" || tag == "sndComplete" || tag == "rcvComplete")) {
            return if (local.present) ok(MediaState.READY, file.sizeBytes, true) else fail(MediaFailure.NOT_ON_DEVICE)
        }
        // Only xftp through our own file server is ever accepted (smp and inline offers are refused).
        if (tag.startsWith("rcv") && file.protocol != "xftp") return fail(MediaFailure.UNSUPPORTED)

        return when (tag) {
            "sndStored" -> ok(MediaState.UPLOADING, 0L, true)
            "sndTransfer", "sndWarning" -> ok(MediaState.UPLOADING, scaled(file), true)
            "sndComplete" -> if (local.present) ok(MediaState.READY, file.sizeBytes, true) else fail(MediaFailure.NOT_ON_DEVICE)
            "sndCancelled" -> fail(MediaFailure.CANCELLED)
            "sndError" -> fail(errorFailure(file, MediaFailure.SEND_FAILED))
            "rcvInvitation" -> invitation(file)
            "rcvAccepted", "rcvWarning" -> ok(MediaState.DOWNLOADING)
            "rcvTransfer" -> ok(MediaState.DOWNLOADING, scaled(file))
            "rcvComplete" -> if (local.present) ok(MediaState.READY, file.sizeBytes, true) else fail(MediaFailure.NOT_ON_DEVICE)
            "rcvCancelled" -> fail(MediaFailure.CANCELLED)
            // The core gives up on an incoming file it can't fetch from the sender's server: treated as a foreign server.
            "rcvAborted" -> fail(MediaFailure.FOREIGN_SERVER)
            "rcvError" -> fail(errorFailure(file, MediaFailure.RECEIVE_FAILED))
            "invalid" -> fail(if (outgoing) MediaFailure.SEND_FAILED else MediaFailure.RECEIVE_FAILED)
            // Unknown future status: outgoing keeps the clock, incoming fails closed.
            else -> if (outgoing) ok(MediaState.UPLOADING, 0L, true) else fail(MediaFailure.RECEIVE_FAILED)
        }
    }

    /** An offer not yet accepted: our own verdict and the user's choices decide what it shows. */
    private fun invitation(file: CoreFile): Mapped {
        val l = file.local
        if (file.sizeBytes > MAX_FILE_BYTES) return fail(MediaFailure.TOO_LARGE)
        val v = l.verdict
        if (v != null && v != Verdict.PASSED) return fail(verdictFailure(v))
        if (l.cancelled) return ok(MediaState.WAITING)
        if (v == null && l.overdue) return fail(MediaFailure.EXPIRED)
        if (l.wanted || l.auto || l.accepted) return ok(MediaState.DOWNLOADING)
        return ok(MediaState.WAITING)
    }

    fun verdictFailure(v: Verdict): MediaFailure = when (v) {
        Verdict.FOREIGN_SERVER -> MediaFailure.FOREIGN_SERVER
        Verdict.TOO_LARGE -> MediaFailure.TOO_LARGE
        Verdict.PROTOCOL -> MediaFailure.UNSUPPORTED
        Verdict.REDIRECT, Verdict.MALFORMED, Verdict.SIZE_MISMATCH, Verdict.NOT_RECIPIENT, Verdict.PASSED -> MediaFailure.CHECK_FAILED
    }

    /**
     * FileError type and hint (docs/media-plan.md §4.6): auth or noFile while receiving is EXPIRED; auth while sending
     * is [other]; blocked and relay are [other]; for `other`, QUOTA is SERVER_FULL, SIZE is TOO_LARGE, DIGEST is DAMAGED.
     */
    private fun errorFailure(file: CoreFile, other: MediaFailure): MediaFailure {
        val receiving = file.statusTag.startsWith("rcv")
        return when (file.errorTag) {
            "auth", "noFile" -> if (receiving) MediaFailure.EXPIRED else other
            "other" -> when (file.errorHint) {
                FileErrorHint.QUOTA -> MediaFailure.SERVER_FULL
                FileErrorHint.SIZE -> MediaFailure.TOO_LARGE
                FileErrorHint.DIGEST -> MediaFailure.DAMAGED
                null -> other
            }
            else -> other
        }
    }

    /** `sizeBytes * done / total`, clamped to the plaintext size. Never trusts done above total. */
    private fun scaled(file: CoreFile): Long {
        val total = file.progressTotal
        if (total <= 0L || file.sizeBytes <= 0L) return 0L
        val done = file.progressDone.coerceIn(0L, total)
        // done and total are at most about 10^9 and sizeBytes at most 10^9 here; the product stays inside Long.
        return (file.sizeBytes * done / total).coerceIn(0L, file.sizeBytes)
    }

    /** Plaintext bytes for a progress event: [done] of [total] in the core's units, scaled to [sizeBytes]. */
    fun scaleProgress(sizeBytes: Long, done: Long, total: Long): Long {
        if (total <= 0L || sizeBytes <= 0L) return 0L
        return (sizeBytes * done.coerceIn(0L, total) / total).coerceIn(0L, sizeBytes)
    }

    /**
     * An outgoing media message shows the clock until the upload is complete: its status is capped at SENDING until the
     * file is `sndComplete`, and FAILED on `sndError` or `sndCancelled`. Incoming items and notes are left as they are.
     */
    fun cappedStatus(status: MessageStatus, file: CoreFile?, outgoing: Boolean): MessageStatus {
        if (!outgoing || file == null || file.protocol == "local") return status
        return when (file.statusTag) {
            "sndComplete" -> status
            "sndError", "sndCancelled" -> MessageStatus.FAILED
            else -> if (status == MessageStatus.SENT_TO_SERVER || status == MessageStatus.DELIVERED) MessageStatus.SENDING else status
        }
    }
}
