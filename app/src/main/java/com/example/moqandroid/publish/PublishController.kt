package com.example.moqandroid.publish

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import com.example.moqandroid.config.RelayConfig
import com.example.moqandroid.media.codec.CodecSupport
import com.example.moqandroid.publish.screen.ScreenCaptureService
import com.example.moqandroid.publish.screen.ScreenPublishConfig
import com.example.moqandroid.publish.screen.ScreenVideoConfig
import com.example.moqandroid.publish.screen.SystemAudioConfig
import kotlinx.coroutines.flow.StateFlow

class PublishController(private val context: Context) {
    val status: StateFlow<PublishState> = ScreenCaptureService.status

    fun prepare(
        broadcastInput: String,
        includeSystemAudio: Boolean,
        hasRecordAudioPermission: Boolean,
        hasNotificationPermission: Boolean,
    ): PublishPreparation {
        val broadcastName = broadcastInput.trim().trim('/')
        if (broadcastName.isEmpty()) {
            return PublishPreparation(PublishRequest.None, null, "Broadcast name cannot be empty.")
        }

        if (!CodecSupport.hasEncoderFor(MIME_AVC)) {
            return PublishPreparation(
                PublishRequest.None,
                broadcastName,
                "This device has no H.264 encoder.\n${CodecSupport.describeVideoEncoders()}",
            )
        }
        if (includeSystemAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PublishPreparation(
                PublishRequest.None,
                broadcastName,
                "System audio capture requires Android 10+.\nbroadcast=$broadcastName",
            )
        }
        if (includeSystemAudio && !hasRecordAudioPermission) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestRecordAudio,
                broadcastName,
                "Audio permission is required before publishing system audio.\nbroadcast=$broadcastName",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            ScreenCaptureService.prepare()
            return PublishPreparation(
                PublishRequest.RequestNotifications,
                broadcastName,
                "Notification permission is required before screen publish.\nbroadcast=$broadcastName",
            )
        }

        ScreenCaptureService.prepare()
        return PublishPreparation(
            PublishRequest.RequestScreenCapture,
            broadcastName,
            "Requesting screen capture permission ...\nbroadcast=$broadcastName",
        )
    }

    fun start(
        relayConfig: RelayConfig,
        broadcastName: String,
        resultCode: Int,
        resultData: Intent,
        metrics: DisplayMetrics,
        includeSystemAudio: Boolean,
        compatibilityMode: Boolean,
    ) {
        ScreenCaptureService.start(
            context = context,
            relayUrl = relayConfig.relayUrl,
            broadcastName = broadcastName,
            resultCode = resultCode,
            resultData = resultData,
            config = screenPublishConfig(metrics, includeSystemAudio, compatibilityMode),
        )
    }

    fun stop() {
        ScreenCaptureService.stop(context)
    }

    fun fail(reason: String) {
        ScreenCaptureService.fail(reason)
    }

    private fun screenPublishConfig(
        metrics: DisplayMetrics,
        includeSystemAudio: Boolean,
        compatibilityMode: Boolean,
    ): ScreenPublishConfig {
        val (width, height) = scaledVideoSize(metrics.widthPixels, metrics.heightPixels, compatibilityMode)
        return ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = width,
                height = height,
                densityDpi = metrics.densityDpi,
                compatibilityMode = compatibilityMode,
            ),
            audio = if (includeSystemAudio) SystemAudioConfig.Enabled() else SystemAudioConfig.Disabled,
        )
    }

    private fun scaledVideoSize(sourceWidth: Int, sourceHeight: Int, compatibilityMode: Boolean): Pair<Int, Int> {
        val longestEdge = maxOf(sourceWidth, sourceHeight)
        val scale = minOf(MAX_PUBLISH_LONG_EDGE.toFloat() / longestEdge, 1f)
        val alignment = if (compatibilityMode) COMPATIBILITY_ALIGNMENT else DEFAULT_ALIGNMENT
        val width = (sourceWidth * scale).toInt().roundDownTo(alignment).coerceAtLeast(alignment)
        val height = (sourceHeight * scale).toInt().roundDownTo(alignment).coerceAtLeast(alignment)
        return width to height
    }

    private fun Int.roundDownTo(alignment: Int): Int = this - (this % alignment)

    private companion object {
        private const val MIME_AVC = "video/avc"
        private const val MAX_PUBLISH_LONG_EDGE = 1080
        private const val DEFAULT_ALIGNMENT = 2
        private const val COMPATIBILITY_ALIGNMENT = 16
    }
}

data class PublishPreparation(
    val request: PublishRequest,
    val broadcastName: String?,
    val message: String,
)

sealed interface PublishRequest {
    data object None : PublishRequest
    data object RequestRecordAudio : PublishRequest
    data object RequestNotifications : PublishRequest
    data object RequestScreenCapture : PublishRequest
}
