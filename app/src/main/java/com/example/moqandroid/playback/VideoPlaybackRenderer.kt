package com.example.moqandroid.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.moqandroid.BuildConfig
import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.catalog.mediaFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import uniffi.moq.MoqContainer

class VideoPlaybackRenderer(
    private val status: (PlayerState) -> Unit,
) {
    suspend fun play(
        surface: Surface,
        trackInfo: PlaybackTrackInfo,
        subscriptions: PlaybackSubscriptions,
        videoTrackUpdates: ReceiveChannel<PlaybackVideoTrackUpdate>,
    ) {
        var activeVideo = trackInfo.video
        var activeVideoInfo = trackInfo.videoInfo
        var announcedPlaying = false
        var transitionId = 0
        var initialFrameTransitionId: Int? = null
        val pendingRenderedFrame = AtomicReference<PendingRenderedFrame?>(null)

        transitionId += 1
        waitForInitialLayout(activeVideoInfo, transitionId)

        while (true) {
            val decoderTransitionId = transitionId
            val codec = MediaCodec.createDecoderByType(activeVideo.mime)
            val cmafPlayback = activeVideo.video.container is MoqContainer.Cmaf
            val playbackDiagnostics = VideoPlaybackDiagnostics(
                trackName = activeVideo.name,
                codecName = codec.name,
                enabled = BuildConfig.DEBUG,
                cmafDetailsEnabled = cmafPlayback && BuildConfig.DEBUG,
            )
            var codecStarted = false
            val renderCallbacksEnabled = AtomicBoolean(true)
            val decoderCapabilities = runCatching {
                codec.codecInfo
                    .getCapabilitiesForType(activeVideo.mime)
            }.getOrNull()
            val adaptivePlaybackSupported = decoderCapabilities
                ?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback) == true
            val lowLatencyPlayback =
                activeVideo.video.container !is MoqContainer.Cmaf &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                    decoderCapabilities
                        ?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true
            val adaptiveMaxDimension = if (adaptivePlaybackSupported) {
                listOfNotNull(
                    activeVideoInfo.displayWidth?.takeIf { it > 0 },
                    activeVideoInfo.displayHeight?.takeIf { it > 0 },
                    activeVideo.video.coded?.width?.toInt()?.takeIf { it > 0 },
                    activeVideo.video.coded?.height?.toInt()?.takeIf { it > 0 },
                ).maxOrNull()
            } else {
                null
            }
            val adaptivePlayback = adaptivePlaybackSupported && adaptiveMaxDimension != null

            val decodeResult = try {
                Log.i(
                    "MoqAndroid",
                    "rotationTrace=$decoderTransitionId decoder starting " +
                        "name=${codec.name} track=${activeVideo.name} codec=${activeVideo.video.codec} " +
                        "display=${activeVideoInfo.displayWidth ?: "unknown"}x${activeVideoInfo.displayHeight ?: "unknown"} " +
                        "adaptive=$adaptivePlayback maxDimension=${adaptiveMaxDimension ?: "none"} " +
                        "lowLatency=$lowLatencyPlayback",
                )
                codec.configure(
                    activeVideo.video.mediaFormat(
                        mime = activeVideo.mime,
                        avcConfig = activeVideo.avcConfig,
                        adaptiveMaxDimension = adaptiveMaxDimension,
                        lowLatency = lowLatencyPlayback,
                        rotationDegrees = activeVideoInfo.rotationDegrees,
                    ),
                    surface,
                    null,
                    0,
                )
                codec.setOnFrameRenderedListener(
                    { _, presentationTimeUs, nanoTime ->
                        if (renderCallbacksEnabled.get()) {
                            playbackDiagnostics.onSurfaceFrameRendered(presentationTimeUs)
                            val pending = pendingRenderedFrame.get()
                            if (
                                pending != null &&
                                presentationTimeUs >= pending.presentationTimeUs &&
                                pendingRenderedFrame.compareAndSet(pending, null)
                            ) {
                                Log.i(
                                    "MoqAndroid",
                                    "rotationTrace=${pending.transitionId} decoder first frame rendered " +
                                        "presentationTimeUs=$presentationTimeUs nanoTime=$nanoTime",
                                )
                                status(PlayerState.VideoFrameRendered(pending.transitionId))
                            }
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                codec.start()
                codecStarted = true

                if (!announcedPlaying) {
                    status(PlayerState.Playing(activeVideoInfo))
                    announcedPlaying = true
                }
                VideoDecoder(status).decodeLoop(
                    config = VideoDecodeConfig(
                        codec = codec,
                        media = subscriptions.media,
                        avcConfig = activeVideo.avcConfig,
                        videoInfo = activeVideoInfo,
                        videoTrackUpdates = videoTrackUpdates,
                        audioClock = subscriptions.audioClock,
                        allowAdaptiveSizeChanges = adaptivePlayback,
                        drainWhileSourceWaits = cmafPlayback,
                        diagnostics = playbackDiagnostics,
                        initialFrameTransitionId = initialFrameTransitionId.also {
                            initialFrameTransitionId = null
                        },
                    ),
                    callbacks = VideoDecodeCallbacks(
                        nextTransitionId = {
                            transitionId += 1
                            transitionId
                        },
                        onVideoFormatTransition = { nextVideoInfo, nextTransitionId ->
                            val orientationChanged = waitForLayoutIfOrientationChanged(
                                activeVideoInfo,
                                nextVideoInfo,
                                nextTransitionId,
                                coverVideo = false,
                            )
                            activeVideoInfo = nextVideoInfo
                            orientationChanged
                        },
                        onTransitionFrameQueued = { queuedTransitionId, presentationTimeUs ->
                            pendingRenderedFrame.set(
                                PendingRenderedFrame(queuedTransitionId, presentationTimeUs),
                            )
                        },
                    ),
                )
            } finally {
                renderCallbacksEnabled.set(false)
                Log.i("MoqAndroid", "rotationTrace=$decoderTransitionId decoder stopping")
                if (codecStarted) runCatching { codec.stop() }
                codec.release()
                Log.i("MoqAndroid", "rotationTrace=$decoderTransitionId decoder released")
            }

            when (val result = decodeResult) {
                VideoDecodeResult.StreamEnded -> {
                    status(PlayerState.StreamEnded)
                    return
                }
                is VideoDecodeResult.RestartDecoder -> {
                    val nextVideoInfo = result.videoInfo
                    transitionId += 1
                    waitForLayoutIfOrientationChanged(
                        activeVideoInfo,
                        nextVideoInfo,
                        transitionId,
                        coverVideo = true,
                    )
                    result.update?.let { activeVideo = it.video }
                    activeVideoInfo = nextVideoInfo
                    initialFrameTransitionId = transitionId
                }
            }
        }
    }

    private suspend fun waitForLayoutIfOrientationChanged(
        activeVideoInfo: PlayableVideoInfo,
        nextVideoInfo: PlayableVideoInfo,
        transitionId: Int,
        coverVideo: Boolean,
    ): Boolean {
        val orientationChanged = activeVideoInfo.isLandscape() != nextVideoInfo.isLandscape()
        if (!orientationChanged) {
            status(PlayerState.VideoSizeChanged(nextVideoInfo, transitionId, coverVideo = coverVideo))
            return false
        }

        val layoutReady = CompletableDeferred<Unit>()
        Log.i(
            "MoqAndroid",
            "rotationTrace=$transitionId waiting for layout " +
                "display=${nextVideoInfo.displayWidth ?: "unknown"}x${nextVideoInfo.displayHeight ?: "unknown"}",
        )
        status(
            PlayerState.VideoSizeChanged(
                videoInfo = nextVideoInfo,
                transitionId = transitionId,
                coverVideo = coverVideo,
                onLayoutReady = { layoutReady.complete(Unit) },
            ),
        )
        val layoutCompleted = withTimeoutOrNull(PLAYBACK_ORIENTATION_LAYOUT_TIMEOUT_MS) {
            layoutReady.await()
            true
        } ?: false
        Log.i(
            "MoqAndroid",
            "rotationTrace=$transitionId layout wait completed ready=$layoutCompleted",
        )
        return true
    }

    private suspend fun waitForInitialLayout(videoInfo: PlayableVideoInfo, transitionId: Int) {
        val layoutReady = CompletableDeferred<Unit>()
        Log.i(
            "MoqAndroid",
            "rotationTrace=$transitionId waiting for initial presentation " +
                "display=${videoInfo.displayWidth ?: "unknown"}x${videoInfo.displayHeight ?: "unknown"} " +
                "rotation=${videoInfo.rotationDegrees} flip=${videoInfo.flip}",
        )
        status(
            PlayerState.VideoSizeChanged(
                videoInfo = videoInfo,
                transitionId = transitionId,
                onLayoutReady = { layoutReady.complete(Unit) },
            ),
        )
        val layoutCompleted = withTimeoutOrNull(PLAYBACK_ORIENTATION_LAYOUT_TIMEOUT_MS) {
            layoutReady.await()
            true
        } ?: false
        Log.i(
            "MoqAndroid",
            "rotationTrace=$transitionId initial presentation ready=$layoutCompleted",
        )
    }

    private fun PlayableVideoInfo.isLandscape(): Boolean? {
        val width = displayWidth ?: return null
        val height = displayHeight ?: return null
        if (width <= 0 || height <= 0) return null
        return width > height
    }

}

private const val PLAYBACK_ORIENTATION_LAYOUT_TIMEOUT_MS = 1000L

private data class PendingRenderedFrame(
    val transitionId: Int,
    val presentationTimeUs: Long,
)
