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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.deusbr.luminaa55.model.CapturedPhotoSession
import dev.deusbr.luminaa55.model.FilmStock

private val StudioBg = Color(0xFF0E0D0C)
private val CardBg = Color(0xFF191715)
private val Brass = Color(0xFFE08B44)
private val Ivory = Color(0xFFF6F2EA)
private val Muted = Color(0xFFBDB5AA)

@Composable
fun ReviewStudioScreen(
    session: CapturedPhotoSession,
    state: LuminaUiState,
    viewModel: LuminaViewModel
) {
    // viewMode: 0 = Dividido (Antes/Depois), 1 = Só Ensaio, 2 = Só Original
    var viewMode by remember { mutableIntStateOf(0) }
    var splitRatio by remember { mutableFloatStateOf(0.55f) }
    var proMist by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.proMistStrength) }
    var bokeh by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.backgroundBokeh) }
    var grain by remember(session.concept.recipe) { mutableFloatStateOf(session.concept.recipe.grainAmount) }

    val beforeImg = remember(session.originalBitmap) { session.originalBitmap.asImageBitmap() }
    val afterImg = remember(session.processedBitmap) { session.processedBitmap.asImageBitmap() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeReviewStudio() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Ivory)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ESTÚDIO EDITORIAL", color = Ivory, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                Text(
                    "${session.lensUsed.shortZoom} · ${session.burstFramesMerged}x HDR+ · ${if (session.gpuAccelerated) "GPU" else "CPU"} ${session.processingTimeMs}ms",
                    color = Brass,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { viewModel.saveActiveSession() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = StudioBg)
            ) {
                Icon(
                    if (session.savedUriString != null) Icons.Default.CheckCircle else Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (session.savedUriString != null) "Salva" else "Salvar", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }

        // Split Before / After Preview with exact 1:1 requiredWidth alignment
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
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
                        .background(Brass)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = fullWidth * splitRatio - 13.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Brass),
                    contentAlignment = Alignment.Center
                ) {
                    Text("↔", color = StudioBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // View Mode Switcher + Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (viewMode == 2) "ORIGINAL" else "ENSAIO (${session.concept.recommendedStock.badge})",
                    color = if (viewMode == 2) Ivory else StudioBg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (viewMode == 2) Color.Black.copy(alpha = 0.72f) else Brass)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(0 to "Dividido", 1 to "Ensaio", 2 to "Original").forEach { (idx, label) ->
                        val active = viewMode == idx
                        Text(
                            text = label,
                            color = if (active) StudioBg else Ivory,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) Brass else Color.Transparent)
                                .clickable { viewMode = idx }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Concept Analysis + Editorial Tuning Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(295.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Concept Analysis Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .border(1.dp, Color(0xFF2D2925), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = session.concept.conceptTitle,
                    color = Brass,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = session.concept.conceptSummary,
                    color = Ivory,
                    fontSize = 12.sp
                )
                Text(
                    text = "Luz: ${session.concept.lighting.labelPt} · Rostos: ${session.concept.faceCount} · Tags: ${session.concept.detectedTags.ifEmpty { listOf("Cena geral") }.joinToString()}",
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Text(
                    text = "Modificações: ${session.concept.appliedAdjustments.joinToString(" • ")}",
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            // Film Stock Strip
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
                            .background(if (selected) Color(0xFF2E261E) else CardBg)
                            .border(1.dp, if (selected) Brass else Color(0xFF2D2925), RoundedCornerShape(10.dp))
                            .clickable { viewModel.reprocessActiveSession(stock = stock) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(stock.displayName, color = if (selected) Brass else Ivory, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Fine Tuning Sliders
            TuningSlider("Intensidade do Ensaio", state.editIntensity, 0f..1.4f) {
                viewModel.reprocessActiveSession(intensity = it)
            }
            TuningSlider("Black Pro-Mist / Halation", proMist, 0f..0.5f) {
                proMist = it
                viewModel.reprocessActiveSession(customProMist = it)
            }
            TuningSlider("Separação de Fundo (Bokeh)", bokeh, 0f..0.9f) {
                bokeh = it
                viewModel.reprocessActiveSession(customBokeh = it)
            }
            TuningSlider("Granulação Fílmica", grain, 0f..0.35f) {
                grain = it
                viewModel.reprocessActiveSession(customGrain = it)
            }
        }
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(String.format("%.2f", value), color = Ivory, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Brass, activeTrackColor = Brass)
        )
    }
}
