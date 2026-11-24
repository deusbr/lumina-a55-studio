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
import androidx.camera.camera2.interop.Camera2Interop
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

data class RawCaptureOutput(
    val fullResBitmap: Bitmap,
    val exifBytes: ByteArray?,
    val framesDenoised: Int
)

class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var activeCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

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

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )

        // Request maximum ISP quality (demosaic, noise reduction, edge sharpness, aberration correction)
        runCatching {
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, 100.toByte())
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
        }
        val capture = captureBuilder.build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val now = System.currentTimeMillis()
                if (now - lastHintTimestampMs > 1150L) {
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
     * Captures at 100% full sensor resolution (no 2560px downscale) and applies shadow chroma
     * cleaning safely across strips when needed.
     */
    suspend fun captureFullRes(): RawCaptureOutput {
        val cap = imageCapture ?: error("Câmera ainda não está pronta.")
        return suspendCancellableCoroutine { cont ->
            cap.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val (bmp, jpegBytes) = decodeFullResImageProxy(image)
                        cont.resume(RawCaptureOutput(fullResBitmap = bmp, exifBytes = jpegBytes, framesDenoised = 1))
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
    }

    /**
     * Applies edge-preserving shadow chroma cleanup row-by-row when context/mode calls for denoise
     * without allocating duplicate multi-megabyte full-image arrays.
     */
    suspend fun applyFullResDenoiseIfRequested(raw: Bitmap, burstStrength: Int): Pair<Bitmap, Int> {
        if (burstStrength <= 1) return raw to 1
        return withContext(Dispatchers.Default) {
            val w = raw.width
            val h = raw.height
            if (w * h <= 4_200_000) {
                val px = IntArray(w * h)
                raw.getPixels(px, 0, w, 0, 0, w, h)
                val cleaned = MultiFrameBurstProcessor.denoiseShadowChroma(px, w, h)
                Bitmap.createBitmap(cleaned, w, h, Bitmap.Config.ARGB_8888) to burstStrength
            } else {
                // Memory-safe strip denoise for 12MP / 32MP / 50MP photos
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val strip = 512
                var y0 = 0
                while (y0 < h) {
                    val yEnd = minOf(h, y0 + strip)
                    val readTop = maxOf(0, y0 - 1)
                    val readBot = minOf(h, yEnd + 1)
                    val readH = readBot - readTop
                    val buf = IntArray(w * readH)
                    raw.getPixels(buf, 0, w, 0, readTop, w, readH)
                    val cleaned = MultiFrameBurstProcessor.denoiseShadowChroma(buf, w, readH)
                    val skipTop = y0 - readTop
                    out.setPixels(cleaned, skipTop * w, w, 0, y0, w, yEnd - y0)
                    y0 += strip
                }
                out to burstStrength
            }
        }
    }

    private fun decodeFullResImageProxy(image: ImageProxy): Pair<Bitmap, ByteArray?> {
        if (image.format == android.graphics.ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: error("Falha ao decodificar JPEG da câmera.")

            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val exifOrient = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            val oriented = applyExifOrRotation(decoded, exifOrient, image.imageInfo.rotationDegrees)
            return oriented to bytes
        }
        val bmp = MultiFrameBurstProcessor.imageProxyToBitmap(image)
        return bmp to null
    }

    fun decodeGalleryUriFullRes(uri: Uri): Pair<Bitmap, ByteArray?> {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Não foi possível abrir a imagem selecionada.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxSafetyEdge = 8200
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSafetyEdge) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("Formato de imagem inválido.")

        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val exifOrient = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL

        return applyExifOrRotation(raw, exifOrient, 0) to bytes
    }

    fun createUiPreviewBitmap(src: Bitmap, maxEdge: Int = 1800): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            max(16, (src.width * scale).toInt()),
            max(16, (src.height * scale).toInt()),
            true
        )
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

    suspend fun saveBitmapToGallery(bitmap: Bitmap, sourceExifBytes: ByteArray?, conceptTag: String): Uri = withContext(Dispatchers.IO) {
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, buf)
            stream.write(buf.toByteArray())
        } ?: error("Falha ao gravar JPEG na galeria.")

        // Preserve original camera EXIF metadata cleanly on the upright processed JPEG
        if (sourceExifBytes != null) {
            runCatching {
                val srcExif = ExifInterface(ByteArrayInputStream(sourceExifBytes))
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val dstExif = ExifInterface(pfd.fileDescriptor)
                    val tags = listOf(
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_EXPOSURE_TIME,
                        ExifInterface.TAG_F_NUMBER,
                        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                        ExifInterface.TAG_WHITE_BALANCE,
                        ExifInterface.TAG_FLASH
                    )
                    for (t in tags) {
                        srcExif.getAttribute(t)?.let { dstExif.setAttribute(t, it) }
                    }
                    dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, "Lumina A55 Studio v1.1 ($conceptTag)")
                    dstExif.saveAttributes()
                }
            }
        }

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
