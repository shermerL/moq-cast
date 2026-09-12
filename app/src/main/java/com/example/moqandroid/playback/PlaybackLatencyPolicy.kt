package com.example.moqandroid.playback

// Chosen once per Watch. This is subscription freshness, not presentation delay.
internal enum class PlaybackLatencyPolicy(val maxAgeMs: ULong) {
    VideoOnly(0uL),
    AudioVideo(80uL);

    companion object {
        fun forInitialTracks(hasPlayableAudio: Boolean): PlaybackLatencyPolicy =
            if (hasPlayableAudio) AudioVideo else VideoOnly
    }
}
