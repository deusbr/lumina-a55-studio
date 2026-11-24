package dev.deusbr.luminaa55.context

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.SceneConcept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max

data class ConceptAnalysisOutput(
    val concept: SceneConcept,
    val subjectMask: Bitmap
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
        val segMaskDeferred = async {
            runCatching {
                val res = subjectSegmenter.process(inputImage).await()
                val mask = res.foregroundConfidenceMask ?: return@runCatching null
                confidenceMaskToBitmap(mask, analysisBmp.width, analysisBmp.height)
            }.getOrNull()
        }

        val faces = facesDeferred.await()
        val labels = labelsDeferred.await()
        val rawSubjectMask = segMaskDeferred.await()

        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val faceCx = primaryFace?.let { it.boundingBox.exactCenterX() / analysisBmp.width }
        val faceCy = primaryFace?.let { it.boundingBox.exactCenterY() / analysisBmp.height }

        val pixels = IntArray(analysisBmp.width * analysisBmp.height)
        analysisBmp.getPixels(pixels, 0, analysisBmp.width, 0, 0, analysisBmp.width, analysisBmp.height)
        val photoStats = PhotometricAnalyzer.analyzeArgbPixels(pixels, analysisBmp.width, analysisBmp.height, faceCx, faceCy)

        val finalMask = rawSubjectMask ?: buildFallbackSubjectMask(analysisBmp.width, analysisBmp.height, faces)
        val subjectRatio = estimateMaskCoverage(finalMask)

        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = photoStats,
            labels = labels,
            faceCount = faces.size,
            subjectRatio = subjectRatio,
            userSelectedStock = selectedStock
        )

        ConceptAnalysisOutput(concept = concept, subjectMask = finalMask)
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
        val concept = PhotometricAnalyzer.buildConceptProfile(
            stats = stats,
            labels = labels,
            faceCount = faces.size,
            subjectRatio = if (faces.isNotEmpty()) 0.32f else 0.0f
        )
        "${concept.conceptTitle} · ${concept.recommendedStock.badge}"
    }

    private fun confidenceMaskToBitmap(buffer: java.nio.FloatBuffer, w: Int, h: Int): Bitmap {
        buffer.rewind()
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val conf = if (buffer.hasRemaining()) buffer.get().coerceIn(0f, 1f) else 0f
            val g = (conf * 255f).toInt()
            out[i] = Color.argb(255, g, g, g)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun buildFallbackSubjectMask(w: Int, h: Int, faces: List<Face>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (faces.isNotEmpty()) {
            for (face in faces) {
                val box = face.boundingBox
                val cx = box.exactCenterX()
                val cy = (box.exactCenterY() + box.height() * 0.65f).coerceIn(0f, h.toFloat())
                val radius = max(box.width(), box.height()) * 2.2f
                paint.shader = RadialGradient(
                    cx, cy, max(20f, radius),
                    intArrayOf(Color.WHITE, Color.argb(255, 220, 220, 220), Color.BLACK),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(
                    RectF(cx - radius * 0.85f, cy - radius * 1.1f, cx + radius * 0.85f, cy + radius * 1.2f),
                    paint
                )
            }
        } else {
            // Soft center-weighted editorial subject priority when no person/Play Services segmenter is active
            val radius = max(w, h) * 0.52f
            paint.shader = RadialGradient(
                w * 0.5f, h * 0.5f, radius,
                intArrayOf(Color.WHITE, Color.argb(255, 160, 160, 160), Color.BLACK),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.5f, h * 0.5f, radius, paint)
        }
        return bmp
    }

    private fun estimateMaskCoverage(mask: Bitmap): Float {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        var fg = 0
        for (p in pixels) {
            if (((p ushr 16) and 0xFF) > 120) fg++
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
    }
}
