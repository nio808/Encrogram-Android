package app.encrogram.core.background

/**
 * Coalesces a burst of incoming messages into one notification post (AR4). The first message of a burst asks the
 * caller to schedule one flush [WINDOW_MS] later; the rest only add to the counts. The flush then posts once, with the
 * alert on, so a backlog after being offline buzzes once. Pure (the caller owns the timer), so a JVM test pins it down.
 */
internal class NotifyBurst {
    private var pending = false

    /** A message arrived. True when this one starts a burst, so the caller must schedule a flush after [WINDOW_MS]. */
    @Synchronized fun onMessage(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** The timer fired. True when a post is due (false if the burst was cancelled meanwhile). */
    @Synchronized fun flush(): Boolean {
        val due = pending
        pending = false
        return due
    }

    /** The user saw the chats, or the notification is gone: drop the burst. */
    @Synchronized fun cancel() { pending = false }

    @Synchronized fun isPending(): Boolean = pending

    companion object {
        const val WINDOW_MS = 700L
    }
}
