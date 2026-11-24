package dev.deusbr.luminaa55.context

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class NeuralGuidanceMaps(
    val semanticBitmap: Bitmap?,       // R = fgSubject, G = skin(face+body), B = hair+clothes
    val depthBlurBitmap: Bitmap?,      // R = invDepth, G = focalCoCBlurWeight
    val neuralGainBitmap: Bitmap?,     // RGB encoded multiplicative Zero-DCE gain ((gain - 0.5)/2.0)
    val foregroundCoverage: Float,
    val hasConfidentSemantic: Boolean,
    val hasDepthMap: Boolean,
    val modelsExecuted: List<String>
)

/**
 * Local embedded deep-learning engine bundling:
 * 1. Zero-DCE (Low-Light & Curve Enhancement CNN, 512x512)
 * 2. MiDaS v2.1 small (Monocular 3D Depth Estimation, 256x256)
 * 3. MediaPipe Multi-Class Selfie Segmenter (Bg/Hair/BodySkin/FaceSkin/Clothes/Accessories, 256x256)
 */
class NeuralStudioEngine(private val context: Context) {

    private val zeroDceLock = Any()
    private val midasLock = Any()
    private val segLock = Any()

    @Volatile private var zeroDceInterpreter: Interpreter? = null
    @Volatile private var midasInterpreter: Interpreter? = null
    @Volatile private var multiClassInterpreter: Interpreter? = null

    fun warmUpModels() {
        ensureZeroDce()
        ensureMidas()
        ensureMultiClass()
    }

    fun inferStudioMaps(source: Bitmap, meanLuma: Float, shadowRatio: Float): NeuralGuidanceMaps {
        val executed = mutableListOf<String>()

        // 1. Multi-class semantic portrait segmentation (Hair, Face Skin, Body Skin, Clothes)
        var semanticBmp: Bitmap? = null
        var fgCoverage = 0f
        var hasConfidentSemantic = false
        val segRaw = runMultiClassSegmentation(source)
        if (segRaw != null) {
            executed.add("MultiClassSeg")
            fgCoverage = segRaw.fgCoverage
            hasConfidentSemantic = fgCoverage in 0.05f..0.92f
            semanticBmp = segRaw.bitmap
        }

        // 2. Monocular 3D Depth Map (MiDaS v2.1) -> continuous CoC lens bokeh relative to subject plane
        var depthBmp: Bitmap? = null
        val depthRaw = runMidasDepth(source, segRaw?.fgMask256)
        if (depthRaw != null) {
            executed.add("MiDaS-Depth")
            depthBmp = depthRaw
        }

        // 3. Zero-DCE Neural Exposure/Curve Gain Map ONLY in genuinely dark/underexposed scenes
        val mixStrength = computeZeroDceAdaptiveMix(meanLuma, shadowRatio)
        val gainBmp = if (mixStrength > 0.02f) {
            runZeroDceGainMap(source, mixStrength)
        } else null
        if (gainBmp != null) {
            executed.add("Zero-DCE")
        }

        return NeuralGuidanceMaps(
            semanticBitmap = semanticBmp,
            depthBlurBitmap = depthBmp,
            neuralGainBitmap = gainBmp,
            foregroundCoverage = fgCoverage,
            hasConfidentSemantic = hasConfidentSemantic,
            hasDepthMap = depthBmp != null,
            modelsExecuted = executed
        )
    }

    private data class SegResult(val bitmap: Bitmap, val fgMask256: FloatArray, val fgCoverage: Float)

    private fun runMultiClassSegmentation(source: Bitmap): SegResult? = synchronized(segLock) {
        val interp = ensureMultiClass() ?: return null
        return runCatching {
            val w = 256
            val h = 256
            val resized = Bitmap.createScaledBitmap(source, w, h, true)
            val pixels = IntArray(w * h)
            resized.getPixels(pixels, 0, w, 0, 0, w, h)
            if (resized !== source) resized.recycle()

            val inBuf = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            // MediaPipe MultiClass Selfie Segmenter expects [-1.0, +1.0] (mean=127.5, std=127.5)
            for (c in pixels) {
                inBuf.putFloat((((c ushr 16) and 0xFF) - 127.5f) / 127.5f)
                inBuf.putFloat((((c ushr 8) and 0xFF) - 127.5f) / 127.5f)
                inBuf.putFloat(((c and 0xFF) - 127.5f) / 127.5f)
            }
            inBuf.rewind()

            val outShape = interp.getOutputTensor(0).shape() // [1, 256, 256, 6]
            val channels = outShape.lastOrNull() ?: 6
            val outBuf = ByteBuffer.allocateDirect(4 * w * h * channels).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val fb = outBuf.asFloatBuffer()

            val outPixels = IntArray(w * h)
            val fgArr = FloatArray(w * h)
            var fgPixels = 0

            val logits = FloatArray(channels)
            val probs = FloatArray(channels)
            for (i in 0 until w * h) {
                var rawSum = 0f
                var looksProb = true
                for (k in 0 until channels) {
                    val v = fb.get()
                    logits[k] = v
                    rawSum += v
                    if (v < -0.01f || v > 1.01f) looksProb = false
                }
                if (looksProb && kotlin.math.abs(rawSum - 1f) < 0.04f) {
                    System.arraycopy(logits, 0, probs, 0, channels)
                } else {
                    var maxL = logits[0]
                    for (k in 1 until channels) if (logits[k] > maxL) maxL = logits[k]
                    var eSum = 0f
                    for (k in 0 until channels) {
                        val e = exp((logits[k] - maxL).toDouble()).toFloat()
                        probs[k] = e
                        eSum += e
                    }
                    val inv = 1f / max(1e-5f, eSum)
                    for (k in 0 until channels) probs[k] *= inv
                }

                val bg = probs[0].coerceIn(0f, 1f)
                val hair = if (channels > 1) probs[1].coerceIn(0f, 1f) else 0f
                val bodySkin = if (channels > 2) probs[2].coerceIn(0f, 1f) else 0f
                val faceSkin = if (channels > 3) probs[3].coerceIn(0f, 1f) else 0f
                val clothes = if (channels > 4) probs[4].coerceIn(0f, 1f) else 0f
                val accessories = if (channels > 5) probs[5].coerceIn(0f, 1f) else 0f

                val fg = (1f - bg).coerceIn(0f, 1f)
                val skin = (faceSkin + bodySkin).coerceIn(0f, 1f)
                val detail = (hair + clothes + accessories).coerceIn(0f, 1f)

                fgArr[i] = fg
                if (fg > 0.5f) fgPixels++

                outPixels[i] = Color.argb(
                    255,
                    (fg * 255f).toInt(),
                    (skin * 255f).toInt(),
                    (detail * 255f).toInt()
                )
            }
            SegResult(
                bitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888),
                fgMask256 = fgArr,
                fgCoverage = fgPixels.toFloat() / (w * h)
            )
        }.getOrNull()
    }

    private fun runMidasDepth(source: Bitmap, fgMask256: FloatArray?): Bitmap? = synchronized(midasLock) {
        val interp = ensureMidas() ?: return null
        return runCatching {
            val w = 256
            val h = 256
            val resized = Bitmap.createScaledBitmap(source, w, h, true)
            val pixels = IntArray(w * h)
            resized.getPixels(pixels, 0, w, 0, 0, w, h)
            if (resized !== source) resized.recycle()

            val inBuf = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            for (c in pixels) {
                val r = (((c ushr 16) and 0xFF) / 255f - 0.485f) / 0.229f
                val g = (((c ushr 8) and 0xFF) / 255f - 0.456f) / 0.224f
                val b = ((c and 0xFF) / 255f - 0.406f) / 0.225f
                inBuf.putFloat(r)
                inBuf.putFloat(g)
                inBuf.putFloat(b)
            }
            inBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(4 * w * h).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val fb = outBuf.asFloatBuffer()
            val rawDepth = FloatArray(w * h)
            for (i in 0 until w * h) {
                rawDepth[i] = fb.get()
            }

            val blurWeights = computeDepthCocWeights(rawDepth, fgMask256, w, h)
            val outPixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val dByte = (blurWeights.normalizedDepth[i] * 255f).toInt().coerceIn(0, 255)
                val cocByte = (blurWeights.cocBlur[i] * 255f).toInt().coerceIn(0, 255)
                outPixels[i] = Color.argb(255, dByte, cocByte, 128)
            }
            Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun runZeroDceGainMap(source: Bitmap, mixStrength: Float): Bitmap? = synchronized(zeroDceLock) {
        val interp = ensureZeroDce() ?: return null
        return runCatching {
            val w = 512
            val h = 512
            val resized = Bitmap.createScaledBitmap(source, w, h, true)
            val pixels = IntArray(w * h)
            resized.getPixels(pixels, 0, w, 0, 0, w, h)
            if (resized !== source) resized.recycle()

            val inBuf = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            // Zero-DCE expects [0.0, 1.0] RGB input
            for (c in pixels) {
                inBuf.putFloat(((c ushr 16) and 0xFF) / 255f)
                inBuf.putFloat(((c ushr 8) and 0xFF) / 255f)
                inBuf.putFloat((c and 0xFF) / 255f)
            }
            inBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val fb = outBuf.asFloatBuffer()

            val gainPx = IntArray(w * h)
            for (i in 0 until w * h) {
                val c = pixels[i]
                val ir = ((c ushr 16) and 0xFF) / 255f
                val ig = ((c ushr 8) and 0xFF) / 255f
                val ib = (c and 0xFF) / 255f

                val er = fb.get().coerceIn(0f, 1f)
                val eg = fb.get().coerceIn(0f, 1f)
                val eb = fb.get().coerceIn(0f, 1f)

                val gr = encodeGainChannel(ir, er, mixStrength)
                val gg = encodeGainChannel(ig, eg, mixStrength)
                val gb = encodeGainChannel(ib, eb, mixStrength)
                gainPx[i] = Color.argb(255, gr, gg, gb)
            }
            Bitmap.createBitmap(gainPx, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun ensureZeroDce(): Interpreter? {
        zeroDceInterpreter?.let { return it }
        return loadAssetModel("zerodce_512.tflite")?.also { zeroDceInterpreter = it }
    }

    private fun ensureMidas(): Interpreter? {
        midasInterpreter?.let { return it }
        return loadAssetModel("midas_small_256_fp16.tflite")?.also { midasInterpreter = it }
    }

    private fun ensureMultiClass(): Interpreter? {
        multiClassInterpreter?.let { return it }
        return loadAssetModel("selfie_multiclass_256x256.tflite")?.also { multiClassInterpreter = it }
    }

    private fun loadAssetModel(assetName: String): Interpreter? = runCatching {
        val afd = context.assets.openFd(assetName)
        val buffer: MappedByteBuffer = afd.createInputStream().use { fis ->
            (fis as FileInputStream).channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
        val opts = Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
        }
        Interpreter(buffer, opts)
    }.getOrNull()

    fun close() {
        runCatching { zeroDceInterpreter?.close() }
        runCatching { midasInterpreter?.close() }
        runCatching { multiClassInterpreter?.close() }
    }

    companion object {
        data class DepthCocOutput(val normalizedDepth: FloatArray, val cocBlur: FloatArray, val focusDepth: Float)

        /**
         * Pure-Kotlin depth normalization and Circle-of-Confusion calculator (JVM unit-testable).
         */
        fun computeDepthCocWeights(rawInvDepth: FloatArray, fgMask: FloatArray?, w: Int, h: Int): DepthCocOutput {
            var dMin = Float.MAX_VALUE
            var dMax = -Float.MAX_VALUE
            for (v in rawInvDepth) {
                if (v < dMin) dMin = v
                if (v > dMax) dMax = v
            }
            val span = max(1e-4f, dMax - dMin)
            val norm = FloatArray(rawInvDepth.size)
            for (i in rawInvDepth.indices) {
                norm[i] = ((rawInvDepth[i] - dMin) / span).coerceIn(0f, 1f)
            }

            // Focus plane = average depth across foreground mask (or upper-center subject zone)
            var focusSum = 0.0
            var focusCount = 0
            if (fgMask != null && fgMask.size == norm.size) {
                for (i in norm.indices) {
                    if (fgMask[i] > 0.55f) {
                        focusSum += norm[i]
                        focusCount++
                    }
                }
            }
            if (focusCount < (w * h) / 40) {
                focusSum = 0.0; focusCount = 0
                for (y in (h * 0.25f).toInt()..(h * 0.72f).toInt()) {
                    for (x in (w * 0.30f).toInt()..(w * 0.70f).toInt()) {
                        focusSum += norm[y * w + x]
                        focusCount++
                    }
                }
            }
            val focusDepth = if (focusCount > 0) (focusSum / focusCount).toFloat() else 0.65f

            val coc = FloatArray(norm.size)
            for (i in norm.indices) {
                val behind = max(0f, focusDepth - norm[i])
                val t = ((behind - 0.04f) / 0.30f).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                val fgGuard = if (fgMask != null && fgMask.size == norm.size && fgMask[i] > 0.50f) 0f else 1f
                coc[i] = (smooth * fgGuard).coerceIn(0f, 1f)
            }
            return DepthCocOutput(normalizedDepth = norm, cocBlur = coc, focusDepth = focusDepth)
        }

        fun computeZeroDceAdaptiveMix(meanLuma: Float, shadowRatio: Float): Float {
            return when {
                meanLuma < 0.22f -> 0.65f
                meanLuma < 0.32f && shadowRatio > 0.42f -> 0.38f
                else -> 0.0f
            }
        }

        fun encodeGainChannel(inVal: Float, enhancedVal: Float, mixStrength: Float): Int {
            val rawGain = enhancedVal / max(0.04f, inVal)
            // Protect deep shadows from turning milky gray; cap boost at 1.85x
            val shadowAnchor = if (inVal < 0.06f) (inVal / 0.06f) else 1.0f
            val clamped = rawGain.coerceIn(0.85f, 1.85f)
            val blended = 1f + (clamped - 1f) * mixStrength * shadowAnchor
            val norm = ((blended - 0.5f) / 2.0f).coerceIn(0f, 1f)
            return (norm * 255f).toInt().coerceIn(0, 255)
        }
    }
}
