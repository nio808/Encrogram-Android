package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.Commands
import org.json.JSONArray
import org.json.JSONObject

/**
 * Command strings for Secured media (docs/media-plan.md §4.3; syntax from Commands.hs `/freceive`, `/fcancel`, `/_send`).
 * Every JSON fragment goes through [Commands.asciiJson]. Nothing here logs; names and captions are user content.
 */
internal object MediaCommands {

    /**
     * The only receive builder. A download may only start for a [CheckedFile] (a PASSED verdict), and the core is told
     * to refuse any file server that isn't already one of ours. There is deliberately no parameter to change that.
     */
    fun receive(c: CheckedFile): String = "/freceive ${c.fileId} approved_relays=off encrypt=on inline=off"

    fun cancel(fileId: Long): String {
        require(fileId > 0) { "file id" }
        return "/fcancel $fileId"
    }

    /**
     * `/_send <ref> json [...]` with a file already committed into the core's files folder as [fileName] (bare name),
     * stored encrypted with [key]. Not for notes.
     */
    fun send(chat: ChatRef, fileName: String, key: FileKey, content: OutgoingContent, quotedItemId: Long?): String {
        require(!chat.isNotes) { "notes use /_create" }
        require(fileName.isNotEmpty() && fileName != "." && fileName != ".." && '/' !in fileName && '\u0000' !in fileName) { "file name" }
        val mc = JSONObject()
        mc.put("text", content.caption)
        when (content.kind) {
            MediaKind.IMAGE, MediaKind.GIF -> {
                mc.put("type", "image")
                mc.put("image", requireNotNull(content.previewDataUrl) { "preview" })
            }
            MediaKind.VIDEO -> {
                mc.put("type", "video")
                mc.put("image", requireNotNull(content.previewDataUrl) { "preview" })
                mc.put("duration", requireNotNull(content.durationSeconds) { "duration" })
            }
            MediaKind.VOICE -> {
                mc.put("type", "voice")
                mc.put("duration", requireNotNull(content.durationSeconds) { "duration" })
            }
            MediaKind.FILE -> mc.put("type", "file")
        }
        val source = JSONObject()
            .put("filePath", fileName)
            .put("cryptoArgs", JSONObject().put("fileKey", key.key).put("fileNonce", key.nonce))
        val composed = JSONObject().put("fileSource", source).put("msgContent", mc).put("mentions", JSONObject())
        if (quotedItemId != null) composed.put("quotedItemId", quotedItemId)
        return "/_send $chat json " + Commands.asciiJson(JSONArray().put(composed).toString())
    }
}
