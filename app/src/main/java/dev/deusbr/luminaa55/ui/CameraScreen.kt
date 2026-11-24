package dev.deusbr.luminaa55.ui

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.deusbr.luminaa55.model.CameraExperienceMode
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LensOption
import dev.deusbr.luminaa55.model.ProDeckTab

@Composable
fun CameraScreen(
    state: LuminaUiState,
    viewModel: LuminaViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPro = state.experienceMode == CameraExperienceMode.PRO

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
            .background(LuminaColors.VoidBg)
    ) {
        // Unobstructed Full-Bleed Viewfinder
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

        // Subtle Rule-of-Thirds & Precision Optical Corner Reticle
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.showGrid && isPro) {
                val lineColor = LuminaColors.InkPrimary.copy(alpha = 0.11f)
                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1f)
                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1f)
                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1f)
            }
            focusPoint?.let { pt ->
                val half = 34f
                val arm = 12f
                val col = LuminaColors.OpticalAmber
                val sw = 2.5f
                drawLine(col, Offset(pt.x - half, pt.y - half), Offset(pt.x - half + arm, pt.y - half), sw)
                drawLine(col, Offset(pt.x - half, pt.y - half), Offset(pt.x - half, pt.y - half + arm), sw)
                drawLine(col, Offset(pt.x + half, pt.y - half), Offset(pt.x + half - arm, pt.y - half), sw)
                drawLine(col, Offset(pt.x + half, pt.y - half), Offset(pt.x + half, pt.y - half + arm), sw)
                drawLine(col, Offset(pt.x - half, pt.y + half), Offset(pt.x - half + arm, pt.y + half), sw)
                drawLine(col, Offset(pt.x - half, pt.y + half), Offset(pt.x - half, pt.y + half - arm), sw)
                drawLine(col, Offset(pt.x + half, pt.y + half), Offset(pt.x + half - arm, pt.y + half), sw)
                drawLine(col, Offset(pt.x + half, pt.y + half), Offset(pt.x + half, pt.y + half - arm), sw)
            }
        }

        // Whisper-Clean Top Gradient Scrim + Single Quiet Status Line (Zero boxy cards!)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.52f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isPro) LuminaColors.OpticalAmber else LuminaColors.StudioEmerald)
                )
                Text(
                    text = if (isPro) {
                        state.selectedStock.displayName
                    } else {
                        "${state.autoDecision.headline} · ${state.autoDecision.recommendedStock.displayName}"
                    },
                    color = LuminaColors.InkPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isPro) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleSessionLock() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (state.sessionLookLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Travar look",
                            tint = if (state.sessionLookLocked) LuminaColors.OpticalAmber else LuminaColors.InkPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.toggleGrid() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Grade",
                            tint = if (state.showGrid) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.toggleDiagnostics(true) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Sensores",
                            tint = LuminaColors.InkPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "AUTO",
                    color = LuminaColors.InkPrimary.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp
                )
            }
        }

        // Clean Darkroom Bottom Zone (Zero bulky multi-layer capsules!)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.96f))
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isPro) {
                ProMinimalDeck(
                    state = state,
                    viewModel = viewModel,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView
                )
            }

            // Floating Minimal Focal Length Selector (typographic circles)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val backLenses = remember {
                    listOf(LensOption.ULTRA_WIDE, LensOption.MAIN_IMX906, LensOption.TELE_2X, LensOption.MACRO)
                }
                backLenses.forEach { lens ->
                    val selected = state.activeLens == lens
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.35f))
                            .border(
                                1.dp,
                                if (selected) LuminaColors.OpticalAmber else Color.White.copy(alpha = 0.12f),
                                CircleShape
                            )
                            .clickable { viewModel.selectLens(lens, lifecycleOwner, previewView) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lens.shortZoom,
                            color = if (selected) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Quiet Editorial Mode Switcher: Normal | Pro
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraExperienceMode.entries.forEach { expMode ->
                    val active = state.experienceMode == expMode
                    val labelText = if (expMode == CameraExperienceMode.NORMAL) "NORMAL" else "PRO"
                    Column(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 32.dp)
                            .clickable { viewModel.selectExperienceMode(expMode, lifecycleOwner, previewView) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = labelText,
                            color = when {
                                active && expMode == CameraExperienceMode.PRO -> LuminaColors.OpticalAmber
                                active -> LuminaColors.InkPrimary
                                else -> LuminaColors.InkSecondary
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            letterSpacing = 1.6.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(width = if (active) 16.dp else 0.dp, height = 2.dp)
                                .clip(CircleShape)
                                .background(
                                    if (expMode == CameraExperienceMode.PRO) LuminaColors.OpticalAmber else LuminaColors.InkPrimary
                                )
                        )
                    }
                }
            }

            // Balanced 3-Control Shutter Bar (Thumbnail · Leica Shutter · Flip)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val latestSession = state.studioSessions.firstOrNull()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(LuminaColors.ElevatedSurface)
                        .border(
                            1.5.dp,
                            if (state.pendingQueueCount > 0) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                            CircleShape
                        )
                        .clickable {
                            if (latestSession != null) viewModel.openStudioReview(latestSession)
                            else galleryLauncher.launch("image/*")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (latestSession != null) {
                        Image(
                            bitmap = remember(latestSession.previewProcessedBitmap) {
                                latestSession.previewProcessedBitmap.asImageBitmap()
                            },
                            contentDescription = "Abrir ensaio",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (state.pendingQueueCount > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${state.pendingQueueCount}",
                                    color = LuminaColors.OpticalAmber,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else if (state.pendingQueueCount > 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = LuminaColors.OpticalAmber,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Galeria",
                            tint = LuminaColors.InkPrimary.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Refined Dual-Ring Tactile Shutter
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(
                            2.dp,
                            if (isPro) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                            CircleShape
                        )
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(if (state.isShutterBusy) LuminaColors.ShutterCrimson else LuminaColors.InkPrimary)
                        .clickable(enabled = !state.isShutterBusy) { viewModel.captureEditorialPhoto() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isShutterBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = LuminaColors.InkPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else if (isPro) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LuminaColors.ShutterCrimson)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val next = if (state.activeLens == LensOption.FRONT_32MP) LensOption.MAIN_IMX906 else LensOption.FRONT_32MP
                        viewModel.selectLens(next, lifecycleOwner, previewView)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.activeLens == LensOption.FRONT_32MP) LuminaColors.OpticalAmber else LuminaColors.ElevatedSurface
                        )
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Alternar frontal",
                        tint = if (state.activeLens == LensOption.FRONT_32MP) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (state.pendingQueueCount > 0) {
                Text(
                    text = "Revelando ${state.pendingQueueCount} ${if (state.pendingQueueCount == 1) "foto" else "fotos"} em segundo plano",
                    color = LuminaColors.InkSecondary,
                    fontSize = 10.sp
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
private fun ProMinimalDeck(
    state: LuminaUiState,
    viewModel: LuminaViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView
) {
    val evRange = remember { viewModel.cameraController.exposureRange() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.64f))
            .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ProDeckTab.entries.forEach { tab ->
                val active = state.proDeckTab == tab
                Text(
                    text = tab.label,
                    color = if (active) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { viewModel.selectProTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        when (state.proDeckTab) {
            ProDeckTab.EXPOSURE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EV ${if (state.evIndex >= 0) "+${state.evIndex}" else "${state.evIndex}"}",
                        color = LuminaColors.InkPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Manual", color = LuminaColors.InkSecondary, fontSize = 11.sp)
                        Switch(
                            checked = state.manualMode,
                            onCheckedChange = { viewModel.updateManualExposure(it, state.manualIso, state.manualShutterMs) },
                            colors = SwitchDefaults.colors(checkedThumbColor = LuminaColors.VoidBg, checkedTrackColor = LuminaColors.OpticalAmber)
                        )
                    }
                }
                Slider(
                    value = state.evIndex.toFloat(),
                    onValueChange = { viewModel.setEvIndex(it.toInt()) },
                    valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = LuminaColors.OpticalAmber, activeTrackColor = LuminaColors.OpticalAmber)
                )
                if (state.manualMode) {
                    Text(
                        "ISO ${state.manualIso} · 1/${(1000f / state.manualShutterMs).toInt().coerceAtLeast(1)}s",
                        color = LuminaColors.OpticalAmber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Slider(
                        value = state.manualIso.toFloat(),
                        onValueChange = { viewModel.updateManualExposure(true, it.toInt(), state.manualShutterMs) },
                        valueRange = 50f..3200f,
                        colors = SliderDefaults.colors(thumbColor = LuminaColors.OpticalAmber, activeTrackColor = LuminaColors.OpticalAmber)
                    )
                    Slider(
                        value = state.manualShutterMs,
                        onValueChange = { viewModel.updateManualExposure(true, state.manualIso, it) },
                        valueRange = 2f..200f,
                        colors = SliderDefaults.colors(thumbColor = LuminaColors.OpticalAmber, activeTrackColor = LuminaColors.OpticalAmber)
                    )
                }
            }

            ProDeckTab.FILM -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilmStock.entries.forEach { stock ->
                        val selected = state.selectedStock == stock
                        Text(
                            text = stock.displayName,
                            color = if (selected) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { viewModel.selectFilmStock(stock) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            ProDeckTab.ENGINE -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CaptureMode.entries.forEach { mode ->
                        val selected = state.captureMode == mode
                        Text(
                            text = mode.label,
                            color = if (selected) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) LuminaColors.OpticalAmber else Color.White.copy(alpha = 0.08f))
                                .clickable { viewModel.selectCaptureMode(mode, lifecycleOwner, previewView) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Redução de ruído", color = LuminaColors.InkSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "1x", 3 to "3x", 5 to "5x").forEach { (n, label) ->
                            val active = state.burstCount == n
                            Text(
                                text = label,
                                color = if (active) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { viewModel.setBurstCount(n) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareDiagnosticsDialog(state: LuminaUiState, onDismiss: () -> Unit) {
    val profile = state.hardwareProfile
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LuminaColors.PanelSurface,
        titleContentColor = LuminaColors.InkPrimary,
        textContentColor = LuminaColors.InkSecondary,
        title = { Text("Sensores Galaxy A55") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (profile == null) {
                    Text("Inspecionando câmeras…")
                } else {
                    Text(profile.deviceModel, color = LuminaColors.InkPrimary, fontSize = 12.sp)
                    Text(
                        "GPU AGSL: ${if (profile.supportsHardwareRuntimeShader) "Ativo" else "CPU"}",
                        fontSize = 11.sp
                    )
                    Text(
                        "Extensões Samsung: ${profile.extensionsSupported.ifEmpty { listOf("Padrão") }.joinToString()}",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    profile.lenses.forEach { lens ->
                        Text(
                            "ID ${lens.cameraId} (${lens.facing}) · ${lens.mappedRole?.label ?: "Aux"} · ${lens.hardwareLevel}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar", color = LuminaColors.OpticalAmber)
            }
        }
    )
}
