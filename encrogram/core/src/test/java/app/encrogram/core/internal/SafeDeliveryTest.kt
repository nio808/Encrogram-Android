package app.encrogram.core.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** A10: one throwing listener neither stops the others nor crashes a release build; debug builds stay loud. */
class SafeDeliveryTest {

    private val lines = ArrayList<String>()

    @Before
    fun sink() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { lines.add(it) }
    }

    @After
    fun reset() {
        SecuredLog.testSink = null
        SafeDelivery.rethrow = app.encrogram.core.BuildConfig.DEBUG
    }

    @Test
    fun releaseKeepsDeliveringAndLogsOnlyTheClass() {
        SafeDelivery.rethrow = false
        val heard = ArrayList<String>()
        val listeners = listOf<(String) -> Unit>(
            { heard.add("screen:$it") },
            { throw IllegalStateException("message text canary") },
            { heard.add("notifier:$it") },
        )
        for (event in listOf("a", "b")) SafeDelivery.each(listeners) { it(event) }
        assertEquals(listOf("screen:a", "notifier:a", "screen:b", "notifier:b"), heard)
        assertEquals(listOf("LISTENER_FAILED kind=IllegalStateException", "LISTENER_FAILED kind=IllegalStateException"), lines)
        assertTrue(lines.none { it.contains("canary") })

        var answered = false
        SafeDelivery.one { throw NullPointerException() }
        SafeDelivery.one { answered = true }
        assertTrue(answered)
    }

    @Test
    fun debugRethrows() {
        SafeDelivery.rethrow = true
        try {
            SafeDelivery.one { throw IllegalArgumentException() }
            fail()
        } catch (e: IllegalArgumentException) {
            // expected: the bug stays loud
        }
    }

    @Test
    fun outOfMemoryIsNeverSwallowed() {
        SafeDelivery.rethrow = false
        try {
            SafeDelivery.one { throw OutOfMemoryError() }
            fail()
        } catch (e: OutOfMemoryError) {
            // expected
        }
    }
}
