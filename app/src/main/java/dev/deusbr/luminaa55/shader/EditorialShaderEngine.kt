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
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class ShaderRenderResult(
    val bitmap: Bitmap,
    val gpuAccelerated: Boolean
)

/**
 * Hardware-accelerated AGSL Studio Shader (Lumina A55 v5.0 — OKLab / Capture One Engine):
 * - Pure OKLab Luminance (L) dehaze + S-curve toe/shoulder (zero orange shift on melanin skin)
 * - Skin Hue Lock (15°–42°) + Fluorescent green-to-magenta midtone cast neutralization
 * - Selective frequency-separated sharpness restricted to eyes/glasses/hair/fabric
 * - Gentle 1–3px optical lens blur (depth + subject mask) without painted black cutouts or halos
 * - GPU Black Pro-Mist 1/8 highlight bloom (L > 0.84) + Melanin-safe Silver Gelatin B&W
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
        uniform shader blurredImage;
        uniform shader subjectMask;
        uniform shader depthMap;
        uniform shader neuralGainMap;

        uniform float2 fullImageSize;
        uniform float2 tileOrigin;
        uniform float2 blurSize;
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
        uniform float keyDirectionSign;

        float luma(float3 c) {
            return dot(c, float3(0.2126, 0.7152, 0.0722));
        }

        float hash12(float2 p) {
            float3 p3 = fract(float3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float cbrtSigned(float x) {
            float ax = max(abs(x), 1e-6);
            return sign(x) * pow(ax, 1.0 / 3.0);
        }

        float3 linearToOklab(float3 c) {
            float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
            float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
            float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;

            float l_ = cbrtSigned(l);
            float m_ = cbrtSigned(m);
            float s_ = cbrtSigned(s);

            return float3(
                0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
                1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
                0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
            );
        }

        float3 oklabToLinear(float3 lab) {
            float l_ = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
            float m_ = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
            float s_ = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;

            float l = l_ * l_ * l_;
            float m = m_ * m_ * m_;
            float s = s_ * s_ * s_;

            return max(float3(
                +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            ), float3(0.0));
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
            float4 sem = sampleSemantic(globalCoord);
            float fg = hasSubjectMask > 0.5 ? clamp(sem.r, 0.0, 1.0) : 1.0;
            float skin = hasSubjectMask > 0.5 ? clamp(sem.g, 0.0, 1.0) : 0.0;
            if (hasSubjectMask > 0.5 && (skin > 0.25 || fg > 0.86)) return 0.0;
            float segBg = hasSubjectMask > 0.5 ? smoothstep(0.18, 0.85, 1.0 - fg) : 0.0;
            if (hasDepthMap > 0.5) {
                float depthCoc = clamp(float(depthMap.eval(uv * depthSize).g), 0.0, 1.0);
                return clamp(mix(segBg, depthCoc * segBg, 0.45), 0.0, 1.0) * (1.0 - smoothstep(0.58, 0.92, fg));
            }
            return segBg;
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
            float3 origRgb = float3(baseSample.rgb);
            float3 mistSample = float3(blurredImage.eval(uv * blurSize).rgb);
            float3 rgb = origRgb;

            // 1. Subtle 1–3px Optical Depth Blur (zero cutout border, zero black backdrop paint)
            if (backgroundBokeh > 0.01 && bgWeight > 0.02) {
                float blurMix = smoothstep(0.08, 0.90, bgWeight) * clamp(backgroundBokeh * 0.72, 0.0, 0.72);
                rgb = mix(origRgb, mistSample, blurMix);
            }

            // 2. Apply Zero-DCE Neural Gain Map ONLY when dark scene triggered it
            if (hasNeuralGain > 0.5) {
                float3 encodedGain = float3(neuralGainMap.eval(uv * gainSize).rgb);
                float3 gain = clamp(encodedGain * 2.0 + float3(0.5), float3(0.90), float3(1.65));
                rgb = clamp(rgb * gain, float3(0.0), float3(1.0));
            }

            float heuristicSkin = smoothstep(0.04, 0.22, rgb.r - rgb.b) *
                                  smoothstep(0.015, 0.12, rgb.r - rgb.g) *
                                  smoothstep(0.16, 0.85, rgb.r);
            float activeSkin = clamp(max(skinMask, heuristicSkin * 0.65), 0.0, 1.0);

            // Keep eyes, eyebrows & glasses razor sharp (never soften eye zones)
            float eyeKeepSharp = 0.0;
            if (hasFace > 0.5) {
                if (leftEye.x > 0.0) {
                    float dL = length((uv - leftEye) * asp) / max(faceRadius * 0.22, 0.01);
                    eyeKeepSharp = max(eyeKeepSharp, exp(-dL * dL * 2.4));
                }
                if (rightEye.x > 0.0) {
                    float dR = length((uv - rightEye) * asp) / max(faceRadius * 0.22, 0.01);
                    eyeKeepSharp = max(eyeKeepSharp, exp(-dR * dR * 2.4));
                }
            }

            // 3. Selective Frequency-Separated Crispness (eyes, glasses, beard, hair, fabric ONLY — never smooth skin)
            float blurFine = clamp(minDim * 0.0014, 1.2, 3.2);
            float3 localFine = (
                float3(inputImage.eval(fragCoord + float2(blurFine, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord - float2(blurFine, 0.0)).rgb) +
                float3(inputImage.eval(fragCoord + float2(0.0, blurFine)).rgb) +
                float3(inputImage.eval(fragCoord - float2(0.0, blurFine)).rgb)
            ) * 0.25;
            float lOrig = luma(rgb);
            float lFine = luma(localFine);
            float interiorGuard = smoothstep(0.45, 0.90, fgMask) * (1.0 - bgWeight);
            float skinProtect = activeSkin * 0.90 * (1.0 - eyeKeepSharp);
            float acutance = (clarity * 0.72 + detailMask * 0.14 + eyeKeepSharp * 0.24) * (1.0 - skinProtect) * interiorGuard;
            float detailDelta = (lOrig - lFine) * acutance;
            rgb = clamp(rgb + float3(detailDelta), float3(0.0), float3(1.0));

            // 4. Linearize & Subtle Motivated Portrait Key + Ocular Catchlight (no noisy 256px MiDaS normal map)
            float3 lin = pow(max(clamp(rgb, float3(0.0), float3(1.0)), float3(0.0001)), float3(2.2));
            float faceFocus = fgMask * (0.25 + 0.75 * activeSkin);
            float keyLift = 0.0;
            float eyeSparkle = 0.0;
            if (hasFace > 0.5) {
                float dFace = length((uv - faceCenter) * asp) / max(faceRadius, 0.08);
                float faceSpot = exp(-dFace * dFace * 1.15);
                float sideBias = 1.0 + clamp((uv.x - faceCenter.x) * (-keyDirectionSign) * 0.45, -0.15, 0.22);
                keyLift = faceSpot * sideBias * faceRelightGain * 0.65;
                if (leftEye.x > 0.0) {
                    float dL = length((uv - leftEye) * asp) / max(faceRadius * 0.09, 0.005);
                    eyeSparkle += exp(-dL * dL * 2.5) * 0.18;
                }
                if (rightEye.x > 0.0) {
                    float dR = length((uv - rightEye) * asp) / max(faceRadius * 0.09, 0.005);
                    eyeSparkle += exp(-dR * dR * 2.5) * 0.18;
                }
            } else {
                keyLift = faceRelightGain * faceFocus * 0.45;
            }

            float relight = 1.0 + keyLift - backgroundDimming * bgWeight * 0.65;
            float skyFactor = smoothstep(0.55, 0.0, uv.y) * (1.0 - fgMask * hasSubjectMask) * smoothstep(0.45, 0.92, luma(rgb));
            float skyGain = 1.0 - skyRecovery * skyFactor * 0.34;
            lin *= exp2(exposureOffset) * clamp(relight, 0.74, 1.32) * skyGain * (1.0 + eyeSparkle);

            // Soft white-balance nudge (reduced on protected skin)
            float wbDamp = mix(1.0, 1.0 - skinProtection * 0.55, activeSkin);
            lin.r *= 1.0 + warmth * 0.22 * wbDamp;
            lin.b *= 1.0 - warmth * 0.20 * wbDamp;

            // 5. OKLab Luminance (L) Capture One Curve, Shadow-Weighted Dehaze & Chromatic Guard
            float3 lab = linearToOklab(lin);
            float L0 = clamp(lab.x, 0.0, 1.5);

            // Luminance-only Black-Point Dehaze (anchors lifted shadows cleanly without shifting skin orange!)
            float L1 = L0;
            if (dehazeAmount > 0.002) {
                float dh = clamp(dehazeAmount, 0.0, 0.09);
                float shadowWeight = 1.0 - smoothstep(0.32, 0.85, L0);
                float Lsub = clamp(L0 - dh * 0.68 * shadowWeight, 0.0, 1.5);
                L1 = clamp(Lsub / max(0.88, 1.0 - dh * 0.52), 0.0, 1.45);
            }

            // Mid-shadow open lift (only above true black 0.06..0.38 L)
            float midShadowBand = smoothstep(0.06, 0.18, L1) * (1.0 - smoothstep(0.24, 0.46, L1));
            L1 += shadowLift * 0.055 * midShadowBand;

            // Capture One Filmic S-Curve & Highlight Shoulder on L ONLY
            float Lclamped = clamp(L1, 0.0, 1.0);
            float sCurveDelta = (contrast - 1.0) * 0.62 * Lclamped * (1.0 - Lclamped) * (2.0 * Lclamped - 1.0);
            L1 = clamp(L1 + sCurveDelta, 0.0, 1.35);

            if (L1 > 0.62) {
                float overL = L1 - 0.62;
                float compL = 0.62 + overL / (1.0 + overL * highlightRollOff * 1.10);
                L1 = mix(L1, compL, smoothstep(0.62, 0.92, L1));
            }

            // Rescale OKLab (a, b) smoothly to match Luminance delta without chroma blow-out
            float chromaGain = clamp((L1 + 0.04) / (L0 + 0.04), 0.90, 1.12);
            float newA = lab.y * chromaGain;
            float newB = lab.z * chromaGain;

            // Midtone fluorescent green -> magenta cast neutralization (tint < 0 adds +a magenta cleanly)
            float midtoneCastBand = smoothstep(0.14, 0.34, L1) * (1.0 - smoothstep(0.74, 0.94, L1));
            newA += (-tint) * 0.24 * midtoneCastBand * (1.0 - activeSkin * 0.25);

            // Skin Hue Lock (15°–42° = 0.26..0.74 rad in OKLab a/b) prevents brown/tan skin turning orange or olive
            if (activeSkin > 0.15) {
                float cMag = length(float2(newA, newB));
                if (cMag > 0.008) {
                    float hAngle = atan(newB, newA);
                    float lockedAngle = clamp(hAngle, 0.26, 0.74);
                    float finalAngle = mix(hAngle, lockedAngle, 0.65 * activeSkin * skinProtection);
                    float satSkin = mix(1.0, clamp(saturation, 0.92, 1.10), 0.40);
                    float cAdjusted = cMag * mix(saturation, satSkin, activeSkin * skinProtection);
                    newA = cos(finalAngle) * cAdjusted;
                    newB = sin(finalAngle) * cAdjusted;
                }
            } else {
                float satEff = mix(1.0, saturation, smoothstep(0.05, 0.25, L1));
                newA *= satEff;
                newB *= satEff;
            }

            // Subtle cinema split-tone in OKLab (cool background shadows 6500K, gentle warm highlights)
            if (warmth < -0.008) {
                float shadowBg = (1.0 - smoothstep(0.08, 0.42, L1)) * (1.0 - activeSkin);
                newB -= 0.012 * shadowBg;
                newA -= 0.003 * shadowBg;
            }

            float3 linOut = oklabToLinear(float3(clamp(L1, 0.0, 1.0), newA, newB));
            float3 srgb = pow(max(clamp(linOut, float3(0.0), float3(1.0)), float3(0.0001)), float3(1.0 / 2.2));

            // 6. GPU Black Pro-Mist 1/8 (soft highlight bloom ONLY at L > 0.84 — zero pore blur)
            if (proMistStrength > 0.005) {
                float mistEnergy = smoothstep(0.84, 0.99, luma(mistSample));
                float3 mistGlow = mistSample * (1.0 - srgb);
                srgb = clamp(srgb + mistGlow * mistEnergy * clamp(proMistStrength * 0.95, 0.0, 0.14), float3(0.0), float3(1.0));
            }

            if (halationStrength > 0.01) {
                float haloEnergy = smoothstep(0.88, 1.0, luma(mistSample));
                srgb = clamp(srgb + float3(1.0, 0.34, 0.14) * haloEnergy * halationStrength * 0.16 * (1.0 - activeSkin * 0.90), float3(0.0), float3(1.0));
            }

            // 7. Melanin-safe Silver Gelatin B&W (0.45*R + 0.45*G + 0.10*B)
            if (monochrome > 0.5) {
                float bw = clamp(dot(srgb, float3(0.45, 0.45, 0.10)), 0.0, 1.0);
                srgb = float3(bw, bw * 0.995, bw * 0.985);
            }

            // 8. Natural Optical Lens Vignette (-0.25 to -0.45 EV corner falloff) + Fine 35mm Grain
            float2 centered = (uv - 0.5) * float2(1.0, fullImageSize.y / max(fullImageSize.x, 1.0));
            float distSq = dot(centered, centered);
            srgb *= clamp(1.0 - distSq * vignetteStrength * 0.72, 0.70, 1.0);

            float lumFinal = luma(srgb);
            float grain = (hash12(globalCoord) - 0.5) * grainAmount * 0.065 * (1.0 - abs(lumFinal - 0.5));
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
        val needsBlurPyramid = (scaledRecipe.backgroundBokeh > 0.01f && hasConfidentMask && subjectMask != null) ||
            scaledRecipe.proMistStrength > 0.005f ||
            scaledRecipe.halationStrength > 0.01f
        val blurredBg = if (needsBlurPyramid) buildSmoothBokehPyramid(source, scaledRecipe.backgroundBokeh) else source

        val gpuBitmap = runCatching {
            if (max(source.width, source.height) <= MAX_SINGLE_PASS_DIM) {
                renderSingleTileGpu(
                    tileSource = source,
                    blurredSource = blurredBg,
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
                    blurredSource = blurredBg,
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

        if (blurredBg !== source) blurredBg.recycle()

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
            relight3dStrength = 0.0f,
            studioBackdropStrength = 0.0f
        )
    }

    /**
     * Gentle 1–3px optical lens blur pyramid (no heavy Gaussian smear or glowing halos).
     */
    private fun buildSmoothBokehPyramid(source: Bitmap, bokehStrength: Float): Bitmap {
        val divisor = (2.4f + bokehStrength * 1.4f).coerceIn(2.2f, 3.8f)
        val bw = max(48, (source.width / divisor).toInt())
        val bh = max(48, (source.height / divisor).toInt())
        val tiny = Bitmap.createScaledBitmap(source, bw, bh, true)
        val px = IntArray(bw * bh)
        tiny.getPixels(px, 0, bw, 0, 0, bw, bh)
        tiny.recycle()

        val radius = 2
        val pass1 = boxBlurArgb(px, bw, bh, radius)
        val pass2 = boxBlurArgb(pass1, bw, bh, radius)
        return Bitmap.createBitmap(pass2, bw, bh, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurArgb(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val win = 2 * r + 1
        val horiz = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = src[row + k.coerceIn(0, w - 1)]
                sr += (c ushr 16) and 0xFF; sg += (c ushr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                horiz[row + x] = Color.argb(255, sr / win, sg / win, sb / win)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                sr += ((add ushr 16) and 0xFF) - ((sub ushr 16) and 0xFF)
                sg += ((add ushr 8) and 0xFF) - ((sub ushr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = horiz[k.coerceIn(0, h - 1) * w + x]
                sr += (c ushr 16) and 0xFF; sg += (c ushr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = Color.argb(255, sr / win, sg / win, sb / win)
                val add = horiz[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = horiz[(y - r).coerceAtLeast(0) * w + x]
                sr += ((add ushr 16) and 0xFF) - ((sub ushr 16) and 0xFF)
                sg += ((add ushr 8) and 0xFF) - ((sub ushr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    private fun renderTiledGpu(
        source: Bitmap,
        blurredSource: Bitmap,
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
                    blurredSource = blurredSource,
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
        blurredSource: Bitmap,
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
        shader.setInputShader("blurredImage", BitmapShader(blurredSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("subjectMask", BitmapShader(subjectMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("depthMap", BitmapShader(depthMap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("neuralGainMap", BitmapShader(neuralGainMap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))

        shader.setFloatUniform("fullImageSize", fullW.toFloat(), fullH.toFloat())
        shader.setFloatUniform("tileOrigin", tileOriginX, tileOriginY)
        shader.setFloatUniform("blurSize", blurredSource.width.toFloat(), blurredSource.height.toFloat())
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
        val maxBokehRadius = (minDim * 0.002f * recipe.backgroundBokeh).coerceIn(1f, 3f).toInt()
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
                        val offsets = arrayOf(0 to 0, rad to 0, -rad to 0, 0 to rad, 0 to -rad)
                        for ((dx, dy) in offsets) {
                            val sx = (x + dx).coerceIn(0, w - 1)
                            val sy = (localY + dy).coerceIn(0, readH - 1)
                            val si = sy * w + sx
                            val sm = ((maskPx[si] ushr 16) and 0xFF) / 255f
                            val sw = (1f - smoothstep(0.55f, 0.90f, sm)).coerceAtLeast(0.05f)
                            val sc = srcPx[si]
                            sumR += ((sc ushr 16) and 0xFF) * sw
                            sumG += ((sc ushr 8) and 0xFF) * sw
                            sumB += (sc and 0xFF) * sw
                            wSum += sw
                        }
                        val bc = srcPx[idx]
                        r = (((bc ushr 16) and 0xFF) / 255f) * (1f - bgWeight * 0.72f) + (sumR / wSum / 255f) * (bgWeight * 0.72f)
                        g = (((bc ushr 8) and 0xFF) / 255f) * (1f - bgWeight * 0.72f) + (sumG / wSum / 255f) * (bgWeight * 0.72f)
                        b = ((bc and 0xFF) / 255f) * (1f - bgWeight * 0.72f) + (sumB / wSum / 255f) * (bgWeight * 0.72f)
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

    /**
     * Pure-Kotlin OKLab Capture One tone pipeline matching the AGSL GPU shader pixel math (JVM testable).
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
        val skinScore = smoothstep(0.04f, 0.22f, r - b) *
            smoothstep(0.015f, 0.12f, r - g) *
            smoothstep(0.16f, 0.85f, r)

        val expMul = 2.0.pow(recipe.exposureOffset.toDouble()).toFloat()
        val relight = (1f + recipe.faceRelightGain * 0.55f * (if (hasConfidentMask) mask else 1f) - recipe.backgroundDimming * bgWeight * 0.65f)
            .coerceIn(0.74f, 1.32f)

        var lr = r.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lg = g.coerceIn(0f, 1f).pow(2.2f) * expMul * relight
        var lb = b.coerceIn(0f, 1f).pow(2.2f) * expMul * relight

        val wbDamp = 1f - (recipe.skinProtection * 0.55f) * skinScore
        lr *= (1f + recipe.warmth * 0.22f * wbDamp)
        lb *= (1f - recipe.warmth * 0.20f * wbDamp)

        // Convert Linear RGB -> OKLab
        val lCone = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb
        val mCone = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb
        val sCone = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb
        val lCbrt = cbrt(lCone.toDouble()).toFloat()
        val mCbrt = cbrt(mCone.toDouble()).toFloat()
        val sCbrt = cbrt(sCone.toDouble()).toFloat()

        val l0 = (0.2104542553f * lCbrt + 0.7936177850f * mCbrt - 0.0040720468f * sCbrt).coerceIn(0f, 1.5f)
        val okA0 = 1.9779984951f * lCbrt - 2.4285922050f * mCbrt + 0.4505937099f * sCbrt
        val okB0 = 0.0259040371f * lCbrt + 0.7827717662f * mCbrt - 0.8086757660f * sCbrt

        // Luminance-only dehaze + shadow lift + Capture One Filmic S-curve + Highlight Shoulder
        var l1 = l0
        if (recipe.dehazeAmount > 0.002f) {
            val dh = recipe.dehazeAmount.coerceIn(0f, 0.09f)
            val shadowWeight = 1f - smoothstep(0.32f, 0.85f, l0)
            val lSub = (l0 - dh * 0.68f * shadowWeight).coerceIn(0f, 1.5f)
            l1 = (lSub / max(0.88f, 1f - dh * 0.52f)).coerceIn(0f, 1.45f)
        }
        val midShadowBand = smoothstep(0.06f, 0.18f, l1) * (1f - smoothstep(0.24f, 0.46f, l1))
        l1 += recipe.shadowLift * 0.055f * midShadowBand

        val lClamp = l1.coerceIn(0f, 1f)
        val sCurveDelta = (recipe.contrast - 1f) * 0.62f * lClamp * (1f - lClamp) * (2f * lClamp - 1f)
        l1 = (l1 + sCurveDelta).coerceIn(0f, 1.35f)
        if (l1 > 0.62f) {
            val overL = l1 - 0.62f
            val compL = 0.62f + overL / (1f + overL * recipe.highlightRollOff * 1.10f)
            val shoulderMix = smoothstep(0.62f, 0.92f, l1)
            l1 = l1 * (1f - shoulderMix) + compL * shoulderMix
        }

        val chromaGain = ((l1 + 0.04f) / (l0 + 0.04f)).coerceIn(0.90f, 1.12f)
        var newA = okA0 * chromaGain
        var newB = okB0 * chromaGain

        val midtoneCastBand = smoothstep(0.14f, 0.34f, l1) * (1f - smoothstep(0.74f, 0.94f, l1))
        newA += (-recipe.tint) * 0.24f * midtoneCastBand * (1f - skinScore * 0.25f)

        if (skinScore > 0.15f) {
            val cMag = hypot(newA, newB)
            if (cMag > 0.008f) {
                val hAngle = atan2(newB, newA)
                val lockedAngle = hAngle.coerceIn(0.26f, 0.74f)
                val mixWeight = 0.65f * skinScore * recipe.skinProtection
                val finalAngle = hAngle * (1f - mixWeight) + lockedAngle * mixWeight
                val satSkin = 1f + (recipe.saturation.coerceIn(0.92f, 1.10f) - 1f) * 0.40f
                val satEff = recipe.saturation * (1f - skinScore * recipe.skinProtection) + satSkin * (skinScore * recipe.skinProtection)
                val cAdj = cMag * satEff
                newA = cos(finalAngle) * cAdj
                newB = sin(finalAngle) * cAdj
            }
        } else {
            val satEff = 1f + (recipe.saturation - 1f) * smoothstep(0.05f, 0.25f, l1)
            newA *= satEff
            newB *= satEff
        }

        if (recipe.warmth < -0.008f) {
            val shadowBg = (1f - smoothstep(0.08f, 0.42f, l1)) * (1f - skinScore)
            newB -= 0.012f * shadowBg
            newA -= 0.003f * shadowBg
        }

        // Convert OKLab -> Linear RGB
        val lFinal = l1.coerceIn(0f, 1f)
        val lOut_ = lFinal + 0.3963377774f * newA + 0.2158037573f * newB
        val mOut_ = lFinal - 0.1055613458f * newA - 0.0638541728f * newB
        val sOut_ = lFinal - 0.0894841775f * newA - 1.2914855480f * newB
        val lo = lOut_ * lOut_ * lOut_
        val mo = mOut_ * mOut_ * mOut_
        val so = sOut_ * sOut_ * sOut_

        val outLinR = (+4.0767416621f * lo - 3.3077115913f * mo + 0.2309699292f * so).coerceIn(0f, 1f)
        val outLinG = (-1.2684380046f * lo + 2.6097574011f * mo - 0.3413193965f * so).coerceIn(0f, 1f)
        val outLinB = (-0.0041960863f * lo - 0.7034186147f * mo + 1.7076147010f * so).coerceIn(0f, 1f)

        var sr = outLinR.pow(1f / 2.2f)
        var sg = outLinG.pow(1f / 2.2f)
        var sb = outLinB.pow(1f / 2.2f)

        if (recipe.monochrome) {
            val bw = (0.45f * sr + 0.45f * sg + 0.10f * sb).coerceIn(0f, 1f)
            sr = bw; sg = bw * 0.995f; sb = bw * 0.985f
        }

        val vig = (1f - (nx * nx + ny * ny) * recipe.vignetteStrength * 0.72f).coerceIn(0.70f, 1f)
        val lumOut = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
        val hash = sin(x * 12.9898 + y * 78.233) * 43758.5453
        val grain = ((hash - floor(hash)).toFloat() - 0.5f) * recipe.grainAmount * 0.065f * (1f - abs(lumOut - 0.5f))

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
