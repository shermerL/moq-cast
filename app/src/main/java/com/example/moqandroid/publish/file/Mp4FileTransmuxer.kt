package com.example.moqandroid.publish.file

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@OptIn(UnstableApi::class)
class Mp4FileTransmuxer(private val context: Context) {
    suspend fun transmux(input: ProbedPublishFile, output: WritableByteChannel) {
        require(input.compatibility == PublishFileCompatibility.NeedsRemux) {
            "${input.displayName} does not require MP4 transmuxing."
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input.uri, null)
            FragmentedMp4Muxer.Builder(output)
                // Media3 only cuts video fragments at keyframes. Flush at every GOP boundary so the
                // downstream sample refragmenter never has to retain multiple GOPs.
                .setFragmentDurationMs(0)
                .setSampleCopyingEnabled(true)
                .build()
                .use { muxer ->
                val muxerTracks = buildMap {
                    input.tracks
                        .filter { it.kind == PublishFileTrackKind.Video || it.kind == PublishFileTrackKind.Audio }
                        .forEach { track ->
                            val format = extractor.getTrackFormat(track.index)
                            val muxerTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
                            extractor.selectTrack(track.index)
                            put(track.index, muxerTrack)
                        }
                }
                var sampleBuffer = ByteBuffer.allocate(INITIAL_SAMPLE_BUFFER_BYTES)
                while (extractor.sampleTrackIndex >= 0) {
                    currentCoroutineContext().ensureActive()
                    val extractorTrack = extractor.sampleTrackIndex
                    val sampleTime = extractor.sampleTime
                    val sampleSize = extractor.sampleSize
                    require(sampleSize in 0..MAX_SAMPLE_BYTES.toLong()) {
                        "MP4 sample is too large to publish safely: $sampleSize bytes."
                    }
                    if (sampleSize > sampleBuffer.capacity()) {
                        sampleBuffer = ByteBuffer.allocate(sampleSize.toInt())
                    }
                    sampleBuffer.clear()
                    val bytesRead = extractor.readSampleData(sampleBuffer, 0)
                    check(bytesRead >= 0) { "MP4 sample ended unexpectedly." }
                    sampleBuffer.position(0)
                    sampleBuffer.limit(bytesRead)
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    val muxerTrack = muxerTracks[extractorTrack]
                        ?: error("MP4 extractor returned an unselected track.")
                    muxer.writeSampleData(
                        muxerTrack,
                        sampleBuffer,
                        BufferInfo(sampleTime, bytesRead, flags),
                    )
                    check(extractor.advance() || extractor.sampleTrackIndex < 0) {
                        "MP4 extractor could not advance to the next sample."
                    }
                }
            }
        } finally {
            extractor.release()
        }
    }

    private companion object {
        private const val INITIAL_SAMPLE_BUFFER_BYTES = 1024 * 1024
        private const val MAX_SAMPLE_BYTES = 32 * 1024 * 1024
    }
}
