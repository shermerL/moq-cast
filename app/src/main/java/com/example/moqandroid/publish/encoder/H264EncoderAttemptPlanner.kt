package com.example.moqandroid.publish.encoder

import com.example.moqandroid.publish.VideoPublishConfig

class H264EncoderAttemptPlanner(
    private val capabilityResolver: CodecCapabilityResolver = CodecCapabilityResolver(),
) {
    fun attempts(config: VideoPublishConfig): List<EncoderAttempt> {
        val capability = capabilityResolver.resolveH264Encoder(
            EncoderCapabilityRequest(
                width = config.width,
                height = config.height,
                frameRate = config.frameRate,
            ),
        )
        return when (config.encoderPolicy) {
            VideoEncoderPolicy.Default -> defaultAttempts(config, capability)

            VideoEncoderPolicy.LegacyH264 -> listOf(
                EncoderAttempt(
                    config = config,
                    profile = H264ProfilePreference.Baseline.profile,
                    profileName = "baseline",
                    encoderName = capability.encoderName,
                    capability = capability,
                ),
                EncoderAttempt(
                    config = config.alignForFallback(),
                    profile = null,
                    profileName = "default",
                    encoderName = capability.encoderName,
                    capability = capability,
                    isFallback = true,
                ),
            )
        }
    }

    private fun defaultAttempts(
        config: VideoPublishConfig,
        capability: EncoderCapability,
    ): List<EncoderAttempt> {
        val preferred = config.h264ProfilePreference.supportedBy(capability)
        val attempts = mutableListOf<EncoderAttempt>()

        if (preferred != null) {
            attempts += EncoderAttempt(
                config = config,
                profile = preferred.profile,
                profileName = preferred.profileName,
                encoderName = capability.encoderName,
                capability = capability,
            )
        }

        attempts += EncoderAttempt(
            config = config,
            profile = null,
            profileName = "default",
            encoderName = capability.encoderName,
            capability = capability,
            isFallback = attempts.isNotEmpty(),
        )

        return attempts
    }

    private fun H264ProfilePreference.supportedBy(capability: EncoderCapability): H264ProfilePreference? {
        return when (this) {
            H264ProfilePreference.Baseline -> if (capability.supportsBaseline) this else null
            H264ProfilePreference.High -> when {
                capability.supportsHigh -> this
                capability.supportsBaseline -> H264ProfilePreference.Baseline
                else -> null
            }
        }
    }

    private fun VideoPublishConfig.alignForFallback(): VideoPublishConfig {
        return copy(
            width = width.roundDownTo(FALLBACK_ALIGNMENT).coerceAtLeast(FALLBACK_ALIGNMENT),
            height = height.roundDownTo(FALLBACK_ALIGNMENT).coerceAtLeast(FALLBACK_ALIGNMENT),
        )
    }

    private fun Int.roundDownTo(alignment: Int): Int = this - (this % alignment)

    private companion object {
        private const val FALLBACK_ALIGNMENT = 32
    }
}

data class EncoderAttempt(
    val config: VideoPublishConfig,
    val profile: Int?,
    val profileName: String,
    val encoderName: String,
    val capability: EncoderCapability,
    val isFallback: Boolean = false,
)
