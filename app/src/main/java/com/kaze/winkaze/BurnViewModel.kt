package com.kaze.winkaze

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream

enum class Phase { IDLE, BURNING, DONE, ERROR }

enum class Mode { WINDOWS, RAW }

data class UsbItem(val device: UsbDevice, val name: String, val hasPermission: Boolean)

data class UiState(
    val isoUri: Uri? = null,
    val isoName: String? = null,
    val isoSize: Long = 0,
    val devices: List<UsbItem> = emptyList(),
    val selectedId: Int? = null,
    val selectedName: String? = null,
    val selectedCapacity: Long = 0,
    val phase: Phase = Phase.IDLE,
    val progress: Float = 0f,
    val speedMBs: Float = 0f,
    val etaSec: Long = 0,
    val message: String? = null,
    val mode: Mode = Mode.WINDOWS,
    val stage: String = ""
) {
    val canBurn: Boolean
        get() = isoUri != null && selectedId != null && phase != Phase.BURNING
}

class BurnViewModel(app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var storage: UsbMassStorage? = null
    private var job: Job? = null
    private var pendingId: Int? = null

    fun refreshDevices() {
        val list = usb.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }.map {
            UsbItem(it, it.productName ?: "USB Drive (${"%04X".format(it.vendorId)}:${"%04X".format(it.productId)})", usb.hasPermission(it))
        }
        _state.update { s ->
            val stillThere = list.any { it.device.deviceId == s.selectedId }
            if (!stillThere && s.phase != Phase.BURNING) {
                closeStorage()
                s.copy(devices = list, selectedId = null, selectedName = null, selectedCapacity = 0)
            } else s.copy(devices = list)
        }
        pendingId?.let { id -> list.find { it.device.deviceId == id && it.hasPermission }?.let { pendingId = null; openDevice(it) } }
    }

    fun selectDevice(item: UsbItem) {
        if (_state.value.phase == Phase.BURNING) return
        if (item.hasPermission) openDevice(item) else {
            pendingId = item.device.deviceId
            val pi = PendingIntent.getBroadcast(
                getApplication(), 0,
                Intent(ACTION_USB_PERMISSION).setPackage(getApplication<Application>().packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(item.device, pi)
        }
    }

    private fun openDevice(item: UsbItem) {
        viewModelScope.launch {
            closeStorage()
            val result = withContext(Dispatchers.IO) {
                val conn = usb.openDevice(item.device) ?: return@withContext null
                val st = UsbMassStorage(item.device, conn)
                if (st.open()) st else { st.close(); null }
            }
            if (result == null) {
                _state.update { it.copy(message = "Could not open this drive. Re-plug it and try again.", phase = Phase.ERROR) }
            } else {
                storage = result
                _state.update {
                    it.copy(selectedId = item.device.deviceId, selectedName = item.name,
                        selectedCapacity = result.capacityBytes, phase = Phase.IDLE, message = null)
                }
            }
        }
    }

    fun pickIso(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        var name = "image.iso"; var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0) size = c.getLong(s)
            }
        }
        _state.update { it.copy(isoUri = uri, isoName = name, isoSize = size, phase = Phase.IDLE, progress = 0f, message = null) }
    }

    fun setMode(m: Mode) { if (_state.value.phase != Phase.BURNING) _state.update { it.copy(mode = m) } }

    fun startBurn() {
        val s = _state.value
        val st = storage ?: return
        val uri = s.isoUri ?: return
        if (s.mode == Mode.RAW && s.isoSize > st.capacityBytes) {
            _state.update { it.copy(phase = Phase.ERROR, message = "The ISO is larger than the USB drive.") }
            return
        }
        _state.update { it.copy(phase = Phase.BURNING, progress = 0f, speedMBs = 0f, etaSec = 0, message = null, stage = "Preparing…") }
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = coroutineContext
                if (s.mode == Mode.RAW) {
                    _state.update { it.copy(stage = "Writing image…") }
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot read the ISO file.")
                    input.use { burn(it, st, s.isoSize) }
                } else {
                    windowsBurn(uri, st) { ctx.ensureActive() }
                }
                st.sync()
                _state.update { it.copy(phase = Phase.DONE, progress = 1f, message = "Done! You can safely remove the drive.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(phase = Phase.IDLE, message = "Cancelled. The drive content is incomplete.") }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, message = e.message ?: "Write failed.") }
            }
        }
    }

    private fun windowsBurn(uri: Uri, st: UsbMassStorage, check: () -> Unit) {
        val cr = getApplication<Application>().contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Cannot read the ISO file.")
        pfd.use { fd ->
            val iso = IsoReader(FileInputStream(fd.fileDescriptor).channel)
            _state.update { it.copy(stage = "Reading ISO…") }
            val root = try { iso.parse() } catch (e: java.io.IOException) {
                throw IllegalStateException("Cannot read this ISO (${e.message}). Copy it to internal storage and try again.")
            }
            prepareWindowsTree(root, iso)
            val t0 = System.nanoTime()
            Fat32Writer(st, iso, check) { done, total, stage ->
                val sec = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.001)
                val speed = (done / 1048576.0 / sec).toFloat()
                val eta = if (speed > 0 && total > done) ((total - done) / 1048576.0 / speed).toLong() else 0
                _state.update { it.copy(progress = if (total > 0) done.toFloat() / total else 0f, speedMBs = speed, etaSec = eta, stage = stage) }
            }.run(root)
        }
    }

    private fun prepareWindowsTree(root: IsoNode, iso: IsoReader) {
        val sources = root.children.firstOrNull { it.isDir && it.name.equals("sources", true) }
            ?: throw IllegalStateException("Not a supported Windows ISO: no \"sources\" folder found (UDF-only images are not supported). Use Raw mode for other ISOs.")
        val limit = 4294967295L
        val wim = sources.children.firstOrNull { !it.isDir && it.name.equals("install.wim", true) }
        if (wim != null && wim.size >= limit) {
            _state.update { it.copy(stage = "Splitting install.wim…") }
            sources.children.remove(wim)
            sources.children.addAll(WimSplitter.split(iso, wim))
        }
        fun walk(n: IsoNode) {
            for (c in n.children) {
                if (c.isDir) walk(c)
                else if (c.size >= limit) throw IllegalStateException("\"${c.name}\" is larger than 4 GB and cannot be stored on FAT32.")
            }
        }
        walk(root)
    }

    private suspend fun burn(input: InputStream, st: UsbMassStorage, total: Long) {
        val bs = st.blockSize
        val buf = ByteArray(256 * 1024)
        var lba = 0L; var done = 0L
        val t0 = System.nanoTime(); var last = t0
        while (true) {
            currentCoroutineContext().ensureActive()
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            if (n <= 0) break
            val padded = ((n + bs - 1) / bs) * bs
            if (padded > n) java.util.Arrays.fill(buf, n, padded, 0)
            if (!st.write(lba, buf, padded)) throw IllegalStateException("Write error at ${done / (1024 * 1024)} MB. Check the drive.")
            lba += padded / bs; done += n
            val now = System.nanoTime()
            if (now - last > 300_000_000L || n < buf.size) {
                last = now
                val sec = (now - t0) / 1e9
                val speed = (done / 1048576.0 / sec).toFloat()
                val eta = if (speed > 0 && total > 0) ((total - done) / 1048576.0 / speed).toLong() else 0
                _state.update { it.copy(progress = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f, speedMBs = speed, etaSec = eta) }
            }
            if (n < buf.size) break
        }
        st.sync()
    }

    fun cancel() { job?.cancel() }

    fun dismissMessage() = _state.update { if (it.phase == Phase.BURNING) it else it.copy(phase = Phase.IDLE, message = null, progress = 0f) }

    private fun closeStorage() { storage?.close(); storage = null }

    override fun onCleared() { job?.cancel(); closeStorage() }

    companion object { const val ACTION_USB_PERMISSION = "com.kaze.winkaze.USB_PERMISSION" }
}
