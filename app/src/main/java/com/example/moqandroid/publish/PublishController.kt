package com.example.moqandroid.publish

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import com.example.moqandroid.config.RelayConfig
import com.example.moqandroid.media.codec.CodecSupport
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import com.example.moqandroid.publish.screen.ScreenCaptureService
import com.example.moqandroid.publish.screen.ScreenPublishConfig
import com.example.moqandroid.publish.screen.ScreenVideoConfig
import com.example.moqandroid.publish.screen.SystemAudioConfig
import com.example.moqandroid.publish.screen.withScreenSize
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
        encoderPolicy: VideoEncoderPolicy,
        h264ProfilePreference: H264ProfilePreference,
    ) {
        ScreenCaptureService.start(
            context = context,
            relayUrl = relayConfig.relayUrl,
            broadcastName = broadcastName,
            resultCode = resultCode,
            resultData = resultData,
            config = screenPublishConfig(metrics, includeSystemAudio, encoderPolicy, h264ProfilePreference),
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
        encoderPolicy: VideoEncoderPolicy,
        h264ProfilePreference: H264ProfilePreference,
    ): ScreenPublishConfig {
        val video = VideoPublishConfig(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            encoderPolicy = encoderPolicy,
            h264ProfilePreference = h264ProfilePreference,
        ).withScreenSize(metrics.widthPixels, metrics.heightPixels)
        return ScreenPublishConfig(
            video = ScreenVideoConfig(
                width = video.width,
                height = video.height,
                densityDpi = metrics.densityDpi,
                encoderPolicy = encoderPolicy,
                h264ProfilePreference = h264ProfilePreference,
            ),
            audio = if (includeSystemAudio) SystemAudioConfig.Enabled() else SystemAudioConfig.Disabled,
        )
    }

    private companion object {
        private const val MIME_AVC = "video/avc"
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
