package com.example.moqandroid.publish.file

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class CmafStreamReader(private val input: InputStream) {
    suspend fun stream(
        beforeFragment: suspend (timestampUs: Long) -> Unit,
        write: (ByteArray) -> Unit,
    ) {
        var timescales = emptyMap<Long, Long>()
        var sawMoov = false
        var sawFragment = false
        var awaitingMdat = false
        var pendingMoof: ByteArray? = null
        var pendingMoofWritten = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val header = input.readBoxHeader() ?: break
            when (header.type) {
                "moov" -> {
                    val payload = input.readMetadataPayload(header)
                    timescales = CmafTimingParser.trackTimescales(payload)
                    sawMoov = true
                    write(header.bytes + payload)
                }

                "moof" -> {
                    check(sawMoov) { "CMAF fragment appeared before the moov init segment." }
                    check(!awaitingMdat) { "CMAF fragment is missing its mdat payload." }
                    val payload = input.readMetadataPayload(header)
                    beforeFragment(CmafTimingParser.fragmentTimestampUs(payload, timescales))
                    pendingMoof = header.bytes + payload
                    pendingMoofWritten = false
                    sawFragment = true
                    awaitingMdat = true
                }

                "mdat" -> {
                    check(awaitingMdat) { "CMAF mdat appeared without a preceding moof." }
                    val moof = checkNotNull(pendingMoof)
                    val payloadSize = header.payloadSize
                    if (
                        !pendingMoofWritten &&
                        payloadSize != null &&
                        moof.size + header.bytes.size + payloadSize <= MAX_COALESCED_FRAGMENT_BYTES
                    ) {
                        val payload = ByteArray(payloadSize.toInt()).also(input::readFullyOrThrow)
                        write(moof + header.bytes + payload)
                    } else {
                        if (!pendingMoofWritten) write(moof)
                        write(header.bytes)
                        input.streamPayload(payloadSize, write)
                    }
                    pendingMoof = null
                    pendingMoofWritten = false
                    awaitingMdat = false
                }

                else -> {
                    if (!pendingMoofWritten) {
                        pendingMoof?.let(write)
                        pendingMoofWritten = pendingMoof != null
                    }
                    write(header.bytes)
                    input.streamPayload(header.payloadSize, write)
                }
            }
        }

        check(sawMoov) { "CMAF file has no moov init segment." }
        check(sawFragment) { "CMAF file has no media fragments." }
        check(!awaitingMdat) { "CMAF file ended before the fragment mdat payload." }
    }
}

internal class CmafPublishPacer(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var firstTimestampUs: Long? = null
    private var firstRealtimeNs = 0L

    suspend fun await(timestampUs: Long) {
        val first = firstTimestampUs
        if (first == null) {
            firstTimestampUs = timestampUs
            firstRealtimeNs = nanoTime()
            return
        }

        val relativeUs = (timestampUs - first).coerceAtLeast(0)
        val relativeNs = Math.multiplyExact(relativeUs, NANOS_PER_MICROSECOND)
        val targetNs = Math.addExact(firstRealtimeNs, relativeNs)
        while (true) {
            val remainingNs = targetNs - nanoTime()
            if (remainingNs <= 0) return
            val delayMs = ((remainingNs + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                .coerceAtMost(MAX_DELAY_SLICE_MS)
            kotlinx.coroutines.delay(delayMs)
        }
    }

    private companion object {
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_DELAY_SLICE_MS = 250L
    }
}

private data class TopLevelBoxHeader(
    val type: String,
    val bytes: ByteArray,
    val payloadSize: Long?,
)

private fun InputStream.readBoxHeader(): TopLevelBoxHeader? {
    val sizeBytes = ByteArray(4)
    val first = read()
    if (first < 0) return null
    sizeBytes[0] = first.toByte()
    readFullyOrThrow(sizeBytes, 1)

    val typeBytes = ByteArray(4)
    readFullyOrThrow(typeBytes)
    val size32 = readUInt32(sizeBytes, 0)
    val header = ByteArrayOutputStream().apply {
        write(sizeBytes)
        write(typeBytes)
    }
    val totalSize = when (size32) {
        0L -> null
        1L -> {
            val extendedSize = ByteArray(8)
            readFullyOrThrow(extendedSize)
            header.write(extendedSize)
            readUInt64(extendedSize, 0)
        }
        else -> size32
    }
    val headerBytes = header.toByteArray()
    if (totalSize != null) {
        require(totalSize >= headerBytes.size) { "Invalid MP4 box size for ${typeBytes.asType()}." }
    }
    return TopLevelBoxHeader(
        type = typeBytes.asType(),
        bytes = headerBytes,
        payloadSize = totalSize?.minus(headerBytes.size),
    )
}

private fun InputStream.readMetadataPayload(header: TopLevelBoxHeader): ByteArray {
    val size = requireNotNull(header.payloadSize) { "${header.type} cannot extend to end of file." }
    require(size <= MAX_METADATA_BOX_BYTES) { "${header.type} is too large to inspect safely." }
    return ByteArray(size.toInt()).also(::readFullyOrThrow)
}

private suspend fun InputStream.streamPayload(payloadSize: Long?, write: (ByteArray) -> Unit) {
    val buffer = ByteArray(STREAM_CHUNK_BYTES)
    var remaining = payloadSize
    while (remaining == null || remaining > 0) {
        currentCoroutineContext().ensureActive()
        val requested = remaining?.coerceAtMost(buffer.size.toLong())?.toInt() ?: buffer.size
        val read = read(buffer, 0, requested)
        if (read < 0) {
            check(remaining == null) { "MP4 box ended before its declared size." }
            return
        }
        if (read == 0) continue
        write(if (read == buffer.size) buffer else buffer.copyOf(read))
        remaining = remaining?.minus(read)
    }
}

private object CmafTimingParser {
    fun trackTimescales(moovPayload: ByteArray): Map<Long, Long> {
        val result = buildMap {
            for (trak in boxes(moovPayload, 0, moovPayload.size).filter { it.type == "trak" }) {
                val children = boxes(moovPayload, trak.payloadStart, trak.end)
                val tkhd = children.firstOrNull { it.type == "tkhd" }
                    ?: error("CMAF trak has no tkhd box.")
                val mdia = children.firstOrNull { it.type == "mdia" }
                    ?: error("CMAF trak has no mdia box.")
                val mdhd = boxes(moovPayload, mdia.payloadStart, mdia.end)
                    .firstOrNull { it.type == "mdhd" }
                    ?: error("CMAF mdia has no mdhd box.")

                val trackId = readFullBoxTimeField(moovPayload, tkhd, version0Offset = 12, version1Offset = 20)
                val timescale = readFullBoxTimeField(moovPayload, mdhd, version0Offset = 12, version1Offset = 20)
                require(timescale > 0) { "CMAF track $trackId has an invalid timescale." }
                put(trackId, timescale)
            }
        }
        check(result.isNotEmpty()) { "CMAF moov has no media tracks." }
        return result
    }

    fun fragmentTimestampUs(moofPayload: ByteArray, timescales: Map<Long, Long>): Long {
        val timestamps = boxes(moofPayload, 0, moofPayload.size)
            .filter { it.type == "traf" }
            .map { traf ->
                val children = boxes(moofPayload, traf.payloadStart, traf.end)
                val tfhd = children.firstOrNull { it.type == "tfhd" }
                    ?: error("CMAF traf has no tfhd box.")
                val tfdt = children.firstOrNull { it.type == "tfdt" }
                    ?: error("CMAF traf has no tfdt box.")
                val trackId = readUInt32(moofPayload, tfhd.payloadStart + FULL_BOX_HEADER_BYTES)
                val timescale = timescales[trackId]
                    ?: error("CMAF fragment references unknown track $trackId.")
                val version = moofPayload.byteAt(tfdt.payloadStart).toInt()
                val baseTimeOffset = tfdt.payloadStart + FULL_BOX_HEADER_BYTES
                val baseTime = when (version) {
                    0 -> readUInt32(moofPayload, baseTimeOffset)
                    1 -> readUInt64(moofPayload, baseTimeOffset)
                    else -> error("Unsupported tfdt version $version.")
                }
                timestampUs(baseTime, timescale)
            }
        return timestamps.minOrNull() ?: error("CMAF moof has no track fragments.")
    }

    private fun readFullBoxTimeField(
        data: ByteArray,
        box: BoxSlice,
        version0Offset: Int,
        version1Offset: Int,
    ): Long {
        return when (val version = data.byteAt(box.payloadStart).toInt()) {
            0 -> readUInt32(data, box.payloadStart + version0Offset)
            1 -> readUInt32(data, box.payloadStart + version1Offset)
            else -> error("Unsupported ${box.type} version $version.")
        }
    }
}

private data class BoxSlice(
    val type: String,
    val payloadStart: Int,
    val end: Int,
)

private fun boxes(data: ByteArray, start: Int, end: Int): List<BoxSlice> {
    val result = mutableListOf<BoxSlice>()
    var offset = start
    while (offset < end) {
        require(end - offset >= 8) { "Truncated MP4 child box." }
        val size32 = readUInt32(data, offset)
        val type = data.copyOfRange(offset + 4, offset + 8).asType()
        var headerSize = 8
        val size = when (size32) {
            0L -> (end - offset).toLong()
            1L -> {
                headerSize = 16
                readUInt64(data, offset + 8)
            }
            else -> size32
        }
        require(size >= headerSize) { "Invalid MP4 child box size for $type." }
        val boxEndLong = Math.addExact(offset.toLong(), size)
        require(boxEndLong <= Int.MAX_VALUE) { "MP4 child box $type is too large." }
        val boxEnd = boxEndLong.toInt()
        require(boxEnd <= end) { "MP4 child box $type exceeds its parent." }
        result += BoxSlice(type, offset + headerSize, boxEnd)
        offset = boxEnd
    }
    return result
}

private fun timestampUs(value: Long, timescale: Long): Long {
    val seconds = value / timescale
    val remainder = value % timescale
    return Math.addExact(
        Math.multiplyExact(seconds, MICROS_PER_SECOND),
        Math.multiplyExact(remainder, MICROS_PER_SECOND) / timescale,
    )
}

private fun readUInt32(data: ByteArray, offset: Int): Long {
    require(offset >= 0 && offset + 4 <= data.size) { "Truncated MP4 uint32." }
    var value = 0L
    repeat(4) { index -> value = (value shl 8) or (data[offset + index].toLong() and 0xff) }
    return value
}

private fun readUInt64(data: ByteArray, offset: Int): Long {
    require(offset >= 0 && offset + 8 <= data.size) { "Truncated MP4 uint64." }
    require(data[offset].toInt() and 0x80 == 0) { "MP4 uint64 exceeds the supported range." }
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (data[offset + index].toLong() and 0xff) }
    return value
}

private fun ByteArray.asType(): String = toString(Charsets.US_ASCII)

private fun ByteArray.byteAt(index: Int): UByte {
    require(index in indices) { "Truncated MP4 full box." }
    return this[index].toUByte()
}

private fun InputStream.readFullyOrThrow(bytes: ByteArray, initialOffset: Int = 0) {
    var offset = initialOffset
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        check(read >= 0) { "MP4 file ended unexpectedly." }
        if (read > 0) offset += read
    }
}

private const val FULL_BOX_HEADER_BYTES = 4
private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_METADATA_BOX_BYTES = 16L * 1024 * 1024
private const val MAX_COALESCED_FRAGMENT_BYTES = 4L * 1024 * 1024
private const val STREAM_CHUNK_BYTES = 128 * 1024
