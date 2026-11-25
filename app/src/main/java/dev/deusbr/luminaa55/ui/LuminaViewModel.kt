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
import dev.deusbr.luminaa55.model.GeminiOnlineMode
import dev.deusbr.luminaa55.model.LensOption
import dev.deusbr.luminaa55.model.OnlineDirectionStyle
import dev.deusbr.luminaa55.model.ProDeckTab
import dev.deusbr.luminaa55.model.ProcessingEngine
import dev.deusbr.luminaa55.online.GeminiStudioService
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
        recommendedStock = FilmStock.RETRATO_EDITORIAL,
        recommendedBurst = 3
    ),
    val isShutterBusy: Boolean = false,
    val pendingQueueCount: Int = 0,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val hardwareProfile: CameraHardwareProfile? = null,
    val studioSessions: List<CapturedPhotoSession> = emptyList(),
    val activeSession: CapturedPhotoSession? = null,
    val editIntensity: Float = 1.0f,
    val processingEngine: ProcessingEngine = ProcessingEngine.OFFLINE_LOCAL,
    val geminiOnlineMode: GeminiOnlineMode = GeminiOnlineMode.STUDIO_IMAGE_FLASH,
    val onlineDirectionStyle: OnlineDirectionStyle = OnlineDirectionStyle.STUDIO_EDITORIAL,
    val hasGeminiKey: Boolean = false,
    val showGeminiSettingsDialog: Boolean = false,
    val isOnlineProcessing: Boolean = false
)

private data class QueuedShot(
    val raw: RawCaptureOutput,
    val stock: FilmStock,
    val lens: LensOption,
    val isPro: Boolean,
    val manualBurstCount: Int,
    val captureMode: CaptureMode,
    val engine: ProcessingEngine,
    val geminiMode: GeminiOnlineMode,
    val directionStyle: OnlineDirectionStyle
)

class LuminaViewModel(app: Application) : AndroidViewModel(app) {

    val cameraController = CameraController(app)
    private val conceptEngine = SceneConceptEngine(app)
    private val geminiService = GeminiStudioService(app)
    private val processingQueue = Channel<QueuedShot>(Channel.UNLIMITED)
    private var reprocessJob: Job? = null
    private var hintSamplerJob: Job? = null

    private val _uiState = MutableStateFlow(LuminaUiState(hasGeminiKey = geminiService.hasApiKey()))
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
        val effectiveEngine = if (job.engine == ProcessingEngine.ONLINE_GEMINI && geminiService.hasApiKey()) {
            ProcessingEngine.ONLINE_GEMINI
        } else {
            ProcessingEngine.OFFLINE_LOCAL
        }
        val analysis = conceptEngine.analyze(job.raw.fullResBitmap, job.stock, effectiveEngine)
        // Skip heavy single-frame spatial blur when true multi-frame burst already merged >=2 frames
        val denoiseLevel = if (job.raw.framesDenoised >= 2) {
            1
        } else if (!job.isPro) {
            analysis.concept.recommendedBurstCount
        } else if (job.captureMode == CaptureMode.EDITORIAL_BURST) {
            job.manualBurstCount
        } else {
            1
        }

        val (denoisedFullRes, spatialFrames) = cameraController.applyFullResDenoiseIfRequested(
            job.raw.fullResBitmap,
            denoiseLevel
        )
        val framesUsed = maxOf(job.raw.framesDenoised, spatialFrames)
        if (denoisedFullRes !== job.raw.fullResBitmap) {
            job.raw.fullResBitmap.recycle()
        }

        var usedConcept = analysis.concept
        var onlineNote: String? = null
        val renderedFullRes = if (effectiveEngine == ProcessingEngine.ONLINE_GEMINI) {
            val onlineRes = runCatching {
                geminiService.enhanceWithGemini(
                    sourceBitmap = denoisedFullRes,
                    baseConcept = analysis.concept,
                    stats = analysis.stats,
                    labels = analysis.labels,
                    faceCount = analysis.faceCount,
                    mode = job.geminiMode,
                    directionStyle = job.directionStyle,
                    hasNeuralAlpha = analysis.hasConfidentMask
                )
            }.getOrNull()
            if (onlineRes != null) {
                usedConcept = onlineRes.calibratedConcept
                onlineNote = onlineRes.statusNote
                if (onlineRes.generatedBitmap != null) {
                    dev.deusbr.luminaa55.shader.ShaderRenderResult(onlineRes.generatedBitmap, gpuAccelerated = true)
                } else {
                    EditorialShaderEngine.renderEditorial(
                        source = denoisedFullRes,
                        subjectMask = analysis.subjectMask,
                        depthMap = analysis.depthMap,
                        neuralGainMap = analysis.neuralGainMap,
                        lightingHint = analysis.lightingHint,
                        recipe = usedConcept.recipe,
                        intensity = 1.0f,
                        hasConfidentMask = analysis.hasConfidentMask
                    )
                }
            } else {
                onlineNote = "Sem conexão Gemini — renderizado no Motor Offline."
                EditorialShaderEngine.renderEditorial(
                    source = denoisedFullRes,
                    subjectMask = analysis.subjectMask,
                    depthMap = analysis.depthMap,
                    neuralGainMap = analysis.neuralGainMap,
                    lightingHint = analysis.lightingHint,
                    recipe = analysis.concept.recipe,
                    intensity = 1.0f,
                    hasConfidentMask = analysis.hasConfidentMask
                )
            }
        } else {
            EditorialShaderEngine.renderEditorial(
                source = denoisedFullRes,
                subjectMask = analysis.subjectMask,
                depthMap = analysis.depthMap,
                neuralGainMap = analysis.neuralGainMap,
                lightingHint = analysis.lightingHint,
                recipe = analysis.concept.recipe,
                intensity = 1.0f,
                hasConfidentMask = analysis.hasConfidentMask
            )
        }

        val uiOrigPreview = cameraController.createUiPreviewBitmap(denoisedFullRes, 1800)
        val uiProcPreview = cameraController.createUiPreviewBitmap(renderedFullRes.bitmap, 1800)

        val savedUri = runCatching {
            cameraController.saveBitmapToGallery(renderedFullRes.bitmap, job.raw.exifBytes, usedConcept.conceptTitle)
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
            concept = usedConcept,
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
            hasConfidentMask = analysis.hasConfidentMask,
            engineUsed = effectiveEngine,
            onlineNotes = onlineNote
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
                    preferAutoExtension = false
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

    fun selectProcessingEngine(engine: ProcessingEngine) {
        if (engine == ProcessingEngine.ONLINE_GEMINI && !geminiService.hasApiKey()) {
            _uiState.update { it.copy(showGeminiSettingsDialog = true) }
            return
        }
        _uiState.update { it.copy(processingEngine = engine) }
        if (_uiState.value.activeSession != null) {
            applySelectedEngineToActiveSession(engine)
        }
    }

    fun selectGeminiOnlineMode(mode: GeminiOnlineMode) {
        _uiState.update { it.copy(geminiOnlineMode = mode) }
    }

    fun selectOnlineDirectionStyle(style: OnlineDirectionStyle) {
        _uiState.update { it.copy(onlineDirectionStyle = style) }
    }

    fun openGeminiSettingsDialog(open: Boolean) {
        _uiState.update { it.copy(showGeminiSettingsDialog = open) }
    }

    fun currentGeminiApiKey(): String = geminiService.getSavedApiKey()

    fun saveGeminiApiKey(rawKey: String) {
        geminiService.saveApiKey(rawKey)
        val hasKey = geminiService.hasApiKey()
        _uiState.update {
            it.copy(
                hasGeminiKey = hasKey,
                showGeminiSettingsDialog = false,
                processingEngine = if (hasKey) ProcessingEngine.ONLINE_GEMINI else ProcessingEngine.OFFLINE_LOCAL,
                statusMessage = if (hasKey) "Chave Gemini salva no aparelho (privada)." else "Chave Gemini removida — usando Motor Offline."
            )
        }
        if (hasKey && _uiState.value.activeSession != null) {
            applySelectedEngineToActiveSession(ProcessingEngine.ONLINE_GEMINI)
        }
    }

    fun applySelectedEngineToActiveSession(engine: ProcessingEngine) {
        val session = _uiState.value.activeSession ?: return
        val state = _uiState.value
        if (engine == ProcessingEngine.ONLINE_GEMINI && !geminiService.hasApiKey()) {
            _uiState.update { it.copy(showGeminiSettingsDialog = true) }
            return
        }

        reprocessJob?.cancel()
        reprocessJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val stats = session.cachedStats
            if (engine == ProcessingEngine.OFFLINE_LOCAL || stats == null) {
                val baseConcept = if (stats != null) {
                    val offlineBase = PhotometricAnalyzer.buildConceptProfile(
                        stats = stats,
                        labels = session.cachedLabels,
                        faceCount = session.cachedFaceCount,
                        subjectRatio = session.concept.subjectRatio,
                        userSelectedStock = state.selectedStock,
                        hasConfidentMask = session.hasConfidentMask
                    )
                    val crew = dev.deusbr.luminaa55.context.PhotographyCrewDirector.compileCrewSheet(
                        concept = offlineBase,
                        stats = stats,
                        labels = session.cachedLabels,
                        faceCount = session.cachedFaceCount,
                        engine = ProcessingEngine.OFFLINE_LOCAL,
                        hasNeuralAlpha = session.hasConfidentMask
                    )
                    offlineBase.copy(crewSheet = crew)
                } else session.concept
                val rendered = withContext(Dispatchers.Default) {
                    EditorialShaderEngine.renderEditorial(
                        source = session.previewOriginalBitmap,
                        subjectMask = session.subjectMaskBitmap,
                        depthMap = session.depthMapBitmap,
                        neuralGainMap = session.neuralGainBitmap,
                        lightingHint = session.lightingHint,
                        recipe = baseConcept.recipe,
                        intensity = state.editIntensity,
                        hasConfidentMask = session.hasConfidentMask
                    )
                }
                _uiState.update { cur ->
                    val next = session.copy(
                        previewProcessedBitmap = rendered.bitmap,
                        concept = baseConcept,
                        gpuAccelerated = rendered.gpuAccelerated,
                        processingTimeMs = System.currentTimeMillis() - startMs,
                        fullResMatchesCurrentRecipe = false,
                        savedUriString = null,
                        engineUsed = ProcessingEngine.OFFLINE_LOCAL,
                        onlineNotes = null
                    )
                    cur.copy(
                        processingEngine = ProcessingEngine.OFFLINE_LOCAL,
                        activeSession = next,
                        studioSessions = cur.studioSessions.map { if (it.id == next.id) next else it },
                        statusMessage = "Motor Offline (OKLab GPU) aplicado."
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isOnlineProcessing = true, statusMessage = "Banca 5 Blocos + Gemini analisando…") }
            runCatching {
                geminiService.enhanceWithGemini(
                    sourceBitmap = session.originalBitmap,
                    baseConcept = session.concept,
                    stats = stats,
                    labels = session.cachedLabels,
                    faceCount = session.cachedFaceCount,
                    mode = state.geminiOnlineMode,
                    directionStyle = state.onlineDirectionStyle,
                    hasNeuralAlpha = session.hasConfidentMask
                )
            }.onSuccess { onlineRes ->
                val fullResBmp: Bitmap
                val previewBmp: Bitmap
                val isGpu: Boolean
                if (onlineRes.generatedBitmap != null) {
                    fullResBmp = onlineRes.generatedBitmap
                    previewBmp = withContext(Dispatchers.Default) {
                        cameraController.createUiPreviewBitmap(fullResBmp, 1800)
                    }
                    isGpu = true
                } else {
                    val renderedPreview = withContext(Dispatchers.Default) {
                        EditorialShaderEngine.renderEditorial(
                            source = session.previewOriginalBitmap,
                            subjectMask = session.subjectMaskBitmap,
                            depthMap = session.depthMapBitmap,
                            neuralGainMap = session.neuralGainBitmap,
                            lightingHint = session.lightingHint,
                            recipe = onlineRes.calibratedConcept.recipe,
                            intensity = state.editIntensity,
                            hasConfidentMask = session.hasConfidentMask
                        )
                    }
                    fullResBmp = session.processedBitmap
                    previewBmp = renderedPreview.bitmap
                    isGpu = renderedPreview.gpuAccelerated
                }
                _uiState.update { cur ->
                    val next = session.copy(
                        processedBitmap = fullResBmp,
                        previewProcessedBitmap = previewBmp,
                        concept = onlineRes.calibratedConcept,
                        gpuAccelerated = isGpu,
                        processingTimeMs = System.currentTimeMillis() - startMs,
                        fullResMatchesCurrentRecipe = onlineRes.usedGenerativeImage,
                        savedUriString = null,
                        engineUsed = ProcessingEngine.ONLINE_GEMINI,
                        onlineNotes = onlineRes.statusNote
                    )
                    cur.copy(
                        isOnlineProcessing = false,
                        processingEngine = ProcessingEngine.ONLINE_GEMINI,
                        activeSession = next,
                        studioSessions = cur.studioSessions.map { if (it.id == next.id) next else it },
                        statusMessage = onlineRes.statusNote
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isOnlineProcessing = false,
                        statusMessage = "Gemini Online: ${err.message}"
                    )
                }
            }
        }
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
            val burstFrames = when {
                !isPro -> state.autoDecision.recommendedBurst.coerceIn(1, 3)
                state.captureMode == CaptureMode.EDITORIAL_BURST -> state.burstCount.coerceIn(1, 5)
                else -> 1
            }
            runCatching {
                cameraController.captureBurstAndMerge(burstFrames)
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
                        captureMode = state.captureMode,
                        engine = state.processingEngine,
                        geminiMode = state.geminiOnlineMode,
                        directionStyle = state.onlineDirectionStyle
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
        val activeEngine = if (state.processingEngine == ProcessingEngine.ONLINE_GEMINI && geminiService.hasApiKey()) {
            ProcessingEngine.ONLINE_GEMINI
        } else {
            ProcessingEngine.OFFLINE_LOCAL
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isOnlineProcessing = activeEngine == ProcessingEngine.ONLINE_GEMINI,
                    statusMessage = if (activeEngine == ProcessingEngine.ONLINE_GEMINI) {
                        "Banca Fotográfica + Gemini processando foto…"
                    } else {
                        "Processando foto da galeria com MODNet + GPU local…"
                    }
                )
            }
            val startMs = System.currentTimeMillis()
            runCatching {
                val (fullBmp, exifBytes) = withContext(Dispatchers.IO) {
                    cameraController.decodeGalleryUriFullRes(uri)
                }
                val stock = if (isPro) state.selectedStock else FilmStock.AUTO_EDITORIAL
                val analysis = conceptEngine.analyze(fullBmp, stock, activeEngine)
                var finalConcept = analysis.concept
                var note: String? = null
                val renderedFullRes = if (activeEngine == ProcessingEngine.ONLINE_GEMINI) {
                    val onlineOutcome = runCatching {
                        geminiService.enhanceWithGemini(
                            sourceBitmap = fullBmp,
                            baseConcept = analysis.concept,
                            stats = analysis.stats,
                            labels = analysis.labels,
                            faceCount = analysis.faceCount,
                            mode = state.geminiOnlineMode,
                            directionStyle = state.onlineDirectionStyle,
                            hasNeuralAlpha = analysis.hasConfidentMask
                        )
                    }
                    val onlineRes = onlineOutcome.getOrNull()
                    if (onlineRes != null) {
                        finalConcept = onlineRes.calibratedConcept
                        note = onlineRes.statusNote
                        if (onlineRes.generatedBitmap != null) {
                            dev.deusbr.luminaa55.shader.ShaderRenderResult(onlineRes.generatedBitmap, gpuAccelerated = true)
                        } else {
                            withContext(Dispatchers.Default) {
                                EditorialShaderEngine.renderEditorial(
                                    source = fullBmp,
                                    subjectMask = analysis.subjectMask,
                                    depthMap = analysis.depthMap,
                                    neuralGainMap = analysis.neuralGainMap,
                                    lightingHint = analysis.lightingHint,
                                    recipe = finalConcept.recipe,
                                    intensity = 1.0f,
                                    hasConfidentMask = analysis.hasConfidentMask
                                )
                            }
                        }
                    } else {
                        note = onlineOutcome.exceptionOrNull()?.message ?: "Falha no Gemini Online — usando Motor Offline."
                        withContext(Dispatchers.Default) {
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
                    }
                } else {
                    withContext(Dispatchers.Default) {
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
                    concept = finalConcept,
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
                    hasConfidentMask = analysis.hasConfidentMask,
                    engineUsed = activeEngine,
                    onlineNotes = note
                )
            }.onSuccess { session ->
                _uiState.update { cur ->
                    val updatedList = (listOf(session) + cur.studioSessions).take(10)
                    cur.copy(
                        isOnlineProcessing = false,
                        studioSessions = updatedList,
                        activeSession = session,
                        editIntensity = 1.0f,
                        statusMessage = session.onlineNotes
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(isOnlineProcessing = false, statusMessage = "Erro ao abrir foto: ${err.message}") }
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
                val baseProfile = PhotometricAnalyzer.buildConceptProfile(
                    stats = stats,
                    labels = session.cachedLabels,
                    faceCount = session.cachedFaceCount,
                    subjectRatio = session.concept.subjectRatio,
                    userSelectedStock = stock,
                    hasConfidentMask = session.hasConfidentMask
                )
                val crew = dev.deusbr.luminaa55.context.PhotographyCrewDirector.compileCrewSheet(
                    concept = baseProfile,
                    stats = stats,
                    labels = session.cachedLabels,
                    faceCount = session.cachedFaceCount,
                    engine = ProcessingEngine.OFFLINE_LOCAL,
                    hasNeuralAlpha = session.hasConfidentMask
                )
                baseProfile.copy(crewSheet = crew)
            } else {
                conceptEngine.analyze(session.previewOriginalBitmap, stock, ProcessingEngine.OFFLINE_LOCAL).concept
            }

            val baseRecipe = updatedConcept.recipe
            val tunedRecipe = baseRecipe.copy(
                proMistStrength = customProMist ?: baseRecipe.proMistStrength,
                backgroundBokeh = customBokeh ?: baseRecipe.backgroundBokeh,
                grainAmount = customGrain ?: baseRecipe.grainAmount,
                relight3dStrength = 0.0f,
                studioBackdropStrength = 0.0f
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
