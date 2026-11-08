package app.encrogram.core.internal.media

import app.encrogram.core.internal.ServerAddress

/**
 * Our own check of an incoming file's XFTP description (the YAML text of `rcvFileDescrReady`), run before any
 * download may start (AGENTS.md "Media v1", docs/media-plan.md §5.2 and §6). Pure Kotlin, no I/O, never throws.
 *
 * Why we check it ourselves: with `approved_relays=off` the core refuses outer servers it does not know, but it skips
 * that check when SOCKS is on, and a `redirect` block hides the real chunk servers from it
 * (research/media-engine.md, "The redirect gap"). This check is the second, independent wall: every chunk comes
 * from one of our XFTP relays, there is no redirect, and the size stays within the invitation.
 *
 * How: a strict whitelist of what the canonical encoder writes, line by line. The encoder is
 * `strEncode = Y.encode . encodeFileDescription` (reference/simplexmq/src/Simplex/FileTransfer/Description.hs:212,
 * :230-241); its exact output is pinned by tests/fixtures/file_description.yaml, which
 * tests/FileDescriptionTests.hs:160-165 compares byte for byte. Anything that encoder never writes is MALFORMED.
 * That rules out every YAML feature that could make the core read something other than what we read: anchors,
 * aliases, tags, flow style, block scalars, double quotes, comments, document markers, continuation lines,
 * duplicate keys and unknown keys.
 *
 * Accepted grammar (one space after each `:`, no other spaces than indentation, LF line ends, ASCII only):
 * ```
 * chunkSize: SIZE32          top level, column 0, keys in any order, each exactly once,
 * digest: B64                all seven required (YAMLFileDescription, Description.hs:158-168)
 * key: B64(32 bytes)
 * nonce: B64(24 bytes)
 * party: recipient           `sender` -> NOT_RECIPIENT
 * replicas:                  then one or more items at column R (R = 0 as the encoder writes, or 2)
 * - chunks:                  item keys at column R+2, `chunks` and `server` each exactly once, any order
 *   - CHUNK                  chunk items at column R+2 (as the encoder writes) or R+4, at least one
 *   server: SERVER           (YAMLServerReplicas, Description.hs:170-174)
 * size: SIZE64
 * ```
 * - `redirect` as a top-level key, with any value or none -> REDIRECT. defaultJSON omits Nothing fields
 *   (Description.hs:195-199, Messaging/Parsers.hs:152-153), so an honest chat-file description never has the key.
 * - A top-level value may be wrapped in single quotes (Data.Yaml quotes numeric strings, so `size: '1234567'`).
 *   A digits-only size must be quoted: unquoted, YAML reads it as a number, which the core's String field refuses.
 * - SIZE: digits plus an optional `kb`/`mb`/`gb`, in the canonical form only (FileSize strEncode, Description.hs:287-296):
 *   `4mb`, never `4096kb`; no leading zeros. SIZE32 must fit a Word32 and is never 0, SIZE64 must fit an Int64:
 *   the core's `A.decimal` would wrap an overflow silently (:297-304), so an overflow is MALFORMED here.
 * - CHUNK: `N:replicaId:replicaKey[:digest[:SIZE32]]` (encodeServerReplica / serverReplicaP, Description.hs:327-344).
 *   The 4th field is always the digest and the 5th the chunk size, positionally, as serverReplicaP reads them.
 * - SERVER: `xftp://keyHash@host[,host...][:port]`, lower case. strEncode never writes a password
 *   (Messaging/Protocol.hs:1309-1311) and the core refuses one (:1312-1315), so a password is MALFORMED.
 * - B64: base64url with `=` padding, as `U.encode` writes it (Messaging/Encoding/String.hs:76-87). The key is exactly
 *   32 bytes (sbKey, Messaging/Crypto.hs:1444-1447) and the nonce exactly 24 bytes, as the encoder writes it
 *   (cbNonce would pad or cut other lengths, Crypto.hs:1401-1407; we accept only the canonical one).
 *
 * After the grammar, in this order: party; servers (every one is ours, by [ServerAddress.sameRelayExact]); chunks
 * (numbered 1..n without gaps, one digest each, no conflicting digest or size; Description.hs:221-227, :374-407);
 * sizes (the core's own margin is the same 10 MiB, reference/simplex-chat/src/Simplex/Chat/Library/Internal.hs:757-763).
 *
 * Secrets: the key, the nonce, the replica ids and replica keys are only shape-checked, never stored. Chunk digests
 * live in a local map for the conflict check and go with it. Nothing survives the call; the verdict is an enum.
 */
internal object FileDescriptionCheck {

    /** Longer descriptions are refused. The core itself stops at 1 MiB (simplex-chat Store/Files.hs:570-571). */
    const val MAX_TEXT_CHARS = 1_000_000

    /** More lines are refused. A 100 MiB file in 4 MiB chunks needs about 35 lines. */
    const val MAX_LINES = 20_000

    /**
     * How much bigger than the invitation a description may be. The encrypted upload adds a header, length and auth
     * tag, then pads to whole chunks (simplexmq FileTransfer/Agent.hs:436-445, Client.hs:344-359), so it is always a
     * little larger than the file and never more than one 4 MiB chunk larger. The core allows the same 10 MiB.
     */
    const val SIZE_MARGIN = 10L * 1024 * 1024

    private const val WORD32_MAX = 0xFFFF_FFFFL
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val MAX_CHUNK_NO_DIGITS = 9

    private val REQUIRED = setOf("chunkSize", "digest", "key", "nonce", "party", "replicas", "size")

    /** The only characters an honest description contains, besides LF. Everything else (tab, CR, `"`, `#`, `{`, `[`, `&`, `*`, `!`, `|`, `>`, `%`, `?`, `~`, non-ASCII) is MALFORMED. */
    private const val ALLOWED_PUNCTUATION = " :-_=/@.,'"

    /**
     * Words Data.Yaml reads as booleans or null when unquoted (yaml-0.11 Data.Yaml.Internal specialStrings). A plain
     * value equal to one of them is not a string to the core, so it is refused.
     */
    private val YAML_SPECIAL = setOf(
        "y", "Y", "yes", "Yes", "YES", "n", "N", "no", "No", "NO", "true", "True", "TRUE", "false", "False", "FALSE",
        "on", "On", "ON", "off", "Off", "OFF", "null", "Null", "NULL",
    )

    /**
     * The verdict on [text], the complete description of an incoming file offered with [invitedSize] bytes over
     * [protocol]. Only [Verdict.PASSED] may be downloaded. [ourServers] are our configured relays; entries that are
     * not `xftp` are ignored, and an empty list refuses every description. Never throws: anything unexpected is
     * [Verdict.MALFORMED].
     */
    fun check(
        text: String?,
        invitedSize: Long,
        protocol: String,
        ourServers: List<ServerAddress>,
        maxBytes: Long = 100L * 1024 * 1024,
    ): Verdict =
        try {
            verdictOf(text, invitedSize, protocol, ourServers, maxBytes)
        } catch (stop: Stop) {
            stop.verdict
        } catch (e: Exception) {
            Verdict.MALFORMED
        } catch (e: StackOverflowError) {
            Verdict.MALFORMED
        }

    private fun verdictOf(text: String?, invitedSize: Long, protocol: String, ourServers: List<ServerAddress>, maxBytes: Long): Verdict {
        if (protocol != "xftp") return Verdict.PROTOCOL
        if (invitedSize < 0) return Verdict.SIZE_MISMATCH
        if (invitedSize > maxBytes) return Verdict.TOO_LARGE
        val lines = linesOf(text) ?: return Verdict.MALFORMED
        val doc = Parser(lines).parse()

        if (doc.party != "recipient") return Verdict.NOT_RECIPIENT

        val ours = ourServers.filter { it.scheme == "xftp" }
        for (server in doc.servers) {
            val address = ServerAddress.parse(server)
            if (address == null || address.scheme != "xftp" || ours.none { it.sameRelayExact(address) }) return Verdict.FOREIGN_SERVER
        }

        // foldReplicasToChunks (Description.hs:374-407): any replica may carry the chunk's digest and size, but two
        // replicas must not disagree, and every chunk needs a digest. validateFileDescription (:221-227): the chunk
        // numbers are exactly 1..n, and the chunk sizes add up to the file size.
        val digests = HashMap<Int, String>()
        val sizes = HashMap<Int, Long>()
        for (chunk in doc.chunks) {
            if (chunk.digest != null) {
                val previous = digests.putIfAbsent(chunk.no, chunk.digest)
                if (previous != null && previous != chunk.digest) return Verdict.MALFORMED
            }
            if (chunk.size > 0) {
                val previous = sizes.putIfAbsent(chunk.no, chunk.size)
                if (previous != null && previous != chunk.size) return Verdict.MALFORMED
            }
        }
        val numbers = doc.chunks.mapTo(HashSet()) { it.no }
        val count = numbers.size
        if (numbers.any { it > count }) return Verdict.MALFORMED   // distinct, all >= 1, none above n: exactly 1..n
        if (digests.size != count) return Verdict.MALFORMED
        var total = 0L
        for (no in 1..count) total += sizes[no] ?: doc.chunkSize

        val described = doc.size
        if (described > saturatedAdd(maxBytes, SIZE_MARGIN)) return Verdict.TOO_LARGE
        if (total != described) return Verdict.SIZE_MISMATCH
        if (described < invitedSize || described > saturatedAdd(invitedSize, SIZE_MARGIN)) return Verdict.SIZE_MISMATCH
        return Verdict.PASSED
    }

    /** The lines of [text] without line ends, or null when the text breaks a character, length or line rule. */
    private fun linesOf(text: String?): List<String>? {
        if (text.isNullOrEmpty() || text.length > MAX_TEXT_CHARS) return null
        var breaks = 0
        for (c in text) {
            if (c == '\n') breaks++ else if (!allowed(c)) return null
        }
        val endsWithBreak = text[text.length - 1] == '\n'
        val lineCount = if (endsWithBreak) breaks else breaks + 1
        if (lineCount > MAX_LINES) return null
        val body = if (endsWithBreak) text.substring(0, text.length - 1) else text
        val lines = body.split('\n')
        // The encoder writes no blank lines and no trailing spaces; one final LF (Y.encode ends with one) is optional.
        if (lines.any { it.isEmpty() || it[it.length - 1] == ' ' }) return null
        return lines
    }

    private fun allowed(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || ALLOWED_PUNCTUATION.indexOf(c) >= 0

    private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    // ---- grammar --------------------------------------------------------------------------------------------------

    /** Ends the check early with [verdict]. Internal to this file; [check] turns it into its result. */
    private class Stop(val verdict: Verdict) : RuntimeException(null, null, false, false)

    private fun malformed(): Nothing = throw Stop(Verdict.MALFORMED)

    /** One chunk line, without its replica id and key. [size] is 0 when the line carries none. */
    private class Chunk(val no: Int, val digest: String?, val size: Long)

    /** What the grammar keeps: no key, nonce, replica id or replica key. */
    private class Doc {
        var party = ""
        var size = 0L
        var chunkSize = 0L
        val servers = ArrayList<String>()
        val chunks = ArrayList<Chunk>()
    }

    /** A single pass over the lines. Every line must be one of the shapes in the class comment, at its column. */
    private class Parser(private val lines: List<String>) {
        private var i = 0
        private val doc = Doc()

        fun parse(): Doc {
            val seen = HashSet<String>()
            while (i < lines.size) {
                val line = lines[i]
                if (line[0] == ' ') malformed()   // a top-level key sits at column 0; deeper lines belong to `replicas`
                val (key, value) = keyValue(line, 0)
                if (key == "redirect") throw Stop(Verdict.REDIRECT)
                if (!seen.add(key)) malformed()
                i++
                when (key) {
                    "replicas" -> if (value == null) replicas() else malformed()
                    "party" -> doc.party = scalar(value).also { if (it != "recipient" && it != "sender") malformed() }
                    "size" -> doc.size = topSize(value, Long.MAX_VALUE)
                    "chunkSize" -> doc.chunkSize = topSize(value, WORD32_MAX).also { if (it == 0L) malformed() }
                    "key" -> base64(scalar(value), KEY_BYTES)
                    "nonce" -> base64(scalar(value), NONCE_BYTES)
                    "digest" -> base64(scalar(value), -1)
                    else -> malformed()
                }
            }
            if (seen.size != REQUIRED.size || !seen.containsAll(REQUIRED)) malformed()
            return doc
        }

        /** The sequence under `replicas:`: indentless at column 0 (what the encoder writes) or indented by 2. */
        private fun replicas() {
            if (i >= lines.size) malformed()
            val column = indentOf(lines[i])
            if ((column != 0 && column != 2) || !isItem(lines[i], column)) malformed()   // empty replicas
            while (i < lines.size && indentOf(lines[i]) == column && isItem(lines[i], column)) replica(column)
        }

        /** One `- chunks: ... server: ...` item. Its keys sit at [column] + 2, the first one on the `- ` line. */
        private fun replica(column: Int) {
            val keyColumn = column + 2
            var server: String? = null
            var chunks = false
            var first = true
            while (i < lines.size) {
                val line = lines[i]
                if (!first && (indentOf(line) != keyColumn || line[keyColumn] == '-')) break
                first = false
                val (key, value) = keyValue(line, keyColumn)
                i++
                when (key) {
                    "server" -> if (server == null && value != null) server = serverValue(value) else malformed()
                    "chunks" -> if (!chunks && value == null) { chunks = true; chunkItems(keyColumn) } else malformed()
                    else -> malformed()
                }
            }
            if (server == null || !chunks) malformed()
            doc.servers.add(server)
        }

        /** The sequence under `chunks:`: at the key's own column (what the encoder writes) or 2 deeper. */
        private fun chunkItems(keyColumn: Int) {
            if (i >= lines.size) malformed()
            val column = indentOf(lines[i])
            if ((column != keyColumn && column != keyColumn + 2) || !isItem(lines[i], column)) malformed()   // no chunks
            while (i < lines.size && indentOf(lines[i]) == column && isItem(lines[i], column)) {
                doc.chunks.add(chunkLine(lines[i].substring(column + 2)))
                i++
            }
        }
    }

    private fun indentOf(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == ' ') n++
        return n
    }

    /** `- x` at exactly [column]: a dash, one space, then content (never a second space). */
    private fun isItem(line: String, column: Int): Boolean =
        line.length > column + 2 && line[column] == '-' && line[column + 1] == ' ' && line[column + 2] != ' '

    /**
     * `key:` or `key: value` starting at [column]. The key is letters only (so no quotes, `?`, `<<` or `-`); the value
     * is one space after the colon and has no spaces of its own (so no comments, no trailing spaces, no `: ` inside).
     */
    private fun keyValue(line: String, column: Int): Pair<String, String?> {
        var end = column
        while (end < line.length && (line[end] in 'a'..'z' || line[end] in 'A'..'Z')) end++
        if (end == column || end >= line.length || line[end] != ':') malformed()
        val key = line.substring(column, end)
        if (end + 1 == line.length) return key to null
        if (line[end + 1] != ' ' || end + 2 >= line.length) malformed()
        val value = line.substring(end + 2)
        if (value.indexOf(' ') >= 0) malformed()
        return key to value
    }

    /**
     * A top-level value, plain or in one pair of single quotes. Base64url, sizes and `recipient` contain no quote, so
     * the quoted text is literal (no `''` escapes to interpret). A plain value YAML would not read as a string is
     * refused: the core's String fields would refuse it too.
     */
    private fun scalar(value: String?): String {
        if (value == null) malformed()
        if (value[0] == '\'') {
            if (value.length < 3 || value[value.length - 1] != '\'') malformed()
            val inner = value.substring(1, value.length - 1)
            if (inner.indexOf('\'') >= 0) malformed()
            return inner
        }
        if (value.indexOf('\'') >= 0 || value in YAML_SPECIAL || looksNumeric(value)) malformed()
        return value
    }

    /**
     * Data.Yaml reads a plain `123`, `-5`, `1e3`, `0x1f` or `0o17` as a number (yaml-0.11 textToScientific). Only these
     * forms fit the base64url and size alphabets (no `+` or `.`), so only these are checked.
     */
    private fun looksNumeric(s: String): Boolean {
        if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'o')) {
            val octal = s[1] == 'o'
            return (2 until s.length).all { j ->
                val c = s[j]
                if (octal) c in '0'..'7' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            }
        }
        var p = if (s.startsWith('-')) 1 else 0
        val digitsStart = p
        while (p < s.length && s[p] in '0'..'9') p++
        if (p == digitsStart) return false
        if (p == s.length) return true
        if (s[p] != 'e' && s[p] != 'E') return false
        p++
        if (p < s.length && s[p] == '-') p++
        val expStart = p
        while (p < s.length && s[p] in '0'..'9') p++
        return p > expStart && p == s.length
    }

    /**
     * `size` or `chunkSize`: a quoted digits-only size, or a size with a unit, plain or quoted. A plain digits-only
     * size is refused by [scalar]: YAML reads it as a number, and the core's String field refuses a number.
     */
    private fun topSize(value: String?, max: Long): Long = sizeOf(scalar(value), max)

    /** FileSize strP restricted to the canonical strEncode form (Description.hs:287-304). Bytes, at most [max]. */
    private fun sizeOf(token: String, max: Long): Long {
        val unit = when {
            token.endsWith("gb") -> 1024L * 1024 * 1024
            token.endsWith("mb") -> 1024L * 1024
            token.endsWith("kb") -> 1024L
            else -> 1L
        }
        val digits = if (unit == 1L) token else token.substring(0, token.length - 2)
        if (digits.isEmpty() || digits.length > 19 || !digits.all { it in '0'..'9' }) malformed()
        if (digits.length > 1 && digits[0] == '0') malformed()
        val n = digits.toLongOrNull() ?: malformed()
        if (n > max / unit) malformed()   // the core's A.decimal would wrap; we refuse
        val bytes = n * unit
        if (encodeSize(bytes) != token) malformed()
        return bytes
    }

    /** FileSize strEncode (Description.hs:288-296): the largest unit that divides exactly; 0 is `0gb`. */
    private fun encodeSize(bytes: Long): String {
        if (bytes % 1024 != 0L) return bytes.toString()
        val ks = bytes / 1024
        if (ks % 1024 != 0L) return "${ks}kb"
        val ms = ks / 1024
        if (ms % 1024 != 0L) return "${ms}mb"
        return "${ms / 1024}gb"
    }

    /**
     * Base64url with `=` padding as U.encode writes it: alphabet `A-Z a-z 0-9 - _`, length a multiple of 4, at most two
     * `=` at the end. With [bytes] >= 0 the decoded length must be exactly that. The value is only checked, never kept.
     */
    private fun base64(s: String, bytes: Int) {
        if (s.isEmpty() || s.length % 4 != 0) malformed()
        var body = 0
        while (body < s.length && isBase64Char(s[body])) body++
        val padding = s.length - body
        if (body == 0 || padding > 2) malformed()
        for (j in body until s.length) if (s[j] != '=') malformed()
        if (bytes >= 0 && body * 6 / 8 != bytes) malformed()
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /**
     * `N:replicaId:replicaKey[:digest[:size]]` (Description.hs:327-344). The id and key are checked and dropped. The
     * explicit size is canonical, fits a Word32 and is not 0. The chunk number has no leading zero and at most 9 digits.
     */
    private fun chunkLine(s: String): Chunk {
        val parts = s.split(':')
        if (parts.size < 3 || parts.size > 5) malformed()
        val numberText = parts[0]
        if (numberText.isEmpty() || numberText.length > MAX_CHUNK_NO_DIGITS || numberText[0] !in '1'..'9' ||
            !numberText.all { it in '0'..'9' }
        ) malformed()
        base64(parts[1], -1)
        base64(parts[2], -1)
        val digest = if (parts.size >= 4) parts[3].also { base64(it, -1) } else null
        val size = if (parts.size == 5) sizeOf(parts[4], WORD32_MAX).also { if (it == 0L) malformed() } else 0L
        return Chunk(numberText.toInt(), digest, size)
    }

    /**
     * `scheme://keyHash@host[,host...][:port]`, as strEncodeServer writes it (Messaging/Protocol.hs:1395-1399):
     * lower-case scheme and hosts, base64url key hash, no password, a port without leading zeros. Whether it is ours
     * is decided after the grammar; a well-formed `smp://` address there is FOREIGN_SERVER.
     */
    private fun serverValue(v: String): String {
        val sep = v.indexOf("://")
        if (sep <= 0 || !v.substring(0, sep).all { it in 'a'..'z' }) malformed()
        val at = v.indexOf('@')
        if (at < 0 || at != v.lastIndexOf('@')) malformed()
        val keyHash = v.substring(sep + 3, at)
        if (keyHash.isEmpty()) malformed()
        var body = 0
        while (body < keyHash.length && isBase64Char(keyHash[body])) body++
        if (body == 0 || (body until keyHash.length).any { keyHash[it] != '=' }) malformed()   // also: no `:password`
        val hostPart = v.substring(at + 1)
        val colon = hostPart.indexOf(':')
        val hosts = if (colon < 0) hostPart else hostPart.substring(0, colon)
        if (colon >= 0) {
            val port = hostPart.substring(colon + 1)
            if (port.isEmpty() || port.length > 5 || port[0] !in '1'..'9' || !port.all { it in '0'..'9' }) malformed()
        }
        for (host in hosts.split(',')) {
            if (host.isEmpty() || host.endsWith('.') || !host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) malformed()
        }
        return v
    }
}
