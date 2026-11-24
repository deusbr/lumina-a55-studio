package dev.deusbr.luminaa55.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    // viewMode: 0 = Dividido (Antes/Depois), 1 = Só Ensaio, 2 = Só Original
    var viewMode by remember { mutableIntStateOf(0) }
    var splitRatio by remember { mutableFloatStateOf(0.55f) }
    var proMist by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.proMistStrength) }
    var bokeh by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.backgroundBokeh) }
    var grain by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.grainAmount) }

    val beforeImg = remember(session.previewOriginalBitmap) { session.previewOriginalBitmap.asImageBitmap() }
    val afterImg = remember(session.previewProcessedBitmap) { session.previewProcessedBitmap.asImageBitmap() }
    val mpCount = String.format(Locale.US, "%.1fMP", (session.originalBitmap.width * session.originalBitmap.height) / 1_000_000f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminaColors.VoidBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Lighttable Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.closeReviewStudio() },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LuminaColors.PanelSurface)
                    .border(1.dp, LuminaColors.HairlineBorder, CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = LuminaColors.InkPrimary)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isPro) "MESA DE LUZ · PRO" else "ENSAIO AUTOMÁTICO",
                    color = LuminaColors.InkPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Text(
                    text = "${session.originalBitmap.width}×${session.originalBitmap.height} ($mpCount) · ${if (session.gpuAccelerated) "GPU" else "CPU"} ${session.processingTimeMs}ms",
                    color = LuminaColors.OpticalAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { viewModel.saveActiveSession() },
                enabled = !state.isSaving,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (session.savedUriString != null) LuminaColors.StudioEmerald else LuminaColors.OpticalAmber,
                    contentColor = LuminaColors.VoidBg
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = LuminaColors.VoidBg, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (session.savedUriString != null) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (session.savedUriString != null) "Salva" else "Salvar",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Exact 1:1 Aligned Split Lighttable Viewer
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
                .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(18.dp))
                .pointerInput(viewMode) {
                    if (viewMode == 0) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            splitRatio = (change.position.x / size.width).coerceIn(0.05f, 0.95f)
                        }
                    }
                }
        ) {
            val fullWidth = maxWidth
            val activeSplit = when (viewMode) {
                1 -> 1.0f
                2 -> 0.0f
                else -> splitRatio
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
                        contentDescription = "Ensaio Processado",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth(align = Alignment.Start, unbounded = true)
                            .width(fullWidth)
                    )
                }
            }

            if (viewMode == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset(x = fullWidth * splitRatio)
                        .width(2.dp)
                        .background(LuminaColors.OpticalAmber)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = fullWidth * splitRatio - 15.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(LuminaColors.OpticalAmber)
                        .border(1.dp, LuminaColors.VoidBg, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↔", color = LuminaColors.VoidBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (viewMode == 2) "ORIGINAL" else "ENSAIO · ${session.concept.recommendedStock.badge}",
                    color = if (viewMode == 2) LuminaColors.InkPrimary else LuminaColors.VoidBg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (viewMode == 2) Color.Black.copy(alpha = 0.78f) else LuminaColors.OpticalAmber)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(LuminaColors.PanelSurface.copy(alpha = 0.88f))
                        .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(0 to "Dividido", 1 to "Ensaio", 2 to "Original").forEach { (idx, label) ->
                        val active = viewMode == idx
                        Text(
                            text = label,
                            color = if (active) LuminaColors.VoidBg else LuminaColors.InkPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) LuminaColors.OpticalAmber else Color.Transparent)
                                .clickable { viewMode = idx }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Bottom Editorial Sheet (Automatic summary in NORMAL; full controls in PRO)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isPro) 280.dp else 190.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LuminaColors.PanelSurface)
                    .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.concept.conceptTitle,
                        color = LuminaColors.OpticalAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${session.burstFramesMerged}x Denoise",
                        color = LuminaColors.StudioEmerald,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = session.concept.conceptSummary,
                    color = LuminaColors.InkPrimary,
                    fontSize = 12.sp
                )
                Text(
                    text = "Luz: ${session.concept.lighting.labelPt} · Rostos: ${session.concept.faceCount} · Cena: ${session.concept.detectedTags.ifEmpty { listOf("Geral") }.joinToString()}",
                    color = LuminaColors.InkSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    session.concept.appliedAdjustments.forEach { adj ->
                        Text(
                            text = adj,
                            color = LuminaColors.InkPrimary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(LuminaColors.ElevatedSurface)
                                .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (!isPro) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LuminaColors.ElevatedSurface)
                        .border(1.dp, LuminaColors.HairlineBorder, RoundedCornerShape(12.dp))
                        .clickable { viewModel.enterProModeFromReview() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = LuminaColors.OpticalAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Quer ajuste manual? Abrir controles no Modo Pro",
                        color = LuminaColors.InkPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            } else {
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
                                .defaultMinSize(minHeight = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) LuminaColors.OpticalAmber.copy(alpha = 0.16f) else LuminaColors.PanelSurface)
                                .border(
                                    1.dp,
                                    if (selected) LuminaColors.OpticalAmber else LuminaColors.HairlineBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.reprocessActiveSession(stock = stock) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stock.displayName,
                                color = if (selected) LuminaColors.OpticalAmber else LuminaColors.InkPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                TuningSlider("Intensidade do Ensaio", state.editIntensity, 0f..1.4f) {
                    viewModel.reprocessActiveSession(intensity = it)
                }
                TuningSlider("Black Pro-Mist / Halation", proMist, 0f..0.4f) {
                    proMist = it
                    viewModel.reprocessActiveSession(customProMist = it)
                }
                TuningSlider("Separação de Fundo (Bokeh)", bokeh, 0f..0.85f) {
                    bokeh = it
                    viewModel.reprocessActiveSession(customBokeh = it)
                }
                TuningSlider("Granulação Fílmica", grain, 0f..0.28f) {
                    grain = it
                    viewModel.reprocessActiveSession(customGrain = it)
                }
            }

            state.statusMessage?.let { msg ->
                Text(
                    text = msg,
                    color = LuminaColors.StudioEmerald,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
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
