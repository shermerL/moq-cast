package com.example.moqandroid.publish.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

data class CameraPublishConfig(
    val cameraId: String,
    val lensFacing: CameraLensFacing,
    val width: Int,
    val height: Int,
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
        val size = chooseDefaultSize(outputSizes)
            ?: error("${lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera does not support MediaCodec surface output.")
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
            width = size.width,
            height = size.height,
            frameRate = DEFAULT_FRAME_RATE,
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

    private fun chooseDefaultSize(sizes: List<Size>): Size? {
        return sizes.minWithOrNull(
            compareBy<Size>(
                { aspectRatioDistance(it) },
                { abs(it.width.toLong() * it.height - TARGET_AREA) },
            ),
        )
    }

    private fun aspectRatioDistance(size: Size): Long {
        return abs(size.width.toLong() * TARGET_HEIGHT - size.height.toLong() * TARGET_WIDTH)
    }

    private const val TARGET_WIDTH = 1280
    private const val TARGET_HEIGHT = 720
    private const val TARGET_AREA = TARGET_WIDTH.toLong() * TARGET_HEIGHT
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
