package com.example.moqandroid.publish.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecCapabilityResolverTest {
    @Test
    fun selectsFormatCompatibleEncoderBeforeUnsupportedPreferredProfile() {
        val candidates = listOf(
            candidate(name = "high-wrong-size", high = true, format = false),
            candidate(name = "baseline-right-size", baseline = true, format = true),
        )

        val selected = selectH264EncoderCandidate(candidates, H264ProfilePreference.High)

        assertEquals("baseline-right-size", selected?.name)
    }

    @Test
    fun prefersRequestedProfileAmongFormatCompatibleEncoders() {
        val candidates = listOf(
            candidate(name = "baseline", baseline = true, format = true),
            candidate(name = "high", high = true, format = true),
        )

        val selected = selectH264EncoderCandidate(candidates, H264ProfilePreference.High)

        assertEquals("high", selected?.name)
    }

    @Test
    fun keepsCodecListOrderWhenCandidatesHaveEqualScores() {
        val candidates = listOf(
            candidate(name = "platform-default", baseline = true, format = true),
            candidate(name = "secondary", baseline = true, format = true),
        )

        val selected = selectH264EncoderCandidate(candidates, H264ProfilePreference.Baseline)

        assertEquals("platform-default", selected?.name)
    }

    @Test
    fun selectsPlatformOrderForProfileFreeFallback() {
        val candidates = listOf(
            candidate(name = "platform-default", baseline = true, format = true),
            candidate(name = "high", high = true, format = true),
        )

        val selected = selectH264EncoderCandidate(candidates, null)

        assertEquals("platform-default", selected?.name)
    }

    private fun candidate(
        name: String,
        baseline: Boolean = false,
        high: Boolean = false,
        format: Boolean,
    ): EncoderCandidate {
        return EncoderCandidate(
            name = name,
            supportsBaseline = baseline,
            supportsHigh = high,
            supportsFormat = format,
        )
    }
}
