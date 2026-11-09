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

    // ---- timed metadata tracks (MD10) ------------------------------------------------------------------------

    private enum class Sizes { TABLE, FIXED, COMPACT8, COMPACT4 }

    /** One track: handler, its single sample entry, its samples, and how they are tabled. */
    private class Trk(
        val handler: String,
        val entry: ByteArray,
        val samples: List<ByteArray>,
        val perChunk: Int = 1,
        val sizes: Sizes = Sizes.TABLE,
        val co64: Boolean = false,
    )

    /** A built movie and the byte range of each track's samples. */
    private class Movie(val bytes: ByteArray, val samples: List<IntRange>)

    private val keyName = "com.apple.quicktime.location.ISO6709"
    private val keyId = String(byteArrayOf(0, 0, 0, 1), Charsets.ISO_8859_1) // a mebx local key ID
    private val coords = ascii("+51.5074-000.1278+011.000/") // canary location, ISO 6709
    private fun le(d: Double) = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(d).array()
    private val latLe = le(51.5074)
    private val latE7 = be32(515074000) // GoPro GPS5 latitude, degrees * 1e7

    private fun entry(format: String, vararg children: ByteArray) = box(format, ByteArray(6), byteArrayOf(0, 1), *children)
    private fun hdlr(type: String) = box("hdlr", be32(0), be32(0), ascii(type), ByteArray(12), byteArrayOf(0))
    private fun avc1() = entry("avc1", ByteArray(70) { 7 })
    private fun mebx() = entry(
        "mebx",
        box("keys", box(keyId, box("keyd", ascii("mdta"), ascii(keyName)), box("dtyp", be32(0), be32(1)))),
        box("btrt", ByteArray(12)),
    )
    private fun mebxSample() = box(keyId, coords)
    private fun cammSample() = cat(byteArrayOf(0, 0, 5, 0), latLe, le(-0.1278), le(11.0)) // packet type 5, little-endian
    private fun gpmdSample() = cat(ascii("GPS5"), byteArrayOf(0x6C, 20, 0, 1), latE7, be32(-1278000), be32(11000), be32(0), be32(0))
    private val videoSamples = listOf(ByteArray(40) { (0x40 + it).toByte() }, ByteArray(24) { 0x55 }, ByteArray(9) { 0x21 })

    private fun video() = Trk("vide", avc1(), videoSamples, perChunk = 2)
    private fun appleLocation() = Trk("meta", mebx(), List(3) { mebxSample() }, perChunk = 2)

    private fun stsc(chunks: List<Int>): ByteArray {
        val runs = chunks.indices.filter { it == 0 || chunks[it - 1] != chunks[it] }
        return box("stsc", be32(0), be32(runs.size.toLong()), *runs.map { cat(be32(it + 1L), be32(chunks[it].toLong()), be32(1)) }.toTypedArray())
    }

    private fun sizes(t: Trk): ByteArray {
        val n = t.samples.size.toLong()
        val s = t.samples.map { it.size }
        return when (t.sizes) {
            Sizes.TABLE -> box("stsz", be32(0), be32(0), be32(n), *s.map { be32(it.toLong()) }.toTypedArray())
            Sizes.FIXED -> box("stsz", be32(0), be32(s.distinct().single().toLong()), be32(n))
            // stz2: version and flags, 24 reserved bits + field_size, sample_count, packed sizes
            Sizes.COMPACT8 -> box("stz2", be32(0), be32(8), be32(n), ByteArray(s.size) { s[it].toByte() })
            Sizes.COMPACT4 -> box("stz2", be32(0), be32(4), be32(n), ByteArray((s.size + 1) / 2) { (s[2 * it] shl 4 or s.getOrElse(2 * it + 1) { 0 }).toByte() })
        }
    }

    private fun offsets(t: Trk, offs: List<Long>) =
        if (t.co64) box("co64", be32(0), be32(offs.size.toLong()), *offs.map { be64(it) }.toTypedArray())
        else box("stco", be32(0), be32(offs.size.toLong()), *offs.map { be32(it) }.toTypedArray())

    /** ftyp, a moov with one trak per track, then one mdat holding every track's samples in order. */
    private fun movie(vararg tracks: Trk): Movie {
        val total = tracks.sumOf { t -> t.samples.sumOf { it.size } }
        fun build(mdatStart: Int): Movie {
            var at = mdatStart
            val ranges = ArrayList<IntRange>()
            val traks = tracks.map { t ->
                val first = at
                val chunks = t.samples.chunked(t.perChunk)
                val offs = chunks.map { c -> at.toLong().also { at += c.sumOf { it.size } } }
                ranges.add(first until at)
                val stbl = box("stbl", box("stsd", be32(0), be32(1), t.entry), stsc(chunks.map { it.size }), sizes(t), offsets(t, offs))
                box("trak", tkhd(), box("mdia", mdhd(), hdlr(t.handler), box("minf", stbl)))
            }
            val mdat = box("mdat", *tracks.flatMap { it.samples }.toTypedArray())
            return Movie(cat(ftyp(), box("moov", mvhd(), *traks.toTypedArray()), mdat), ranges)
        }
        val draft = build(0) // offsets don't change sizes, so one draft gives the mdat position
        return build(draft.bytes.size - total)
    }

    private fun zeros(b: ByteArray, r: IntRange) = r.all { b[it].toInt() == 0 }
    private fun bytesAt(b: ByteArray, r: IntRange) = b.copyOfRange(r.first, r.last + 1)
    private fun occurrences(b: ByteArray, s: String) = Regex(Regex.escape(s)).findAll(String(b, Charsets.ISO_8859_1)).map { it.range.first }.toList()
    private fun timeFields(b: ByteArray) = listOf("mvhd", "tkhd", "mdhd").flatMap { t -> occurrences(b, t).map { it + 8 until it + 24 } }
    private fun patch(b: ByteArray, at: Int, v: ByteArray) = b.copyOf().also { System.arraycopy(v, 0, it, at, v.size) }
    private fun last(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).lastIndexOf(s)

    private fun assertOnlyChanged(input: ByteArray, out: ByteArray, allowed: List<IntRange>) {
        assertEquals(input.size, out.size)
        for (i in input.indices) if (input[i] != out[i]) assertTrue("byte $i changed", allowed.any { i in it })
    }

    private fun assertRefusedUnchanged(name: String, input: ByteArray) {
        val copy = input.copyOf()
        assertFalse(name, Mp4Meta.neutralize(copy))
        assertArrayEquals(name, input, copy)
    }

    @Test
    fun scrubsAppleLocationTrack() {
        val m = movie(video(), appleLocation())
        val input = m.bytes
        assertTrue(has(input, "51.5074") && has(input, keyName))
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertFalse(has(out, "51.5074"))
        assertFalse(has(out, keyName))
        assertTrue(has(out, "mdta")) // the key namespace stays; only the name is zeroed
        assertTrue(zeros(out, m.samples[1]))
        assertArrayEquals(bytesAt(input, m.samples[0]), bytesAt(out, m.samples[0]))
        val name = occurrences(input, "keyd").single() + 8
        assertOnlyChanged(input, out, timeFields(input) + listOf(m.samples[1], name until name + keyName.length))
    }

    @Test
    fun scrubsCammAndGpmdTracks() {
        val camm = Trk("camm", entry("camm"), List(4) { cammSample() }, perChunk = 3, sizes = Sizes.COMPACT8, co64 = true)
        val gpmd = Trk("data", entry("gpmd"), List(2) { gpmdSample() }, sizes = Sizes.FIXED)
        val m = movie(video(), camm, gpmd)
        val input = m.bytes
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        for (canary in listOf(latLe, latE7, ascii("GPS5"))) {
            assertTrue(has(input, String(canary, Charsets.ISO_8859_1)))
            assertFalse(has(out, String(canary, Charsets.ISO_8859_1)))
        }
        assertTrue(zeros(out, m.samples[1]) && zeros(out, m.samples[2]))
        assertArrayEquals(bytesAt(input, m.samples[0]), bytesAt(out, m.samples[0]))
        assertOnlyChanged(input, out, timeFields(input) + listOf(m.samples[1], m.samples[2]))
    }

    @Test
    fun metaHandlerAloneIsEnoughAndFourBitSizesRead() {
        // Sizes 2, 9, 5 packed as nibbles 0x29 0x50: reading them in the wrong order would leave bytes of sample 2.
        val t = Trk("meta", entry("mett"), listOf(ascii("+5"), ascii("1.5074-00"), ascii("0.127")), sizes = Sizes.COMPACT4)
        val m = movie(video(), t)
        val out = m.bytes.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertTrue(zeros(out, m.samples[1]))
        assertOnlyChanged(m.bytes, out, timeFields(m.bytes) + listOf(m.samples[1]))
    }

    @Test
    fun scrubsLocationTrackInFile() {
        val input = movie(video(), appleLocation()).bytes
        val f = File.createTempFile("mp4meta", ".mov")
        try {
            f.writeBytes(input)
            RandomAccessFile(f, "rw").use { assertTrue(Mp4Meta.neutralize(it)) }
            val expected = input.copyOf().also { assertTrue(Mp4Meta.neutralize(it)) }
            assertArrayEquals(expected, f.readBytes())
        } finally {
            f.delete()
        }
    }

    @Test
    fun ordinaryTracksAreNotScrubbed() {
        val audio = Trk("soun", entry("mp4a", ByteArray(20)), listOf(ascii("AUDIO-51.5074")))
        val m = movie(video(), audio)
        val out = m.bytes.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertOnlyChanged(m.bytes, out, timeFields(m.bytes))
    }

    @Test
    fun refusesMalformedLocationTables() {
        val input = movie(video(), appleLocation()).bytes // the location trak is last, so last() finds its tables
        val stco = last(input, "stco")
        val stsz = last(input, "stsz")
        val stsc = last(input, "stsc")
        val keyd = last(input, "keyd")
        assertRefusedUnchanged("offset past the end", patch(input, stco + 12, be32(input.size.toLong())))
        assertRefusedUnchanged("offset outside mdat", patch(input, stco + 12, be32(0)))
        assertRefusedUnchanged("chunk runs past the end", patch(input, stco + 12, be32(input.size - 2L)))
        assertRefusedUnchanged("huge sample", patch(input, stsz + 16, be32(0x7FFFFFFF)))
        assertRefusedUnchanged("fewer samples than mapped", patch(input, stsz + 12, be32(2)))
        assertRefusedUnchanged("count beyond the box", patch(input, stsz + 12, be32(4)))
        assertRefusedUnchanged("unmapped samples", patch(input, stsc + 16, be32(1)))
        assertRefusedUnchanged("first chunk not 1", patch(input, stsc + 12, be32(2)))
        assertRefusedUnchanged("chunks not increasing", patch(input, stsc + 24, be32(1)))
        assertRefusedUnchanged("huge stsc", patch(input, stsc + 8, be32(0x10000000)))
        assertRefusedUnchanged("no chunk offsets", patch(input, stco, ascii("stcx")))
        assertRefusedUnchanged("no sample sizes", patch(input, stsz, ascii("stsx")))
        assertRefusedUnchanged("broken key box", patch(input, keyd - 4, be32(0x7FFFFFFF)))
        assertRefusedUnchanged("fragmented", cat(input, box("moof", box("mfhd", ByteArray(8)))))

        val compact = movie(video(), Trk("meta", entry("gpmd"), List(2) { gpmdSample() }, sizes = Sizes.COMPACT8, co64 = true)).bytes
        assertRefusedUnchanged("bad stz2 field size", patch(compact, last(compact, "stz2") + 11, byteArrayOf(12)))
        assertRefusedUnchanged("negative co64 offset", patch(compact, last(compact, "co64") + 12, be64(-1)))

        val fixed = movie(video(), Trk("meta", entry("camm"), List(2) { cammSample() }, sizes = Sizes.FIXED)).bytes
        val fs = last(fixed, "stsz")
        val fc = last(fixed, "stsc")
        assertRefusedUnchanged("fixed size past the end", patch(fixed, fs + 8, be32(0xFFFFFFFFL)))
        val overflow = patch(patch(patch(fixed, fs + 8, be32(0xFFFFFFFFL)), fs + 12, be32(0xFFFFFFFFL)), fc + 16, be32(0xFFFFFFFFL))
        assertRefusedUnchanged("size times count overflows", overflow)
    }

    @Test
    fun fragmentedFileWithoutTimedTrackStillScrubs() {
        val input = cat(sample(), box("moof", box("mfhd", ByteArray(8))))
        val out = input.copyOf()
        assertTrue(Mp4Meta.neutralize(out))
        assertClean(out)
    }

    @Test
    fun corruptedLocationMoviesNeverThrow() {
        val camm = Trk("camm", entry("camm"), List(3) { cammSample() }, perChunk = 2, sizes = Sizes.COMPACT8, co64 = true)
        for (input in listOf(movie(video(), appleLocation()).bytes, movie(video(), camm).bytes)) {
            for (n in 0 until input.size) {
                val cut = input.copyOf(n)
                if (!Mp4Meta.neutralize(cut)) assertArrayEquals("n=$n", input.copyOf(n), cut)
            }
            for (i in input.indices) {
                for (v in intArrayOf(0x00, 0xFF, input[i].toInt() xor 0x01, input[i] + 1)) {
                    val bad = input.copyOf().also { it[i] = v.toByte() }
                    val copy = bad.copyOf()
                    if (!Mp4Meta.neutralize(copy)) assertArrayEquals("i=$i v=$v", bad, copy)
                }
            }
        }
    }
}
