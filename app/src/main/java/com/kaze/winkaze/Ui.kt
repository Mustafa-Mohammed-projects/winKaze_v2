package com.kaze.winkaze

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Bg = Color(0xFF000000)
private val Card = Color(0xFF101318)
private val CardBorder = Color(0xFF1E2430)
private val Blue = Color(0xFF2D8CFF)
private val Cyan = Color(0xFF00D1FF)
private val Green = Color(0xFF2ECC71)
private val Red = Color(0xFFFF5252)
private val TextDim = Color(0xFF8A94A6)
private val Accent = Brush.horizontalGradient(listOf(Blue, Cyan))

private fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "$b B"
}

private fun fmtEta(s: Long) = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

@Composable
fun WinKazeApp(vm: BurnViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    LaunchedEffect(s.phase) { view.keepScreenOn = s.phase == Phase.BURNING }
    var confirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::pickIso)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Card)) {
        Column(
            Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header()

            StepCard(1, "Windows ISO", Icons.Rounded.Album, s.isoUri != null, s.phase != Phase.BURNING,
                onClick = { picker.launch(arrayOf("*/*")) }) {
                if (s.isoName != null) {
                    Text(s.isoName!!, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(fmtSize(s.isoSize), color = TextDim, fontSize = 13.sp)
                } else Text("Tap to choose an .iso file", color = TextDim)
            }

            StepCard(2, "USB Flash Drive (OTG)", Icons.Rounded.Usb, s.selectedId != null, false, onClick = {}) {
                if (s.devices.isEmpty()) {
                    Text("Plug in your flash drive using an OTG adapter.", color = TextDim)
                } else {
                    s.devices.forEach { d ->
                        val sel = d.device.deviceId == s.selectedId
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Blue.copy(alpha = .15f) else Color(0xFF171B22))
                                .clickable(enabled = s.phase != Phase.BURNING) { vm.selectDevice(d) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.SdStorage, null, tint = if (sel) Cyan else TextDim)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = Color.White, fontWeight = FontWeight.Medium)
                                Text(
                                    if (sel) fmtSize(s.selectedCapacity) else if (d.hasPermission) "Tap to select" else "Tap to grant access",
                                    color = TextDim, fontSize = 12.sp
                                )
                            }
                            if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = Green)
                        }
                    }
                }
                TextButton(onClick = vm::refreshDevices, enabled = s.phase != Phase.BURNING) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh")
                }
            }

            ModeSelector(s.mode, s.phase != Phase.BURNING, vm::setMode)

            AnimatedVisibility(s.phase == Phase.BURNING || s.phase == Phase.DONE) { ProgressPanel(s, vm) }

            AnimatedVisibility(s.message != null && s.phase != Phase.DONE, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (s.phase == Phase.ERROR) Red.copy(alpha = .12f) else Card)
                        .padding(14.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (s.phase == Phase.ERROR) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null,
                        tint = if (s.phase == Phase.ERROR) Red else Cyan)
                    Spacer(Modifier.width(10.dp))
                    Text(s.message ?: "", color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = vm::dismissMessage) { Icon(Icons.Rounded.Close, null, tint = TextDim) }
                }
            }

            Spacer(Modifier.weight(1f, fill = false))

            if (s.phase == Phase.BURNING) {
                OutlinedButton(
                    onClick = vm::cancel, modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Red)
                ) { Text("Cancel", color = Red, fontWeight = FontWeight.SemiBold) }
            } else {
                GradientButton("Burn ISO", s.canBurn) { confirm = true }
            }
            Text(if (s.mode == Mode.WINDOWS) "Formats the drive as FAT32 and copies the Windows Setup files (UEFI boot only). All data on the drive will be erased." else "Writes the ISO as a raw disk image (DD mode). All data on the drive will be erased.",
                color = TextDim, fontSize = 12.sp)
        }

        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Card,
            icon = { Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFFB020)) },
            title = { Text("Erase this drive?", color = Color.White) },
            text = { Text("Everything on \"${s.selectedName}\" (${fmtSize(s.selectedCapacity)}) will be permanently deleted.", color = TextDim) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.startBurn() }) { Text("Erase & Burn", color = Red) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text("WinKaze", style = TextStyle(brush = Accent, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold))
        Text("Create a Windows USB installer from your phone", color = TextDim, fontSize = 14.sp)
    }
}

@Composable
private fun StepCard(
    n: Int, title: String, icon: ImageVector, done: Boolean, enabled: Boolean,
    onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(20.dp)).background(Card)
            .clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (done) Brush.linearGradient(listOf(Blue, Cyan)) else Brush.linearGradient(listOf(CardBorder, CardBorder))),
                contentAlignment = Alignment.Center
            ) {
                if (done) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                else Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = TextDim)
        }
        content()
    }
}

@Composable
private fun ProgressPanel(s: UiState, vm: BurnViewModel) {
    val p by animateFloatAsState(s.progress, tween(300), label = "p")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (s.phase == Phase.DONE) {
            Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(56.dp))
            Text("Burn complete", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(s.message ?: "", color = TextDim, fontSize = 14.sp)
            TextButton(onClick = vm::dismissMessage) { Text("OK") }
        } else {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { p }, modifier = Modifier.size(120.dp), strokeWidth = 8.dp,
                    color = Cyan, trackColor = CardBorder)
                Text("${(p * 100).toInt()}%", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(s.stage.ifEmpty { "Burning…" }, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("Keep the drive connected", color = TextDim, fontSize = 12.sp)
            Text("%.1f MB/s  •  ETA %s".format(s.speedMBs, fmtEta(s.etaSec)), color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val a by animateFloatAsState(if (enabled) 1f else .35f, label = "a")
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(Accent, alpha = a)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}


@Composable
private fun ModeSelector(mode: Mode, enabled: Boolean, onSelect: (Mode) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Burn mode", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        ModeRow("Windows Setup (UEFI)", "Formats FAT32, copies files, splits install.wim automatically.", mode == Mode.WINDOWS, enabled) { onSelect(Mode.WINDOWS) }
        ModeRow("Raw image (DD)", "Writes the ISO byte-for-byte. Best for Linux ISOs.", mode == Mode.RAW, enabled) { onSelect(Mode.RAW) }
    }
}

@Composable
private fun ModeRow(title: String, sub: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Blue.copy(alpha = .15f) else Color(0xFF171B22))
            .clickable(enabled = enabled, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) Cyan else TextDim)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(sub, color = TextDim, fontSize = 12.sp)
        }
    }
}
