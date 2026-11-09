package app.encrogram.core.internal

import android.util.Log
import app.encrogram.core.BuildConfig

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
        if (!enabled && sink == null) return
        val sb = StringBuilder(ev.name)
        sanitizeTag(tag)?.let { sb.append(" kind=").append(it) }
        if (count != null) sb.append(" n=").append(count)
        sink?.invoke(sb.toString())
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
}
