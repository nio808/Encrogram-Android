package app.encrogram.core.background

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTextTest {

    @Test
    fun hiddenTextNeverHasNamesOrMessageText() {
        val secretText = "meet at the bridge"
        val secretName = "Alice"
        val all = listOf(1, 2, 3, 99).map { NotificationText.messageText(it) } +
            NotificationText.TITLE + NotificationText.SERVICE_TEXT + NotificationText.SERVICE_TITLE
        for (s in all) {
            assertFalse(s.contains(secretText))
            assertFalse(s.contains(secretName))
        }
    }

    @Test
    fun countText() {
        assertEquals("New Secured message", NotificationText.messageText(1))
        assertEquals("New Secured message", NotificationText.messageText(0))
        assertEquals("3 new Secured messages", NotificationText.messageText(3))
    }

    @Test
    fun notificationIdIsStableAndNeverTheSummary() {
        assertEquals(NotificationText.notificationId(42L), NotificationText.notificationId(42L))
        assertEquals(42, NotificationText.notificationId(42L))
        assertNotEquals(NotificationText.notificationId(1L), NotificationText.notificationId(2L))
        assertNotEquals(NotificationText.SUMMARY_ID, NotificationText.notificationId(1L))
    }

    @Test
    fun bootStartsOnlyWhenSavedFlagIsOnAndActionIsSystem() {
        assertTrue(SecuredBootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, true))
        assertTrue(SecuredBootReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, true))
        assertFalse(SecuredBootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, false))
        assertFalse(SecuredBootReceiver.shouldStart("app.evil.ACTION", true))
        assertFalse(SecuredBootReceiver.shouldStart(null, true))
    }
}
