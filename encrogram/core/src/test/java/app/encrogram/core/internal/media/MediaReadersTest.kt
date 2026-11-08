package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MediaReader
import app.encrogram.core.internal.SecuredLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MediaReadersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun sample(n: Int = 100) = ByteArray(n) { (it + 1).toByte() }

    // ---- memory --------------------------------------------------------------------------------------------------

    @Test
    fun byteArrayReaderReadsLikeMediaDataSource() {
        val data = sample()
        val r = ByteArrayMediaReader(data)
        assertEquals(100L, r.size())
        assertEquals(r.size(), r.available())

        val buf = ByteArray(30)
        assertEquals(30, r.readAt(10, buf, 0, 30))
        assertArrayEquals(data.copyOfRange(10, 40), buf)
        // Near the end: a short read, then -1 at the end.
        assertEquals(5, r.readAt(95, buf, 0, 30))
        assertArrayEquals(data.copyOfRange(95, 100), buf.copyOf(5))
        assertEquals(-1, r.readAt(100, buf, 0, 30))
        assertEquals(-1, r.readAt(1000, buf, 0, 30))
        assertEquals(0, r.readAt(0, buf, 0, 0))
        // Into the middle of the caller's buffer.
        val big = ByteArray(10)
        assertEquals(4, r.readAt(0, big, 6, 4))
        assertArrayEquals(data.copyOf(4), big.copyOfRange(6, 10))
    }

    @Test
    fun byteArrayReaderRefusesBadArguments() {
        val r = ByteArrayMediaReader(sample())
        thrown<IllegalArgumentException> { r.readAt(-1, ByteArray(4), 0, 4) }
        thrown<IndexOutOfBoundsException> { r.readAt(0, ByteArray(4), 2, 4) }
        thrown<IndexOutOfBoundsException> { r.readAt(0, ByteArray(4), -1, 1) }
        thrown<IndexOutOfBoundsException> { r.readAt(0, ByteArray(4), 0, -1) }
    }

    @Test
    fun byteArrayReaderCloseZeroFillsAndIsIdempotent() {
        val data = sample()
        val r = ByteArrayMediaReader(data)
        r.close()
        assertTrue(r.isClosed)
        assertTrue("plaintext zeroed on close", data.all { it == 0.toByte() })
        assertEquals(-1, r.readAt(0, ByteArray(4), 0, 4))
        r.close()
        assertEquals(-1, r.readAt(0, ByteArray(4), 0, 4))
    }

    @Test
    fun closeFromAnotherThreadWhileReadingIsSafe() {
        val data = sample(64 * 1024)
        val r = ByteArrayMediaReader(data)
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)
        val sawEnd = CountDownLatch(1)
        val reader = thread {
            try {
                val buf = ByteArray(1024)
                var pos = 0L
                started.countDown()
                while (true) {
                    val n = r.readAt(pos % data.size, buf, 0, buf.size)
                    if (n < 0) break
                    pos += n
                }
                sawEnd.countDown()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        started.await()
        r.close()
        assertTrue(sawEnd.await(5, TimeUnit.SECONDS))
        reader.join(5000)
        assertEquals(null, failure.get())
        assertTrue(data.all { it == 0.toByte() })
    }

    // ---- file ----------------------------------------------------------------------------------------------------

    @Test
    fun fileReaderReadsAndDeletesOnClose() {
        val data = sample(1000)
        val f = tmp.newFile("view-plain").apply { writeBytes(data) }
        val r = FileMediaReader(f)
        assertEquals(1000L, r.size())
        assertEquals(r.size(), r.available())

        val buf = ByteArray(100)
        assertEquals(100, r.readAt(450, buf, 0, 100))
        assertArrayEquals(data.copyOfRange(450, 550), buf)
        assertEquals(50, r.readAt(950, buf, 0, 100))
        assertArrayEquals(data.copyOfRange(950, 1000), buf.copyOf(50))
        assertEquals(-1, r.readAt(1000, buf, 0, 100))
        assertEquals(0, r.readAt(10, buf, 0, 0))
        // Positions can go backwards (players seek).
        assertEquals(10, r.readAt(0, buf, 0, 10))
        assertArrayEquals(data.copyOf(10), buf.copyOf(10))

        r.close()
        assertTrue(r.isClosed)
        assertFalse("the decrypted copy is deleted on close", f.exists())
        assertEquals(-1, r.readAt(0, buf, 0, 10))
        r.close()
        assertFalse(f.exists())
    }

    @Test
    fun fileReaderOnAMissingFileThrowsAndLeavesNothing() {
        val f = File(tmp.root, "never-written")
        thrown<MediaReaderException> { FileMediaReader(f) }
        assertFalse(f.exists())
    }

    @Test
    fun fileReaderExceptionsCarryNoPath() {
        val f = File(tmp.root, "CANARY_NAME.mp4")
        val e = thrown<MediaReaderException> { FileMediaReader(f) }
        assertFalse(e.message.orEmpty().contains("CANARY"))
        assertEquals(null, e.cause)
    }

    // ---- playback and registry -----------------------------------------------------------------------------------

    @Test
    fun playbackCloseClosesTheReaderOnceAndUnregisters() {
        val f = tmp.newFile("v").apply { writeBytes(sample()) }
        val registry = PlaybackRegistry()
        val reader = FileMediaReader(f)
        val p = registry.open(5L, 9L, MediaKind.VIDEO, "video/mp4", 12, reader)
        assertEquals(MediaKind.VIDEO, p.kind)
        assertEquals("video/mp4", p.mimeType)
        assertEquals(12, p.durationSeconds)
        assertEquals(1, registry.count())

        p.close()
        assertTrue(p.isClosed)
        assertTrue(reader.isClosed)
        assertFalse(f.exists())
        assertEquals(0, registry.count())
        p.close()
        assertEquals(0, registry.count())
    }

    @Test
    fun registryClosesByMessageByChatAndAll() {
        val registry = PlaybackRegistry()
        val a = registry.open(1L, 10L, MediaKind.VOICE, null, 3, ByteArrayMediaReader(sample()))
        val a2 = registry.open(1L, 10L, MediaKind.VOICE, null, 3, ByteArrayMediaReader(sample()))
        val b = registry.open(1L, 11L, MediaKind.VIDEO, null, null, ByteArrayMediaReader(sample()))
        val c = registry.open(2L, 10L, MediaKind.VIDEO, null, null, ByteArrayMediaReader(sample()))
        val d = registry.open(3L, 1L, MediaKind.VIDEO, null, null, ByteArrayMediaReader(sample()))
        assertEquals(5, registry.count())

        assertEquals(2, registry.closeFor(1L, listOf(10L, 99L)))
        assertTrue(a.isClosed && a2.isClosed)
        assertFalse(b.isClosed || c.isClosed || d.isClosed)
        assertEquals(3, registry.count())

        assertEquals(1, registry.closeChat(2L))
        assertTrue(c.isClosed)
        assertEquals(2, registry.count())

        assertEquals(2, registry.closeAll())
        assertTrue(b.isClosed && d.isClosed)
        assertEquals(0, registry.count())
        assertEquals(0, registry.closeAll())
    }

    @Test
    fun aPlaybackThatFailsToCloseDoesNotStopTheOthers() {
        val registry = PlaybackRegistry()
        val bad = object : MediaPlayback {
            override val kind = MediaKind.VIDEO
            override val mimeType: String? = null
            override val durationSeconds: Int? = null
            override val reader: MediaReader = ByteArrayMediaReader(ByteArray(1))
            override fun close() {
                throw IllegalStateException("boom")
            }
        }
        registry.register(1L, 1L, bad)
        val good = registry.open(1L, 2L, MediaKind.VIDEO, null, null, ByteArrayMediaReader(sample()))
        assertEquals(2, registry.closeAll())
        assertTrue(good.isClosed)
        assertEquals(0, registry.count())
    }

    @Test
    fun unregisterRemovesOnlyThatPlayback() {
        val registry = PlaybackRegistry()
        val one = VaultPlayback(MediaKind.VOICE, null, null, ByteArrayMediaReader(sample()))
        val two = VaultPlayback(MediaKind.VOICE, null, null, ByteArrayMediaReader(sample()))
        registry.register(1L, 1L, one)
        registry.register(1L, 1L, two)
        registry.unregister(one)
        assertEquals(1, registry.count())
        assertFalse("unregister doesn't close", one.isClosed)
        registry.closeFor(1L, listOf(1L))
        assertTrue(two.isClosed)
        assertFalse(one.isClosed)
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
