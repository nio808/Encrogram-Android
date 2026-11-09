package app.encrogram.core.internal.media

import app.encrogram.core.internal.SecuredLog
import java.io.File

/**
 * The "now" a retention pass measures file ages against (review of 8c89dfd6, finding 2). The wall clock alone can't be
 * trusted with that: set ahead by hand it would age every file at once, and retention would empty them for good.
 *
 * So a stored trusted time moves forward only with time that really passed:
 * - within one boot, by exactly SystemClock.elapsedRealtime since the last pass (it counts deep sleep, and nobody can
 *   set it);
 * - across a restart of the phone (elapsedRealtime started again below the stored value), by the wall clock's step but
 *   at most [REBOOT_STEP_MS], and never less than the time since boot.
 * It never moves back. A pass measures against the earlier of it and the wall clock: a wall clock set ahead can't make
 * any file older than the time that really passed (the pass then counts from the trusted time instead of the wall
 * clock), and one set back removes less, never more. Files written while the clock was set ahead are dated in its
 * future, so they simply stay longer.
 *
 * A wall clock before [FLOOR_MS] (a phone that booted with its clock unset, 1970) isn't used at all: that pass removes
 * nothing and the stored time doesn't move. Files that such a clock dated decades back are dated afresh when a pass
 * first sees them ([MediaCoordinator.applyRetention]).
 *
 * The very first pass on a phone has nothing to compare with and takes the wall clock as it is.
 *
 * Engine thread. The state is one small file next to the ledger, "<trustedMs> <elapsedMs>", written to a temp file
 * then renamed; one that can't be read starts again from the wall clock. A failed write keeps the state for this run.
 * It is about the phone's time, not the store: a restore or reset keeps it (a reset's deleted file is written again).
 */
internal class RetentionClock(
    private val file: File,
    private val wallMs: () -> Long,
    /** SystemClock.elapsedRealtime in production. */
    private val elapsedMs: () -> Long,
) {
    internal data class State(val trustedMs: Long, val elapsedMs: Long)

    /** [state] to keep (null: none yet); [nowMs] what this pass measures against, null when it removes nothing. */
    internal class Step(val state: State?, val nowMs: Long?)

    private var loaded = false
    private var state: State? = null

    /** Steps the trusted time and returns what this pass measures file ages against, or null: remove nothing now. */
    fun passNow(): Long? {
        val prev = load()
        val s = step(prev, wallMs(), elapsedMs())
        if (s.state != null && s.state != prev) {
            state = s.state
            save(s.state)
        }
        return s.nowMs
    }

    private fun load(): State? {
        if (loaded) return state
        state = read()
        loaded = true
        return state
    }

    private fun read(): State? = try {
        if (!file.isFile) null else parse(file.readText())
    } catch (e: Exception) {
        SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
        null
    }

    private fun save(s: State) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            tmp.writeText("${s.trustedMs} ${s.elapsedMs}")
            if (!tmp.renameTo(file)) {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.MEDIA_IO_FAILED, "clock_rename")
            }
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
        }
    }

    override fun toString() = "RetentionClock"

    companion object {
        /** 2024-01-01T00:00:00Z: no file of ours can be older. A wall clock before it is unset, not real. */
        const val FLOOR_MS = 1_704_067_200_000L

        /** How far the trusted time may follow the wall clock across a restart of the phone, per pass. */
        const val REBOOT_STEP_MS = 2 * MediaRetention.DAY_MS

        /** Pure: the next state and this pass's "now" from the stored [prev], the wall clock and elapsedRealtime. */
        fun step(prev: State?, wallMs: Long, elapsedMs: Long): Step {
            if (wallMs < FLOOR_MS) return Step(prev, null)
            if (prev == null) return Step(State(wallMs, elapsedMs), wallMs)
            val passed = if (elapsedMs >= prev.elapsedMs) {
                // Same boot (or a later one that has been up longer: then this counts less than really passed).
                elapsedMs - prev.elapsedMs
            } else {
                // The phone restarted: at least the time since boot passed; the wall clock may vouch for more, capped.
                maxOf(elapsedMs, minOf(wallMs - prev.trustedMs, REBOOT_STEP_MS))
            }
            val trusted = prev.trustedMs + maxOf(0L, passed)
            return Step(State(trusted, elapsedMs), minOf(wallMs, trusted))
        }

        internal fun parse(text: String): State? {
            val p = text.trim().split(' ')
            if (p.size != 2) return null
            val trusted = p[0].toLongOrNull()?.takeIf { it >= FLOOR_MS } ?: return null
            val elapsed = p[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return State(trusted, elapsed)
        }
    }
}
