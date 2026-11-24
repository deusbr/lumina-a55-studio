package dev.deusbr.luminaa55.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.deusbr.luminaa55.camera.CameraController
import dev.deusbr.luminaa55.context.PhotometricAnalyzer
import dev.deusbr.luminaa55.context.SceneConceptEngine
import dev.deusbr.luminaa55.model.AutoLiveDecision
import dev.deusbr.luminaa55.model.CameraExperienceMode
import dev.deusbr.luminaa55.model.CameraHardwareProfile
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.CapturedPhotoSession
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LensOption
import dev.deusbr.luminaa55.model.ProDeckTab
import dev.deusbr.luminaa55.shader.EditorialShaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LuminaUiState(
    val experienceMode: CameraExperienceMode = CameraExperienceMode.NORMAL,
    val proDeckTab: ProDeckTab = ProDeckTab.EXPOSURE,
    val activeLens: LensOption = LensOption.MAIN_IMX906,
    val captureMode: CaptureMode = CaptureMode.EDITORIAL_BURST,
    val selectedStock: FilmStock = FilmStock.AUTO_EDITORIAL,
    val sessionLookLocked: Boolean = false,
    val burstCount: Int = 3,
    val evIndex: Int = 0,
    val manualMode: Boolean = false,
    val manualIso: Int = 200,
    val manualShutterMs: Float = 16f,
    val showGrid: Boolean = true,
    val showDiagnostics: Boolean = false,
    val autoDecision: AutoLiveDecision = AutoLiveDecision(
        headline = "Modo Automático Contextual",
        subtitle = "Analisando luz e cena em tempo real…",
        recommendedStock = FilmStock.PORTRA_400,
        recommendedBurst = 3
    ),
    val isCapturing: Boolean = false,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val hardwareProfile: CameraHardwareProfile? = null,
    val activeSession: CapturedPhotoSession? = null,
    val editIntensity: Float = 1.0f
)

class LuminaViewModel(app: Application) : AndroidViewModel(app) {

    val cameraController = CameraController(app)
    private val conceptEngine = SceneConceptEngine()
    private var reprocessJob: Job? = null

    private val _uiState = MutableStateFlow(LuminaUiState())
    val uiState: StateFlow<LuminaUiState> = _uiState.asStateFlow()

    init {
        conceptEngine.warmUp(app)
    }

    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        viewModelScope.launch {
            val s = _uiState.value
            val effectiveMode = if (s.experienceMode == CameraExperienceMode.NORMAL) {
                CaptureMode.EDITORIAL_BURST
            } else {
                s.captureMode
            }
            runCatching {
                cameraController.bindCamera(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    lens = s.activeLens,
                    captureMode = effectiveMode,
                    onLiveFrameForHint = { frame ->
                        viewModelScope.launch {
                            val decision = runCatching { conceptEngine.quickLiveHint(frame) }.getOrNull()
                            frame.recycle()
                            if (decision != null) {
                                _uiState.update { it.copy(autoDecision = decision) }
                            }
                        }
                    }
                )
            }.onSuccess {
                _uiState.update { it.copy(hardwareProfile = cameraController.hardwareProfile, statusMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Erro ao iniciar câmera: ${err.message}") }
            }
        }
    }

    fun selectExperienceMode(mode: CameraExperienceMode, owner: LifecycleOwner, previewView: PreviewView) {
        val prev = _uiState.value
        if (prev.experienceMode == mode) return

        if (mode == CameraExperienceMode.NORMAL) {
            val neededRebind = prev.captureMode != CaptureMode.EDITORIAL_BURST
            cameraController.applyManualIsoShutter(null, null)
            cameraController.setExposureIndex(0)
            _uiState.update {
                it.copy(
                    experienceMode = CameraExperienceMode.NORMAL,
                    selectedStock = FilmStock.AUTO_EDITORIAL,
                    captureMode = CaptureMode.EDITORIAL_BURST,
                    manualMode = false,
                    evIndex = 0,
                    sessionLookLocked = false
                )
            }
            if (neededRebind) bindCamera(owner, previewView)
        } else {
            _uiState.update { it.copy(experienceMode = CameraExperienceMode.PRO) }
        }
    }

    fun enterProModeFromReview() {
        _uiState.update { it.copy(experienceMode = CameraExperienceMode.PRO) }
    }

    fun selectProTab(tab: ProDeckTab) {
        _uiState.update { it.copy(proDeckTab = tab) }
    }

    fun toggleSessionLock() {
        _uiState.update { cur ->
            val nextLock = !cur.sessionLookLocked
            val stock = if (nextLock && cur.selectedStock == FilmStock.AUTO_EDITORIAL) {
                cur.autoDecision.recommendedStock
            } else {
                cur.selectedStock
            }
            cur.copy(sessionLookLocked = nextLock, selectedStock = stock)
        }
    }

    fun selectLens(lens: LensOption, owner: LifecycleOwner, previewView: PreviewView) {
        _uiState.update { it.copy(activeLens = lens) }
        bindCamera(owner, previewView)
    }

    fun selectCaptureMode(mode: CaptureMode, owner: LifecycleOwner, previewView: PreviewView) {
        if (_uiState.value.experienceMode != CameraExperienceMode.PRO) return
        _uiState.update { it.copy(captureMode = mode) }
        bindCamera(owner, previewView)
    }

    fun selectFilmStock(stock: FilmStock) {
        if (_uiState.value.experienceMode != CameraExperienceMode.PRO) return
        _uiState.update { it.copy(selectedStock = stock) }
        if (_uiState.value.activeSession != null) {
            reprocessActiveSession(stock = stock, intensity = _uiState.value.editIntensity)
        }
    }

    fun setBurstCount(count: Int) {
        if (_uiState.value.experienceMode != CameraExperienceMode.PRO) return
        _uiState.update { it.copy(burstCount = count.coerceIn(1, 5)) }
    }

    fun setEvIndex(ev: Int) {
        if (_uiState.value.experienceMode != CameraExperienceMode.PRO) return
        _uiState.update { it.copy(evIndex = ev) }
        cameraController.setExposureIndex(ev)
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun toggleDiagnostics(show: Boolean) {
        _uiState.update { it.copy(showDiagnostics = show) }
    }

    fun updateManualExposure(enabled: Boolean, iso: Int, shutterMs: Float) {
        if (_uiState.value.experienceMode != CameraExperienceMode.PRO) return
        _uiState.update { it.copy(manualMode = enabled, manualIso = iso, manualShutterMs = shutterMs) }
        if (enabled) cameraController.applyManualIsoShutter(iso, shutterMs)
        else cameraController.applyManualIsoShutter(null, null)
    }

    fun captureEditorialPhoto() {
        val state = _uiState.value
        if (state.isCapturing) return
        val isPro = state.experienceMode == CameraExperienceMode.PRO

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCapturing = true,
                    statusMessage = if (isPro) "Capturando em resolução máxima…" else "Analisando cena e otimizando foto…"
                )
            }
            val startMs = System.currentTimeMillis()
            runCatching {
                val rawCapture = cameraController.captureFullRes()
                val effectiveStock = if (!isPro) {
                    FilmStock.AUTO_EDITORIAL
                } else if (state.sessionLookLocked && state.selectedStock != FilmStock.AUTO_EDITORIAL) {
                    state.selectedStock
                } else {
                    state.selectedStock
                }

                val analysis = conceptEngine.analyze(rawCapture.fullResBitmap, effectiveStock)
                val denoiseLevel = if (!isPro) {
                    analysis.concept.recommendedBurstCount
                } else if (state.captureMode == CaptureMode.EDITORIAL_BURST) {
                    state.burstCount
                } else {
                    1
                }

                val (denoisedFullRes, framesUsed) = cameraController.applyFullResDenoiseIfRequested(
                    rawCapture.fullResBitmap,
                    denoiseLevel
                )
                if (denoisedFullRes !== rawCapture.fullResBitmap) {
                    rawCapture.fullResBitmap.recycle()
                }

                val renderedFullRes = withContext(Dispatchers.Default) {
                    EditorialShaderEngine.renderEditorial(
                        source = denoisedFullRes,
                        subjectMask = analysis.subjectMask,
                        recipe = analysis.concept.recipe,
                        intensity = 1.0f,
                        hasConfidentMask = analysis.hasConfidentMask
                    )
                }

                val uiOrigPreview = withContext(Dispatchers.Default) {
                    cameraController.createUiPreviewBitmap(denoisedFullRes, 1800)
                }
                val uiProcPreview = withContext(Dispatchers.Default) {
                    cameraController.createUiPreviewBitmap(renderedFullRes.bitmap, 1800)
                }

                CapturedPhotoSession(
                    originalBitmap = denoisedFullRes,
                    previewOriginalBitmap = uiOrigPreview,
                    subjectMaskBitmap = analysis.subjectMask,
                    processedBitmap = renderedFullRes.bitmap,
                    previewProcessedBitmap = uiProcPreview,
                    exifBytes = rawCapture.exifBytes,
                    concept = analysis.concept,
                    burstFramesMerged = framesUsed,
                    gpuAccelerated = renderedFullRes.gpuAccelerated,
                    processingTimeMs = System.currentTimeMillis() - startMs,
                    lensUsed = state.activeLens,
                    capturedFromProMode = isPro,
                    fullResMatchesCurrentRecipe = true,
                    cachedStats = analysis.stats,
                    cachedLabels = analysis.labels,
                    cachedFaceCount = analysis.faceCount,
                    hasConfidentMask = analysis.hasConfidentMask
                )
            }.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        activeSession = session,
                        editIntensity = 1.0f,
                        statusMessage = null
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isCapturing = false, statusMessage = "Falha na captura: ${err.message}") }
            }
        }
    }

    fun importFromGalleryUri(uri: Uri) {
        val state = _uiState.value
        val isPro = state.experienceMode == CameraExperienceMode.PRO
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, statusMessage = "Abrindo em resolução original…") }
            val startMs = System.currentTimeMillis()
            runCatching {
                val (fullBmp, exifBytes) = withContext(Dispatchers.IO) {
                    cameraController.decodeGalleryUriFullRes(uri)
                }
                val stock = if (isPro) state.selectedStock else FilmStock.AUTO_EDITORIAL
                val analysis = conceptEngine.analyze(fullBmp, stock)
                val renderedFullRes = withContext(Dispatchers.Default) {
                    EditorialShaderEngine.renderEditorial(
                        source = fullBmp,
                        subjectMask = analysis.subjectMask,
                        recipe = analysis.concept.recipe,
                        intensity = 1.0f,
                        hasConfidentMask = analysis.hasConfidentMask
                    )
                }
                val uiOrigPreview = withContext(Dispatchers.Default) {
                    cameraController.createUiPreviewBitmap(fullBmp, 1800)
                }
                val uiProcPreview = withContext(Dispatchers.Default) {
                    cameraController.createUiPreviewBitmap(renderedFullRes.bitmap, 1800)
                }

                CapturedPhotoSession(
                    originalBitmap = fullBmp,
                    previewOriginalBitmap = uiOrigPreview,
                    subjectMaskBitmap = analysis.subjectMask,
                    processedBitmap = renderedFullRes.bitmap,
                    previewProcessedBitmap = uiProcPreview,
                    exifBytes = exifBytes,
                    concept = analysis.concept,
                    burstFramesMerged = 1,
                    gpuAccelerated = renderedFullRes.gpuAccelerated,
                    processingTimeMs = System.currentTimeMillis() - startMs,
                    lensUsed = state.activeLens,
                    capturedFromProMode = isPro,
                    fullResMatchesCurrentRecipe = true,
                    cachedStats = analysis.stats,
                    cachedLabels = analysis.labels,
                    cachedFaceCount = analysis.faceCount,
                    hasConfidentMask = analysis.hasConfidentMask
                )
            }.onSuccess { session ->
                _uiState.update { it.copy(isCapturing = false, activeSession = session, editIntensity = 1.0f, statusMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(isCapturing = false, statusMessage = "Erro ao abrir foto: ${err.message}") }
            }
        }
    }

    fun reprocessActiveSession(
        stock: FilmStock = _uiState.value.selectedStock,
        intensity: Float = _uiState.value.editIntensity,
        customProMist: Float? = null,
        customBokeh: Float? = null,
        customGrain: Float? = null
    ) {
        val session = _uiState.value.activeSession ?: return
        _uiState.update { it.copy(selectedStock = stock, editIntensity = intensity) }

        reprocessJob?.cancel()
        reprocessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val stats = session.cachedStats
            val mask = session.subjectMaskBitmap ?: return@launch
            val updatedConcept = if (stats != null) {
                PhotometricAnalyzer.buildConceptProfile(
                    stats = stats,
                    labels = session.cachedLabels,
                    faceCount = session.cachedFaceCount,
                    subjectRatio = session.concept.subjectRatio,
                    userSelectedStock = stock,
                    hasConfidentMask = session.hasConfidentMask
                )
            } else {
                conceptEngine.analyze(session.previewOriginalBitmap, stock).concept
            }

            val baseRecipe = updatedConcept.recipe
            val tunedRecipe = baseRecipe.copy(
                proMistStrength = customProMist ?: baseRecipe.proMistStrength,
                backgroundBokeh = customBokeh ?: baseRecipe.backgroundBokeh,
                grainAmount = customGrain ?: baseRecipe.grainAmount
            )
            val allowMask = session.hasConfidentMask || (customBokeh != null && customBokeh > 0.01f && mask.width > 16)

            // Instant live preview on display-resolution proxy
            val renderedPreview = withContext(Dispatchers.Default) {
                EditorialShaderEngine.renderEditorial(
                    source = session.previewOriginalBitmap,
                    subjectMask = mask,
                    recipe = tunedRecipe,
                    intensity = intensity,
                    hasConfidentMask = allowMask
                )
            }
            _uiState.update { cur ->
                val curSession = cur.activeSession ?: return@update cur
                cur.copy(
                    activeSession = curSession.copy(
                        previewProcessedBitmap = renderedPreview.bitmap,
                        concept = updatedConcept.copy(recipe = tunedRecipe),
                        gpuAccelerated = renderedPreview.gpuAccelerated,
                        processingTimeMs = System.currentTimeMillis() - startMs,
                        fullResMatchesCurrentRecipe = false,
                        savedUriString = null
                    )
                )
            }
        }
    }

    fun saveActiveSession() {
        val session = _uiState.value.activeSession ?: return
        if (_uiState.value.isSaving) return
        val intensity = _uiState.value.editIntensity

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, statusMessage = "Exportando Full-Res para galeria…") }
            runCatching {
                val exportBmp: Bitmap = if (session.fullResMatchesCurrentRecipe) {
                    session.processedBitmap
                } else {
                    val mask = session.subjectMaskBitmap
                    if (mask != null) {
                        val allowMask = session.hasConfidentMask || (session.concept.recipe.backgroundBokeh > 0.01f && mask.width > 16)
                        withContext(Dispatchers.Default) {
                            EditorialShaderEngine.renderEditorial(
                                source = session.originalBitmap,
                                subjectMask = mask,
                                recipe = session.concept.recipe,
                                intensity = intensity,
                                hasConfidentMask = allowMask
                            ).bitmap
                        }
                    } else {
                        session.processedBitmap
                    }
                }
                val uri = cameraController.saveBitmapToGallery(exportBmp, session.exifBytes, session.concept.conceptTitle)
                exportBmp to uri
            }.onSuccess { (finalFullBmp, uri) ->
                _uiState.update { cur ->
                    val s = cur.activeSession ?: return@update cur
                    cur.copy(
                        isSaving = false,
                        activeSession = s.copy(
                            processedBitmap = finalFullBmp,
                            fullResMatchesCurrentRecipe = true,
                            savedUriString = uri.toString()
                        ),
                        statusMessage = "Salva em Pictures/LuminaA55 (${finalFullBmp.width}×${finalFullBmp.height})"
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isSaving = false, statusMessage = "Erro ao salvar: ${err.message}") }
            }
        }
    }

    fun closeReviewStudio() {
        reprocessJob?.cancel()
        _uiState.update { it.copy(activeSession = null, statusMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
        conceptEngine.close()
    }
}
