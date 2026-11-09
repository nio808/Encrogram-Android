package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.SecuredError
import app.encrogram.core.internal.CoreItem
import java.io.File

/*
 * The shared internal model of Secured media (docs/media-plan.md, written by the lead in MD0). Every class that holds
 * a name, path, key, description or preview overrides toString so none of it can reach a log by accident.
 */

/** cryptoArgs of a file stored encrypted (CryptoFileArgs: fileKey, fileNonce as the core's strings). */
internal class FileKey(val key: String, val nonce: String) {
    override fun toString() = "FileKey(redacted)"
    override fun equals(other: Any?) = other is FileKey && other.key == key && other.nonce == nonce
    override fun hashCode() = key.hashCode() * 31 + nonce.hashCode()
}

/** The item's CIFile as the core reports it (MediaJson.parseFile), plus what this phone knows ([local]). */
internal data class CoreFile(
    val fileId: Long,
    val kind: MediaKind,
    /** The name in the invitation (incoming) or the one we sent (outgoing). User content: never logged. */
    val name: String,
    val sizeBytes: Long,
    /** fileStatus.type: sndStored, sndTransfer, ..., rcvInvitation, rcvAccepted, ..., invalid. */
    val statusTag: String,
    /** fileProtocol: "xftp" | "smp" | "local". Only xftp is ever accepted. */
    val protocol: String,
    /** fileSource.filePath: a bare name in the core's files folder; null until a received file is complete. */
    val path: String? = null,
    val key: FileKey? = null,
    /** As reported (sndProgress/sndTotal, rcvProgress/rcvTotal): units of the encrypted, padded upload. */
    val progressDone: Long = 0,
    val progressTotal: Long = 0,
    /** FileError type on sndError/rcvError/warnings: auth | blocked | noFile | relay | other. */
    val errorTag: String? = null,
    /** From fixed tokens in an `other` error's text; the text itself is dropped in MediaJson. */
    val errorHint: FileErrorHint? = null,
    val local: LocalFacts = LocalFacts.NONE,
    /** Up to three fixed protocol words from the error text (INTERNAL, FILE, SESSION...), for the log only; never the text. */
    val errorWords: String? = null,
) {
    override fun toString() = "CoreFile($fileId,$kind,$statusTag,$protocol)"
}

internal enum class FileErrorHint { QUOTA, SIZE, DIGEST }

/** Our own check of an incoming file's description (FileDescriptionCheck). Only PASSED may be downloaded. */
internal enum class Verdict { PASSED, FOREIGN_SERVER, REDIRECT, MALFORMED, SIZE_MISMATCH, TOO_LARGE, NOT_RECIPIENT, PROTOCOL }

/** What this phone knows about one file, beyond the core's own status (MediaCoordinator.annotate). */
internal data class LocalFacts(
    /** Our verdict on its description, once it has arrived (from MediaLedger). */
    val verdict: Verdict? = null,
    /** The user tapped download before the description could be checked. */
    val wanted: Boolean = false,
    /** The user cancelled this download here. */
    val cancelled: Boolean = false,
    /** /freceive was accepted by the core. */
    val accepted: Boolean = false,
    /** It downloads by itself (MediaPolicy) once checked. */
    val auto: Boolean = false,
    /** The encrypted file exists in the core's files folder. */
    val present: Boolean = false,
    /** The description never arrived in time (MediaPolicy.descriptionOverdue). */
    val overdue: Boolean = false,
) {
    companion object {
        val NONE = LocalFacts()
    }
}

/** A file we stored encrypted ([key]) in our own staging folder, ready to be committed into the core's files folder. */
internal class SealedFile(val file: File, val key: FileKey, val plainSize: Long) {
    override fun toString() = "SealedFile($plainSize bytes)"
}

/** The only ticket MediaCommands.receive takes: made only from a PASSED verdict. */
internal class CheckedFile private constructor(val fileId: Long) {
    override fun toString() = "CheckedFile($fileId)"

    companion object {
        fun of(fileId: Long, verdict: Verdict?): CheckedFile? = if (verdict == Verdict.PASSED && fileId > 0) CheckedFile(fileId) else null
    }
}

/** The message part of a media send. [previewDataUrl] is required for IMAGE, GIF and VIDEO (the core needs one). */
internal class OutgoingContent(
    val kind: MediaKind,
    val caption: String,
    val previewDataUrl: String?,
    val durationSeconds: Int?,
) {
    override fun toString() = "OutgoingContent($kind)"
}

/** What MediaPreparer made from one input: sealed, neutral-named, with its preview. */
internal class Prepared(
    val kind: MediaKind,
    /** Neutral for photos, GIFs, video and voice (MediaNames); the sanitized original for documents. */
    val name: String,
    val mimeType: String?,
    val previewDataUrl: String?,
    val durationSeconds: Int?,
    /** Sent byte for byte (documents). */
    val unchanged: Boolean,
    val sealed: SealedFile,
) {
    override fun toString() = "Prepared($kind)"
}

/** A file event, decoded by the transport (MediaJson.event). */
internal sealed class MediaEvent {
    /** rcvFileDescrReady with a complete description. [text] is the description itself: checked, then dropped. */
    class DescriptionReady(
        val item: CoreItem,
        val fileId: Long,
        val invitedSize: Long,
        val protocol: String,
        val text: String,
    ) : MediaEvent() {
        override fun toString() = "DescriptionReady($fileId)"
    }

    /** rcvFileProgressXFTP / sndFileProgressXFTP. */
    class Progress(val chatId: Long, val itemId: Long, val done: Long, val total: Long) : MediaEvent() {
        override fun toString() = "Progress($itemId)"
    }

    /** Any other file event that changes the item ([tag]: the event type, a fixed word). [item] null: chatItem_ absent. */
    class Changed(val item: CoreItem?, val tag: String) : MediaEvent() {
        override fun toString() = "Changed($tag)"
    }
}

/** What a transport command came back with. */
internal sealed class TransportReply {
    /** Done; [item] is the affected chat item when the reply carries one. */
    class Item(val item: CoreItem?) : TransportReply()
    /** Refused for a media reason (the item's state says the same). */
    class Refused(val failure: MediaFailure) : TransportReply()
    class Failed(val error: SecuredError) : TransportReply()
}
