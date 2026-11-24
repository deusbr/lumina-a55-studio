package dev.deusbr.luminaa55.context

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.SceneConcept
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
    val hasConfidentMask: Boolean,
    val stats: PhotometricStats,
    val labels: List<String>,
    val faceCount: Int
)

class SceneConceptEngine {

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

    // Bundled 100% offline portrait/person segmenter (works immediately without waiting for Play Services download)
    private val selfieSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /**
     * Proactively requests Play Services Subject Segmentation model install in the background.
     */
    fun warmUp(context: Context) {
        runCatching {
            val installClient = ModuleInstall.getClient(context)
            val request = ModuleInstallRequest.newBuilder()
                .addApi(subjectSegmenter)
                .build()
            installClient.installModules(request)
        }
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
        val selfieSegDeferred = async {
            runCatching {
                val res = selfieSegmenter.process(inputImage).await()
                selfieMaskToBitmap(res.buffer, res.width, res.height, analysisBmp.width, analysisBmp.height)
            }.getOrNull()
        }

        val faces = facesDeferred.await()
        val labels = labelsDeferred.await()
        val subjectSegBmp = subjectSegDeferred.await()
        val selfieSegBmp = if (subjectSegBmp == null) selfieSegDeferred.await() else null

        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val faceCx = primaryFace?.let { it.boundingBox.exactCenterX() / analysisBmp.width }
        val faceCy = primaryFace?.let { it.boundingBox.exactCenterY() / analysisBmp.height }

        val pixels = IntArray(analysisBmp.width * analysisBmp.height)
        analysisBmp.getPixels(pixels, 0, analysisBmp.width, 0, 0, analysisBmp.width, analysisBmp.height)
        val photoStats = PhotometricAnalyzer.analyzeArgbPixels(pixels, analysisBmp.width, analysisBmp.height, faceCx, faceCy)

        // Choose real confident foreground mask (1.0 = foreground subject, 0.0 = background)
        val candidateMask = subjectSegBmp ?: selfieSegBmp
        val candidateCoverage = candidateMask?.let { estimateMaskCoverage(it) } ?: 0f
        val hasRealMask = candidateMask != null && candidateCoverage in 0.06f..0.86f

        val finalMask = if (hasRealMask && candidateMask != null) {
            smoothMaskEdges(candidateMask)
        } else {
            // Neutral all-foreground mask: never apply blind radial blur onto unsegmented foreground objects
            buildNeutralForegroundMask(16, 16)
        }

        if (subjectSegBmp != null && subjectSegBmp !== candidateMask) subjectSegBmp.recycle()
        if (selfieSegBmp != null && selfieSegBmp !== candidateMask) selfieSegBmp.recycle()
        if (analysisBmp !== bitmap) analysisBmp.recycle()

        val subjectRatio = if (hasRealMask) candidateCoverage else 1.0f
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = photoStats,
            labels = labels,
            faceCount = faces.size,
            subjectRatio = subjectRatio,
            userSelectedStock = selectedStock,
            hasConfidentMask = hasRealMask
        )

        ConceptAnalysisOutput(
            concept = concept,
            subjectMask = finalMask,
            hasConfidentMask = hasRealMask,
            stats = photoStats,
            labels = labels,
            faceCount = faces.size
        )
    }

    /**
     * Lightweight real-time preview concept hint from low-res live frames.
     */
    suspend fun quickLiveHint(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
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
        "${concept.conceptTitle} · ${concept.recommendedStock.badge}"
    }

    private fun floatBufferMaskToBitmap(buffer: FloatBuffer, w: Int, h: Int): Bitmap {
        buffer.rewind()
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val conf = if (buffer.hasRemaining()) buffer.get().coerceIn(0f, 1f) else 0f
            val g = (conf * 255f).toInt()
            out[i] = Color.argb(255, g, g, g)
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
            out[i] = Color.argb(255, g, g, g)
        }
        val raw = Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
        if (mw == targetW && mh == targetH) return raw
        val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
        raw.recycle()
        return scaled
    }

    /**
     * Gentle 3x3 edge feathering so subject-vs-background transitions never halo or cut abruptly.
     */
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
                var sum = 0
                for (yy in y0..y1) {
                    val row = yy * w
                    for (xx in x0..x1) {
                        sum += (src[row + xx] ushr 16) and 0xFF
                    }
                }
                val v = (sum / 9).coerceIn(0, 255)
                dst[y * w + x] = Color.argb(255, v, v, v)
            }
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun buildNeutralForegroundMask(w: Int, h: Int): Bitmap {
        val out = IntArray(w * h) { Color.WHITE }
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
    }
}
