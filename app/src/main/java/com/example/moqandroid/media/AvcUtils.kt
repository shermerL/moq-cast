package com.example.moqandroid.media

import java.io.ByteArrayOutputStream
import uniffi.moq.MoqFrame

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

fun MoqFrame.payloadForDecoder(avcConfig: AvcConfig?): ByteArray {
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
        if (length <= 0 || offset + length > data.size) return data

        out.writeStartCode()
        out.write(data, offset, length)
        offset += length
    }

    return if (offset == data.size) out.toByteArray() else data
}

private fun ByteArray.hasStartCode(): Boolean {
    return size >= 4 && this[0].toInt() == 0 && this[1].toInt() == 0 &&
        (this[2].toInt() == 1 || (this[2].toInt() == 0 && this[3].toInt() == 1))
}

private fun ByteArray.readU16(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArrayOutputStream.writeStartCode() {
    write(START_CODE)
}

private val START_CODE = byteArrayOf(0, 0, 0, 1)
