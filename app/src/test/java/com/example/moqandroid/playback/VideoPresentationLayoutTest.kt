package com.example.moqandroid.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPresentationLayoutTest {
    @Test
    fun rotatedDecoderOutputUsesFinalPortraitSurfaceDimensions() {
        val layout = calculateVideoPresentationLayout(
            containerWidth = 1080,
            containerHeight = 2400,
            displayWidth = 720,
            displayHeight = 1280,
        )

        assertEquals(
            VideoPresentationLayout(
                displayWidth = 1080,
                displayHeight = 1920,
                surfaceWidth = 1080,
                surfaceHeight = 1920,
            ),
            layout,
        )
    }

    @Test
    fun unrotatedVideoKeepsDisplayAndSurfaceDimensionsTogether() {
        val layout = calculateVideoPresentationLayout(
            containerWidth = 2400,
            containerHeight = 1080,
            displayWidth = 1280,
            displayHeight = 720,
        )

        assertEquals(
            VideoPresentationLayout(
                displayWidth = 1920,
                displayHeight = 1080,
                surfaceWidth = 1920,
                surfaceHeight = 1080,
            ),
            layout,
        )
    }
}
