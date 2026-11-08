package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind

/** What the first bytes of a file say it is. The sniff always wins over a declared MIME type or extension. */
internal enum class Sniffed { JPEG, PNG, GIF, WEBP, WEBP_ANIMATED, HEIC, MP4, MOV, M4A, UNKNOWN }

/** Pure: magic-byte sniffing, kind choice and a fixed MIME map. */
internal object MediaKindRules {

    /** Bytes [sniff] needs at most. */
    const val HEAD_BYTES = 64

    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "heif")
    private val MP4_BRANDS = setOf("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "m4v ", "mmp4", "3gp4", "3gp5", "3gp6", "3g2a")
    private val M4A_BRANDS = setOf("m4a ", "m4b ")

    fun sniff(head: ByteArray): Sniffed {
        val n = head.size
        fun b(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        fun ascii(from: Int, len: Int): String? =
            if (from + len <= n) String(head, from, len, Charsets.ISO_8859_1) else null

        if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return Sniffed.JPEG
        if (b(0) == 0x89 && ascii(1, 3) == "PNG" && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A) return Sniffed.PNG
        val g = ascii(0, 6)
        if (g == "GIF87a" || g == "GIF89a") return Sniffed.GIF
        if (ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
            // VP8X extended header: the flags byte follows the chunk size; bit 1 (0x02) is the animation flag.
            if (ascii(12, 4) == "VP8X" && b(20) >= 0 && (b(20) and 0x02) != 0) return Sniffed.WEBP_ANIMATED
            return Sniffed.WEBP
        }
        if (ascii(4, 4) == "ftyp") {
            val brand = ascii(8, 4)?.lowercase() ?: return Sniffed.UNKNOWN
            when {
                brand in HEIC_BRANDS -> return Sniffed.HEIC
                brand == "qt  " -> return Sniffed.MOV
                brand in M4A_BRANDS -> return Sniffed.M4A
                brand in MP4_BRANDS -> return Sniffed.MP4
            }
            // Unknown major brand: look at the compatible brands that fit in the head.
            var i = 16
            while (i + 4 <= n) {
                val c = ascii(i, 4)?.lowercase()
                if (c != null && (c in MP4_BRANDS)) return Sniffed.MP4
                i += 4
            }
        }
        return Sniffed.UNKNOWN
    }

    /**
     * The kind of something picked to send. [asFile] (the user chose "send as file") is always FILE.
     * Pictures go through re-encoding, so only the formats the decoder knows are IMAGE.
     */
    fun kindForSending(head: ByteArray, asFile: Boolean): MediaKind {
        if (asFile) return MediaKind.FILE
        return when (sniff(head)) {
            Sniffed.JPEG, Sniffed.PNG, Sniffed.WEBP, Sniffed.HEIC -> MediaKind.IMAGE
            Sniffed.GIF, Sniffed.WEBP_ANIMATED -> MediaKind.GIF
            Sniffed.MP4, Sniffed.MOV -> MediaKind.VIDEO
            Sniffed.M4A, Sniffed.UNKNOWN -> MediaKind.FILE
        }
    }

    /**
     * The kind of an incoming item from the core's message type ("image", "video", "voice", "file", ...) and the
     * invited file name. An image named .gif, or whose [head] says GIF or animated WebP, is GIF.
     * Anything the type does not name is FILE. The name is only inspected, never kept or logged.
     */
    fun kindForReceiving(msgContentType: String?, fileName: String, head: ByteArray? = null): MediaKind =
        when (msgContentType) {
            "image" -> {
                val sniffed = head?.let { sniff(it) }
                if (sniffed == Sniffed.GIF || sniffed == Sniffed.WEBP_ANIMATED || extensionOf(fileName) == "gif") MediaKind.GIF
                else MediaKind.IMAGE
            }
            "video" -> MediaKind.VIDEO
            "voice" -> MediaKind.VOICE
            else -> MediaKind.FILE
        }

    /** Lower-case extension without the dot, or "". */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
        "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg", "wav" to "audio/wav",
        "pdf" to "application/pdf", "txt" to "text/plain", "zip" to "application/zip",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    /** Fixed MIME by extension. Unknown (and active content such as html or svg) is octet-stream, never rendered. */
    fun mimeFor(name: String): String = MIME[extensionOf(name)] ?: "application/octet-stream"
}
