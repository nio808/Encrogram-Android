package app.encrogram.core.internal.media.prep

import java.io.RandomAccessFile

/**
 * Neutralizes metadata in an MP4 or MOV file in place (docs/media-plan.md header amendment 2): no remux, no re-encode,
 * so the file size and every box offset stay the same. Pure Kotlin.
 *
 * Handled:
 * - `udta` and `meta` boxes (at the top level and inside moov, trak, mdia) and the XMP `uuid` box
 *   BE7ACFCB-97A9-42E8-9C71-999491E3AFAC become type `free` with a zeroed payload;
 * - creation and modification times in `mvhd`, `tkhd` and `mdhd` are zeroed (version 0: 4 bytes each, version 1: 8);
 * - timed metadata tracks (MD10, research/media-engine.md fact 20), which can carry location as samples in `mdat`:
 *   a track whose handler is `meta`, `camm` or `data`, or that lists a `mebx`, `camm` or `gpmd` sample entry, has
 *   every sample zeroed (found through stsc, stsz or stz2, and stco or co64) and the key names of its `mebx` entries
 *   zeroed. Every sample must lie inside a top-level `mdat`. Such a track in a fragmented file (`mvex`, `moof`) is
 *   refused, because its samples would be described outside the tables read here.
 * Other `mdat` bytes are never read or written. Box walking supports 64-bit largesize and size 0 (to the end).
 *
 * The whole structure is validated first and writes happen only afterwards, so a refusal (false) changes nothing.
 * Validation throws [Refused] (or any other RuntimeException) and [plan] turns that into the refusal.
 * Only MP4 and MOV brands are accepted.
 */
internal object Mp4Meta {
    private val BRANDS = setOf("isom", "iso2", "mp41", "mp42", "avc1", "qt  ", "M4V ", "M4A ", "3gp4", "3gp5", "3gp6")
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )
    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)
    private val TIMED_HANDLERS = setOf("meta", "camm", "data")
    private val TIMED_ENTRIES = setOf("mebx", "camm", "gpmd")
    private const val MAX_DEPTH = 6
    private const val MAX_TABLE = 16L shl 20 // bytes per sample table; far above any real metadata track

    private interface Io {
        val length: Long
        fun read(pos: Long, n: Int): ByteArray?
    }

    /** len bytes at pos: zeros when [data] is null. */
    private class Op(val pos: Long, val len: Long, val data: ByteArray?)

    /** A box: header at [pos], payload from [body] to [end]. */
    private class Box(val type: String, val pos: Long, val body: Long, val end: Long)

    private class Refused : RuntimeException()

    /** What one pass over the file found. Sample zeros are kept apart so they can be checked against `mdat`. */
    private class Scan {
        val ops = ArrayList<Op>()
        val samples = ArrayList<Op>()
        val mdats = ArrayList<Box>()
        var moov = false
        var timed = false
        var fragmented = false
    }

    fun neutralize(bytes: ByteArray): Boolean {
        val io = object : Io {
            override val length = bytes.size.toLong()
            override fun read(pos: Long, n: Int): ByteArray? =
                if (pos < 0 || n < 0 || pos + n > bytes.size) null else bytes.copyOfRange(pos.toInt(), pos.toInt() + n)
        }
        val ops = plan(io) ?: return false
        for (op in ops) {
            if (op.data != null) {
                System.arraycopy(op.data, 0, bytes, op.pos.toInt(), op.len.toInt())
            } else {
                java.util.Arrays.fill(bytes, op.pos.toInt(), (op.pos + op.len).toInt(), 0)
            }
        }
        return true
    }

    fun neutralize(file: RandomAccessFile): Boolean = try {
        val io = object : Io {
            override val length = file.length()
            override fun read(pos: Long, n: Int): ByteArray? {
                if (pos < 0 || n < 0 || pos + n > length) return null
                val out = ByteArray(n)
                file.seek(pos)
                file.readFully(out)
                return out
            }
        }
        val ops = plan(io)
        if (ops == null) {
            false
        } else {
            val zeros = ByteArray(65536)
            for (op in ops) {
                file.seek(op.pos)
                if (op.data != null) {
                    file.write(op.data, 0, op.len.toInt())
                } else {
                    var left = op.len
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        file.write(zeros, 0, n)
                        left -= n
                    }
                }
            }
            true
        }
    } catch (_: java.io.IOException) {
        false
    }

    private fun plan(io: Io): List<Op>? = try {
        val head = bytes(io, 0, 16)
        need(ascii(head, 4) == "ftyp" && ascii(head, 8) in BRANDS)
        val s = Scan()
        walk(io, 0, io.length, 0, s)
        need(s.moov)
        if (s.timed) {
            need(!s.fragmented)
            for (r in s.samples) need(s.mdats.any { r.pos >= it.body && r.pos + r.len <= it.end })
        }
        s.ops + s.samples
    } catch (_: RuntimeException) {
        null
    }

    private fun walk(io: Io, start: Long, end: Long, depth: Int, s: Scan) {
        need(depth <= MAX_DEPTH)
        boxes(io, start, end) { b ->
            when (b.type) {
                "udta", "meta" -> free(s.ops, b)
                "uuid" -> if (b.end - b.body >= 16 && bytes(io, b.body, 16).contentEquals(XMP_UUID)) free(s.ops, b)
                "moov", "trak", "mdia" -> {
                    if (b.type == "moov") s.moov = true
                    walk(io, b.body, b.end, depth + 1, s)
                    if (b.type == "trak") track(io, b, s)
                }
                "mvhd", "tkhd", "mdhd" -> {
                    val width = when (bytes(io, b.body, 1)[0].toInt()) {
                        0 -> 8L
                        1 -> 16L
                        else -> throw Refused()
                    }
                    need(b.end - b.body >= 4 + width)
                    s.ops.add(Op(b.body + 4, width, null))
                }
                "mdat" -> if (depth == 0) s.mdats.add(b)
                "mvex", "moof" -> s.fragmented = true
            }
        }
    }

    /** Queues zeros over a timed metadata track's samples and `mebx` key names; other tracks are left alone. */
    private fun track(io: Io, trak: Box, s: Scan) {
        val mdia = child(io, trak, "mdia") ?: return
        val handler = child(io, mdia, "hdlr")?.let {
            need(it.end - it.body >= 12) // version, flags and pre_defined come before handler_type
            ascii(bytes(io, it.body + 8, 4), 0)
        }
        val stbl = child(io, mdia, "minf")?.let { child(io, it, "stbl") }
        val stsd = stbl?.let { child(io, it, "stsd") }
        val keyOps = ArrayList<Op>()
        var listed = false
        if (stsd != null) {
            need(stsd.end - stsd.body >= 8) // version, flags and entry count come before the entries
            boxes(io, stsd.body + 8, stsd.end) { entry ->
                if (entry.type in TIMED_ENTRIES) listed = true
                if (entry.type == "mebx") mebxKeys(io, entry, keyOps)
            }
        }
        if (handler !in TIMED_HANDLERS && !listed) return
        s.timed = true
        s.ops += keyOps
        chunks(io, stbl ?: throw Refused(), s.samples)
    }

    /** Zeroes the key names of a `mebx` entry: `keys` holds one box per local key ID, each with `keyd` = namespace + name. */
    private fun mebxKeys(io: Io, entry: Box, ops: MutableList<Op>) {
        need(entry.end - entry.body >= 8) // reserved and data_reference_index come before the child boxes
        boxes(io, entry.body + 8, entry.end) { keys ->
            if (keys.type == "keys") boxes(io, keys.body, keys.end) { key ->
                boxes(io, key.body, key.end) { d -> if (d.type == "keyd" && d.end - d.body > 4) zero(ops, d.body + 4, d.end) }
            }
        }
    }

    /** Queues one zero op per chunk of samples; every sample must be mapped exactly once and fit in the file. */
    private fun chunks(io: Io, stbl: Box, out: MutableList<Op>) {
        val stsc = child(io, stbl, "stsc") ?: throw Refused()
        val stsz = child(io, stbl, "stsz")
        val stz2 = child(io, stbl, "stz2")
        val stco = child(io, stbl, "stco")
        val co64 = child(io, stbl, "co64")
        need((stsz == null) != (stz2 == null) && (stco == null) != (co64 == null))
        val map = table(io, stsc, 4, 32, fields = 3) // first_chunk (1-based), samples_per_chunk, description index
        val offsets = if (stco != null) table(io, stco, 4, 32) else table(io, co64!!, 4, 64)
        val fixed = if (stsz != null) field(io, stsz, 4) else 0L // stsz sample_size: one size for all, or 0
        val sizes = when {
            stz2 != null -> {
                val bits = (field(io, stz2, 4) and 0xFF).toInt() // 24 reserved bits, then field_size
                need(bits == 4 || bits == 8 || bits == 16)
                table(io, stz2, 8, bits)
            }
            fixed == 0L -> table(io, stsz!!, 8, 32)
            else -> null
        }
        val count = sizes?.count ?: field(io, stsz!!, 8)
        val runs = map.count / 3
        need(runs > 0 || offsets.count == 0L)
        for (r in 0 until runs) need(if (r == 0L) map[0] == 1L else map[r * 3] > map[r * 3 - 3])
        var run = 0L
        var sample = 0L
        for (c in 0 until offsets.count) {
            if (run + 1 < runs && map[(run + 1) * 3] == c + 1) run++
            val n = map[run * 3 + 1]
            need(n <= count - sample)
            var len = 0L
            if (sizes == null) len = Math.multiplyExact(n, fixed) else for (i in sample until sample + n) len += sizes[i]
            val pos = offsets[c]
            need(pos in 0..io.length && len <= io.length - pos)
            if (len > 0) out.add(Op(pos, len, null))
            sample += n
        }
        need(sample == count)
    }

    /** [count] big-endian entries of [bits] each (4, 8, 16, 32 or 64), read whole (tables are capped at [MAX_TABLE]). */
    private class Table(private val data: ByteArray, val count: Long, private val bits: Int) {
        operator fun get(i: Long): Long {
            if (bits == 4) {
                val b = data[(i / 2).toInt()].toInt() and 0xFF
                return (if (i % 2 == 0L) b ushr 4 else b and 0xF).toLong()
            }
            val n = bits / 8
            val o = (i * n).toInt()
            var v = 0L
            for (k in 0 until n) v = (v shl 8) or (data[o + k].toLong() and 0xFF)
            return v
        }
    }

    /** A table whose 32-bit entry count sits [countAt] bytes into [box]'s payload, with the entries right after it. */
    private fun table(io: Io, box: Box, countAt: Int, bits: Int, fields: Int = 1): Table {
        val count = field(io, box, countAt) * fields
        val start = box.body + countAt + 4
        val len = (count * bits + 7) / 8
        need(len <= MAX_TABLE && len <= box.end - start)
        return Table(bytes(io, start, len.toInt()), count, bits)
    }

    /** The unsigned 32-bit field [at] bytes into [box]'s payload. */
    private fun field(io: Io, box: Box, at: Int): Long {
        need(box.end - box.body >= at + 4)
        return u32(bytes(io, box.body + at, 4), 0)
    }

    /** The first child of [parent] with this [type], or null. */
    private fun child(io: Io, parent: Box, type: String): Box? {
        var found: Box? = null
        boxes(io, parent.body, parent.end) { if (found == null && it.type == type) found = it }
        return found
    }

    /** Calls [f] for each box in [start, end); throws [Refused] for a box that doesn't fit. */
    private fun boxes(io: Io, start: Long, end: Long, f: (Box) -> Unit) {
        var pos = start
        while (pos < end) {
            if (end - pos < 8) {
                // QuickTime may end a container with a 4-byte zero terminator; anything else is truncation.
                need(bytes(io, pos, (end - pos).toInt()).all { it.toInt() == 0 })
                return
            }
            val h = bytes(io, pos, 8)
            var size = u32(h, 0)
            var hdr = 8L
            if (size == 1L) {
                size = u64(bytes(io, pos + 8, 8))
                hdr = 16
            } else if (size == 0L) {
                size = end - pos
            }
            need(size >= hdr && size <= end - pos)
            f(Box(ascii(h, 4), pos, pos + hdr, pos + size))
            pos += size
        }
    }

    private fun free(ops: MutableList<Op>, b: Box) {
        ops.add(Op(b.pos + 4, 4, FREE))
        zero(ops, b.body, b.end)
    }

    private fun zero(ops: MutableList<Op>, from: Long, to: Long) {
        if (to > from) ops.add(Op(from, to - from, null))
    }

    private fun need(ok: Boolean) {
        if (!ok) throw Refused()
    }

    private fun bytes(io: Io, pos: Long, n: Int): ByteArray = io.read(pos, n) ?: throw Refused()

    private fun ascii(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
