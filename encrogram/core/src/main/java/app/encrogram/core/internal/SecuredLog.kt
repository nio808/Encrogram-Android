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
        TASK_CRASHED, STOPPED,
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
