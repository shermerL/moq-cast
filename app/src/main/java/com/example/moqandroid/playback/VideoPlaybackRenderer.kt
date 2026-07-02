package com.example.moqandroid.playback

import android.media.MediaCodec
import android.view.Surface
import com.example.moqandroid.catalog.mediaFormat

class VideoPlaybackRenderer(
    private val status: (PlayerState) -> Unit,
) {
    suspend fun play(
        surface: Surface,
        trackInfo: PlaybackTrackInfo,
        subscriptions: PlaybackSubscriptions,
    ) {
        val video = trackInfo.video
        val codec = MediaCodec.createDecoderByType(video.mime)
        var codecStarted = false

        try {
            codec.configure(video.video.mediaFormat(video.mime, video.avcConfig), surface, null, 0)
            codec.start()
            codecStarted = true

            status(PlayerState.Playing(trackInfo.videoInfo))
            VideoDecoder(status).decodeLoop(
                codec = codec,
                media = subscriptions.media,
                avcConfig = video.avcConfig,
                videoInfo = trackInfo.videoInfo,
                audioClock = subscriptions.audioClock,
            )
            status(PlayerState.StreamEnded)
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
        }
    }
}
