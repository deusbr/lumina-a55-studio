package dev.deusbr.luminaa55.context

import dev.deusbr.luminaa55.model.EditRecipe
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.SceneConcept
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin photometric and semantic concept engine (zero external dependencies, JVM unit-testable).
 * Calibrated for Lumina A55 v5.0 (OKLab luminance dehaze, fluorescent green neutralization, melanin-safe tone curve).
 */
object PhotometricAnalyzer {

    fun analyzeArgbPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        faceCenterX: Float? = null,
        faceCenterY: Float? = null
    ): PhotometricStats {
        if (pixels.isEmpty() || width <= 0 || height <= 0) {
            return PhotometricStats(0.5f, 0f, 0f, 0.5f, 0f, 0.3f, 0.5f, 0.5f, 0.5f, 0.5f, LightDirection.BALANCED)
        }

        val hist = IntArray(256)
        var lumaSum = 0.0
        var shadowCount = 0
        var clipCount = 0
        var warmthSum = 0.0
        var greenCastSum = 0.0
        var greenSampled = 0
        var satSum = 0.0

        var leftSum = 0.0; var leftN = 0
        var rightSum = 0.0; var rightN = 0
        var topSum = 0.0; var topN = 0
        var centerSum = 0.0; var centerN = 0

        val step = max(1, (width * height) / 16000)
        var sampled = 0

        for (y in 0 until height step step.coerceAtMost(4)) {
            for (x in 0 until width step step.coerceAtMost(4)) {
                val c = pixels[y * width + x]
                val r = ((c ushr 16) and 0xFF) / 255f
                val g = ((c ushr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f

                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val bin = (luma * 255f).toInt().coerceIn(0, 255)
                hist[bin]++

                lumaSum += luma
                if (luma < 0.16f) shadowCount++
                if (luma > 0.94f) clipCount++

                warmthSum += (r - b)
                if (luma in 0.16f..0.78f) {
                    greenCastSum += (g - (r + b) * 0.5f)
                    greenSampled++
                }
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                satSum += if (maxC > 0.001f) (maxC - minC) / maxC else 0f

                val nx = x.toFloat() / width
                val ny = y.toFloat() / height
                if (nx < 0.42f) { leftSum += luma; leftN++ }
                if (nx > 0.58f) { rightSum += luma; rightN++ }
                if (ny < 0.34f) { topSum += luma; topN++ }
                val cx = faceCenterX ?: 0.5f
                val cy = faceCenterY ?: 0.5f
                if (abs(nx - cx) < 0.18f && abs(ny - cy) < 0.18f) {
                    centerSum += luma; centerN++
                }
                sampled++
            }
        }

        val n = max(1, sampled)
        val meanLuma = (lumaSum / n).toFloat()
        val shadowRatio = shadowCount.toFloat() / n
        val clipRatio = clipCount.toFloat() / n
        val warmthBias = (warmthSum / n).toFloat()
        val greenCastBias = if (greenSampled > 0) (greenCastSum / greenSampled).toFloat() else 0f
        val satMean = (satSum / n).toFloat()
        val leftLuma = if (leftN > 0) (leftSum / leftN).toFloat() else meanLuma
        val rightLuma = if (rightN > 0) (rightSum / rightN).toFloat() else meanLuma
        val topLuma = if (topN > 0) (topSum / topN).toFloat() else meanLuma
        val centerLuma = if (centerN > 0) (centerSum / centerN).toFloat() else meanLuma

        var p05 = 0
        var p95 = 255
        var acc = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= n * 0.05f) { p05 = i; break }
        }
        acc = 0
        for (i in 255 downTo 0) {
            acc += hist[i]
            if (acc >= n * 0.05f) { p95 = i; break }
        }
        val spread = ((p95 - p05) / 255f).coerceIn(0f, 1f)

        val lightDirection = when {
            meanLuma < 0.24f || shadowRatio > 0.52f -> LightDirection.LOW_KEY
            topLuma - centerLuma > 0.18f && centerLuma < 0.42f -> LightDirection.BACKLIT
            leftLuma - rightLuma > 0.11f -> LightDirection.LEFT_KEY
            rightLuma - leftLuma > 0.11f -> LightDirection.RIGHT_KEY
            meanLuma > 0.68f && clipRatio > 0.08f -> LightDirection.HIGH_KEY
            else -> LightDirection.BALANCED
        }

        return PhotometricStats(
            meanLuma = meanLuma,
            shadowRatio = shadowRatio,
            highlightClipRatio = clipRatio,
            dynamicRangeSpread = spread,
            warmthBias = warmthBias,
            saturationMean = satMean,
            leftLuma = leftLuma,
            rightLuma = rightLuma,
            topLuma = topLuma,
            centerLuma = centerLuma,
            lightDirection = lightDirection,
            shadowFloorLuma = (p05 / 255f).coerceIn(0f, 1f),
            greenCastBias = greenCastBias
        )
    }

    fun buildConceptProfile(
        stats: PhotometricStats,
        labels: List<String>,
        faceCount: Int,
        subjectRatio: Float,
        userSelectedStock: FilmStock = FilmStock.AUTO_EDITORIAL,
        hasConfidentMask: Boolean = true
    ): SceneConcept {
        val normLabels = labels.map { it.lowercase() }
        val hasPerson = faceCount > 0 || normLabels.any {
            it.contains("person") || it.contains("selfie") || it.contains("smile") || it.contains("portrait") || it.contains("fashion")
        }
        val hasSky = normLabels.any {
            it.contains("sky") || it.contains("cloud") || it.contains("sunset") || it.contains("sunrise") || it.contains("mountain") || it.contains("beach")
        }
        val hasFood = normLabels.any { it.contains("food") || it.contains("dish") || it.contains("coffee") || it.contains("drink") || it.contains("cuisine") }
        val hasPlant = normLabels.any { it.contains("plant") || it.contains("flower") || it.contains("tree") || it.contains("forest") || it.contains("garden") }
        val hasUrban = normLabels.any { it.contains("building") || it.contains("city") || it.contains("street") || it.contains("architecture") || it.contains("car") || it.contains("night") }
        val hasPet = normLabels.any { it.contains("dog") || it.contains("cat") || it.contains("pet") || it.contains("animal") }

        val isNight = stats.lightDirection == LightDirection.LOW_KEY
        val isBacklit = stats.lightDirection == LightDirection.BACKLIT
        val isGoldenWarm = stats.warmthBias > 0.07f && !isNight

        val recommendedStock = when {
            isNight -> FilmStock.CINEMA_CHIAROSCURO
            hasPerson -> FilmStock.RETRATO_EDITORIAL
            isGoldenWarm || hasSky || hasPlant -> FilmStock.GOLDEN_FILM
            hasFood || hasPet || hasUrban -> FilmStock.LUZ_NATURAL
            else -> FilmStock.RETRATO_EDITORIAL
        }

        val activeStock = if (userSelectedStock == FilmStock.AUTO_EDITORIAL) recommendedStock else userSelectedStock
        val adjustments = mutableListOf<String>()

        val conceptTitle: String
        val conceptSummary: String

        when {
            hasPerson && isBacklit -> {
                conceptTitle = "Retrato Editorial em Contraluz"
                conceptSummary = "Resgate limpo em luminância OKLab L, hue-lock de pele e roll-off suave sem halos de recorte."
            }
            hasPerson && isNight -> {
                conceptTitle = "Retrato Cinema Noturno"
                conceptSummary = "Contraste chiaroscuro em OKLab L, controle de altas luzes Pro-Mist 1/8 e pretos ancorados."
            }
            hasPerson && (stats.lightDirection == LightDirection.LEFT_KEY || stats.lightDirection == LightDirection.RIGHT_KEY) -> {
                conceptTitle = "Retrato Luz Lateral Editorial"
                conceptSummary = "Separação natural 85mm f/1.4 com micro-nitidez ocular e neutralização de dominante."
            }
            hasPerson -> {
                conceptTitle = if (faceCount > 1) "Ensaio Editorial em Grupo" else "Retrato Editorial Limpo"
                conceptSummary = "Curva Capture One em OKLab L, proteção de matiz de pele (15°–38°) e ótica Pro-Mist 1/8."
            }
            hasSky && isGoldenWarm -> {
                conceptTitle = "Paisagem Golden Hour"
                conceptSummary = "Recuperação natural de céu, calor dourado equilibrado e nitidez de borda a borda."
            }
            isNight -> {
                conceptTitle = "Cena Cinema Noturna"
                conceptSummary = "Chiaroscuro controlado, halation suave nas fontes pontuais e sombras limpas."
            }
            hasFood -> {
                conceptTitle = "Editorial Gastronomia & Textura"
                conceptSummary = "Micro-contraste tátil, balanço de branco neutro e brilho natural sem saturação artificial."
            }
            hasPet -> {
                conceptTitle = "Retrato Animal / Pelagem"
                conceptSummary = "Nitidez seletiva nos olhos e pelos com desfoque ótico sutil."
            }
            hasUrban -> {
                conceptTitle = "Arquitetura & Geometria"
                conceptSummary = "Micro-contraste estrutural, controle de altas luzes e graduação neutra."
            }
            else -> {
                conceptTitle = "Ensaio Editorial OKLab"
                conceptSummary = "Curva fílmica Capture One em luminância pura respeitando a luz real da cena."
            }
        }

        var exposureOffset = 0.0f
        var contrast = 1.09f
        var shadowLift = 0.02f
        var highlightRollOff = 0.36f
        var clarity = 0.14f
        var warmth = 0.008f
        var tint = 0.0f
        var saturation = 1.03f
        val skinProtection = if (hasPerson) 0.90f else 0.40f
        var faceRelight = 0.0f
        var bgDimming = 0.0f
        var bgBokeh = 0.0f
        var skyRecovery = 0.0f
        var halation = 0.0f
        var proMist = 0.10f
        var grain = 0.025f
        var vignette = 0.16f
        var mono = false

        when (activeStock) {
            FilmStock.RETRATO_EDITORIAL -> {
                contrast = 1.10f; shadowLift = 0.02f; highlightRollOff = 0.40f; clarity = 0.16f
                warmth = 0.006f; tint = -0.008f; saturation = 1.04f; proMist = 0.10f; grain = 0.022f; vignette = 0.17f
            }
            FilmStock.LUZ_NATURAL -> {
                contrast = 1.07f; shadowLift = 0.08f; highlightRollOff = 0.36f; clarity = 0.15f
                warmth = 0.004f; tint = -0.005f; saturation = 1.02f; proMist = 0.08f; grain = 0.015f; vignette = 0.14f
            }
            FilmStock.GOLDEN_FILM -> {
                contrast = 1.11f; shadowLift = 0.04f; highlightRollOff = 0.42f; clarity = 0.14f
                warmth = 0.032f; tint = 0.004f; saturation = 1.06f; halation = 0.08f; proMist = 0.12f; grain = 0.032f; vignette = 0.18f
            }
            FilmStock.CINEMA_CHIAROSCURO -> {
                contrast = 1.14f; shadowLift = 0.0f; highlightRollOff = 0.46f; clarity = 0.16f
                warmth = -0.015f; tint = -0.010f; saturation = 1.03f; halation = 0.18f; proMist = 0.12f; grain = 0.040f; vignette = 0.22f
            }
            FilmStock.PRATA_MONOCHROME -> {
                contrast = 1.18f; shadowLift = 0.0f; highlightRollOff = 0.42f
                clarity = 0.20f; saturation = 0f; halation = 0.0f; proMist = 0.08f; grain = 0.055f; vignette = 0.22f; mono = true
            }
            FilmStock.AUTO_EDITORIAL -> Unit
        }

        // Neutralize greenish fluorescent indoor cast cleanly without shifting skin orange
        if (stats.greenCastBias > 0.010f) {
            val magFix = -(stats.greenCastBias * 0.90f).coerceIn(0.012f, 0.045f)
            tint = (tint + magFix).coerceIn(-0.06f, 0.04f)
            adjustments.add("Neutralização de verde fluorescente (+magenta)")
        }

        val keySideSign = if (stats.rightLuma > stats.leftLuma + 0.03f) 1.0f else -1.0f

        if (isBacklit && hasPerson) {
            exposureOffset += 0.12f
            faceRelight += 0.16f
            bgDimming += 0.10f
            highlightRollOff = max(highlightRollOff, 0.50f)
            adjustments.add("Resgate suave de contraluz em OKLab L")
        } else if (hasPerson && stats.centerLuma < 0.44f) {
            exposureOffset += 0.06f
            faceRelight += 0.12f
            adjustments.add("Fill facial sutil + foco ótico nos olhos")
        } else if (hasPerson) {
            faceRelight += 0.06f
            adjustments.add("Hue-Lock de pele (15°–38°) + nitidez ocular")
        }

        val canPortraitBokeh = hasConfidentMask && (
            (hasPerson && faceCount > 0 && subjectRatio in 0.22f..0.92f) ||
                (hasPet && !hasSky && subjectRatio in 0.18f..0.88f)
            )
        if (canPortraitBokeh) {
            // Subtle optical lens bokeh (1–3px feathered), zero dark backdrop cutout paint
            bgBokeh = if (hasPerson) 0.42f else 0.34f
            bgDimming = max(bgDimming, if (hasPerson) 0.09f else 0.05f)
            adjustments.add("Bokeh ótico 1–3px natural + vinheta f/1.4")
        } else if (!hasSky && (hasUrban || hasFood || !hasPerson || faceCount == 0 || subjectRatio < 0.22f)) {
            bgBokeh = 0.0f
            clarity = max(clarity, 0.20f)
            adjustments.add("Plano amplo nítido (35mm f/2.0 · zero blur artificial)")
        }

        if (hasSky || stats.highlightClipRatio > 0.06f) {
            skyRecovery = if (hasSky && !hasPerson) 0.50f else 0.30f
            highlightRollOff = max(highlightRollOff, 0.48f)
            if (hasSky && !hasPerson) {
                bgBokeh = 0.0f
                clarity = max(clarity, 0.20f)
            }
            adjustments.add("Roll-off suave nas altas luzes / céu")
        }

        if (hasFood || hasUrban || hasPet) {
            clarity = max(clarity, 0.22f)
            if (hasFood) saturation = max(saturation, 1.07f)
            adjustments.add("Micro-contraste de textura seletivo")
        }

        if (isNight) {
            exposureOffset += 0.05f
            shadowLift = max(shadowLift, 0.10f)
            halation = max(halation, 0.18f)
            adjustments.add("Halation noturna e controle de sombras")
        }

        // Pure OKLab Luminance (L) dehaze + black-point anchor — never crushes R/G/B skin ratios
        val adaptiveDehaze = if (!isNight && stats.shadowFloorLuma > 0.03f) {
            ((stats.shadowFloorLuma - 0.02f) * 0.65f).coerceIn(0.015f, 0.065f)
        } else if (!isNight) {
            0.018f
        } else {
            0.0f
        }
        if (adaptiveDehaze >= 0.02f) {
            adjustments.add("Dehaze em luminância OKLab L (zero shift laranja)")
        }

        if (adjustments.isEmpty()) {
            adjustments.add("Curva OKLab Capture One + Pro-Mist 1/8")
        }

        val autoBurst = when {
            isNight -> 5
            isBacklit || hasPerson || stats.shadowRatio > 0.25f -> 3
            else -> 1
        }

        return SceneConcept(
            conceptTitle = conceptTitle,
            conceptSummary = conceptSummary,
            detectedTags = labels.take(5),
            faceCount = faceCount,
            subjectRatio = subjectRatio,
            hasConfidentMask = hasConfidentMask,
            lighting = stats.lightDirection,
            recommendedStock = activeStock,
            recommendedBurstCount = autoBurst,
            appliedAdjustments = adjustments,
            recipe = EditRecipe(
                filmStock = activeStock,
                exposureOffset = exposureOffset.coerceIn(-0.20f, 0.28f),
                contrast = contrast,
                shadowLift = shadowLift,
                highlightRollOff = highlightRollOff,
                clarity = clarity,
                warmth = warmth,
                tint = tint,
                saturation = saturation,
                skinProtection = skinProtection,
                faceRelightGain = faceRelight,
                backgroundDimming = bgDimming,
                backgroundBokeh = bgBokeh,
                skyRecovery = skyRecovery,
                halationStrength = halation,
                proMistStrength = proMist,
                grainAmount = grain,
                vignetteStrength = vignette,
                monochrome = mono,
                dehazeAmount = adaptiveDehaze,
                relight3dStrength = 0.0f,
                studioBackdropStrength = 0.0f,
                keyDirectionSign = keySideSign
            )
        )
    }
}
