package app.encrogram.core.internal.media

import java.util.Base64

/**
 * `data:image/jpg;base64,...` preview URLs, as the core and the official clients write them (Images.android.kt).
 * Only jpg, jpeg and png are accepted: a preview from a peer is untrusted, so svg, html and the rest never decode.
 * Pure JVM (java.util.Base64, strict decoder: no whitespace, no URL alphabet).
 */
internal object DataUrl {

    const val JPEG_PREFIX = "data:image/jpg;base64,"
    const val PNG_PREFIX = "data:image/png;base64,"
    private const val JPEG_ALT_PREFIX = "data:image/jpeg;base64,"

    /** A whole data URL longer than this is not a preview (the core's own ceiling is about 14,000). */
    const val MAX_CHARS = 20_000

    /** A neutral 16x16 grey JPEG: what the UI draws until a real preview exists. */
    const val PLACEHOLDER = JPEG_PREFIX +
        "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwAooooA/9k="

    fun encodeJpeg(bytes: ByteArray): String = JPEG_PREFIX + Base64.getEncoder().encodeToString(bytes)

    fun encodePng(bytes: ByteArray): String = PNG_PREFIX + Base64.getEncoder().encodeToString(bytes)

    /** The bytes of an accepted data URL, or null (wrong prefix, too long, bad base64, empty). */
    fun decode(url: String?): ByteArray? {
        if (url == null || url.length > MAX_CHARS) return null
        val prefix = when {
            url.startsWith(JPEG_PREFIX) -> JPEG_PREFIX
            url.startsWith(JPEG_ALT_PREFIX) -> JPEG_ALT_PREFIX
            url.startsWith(PNG_PREFIX) -> PNG_PREFIX
            else -> return null
        }
        val body = url.substring(prefix.length)
        if (body.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(body).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** True when [url] starts with one of the three accepted prefixes (no decoding). */
    fun hasAcceptedPrefix(url: String?): Boolean =
        url != null && (url.startsWith(JPEG_PREFIX) || url.startsWith(JPEG_ALT_PREFIX) || url.startsWith(PNG_PREFIX))
}
