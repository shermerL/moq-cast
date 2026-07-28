package com.example.moqandroid.publish.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.util.Size
import android.view.Display
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishPresentation
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import kotlin.math.abs

enum class CameraLensFacing(
    val storageValue: String,
    val cameraCharacteristicsValue: Int,
    val statusLabel: String,
) {
    Back(
        storageValue = "back",
        cameraCharacteristicsValue = CameraCharacteristics.LENS_FACING_BACK,
        statusLabel = "rear",
    ),
    Front(
        storageValue = "front",
        cameraCharacteristicsValue = CameraCharacteristics.LENS_FACING_FRONT,
        statusLabel = "front",
    );

    companion object {
        fun fromStorageValue(value: String?): CameraLensFacing {
            return entries.firstOrNull { it.storageValue == value } ?: Back
        }
    }
}

enum class CameraQualityPreset(
    val storageValue: String,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetBitrate: Int,
    val frameRateCandidates: List<Int>,
) {
    Auto(
        storageValue = "auto",
        targetWidth = 1280,
        targetHeight = 720,
        targetBitrate = 4_000_000,
        frameRateCandidates = listOf(30, 24),
    ),
    Quality(
        storageValue = "quality",
        targetWidth = 1920,
        targetHeight = 1080,
        targetBitrate = 8_000_000,
        frameRateCandidates = listOf(30, 24),
    );

    companion object {
        fun fromStorageValue(value: String?): CameraQualityPreset {
            return entries.firstOrNull { it.storageValue == value } ?: Auto
        }
    }
}

data class CameraPublishConfig(
    val cameraId: String,
    val lensFacing: CameraLensFacing,
    val qualityPreset: CameraQualityPreset,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val sensorOrientation: Int,
    val displayRotationDegrees: Int,
    val presentationRotationDegrees: Int,
) {
    fun encoderConfig(
        encoderPolicy: VideoEncoderPolicy,
        h264ProfilePreference: H264ProfilePreference,
    ): VideoPublishConfig {
        return VideoPublishConfig(
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate,
            encoderPolicy = encoderPolicy,
            h264ProfilePreference = h264ProfilePreference,
        )
    }

    fun presentation(): VideoPublishPresentation {
        val swapDimensions = presentationRotationDegrees == 90 || presentationRotationDegrees == 270
        return VideoPublishPresentation(
            displayWidth = if (swapDimensions) height else width,
            displayHeight = if (swapDimensions) width else height,
            rotationDegrees = presentationRotationDegrees,
        )
    }
}

object CameraPublishCapabilityResolver {
    fun resolve(
        context: Context,
        lensFacing: CameraLensFacing = CameraLensFacing.Back,
        qualityPreset: CameraQualityPreset = CameraQualityPreset.Auto,
    ): CameraPublishConfig {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                lensFacing.cameraCharacteristicsValue
        } ?: error("No ${lensFacing.statusLabel} camera is available.")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("${lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera does not expose a stream configuration map.")
        val outputSizes = runCatching { streamMap.getOutputSizes(MediaCodec::class.java) }
            .getOrNull()
            ?.filter { it.width > 0 && it.height > 0 }
            .orEmpty()
        val size = chooseSize(outputSizes, qualityPreset)
            ?: error("${lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera does not support MediaCodec surface output.")
        val frameRate = chooseFrameRate(characteristics, streamMap, size, qualityPreset)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacingValue = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val displayRotationDegrees = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?.let(::surfaceRotationDegrees)
            ?: 0

        return CameraPublishConfig(
            cameraId = cameraId,
            lensFacing = lensFacing,
            qualityPreset = qualityPreset,
            width = size.width,
            height = size.height,
            bitrate = qualityPreset.targetBitrate,
            frameRate = frameRate,
            sensorOrientation = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
            //for test
//            presentationRotationDegrees = 0
            presentationRotationDegrees = cameraPresentationRotation(
                sensorOrientationDegrees = sensorOrientation,
                deviceOrientationDegrees = displayRotationDegrees,
                frontFacing = lensFacingValue == CameraCharacteristics.LENS_FACING_FRONT,
            ),
        )
    }

    private fun chooseSize(sizes: List<Size>, preset: CameraQualityPreset): Size? {
        return sizes.minWithOrNull(
            compareBy<Size>(
                { aspectRatioDistance(it, preset) },
                {
                    abs(
                        it.width.toLong() * it.height -
                            preset.targetWidth.toLong() * preset.targetHeight,
                    )
                },
            ),
        )
    }

    private fun chooseFrameRate(
        characteristics: CameraCharacteristics,
        streamMap: StreamConfigurationMap,
        size: Size,
        preset: CameraQualityPreset,
    ): Int {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        val minFrameDuration = runCatching {
            streamMap.getOutputMinFrameDuration(MediaCodec::class.java, size)
        }.getOrDefault(0L)
        val maximumStreamFrameRate = if (minFrameDuration > 0L) {
            (NANOS_PER_SECOND / minFrameDuration).toInt()
        } else {
            Int.MAX_VALUE
        }

        return preset.frameRateCandidates.firstOrNull { frameRate ->
            frameRate <= maximumStreamFrameRate && ranges.any { frameRate in it }
        } ?: ranges
            .asSequence()
            .map { it.upper }
            .filter { it <= maximumStreamFrameRate }
            .maxOrNull()
            ?.coerceAtMost(preset.frameRateCandidates.first())
            ?: DEFAULT_FRAME_RATE.coerceAtMost(maximumStreamFrameRate)
    }

    private fun aspectRatioDistance(size: Size, preset: CameraQualityPreset): Long {
        return abs(
            size.width.toLong() * preset.targetHeight -
                size.height.toLong() * preset.targetWidth,
        )
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val DEFAULT_FRAME_RATE = 30
}

internal fun cameraPresentationRotation(
    sensorOrientationDegrees: Int,
    deviceOrientationDegrees: Int,
    frontFacing: Boolean,
): Int {
    val sign = if (frontFacing) 1 else -1
    return Math.floorMod(sensorOrientationDegrees - deviceOrientationDegrees * sign, 360)
}

internal fun surfaceRotationDegrees(rotation: Int): Int {
    return when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> error("Unsupported display rotation $rotation.")
    }
}
