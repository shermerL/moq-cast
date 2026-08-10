package com.example.moqandroid.media

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import uniffi.moq.MoqMediaFrame

data class AvcConfig(
    val lengthSize: Int,
    val sps: ByteArray,
    val pps: ByteArray,
)

fun ByteArray.parseAvcConfig(): AvcConfig? {
    if (size < 7 || this[0].toInt() != 1) return null

    val lengthSize = (this[4].toInt() and 0x03) + 1
    var offset = 6

    val spsCount = this[5].toInt() and 0x1f
    val spsOut = ByteArrayOutputStream()
    repeat(spsCount) {
        if (offset + 2 > size) return null
        val len = readU16(offset)
        offset += 2
        if (offset + len > size) return null
        spsOut.writeStartCode()
        spsOut.write(this, offset, len)
        offset += len
    }

    if (offset >= size) return null
    val ppsCount = this[offset].toInt() and 0xff
    offset += 1
    val ppsOut = ByteArrayOutputStream()
    repeat(ppsCount) {
        if (offset + 2 > size) return null
        val len = readU16(offset)
        offset += 2
        if (offset + len > size) return null
        ppsOut.writeStartCode()
        ppsOut.write(this, offset, len)
        offset += len
    }

    return AvcConfig(lengthSize, spsOut.toByteArray(), ppsOut.toByteArray())
}

fun MoqMediaFrame.payloadForDecoder(avcConfig: AvcConfig?): ByteArray {
    val data = payload
    if (avcConfig == null || data.hasStartCode()) return data

    val out = ByteArrayOutputStream(data.size + 16)
    var offset = 0
    while (offset + avcConfig.lengthSize <= data.size) {
        var length = 0
        repeat(avcConfig.lengthSize) {
            length = (length shl 8) or (data[offset + it].toInt() and 0xff)
        }
        offset += avcConfig.lengthSize
        if (length <= 0 || offset + length > data.size) {
            logAvccConversionFailure(
                payloadSize = data.size,
                lengthSize = avcConfig.lengthSize,
                offset = offset,
                nalLength = length,
            )
            return data
        }

        out.writeStartCode()
        out.write(data, offset, length)
        offset += length
    }

    if (offset != data.size) {
        logAvccConversionFailure(
            payloadSize = data.size,
            lengthSize = avcConfig.lengthSize,
            offset = offset,
            nalLength = null,
        )
        return data
    }
    return out.toByteArray()
}

internal data class AvcNalSummary(
    val types: String,
    val hasIdr: Boolean,
)

internal fun ByteArray.summarizeAvcNalUnits(): AvcNalSummary {
    val types = mutableListOf<Int>()
    var offset = findStartCode(0)
    while (offset >= 0 && types.size < MAX_REPORTED_NAL_UNITS) {
        val startCodeSize = if (this[offset + 2].toInt() == 1) 3 else 4
        val nalOffset = offset + startCodeSize
        if (nalOffset >= size) break
        types += this[nalOffset].toInt() and AVC_NAL_TYPE_MASK
        offset = findStartCode(nalOffset + 1)
    }
    return AvcNalSummary(
        types = if (types.isEmpty()) "none" else types.joinToString(","),
        hasIdr = AVC_NAL_TYPE_IDR in types,
    )
}

private fun ByteArray.hasStartCode(): Boolean {
    return size >= 4 && this[0].toInt() == 0 && this[1].toInt() == 0 &&
        (this[2].toInt() == 1 || (this[2].toInt() == 0 && this[3].toInt() == 1))
}

private fun ByteArray.findStartCode(fromIndex: Int): Int {
    var index = fromIndex.coerceAtLeast(0)
    while (index + 2 < size) {
        if (
            this[index].toInt() == 0 &&
            this[index + 1].toInt() == 0 &&
            (
                this[index + 2].toInt() == 1 ||
                    (
                        index + 3 < size &&
                            this[index + 2].toInt() == 0 &&
                            this[index + 3].toInt() == 1
                        )
                )
        ) {
            return index
        }
        index += 1
    }
    return -1
}

private fun logAvccConversionFailure(
    payloadSize: Int,
    lengthSize: Int,
    offset: Int,
    nalLength: Int?,
) {
    val failureCount = avccConversionFailures.incrementAndGet()
    if (failureCount > AVCC_FAILURE_LOG_LIMIT && failureCount % AVCC_FAILURE_LOG_INTERVAL != 0) return
    Log.w(
        LOG_TAG,
        "AVCC conversion failed count=$failureCount payloadBytes=$payloadSize " +
            "lengthSize=$lengthSize offset=$offset nalLength=${nalLength ?: "trailing-bytes"}; " +
            "forwarding original payload",
    )
}

private fun ByteArray.readU16(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArrayOutputStream.writeStartCode() {
    write(START_CODE)
}

private val START_CODE = byteArrayOf(0, 0, 0, 1)
private val avccConversionFailures = AtomicInteger()
private const val LOG_TAG = "MoqAndroid"
private const val AVC_NAL_TYPE_MASK = 0x1f
private const val AVC_NAL_TYPE_IDR = 5
private const val MAX_REPORTED_NAL_UNITS = 16
private const val AVCC_FAILURE_LOG_LIMIT = 5
private const val AVCC_FAILURE_LOG_INTERVAL = 100
