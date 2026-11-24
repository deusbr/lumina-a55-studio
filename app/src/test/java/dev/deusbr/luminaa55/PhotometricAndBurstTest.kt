package dev.deusbr.luminaa55

import dev.deusbr.luminaa55.camera.MultiFrameBurstProcessor
import dev.deusbr.luminaa55.context.PhotometricAnalyzer
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
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
}
