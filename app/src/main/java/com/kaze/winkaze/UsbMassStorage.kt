package com.kaze.winkaze

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal USB Mass Storage (Bulk-Only Transport + SCSI) driver on top of Android's USB Host API. */
class UsbMassStorage(private val device: UsbDevice, private val conn: UsbDeviceConnection) {
    private var inEp: UsbEndpoint? = null
    private var outEp: UsbEndpoint? = null
    private var iface: UsbInterface? = null
    private var tag = 1

    var blockSize = 512
        private set
    var blockCount = 0L
        private set
    val capacityBytes: Long get() = blockCount * blockSize

    fun open(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE || itf.interfaceProtocol != 0x50) continue
            var ein: UsbEndpoint? = null
            var eout: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) ein = ep else eout = ep
                }
            }
            if (ein != null && eout != null && conn.claimInterface(itf, true)) {
                inEp = ein; outEp = eout; iface = itf
                return waitReady() && readCapacity()
            }
        }
        return false
    }

    private fun command(cb: ByteArray, data: ByteArray?, len: Int, read: Boolean): Boolean {
        val cbw = ByteArray(31)
        ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355); putInt(tag++); putInt(len)
        }
        cbw[12] = if (read && len > 0) 0x80.toByte() else 0
        cbw[14] = cb.size.toByte()
        System.arraycopy(cb, 0, cbw, 15, cb.size)

        if (conn.bulkTransfer(outEp, cbw, cbw.size, TIMEOUT) != cbw.size) return false

        if (data != null && len > 0) {
            var off = 0
            while (off < len) {
                val n = minOf(SLICE, len - off)
                val r = conn.bulkTransfer(if (read) inEp else outEp, data, off, n, TIMEOUT)
                if (r <= 0) return false
                off += r
            }
        }
        val csw = ByteArray(13)
        var r = conn.bulkTransfer(inEp, csw, csw.size, TIMEOUT)
        if (r != 13) r = conn.bulkTransfer(inEp, csw, csw.size, TIMEOUT)
        if (r != 13) return false
        val sig = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).int
        return sig == 0x53425355 && csw[12].toInt() == 0
    }

    private fun waitReady(): Boolean {
        repeat(10) {
            if (command(ByteArray(6), null, 0, false)) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun readCapacity(): Boolean {
        val buf = ByteArray(8)
        val cb = ByteArray(10).also { it[0] = 0x25 }
        if (!command(cb, buf, 8, true)) return false
        val bb = ByteBuffer.wrap(buf) // big-endian
        val last = bb.int.toLong() and 0xFFFFFFFFL
        blockSize = bb.int
        blockCount = last + 1
        return blockSize > 0
    }

    /** Writes [len] bytes (multiple of blockSize) starting at block [lba]. */
    fun write(lba: Long, data: ByteArray, len: Int): Boolean {
        val blocks = len / blockSize
        val cb = ByteArray(10)
        cb[0] = 0x2A
        ByteBuffer.wrap(cb).order(ByteOrder.BIG_ENDIAN).apply {
            position(2); putInt(lba.toInt()); position(7); putShort(blocks.toShort())
        }
        return command(cb, data, len, false) || command(cb, data, len, false)
    }

    fun sync(): Boolean = command(ByteArray(10).also { it[0] = 0x35 }, null, 0, false)

    fun close() {
        try { iface?.let { conn.releaseInterface(it) } } catch (_: Exception) {}
        conn.close()
    }

    companion object {
        private const val TIMEOUT = 30_000
        private const val SLICE = 16 * 1024
    }
}
