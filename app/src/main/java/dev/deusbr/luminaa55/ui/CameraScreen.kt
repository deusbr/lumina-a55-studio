package dev.deusbr.luminaa55.ui

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LensOption

private val Obsidian = Color(0xFF0E0D0C)
private val SurfaceDark = Color(0xCC1B1917)
private val BrassAccent = Color(0xFFE08B44)
private val IvoryText = Color(0xFFF6F2EA)
private val MutedText = Color(0xFFBDB5AA)

@Composable
fun CameraScreen(
    state: LuminaUiState,
    viewModel: LuminaViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importFromGalleryUri(uri)
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner, previewView)
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian)
    ) {
        // Live Viewfinder
        AndroidView(
            factory = {
                previewView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        focusPoint = Offset(event.x, event.y)
                        viewModel.cameraController.focusAtPoint(previewView, event.x, event.y)
                    }
                    true
                }
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Rule-of-thirds grid + focus reticle
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.showGrid) {
                val lineColor = Color.White.copy(alpha = 0.16f)
                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1.5f)
                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1.5f)
                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1.5f)
                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1.5f)
            }
            focusPoint?.let { pt ->
                drawCircle(
                    color = BrassAccent,
                    radius = 42f,
                    center = pt,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
            }
        }

        // Top Bar: Sensor info + Live Concept Pill + Actions
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceDark)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "LUMINA A55",
                        color = IvoryText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(
                        text = state.activeLens.sensorSpec,
                        color = BrassAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SurfaceDark)
                            .clickable { viewModel.setBurstCount(if (state.burstCount == 1) 3 else if (state.burstCount == 3) 5 else 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${state.burstCount}x",
                            color = if (state.burstCount > 1) BrassAccent else IvoryText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleGrid() },
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceDark)
                    ) {
                        Icon(Icons.Default.GridOn, contentDescription = "Grade", tint = if (state.showGrid) BrassAccent else IvoryText, modifier = Modifier.size(17.dp))
                    }
                    IconButton(
                        onClick = { viewModel.toggleProBar() },
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceDark)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Controles Pro", tint = if (state.showProBar) BrassAccent else IvoryText, modifier = Modifier.size(17.dp))
                    }
                    IconButton(
                        onClick = { viewModel.toggleDiagnostics(true) },
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceDark)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = "Diagnóstico A55", tint = IvoryText, modifier = Modifier.size(17.dp))
                    }
                }
            }

            // Live Concept Badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BrassAccent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = BrassAccent, modifier = Modifier.size(14.dp))
                Text(
                    text = state.liveConceptHint,
                    color = IvoryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        // Bottom Editorial Deck
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Obsidian.copy(alpha = 0.88f))
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.showProBar) {
                ProManualDeck(state = state, viewModel = viewModel)
            }

            // Capture Mode Row (Burst HDR+ / Samsung HDR / Night / Bokeh)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CaptureMode.entries.forEach { mode ->
                    val selected = state.captureMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) BrassAccent else Color(0xFF211F1C))
                            .clickable { viewModel.selectCaptureMode(mode, lifecycleOwner, previewView) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) Obsidian else MutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Lens Selector Pills (0.5x / 1x / 2x / Macro / 32M)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LensOption.entries.forEach { lens ->
                    val selected = state.activeLens == lens
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .background(if (selected) BrassAccent else Color(0xFF211F1C))
                            .border(1.dp, if (selected) BrassAccent else Color(0xFF3A3530), CircleShape)
                            .clickable { viewModel.selectLens(lens, lifecycleOwner, previewView) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = lens.shortZoom,
                            color = if (selected) Obsidian else IvoryText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Film Stock Quick Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilmStock.entries.forEach { stock ->
                    val selected = state.selectedStock == stock
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFF2F2821) else Color(0xFF191715))
                            .border(1.dp, if (selected) BrassAccent else Color(0xFF2E2A26), RoundedCornerShape(10.dp))
                            .clickable { viewModel.selectFilmStock(stock) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stock.displayName,
                            color = if (selected) BrassAccent else MutedText,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Shutter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF211F1C))
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Abrir da galeria", tint = IvoryText)
                }

                // Mechanical Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.dp, IvoryText, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(if (state.isCapturing) BrassAccent else IvoryText)
                        .clickable(enabled = !state.isCapturing) { viewModel.captureEditorialPhoto() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isCapturing) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), color = Obsidian, strokeWidth = 3.dp)
                    }
                }

                IconButton(
                    onClick = {
                        val next = if (state.activeLens == LensOption.FRONT_32MP) LensOption.MAIN_IMX906 else LensOption.FRONT_32MP
                        viewModel.selectLens(next, lifecycleOwner, previewView)
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF211F1C))
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Alternar frontal", tint = IvoryText)
                }
            }

            state.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = BrassAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (state.showDiagnostics) {
        HardwareDiagnosticsDialog(
            state = state,
            onDismiss = { viewModel.toggleDiagnostics(false) }
        )
    }
}

@Composable
private fun ProManualDeck(state: LuminaUiState, viewModel: LuminaViewModel) {
    val evRange = remember { viewModel.cameraController.exposureRange() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1816))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EV: ${state.evIndex}", color = IvoryText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ISO/S Manual", color = MutedText, fontSize = 11.sp)
                Switch(
                    checked = state.manualMode,
                    onCheckedChange = { viewModel.updateManualExposure(it, state.manualIso, state.manualShutterMs) }
                )
            }
        }
        Slider(
            value = state.evIndex.toFloat(),
            onValueChange = { viewModel.setEvIndex(it.toInt()) },
            valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = BrassAccent, activeTrackColor = BrassAccent)
        )
        if (state.manualMode) {
            Text(
                "ISO ${state.manualIso} · 1/${(1000f / state.manualShutterMs).toInt().coerceAtLeast(1)}s",
                color = BrassAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Slider(
                value = state.manualIso.toFloat(),
                onValueChange = { viewModel.updateManualExposure(true, it.toInt(), state.manualShutterMs) },
                valueRange = 50f..1600f,
                colors = SliderDefaults.colors(thumbColor = BrassAccent, activeTrackColor = BrassAccent)
            )
            Slider(
                value = state.manualShutterMs,
                onValueChange = { viewModel.updateManualExposure(true, state.manualIso, it) },
                valueRange = 2f..125f,
                colors = SliderDefaults.colors(thumbColor = BrassAccent, activeTrackColor = BrassAccent)
            )
        }
    }
}

@Composable
private fun HardwareDiagnosticsDialog(state: LuminaUiState, onDismiss: () -> Unit) {
    val profile = state.hardwareProfile
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF191715),
        titleContentColor = IvoryText,
        textContentColor = MutedText,
        title = { Text("Diagnóstico Camera2 / Galaxy A55") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (profile == null) {
                    Text("Inspecionando câmeras…")
                } else {
                    Text("Aparelho: ${profile.deviceModel}", color = IvoryText, fontSize = 12.sp)
                    Text("Hardware: ${profile.socHardware}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        if (profile.rawBlockedByFirmware)
                            "RAW/DNG: Bloqueado pela firmware sem root — usando rajada YUV Multi-Frame HDR+ e shaders GPU."
                        else
                            "RAW/DNG: Exposto pela HAL!",
                        color = BrassAccent,
                        fontSize = 11.sp
                    )
                    Text(
                        "GPU RuntimeShader AGSL: ${if (profile.supportsHardwareRuntimeShader) "Ativo (Xclipse / API 33+)" else "Indisponível"}",
                        fontSize = 11.sp
                    )
                    Text(
                        "Extensões Samsung CameraX: ${if (profile.extensionsSupported.isEmpty()) "Nenhuma detectada neste dispositivo" else profile.extensionsSupported.joinToString()}",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Sensores expostos (${profile.lenses.size}):", color = IvoryText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    profile.lenses.forEach { lens ->
                        Text(
                            "ID ${lens.cameraId} (${lens.facing}) -> ${lens.mappedRole?.label ?: "Aux"}\n" +
                                "Nível: ${lens.hardwareLevel} · OIS: ${lens.hasOis} · f/${lens.apertures.joinToString()} · ${lens.focalLengthsMm.joinToString()}mm",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = BrassAccent)
            }
        }
    )
}
