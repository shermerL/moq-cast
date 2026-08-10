package com.example.moqandroid.publish.file

internal data class AvcTimingInspection(
    val hasBSlice: Boolean,
    val hasPtsRegression: Boolean,
) {
    val lacksCompositionTiming: Boolean
        get() = hasBSlice && !hasPtsRegression
}

internal enum class AvcSampleInspection {
    NoBSlice,
    BSlice,
    Unrecognized,
}

internal object AvcSampleTimingInspector {
    fun inspect(sample: ByteArray): AvcSampleInspection {
        val annexB = annexBNalUnits(sample)
        if (annexB != null) return inspectNalUnits(sample, annexB)

        for (lengthSize in intArrayOf(4, 2, 1)) {
            val avcc = avccNalUnits(sample, lengthSize) ?: continue
            return inspectNalUnits(sample, avcc)
        }
        return AvcSampleInspection.Unrecognized
    }

    private fun inspectNalUnits(
        sample: ByteArray,
        nalUnits: List<IntRange>,
    ): AvcSampleInspection {
        var foundVcl = false
        for (range in nalUnits) {
            if (range.isEmpty()) continue
            when (sample[range.first].toInt() and NAL_TYPE_MASK) {
                NAL_NON_IDR_SLICE,
                NAL_IDR_SLICE,
                -> {
                    foundVcl = true
                    val reader = RbspBitReader(sample, range.first + 1, range.last + 1)
                    val firstMacroblock = reader.readUnsignedExpGolomb()
                    val sliceType = reader.readUnsignedExpGolomb()
                    if (firstMacroblock == null || sliceType == null) {
                        return AvcSampleInspection.Unrecognized
                    }
                    if (sliceType % SLICE_TYPE_COUNT == B_SLICE_TYPE) {
                        return AvcSampleInspection.BSlice
                    }
                }
                NAL_PARTITION_A,
                NAL_PARTITION_B,
                NAL_PARTITION_C,
                -> return AvcSampleInspection.Unrecognized
            }
        }
        return if (foundVcl) AvcSampleInspection.NoBSlice else AvcSampleInspection.Unrecognized
    }

    private fun annexBNalUnits(sample: ByteArray): List<IntRange>? {
        val firstStart = findStartCode(sample, 0) ?: return null
        if (sample.copyOfRange(0, firstStart.first).any { it.toInt() != 0 }) return null

        val units = mutableListOf<IntRange>()
        var startCode = firstStart
        while (true) {
            val nalStart = startCode.first + startCode.second
            val next = findStartCode(sample, nalStart)
            val nalEnd = next?.first ?: sample.size
            if (nalStart < nalEnd) units += nalStart until nalEnd
            startCode = next ?: break
        }
        return units.takeIf { it.isNotEmpty() }
    }

    private fun findStartCode(sample: ByteArray, fromIndex: Int): Pair<Int, Int>? {
        var index = fromIndex
        while (index + 2 < sample.size) {
            if (sample[index].toInt() == 0 && sample[index + 1].toInt() == 0) {
                if (sample[index + 2].toInt() == 1) return index to 3
                if (index + 3 < sample.size && sample[index + 2].toInt() == 0 && sample[index + 3].toInt() == 1) {
                    return index to 4
                }
            }
            index++
        }
        return null
    }

    private fun avccNalUnits(sample: ByteArray, lengthSize: Int): List<IntRange>? {
        val units = mutableListOf<IntRange>()
        var offset = 0
        while (offset < sample.size) {
            if (sample.size - offset < lengthSize) return null
            var nalSize = 0
            repeat(lengthSize) {
                nalSize = (nalSize shl 8) or (sample[offset + it].toInt() and 0xff)
            }
            offset += lengthSize
            if (nalSize <= 0 || nalSize > sample.size - offset) return null
            units += offset until offset + nalSize
            offset += nalSize
        }
        return units.takeIf { it.isNotEmpty() }
    }

    private const val NAL_TYPE_MASK = 0x1f
    private const val NAL_NON_IDR_SLICE = 1
    private const val NAL_PARTITION_A = 2
    private const val NAL_PARTITION_B = 3
    private const val NAL_PARTITION_C = 4
    private const val NAL_IDR_SLICE = 5
    private const val B_SLICE_TYPE = 1
    private const val SLICE_TYPE_COUNT = 5
}

private class RbspBitReader(
    data: ByteArray,
    start: Int,
    end: Int,
) {
    private val rbsp = buildList {
        var zeroCount = 0
        for (index in start until end) {
            val value = data[index].toInt() and 0xff
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0
                continue
            }
            add(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
    }
    private var bitOffset = 0

    fun readUnsignedExpGolomb(): Int? {
        var leadingZeros = 0
        while (true) {
            when (readBit()) {
                0 -> {
                    leadingZeros++
                    if (leadingZeros > MAX_EXP_GOLOMB_BITS) return null
                }
                1 -> break
                null -> return null
            }
        }
        var suffix = 0
        repeat(leadingZeros) {
            suffix = (suffix shl 1) or (readBit() ?: return null)
        }
        return ((1 shl leadingZeros) - 1) + suffix
    }

    private fun readBit(): Int? {
        if (bitOffset >= rbsp.size * Byte.SIZE_BITS) return null
        val value = rbsp[bitOffset / Byte.SIZE_BITS]
        val bit = (value ushr (7 - bitOffset % Byte.SIZE_BITS)) and 1
        bitOffset++
        return bit
    }

    private companion object {
        const val MAX_EXP_GOLOMB_BITS = 30
    }
}
