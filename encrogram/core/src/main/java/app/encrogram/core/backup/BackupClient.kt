package app.encrogram.core.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Stores and fetches backup envelopes on our own web host: `PUT | GET | HEAD | DELETE <base><backupId>`
 * (nginx WebDAV location, docs/recovery-phrase-plan.md "Server").
 *
 * nginx answers GET and HEAD with an `ETag` that changes on every PUT (PUT replies may carry none). The engine keeps
 * the tag of its own last upload and HEADs before every automatic upload ([tagDecision]): a different tag means
 * another phone uploaded with the same words, so this phone must not overwrite that copy (review finding 4). The
 * HEAD is also the connectivity probe that runs before the chat is paused (finding 5). Tags are never logged.
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

    /** What to do before an upload ([tagDecision]). */
    enum class Check {
        UPLOAD,
        /** Another phone uploaded after this phone's last upload: don't overwrite it. */
        OTHER_PHONE,
        /** A copy exists but the server sent no tag to compare: try again later. */
        CANT_CHECK,
        /** The server can't be reached (or is busy): don't pause the chat, try again later. */
        UNREACHABLE,
    }

    @Throws(HttpFailure::class)
    fun put(id: String, body: ByteArray) {
        if (body.size > Envelope.MAX_ENVELOPE_BYTES) throw HttpFailure(Failure.TOO_LARGE, 0)
        val conn = open(id, "PUT")
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpFailure(failureFor(code), code)
            drain(conn)
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
         * - Same tag: upload. A different tag, or no stored tag next to an existing copy: another phone may have
         *   uploaded, so don't overwrite it ([Check.OTHER_PHONE]).
         * - A copy without a tag can't be compared: [Check.CANT_CHECK] (a server problem, retried later).
         */
        fun tagDecision(probe: Probe, lastTag: String?, skipCheck: Boolean): Check = when (probe) {
            is Probe.Failed -> Check.UNREACHABLE
            Probe.Missing -> Check.UPLOAD
            is Probe.Found -> when {
                skipCheck -> Check.UPLOAD
                probe.etag == null -> Check.CANT_CHECK
                lastTag != null && sameTag(probe.etag, lastTag) -> Check.UPLOAD
                else -> Check.OTHER_PHONE
            }
        }

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
