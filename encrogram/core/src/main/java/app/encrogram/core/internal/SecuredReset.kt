package app.encrogram.core.internal

import app.encrogram.core.api.SecuredError
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The order of "Reset Secured" (b3, [app.encrogram.core.api.SecuredEngine.resetSecured]). Each step runs on the thread
 * that owns what it deletes, queued behind whatever that thread is already doing:
 *
 * 1. engine thread: [Steps.begin], then [Steps.stopEngine] (receiver and chat stopped, media generation bumped,
 *    CtrlGate closed around the store's close, so no native file call is still writing);
 * 2. phrase thread: [Steps.wipePhrase], after any "show / store 12 words" in flight;
 * 3. backup thread: [Steps.wipeBackup], after any upload or restore fetch in flight;
 * 4. engine thread again: [Steps.wipeFiles], then [Steps.reopen] (a fresh, empty store: NEEDS_PROFILE), then [Steps.end].
 *
 * A step that throws is logged; the chain still reaches [Steps.end], with an error when the stop, the file wipe or
 * the reopen failed. Pure: the engine supplies the hops and the steps, tests supply their own.
 */
internal class SecuredReset(
    private val onEngine: (() -> Unit) -> Unit,
    private val onPhrase: (() -> Unit) -> Unit,
    private val onWorker: (() -> Unit) -> Unit,
    private val steps: Steps,
) {
    interface Steps {
        /** Engine thread. False when a reset is already under way: nothing else runs. */
        fun begin(): Boolean
        fun stopEngine()
        fun wipePhrase()
        fun wipeBackup()
        /** False when something couldn't be deleted. */
        fun wipeFiles(): Boolean
        /** Null when the new store is open and the engine waits for a profile; else why not. */
        fun reopen(): SecuredError?
        /** Engine thread, exactly once per [begin] that returned true. Null: the reset is done. */
        fun end(error: SecuredError?)
    }

    fun run() = onEngine {
        if (!steps.begin()) return@onEngine
        if (!attempt("stop") { steps.stopEngine() }) return@onEngine steps.end(ErrorMapper.engine("reset_stop"))
        onPhrase {
            attempt("phrase") { steps.wipePhrase() }
            onWorker {
                attempt("backup") { steps.wipeBackup() }
                onEngine { finish() }
            }
        }
    }

    private fun finish() {
        var wiped = false
        attempt("files") { wiped = steps.wipeFiles() }
        if (!wiped) return steps.end(SecuredError(SecuredError.Kind.STORAGE, ErrorMapper.RESET_INCOMPLETE))
        var error: SecuredError? = ErrorMapper.engine("reset_reopen")
        attempt("reopen") { error = steps.reopen() }
        steps.end(error)
    }

    private inline fun attempt(tag: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (t: Throwable) {
        SecuredLog.e(SecuredLog.Ev.RESET, tag)
        SecuredLog.crash(SecuredLog.Ev.RESET, t)
        false
    }

    companion object {
        /**
         * Deletes [dir] and everything in it without following links: a link is deleted itself, never what it points
         * to, so nothing outside our folder (Telegram's files included) can be reached through one. False when
         * something stayed. A copy of MediaVault's private deleteTree (that file belongs to the media work).
         */
        fun deleteTree(dir: File): Boolean = deleteTree(dir.toPath()) && !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)

        private fun deleteTree(p: Path): Boolean {
            var ok = true
            try {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(p).use { entries -> for (e in entries) if (!deleteTree(e)) ok = false }
                }
                Files.deleteIfExists(p)
            } catch (e: IOException) {
                ok = false
            } catch (e: SecurityException) {
                ok = false
            }
            return ok
        }
    }
}
