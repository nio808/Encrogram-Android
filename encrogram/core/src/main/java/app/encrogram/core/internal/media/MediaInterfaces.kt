package app.encrogram.core.internal.media

import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreReply
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONObject

/*
 * The seams of Secured media (docs/media-plan.md §2). Everything SimpleX- or XFTP-specific sits behind
 * [MediaTransport] and [NativeFiles] (plus FileDescriptionCheck and MediaJson); a later core replaces those, and the
 * coordinator, policy, ledger, vault, preparer and the contract stay as they are.
 */

/** What MediaCoordinator may ask of the engine. Implemented by SimplexSecuredEngine. */
internal interface MediaHost {
    /** Engine thread only: one core command (the engine's send()). */
    fun run(cmd: String): CoreReply

    /** The item as the UI sees it (localView + MessageMapper + role). Engine thread. */
    fun toMessage(item: CoreItem): SecuredMessage

    /** Engine thread: [chatId]'s message [itemId] as the store holds it now (localView), or null when it is gone. */
    fun currentItem(chatId: Long, itemId: Long): CoreItem?

    /** onMessageChanged only (never onMessageReceived, never markChanged). Engine thread; delivered on main. */
    fun messageChanged(items: List<CoreItem>)

    /** A message we just sent: the chat row's preview and ticks (applyNewItems). Engine thread. */
    fun itemsAdded(items: List<CoreItem>)

    /** onMediaProgress on main. */
    fun progress(chatId: Long, itemId: Long, done: Long, total: Long)

    /** Delivers a callback result on main. */
    fun <T> deliver(callback: SecuredCallback<T>, result: SecuredResult<T>)

    /** Runs [task] on the engine thread (for work coming back from the prep and read threads). */
    fun onEngine(task: () -> Unit)

    fun running(): Boolean

    fun nowMs(): Long
}

/** Sending, receiving and cancelling files. Today SimplexMediaTransport (XFTP through the SimpleX core). Engine thread. */
internal interface MediaTransport {
    /** [fileName]: a bare name already committed into the core's files folder, stored encrypted with [key]. */
    fun send(chat: ChatRef, fileName: String, key: FileKey, content: OutgoingContent, quotedItemId: Long?): TransportReply

    /** Starts a download; only a [CheckedFile] (a PASSED verdict) can be passed. */
    fun receive(checked: CheckedFile): TransportReply

    fun cancel(fileId: Long): TransportReply

    /** A core event of one of [eventTypes] as a [MediaEvent]; null when it isn't one or can't be read. */
    fun decode(type: String, body: JSONObject): MediaEvent?

    /** The event types this transport handles. */
    val eventTypes: Set<String>
}

/**
 * The core's four local file functions (JNI). Error text from the core never surfaces: failures throw
 * [NativeFileException] with a fixed tag. The calls that need the controller run under CtrlGate.
 */
internal interface NativeFiles {
    /** chatWriteFile: [data] must be a direct buffer; it is zeroed by the caller afterwards. */
    fun writeEncrypted(target: File, data: ByteBuffer): FileKey

    /** chatEncryptFile. */
    fun encryptFile(from: File, to: File): FileKey

    /** chatReadFile: the whole plaintext, authenticated. */
    fun readDecrypted(file: File, key: FileKey): ByteArray

    /** chatDecryptFile. Any failure deletes [to] (it may hold unauthenticated plaintext) before throwing. */
    fun decryptFile(from: File, key: FileKey, to: File)
}

/** A native file call failed. [tag] is a fixed word (write, read, decrypt, encrypt, no_ctrl); no core text. */
internal class NativeFileException(val tag: String) : Exception(tag)
