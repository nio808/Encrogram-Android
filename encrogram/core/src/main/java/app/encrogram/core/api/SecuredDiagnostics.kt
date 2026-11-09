package app.encrogram.core.api

import android.content.Context
import app.encrogram.core.internal.SecuredLog
import java.io.File

/**
 * Opt-in, on-phone diagnostics for beta testers. Off by default. While on, the engine's fixed log vocabulary (event
 * names, kinds, counts) and crash class names are kept as the last 300 lines in `noBackupFilesDir/encrogram-secured/diag`.
 * Nothing is ever sent anywhere; the tester copies the text out. Never holds messages, names, links, keys or file names.
 * The file's existence is the "on" switch, so it survives restarts without any other storage.
 */
object SecuredDiagnostics {

    private const val MAX_FRAMES = 8
    private const val PACKAGE = "app.encrogram."

    private fun file(context: Context) = File(context.noBackupFilesDir, "encrogram-secured/diag")

    fun isEnabled(context: Context): Boolean = SecuredLog.diagActive() || file(context).isFile

    fun setEnabled(context: Context, on: Boolean) {
        val f = file(context)
        if (on) {
            try {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile()
            } catch (_: Throwable) {
            }
            if (!SecuredLog.diagActive()) SecuredLog.diagStart(f)
        } else {
            SecuredLog.diagStop()
            try {
                f.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun lines(context: Context): List<String> {
        SecuredLog.diagLines()?.let { return it }
        return try {
            val f = file(context)
            if (f.isFile) f.readLines().takeLast(300) else emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        if (SecuredLog.diagActive()) {
            SecuredLog.diagClear()
        } else {
            try {
                val f = file(context)
                if (f.isFile) f.writeText("")
            } catch (_: Throwable) {
            }
        }
    }

    /** Call once at startup: resumes the buffer when diagnostics was left on. */
    fun install(context: Context) {
        try {
            if (file(context).isFile && !SecuredLog.diagActive()) SecuredLog.diagStart(file(context))
        } catch (_: Throwable) {
        }
    }

    /** Records a crash while diagnostics is on: the exception class and `app.encrogram` frames only, never a message. */
    fun recordCrash(context: Context, throwable: Throwable) {
        if (!isEnabled(context)) return
        install(context)
        for (line in crashLines(throwable)) SecuredLog.diagRaw(line)
    }

    /** Pure and testable: "CRASH <class>", then up to 8 `Class.method:line` frames from our packages. */
    internal fun crashLines(t: Throwable): List<String> {
        val out = ArrayList<String>()
        out.add("CRASH " + t.javaClass.name)
        var frames = 0
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4 && frames < MAX_FRAMES) {
            if (depth > 0) out.add("CAUSE " + cur.javaClass.name)
            for (e in cur.stackTrace) {
                if (frames >= MAX_FRAMES) break
                if (!e.className.startsWith(PACKAGE)) continue
                out.add("  " + e.className + "." + e.methodName + ":" + e.lineNumber)
                frames++
            }
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        return out
    }
}
