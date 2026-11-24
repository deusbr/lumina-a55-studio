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
    private const val TILE_PAD = 64

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
        uniform float dehazeAmount;
        uniform float relight3dStrength;
        uniform float studioBackdropStrength;
        uniform float keyDirectionSign;

        float luma(float3 c) {
            return dot(c, float3(0.2126, 0.7152, 0.0722));
        }

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float4 sampleSemantic(float2 globalCoord) {
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            float2 px = uv * maskSize - float2(0.5);
            float2 f = fract(px);
            float2 p00 = clamp(floor(px) + float2(0.5), float2(0.5), max(maskSize - float2(0.5), float2(0.5)));
            float2 p10 = clamp(p00 + float2(1.0, 0.0), float2(0.5), max(maskSize - float2(0.5), float2(0.5)));
            float2 p01 = clamp(p00 + float2(0.0, 1.0), float2(0.5), max(maskSize - float2(0.5), float2(0.5)));
            float2 p11 = clamp(p00 + float2(1.0, 1.0), float2(0.5), max(maskSize - float2(0.5), float2(0.5)));
            float4 c00 = float4(subjectMask.eval(p00));
            float4 c10 = float4(subjectMask.eval(p10));
            float4 c01 = float4(subjectMask.eval(p01));
            float4 c11 = float4(subjectMask.eval(p11));
            return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
        }

        float sampleBokehWeight(float2 globalCoord) {
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            float fg = hasSubjectMask > 0.5 ? clamp(sampleSemantic(globalCoord).r, 0.0, 1.0) : 1.0;
            float segBg = hasSubjectMask > 0.5 ? clamp(1.0 - fg, 0.0, 1.0) : 0.0;
            if (hasDepthMap > 0.5) {
                float depthCoc = clamp(float(depthMap.eval(uv * depthSize).g), 0.0, 1.0);
                if (hasSubjectMask > 0.5) {
                    float combinedBg = max(depthCoc * 0.72, segBg);
                    return combinedBg * (1.0 - smoothstep(0.55, 0.96, fg));
                }
                return depthCoc;
            }
            return smoothstep(0.04, 0.90, segBg);
        }

        float3 applyStudioBackdrop(float3 col, float2 uv, float2 asp, float weightBg, float fgVal) {
            if (weightBg <= 0.005) return col;
            // Color-decontaminate bright background spill along hair/shoulder edges (stops white aura fringe)
            float edgeZone = smoothstep(0.05, 0.45, fgVal) * (1.0 - smoothstep(0.50, 0.92, fgVal));
            float lum = luma(col);
            float bleed = clamp((lum - 0.38) * 1.5, 0.0, 1.0) * edgeZone;
            float3 cleanCol = mix(col, col * 0.58, bleed);

            if (studioBackdropStrength <= 0.01) return cleanCol;
            float2 haloCenter = hasFace > 0.5 ? faceCenter : float2(0.5, 0.42);
            float dHalo = length((uv - haloCenter) * asp);
            float haloGrad = exp(-dHalo * dHalo * 2.6);
            // Kelvin-separated moody slate backdrop (6200K outer vignette, natural falloff near halo)
            float3 studioNeutral = mix(float3(0.085, 0.088, 0.096), float3(0.20, 0.195, 0.192), haloGrad);
            float3 moodyBg = mix(cleanCol * 0.52, studioNeutral, 0.36);
            float bgAlpha = clamp(pow(weightBg, 0.80) * studioBackdropStrength, 0.0, 1.0);
            return mix(cleanCol, moodyBg, bgAlpha);
        }

        half4 main(float2 fragCoord) {
            float2 globalCoord = fragCoord + tileOrigin;
            float2 uv = clamp(globalCoord / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
            float minDim = min(fullImageSize.x, fullImageSize.y);
            float2 asp = float2(1.0, fullImageSize.y / max(fullImageSize.x, 1.0));

            float4 sem = sampleSemantic(globalCoord);
            float fgMask = hasSubjectMask > 0.5 ? clamp(sem.r, 0.0, 1.0) : 1.0;
            float skinMask = hasSubjectMask > 0.5 ? clamp(sem.g, 0.0, 1.0) : 0.0;
            float detailMask = hasSubjectMask > 0.5 ? clamp(sem.b, 0.0, 1.0) : 0.0;
            float bgWeight = sampleBokehWeight(globalCoord);

            half4 baseSample = inputImage.eval(fragCoord);
            float3 centerBackdropRgb = applyStudioBackdrop(float3(baseSample.rgb), uv, asp, bgWeight, fgMask);
            float3 rgb = centerBackdropRgb;

            // 1. Creamy 24-Tap Vogel Golden-Angle Optical Bokeh (pre-decontaminated taps prevent edge halo bleed)
            if (backgroundBokeh > 0.01 && bgWeight > 0.015) {
                float radius = bgWeight * backgroundBokeh * clamp(minDim * 0.022, 10.0, 52.0);
                float3 acc = centerBackdropRgb;
                float weightSum = 1.0;
                float goldenAngle = 2.39996323;
                for (int i = 0; i < 24; i++) {
                    float fi = float(i + 1);
                    float rFrac = sqrt(fi / 24.0);
                    float ang = fi * goldenAngle;
                    float2 off = float2(cos(ang), sin(ang)) * (rFrac * radius);
                    float2 tapGlobal = globalCoord + off;
                    float2 tapUv = clamp(tapGlobal / max(fullImageSize, float2(1.0)), float2(0.0), float2(1.0));
                    float sf = hasSubjectMask > 0.5 ? clamp(sampleSemantic(tapGlobal).r, 0.0, 1.0) : 0.0;
                    float tapBgWeight = sampleBokehWeight(tapGlobal);
                    float3 tapCol = applyStudioBackdrop(float3(inputImage.eval(fragCoord + off).rgb), tapUv, asp, tapBgWeight, sf);
                    float w = 1.0 - smoothstep(0.28, 0.82, sf);
                    acc += tapCol * w;
                    weightSum += w;
                }
                rgb = mix(centerBackdropRgb, acc / max(weightSum, 0.001), smoothstep(0.02, 0.82, bgWeight));
            }

            // 2. Apply Zero-DCE Neural Gain Map ONLY when genuinely dark scene triggered it
            if (hasNeuralGain > 0.5) {
                float3 encodedGain = float3(neuralGainMap.eval(uv * gainSize).rgb);
                float3 gain = clamp(encodedGain * 2.0 + float3(0.5), float3(0.85), float3(1.85));
                rgb = clamp(rgb * gain, float3(0.0), float3(1.0));
            }

            float heuristicSkin = smoothstep(0.05, 0.24, rgb.r - rgb.b) *
                                  smoothstep(0.02, 0.14, rgb.r - rgb.g) *
                                  smoothstep(0.18, 0.85, rgb.r);
            float activeSkin = max(skinMask, heuristicSkin * 0.55);

            // Keep eyes & eyebrows 100% razor sharp (suppress skin smoothing around eye sockets)
            float eyeKeepSharp = 0.0;
            if (hasFace > 0.5) {
                if (leftEye.x > 0.0) {
                    float dL = length((uv - leftEye) * asp) / max(faceRadius * 0.18, 0.01);
                    eyeKeepSharp = max(eyeKeepSharp, exp(-dL * dL * 2.5));
                }
                if (rightEye.x > 0.0) {
                    float dR = length((uv - rightEye) * asp) / max(faceRadius * 0.18, 0.01);
                    eyeKeepSharp = max(eyeKeepSharp, exp(-dR * dR * 2.5));
                }
            }

            // 3. Anti-AI Realism Clarity (ZERO plastic skin blur! Keeps pores/beard real while clearing glasses/lens fog)
            float blurFine = clamp(minDim * 0.0018, 1.4, 4.0);
            float3 localFine = (
                float3(inputImage.eval(fragCoord + float2(blurFine, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord - float2(blurFine, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord + float2(0.0, blurFine)).rgb) +
                float3(inputImage.eval(fragCoord - float2(0.0, blurFine)).rgb)
            ) * 0.25;

            float blurWide = clamp(minDim * 0.0085, 6.0, 22.0);
            float3 localWide = (
                float3(inputImage.eval(fragCoord + float2(blurWide, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord - float2(blurWide, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord + float2(0.0, blurWide)).rgb) +
                float3(inputImage.eval(fragCoord - float2(0.0, blurWide)).rgb)
            ) * 0.25;

            float interiorGuard = smoothstep(0.45, 0.90, fgMask) * (1.0 - bgWeight);
            // Medium-frequency local contrast cuts optical fog off eyes, glasses lenses, skin & fabric without plastic artifacts
            float localFogClarity = (0.14 + eyeKeepSharp * 0.14) * interiorGuard;
            rgb = clamp(rgb + (rgb - localWide) * localFogClarity, float3(0.0), float3(1.0));

            // Fine crisp acutance strictly on eyes, hair, beard, glasses, clothes (never over-sharpening skin pores)
            float skinDamp = activeSkin * 0.88 * (1.0 - eyeKeepSharp);
            float acutance = (clarity * 0.85 + 0.06 + detailMask * 0.14 + eyeKeepSharp * 0.14) * (1.0 - skinDamp) * interiorGuard;
            rgb = clamp(rgb + (rgb - localFine) * acutance, float3(0.0), float3(1.0));

            // Optional Pro-Mist & Halation (zero by default in Studio/Normal mode; active only when requested)
            if (proMistStrength > 0.01) {
                float mistMask = smoothstep(0.76, 1.0, luma(localFine));
                rgb = mix(rgb, max(rgb, localFine), mistMask * proMistStrength * 0.28);
            }

            if (halationStrength > 0.01) {
                float haloSpread = clamp(minDim * 0.005, 4.0, 14.0);
                float3 haloAvg = (
                    float3(inputImage.eval(fragCoord + float2(haloSpread, haloSpread)).rgb) +
                    float3(inputImage.eval(fragCoord + float2(-haloSpread, haloSpread)).rgb) +
                    float3(inputImage.eval(fragCoord + float2(haloSpread, -haloSpread)).rgb) +
                    float3(inputImage.eval(fragCoord + float2(-haloSpread, -haloSpread)).rgb)
                ) * 0.25;
                float haloEnergy = smoothstep(0.88, 1.0, luma(haloAvg));
                rgb += float3(1.0, 0.34, 0.14) * haloEnergy * halationStrength * 0.22 * (1.0 - activeSkin * 0.90);
            }

            // 4. Linearize + 3D Normal-Map Studio Relighting (45° Softbox + Negative Fill + Rim + Dodge & Burn)
            float3 lin = pow(max(clamp(rgb, float3(0.0), float3(1.0)), float3(0.0001)), float3(2.2));

            float faceFocus = fgMask * (0.20 + 0.80 * activeSkin);
            float keyBoost = 0.0;
            float eyeSparkle = 0.0;
            float underEyeFill = 0.0;
            if (hasFace > 0.5) {
                float dFace = length((uv - faceCenter) * asp) / max(faceRadius, 0.08);
                float faceSpot = exp(-dFace * dFace * 1.10);
                keyBoost = faceSpot * 0.15 * (0.25 + 0.75 * activeSkin);
                faceFocus = mix(0.12, 1.0, faceSpot) * fgMask * (0.30 + 0.70 * activeSkin);
                if (leftEye.x > 0.0) {
                    float dL = length((uv - leftEye) * asp) / max(faceRadius * 0.10, 0.005);
                    eyeSparkle += exp(-dL * dL * 2.4) * 0.24;
                    float2 underL = (uv - float2(leftEye.x, leftEye.y + faceRadius * 0.09)) * asp;
                    float duL = length(underL) / max(faceRadius * 0.14, 0.01);
                    underEyeFill = max(underEyeFill, exp(-duL * duL * 2.2) * 0.14);
                }
                if (rightEye.x > 0.0) {
                    float dR = length((uv - rightEye) * asp) / max(faceRadius * 0.10, 0.005);
                    eyeSparkle += exp(-dR * dR * 2.4) * 0.24;
                    float2 underR = (uv - float2(rightEye.x, rightEye.y + faceRadius * 0.09)) * asp;
                    float duR = length(underR) / max(faceRadius * 0.14, 0.01);
                    underEyeFill = max(underEyeFill, exp(-duR * duR * 2.2) * 0.14);
                }
            }

            float normalRelight3d = 1.0;
            if (relight3dStrength > 0.01 && hasDepthMap > 0.5) {
                float2 dUV = clamp(uv, float2(0.01), float2(0.99)) * depthSize;
                float dStep = 2.0;
                float dL = float(depthMap.eval(dUV - float2(dStep, 0.0)).r);
                float dR = float(depthMap.eval(dUV + float2(dStep, 0.0)).r);
                float dU = float(depthMap.eval(dUV - float2(0.0, dStep)).r);
                float dD = float(depthMap.eval(dUV + float2(0.0, dStep)).r);
                float3 surfN = normalize(float3((dL - dR) * 4.2, (dU - dD) * 4.2, 0.34));
                float3 keyDir = normalize(float3(keyDirectionSign * 0.58, -0.42, 0.68));
                float ndotl = clamp(dot(surfN, keyDir), 0.0, 1.0);
                float negFill = clamp(-dot(surfN, keyDir), 0.0, 1.0);
                float kickerSide = clamp(surfN.x * (-keyDirectionSign) * 0.8 + 0.25, 0.0, 1.0);
                float fresnelRim = pow(max(0.001, 1.0 - abs(surfN.z)), 4.0) * kickerSide * fgMask * (1.0 - smoothstep(0.18, 0.42, luma(rgb)));
                float sculpt = (ndotl * 0.28 - negFill * 0.20 + fresnelRim * 0.09) * fgMask * relight3dStrength;
                normalRelight3d = clamp(1.0 + sculpt + underEyeFill * relight3dStrength, 0.74, 1.42);
            }

            float relight = 1.0 + (faceRelightGain * faceFocus + keyBoost) - backgroundDimming * bgWeight;
            float skyFactor = smoothstep(0.55, 0.0, uv.y) * (1.0 - fgMask * hasSubjectMask) * smoothstep(0.45, 0.92, luma(rgb));
            float skyGain = 1.0 - skyRecovery * skyFactor * 0.36;
            lin *= exp2(exposureOffset) * clamp(relight, 0.52, 1.45) * normalRelight3d * skyGain * (1.0 + eyeSparkle);

            float wbDamp = mix(1.0, 1.0 - skinProtection * 0.55, activeSkin);
            lin.r *= 1.0 + warmth * 0.30 * wbDamp;
            lin.b *= 1.0 - warmth * 0.28 * wbDamp;
            lin.g *= 1.0 + tint * 0.16 * wbDamp;

            // Black-Anchored Mid-Shadow Lift (never lifts true 0,0,0 black into milky gray!)
            float lumLin = luma(lin);
            float midShadowBand = smoothstep(0.015, 0.075, lumLin) * (1.0 - smoothstep(0.10, 0.32, lumLin));
            lin *= 1.0 + shadowLift * 0.55 * midShadowBand;

            float over = max(0.0, lumLin - 0.54);
            float shoulder = 1.0 / (1.0 + over * highlightRollOff * 0.95);
            lin *= mix(1.0, shoulder, smoothstep(0.50, 1.15, lumLin));

            // 5. Studio Print Grading in sRGB Space + Adaptive Veiling-Glare & Overhead Hair Haze Cut
            float3 srgb = pow(max(clamp(lin, float3(0.0), float3(1.0)), float3(0.0001)), float3(1.0 / 2.2));
            if (dehazeAmount > 0.002) {
                float dh = clamp(dehazeAmount, 0.0, 0.12);
                srgb = clamp((srgb - float3(dh)) / max(0.85, 1.0 - dh), float3(0.0), float3(1.0));
            }
            float darkDetail = clamp(detailMask * (1.0 - activeSkin) * fgMask, 0.0, 1.0);
            if (darkDetail > 0.05) {
                float lDet = luma(srgb);
                float hazeZone = smoothstep(0.08, 0.36, lDet) * (1.0 - smoothstep(0.44, 0.70, lDet));
                srgb *= mix(1.0, 0.70, hazeZone * darkDetail);
                float coolExcess = smoothstep(0.0, 0.05, srgb.b - srgb.r);
                srgb.b = mix(srgb.b, (srgb.r + srgb.g) * 0.49, 0.45 * darkDetail * coolExcess);
            }
            srgb = clamp((srgb - 0.50) * contrast + 0.50, float3(0.0), float3(1.0));

            // Subtle Split-Tone (clean neutral highlights + crisp studio shadows)
            float lumOut = luma(srgb);
            if (monochrome > 0.5) {
                float bw = clamp(dot(srgb, float3(0.30, 0.56, 0.14)), 0.0, 1.0);
                srgb = float3(bw, bw * 0.995, bw * 0.985);
            } else {
                float3 coolShadow = float3(-0.008, 0.0, 0.012) * (1.0 - smoothstep(0.0, 0.30, lumOut)) * (1.0 - activeSkin);
                float3 warmHigh = float3(0.006, 0.002, -0.004) * smoothstep(0.58, 0.95, lumOut);
                srgb = clamp(srgb + coolShadow + warmHigh, float3(0.0), float3(1.0));
                float satEffective = mix(saturation, mix(1.0, saturation, 0.45), activeSkin * skinProtection);
                srgb = clamp(mix(float3(lumOut), srgb, satEffective), float3(0.0), float3(1.0));
            }

            float2 centered = (uv - 0.5) * float2(1.0, fullImageSize.y / max(fullImageSize.x, 1.0));
            float distSq = dot(centered, centered);
            srgb *= clamp(1.0 - distSq * vignetteStrength * 0.95, 0.55, 1.0);

            float grain = (hash12(globalCoord) - 0.5) * grainAmount * 0.075 * (1.0 - abs(lumOut - 0.5));
            srgb = clamp(srgb + float3(grain), float3(0.0), float3(1.0));

            return half4(half3(srgb), baseSample.a);
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
        }.onFailure { err ->
            android.util.Log.e("EditorialShaderEngine", "GPU RuntimeShader failed, falling back to CPU", err)
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
            vignetteStrength = r.vignetteStrength * t,
            dehazeAmount = r.dehazeAmount * t,
            relight3dStrength = r.relight3dStrength * t,
            studioBackdropStrength = r.studioBackdropStrength * t
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
        shader.setFloatUniform("dehazeAmount", recipe.dehazeAmount)
        shader.setFloatUniform("relight3dStrength", recipe.relight3dStrength)
        shader.setFloatUniform("studioBackdropStrength", recipe.studioBackdropStrength)
        shader.setFloatUniform("keyDirectionSign", recipe.keyDirectionSign)

        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_CPU_READ_OFTEN
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
                val planeBmp = runCatching {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * w
                    val outBmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    outBmp.copyPixelsFromBuffer(plane.buffer)
                    if (outBmp.width != w) {
                        Bitmap.createBitmap(outBmp, 0, 0, w, h).also { outBmp.recycle() }
                    } else {
                        outBmp
                    }
                }.getOrNull()
                if (planeBmp != null) return planeBmp

                val hwBmp = image.hardwareBuffer?.let { hb ->
                    try {
                        Bitmap.wrapHardwareBuffer(hb, null)?.let { wrapped ->
                            wrapped.copy(Bitmap.Config.ARGB_8888, false).also { wrapped.recycle() }
                        }
                    } finally {
                        hb.close()
                    }
                }
                return hwBmp ?: error("Failed to read RGBA_8888 GPU frame from ImageReader.")
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
            .coerceIn(0.68f, 1.38f)

        var lr = r.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lg = g.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lb = b.coerceIn(0f, 1f).pow(2.2f) * expMul * relight

        val wbDamp = 1f - (recipe.skinProtection * 0.55f) * skinScore
        lr *= (1f + recipe.warmth * 0.30f * wbDamp)
        lb *= (1f - recipe.warmth * 0.28f * wbDamp)
        lg *= (1f + recipe.tint * 0.16f * wbDamp)

        val lumLin = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
        val midShadowBand = smoothstep(0.015f, 0.075f, lumLin) * (1f - smoothstep(0.10f, 0.32f, lumLin))
        val liftMul = 1f + recipe.shadowLift * 0.55f * midShadowBand
        lr *= liftMul; lg *= liftMul; lb *= liftMul

        val over = max(0f, lumLin - 0.54f)
        val shoulder = 1f / (1f + over * recipe.highlightRollOff * 0.95f)
        val shoulderMix = smoothstep(0.50f, 1.15f, lumLin)
        val rollScale = (1f - shoulderMix) + shoulder * shoulderMix
        lr *= rollScale; lg *= rollScale; lb *= rollScale

        var sr = lr.coerceIn(0f, 1f).pow(1f / 2.2f)
        var sg = lg.coerceIn(0f, 1f).pow(1f / 2.2f)
        var sb = lb.coerceIn(0f, 1f).pow(1f / 2.2f)

        if (recipe.dehazeAmount > 0.002f) {
            val dh = recipe.dehazeAmount.coerceIn(0f, 0.12f)
            val denom = max(0.85f, 1f - dh)
            sr = ((sr - dh) / denom).coerceIn(0f, 1f)
            sg = ((sg - dh) / denom).coerceIn(0f, 1f)
            sb = ((sb - dh) / denom).coerceIn(0f, 1f)
        }
        sr = ((sr - 0.50f) * recipe.contrast + 0.50f).coerceIn(0f, 1f)
        sg = ((sg - 0.50f) * recipe.contrast + 0.50f).coerceIn(0f, 1f)
        sb = ((sb - 0.50f) * recipe.contrast + 0.50f).coerceIn(0f, 1f)

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
