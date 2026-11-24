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
 */
object PhotometricAnalyzer {

    fun analyzeArgbPixels(pixels: IntArray, width: Int, height: Int, faceCenterX: Float? = null, faceCenterY: Float? = null): PhotometricStats {
        if (pixels.isEmpty() || width <= 0 || height <= 0) {
            return PhotometricStats(0.5f, 0f, 0f, 0.5f, 0f, 0.3f, 0.5f, 0.5f, 0.5f, 0.5f, LightDirection.BALANCED)
        }

        val hist = IntArray(256)
        var lumaSum = 0.0
        var shadowCount = 0
        var clipCount = 0
        var warmthSum = 0.0
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
        val satMean = (satSum / n).toFloat()
        val leftLuma = if (leftN > 0) (leftSum / leftN).toFloat() else meanLuma
        val rightLuma = if (rightN > 0) (rightSum / rightN).toFloat() else meanLuma
        val topLuma = if (topN > 0) (topSum / topN).toFloat() else meanLuma
        val centerLuma = if (centerN > 0) (centerSum / centerN).toFloat() else meanLuma

        // 5th to 95th percentile dynamic spread
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
            shadowFloorLuma = (p05 / 255f).coerceIn(0f, 1f)
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
        val hasPerson = faceCount > 0 || normLabels.any { it.contains("person") || it.contains("selfie") || it.contains("smile") || it.contains("portrait") || it.contains("fashion") }
        val hasSky = normLabels.any { it.contains("sky") || it.contains("cloud") || it.contains("sunset") || it.contains("sunrise") || it.contains("mountain") || it.contains("beach") }
        val hasFood = normLabels.any { it.contains("food") || it.contains("dish") || it.contains("coffee") || it.contains("drink") || it.contains("cuisine") }
        val hasPlant = normLabels.any { it.contains("plant") || it.contains("flower") || it.contains("tree") || it.contains("forest") || it.contains("garden") }
        val hasUrban = normLabels.any { it.contains("building") || it.contains("city") || it.contains("street") || it.contains("architecture") || it.contains("car") || it.contains("night") }
        val hasPet = normLabels.any { it.contains("dog") || it.contains("cat") || it.contains("pet") || it.contains("animal") }

        val isNight = stats.lightDirection == LightDirection.LOW_KEY
        val isBacklit = stats.lightDirection == LightDirection.BACKLIT
        val isGoldenWarm = stats.warmthBias > 0.07f && !isNight

        val recommendedStock = when {
            isNight && (hasUrban || !hasPerson) -> FilmStock.CINESTILL_800T
            hasPerson -> FilmStock.PORTRA_400
            isGoldenWarm || hasSky || hasPlant -> FilmStock.KODAK_GOLD_200
            hasFood || hasPet -> FilmStock.HASSELBLAD_NATURAL
            else -> FilmStock.PORTRA_400
        }

        val activeStock = if (userSelectedStock == FilmStock.AUTO_EDITORIAL) recommendedStock else userSelectedStock
        val adjustments = mutableListOf<String>()

        val conceptTitle: String
        val conceptSummary: String

        when {
            hasPerson && isBacklit -> {
                conceptTitle = "Retrato Editorial em Contraluz"
                conceptSummary = "Sujeito escuro contra fundo luminoso: recuperamos a luz facial com proteção de pele e halation suave nas bordas."
            }
            hasPerson && isNight -> {
                conceptTitle = "Retrato Noturno Cinemático"
                conceptSummary = "Baixa luz detectada: preservamos os pretos profundos com levantamento limpo no rosto e brilho suave de película 800T."
            }
            hasPerson && (stats.lightDirection == LightDirection.LEFT_KEY || stats.lightDirection == LightDirection.RIGHT_KEY) -> {
                conceptTitle = "Retrato Luz Lateral (Chiaroscuro)"
                conceptSummary = "Direção de luz marcada (${stats.lightDirection.labelPt}): acentuamos o volume sem estourar altas luzes na pele."
            }
            hasPerson -> {
                conceptTitle = if (faceCount > 1) "Ensaio Editorial em Grupo" else "Retrato Editorial Natural"
                conceptSummary = "Pele protegida no vetorscópio, separação tonal suave do fundo e contraste fílmico Portra."
            }
            hasSky && isGoldenWarm -> {
                conceptTitle = "Paisagem Golden Hour"
                conceptSummary = "Recuperação de céu com curva ACES, calor solar equilibrado e sombras limpas sem aspecto artificial."
            }
            isNight -> {
                conceptTitle = "Cena Noturna Urbana"
                conceptSummary = "Contraste cinematográfico com controle de ruído, halation nas luzes pontuais e sombras densas."
            }
            hasFood -> {
                conceptTitle = "Editorial Gastronomia & Textura"
                conceptSummary = "Micro-contraste de textura elevado, brilhos suaves e cores naturais apetitosas sem excesso de saturação."
            }
            hasPet -> {
                conceptTitle = "Retrato Animal / Pelagem"
                conceptSummary = "Nitidez seletiva nos olhos/pelagem com desfoque orgânico de fundo."
            }
            hasUrban -> {
                conceptTitle = "Arquitetura & Geometria"
                conceptSummary = "Micro-contraste estrutural, controle de altas luzes e graduação editorial limpa."
            }
            else -> {
                conceptTitle = "Ensaio Editorial Adaptativo"
                conceptSummary = "Curva fílmica balanceada conforme histograma e direção de luz da cena."
            }
        }

        // Base recipe per stock (clean black point, studio contrast, zero unsolicited veil/halation)
        var exposureOffset = 0.0f
        var contrast = 1.10f
        var shadowLift = 0.0f
        var highlightRollOff = 0.36f
        var clarity = 0.14f
        var warmth = 0.015f
        var tint = 0.0f
        var saturation = 1.04f
        val skinProtection = if (hasPerson) 0.88f else 0.35f
        var faceRelight = 0.0f
        var bgDimming = 0.0f
        var bgBokeh = 0.0f
        var skyRecovery = 0.0f
        var halation = 0.0f
        var proMist = 0.0f
        var grain = 0.03f
        var vignette = 0.14f
        var mono = false

        when (activeStock) {
            FilmStock.PORTRA_400 -> {
                contrast = 1.14f; shadowLift = 0.0f; highlightRollOff = 0.38f; clarity = 0.18f
                warmth = 0.008f; tint = 0.002f; saturation = 1.05f; proMist = 0.0f; grain = 0.025f; vignette = 0.16f
            }
            FilmStock.CINESTILL_800T -> {
                contrast = 1.14f; shadowLift = 0.0f; highlightRollOff = 0.42f; clarity = 0.14f
                warmth = -0.04f; tint = -0.01f; saturation = 1.06f; halation = 0.28f; proMist = 0.04f; grain = 0.06f; vignette = 0.20f
            }
            FilmStock.HASSELBLAD_NATURAL -> {
                contrast = 1.12f; shadowLift = 0.0f; highlightRollOff = 0.35f; clarity = 0.18f
                warmth = 0.0f; saturation = 1.02f; proMist = 0.0f; grain = 0.01f; vignette = 0.10f
            }
            FilmStock.KODAK_GOLD_200 -> {
                contrast = 1.11f; shadowLift = 0.0f; highlightRollOff = 0.36f; clarity = 0.13f
                warmth = 0.05f; tint = 0.008f; saturation = 1.06f; halation = 0.0f; proMist = 0.0f; grain = 0.04f; vignette = 0.14f
            }
            FilmStock.LEICA_MONOCHROME -> {
                contrast = 1.22f; shadowLift = 0.0f; highlightRollOff = 0.40f
                clarity = 0.20f; saturation = 0f; halation = 0.0f; proMist = 0.0f; grain = 0.07f; vignette = 0.24f; mono = true
            }
            FilmStock.AUTO_EDITORIAL -> Unit
        }

        // Context-driven adaptive adjustments (background blur/dimming ONLY when a real confident mask exists)
        if (isBacklit && hasPerson) {
            faceRelight += 0.26f
            if (hasConfidentMask) bgDimming += 0.14f
            highlightRollOff = max(highlightRollOff, 0.50f)
            adjustments.add("Relight facial em contraluz (+26%)")
        } else if (hasPerson && stats.centerLuma < 0.42f) {
            faceRelight += 0.16f
            adjustments.add("Abertura seletiva de rosto (+16%)")
        } else if (hasPerson) {
            faceRelight += 0.08f
            adjustments.add("Key light de estúdio no rosto (+8%)")
        }

        if (hasConfidentMask && subjectRatio in 0.05f..0.90f && (hasPerson || hasPet || !hasSky)) {
            bgBokeh = if (hasPerson) 0.92f else 0.65f
            bgDimming = max(bgDimming, if (hasPerson) 0.28f else 0.14f)
            adjustments.add("Bokeh ótico f/1.8 de estúdio + separação de fundo")
        }

        if (hasPerson) {
            adjustments.add("Proteção de tom de pele e micro-nitidez ocular")
        }

        if (hasSky || stats.highlightClipRatio > 0.06f) {
            skyRecovery = 0.32f
            highlightRollOff = max(highlightRollOff, 0.48f)
            adjustments.add("Recuperação de altas luzes / céu (roll-off suave)")
        }

        if (hasFood || hasUrban || hasPet) {
            clarity = max(clarity, 0.16f)
            adjustments.add("Micro-contraste de textura seletivo")
        }

        if (isNight) {
            exposureOffset += 0.06f
            shadowLift = 0.18f
            halation = max(halation, 0.18f)
            adjustments.add("Halation noturna e controle de sombras")
        }

        if (stats.dynamicRangeSpread < 0.48f) {
            contrast += 0.08f
            adjustments.add("Dehaze e ancoragem de ponto preto")
        }

        if (adjustments.isEmpty()) {
            adjustments.add("Curva fílmica natural + controle de altas luzes")
        }

        val autoBurst = when {
            isNight -> 5
            isBacklit || hasPerson || stats.shadowRatio > 0.25f -> 3
            else -> 1
        }

        val adaptiveDehaze = if (!isNight && stats.shadowFloorLuma > 0.03f) {
            ((stats.shadowFloorLuma - 0.02f) * 0.72f).coerceIn(0.015f, 0.075f)
        } else if (!isNight) {
            0.018f
        } else {
            0.0f
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
                exposureOffset = exposureOffset,
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
                dehazeAmount = adaptiveDehaze
            )
        )
    }
}
