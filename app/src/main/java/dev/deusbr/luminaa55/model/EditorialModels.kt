package dev.deusbr.luminaa55.model

import android.graphics.Bitmap

enum class ProcessingEngine(
    val label: String,
    val shortLabel: String,
    val subtitle: String
) {
    OFFLINE_LOCAL("Offline · GPU + MODNet", "Offline", "100% local · Zero nuvem"),
    ONLINE_GEMINI("Online · Gemini Estúdio", "Gemini", "Banca do canal + API Gemini")
}

enum class GeminiOnlineMode(
    val modelId: String,
    val label: String,
    val description: String,
    val outputsImageDirectly: Boolean
) {
    STUDIO_IMAGE_FLASH(
        modelId = "gemini-3.1-flash-image-preview",
        label = "Gemini Flash Image (Nuvem)",
        description = "Gera revelação completa em estúdio na nuvem (requer cota/faturamento na chave Gemini)",
        outputsImageDirectly = true
    ),
    STUDIO_IMAGE_PRO(
        modelId = "gemini-3-pro-image-preview",
        label = "Gemini Pro Image (Estúdio Max)",
        description = "Máxima fidelidade fotográfica na nuvem (requer cota/faturamento na chave Gemini)",
        outputsImageDirectly = true
    ),
    VISION_CREW_DIRECTOR(
        modelId = "gemini-3.8-flash",
        label = "Gemini Diretor + GPU Local",
        description = "Banca de 6 especialistas analisa a foto na nuvem e calibra a GPU local (plano gratuito)",
        outputsImageDirectly = false
    )
}

data class PhotographyCrewSheet(
    val directorDecision: String,
    val forensicDiagnosis: String,
    val cameraLensSpec: String,
    val lightingPlan: String,
    val consistencyGuardLock: String,
    val antiAiAuditStatus: String,
    val engineUsedLabel: String
)

enum class CameraExperienceMode(val label: String) {
    NORMAL("Normal"),
    PRO("Pro")
}

enum class ProDeckTab(val label: String) {
    EXPOSURE("Exposição"),
    FILM("Película"),
    ENGINE("Motor")
}

enum class FilmStock(
    val displayName: String,
    val subtitle: String,
    val badge: String
) {
    AUTO_EDITORIAL("Auto Ensaio", "Motor contextual decide o look", "AUTO"),
    PORTRA_400("Portra 400", "Pele cremosa e contraste editorial", "P400"),
    CINESTILL_800T("CineStill 800T", "Noturno urbano com halation quente", "800T"),
    HASSELBLAD_NATURAL("Hasselblad HNCS", "Cores puras de estúdio médio formato", "HNCS"),
    KODAK_GOLD_200("Gold 200", "Dourado solar e sombras acolhedoras", "G200"),
    LEICA_MONOCHROME("Leica Mono", "Preto e branco dramático de prata", "MONO")
}

enum class LensOption(
    val label: String,
    val shortZoom: String,
    val sensorSpec: String
) {
    ULTRA_WIDE("Ultra 0.5x", "0.5x", "12MP IMX258 · f/2.2"),
    MAIN_IMX906("Principal 1x", "1x", "50MP IMX906 · OIS f/1.8"),
    TELE_2X("Retrato 2x", "2x", "Crop Sensor IMX906 · f/1.8"),
    MACRO("Macro", "M", "5MP Dedicada · f/2.4"),
    FRONT_32MP("Frontal 32MP", "32M", "32MP IMX616 · f/2.2")
}

enum class CaptureMode(
    val label: String,
    val description: String
) {
    EDITORIAL_BURST("Estúdio Neural", "ISP + Rede Neural Zero-DCE + Profundidade 3D"),
    SAMSUNG_HDR("Samsung HDR", "Extensão nativa CameraX HDR do A55"),
    SAMSUNG_NIGHT("Samsung Night", "Extensão nativa noturna do ISP Exynos"),
    SAMSUNG_BOKEH("Samsung Bokeh", "Extensão nativa de retrato")
}

enum class LightDirection(val labelPt: String) {
    BALANCED("Luz Equilibrada"),
    BACKLIT("Contraluz Dramática"),
    LEFT_KEY("Luz Lateral Esquerda"),
    RIGHT_KEY("Luz Lateral Direita"),
    LOW_KEY("Baixa Luminosidade / Noturna"),
    HIGH_KEY("Alta Luz / Ensolarada")
}

data class CameraLensInfo(
    val cameraId: String,
    val facing: String,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val hardwareLevel: String,
    val hasOis: Boolean,
    val supportsRaw: Boolean,
    val maxDigitalZoom: Float,
    val mappedRole: LensOption?
)

data class CameraHardwareProfile(
    val deviceModel: String,
    val socHardware: String,
    val lenses: List<CameraLensInfo>,
    val extensionsSupported: List<String>,
    val rawBlockedByFirmware: Boolean,
    val supportsHardwareRuntimeShader: Boolean
)

data class PhotometricStats(
    val meanLuma: Float,
    val shadowRatio: Float,
    val highlightClipRatio: Float,
    val dynamicRangeSpread: Float,
    val warmthBias: Float,
    val saturationMean: Float,
    val leftLuma: Float,
    val rightLuma: Float,
    val topLuma: Float,
    val centerLuma: Float,
    val lightDirection: LightDirection,
    val shadowFloorLuma: Float = 0f
)

data class StudioLightingHint(
    val hasFace: Boolean = false,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.45f,
    val faceRadius: Float = 0.18f,
    val leftEyeX: Float = -1f,
    val leftEyeY: Float = -1f,
    val rightEyeX: Float = -1f,
    val rightEyeY: Float = -1f
)

data class EditRecipe(
    val filmStock: FilmStock,
    val exposureOffset: Float,
    val contrast: Float,
    val shadowLift: Float,
    val highlightRollOff: Float,
    val clarity: Float,
    val warmth: Float,
    val tint: Float,
    val saturation: Float,
    val skinProtection: Float,
    val faceRelightGain: Float,
    val backgroundDimming: Float,
    val backgroundBokeh: Float,
    val skyRecovery: Float,
    val halationStrength: Float,
    val proMistStrength: Float,
    val grainAmount: Float,
    val vignetteStrength: Float,
    val monochrome: Boolean,
    val dehazeAmount: Float = 0.02f,
    val relight3dStrength: Float = 0.0f,
    val studioBackdropStrength: Float = 0.0f,
    val keyDirectionSign: Float = -1.0f
)

data class SceneConcept(
    val conceptTitle: String,
    val conceptSummary: String,
    val detectedTags: List<String>,
    val faceCount: Int,
    val subjectRatio: Float,
    val hasConfidentMask: Boolean = true,
    val hasDepthMap: Boolean = false,
    val neuralBoostStrength: Float = 0.45f,
    val lighting: LightDirection,
    val recommendedStock: FilmStock,
    val recommendedBurstCount: Int = 3,
    val appliedAdjustments: List<String>,
    val recipe: EditRecipe,
    val crewSheet: PhotographyCrewSheet? = null
)

data class AutoLiveDecision(
    val headline: String,
    val subtitle: String,
    val recommendedStock: FilmStock,
    val recommendedBurst: Int
)

data class CapturedPhotoSession(
    val id: Long = System.nanoTime(),
    val originalBitmap: Bitmap,
    val previewOriginalBitmap: Bitmap,
    val subjectMaskBitmap: Bitmap?,
    val depthMapBitmap: Bitmap? = null,
    val neuralGainBitmap: Bitmap? = null,
    val lightingHint: StudioLightingHint = StudioLightingHint(),
    val processedBitmap: Bitmap,
    val previewProcessedBitmap: Bitmap,
    val exifBytes: ByteArray? = null,
    val concept: SceneConcept,
    val aiModelsUsed: List<String> = emptyList(),
    val burstFramesMerged: Int,
    val gpuAccelerated: Boolean,
    val processingTimeMs: Long,
    val lensUsed: LensOption,
    val capturedFromProMode: Boolean = false,
    val savedUriString: String? = null,
    val fullResMatchesCurrentRecipe: Boolean = true,
    val cachedStats: PhotometricStats? = null,
    val cachedLabels: List<String> = emptyList(),
    val cachedFaceCount: Int = 0,
    val hasConfidentMask: Boolean = true,
    val engineUsed: ProcessingEngine = ProcessingEngine.OFFLINE_LOCAL,
    val onlineNotes: String? = null
)
