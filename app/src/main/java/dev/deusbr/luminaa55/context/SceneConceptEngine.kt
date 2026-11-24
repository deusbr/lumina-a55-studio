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
        selectedStock: FilmStock = FilmStock.AUTO_EDITORIAL
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

        // Run bundled deep neural studio engine (Zero-DCE + MiDaS Depth + Multi-Class Portrait Seg)
        val neuralDeferred = async {
            neuralEngine.inferStudioMaps(analysisBmp, photoStats.meanLuma, photoStats.shadowRatio)
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

        // Priority: Multi-class semantic portrait map (has hair/skin/clothes!), else Play Services Subject Seg, else Selfie Seg
        val candidateSemantic = when {
            neuralMaps.hasConfidentSemantic && neuralMaps.semanticBitmap != null -> {
                subjectSegBmp?.recycle()
                selfieFallbackDeferred?.recycle()
                smoothMaskEdges(neuralMaps.semanticBitmap)
            }
            subjectSegBmp != null -> {
                neuralMaps.semanticBitmap?.recycle()
                selfieFallbackDeferred?.recycle()
                smoothMaskEdges(subjectSegBmp)
            }
            selfieFallbackDeferred != null -> {
                neuralMaps.semanticBitmap?.recycle()
                smoothMaskEdges(selfieFallbackDeferred)
            }
            else -> {
                neuralMaps.semanticBitmap?.recycle()
                null
            }
        }

        val coverage = if (candidateSemantic != null) estimateMaskCoverage(candidateSemantic) else 1.0f
        val hasMask = candidateSemantic != null && coverage in 0.06f..0.88f
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
        if (neuralMaps.hasDepthMap && !extraNotes.any { it.contains("Profundidade", ignoreCase = true) }) {
            extraNotes.add("Profundidade 3D MiDaS para bokeh progressivo")
        }
        if ("Zero-DCE" in neuralMaps.modelsExecuted) {
            extraNotes.add("Rede Neural Zero-DCE de luz & contraste")
        }
        if ("MultiClassSeg" in neuralMaps.modelsExecuted && hasMask) {
            extraNotes.add("Separação IA pele / cabelo / roupa")
        }

        val finalConcept = baseConcept.copy(
            hasDepthMap = neuralMaps.hasDepthMap,
            neuralBoostStrength = NeuralStudioEngine.computeZeroDceAdaptiveMix(photoStats.meanLuma, photoStats.shadowRatio),
            appliedAdjustments = extraNotes
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

    private fun smoothMaskEdges(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        mask.recycle()
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - 1)
            val y1 = minOf(h - 1, y + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - 1)
                val x1 = minOf(w - 1, x + 1)
                var sumR = 0; var sumG = 0; var sumB = 0
                for (yy in y0..y1) {
                    val row = yy * w
                    for (xx in x0..x1) {
                        val c = src[row + xx]
                        sumR += (c ushr 16) and 0xFF
                        sumG += (c ushr 8) and 0xFF
                        sumB += c and 0xFF
                    }
                }
                dst[y * w + x] = Color.argb(
                    255,
                    (sumR / 9).coerceIn(0, 255),
                    (sumG / 9).coerceIn(0, 255),
                    (sumB / 9).coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
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
