package dev.deusbr.luminaa55.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.deusbr.luminaa55.camera.CameraController
import dev.deusbr.luminaa55.camera.RawCaptureOutput
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
        headline = "Modo Automático · IA Local",
        subtitle = "Analisando cena e profundidade…",
        recommendedStock = FilmStock.PORTRA_400,
        recommendedBurst = 3
    ),
    val isShutterBusy: Boolean = false,
    val pendingQueueCount: Int = 0,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val hardwareProfile: CameraHardwareProfile? = null,
    val studioSessions: List<CapturedPhotoSession> = emptyList(),
    val activeSession: CapturedPhotoSession? = null,
    val editIntensity: Float = 1.0f
)

private data class QueuedShot(
    val raw: RawCaptureOutput,
    val stock: FilmStock,
    val lens: LensOption,
    val isPro: Boolean,
    val manualBurstCount: Int,
    val captureMode: CaptureMode
)

class LuminaViewModel(app: Application) : AndroidViewModel(app) {

    val cameraController = CameraController(app)
    private val conceptEngine = SceneConceptEngine(app)
    private val processingQueue = Channel<QueuedShot>(Channel.UNLIMITED)
    private var reprocessJob: Job? = null
    private var hintSamplerJob: Job? = null

    private val _uiState = MutableStateFlow(LuminaUiState())
    val uiState: StateFlow<LuminaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            conceptEngine.warmUp()
        }
        startBackgroundStudioWorker()
    }

    private fun startBackgroundStudioWorker() {
        viewModelScope.launch(Dispatchers.Default) {
            for (job in processingQueue) {
                runCatching {
                    processQueuedShot(job)
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(statusMessage = "Erro na fila IA: ${err.message}")
                    }
                }
                _uiState.update {
                    it.copy(pendingQueueCount = (it.pendingQueueCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private suspend fun processQueuedShot(job: QueuedShot) {
        val startMs = System.currentTimeMillis()
        val analysis = conceptEngine.analyze(job.raw.fullResBitmap, job.stock)
        val denoiseLevel = if (!job.isPro) {
            analysis.concept.recommendedBurstCount
        } else if (job.captureMode == CaptureMode.EDITORIAL_BURST) {
            job.manualBurstCount
        } else {
            1
        }

        val (denoisedFullRes, framesUsed) = cameraController.applyFullResDenoiseIfRequested(
            job.raw.fullResBitmap,
            denoiseLevel
        )
        if (denoisedFullRes !== job.raw.fullResBitmap) {
            job.raw.fullResBitmap.recycle()
        }

        val renderedFullRes = EditorialShaderEngine.renderEditorial(
            source = denoisedFullRes,
            subjectMask = analysis.subjectMask,
            depthMap = analysis.depthMap,
            neuralGainMap = analysis.neuralGainMap,
            lightingHint = analysis.lightingHint,
            recipe = analysis.concept.recipe,
            intensity = 1.0f,
            hasConfidentMask = analysis.hasConfidentMask
        )

        val uiOrigPreview = cameraController.createUiPreviewBitmap(denoisedFullRes, 1800)
        val uiProcPreview = cameraController.createUiPreviewBitmap(renderedFullRes.bitmap, 1800)

        val savedUri = runCatching {
            cameraController.saveBitmapToGallery(renderedFullRes.bitmap, job.raw.exifBytes, analysis.concept.conceptTitle)
        }.getOrNull()

        val session = CapturedPhotoSession(
            originalBitmap = denoisedFullRes,
            previewOriginalBitmap = uiOrigPreview,
            subjectMaskBitmap = analysis.subjectMask,
            depthMapBitmap = analysis.depthMap,
            neuralGainBitmap = analysis.neuralGainMap,
            lightingHint = analysis.lightingHint,
            processedBitmap = renderedFullRes.bitmap,
            previewProcessedBitmap = uiProcPreview,
            exifBytes = job.raw.exifBytes,
            concept = analysis.concept,
            aiModelsUsed = analysis.aiModelsUsed,
            burstFramesMerged = framesUsed,
            gpuAccelerated = renderedFullRes.gpuAccelerated,
            processingTimeMs = System.currentTimeMillis() - startMs,
            lensUsed = job.lens,
            capturedFromProMode = job.isPro,
            savedUriString = savedUri?.toString(),
            fullResMatchesCurrentRecipe = true,
            cachedStats = analysis.stats,
            cachedLabels = analysis.labels,
            cachedFaceCount = analysis.faceCount,
            hasConfidentMask = analysis.hasConfidentMask
        )

        _uiState.update { cur ->
            val updatedList = (listOf(session) + cur.studioSessions).take(10)
            cur.copy(
                studioSessions = updatedList,
                statusMessage = if (savedUri != null) {
                    "Salva na galeria (${renderedFullRes.bitmap.width}×${renderedFullRes.bitmap.height})"
                } else {
                    "Processada (${renderedFullRes.bitmap.width}×${renderedFullRes.bitmap.height})"
                }
            )
        }
    }

    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        viewModelScope.launch {
            val s = _uiState.value
            val isNormal = s.experienceMode == CameraExperienceMode.NORMAL
            val effectiveMode = if (isNormal) CaptureMode.EDITORIAL_BURST else s.captureMode
            runCatching {
                cameraController.bindCamera(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    lens = s.activeLens,
                    captureMode = effectiveMode,
                    preferAutoExtension = isNormal
                )
            }.onSuccess {
                _uiState.update { it.copy(hardwareProfile = cameraController.hardwareProfile, statusMessage = null) }
                startLiveHintLoop(previewView)
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Erro ao iniciar câmera: ${err.message}") }
            }
        }
    }

    private fun startLiveHintLoop(previewView: PreviewView) {
        hintSamplerJob?.cancel()
        hintSamplerJob = viewModelScope.launch {
            while (isActive) {
                delay(1150L)
                if (_uiState.value.activeSession != null) continue
                val snap: Bitmap? = withContext(Dispatchers.Main) {
                    runCatching { previewView.bitmap }.getOrNull()
                }
                if (snap != null) {
                    val decision = runCatching { conceptEngine.quickLiveHint(snap) }.getOrNull()
                    snap.recycle()
                    if (decision != null) {
                        _uiState.update { it.copy(autoDecision = decision) }
                    }
                }
            }
        }
    }

    fun selectExperienceMode(mode: CameraExperienceMode, owner: LifecycleOwner, previewView: PreviewView) {
        val prev = _uiState.value
        if (prev.experienceMode == mode) return

        if (mode == CameraExperienceMode.NORMAL) {
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
            bindCamera(owner, previewView)
        } else {
            _uiState.update { it.copy(experienceMode = CameraExperienceMode.PRO) }
            bindCamera(owner, previewView)
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

    /**
     * Non-blocking shutter: takes raw frame (~0.35s), IMMEDIATELY unlocks shutter for next photo,
     * and pushes enhancement + auto-save into background neural queue.
     */
    fun captureEditorialPhoto() {
        val state = _uiState.value
        if (state.isShutterBusy) return
        val isPro = state.experienceMode == CameraExperienceMode.PRO

        viewModelScope.launch {
            _uiState.update { it.copy(isShutterBusy = true) }
            runCatching {
                cameraController.captureFullRes()
            }.onSuccess { raw ->
                val effectiveStock = if (!isPro) FilmStock.AUTO_EDITORIAL else state.selectedStock
                _uiState.update {
                    it.copy(
                        isShutterBusy = false,
                        pendingQueueCount = it.pendingQueueCount + 1,
                        statusMessage = "Ensaio em fila (${it.pendingQueueCount + 1}) — pronto para outro clique!"
                    )
                }
                processingQueue.trySend(
                    QueuedShot(
                        raw = raw,
                        stock = effectiveStock,
                        lens = state.activeLens,
                        isPro = isPro,
                        manualBurstCount = state.burstCount,
                        captureMode = state.captureMode
                    )
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isShutterBusy = false, statusMessage = "Falha na captura: ${err.message}")
                }
            }
        }
    }

    fun openStudioReview(session: CapturedPhotoSession? = _uiState.value.studioSessions.firstOrNull()) {
        if (session == null) return
        _uiState.update {
            it.copy(
                activeSession = session,
                selectedStock = if (it.experienceMode == CameraExperienceMode.PRO) session.concept.recommendedStock else it.selectedStock,
                editIntensity = 1.0f
            )
        }
    }

    fun importFromGalleryUri(uri: Uri) {
        val state = _uiState.value
        val isPro = state.experienceMode == CameraExperienceMode.PRO
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Processando foto da galeria com IA local…") }
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
                        depthMap = analysis.depthMap,
                        neuralGainMap = analysis.neuralGainMap,
                        lightingHint = analysis.lightingHint,
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
                    depthMapBitmap = analysis.depthMap,
                    neuralGainBitmap = analysis.neuralGainMap,
                    lightingHint = analysis.lightingHint,
                    processedBitmap = renderedFullRes.bitmap,
                    previewProcessedBitmap = uiProcPreview,
                    exifBytes = exifBytes,
                    concept = analysis.concept,
                    aiModelsUsed = analysis.aiModelsUsed,
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
                _uiState.update { cur ->
                    val updatedList = (listOf(session) + cur.studioSessions).take(10)
                    cur.copy(
                        studioSessions = updatedList,
                        activeSession = session,
                        editIntensity = 1.0f,
                        statusMessage = null
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Erro ao abrir foto: ${err.message}") }
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
            val mask = session.subjectMaskBitmap
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
            val allowMask = session.hasConfidentMask || (customBokeh != null && customBokeh > 0.01f && (mask?.width ?: 0) > 16)

            val renderedPreview = withContext(Dispatchers.Default) {
                EditorialShaderEngine.renderEditorial(
                    source = session.previewOriginalBitmap,
                    subjectMask = mask,
                    depthMap = session.depthMapBitmap,
                    neuralGainMap = session.neuralGainBitmap,
                    lightingHint = session.lightingHint,
                    recipe = tunedRecipe,
                    intensity = intensity,
                    hasConfidentMask = allowMask
                )
            }
            _uiState.update { cur ->
                val curSession = cur.activeSession ?: return@update cur
                val nextSession = curSession.copy(
                    previewProcessedBitmap = renderedPreview.bitmap,
                    concept = updatedConcept.copy(recipe = tunedRecipe),
                    gpuAccelerated = renderedPreview.gpuAccelerated,
                    processingTimeMs = System.currentTimeMillis() - startMs,
                    fullResMatchesCurrentRecipe = false,
                    savedUriString = null
                )
                cur.copy(
                    activeSession = nextSession,
                    studioSessions = cur.studioSessions.map { if (it.id == nextSession.id) nextSession else it }
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
                    val allowMask = session.hasConfidentMask || (session.concept.recipe.backgroundBokeh > 0.01f && (mask?.width ?: 0) > 16)
                    withContext(Dispatchers.Default) {
                        EditorialShaderEngine.renderEditorial(
                            source = session.originalBitmap,
                            subjectMask = mask,
                            depthMap = session.depthMapBitmap,
                            neuralGainMap = session.neuralGainBitmap,
                            lightingHint = session.lightingHint,
                            recipe = session.concept.recipe,
                            intensity = intensity,
                            hasConfidentMask = allowMask
                        ).bitmap
                    }
                }
                val uri = cameraController.saveBitmapToGallery(exportBmp, session.exifBytes, session.concept.conceptTitle)
                exportBmp to uri
            }.onSuccess { (finalFullBmp, uri) ->
                _uiState.update { cur ->
                    val s = cur.activeSession ?: return@update cur
                    val updated = s.copy(
                        processedBitmap = finalFullBmp,
                        fullResMatchesCurrentRecipe = true,
                        savedUriString = uri.toString()
                    )
                    cur.copy(
                        isSaving = false,
                        activeSession = updated,
                        studioSessions = cur.studioSessions.map { if (it.id == updated.id) updated else it },
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
        hintSamplerJob?.cancel()
        processingQueue.close()
        cameraController.release()
        conceptEngine.close()
    }
}
