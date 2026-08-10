package com.example.moqandroid.publish.file

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishFileProbeTest {
    @Test
    fun inspectorFindsFragmentedMp4() {
        val input = ByteArrayInputStream(
            box("ftyp") +
                box("moov") +
                box("moof"),
        )

        assertEquals(
            IsoBmffStructure(isoBmff = true, fragmented = true),
            IsoBmffInspector.inspect(input),
        )
    }

    @Test
    fun inspectorIdentifiesRegularMp4() {
        val input = ByteArrayInputStream(
            box("ftyp") +
                box("mdat", byteArrayOf(1, 2, 3, 4)) +
                box("moov"),
        )

        assertEquals(
            IsoBmffStructure(isoBmff = true, fragmented = false),
            IsoBmffInspector.inspect(input),
        )
    }

    @Test
    fun inspectorRejectsFragmentWithoutInitSegment() {
        val input = ByteArrayInputStream(box("ftyp") + box("moof"))

        assertEquals(
            IsoBmffStructure(isoBmff = true, fragmented = false),
            IsoBmffInspector.inspect(input),
        )
    }

    @Test
    fun classifierSeparatesDirectRemuxAndUnsupportedPaths() {
        val h264 = PublishFileTrack(
            index = 0,
            kind = PublishFileTrackKind.Video,
            mimeType = "video/avc",
            durationUs = null,
            width = 1280,
            height = 720,
            sampleRate = null,
            channelCount = null,
        )
        val unsupportedVideo = h264.copy(mimeType = "video/unknown")

        assertEquals(
            PublishFileCompatibility.DirectFmp4,
            classify(PublishFileContainer.FragmentedMp4, listOf(h264)),
        )
        assertEquals(
            PublishFileCompatibility.NeedsRemux,
            classify(PublishFileContainer.Mp4, listOf(h264)),
        )
        assertEquals(
            PublishFileCompatibility.Unsupported,
            classify(PublishFileContainer.Mp4, listOf(unsupportedVideo)),
        )
    }

    @Test
    fun classifierKeepsInitialDirectPathToOneH264AndOptionalAacTrack() {
        val video = track(PublishFileTrackKind.Video, "video/avc")
        val audio = track(PublishFileTrackKind.Audio, "audio/mp4a-latm")

        assertEquals(
            PublishFileCompatibility.DirectFmp4,
            classify(PublishFileContainer.FragmentedMp4, listOf(video, audio)),
        )
        assertEquals(
            PublishFileCompatibility.Unsupported,
            classify(PublishFileContainer.FragmentedMp4, listOf(video, video.copy(index = 1))),
        )
        assertEquals(
            PublishFileCompatibility.Unsupported,
            classify(PublishFileContainer.FragmentedMp4, listOf(video, track(PublishFileTrackKind.Other, "text/vtt"))),
        )
        assertEquals(
            PublishFileCompatibility.Unsupported,
            classify(PublishFileContainer.FragmentedMp4, listOf(video.copy(mimeType = "video/hevc"))),
        )
        assertEquals(
            PublishFileCompatibility.Unsupported,
            classify(PublishFileContainer.FragmentedMp4, listOf(video.copy(hasEncryptedSamples = true))),
        )
    }

    @Test
    fun presentationUsesFinalDisplayDimensionsAfterRotation() {
        val video = track(PublishFileTrackKind.Video, "video/avc").copy(
            width = 1920,
            height = 1080,
            rotationDegrees = 90,
        )
        assertEquals(1080, video.videoPresentation()?.displayWidth)
        assertEquals(1920, video.videoPresentation()?.displayHeight)
        assertEquals(90, video.videoPresentation()?.rotationDegrees)
    }

    @Test
    fun avcInspectorFindsBSliceInAnnexBAndAvccSamples() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x41, 0xa0.toByte())
        val avcc = byteArrayOf(0, 0, 0, 2, 0x41, 0xa0.toByte())

        assertEquals(AvcSampleInspection.BSlice, AvcSampleTimingInspector.inspect(annexB))
        assertEquals(AvcSampleInspection.BSlice, AvcSampleTimingInspector.inspect(avcc))
    }

    @Test
    fun avcInspectorDoesNotClassifyPSliceAsBFrame() {
        val annexB = byteArrayOf(0, 0, 1, 0x41, 0xc0.toByte())

        assertEquals(AvcSampleInspection.NoBSlice, AvcSampleTimingInspector.inspect(annexB))
    }

    @Test
    fun avcInspectorRejectsTruncatedSliceHeader() {
        val annexB = byteArrayOf(0, 0, 1, 0x41)

        assertEquals(AvcSampleInspection.Unrecognized, AvcSampleTimingInspector.inspect(annexB))
    }

    @Test
    fun bSlicesRequirePresentationTimestampReordering() {
        assertTrue(AvcTimingInspection(hasBSlice = true, hasPtsRegression = false).lacksCompositionTiming)
        assertFalse(AvcTimingInspection(hasBSlice = true, hasPtsRegression = true).lacksCompositionTiming)
        assertFalse(AvcTimingInspection(hasBSlice = false, hasPtsRegression = false).lacksCompositionTiming)
    }

    private fun track(kind: PublishFileTrackKind, mimeType: String) = PublishFileTrack(
        index = 0,
        kind = kind,
        mimeType = mimeType,
        durationUs = null,
        width = null,
        height = null,
        sampleRate = null,
        channelCount = null,
    )

    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type.length == 4)
        return ByteArrayOutputStream().apply {
            val size = payload.size + 8
            write(
                byteArrayOf(
                    (size ushr 24).toByte(),
                    (size ushr 16).toByte(),
                    (size ushr 8).toByte(),
                    size.toByte(),
                ),
            )
            write(type.toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()
    }
}
