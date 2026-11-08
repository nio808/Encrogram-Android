package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreItem
import org.json.JSONObject

/**
 * A [MediaTransport] for MediaCoordinator tests: records every command and answers with configurable replies.
 * Events are decoded by the real MediaJson, so tests feed the core's JSON shapes (MediaSamples).
 */
internal class FakeTransport : MediaTransport {

    class Sent(val chat: ChatRef, val fileName: String, val key: FileKey, val content: OutgoingContent, val quotedItemId: Long?) {
        override fun toString() = "Sent(${content.kind})"
    }

    val receives = ArrayList<Long>()
    val cancels = ArrayList<Long>()
    val sends = ArrayList<Sent>()

    /** Runs before each receive is answered: invariant checks go here. */
    var beforeReceive: (Long) -> Unit = {}
    var receiveReply: (Long) -> TransportReply = { TransportReply.Item(null) }
    var cancelReply: (Long) -> TransportReply = { TransportReply.Item(null) }
    var sendReply: (Sent) -> TransportReply = { TransportReply.Item(outgoing(it)) }

    /** The chat the default send reply puts its item in. */
    var chatId = 5L
    private var nextItemId = 500L

    override fun send(chat: ChatRef, fileName: String, key: FileKey, content: OutgoingContent, quotedItemId: Long?): TransportReply {
        val s = Sent(chat, fileName, key, content, quotedItemId)
        sends += s
        return sendReply(s)
    }

    override fun receive(checked: CheckedFile): TransportReply {
        beforeReceive(checked.fileId)
        receives += checked.fileId
        return receiveReply(checked.fileId)
    }

    override fun cancel(fileId: Long): TransportReply {
        cancels += fileId
        return cancelReply(fileId)
    }

    override fun decode(type: String, body: JSONObject): MediaEvent? = MediaJson.event(type, body)

    override val eventTypes: Set<String> get() = MediaJson.EVENTS

    /** The core's newChatItems item for a send: our file, stored, in the files folder under the committed name. */
    fun outgoing(s: Sent, status: String = "sndStored"): CoreItem {
        val id = nextItemId++
        return CoreItem(
            chatId = chatId,
            itemId = id,
            outgoing = true,
            isMessage = true,
            text = s.content.caption,
            timeMs = 0L,
            statusTag = "sndNew",
            status = MessageStatus.SENDING,
            unread = false,
            msgContentType = typeOf(s.content.kind),
            shared = true,
            sharedMsgId = "c2VudC0$id",
            file = CoreFile(id + 1000, s.content.kind, s.fileName, 1000L, status, "xftp", s.fileName, s.key),
            preview = s.content.previewDataUrl,
            durationSeconds = s.content.durationSeconds,
        )
    }

    private fun typeOf(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE, MediaKind.GIF -> "image"
        MediaKind.VIDEO -> "video"
        MediaKind.VOICE -> "voice"
        MediaKind.FILE -> "file"
    }
}
