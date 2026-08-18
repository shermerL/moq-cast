package com.example.moqandroid.publish.encoder

private val START_CODE = byteArrayOf(0, 0, 0, 1)

fun ByteArray.toAnnexB(): ByteArray {
    if (hasStartCode()) return this
    return convertLengthPrefixedToAnnexB() ?: this
}

fun ByteArray.hasStartCode(): Boolean {
    return size >= 4 && this[0].toInt() == 0 && this[1].toInt() == 0 &&
        (this[2].toInt() == 1 || (this[2].toInt() == 0 && this[3].toInt() == 1))
}

internal inline fun ByteArray.forEachH264NalUnit(block: (type: Int, start: Int, end: Int) -> Unit) {
    var offset = findStartCode(0)
    while (offset >= 0) {
        val nalStart = if (offset + 2 < size && this[offset + 2].toInt() == 1) offset + 3 else offset + 4
        val nextOffset = findStartCode(nalStart)
        val nalEnd = if (nextOffset >= 0) nextOffset else size
        if (nalStart < nalEnd) block(this[nalStart].toInt() and NAL_TYPE_MASK, offset, nalEnd)
        offset = nextOffset
    }
}

@PublishedApi
internal fun ByteArray.findStartCode(fromIndex: Int): Int {
    var index = fromIndex
    while (index + 3 < size) {
        if (this[index].toInt() == 0 && this[index + 1].toInt() == 0) {
            if (this[index + 2].toInt() == 1) return index
            if (index + 3 < size && this[index + 2].toInt() == 0 && this[index + 3].toInt() == 1) return index
        }
        index += 1
    }
    return -1
}

private fun ByteArray.convertLengthPrefixedToAnnexB(): ByteArray? {
    val output = ByteArray(size)
    var offset = 0
    while (offset + 4 <= size) {
        val length = ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
        offset += 4
        if (length <= 0 || offset + length > size) return null

        START_CODE.copyInto(output, destinationOffset = offset - 4)
        copyInto(output, destinationOffset = offset, startIndex = offset, endIndex = offset + length)
        offset += length
    }

    if (offset != size || output.isEmpty()) return null
    return output
}

@PublishedApi
internal const val NAL_TYPE_MASK = 0x1f
