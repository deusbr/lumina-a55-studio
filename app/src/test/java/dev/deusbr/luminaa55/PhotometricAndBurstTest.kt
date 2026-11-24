package dev.deusbr.luminaa55

import dev.deusbr.luminaa55.camera.MultiFrameBurstProcessor
import dev.deusbr.luminaa55.context.NeuralStudioEngine
import dev.deusbr.luminaa55.context.PhotometricAnalyzer
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
import dev.deusbr.luminaa55.shader.EditorialShaderEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotometricAndBurstTest {

    private fun solidFrame(w: Int, h: Int, r: Int, g: Int, b: Int): IntArray {
        val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return IntArray(w * h) { px }
    }

    @Test
    fun backlitPortraitTriggersRelightAndPortra400() {
        val w = 64
        val h = 64
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Bright sky top, dark backlit center face
                val v = if (y < 24) 240 else 45
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, w, h, faceCenterX = 0.5f, faceCenterY = 0.55f)
        assertEquals(LightDirection.BACKLIT, stats.lightDirection)

        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person", "Sky"),
            faceCount = 1,
            subjectRatio = 0.35f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL
        )
        assertEquals(FilmStock.PORTRA_400, concept.recommendedStock)
        assertTrue(concept.recipe.faceRelightGain >= 0.25f)
        assertTrue(concept.recipe.skyRecovery > 0.2f)
    }

    @Test
    fun nightUrbanSelectsCineStill800TWithHalation() {
        val px = solidFrame(48, 48, 18, 22, 34)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 48, 48)
        assertEquals(LightDirection.LOW_KEY, stats.lightDirection)

        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("City", "Street", "Night"),
            faceCount = 0,
            subjectRatio = 0f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL
        )
        assertEquals(FilmStock.CINESTILL_800T, concept.recommendedStock)
        assertTrue(concept.recipe.halationStrength >= 0.28f)
    }

    @Test
    fun burstProcessorSuppressesNoiseAndRejectsMovingGhost() {
        val w = 24
        val h = 24
        val f1 = solidFrame(w, h, 100, 100, 100)
        val f2 = solidFrame(w, h, 106, 106, 106)
        // Introduce strong moving ghost object in center of f3
        val f3 = solidFrame(w, h, 94, 94, 94)
        f3[12 * w + 12] = (0xFF shl 24) or (250 shl 16) or (10 shl 8) or 10

        val merged = MultiFrameBurstProcessor.mergeArgbBurst(listOf(f1, f2, f3), w, h, searchRadius = 1)
        val centerR = (merged[12 * w + 12] ushr 16) and 0xFF
        // Ghost pixel (250) must be rejected so center stays close to 103, not 152
        assertTrue("Expected ghost-rejected red channel < 115, got $centerR", centerR < 115)
    }

    @Test
    fun missingSegmentationDisablesBackgroundBlurAndDimming() {
        val px = solidFrame(32, 32, 128, 128, 128)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32)
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person"),
            faceCount = 1,
            subjectRatio = 1.0f,
            userSelectedStock = FilmStock.PORTRA_400,
            hasConfidentMask = false
        )
        assertEquals(0f, concept.recipe.backgroundBokeh, 0.0001f)
        assertEquals(0f, concept.recipe.backgroundDimming, 0.0001f)
    }

    @Test
    fun toneCurvePreservesMidtonesAndRelightsForegroundNotBackground() {
        val px = solidFrame(32, 32, 128, 128, 128)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32, faceCenterX = 0.5f, faceCenterY = 0.5f)
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person"),
            faceCount = 1,
            subjectRatio = 0.35f,
            userSelectedStock = FilmStock.PORTRA_400,
            hasConfidentMask = true
        )
        val recipe = concept.recipe.copy(faceRelightGain = 0.25f, backgroundDimming = 0.12f, grainAmount = 0f, vignetteStrength = 0f)

        val fgOut = EditorialShaderEngine.applyEditorialToneToPixel(
            r = 0.5f, g = 0.5f, b = 0.5f,
            nx = 0f, ny = 0f, x = 8, y = 8,
            mask = 1.0f, hasConfidentMask = true, recipe = recipe
        )
        val bgOut = EditorialShaderEngine.applyEditorialToneToPixel(
            r = 0.5f, g = 0.5f, b = 0.5f,
            nx = 0f, ny = 0f, x = 8, y = 8,
            mask = 0.0f, hasConfidentMask = true, recipe = recipe
        )
        val fgR = (fgOut ushr 16) and 0xFF
        val bgR = (bgOut ushr 16) and 0xFF
        assertTrue("Foreground ($fgR) must be brighter than background ($bgR)", fgR > bgR)
        assertTrue("Midtone foreground ($fgR) must not be crushed dark", fgR in 120..180)
    }

    @Test
    fun shadowChromaDenoiseSmoothsDarkNoiseWithoutShiftingFlatBrightness() {
        val w = 16
        val h = 16
        val noisy = solidFrame(w, h, 36, 36, 36)
        noisy[8 * w + 8] = (0xFF shl 24) or (48 shl 16) or (28 shl 8) or 36
        val cleaned = MultiFrameBurstProcessor.denoiseShadowChroma(noisy, w, h)
        val cr = (cleaned[8 * w + 8] ushr 16) and 0xFF
        assertTrue("Expected smoothed dark pixel between 36 and 46, got $cr", cr in 36..46)
    }

    @Test
    fun autoModeRecommendsHigherBurstInLowLightAndLighterInDaylight() {
        val darkPx = solidFrame(32, 32, 16, 18, 24)
        val darkStats = PhotometricAnalyzer.analyzeArgbPixels(darkPx, 32, 32)
        val nightConcept = PhotometricAnalyzer.buildConceptProfile(
            stats = darkStats, labels = listOf("Night"), faceCount = 0, subjectRatio = 1f, hasConfidentMask = false
        )
        assertEquals(5, nightConcept.recommendedBurstCount)

        val dayPx = solidFrame(32, 32, 185, 190, 195)
        val dayStats = PhotometricAnalyzer.analyzeArgbPixels(dayPx, 32, 32)
        val dayConcept = PhotometricAnalyzer.buildConceptProfile(
            stats = dayStats, labels = listOf("Landscape"), faceCount = 0, subjectRatio = 1f, hasConfidentMask = false
        )
        assertEquals(1, dayConcept.recommendedBurstCount)
    }

    @Test
    fun midasDepthCocKeepsForegroundZeroBlurAndBlursDistantBackground() {
        val w = 16
        val h = 16
        val rawInvDepth = FloatArray(w * h)
        val fgMask = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val isSubject = x in 5..10 && y in 4..12
                // Inverse depth: near subject = 10f, far wall = 1f
                rawInvDepth[i] = if (isSubject) 10f else 1f
                fgMask[i] = if (isSubject) 0.95f else 0.05f
            }
        }
        val coc = NeuralStudioEngine.computeDepthCocWeights(rawInvDepth, fgMask, w, h)
        assertEquals(0f, coc.cocBlur[8 * w + 8], 0.001f)
        assertTrue("Distant background CoC (${coc.cocBlur[0]}) must be > 0.8", coc.cocBlur[0] > 0.8f)
    }

    @Test
    fun zeroDceGainEncodingBoostsLowLightAndPreservesNeutral() {
        val neutralByte = NeuralStudioEngine.encodeGainChannel(inVal = 0.5f, enhancedVal = 0.5f, mixStrength = 0.5f)
        // gain=1.0 -> (1.0 - 0.5) / 2.0 = 0.25 -> ~63..64
        assertTrue("Neutral gain byte expected near 63..64, got $neutralByte", neutralByte in 62..65)

        val liftedByte = NeuralStudioEngine.encodeGainChannel(inVal = 0.15f, enhancedVal = 0.30f, mixStrength = 0.78f)
        assertTrue("Low-light lifted gain byte ($liftedByte) must exceed neutral ($neutralByte)", liftedByte > neutralByte + 30)
    }
}
