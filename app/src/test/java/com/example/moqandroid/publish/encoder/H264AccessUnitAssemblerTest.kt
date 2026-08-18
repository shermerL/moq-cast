package com.example.moqandroid.publish.encoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AccessUnitAssemblerTest {
    @Test
    fun convertsLengthPrefixedAccessUnitWithoutBoxingBytes() {
        val payload = avcc(
            byteArrayOf(0x67, 0x64, 0x00, 0x28),
            byteArrayOf(0x68, 0x01),
            byteArrayOf(0x65, 0x11, 0x22),
        )

        assertArrayEquals(SPS + PPS + IDR, payload.toAnnexB())
    }

    @Test
    fun keepsMalformedLengthPrefixedPayloadUnchanged() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x65)

        assertSame(payload, payload.toAnnexB())
    }

    @Test
    fun cachesSplitParameterSetsForNextKeyFrame() {
        val assembler = H264AccessUnitAssembler()

        assertNull(assembler.assemble(SPS, keyFrame = false))
        assertNull(assembler.assemble(PPS, keyFrame = false))
        val accessUnit = assembler.assemble(IDR, keyFrame = false)

        assertTrue(accessUnit?.isKeyFrame == true)
        assertTrue(accessUnit?.hasDecoderConfiguration == true)
        assertArrayEquals(SPS + PPS + IDR, accessUnit?.payload)
        assertTrue(accessUnit?.parameterSetsBytes == SPS.size + PPS.size)
    }

    @Test
    fun readsAvcDecoderConfigurationRecordFromOutputFormat() {
        val assembler = H264AccessUnitAssembler()

        assembler.updateCodecConfig(avcDecoderConfigurationRecord())
        val accessUnit = assembler.assemble(IDR, keyFrame = false)

        assertArrayEquals(SPS + PPS + IDR, accessUnit?.payload)
        assertTrue(accessUnit?.hasDecoderConfiguration == true)
    }

    @Test
    fun preservesVclDataWhenParameterSetsShareTheOutputBuffer() {
        val assembler = H264AccessUnitAssembler()
        val combined = SPS + PPS + IDR

        val accessUnit = assembler.assemble(combined, keyFrame = false)

        assertTrue(accessUnit?.isKeyFrame == true)
        assertTrue(accessUnit?.hasDecoderConfiguration == true)
        assertArrayEquals(combined, accessUnit?.payload)
    }

    @Test
    fun acceptsCodecKeyFrameFlagWhenIdrDetectionIsUnavailable() {
        val assembler = H264AccessUnitAssembler()

        val accessUnit = assembler.assemble(DELTA, keyFrame = true)

        assertTrue(accessUnit?.isKeyFrame == true)
        assertFalse(accessUnit?.hasDecoderConfiguration ?: true)
    }

    @Test
    fun doesNotMarkSingleParameterSetAsDecoderConfiguration() {
        val assembler = H264AccessUnitAssembler()

        assertNull(assembler.assemble(SPS, keyFrame = false))
        val accessUnit = assembler.assemble(IDR, keyFrame = false)

        assertTrue(accessUnit?.isKeyFrame == true)
        assertFalse(accessUnit?.hasDecoderConfiguration ?: true)
    }

    @Test
    fun leavesDeltaAccessUnitUnmarked() {
        val assembler = H264AccessUnitAssembler()

        val accessUnit = assembler.assemble(DELTA, keyFrame = false)

        assertFalse(accessUnit?.isKeyFrame ?: true)
        assertArrayEquals(DELTA, accessUnit?.payload)
    }

    private fun avcc(vararg nalUnits: ByteArray): ByteArray {
        val output = ByteArray(nalUnits.sumOf { it.size + 4 })
        var offset = 0
        nalUnits.forEach { nal ->
            output[offset] = (nal.size ushr 24).toByte()
            output[offset + 1] = (nal.size ushr 16).toByte()
            output[offset + 2] = (nal.size ushr 8).toByte()
            output[offset + 3] = nal.size.toByte()
            nal.copyInto(output, destinationOffset = offset + 4)
            offset += nal.size + 4
        }
        return output
    }

    private fun avcDecoderConfigurationRecord(): ByteArray {
        val sps = SPS.copyOfRange(4, SPS.size)
        val pps = PPS.copyOfRange(4, PPS.size)
        return byteArrayOf(
            1,
            0x64,
            0,
            0x28,
            0xff.toByte(),
            0xe1.toByte(),
            (sps.size ushr 8).toByte(),
            sps.size.toByte(),
            *sps,
            1,
            (pps.size ushr 8).toByte(),
            pps.size.toByte(),
            *pps,
        )
    }

    private companion object {
        val SPS = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28)
        val PPS = byteArrayOf(0, 0, 0, 1, 0x68, 0x01)
        val IDR = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val DELTA = byteArrayOf(0, 0, 0, 1, 0x41, 0x33)
    }
}
