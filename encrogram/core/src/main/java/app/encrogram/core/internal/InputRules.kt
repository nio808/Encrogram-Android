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
    fun replyFits(text: String, quotedText: String): Boolean = replyFits(text, quotedText, 0)

    /**
     * Same, for a reply to a photo or video: the core also copies the quoted PREVIEW into the reply (Internal.hs:314-335),
     * so its data URL ([quotedPreviewChars], 0 when the quoted message has none) counts too. A data URL is ASCII.
     */
    fun replyFits(text: String, quotedText: String, quotedPreviewChars: Int): Boolean =
        encodedTextBytes(text) + encodedTextBytes(quotedText) + QUOTE_OVERHEAD_BYTES + quotedPreviewChars.coerceAtLeast(0) <= MAX_TEXT_BYTES

    /** Everything in a media message except the preview, the caption, the name and a quote (envelope, file record, JSON). */
    const val MEDIA_OVERHEAD_BYTES = 600

    /**
     * A media message must stay under [MAX_TEXT_BYTES] without relying on compression (docs/media-plan.md §2.8):
     * preview + caption + file name + quote + [MEDIA_OVERHEAD_BYTES]. [nameBytes]: the file name's encoded bytes.
     * [quoteBytes]: 0 without a quote; with one, the quoted text's encoded bytes plus [QUOTE_OVERHEAD_BYTES].
     */
    fun mediaFits(caption: String, previewChars: Int, nameBytes: Int, quoteBytes: Int = 0): Boolean =
        previewChars.coerceAtLeast(0).toLong() + encodedTextBytes(caption) + nameBytes.coerceAtLeast(0) +
            quoteBytes.coerceAtLeast(0) + MEDIA_OVERHEAD_BYTES <= MAX_TEXT_BYTES

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

    // ---- display names (H8) -------------------------------------------------------------------------------------

    /** mkValidName keeps at most this many characters (code points) of a name. */
    const val MAX_NAME_CHARS = 50

    /** Characters a name may never contain (Commands.hs mkValidName `prohibited`). */
    const val PROHIBITED_NAME_CHARS = ".,;/\\#@'\"`~"

    /** Why the core would change a name, from the first character it drops. */
    enum class NameProblem { PROHIBITED, START, PUNCTUATION, EMOJI, OTHER }

    /**
     * Commands.hs:6035 mkValidName, character for character: the name the core makes of [name]. The core refuses
     * every display name (profile, new group, group rename: checkValidName, Commands.hs:4058) that this changes.
     * Categories are Java's ([Character.getType]) for Haskell's generalCategory; both follow Unicode, so they agree
     * except on characters one side's Unicode version doesn't know yet.
     */
    fun mkValidName(name: String): String = foldName(name).first

    /**
     * Null when the core takes [name] as it is (non-empty, nothing dropped or changed); else why not. [name] is
     * expected trimmed with its spaces already one each ([normalizeNameSpaces]); a leftover space problem is OTHER.
     */
    fun nameProblem(name: String): NameProblem? {
        if (name.isEmpty()) return NameProblem.OTHER
        val (valid, problem) = foldName(name)
        return if (valid == name) null else problem ?: NameProblem.OTHER
    }

    /** [raw] as it is sent: trimmed, with its spaces one each ([normalizeNameSpaces]). */
    fun cleanName(raw: String): String = normalizeNameSpaces(raw.trim())

    /**
     * Every space character (Haskell's isSpace) becomes one plain space, runs of them one space, and none at the ends:
     * exactly what mkValidName does to spaces, so this never changes what the core keeps of a name.
     */
    fun normalizeNameSpaces(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (hsIsSpace(cp)) {
                if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    /** The fold of mkValidName, plus the reason for the first character it dropped (null: none dropped). */
    private fun foldName(name: String): Pair<String, NameProblem?> {
        val out = StringBuilder(name.length)
        var prev = 0 // '\NUL': nothing kept yet
        var punct = 0
        var problem: NameProblem? = null
        var i = 0
        while (i < name.length) {
            val raw = name.codePointAt(i)
            i += Character.charCount(raw)
            val c = if (hsIsSpace(raw)) SPACE else raw
            val type = Character.getType(c)
            val isPunct = type == Character.CONNECTOR_PUNCTUATION.toInt() || type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt()
            val firstName = isLetterType(type) || type == Character.DECIMAL_DIGIT_NUMBER.toInt() || type == Character.OTHER_SYMBOL.toInt()
            val first = firstName || type == Character.CURRENCY_SYMBOL.toInt() || type == Character.MATH_SYMBOL.toInt()
            val prohibited = c < 0x80 && PROHIBITED_NAME_CHARS.indexOf(c.toChar()) >= 0
            val valid = when {
                prohibited -> false
                prev == 0 -> c > SPACE && firstName
                prev == SPACE -> first || (punct == 0 && isPunct)
                punct > 0 -> first || c == SPACE
                else -> first || c == SPACE || isMarkType(type) || isPunct
            }
            if (valid) {
                out.appendCodePoint(c)
                prev = c
                punct = when {
                    isPunct -> punct + 1
                    c == SPACE -> punct
                    else -> 0
                }
            } else if (problem == null) {
                problem = when {
                    prohibited -> NameProblem.PROHIBITED
                    prev == 0 -> NameProblem.START
                    isPunct && punct > 0 -> NameProblem.PUNCTUATION
                    isEmojiPart(c, prev) -> NameProblem.EMOJI
                    else -> NameProblem.OTHER
                }
            }
        }
        // take 50, then dropWhileEnd isSpace (every space is ' ' by now).
        var kept = out.toString()
        if (kept.codePointCount(0, kept.length) > MAX_NAME_CHARS) kept = kept.substring(0, kept.offsetByCodePoints(0, MAX_NAME_CHARS))
        return kept.trimEnd(' ') to problem
    }

    private const val SPACE = 0x20

    /** GHC's Data.Char.isSpace: tab to carriage return, space and no-break space; above U+0377 the Zs category. */
    private fun hsIsSpace(cp: Int): Boolean =
        if (cp <= 0x377) cp == SPACE || cp in 0x09..0x0D || cp == 0xA0 else Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isLetterType(type: Int): Boolean =
        type == Character.UPPERCASE_LETTER.toInt() || type == Character.LOWERCASE_LETTER.toInt() ||
            type == Character.TITLECASE_LETTER.toInt() || type == Character.MODIFIER_LETTER.toInt() || type == Character.OTHER_LETTER.toInt()

    private fun isMarkType(type: Int): Boolean =
        type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()

    /**
     * Parts of a composed emoji the core drops: a joiner right after an emoji (👨‍💻), a skin tone (👍🏽, modifier
     * symbols), a tag character (subdivision flags).
     */
    private fun isEmojiPart(c: Int, prev: Int): Boolean =
        (c == 0x200D && Character.getType(prev) == Character.OTHER_SYMBOL.toInt()) || c in 0x1F3FB..0x1F3FF || c in 0xE0020..0xE007F

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
     * "last" and "before", the oldest for "after", centred on the id for "around". Group service rows we know
     * ([CoreItem.event]) stay, except in a text search; every other service item is dropped.
     */
    fun pageWindow(items: List<CoreItem>, page: MessagePage, want: Int): List<CoreItem> {
        val msgs = if (page.search != null) items.filter { it.isMessage } else items.filter { it.shown }
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
