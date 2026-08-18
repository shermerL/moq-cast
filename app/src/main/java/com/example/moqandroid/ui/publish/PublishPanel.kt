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
                onSubmit = actions.onPublish,
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
                    onIncludeSystemAudioChange = actions.onIncludeSystemAudioChange,
                    onIncludeMicrophoneChange = actions.onIncludeMicrophoneChange,
                    onCameraLensFacingChange = actions.onCameraLensFacingChange,
                    onCameraQualityPresetChange = actions.onCameraQualityPresetChange,
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
    onIncludeSystemAudioChange: (Boolean) -> Unit,
    onIncludeMicrophoneChange: (Boolean) -> Unit,
    onCameraLensFacingChange: (CameraLensFacing) -> Unit,
    onCameraQualityPresetChange: (CameraQualityPreset) -> Unit,
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
    }
    Spacer(Modifier.height(26.dp))
    PrimaryAction(
        text = stringResource(
            when (selectedSource) {
                PublishSourceType.Camera -> R.string.publish_camera
                PublishSourceType.Screen -> R.string.publish_screen
            },
        ),
        onClick = onPublish,
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

private val PublishSourceType.marker: String
    get() = when (this) {
        PublishSourceType.Camera -> "CAM"
        PublishSourceType.Screen -> "SCR"
    }

private val PublishSourceType.labelRes: Int
    @StringRes get() = when (this) {
        PublishSourceType.Camera -> R.string.publish_source_camera
        PublishSourceType.Screen -> R.string.publish_source_screen
    }

private val PublishSourceType.stateRes: Int
    @StringRes get() = R.string.source_state_ready

private val PublishSourceType.noteRes: Int
    @StringRes get() = when (this) {
        PublishSourceType.Camera -> R.string.publish_source_camera_note
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
