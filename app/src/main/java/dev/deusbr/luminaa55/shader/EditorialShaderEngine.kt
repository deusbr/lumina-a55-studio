package dev.deusbr.luminaa55.shader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import dev.deusbr.luminaa55.model.EditRecipe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class ShaderRenderResult(
    val bitmap: Bitmap,
    val gpuAccelerated: Boolean
)

/**
 * Hardware-accelerated AGSL RuntimeShader pipeline targeting Galaxy A55 Xclipse 530 (RDNA2) GPU,
 * with a calibrated CPU fallback matching foreground/background mask separation and sRGB midtones.
 */
object EditorialShaderEngine {

    private const val AGSL_EDITORIAL_SHADER = """
        uniform shader inputImage;
        uniform shader subjectMask;
        uniform float2 imageSize;
        uniform float2 maskSize;

        uniform float hasSubjectMask;
        uniform float exposureOffset;
        uniform float contrast;
        uniform float shadowLift;
        uniform float highlightRollOff;
        uniform float clarity;
        uniform float warmth;
        uniform float tint;
        uniform float saturation;
        uniform float skinProtection;
        uniform float faceRelightGain;
        uniform float backgroundDimming;
        uniform float backgroundBokeh;
        uniform float skyRecovery;
        uniform float halationStrength;
        uniform float proMistStrength;
        uniform float grainAmount;
        uniform float vignetteStrength;
        uniform float monochrome;

        float luma(half3 c) {
            return dot(c, half3(0.2126, 0.7152, 0.0722));
        }

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float sampleMaskAt(float2 coord) {
            float2 uv = clamp(coord / max(imageSize, float2(1.0)), float2(0.0), float2(1.0));
            return clamp(float(subjectMask.eval(uv * maskSize).r), 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(imageSize, float2(1.0));
            float minDim = min(imageSize.x, imageSize.y);
            float mask = hasSubjectMask > 0.5 ? sampleMaskAt(fragCoord) : 1.0;
            float bgWeight = hasSubjectMask > 0.5 ? smoothstep(0.20, 0.88, 1.0 - mask) : 0.0;

            half4 baseSample = inputImage.eval(fragCoord);
            half3 rgb = baseSample.rgb;

            // 1. Optical background-only bokeh (resolution-scaled Vogel disk with foreground bleed protection)
            if (backgroundBokeh > 0.01 && bgWeight > 0.02) {
                float radius = bgWeight * backgroundBokeh * clamp(minDim * 0.013, 6.0, 34.0);
                half3 acc = rgb;
                float weightSum = 1.0;

                float2 d0 = float2(0.28, 0.05);
                float2 d1 = float2(-0.22, 0.36);
                float2 d2 = float2(-0.35, -0.30);
                float2 d3 = float2(0.41, -0.35);
                float2 d4 = float2(0.06, 0.61);
                float2 d5 = float2(-0.62, 0.18);
                float2 d6 = float2(0.22, -0.68);
                float2 d7 = float2(0.66, 0.42);
                float2 d8 = float2(-0.46, -0.68);
                float2 d9 = float2(-0.78, -0.20);
                float2 d10 = float2(0.84, -0.18);
                float2 d11 = float2(-0.18, 0.92);

                float2 sc = fragCoord + d0 * radius; float w0 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w0; weightSum += w0;
                sc = fragCoord + d1 * radius; float w1 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w1; weightSum += w1;
                sc = fragCoord + d2 * radius; float w2 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w2; weightSum += w2;
                sc = fragCoord + d3 * radius; float w3 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w3; weightSum += w3;
                sc = fragCoord + d4 * radius; float w4 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w4; weightSum += w4;
                sc = fragCoord + d5 * radius; float w5 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w5; weightSum += w5;
                sc = fragCoord + d6 * radius; float w6 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w6; weightSum += w6;
                sc = fragCoord + d7 * radius; float w7 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w7; weightSum += w7;
                sc = fragCoord + d8 * radius; float w8 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w8; weightSum += w8;
                sc = fragCoord + d9 * radius; float w9 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w9; weightSum += w9;
                sc = fragCoord + d10 * radius; float w10 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w10; weightSum += w10;
                sc = fragCoord + d11 * radius; float w11 = 1.0 - smoothstep(0.5, 0.9, sampleMaskAt(sc));
                acc += inputImage.eval(sc).rgb * w11; weightSum += w11;

                rgb = mix(rgb, acc / max(weightSum, 0.001), bgWeight);
            }

            // Skin detector for protecting natural complexion against over-sharpening and tint drift
            float skinScore = smoothstep(0.05, 0.24, float(rgb.r - rgb.b)) *
                              smoothstep(0.02, 0.14, float(rgb.r - rgb.g)) *
                              smoothstep(0.18, 0.85, float(rgb.r));

            // 2. Resolution-scaled subtle clarity & specular Black Pro-Mist + Halation
            float blurStep = clamp(minDim * 0.0032, 2.0, 8.0);
            half3 localBlur = (
                inputImage.eval(fragCoord + float2(blurStep, 0.0)).rgb +
                inputImage.eval(fragCoord - float2(blurStep, 0.0)).rgb +
                inputImage.eval(fragCoord + float2(0.0, blurStep)).rgb +
                inputImage.eval(fragCoord - float2(0.0, blurStep)).rgb
            ) * 0.25;

            float clarityAtten = (1.0 - skinScore * 0.72) * (1.0 - bgWeight * 0.65);
            rgb = clamp(rgb + (rgb - localBlur) * clarity * clarityAtten, 0.0, 1.0);

            float lumLocal = luma(localBlur);
            float mistMask = smoothstep(0.68, 0.99, lumLocal);
            rgb = mix(rgb, max(rgb, localBlur), mistMask * proMistStrength * 0.35);

            float haloSpread = clamp(minDim * 0.006, 4.0, 14.0);
            half3 haloAvg = (
                inputImage.eval(fragCoord + float2(haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(haloSpread, -haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, -haloSpread)).rgb
            ) * 0.25;
            float haloEnergy = smoothstep(0.84, 1.0, luma(haloAvg));
            rgb += half3(1.0, 0.34, 0.14) * haloEnergy * halationStrength * 0.26 * (1.0 - skinScore * 0.85);

            // 3. Linearize to scene-referred space while preserving midtone anchor
            half3 lin = pow(clamp(rgb, 0.0, 1.0), half3(2.2));

            // 4. Contextual exposure, subject relight (only foreground) & background negative fill (only background)
            float relight = 1.0 + faceRelightGain * (hasSubjectMask > 0.5 ? mask : 0.0) - backgroundDimming * bgWeight;
            float skyFactor = smoothstep(0.55, 0.0, uv.y) * (1.0 - mask * hasSubjectMask) * smoothstep(0.45, 0.92, luma(rgb));
            float skyGain = 1.0 - skyRecovery * skyFactor * 0.36;
            lin *= exp2(exposureOffset) * clamp(relight, 0.78, 1.38) * skyGain;

            // 5. White balance with skin protection
            float wbDamp = mix(1.0, 1.0 - skinProtection * 0.55, skinScore);
            lin.r *= 1.0 + warmth * 0.32 * wbDamp;
            lin.b *= 1.0 - warmth * 0.30 * wbDamp;
            lin.g *= 1.0 + tint * 0.18 * wbDamp;

            // 6. Gentle linear shadow toe & shoulder highlight roll-off (NO linear contrast crush!)
            float lumLin = luma(lin);
            lin += half3(shadowLift * 0.04 * (1.0 - smoothstep(0.0, 0.22, lumLin)));
            float over = max(0.0, lumLin - 0.52);
            float shoulder = 1.0 / (1.0 + over * highlightRollOff * 0.95);
            lin *= mix(1.0, shoulder, smoothstep(0.48, 1.15, lumLin));

            // Convert back to sRGB BEFORE perceptual contrast and color grading
            half3 srgb = pow(clamp(lin, 0.0, 1.0), half3(1.0 / 2.2));

            // 7. True perceptual sRGB contrast pivot at 0.48 + saturation
            srgb = clamp((srgb - 0.48) * contrast + 0.48, 0.0, 1.0);

            float lumOut = luma(srgb);
            if (monochrome > 0.5) {
                float bw = clamp(dot(srgb, half3(0.30, 0.56, 0.14)), 0.0, 1.0);
                srgb = half3(bw, bw * 0.995, bw * 0.985);
            } else {
                float satEffective = mix(saturation, mix(1.0, saturation, 0.45), skinScore * skinProtection);
                srgb = clamp(mix(half3(lumOut), srgb, satEffective), 0.0, 1.0);
            }

            // 8. Soft optical vignette + fine 35mm film grain
            float2 centered = (uv - 0.5) * float2(1.0, imageSize.y / max(imageSize.x, 1.0));
            float distSq = dot(centered, centered);
            srgb *= clamp(1.0 - distSq * vignetteStrength * 0.95, 0.55, 1.0);

            float grain = (hash12(fragCoord) - 0.5) * grainAmount * 0.08 * (1.0 - abs(lumOut - 0.5));
            srgb = clamp(srgb + half3(grain), 0.0, 1.0);

            return half4(srgb, baseSample.a);
        }
    """

    fun renderEditorial(
        source: Bitmap,
        subjectMask: Bitmap,
        recipe: EditRecipe,
        intensity: Float = 1.0f,
        hasConfidentMask: Boolean = true
    ): ShaderRenderResult {
        val scaledRecipe = scaleRecipeIntensity(recipe, intensity.coerceIn(0f, 1.5f))
        val gpuBitmap = runCatching {
            renderWithHardwareRuntimeShader(source, subjectMask, scaledRecipe, hasConfidentMask)
        }.getOrNull()

        if (gpuBitmap != null) {
            return ShaderRenderResult(bitmap = gpuBitmap, gpuAccelerated = true)
        }

        return ShaderRenderResult(
            bitmap = renderCpuFallback(source, subjectMask, scaledRecipe, hasConfidentMask),
            gpuAccelerated = false
        )
    }

    private fun scaleRecipeIntensity(r: EditRecipe, t: Float): EditRecipe {
        if (t == 1.0f) return r
        return r.copy(
            exposureOffset = r.exposureOffset * t,
            contrast = 1.0f + (r.contrast - 1.0f) * t,
            shadowLift = r.shadowLift * t,
            highlightRollOff = r.highlightRollOff * t,
            clarity = r.clarity * t,
            warmth = r.warmth * t,
            tint = r.tint * t,
            saturation = 1.0f + (r.saturation - 1.0f) * t,
            faceRelightGain = r.faceRelightGain * t,
            backgroundDimming = r.backgroundDimming * t,
            backgroundBokeh = r.backgroundBokeh * t,
            skyRecovery = r.skyRecovery * t,
            halationStrength = r.halationStrength * t,
            proMistStrength = r.proMistStrength * t,
            grainAmount = r.grainAmount * t,
            vignetteStrength = r.vignetteStrength * t
        )
    }

    private fun renderWithHardwareRuntimeShader(
        source: Bitmap,
        subjectMask: Bitmap,
        recipe: EditRecipe,
        hasConfidentMask: Boolean
    ): Bitmap {
        val w = source.width
        val h = source.height
        val shader = RuntimeShader(AGSL_EDITORIAL_SHADER)
        shader.setInputShader("inputImage", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("subjectMask", BitmapShader(subjectMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("imageSize", w.toFloat(), h.toFloat())
        shader.setFloatUniform("maskSize", subjectMask.width.toFloat(), subjectMask.height.toFloat())

        shader.setFloatUniform("hasSubjectMask", if (hasConfidentMask) 1.0f else 0.0f)
        shader.setFloatUniform("exposureOffset", recipe.exposureOffset)
        shader.setFloatUniform("contrast", recipe.contrast)
        shader.setFloatUniform("shadowLift", recipe.shadowLift)
        shader.setFloatUniform("highlightRollOff", recipe.highlightRollOff)
        shader.setFloatUniform("clarity", recipe.clarity)
        shader.setFloatUniform("warmth", recipe.warmth)
        shader.setFloatUniform("tint", recipe.tint)
        shader.setFloatUniform("saturation", recipe.saturation)
        shader.setFloatUniform("skinProtection", recipe.skinProtection)
        shader.setFloatUniform("faceRelightGain", recipe.faceRelightGain)
        shader.setFloatUniform("backgroundDimming", recipe.backgroundDimming)
        shader.setFloatUniform("backgroundBokeh", recipe.backgroundBokeh)
        shader.setFloatUniform("skyRecovery", recipe.skyRecovery)
        shader.setFloatUniform("halationStrength", recipe.halationStrength)
        shader.setFloatUniform("proMistStrength", recipe.proMistStrength)
        shader.setFloatUniform("grainAmount", recipe.grainAmount)
        shader.setFloatUniform("vignetteStrength", recipe.vignetteStrength)
        shader.setFloatUniform("monochrome", if (recipe.monochrome) 1.0f else 0.0f)

        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        val node = RenderNode("LuminaEditorialPass").apply {
            setPosition(0, 0, w, h)
            val canvas = beginRecording()
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
            endRecording()
        }

        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val img = reader.acquireLatestImage() ?: error("ImageReader returned null GPU frame.")
            img.use { image ->
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * w
                val outBmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                outBmp.copyPixelsFromBuffer(plane.buffer)
                return if (outBmp.width != w) {
                    Bitmap.createBitmap(outBmp, 0, 0, w, h).also { outBmp.recycle() }
                } else {
                    outBmp
                }
            }
        } finally {
            node.discardDisplayList()
            renderer.destroy()
            reader.close()
        }
    }

    /**
     * Pure-Kotlin calibrated editorial pipeline with background bokeh & foreground preservation.
     */
    private fun renderCpuFallback(
        source: Bitmap,
        subjectMask: Bitmap,
        recipe: EditRecipe,
        hasConfidentMask: Boolean
    ): Bitmap {
        val w = source.width
        val h = source.height
        val srcPx = IntArray(w * h)
        source.getPixels(srcPx, 0, w, 0, 0, w, h)

        val scaledMask = if (subjectMask.width == w && subjectMask.height == h) {
            subjectMask
        } else {
            Bitmap.createScaledBitmap(subjectMask, w, h, true)
        }
        val maskPx = IntArray(w * h)
        scaledMask.getPixels(maskPx, 0, w, 0, 0, w, h)
        if (scaledMask !== subjectMask) scaledMask.recycle()

        val minDim = min(w, h)
        val maxBokehRadius = (minDim * 0.011f * recipe.backgroundBokeh).coerceIn(4f, 22f).toInt()
        val outPx = IntArray(w * h)

        for (y in 0 until h) {
            val ny = y.toFloat() / h - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val mask = if (hasConfidentMask) ((maskPx[i] ushr 16) and 0xFF) / 255f else 1.0f
                val bgWeight = if (hasConfidentMask) smoothstep(0.20f, 0.88f, 1f - mask) else 0f

                var r: Float
                var g: Float
                var b: Float

                if (hasConfidentMask && recipe.backgroundBokeh > 0.01f && bgWeight > 0.02f) {
                    val rad = max(1, (maxBokehRadius * bgWeight).toInt())
                    var sumR = 0f; var sumG = 0f; var sumB = 0f; var wSum = 0f
                    val offsets = arrayOf(
                        0 to 0, rad to 0, -rad to 0, 0 to rad, 0 to -rad,
                        (rad * 0.7f).toInt() to (rad * 0.7f).toInt(),
                        -(rad * 0.7f).toInt() to (rad * 0.7f).toInt(),
                        (rad * 0.7f).toInt() to -(rad * 0.7f).toInt(),
                        -(rad * 0.7f).toInt() to -(rad * 0.7f).toInt()
                    )
                    for ((dx, dy) in offsets) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val sy = (y + dy).coerceIn(0, h - 1)
                        val si = sy * w + sx
                        val sm = ((maskPx[si] ushr 16) and 0xFF) / 255f
                        val sampleW = (1f - smoothstep(0.5f, 0.9f, sm)).coerceAtLeast(0.05f)
                        val sc = srcPx[si]
                        sumR += ((sc ushr 16) and 0xFF) * sampleW
                        sumG += ((sc ushr 8) and 0xFF) * sampleW
                        sumB += (sc and 0xFF) * sampleW
                        wSum += sampleW
                    }
                    val baseC = srcPx[i]
                    val br = ((baseC ushr 16) and 0xFF) / 255f
                    val bg = ((baseC ushr 8) and 0xFF) / 255f
                    val bb = (baseC and 0xFF) / 255f
                    val blurR = (sumR / wSum) / 255f
                    val blurG = (sumG / wSum) / 255f
                    val blurB = (sumB / wSum) / 255f
                    r = br * (1f - bgWeight) + blurR * bgWeight
                    g = bg * (1f - bgWeight) + blurG * bgWeight
                    b = bb * (1f - bgWeight) + blurB * bgWeight
                } else {
                    val c = srcPx[i]
                    r = ((c ushr 16) and 0xFF) / 255f
                    g = ((c ushr 8) and 0xFF) / 255f
                    b = (c and 0xFF) / 255f
                }

                outPx[i] = applyEditorialToneToPixel(
                    r = r, g = g, b = b,
                    nx = x.toFloat() / w - 0.5f,
                    ny = ny,
                    x = x, y = y,
                    mask = mask,
                    hasConfidentMask = hasConfidentMask,
                    recipe = recipe
                )
            }
        }

        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pure-Kotlin per-pixel color & tone grading (directly JVM unit-testable).
     */
    fun applyEditorialToneToPixel(
        r: Float,
        g: Float,
        b: Float,
        nx: Float,
        ny: Float,
        x: Int,
        y: Int,
        mask: Float,
        hasConfidentMask: Boolean,
        recipe: EditRecipe
    ): Int {
        val bgWeight = if (hasConfidentMask) smoothstep(0.20f, 0.88f, 1f - mask) else 0f
        val skinScore = smoothstep(0.05f, 0.24f, r - b) *
            smoothstep(0.02f, 0.14f, r - g) *
            smoothstep(0.18f, 0.85f, r)

        val expMul = 2.0.pow(recipe.exposureOffset.toDouble()).toFloat()
        val relight = (1f + recipe.faceRelightGain * (if (hasConfidentMask) mask else 0f) - recipe.backgroundDimming * bgWeight)
            .coerceIn(0.78f, 1.38f)

        var lr = r.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lg = g.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lb = b.coerceIn(0f, 1f).pow(2.2f) * expMul * relight

        val wbDamp = 1f - (recipe.skinProtection * 0.55f) * skinScore
        lr *= (1f + recipe.warmth * 0.32f * wbDamp)
        lb *= (1f - recipe.warmth * 0.30f * wbDamp)
        lg *= (1f + recipe.tint * 0.18f * wbDamp)

        val lumLin = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
        val lift = recipe.shadowLift * 0.04f * (1f - smoothstep(0f, 0.22f, lumLin))
        lr += lift; lg += lift; lb += lift

        val over = max(0f, lumLin - 0.52f)
        val shoulder = 1f / (1f + over * recipe.highlightRollOff * 0.95f)
        val shoulderMix = smoothstep(0.48f, 1.15f, lumLin)
        val rollScale = (1f - shoulderMix) + shoulder * shoulderMix
        lr *= rollScale; lg *= rollScale; lb *= rollScale

        // Convert linear -> sRGB before perceptual contrast
        var sr = lr.coerceIn(0f, 1f).pow(1f / 2.2f)
        var sg = lg.coerceIn(0f, 1f).pow(1f / 2.2f)
        var sb = lb.coerceIn(0f, 1f).pow(1f / 2.2f)

        sr = ((sr - 0.48f) * recipe.contrast + 0.48f).coerceIn(0f, 1f)
        sg = ((sg - 0.48f) * recipe.contrast + 0.48f).coerceIn(0f, 1f)
        sb = ((sb - 0.48f) * recipe.contrast + 0.48f).coerceIn(0f, 1f)

        val lumOut = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
        if (recipe.monochrome) {
            val bw = (0.30f * sr + 0.56f * sg + 0.14f * sb).coerceIn(0f, 1f)
            sr = bw; sg = bw * 0.995f; sb = bw * 0.985f
        } else {
            val satSkin = 1f + (recipe.saturation - 1f) * 0.45f
            val satEff = recipe.saturation * (1f - skinScore * recipe.skinProtection) + satSkin * (skinScore * recipe.skinProtection)
            sr = (lumOut + (sr - lumOut) * satEff).coerceIn(0f, 1f)
            sg = (lumOut + (sg - lumOut) * satEff).coerceIn(0f, 1f)
            sb = (lumOut + (sb - lumOut) * satEff).coerceIn(0f, 1f)
        }

        val vig = (1f - (nx * nx + ny * ny) * recipe.vignetteStrength * 0.95f).coerceIn(0.55f, 1f)
        val hash = sin(x * 12.9898 + y * 78.233) * 43758.5453
        val grain = ((hash - floor(hash)).toFloat() - 0.5f) * recipe.grainAmount * 0.08f * (1f - abs(lumOut - 0.5f))

        sr = (sr * vig + grain).coerceIn(0f, 1f)
        sg = (sg * vig + grain).coerceIn(0f, 1f)
        sb = (sb * vig + grain).coerceIn(0f, 1f)

        return (0xFF shl 24) or
            ((sr * 255f).toInt() shl 16) or
            ((sg * 255f).toInt() shl 8) or
            (sb * 255f).toInt()
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / max(0.0001f, edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
