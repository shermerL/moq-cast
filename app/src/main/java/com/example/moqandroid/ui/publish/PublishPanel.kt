package com.example.moqandroid.ui.publish

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.moqandroid.R
import com.example.moqandroid.publish.PublishSourceType
import com.example.moqandroid.publish.camera.CameraLensFacing
import com.example.moqandroid.publish.camera.CameraQualityPreset
import com.example.moqandroid.publish.file.ProbedPublishFile
import com.example.moqandroid.publish.file.PublishFileCompatibility
import com.example.moqandroid.publish.file.PublishFileContainer
import com.example.moqandroid.publish.file.PublishFileState
import com.example.moqandroid.publish.file.PublishFileTrackKind
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.MoqBrandHeader
import com.example.moqandroid.ui.components.MoqInfoRow
import com.example.moqandroid.ui.components.MoqPill
import com.example.moqandroid.ui.components.MoqSourceCard
import com.example.moqandroid.ui.components.MoqStatusCard
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.components.SecondaryAction
import com.example.moqandroid.ui.app.PublishPanelActions
import com.example.moqandroid.ui.app.PublishPanelMode
import com.example.moqandroid.ui.app.PublishPanelState

@Composable
fun PublishPanel(
    state: PublishPanelState,
    actions: PublishPanelActions,
) {
    Page {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            MoqBrandHeader(
                appName = stringResource(R.string.app_name),
                relayLabel = stringResource(R.string.local_relay_chip),
            )
            Spacer(Modifier.height(32.dp))
            LabeledField(
                label = stringResource(R.string.relay_url_label),
                value = state.relayUrl,
                placeholder = "http://host:4443/anon",
                onValueChange = actions.onRelayUrlChange,
                onSubmit = {},
            )
            Spacer(Modifier.height(12.dp))
            LabeledField(
                label = stringResource(R.string.broadcast_label),
                value = state.broadcast,
                placeholder = "bbb.hang",
                onValueChange = actions.onBroadcastChange,
                onSubmit = {
                    if (state.source != PublishSourceType.File || state.publishFileState.canPublishDirectly()) {
                        actions.onPublish()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            if (state.mode.isActiveLayout()) {
                PublishingContent(
                    status = state.status,
                    mode = state.mode,
                    source = state.source,
                    includeSystemAudio = state.includeSystemAudio,
                    includeMicrophone = state.includeMicrophone,
                    cameraLensFacing = state.cameraLensFacing,
                    cameraQualityPreset = state.cameraQualityPreset,
                    onIncludeSystemAudioChange = actions.onIncludeSystemAudioChange,
                    onIncludeMicrophoneChange = actions.onIncludeMicrophoneChange,
                    onStopPublish = actions.onStopPublish,
                )
            } else {
                ReadyContent(
                    status = state.status,
                    selectedSource = state.source,
                    onSelectedSource = actions.onSourceChange,
                    includeSystemAudio = state.includeSystemAudio,
                    includeMicrophone = state.includeMicrophone,
                    cameraLensFacing = state.cameraLensFacing,
                    cameraQualityPreset = state.cameraQualityPreset,
                    publishFileState = state.publishFileState,
                    onIncludeSystemAudioChange = actions.onIncludeSystemAudioChange,
                    onIncludeMicrophoneChange = actions.onIncludeMicrophoneChange,
                    onCameraLensFacingChange = actions.onCameraLensFacingChange,
                    onCameraQualityPresetChange = actions.onCameraQualityPresetChange,
                    onChoosePublishFile = actions.onChoosePublishFile,
                    onPublish = actions.onPublish,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    status: String,
    selectedSource: PublishSourceType,
    onSelectedSource: (PublishSourceType) -> Unit,
    includeSystemAudio: Boolean,
    includeMicrophone: Boolean,
    cameraLensFacing: CameraLensFacing,
    cameraQualityPreset: CameraQualityPreset,
    publishFileState: PublishFileState,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
    onIncludeMicrophoneChange: (Boolean) -> Unit,
    onCameraLensFacingChange: (CameraLensFacing) -> Unit,
    onCameraQualityPresetChange: (CameraQualityPreset) -> Unit,
    onChoosePublishFile: () -> Unit,
    onPublish: () -> Unit,
) {
    SourcePicker(
        selected = selectedSource,
        onSelected = onSelectedSource,
    )
    Spacer(Modifier.height(32.dp))
    when (selectedSource) {
        PublishSourceType.Screen -> SystemAudioRow(
            includeSystemAudio = includeSystemAudio,
            onIncludeSystemAudioChange = onIncludeSystemAudioChange,
        )
        PublishSourceType.Camera -> {
            CameraOptionsRow(
                selected = cameraLensFacing,
                onSelected = onCameraLensFacingChange,
            )
            Spacer(Modifier.height(24.dp))
            CameraQualityRow(
                selected = cameraQualityPreset,
                onSelected = onCameraQualityPresetChange,
            )
            Spacer(Modifier.height(24.dp))
            MicrophoneRow(includeMicrophone, onIncludeMicrophoneChange)
        }
        PublishSourceType.File -> FileOptions(
            state = publishFileState,
            onChooseFile = onChoosePublishFile,
        )
    }
    Spacer(Modifier.height(26.dp))
    PrimaryAction(
        text = stringResource(
            when (selectedSource) {
                PublishSourceType.Camera -> R.string.publish_camera
                PublishSourceType.File -> R.string.publish_file
                PublishSourceType.Screen -> R.string.publish_screen
            },
        ),
        onClick = onPublish,
        enabled = selectedSource != PublishSourceType.File || publishFileState.canPublishDirectly(),
    )
    Spacer(Modifier.height(16.dp))
    MoqStatusCard(
        title = stringResource(R.string.status_ready_title),
        body = status,
    )
}

@Composable
private fun PublishingContent(
    status: String,
    mode: PublishPanelMode,
    source: PublishSourceType,
    includeSystemAudio: Boolean,
    includeMicrophone: Boolean,
    cameraLensFacing: CameraLensFacing,
    cameraQualityPreset: CameraQualityPreset,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
    onIncludeMicrophoneChange: (Boolean) -> Unit,
    onStopPublish: () -> Unit,
) {
    MoqStatusCard(
        title = stringResource(mode.titleRes),
        body = status,
        heightDp = 86,
    )
    Spacer(Modifier.height(26.dp))
    MoqInfoRow(
        label = stringResource(R.string.publish_source_label),
        note = stringResource(source.noteRes),
    ) {
        MoqPill(
            text = stringResource(source.labelRes),
            selected = false,
        )
    }
    Spacer(Modifier.height(24.dp))
    when (source) {
        PublishSourceType.Screen -> SystemAudioRow(
            includeSystemAudio = includeSystemAudio,
            onIncludeSystemAudioChange = onIncludeSystemAudioChange,
        )
        PublishSourceType.Camera -> {
            CameraOptionsRow(
                selected = cameraLensFacing,
                onSelected = null,
            )
            Spacer(Modifier.height(24.dp))
            CameraQualityRow(
                selected = cameraQualityPreset,
                onSelected = null,
            )
            Spacer(Modifier.height(24.dp))
            MicrophoneRow(includeMicrophone, onIncludeMicrophoneChange)
        }
        PublishSourceType.File -> Unit
    }
    Spacer(Modifier.height(26.dp))
    SecondaryAction(stringResource(R.string.stop_publish), onStopPublish)
}

private fun PublishPanelMode.isActiveLayout(): Boolean = when (this) {
    PublishPanelMode.Ready,
    PublishPanelMode.Error,
    -> false
    PublishPanelMode.Preparing,
    PublishPanelMode.Publishing,
    PublishPanelMode.Stopping,
    -> true
}

private val PublishPanelMode.titleRes: Int
    get() = when (this) {
        PublishPanelMode.Ready -> R.string.status_ready_title
        PublishPanelMode.Preparing -> R.string.status_preparing_title
        PublishPanelMode.Publishing -> R.string.status_publishing_title
        PublishPanelMode.Stopping -> R.string.status_stopping_title
        PublishPanelMode.Error -> R.string.status_error_title
    }

@Composable
private fun SourcePicker(
    selected: PublishSourceType,
    onSelected: (PublishSourceType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        PublishSourceType.entries.forEach { source ->
            MoqSourceCard(
                marker = source.marker,
                title = stringResource(source.labelRes),
                subtitle = stringResource(source.stateRes),
                selected = selected == source,
                enabled = true,
                onClick = { onSelected(source) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SystemAudioRow(
    includeSystemAudio: Boolean,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
) {
    MoqInfoRow(
        label = stringResource(R.string.system_audio_label),
        note = stringResource(R.string.system_audio_note),
    ) {
        MoqPill(
            text = if (includeSystemAudio) stringResource(R.string.system_audio_on) else stringResource(R.string.system_audio_off),
            selected = includeSystemAudio,
            onClick = { onIncludeSystemAudioChange(!includeSystemAudio) },
        )
    }
}

@Composable
private fun CameraOptionsRow(
    selected: CameraLensFacing,
    onSelected: ((CameraLensFacing) -> Unit)?,
) {
    MoqInfoRow(
        label = stringResource(R.string.camera_options),
        note = stringResource(R.string.camera_options_note),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraLensFacing.entries.forEach { lensFacing ->
                MoqPill(
                    text = stringResource(lensFacing.labelRes),
                    selected = selected == lensFacing,
                    enabled = onSelected != null,
                    onClick = onSelected?.let { select -> { select(lensFacing) } },
                )
            }
        }
    }
}

@Composable
private fun CameraQualityRow(
    selected: CameraQualityPreset,
    onSelected: ((CameraQualityPreset) -> Unit)?,
) {
    MoqInfoRow(
        label = stringResource(R.string.camera_quality),
        note = stringResource(selected.noteRes),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraQualityPreset.entries.forEach { preset ->
                MoqPill(
                    text = stringResource(preset.labelRes),
                    selected = selected == preset,
                    enabled = onSelected != null,
                    onClick = onSelected?.let { select -> { select(preset) } },
                )
            }
        }
    }
}

@Composable
private fun MicrophoneRow(
    includeMicrophone: Boolean,
    onIncludeMicrophoneChange: (Boolean) -> Unit,
) {
    MoqInfoRow(
        label = stringResource(R.string.microphone_label),
        note = stringResource(R.string.microphone_note),
    ) {
        MoqPill(
            text = if (includeMicrophone) {
                stringResource(R.string.system_audio_on)
            } else {
                stringResource(R.string.system_audio_off)
            },
            selected = includeMicrophone,
            onClick = { onIncludeMicrophoneChange(!includeMicrophone) },
        )
    }
}

@Composable
private fun FileOptions(
    state: PublishFileState,
    onChooseFile: () -> Unit,
) {
    when (state) {
        PublishFileState.NotSelected -> {
            MoqInfoRow(
                label = stringResource(R.string.file_selection),
                note = stringResource(R.string.file_not_selected),
            ) {
                MoqPill(
                    text = stringResource(R.string.choose_file),
                    selected = false,
                    onClick = onChooseFile,
                )
            }
        }

        is PublishFileState.Probing -> {
            MoqInfoRow(
                label = state.displayName ?: stringResource(R.string.file_selection),
                note = stringResource(R.string.file_probing),
            ) {
                MoqPill(
                    text = stringResource(R.string.choose_file),
                    selected = false,
                    enabled = false,
                )
            }
        }

        is PublishFileState.Failed -> {
            MoqInfoRow(
                label = state.displayName ?: stringResource(R.string.file_selection),
                note = stringResource(R.string.file_probe_failed, state.reason),
            ) {
                MoqPill(
                    text = stringResource(R.string.choose_another_file),
                    selected = false,
                    onClick = onChooseFile,
                )
            }
        }

        is PublishFileState.Ready -> FileProbeResult(
            file = state.file,
            onChooseFile = onChooseFile,
        )
    }
}

@Composable
private fun FileProbeResult(
    file: ProbedPublishFile,
    onChooseFile: () -> Unit,
) {
    MoqInfoRow(
        label = file.displayName,
        note = file.fileSummary(),
    ) {
        MoqPill(
            text = stringResource(R.string.choose_another_file),
            selected = false,
            onClick = onChooseFile,
        )
    }
    Spacer(Modifier.height(24.dp))
    MoqInfoRow(
        label = stringResource(R.string.file_tracks),
        note = file.trackSummary(),
    ) {
        MoqPill(
            text = stringResource(file.container.labelRes),
            selected = false,
        )
    }
    Spacer(Modifier.height(24.dp))
    MoqInfoRow(
        label = stringResource(R.string.file_publish_path),
        note = stringResource(file.compatibility.noteRes),
    ) {
        MoqPill(
            text = stringResource(file.compatibility.labelRes),
            selected = file.compatibility == PublishFileCompatibility.DirectFmp4,
        )
    }
}

private fun PublishFileState.canPublishDirectly(): Boolean {
    return this is PublishFileState.Ready && file.compatibility == PublishFileCompatibility.DirectFmp4
}

private val PublishSourceType.marker: String
    get() = when (this) {
        PublishSourceType.Camera -> "CAM"
        PublishSourceType.File -> "FIL"
        PublishSourceType.Screen -> "SCR"
    }

private val PublishSourceType.labelRes: Int
    @StringRes get() = when (this) {
        PublishSourceType.Camera -> R.string.publish_source_camera
        PublishSourceType.File -> R.string.publish_source_file
        PublishSourceType.Screen -> R.string.publish_source_screen
    }

private val PublishSourceType.stateRes: Int
    @StringRes get() = when (this) {
        PublishSourceType.Camera,
        PublishSourceType.Screen,
        -> R.string.source_state_ready
        PublishSourceType.File -> R.string.source_state_select
    }

private val PublishSourceType.noteRes: Int
    @StringRes get() = when (this) {
        PublishSourceType.Camera -> R.string.publish_source_camera_note
        PublishSourceType.File -> R.string.publish_source_file_note
        PublishSourceType.Screen -> R.string.publish_source_screen_note
    }

private val CameraLensFacing.labelRes: Int
    @StringRes get() = when (this) {
        CameraLensFacing.Back -> R.string.camera_rear
        CameraLensFacing.Front -> R.string.camera_front
    }

private val CameraQualityPreset.labelRes: Int
    @StringRes get() = when (this) {
        CameraQualityPreset.Auto -> R.string.camera_quality_auto
        CameraQualityPreset.Quality -> R.string.camera_quality_quality
    }

private val CameraQualityPreset.noteRes: Int
    @StringRes get() = when (this) {
        CameraQualityPreset.Auto -> R.string.camera_quality_auto_note
        CameraQualityPreset.Quality -> R.string.camera_quality_quality_note
    }

private val PublishFileContainer.labelRes: Int
    @StringRes get() = when (this) {
        PublishFileContainer.FragmentedMp4 -> R.string.file_container_fragmented_mp4
        PublishFileContainer.Mp4 -> R.string.file_container_mp4
        PublishFileContainer.Other -> R.string.file_container_other
    }

private val PublishFileCompatibility.labelRes: Int
    @StringRes get() = when (this) {
        PublishFileCompatibility.DirectFmp4 -> R.string.file_path_direct
        PublishFileCompatibility.NeedsRemux -> R.string.file_path_remux
        PublishFileCompatibility.Unsupported -> R.string.file_path_unsupported
    }

private val PublishFileCompatibility.noteRes: Int
    @StringRes get() = when (this) {
        PublishFileCompatibility.DirectFmp4 -> R.string.file_path_direct_note
        PublishFileCompatibility.NeedsRemux -> R.string.file_path_remux_note
        PublishFileCompatibility.Unsupported -> R.string.file_path_unsupported_note
    }

@Composable
private fun ProbedPublishFile.fileSummary(): String {
    val duration = durationUs?.let(::formatDuration) ?: "--:--"
    val size = sizeBytes?.let(::formatFileSize) ?: stringResource(R.string.file_size_unknown)
    return listOfNotNull(mimeType, duration, size).joinToString(" · ")
}

@Composable
private fun ProbedPublishFile.trackSummary(): String {
    return tracks
        .filter { it.kind != PublishFileTrackKind.Other }
        .joinToString("\n") { track ->
            when (track.kind) {
                PublishFileTrackKind.Video -> {
                    val dimensions = if (track.width != null && track.height != null) {
                        " ${track.width}x${track.height}"
                    } else {
                        ""
                    }
                    "${track.mimeType}$dimensions"
                }
                PublishFileTrackKind.Audio -> {
                    val audio = buildList {
                        track.sampleRate?.let { add("${it / 1000} kHz") }
                        track.channelCount?.let { add("$it ch") }
                    }.joinToString(" ")
                    "${track.mimeType}${audio.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()}"
                }
                PublishFileTrackKind.Other -> track.mimeType
            }
        }
        .ifEmpty { stringResource(R.string.file_no_media_tracks) }
}

private fun formatDuration(durationUs: Long): String {
    val totalSeconds = durationUs / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(sizeBytes: Long): String {
    return if (sizeBytes >= 1_000_000) {
        "%.1f MB".format(sizeBytes / 1_000_000.0)
    } else {
        "%.1f KB".format(sizeBytes / 1_000.0)
    }
}
