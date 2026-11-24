package dev.deusbr.luminaa55.ui

import android.app.Application
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.deusbr.luminaa55.camera.CameraController
import dev.deusbr.luminaa55.context.PhotometricAnalyzer
import dev.deusbr.luminaa55.context.SceneConceptEngine
import dev.deusbr.luminaa55.model.CameraHardwareProfile
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.CapturedPhotoSession
import dev.deusbr.luminaa55.model.FilmStock
import dev.deusbr.luminaa55.model.LensOption
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
    val activeLens: LensOption = LensOption.MAIN_IMX906,
    val captureMode: CaptureMode = CaptureMode.EDITORIAL_BURST,
    val selectedStock: FilmStock = FilmStock.AUTO_EDITORIAL,
    val burstCount: Int = 3,
    val evIndex: Int = 0,
    val manualMode: Boolean = false,
    val manualIso: Int = 200,
    val manualShutterMs: Float = 16f,
    val showGrid: Boolean = true,
    val showProBar: Boolean = false,
    val showDiagnostics: Boolean = false,
    val liveConceptHint: String = "Analisando cena…",
    val isCapturing: Boolean = false,
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
            runCatching {
                cameraController.bindCamera(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    lens = s.activeLens,
                    captureMode = s.captureMode,
                    onLiveFrameForHint = { frame ->
                        viewModelScope.launch {
                            val hint = runCatching { conceptEngine.quickLiveHint(frame) }.getOrNull()
                            frame.recycle()
                            if (hint != null) {
                                _uiState.update { it.copy(liveConceptHint = hint) }
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

    fun selectLens(lens: LensOption, owner: LifecycleOwner, previewView: PreviewView) {
        _uiState.update { it.copy(activeLens = lens) }
        bindCamera(owner, previewView)
    }

    fun selectCaptureMode(mode: CaptureMode, owner: LifecycleOwner, previewView: PreviewView) {
        _uiState.update { it.copy(captureMode = mode) }
        bindCamera(owner, previewView)
    }

    fun selectFilmStock(stock: FilmStock) {
        _uiState.update { it.copy(selectedStock = stock) }
        if (_uiState.value.activeSession != null) {
            reprocessActiveSession(stock, _uiState.value.editIntensity)
        }
    }

    fun setBurstCount(count: Int) {
        _uiState.update { it.copy(burstCount = count.coerceIn(1, 5)) }
    }

    fun setEvIndex(ev: Int) {
        _uiState.update { it.copy(evIndex = ev) }
        cameraController.setExposureIndex(ev)
    }

    fun toggleProBar() {
        _uiState.update { it.copy(showProBar = !it.showProBar) }
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun toggleDiagnostics(show: Boolean) {
        _uiState.update { it.copy(showDiagnostics = show) }
    }

    fun updateManualExposure(enabled: Boolean, iso: Int, shutterMs: Float) {
        _uiState.update { it.copy(manualMode = enabled, manualIso = iso, manualShutterMs = shutterMs) }
        if (enabled) cameraController.applyManualIsoShutter(iso, shutterMs)
        else cameraController.applyManualIsoShutter(null, null)
    }

    fun captureEditorialPhoto() {
        if (_uiState.value.isCapturing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, statusMessage = "Capturando & analisando cena…") }
            val startMs = System.currentTimeMillis()
            runCatching {
                val burst = if (_uiState.value.captureMode == CaptureMode.EDITORIAL_BURST) _uiState.value.burstCount else 1
                val (capturedBmp, mergedCount) = cameraController.captureHighResWithBurst(burst)
                val analysis = conceptEngine.analyze(capturedBmp, _uiState.value.selectedStock)
                val rendered = withContext(Dispatchers.Default) {
                    EditorialShaderEngine.renderEditorial(
                        source = capturedBmp,
                        subjectMask = analysis.subjectMask,
                        recipe = analysis.concept.recipe,
                        intensity = 1.0f,
                        hasConfidentMask = analysis.hasConfidentMask
                    )
                }
                CapturedPhotoSession(
                    originalBitmap = capturedBmp,
                    subjectMaskBitmap = analysis.subjectMask,
                    processedBitmap = rendered.bitmap,
                    concept = analysis.concept,
                    burstFramesMerged = mergedCount,
                    gpuAccelerated = rendered.gpuAccelerated,
                    processingTimeMs = System.currentTimeMillis() - startMs,
                    lensUsed = _uiState.value.activeLens,
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
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, statusMessage = "Lendo imagem & aplicando ensaio…") }
            val startMs = System.currentTimeMillis()
            runCatching {
                val scaled = withContext(Dispatchers.IO) {
                    cameraController.decodeGalleryUriOriented(uri, maxLongEdge = 2560)
                }
                val analysis = conceptEngine.analyze(scaled, _uiState.value.selectedStock)
                val rendered = withContext(Dispatchers.Default) {
                    EditorialShaderEngine.renderEditorial(
                        source = scaled,
                        subjectMask = analysis.subjectMask,
                        recipe = analysis.concept.recipe,
                        intensity = 1.0f,
                        hasConfidentMask = analysis.hasConfidentMask
                    )
                }
                CapturedPhotoSession(
                    originalBitmap = scaled,
                    subjectMaskBitmap = analysis.subjectMask,
                    processedBitmap = rendered.bitmap,
                    concept = analysis.concept,
                    burstFramesMerged = 1,
                    gpuAccelerated = rendered.gpuAccelerated,
                    processingTimeMs = System.currentTimeMillis() - startMs,
                    lensUsed = _uiState.value.activeLens,
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
            val mask = session.subjectMaskBitmap
            val updatedConcept = if (stats != null && mask != null) {
                PhotometricAnalyzer.buildConceptProfile(
                    stats = stats,
                    labels = session.cachedLabels,
                    faceCount = session.cachedFaceCount,
                    subjectRatio = session.concept.subjectRatio,
                    userSelectedStock = stock,
                    hasConfidentMask = session.hasConfidentMask
                )
            } else {
                conceptEngine.analyze(session.originalBitmap, stock).concept
            }

            val baseRecipe = updatedConcept.recipe
            val tunedRecipe = baseRecipe.copy(
                proMistStrength = customProMist ?: baseRecipe.proMistStrength,
                backgroundBokeh = customBokeh ?: baseRecipe.backgroundBokeh,
                grainAmount = customGrain ?: baseRecipe.grainAmount
            )
            val maskForRender = mask ?: return@launch
            val allowMask = session.hasConfidentMask || (customBokeh != null && customBokeh > 0.01f && mask.width > 16)

            val rendered = withContext(Dispatchers.Default) {
                EditorialShaderEngine.renderEditorial(
                    source = session.originalBitmap,
                    subjectMask = maskForRender,
                    recipe = tunedRecipe,
                    intensity = intensity,
                    hasConfidentMask = allowMask
                )
            }
            _uiState.update { cur ->
                val curSession = cur.activeSession ?: return@update cur
                cur.copy(
                    activeSession = curSession.copy(
                        processedBitmap = rendered.bitmap,
                        concept = updatedConcept.copy(recipe = tunedRecipe),
                        gpuAccelerated = rendered.gpuAccelerated,
                        processingTimeMs = System.currentTimeMillis() - startMs,
                        savedUriString = null
                    )
                )
            }
        }
    }

    fun saveActiveSession() {
        val session = _uiState.value.activeSession ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Salvando JPEG editorial na galeria…") }
            runCatching {
                cameraController.saveBitmapToGallery(session.processedBitmap, session.concept.conceptTitle)
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        activeSession = session.copy(savedUriString = uri.toString()),
                        statusMessage = "Salva em Pictures/LuminaA55"
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = "Erro ao salvar: ${err.message}") }
            }
        }
    }

    fun closeReviewStudio() {
        reprocessJob?.cancel()
        _uiState.update { it.copy(activeSession = null, statusMessage = null) }
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
        conceptEngine.close()
    }
}
