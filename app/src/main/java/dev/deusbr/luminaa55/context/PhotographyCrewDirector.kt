package dev.deusbr.luminaa55.context

import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
import dev.deusbr.luminaa55.model.OnlineDirectionStyle
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.PhotographyCrewSheet
import dev.deusbr.luminaa55.model.ProcessingEngine
import dev.deusbr.luminaa55.model.SceneConcept

/**
 * Orchestrates the 6-specialist Fotografia Channel Crew + 5-Block Prompt & Anti-AI Vision Gate:
 * 1. Diretor de Ensaio
 * 2. Analista Forense
 * 3. Câmera & Lentes
 * 4. Especialista em Iluminação
 * 5. Guardião de Consistência
 * 6. Auditor Anti-IA & Realismo
 */
object PhotographyCrewDirector {

    fun compileCrewSheet(
        concept: SceneConcept,
        stats: PhotometricStats,
        labels: List<String>,
        faceCount: Int,
        engine: ProcessingEngine,
        hasNeuralAlpha: Boolean,
        customCameraOverride: String? = null,
        customLightingOverride: String? = null
    ): PhotographyCrewSheet {
        val normLabels = labels.map { it.lowercase() }
        val hasPerson = faceCount > 0 || normLabels.any {
            it.contains("person") || it.contains("selfie") || it.contains("portrait") || it.contains("smile")
        }
        val hasSky = normLabels.any { it.contains("sky") || it.contains("mountain") || it.contains("beach") || it.contains("cloud") }
        val hasFood = normLabels.any { it.contains("food") || it.contains("dish") || it.contains("coffee") || it.contains("drink") }
        val isNight = stats.lightDirection == LightDirection.LOW_KEY
        val isBacklit = stats.lightDirection == LightDirection.BACKLIT

        val directorDecision = when {
            hasPerson && isBacklit -> "Diretor: Retrato em contraluz — resgate facial limpo em OKLab L sem halos de recorte."
            hasPerson && isNight -> "Diretor: Retrato cinema noturno — chiaroscuro orgânico com pretos ancorados."
            hasPerson -> "Diretor: Retrato editorial preservando identidade, ambiente real e microtextura do vestuário."
            hasSky -> "Diretor: Paisagem — recuperação de céu e nitidez de borda a borda."
            hasFood -> "Diretor: Gastronomia — micro-contraste tátil e balanço cromático neutro."
            isNight -> "Diretor: Cena noturna — halation pontual controlada e sombras limpas."
            else -> "Diretor: Cena geral — curva editorial Capture One em luminância pura."
        }

        val hazeDesc = when {
            stats.shadowFloorLuma > 0.04f -> "Véu/neblina detectado (piso ${"%.2f".format(stats.shadowFloorLuma)}) — dehaze OKLab L."
            else -> "Ponto preto limpo (piso ${"%.2f".format(stats.shadowFloorLuma)}) — latitude preservada."
        }
        val castDesc = when {
            stats.greenCastBias > 0.012f -> "Cast verde fluorescente compensado (-magenta)"
            stats.warmthBias > 0.06f -> "Luz quente (${stats.lightDirection.labelPt})"
            stats.warmthBias < -0.05f -> "Luz fria (${stats.lightDirection.labelPt})"
            else -> "Balanço neutro (${stats.lightDirection.labelPt})"
        }
        val forensicDiagnosis = "Forense: $castDesc · $hazeDesc"

        val cameraLensSpec = customCameraOverride ?: when {
            hasPerson && concept.recommendedStock == FilmStock.RETRATO_EDITORIAL ->
                "Hasselblad X2D 100C · 85mm f/1.4 · Curva Capture One + Pro-Mist 1/8"
            hasPerson && concept.recommendedStock == FilmStock.LUZ_NATURAL ->
                "Leica M11 · 50mm f/1.4 · Luz Natural 5200K (sombras abertas 2:1)"
            hasPerson && concept.recommendedStock == FilmStock.GOLDEN_FILM ->
                "Medium Format · 85mm f/1.8 · Golden Film Portra 400 + Pro-Mist 1/8"
            hasPerson && concept.recommendedStock == FilmStock.CINEMA_CHIAROSCURO ->
                "ARRI Alexa 35 · 50mm T1.4 · Chiaroscuro 3400K/6500K"
            hasPerson && concept.recommendedStock == FilmStock.PRATA_MONOCHROME ->
                "Leica M11 Monochrom · 50mm f/1.4 · Gelatina Prata Melanin-Safe"
            hasPerson && concept.subjectRatio >= 0.45f ->
                "Hasselblad X2D · 85mm f/1.4 · ${concept.recommendedStock.displayName}"
            hasPerson ->
                "Leica M11 · 50mm f/1.2 · ${concept.recommendedStock.displayName}"
            hasSky ->
                "Sony A7R V · 35mm f/4.0 · ${concept.recommendedStock.displayName} (plano infinito nítido)"
            hasFood ->
                "Hasselblad 90mm Macro · f/2.8 · Textura tátil"
            else ->
                "Leica M11 · 35mm f/1.8 · ${concept.recommendedStock.displayName}"
        }

        val keySide = if (stats.rightLuma > stats.leftLuma + 0.03f) "45° direita" else "45° esquerda"
        val lightingPlan = customLightingOverride ?: when {
            hasPerson && isBacklit ->
                "Iluminação: Fill frontal difuso 5200K + roll-off suave de fundo + catchlight ocular."
            hasPerson && isNight ->
                "Iluminação: Chave quente 3400K motivada + Chiaroscuro + separação fria 6500K."
            hasPerson ->
                "Iluminação: Softbox difuso $keySide (5400K) + Hue-Lock de pele (15°–38°) + vinheta ótica."
            hasSky ->
                "Iluminação: Curva solar com roll-off suave nas altas luzes e sombras abertas."
            else ->
                "Iluminação: Direcional suave em OKLab L com queda natural de luz."
        }

        val consistencyGuardLock = if (hasPerson) {
            "Guardião: Identidade, óculos, expressão, pele real e detalhes da roupa travados 100%."
        } else {
            "Guardião: Geometria, letreiros e cores originais preservados sem invenção."
        }

        val antiAiAuditStatus = if (engine == ProcessingEngine.OFFLINE_LOCAL) {
            val depthTag = if (hasNeuralAlpha) "Separação neural 1–3px + Hue-Lock" else "Curva OKLab pura"
            "Auditor Anti-IA: Pele com poros 100% reais (zero blur plástico / zero halo) · $depthTag."
        } else {
            "Auditor Anti-IA (Gemini Vision): Portão de realismo ativo (meta >= 8/10 em pele, bordas e identidade)."
        }

        return PhotographyCrewSheet(
            directorDecision = directorDecision,
            forensicDiagnosis = forensicDiagnosis,
            cameraLensSpec = cameraLensSpec,
            lightingPlan = lightingPlan,
            consistencyGuardLock = consistencyGuardLock,
            antiAiAuditStatus = antiAiAuditStatus,
            engineUsedLabel = engine.label
        )
    }

    /**
     * Canonical 5-Block Generative Prompt aligned with Direção de Ensaio Fotográfico + Auditor Anti-IA.
     */
    fun buildGeminiCanonical5BlockPrompt(
        sheet: PhotographyCrewSheet,
        concept: SceneConcept,
        style: OnlineDirectionStyle
    ): String {
        val styleSceneBlock = when (style) {
            OnlineDirectionStyle.SUBTLE_RETOUCH ->
                "Keep the EXACT original physical location, office/workshop background, natural posture, and wardrobe untouched. Only remove dirty-lens veiling glare, greenish fluorescent cast, and harsh flat lighting while keeping authentic environmental depth."
            OnlineDirectionStyle.STUDIO_EDITORIAL ->
                "Elevate the lighting to a clean commercial portrait studio setup (large feathered octabox 45 degrees + subtle rim separation + cohesive depth-of-field bokeh), while keeping the person's exact pose, glasses, facial expression, and exact workwear/uniform micro-details completely faithful."
            OnlineDirectionStyle.CINEMA_TRAILER ->
                "Apply dramatic 5:1 cinema chiaroscuro grading with motivated warm 3400K key light on the subject, cool 6500K atmospheric shadow separation in the background, and delicate Black Pro-Mist 1/8 highlight halation, strictly preserving pose, wardrobe lettering, and facial structure."
        }

        return """
[1. IDENTITY & STRICT BIOMETRIC LOCK]
Preserve 100% of the subject's exact facial bone structure, nose, lips, eyes, eyewear/glasses frame, facial hair, natural melanin skin tone (NO lightening, NO orange/gray muddiness), hairline, and every garment detail (collar, pockets, patches, stitching, text like RF / ATPV 11 CAL). Zero face replacement or beautification.

[2. SCENE, POSE & WARDROBE FIDELITY]
${sheet.directorDecision}
$styleSceneBlock

[3. CAMERA, LENS & OPTICAL PHYSICS]
${sheet.cameraLensSpec}. Genuine shallow depth-of-field optical roll-off (f/1.4–f/1.8) with organic edge transition around curly hair and shoulders — ZERO glowing cutout halos, ZERO muddy dark cutout borders. Crisp focus locked squarely on iris and eyelashes.

[4. LIGHTING, KELVIN & ATMOSPHERE]
${sheet.forensicDiagnosis}. ${sheet.lightingPlan}. Sculpt subtle 3D cheek and jawline dimension with Negative Fill on the shadow side and natural physical specular catchlights in the eyes. Anchor black point cleanly without crushing shadow detail.

[5. ANTI-AI AUDITOR & REALISM MANDATE]
Maintain authentic human pores, natural skin micro-texture, individual hair flyaways, true fabric weave, and delicate 35mm grain (${concept.recommendedStock.displayName}).
FORBIDDEN: waxy/porcelain smoothed skin, plastic AI sheen, glowing aura rims, over-whitened teeth or sclera, painted-on dark cutouts, distorted text on badges, or HDR artifacts.
        """.trimIndent()
    }

    fun buildAntiAiAuditPrompt(): String {
        return """
You are the strict Auditor Anti-IA inspecting an edited studio photograph for realism vs AI artifacts.
Return ONLY valid JSON with these exact keys:
{
  "identityPreserved": integer 1..10,
  "skinRealism": integer 1..10,
  "edgeHaloFree": integer 1..10,
  "lightingCoherence": integer 1..10,
  "overallScore": integer 1..10,
  "verdictPt": "short Portuguese verdict (max 70 chars)",
  "revisionDeltaPrompt": "concise English fix instruction ONLY if overallScore < 8, else empty string"
}
Penalize below 8 if skin looks waxy/plastic, glasses look warped, text on shirt is garbled, or there is a white/dark cutout halo around head or shoulders.
        """.trimIndent()
    }

    fun buildGeminiVisionDirectorPrompt(concept: SceneConcept, stats: PhotometricStats): String {
        return """
You are the 6-specialist Fotografia Channel Crew calibrating our on-device OKLab/Capture One GPU engine (${concept.recommendedStock.displayName}, meanLuma=${"%.2f".format(stats.meanLuma)}, shadowFloor=${"%.2f".format(stats.shadowFloorLuma)}, greenBias=${"%.3f".format(stats.greenCastBias)}, light=${stats.lightDirection.labelPt}).

Analyze this photo carefully (lens veil/haze, fluorescent green cast, highlight headroom, skin tone, focus) and output ONLY valid JSON:
{
  "conceptTitle": "short Portuguese title (max 38 chars)",
  "directorDecision": "Portuguese summary matching the real scene essence",
  "cameraLensSpec": "exact camera + focal length + preset recommendation",
  "lightingPlan": "Portuguese motivated lighting + Negative Fill + Kelvin note",
  "consistencyGuardLock": "Portuguese preservation lock note",
  "antiAiAuditStatus": "Portuguese Anti-AI audit verdict",
  "exposureOffset": number in [-0.20, 0.28],
  "contrast": number in [1.02, 1.22],
  "dehazeAmount": number in [0.01, 0.08],
  "warmth": number in [-0.06, 0.06],
  "tint": number in [-0.05, 0.03],
  "saturation": number in [0.94, 1.12],
  "faceRelightGain": number in [0.0, 0.24],
  "backgroundBokeh": number in [0.0, 0.70],
  "backgroundDimming": number in [0.0, 0.20],
  "clarity": number in [0.08, 0.26],
  "proMistStrength": number in [0.04, 0.14],
  "keyDirectionSign": -1.0 or 1.0
}
        """.trimIndent()
    }
}
