package app.encrogram.core.internal.media.prep

import java.io.RandomAccessFile

/**
 * Neutralizes metadata in an MP4 or MOV file in place (docs/media-plan.md header amendment 2): no remux, no re-encode,
 * so the file size and every box offset stay the same. Pure Kotlin.
 *
 * Handled:
 * - `udta` and `meta` boxes (at the top level and inside moov, trak, mdia) and the XMP `uuid` box
 *   BE7ACFCB-97A9-42E8-9C71-999491E3AFAC become type `free` with a zeroed payload;
 * - creation and modification times in `mvhd`, `tkhd` and `mdhd` are zeroed (version 0: 4 bytes each, version 1: 8).
 * `mdat` and `moof` payloads are never read or written. Box walking supports 64-bit largesize and size 0 (to the end).
 *
 * The whole structure is validated first and writes happen only afterwards, so a refusal (false) changes nothing.
 * Only MP4 and MOV brands are accepted.
 */
internal object Mp4Meta {
    private val BRANDS = setOf("isom", "iso2", "mp41", "mp42", "avc1", "qt  ", "M4V ", "M4A ", "3gp4", "3gp5", "3gp6")
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )
    private const val MAX_DEPTH = 6

    private interface Io {
        val length: Long
        fun read(pos: Long, n: Int): ByteArray?
    }

    /** len bytes at pos: zeros when [data] is null. */
    private class Op(val pos: Long, val len: Long, val data: ByteArray?)

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
        val head = io.read(0, 16)
        if (head == null || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp" ||
            String(head, 8, 4, Charsets.ISO_8859_1) !in BRANDS
        ) {
            null
        } else {
            val ops = ArrayList<Op>()
            val seen = BooleanArray(1)
            if (walk(io, 0, io.length, 0, ops, seen) && seen[0]) ops else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun walk(io: Io, start: Long, end: Long, depth: Int, ops: MutableList<Op>, moovSeen: BooleanArray): Boolean {
        if (depth > MAX_DEPTH) return false
        var pos = start
        while (pos < end) {
            if (end - pos < 8) {
                // QuickTime may end a container with a 4-byte zero terminator; anything else is truncation.
                val rest = io.read(pos, (end - pos).toInt()) ?: return false
                return rest.all { it.toInt() == 0 }
            }
            val h = io.read(pos, 8) ?: return false
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8L
            if (size == 1L) {
                val l = io.read(pos + 8, 8) ?: return false
                size = u64(l)
                hdr = 16
                if (size < 0) return false
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < hdr || size > end - pos) return false
            val boxEnd = pos + size
            when (type) {
                "udta", "meta" -> ops.add(Op(pos + 4, 4, "free".toByteArray(Charsets.ISO_8859_1))).also { zero(ops, pos + hdr, boxEnd) }
                "uuid" -> if (size >= hdr + 16) {
                    val id = io.read(pos + hdr, 16) ?: return false
                    if (id.contentEquals(XMP_UUID)) {
                        ops.add(Op(pos + 4, 4, "free".toByteArray(Charsets.ISO_8859_1)))
                        zero(ops, pos + hdr, boxEnd)
                    }
                }
                "moov", "trak", "mdia" -> {
                    if (type == "moov") moovSeen[0] = true
                    if (!walk(io, pos + hdr, boxEnd, depth + 1, ops, moovSeen)) return false
                }
                "mvhd", "tkhd", "mdhd" -> {
                    val v = io.read(pos + hdr, 1) ?: return false
                    val width = when (v[0].toInt()) {
                        0 -> 8L
                        1 -> 16L
                        else -> return false
                    }
                    if (size < hdr + 4 + width) return false
                    ops.add(Op(pos + hdr + 4, width, null))
                }
            }
            pos = boxEnd
        }
        return true
    }

    private fun zero(ops: MutableList<Op>, from: Long, to: Long) {
        if (to > from) ops.add(Op(from, to - from, null))
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
