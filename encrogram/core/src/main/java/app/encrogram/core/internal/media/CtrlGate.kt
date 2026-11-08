package app.encrogram.core.internal.media

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Keeps the core's file functions and the store's close apart (docs/media-plan.md §4.2, §4.7).
 *
 * - Every native file call runs inside [use] (read lock), so several can run at once.
 * - The engine wraps `chatCloseStore` in [close] (write lock): it waits for the calls in flight, and from then on
 *   [use] throws `NativeFileException("no_ctrl")` until the engine calls [reopen] after the next successful open.
 *   So when the engine wipes `view/` after a close, no decrypt or seal can still be writing there.
 *
 * Threads: [use] on the prep and read threads (never main, never the engine thread while it might [close]: a read
 * lock can't be upgraded). [close] and [reopen] on the engine thread. A stop waits for at most the calls in flight
 * (a few seconds for a 100 MB seal or decrypt).
 */
internal class CtrlGate {

    private val lock = ReentrantReadWriteLock()

    /** Written only under the write lock, read under the read lock. */
    private var closed = false

    /** Runs [block] while the store can't be closed. Throws `NativeFileException("no_ctrl")` after [close]. */
    fun <T> use(block: () -> T): T {
        val read = lock.readLock()
        read.lock()
        try {
            if (closed) throw NativeFileException(NO_CTRL)
            return block()
        } finally {
            read.unlock()
        }
    }

    /** Waits for every [use] in flight, marks the gate closed, then runs [block] (the store's close) exclusively. */
    fun close(block: () -> Unit) {
        val write = lock.writeLock()
        write.lock()
        try {
            closed = true
            block()
        } finally {
            write.unlock()
        }
    }

    /** Call after every successful store open (the new controller is set). */
    fun reopen() {
        val write = lock.writeLock()
        write.lock()
        try {
            closed = false
        } finally {
            write.unlock()
        }
    }

    /** For checks and tests only; the answer can change right after. */
    val isClosed: Boolean
        get() {
            val read = lock.readLock()
            read.lock()
            try {
                return closed
            } finally {
                read.unlock()
            }
        }

    override fun toString() = "CtrlGate(closed=$isClosed)"

    companion object {
        const val NO_CTRL = "no_ctrl"
    }
}
