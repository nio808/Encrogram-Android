package app.encrogram.core.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyBurstTest {

    /** A fake clock and timer around [NotifyBurst], the way SecuredNotifier drives it. */
    private class Harness {
        val burst = NotifyBurst()
        val unread = UnreadTracker()
        var now = 0L
        var dueAt = -1L
        val posts = mutableListOf<Pair<Int, Int>>()  // total, chats

        fun incoming(chat: Long) {
            unread.add(chat)
            if (burst.onMessage()) dueAt = now + NotifyBurst.WINDOW_MS
        }

        fun advance(ms: Long) {
            now += ms
            if (dueAt in 0..now) {
                dueAt = -1
                if (burst.flush() && unread.total() > 0) posts += unread.total() to unread.chats()
            }
        }
    }

    @Test
    fun hundredMessagesInABurstPostOnceWithFinalCounts() {
        val h = Harness()
        repeat(100) { h.incoming(1L + it % 3); h.advance(5) }
        assertTrue(h.posts.isEmpty())
        h.advance(NotifyBurst.WINDOW_MS)
        assertEquals(listOf(100 to 3), h.posts)
    }

    @Test
    fun separateBurstsPostSeparately() {
        val h = Harness()
        h.incoming(1); h.advance(NotifyBurst.WINDOW_MS)
        h.incoming(1); h.advance(NotifyBurst.WINDOW_MS)
        assertEquals(listOf(1 to 1, 2 to 1), h.posts)
    }

    @Test
    fun cancelledBurstPostsNothing() {
        val h = Harness()
        h.incoming(1)
        h.unread.clearAll(); h.burst.cancel()
        h.advance(NotifyBurst.WINDOW_MS)
        assertTrue(h.posts.isEmpty())
    }

    @Test
    fun onlyFirstMessageStartsABurst() {
        val b = NotifyBurst()
        assertTrue(b.onMessage())
        assertFalse(b.onMessage())
        assertTrue(b.flush())
        assertFalse(b.flush())
        assertTrue(b.onMessage())
    }
}
