package app.encrogram.core.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Stores and fetches backup envelopes on our own web host: `PUT | GET | HEAD | DELETE <base><backupId>`
 * (nginx WebDAV location, docs/recovery-phrase-plan.md "Server").
 *
 * nginx answers GET and HEAD with an `ETag` that changes on every PUT (PUT replies carry none). Its WebDAV PUT takes
 * the file's modification time from the request's `Date` header, and the ETag is `"%x-%x"` of (modification time in
 * epoch seconds, length) (R6.2, checked on our server 8 Nov 2026: `Date: Sun, 01 Nov 2026 10:00:00 GMT` with 1000
 * bytes gave `"6ae70da0-3e8"`). So [put] sends a `Date` and returns the tag of the copy it wrote ([etagFor]) without
 * a HEAD that could fail. The engine keeps that tag and HEADs before every automatic upload ([tagDecision]): the same
 * tag means the copy is ours; any doubt (another tag, none stored, none sent) is settled by reading the copy's counter
 * ([counterDecision]), so a copy another phone uploaded with the same words is never overwritten (review finding 4)
 * and a lost tag never pauses uploads for good. The HEAD is also the connectivity probe that runs before the chat is
 * paused (finding 5), and the check before every DELETE ([deleteDecision], R6.3). Tags are never logged.
 *
 * Only the platform's [HttpsURLConnection], never Telegram's network code. HTTPS only, no redirects, no caches,
 * bodies capped at [Envelope.MAX_ENVELOPE_BYTES]. The URL contains the backup id, which is a secret: it is never
 * logged, put in an exception message or shown. Blocking: call from the backup thread only.
 */
internal class BackupClient private constructor(private val base: String) {

    enum class Failure { NETWORK, NOT_FOUND, TOO_LARGE, BUSY, SERVER }

    /** [code] is the HTTP status when there was one (safe to log and show), else 0. */
    class HttpFailure(val failure: Failure, val code: Int) : Exception("backup http ${failure.name} $code")

    /** A downloaded envelope and the server's tag for it (null when the reply had none). */
    class Download(val body: ByteArray, val etag: String?)

    /** What a HEAD found. Never carries the id or URL. */
    sealed class Probe {
        /** A copy is stored; [etag] is its tag, or null when the server sent none. */
        data class Found(val etag: String?) : Probe()
        object Missing : Probe()
        /** No answer, or an answer that says nothing about the copy ([code]: HTTP status or 0). */
        data class Failed(val failure: Failure, val code: Int) : Probe()
    }

    /** What to do before an upload ([tagDecision], then [counterDecision] for [VERIFY]). */
    enum class Check {
        UPLOAD,
        /** Another phone uploaded after this phone's last upload: don't overwrite it. */
        OTHER_PHONE,
        /** A copy exists and its tag doesn't prove it is ours: download it and compare its counter ([counterDecision]). */
        VERIFY,
        /** The server can't be reached (or is busy): don't pause the chat, try again later. */
        UNREACHABLE,
    }

    /** What to do with a server copy before deleting it ([deleteDecision], R6.3). */
    enum class Del {
        /** The copy is the one this phone uploaded last: delete it. */
        DELETE,
        /** Nothing stored: nothing to delete. */
        GONE,
        /** Another phone's copy, or one this phone can't prove is its own: keep it. */
        KEEP,
        /** No answer: try again later. */
        RETRY,
    }

    /**
     * Uploads [body] with `Date` = [dateSeconds] (whole seconds, RFC 1123 GMT) and returns the copy's tag: the reply's
     * ETag when it sends one, else [etagFor] (nginx WebDAV, see the class comment).
     */
    @Throws(HttpFailure::class)
    fun put(id: String, body: ByteArray, dateSeconds: Long): String {
        if (body.size > Envelope.MAX_ENVELOPE_BYTES) throw HttpFailure(Failure.TOO_LARGE, 0)
        val conn = open(id, "PUT")
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Date", httpDate(dateSeconds))
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpFailure(failureFor(code), code)
            val replyTag = cleanTag(conn.getHeaderField("ETag"))
            drain(conn)
            return replyTag ?: etagFor(dateSeconds, body.size.toLong())
        } catch (e: IOException) {
            throw HttpFailure(Failure.NETWORK, 0)
        } finally {
            conn.disconnect()
        }
    }

    /** The stored envelope and its tag; [Failure.NOT_FOUND] when there is none. */
    @Throws(HttpFailure::class)
    fun get(id: String): Download {
        val conn = open(id, "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpFailure(failureFor(code), code)
            val declared = conn.contentLengthLong
            if (declared > Envelope.MAX_ENVELOPE_BYTES) throw HttpFailure(Failure.TOO_LARGE, code)
            val etag = cleanTag(conn.getHeaderField("ETag"))
            val body = conn.inputStream.use { readCapped(it, Envelope.MAX_ENVELOPE_BYTES) }
                ?: throw HttpFailure(Failure.TOO_LARGE, code)
            return Download(body, etag)
        } catch (e: IOException) {
            throw HttpFailure(Failure.NETWORK, 0)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HEAD: is a copy stored, and with which tag? Never throws: no answer is [Probe.Failed]. Short read timeout,
     * because it runs before every upload while the chat is still receiving.
     */
    fun head(id: String): Probe {
        val conn = try {
            open(id, "HEAD")
        } catch (e: HttpFailure) {
            return Probe.Failed(e.failure, e.code)
        }
        return try {
            conn.readTimeout = HEAD_READ_TIMEOUT_MS
            val code = conn.responseCode
            when {
                code in 200..299 -> Probe.Found(cleanTag(conn.getHeaderField("ETag")))
                failureFor(code) == Failure.NOT_FOUND -> Probe.Missing
                else -> Probe.Failed(failureFor(code), code)
            }
        } catch (e: Exception) {
            Probe.Failed(Failure.NETWORK, 0)
        } finally {
            conn.disconnect()
        }
    }

    /** Deletes the stored envelope. Already gone (404) counts as success. */
    @Throws(HttpFailure::class)
    fun delete(id: String) {
        val conn = open(id, "DELETE")
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) throw HttpFailure(failureFor(code), code)
            drain(conn)
        } catch (e: IOException) {
            throw HttpFailure(Failure.NETWORK, 0)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(id: String, method: String): HttpsURLConnection {
        val url = urlFor(base, id) ?: throw HttpFailure(Failure.SERVER, 0)
        val conn = try {
            URL(url).openConnection() as? HttpsURLConnection
        } catch (e: IOException) {
            null
        } ?: throw HttpFailure(Failure.NETWORK, 0)
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-store")
        conn.setRequestProperty("Accept-Encoding", "identity")
        return conn
    }

    private fun drain(conn: HttpsURLConnection) {
        try {
            conn.inputStream.use { readCapped(it, 64 * 1024) }
        } catch (_: IOException) {
            // The status already said everything.
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val HEAD_READ_TIMEOUT_MS = 15_000
        private const val MAX_TAG_CHARS = 200

        /**
         * Before an upload (review findings 4 and 5). [lastTag] is the tag this phone stored after its own last
         * upload (or from the GET of a restore); [skipCheck] is set for uploads the user asked for ("Back up now",
         * turning backup on) and the first upload after a restore, which make this phone the backup phone.
         *
         * - No answer: [Check.UNREACHABLE], for every upload, so the chat is never paused while offline.
         * - Nothing stored (404): upload.
         * - Same tag: upload.
         * - A different tag, no stored tag (a dev install, a lost HEAD in an older build, a server move) or no tag
         *   from the server: [Check.VERIFY], never "no tag means upload" (R6.2 part B): that is exactly when an old
         *   phone would overwrite the restored phone's copy.
         */
        fun tagDecision(probe: Probe, lastTag: String?, skipCheck: Boolean): Check = when (probe) {
            is Probe.Failed -> Check.UNREACHABLE
            Probe.Missing -> Check.UPLOAD
            is Probe.Found -> when {
                skipCheck -> Check.UPLOAD
                probe.etag != null && lastTag != null && sameTag(probe.etag, lastTag) -> Check.UPLOAD
                else -> Check.VERIFY
            }
        }

        /**
         * [Check.VERIFY] settled by the stored copy's counter (R6.2 part B): every upload's counter is one more than
         * the highest this phone knew ([myCounter] = the counter of this phone's last upload, or of the restored copy).
         * Not higher: the copy is ours (or older), upload. Higher: another phone uploaded after us, keep it.
         */
        fun counterDecision(serverCounter: Long, myCounter: Long): Check =
            if (serverCounter <= myCounter) Check.UPLOAD else Check.OTHER_PHONE

        /**
         * Before a DELETE (R6.3): only the copy this phone uploaded last ([storedTag], kept next to the queued id) may
         * be deleted; another phone's copy made with the same words stays.
         */
        fun deleteDecision(probe: Probe, storedTag: String?): Del = when (probe) {
            is Probe.Failed -> Del.RETRY
            Probe.Missing -> Del.GONE
            is Probe.Found -> if (probe.etag != null && storedTag != null && sameTag(probe.etag, storedTag)) Del.DELETE else Del.KEEP
        }

        /** nginx's ETag of a static file: `"<mtime hex>-<length hex>"` (ngx_http_set_etag, `"%xT-%xO"`). */
        fun etagFor(epochSeconds: Long, length: Long): String =
            "\"" + java.lang.Long.toHexString(epochSeconds) + "-" + java.lang.Long.toHexString(length) + "\""

        private val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

        /** RFC 1123 date with a two-digit day (nginx's ngx_parse_http_time needs it): `Sun, 01 Nov 2026 10:00:00 GMT`. */
        fun httpDate(epochSeconds: Long): String = HTTP_DATE.format(Instant.ofEpochSecond(epochSeconds))

        /** An ETag header as we keep it: trimmed printable ASCII, at most [MAX_TAG_CHARS]; null otherwise. */
        fun cleanTag(raw: String?): String? {
            val t = raw?.trim() ?: return null
            if (t.isEmpty() || t.length > MAX_TAG_CHARS) return null
            if (t.any { it.code < 0x20 || it.code > 0x7E }) return null
            return t
        }

        /** Tags compare as strings; a weak marker (`W/`) is ignored, since we only ask "same copy or not". */
        fun sameTag(a: String, b: String): Boolean = a.removePrefix("W/") == b.removePrefix("W/")

        /** A client for [baseUrl], or null when it isn't a usable `https://host/.../` base. */
        fun create(baseUrl: String?): BackupClient? = baseUrl?.trim()?.takeIf { validBase(it) }?.let { BackupClient(it) }

        /** `https://<host>[:port]/<path>/`: HTTPS, a host, no credentials, query or fragment, ends with '/'. */
        fun validBase(base: String): Boolean {
            if (!base.startsWith("https://") || !base.endsWith("/")) return false
            if (base.any { it.code <= 0x20 || it.code >= 0x7F }) return false
            val uri = try {
                URI(base)
            } catch (e: Exception) {
                return false
            }
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath ?: "").startsWith("/")
        }

        /** `<base><id>`, or null when [base] isn't valid or [id] isn't 64 lower-case hex characters. */
        fun urlFor(base: String, id: String): String? {
            if (!validBase(base)) return null
            if (id.length != 64 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return base + id
        }

        /** HTTP status -> failure kind. 404: no backup; 413: too large; 429/503: try later; anything else: server. */
        fun failureFor(code: Int): Failure = when (code) {
            404, 410 -> Failure.NOT_FOUND
            413 -> Failure.TOO_LARGE
            429, 503 -> Failure.BUSY
            else -> Failure.SERVER
        }

        /** Reads at most [cap] bytes; null when the stream has more. */
        fun readCapped(input: InputStream, cap: Int): ByteArray? {
            val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
