package com.example.moqandroid.publish.encoder

import com.example.moqandroid.publish.VideoPublishConfig

class H264EncoderAttemptPlanner(
    private val resolveCapability: (EncoderCapabilityRequest) -> EncoderCapability =
        CodecCapabilityResolver()::resolveH264Encoder,
) {
    fun attempts(config: VideoPublishConfig): List<EncoderAttempt> {
        return when (config.encoderPolicy) {
            VideoEncoderPolicy.Default -> defaultAttempts(
                config,
                preferredCapability = resolve(config, config.h264ProfilePreference),
                fallbackCapability = resolve(config, null),
            )

            VideoEncoderPolicy.LegacyH264 -> legacyAttempts(config)
        }
    }

    private fun legacyAttempts(config: VideoPublishConfig): List<EncoderAttempt> {
        val primaryCapability = resolve(config, H264ProfilePreference.Baseline)
        val fallbackConfig = config.alignForFallback()
        val fallbackCapability = resolve(fallbackConfig, null)
        return listOf(
            EncoderAttempt(
                config = config,
                profile = H264ProfilePreference.Baseline.profile,
                profileName = "baseline",
                encoderName = primaryCapability.encoderName,
                capability = primaryCapability,
            ),
            EncoderAttempt(
                config = fallbackConfig,
                profile = null,
                profileName = "default",
                encoderName = fallbackCapability.encoderName,
                capability = fallbackCapability,
                isFallback = true,
            ),
        )
    }

    private fun resolve(
        config: VideoPublishConfig,
        profilePreference: H264ProfilePreference?,
    ): EncoderCapability {
        return resolveCapability(
            EncoderCapabilityRequest(
                width = config.width,
                height = config.height,
                frameRate = config.frameRate,
                profilePreference = profilePreference,
            ),
        )
    }

    private fun defaultAttempts(
        config: VideoPublishConfig,
        preferredCapability: EncoderCapability,
        fallbackCapability: EncoderCapability,
    ): List<EncoderAttempt> {
        val preferred = config.h264ProfilePreference.supportedBy(preferredCapability)
        val attempts = mutableListOf<EncoderAttempt>()

        if (preferred != null) {
            attempts += EncoderAttempt(
                config = config,
                profile = preferred.profile,
                profileName = preferred.profileName,
                encoderName = preferredCapability.encoderName,
                capability = preferredCapability,
            )
        }

        attempts += EncoderAttempt(
            config = config,
            profile = null,
            profileName = "default",
            encoderName = fallbackCapability.encoderName,
            capability = fallbackCapability,
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
