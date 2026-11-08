package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.str
import org.json.JSONObject

/**
 * Reads the media parts of the core's JSON (docs/media-plan.md §4.2): an item's file (CIFile), the preview and duration
 * of its content, and the file events. Pure JVM. Free text from the core (names aside) is never kept: a FileError's text
 * is reduced to a hint from exact tokens and dropped, because it can hold server addresses.
 */
internal object MediaJson {

    /** The file events the engine hands to the media coordinator (Controller.hs:950-969). */
    val EVENTS: Set<String> = setOf(
        "rcvFileDescrReady", "rcvFileAccepted", "rcvFileStart", "rcvFileProgressXFTP", "rcvFileComplete", "rcvFileError",
        "rcvFileWarning", "rcvFileSndCancelled", "rcvFileCancelled", "rcvFileAcceptedSndCancelled",
        "sndFileProgressXFTP", "sndFileCompleteXFTP", "sndFileError", "sndFileWarning", "sndFileCancelled",
    )

    /** The longest preview data URL kept (the core's own ceiling is about 14,000 characters). */
    const val MAX_PREVIEW_CHARS = DataUrl.MAX_CHARS

    private val SIZE_TOKEN = Regex("(?<![A-Za-z0-9_])SIZE(?![A-Za-z0-9_])")
    private val QUOTA_TOKEN = Regex("(?<![A-Za-z0-9_])QUOTA(?![A-Za-z0-9_])")

    /** The kind of a message's content type; null for text and everything else. [fileName] tells GIF from a still image. */
    fun kindOf(contentType: String?, fileName: String?): MediaKind? = when (contentType) {
        "image" -> {
            val n = fileName?.lowercase().orEmpty()
            if (n.endsWith(".gif") || n.endsWith(".webp")) MediaKind.GIF else MediaKind.IMAGE
        }
        "video" -> MediaKind.VIDEO
        "voice" -> MediaKind.VOICE
        "file" -> MediaKind.FILE
        else -> null
    }

    /** True for the four content types that carry a file and whose caption is `msgContent.text` alone. */
    fun isMediaType(contentType: String?): Boolean = contentType == "image" || contentType == "video" || contentType == "voice" || contentType == "file"

    /**
     * CIFile {fileId, fileName, fileSize, fileSource?, fileStatus, fileProtocol}; null when [file] is null or [msgContent]
     * is not image, video, voice or file content.
     */
    fun parseFile(file: JSONObject?, msgContent: JSONObject?): CoreFile? {
        if (file == null) return null
        val name = file.str("fileName").orEmpty()
        val kind = kindOf(CoreJson.tagOf(msgContent), name) ?: return null
        val status = file.optJSONObject("fileStatus")
        val tag = CoreJson.tagOf(status) ?: "invalid"
        val (done, total) = when (tag) {
            "sndTransfer" -> status!!.optLong("sndProgress", 0L) to status.optLong("sndTotal", 0L)
            "rcvTransfer" -> status!!.optLong("rcvProgress", 0L) to status.optLong("rcvTotal", 0L)
            else -> 0L to 0L
        }
        val errorJson = when (tag) {
            "sndError", "sndWarning" -> status?.optJSONObject("sndFileError")
            "rcvError", "rcvWarning" -> status?.optJSONObject("rcvFileError")
            else -> null
        }
        val errorTag = CoreJson.tagOf(errorJson)
        val hint = if (errorTag == "other") hintOf(errorJson?.str("fileError")) else null
        val source = file.optJSONObject("fileSource")
        val args = source?.optJSONObject("cryptoArgs")
        val key = args?.let {
            val k = it.str("fileKey").orEmpty()
            val n = it.str("fileNonce").orEmpty()
            if (k.isNotEmpty() && n.isNotEmpty()) FileKey(k, n) else null
        }
        return CoreFile(
            fileId = file.optLong("fileId", 0L),
            kind = kind,
            name = name,
            sizeBytes = file.optLong("fileSize", 0L).coerceAtLeast(0L),
            statusTag = tag,
            protocol = file.str("fileProtocol").orEmpty(),
            path = source?.str("filePath")?.ifEmpty { null },
            key = key,
            progressDone = done.coerceAtLeast(0L),
            progressTotal = total.coerceAtLeast(0L),
            errorTag = errorTag,
            errorHint = hint,
        )
    }

    /** Exact tokens only (QUOTA, SIZE as whole words, "file digest"); the text itself is dropped. */
    fun hintOf(text: String?): FileErrorHint? = when {
        text == null -> null
        QUOTA_TOKEN.containsMatchIn(text) -> FileErrorHint.QUOTA
        SIZE_TOKEN.containsMatchIn(text) -> FileErrorHint.SIZE
        text.contains("file digest") -> FileErrorHint.DIGEST
        else -> null
    }

    /** msgContent.image when it is a jpg, jpeg or png data URL of at most [MAX_PREVIEW_CHARS] characters; else null. */
    fun preview(msgContent: JSONObject?): String? {
        val url = msgContent?.str("image") ?: return null
        if (url.length > MAX_PREVIEW_CHARS || !DataUrl.hasAcceptedPrefix(url)) return null
        return url
    }

    /** msgContent.duration in seconds, 0 or more; null when absent. */
    fun duration(msgContent: JSONObject?): Int? {
        if (msgContent == null || !msgContent.has("duration") || msgContent.isNull("duration")) return null
        val d = msgContent.optLong("duration", -1L)
        return if (d in 0..Int.MAX_VALUE.toLong()) d.toInt() else null
    }

    /**
     * A core event of [EVENTS] as a [MediaEvent]; null for any other type, and for a progress or description event that
     * names no item. Other events become [MediaEvent.Changed] (its item may be null when chatItem_ is absent).
     */
    fun event(type: String, body: JSONObject): MediaEvent? {
        if (type !in EVENTS) return null
        val item = CoreJson.parseAChatItem(body.optJSONObject("chatItem") ?: body.optJSONObject("chatItem_"))
        when (type) {
            "rcvFileProgressXFTP" -> return progress(item, body.optLong("receivedSize", 0L), body.optLong("totalSize", 0L))
            "sndFileProgressXFTP" -> return progress(item, body.optLong("sentSize", 0L), body.optLong("totalSize", 0L))
            "rcvFileDescrReady" -> {
                val file = item?.file
                val descr = body.optJSONObject("rcvFileDescr")
                val transferId = body.optJSONObject("rcvFileTransfer")?.optLong("fileId", -1L) ?: -1L
                val text = descr?.str("fileDescrText")
                if (item != null && file != null && descr != null && descr.optBoolean("fileDescrComplete", false) &&
                    transferId == file.fileId && file.fileId > 0 && !text.isNullOrEmpty()
                ) {
                    return MediaEvent.DescriptionReady(item, file.fileId, file.sizeBytes, file.protocol, text)
                }
            }
        }
        return MediaEvent.Changed(item, type)
    }

    private fun progress(item: app.encrogram.core.internal.CoreItem?, done: Long, total: Long): MediaEvent? =
        if (item == null) null else MediaEvent.Progress(item.chatId, item.itemId, done.coerceAtLeast(0L), total.coerceAtLeast(0L))
}
