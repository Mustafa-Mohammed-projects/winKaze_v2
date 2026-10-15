package com.kaze.winkaze

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits a big install.wim (> 4 GB) into install.swm, install2.swm, ... that fit on FAT32.
 * Works at resource level (no recompression), streaming straight from the ISO.
 */
object WimSplitter {
    private const val LIMIT = 4_000_000_000L
    private const val MASK = 0x00FFFFFFFFFFFFFFL

    private class Res(val size: Long, val flags: Int, val offset: Long, val orig: Long, val refs: Int, val hash: ByteArray) {
        var newOffset = 0L
    }

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun putHdr(bb: ByteBuffer, pos: Int, size: Long, flags: Int, off: Long, orig: Long) {
        bb.putLong(pos, size or (flags.toLong() shl 56))
        bb.putLong(pos + 8, off)
        bb.putLong(pos + 16, orig)
    }

    private fun getHdr(bb: ByteBuffer, pos: Int): Res {
        val w = bb.getLong(pos)
        return Res(w and MASK, (w ushr 56).toInt(), bb.getLong(pos + 8), bb.getLong(pos + 16), 0, ByteArray(20))
    }

    fun split(iso: IsoReader, wim: IsoNode): List<IsoNode> {
        val src = wim.pieces
        val h = iso.readRange(src, 0, 208)
        val hb = le(h)
        if (String(h, 0, 5, Charsets.US_ASCII) != "MSWIM") throw IOException("install.wim is not a valid WIM file.")
        if ((hb.getInt(16) and 0x8) != 0) throw IOException("install.wim is already a split WIM.")

        val tbl = getHdr(hb, 48)
        val xml = getHdr(hb, 72)
        val boot = getHdr(hb, 96)
        if ((tbl.flags and 4) != 0) throw IOException("Compressed WIM lookup table is not supported.")

        val tb = le(iso.readRange(src, tbl.offset, tbl.size))
        val count = (tbl.size / 50).toInt()
        val all = ArrayList<Res>(count)
        for (i in 0 until count) {
            val p = i * 50
            val r = getHdr(tb, p)
            val hash = ByteArray(20)
            for (k in 0 until 20) hash[k] = tb.get(p + 30 + k)
            all.add(Res(r.size, r.flags, r.offset, r.orig, tb.getInt(p + 26), hash))
        }
        val xmlBytes = iso.readRange(src, xml.offset, xml.size)

        val meta = all.filter { (it.flags and 2) != 0 }
        val data = all.filter { (it.flags and 2) == 0 }.sortedBy { it.offset }

        val fixed = 208L + xmlBytes.size
        val parts = ArrayList<List<Res>>()
        var cur = ArrayList<Res>(meta)
        var curSize = fixed + meta.sumOf { it.size + 50 }
        for (r in data) {
            if (r.size + 50 + fixed > LIMIT) throw IOException("install.wim has a single resource larger than 4 GB.")
            if (curSize + r.size + 50 > LIMIT) { parts.add(cur); cur = ArrayList(); curSize = fixed }
            cur.add(r); curSize += r.size + 50
        }
        parts.add(cur)

        val out = ArrayList<IsoNode>()
        parts.forEachIndexed { idx, list ->
            var cursor = 208L
            for (r in list) { r.newOffset = cursor; cursor += r.size }
            val xmlOff = cursor; cursor += xmlBytes.size
            val tblOff = cursor

            val tArr = ByteArray(list.size * 50)
            val tBuf = le(tArr)
            list.forEachIndexed { i, r ->
                val p = i * 50
                putHdr(tBuf, p, r.size, r.flags, r.newOffset, r.orig)
                tBuf.putShort(p + 24, (idx + 1).toShort())
                tBuf.putInt(p + 26, r.refs)
                System.arraycopy(r.hash, 0, tArr, p + 30, 20)
            }

            val hdr = h.copyOf()
            val b = le(hdr)
            b.putInt(16, hb.getInt(16) or 0x8)
            b.putShort(40, (idx + 1).toShort())
            b.putShort(42, parts.size.toShort())
            putHdr(b, 48, tArr.size.toLong(), tbl.flags, tblOff, tArr.size.toLong())
            putHdr(b, 72, xmlBytes.size.toLong(), xml.flags, xmlOff, xml.orig)
            val bootRes = if (idx == 0 && boot.size > 0) list.firstOrNull { it.offset == boot.offset } else null
            if (bootRes != null) putHdr(b, 96, boot.size, boot.flags, bootRes.newOffset, boot.orig)
            else for (k in 96 until 120) hdr[k] = 0
            if (idx != 0) b.putInt(120, 0)
            for (k in 124 until 148) hdr[k] = 0 // no integrity table

            val pieces = ArrayList<Piece>()
            pieces.add(Piece(hdr))
            for (r in list) pieces.addAll(iso.slice(src, r.offset, r.size))
            pieces.add(Piece(xmlBytes))
            pieces.add(Piece(tArr))
            out.add(IsoNode(if (idx == 0) "install.swm" else "install${idx + 1}.swm", false, pieces))
        }
        return out
    }
}
