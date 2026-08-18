package com.example.moqandroid.publish.encoder

import android.util.Log
import com.example.moqandroid.publish.PublisherEvent
import com.example.moqandroid.publish.PublisherLifecycleEventSink
import com.example.moqandroid.publish.PublisherState
import com.example.moqandroid.publish.PublishTimeline
import com.example.moqandroid.publish.VideoPublishTimeline
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import com.example.moqandroid.publish.VideoPublishTransition
import com.example.moqandroid.publish.audio.AudioPublishConfig
import com.example.moqandroid.protocol.VideoLayoutEvent
import com.example.moqandroid.protocol.VideoLayoutPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import uniffi.moq.MoqFrame
import uniffi.moq.MoqMediaProducer
import uniffi.moq.MoqTrackProducer
import kotlin.coroutines.coroutineContext

internal class SurfaceVideoEncoder(
    private val source: VideoPublishSource,
    private val media: MoqMediaProducer,
    private val videoLayout: MoqTrackProducer?,
    private val timeline: PublishTimeline,
    private val connectionLabel: String,
    private val lifecycle: PublisherLifecycleEventSink,
) {
    private val attemptPlanner = H264EncoderAttemptPlanner()
    private val layoutTransitions = source.layoutTransitions

    suspend fun run(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: AudioPublishConfig?,
    ) = withContext(Dispatchers.Default) {
        val stats = PublishStatsTracker(connectionLabel, broadcastName)
        var activeConfig = config
        var activeGeneration: Long? = null
        var trackStarted = false
        val videoTimeline = timeline.video()

        try {
            while (coroutineContext.isActive) {
                val nextConfig = runAttempts(
                    config = activeConfig,
                    broadcastName = broadcastName,
                    audioConfig = audioConfig,
                    stats = stats,
                    videoTimeline = videoTimeline,
                    generation = activeGeneration,
                    onEncodingStarted = {
                        if (!trackStarted) {
                            lifecycle.emit(PublisherEvent.TrackStarted(VIDEO_TRACK_NAME))
                            trackStarted = true
                        }
                    },
                ) ?: break
                Log.i(
                    LOG_TAG,
                    "restarting H.264 encoder for screen resize " +
                        "from=${activeConfig.width}x${activeConfig.height} " +
                        "to=${nextConfig.config.width}x${nextConfig.config.height} " +
                        "generation=${nextConfig.generation} track=reused",
                )
                activeConfig = nextConfig.config
                activeGeneration = nextConfig.generation
            }
        } finally {
            if (trackStarted) lifecycle.emit(PublisherEvent.TrackStopped(VIDEO_TRACK_NAME))
        }
    }

    private suspend fun runAttempts(
        config: VideoPublishConfig,
        broadcastName: String,
        audioConfig: AudioPublishConfig?,
        stats: PublishStatsTracker,
        videoTimeline: VideoPublishTimeline,
        generation: Long?,
        onEncodingStarted: () -> Unit,
    ): VideoPublishTransition? {
        var lastError: Throwable? = null
        val attempts = attemptPlanner.attempts(config)
        for ((index, attempt) in attempts.withIndex()) {
            val isFallbackAvailable = index < attempts.lastIndex
            var encodingStarted = false

            try {
                Log.i(
                    LOG_TAG,
                    "Starting H.264 encoder attempt ${index + 1}/${attempts.size} " +
                        "encoder=${attempt.encoderName} profile=${attempt.profileName} " +
                        "width=${attempt.config.width} height=${attempt.config.height} " +
                        "fps=${attempt.config.frameRate} bitrate=${attempt.config.bitrate} " +
                        "policy=${attempt.config.encoderPolicy.storageValue} " +
                        "supportsHigh=${attempt.capability.supportsHigh} " +
                        "supportsBaseline=${attempt.capability.supportsBaseline} " +
                        "supportsFormat=${attempt.capability.supportsRequestedFormat}",
                )
                H264CodecSession.open(attempt).use { encoder ->
                    var sourceAttached = false
                    try {
                        source.attachEncoderSurface(encoder.inputSurface, attempt.config)
                        sourceAttached = true
                        encoder.requestKeyFrame()

                        onEncodingStarted()
                        lifecycle.update(
                            PublisherState.Publishing(
                                relayUrl = connectionLabel,
                                broadcastName = broadcastName,
                                width = attempt.config.width,
                                height = attempt.config.height,
                                bitrate = attempt.config.bitrate,
                                frameRate = attempt.config.frameRate,
                                audioEnabled = audioConfig != null,
                            ),
                        )
                        encodingStarted = true
                        videoTimeline.beginGeneration()
                        return drain(encoder, stats, videoTimeline, attempt.config, generation)
                    } finally {
                        if (sourceAttached) {
                            withContext(NonCancellable) {
                                source.detachEncoderSurface()
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                if (encodingStarted && error !is CancellationException) {
                    lifecycle.emit(PublisherEvent.TrackError(VIDEO_TRACK_NAME, error.message ?: error::class.java.name))
                }
                if (error is CancellationException || encodingStarted || !isFallbackAvailable) throw error

                lastError = error

                Log.w(
                    LOG_TAG,
                    "H.264 encoder configure failed, retrying with fallback " +
                        "width=${attempt.config.width} height=${attempt.config.height} " +
                        "fps=${attempt.config.frameRate} bitrate=${attempt.config.bitrate} " +
                        "profile=${attempt.profileName} policy=${attempt.config.encoderPolicy.storageValue} " +
                        "encoder=${attempt.encoderName}",
                    error,
                )
            }
        }

        throw lastError ?: IllegalStateException("H.264 encoder did not start.")
    }

    private suspend fun drain(
        encoder: H264CodecSession,
        stats: PublishStatsTracker,
        videoTimeline: VideoPublishTimeline,
        activeConfig: VideoPublishConfig,
        generation: Long?,
    ): VideoPublishTransition? {
        val accessUnits = H264AccessUnitAssembler()
        var awaitingKeyFrame = true
        var loggedDiscardedDelta = false
        var outputWasSuspended = false
        var layoutReadySent = generation == null
        var lastTimelineLogUs: Long? = null
        while (coroutineContext.isActive) {
            source.pollFailure()?.let { throw it }
            publishPendingLayoutEvents()
            layoutTransitions?.pollConfigChange()?.let { nextConfig ->
                if (
                    nextConfig.config.width != activeConfig.width ||
                    nextConfig.config.height != activeConfig.height
                ) {
                    return nextConfig
                }
            }
            when (val output = encoder.dequeueOutput(OUTPUT_TIMEOUT_US)) {
                H264CodecOutput.TryAgain -> Unit
                is H264CodecOutput.FormatChanged -> {
                    accessUnits.updateCodecConfig(output.codecConfig)
                    Log.i(
                        LOG_TAG,
                        "H.264 encoder output format " +
                            "size=${output.width ?: "unknown"}x${output.height ?: "unknown"} " +
                            "spsBytes=${output.spsBytes} ppsBytes=${output.ppsBytes} " +
                            "reorderDepth=${output.outputReorderDepth ?: "unknown"} " +
                            "maxBFrames=${output.maxBFrames ?: "unknown"} " +
                            "latencyFrames=${output.latencyFrames ?: "unknown"} " +
                            "catalogFormat=avc3 catalogRotation=unset",
                    )
                }
                is H264CodecOutput.AccessUnit -> {
                    val accessUnit = accessUnits.assemble(
                        payload = output.payload,
                        keyFrame = output.keyFrame,
                    ) ?: continue
                    val sourceTimestampUs = output.presentationTimeUs

                    if (layoutTransitions?.isOutputSuspended() == true) {
                        if (!outputWasSuspended) {
                            Log.i(
                                LOG_TAG,
                                "suspending H.264 output for screen resize " +
                                    "size=${activeConfig.width}x${activeConfig.height}",
                            )
                        }
                        outputWasSuspended = true
                        continue
                    }

                    if (outputWasSuspended) {
                        outputWasSuspended = false
                        awaitingKeyFrame = true
                        loggedDiscardedDelta = false
                        encoder.requestKeyFrame()
                        Log.i(
                            LOG_TAG,
                            "screen resize cancelled, waiting for a fresh H.264 IDR " +
                                "size=${activeConfig.width}x${activeConfig.height}",
                        )
                    }

                    if (awaitingKeyFrame && (!accessUnit.isKeyFrame || !accessUnit.hasDecoderConfiguration)) {
                        if (!loggedDiscardedDelta) {
                            Log.w(
                                LOG_TAG,
                                "discarding H.264 output until the restarted encoder emits SPS, PPS, and IDR",
                            )
                            loggedDiscardedDelta = true
                        }
                        if (accessUnit.isKeyFrame) encoder.requestKeyFrame()
                        continue
                    }

                    if (awaitingKeyFrame) {
                        awaitingKeyFrame = false
                        Log.i(
                            LOG_TAG,
                            "H.264 encoder IDR ready size=${activeConfig.width}x${activeConfig.height} " +
                                "parameterSetsBytes=${accessUnit.parameterSetsBytes} " +
                                "presentationTimeUs=$sourceTimestampUs",
                        )
                    }
                    val timestamp = videoTimeline.map(sourceTimestampUs)
                    val shouldLogTimeline =
                        lastTimelineLogUs == null ||
                            timestamp.sessionElapsedUs - lastTimelineLogUs >= TIMELINE_LOG_INTERVAL_US
                    val writeStartedUs = if (shouldLogTimeline) timeline.elapsedUs() else null
                    media.writeFrame(
                        MoqFrame(
                            payload = accessUnit.payload,
                            timestampUs = timestamp.timestampUs.toULong(),
                        ),
                    )
                    if (writeStartedUs != null) {
                        val writeCompletedUs = timeline.elapsedUs()
                        Log.i(
                            LOG_TAG,
                            "video publish timeline sourcePtsUs=$sourceTimestampUs " +
                                "mediaPtsUs=${timestamp.timestampUs} " +
                                "sessionElapsedUs=${timestamp.sessionElapsedUs} " +
                                "encoderOutputLagUs=${timestamp.sessionElapsedUs - timestamp.timestampUs} " +
                                "writeDurationUs=${writeCompletedUs - writeStartedUs} " +
                                "postWriteLagUs=${writeCompletedUs - timestamp.timestampUs} " +
                                "sourceClock=${if (timestamp.sourceClockMatched) "monotonic" else "anchored"}",
                        )
                        lastTimelineLogUs = timestamp.sessionElapsedUs
                    }
                    if (!layoutReadySent && generation != null) {
                        publishLayoutEvent(
                            VideoLayoutEvent(
                                phase = VideoLayoutPhase.Ready,
                                generation = generation,
                                width = activeConfig.width,
                                height = activeConfig.height,
                                rotation = null,
                            ),
                        )
                        layoutTransitions?.onLayoutReady(generation)
                        layoutReadySent = true
                    }
                    stats.onFrame(accessUnit.payload.size, lifecycle::emit)
                }
            }
        }
        return null
    }

    private fun publishPendingLayoutEvents() {
        val transitions = layoutTransitions ?: return
        while (true) {
            val event = transitions.pollLayoutEvent() ?: return
            publishLayoutEvent(event)
        }
    }

    private fun publishLayoutEvent(event: VideoLayoutEvent) {
        val producer = videoLayout ?: return
        runCatching { producer.writeFrame(MoqFrame(payload = event.encode())) }
            .onSuccess {
                Log.i(
                    LOG_TAG,
                    "video layout ${event.phase.wireValue} generation=${event.generation} " +
                        "target=${event.width}x${event.height} rotation=${event.rotation ?: "none"}",
                )
            }
            .onFailure { error ->
                Log.w(
                    LOG_TAG,
                    "video layout event failed phase=${event.phase.wireValue} " +
                        "generation=${event.generation}",
                    error,
                )
            }
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val VIDEO_TRACK_NAME = "video"
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val TIMELINE_LOG_INTERVAL_US = 5_000_000L
    }
}
