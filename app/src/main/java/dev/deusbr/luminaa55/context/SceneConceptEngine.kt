package dev.deusbr.luminaa55.context

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.deusbr.luminaa55.model.AutoLiveDecision
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.ProcessingEngine
import dev.deusbr.luminaa55.model.SceneConcept
import dev.deusbr.luminaa55.model.StudioLightingHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.max

data class ConceptAnalysisOutput(
    val concept: SceneConcept,
    val subjectMask: Bitmap,
    val depthMap: Bitmap?,
    val neuralGainMap: Bitmap?,
    val lightingHint: StudioLightingHint,
    val aiModelsUsed: List<String>,
    val hasConfidentMask: Boolean,
    val stats: PhotometricStats,
    val labels: List<String>,
    val faceCount: Int
)

class SceneConceptEngine(private val context: Context) {

    private val neuralEngine = NeuralStudioEngine(context)

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
    }

    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build()
        )
    }

    private val subjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }

    private val selfieSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    fun warmUp() {
        runCatching {
            val installClient = ModuleInstall.getClient(context)
            val request = ModuleInstallRequest.newBuilder()
                .addApi(subjectSegmenter)
                .build()
            installClient.installModules(request)
        }
        runCatching { neuralEngine.warmUpModels() }
    }

    suspend fun analyze(
        bitmap: Bitmap,
        selectedStock: FilmStock = FilmStock.AUTO_EDITORIAL,
        engine: ProcessingEngine = ProcessingEngine.OFFLINE_LOCAL
    ): ConceptAnalysisOutput = withContext(Dispatchers.Default) {
        val analysisBmp = scaleDown(bitmap, 512)
        val inputImage = InputImage.fromBitmap(analysisBmp, 0)

        val facesDeferred = async {
            runCatching { faceDetector.process(inputImage).await() }.getOrDefault(emptyList())
        }
        val labelsDeferred = async {
            runCatching {
                imageLabeler.process(inputImage).await()
                    .sortedByDescending { it.confidence }
                    .map { it.text }
            }.getOrDefault(emptyList())
        }
        val subjectSegDeferred = async {
            runCatching {
                val res = subjectSegmenter.process(inputImage).await()
                val mask = res.foregroundConfidenceMask ?: return@runCatching null
                floatBufferMaskToBitmap(mask, analysisBmp.width, analysisBmp.height)
            }.getOrNull()
        }

        val pixels = IntArray(analysisBmp.width * analysisBmp.height)
        analysisBmp.getPixels(pixels, 0, analysisBmp.width, 0, 0, analysisBmp.width, analysisBmp.height)

        val faces = facesDeferred.await()
        val labels = labelsDeferred.await()
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val faceCx = primaryFace?.let { it.boundingBox.exactCenterX() / analysisBmp.width }
        val faceCy = primaryFace?.let { it.boundingBox.exactCenterY() / analysisBmp.height }

        val photoStats = PhotometricAnalyzer.analyzeArgbPixels(pixels, analysisBmp.width, analysisBmp.height, faceCx, faceCy)

        // Run bundled deep neural studio engine (MODNet 512 Guided + MultiClassSeg + MiDaS Depth + Zero-DCE)
        val hasSkyHint = labels.any {
            val l = it.lowercase()
            l.contains("sky") || l.contains("mountain") || l.contains("landscape")
        } && faces.isEmpty()
        val neuralDeferred = async {
            neuralEngine.inferStudioMaps(
                source = analysisBmp,
                meanLuma = photoStats.meanLuma,
                shadowRatio = photoStats.shadowRatio,
                allowDepthBlur = !hasSkyHint
            )
        }
        val subjectSegBmp = subjectSegDeferred.await()
        val neuralMaps = neuralDeferred.await()

        val selfieFallbackDeferred = if (subjectSegBmp == null && !neuralMaps.hasConfidentSemantic) {
            runCatching {
                val res = selfieSegmenter.process(inputImage).await()
                selfieMaskToBitmap(res.buffer, res.width, res.height, analysisBmp.width, analysisBmp.height)
            }.getOrNull()
        } else null

        val aw = analysisBmp.width.toFloat()
        val ah = analysisBmp.height.toFloat()
        val leftEye = primaryFace?.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = primaryFace?.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val lightingHint = if (primaryFace != null) {
            StudioLightingHint(
                hasFace = true,
                faceCenterX = (primaryFace.boundingBox.exactCenterX() / aw).coerceIn(0f, 1f),
                faceCenterY = (primaryFace.boundingBox.exactCenterY() / ah).coerceIn(0f, 1f),
                faceRadius = (max(primaryFace.boundingBox.width(), primaryFace.boundingBox.height()) / max(aw, ah)).coerceIn(0.05f, 0.55f),
                leftEyeX = leftEye?.let { (it.x / aw).coerceIn(0f, 1f) } ?: -1f,
                leftEyeY = leftEye?.let { (it.y / ah).coerceIn(0f, 1f) } ?: -1f,
                rightEyeX = rightEye?.let { (it.x / aw).coerceIn(0f, 1f) } ?: -1f,
                rightEyeY = rightEye?.let { (it.y / ah).coerceIn(0f, 1f) } ?: -1f
            )
        } else {
            StudioLightingHint()
        }

        // Priority: MultiClassSeg 256px (for skin/detail masking & soft bokeh guard ONLY — never cutout background painting!)
        val candidateSemantic = when {
            neuralMaps.hasConfidentSemantic && neuralMaps.semanticBitmap != null -> {
                subjectSegBmp?.recycle()
                selfieFallbackDeferred?.recycle()
                smoothMaskEdges(upscaleSmoothMask(neuralMaps.semanticBitmap, 640))
            }
            subjectSegBmp != null -> {
                neuralMaps.semanticBitmap?.recycle()
                selfieFallbackDeferred?.recycle()
                smoothMaskEdges(upscaleSmoothMask(subjectSegBmp, 640))
            }
            selfieFallbackDeferred != null -> {
                neuralMaps.semanticBitmap?.recycle()
                smoothMaskEdges(upscaleSmoothMask(selfieFallbackDeferred, 640))
            }
            else -> {
                neuralMaps.semanticBitmap?.recycle()
                null
            }
        }

        val coverage = if (candidateSemantic != null) estimateMaskCoverage(candidateSemantic) else 1.0f
        val hasMask = candidateSemantic != null && coverage in 0.05f..0.92f
        val finalSemanticMask = if (hasMask && candidateSemantic != null) {
            candidateSemantic
        } else {
            candidateSemantic?.recycle()
            buildNeutralForegroundMask(16, 16)
        }

        if (analysisBmp !== bitmap) analysisBmp.recycle()

        val baseConcept = PhotometricAnalyzer.buildConceptProfile(
            stats = photoStats,
            labels = labels,
            faceCount = faces.size,
            subjectRatio = coverage,
            userSelectedStock = selectedStock,
            hasConfidentMask = hasMask || neuralMaps.hasDepthMap
        )

        val extraNotes = baseConcept.appliedAdjustments.toMutableList()
        extraNotes.add("Ciência de cor OKLab Capture One (Luminância pura)")
        if (neuralMaps.hasDepthMap && baseConcept.recipe.backgroundBokeh > 0.01f) {
            extraNotes.add("Desfoque ótico sutil (1-3px) sem halo")
        }
        if ("Zero-DCE" in neuralMaps.modelsExecuted) {
            extraNotes.add("Rede Neural Zero-DCE de baixa luz")
        }

        val crewSheet = PhotographyCrewDirector.compileCrewSheet(
            concept = baseConcept,
            stats = photoStats,
            labels = labels,
            faceCount = faces.size,
            engine = engine,
            hasNeuralAlpha = false
        )

        val finalConcept = baseConcept.copy(
            hasDepthMap = neuralMaps.hasDepthMap,
            neuralBoostStrength = NeuralStudioEngine.computeZeroDceAdaptiveMix(photoStats.meanLuma, photoStats.shadowRatio),
            appliedAdjustments = extraNotes,
            crewSheet = crewSheet
        )

        ConceptAnalysisOutput(
            concept = finalConcept,
            subjectMask = finalSemanticMask,
            depthMap = neuralMaps.depthBlurBitmap,
            neuralGainMap = neuralMaps.neuralGainBitmap,
            lightingHint = lightingHint,
            aiModelsUsed = neuralMaps.modelsExecuted,
            hasConfidentMask = hasMask || neuralMaps.hasDepthMap,
            stats = photoStats,
            labels = labels,
            faceCount = faces.size
        )
    }

    suspend fun quickLiveHint(bitmap: Bitmap): AutoLiveDecision = withContext(Dispatchers.Default) {
        val small = scaleDown(bitmap, 224)
        val input = InputImage.fromBitmap(small, 0)
        val faces = runCatching { faceDetector.process(input).await() }.getOrDefault(emptyList())
        val labels = runCatching {
            imageLabeler.process(input).await().take(3).map { it.text }
        }.getOrDefault(emptyList())

        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val face = faces.firstOrNull()
        val stats = PhotometricAnalyzer.analyzeArgbPixels(
            pixels,
            small.width,
            small.height,
            face?.let { it.boundingBox.exactCenterX() / small.width },
            face?.let { it.boundingBox.exactCenterY() / small.height }
        )
        if (small !== bitmap) small.recycle()

        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = labels,
            faceCount = faces.size,
            subjectRatio = if (faces.isNotEmpty()) 0.32f else 0.0f,
            hasConfidentMask = faces.isNotEmpty()
        )
        AutoLiveDecision(
            headline = concept.conceptTitle,
            subtitle = "${concept.recommendedStock.displayName} · IA Local Ativa · ${concept.lighting.labelPt}",
            recommendedStock = concept.recommendedStock,
            recommendedBurst = concept.recommendedBurstCount
        )
    }

    private fun floatBufferMaskToBitmap(buffer: FloatBuffer, w: Int, h: Int): Bitmap {
        buffer.rewind()
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val conf = if (buffer.hasRemaining()) buffer.get().coerceIn(0f, 1f) else 0f
            val g = (conf * 255f).toInt()
            // Pack R=fgSubject, G=0 (no separate skin class), B=0
            out[i] = Color.argb(255, g, 0, 0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun selfieMaskToBitmap(buffer: ByteBuffer, mw: Int, mh: Int, targetW: Int, targetH: Int): Bitmap {
        buffer.rewind()
        val fb = buffer.asFloatBuffer()
        val out = IntArray(mw * mh)
        for (i in 0 until mw * mh) {
            val conf = if (fb.hasRemaining()) fb.get().coerceIn(0f, 1f) else 0f
            val g = (conf * 255f).toInt()
            out[i] = Color.argb(255, g, (g * 0.65f).toInt(), (g * 0.35f).toInt())
        }
        val raw = Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
        if (mw == targetW && mh == targetH) return raw
        val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
        raw.recycle()
        return scaled
    }

    private fun upscaleSmoothMask(src: Bitmap, targetMaxDim: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge >= targetMaxDim) return src
        val s = targetMaxDim.toFloat() / longEdge
        val up = Bitmap.createScaledBitmap(
            src,
            max(16, (src.width * s).toInt()),
            max(16, (src.height * s).toInt()),
            true
        )
        if (up !== src) src.recycle()
        return up
    }

    private fun smoothMaskEdges(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        mask.recycle()

        // 2-pass separable box blur (sigma ~ 4px at 640px -> ~14px silky optical feather on 3:4 portrait)
        // Only feathers R (fgSubject) wide to eliminate 256px stair-step cutouts around curly hair/shoulders,
        // while keeping G (skin) and B (hair/clothes detail) tighter so facial retouch stays sharp.
        var rBuf = IntArray(w * h)
        val gBuf = IntArray(w * h)
        var bBuf = IntArray(w * h)
        for (i in src.indices) {
            val c = src[i]
            rBuf[i] = (c ushr 16) and 0xFF
            gBuf[i] = (c ushr 8) and 0xFF
            bBuf[i] = c and 0xFF
        }

        rBuf = separableBoxBlur(rBuf, w, h, radius = 5)
        rBuf = separableBoxBlur(rBuf, w, h, radius = 5)
        bBuf = separableBoxBlur(bBuf, w, h, radius = 2)

        val dst = IntArray(w * h)
        for (i in dst.indices) {
            dst[i] = Color.argb(
                255,
                rBuf[i].coerceIn(0, 255),
                gBuf[i].coerceIn(0, 255),
                bBuf[i].coerceIn(0, 255)
            )
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun separableBoxBlur(input: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val horiz = IntArray(w * h)
        val win = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -radius..radius) {
                sum += input[row + k.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                horiz[row + x] = sum / win
                val addX = (x + radius + 1).coerceAtMost(w - 1)
                val subX = (x - radius).coerceAtLeast(0)
                sum += input[row + addX] - input[row + subX]
            }
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) {
                sum += horiz[k.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / win
                val addY = (y + radius + 1).coerceAtMost(h - 1)
                val subY = (y - radius).coerceAtLeast(0)
                sum += horiz[addY * w + x] - horiz[subY * w + x]
            }
        }
        return out
    }

    private fun buildNeutralForegroundMask(w: Int, h: Int): Bitmap {
        val out = IntArray(w * h) { Color.argb(255, 255, 0, 0) }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun estimateMaskCoverage(mask: Bitmap): Float {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        var fg = 0
        for (p in pixels) {
            if (((p ushr 16) and 0xFF) > 128) fg++
        }
        return fg.toFloat() / max(1, pixels.size)
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            max(16, (src.width * scale).toInt()),
            max(16, (src.height * scale).toInt()),
            true
        )
    }

    fun close() {
        runCatching { faceDetector.close() }
        runCatching { imageLabeler.close() }
        runCatching { subjectSegmenter.close() }
        runCatching { selfieSegmenter.close() }
        neuralEngine.close()
    }
}
