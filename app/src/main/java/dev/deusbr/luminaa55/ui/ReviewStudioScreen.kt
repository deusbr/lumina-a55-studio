package dev.deusbr.luminaa55.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deusbr.luminaa55.model.CameraExperienceMode
import dev.deusbr.luminaa55.model.CapturedPhotoSession
import dev.deusbr.luminaa55.model.FilmStock
import java.util.Locale

@Composable
fun ReviewStudioScreen(
    session: CapturedPhotoSession,
    state: LuminaUiState,
    viewModel: LuminaViewModel
) {
    val isPro = state.experienceMode == CameraExperienceMode.PRO
    var compareSplitMode by remember { mutableStateOf(false) }
    var holdingOriginal by remember { mutableStateOf(false) }
    var showCrewSheet by remember { mutableStateOf(false) }
    var splitRatio by remember { mutableFloatStateOf(0.50f) }

    var proMist by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.proMistStrength) }
    var bokeh by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.backgroundBokeh) }
    var grain by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.grainAmount) }
    var relight3d by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.relight3dStrength) }
    var backdrop by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.studioBackdropStrength) }

    val beforeImg = remember(session.previewOriginalBitmap) { session.previewOriginalBitmap.asImageBitmap() }
    val afterImg = remember(session.previewProcessedBitmap) { session.previewProcessedBitmap.asImageBitmap() }
    val mpCount = String.format(Locale.US, "%.1f MP", (session.originalBitmap.width * session.originalBitmap.height) / 1_000_000f)

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importFromGalleryUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminaColors.VoidBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Minimal Gallery Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeReviewStudio() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = LuminaColors.InkPrimary)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = session.concept.conceptTitle,
                    color = LuminaColors.InkPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${session.concept.recommendedStock.displayName} · $mpCount · ${if (session.gpuAccelerated) "GPU" else "CPU"}",
                    color = LuminaColors.InkSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Abrir foto", tint = LuminaColors.InkSecondary, modifier = Modifier.size(20.dp))
                }
                Button(
                    onClick = { viewModel.saveActiveSession() },
                    enabled = !state.isSaving,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (session.savedUriString != null) LuminaColors.StudioEmerald else LuminaColors.InkPrimary,
                        contentColor = LuminaColors.VoidBg
                    ),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = LuminaColors.VoidBg, strokeWidth = 2.dp)
                    } else if (session.savedUriString != null) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = if (session.savedUriString != null) "Salva" else "Salvar",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Engine Switcher (Offline GPU+MODNet vs Online Gemini) + 6-Specialist Channel Crew Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            EngineSwitchPill(
                activeEngine = session.engineUsed,
                hasGeminiKey = state.hasGeminiKey,
                isLoadingOnline = state.isOnlineProcessing,
                onSelectEngine = { viewModel.applySelectedEngineToActiveSession(it) },
                onOpenKeyConfig = { viewModel.openGeminiSettingsDialog(true) }
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (showCrewSheet) LuminaColors.OpticalAmber.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                    .border(
                        1.dp,
                        if (showCrewSheet) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { showCrewSheet = !showCrewSheet }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = "Banca do Canal",
                    tint = if (showCrewSheet) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Banca (6)",
                    color = if (showCrewSheet) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (showCrewSheet) {
            val crew = session.concept.crewSheet
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LuminaColors.PanelSurface)
                    .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (crew != null) {
                    Text(crew.directorDecision, color = LuminaColors.InkPrimary, fontSize = 11.sp)
                    Text(crew.forensicDiagnosis, color = LuminaColors.InkSecondary, fontSize = 10.sp)
                    Text("Lente: ${crew.cameraLensSpec}", color = LuminaColors.OpticalAmber, fontSize = 10.sp)
                    Text(crew.lightingPlan, color = LuminaColors.InkSecondary, fontSize = 10.sp)
                    Text(crew.consistencyGuardLock, color = LuminaColors.InkSecondary, fontSize = 10.sp)
                    Text(crew.antiAiAuditStatus, color = LuminaColors.StudioEmerald, fontSize = 10.sp)
                } else {
                    Text("Ficha da Banca: ${session.concept.conceptSummary}", color = LuminaColors.InkSecondary, fontSize = 11.sp)
                }
                session.onlineNotes?.let { note ->
                    Text(note, color = LuminaColors.OpticalAmber, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Uncluttered Full-Canvas Photo Viewer (Hold anywhere for Original, or toggle Split)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .pointerInput(compareSplitMode) {
                    if (compareSplitMode) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            splitRatio = (change.position.x / size.width).coerceIn(0.05f, 0.95f)
                        }
                    } else {
                        detectTapGestures(
                            onPress = {
                                holdingOriginal = true
                                tryAwaitRelease()
                                holdingOriginal = false
                            }
                        )
                    }
                }
        ) {
            val fullWidth = maxWidth
            val activeSplit = when {
                compareSplitMode -> splitRatio
                holdingOriginal -> 0.0f
                else -> 1.0f
            }

            Image(
                bitmap = beforeImg,
                contentDescription = "Original",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            if (activeSplit > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(fullWidth * activeSplit)
                        .clip(RectangleShape)
                        .clipToBounds()
                ) {
                    Image(
                        bitmap = afterImg,
                        contentDescription = "Ensaio",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth(align = Alignment.Start, unbounded = true)
                            .width(fullWidth)
                    )
                }
            }

            if (compareSplitMode) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset(x = fullWidth * splitRatio)
                        .width(1.5.dp)
                        .background(LuminaColors.OpticalAmber)
                )
            }

            if (holdingOriginal) {
                Text(
                    text = "ORIGINAL",
                    color = LuminaColors.InkPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Quiet floating bottom-right compare pill
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                    .clickable { compareSplitMode = !compareSplitMode }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    Icons.Default.Compare,
                    contentDescription = "Comparar Antes/Depois",
                    tint = if (compareSplitMode) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (compareSplitMode) "Dividido" else "Segure p/ Original",
                    color = if (compareSplitMode) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                    fontSize = 10.sp
                )
            }
        }

        // Session Filmstrip Dots / Thumbs (only when >1 shot captured)
        if (state.studioSessions.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.studioSessions.forEach { sItem ->
                    val current = sItem.id == session.id
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                if (current) 1.5.dp else 0.5.dp,
                                if (current) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.openStudioReview(sItem) }
                    ) {
                        Image(
                            bitmap = remember(sItem.previewProcessedBitmap) { sItem.previewProcessedBitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Quiet Bottom Area: simple summary in NORMAL, clean sliders in PRO
        if (!isPro) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = session.concept.conceptSummary,
                    color = LuminaColors.InkSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.concept.lighting.labelPt,
                        color = LuminaColors.InkSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                    TextButton(onClick = { viewModel.enterProModeFromReview() }) {
                        Text(
                            text = "Ajustar no Pro",
                            color = LuminaColors.OpticalAmber,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(235.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                .clickable { viewModel.reprocessActiveSession(stock = stock) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                MinimalSlider("Intensidade", state.editIntensity, 0f..1.4f) {
                    viewModel.reprocessActiveSession(intensity = it)
                }
                MinimalSlider("Luz 3D Estúdio", relight3d, 0f..1.2f) {
                    relight3d = it
                    viewModel.reprocessActiveSession(customRelight3d = it)
                }
                MinimalSlider("Fundo Estúdio", backdrop, 0f..1.0f) {
                    backdrop = it
                    viewModel.reprocessActiveSession(customBackdrop = it)
                }
                MinimalSlider("Bokeh", bokeh, 0f..1.0f) {
                    bokeh = it
                    viewModel.reprocessActiveSession(customBokeh = it)
                }
                MinimalSlider("Pro-Mist", proMist, 0f..0.4f) {
                    proMist = it
                    viewModel.reprocessActiveSession(customProMist = it)
                }
                MinimalSlider("Grão", grain, 0f..0.28f) {
                    grain = it
                    viewModel.reprocessActiveSession(customGrain = it)
                }
            }
        }
    }

    if (state.showGeminiSettingsDialog) {
        GeminiSettingsDialog(
            state = state,
            initialKey = viewModel.currentGeminiApiKey(),
            onSelectMode = { viewModel.selectGeminiOnlineMode(it) },
            onSaveKey = { viewModel.saveGeminiApiKey(it) },
            onDismiss = { viewModel.openGeminiSettingsDialog(false) }
        )
    }
}

@Composable
private fun MinimalSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = LuminaColors.InkSecondary, fontSize = 11.sp)
            Text(
                String.format(Locale.US, "%.2f", value),
                color = LuminaColors.InkPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = LuminaColors.OpticalAmber,
                activeTrackColor = LuminaColors.OpticalAmber,
                inactiveTrackColor = LuminaColors.ElevatedSurface
            )
        )
    }
}
