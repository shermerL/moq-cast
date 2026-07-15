package com.example.moqandroid.publish.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.util.Size
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import kotlin.math.abs

data class CameraPublishConfig(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val sensorOrientation: Int,
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
}

object CameraPublishCapabilityResolver {
    fun resolve(context: Context): CameraPublishConfig {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No rear camera is available.")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Rear camera does not expose a stream configuration map.")
        val outputSizes = runCatching { streamMap.getOutputSizes(MediaCodec::class.java) }
            .getOrNull()
            ?.filter { it.width > 0 && it.height > 0 }
            .orEmpty()
        val size = chooseDefaultSize(outputSizes)
            ?: error("Rear camera does not support MediaCodec surface output.")

        return CameraPublishConfig(
            cameraId = cameraId,
            width = size.width,
            height = size.height,
            frameRate = DEFAULT_FRAME_RATE,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
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
