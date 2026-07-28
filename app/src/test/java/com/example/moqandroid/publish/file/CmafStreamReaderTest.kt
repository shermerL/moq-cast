package com.example.moqandroid.publish.file

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CmafStreamReaderTest {
    @Test
    fun streamsOriginalBytesAndReadsFragmentTimestamp() = runBlocking {
        val timescale = 90_000
        val trackId = 7
        val moov = box(
            "moov",
            box(
                "trak",
                fullBox("tkhd", uint32(0) + uint32(0) + uint32(trackId)) +
                    box(
                        "mdia",
                        fullBox("mdhd", uint32(0) + uint32(0) + uint32(timescale)),
                    ),
            ),
        )
        val moof = box(
            "moof",
            box(
                "traf",
                fullBox("tfhd", uint32(trackId)) +
                    fullBox("tfdt", uint32(timescale)),
            ),
        )
        val input = box("ftyp") + moov + moof + box("mdat", byteArrayOf(1, 2, 3, 4))
        val output = ByteArrayOutputStream()
        val timestamps = mutableListOf<Long>()

        CmafStreamReader(ByteArrayInputStream(input)).stream(
            beforeFragment = timestamps::add,
            write = output::write,
        )

        assertArrayEquals(input, output.toByteArray())
        assertEquals(listOf(1_000_000L), timestamps)
    }

    @Test
    fun injectsMissingTfdtAndPreservesMediaOffsets() = runBlocking {
        val timescale = 3_000
        val trackId = 7
        val moov = box(
            "moov",
            box(
                "trak",
                fullBox("tkhd", uint32(0) + uint32(0) + uint32(trackId)) +
                    box(
                        "mdia",
                        fullBox("mdhd", uint32(0) + uint32(0) + uint32(timescale)),
                    ),
            ),
        )
        val ftyp = box("ftyp")
        val firstMoofOffset = ftyp.size + moov.size
        val firstFragment = media3Fragment(trackId, firstMoofOffset.toLong(), sampleDuration = timescale)
        val secondMoofOffset = firstMoofOffset + firstFragment.size
        val secondFragment = media3Fragment(trackId, secondMoofOffset.toLong(), sampleDuration = timescale)
        val input = ftyp + moov + firstFragment + secondFragment
        val output = ByteArrayOutputStream()
        val timestamps = mutableListOf<Long>()

        CmafStreamReader(ByteArrayInputStream(input)).stream(
            beforeFragment = timestamps::add,
            write = output::write,
        )

        val normalized = output.toByteArray()
        val tfdtOffsets = findBoxTypes(normalized, "tfdt")
        val tfhdOffsets = findBoxTypes(normalized, "tfhd")
        val trunOffsets = findBoxTypes(normalized, "trun")
        assertEquals(listOf(0L, 1_000_000L), timestamps)
        assertEquals(input.size + 24, normalized.size)
        assertEquals(2, tfdtOffsets.size)
        assertEquals(0L, readUInt64(normalized, tfdtOffsets[0] + 12))
        assertEquals(timescale.toLong(), readUInt64(normalized, tfdtOffsets[1] + 12))
        assertEquals(16, readInt32(normalized, tfhdOffsets[0]))
        assertEquals(16, readInt32(normalized, tfhdOffsets[1]))
        assertEquals(0x020000, readInt32(normalized, tfhdOffsets[0] + 8))
        assertEquals(0x020000, readInt32(normalized, tfhdOffsets[1] + 8))
        assertEquals(media3MoofSize() + 8 + 12, readInt32(normalized, trunOffsets[0] + 16))
        assertEquals(media3MoofSize() + 8 + 12, readInt32(normalized, trunOffsets[1] + 16))
    }

    @Test
    fun refragmentsMedia3TracksIntoTimestampOrderedSamples() = runBlocking {
        val timescale = 1_000
        val videoTrackId = 1
        val audioTrackId = 2
        val ftyp = box("ftyp")
        val moov = box(
            "moov",
            track(videoTrackId, timescale) + track(audioTrackId, timescale),
        )
        val fragment = media3MultiTrackFragment(
            moofOffset = (ftyp.size + moov.size).toLong(),
            tracks = listOf(
                Media3Track(
                    trackId = videoTrackId,
                    durations = listOf(1_000, 1_000),
                    flags = listOf(0x02000000, 0x01010000),
                    compositionOffsets = listOf(1_000, -1_000),
                    samples = listOf(byteArrayOf(1), byteArrayOf(2)),
                ),
                Media3Track(
                    trackId = audioTrackId,
                    durations = listOf(500, 500, 500, 500),
                    flags = List(4) { 0x02000000 },
                    samples = listOf(byteArrayOf(3), byteArrayOf(4), byteArrayOf(5), byteArrayOf(6)),
                ),
            ),
        )
        val output = ByteArrayOutputStream()
        val timestamps = mutableListOf<Long>()

        CmafStreamReader(
            ByteArrayInputStream(ftyp + moov + fragment),
            CmafFragmentMode.RefragmentSamples,
        ).stream(
            beforeFragment = timestamps::add,
            write = output::write,
        )

        val refragmented = output.toByteArray()
        val tfhdOffsets = findBoxTypes(refragmented, "tfhd")
        val tfdtOffsets = findBoxTypes(refragmented, "tfdt")
        val trunOffsets = findBoxTypes(refragmented, "trun")
        val mdatOffsets = findBoxTypes(refragmented, "mdat")
        assertEquals(listOf(0L, 0L, 500_000L, 1_000_000L, 1_000_000L, 1_500_000L), timestamps)
        assertEquals(6, findBoxTypes(refragmented, "moof").size)
        assertEquals(6, tfhdOffsets.size)
        assertEquals(6, tfdtOffsets.size)
        assertEquals(6, trunOffsets.size)
        assertEquals(6, mdatOffsets.size)
        assertEquals(listOf(1, 2, 2, 1, 2, 2), tfhdOffsets.map { readInt32(refragmented, it + 12) })
        assertEquals(listOf(0L, 0L, 500L, 1_000L, 1_000L, 1_500L), tfdtOffsets.map {
            readUInt64(refragmented, it + 12)
        })
        assertEquals(List(6) { 1 }, trunOffsets.map { readInt32(refragmented, it + 12) })
        assertEquals(listOf(1, 3, 4, 2, 5, 6), mdatOffsets.map { refragmented[it + 8].toInt() })
        assertEquals(1_000, readInt32(refragmented, trunOffsets[0] + 32))
        assertEquals(-1_000, readInt32(refragmented, trunOffsets[3] + 32))
    }

    private fun media3Fragment(trackId: Int, moofOffset: Long, sampleDuration: Int): ByteArray {
        val tfhd = fullBox(
            type = "tfhd",
            flags = 0x000001,
            payload = uint32(trackId) + uint64(moofOffset),
        )
        val trun = fullBox(
            type = "trun",
            flags = 0x000701,
            payload = uint32(1) +
                int32(media3MoofSize() + 8) +
                uint32(sampleDuration) +
                uint32(4) +
                uint32(0),
        )
        return box("moof", box("traf", tfhd + trun)) + box("mdat", byteArrayOf(1, 2, 3, 4))
    }

    private fun media3MoofSize(): Int {
        val tfhdSize = 8 + 4 + 4 + 8
        val trunSize = 8 + 4 + 4 + 4 + 4 + 4 + 4
        return 8 + 8 + tfhdSize + trunSize
    }

    private fun track(trackId: Int, timescale: Int): ByteArray {
        return box(
            "trak",
            fullBox("tkhd", uint32(0) + uint32(0) + uint32(trackId)) +
                box(
                    "mdia",
                    fullBox("mdhd", uint32(0) + uint32(0) + uint32(timescale)),
                ),
        )
    }

    private fun media3MultiTrackFragment(
        moofOffset: Long,
        tracks: List<Media3Track>,
    ): ByteArray {
        fun traf(track: Media3Track, dataOffset: Int): ByteArray {
            val tfhd = fullBox(
                type = "tfhd",
                flags = 0x000001,
                payload = uint32(track.trackId) + uint64(moofOffset),
            )
            val entries = track.samples.indices.fold(byteArrayOf()) { bytes, index ->
                bytes +
                    uint32(track.durations[index]) +
                    uint32(track.samples[index].size) +
                    uint32(track.flags[index]) +
                    if (track.compositionOffsets.any { it != 0 }) {
                        int32(track.compositionOffsets[index])
                    } else {
                        byteArrayOf()
                    }
            }
            val trun = fullBox(
                type = "trun",
                flags = 0x000701 or
                    if (track.compositionOffsets.any { it != 0 }) 0x000800 else 0,
                payload = uint32(track.samples.size) + int32(dataOffset) + entries,
                version = if (track.compositionOffsets.any { it != 0 }) 1 else 0,
            )
            return box("traf", tfhd + trun)
        }

        val placeholder = box("moof", tracks.fold(byteArrayOf()) { bytes, track -> bytes + traf(track, 0) })
        var sampleOffset = placeholder.size + 8
        val moof = box(
            "moof",
            tracks.fold(byteArrayOf()) { bytes, track ->
                val box = traf(track, sampleOffset)
                sampleOffset += track.samples.sumOf { it.size }
                bytes + box
            },
        )
        val payload = tracks.fold(byteArrayOf()) { bytes, track ->
            bytes + track.samples.fold(byteArrayOf()) { samples, sample -> samples + sample }
        }
        return moof + box("mdat", payload)
    }

    private fun fullBox(type: String, payload: ByteArray): ByteArray {
        return box(type, byteArrayOf(0, 0, 0, 0) + payload)
    }

    private fun fullBox(type: String, flags: Int, payload: ByteArray, version: Int = 0): ByteArray {
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

    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type.length == 4)
        return uint32(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun uint32(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    private fun uint64(value: Long): ByteArray {
        return ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }
    }

    private fun int32(value: Int): ByteArray = uint32(value)

    private fun findBoxTypes(data: ByteArray, type: String): List<Int> {
        val marker = type.toByteArray(Charsets.US_ASCII)
        return (4..data.size - 4).filter { offset ->
            data[offset] == marker[0] &&
                data[offset + 1] == marker[1] &&
                data[offset + 2] == marker[2] &&
                data[offset + 3] == marker[3]
        }.map { it - 4 }
    }

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (data[offset + index].toLong() and 0xff) }
        return value
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private data class Media3Track(
        val trackId: Int,
        val durations: List<Int>,
        val flags: List<Int>,
        val samples: List<ByteArray>,
        val compositionOffsets: List<Int> = List(samples.size) { 0 },
    ) {
        init {
            require(durations.size == samples.size)
            require(flags.size == samples.size)
            require(compositionOffsets.size == samples.size)
        }
    }
}
