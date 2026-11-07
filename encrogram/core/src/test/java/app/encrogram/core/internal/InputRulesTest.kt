package app.encrogram.core.internal

import app.encrogram.core.api.MessagePage
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.SECURED_REACTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRulesTest {

    @Test
    fun reactionsAreExactlyTheProtocolSet() {
        assertEquals(8, SECURED_REACTIONS.size)
        for (r in SECURED_REACTIONS) assertTrue(r, InputRules.reactionAllowed(r))
        // The heart is U+2764 alone; the coloured form with U+FE0F fails the core's parser (gotcha G2).
        assertTrue(InputRules.reactionAllowed("❤"))
        assertFalse(InputRules.reactionAllowed("❤️"))
        assertFalse(InputRules.reactionAllowed("🔥"))
        assertFalse(InputRules.reactionAllowed(""))
        assertFalse(InputRules.reactionAllowed("👍👍"))
    }

    @Test
    fun searchKeepsBmpTextAndSplitsOnEmoji() {
        assertEquals("hello", InputRules.searchQuery("  hello  "))
        assertEquals("مرحبا بك", InputRules.searchQuery("مرحبا بك"))
        // ❤ (U+2764) is in the BMP and survives JNI's modified UTF-8.
        assertEquals("❤ ok", InputRules.searchQuery("❤ ok"))
        // Outside the BMP: split there and search the longest run (a wider match, never a missed one).
        assertEquals("see you", InputRules.searchQuery("hi 👋 see you"))
        assertEquals("tomorrow", InputRules.searchQuery("ok👍tomorrow"))
        // Control characters split too, so nothing can end the command line early.
        assertEquals("/_stop now", InputRules.searchQuery("a\n/_stop now"))
        assertNull(InputRules.searchQuery("👋🚀"))
        assertNull(InputRules.searchQuery("   "))
        // Lone surrogates are dropped.
        assertEquals("ab", InputRules.searchQuery("ab\uD83D"))
    }

    @Test
    fun sanitisedSearchIsStable() {
        for (q in listOf("hello", "hi 👋 see you", "a\tb", "مرحبا")) {
            val once = InputRules.searchQuery(q)!!
            assertEquals(once, InputRules.searchQuery(once))
        }
    }

    @Test
    fun securityCodes() {
        assertEquals("12345 67890", InputRules.securityCode(" 12345   67890 "))
        assertEquals("12345 67890", InputRules.securityCode("12345\n67890"))
        assertNull(InputRules.securityCode("12345-67890"))
        assertNull(InputRules.securityCode("12345 /_stop"))
        assertNull(InputRules.securityCode(""))
    }

    @Test
    fun textLimits() {
        assertTrue(InputRules.textFits("a".repeat(InputRules.MAX_TEXT_BYTES)))
        assertFalse(InputRules.textFits("a".repeat(InputRules.MAX_TEXT_BYTES + 1)))
        // Quotes and newlines are escaped to two bytes, emoji are four.
        assertEquals(2, InputRules.encodedTextBytes("\""))
        assertEquals(4, InputRules.encodedTextBytes("👋"))
        assertEquals(3, InputRules.encodedTextBytes("❤"))
    }

    @Test
    fun replyBudgetCountsTheQuotedText() {
        val quoted = "q".repeat(10_000)
        val room = InputRules.MAX_TEXT_BYTES - 10_000 - InputRules.QUOTE_OVERHEAD_BYTES
        assertTrue(InputRules.replyFits("r".repeat(room), quoted))
        assertFalse(InputRules.replyFits("r".repeat(room + 1), quoted))
        // A short reply to a short message always fits.
        assertTrue(InputRules.replyFits("ok", "hello"))
        // Replying to a maximum-size message leaves no room: refused rather than failing in the core.
        assertFalse(InputRules.replyFits("ok", "q".repeat(InputRules.MAX_TEXT_BYTES)))
    }

    @Test
    fun previewNeverSplitsAnEmoji() {
        val text = "a".repeat(9) + "👋" + "b"
        assertEquals("a".repeat(9), InputRules.preview(text, 10))
        assertEquals("abc", InputRules.preview("abc", 10))
    }

    @Test
    fun ttlRange() {
        assertTrue(InputRules.ttlAllowed(600))
        assertFalse(InputRules.ttlAllowed(0))
        assertFalse(InputRules.ttlAllowed(-5))
        assertFalse(InputRules.ttlAllowed(InputRules.MAX_TTL_SECONDS + 1))
    }

    private fun msg(id: Long, message: Boolean = true) =
        CoreItem(5, id, false, message, "t$id", id * 1000, "rcvRead", MessageStatus.RECEIVED, false)

    @Test
    fun pageWindows() {
        // Oldest first, with a service item (id 3) the UI never sees.
        val items = (1L..10L).map { msg(it, message = it != 3L) }
        assertEquals(listOf(8L, 9L, 10L), InputRules.pageWindow(items, MessagePage(3), 3).map { it.itemId })
        assertEquals(listOf(8L, 9L, 10L), InputRules.pageWindow(items, MessagePage(3, beforeId = 11), 3).map { it.itemId })
        assertEquals(listOf(1L, 2L, 4L), InputRules.pageWindow(items, MessagePage(3, afterId = 0), 3).map { it.itemId })
        // Around: centred on the id, shifted at the ends.
        assertEquals(listOf(5L, 6L, 7L), InputRules.pageWindow(items, MessagePage(3, aroundId = 6), 3).map { it.itemId })
        assertEquals(listOf(1L, 2L, 4L), InputRules.pageWindow(items, MessagePage(3, aroundId = 1), 3).map { it.itemId })
        assertEquals(listOf(8L, 9L, 10L), InputRules.pageWindow(items, MessagePage(3, aroundId = 10), 3).map { it.itemId })
        // The cursor item itself is never repeated in a before/after page.
        assertEquals(listOf(9L, 10L), InputRules.pageWindow(items.filter { it.itemId >= 8 }, MessagePage(2, afterId = 8), 2).map { it.itemId })
    }

    @Test
    fun corePageAsksForMoreThanTheUiWants() {
        assertEquals(70, InputRules.corePageCount(MessagePage(20), 20))
        assertEquals(60, InputRules.corePageCount(MessagePage(20, aroundId = 5), 20))
        assertEquals(550, InputRules.corePageCount(MessagePage(500), 500))
        assertEquals(InputRules.MAX_PAGE, InputRules.corePageCount(MessagePage(1000, aroundId = 5), 1000))
    }
}
