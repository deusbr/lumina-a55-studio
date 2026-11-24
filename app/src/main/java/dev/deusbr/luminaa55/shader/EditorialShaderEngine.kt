package dev.deusbr.luminaa55.shader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
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
import dev.deusbr.luminaa55.model.StudioLightingHint
import kotlin.math.abs
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
 * Hardware-accelerated AGSL Studio Shader combining:
 * - Zero-DCE neural light curve gain map transfer at 100% sensor resolution
 * - MiDaS progressive 3D optical bokeh + Multi-Class semantic protection
 * - Frequency-separated studio skin retouching (evens blotches, preserves pores)
 * - Hair/clothing micro-acutance + 3D face softbox key light & eye catchlights
 */
object EditorialShaderEngine {

    private const val MAX_SINGLE_PASS_DIM = 4096
    private const val TILE_CORE_SIZE = 2048
    private const val TILE_PAD = 48

    private val neutralMask1x1 by lazy {
        Bitmap.createBitmap(intArrayOf(Color.argb(255, 255, 0, 0)), 1, 1, Bitmap.Config.ARGB_8888)
    }
    private val neutralDepth1x1 by lazy {
        Bitmap.createBitmap(intArrayOf(Color.argb(255, 128, 0, 128)), 1, 1, Bitmap.Config.ARGB_8888)
    }
    // Encoded 1.0x gain: (1.0 - 0.5) / 2.0 = 0.25 -> 64
    private val neutralGain1x1 by lazy {
        Bitmap.createBitmap(intArrayOf(Color.argb(255, 64, 64, 64)), 1, 1, Bitmap.Config.ARGB_8888)
    }

    private const val AGSL_STUDIO_SHADER = """
        uniform shader inputImage;
        uniform shader subjectMask;
        uniform shader depthMap;
        uniform shader neuralGainMap;

        uniform float2 fullImageSize;
        uniform float2 tileOrigin;
        uniform float2 maskSize;
        uniform float2 depthSize;
        uniform float2 gainSize;

        uniform float hasSubjectMask;
        uniform float hasDepthMap;
        uniform float hasNeuralGain;

        uniform float hasFace;
        uniform float2 faceCenter;
        uniform float faceRadius;
        uniform float2 leftEye;
        uniform float2 rightEye;

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

        float4 sampleSemantic(float2 globalCoord) {
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            return subjectMask.eval(uv * maskSize);
        }

        float sampleBokehWeight(float2 globalCoord) {
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            float fg = hasSubjectMask > 0.5 ? clamp(float(subjectMask.eval(uv * maskSize).r), 0.0, 1.0) : 1.0;
            float segBg = hasSubjectMask > 0.5 ? smoothstep(0.18, 0.86, 1.0 - fg) : 0.0;
            if (hasDepthMap > 0.5) {
                float depthCoc = clamp(float(depthMap.eval(uv * depthSize).g), 0.0, 1.0);
                // Fuse 3D depth falloff with semantic foreground protection so planes near subject stay razor sharp
                return hasSubjectMask > 0.5 ? depthCoc * (1.0 - smoothstep(0.45, 0.90, fg)) : depthCoc;
            }
            return segBg;
        }

        half4 main(float2 fragCoord) {
            float2 globalCoord = fragCoord + tileOrigin;
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            float minDim = min(fullImageSize.x, fullImageSize.y);

            float4 sem = sampleSemantic(globalCoord);
            float fgMask = hasSubjectMask > 0.5 ? clamp(sem.r, 0.0, 1.0) : 1.0;
            float skinMask = hasSubjectMask > 0.5 ? clamp(sem.g, 0.0, 1.0) : 0.0;
            float detailMask = hasSubjectMask > 0.5 ? clamp(sem.b, 0.0, 1.0) : 0.0;
            float bgWeight = sampleBokehWeight(globalCoord);

            half4 baseSample = inputImage.eval(fragCoord);
            half3 rgb = baseSample.rgb;

            // 1. Progressive 3D Optical Bokeh (MiDaS Depth + Semantic Foreground Bleed Guard)
            if (backgroundBokeh > 0.01 && bgWeight > 0.02) {
                float radius = bgWeight * backgroundBokeh * clamp(minDim * 0.013, 6.0, 38.0);
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

                float2 off; float w; float sf;
                off = d0 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d1 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d2 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d3 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d4 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d5 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d6 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d7 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d8 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d9 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d10 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;
                off = d11 * radius; sf = sampleSemantic(globalCoord + off).r; w = 1.0 - smoothstep(0.45, 0.88, sf);
                acc += inputImage.eval(fragCoord + off).rgb * w; weightSum += w;

                rgb = mix(rgb, acc / max(weightSum, 0.001), bgWeight);
            }

            // 2. Apply Zero-DCE Neural Exposure/Curve Gain Map cleanly at 100% full resolution
            if (hasNeuralGain > 0.5) {
                half3 encodedGain = neuralGainMap.eval(uv * gainSize).rgb;
                half3 gain = clamp(encodedGain * 2.0 + half3(0.5), half3(0.75), half3(2.25));
                rgb = clamp(rgb * gain, 0.0, 1.0);
            }

            float heuristicSkin = smoothstep(0.05, 0.24, float(rgb.r - rgb.b)) *
                                  smoothstep(0.02, 0.14, float(rgb.r - rgb.g)) *
                                  smoothstep(0.18, 0.85, float(rgb.r));
            float activeSkin = max(skinMask, heuristicSkin * 0.65);

            // 3. Frequency-Separated Studio Skin Retouch + Hair/Clothes Micro-Acutance
            float blurFine = clamp(minDim * 0.0022, 1.6, 5.0);
            half3 localFine = (
                inputImage.eval(fragCoord + float2(blurFine, 0.0)).rgb +
                inputImage.eval(fragCoord - float2(blurFine, 0.0)).rgb +
                inputImage.eval(fragCoord + float2(0.0, blurFine)).rgb +
                inputImage.eval(fragCoord - float2(0.0, blurFine)).rgb
            ) * 0.25;

            if (activeSkin > 0.12) {
                float blurWide = blurFine * 2.8;
                half3 localWide = (
                    inputImage.eval(fragCoord + float2(blurWide, 0.0)).rgb +
                    inputImage.eval(fragCoord - float2(blurWide, 0.0)).rgb +
                    inputImage.eval(fragCoord + float2(0.0, blurWide)).rgb +
                    inputImage.eval(fragCoord - float2(0.0, blurWide)).rgb
                ) * 0.25;
                // Keep fine pore detail (rgb - localFine) while smoothing uneven blotches
                half3 smoothedSkin = clamp(localWide + (rgb - localFine) * 0.88, 0.0, 1.0);
                rgb = mix(rgb, smoothedSkin, activeSkin * 0.34);
            }

            // Hair, Eyes & Clothes Texture Acutance (strictly suppresses skin grain)
            float acutance = (clarity + 0.06 + detailMask * 0.10) * (1.0 - activeSkin * 0.82) * (1.0 - bgWeight * 0.80);
            rgb = clamp(rgb + (rgb - localFine) * acutance, 0.0, 1.0);

            // Specular-only Black Pro-Mist & Halation
            float mistMask = smoothstep(0.68, 0.99, luma(localFine));
            rgb = mix(rgb, max(rgb, localFine), mistMask * proMistStrength * 0.32);

            float haloSpread = clamp(minDim * 0.005, 4.0, 14.0);
            half3 haloAvg = (
                inputImage.eval(fragCoord + float2(haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(haloSpread, -haloSpread)).rgb +
                inputImage.eval(fragCoord + float2(-haloSpread, -haloSpread)).rgb
            ) * 0.25;
            float haloEnergy = smoothstep(0.85, 1.0, luma(haloAvg));
            rgb += half3(1.0, 0.34, 0.14) * haloEnergy * halationStrength * 0.24 * (1.0 - activeSkin * 0.88);

            // 4. Linearize + Sculpted 3D Studio Softbox Key Light & Eye Catchlights
            half3 lin = pow(clamp(rgb, 0.0, 1.0), half3(2.2));

            float keyBoost = 0.0;
            float eyeSparkle = 0.0;
            if (hasFace > 0.5) {
                float2 asp = float2(1.0, fullImageSize.y / max(fullImageSize.x, 1.0));
                float dFace = length((uv - faceCenter) * asp) / max(faceRadius, 0.08);
                keyBoost = exp(-dFace * dFace * 1.15) * 0.14;
                if (leftEye.x > 0.0) {
                    float dL = length((uv - leftEye) * asp) / max(faceRadius * 0.11, 0.006);
                    eyeSparkle += exp(-dL * dL * 2.2) * 0.20;
                }
                if (rightEye.x > 0.0) {
                    float dR = length((uv - rightEye) * asp) / max(faceRadius * 0.11, 0.006);
                    eyeSparkle += exp(-dR * dR * 2.2) * 0.20;
                }
            }

            float relight = 1.0 + (faceRelightGain * fgMask + keyBoost) - backgroundDimming * bgWeight;
            float skyFactor = smoothstep(0.55, 0.0, uv.y) * (1.0 - fgMask * hasSubjectMask) * smoothstep(0.45, 0.92, luma(rgb));
            float skyGain = 1.0 - skyRecovery * skyFactor * 0.36;
            lin *= exp2(exposureOffset) * clamp(relight, 0.78, 1.42) * skyGain * (1.0 + eyeSparkle);

            float wbDamp = mix(1.0, 1.0 - skinProtection * 0.55, activeSkin);
            lin.r *= 1.0 + warmth * 0.30 * wbDamp;
            lin.b *= 1.0 - warmth * 0.28 * wbDamp;
            lin.g *= 1.0 + tint * 0.16 * wbDamp;

            // Filmic Toe & Silky Highlight Shoulder (never crushes midtones)
            float lumLin = luma(lin);
            lin += half3(shadowLift * 0.038 * (1.0 - smoothstep(0.0, 0.22, lumLin)));
            float over = max(0.0, lumLin - 0.52);
            float shoulder = 1.0 / (1.0 + over * highlightRollOff * 0.95);
            lin *= mix(1.0, shoulder, smoothstep(0.48, 1.15, lumLin));

            // 5. Studio Print Grading in sRGB Space
            half3 srgb = pow(clamp(lin, 0.0, 1.0), half3(1.0 / 2.2));
            srgb = clamp((srgb - 0.48) * contrast + 0.48, 0.0, 1.0);

            // Subtle Split-Tone (warm silky highlights + crisp studio shadows)
            float lumOut = luma(srgb);
            if (monochrome > 0.5) {
                float bw = clamp(dot(srgb, half3(0.30, 0.56, 0.14)), 0.0, 1.0);
                srgb = half3(bw, bw * 0.995, bw * 0.985);
            } else {
                half3 coolShadow = half3(-0.012, 0.0, 0.014) * (1.0 - smoothstep(0.0, 0.32, lumOut)) * (1.0 - activeSkin);
                half3 warmHigh = half3(0.015, 0.008, -0.010) * smoothstep(0.55, 0.95, lumOut);
                srgb = clamp(srgb + coolShadow + warmHigh, 0.0, 1.0);
                float satEffective = mix(saturation, mix(1.0, saturation, 0.45), activeSkin * skinProtection);
                srgb = clamp(mix(half3(lumOut), srgb, satEffective), 0.0, 1.0);
            }

            float2 centered = (uv - 0.5) * float2(1.0, fullImageSize.y / max(fullImageSize.x, 1.0));
            float distSq = dot(centered, centered);
            srgb *= clamp(1.0 - distSq * vignetteStrength * 0.95, 0.55, 1.0);

            float grain = (hash12(globalCoord) - 0.5) * grainAmount * 0.075 * (1.0 - abs(lumOut - 0.5));
            srgb = clamp(srgb + half3(grain), 0.0, 1.0);

            return half4(srgb, baseSample.a);
        }
    """

    fun renderEditorial(
        source: Bitmap,
        subjectMask: Bitmap?,
        depthMap: Bitmap? = null,
        neuralGainMap: Bitmap? = null,
        lightingHint: StudioLightingHint = StudioLightingHint(),
        recipe: EditRecipe,
        intensity: Float = 1.0f,
        hasConfidentMask: Boolean = true
    ): ShaderRenderResult {
        val scaledRecipe = scaleRecipeIntensity(recipe, intensity.coerceIn(0f, 1.5f))
        val safeMask = subjectMask ?: neutralMask1x1
        val safeDepth = depthMap ?: neutralDepth1x1
        val safeGain = neuralGainMap ?: neutralGain1x1

        val gpuBitmap = runCatching {
            if (max(source.width, source.height) <= MAX_SINGLE_PASS_DIM) {
                renderSingleTileGpu(
                    tileSource = source,
                    subjectMask = safeMask,
                    depthMap = safeDepth,
                    neuralGainMap = safeGain,
                    hasDepth = depthMap != null,
                    hasGain = neuralGainMap != null,
                    lightingHint = lightingHint,
                    fullW = source.width,
                    fullH = source.height,
                    tileOriginX = 0f,
                    tileOriginY = 0f,
                    recipe = scaledRecipe,
                    hasConfidentMask = hasConfidentMask && subjectMask != null
                )
            } else {
                renderTiledGpu(
                    source = source,
                    subjectMask = safeMask,
                    depthMap = safeDepth,
                    neuralGainMap = safeGain,
                    hasDepth = depthMap != null,
                    hasGain = neuralGainMap != null,
                    lightingHint = lightingHint,
                    recipe = scaledRecipe,
                    hasConfidentMask = hasConfidentMask && subjectMask != null
                )
            }
        }.getOrNull()

        if (gpuBitmap != null) {
            return ShaderRenderResult(bitmap = gpuBitmap, gpuAccelerated = true)
        }

        return ShaderRenderResult(
            bitmap = renderCpuFallback(source, safeMask, scaledRecipe, hasConfidentMask && subjectMask != null),
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

    private fun renderTiledGpu(
        source: Bitmap,
        subjectMask: Bitmap,
        depthMap: Bitmap,
        neuralGainMap: Bitmap,
        hasDepth: Boolean,
        hasGain: Boolean,
        lightingHint: StudioLightingHint,
        recipe: EditRecipe,
        hasConfidentMask: Boolean
    ): Bitmap {
        val fullW = source.width
        val fullH = source.height
        val output = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(output)

        var y = 0
        while (y < fullH) {
            val coreH = min(TILE_CORE_SIZE, fullH - y)
            val srcY0 = max(0, y - TILE_PAD)
            val srcY1 = min(fullH, y + coreH + TILE_PAD)
            val srcH = srcY1 - srcY0

            var x = 0
            while (x < fullW) {
                val coreW = min(TILE_CORE_SIZE, fullW - x)
                val srcX0 = max(0, x - TILE_PAD)
                val srcX1 = min(fullW, x + coreW + TILE_PAD)
                val srcW = srcX1 - srcX0

                val subSrc = Bitmap.createBitmap(source, srcX0, srcY0, srcW, srcH)
                val subRendered = renderSingleTileGpu(
                    tileSource = subSrc,
                    subjectMask = subjectMask,
                    depthMap = depthMap,
                    neuralGainMap = neuralGainMap,
                    hasDepth = hasDepth,
                    hasGain = hasGain,
                    lightingHint = lightingHint,
                    fullW = fullW,
                    fullH = fullH,
                    tileOriginX = srcX0.toFloat(),
                    tileOriginY = srcY0.toFloat(),
                    recipe = recipe,
                    hasConfidentMask = hasConfidentMask
                )
                subSrc.recycle()

                val cropX = x - srcX0
                val cropY = y - srcY0
                val coreTile = Bitmap.createBitmap(subRendered, cropX, cropY, coreW, coreH)
                subRendered.recycle()
                outCanvas.drawBitmap(coreTile, x.toFloat(), y.toFloat(), null)
                coreTile.recycle()

                x += TILE_CORE_SIZE
            }
            y += TILE_CORE_SIZE
        }
        return output
    }

    private fun renderSingleTileGpu(
        tileSource: Bitmap,
        subjectMask: Bitmap,
        depthMap: Bitmap,
        neuralGainMap: Bitmap,
        hasDepth: Boolean,
        hasGain: Boolean,
        lightingHint: StudioLightingHint,
        fullW: Int,
        fullH: Int,
        tileOriginX: Float,
        tileOriginY: Float,
        recipe: EditRecipe,
        hasConfidentMask: Boolean
    ): Bitmap {
        val w = tileSource.width
        val h = tileSource.height
        val shader = RuntimeShader(AGSL_STUDIO_SHADER)
        shader.setInputShader("inputImage", BitmapShader(tileSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("subjectMask", BitmapShader(subjectMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("depthMap", BitmapShader(depthMap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("neuralGainMap", BitmapShader(neuralGainMap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

        shader.setFloatUniform("fullImageSize", fullW.toFloat(), fullH.toFloat())
        shader.setFloatUniform("tileOrigin", tileOriginX, tileOriginY)
        shader.setFloatUniform("maskSize", subjectMask.width.toFloat(), subjectMask.height.toFloat())
        shader.setFloatUniform("depthSize", depthMap.width.toFloat(), depthMap.height.toFloat())
        shader.setFloatUniform("gainSize", neuralGainMap.width.toFloat(), neuralGainMap.height.toFloat())

        shader.setFloatUniform("hasSubjectMask", if (hasConfidentMask) 1.0f else 0.0f)
        shader.setFloatUniform("hasDepthMap", if (hasDepth) 1.0f else 0.0f)
        shader.setFloatUniform("hasNeuralGain", if (hasGain) 1.0f else 0.0f)

        shader.setFloatUniform("hasFace", if (lightingHint.hasFace) 1.0f else 0.0f)
        shader.setFloatUniform("faceCenter", lightingHint.faceCenterX, lightingHint.faceCenterY)
        shader.setFloatUniform("faceRadius", lightingHint.faceRadius)
        shader.setFloatUniform("leftEye", lightingHint.leftEyeX, lightingHint.leftEyeY)
        shader.setFloatUniform("rightEye", lightingHint.rightEyeX, lightingHint.rightEyeY)

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

    private fun renderCpuFallback(
        source: Bitmap,
        subjectMask: Bitmap,
        recipe: EditRecipe,
        hasConfidentMask: Boolean
    ): Bitmap {
        val w = source.width
        val h = source.height
        val scaledMask = if (subjectMask.width == w && subjectMask.height == h) {
            subjectMask
        } else {
            Bitmap.createScaledBitmap(subjectMask, w, h, true)
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val minDim = min(w, h)
        val maxBokehRadius = (minDim * 0.011f * recipe.backgroundBokeh).coerceIn(4f, 22f).toInt()
        val stripRows = 256
        val srcRow = IntArray(w)
        val maskRow = IntArray(w)

        if (!hasConfidentMask || recipe.backgroundBokeh <= 0.01f) {
            val outRow = IntArray(w)
            for (y in 0 until h) {
                source.getPixels(srcRow, 0, w, 0, y, w, 1)
                scaledMask.getPixels(maskRow, 0, w, 0, y, w, 1)
                val ny = y.toFloat() / h - 0.5f
                for (x in 0 until w) {
                    val c = srcRow[x]
                    val mask = if (hasConfidentMask) ((maskRow[x] ushr 16) and 0xFF) / 255f else 1.0f
                    outRow[x] = applyEditorialToneToPixel(
                        r = ((c ushr 16) and 0xFF) / 255f,
                        g = ((c ushr 8) and 0xFF) / 255f,
                        b = (c and 0xFF) / 255f,
                        nx = x.toFloat() / w - 0.5f,
                        ny = ny,
                        x = x, y = y,
                        mask = mask,
                        hasConfidentMask = hasConfidentMask,
                        recipe = recipe
                    )
                }
                outBmp.setPixels(outRow, 0, w, 0, y, w, 1)
            }
            if (scaledMask !== subjectMask) scaledMask.recycle()
            return outBmp
        }

        val srcPx = IntArray(w * min(h, stripRows + maxBokehRadius * 2))
        val maskPx = IntArray(w * min(h, stripRows + maxBokehRadius * 2))

        var y0 = 0
        while (y0 < h) {
            val yEnd = min(h, y0 + stripRows)
            val readTop = max(0, y0 - maxBokehRadius)
            val readBot = min(h, yEnd + maxBokehRadius)
            val readH = readBot - readTop
            source.getPixels(srcPx, 0, w, 0, readTop, w, readH)
            scaledMask.getPixels(maskPx, 0, w, 0, readTop, w, readH)

            val outStrip = IntArray(w * (yEnd - y0))
            for (y in y0 until yEnd) {
                val localY = y - readTop
                val ny = y.toFloat() / h - 0.5f
                val outRowOffset = (y - y0) * w
                for (x in 0 until w) {
                    val idx = localY * w + x
                    val mask = ((maskPx[idx] ushr 16) and 0xFF) / 255f
                    val bgWeight = smoothstep(0.20f, 0.88f, 1f - mask)

                    val r: Float; val g: Float; val b: Float
                    if (bgWeight > 0.02f) {
                        val rad = max(1, (maxBokehRadius * bgWeight).toInt())
                        var sumR = 0f; var sumG = 0f; var sumB = 0f; var wSum = 0f
                        val d7 = (rad * 0.7f).toInt()
                        val offsets = arrayOf(
                            0 to 0, rad to 0, -rad to 0, 0 to rad, 0 to -rad,
                            d7 to d7, -d7 to d7, d7 to -d7, -d7 to -d7
                        )
                        for ((dx, dy) in offsets) {
                            val sx = (x + dx).coerceIn(0, w - 1)
                            val sy = (localY + dy).coerceIn(0, readH - 1)
                            val si = sy * w + sx
                            val sm = ((maskPx[si] ushr 16) and 0xFF) / 255f
                            val sw = (1f - smoothstep(0.5f, 0.9f, sm)).coerceAtLeast(0.05f)
                            val sc = srcPx[si]
                            sumR += ((sc ushr 16) and 0xFF) * sw
                            sumG += ((sc ushr 8) and 0xFF) * sw
                            sumB += (sc and 0xFF) * sw
                            wSum += sw
                        }
                        val bc = srcPx[idx]
                        r = (((bc ushr 16) and 0xFF) / 255f) * (1f - bgWeight) + (sumR / wSum / 255f) * bgWeight
                        g = (((bc ushr 8) and 0xFF) / 255f) * (1f - bgWeight) + (sumG / wSum / 255f) * bgWeight
                        b = ((bc and 0xFF) / 255f) * (1f - bgWeight) + (sumB / wSum / 255f) * bgWeight
                    } else {
                        val c = srcPx[idx]
                        r = ((c ushr 16) and 0xFF) / 255f
                        g = ((c ushr 8) and 0xFF) / 255f
                        b = (c and 0xFF) / 255f
                    }
                    outStrip[outRowOffset + x] = applyEditorialToneToPixel(
                        r = r, g = g, b = b,
                        nx = x.toFloat() / w - 0.5f,
                        ny = ny,
                        x = x, y = y,
                        mask = mask,
                        hasConfidentMask = true,
                        recipe = recipe
                    )
                }
            }
            outBmp.setPixels(outStrip, 0, w, 0, y0, w, yEnd - y0)
            y0 += stripRows
        }

        if (scaledMask !== subjectMask) scaledMask.recycle()
        return outBmp
    }

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
        lr *= (1f + recipe.warmth * 0.30f * wbDamp)
        lb *= (1f - recipe.warmth * 0.28f * wbDamp)
        lg *= (1f + recipe.tint * 0.16f * wbDamp)

        val lumLin = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
        val lift = recipe.shadowLift * 0.038f * (1f - smoothstep(0f, 0.22f, lumLin))
        lr += lift; lg += lift; lb += lift

        val over = max(0f, lumLin - 0.52f)
        val shoulder = 1f / (1f + over * recipe.highlightRollOff * 0.95f)
        val shoulderMix = smoothstep(0.48f, 1.15f, lumLin)
        val rollScale = (1f - shoulderMix) + shoulder * shoulderMix
        lr *= rollScale; lg *= rollScale; lb *= rollScale

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
        val grain = ((hash - floor(hash)).toFloat() - 0.5f) * recipe.grainAmount * 0.075f * (1f - abs(lumOut - 0.5f))

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
