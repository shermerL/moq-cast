package com.example.moqandroid.publish.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

class CodecCapabilityResolver(
    private val codecInfos: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos,
) {
    fun resolveH264Encoder(config: EncoderCapabilityRequest): EncoderCapability {
        val candidates = codecInfos.mapNotNull { codec ->
            if (!codec.isEncoder) return@mapNotNull null

            val capabilities = runCatching { codec.getCapabilitiesForType(MIME_AVC) }.getOrNull()
                ?: return@mapNotNull null
            if (!capabilities.supportsSurfaceInput()) return@mapNotNull null

            EncoderCandidate(
                name = codec.name,
                supportsBaseline = capabilities.supportsProfile(
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                ),
                supportsHigh = capabilities.supportsProfile(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
                supportsFormat = capabilities.supportsFormat(config.width, config.height, config.frameRate),
            )
        }

        val preferred = selectH264EncoderCandidate(candidates, config.profilePreference)
        return EncoderCapability(
            encoderName = preferred?.name ?: "unknown",
            supportsBaseline = preferred?.supportsBaseline == true,
            supportsHigh = preferred?.supportsHigh == true,
            supportsRequestedFormat = preferred?.supportsFormat == true,
            hasSurfaceEncoder = candidates.isNotEmpty(),
        )
    }

    private fun MediaCodecInfo.CodecCapabilities.supportsSurfaceInput(): Boolean {
        return colorFormats.any { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface }
    }

    private fun MediaCodecInfo.CodecCapabilities.supportsProfile(profile: Int): Boolean {
        return profileLevels.any { it.profile == profile }
    }

    private fun MediaCodecInfo.CodecCapabilities.supportsFormat(width: Int, height: Int, frameRate: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        }
        return runCatching { isFormatSupported(format) }.getOrDefault(false)
    }

    private companion object {
        private const val MIME_AVC = "video/avc"
    }
}

data class EncoderCapabilityRequest(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val profilePreference: H264ProfilePreference?,
)

data class EncoderCapability(
    val encoderName: String,
    val supportsBaseline: Boolean,
    val supportsHigh: Boolean,
    val supportsRequestedFormat: Boolean,
    val hasSurfaceEncoder: Boolean,
)

internal data class EncoderCandidate(
    val name: String,
    val supportsBaseline: Boolean,
    val supportsHigh: Boolean,
    val supportsFormat: Boolean,
)

internal fun selectH264EncoderCandidate(
    candidates: List<EncoderCandidate>,
    preference: H264ProfilePreference?,
): EncoderCandidate? {
    return candidates.maxByOrNull { candidate ->
        val formatScore = if (candidate.supportsFormat) 100 else 0
        val profileScore = when (preference) {
            H264ProfilePreference.Baseline -> if (candidate.supportsBaseline) 20 else 0
            H264ProfilePreference.High -> when {
                candidate.supportsHigh -> 20
                candidate.supportsBaseline -> 10
                else -> 0
            }
            null -> 0
        }
        formatScore + profileScore
    }
}
