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
    val semanticBitmap: Bitmap?,       // R = fgSubject (MODNet Guided or MultiClass), G = skin, B = hair+clothes
    val depthBlurBitmap: Bitmap?,      // R = invDepth, G = focalCoCBlurWeight
    val neuralGainBitmap: Bitmap?,     // RGB encoded multiplicative Zero-DCE gain ((gain - 0.5)/2.0)
    val foregroundCoverage: Float,
    val hasConfidentSemantic: Boolean,
    val hasModNetAlpha: Boolean,
    val hasDepthMap: Boolean,
    val modelsExecuted: List<String>
)

/**
 * Local embedded deep-learning engine bundling:
 * 1. MODNet-LiteRT (512x512 trimap-free soft portrait alpha matting + Guided Edge Refinement)
 * 2. MediaPipe Multi-Class Selfie Segmenter (Bg/Hair/BodySkin/FaceSkin/Clothes/Accessories, 256x256)
 * 3. MiDaS v2.1 small (Monocular 3D Depth Estimation, 256x256)
 * 4. Zero-DCE (Low-Light & Curve Enhancement CNN, 512x512, low-light only)
 */
class NeuralStudioEngine(private val context: Context) {

    private val zeroDceLock = Any()
    private val midasLock = Any()
    private val segLock = Any()
    private val modnetLock = Any()

    @Volatile private var zeroDceInterpreter: Interpreter? = null
    @Volatile private var midasInterpreter: Interpreter? = null
    @Volatile private var multiClassInterpreter: Interpreter? = null
    @Volatile private var modnetInterpreter: Interpreter? = null

    fun warmUpModels() {
        ensureZeroDce()
        ensureMidas()
        ensureMultiClass()
        ensureModNet()
    }

    fun inferStudioMaps(source: Bitmap, meanLuma: Float, shadowRatio: Float, preferPortraitMatting: Boolean = true): NeuralGuidanceMaps {
        val executed = mutableListOf<String>()

        // 1. High-res 512x512 MODNet hair/silhouette alpha matting + Fast Guided Filter (zero bright-wall halos)
        val modnetRaw = if (preferPortraitMatting) runModNetMatting(source) else null
        if (modnetRaw != null) executed.add("MODNet-Guided")

        // 2. Multi-class semantic portrait classes (Hair, Face Skin, Body Skin, Clothes)
        val segRaw = runMultiClassSegmentation(source)
        if (segRaw != null) executed.add("MultiClassSeg")

        val hasModNetAlpha = modnetRaw != null && modnetRaw.fgCoverage in 0.04f..0.94f
        var semanticBmp: Bitmap? = null
        var fgCoverage = 0f
        var hasConfidentSemantic = false

        if (hasModNetAlpha && modnetRaw != null) {
            fgCoverage = modnetRaw.fgCoverage
            hasConfidentSemantic = true
            semanticBmp = composeSemanticWithModNet(modnetRaw.alpha512, segRaw?.bitmap, 512, 512)
            segRaw?.bitmap?.recycle()
        } else if (segRaw != null) {
            fgCoverage = segRaw.fgCoverage
            hasConfidentSemantic = fgCoverage in 0.05f..0.92f
            semanticBmp = segRaw.bitmap
        }

        val depthGuideMask = when {
            hasModNetAlpha && modnetRaw != null -> downsample512To256(modnetRaw.alpha512)
            else -> segRaw?.fgMask256
        }

        // 3. Monocular 3D Depth Map (MiDaS v2.1) -> continuous CoC lens bokeh relative to subject plane
        var depthBmp: Bitmap? = null
        val depthRaw = runMidasDepth(source, depthGuideMask)
        if (depthRaw != null) {
            executed.add("MiDaS-Depth")
            depthBmp = depthRaw
        }

        // 4. Zero-DCE Neural Exposure/Curve Gain Map ONLY in genuinely dark/underexposed scenes
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
            hasModNetAlpha = hasModNetAlpha,
            hasDepthMap = depthBmp != null,
            modelsExecuted = executed
        )
    }

    private data class ModNetResult(val alpha512: FloatArray, val fgCoverage: Float)

    private fun runModNetMatting(source: Bitmap): ModNetResult? = synchronized(modnetLock) {
        val interp = ensureModNet() ?: return null
        return runCatching {
            val w = 512
            val h = 512
            val resized = Bitmap.createScaledBitmap(source, w, h, true)
            val pixels = IntArray(w * h)
            resized.getPixels(pixels, 0, w, 0, 0, w, h)
            if (resized !== source) resized.recycle()

            // MODNet-LiteRT expects [1, 3, 512, 512] NCHW normalized to [-1, +1]
            val planeSize = w * h
            val nchw = FloatArray(3 * planeSize)
            val luma512 = FloatArray(planeSize)
            for (i in 0 until planeSize) {
                val c = pixels[i]
                val r = ((c ushr 16) and 0xFF) / 255f
                val g = ((c ushr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                nchw[i] = (r - 0.5f) / 0.5f
                nchw[planeSize + i] = (g - 0.5f) / 0.5f
                nchw[2 * planeSize + i] = (b - 0.5f) / 0.5f
                luma512[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            }

            val inBuf = ByteBuffer.allocateDirect(4 * nchw.size).order(ByteOrder.nativeOrder())
            inBuf.asFloatBuffer().put(nchw)
            inBuf.rewind()

            val outBuf = ByteBuffer.allocateDirect(4 * planeSize).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()

            val rawAlpha = FloatArray(planeSize)
            outBuf.asFloatBuffer().get(rawAlpha)
            for (i in rawAlpha.indices) rawAlpha[i] = rawAlpha[i].coerceIn(0f, 1f)

            val guided = refineAlphaGuided(rawAlpha, luma512, w, h, radius = 4, eps = 0.006f)
            var fgCount = 0
            for (a in guided) {
                if (a > 0.5f) fgCount++
            }
            ModNetResult(alpha512 = guided, fgCoverage = fgCount.toFloat() / planeSize)
        }.getOrNull()
    }

    private fun composeSemanticWithModNet(alpha512: FloatArray, multiClass256: Bitmap?, w: Int, h: Int): Bitmap {
        val segScaled = if (multiClass256 != null) Bitmap.createScaledBitmap(multiClass256, w, h, true) else null
        val segPx = if (segScaled != null) IntArray(w * h).also { segScaled.getPixels(it, 0, w, 0, 0, w, h) } else null
        if (segScaled != null && segScaled !== multiClass256) segScaled.recycle()

        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val sc = segPx?.get(i) ?: 0
            val skinByte = (sc ushr 8) and 0xFF
            val detailByte = sc and 0xFF
            // Never allow segmented human skin to drop to background under MODNet
            val modFg = alpha512[i].coerceIn(0f, 1f)
            val skinProtect = if (skinByte > 85) skinByte / 255f else 0f
            val fgByte = (max(modFg, skinProtect) * 255f).toInt().coerceIn(0, 255)
            out[i] = Color.argb(255, fgByte, skinByte, detailByte)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun downsample512To256(alpha512: FloatArray): FloatArray {
        val out = FloatArray(256 * 256)
        for (y in 0 until 256) {
            val y2 = y * 2
            for (x in 0 until 256) {
                val x2 = x * 2
                val sum = alpha512[y2 * 512 + x2] + alpha512[y2 * 512 + x2 + 1] +
                    alpha512[(y2 + 1) * 512 + x2] + alpha512[(y2 + 1) * 512 + x2 + 1]
                out[y * 256 + x] = (sum * 0.25f).coerceIn(0f, 1f)
            }
        }
        return out
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

    private fun ensureModNet(): Interpreter? {
        modnetInterpreter?.let { return it }
        return loadAssetModel("modnet.tflite")?.also { modnetInterpreter = it }
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
        runCatching { modnetInterpreter?.close() }
    }

    companion object {
        /**
         * O(N) Guided Filter (He et al.) aligning soft MODNet alpha edges strictly to the source image luminance
         * so foreground contours do not bleed into surrounding bright walls.
         */
        fun refineAlphaGuided(alpha: FloatArray, guideLuma: FloatArray, w: Int, h: Int, radius: Int = 4, eps: Float = 0.006f): FloatArray {
            if (alpha.size != w * h || guideLuma.size != w * h) return alpha
            val meanI = boxMean(guideLuma, w, h, radius)
            val meanP = boxMean(alpha, w, h, radius)
            val ip = FloatArray(w * h) { guideLuma[it] * alpha[it] }
            val ii = FloatArray(w * h) { guideLuma[it] * guideLuma[it] }
            val corrIp = boxMean(ip, w, h, radius)
            val corrIi = boxMean(ii, w, h, radius)

            val a = FloatArray(w * h)
            val b = FloatArray(w * h)
            for (i in 0 until w * h) {
                val varI = corrIi[i] - meanI[i] * meanI[i]
                val covIp = corrIp[i] - meanI[i] * meanP[i]
                a[i] = covIp / (varI + eps)
                b[i] = meanP[i] - a[i] * meanI[i]
            }

            val meanA = boxMean(a, w, h, radius)
            val meanB = boxMean(b, w, h, radius)
            val q = FloatArray(w * h)
            for (i in 0 until w * h) {
                q[i] = (meanA[i] * guideLuma[i] + meanB[i]).coerceIn(0f, 1f)
            }
            return q
        }

        private fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val horiz = FloatArray(w * h)
            val invWin = 1f / (2 * r + 1)
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    horiz[row + x] = sum * invWin
                    val addX = (x + r + 1).coerceAtMost(w - 1)
                    val subX = (x - r).coerceAtLeast(0)
                    sum += src[row + addX] - src[row + subX]
                }
            }
            val out = FloatArray(w * h)
            for (x in 0 until w) {
                var sum = 0f
                for (k in -r..r) sum += horiz[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = sum * invWin
                    val addY = (y + r + 1).coerceAtMost(h - 1)
                    val subY = (y - r).coerceAtLeast(0)
                    sum += horiz[addY * w + x] - horiz[subY * w + x]
                }
            }
            return out
        }
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

            // Focus plane = average depth across foreground subject mask; if subject is tiny/distant (<14% of frame), keep CoC=0 to avoid blurring distant people behind foreground objects
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
            if (focusCount < (w * h) / 7) {
                return DepthCocOutput(normalizedDepth = norm, cocBlur = FloatArray(norm.size), focusDepth = 0.5f)
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
