package app.encrogram.core.internal.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressThrottleTest {
    private var now = 0L
    private val t = ProgressThrottle { now }

    @Test
    fun needsBothPerMilleAndTime() {
        assertTrue(t.shouldEmit(1, 1, 0, 10_000))          // first
        now += 300
        assertFalse(t.shouldEmit(1, 1, 5, 10_000))         // 0.5 per mille
        assertTrue(t.shouldEmit(1, 1, 10, 10_000))         // 1 per mille, 300 ms
        now += 100
        assertFalse(t.shouldEmit(1, 1, 50, 10_000))        // enough change, too soon
        now += 150
        assertTrue(t.shouldEmit(1, 1, 50, 10_000))         // 250 ms exactly
    }

    @Test
    fun finalValueAlwaysPasses() {
        assertTrue(t.shouldEmit(1, 1, 10, 100))
        assertTrue(t.shouldEmit(1, 1, 100, 100))           // no time passed
        assertTrue(t.shouldEmit(1, 1, 101, 100))
    }

    @Test
    fun itemsAreIndependentAndForgettable() {
        assertTrue(t.shouldEmit(1, 1, 10, 100))
        assertTrue(t.shouldEmit(1, 2, 10, 100))
        assertTrue(t.shouldEmit(2, 1, 10, 100))
        assertFalse(t.shouldEmit(1, 1, 20, 100))
        t.forget(1, 1)
        assertTrue(t.shouldEmit(1, 1, 20, 100))
    }

    @Test
    fun unknownTotalNeverEmits() {
        assertFalse(t.shouldEmit(1, 1, 5, 0))
    }
}
