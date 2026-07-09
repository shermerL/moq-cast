package com.example.moqandroid.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.R
import com.example.moqandroid.config.AppLanguage
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.ui.components.ClearFocusOnEntry
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.MoqBrandHeader
import com.example.moqandroid.ui.components.MoqInfoRow
import com.example.moqandroid.ui.components.MoqPill
import com.example.moqandroid.ui.components.MoqSettingSection
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.components.SecondaryAction
import com.example.moqandroid.ui.components.StatusPanel
import com.example.moqandroid.ui.components.bottomSystemInset
import com.example.moqandroid.ui.components.navColors
import com.example.moqandroid.ui.components.topSystemInset
import com.example.moqandroid.ui.publish.PublishPanel
import com.example.moqandroid.ui.subscribe.SubscribePanel
import com.example.moqandroid.ui.theme.BorderColor
import com.example.moqandroid.ui.theme.MoqAppTheme
import com.example.moqandroid.ui.theme.PrimaryColor
import com.example.moqandroid.ui.theme.SurfaceColor
import com.example.moqandroid.ui.theme.SurfaceMuted
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary
import com.example.moqandroid.ui.theme.WorkspaceBackground

private enum class HomeTab {
    Publish,
    Subscribe,
    Settings,
}

@Composable
fun FirstRunConfig(
    state: RelayConfigUiState,
    actions: RelayConfigActions,
) {
    MoqAppTheme {
        ClearFocusOnEntry("first-run-config")
        Page(modifier = Modifier.topSystemInset()) {
            Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            StatusPanel(state.status)
            Spacer(Modifier.height(24.dp))
            LabeledField(
                label = stringResource(R.string.relay_url_label),
                value = state.relayUrl,
                placeholder = "http://host:4443/anon",
                onValueChange = actions.onRelayUrlChange,
                onSubmit = actions.onContinue,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryAction(stringResource(R.string.continue_action), actions.onContinue)
        }
    }
}

@Composable
fun MainTabs(
    state: MainTabsState,
    actions: MainTabsActions,
) {
    var selectedTab by remember { mutableStateOf(HomeTab.Publish) }

    MoqAppTheme {
        ClearFocusOnEntry(selectedTab)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WorkspaceBackground),
        ) {
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    HomeTab.Publish -> PublishPanel(
                        state = state.publish,
                        actions = actions.publish,
                    )

                    HomeTab.Subscribe -> SubscribePanel(
                        state = state.subscribe,
                        actions = actions.subscribe,
                    )

                    HomeTab.Settings -> SettingsPanel(
                        state = state.settings,
                        actions = actions.settings,
                    )
                }
            }
            NavigationBar(
                containerColor = SurfaceColor,
                tonalElevation = 0.dp,
                modifier = Modifier.bottomSystemInset(),
            ) {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Publish,
                    onClick = { selectedTab = HomeTab.Publish },
                    label = { Text(stringResource(R.string.publish_tab)) },
                    icon = { Text("UP") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Subscribe,
                    onClick = { selectedTab = HomeTab.Subscribe },
                    label = { Text(stringResource(R.string.subscribe_tab)) },
                    icon = { Text("DOWN") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Settings,
                    onClick = { selectedTab = HomeTab.Settings },
                    label = { Text(stringResource(R.string.settings)) },
                    icon = { Text("SET") },
                    colors = navColors(),
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    ClearFocusOnEntry("relay-settings")
    Page {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            MoqBrandHeader(
                appName = stringResource(R.string.app_name),
                relayLabel = stringResource(R.string.local_relay_chip),
            )

            MoqSettingSection(title = stringResource(R.string.general_section)) {
                LanguageSettingRow(
                    selected = state.language,
                    options = state.languageOptions,
                    onSelected = actions.onLanguageChange,
                )
                Spacer(Modifier.height(18.dp))
                StaticSettingRow(
                    label = stringResource(R.string.theme_label),
                    note = stringResource(R.string.theme_note),
                    pill = stringResource(R.string.theme_system),
                )
            }

            MoqSettingSection(title = stringResource(R.string.publish_section)) {
                ToggleSettingRow(
                    label = stringResource(R.string.publish_compatibility_mode),
                    note = stringResource(R.string.publish_compatibility_note),
                    checked = state.publishCompatibilityMode,
                    onCheckedChange = actions.onPublishCompatibilityModeChange,
                )
                Spacer(Modifier.height(18.dp))
                H264ProfileSettingRow(
                    selected = state.h264ProfilePreference,
                    options = state.h264ProfileOptions,
                    onSelected = actions.onH264ProfilePreferenceChange,
                )
            }

            MoqSettingSection(title = stringResource(R.string.playback_section)) {
                ToggleSettingRow(
                    label = stringResource(R.string.show_playback_stats),
                    note = stringResource(R.string.show_playback_stats_note),
                    checked = state.showPlaybackStats,
                    onCheckedChange = actions.onShowPlaybackStatsChange,
                )
            }

            MoqSettingSection(title = stringResource(R.string.connection_section)) {
                RelayUrlSettingRow(
                    value = state.relayUrl,
                    onValueChange = actions.onRelayUrlChange,
                )
            }

            MoqSettingSection(title = stringResource(R.string.about_section)) {
                StaticSettingRow(
                    label = stringResource(R.string.about_app_label),
                    note = stringResource(R.string.about_app_note),
                    pill = stringResource(R.string.about_version),
                )
            }

            StatusPanel(state.status)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction(stringResource(R.string.save), actions.onSave)
    }
}

@Composable
private fun SettingRow(
    label: String,
    note: String? = null,
    control: @Composable () -> Unit,
) {
    MoqInfoRow(
        label = label,
        note = note,
    ) {
        Box(contentAlignment = Alignment.CenterEnd) {
            control()
        }
    }
}

@Composable
private fun StaticSettingRow(
    label: String,
    note: String,
    pill: String,
) {
    SettingRow(label = label, note = note) {
        MoqPill(text = pill, selected = false)
    }
}

@Composable
private fun PillDropdown(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    contentDescription: String,
    menu: @Composable () -> Unit,
) {
    Box {
        Surface(
            color = SurfaceMuted,
            shape = RoundedCornerShape(999.dp),
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(min = 96.dp, max = 132.dp)
                .height(30.dp)
                .clickable { onExpandedChange(true) },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = contentDescription,
                    tint = TextSecondary,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            menu()
        }
    }
}

@Composable
private fun ToggleSettingRow(
    label: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(label = label, note = note) {
        MoqPill(
            text = if (checked) stringResource(R.string.system_audio_on) else stringResource(R.string.system_audio_off),
            selected = checked,
            onClick = { onCheckedChange(!checked) },
        )
    }
}

@Composable
private fun H264ProfileSettingRow(
    selected: H264ProfilePreference,
    options: List<H264ProfilePreference>,
    onSelected: (H264ProfilePreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingRow(
        label = stringResource(R.string.h264_profile_label),
        note = stringResource(R.string.h264_profile_note),
    ) {
        PillDropdown(
            text = stringResource(selected.labelRes),
            expanded = expanded,
            onExpandedChange = { expanded = it },
            contentDescription = stringResource(R.string.h264_profile_options),
        ) {
            options.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(stringResource(profile.labelRes)) },
                    onClick = {
                        expanded = false
                        onSelected(profile)
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageSettingRow(
    selected: AppLanguage,
    options: List<AppLanguage>,
    onSelected: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    SettingRow(
        label = stringResource(R.string.language_label),
        note = stringResource(R.string.language_note),
    ) {
        PillDropdown(
            text = stringResource(selected.labelRes),
            expanded = expanded,
            onExpandedChange = { expanded = it },
            contentDescription = stringResource(R.string.language_options),
        ) {
            options.forEach { language ->
                DropdownMenuItem(
                    text = { Text(stringResource(language.labelRes)) },
                    onClick = {
                        expanded = false
                        onSelected(language)
                    },
                )
            }
        }
    }
}

@Composable
private fun RelayUrlSettingRow(
    value: String,
    onValueChange: (String) -> Unit,
) {
    SettingRow(label = stringResource(R.string.relay_url_label), note = stringResource(R.string.relay_url_note)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text("http://host:4443/anon") },
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceMuted,
                unfocusedContainerColor = SurfaceMuted,
                focusedIndicatorColor = PrimaryColor,
                unfocusedIndicatorColor = BorderColor,
                cursorColor = PrimaryColor,
            ),
            modifier = Modifier
                .widthIn(min = 150.dp, max = 220.dp)
                .height(56.dp),
        )
    }
}
