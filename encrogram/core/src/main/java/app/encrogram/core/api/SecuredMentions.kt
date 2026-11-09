package app.encrogram.core.api

/**
 * Group mentions (`@name`), the way the SimpleX core reads them (reference/simplex-chat 7.0.0.12: Markdown.hs
 * markdownP/mentionP/displayNameTextP_, Library/Internal.hs:267-275 getCIMentions). Pure Kotlin: no Android, no core.
 *
 * The UI inserts [MentionToken.text] for a member and keeps `key -> memberId` in the map it passes to
 * [SecuredEngine.sendMessage]; the engine sends only keys that still appear as mentions in the text ([forSend]).
 */
object SecuredMentions {

    /** At most this many members per message (maxSndMentions, Library/Internal.hs:121-122). */
    const val MAX_MENTIONS = 3

    /**
     * [key]: the name in the mentions map (the member's name as you see it, made unique among [taken] with `_1`, `_2`...,
     * like the official app). [text]: what goes into the message, `@key`, or `@'key'` when the key has spaces, dots or
     * ends in punctuation. Insert it at the start of a line or after a space, and follow it with a space.
     */
    data class MentionToken(val key: String, val text: String) {
        /** Names are user content: never logged. */
        override fun toString() = "MentionToken"
    }

    /** The token for a member whose shown name is [displayName]. [taken]: keys already used in this message. */
    @JvmStatic
    @JvmOverloads
    fun token(displayName: String, taken: Collection<String> = emptyList()): MentionToken {
        val base = cleanKey(displayName)
        var key = base
        var n = 0
        while (key in taken) key = base + "_" + (++n)
        return MentionToken(key, if (plainForm(key)) "@$key" else "@'$key'")
    }

    /**
     * [mentions] reduced to what the core accepts (getCIMentions): only names the core reads as mentions in [text]
     * (in the order they appear), each member once, at most [MAX_MENTIONS]. Anything else would make the core refuse
     * the whole message (SEInvalidMention).
     */
    internal fun forSend(text: String, mentions: Map<String, Long>): Map<String, Long> {
        if (mentions.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        val members = HashSet<Long>()
        for (name in mentionedNames(text)) {
            val id = mentions[name] ?: continue
            if (id <= 0 || !members.add(id)) continue
            out[name] = id
            if (out.size == MAX_MENTIONS) break
        }
        return out
    }

    /**
     * The names the core's markdown reads as mentions in [text], in order, each once. Conservative: a name is listed
     * only when the core certainly reads it so (a markdown link `[..](..)` makes the rest of its line uncertain, and a
     * dotted `@a.b` may be a SimpleX name), because sending a name the core doesn't see fails the whole message.
     */
    internal fun mentionedNames(text: String): List<String> {
        val lines = lines(text)
        if (lines.isEmpty()) return emptyList()
        if (lines.size == 1) {
            // parseMaybeMarkdownList (Markdown.hs:148-157): one code point only, or a /command line, has no markdown.
            val t = lines[0].trimStart(' ')
            if (t.codePointCount(0, t.length) <= 1 || t.startsWith('/')) return emptyList()
        }
        val out = LinkedHashSet<String>()
        for (line in lines) scanLine(line, out)
        out.remove("")
        return out.toList()
    }

    // ---- token form ----

    private fun cleanKey(name: String): String {
        val sb = StringBuilder()
        var space = false
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\''.code -> Unit                      // can't be quoted: dropped
                isSpace(cp) || Character.isISOControl(cp) -> space = sb.isNotEmpty()
                else -> {
                    if (space) sb.append(' ')
                    space = false
                    sb.appendCodePoint(cp)
                }
            }
        }
        // A name must start with a character the core accepts (refChar): no leading '#' or '@'.
        val key = sb.toString().trimStart('#', '@', ' ')
        return key.ifEmpty { "member" }
    }

    /** `@key` reads back as exactly [key]: no spaces or dots, not ending in punctuation. */
    private fun plainForm(key: String): Boolean {
        if (key.isEmpty() || !refChar(key.codePointAt(0)) || key.contains('.')) return false
        var i = 0
        while (i < key.length) {
            val cp = key.codePointAt(i)
            if (isSpace(cp)) return false
            i += Character.charCount(cp)
        }
        return !isPunctuation(key.codePointBefore(key.length))
    }

    // ---- the core's fragment parser, as far as mentions need it (Markdown.hs:203-330) ----

    /** T.lines: split on '\n'; a final '\n' starts no extra line. */
    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (text.endsWith('\n')) parts.dropLast(1) else parts
    }

    private fun scanLine(line: String, out: MutableSet<String>) {
        val n = line.length
        var i = 0
        while (i < n) {
            val c = line[i]
            i = when {
                c == ' ' -> skipWhile(line, i) { it == ' ' }
                c == '*' || c == '_' || c == '~' || c == '`' -> {
                    // formattedP: through the next same character, or the rest of the line when there is none.
                    val close = line.indexOf(c, i + 1)
                    if (close < 0) n else close + 1
                }
                c == '#' -> secretEnd(line, i + 1) ?: wordEnd(line, i + 1)
                c == '!' -> styledEnd(line, i) ?: wordEnd(line, i)
                c == '@' -> mention(line, i, out)
                c == '/' -> commandEnd(line, i) ?: wordEnd(line, i)
                // sowLinkP: a hyperlink may swallow what follows; whether it is one needs URI parsing, so stop here.
                c == '[' -> if (linkShape(line, i)) return else wordEnd(line, i)
                c == '+' || c in '0'..'9' -> phoneEnd(line, i) ?: wordEnd(line, i)
                else -> wordEnd(line, i)
            }
        }
    }

    /** wordP: up to the next space. */
    private fun wordEnd(line: String, from: Int): Int = line.indexOf(' ', from).let { if (it < 0) line.length else it }

    private inline fun skipWhile(line: String, from: Int, p: (Char) -> Boolean): Int {
        var i = from
        while (i < line.length && p(line[i])) i++
        return i
    }

    /** At '@' ([at]): records the mention, if any, and returns where the next fragment starts. */
    private fun mention(line: String, at: Int, out: MutableSet<String>): Int {
        val start = at + 1
        if (start >= line.length) return line.length
        val wordEnd = wordEnd(line, start)
        // nameRefP '@' first: a dotted name may be a SimpleX name (@domain.simplex), then no mention.
        val name = splitPunctuation(line.substring(start, wordEnd))
        if (name.contains('.') && simplexDomain(name)) return wordEnd
        val c = line[start]
        if (c == '\'') {
            val close = line.indexOf('\'', start + 1)
            if (close > start + 1 && refChar(line.codePointAt(start + 1))) {
                out.add(line.substring(start + 1, close))
                return close + 1
            }
            return wordEnd
        }
        if (!refChar(line.codePointAt(start))) return wordEnd
        // Up to the next whitespace (a non-space one starts a word that runs to the next space anyway).
        var end = start
        while (end < line.length) {
            val cp = line.codePointAt(end)
            if (isSpace(cp)) break
            end += Character.charCount(cp)
        }
        out.add(splitPunctuation(line.substring(start, end)))
        return wordEnd
    }

    /** secretP after the first '#': `#..#text#..#`; null when it isn't one. */
    private fun secretEnd(line: String, from: Int): Int? {
        val sStart = skipWhile(line, from) { it == '#' }
        val close = line.indexOf('#', sStart)
        if (close < 0 || close == sStart) return null
        val s = line.substring(sStart, close)
        if (s.first() == ' ' || s.last() == ' ') return null
        return skipWhile(line, close) { it == '#' }
    }

    /** styledP at '!': `!<color|-> text!`; null when it isn't one. */
    private fun styledEnd(line: String, at: Int): Int? {
        var i = at + 1
        if (i >= line.length) return null
        val f = line[i++]
        if (f != '-' && f !in '1'..'6') {
            val suffix = COLOR_SUFFIX[f] ?: return null
            if (line.startsWith(suffix, i)) i += suffix.length
        }
        if (i >= line.length || !isSpace(line.codePointAt(i))) return null
        i += Character.charCount(line.codePointAt(i))
        if (i >= line.length || line[i] == ' ' || line[i] == '!') return null
        val close = line.indexOf('!', i)
        if (close < 0 || line[close - 1] == ' ') return null
        return close + 1
    }

    /** commandP at '/': `/word...` or `/'quoted words'` whose first word is letters, digits and '_'; null otherwise. */
    private fun commandEnd(line: String, at: Int): Int? {
        val start = at + 1
        if (start >= line.length) return null
        val cmd: String
        val end: Int
        if (line[start] == '\'') {
            val close = line.indexOf('\'', start + 1)
            if (close <= start + 1 || !refChar(line.codePointAt(start + 1))) return null
            cmd = line.substring(start + 1, close)
            end = close + 1
        } else {
            if (!refChar(line.codePointAt(start))) return null
            var e = start
            while (e < line.length && !isSpace(line.codePointAt(e))) e += Character.charCount(line.codePointAt(e))
            cmd = splitPunctuation(line.substring(start, e))
            end = wordEnd(line, start)
        }
        val keyword = firstWord(cmd) ?: return null
        var i = 0
        while (i < keyword.length) {
            val cp = keyword.codePointAt(i)
            if (!(Character.isLetter(cp) || cp in '0'.code..'9'.code || cp == '_'.code)) return null
            i += Character.charCount(cp)
        }
        return end
    }

    private fun firstWord(s: String): String? {
        var i = 0
        while (i < s.length && isSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
        val start = i
        while (i < s.length && !isSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
        return if (i > start) s.substring(start, i) else null
    }

    /** `[text](link)` shape at '['. */
    private fun linkShape(line: String, at: Int): Boolean {
        val close = line.indexOf(']', at + 1)
        if (close <= at + 1 || close + 1 >= line.length || line[close + 1] != '(') return false
        val end = line.indexOf(')', close + 2)
        return end > close + 2
    }

    /** phoneP: 7 to 22 characters of `+`, digits and separators; null when it isn't one. */
    private fun phoneEnd(line: String, at: Int): Int? {
        val n = line.length
        fun digitsEnd(from: Int) = skipWhile(line, from) { it in '0'..'9' }
        fun isDigit(i: Int) = i < n && line[i] in '0'..'9'
        fun isSep(i: Int) = i < n && (line[i] == ' ' || line[i] == '-' || line[i] == '.')
        var j = at
        if (line[j] == '+' && isDigit(j + 1)) j = digitsEnd(j + 1)
        // Optional "(digits)", after one separator or none; a separator not followed by '(' gives no code.
        val open = if (isSep(j)) j + 1 else j
        if (open < n && line[open] == '(' && isDigit(open + 1)) {
            val d = digitsEnd(open + 1)
            if (d < n && line[d] == ')') j = d + 1
        }
        while (true) {
            j = when {
                isSep(j) && isDigit(j + 1) -> digitsEnd(j + 1)
                isDigit(j) -> digitsEnd(j)
                else -> break
            }
        }
        return if (j - at in 7..22) j else null
    }

    /** SimplexDomain strP (simplexmq SimplexName.hs:92-104): ASCII labels (letters, digits, single '-') joined by '.', two or more. */
    private fun simplexDomain(name: String): Boolean {
        if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 253) return false
        val labels = name.split('.')
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 && label.split('-').all { part ->
                part.isNotEmpty() && part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            }
        }
    }

    private fun splitPunctuation(s: String): String {
        var end = s.length
        while (end > 0) {
            val cp = s.codePointBefore(end)
            if (!isPunctuation(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(0, end)
    }

    /** Markdown.hs refChar: a name's first character. */
    private fun refChar(cp: Int) = cp > ' '.code && cp != '#'.code && cp != '@'.code && cp != '\''.code

    /** Data.Char.isSpace. */
    private fun isSpace(cp: Int) =
        cp == ' '.code || cp in 0x09..0x0D || cp == 0xA0 || (cp > 0x377 && Character.getType(cp) == Character.SPACE_SEPARATOR.toInt())

    /** Data.Char.isPunctuation: the seven Unicode punctuation categories. */
    private fun isPunctuation(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /** colorP's long names (Markdown.hs:290-303): `!r` or `!red`, ... */
    private val COLOR_SUFFIX = mapOf('r' to "ed", 'g' to "reen", 'b' to "lue", 'y' to "ellow", 'c' to "yan", 'm' to "agenta")
}
