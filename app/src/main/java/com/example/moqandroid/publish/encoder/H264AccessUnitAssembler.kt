package com.example.moqandroid.publish.encoder

import com.example.moqandroid.media.parseAvcConfig

internal data class H264AccessUnit(
    val payload: ByteArray,
    val isKeyFrame: Boolean,
    val hasDecoderConfiguration: Boolean,
    val parameterSetsBytes: Int,
)

internal class H264AccessUnitAssembler {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun updateCodecConfig(payload: ByteArray?) {
        if (payload == null || payload.isEmpty()) return
        payload.parseAvcConfig()?.let { config ->
            sps = config.sps
            pps = config.pps
            return
        }
        inspectAndCache(payload.toAnnexB())
    }

    fun assemble(
        payload: ByteArray,
        keyFrame: Boolean,
    ): H264AccessUnit? {
        if (payload.isEmpty()) return null
        payload.parseAvcConfig()?.let { config ->
            sps = config.sps
            pps = config.pps
            return null
        }

        val annexB = payload.toAnnexB()
        val flags = inspectAndCache(annexB)
        if (flags and FLAG_VCL == 0) return null

        val isKeyFrame = keyFrame || flags and FLAG_IDR != 0
        val parameterSetsPrefix = parameterSetsPrefix()
        val hasCompleteParameterSets = flags and FLAG_SPS != 0 && flags and FLAG_PPS != 0
        return H264AccessUnit(
            payload = if (isKeyFrame && !hasCompleteParameterSets && parameterSetsPrefix != null) {
                parameterSetsPrefix + annexB
            } else {
                annexB
            },
            isKeyFrame = isKeyFrame,
            hasDecoderConfiguration = parameterSetsPrefix != null,
            parameterSetsBytes = parameterSetsPrefix?.size ?: 0,
        )
    }

    private fun inspectAndCache(payload: ByteArray): Int {
        var flags = 0
        var discoveredSps: ByteArray? = null
        var discoveredPps: ByteArray? = null
        payload.forEachH264NalUnit { type, start, end ->
            when {
                type == NAL_IDR -> flags = flags or FLAG_VCL or FLAG_IDR
                type in NAL_NON_IDR..NAL_IDR -> flags = flags or FLAG_VCL
                type == NAL_SPS -> {
                    flags = flags or FLAG_SPS
                    discoveredSps = payload.appendNalUnit(discoveredSps, start, end)
                }
                type == NAL_PPS -> {
                    flags = flags or FLAG_PPS
                    discoveredPps = payload.appendNalUnit(discoveredPps, start, end)
                }
            }
        }
        if (discoveredSps != null) sps = discoveredSps
        if (discoveredPps != null) pps = discoveredPps
        return flags
    }

    private fun parameterSetsPrefix(): ByteArray? {
        val cachedSps = sps ?: return null
        val cachedPps = pps ?: return null
        return cachedSps + cachedPps
    }

    private fun ByteArray.appendNalUnit(existing: ByteArray?, start: Int, end: Int): ByteArray {
        val prefixSize = existing?.size ?: 0
        return ByteArray(prefixSize + end - start).also { output ->
            existing?.copyInto(output)
            copyInto(output, destinationOffset = prefixSize, startIndex = start, endIndex = end)
        }
    }

    private companion object {
        private const val NAL_NON_IDR = 1
        private const val NAL_IDR = 5
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val FLAG_VCL = 1
        private const val FLAG_IDR = 1 shl 1
        private const val FLAG_SPS = 1 shl 2
        private const val FLAG_PPS = 1 shl 3
    }
}
