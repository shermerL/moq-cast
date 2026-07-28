package com.example.moqandroid.publish.file

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CmafFragmentMode {
    Preserve,
    RefragmentSamples,
}

internal class CmafStreamReader(
    private val input: InputStream,
    private val fragmentMode: CmafFragmentMode = CmafFragmentMode.Preserve,
) {
    suspend fun stream(
        beforeFragment: suspend (timestampUs: Long) -> Unit,
        write: (ByteArray) -> Unit,
    ) {
        val timing = CmafTimingParser()
        var sawMoov = false
        var sawFragment = false
        var awaitingMdat = false
        var pendingFragment: NormalizedCmafFragment? = null
        var pendingMoofWritten = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val header = input.readBoxHeader() ?: break
            when (header.type) {
                "moov" -> {
                    val payload = input.readMetadataPayload(header)
                    timing.initialize(payload)
                    sawMoov = true
                    write(header.bytes + payload)
                }

                "moof" -> {
                    check(sawMoov) { "CMAF fragment appeared before the moov init segment." }
                    check(!awaitingMdat) { "CMAF fragment is missing its mdat payload." }
                    val payload = input.readMetadataPayload(header)
                    val fragment = timing.normalizeFragment(header.bytes, payload)
                    if (fragmentMode == CmafFragmentMode.Preserve) {
                        beforeFragment(fragment.timestampUs)
                    }
                    pendingFragment = fragment
                    pendingMoofWritten = false
                    sawFragment = true
                    awaitingMdat = true
                }

                "mdat" -> {
                    check(awaitingMdat) { "CMAF mdat appeared without a preceding moof." }
                    val fragment = checkNotNull(pendingFragment)
                    val moof = fragment.bytes
                    val payloadSize = header.payloadSize
                    if (fragmentMode == CmafFragmentMode.RefragmentSamples) {
                        val size = requireNotNull(payloadSize) {
                            "A CMAF fragment cannot be refragmented when mdat extends to end of file."
                        }
                        require(size <= MAX_REFRAGMENT_MDAT_BYTES) {
                            "CMAF GOP is too large to refragment safely: $size bytes."
                        }
                        val payload = ByteArray(size.toInt()).also(input::readFullyOrThrow)
                        timing.refragmentSamples(fragment, header.bytes, payload).forEach { sample ->
                            beforeFragment(sample.timestampUs)
                            write(sample.bytes)
                        }
                    } else if (
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
                    pendingFragment = null
                    pendingMoofWritten = false
                    awaitingMdat = false
                }

                else -> {
                    check(!awaitingMdat || fragmentMode != CmafFragmentMode.RefragmentSamples) {
                        "Sample refragmenting requires mdat to immediately follow moof."
                    }
                    if (!pendingMoofWritten) {
                        pendingFragment?.bytes?.let(write)
                        pendingMoofWritten = pendingFragment != null
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

private class CmafTimingParser {
    private var timescales = emptyMap<Long, Long>()
    private val nextDecodeTimes = mutableMapOf<Long, Long>()
    private val seenTrackIds = mutableSetOf<Long>()
    private var fragmentSequence = 1L

    fun initialize(moovPayload: ByteArray) {
        nextDecodeTimes.clear()
        seenTrackIds.clear()
        fragmentSequence = 1L
        timescales = buildMap {
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
        check(timescales.isNotEmpty()) { "CMAF moov has no media tracks." }
    }

    fun normalizeFragment(moofHeader: ByteArray, moofPayload: ByteArray): NormalizedCmafFragment {
        val trafs = boxes(moofPayload, 0, moofPayload.size).filter { it.type == "traf" }
        check(trafs.isNotEmpty()) { "CMAF moof has no track fragments." }
        val states = trafs.map { traf -> inspectTraf(moofPayload, traf) }
        val missingTfdtCount = states.count { it.tfdt == null }
        val insertedBytes = Math.multiplyExact(missingTfdtCount, TFDT_BOX_BYTES)
        val removedBytes = Math.multiplyExact(
            states.count { state -> hasTfhdBaseDataOffset(moofPayload, state.tfhd) },
            TFHD_BASE_DATA_OFFSET_BYTES,
        )
        val moofSizeDelta = Math.subtractExact(insertedBytes, removedBytes)
        val timestamps = states.map { state ->
            val timescale = timescales[state.trackId]
                ?: error("CMAF fragment references unknown track ${state.trackId}.")
            timestampUs(state.baseDecodeTime, timescale)
        }

        states.forEach { state ->
            seenTrackIds += state.trackId
            val duration = state.duration
            if (duration == null) {
                nextDecodeTimes.remove(state.trackId)
            } else {
                nextDecodeTimes[state.trackId] = Math.addExact(state.baseDecodeTime, duration)
            }
        }
        if (insertedBytes == 0) {
            return NormalizedCmafFragment(moofHeader + moofPayload, timestamps.min())
        }

        // Media3 omits tfdt, while moq-mux requires it to establish each track's decode timeline.
        val output = ByteArrayOutputStream(moofPayload.size + moofSizeDelta.coerceAtLeast(0))
        var position = 0
        states.forEach { state ->
            output.write(moofPayload, position, state.traf.start - position)
            output.write(rewriteTraf(moofPayload, state, moofSizeDelta))
            position = state.traf.end
        }
        output.write(moofPayload, position, moofPayload.size - position)
        val rewrittenPayload = output.toByteArray()
        val rewrittenHeader = resizedBoxHeader(moofHeader, rewrittenPayload.size)
        return NormalizedCmafFragment(rewrittenHeader + rewrittenPayload, timestamps.min())
    }

    fun refragmentSamples(
        fragment: NormalizedCmafFragment,
        mdatHeader: ByteArray,
        mdatPayload: ByteArray,
    ): List<NormalizedCmafFragment> {
        require(mdatHeader.size == STANDARD_BOX_HEADER_BYTES) {
            "Sample refragmenting requires a standard mdat header."
        }
        val moofHeaderSize = boxHeaderSize(fragment.bytes)
        val moofPayload = fragment.bytes.copyOfRange(moofHeaderSize, fragment.bytes.size)
        val samples = boxes(moofPayload, 0, moofPayload.size)
            .filter { it.type == "traf" }
            .flatMap { traf -> readSamples(moofPayload, traf, fragment.bytes.size, mdatHeader.size, mdatPayload) }
            .sortedWith(compareBy<CmafSample> { it.decodeTimestampUs }.thenBy { it.trackId })
        check(samples.isNotEmpty()) { "CMAF fragment has no media samples." }
        return samples.map { sample ->
            NormalizedCmafFragment(
                bytes = sampleFragment(sample, fragmentSequence++),
                timestampUs = sample.decodeTimestampUs,
            )
        }
    }

    private fun readSamples(
        data: ByteArray,
        traf: BoxSlice,
        moofSize: Int,
        mdatHeaderSize: Int,
        mdatPayload: ByteArray,
    ): List<CmafSample> {
        val children = boxes(data, traf.payloadStart, traf.end)
        val tfhd = children.firstOrNull { it.type == "tfhd" }
            ?: error("CMAF traf has no tfhd box.")
        val tfdt = children.firstOrNull { it.type == "tfdt" }
            ?: error("CMAF traf has no tfdt box.")
        val trackId = readUInt32(data, tfhd.payloadStart + FULL_BOX_HEADER_BYTES)
        val timescale = timescales[trackId]
            ?: error("CMAF fragment references unknown track $trackId.")
        val defaults = readTfhdDefaults(data, tfhd)
        var decodeTime = readTfdt(data, tfdt)
        var sampleOffset = 0
        val samples = mutableListOf<CmafSample>()
        children.filter { it.type == "trun" }.forEach { trun ->
            val parsed = readTrun(data, trun, defaults)
            parsed.dataOffset?.let { dataOffset ->
                sampleOffset = dataOffset
                    .minus(moofSize)
                    .minus(mdatHeaderSize)
            }
            require(sampleOffset >= 0) { "CMAF trun has an invalid data offset." }
            parsed.samples.forEach { entry ->
                val sampleEnd = Math.addExact(sampleOffset, entry.size)
                require(sampleEnd <= mdatPayload.size) { "CMAF sample exceeds its mdat payload." }
                samples += CmafSample(
                    trackId = trackId,
                    decodeTime = decodeTime,
                    decodeTimestampUs = timestampUs(decodeTime, timescale),
                    duration = entry.duration,
                    flags = entry.flags,
                    compositionOffset = entry.compositionOffset,
                    data = mdatPayload.copyOfRange(sampleOffset, sampleEnd),
                )
                sampleOffset = sampleEnd
                decodeTime = Math.addExact(decodeTime, entry.duration)
            }
        }
        return samples
    }

    private fun readTfhdDefaults(data: ByteArray, tfhd: BoxSlice): TfhdDefaults {
        val flags = readUInt32(data, tfhd.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        var offset = tfhd.payloadStart + FULL_BOX_HEADER_BYTES + TRACK_ID_BYTES
        if (flags and TFHD_BASE_DATA_OFFSET_PRESENT != 0) offset += TFHD_BASE_DATA_OFFSET_BYTES
        if (flags and TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT != 0) offset += 4
        val duration = if (flags and TFHD_DEFAULT_SAMPLE_DURATION_PRESENT != 0) {
            readUInt32(data, offset).also { offset += 4 }
        } else {
            null
        }
        val size = if (flags and TFHD_DEFAULT_SAMPLE_SIZE_PRESENT != 0) {
            readSampleSize(data, offset).also { offset += 4 }
        } else {
            null
        }
        val sampleFlags = if (flags and TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT != 0) {
            readInt32(data, offset)
        } else {
            null
        }
        return TfhdDefaults(duration, size, sampleFlags)
    }

    private fun readTrun(data: ByteArray, trun: BoxSlice, defaults: TfhdDefaults): ParsedTrun {
        val version = data.byteAt(trun.payloadStart).toInt()
        require(version == 0 || version == 1) { "Unsupported trun version $version." }
        val flags = readUInt32(data, trun.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        val sampleCount = readUInt32(data, trun.payloadStart + FULL_BOX_HEADER_BYTES)
        require(sampleCount <= Int.MAX_VALUE) { "CMAF trun has too many samples." }
        var offset = trun.payloadStart + FULL_BOX_HEADER_BYTES + SAMPLE_COUNT_BYTES
        val dataOffset = if (flags and TRUN_DATA_OFFSET_PRESENT != 0) {
            readInt32(data, offset).also { offset += 4 }
        } else {
            null
        }
        val firstSampleFlags = if (flags and TRUN_FIRST_SAMPLE_FLAGS_PRESENT != 0) {
            readInt32(data, offset).also { offset += 4 }
        } else {
            null
        }
        val samples = buildList {
            repeat(sampleCount.toInt()) { index ->
                val duration = if (flags and TRUN_SAMPLE_DURATION_PRESENT != 0) {
                    readUInt32(data, offset).also { offset += 4 }
                } else {
                    requireNotNull(defaults.duration) { "CMAF sample has no duration." }
                }
                val size = if (flags and TRUN_SAMPLE_SIZE_PRESENT != 0) {
                    readSampleSize(data, offset).also { offset += 4 }
                } else {
                    requireNotNull(defaults.size) { "CMAF sample has no size." }
                }
                val sampleFlags = when {
                    flags and TRUN_SAMPLE_FLAGS_PRESENT != 0 -> readInt32(data, offset).also { offset += 4 }
                    index == 0 && firstSampleFlags != null -> firstSampleFlags
                    else -> requireNotNull(defaults.flags) { "CMAF sample has no flags." }
                }
                val compositionOffset = if (flags and TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT != 0) {
                    if (version == 0) {
                        readUInt32(data, offset).also { offset += 4 }
                    } else {
                        readInt32(data, offset).toLong().also { offset += 4 }
                    }
                } else {
                    0L
                }
                require(offset <= trun.end) { "CMAF trun sample table is truncated." }
                add(TrunSample(duration, size, sampleFlags, compositionOffset))
            }
        }
        return ParsedTrun(dataOffset, samples)
    }

    private fun sampleFragment(sample: CmafSample, sequence: Long): ByteArray {
        val mfhd = fullBox("mfhd", payload = uint32Bytes(sequence))
        val tfhd = fullBox(
            type = "tfhd",
            flags = TFHD_DEFAULT_BASE_IS_MOOF,
            payload = uint32Bytes(sample.trackId),
        )
        val tfdt = tfdtBox(sample.decodeTime)
        val includeCompositionOffset = sample.compositionOffset != 0L
        val trunFlags = TRUN_DATA_OFFSET_PRESENT or
            TRUN_SAMPLE_DURATION_PRESENT or
            TRUN_SAMPLE_SIZE_PRESENT or
            TRUN_SAMPLE_FLAGS_PRESENT or
            if (includeCompositionOffset) TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT else 0

        fun trun(dataOffset: Int): ByteArray {
            val payload = ByteArrayOutputStream().apply {
                write(uint32Bytes(1))
                write(int32Bytes(dataOffset))
                write(uint32Bytes(sample.duration))
                write(uint32Bytes(sample.data.size))
                write(int32Bytes(sample.flags))
                if (includeCompositionOffset) write(int32Bytes(Math.toIntExact(sample.compositionOffset)))
            }.toByteArray()
            return fullBox("trun", version = 1, flags = trunFlags, payload = payload)
        }

        val initialMoof = box("moof", mfhd + box("traf", tfhd + tfdt + trun(0)))
        val moof = box("moof", mfhd + box("traf", tfhd + tfdt + trun(initialMoof.size + STANDARD_BOX_HEADER_BYTES)))
        return moof + box("mdat", sample.data)
    }

    private fun inspectTraf(data: ByteArray, traf: BoxSlice): TrafTiming {
        val children = boxes(data, traf.payloadStart, traf.end)
        val tfhd = children.firstOrNull { it.type == "tfhd" }
            ?: error("CMAF traf has no tfhd box.")
        val trackId = readUInt32(data, tfhd.payloadStart + FULL_BOX_HEADER_BYTES)
        val tfdt = children.firstOrNull { it.type == "tfdt" }
        val baseDecodeTime = tfdt?.let { readTfdt(data, it) } ?: when {
            nextDecodeTimes.containsKey(trackId) -> checkNotNull(nextDecodeTimes[trackId])
            trackId !in seenTrackIds -> 0L
            else -> error("CMAF track $trackId has no tfdt and its decode time cannot be inferred.")
        }
        val defaultDuration = readTfhdDefaultDuration(data, tfhd)
        val truns = children.filter { it.type == "trun" }
        if (tfdt == null) {
            check(truns.isNotEmpty()) { "CMAF fragment without tfdt has no trun box." }
        }
        val duration = if (truns.isEmpty()) {
            null
        } else {
            truns.fold(0L) { total, trun ->
                Math.addExact(total, readTrunDuration(data, trun, defaultDuration))
            }
        }
        return TrafTiming(traf, tfhd, tfdt, trackId, baseDecodeTime, duration)
    }

    private fun rewriteTraf(data: ByteArray, state: TrafTiming, moofSizeDelta: Int): ByteArray {
        val payload = ByteArrayOutputStream(state.traf.end - state.traf.payloadStart + TFDT_BOX_BYTES)
        var position = state.traf.payloadStart
        val children = boxes(data, state.traf.payloadStart, state.traf.end)
        children.forEach { child ->
            payload.write(data, position, child.start - position)
            val rewritten = when (child.type) {
                "tfhd" -> rewriteTfhd(data, child)
                "trun" -> rewriteTrun(data, child, moofSizeDelta)
                else -> data.copyOfRange(child.start, child.end)
            }
            payload.write(rewritten)
            if (child == state.tfhd && state.tfdt == null) {
                payload.write(tfdtBox(state.baseDecodeTime))
            }
            position = child.end
        }
        payload.write(data, position, state.traf.end - position)
        val rewrittenPayload = payload.toByteArray()
        val header = data.copyOfRange(state.traf.start, state.traf.payloadStart)
        return resizedBoxHeader(header, rewrittenPayload.size) + rewrittenPayload
    }

    private fun rewriteTfhd(data: ByteArray, tfhd: BoxSlice): ByteArray {
        val flags = readUInt32(data, tfhd.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        if (flags and TFHD_BASE_DATA_OFFSET_PRESENT == 0) {
            return data.copyOfRange(tfhd.start, tfhd.end)
        }

        val header = data.copyOfRange(tfhd.start, tfhd.payloadStart)
        val payload = data.copyOfRange(tfhd.payloadStart, tfhd.end)
        val baseDataOffset = FULL_BOX_HEADER_BYTES + TRACK_ID_BYTES
        val rewrittenPayload = payload.copyOfRange(0, baseDataOffset) +
            payload.copyOfRange(baseDataOffset + TFHD_BASE_DATA_OFFSET_BYTES, payload.size)
        val versionAndFlags = readInt32(rewrittenPayload, 0)
        val rewrittenFlags = (versionAndFlags and TFHD_BASE_DATA_OFFSET_PRESENT.inv()) or
            TFHD_DEFAULT_BASE_IS_MOOF
        writeInt32(rewrittenPayload, 0, rewrittenFlags)
        return resizedBoxHeader(header, rewrittenPayload.size) + rewrittenPayload
    }

    private fun rewriteTrun(data: ByteArray, trun: BoxSlice, moofSizeDelta: Int): ByteArray {
        val rewritten = data.copyOfRange(trun.start, trun.end)
        val flags = readUInt32(data, trun.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        if (flags and TRUN_DATA_OFFSET_PRESENT == 0) return rewritten
        val relativeOffset = trun.payloadStart - trun.start + FULL_BOX_HEADER_BYTES + SAMPLE_COUNT_BYTES
        val dataOffset = readInt32(rewritten, relativeOffset)
        writeInt32(rewritten, relativeOffset, Math.addExact(dataOffset, moofSizeDelta))
        return rewritten
    }

    private fun hasTfhdBaseDataOffset(data: ByteArray, tfhd: BoxSlice): Boolean {
        val flags = readUInt32(data, tfhd.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        return flags and TFHD_BASE_DATA_OFFSET_PRESENT != 0
    }

    private fun readTfdt(data: ByteArray, tfdt: BoxSlice): Long {
        val version = data.byteAt(tfdt.payloadStart).toInt()
        val baseTimeOffset = tfdt.payloadStart + FULL_BOX_HEADER_BYTES
        return when (version) {
            0 -> readUInt32(data, baseTimeOffset)
            1 -> readUInt64(data, baseTimeOffset)
            else -> error("Unsupported tfdt version $version.")
        }
    }

    private fun readTfhdDefaultDuration(data: ByteArray, tfhd: BoxSlice): Long? {
        val flags = readUInt32(data, tfhd.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        var offset = tfhd.payloadStart + FULL_BOX_HEADER_BYTES + TRACK_ID_BYTES
        if (flags and TFHD_BASE_DATA_OFFSET_PRESENT != 0) offset += 8
        if (flags and TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT != 0) offset += 4
        return if (flags and TFHD_DEFAULT_SAMPLE_DURATION_PRESENT != 0) {
            readUInt32(data, offset)
        } else {
            null
        }
    }

    private fun readTrunDuration(data: ByteArray, trun: BoxSlice, defaultDuration: Long?): Long {
        val flags = readUInt32(data, trun.payloadStart).toInt() and FULL_BOX_FLAGS_MASK
        val sampleCount = readUInt32(data, trun.payloadStart + FULL_BOX_HEADER_BYTES)
        require(sampleCount <= Int.MAX_VALUE) { "CMAF trun has too many samples." }
        var offset = trun.payloadStart + FULL_BOX_HEADER_BYTES + SAMPLE_COUNT_BYTES
        if (flags and TRUN_DATA_OFFSET_PRESENT != 0) offset += 4
        if (flags and TRUN_FIRST_SAMPLE_FLAGS_PRESENT != 0) offset += 4
        var duration = 0L
        repeat(sampleCount.toInt()) {
            val sampleDuration = if (flags and TRUN_SAMPLE_DURATION_PRESENT != 0) {
                readUInt32(data, offset).also { offset += 4 }
            } else {
                requireNotNull(defaultDuration) {
                    "CMAF fragment without tfdt must declare sample durations."
                }
            }
            duration = Math.addExact(duration, sampleDuration)
            if (flags and TRUN_SAMPLE_SIZE_PRESENT != 0) offset += 4
            if (flags and TRUN_SAMPLE_FLAGS_PRESENT != 0) offset += 4
            if (flags and TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT != 0) offset += 4
            require(offset <= trun.end) { "CMAF trun sample table is truncated." }
        }
        return duration
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
    val start: Int,
    val payloadStart: Int,
    val end: Int,
)

private data class TrafTiming(
    val traf: BoxSlice,
    val tfhd: BoxSlice,
    val tfdt: BoxSlice?,
    val trackId: Long,
    val baseDecodeTime: Long,
    val duration: Long?,
)

private data class NormalizedCmafFragment(
    val bytes: ByteArray,
    val timestampUs: Long,
)

private data class TfhdDefaults(
    val duration: Long?,
    val size: Int?,
    val flags: Int?,
)

private data class ParsedTrun(
    val dataOffset: Int?,
    val samples: List<TrunSample>,
)

private data class TrunSample(
    val duration: Long,
    val size: Int,
    val flags: Int,
    val compositionOffset: Long,
)

private data class CmafSample(
    val trackId: Long,
    val decodeTime: Long,
    val decodeTimestampUs: Long,
    val duration: Long,
    val flags: Int,
    val compositionOffset: Long,
    val data: ByteArray,
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
        result += BoxSlice(type, offset, offset + headerSize, boxEnd)
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

private fun readInt32(data: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset + 4 <= data.size) { "Truncated MP4 int32." }
    return (data[offset].toInt() shl 24) or
        ((data[offset + 1].toInt() and 0xff) shl 16) or
        ((data[offset + 2].toInt() and 0xff) shl 8) or
        (data[offset + 3].toInt() and 0xff)
}

private fun readSampleSize(data: ByteArray, offset: Int): Int {
    val size = readUInt32(data, offset)
    require(size <= Int.MAX_VALUE) { "CMAF sample is too large to buffer safely." }
    return size.toInt()
}

private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
    require(offset >= 0 && offset + 4 <= data.size) { "Truncated MP4 int32." }
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

private fun writeUInt64(data: ByteArray, offset: Int, value: Long) {
    require(value >= 0) { "MP4 uint64 cannot be negative." }
    require(offset >= 0 && offset + 8 <= data.size) { "Truncated MP4 uint64." }
    repeat(8) { index ->
        data[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}

private fun box(type: String, payload: ByteArray): ByteArray {
    require(type.length == 4)
    return ByteArrayOutputStream(payload.size + 8).apply {
        write(uint32Bytes(payload.size + 8))
        write(type.toByteArray(Charsets.US_ASCII))
        write(payload)
    }.toByteArray()
}

private fun fullBox(
    type: String,
    version: Int = 0,
    flags: Int = 0,
    payload: ByteArray,
): ByteArray {
    require(version in 0..0xff)
    require(flags in 0..FULL_BOX_FLAGS_MASK)
    return box(
        type,
        byteArrayOf(
            version.toByte(),
            (flags ushr 16).toByte(),
            (flags ushr 8).toByte(),
            flags.toByte(),
        ) + payload,
    )
}

private fun tfdtBox(baseDecodeTime: Long): ByteArray {
    return box(
        "tfdt",
        byteArrayOf(1, 0, 0, 0) + uint64Bytes(baseDecodeTime),
    )
}

private fun boxHeaderSize(box: ByteArray): Int {
    require(box.size >= STANDARD_BOX_HEADER_BYTES) { "Truncated MP4 box header." }
    return when (readUInt32(box, 0)) {
        1L -> EXTENDED_BOX_HEADER_BYTES
        0L -> error("An inspected MP4 box cannot extend to end of stream.")
        else -> STANDARD_BOX_HEADER_BYTES
    }
}

private fun resizedBoxHeader(header: ByteArray, payloadSize: Int): ByteArray {
    val rewritten = header.copyOf()
    return when (readUInt32(rewritten, 0)) {
        1L -> rewritten.also { writeUInt64(it, 8, payloadSize.toLong() + it.size) }
        0L -> error("A rewritten CMAF box cannot extend to the end of the stream.")
        else -> rewritten.also { writeInt32(it, 0, Math.addExact(payloadSize, it.size)) }
    }
}

private fun uint32Bytes(value: Int): ByteArray {
    return ByteArray(4).also { writeInt32(it, 0, value) }
}

private fun uint32Bytes(value: Long): ByteArray {
    require(value in 0..UINT32_MAX) { "Value does not fit in an MP4 uint32." }
    return uint32Bytes(value.toInt())
}

private fun int32Bytes(value: Int): ByteArray = uint32Bytes(value)

private fun uint64Bytes(value: Long): ByteArray {
    return ByteArray(8).also { writeUInt64(it, 0, value) }
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
private const val FULL_BOX_FLAGS_MASK = 0x00ff_ffff
private const val TRACK_ID_BYTES = 4
private const val SAMPLE_COUNT_BYTES = 4
private const val TFDT_BOX_BYTES = 20
private const val TFHD_BASE_DATA_OFFSET_PRESENT = 0x000001
private const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
private const val TFHD_BASE_DATA_OFFSET_BYTES = 8
private const val TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT = 0x000002
private const val TFHD_DEFAULT_SAMPLE_DURATION_PRESENT = 0x000008
private const val TFHD_DEFAULT_SAMPLE_SIZE_PRESENT = 0x000010
private const val TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT = 0x000020
private const val TRUN_DATA_OFFSET_PRESENT = 0x000001
private const val TRUN_FIRST_SAMPLE_FLAGS_PRESENT = 0x000004
private const val TRUN_SAMPLE_DURATION_PRESENT = 0x000100
private const val TRUN_SAMPLE_SIZE_PRESENT = 0x000200
private const val TRUN_SAMPLE_FLAGS_PRESENT = 0x000400
private const val TRUN_SAMPLE_COMPOSITION_TIME_OFFSET_PRESENT = 0x000800
private const val MICROS_PER_SECOND = 1_000_000L
private const val UINT32_MAX = 0xffff_ffffL
private const val STANDARD_BOX_HEADER_BYTES = 8
private const val EXTENDED_BOX_HEADER_BYTES = 16
private const val MAX_METADATA_BOX_BYTES = 16L * 1024 * 1024
private const val MAX_COALESCED_FRAGMENT_BYTES = 4L * 1024 * 1024
private const val MAX_REFRAGMENT_MDAT_BYTES = 64L * 1024 * 1024
private const val STREAM_CHUNK_BYTES = 128 * 1024
