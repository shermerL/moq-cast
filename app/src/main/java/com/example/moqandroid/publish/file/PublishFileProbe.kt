package com.example.moqandroid.publish.file

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

sealed interface PublishFileState {
    data object NotSelected : PublishFileState

    data class Probing(val displayName: String?) : PublishFileState

    data class Ready(val file: ProbedPublishFile) : PublishFileState

    data class Failed(
        val displayName: String?,
        val reason: String,
    ) : PublishFileState
}

data class ProbedPublishFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationUs: Long?,
    val tracks: List<PublishFileTrack>,
    val container: PublishFileContainer,
    val compatibility: PublishFileCompatibility,
)

data class PublishFileTrack(
    val index: Int,
    val kind: PublishFileTrackKind,
    val mimeType: String,
    val durationUs: Long?,
    val width: Int?,
    val height: Int?,
    val sampleRate: Int?,
    val channelCount: Int?,
    val frameRate: Int? = null,
    val bitrate: Int? = null,
    val rotationDegrees: Int? = null,
    val hasEncryptedSamples: Boolean = false,
)

enum class PublishFileTrackKind {
    Video,
    Audio,
    Other,
}

enum class PublishFileContainer {
    FragmentedMp4,
    Mp4,
    Other,
}

enum class PublishFileCompatibility {
    DirectFmp4,
    NeedsRemux,
    Unsupported,
}

class PublishFileProbe(private val context: Context) {
    fun probe(uri: Uri): ProbedPublishFile {
        val resolver = context.contentResolver
        val document = resolver.queryDocument(uri)
        val structure = resolver.openInputStream(uri)?.use(IsoBmffInspector::inspect) ?: IsoBmffStructure()
        val container = when {
            structure.fragmented -> PublishFileContainer.FragmentedMp4
            structure.isoBmff -> PublishFileContainer.Mp4
            else -> PublishFileContainer.Other
        }
        val tracks = readTracks(uri, inspectSampleEncryption = container == PublishFileContainer.Mp4)

        return ProbedPublishFile(
            uri = uri,
            displayName = document.displayName ?: uri.lastPathSegment ?: "media",
            mimeType = resolver.getType(uri),
            sizeBytes = document.sizeBytes,
            durationUs = tracks.mapNotNull(PublishFileTrack::durationUs).maxOrNull(),
            tracks = tracks,
            container = container,
            compatibility = classify(container, tracks),
        )
    }

    private fun readTracks(uri: Uri, inspectSampleEncryption: Boolean): List<PublishFileTrack> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val encryptedContainer = extractor.psshInfo?.isNotEmpty() == true
            val tracks = buildList {
                repeat(extractor.trackCount) { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: "application/octet-stream"
                    add(
                        PublishFileTrack(
                            index = index,
                            kind = when {
                                mime.startsWith("video/") -> PublishFileTrackKind.Video
                                mime.startsWith("audio/") -> PublishFileTrackKind.Audio
                                else -> PublishFileTrackKind.Other
                            },
                            mimeType = mime,
                            durationUs = format.longOrNull(MediaFormat.KEY_DURATION),
                            width = format.intOrNull(MediaFormat.KEY_WIDTH),
                            height = format.intOrNull(MediaFormat.KEY_HEIGHT),
                            sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                            channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                            frameRate = format.intOrNull(MediaFormat.KEY_FRAME_RATE),
                            bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE),
                            rotationDegrees = format.intOrNull(MediaFormat.KEY_ROTATION),
                        ),
                    )
                }
            }
            val sampleInspection = if (inspectSampleEncryption) {
                inspectSamples(extractor, tracks)
            } else {
                SampleInspection(emptySet())
            }
            tracks.map { track ->
                track.copy(
                    hasEncryptedSamples =
                        encryptedContainer || track.index in sampleInspection.encryptedTracks,
                )
            }
        } finally {
            extractor.release()
        }
    }

    private fun inspectSamples(
        extractor: MediaExtractor,
        tracks: List<PublishFileTrack>,
    ): SampleInspection {
        val mediaTracks = tracks.filter {
            it.kind == PublishFileTrackKind.Video || it.kind == PublishFileTrackKind.Audio
        }
        mediaTracks.forEach { extractor.selectTrack(it.index) }
        val encryptedTracks = mutableSetOf<Int>()
        val cryptoInfo = android.media.MediaCodec.CryptoInfo()
        while (extractor.sampleTrackIndex >= 0) {
            val track = extractor.sampleTrackIndex
            if (extractor.getSampleCryptoInfo(cryptoInfo)) encryptedTracks += track
            if (!extractor.advance()) break
        }
        return SampleInspection(encryptedTracks)
    }
}

private data class SampleInspection(
    val encryptedTracks: Set<Int>,
)

private data class DocumentInfo(
    val displayName: String?,
    val sizeBytes: Long?,
)

private fun android.content.ContentResolver.queryDocument(uri: Uri): DocumentInfo {
    return runCatching {
        query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use DocumentInfo(null, null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            DocumentInfo(
                displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
            )
        }
    }.getOrNull() ?: DocumentInfo(null, null)
}

private fun MediaFormat.intOrNull(key: String): Int? {
    return if (containsKey(key)) getInteger(key) else null
}

private fun MediaFormat.longOrNull(key: String): Long? {
    return if (containsKey(key)) getLong(key) else null
}

internal fun classify(
    container: PublishFileContainer,
    tracks: List<PublishFileTrack>,
): PublishFileCompatibility {
    val videoTracks = tracks.filter { it.kind == PublishFileTrackKind.Video }
    val audioTracks = tracks.filter { it.kind == PublishFileTrackKind.Audio }
    val hasUnsupportedTrack = tracks.any { it.kind == PublishFileTrackKind.Other }
    val supportedTrackLayout = videoTracks.size == 1 && audioTracks.size <= 1 && !hasUnsupportedTrack
    val codecsSupportedForInitialRelease =
        videoTracks.singleOrNull()?.mimeType == MIME_AVC && audioTracks.all { it.mimeType == MIME_AAC }
    val hasEncryptedSamples = tracks.any(PublishFileTrack::hasEncryptedSamples)
    return when {
        !supportedTrackLayout ||
            !codecsSupportedForInitialRelease ||
            hasEncryptedSamples -> PublishFileCompatibility.Unsupported
        container == PublishFileContainer.FragmentedMp4 -> PublishFileCompatibility.DirectFmp4
        container == PublishFileContainer.Mp4 -> PublishFileCompatibility.NeedsRemux
        else -> PublishFileCompatibility.Unsupported
    }
}

private const val MIME_AVC = "video/avc"
private const val MIME_AAC = "audio/mp4a-latm"

internal data class IsoBmffStructure(
    val isoBmff: Boolean = false,
    val fragmented: Boolean = false,
)

internal object IsoBmffInspector {
    fun inspect(input: InputStream): IsoBmffStructure {
        var foundFtyp = false
        var foundMoov = false
        repeat(MAX_BOX_COUNT) {
            val size32 = input.readUnsignedInt() ?: return IsoBmffStructure(foundFtyp, false)
            val type = input.readType() ?: return IsoBmffStructure(foundFtyp, false)
            val headerSize: Long
            val boxSize = when (size32) {
                0L -> return IsoBmffStructure(foundFtyp, type == "moof" && foundMoov)
                1L -> {
                    headerSize = 16
                    input.readUnsignedLong() ?: return IsoBmffStructure(foundFtyp, false)
                }
                else -> {
                    headerSize = 8
                    size32
                }
            }
            if (boxSize < headerSize) return IsoBmffStructure(foundFtyp, false)
            if (type == "ftyp") foundFtyp = true
            if (type == "moov") foundMoov = true
            if (type == "moof") return IsoBmffStructure(foundFtyp, foundFtyp && foundMoov)
            if (!input.skipFully(boxSize - headerSize)) return IsoBmffStructure(foundFtyp, false)
        }
        return IsoBmffStructure(foundFtyp, false)
    }

    private const val MAX_BOX_COUNT = 10_000
}

private fun InputStream.readUnsignedInt(): Long? {
    val bytes = ByteArray(4)
    if (!readFully(bytes)) return null
    return bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
}

private fun InputStream.readUnsignedLong(): Long? {
    val bytes = ByteArray(8)
    if (!readFully(bytes)) return null
    var value = 0L
    for (byte in bytes) {
        if (value < 0) return null
        value = (value shl 8) or (byte.toLong() and 0xff)
    }
    return value.takeIf { it >= 0 }
}

private fun InputStream.readType(): String? {
    val bytes = ByteArray(4)
    if (!readFully(bytes)) return null
    return bytes.toString(Charsets.US_ASCII)
}

private fun InputStream.readFully(bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) return false
        offset += read
    }
    return true
}

private fun InputStream.skipFully(byteCount: Long): Boolean {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) return false
            remaining--
        }
    }
    return true
}
