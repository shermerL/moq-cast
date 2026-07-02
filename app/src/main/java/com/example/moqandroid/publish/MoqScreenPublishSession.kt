package com.example.moqandroid.publish

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import uniffi.moq.MoqBroadcastProducer
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer

class MoqScreenPublishSession(
    private val relayUrl: String,
    private val status: (PublishState) -> Unit,
) {
    suspend fun publish(
        projection: MediaProjection,
        broadcastName: String,
        config: ScreenPublishConfig,
    ) {
        status(PublishState.Preparing)
        val projectionCallback = registerProjectionCallback(projection, currentCoroutineContext()[Job])

        try {
            MoqBroadcastProducer().use { broadcast ->
                val media = broadcast.publishMediaStream("avc3")
                val audio = (config.audio as? SystemAudioConfig.Enabled)?.let { audioConfig ->
                    Log.i(
                        LOG_TAG,
                        "publishing audio track=0 codec=opus encoder=moq-native input=s16 " +
                            "sampleRate=${audioConfig.sampleRate} channels=${audioConfig.channelCount} " +
                            "bitrate=${audioConfig.bitrate} frameDurationMs=${audioConfig.frameDurationMs}",
                    )
                    broadcast.publishAudio("0", audioConfig.encoderInput(), audioConfig.encoderOutput())
                }
                MoqOriginProducer().use { origin ->
                    MoqClient().use { client ->
                        client.setPublish(origin)
                        status(PublishState.Connecting(relayUrl, broadcastName))
                        client.connect(relayUrl).use { session ->
                            try {
                                origin.publish(broadcastName, broadcast)
                                coroutineScope {
                                    val audioJob = audio?.let { producer ->
                                        val audioConfig = config.audio as SystemAudioConfig.Enabled
                                        launch {
                                            runCatching {
                                                SystemAudioCapture(projection, producer, audioConfig, LOG_TAG).run()
                                            }.onFailure { error ->
                                                if (error !is CancellationException) {
                                                    Log.w(LOG_TAG, "system audio capture failed", error)
                                                    status(PublishState.AudioFailed(error.message ?: error::class.java.name))
                                                }
                                            }.also {
                                                runCatching { producer.finish() }
                                            }
                                        }
                                    }

                                    try {
                                        ScreenVideoEncoder(projection, media, relayUrl, status).run(config.video, broadcastName, config.audio)
                                    } finally {
                                        audioJob?.cancel()
                                    }
                                }
                            } finally {
                                runCatching { broadcast.finish() }
                                session.shutdown()
                            }
                        }
                    }
                }
            }
        } finally {
            projection.unregisterCallbackSafe(projectionCallback)
            projection.stopSafe()
        }

        status(PublishState.Stopped)
    }

    private fun registerProjectionCallback(projection: MediaProjection, job: Job?): MediaProjection.Callback {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(LOG_TAG, "media projection stopped")
                job?.cancel(CancellationException("MediaProjection stopped by Android."))
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    private fun MediaProjection.unregisterCallbackSafe(callback: MediaProjection.Callback) {
        runCatching { unregisterCallback(callback) }
            .onFailure { Log.w(LOG_TAG, "failed to unregister media projection callback", it) }
    }

    private fun MediaProjection.stopSafe() {
        runCatching { stop() }
            .onFailure { Log.w(LOG_TAG, "failed to stop media projection", it) }
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
    }
}
