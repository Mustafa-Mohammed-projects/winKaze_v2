package com.kaze.winkaze

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** A run of bytes: either in-memory [bytes] or a range of the ISO file. */
class Piece(val bytes: ByteArray?, val offset: Long, val length: Long) {
    constructor(b: ByteArray) : this(b, 0L, b.size.toLong())
}

class IsoNode(val name: String, val isDir: Boolean, var pieces: List<Piece>) {
    val children = ArrayList<IsoNode>()
    val size: Long get() = pieces.sumOf { it.length }

    // Filled in by Fat32Writer
    var parent: IsoNode? = null
    var cluster = 0
    var clusterCount = 0
    var short = ByteArray(11)
    var nt = 0
    var lfn: List<ByteArray> = emptyList()
}

/** Reads the ISO9660 (+Joliet, multi-extent) file tree of an ISO through random access. */
class IsoReader(private val ch: FileChannel) {

    fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var done = 0
        while (done < len) {
            val r = ch.read(bb, pos + done)
            if (r < 0) throw IOException("Unexpected end of ISO file.")
            done += r
        }
    }

    fun slice(src: List<Piece>, off: Long, len: Long): List<Piece> {
        val out = ArrayList<Piece>()
        var skip = off
        var left = len
        for (p in src) {
            if (left <= 0) break
            if (skip >= p.length) { skip -= p.length; continue }
            val take = minOf(p.length - skip, left)
            out.add(Piece(null, p.offset + skip, take))
            left -= take; skip = 0
        }
        return out
    }

    fun readRange(src: List<Piece>, off: Long, len: Long): ByteArray {
        val out = ByteArray(len.toInt())
        var pos = 0
        for (p in slice(src, off, len)) {
            readAt(p.offset, out, pos, p.length.toInt())
            pos += p.length.toInt()
        }
        return out
    }

    fun parse(): IsoNode {
        val b = ByteArray(2048)
        var primary: ByteArray? = null
        var joliet: ByteArray? = null
        var sec = 16L
        while (sec < 100) {
            readAt(sec * 2048, b, 0, 2048)
            if (String(b, 1, 5, Charsets.US_ASCII) != "CD001") break
            val type = b[0].toInt() and 0xFF
            if (type == 255) break
            if (type == 1 && primary == null) primary = b.copyOf()
            if (type == 2 && b[88] == 0x25.toByte() && b[89] == 0x2F.toByte() &&
                (b[90] == 0x40.toByte() || b[90] == 0x43.toByte() || b[90] == 0x45.toByte())
            ) joliet = b.copyOf()
            sec++
        }
        val vd = joliet ?: primary ?: throw IOException("Not a valid ISO file.")
        val bb = ByteBuffer.wrap(vd).order(ByteOrder.LITTLE_ENDIAN)
        val rootLba = bb.getInt(156 + 2).toLong() and 0xFFFFFFFFL
        val rootSize = bb.getInt(156 + 10).toLong() and 0xFFFFFFFFL
        val root = IsoNode("", true, emptyList())
        root.children.addAll(readDir(rootLba, rootSize, joliet != null, 0))
        return root
    }

    private fun readDir(lba: Long, size: Long, joliet: Boolean, depth: Int): List<IsoNode> {
        if (depth > 32) throw IOException("ISO directory tree is too deep.")
        val data = ByteArray(size.toInt())
        readAt(lba * 2048, data, 0, data.size)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<IsoNode>()
        var pending = ArrayList<Piece>()
        var pos = 0
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) { pos = (pos / 2048 + 1) * 2048; continue }
            val flags = data[pos + 25].toInt() and 0xFF
            val nlen = data[pos + 32].toInt() and 0xFF
            val ext = bb.getInt(pos + 2).toLong() and 0xFFFFFFFFL
            val sz = bb.getInt(pos + 10).toLong() and 0xFFFFFFFFL
            val nameBytes = data.copyOfRange(pos + 33, pos + 33 + nlen)
            pos += len
            if (nlen == 1 && (nameBytes[0] == 0.toByte() || nameBytes[0] == 1.toByte())) continue
            var name = if (joliet) String(nameBytes, Charsets.UTF_16BE) else String(nameBytes, Charsets.ISO_8859_1)
            name = name.substringBefore(';')
            if (!joliet && name.endsWith(".")) name = name.dropLast(1)
            if (flags and 2 != 0) {
                val d = IsoNode(name, true, emptyList())
                d.children.addAll(readDir(ext, sz, joliet, depth + 1))
                out.add(d)
            } else {
                pending.add(Piece(null, ext * 2048, sz))
                if (flags and 0x80 == 0) { out.add(IsoNode(name, false, pending)); pending = ArrayList() }
            }
        }
        return out
    }
}
