package com.example.moqandroid.publish.audio

import com.example.moqandroid.publish.PublishTimeline
import uniffi.moq.MoqAudioProducer

internal interface AudioPublishSource {
    val config: AudioPublishConfig

    suspend fun capture(producer: MoqAudioProducer, timeline: PublishTimeline)
}
