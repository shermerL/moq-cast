package com.example.moqandroid.publish.screen

import com.example.moqandroid.publish.VideoPublishConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPublishConfigTest {
    @Test
    fun legacyFallbackSizeDoesNotLookLikeAChangedScreenTarget() {
        val requested = config(width = 912, height = 1920)
        val activeFallback = config(width = 896, height = 1920)

        assertTrue(activeFallback.width != requested.width)
        assertFalse(
            requiresScreenLayoutTransition(
                nextConfig = requested,
                requestedConfig = requested,
                rotationAxesChanged = false,
            ),
        )
    }

    @Test
    fun changedTargetOrRotationAxesRequiresLayoutTransition() {
        val requested = config(width = 912, height = 1920)

        assertTrue(
            requiresScreenLayoutTransition(
                nextConfig = config(width = 1920, height = 912),
                requestedConfig = requested,
                rotationAxesChanged = false,
            ),
        )
        assertTrue(
            requiresScreenLayoutTransition(
                nextConfig = requested,
                requestedConfig = requested,
                rotationAxesChanged = true,
            ),
        )
    }

    private fun config(width: Int, height: Int): VideoPublishConfig {
        return VideoPublishConfig(width = width, height = height)
    }
}
