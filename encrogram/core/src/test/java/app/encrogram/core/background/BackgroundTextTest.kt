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
    fun groupNotificationIdsNeverMeetContactIds() {
        val base = app.encrogram.core.api.GROUP_CHAT_ID_BASE
        // The old `chatId xor (chatId ushr 32)` folded group 3 onto contact 259 (3 xor 256): the kind is mixed in now.
        for (g in listOf(1L, 3L, 256L, 0x3FFFFFFFL)) {
            val id = NotificationText.notificationId(base + g)
            assertTrue("groups use the negative half", id < -1)
            assertNotEquals(NotificationText.SUMMARY_ID, id)
            for (c in listOf(g, g xor 256L, 1L, 259L, Int.MAX_VALUE.toLong())) assertNotEquals(NotificationText.notificationId(c), id)
        }
        assertEquals(Int.MIN_VALUE + 3, NotificationText.notificationId(base + 3))
        assertNotEquals(NotificationText.notificationId(base + 3), NotificationText.notificationId(base + 4))
        // Contacts keep the ids they had.
        assertEquals(259, NotificationText.notificationId(259L))
        assertEquals(Int.MAX_VALUE, NotificationText.notificationId(Int.MAX_VALUE.toLong()))
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
