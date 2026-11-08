package app.encrogram.core.background

import android.content.Intent
import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.GroupEvent
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.SecuredMessage
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
        val all = listOf(1, 2, 3, 99).map { NotificationText.combinedText(it, it) } + NotificationText.PUBLIC_TEXT +
            NotificationText.TITLE + NotificationText.SERVICE_TEXT + NotificationText.SERVICE_TITLE
        for (s in all) {
            assertFalse(s.contains(secretText))
            assertFalse(s.contains(secretName))
        }
    }

    @Test
    fun combinedCountText() {
        assertEquals("1 new message", NotificationText.combinedText(1, 1))
        assertEquals("1 new message", NotificationText.combinedText(0, 0))
        assertEquals("5 new messages", NotificationText.combinedText(5, 1))
        assertEquals("5 new messages in 3 chats", NotificationText.combinedText(5, 3))
        assertEquals("2 new messages in 2 chats", NotificationText.combinedText(2, 2))
    }

    @Test
    fun singleMessageIdForEveryChat() {
        // One id, tagged: no per-chat ids left for notification listeners to count by.
        assertEquals(1, NotificationText.MESSAGES_ID)
        assertNotEquals(NotificationText.SERVICE_ID, NotificationText.MESSAGES_ID)
    }

    @Test
    fun unreadTrackerCountsAndPicksTapTarget() {
        val t = UnreadTracker()
        assertEquals(0, t.total())
        assertEquals(null, t.singleChat())
        t.add(5); t.add(5)
        assertEquals(2, t.total()); assertEquals(1, t.chats()); assertEquals(5L, t.singleChat())
        t.add(GROUP_CHAT_ID_BASE + 3)
        assertEquals(3, t.total()); assertEquals(2, t.chats()); assertEquals(null, t.singleChat())
        t.clear(5)
        assertEquals(1, t.total()); assertEquals(GROUP_CHAT_ID_BASE + 3, t.singleChat())
        t.clear(GROUP_CHAT_ID_BASE + 3)
        assertEquals(0, t.total())
        t.add(1); t.add(2); t.clearAll()
        assertEquals(0, t.chats())
    }

    @Test
    fun onlyMessagesAndInvitationsToYouNotify() {
        // Review #5: "X was invited" in a group notified every member.
        fun msg(chatId: Long, outgoing: Boolean = false, event: GroupEvent? = null) =
            SecuredMessage(chatId, 1, outgoing, "", 0, MessageStatus.DELIVERED, event = event)
        val group = GROUP_CHAT_ID_BASE + 3
        // Incoming messages, 1:1 and group.
        assertTrue(SecuredService.notifies(msg(5)))
        assertTrue(SecuredService.notifies(msg(group)))
        // "You were invited to <group>", in the inviter's 1:1 chat.
        assertTrue(SecuredService.notifies(msg(5, event = GroupEvent(GroupEvent.Kind.INVITED, groupName = "Team"))))
        // "X was invited" (memberAdded, invitedViaGroupLink) in a group: no.
        assertFalse(SecuredService.notifies(msg(group, event = GroupEvent(GroupEvent.Kind.INVITED, "Dan"))))
        // Other service rows, anywhere: no.
        for (k in GroupEvent.Kind.values().filter { it != GroupEvent.Kind.INVITED }) {
            assertFalse(k.name, SecuredService.notifies(msg(group, event = GroupEvent(k, "Dan"))))
            assertFalse(k.name, SecuredService.notifies(msg(5, event = GroupEvent(k, "Dan"))))
        }
        // Our own: messages, "Bob was invited" in our chat with Bob, "You left".
        assertFalse(SecuredService.notifies(msg(5, outgoing = true)))
        assertFalse(SecuredService.notifies(msg(5, outgoing = true, event = GroupEvent(GroupEvent.Kind.INVITED, "Bob", groupName = "Team"))))
        assertFalse(SecuredService.notifies(msg(group, outgoing = true, event = GroupEvent(GroupEvent.Kind.LEFT))))
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
