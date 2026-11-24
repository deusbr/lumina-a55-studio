package dev.deusbr.luminaa55.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import dev.deusbr.luminaa55.hardware.A55CameraInspector
import dev.deusbr.luminaa55.model.CameraHardwareProfile
import dev.deusbr.luminaa55.model.CaptureMode
import dev.deusbr.luminaa55.model.LensOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var activeCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Dedicated executors prevent live preview hints from blocking shutter capture
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var hardwareProfile: CameraHardwareProfile? = null
        private set

    @Volatile
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

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val now = System.currentTimeMillis()
                // Only decode a frame when a live hint refresh is actually due (~1.2s)
                if (now - lastHintTimestampMs > 1200L) {
                    lastHintTimestampMs = now
                    val bmp = MultiFrameBurstProcessor.imageProxyToBitmap(proxy)
                    onLiveFrameForHint(bmp)
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

            // Samsung One UI routes 0.5x ultrawide through minZoomRatio when physical ID is logical-grouped
            val zoomState = cam.cameraInfo.zoomState.value
            val minZ = zoomState?.minZoomRatio ?: 0.5f
            val maxZ = zoomState?.maxZoomRatio ?: 8.0f
            val hasPhysicalUw = hardwareProfile?.lenses?.any { it.mappedRole == LensOption.ULTRA_WIDE } == true
            val targetZoom = when {
                lens == LensOption.ULTRA_WIDE && !hasPhysicalUw -> max(0.5f, minZ)
                lens == LensOption.TELE_2X -> 2.0f.coerceAtMost(maxZ)
                else -> 1.0f.coerceIn(minZ, maxZ)
            }
            cam.cameraControl.setZoomRatio(targetZoom)
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
        val clamped = ratio.coerceIn(state?.minZoomRatio ?: 0.5f, state?.maxZoomRatio ?: 8f)
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
     * Captures a sharp ISP-stacked photo at working studio resolution (<= 2560px) and optionally
     * applies edge-preserving shadow chroma denoising without stale pre-shutter ghosting.
     */
    suspend fun captureHighResWithBurst(burstCount: Int): Pair<Bitmap, Int> {
        val cap = imageCapture ?: error("Câmera ainda não está pronta.")
        val primaryBmp = suspendCancellableCoroutine<Bitmap> { cont ->
            cap.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = decodeCapturedImageProxy(image, maxLongEdge = 2560)
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

        return withContext(Dispatchers.Default) {
            val w = primaryBmp.width
            val h = primaryBmp.height
            val px = IntArray(w * h)
            primaryBmp.getPixels(px, 0, w, 0, 0, w, h)
            val cleaned = MultiFrameBurstProcessor.denoiseShadowChroma(px, w, h)
            primaryBmp.recycle()
            Bitmap.createBitmap(cleaned, w, h, Bitmap.Config.ARGB_8888) to burstCount
        }
    }

    private fun decodeCapturedImageProxy(image: ImageProxy, maxLongEdge: Int): Bitmap {
        if (image.format == android.graphics.ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: error("Falha ao decodificar JPEG da câmera.")

            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val exifOrient = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            val oriented = applyExifOrRotation(decoded, exifOrient, image.imageInfo.rotationDegrees)
            return capLongEdge(oriented, maxLongEdge)
        }
        val bmp = MultiFrameBurstProcessor.imageProxyToBitmap(image)
        return capLongEdge(bmp, maxLongEdge)
    }

    fun decodeGalleryUriOriented(uri: Uri, maxLongEdge: Int = 2560): Bitmap {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Não foi possível abrir a imagem selecionada.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("Formato de imagem inválido.")

        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val exifOrient = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

        val oriented = applyExifOrRotation(raw, exifOrient, 0)
        return capLongEdge(oriented, maxLongEdge)
    }

    private fun computeInSampleSize(w: Int, h: Int, targetMax: Int): Int {
        var sample = 1
        val longEdge = max(w, h)
        while (longEdge / (sample * 2) >= targetMax) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrRotation(bmp: Bitmap, exifOrientation: Int, fallbackRot: Int): Bitmap {
        val m = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> if (fallbackRot != 0) m.postRotate(fallbackRot.toFloat()) else return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun capLongEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            max(16, (bmp.width * scale).toInt()),
            max(16, (bmp.height * scale).toInt()),
            true
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
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
        runCatching { cameraProvider?.unbindAll() }
        captureExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitListenable(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
