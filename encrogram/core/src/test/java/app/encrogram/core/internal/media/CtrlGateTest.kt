package app.encrogram.core.internal.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CtrlGateTest {

    @Test
    fun useRunsTheBlockAndReturnsItsValue() {
        val gate = CtrlGate()
        assertEquals(42, gate.use { 42 })
        assertFalse(gate.isClosed)
    }

    @Test
    fun severalUsesRunAtTheSameTime() {
        val gate = CtrlGate()
        val both = CountDownLatch(2)
        val ok = AtomicInteger()
        val ts = (1..2).map {
            thread {
                gate.use {
                    both.countDown()
                    if (both.await(5, TimeUnit.SECONDS)) ok.incrementAndGet()
                    Unit
                }
            }
        }
        ts.forEach { it.join(5000) }
        assertEquals(2, ok.get())
    }

    @Test
    fun aCloseWaitsForAnInFlightSeal() {
        val gate = CtrlGate()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sealDone = AtomicBoolean(false)
        val sealer = thread {
            gate.use {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                sealDone.set(true)
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))

        val closedAfterSeal = AtomicBoolean(false)
        val closer = thread { gate.close { closedAfterSeal.set(sealDone.get()) } }
        Thread.sleep(200)
        assertTrue("close is still waiting for the seal", closer.isAlive)

        release.countDown()
        closer.join(5000)
        sealer.join(5000)
        assertFalse(closer.isAlive)
        assertTrue("the store closed only after the seal finished", closedAfterSeal.get())
        assertTrue(gate.isClosed)
    }

    @Test
    fun aSealAfterCloseIsNoCtrlUntilReopen() {
        val gate = CtrlGate()
        val ran = AtomicBoolean(false)
        gate.close {}
        val e = thrown<NativeFileException> { gate.use { ran.set(true) } }
        assertEquals("no_ctrl", e.tag)
        assertFalse(ran.get())

        gate.reopen()
        assertEquals("ok", gate.use { "ok" })
    }

    @Test
    fun aSealStartingDuringTheCloseWaitsThenIsRefused() {
        val gate = CtrlGate()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closer = thread {
            gate.close {
                closing.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        val outcome = AtomicReference<Any?>(null)
        val sealer = thread {
            outcome.set(
                try {
                    gate.use { "ran" }
                } catch (e: NativeFileException) {
                    e.tag
                },
            )
        }
        Thread.sleep(150)
        assertTrue("the seal waits while the store closes", sealer.isAlive)
        release.countDown()
        closer.join(5000)
        sealer.join(5000)
        assertEquals("no_ctrl", outcome.get())
    }

    @Test
    fun aFailingBlockReleasesTheGate() {
        val gate = CtrlGate()
        thrown<IllegalStateException> { gate.use { throw IllegalStateException("x") } }
        val closed = CountDownLatch(1)
        thread { gate.close { closed.countDown() } }
        assertTrue("close isn't blocked by a block that threw", closed.await(5, TimeUnit.SECONDS))

        // A close whose block throws still leaves the gate closed and unlocked.
        val g2 = CtrlGate()
        thrown<IllegalStateException> { g2.close { throw IllegalStateException("y") } }
        assertTrue(g2.isClosed)
        assertEquals("no_ctrl", thrown<NativeFileException> { g2.use { } }.tag)
        g2.reopen()
        assertEquals(1, g2.use { 1 })
    }

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.simpleName}, got ${t.javaClass.simpleName}", t)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing thrown")
    }
}
