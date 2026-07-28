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

    private fun fullBox(type: String, payload: ByteArray): ByteArray {
        return box(type, byteArrayOf(0, 0, 0, 0) + payload)
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
}
