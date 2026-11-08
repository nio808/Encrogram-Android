package app.encrogram.core.internal.media.prep

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class Mp4MetaTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be64(v: Long) = cat(be32(v ushr 32), be32(v and 0xFFFFFFFFL))
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val p = cat(*payload)
        return cat(be32(p.size + 8L), ascii(type), p)
    }
    private fun box64(type: String, vararg payload: ByteArray): ByteArray {
        val p = cat(*payload)
        return cat(be32(1), ascii(type), be64(p.size + 16L), p)
    }
    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)
    private val time = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val time8 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val xmpUuid = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    private fun ftyp(brand: String = "isom") = box("ftyp", ascii(brand), be32(0), ascii("isom"))
    private val mdatPayload = ByteArray(64) { (it * 3 + 1).toByte() }
    private fun mdat() = box("mdat", mdatPayload)

    private fun mvhd() = box("mvhd", byteArrayOf(0, 0, 0, 0), time, time, ByteArray(20) { 9 })
    private fun tkhd() = box("tkhd", byteArrayOf(1, 0, 0, 7), time8, time8, ByteArray(20) { 9 })
    private fun mdhd() = box("mdhd", byteArrayOf(0, 0, 0, 0), time, time, ByteArray(8) { 5 })
    private fun gps() = box("udta", box("©xyz", ascii("CANARY-GPS-51.5074")))

    private fun sample(): ByteArray {
        val trak = box("trak", tkhd(), box("mdia", mdhd(), box("hdlr", ByteArray(12) { 3 })), gps())
        val moov = box("moov", mvhd(), trak, box("meta", ascii("CANARY-CAMERA")), gps())
        return cat(ftyp(), moov, box("uuid", xmpUuid, ascii("<x:xmpmeta>CANARY-CAMERA")), mdat())
    }

    private fun assertClean(out: ByteArray) {
        assertFalse(has(out, "CANARY"))
    }

    @Test
    fun neutralizesBytes() {
        val input = sample()
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertEquals(input.size, out.size)
        assertClean(out)
        assertTrue(has(out, "free"))
        assertFalse(has(out, "udta"))
        assertFalse(has(out, "meta"))
        assertFalse(has(out, "uuid"))
        // mdat payload identical, at the same offset
        val at = input.size - mdatPayload.size
        assertArrayEquals(mdatPayload, out.copyOfRange(at, input.size))
        // times zeroed: mvhd version 0 -> 8 bytes after version/flags
        val mv = indexOf(out, "mvhd") + 4 + 4
        assertTrue(out.copyOfRange(mv, mv + 8).all { it.toInt() == 0 })
        assertEquals(9, out[mv + 8].toInt()) // fields after the times untouched
        val tk = indexOf(out, "tkhd") + 4 + 4
        assertTrue(out.copyOfRange(tk, tk + 16).all { it.toInt() == 0 })
        assertEquals(9, out[tk + 16].toInt())
        val md = indexOf(out, "mdhd") + 4 + 4
        assertTrue(out.copyOfRange(md, md + 8).all { it.toInt() == 0 })
        assertEquals(5, out[md + 8].toInt())
    }

    private fun indexOf(b: ByteArray, s: String): Int = String(b, Charsets.ISO_8859_1).indexOf(s)

    @Test
    fun neutralizesFileInPlace() {
        val input = sample()
        val f = File.createTempFile("mp4meta", ".mp4")
        try {
            f.writeBytes(input)
            RandomAccessFile(f, "rw").use { assertTrue(Mp4Meta.neutralize(it)) }
            val out = f.readBytes()
            assertEquals(input.size, out.size)
            assertClean(out)
            val expected = input.copyOf().also { Mp4Meta.neutralize(it) }
            assertArrayEquals(expected, out)
        } finally {
            f.delete()
        }
    }

    @Test
    fun handlesSixtyFourBitBoxes() {
        val trak = box("trak", box64("udta", box("©xyz", ascii("CANARY-GPS-51.5074"))), tkhd())
        val input = cat(ftyp("mp42"), box64("moov", mvhd(), trak), box64("mdat", mdatPayload))
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertEquals(input.size, out.size)
        assertClean(out)
        assertArrayEquals(mdatPayload, out.copyOfRange(input.size - 64, input.size))
    }

    @Test
    fun versionOneMvhdAndSizeZeroLastBox() {
        val mvhd1 = box("mvhd", byteArrayOf(1, 0, 0, 0), time8, time8, ByteArray(30) { 4 })
        val zeroUdta = cat(be32(0), ascii("udta"), ascii("CANARY-GPS-51.5074")) // size 0: to end of file
        val input = cat(ftyp("qt  "), box("moov", mvhd1), zeroUdta)
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertClean(out)
        val mv = indexOf(out, "mvhd") + 8
        assertTrue(out.copyOfRange(mv, mv + 16).all { it.toInt() == 0 })
        assertEquals(4, out[mv + 16].toInt())
    }

    @Test
    fun truncatedBoxRefusesWithoutChange() {
        val input = sample()
        val cut = input.copyOf(input.size - 10) // mdat now claims more than exists
        val before = cut.copyOf()
        assertFalse(Mp4Meta.neutralize(cut))
        assertArrayEquals(before, cut)
    }

    @Test
    fun invalidInnerStructureRefusesWithoutChange() {
        // A udta first (would be queued), then a trak whose child claims more than the trak holds.
        val badChild = cat(be32(500), ascii("tkhd"), ByteArray(4))
        val input = cat(ftyp(), box("moov", gps(), box("trak", badChild)), mdat())
        val copy = input.copyOf()
        assertFalse(Mp4Meta.neutralize(copy))
        assertArrayEquals(input, copy)
    }

    @Test
    fun nonMp4BrandRefuses() {
        val input = cat(ftyp("heic"), box("moov", mvhd(), gps()), mdat())
        val copy = input.copyOf()
        assertFalse(Mp4Meta.neutralize(copy))
        assertArrayEquals(input, copy)
        assertFalse(Mp4Meta.neutralize(ascii("GIF89a-not-an-mp4-file")))
        assertFalse(Mp4Meta.neutralize(ByteArray(0)))
    }

    @Test
    fun noMoovRefuses() {
        assertFalse(Mp4Meta.neutralize(cat(ftyp(), mdat())))
    }

    @Test
    fun truncationsNeverThrowAndNeverChangeOnRefusal() {
        val input = sample()
        val moovSize = input.size - ftyp().size - box("uuid", xmpUuid, ascii("<x:xmpmeta>CANARY-CAMERA")).size - mdat().size
        for (n in 0 until input.size) {
            val cut = input.copyOf(n)
            val before = cut.copyOf()
            val ok = Mp4Meta.neutralize(cut)
            if (n < ftyp().size + moovSize) assertFalse("n=$n", ok)
            if (!ok) assertArrayEquals("n=$n", before, cut)
        }
    }
}
