package app.encrogram.core.background

import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.TestContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** H11: "background on" goes to disk only when it changes; the reset's folder delete is followed by [BackgroundState.forget]. */
class BackgroundStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val flag get() = File(dir, "encrogram-secured/background_on")

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        dir = tmp.newFolder("nobackup")
        BackgroundState.forget()
    }

    @After
    fun tearDown() = BackgroundState.forget()

    @Test
    fun repeatedCallsWithTheSameValueSkipTheDisk() {
        val context = TestContext(dir)
        assertFalse(BackgroundState.isEnabled(context))
        BackgroundState.setEnabled(context, true)
        assertTrue(flag.isFile)
        assertTrue(BackgroundState.isEnabled(context))

        // Removed behind the object's back: the same value again doesn't look at the disk, nor does isEnabled.
        assertTrue(flag.delete())
        BackgroundState.setEnabled(context, true)
        assertFalse(flag.isFile)
        assertTrue(BackgroundState.isEnabled(context))

        // A change still writes.
        BackgroundState.setEnabled(context, false)
        BackgroundState.setEnabled(context, true)
        assertTrue(flag.isFile)
        BackgroundState.setEnabled(context, false)
        assertFalse(flag.isFile)
        assertFalse(BackgroundState.isEnabled(context))
    }

    @Test
    fun afterAResetTheNextCallGoesToTheDiskAgain() {
        val context = TestContext(dir)
        BackgroundState.setEnabled(context, true)
        // What "Reset Secured" does: the whole folder goes, then the in-memory copy.
        assertTrue(File(dir, "encrogram-secured").deleteRecursively())
        BackgroundState.forget()
        assertFalse(BackgroundState.isEnabled(context))
        BackgroundState.setEnabled(context, true)
        assertTrue(flag.isFile)
    }

    @Test
    fun theFirstReadComesFromTheDisk() {
        assertTrue(File(dir, "encrogram-secured").mkdirs())
        assertTrue(flag.createNewFile())
        assertTrue(BackgroundState.isEnabled(TestContext(dir)))
    }
}
