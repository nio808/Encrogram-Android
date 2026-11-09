package app.encrogram.core.internal

import android.util.Log
import app.encrogram.core.BuildConfig
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The only logger in :encrogram-core.
 *
 * It can only print a fixed [Ev] name, an optional type tag and an optional count. Type tags are the core's own
 * vocabulary (response, event and error type names such as "newChatItems" or "errorAgent"), never values.
 * Never pass message text, display names, links, server addresses, keys or file names here: there is no
 * free-text parameter on purpose. Exceptions are reduced to their class name.
 */
internal object SecuredLog {

    private const val TAG = "EncroSecured"

    /** On in debug builds only (event names and counts, never content). Tests turn it off. */
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    enum class Ev {
        NATIVE_LOADED, NATIVE_LOAD_FAILED,
        DB_OPEN, DB_OPEN_FAILED, DB_RETRY_AFTER_PARTIAL, DB_KEY_CREATED, DB_KEY_FAILED,
        STATE, START_FAILED, CONFIG_STEP_FAILED, CONFIG_OK,
        CMD_ERROR, CMD_INVALID_REPLY, CMD_UNEXPECTED_REPLY,
        // A network command tried again with a longer core timeout: the error tag and the retry number.
        CMD_RETRY,
        // A screen's listener or callback threw (release builds only; the exception's class name).
        LISTENER_FAILED,
        // Milliseconds since STARTING: tag "chats_loaded" or "running".
        START_MS,
        EVENT, EVENT_ERROR, EVENT_PARSE_FAILED,
        RECEIVER_STARTED, RECEIVER_STOPPED, RECEIVER_ERROR,
        CHATS_LOADED, INVITE_REFUSED, CONNECT_REFUSED, PRESET_CARD_REMOVED,
        ITEM_ACTION, CHAT_ACTION, GROUP_ACTION, ACTION_REFUSED, ARCHIVE_IO_FAILED,
        PROFILE_UPDATED, PROFILE_PREFS_MERGED, PROFILE_PREFS_FAILED,
        TASK_CRASHED, STOPPED,
        // Phone network changes: the tag is the network type (wifi, cellular, ...) or "offline", never an address.
        NETWORK,
        BG_START_REFUSED, BG_SERVICE_STARTED, BG_NOTIFY_FAILED,
        // 12-word backup. Tags are fixed words; the count is at most an HTTP status or a number of items.
        // Never the words, the backup id or key, the URL, the database key or the archive.
        BACKUP_ENABLED, BACKUP_DISABLED, BACKUP_STARTED, BACKUP_EXPORTED, BACKUP_UPLOADED, BACKUP_FAILED,
        BACKUP_LOOKUP, BACKUP_LOOKUP_FAILED, BACKUP_IO_FAILED, VAULT_FAILED, PHRASE_SHOWN, PHRASE_REFUSED,
        RESTORE_STARTED, RESTORE_DONE, RESTORE_FAILED, RESTORE_WIPED, RATCHET_SYNC, QUEUE_SWITCH, RESTORE_REPAIR,
        // Media (docs/media-plan.md). Tags are fixed words (a kind, a verdict, a state); never a name, path, key,
        // server, description or preview.
        MEDIA_PREPARED, MEDIA_PREPARE_FAILED, MEDIA_SENT, MEDIA_DESCR, MEDIA_ACCEPTED, MEDIA_CANCELLED, MEDIA_EVENT,
        MEDIA_DONE, MEDIA_FAILED, MEDIA_READ_FAILED, MEDIA_WIPED, MEDIA_IO_FAILED, MEDIA_CATCHUP,
        // Relays we don't run (b1): "marked", "cleared", "check"; never a server, a name or an id.
        FOREIGN_RELAY,
        // "Reset Secured" (b3): step tags only.
        RESET,
    }

    /** Tests only: receives every line that would be written, even while [enabled] is off (the canary test reads it). */
    @Volatile
    internal var testSink: ((String) -> Unit)? = null

    fun i(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.INFO, ev, tag, count)

    fun w(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.WARN, ev, tag, count)

    fun e(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.ERROR, ev, tag, count)

    /** Logs only the class name of [t], never its message (which may embed engine JSON). */
    fun crash(ev: Ev, t: Throwable) = write(Log.ERROR, ev, t.javaClass.simpleName, null)

    private fun write(priority: Int, ev: Ev, tag: String?, count: Int?) {
        val sink = testSink
        val diag = diag
        if (!enabled && sink == null && diag == null) return
        val sb = StringBuilder(ev.name)
        sanitizeTag(tag)?.let { sb.append(" kind=").append(it) }
        if (count != null) sb.append(" n=").append(count)
        sink?.invoke(sb.toString())
        diag?.add(sb.toString())
        if (!enabled) return
        try {
            Log.println(priority, TAG, sb.toString())
        } catch (_: Throwable) {
            // android.util.Log is a stub in JVM unit tests.
        }
    }

    /** Keeps only short identifier-like tags; anything else is dropped rather than printed. */
    internal fun sanitizeTag(tag: String?): String? {
        if (tag.isNullOrEmpty()) return null
        if (tag.length > 48) return null
        for (c in tag) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'
            if (!ok) return null
        }
        return tag
    }

    // ---- opt-in diagnostics (A5) -------------------------------------------------------------------------------

    /** The on-phone ring buffer; null while diagnostics is off (then nothing is kept or written). */
    @Volatile
    private var diag: DiagBuffer? = null

    /** Starts keeping the last lines in memory and in [file] (call only when the tester turned diagnostics on). */
    internal fun diagStart(file: File) {
        diag = DiagBuffer(file)
    }

    /**
     * Stops and deletes the file. A log call that took the buffer just before this can still reach [DiagBuffer.add]
     * afterwards: the buffer is closed first, so that call appends nothing and can't recreate the file (which would
     * turn diagnostics back on at the next launch, SecuredDiagnostics.install).
     */
    internal fun diagStop() {
        val d = diag
        diag = null
        d?.clear()
    }

    internal fun diagActive(): Boolean = diag != null

    internal fun diagLines(): List<String>? = diag?.lines()

    internal fun diagClear() {
        diag?.clear(keepFile = true)
    }

    /** A line built by the diagnostics API itself from class names only. No-op while diagnostics is off. */
    internal fun diagRaw(line: String) {
        diag?.add(line)
    }

    /**
     * The last [max] lines, each stamped with the time of day. In memory and mirrored to [file] so a crash leaves them
     * on disk. Appends are cheap; the file is rewritten from memory every [max] appends so it never grows past ~2x.
     */
    internal class DiagBuffer(private val file: File?, private val max: Int = 300) {
        private val buf = ArrayDeque<String>()
        private var appended = 0
        /** Set by [clear] without keepFile (diagnostics turned off), before the delete: [add] then does nothing, for good. */
        private var closed = false

        init {
            // Keep what an earlier run left (the crash lines), so one file tells the whole story.
            try {
                if (file != null && file.isFile) file.readLines().takeLast(max).forEach { buf.addLast(it) }
            } catch (_: Throwable) {
            }
        }

        @Synchronized
        fun add(line: String) {
            if (closed) return
            val stamped = STAMP.format(LocalTime.now()) + " " + line
            buf.addLast(stamped)
            while (buf.size > max) buf.removeFirst()
            if (file == null) return
            try {
                file.parentFile?.mkdirs()
                if (++appended >= max) {
                    appended = 0
                    file.writeText(buf.joinToString("\n", postfix = "\n"))
                } else {
                    file.appendText(stamped + "\n")
                }
            } catch (_: Throwable) {
                // Diagnostics must never break the engine.
            }
        }

        @Synchronized
        fun lines(): List<String> = buf.toList()

        /** [keepFile] false: diagnostics is off; the buffer is closed before the file goes, so no late line brings it back. */
        @Synchronized
        fun clear(keepFile: Boolean = false) {
            if (!keepFile) closed = true
            buf.clear()
            appended = 0
            try {
                if (keepFile) file?.writeText("") else file?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
}
