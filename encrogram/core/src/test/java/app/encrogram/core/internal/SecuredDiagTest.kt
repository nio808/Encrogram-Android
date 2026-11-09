package app.encrogram.core.internal

import app.encrogram.core.api.SecuredDiagnostics
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SecuredDiagTest {

    private fun tmp(): File = File.createTempFile("diag", "").also { it.delete() }

    @Test
    fun keepsOnlyLast300AndMirrorsToDisk() {
        val f = tmp()
        val b = SecuredLog.DiagBuffer(f)
        for (i in 1..650) b.add("L$i")
        val lines = b.lines()
        assertEquals(300, lines.size)
        assertTrue(lines.last().endsWith("L650"))
        assertTrue(lines.first().endsWith("L351"))
        val disk = f.readLines()
        assertTrue(disk.size in 300..600)
        assertTrue(disk.last().endsWith("L650"))
        b.clear()
        assertFalse(f.exists())
        f.delete()
    }

    /** Review of 8c89dfd6, finding 4: a log call that took the buffer before diagStop() can't recreate the file. */
    @Test
    fun aLineFromBeforeTheStopCantTurnDiagnosticsBackOn() {
        val f = tmp()
        val b = SecuredLog.DiagBuffer(f)
        b.add("on")
        assertTrue(f.isFile)
        // Diagnostics turned off between a log call taking the buffer and its append.
        b.clear()
        b.add("late")
        assertFalse(f.exists())
        assertTrue(b.lines().isEmpty())

        // Through the logger: started, stopped, then a line.
        SecuredLog.diagStart(f)
        SecuredLog.i(SecuredLog.Ev.STATE, "running")
        assertTrue(f.isFile)
        SecuredLog.diagStop()
        SecuredLog.i(SecuredLog.Ev.STATE, "running")
        assertFalse(f.exists())
        assertFalse(SecuredLog.diagActive())

        // Clearing the lines while diagnostics stays on keeps the buffer working.
        val kept = SecuredLog.DiagBuffer(f)
        kept.add("x")
        kept.clear(keepFile = true)
        kept.add("y")
        assertEquals(1, kept.lines().size)
        assertTrue(f.readText().contains("y"))
        f.delete()
    }

    @Test
    fun reloadsEarlierLines() {
        val f = tmp()
        SecuredLog.DiagBuffer(f).add("first")
        val again = SecuredLog.DiagBuffer(f)
        assertEquals(1, again.lines().size)
        f.delete()
    }

    @Test
    fun crashLinesKeepOnlyOurFramesAndNoMessage() {
        val t = IllegalStateException("secret message text")
        t.stackTrace = arrayOf(
            StackTraceElement("android.os.Handler", "dispatch", "H.java", 1),
            StackTraceElement("app.encrogram.core.internal.Foo", "bar", "Foo.kt", 42),
            StackTraceElement("org.telegram.ui.X", "y", "X.java", 5),
        )
        val out = SecuredDiagnostics.crashLines(t)
        assertEquals(listOf("CRASH java.lang.IllegalStateException", "  app.encrogram.core.internal.Foo.bar:42"), out)
        assertFalse(out.joinToString().contains("secret"))
    }

    @Test
    fun crashLinesCapFrames() {
        val t = RuntimeException()
        t.stackTrace = Array(20) { StackTraceElement("app.encrogram.A", "m$it", "A.kt", it) }
        assertEquals(1 + 8, SecuredDiagnostics.crashLines(t).size)
    }
}
