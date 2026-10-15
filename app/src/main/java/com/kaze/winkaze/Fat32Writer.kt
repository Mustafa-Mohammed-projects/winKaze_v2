package com.kaze.winkaze

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Partitions (MBR), formats (FAT32) and fills a USB drive from an [IsoNode] tree. UEFI boot only. */
class Fat32Writer(
    private val st: UsbMassStorage,
    private val iso: IsoReader,
    private val check: () -> Unit,
    private val onProgress: (done: Long, total: Long, stage: String) -> Unit
) {
    private val bs = st.blockSize
    private var spc = 0
    private var clusterBytes = 0
    private var next = 2
    private val order = ArrayList<IsoNode>()
    private lateinit var root: IsoNode
    private var done = 0L
    private var total = 0L
    private var lastTick = 0L

    fun run(rootNode: IsoNode) {
        root = rootNode
        onProgress(0, 0, "Preparing drive…")

        val startLba = (1L shl 20) / bs
        val tail = (1L shl 20) / bs
        val partBlocks = st.blockCount - startLba - tail
        if (partBlocks <= 0 || partBlocks > 0xFFFFFFFFL) throw IOException("Unsupported drive size.")
        val cap = partBlocks * bs
        clusterBytes = max(
            bs, when {
                cap <= (8L shl 30) -> 4096
                cap <= (16L shl 30) -> 8192
                cap <= (32L shl 30) -> 16384
                else -> 32768
            }
        )
        spc = clusterBytes / bs
        val reserved = 32
        val t2 = (bs / 2L * spc + 2) / 2
        val fatSz = (partBlocks - reserved + t2 - 1) / t2
        val dataStart = reserved + 2 * fatSz
        val clusters = ((partBlocks - dataStart) / spc).toInt()
        if (clusters < 65525) throw IOException("The drive is too small for FAT32.")

        assignNames(root)
        total = sumFiles(root)
        next = 2
        allocate(root)
        if (next - 2 > clusters) throw IOException("The USB drive is too small for this ISO.")

        val fat = IntArray(clusters + 2)
        fat[0] = 0x0FFFFFF8; fat[1] = 0x0FFFFFFF
        for (n in order) if (n.clusterCount > 0) {
            for (c in n.cluster until n.cluster + n.clusterCount - 1) fat[c] = c + 1
            fat[n.cluster + n.clusterCount - 1] = 0x0FFFFFFF
        }

        // Clear the gap after the MBR (old GPT) and the tail of the disk.
        zeros(1, startLba - 1)
        zeros(st.blockCount - tail, tail)

        // Data region: directories + files, strictly sequential.
        val seq = Seq(startLba + dataStart)
        val tmp = ByteArray(256 * 1024)
        for (n in order) {
            check()
            if (n.isDir) {
                seq.append(dirBytes(n), 0, n.clusterCount * clusterBytes)
            } else if (n.clusterCount > 0) {
                for (p in n.pieces) {
                    if (p.bytes != null) {
                        seq.append(p.bytes, 0, p.bytes.size)
                        done += p.bytes.size
                    } else {
                        var pos = 0L
                        while (pos < p.length) {
                            check()
                            val k = min(tmp.size.toLong(), p.length - pos).toInt()
                            iso.readAt(p.offset + pos, tmp, 0, k)
                            seq.append(tmp, 0, k)
                            pos += k; done += k
                            tick(n.name)
                        }
                    }
                }
                val pad = n.clusterCount.toLong() * clusterBytes - n.size
                if (pad > 0) seq.append(ByteArray(pad.toInt()), 0, pad.toInt())
            }
        }
        seq.flush()

        onProgress(total, total, "Finalizing…")
        // FAT copies
        for (copy in 0..1) writeFat(fat, startLba + reserved + copy * fatSz, fatSz)
        // Boot sector, FSInfo and backups (written last so an interrupted drive is not mountable)
        writeReserved(startLba, partBlocks, fatSz, reserved, clusters - (next - 2), next)
        writeMbr(startLba, partBlocks)
    }

    // ---------- naming ----------
    private val badChars = "\"*+,/:;<=>?[\\]|"

    private fun fits83(name: String): Boolean {
        val i = name.lastIndexOf('.')
        val base = if (i < 0) name else name.substring(0, i)
        val ext = if (i < 0) "" else name.substring(i + 1)
        if (base.isEmpty() || base.length > 8 || ext.length > 3 || base.contains('.')) return false
        if ((base + ext).any { it.code <= 32 || it.code > 126 || badChars.indexOf(it) >= 0 }) return false
        return (base == base.lowercase() || base == base.uppercase()) && (ext == ext.lowercase() || ext == ext.uppercase())
    }

    private fun assignNames(dir: IsoNode) {
        val used = HashSet<String>()
        for (c in dir.children) {
            c.parent = dir
            makeName(c, used)
            if (c.isDir) assignNames(c)
        }
    }

    private fun makeName(c: IsoNode, used: HashSet<String>) {
        val name = c.name
        if (fits83(name)) {
            val i = name.lastIndexOf('.')
            val base = if (i < 0) name else name.substring(0, i)
            val ext = if (i < 0) "" else name.substring(i + 1)
            val key = base.uppercase().padEnd(8) + ext.uppercase().padEnd(3)
            if (used.add(key)) {
                c.short = key.toByteArray(Charsets.US_ASCII)
                c.nt = (if (base.any { it.isLowerCase() }) 0x08 else 0) or (if (ext.any { it.isLowerCase() }) 0x10 else 0)
                c.lfn = emptyList()
                return
            }
        }
        fun clean(s: String) = s.uppercase().map { ch ->
            if (ch.code in 33..126 && ".$badChars".indexOf(ch) < 0) ch else '_'
        }.joinToString("")
        val i = name.lastIndexOf('.')
        val baseRaw = (if (i <= 0) name else name.substring(0, i)).replace(" ", "").replace(".", "")
        val ext = clean(if (i <= 0) "" else name.substring(i + 1).replace(" ", "")).take(3)
        val base = clean(baseRaw)
        var n = 1
        while (true) {
            val suf = "~$n"
            val key = (base.take(8 - suf.length) + suf).padEnd(8) + ext.padEnd(3)
            if (used.add(key)) { c.short = key.toByteArray(Charsets.US_ASCII); break }
            n++
        }
        c.nt = 0
        c.lfn = lfnEntries(name, c.short)
    }

    private fun lfnEntries(name: String, short: ByteArray): List<ByteArray> {
        var sum = 0
        for (b in short) sum = (((sum and 1) shl 7) + (sum shr 1) + (b.toInt() and 0xFF)) and 0xFF
        val chars = name.toCharArray()
        val n = (chars.size + 12) / 13
        val list = ArrayList<ByteArray>()
        for (seq in n downTo 1) {
            val e = ByteArray(32)
            e[0] = (seq or (if (seq == n) 0x40 else 0)).toByte()
            e[11] = 0x0F
            e[13] = sum.toByte()
            for (k in 0 until 13) {
                val idx = (seq - 1) * 13 + k
                val ch = if (idx < chars.size) chars[idx].code else if (idx == chars.size) 0 else 0xFFFF
                val pos = when { k < 5 -> 1 + 2 * k; k < 11 -> 14 + 2 * (k - 5); else -> 28 + 2 * (k - 11) }
                e[pos] = ch.toByte(); e[pos + 1] = (ch shr 8).toByte()
            }
            list.add(e)
        }
        return list
    }

    // ---------- layout ----------
    private fun sumFiles(n: IsoNode): Long = if (n.isDir) n.children.sumOf { sumFiles(it) } else n.size

    private fun entryCount(n: IsoNode): Int =
        (if (n.parent == null) 0 else 2) + n.children.sumOf { it.lfn.size + 1 }

    private fun allocate(n: IsoNode) {
        n.clusterCount = if (n.isDir) max(1, ((entryCount(n) * 32L + clusterBytes - 1) / clusterBytes).toInt())
        else ((n.size + clusterBytes - 1) / clusterBytes).toInt()
        n.cluster = if (n.clusterCount == 0) 0 else next
        next += n.clusterCount
        order.add(n)
        if (n.isDir) n.children.forEach { allocate(it) }
    }

    private fun entry(short: ByteArray, nt: Int, attr: Int, cluster: Int, size: Long): ByteArray {
        val e = ByteArray(32)
        val bb = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy(short, 0, e, 0, 11)
        e[11] = attr.toByte(); e[12] = nt.toByte()
        bb.putShort(16, DATE.toShort()); bb.putShort(18, DATE.toShort())
        bb.putShort(20, (cluster ushr 16).toShort())
        bb.putShort(24, DATE.toShort())
        bb.putShort(26, (cluster and 0xFFFF).toShort())
        bb.putInt(28, size.toInt())
        return e
    }

    private fun dirBytes(dir: IsoNode): ByteArray {
        val out = ByteArray(dir.clusterCount * clusterBytes)
        var p = 0
        fun put(e: ByteArray) { System.arraycopy(e, 0, out, p, 32); p += 32 }
        if (dir.parent != null) {
            put(entry(".          ".toByteArray(), 0, 0x10, dir.cluster, 0))
            val par = dir.parent!!
            put(entry("..         ".toByteArray(), 0, 0x10, if (par.parent == null) 0 else par.cluster, 0))
        }
        for (c in dir.children) {
            for (e in c.lfn) put(e)
            put(entry(c.short, c.nt, if (c.isDir) 0x10 else 0x20, c.cluster, if (c.isDir) 0 else c.size))
        }
        return out
    }

    // ---------- raw writing ----------
    private inner class Seq(var lba: Long) {
        val buf = ByteArray(256 * 1024)
        var len = 0
        fun append(b: ByteArray, off: Int, n: Int) {
            var o = off; var left = n
            while (left > 0) {
                val k = min(left, buf.size - len)
                System.arraycopy(b, o, buf, len, k)
                len += k; o += k; left -= k
                if (len == buf.size) flush()
            }
        }
        fun flush() {
            if (len == 0) return
            val padded = ((len + bs - 1) / bs) * bs
            if (padded > len) java.util.Arrays.fill(buf, len, padded, 0)
            if (!st.write(lba, buf, padded)) throw IOException("Write error. Check the drive and try again.")
            lba += padded / bs; len = 0
        }
    }

    private fun writeAt(lba0: Long, data: ByteArray) {
        var off = 0; var lba = lba0
        while (off < data.size) {
            val n = min(256 * 1024, data.size - off)
            val chunk = if (off == 0 && n == data.size) data else data.copyOfRange(off, off + n)
            if (!st.write(lba, chunk, n)) throw IOException("Write error. Check the drive and try again.")
            off += n; lba += n / bs
        }
    }

    private fun zeros(lba0: Long, blocks: Long) {
        val z = ByteArray(256 * 1024)
        var lba = lba0; var left = blocks
        while (left > 0) {
            val n = min(left, (z.size / bs).toLong()).toInt()
            if (!st.write(lba, z, n * bs)) throw IOException("Write error. Check the drive and try again.")
            lba += n; left -= n
        }
    }

    private fun writeFat(fat: IntArray, lba0: Long, fatSz: Long) {
        val chunkSectors = (256 * 1024 / bs).toLong()
        var s = 0L
        while (s < fatSz) {
            check()
            val n = min(chunkSectors, fatSz - s).toInt()
            val b = ByteArray(n * bs)
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val first = s * bs / 4
            for (i in 0 until n * bs / 4) {
                val idx = first + i
                if (idx < fat.size) bb.putInt(i * 4, fat[idx.toInt()])
            }
            if (!st.write(lba0 + s, b, b.size)) throw IOException("Write error. Check the drive and try again.")
            s += n
        }
    }

    private fun writeReserved(startLba: Long, partBlocks: Long, fatSz: Long, reserved: Int, free: Int, nextFree: Int) {
        val b = ByteArray(reserved * bs)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray().copyInto(b, 3)
        bb.putShort(11, bs.toShort()); b[13] = spc.toByte()
        bb.putShort(14, reserved.toShort()); b[16] = 2
        b[21] = 0xF8.toByte()
        bb.putShort(24, 63); bb.putShort(26, 255)
        bb.putInt(28, startLba.toInt()); bb.putInt(32, partBlocks.toInt()); bb.putInt(36, fatSz.toInt())
        bb.putInt(44, 2); bb.putShort(48, 1); bb.putShort(50, 6)
        b[64] = 0x80.toByte(); b[66] = 0x29; bb.putInt(67, 0x4B415A45)
        "WINKAZE    ".toByteArray().copyInto(b, 71)
        "FAT32   ".toByteArray().copyInto(b, 82)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        if (bs > 512) { b[bs - 2] = 0x55; b[bs - 1] = 0xAA.toByte() }

        val fs = ByteArray(bs)
        val fb = ByteBuffer.wrap(fs).order(ByteOrder.LITTLE_ENDIAN)
        fb.putInt(0, 0x41615252); fb.putInt(484, 0x61417272)
        fb.putInt(488, free); fb.putInt(492, nextFree); fb.putInt(508, 0xAA550000.toInt())
        System.arraycopy(fs, 0, b, bs, bs)
        System.arraycopy(fs, 0, b, 7 * bs, bs)
        System.arraycopy(b, 0, b, 6 * bs, bs)
        writeAt(startLba, b)
    }

    private fun writeMbr(startLba: Long, partBlocks: Long) {
        val m = ByteArray(bs)
        val mb = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        mb.putInt(440, 0x57494E4B)
        m[446] = 0x80.toByte()
        m[447] = 0xFE.toByte(); m[448] = 0xFF.toByte(); m[449] = 0xFF.toByte()
        m[450] = 0x0C
        m[451] = 0xFE.toByte(); m[452] = 0xFF.toByte(); m[453] = 0xFF.toByte()
        mb.putInt(454, startLba.toInt()); mb.putInt(458, partBlocks.toInt())
        m[510] = 0x55; m[511] = 0xAA.toByte()
        writeAt(0, m)
    }

    private fun tick(name: String) {
        val now = System.nanoTime()
        if (now - lastTick > 300_000_000L) { lastTick = now; onProgress(done, total, "Copying: $name") }
    }

    companion object { private const val DATE = 23073 }
}
