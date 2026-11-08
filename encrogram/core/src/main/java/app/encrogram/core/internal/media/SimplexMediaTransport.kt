package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreError
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.CoreReply
import app.encrogram.core.internal.ErrorMapper
import org.json.JSONObject

/**
 * [MediaTransport] over the SimpleX core (XFTP). [run] is the engine's one-command function (engine thread).
 * Errors are mapped by tag only: the core's text, paths and server addresses never leave [CoreJson.parseError].
 */
internal class SimplexMediaTransport(private val run: (String) -> CoreReply) : MediaTransport {

    override fun send(chat: ChatRef, fileName: String, key: FileKey, content: OutgoingContent, quotedItemId: Long?): TransportReply {
        val reply = run(MediaCommands.send(chat, fileName, key, content, quotedItemId))
        return when (reply) {
            is CoreReply.Result ->
                if (reply.type == "newChatItems") {
                    val first = CoreJson.parseChatItems(reply.body).firstOrNull()
                    if (first != null) TransportReply.Item(first) else TransportReply.Failed(ErrorMapper.engine("noItem"))
                } else TransportReply.Failed(ErrorMapper.engine("unexpected"))
            is CoreReply.Error -> sizeRefusal(reply.error) ?: TransportReply.Failed(ErrorMapper.fromCore(reply.error, ErrorMapper.Op.SEND))
            CoreReply.Invalid -> TransportReply.Failed(ErrorMapper.engine("invalid"))
        }
    }

    override fun receive(checked: CheckedFile): TransportReply {
        val reply = run(MediaCommands.receive(checked))
        return when (reply) {
            is CoreReply.Result -> when (reply.type) {
                "rcvFileAccepted" -> TransportReply.Item(CoreJson.parseAChatItem(reply.body.optJSONObject("chatItem")))
                "rcvFileAcceptedSndCancelled" -> TransportReply.Refused(MediaFailure.CANCELLED)
                else -> TransportReply.Failed(ErrorMapper.engine("unexpected"))
            }
            is CoreReply.Error -> {
                val e = reply.error
                if (e.type == "error") when (e.detail) {
                    "fileAlreadyReceiving" -> return TransportReply.Item(null)
                    // The tag only: CEFileNotApproved carries the unknown servers' addresses.
                    "fileNotApproved" -> return TransportReply.Refused(MediaFailure.FOREIGN_SERVER)
                    "fileCancelled" -> return TransportReply.Refused(MediaFailure.CANCELLED)
                    "fileRcvChunk" -> return TransportReply.Refused(MediaFailure.TOO_LARGE)
                }
                TransportReply.Failed(ErrorMapper.fromCore(e, ErrorMapper.Op.OTHER))
            }
            CoreReply.Invalid -> TransportReply.Failed(ErrorMapper.engine("invalid"))
        }
    }

    override fun cancel(fileId: Long): TransportReply {
        val reply = run(MediaCommands.cancel(fileId))
        return when (reply) {
            is CoreReply.Result -> when (reply.type) {
                "sndFileCancelled", "rcvFileCancelled" -> TransportReply.Item(CoreJson.parseAChatItem(reply.body.optJSONObject("chatItem_")))
                else -> TransportReply.Failed(ErrorMapper.engine("unexpected"))
            }
            is CoreReply.Error ->
                // CEFileCancel: the transfer is already finished or cancelled; nothing left to cancel.
                if (reply.error.type == "error" && reply.error.detail == "fileCancel") TransportReply.Item(null)
                else TransportReply.Failed(ErrorMapper.fromCore(reply.error, ErrorMapper.Op.OTHER))
            CoreReply.Invalid -> TransportReply.Failed(ErrorMapper.engine("invalid"))
        }
    }

    override fun decode(type: String, body: JSONObject): MediaEvent? = MediaJson.event(type, body)

    override val eventTypes: Set<String> get() = MediaJson.EVENTS

    private fun sizeRefusal(e: CoreError): TransportReply? =
        if (e.type == "error" && (e.detail == "fileSize" || e.detail == "fileImageSize")) TransportReply.Refused(MediaFailure.TOO_LARGE) else null
}
