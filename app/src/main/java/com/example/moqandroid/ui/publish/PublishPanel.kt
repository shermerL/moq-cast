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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.R
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
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary

private enum class PublishSourceOption(
    @StringRes val labelRes: Int,
    val marker: String,
    @StringRes val stateRes: Int,
    val enabled: Boolean,
) {
    Camera(R.string.publish_source_camera, "CAM", R.string.source_state_next, enabled = false),
    File(R.string.publish_source_file, "FIL", R.string.source_state_next, enabled = false),
    Screen(R.string.publish_source_screen, "SCR", R.string.source_state_ready, enabled = true),
}

@Composable
fun PublishPanel(
    state: PublishPanelState,
    actions: PublishPanelActions,
) {
    var selectedSource by remember { mutableStateOf(PublishSourceOption.Screen) }

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
                    includeSystemAudio = state.includeSystemAudio,
                    onIncludeSystemAudioChange = actions.onIncludeSystemAudioChange,
                    onStopPublish = actions.onStopPublish,
                )
            } else {
                ReadyContent(
                    status = state.status,
                    selectedSource = selectedSource,
                    onSelectedSource = { selectedSource = it },
                    includeSystemAudio = state.includeSystemAudio,
                    onIncludeSystemAudioChange = actions.onIncludeSystemAudioChange,
                    onPublish = actions.onPublish,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    status: String,
    selectedSource: PublishSourceOption,
    onSelectedSource: (PublishSourceOption) -> Unit,
    includeSystemAudio: Boolean,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
    onPublish: () -> Unit,
) {
    SourcePicker(
        selected = selectedSource,
        onSelected = onSelectedSource,
    )
    Spacer(Modifier.height(32.dp))
    SystemAudioRow(
        includeSystemAudio = includeSystemAudio,
        onIncludeSystemAudioChange = onIncludeSystemAudioChange,
    )
    Spacer(Modifier.height(26.dp))
    PrimaryAction(stringResource(R.string.publish_screen), onPublish)
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
    includeSystemAudio: Boolean,
    onIncludeSystemAudioChange: (Boolean) -> Unit,
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
        note = stringResource(R.string.publish_source_screen_note),
    ) {
        MoqPill(
            text = stringResource(R.string.publish_source_screen),
            selected = false,
        )
    }
    Spacer(Modifier.height(24.dp))
    SystemAudioRow(
        includeSystemAudio = includeSystemAudio,
        onIncludeSystemAudioChange = onIncludeSystemAudioChange,
    )
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
    selected: PublishSourceOption,
    onSelected: (PublishSourceOption) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        PublishSourceOption.entries.forEach { source ->
            MoqSourceCard(
                marker = source.marker,
                title = stringResource(source.labelRes),
                subtitle = stringResource(source.stateRes),
                selected = selected == source,
                enabled = source.enabled,
                onClick = { if (source.enabled) onSelected(source) },
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
