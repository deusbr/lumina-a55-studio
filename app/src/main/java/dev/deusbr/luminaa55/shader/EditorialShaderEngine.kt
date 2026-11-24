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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ShaderRenderResult(
    val bitmap: Bitmap,
    val gpuAccelerated: Boolean
)

/**
 * Hardware-accelerated AGSL RuntimeShader pipeline targeting Galaxy A55 Xclipse 530 (RDNA2) GPU,
 * with a deterministic CPU fallback for software-only environments.
 */
object EditorialShaderEngine {

    private const val AGSL_EDITORIAL_SHADER = """
        uniform shader inputImage;
        uniform shader subjectMask;
        uniform float2 imageSize;
        uniform float2 maskSize;

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

        half3 acesFilm(half3 x) {
            float a = 2.51;
            float b = 0.03;
            float c = 2.43;
            float d = 0.59;
            float e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(imageSize, float2(1.0));
            float2 maskCoord = uv * maskSize;
            float mask = clamp(float(subjectMask.eval(maskCoord).r), 0.0, 1.0);

            // 1. Base sample + optional background optical bokeh blur
            half4 baseSample = inputImage.eval(fragCoord);
            half3 rgb = baseSample.rgb;

            if (backgroundBokeh > 0.01 && mask < 0.92) {
                float blurRadius = (1.0 - mask) * backgroundBokeh * 11.0;
                half3 acc = rgb;
                acc += inputImage.eval(fragCoord + float2(blurRadius, 0.0)).rgb;
                acc += inputImage.eval(fragCoord + float2(-blurRadius, 0.0)).rgb;
                acc += inputImage.eval(fragCoord + float2(0.0, blurRadius)).rgb;
                acc += inputImage.eval(fragCoord + float2(0.0, -blurRadius)).rgb;
                acc += inputImage.eval(fragCoord + float2(blurRadius * 0.7, blurRadius * 0.7)).rgb;
                acc += inputImage.eval(fragCoord + float2(-blurRadius * 0.7, blurRadius * 0.7)).rgb;
                acc += inputImage.eval(fragCoord + float2(blurRadius * 0.7, -blurRadius * 0.7)).rgb;
                acc += inputImage.eval(fragCoord + float2(-blurRadius * 0.7, -blurRadius * 0.7)).rgb;
                rgb = mix(rgb, acc / 9.0, 1.0 - mask);
            }

            // 2. Local clarity / micro-contrast + Black Pro-Mist + Halation glow
            float blurStep = 4.5;
            half3 localBlur = (
                inputImage.eval(fragCoord + float2(blurStep, 0.0)).rgb +
                inputImage.eval(fragCoord - float2(blurStep, 0.0)).rgb +
                inputImage.eval(fragCoord + float2(0.0, blurStep)).rgb +
                inputImage.eval(fragCoord - float2(0.0, blurStep)).rgb
            ) * 0.25;

            float lumLocal = luma(localBlur);
            float mistMask = smoothstep(0.55, 0.98, lumLocal);
            rgb += (rgb - localBlur) * clarity * (0.4 + 0.6 * mask);
            rgb = mix(rgb, max(rgb, localBlur), mistMask * proMistStrength * 0.45);

            // Halation warm red-amber bleed around bright highlights
            float haloSpread = 9.0;
            half3 haloAvg = (
                inputImage.eval(fragCoord + float2(haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(haloSpread, -haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, -haloSpread)).rgb
            ) * 0.25;
            float haloEnergy = smoothstep(0.76, 1.0, luma(haloAvg));
            rgb += half3(1.0, 0.34, 0.16) * haloEnergy * halationStrength * 0.38;

            // 3. Linearize to scene-referred space
            half3 lin = pow(max(rgb, half3(0.0)), half3(2.2));

            // 4. Contextual exposure, subject relighting & negative fill
            float relight = 1.0 + faceRelightGain * mask - backgroundDimming * (1.0 - mask);
            float skyFactor = smoothstep(0.58, 0.0, uv.y) * (1.0 - mask) * smoothstep(0.45, 0.92, luma(rgb));
            float skyGain = 1.0 - skyRecovery * skyFactor * 0.42;
            lin *= exp2(exposureOffset) * relight * skyGain;

            // 5. White balance warmth / tint with skin-safe damping
            float skinScore = smoothstep(0.04, 0.25, float(rgb.r - rgb.b)) *
                              smoothstep(0.01, 0.14, float(rgb.r - rgb.g)) *
                              smoothstep(0.15, 0.85, float(rgb.r));
            float wbDamp = mix(1.0, 1.0 - skinProtection * 0.55, skinScore);
            lin.r *= 1.0 + warmth * 0.36 * wbDamp;
            lin.b *= 1.0 - warmth * 0.34 * wbDamp;
            lin.g *= 1.0 + tint * 0.22 * wbDamp;

            // 6. Shadow lift + Highlight roll-off + ACES Filmic Tone Mapping
            float lumLin = luma(lin);
            lin += half3(shadowLift * 0.08 * (1.0 - smoothstep(0.0, 0.35, lumLin)));
            float roll = 1.0 / (1.0 + max(0.0, lumLin - 0.55) * highlightRollOff * 1.4);
            lin *= mix(1.0, roll, smoothstep(0.45, 1.2, lumLin));

            half3 mapped = acesFilm(lin * 1.18);

            // Contrast pivot around 0.42
            mapped = clamp((mapped - 0.42) * contrast + 0.42, 0.0, 1.0);

            // 7. Saturation or Leica Monochrome conversion
            float lumOut = luma(mapped);
            if (monochrome > 0.5) {
                float bw = clamp(dot(mapped, half3(0.32, 0.54, 0.14)), 0.0, 1.0);
                mapped = half3(bw, bw * 0.995, bw * 0.985);
            } else {
                float satEffective = mix(saturation, mix(1.0, saturation, 0.45), skinScore * skinProtection);
                mapped = clamp(mix(half3(lumOut), mapped, satEffective), 0.0, 1.0);
            }

            // 8. Optical lens vignette + fine organic film grain
            float2 centered = (uv - 0.5) * float2(1.0, imageSize.y / max(imageSize.x, 1.0));
            float distSq = dot(centered, centered);
            mapped *= clamp(1.0 - distSq * vignetteStrength * 1.25, 0.35, 1.0);

            float grain = (hash12(fragCoord) - 0.5) * grainAmount * 0.14 * (1.0 - abs(lumOut - 0.45));
            mapped = clamp(mapped + half3(grain), 0.0, 1.0);

            half3 srgb = pow(mapped, half3(1.0 / 2.2));
            return half4(srgb, baseSample.a);
        }
    """

    fun renderEditorial(
        source: Bitmap,
        subjectMask: Bitmap,
        recipe: EditRecipe,
        intensity: Float = 1.0f
    ): ShaderRenderResult {
        val scaledRecipe = scaleRecipeIntensity(recipe, intensity.coerceIn(0f, 1.5f))
        val gpuBitmap = runCatching {
            renderWithHardwareRuntimeShader(source, subjectMask, scaledRecipe)
        }.getOrNull()

        if (gpuBitmap != null) {
            return ShaderRenderResult(bitmap = gpuBitmap, gpuAccelerated = true)
        }

        return ShaderRenderResult(
            bitmap = renderCpuFallback(source, subjectMask, scaledRecipe),
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
        recipe: EditRecipe
    ): Bitmap {
        val w = source.width
        val h = source.height
        val shader = RuntimeShader(AGSL_EDITORIAL_SHADER)
        shader.setInputShader("inputImage", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("subjectMask", BitmapShader(subjectMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("imageSize", w.toFloat(), h.toFloat())
        shader.setFloatUniform("maskSize", subjectMask.width.toFloat(), subjectMask.height.toFloat())

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
     * Pure-Kotlin ACES + semantic-mask fallback renderer when GPU HardwareRenderer is unavailable.
     */
    private fun renderCpuFallback(source: Bitmap, subjectMask: Bitmap, recipe: EditRecipe): Bitmap {
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

        val outPx = IntArray(w * h)
        val expMul = 2.0.pow(recipe.exposureOffset.toDouble()).toFloat()

        for (y in 0 until h) {
            val ny = y.toFloat() / h - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val c = srcPx[i]
                var r = ((c ushr 16) and 0xFF) / 255f
                var g = ((c ushr 8) and 0xFF) / 255f
                var b = (c and 0xFF) / 255f

                val mask = ((maskPx[i] ushr 16) and 0xFF) / 255f
                val relight = 1f + recipe.faceRelightGain * mask - recipe.backgroundDimming * (1f - mask)

                // Linearize
                r = r.pow(2.2f) * expMul * relight
                g = g.pow(2.2f) * expMul * relight
                b = b.pow(2.2f) * expMul * relight

                r *= (1f + recipe.warmth * 0.34f)
                b *= (1f - recipe.warmth * 0.32f)
                g *= (1f + recipe.tint * 0.2f)

                r += recipe.shadowLift * 0.06f
                g += recipe.shadowLift * 0.06f
                b += recipe.shadowLift * 0.06f

                // ACES Filmic approximation
                r = aces(r * 1.16f)
                g = aces(g * 1.16f)
                b = aces(b * 1.16f)

                r = ((r - 0.42f) * recipe.contrast + 0.42f).coerceIn(0f, 1f)
                g = ((g - 0.42f) * recipe.contrast + 0.42f).coerceIn(0f, 1f)
                b = ((b - 0.42f) * recipe.contrast + 0.42f).coerceIn(0f, 1f)

                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (recipe.monochrome) {
                    val bw = (0.32f * r + 0.54f * g + 0.14f * b).coerceIn(0f, 1f)
                    r = bw; g = bw * 0.995f; b = bw * 0.985f
                } else {
                    r = (lum + (r - lum) * recipe.saturation).coerceIn(0f, 1f)
                    g = (lum + (g - lum) * recipe.saturation).coerceIn(0f, 1f)
                    b = (lum + (b - lum) * recipe.saturation).coerceIn(0f, 1f)
                }

                val nx = x.toFloat() / w - 0.5f
                val vig = (1f - (nx * nx + ny * ny) * recipe.vignetteStrength * 1.25f).coerceIn(0.35f, 1f)
                val grain = (sin((x * 12.9898 + y * 78.233)) * 43758.5453).let { (it - kotlin.math.floor(it)).toFloat() - 0.5f } * recipe.grainAmount * 0.08f

                r = (r * vig + grain).coerceIn(0f, 1f).pow(1f / 2.2f)
                g = (g * vig + grain).coerceIn(0f, 1f).pow(1f / 2.2f)
                b = (b * vig + grain).coerceIn(0f, 1f).pow(1f / 2.2f)

                outPx[i] = Color.argb(255, (r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
            }
        }

        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun aces(x: Float): Float {
        val v = max(0f, x)
        return ((v * (2.51f * v + 0.03f)) / (v * (2.43f * v + 0.59f) + 0.14f)).coerceIn(0f, 1f)
    }
}
