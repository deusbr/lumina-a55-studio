package dev.deusbr.luminaa55.hardware

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import dev.deusbr.luminaa55.model.CameraHardwareProfile
import dev.deusbr.luminaa55.model.CameraLensInfo
import dev.deusbr.luminaa55.model.LensOption

object A55CameraInspector {

    fun inspect(context: Context, extensionsManager: ExtensionsManager? = null): CameraHardwareProfile {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<CameraLensInfo>()

        val backCandidates = mutableListOf<Pair<CameraLensInfo, Float>>()

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facingInt = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
            val facing = when (facingInt) {
                CameraCharacteristics.LENS_FACING_BACK -> "Traseira"
                CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                else -> "Externa"
            }

            val focals = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()).toList()
            val apertures = (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()).toList()
            val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                else -> "EXTERNAL"
            }
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            val hasOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

            val primaryFocal = focals.firstOrNull() ?: 4.0f
            val info = CameraLensInfo(
                cameraId = id,
                facing = facing,
                focalLengthsMm = focals,
                apertures = apertures,
                hardwareLevel = hwLevel,
                hasOis = hasOis,
                supportsRaw = supportsRaw,
                maxDigitalZoom = maxZoom,
                mappedRole = if (facingInt == CameraCharacteristics.LENS_FACING_FRONT) LensOption.FRONT_32MP else null
            )

            if (facingInt == CameraCharacteristics.LENS_FACING_BACK) {
                backCandidates.add(info to primaryFocal)
            } else {
                lenses.add(info)
            }
        }

        // Map rear sensors heuristically by focal length and aperture (A55: UW ~1.8-2.2mm, Main ~4.5-5.5mm, Macro ~2.6-3.2mm f/2.4)
        val sortedBack = backCandidates.sortedBy { it.second }
        for ((idx, pair) in sortedBack.withIndex()) {
            val (info, focal) = pair
            val aperture = info.apertures.firstOrNull() ?: 2.0f
            val role = when {
                idx == 0 && sortedBack.size >= 2 && focal < 3.0f && aperture <= 2.35f -> LensOption.ULTRA_WIDE
                aperture >= 2.35f && focal < 4.0f -> LensOption.MACRO
                info.hasOis || focal >= 4.0f -> LensOption.MAIN_IMX906
                idx == 0 -> LensOption.MAIN_IMX906
                else -> LensOption.ULTRA_WIDE
            }
            lenses.add(info.copy(mappedRole = role))
        }

        val supportedExtensions = mutableListOf<String>()
        if (extensionsManager != null) {
            val checks = listOf(
                "HDR" to ExtensionMode.HDR,
                "NIGHT" to ExtensionMode.NIGHT,
                "BOKEH" to ExtensionMode.BOKEH,
                "FACE_RETOUCH" to ExtensionMode.FACE_RETOUCH,
                "AUTO" to ExtensionMode.AUTO
            )
            for ((name, mode) in checks) {
                try {
                    if (extensionsManager.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, mode)) {
                        supportedExtensions.add(name)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        val anyRaw = lenses.any { it.supportsRaw }
        return CameraHardwareProfile(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            socHardware = "${Build.HARDWARE} / API ${Build.VERSION.SDK_INT}",
            lenses = lenses,
            extensionsSupported = supportedExtensions,
            rawBlockedByFirmware = !anyRaw,
            supportsHardwareRuntimeShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
    }
}
