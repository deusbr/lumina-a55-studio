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
import androidx.compose.material.icons.filled.AutoAwesome
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
        // Full-bleed Live Viewfinder
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

        // Precision Rule-of-Thirds & Optical Corner Bracket Reticle
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.showGrid) {
                val lineColor = LuminaColors.InkPrimary.copy(alpha = 0.13f)
                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1.5f)
                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1.5f)
                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1.5f)
                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1.5f)
            }
            focusPoint?.let { pt ->
                val half = 36f
                val arm = 14f
                val col = LuminaColors.OpticalAmber
                val sw = 3f
                // Top-left bracket
                drawLine(col, Offset(pt.x - half, pt.y - half), Offset(pt.x - half + arm, pt.y - half), sw)
                drawLine(col, Offset(pt.x - half, pt.y - half), Offset(pt.x - half, pt.y - half + arm), sw)
                // Top-right bracket
                drawLine(col, Offset(pt.x + half, pt.y - half), Offset(pt.x + half - arm, pt.y - half), sw)
                drawLine(col, Offset(pt.x + half, pt.y - half), Offset(pt.x + half, pt.y - half + arm), sw)
                // Bottom-left bracket
                drawLine(col, Offset(pt.x - half, pt.y + half), Offset(pt.x - half + arm, pt.y + half), sw)
                drawLine(col, Offset(pt.x - half, pt.y + half), Offset(pt.x - half, pt.y + half - arm), sw)
                // Bottom-right bracket
                drawLine(col, Offset(pt.x + half, pt.y + half), Offset(pt.x + half - arm, pt.y + half), sw)
                drawLine(col, Offset(pt.x + half, pt.y + half), Offset(pt.x + half, pt.y + half - arm), sw)
            }
        }

        // Top Minimal Darkroom Telemetry HUD
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
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LuminaColors.PanelTranslucent)
                        .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isPro) LuminaColors.OpticalAmber else LuminaColors.StudioEmerald)
                    )
                    Column {
                        Text(
                            text = "LUMINA A55",
                            color = LuminaColors.InkPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "${state.activeLens.sensorSpec} · FULL-RES",
                            color = LuminaColors.InkSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Right quick indicator / Pro tools only when in Pro
                if (isPro) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        IconButton(
                            onClick = { viewModel.toggleSessionLock() },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(LuminaColors.PanelTranslucent)
                                .border(
                                    1.dp,
                                    if (state.sessionLookLocked) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                if (state.sessionLookLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Travar Look da Sessão",
                                tint = if (state.sessionLookLocked) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleGrid() },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(LuminaColors.PanelTranslucent)
                                .border(1.dp, LuminaColors.HairlineBorder, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.GridOn,
                                contentDescription = "Grade 3x3",
                                tint = if (state.showGrid) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleDiagnostics(true) },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(LuminaColors.PanelTranslucent)
                                .border(1.dp, LuminaColors.HairlineBorder, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "Diagnóstico Camera2",
                                tint = LuminaColors.InkPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(LuminaColors.PanelTranslucent)
                            .border(1.dp, LuminaColors.StudioEmerald.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "AUTO IA",
                            color = LuminaColors.StudioEmerald,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Live Contextual Intelligence Pill (informative in Normal, reference in Pro)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(LuminaColors.PanelTranslucent)
                    .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (isPro) LuminaColors.OpticalAmber else LuminaColors.StudioEmerald,
                    modifier = Modifier.size(15.dp)
                )
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = state.autoDecision.headline,
                        color = LuminaColors.InkPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isPro) {
                            "Pro: ${state.selectedStock.displayName} · ${state.captureMode.label}"
                        } else {
                            "Auto: ${state.autoDecision.subtitle}"
                        },
                        color = LuminaColors.InkSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom Ergonomic Thumb-Zone Deck
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LuminaColors.PanelSurface.copy(alpha = 0.94f))
                .border(1.dp, LuminaColors.HairlineBorder)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Dedicated PRO Controls Drawer (hidden completely in NORMAL mode)
            if (isPro) {
                ProStudioDeck(state = state, viewModel = viewModel, lifecycleOwner = lifecycleOwner, previewView = previewView)
            }

            // Optical Focal Length Ruler (Clean in both Normal & Pro)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val backLenses = remember {
                    listOf(LensOption.ULTRA_WIDE, LensOption.MAIN_IMX906, LensOption.TELE_2X, LensOption.MACRO)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(LuminaColors.ElevatedSurface)
                        .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    backLenses.forEach { lens ->
                        val selected = state.activeLens == lens
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) LuminaColors.OpticalAmber else Color.Transparent)
                                .clickable { viewModel.selectLens(lens, lifecycleOwner, previewView) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lens.shortZoom,
                                color = if (selected) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // NORMAL vs PRO Segmented Experience Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(LuminaColors.VoidBg)
                        .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CameraExperienceMode.entries.forEach { expMode ->
                        val active = state.experienceMode == expMode
                        val labelText = if (expMode == CameraExperienceMode.NORMAL) "NORMAL · AUTO" else "PRO · MANUAL"
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    when {
                                        active && expMode == CameraExperienceMode.PRO -> LuminaColors.OpticalAmber
                                        active -> LuminaColors.InkPrimary
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { viewModel.selectExperienceMode(expMode, lifecycleOwner, previewView) }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = labelText,
                                color = if (active) LuminaColors.VoidBg else LuminaColors.InkSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Primary Shutter Row (Live Studio Thumbnail + Non-Blocking Shutter + Front Switch)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val latestSession = state.studioSessions.firstOrNull()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LuminaColors.ElevatedSurface)
                            .border(
                                1.5.dp,
                                if (state.pendingQueueCount > 0) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                                RoundedCornerShape(14.dp)
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
                                contentDescription = "Abrir ensaio recente",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            val badgeText = if (state.pendingQueueCount > 0) "⏳${state.pendingQueueCount}" else "${state.studioSessions.size}"
                            Text(
                                text = badgeText,
                                color = LuminaColors.VoidBg,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (state.pendingQueueCount > 0) LuminaColors.OpticalAmber else LuminaColors.StudioEmerald)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        } else if (state.pendingQueueCount > 0) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = LuminaColors.OpticalAmber, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Abrir da galeria", tint = LuminaColors.InkPrimary)
                        }
                    }

                    if (latestSession != null) {
                        IconButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LuminaColors.ElevatedSurface)
                                .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(12.dp))
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Importar foto", tint = LuminaColors.InkSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Dual-Ring Non-Blocking Tactile Precision Shutter
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(
                            3.dp,
                            if (isPro) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                            CircleShape
                        )
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (state.isShutterBusy) LuminaColors.ShutterCrimson else LuminaColors.InkPrimary)
                        .clickable(enabled = !state.isShutterBusy) { viewModel.captureEditorialPhoto() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isShutterBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = LuminaColors.InkPrimary,
                            strokeWidth = 3.dp
                        )
                    } else if (isPro) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
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
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.activeLens == LensOption.FRONT_32MP) LuminaColors.OpticalAmber else LuminaColors.ElevatedSurface
                        )
                        .border(1.dp, LuminaColors.HairlineBorder, CircleShape)
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Alternar frontal 32MP",
                        tint = if (state.activeLens == LensOption.FRONT_32MP) LuminaColors.VoidBg else LuminaColors.InkPrimary
                    )
                }
            }

            state.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = LuminaColors.OpticalAmber,
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
private fun ProStudioDeck(
    state: LuminaUiState,
    viewModel: LuminaViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView
) {
    val evRange = remember { viewModel.cameraController.exposureRange() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LuminaColors.ElevatedSurface)
            .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab Switcher: Exposição | Película | Motor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LuminaColors.VoidBg)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ProDeckTab.entries.forEach { tab ->
                val active = state.proDeckTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) LuminaColors.ElevatedSurface else Color.Transparent)
                        .clickable { viewModel.selectProTab(tab) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (active) LuminaColors.OpticalAmber else LuminaColors.InkSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
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
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ISO/Obturador Manual", color = LuminaColors.InkSecondary, fontSize = 11.sp)
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
                        "ISO ${state.manualIso}  ·  1/${(1000f / state.manualShutterMs).toInt().coerceAtLeast(1)}s",
                        color = LuminaColors.OpticalAmber,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilmStock.entries.forEach { stock ->
                        val selected = state.selectedStock == stock
                        Column(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) LuminaColors.OpticalAmber.copy(alpha = 0.16f) else LuminaColors.PanelSurface)
                                .border(
                                    1.dp,
                                    if (selected) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.selectFilmStock(stock) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stock.displayName,
                                color = if (selected) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = stock.badge,
                                color = LuminaColors.InkSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            ProDeckTab.ENGINE -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CaptureMode.entries.forEach { mode ->
                        val selected = state.captureMode == mode
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 38.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) LuminaColors.OpticalAmber else LuminaColors.PanelSurface)
                                .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                                .clickable { viewModel.selectCaptureMode(mode, lifecycleOwner, previewView) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Limpeza Sombra:", color = LuminaColors.InkSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "1x Puro", 3 to "3x Padrão", 5 to "5x Noturno").forEach { (n, label) ->
                            val active = state.burstCount == n
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) LuminaColors.OpticalAmber else LuminaColors.PanelSurface)
                                    .clickable { viewModel.setBurstCount(n) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (active) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
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
        title = { Text("Diagnóstico Camera2 / Galaxy A55") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (profile == null) {
                    Text("Inspecionando câmeras…")
                } else {
                    Text("Aparelho: ${profile.deviceModel}", color = LuminaColors.InkPrimary, fontSize = 12.sp)
                    Text("Hardware: ${profile.socHardware}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        if (profile.rawBlockedByFirmware)
                            "RAW/DNG: Bloqueado pela firmware — operando em resolução máxima JPEG 100% + shaders GPU em blocos."
                        else
                            "RAW/DNG: Exposto pela HAL!",
                        color = LuminaColors.OpticalAmber,
                        fontSize = 11.sp
                    )
                    Text(
                        "GPU RuntimeShader AGSL: ${if (profile.supportsHardwareRuntimeShader) "Ativo (Xclipse / API 33+)" else "Indisponível"}",
                        fontSize = 11.sp
                    )
                    Text(
                        "Extensões Samsung CameraX: ${if (profile.extensionsSupported.isEmpty()) "Nenhuma detectada neste aparelho" else profile.extensionsSupported.joinToString()}",
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Sensores (${profile.lenses.size}):", color = LuminaColors.InkPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                Text("Fechar", color = LuminaColors.OpticalAmber)
            }
        }
    )
}
