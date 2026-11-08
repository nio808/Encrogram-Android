package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import java.security.SecureRandom

/** File names: random and neutral for what we make, sanitized for documents. No clock, so no time leaks. */
internal object MediaNames {

    const val MAX_NAME_BYTES = 200
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val random = SecureRandom()

    private fun rand(): String {
        val sb = StringBuilder(8)
        repeat(8) { sb.append(ALPHABET[random.nextInt(32)]) }
        return sb.toString()
    }

    /**
     * A neutral name: IMG_x.jpg, GIF_x.gif or .webp, video_x.mp4 or .mov, voice_x.m4a.
     * [sniffed] decides the extension; documents are not named here (see [sanitizeDocumentName]).
     */
    fun neutral(kind: MediaKind, sniffed: Sniffed = Sniffed.UNKNOWN): String = when (kind) {
        MediaKind.IMAGE -> "IMG_${rand()}.jpg"
        MediaKind.GIF -> "GIF_${rand()}." + if (sniffed == Sniffed.WEBP_ANIMATED || sniffed == Sniffed.WEBP) "webp" else "gif"
        MediaKind.VIDEO -> "video_${rand()}." + if (sniffed == Sniffed.MOV) "mov" else "mp4"
        MediaKind.VOICE -> "voice_${rand()}.m4a"
        MediaKind.FILE -> "file_${rand()}"
    }

    private fun isBidi(c: Char): Boolean =
        c == '\u061C' || c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    private fun utf8Len(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3
    }

    /**
     * A document name that is safe to store and to show: BMP only, no path separators, NUL, control, line-separator
     * or bidi characters; at most [MAX_NAME_BYTES] UTF-8 bytes, keeping the extension; "file" when nothing is left.
     */
    fun sanitizeDocumentName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c == '/' || c == '\\' || c == '\u0000') continue
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029' || isBidi(c)) continue
            if (Character.isSurrogate(c)) continue
            sb.append(c)
        }
        val cleaned = sb.toString().trim()
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "file"
        if (cleaned.sumOf { utf8Len(it) } <= MAX_NAME_BYTES) return cleaned
        val (base, ext) = split(cleaned)
        val extBytes = ext.sumOf { utf8Len(it) }
        // A long "extension" is not one: cut the whole name instead.
        val keepExt = if (extBytes <= 40) ext else ""
        val budget = MAX_NAME_BYTES - keepExt.sumOf { utf8Len(it) }
        val out = StringBuilder()
        var used = 0
        for (c in if (keepExt.isEmpty()) cleaned else base) {
            val l = utf8Len(c)
            if (used + l > budget) break
            out.append(c)
            used += l
        }
        val result = (out.toString().trimEnd() + keepExt)
        return if (result.isBlank()) "file" else result
    }

    /** (base, ".ext") with the dot kept in ext; ext is "" when there is none. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** [name] if [exists] says it is free, else name_1.ext, name_2.ext, ... (before the extension). */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, ext) = split(name)
        for (i in 1..9_999) {
            val candidate = "${base}_$i$ext"
            if (!exists(candidate)) return candidate
        }
        return "${base}_${rand()}$ext"
    }

    /** True when every character is in the BMP (no surrogates) and none is NUL: safe for the core's JNI strings. */
    fun jniSafe(path: String): Boolean = path.none { it == '\u0000' || Character.isSurrogate(it) }
}
