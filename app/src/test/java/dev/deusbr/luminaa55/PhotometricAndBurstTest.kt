package dev.deusbr.luminaa55

import dev.deusbr.luminaa55.camera.MultiFrameBurstProcessor
import dev.deusbr.luminaa55.context.NeuralStudioEngine
import dev.deusbr.luminaa55.context.PhotometricAnalyzer
import dev.deusbr.luminaa55.context.PhotographyCrewDirector
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
import dev.deusbr.luminaa55.model.OnlineDirectionStyle
import dev.deusbr.luminaa55.model.ProcessingEngine
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
    fun backlitPortraitTriggersRelightAndEditorialLimpo() {
        val w = 64
        val h = 64
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
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
        assertEquals(FilmStock.RETRATO_EDITORIAL, concept.recommendedStock)
        assertTrue(concept.recipe.faceRelightGain >= 0.14f)
        assertTrue(concept.recipe.skyRecovery > 0.2f)
        assertEquals(0f, concept.recipe.studioBackdropStrength, 0.001f)
        assertEquals(0f, concept.recipe.relight3dStrength, 0.001f)
    }

    @Test
    fun nightUrbanSelectsCinemaChiaroscuroWithHalation() {
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
        assertEquals(FilmStock.CINEMA_CHIAROSCURO, concept.recommendedStock)
        assertTrue(concept.recipe.halationStrength >= 0.16f)
    }

    @Test
    fun burstProcessorSuppressesNoiseAndRejectsMovingGhost() {
        val w = 24
        val h = 24
        val f1 = solidFrame(w, h, 100, 100, 100)
        val f2 = solidFrame(w, h, 106, 106, 106)
        val f3 = solidFrame(w, h, 94, 94, 94)
        f3[12 * w + 12] = (0xFF shl 24) or (250 shl 16) or (10 shl 8) or 10

        val merged = MultiFrameBurstProcessor.mergeArgbBurst(listOf(f1, f2, f3), w, h, searchRadius = 1)
        val centerR = (merged[12 * w + 12] ushr 16) and 0xFF
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
            userSelectedStock = FilmStock.RETRATO_EDITORIAL,
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
            userSelectedStock = FilmStock.RETRATO_EDITORIAL,
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
    fun oklabLuminanceDehazePreservesSkinHueWithoutOrangeShift() {
        // Forehead benchmark from real A55 sample 1000407142.jpg: RGB(136, 112, 96)
        val px = solidFrame(32, 32, 136, 112, 96)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32, faceCenterX = 0.5f, faceCenterY = 0.45f)
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person", "Portrait"),
            faceCount = 1,
            subjectRatio = 0.50f,
            userSelectedStock = FilmStock.RETRATO_EDITORIAL,
            hasConfidentMask = true
        )
        val recipe = concept.recipe.copy(dehazeAmount = 0.065f, grainAmount = 0f, vignetteStrength = 0f)
        val out = EditorialShaderEngine.applyEditorialToneToPixel(
            r = 136f / 255f, g = 112f / 255f, b = 96f / 255f,
            nx = 0f, ny = 0f, x = 16, y = 16,
            mask = 1.0f, hasConfidentMask = true, recipe = recipe
        )
        val outR = (out ushr 16) and 0xFF
        val outG = (out ushr 8) and 0xFF
        val outB = out and 0xFF
        assertTrue("OKLab dehaze must NOT crush skin green channel orange (got R=$outR G=$outG B=$outB)", outG >= 96)
        assertTrue("OKLab dehaze must preserve R-B separation without orange shift (R=$outR, B=$outB)", (outR - outB) <= 50)
    }

    @Test
    fun silverGelatinMonochromeUsesMelaninSafeWeights() {
        val px = solidFrame(32, 32, 160, 120, 80)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32)
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person"),
            faceCount = 1,
            subjectRatio = 0.45f,
            userSelectedStock = FilmStock.PRATA_MONOCHROME,
            hasConfidentMask = true
        )
        assertTrue(concept.recipe.monochrome)
        val out = EditorialShaderEngine.applyEditorialToneToPixel(
            r = 160f / 255f, g = 120f / 255f, b = 80f / 255f,
            nx = 0f, ny = 0f, x = 8, y = 8,
            mask = 1.0f, hasConfidentMask = true,
            recipe = concept.recipe.copy(grainAmount = 0f, vignetteStrength = 0f)
        )
        val outR = (out ushr 16) and 0xFF
        val outB = out and 0xFF
        assertTrue("Melanin-safe Silver Gelatin B&W must keep warm luminosity around >=125 (got $outR)", outR >= 125)
        assertTrue("Silver tone neutral balance check ($outR vs $outB)", outR >= outB)
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
        assertTrue("Neutral gain byte expected near 63..64, got $neutralByte", neutralByte in 62..65)

        val liftedByte = NeuralStudioEngine.encodeGainChannel(inVal = 0.15f, enhancedVal = 0.30f, mixStrength = 0.65f)
        assertTrue("Low-light lifted gain byte ($liftedByte) must exceed neutral ($neutralByte)", liftedByte > neutralByte + 25)

        assertEquals(0f, NeuralStudioEngine.computeZeroDceAdaptiveMix(meanLuma = 0.49f, shadowRatio = 0.18f), 0.0001f)
        assertTrue(NeuralStudioEngine.computeZeroDceAdaptiveMix(meanLuma = 0.16f, shadowRatio = 0.60f) > 0.5f)
    }

    @Test
    fun pureBlackRemainsAnchoredAndCloseSelfieKeepsSubtleOpticalBokehWithZeroBackdropPaint() {
        val px = solidFrame(32, 32, 128, 128, 128)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32, faceCenterX = 0.5f, faceCenterY = 0.45f)
        val closeSelfieConcept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person", "Selfie"),
            faceCount = 1,
            subjectRatio = 0.80f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL,
            hasConfidentMask = true
        )
        assertTrue("Close selfie (80% frame) must keep subtle optical bokeh active", closeSelfieConcept.recipe.backgroundBokeh in 0.30f..0.60f)
        assertEquals("Zero fake black backdrop cutout allowed", 0f, closeSelfieConcept.recipe.studioBackdropStrength, 0.001f)
        assertTrue("Editorial Limpo enables gentle Black Pro-Mist 1/8 highlight bloom", closeSelfieConcept.recipe.proMistStrength in 0.06f..0.14f)

        val blackPixel = EditorialShaderEngine.applyEditorialToneToPixel(
            r = 0f, g = 0f, b = 0f,
            nx = 0f, ny = 0f, x = 4, y = 4,
            mask = 1f, hasConfidentMask = true,
            recipe = closeSelfieConcept.recipe.copy(shadowLift = 0.25f, grainAmount = 0f, vignetteStrength = 0f)
        )
        val blackR = (blackPixel ushr 16) and 0xFF
        assertTrue("Pure black must stay deep black (<= 2), got $blackR", blackR <= 2)
    }

    @Test
    fun contextEngineDifferentiatesIndoorPortraitFromOutdoorLandscape() {
        val px = solidFrame(32, 32, 122, 118, 110)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32, faceCenterX = 0.5f, faceCenterY = 0.45f)

        val indoorPortrait = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person", "Portrait"),
            faceCount = 1,
            subjectRatio = 0.65f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL,
            hasConfidentMask = true
        )
        assertEquals(0f, indoorPortrait.recipe.relight3dStrength, 0.001f)
        assertEquals(0f, indoorPortrait.recipe.studioBackdropStrength, 0.001f)
        assertTrue(indoorPortrait.recipe.backgroundBokeh >= 0.35f)

        val skyLandscape = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Sky", "Mountain"),
            faceCount = 0,
            subjectRatio = 0.25f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL,
            hasConfidentMask = true
        )
        assertEquals(0f, skyLandscape.recipe.studioBackdropStrength, 0.001f)
        assertEquals(0f, skyLandscape.recipe.backgroundBokeh, 0.001f)
        assertTrue(skyLandscape.recipe.skyRecovery >= 0.45f)
    }

    @Test
    fun photographyCrewDirectorCompiles5BlockPromptAndAntiAiLocks() {
        val px = solidFrame(32, 32, 118, 114, 108)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32, faceCenterX = 0.5f, faceCenterY = 0.45f)
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Person", "Portrait"),
            faceCount = 1,
            subjectRatio = 0.55f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL,
            hasConfidentMask = true
        )
        val sheet = PhotographyCrewDirector.compileCrewSheet(
            concept = concept,
            stats = stats,
            labels = listOf("Person"),
            faceCount = 1,
            engine = ProcessingEngine.OFFLINE_LOCAL,
            hasNeuralAlpha = true
        )
        assertTrue(sheet.cameraLensSpec.contains("85mm"))
        assertTrue(sheet.lightingPlan.contains("Hue-Lock"))
        assertTrue(sheet.antiAiAuditStatus.contains("zero halo"))

        val prompt = PhotographyCrewDirector.buildGeminiCanonical5BlockPrompt(sheet, concept, OnlineDirectionStyle.STUDIO_EDITORIAL)
        assertTrue(prompt.contains("[1. IDENTITY & STRICT BIOMETRIC LOCK]"))
        assertTrue(prompt.contains("[5. ANTI-AI AUDITOR & REALISM MANDATE]"))
    }

    @Test
    fun environmentalWideSceneWithSmallOrNoFaceNeverBlursSubjectOrBackground() {
        val px = solidFrame(32, 32, 95, 96, 98)
        val stats = PhotometricAnalyzer.analyzeArgbPixels(px, 32, 32)
        val officeScene = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = listOf("Computer", "Desk", "Room"),
            faceCount = 0,
            subjectRatio = 0.14f,
            userSelectedStock = FilmStock.AUTO_EDITORIAL,
            hasConfidentMask = true
        )
        assertEquals("Wide office scene must NOT blur subjects behind boxes", 0f, officeScene.recipe.backgroundBokeh, 0.001f)
        assertEquals("Wide office scene must keep zero studio backdrop paint", 0f, officeScene.recipe.studioBackdropStrength, 0.001f)
    }
}
