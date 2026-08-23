package com.example.moqandroid.publish.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.moqandroid.publish.PublishTimeline
import uniffi.moq.MoqAudioProducer

internal class MicrophoneAudioCapture(
    override val config: AudioPublishConfig,
    private val logTag: String,
) : AudioPublishSource {
    @SuppressLint("MissingPermission")
    override suspend fun capture(producer: MoqAudioProducer, timeline: PublishTimeline) {
        PcmAudioCapture(
            producer = producer,
            config = config,
            timeline = timeline,
            sourceLabel = "microphone",
            logTag = logTag,
            createRecord = { audioFormat, bufferSize ->
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            },
        ).run()
    }
}
