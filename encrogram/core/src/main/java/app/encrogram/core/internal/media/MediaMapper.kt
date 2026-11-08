package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPreview
import app.encrogram.core.api.MediaState
import app.encrogram.core.api.SecuredMedia
import app.encrogram.core.internal.CoreItem

/**
 * A parsed item's media as the contract's [SecuredMedia] (docs/media-plan.md §4.2). Pure logic. The preview is
 * untrusted: it must be a jpg, jpeg or png data URL whose bytes really are that format with a small size in the header,
 * so the UI can decode it in memory without surprises.
 */
internal object MediaMapper {

    /** A preview wider or taller than this is refused (our own are about 200 px; the official clients' about 300 px). */
    const val MAX_PREVIEW_SIDE = 2048

    private const val MAX_NAME_BYTES = 200

    /** Our own neutral names (MediaNames): IMG_, GIF_, video_, voice_ + 8 base32 characters + an extension. */
    private val NEUTRAL_NAME = Regex("^(IMG_|GIF_|video_|voice_)[A-Za-z2-7]{8}\\.[A-Za-z0-9]{1,5}$")

    /** The media of [item]; null for text, service rows and deleted messages. */
    fun map(item: CoreItem): SecuredMedia? {
        if (item.deleted || !item.isMessage) return null
        val kind = kindOf(item) ?: return null
        val file = item.file
            // A media message whose file record is gone: shown as not on this phone.
            ?: return SecuredMedia(kind, "", 0L, MediaState.FAILED, MediaFailure.NOT_ON_DEVICE)
        val m = FileStatusMapper.map(file, item.outgoing)
        return SecuredMedia(
            kind = kind,
            name = displayName(file.name),
            sizeBytes = file.sizeBytes,
            state = m.state,
            failure = m.failure,
            transferredBytes = m.transferredBytes,
            readable = m.readable,
            mimeType = mimeOf(kind, file.name),
            preview = if (kind == MediaKind.IMAGE || kind == MediaKind.GIF || kind == MediaKind.VIDEO) previewOf(item.preview) else null,
            durationSeconds = if (kind == MediaKind.VIDEO || kind == MediaKind.VOICE) item.durationSeconds else null,
        )
    }

    /** The media kind of a message item: its file's kind, else from the content type; null for text and the rest. */
    fun kindOf(item: CoreItem): MediaKind? {
        if (item.deleted || !item.isMessage) return null
        return item.file?.kind ?: kindOfContentType(item.msgContentType)
    }

    /** The kind for a quoted message's content type (a quote has no file name, so a GIF reads as IMAGE). */
    fun kindOfContentType(type: String?): MediaKind? = MediaJson.kindOf(type, null)

    /**
     * A quote's text for display: a captionless quoted media message carries its file name, and our own neutral names
     * (IMG_ab2cd3ef.jpg) mean nothing to read, so they become empty (the UI then shows "Photo", "Video"...).
     */
    fun quoteText(text: String): String = if (NEUTRAL_NAME.matches(text.trim())) "" else text

    /** A preview from the message: bytes only when it is a real, small JPEG or PNG. */
    fun previewOf(dataUrl: String?): MediaPreview? {
        val bytes = DataUrl.decode(dataUrl) ?: return null
        val format = ImageHeaders.format(bytes)
        if (format != ImageHeaders.Format.JPEG && format != ImageHeaders.Format.PNG) return null
        val size = ImageHeaders.size(bytes) ?: return null
        if (size.width > MAX_PREVIEW_SIDE || size.height > MAX_PREVIEW_SIDE) return null
        return MediaPreview(bytes)
    }

    /**
     * A file name safe to show: the last path part only, no control or bidi characters (U+202E and friends), at most
     * 200 UTF-8 bytes keeping the extension, "file" when nothing is left.
     */
    fun displayName(raw: String): String {
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder()
        var i = 0
        while (i < last.length) {
            val cp = last.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0xD800..0xDFFF) continue
            if (Character.getType(cp) == Character.CONTROL.toInt() || cp in BIDI || cp == 0x2028 || cp == 0x2029 || cp == 0xFEFF) continue
            sb.appendCodePoint(cp)
        }
        val clean = sb.toString().trim()
        if (clean.isEmpty() || clean == "." || clean == "..") return "file"
        return cutBytes(clean, MAX_NAME_BYTES)
    }

    /** Bidi controls, plus invisible characters that make look-alike names (zero-width, word joiner, soft hyphen). */
    private val BIDI: Set<Int> = setOf(0x061C, 0x200E, 0x200F, 0x200B, 0x200C, 0x200D, 0x2060, 0x00AD) + (0x202A..0x202E) + (0x2066..0x2069)

    private fun cutBytes(s: String, max: Int): String {
        if (utf8(s) <= max) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && utf8(s.substring(dot)) <= 20) s.substring(dot) else ""
        val budget = max - utf8(ext)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        val stem = s.substring(0, s.length - ext.length)
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val n = utf8Of(cp)
            if (used + n > budget) break
            sb.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString() + ext
    }

    private fun utf8(s: String): Int = s.toByteArray(Charsets.UTF_8).size
    private fun utf8Of(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /** A MIME type from the kind and the file name's extension; null when unknown. Informational only. */
    fun mimeOf(kind: MediaKind, name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (kind) {
            MediaKind.IMAGE, MediaKind.GIF -> when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                else -> null
            }
            MediaKind.VIDEO -> when (ext) {
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                else -> null
            }
            MediaKind.VOICE -> when (ext) {
                "m4a", "mp4", "aac" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                else -> null
            }
            MediaKind.FILE -> null
        }
    }
}
