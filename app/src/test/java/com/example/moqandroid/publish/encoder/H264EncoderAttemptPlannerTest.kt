package com.example.moqandroid.publish.encoder

import com.example.moqandroid.publish.VideoPublishConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264EncoderAttemptPlannerTest {
    @Test
    fun fallsBackFromHighToBaselineOnSelectedEncoder() {
        val planner = H264EncoderAttemptPlanner { request ->
            if (request.profilePreference == null) {
                capability(name = "default-encoder", baseline = true)
            } else {
                capability(name = "baseline-encoder", baseline = true)
            }
        }

        val attempts = planner.attempts(
            config(profile = H264ProfilePreference.High),
        )

        assertEquals("baseline", attempts[0].profileName)
        assertEquals(H264ProfilePreference.Baseline.profile, attempts[0].profile)
        assertEquals("baseline-encoder", attempts[0].encoderName)
        assertEquals("default", attempts[1].profileName)
        assertNull(attempts[1].profile)
        assertEquals("default-encoder", attempts[1].encoderName)
    }

    @Test
    fun resolvesLegacyFallbackAgainstAlignedDimensions() {
        val requests = mutableListOf<EncoderCapabilityRequest>()
        val planner = H264EncoderAttemptPlanner { request ->
            requests += request
            capability(name = "encoder-${request.width}x${request.height}", baseline = true)
        }

        val attempts = planner.attempts(
            config(width = 918, height = 1920, policy = VideoEncoderPolicy.LegacyH264),
        )

        assertEquals(listOf(918, 896), requests.map { it.width })
        assertEquals(listOf(H264ProfilePreference.Baseline, null), requests.map { it.profilePreference })
        assertEquals(896, attempts[1].config.width)
        assertEquals("encoder-896x1920", attempts[1].encoderName)
    }

    private fun config(
        width: Int = 1280,
        height: Int = 720,
        profile: H264ProfilePreference = H264ProfilePreference.High,
        policy: VideoEncoderPolicy = VideoEncoderPolicy.Default,
    ): VideoPublishConfig {
        return VideoPublishConfig(
            width = width,
            height = height,
            encoderPolicy = policy,
            h264ProfilePreference = profile,
        )
    }

    private fun capability(
        name: String,
        baseline: Boolean = false,
        high: Boolean = false,
    ): EncoderCapability {
        return EncoderCapability(
            encoderName = name,
            supportsBaseline = baseline,
            supportsHigh = high,
            supportsRequestedFormat = true,
            hasSurfaceEncoder = true,
        )
    }
}
