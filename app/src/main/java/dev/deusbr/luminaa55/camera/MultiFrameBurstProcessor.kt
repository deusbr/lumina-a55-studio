package dev.deusbr.luminaa55.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HDR+ inspired multi-frame YUV burst aligner & ghost-rejected temporal merger.
 * Works on unrooted Galaxy A55 without requiring Camera2 RAW_SENSOR.
 */
object MultiFrameBurstProcessor {

    /**
     * Converts a CameraX YUV_420_888 ImageProxy cleanly to an upright ARGB_8888 Bitmap.
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val yRowOffset = y * yRowStride
            val uvRowOffset = (y shr 1) * uvRowStride
            for (x in 0 until width) {
                val yIdx = yRowOffset + x * yPixelStride
                val uvIdx = uvRowOffset + (x shr 1) * uvPixelStride

                val yVal = (yBuf.get(yIdx).toInt() and 0xFF) - 16
                val uVal = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

                val y1192 = 1192 * max(0, yVal)
                var r = (y1192 + 1634 * vVal) shr 10
                var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                var b = (y1192 + 2066 * uVal) shr 10

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val rawBmp = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return rawBmp

        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
        if (rotated !== rawBmp) rawBmp.recycle()
        return rotated
    }

    /**
     * Pure-Kotlin tile alignment + ghost-rejected temporal merge over ARGB frames.
     * Directly testable on JVM.
     */
    fun mergeArgbBurst(frames: List<IntArray>, width: Int, height: Int, searchRadius: Int = 4): IntArray {
        require(frames.isNotEmpty()) { "Burst frames list cannot be empty." }
        if (frames.size == 1) return frames[0].copyOf()

        val base = frames[0]
        val count = width * height
        val accR = FloatArray(count)
        val accG = FloatArray(count)
        val accB = FloatArray(count)
        val weights = FloatArray(count)

        // Base frame has weight 1.0
        for (i in 0 until count) {
            val c = base[i]
            accR[i] = ((c ushr 16) and 0xFF).toFloat()
            accG[i] = ((c ushr 8) and 0xFF).toFloat()
            accB[i] = (c and 0xFF).toFloat()
            weights[i] = 1.0f
        }

        // Align and fuse secondary frames
        for (fIdx in 1 until frames.size) {
            val frame = frames[fIdx]
            if (frame.size != count) continue

            val (dx, dy) = estimateGlobalShift(base, frame, width, height, searchRadius)
            for (y in 0 until height) {
                val sy = (y + dy).coerceIn(0, height - 1)
                for (x in 0 until width) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val dstIdx = y * width + x
                    val srcIdx = sy * width + sx

                    val bc = base[dstIdx]
                    val sc = frame[srcIdx]
                    val br = (bc ushr 16) and 0xFF
                    val bg = (bc ushr 8) and 0xFF
                    val bb = bc and 0xFF

                    val sr = (sc ushr 16) and 0xFF
                    val sg = (sc ushr 8) and 0xFF
                    val sb = sc and 0xFF

                    // Ghost rejection: lower weight when pixel differs strongly from base frame
                    val diff = (abs(br - sr) + abs(bg - sg) + abs(bb - sb)) / 3f
                    val motionWeight = when {
                        diff < 10f -> 1.0f
                        diff < 26f -> 1.0f - (diff - 10f) / 18f
                        else -> 0.0f
                    }
                    // Highlight preservation: avoid blending clipped pixels over textured highlights
                    val baseLuma = 0.2126f * br + 0.7152f * bg + 0.0722f * bb
                    val srcLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
                    val highlightWeight = if (srcLuma > 246f && baseLuma < 238f) 0.15f else 1.0f

                    val w = motionWeight * highlightWeight
                    if (w > 0.01f) {
                        accR[dstIdx] += sr * w
                        accG[dstIdx] += sg * w
                        accB[dstIdx] += sb * w
                        weights[dstIdx] += w
                    }
                }
            }
        }

        val merged = IntArray(count)
        for (i in 0 until count) {
            val inv = 1f / max(1f, weights[i])
            val r = (accR[i] * inv).toInt().coerceIn(0, 255)
            val g = (accG[i] * inv).toInt().coerceIn(0, 255)
            val b = (accB[i] * inv).toInt().coerceIn(0, 255)
            merged[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return merged
    }

    private fun estimateGlobalShift(
        base: IntArray,
        candidate: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): Pair<Int, Int> {
        val xStart = (width * 0.25f).toInt()
        val xEnd = (width * 0.75f).toInt()
        val yStart = (height * 0.25f).toInt()
        val yEnd = (height * 0.75f).toInt()
        val step = max(2, min(width, height) / 48)

        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                var sad = 0L
                var y = yStart
                while (y < yEnd) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    var x = xStart
                    while (x < xEnd) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val b = base[y * width + x]
                        val s = candidate[sy * width + sx]
                        val bl = ((b ushr 8) and 0xFF)
                        val sl = ((s ushr 8) and 0xFF)
                        sad += abs(bl - sl)
                        x += step
                    }
                    y += step
                }
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    fun mergeBitmaps(bitmaps: List<Bitmap>): Bitmap {
        require(bitmaps.isNotEmpty()) { "No frames to merge." }
        if (bitmaps.size == 1) return bitmaps[0]
        val w = bitmaps[0].width
        val h = bitmaps[0].height
        val arrays = bitmaps.map { bmp ->
            val target = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
            IntArray(w * h).also { target.getPixels(it, 0, w, 0, 0, w, h) }
        }
        val merged = mergeArgbBurst(arrays, w, h, searchRadius = 4)
        return Bitmap.createBitmap(merged, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Edge-preserving shadow chroma & luma noise suppression for crisp single/multi-frame handheld captures
     * without introducing pre-shutter motion ghosting.
     */
    fun denoiseShadowChroma(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = pixels.copyOf()
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val c = pixels[i]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val luma = (0.2126f * cr + 0.7152f * cg + 0.0722f * cb) / 255f
                if (luma > 0.38f) continue

                var accR = cr.toFloat(); var accG = cg.toFloat(); var accB = cb.toFloat(); var wSum = 1f
                val neighbors = intArrayOf(i - 1, i + 1, i - width, i + width)
                for (ni in neighbors) {
                    val nc = pixels[ni]
                    val nr = (nc ushr 16) and 0xFF
                    val ng = (nc ushr 8) and 0xFF
                    val nb = nc and 0xFF
                    val diff = (abs(cr - nr) + abs(cg - ng) + abs(cb - nb)) / 3f
                    if (diff < 22f) {
                        val w = 1f - diff / 24f
                        accR += nr * w; accG += ng * w; accB += nb * w; wSum += w
                    }
                }
                val blend = ((0.38f - luma) / 0.38f) * 0.45f
                val dr = (accR / wSum)
                val dg = (accG / wSum)
                val db = (accB / wSum)
                val fr = (cr * (1f - blend) + dr * blend).toInt().coerceIn(0, 255)
                val fg = (cg * (1f - blend) + dg * blend).toInt().coerceIn(0, 255)
                val fb = (cb * (1f - blend) + db * blend).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
            }
        }
        return out
    }
}
