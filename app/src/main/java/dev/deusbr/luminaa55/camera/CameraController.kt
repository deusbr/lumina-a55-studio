package dev.deusbr.luminaa55.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.deusbr.luminaa55.hardware.A55CameraInspector
import dev.deusbr.luminaa55.model.CameraHardwareProfile
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.LensOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var activeCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val burstRingBuffer = ArrayDeque<Bitmap>(6)
    private val ringLock = Any()

    var hardwareProfile: CameraHardwareProfile? = null
        private set

    private var lastHintTimestampMs = 0L

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lens: LensOption,
        captureMode: CaptureMode,
        onLiveFrameForHint: (Bitmap) -> Unit
    ) {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).awaitListenable(context)
            .also { cameraProvider = it }

        val extMgr = extensionsManager ?: runCatching {
            ExtensionsManager.getInstanceAsync(context, provider).awaitListenable(context)
        }.getOrNull().also { extensionsManager = it }

        if (hardwareProfile == null) {
            hardwareProfile = A55CameraInspector.inspect(context, extMgr)
        }

        val baseSelector = buildSelectorForLens(lens, hardwareProfile)
        val finalSelector = resolveExtensionSelector(baseSelector, captureMode, extMgr)

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(cameraExecutor) { proxy ->
            try {
                val bmp = MultiFrameBurstProcessor.imageProxyToBitmap(proxy)
                synchronized(ringLock) {
                    if (burstRingBuffer.size >= 5) {
                        val oldest = burstRingBuffer.removeFirst()
                        oldest.recycle()
                    }
                    burstRingBuffer.addLast(bmp.copy(Bitmap.Config.ARGB_8888, false))
                }
                val now = System.currentTimeMillis()
                if (now - lastHintTimestampMs > 1400L) {
                    lastHintTimestampMs = now
                    onLiveFrameForHint(bmp)
                } else {
                    bmp.recycle()
                }
            } catch (_: Throwable) {
            } finally {
                proxy.close()
            }
        }

        withContext(Dispatchers.Main) {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, finalSelector, preview, capture, analysis)
            activeCamera = cam
            imageCapture = capture
            imageAnalysis = analysis

            if (lens == LensOption.TELE_2X) {
                cam.cameraControl.setZoomRatio(2.0f)
            } else {
                cam.cameraControl.setZoomRatio(1.0f)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildSelectorForLens(lens: LensOption, profile: CameraHardwareProfile?): CameraSelector {
        if (lens == LensOption.FRONT_32MP) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val matchedId = profile?.lenses?.firstOrNull { it.mappedRole == lens }?.cameraId
        if (matchedId != null) {
            return CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { infos ->
                    val exact = infos.filter { info ->
                        runCatching { Camera2CameraInfo.from(info).cameraId == matchedId }.getOrDefault(false)
                    }
                    exact.ifEmpty { infos }
                }
                .build()
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun resolveExtensionSelector(
        base: CameraSelector,
        mode: CaptureMode,
        extMgr: ExtensionsManager?
    ): CameraSelector {
        if (extMgr == null || mode == CaptureMode.EDITORIAL_BURST) return base
        val extMode = when (mode) {
            CaptureMode.SAMSUNG_HDR -> ExtensionMode.HDR
            CaptureMode.SAMSUNG_NIGHT -> ExtensionMode.NIGHT
            CaptureMode.SAMSUNG_BOKEH -> ExtensionMode.BOKEH
            CaptureMode.EDITORIAL_BURST -> ExtensionMode.NONE
        }
        return runCatching {
            if (extMgr.isExtensionAvailable(base, extMode)) {
                extMgr.getExtensionEnabledCameraSelector(base, extMode)
            } else {
                base
            }
        }.getOrDefault(base)
    }

    fun setZoomRatio(ratio: Float) {
        val cam = activeCamera ?: return
        val state = cam.cameraInfo.zoomState.value
        val clamped = ratio.coerceIn(state?.minZoomRatio ?: 1f, state?.maxZoomRatio ?: 8f)
        cam.cameraControl.setZoomRatio(clamped)
    }

    fun setExposureIndex(evIndex: Int) {
        val cam = activeCamera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return
        cam.cameraControl.setExposureCompensationIndex(evIndex.coerceIn(range.lower, range.upper))
    }

    fun focusAtPoint(previewView: PreviewView, x: Float, y: Float) {
        val cam = activeCamera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualIsoShutter(iso: Int?, shutterMs: Float?) {
        val cam = activeCamera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        if (iso != null && shutterMs != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(50, 3200))
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, (shutterMs * 1_000_000L).toLong())
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        c2Control.captureRequestOptions = builder.build()
    }

    fun exposureRange(): IntRange {
        val range = activeCamera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return -6..6
        return range.lower..range.upper
    }

    /**
     * Captures a high-res photo and optionally fuses recent aligned YUV ring frames for HDR+ noise reduction.
     */
    suspend fun captureHighResWithBurst(burstCount: Int): Pair<Bitmap, Int> {
        val cap = imageCapture ?: error("Camera is not ready yet.")
        val primaryBmp = suspendCancellableCoroutine<Bitmap> { cont ->
            cap.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = decodeCapturedImageProxy(image)
                        cont.resume(bmp)
                    } catch (err: Throwable) {
                        cont.resumeWithException(err)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

        if (burstCount <= 1) {
            return primaryBmp to 1
        }

        val yuvSnaps = synchronized(ringLock) {
            burstRingBuffer.toList().takeLast((burstCount - 1).coerceAtLeast(0))
                .map { it.copy(Bitmap.Config.ARGB_8888, false) }
        }
        if (yuvSnaps.isEmpty()) {
            return primaryBmp to 1
        }

        return withContext(Dispatchers.Default) {
            // Upscale YUV burst frames only for subtle low-frequency shadow denoising blend with crisp primary frame
            val mergedLowRes = MultiFrameBurstProcessor.mergeBitmaps(yuvSnaps)
            yuvSnaps.forEach { it.recycle() }
            val alignedShadowReference = Bitmap.createScaledBitmap(mergedLowRes, primaryBmp.width, primaryBmp.height, true)
            mergedLowRes.recycle()

            val fused = blendShadowDetail(primaryBmp, alignedShadowReference)
            alignedShadowReference.recycle()
            primaryBmp.recycle()
            fused to (1 + yuvSnaps.size)
        }
    }

    /**
     * Preserves full-res sharpness of primary frame while pulling clean denoised shadow tone from aligned YUV burst.
     */
    private fun blendShadowDetail(primary: Bitmap, denoisedBurst: Bitmap): Bitmap {
        val w = primary.width
        val h = primary.height
        val pPx = IntArray(w * h)
        val bPx = IntArray(w * h)
        primary.getPixels(pPx, 0, w, 0, 0, w, h)
        denoisedBurst.getPixels(bPx, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val pc = pPx[i]
            val bc = bPx[i]
            val pr = (pc ushr 16) and 0xFF
            val pg = (pc ushr 8) and 0xFF
            val pb = pc and 0xFF

            val br = (bc ushr 16) and 0xFF
            val bg = (bc ushr 8) and 0xFF
            val bb = bc and 0xFF

            val luma = (0.2126f * pr + 0.7152f * pg + 0.0722f * pb) / 255f
            val shadowBlend = if (luma < 0.28f) (1f - luma / 0.28f) * 0.28f else 0f

            val r = (pr * (1f - shadowBlend) + br * shadowBlend).toInt().coerceIn(0, 255)
            val g = (pg * (1f - shadowBlend) + bg * shadowBlend).toInt().coerceIn(0, 255)
            val b = (pb * (1f - shadowBlend) + bb * shadowBlend).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun decodeCapturedImageProxy(image: ImageProxy): Bitmap {
        if (image.format == android.graphics.ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rot = image.imageInfo.rotationDegrees
            if (rot == 0) return raw
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it !== raw) raw.recycle()
            }
        }
        return MultiFrameBurstProcessor.imageProxyToBitmap(image)
    }

    suspend fun saveBitmapToGallery(bitmap: Bitmap, conceptTag: String): Uri = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "LuminaA55_${stamp}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LuminaA55")
            put(MediaStore.Images.Media.DESCRIPTION, "Lumina A55 Studio · $conceptTag")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Falha ao criar item na galeria.")

        resolver.openOutputStream(uri)?.use { stream ->
            val buf = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, buf)
            stream.write(buf.toByteArray())
        } ?: error("Falha ao gravar JPEG na galeria.")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    fun release() {
        cameraExecutor.shutdown()
        synchronized(ringLock) {
            burstRingBuffer.forEach { it.recycle() }
            burstRingBuffer.clear()
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitListenable(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (err: Throwable) {
                cont.resumeWithException(err)
            }
        }, ContextCompat.getMainExecutor(context))
    }
