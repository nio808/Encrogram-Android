package app.encrogram.core.internal.media.prep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBudgetTest {
    @Test
    fun dataUrlLengthFormula() {
        assertEquals(22 + 4, PreviewBudget.dataUrlLength(3))
        assertEquals(22 + 8, PreviewBudget.dataUrlLength(4))
        assertEquals(22, PreviewBudget.dataUrlLength(0))
    }

    @Test
    fun firstAttemptWhenItFits() {
        val calls = ArrayList<Pair<Int, Int>>()
        val r = PreviewBudget.fit(8000) { s, q -> calls.add(s to q); ByteArray(1000) }
        assertNotNull(r)
        assertEquals(listOf(320 to 70), calls)
    }

    @Test
    fun lowersQualityThenSideWithKnownCurve() {
        val calls = ArrayList<Pair<Int, Int>>()
        val r2 = PreviewBudget.fit(8000) { s, q -> calls.add(s to q); ByteArray(s * q / 2) }
        assertNotNull(r2)
        // sides: 320 (q70..40: 11200..6400, none <= 5983), 240 at q70 = 8400, 60=7200, 50=6000, 40=4800 -> fits
        assertEquals(listOf(320, 320, 320, 320, 240, 240, 240, 240) , calls.map { it.first })
        assertEquals(listOf(70, 60, 50, 40, 70, 60, 50, 40), calls.map { it.second })
        assertTrue(PreviewBudget.dataUrlLength(r2!!.size) <= 8000)
    }

    @Test
    fun sidesShrinkDownToSixtyFourThenNull() {
        val sides = LinkedHashSet<Int>()
        val r = PreviewBudget.fit(100) { s, _ -> sides.add(s); ByteArray(5000) }
        assertNull(r)
        assertEquals(listOf(320, 240, 180, 135, 101, 75, 64), sides.toList())
    }

    @Test
    fun encoderNullCountsAsMiss() {
        var n = 0
        val r = PreviewBudget.fit(8000) { s, _ -> n++; if (s > 200) null else ByteArray(10) }
        assertNotNull(r)
        assertEquals(9, n)
    }

    @Test
    fun exactBoundaryFits() {
        // 22 + 4*ceil(732/3) = 998
        assertNotNull(PreviewBudget.fit(998) { _, _ -> ByteArray(732) })
        assertNull(PreviewBudget.fit(997) { _, _ -> ByteArray(732) })
    }
}
