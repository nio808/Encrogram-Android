package app.encrogram.core.internal

import android.util.Log

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

    /** Turned off by tests and, if wanted, by the app for release builds. */
    @Volatile
    var enabled: Boolean = true

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
    }

    fun i(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.INFO, ev, tag, count)

    fun w(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.WARN, ev, tag, count)

    fun e(ev: Ev, tag: String? = null, count: Int? = null) = write(Log.ERROR, ev, tag, count)

    /** Logs only the class name of [t], never its message (which may embed engine JSON). */
    fun crash(ev: Ev, t: Throwable) = write(Log.ERROR, ev, t.javaClass.simpleName, null)

    private fun write(priority: Int, ev: Ev, tag: String?, count: Int?) {
        if (!enabled) return
        val sb = StringBuilder(ev.name)
        sanitizeTag(tag)?.let { sb.append(" kind=").append(it) }
        if (count != null) sb.append(" n=").append(count)
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
