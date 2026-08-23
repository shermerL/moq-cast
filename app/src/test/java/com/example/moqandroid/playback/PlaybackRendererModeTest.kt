package com.example.moqandroid.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRendererModeTest {
    @Test
    fun storedValuesRestoreTheirRenderer() {
        assertEquals(
            PlaybackRendererMode.SurfaceView,
            PlaybackRendererMode.fromStorageValue("surface_view"),
        )
        assertEquals(
            PlaybackRendererMode.TextureView,
            PlaybackRendererMode.fromStorageValue("texture_view"),
        )
    }

    @Test
    fun missingOrUnknownValueUsesSurfaceView() {
        assertEquals(PlaybackRendererMode.SurfaceView, PlaybackRendererMode.fromStorageValue(null))
        assertEquals(PlaybackRendererMode.SurfaceView, PlaybackRendererMode.fromStorageValue("unknown"))
    }
}
