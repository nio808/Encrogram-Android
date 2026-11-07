package app.encrogram.core.internal

import app.encrogram.core.api.MessagePage
import app.encrogram.core.api.SECURED_REACTIONS

/**
 * Input limits and checks applied before a command reaches the engine. Pure logic, so it is unit tested on the JVM.
 * Every limit here mirrors a rule of chat core 7.0.0.12; the source line is named next to it.
 */
internal object InputRules {

    /**
     * Protocol.hs: a message body over maxEncodedMsgLength (15602 bytes) is refused, and on PQ connections a
     * body over maxCompressedMsgLength (13380) must compress below 13380. Staying under 13380 including the
     * ~200-byte x.msg.new envelope never depends on compression: 13380 - 380 reserved.
     */
    const val MAX_TEXT_BYTES = 13_000

    /**
     * A reply carries `quote: {msgRef: {msgId, sentAt, sent}, content: {type, text}}` next to the new text
     * (Commands.hs prepareMsgs, mcQuote). Everything but the quoted text itself fits in this many bytes.
     */
    const val QUOTE_OVERHEAD_BYTES = 300

    /** Messages per page the UI can ask for. */
    const val MAX_PAGE = 500

    /** Messages per item action. */
    const val MAX_ITEM_IDS = 100

    /** Library/Internal.hs maxMsgReactions: at most 3 of our own reactions on one item. */
    const val MAX_OWN_REACTIONS = 3

    /** Search text, in characters, before G1 sanitising. */
    const val MAX_SEARCH_CHARS = 256

    /** Characters of a quoted message kept in [app.encrogram.core.api.QuotePreview]: the UI shows one or two lines. */
    const val QUOTE_PREVIEW_CHARS = 300

    /** Disappearing-message timer: the core does not validate it; we accept 1 s to 1 year. */
    const val MAX_TTL_SECONDS = 366 * 24 * 3600

    /**
     * UTF-8 bytes of [text] once the core JSON-encodes it into the message body (aeson writes non-ASCII as raw
     * UTF-8 and escapes quotes, backslashes and control characters). Lone surrogates become U+FFFD (3 bytes).
     */
    fun encodedTextBytes(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            n += when {
                cp == '"'.code || cp == '\\'.code || cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> 2
                cp < 0x20 -> 6
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return n
    }

    fun textFits(text: String): Boolean = encodedTextBytes(text) <= MAX_TEXT_BYTES

    /**
     * The core copies the WHOLE quoted text into the reply's wire message and cannot be told to shorten it
     * (`/_send` takes only `quotedItemId`). So the new text gets what is left of the body cap.
     */
    fun replyFits(text: String, quotedText: String): Boolean =
        encodedTextBytes(text) + encodedTextBytes(quotedText) + QUOTE_OVERHEAD_BYTES <= MAX_TEXT_BYTES

    /** The protocol accepts exactly these eight (Protocol.hs mrEmojiChar); the heart is U+2764 alone. */
    fun reactionAllowed(emoji: String): Boolean = emoji in SECURED_REACTIONS

    fun ttlAllowed(ttlSeconds: Int): Boolean = ttlSeconds in 1..MAX_TTL_SECONDS

    /**
     * Search text for `search=<text>`, which is a plain-text argument passed through JNI as modified UTF-8
     * (gotcha G1): characters outside the BMP (most emoji) arrive mangled and the match silently fails.
     * Such characters, lone surrogates and control characters split the query; the longest remaining run is
     * searched. That can only widen the result, never miss a match. Null when nothing searchable is left.
     */
    fun searchQuery(raw: String): String? {
        var best = ""
        val run = StringBuilder()
        fun endRun() {
            val r = run.toString().trim()
            if (r.length > best.length) best = r
            run.setLength(0)
        }
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val plain = cp in 0x20 until 0x7F || (cp in 0xA0..0xFFFF && cp !in 0xD800..0xDFFF)
            if (plain) run.appendCodePoint(cp) else endRun()
            i += Character.charCount(cp)
        }
        endRun()
        return best.ifEmpty { null }
    }

    /**
     * A security code for `/_verify code` (Commands.hs verifyCodeP: digits and spaces only, the rest of the
     * command must be empty). Whitespace runs become one space. Null when anything else is in it.
     */
    fun securityCode(raw: String): String? {
        val code = raw.trim().split(Regex("\\s+")).joinToString(" ")
        if (code.isEmpty() || code.length > 256) return null
        if (!code.all { it in '0'..'9' || it == ' ' }) return null
        return code
    }

    /** A short preview of a quoted message, never cutting a surrogate pair. */
    fun preview(text: String, maxChars: Int = QUOTE_PREVIEW_CHARS): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** How many items to ask the core for: service items count against its page, so ask for more. */
    fun corePageCount(page: MessagePage, want: Int): Int =
        if (page.aroundId != null) minOf(want / 2 + 50, MAX_PAGE) else minOf(want + 50, MAX_PAGE * 2)

    /**
     * The messages of a core page (items oldest first), cut to [want] the way the UI asked: the newest for
     * "last" and "before", the oldest for "after", centred on the id for "around". Service items are dropped.
     */
    fun pageWindow(items: List<CoreItem>, page: MessagePage, want: Int): List<CoreItem> {
        val msgs = items.filter { it.isMessage }
        val around = page.aroundId
        return when {
            page.afterId != null -> msgs.filter { it.itemId != page.afterId }.take(want)
            around != null -> {
                val idx = msgs.indexOfFirst { it.itemId >= around }.let { if (it < 0) msgs.size else it }
                var start = (idx - want / 2).coerceAtLeast(0)
                val end = (start + want).coerceAtMost(msgs.size)
                start = (end - want).coerceAtLeast(0)
                msgs.subList(start, end).toList()
            }
            else -> msgs.filter { it.itemId != page.beforeId }.takeLast(want)
        }
    }
}
