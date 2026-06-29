package com.example.moqandroid.media.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

object CodecSupport {
    private val codecs: Array<MediaCodecInfo>
        get() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

    fun hasDecoderFor(format: MediaFormat): Boolean {
        return codecs.any { codec -> !codec.isEncoder && codec.supports(format) }
    }

    fun hasEncoderFor(mime: String): Boolean {
        return codecs.any { codec ->
            codec.isEncoder && runCatching {
                codec.getCapabilitiesForType(mime)
                true
            }.getOrDefault(false)
        }
    }

    fun describeVideoDecoders(): String {
        return describeCodecs(isEncoder = false, videoOnly = true)
    }

    fun describeVideoEncoders(): String {
        return describeCodecs(isEncoder = true, videoOnly = true)
    }

    private fun describeCodecs(isEncoder: Boolean, videoOnly: Boolean): String {
        return codecs
            .filter { codec -> codec.isEncoder == isEncoder }
            .mapNotNull { codec ->
                val types = codec.supportedTypes.filter { type ->
                    !videoOnly || type.startsWith("video/")
                }
                if (types.isEmpty()) null else "${codec.name}: ${types.joinToString()}"
            }
            .joinToString(separator = "\n")
            .ifBlank { "none" }
    }

    private fun MediaCodecInfo.supports(format: MediaFormat): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        return runCatching { getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
    }
}
