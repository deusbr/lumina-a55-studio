package dev.deusbr.luminaa55.context

import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LightDirection
import dev.deusbr.luminaa55.model.PhotometricStats
import dev.deusbr.luminaa55.model.PhotographyCrewSheet
import dev.deusbr.luminaa55.model.ProcessingEngine
import dev.deusbr.luminaa55.model.SceneConcept

/**
 * Mirrors the 6-specialist Fotografia channel workflow (ai-photo-studio v4.0):
 * 1. Diretor de Ensaio (Essence & scene routing)
 * 2. Analista Forense (Optical veil/glare, Kelvin, dynamic range)
 * 3. Câmera & Lentes (Hasselblad/Leica focal length & film emulsion rules)
 * 4. Especialista em Iluminação (Motivated softbox/Rembrandt, Negative Fill, Kelvin separation)
 * 5. Guardião de Consistência (Strict identity, glasses, expression, garment micro-details lock)
 * 6. Auditor Anti-IA & Realismo (Zero plastic skin, real pores/catchlights/strands, anchored shadows)
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
            hasPerson && isBacklit -> "Diretor: Retrato em contraluz — resgate facial mantendo o clima original da cena."
            hasPerson && isNight -> "Diretor: Retrato noturno — preservar sombras densas e luz prática crível sem lavar pretos."
            hasPerson -> "Diretor: Retrato editorial preservando identidade, ambiente real e microtextura do vestuário."
            hasSky -> "Diretor: Paisagem — recuperação limpa de céu e profundidade com nitidez de borda a borda."
            hasFood -> "Diretor: Gastronomia — micro-contraste de textura e cor apetitosa fiel à luz local."
            isNight -> "Diretor: Cena noturna urbana — halation controlada nas fontes de luz e pretos ancorados."
            else -> "Diretor: Cena geral — equilíbrio cromático e contraste editorial orgânico."
        }

        val hazeDesc = when {
            stats.shadowFloorLuma > 0.045f -> "Véu ótico / haze detectado (piso ${"%.2f".format(stats.shadowFloorLuma)}) — remoção aplicada."
            else -> "Ponto preto limpo (piso ${"%.2f".format(stats.shadowFloorLuma)}) — latitude dinâmica preservada."
        }
        val castDesc = when {
            stats.warmthBias > 0.06f -> "Dominante quente (${stats.lightDirection.labelPt})"
            stats.warmthBias < -0.05f -> "Dominante fria/fluorescente (${stats.lightDirection.labelPt})"
            else -> "Balanço neutro (${stats.lightDirection.labelPt})"
        }
        val forensicDiagnosis = "Forense: $castDesc · $hazeDesc"

        val cameraLensSpec = customCameraOverride ?: when {
            hasPerson && concept.recommendedStock == FilmStock.HASSELBLAD_NATURAL ->
                "Hasselblad X2D 100C · 85mm f/1.4 · Natural Color Solution (zero distorção nasal)"
            hasPerson && concept.recommendedStock == FilmStock.CINESTILL_800T ->
                "Leica M11 · 50mm f/1.2 · CineStill 800T (tungstênio + halation sutil)"
            hasPerson && concept.recommendedStock == FilmStock.LEICA_MONOCHROME ->
                "Leica M11 Monochrom · 50mm f/1.4 · Kodak Tri-X 400"
            hasPerson && concept.subjectRatio >= 0.45f ->
                "Hasselblad X2D · 85mm f/1.4 · ${concept.recommendedStock.displayName} (bokeh cremoso)"
            hasPerson ->
                "Leica M11 · 50mm f/1.2 · ${concept.recommendedStock.displayName} (recorte ambiental natural)"
            hasSky ->
                "Sony A7R V · 35mm f/4.0 · ${concept.recommendedStock.displayName} (plano infinito nítido)"
            hasFood ->
                "Hasselblad 90mm Macro · f/2.8 · HNCS (textura tátil)"
            else ->
                "Leica M11 · 35mm f/1.8 · ${concept.recommendedStock.displayName}"
        }

        val keySide = if (stats.rightLuma > stats.leftLuma + 0.03f) "45° direita" else "45° esquerda"
        val lightingPlan = customLightingOverride ?: when {
            hasPerson && isBacklit ->
                "Iluminação: Softbox frontal difuso 5200K (3:1) + Negative Fill + Recorte traseiro natural."
            hasPerson && isNight ->
                "Iluminação: Chave quente 3200K motivada + Chiaroscuro (6:1) + Separação Kelvin 6200K no fundo."
            hasPerson ->
                "Iluminação: Softbox difuso $keySide (5200K, razão 3.5:1) + Negative Fill oposto + Catchlight ocular."
            hasSky ->
                "Iluminação: Curva solar com roll-off ACES nas altas luzes e sombras abertas sem halo."
            else ->
                "Iluminação: Direcional suave com volume 3D e queda natural de luz (falloff físico)."
        }

        val consistencyGuardLock = if (hasPerson) {
            "Guardião: Identidade biométrica, óculos, expressão natural e bordados/golas da roupa travados em 100%."
        } else {
            "Guardião: Geometria, letreiros e cores originais dos objetos preservados sem invenção."
        }

        val antiAiAuditStatus = if (engine == ProcessingEngine.OFFLINE_LOCAL) {
            val matteTag = if (hasNeuralAlpha) "MODNet 512 + Guided Filter" else "Profundidade MiDaS"
            "Auditor Anti-IA: Zero blur plástico na pele (poros 100% reais) · $matteTag · Preto ancorado."
        } else {
            "Auditor Anti-IA (Gemini): Trava antiporcelana, microporos reais, fios soltos orgânicos e catchlights físicos."
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

    fun buildGeminiGenerativePrompt(sheet: PhotographyCrewSheet, concept: SceneConcept): String {
        return """
STRICT PRESERVATION RULE (MANDATORY): Keep the EXACT person's facial identity, bone structure, glasses frame, facial hair, expression, skin tone/melanin, body pose, and exact wardrobe micro-details (every collar stitch, flap, pocket, badge, text like RF / ATPV 11 CAL, and color) unchanged. Do NOT replace the person, do NOT beautify or smooth skin, do NOT hallucinate accessories.

You are the 6-specialist Studio Photography Crew:
1. Director decision: ${sheet.directorDecision}
2. Forensic fix: ${sheet.forensicDiagnosis}. Remove any dirty-lens veiling glare, milky washed-out blacks, or greenish fluorescent ceiling cast cleanly.
3. Camera & Optics: ${sheet.cameraLensSpec}. Emulate natural optical depth-of-field separation (smooth authentic bokeh without glowing white cutout halos around hair curls or shoulders). Keep the real physical environment cohesive while cleanly separating foreground depth.
4. Lighting & Kelvin Physics: ${sheet.lightingPlan}. Sculpt natural 3D facial cheek/jawline volume with a motivated diffused softbox key light, genuine black Negative Fill on the opposite shadow side, crisp rectangular studio catchlights inside the iris, and warm-to-cool Kelvin separation between subject and shadowed background.
5. Consistency Guardian: ${sheet.consistencyGuardLock}.
6. Anti-AI Realism Audit: Maintain 100% visible human pores, natural hair flyaways, true fabric weave texture, authentic shadow falloff, and subtle fine analog film grain (${concept.recommendedStock.displayName}). FORBIDDEN aesthetics: plastic/waxy skin, smooth airbrushed face, HDR aura halos, artificial teeth, or CGI rendering.
        """.trimIndent()
    }

    fun buildGeminiVisionDirectorPrompt(concept: SceneConcept, stats: PhotometricStats): String {
        return """
You are the 6-specialist Fotografia Channel Crew (Diretor de Ensaio, Analista Forense, Câmera & Lentes, Especialista em Iluminação, Guardião de Consistência, Auditor Anti-IA) auditing this exact photo (${concept.recommendedStock.displayName}, meanLuma=${"%.2f".format(stats.meanLuma)}, shadowFloor=${"%.2f".format(stats.shadowFloorLuma)}, light=${stats.lightDirection.labelPt}).

Analyze the real photo you see carefully (lens glare/haze, fluorescent green/warm cast, subject framing, skin, clothing, lighting side) and output ONLY valid JSON with these exact keys to drive our on-device GPU optical & 3D normal-map studio engine:
{
  "conceptTitle": "short Portuguese title (max 38 chars)",
  "directorDecision": "Portuguese summary of creative direction matching photo essence",
  "cameraLensSpec": "exact camera + focal length + film recommendation (e.g. Hasselblad X2D · 85mm f/1.4 · Portra 400)",
  "lightingPlan": "Portuguese motivated lighting + Negative Fill + Kelvin plan",
  "consistencyGuardLock": "Portuguese preservation lock note",
  "antiAiAuditStatus": "Portuguese Anti-AI audit verdict",
  "exposureOffset": number in [-0.25, 0.35],
  "contrast": number in [1.02, 1.28],
  "dehazeAmount": number in [0.01, 0.11],
  "warmth": number in [-0.08, 0.09],
  "tint": number in [-0.05, 0.05],
  "saturation": number in [0.92, 1.16],
  "faceRelightGain": number in [0.0, 0.34],
  "relight3dStrength": number in [0.0, 1.05],
  "backgroundBokeh": number in [0.0, 1.0],
  "backgroundDimming": number in [0.0, 0.45],
  "studioBackdropStrength": number in [0.0, 0.65],
  "clarity": number in [0.08, 0.32],
  "keyDirectionSign": -1.0 or 1.0
}
        """.trimIndent()
    }
}
