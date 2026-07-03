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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
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
import com.example.moqandroid.ui.components.ClearFocusOnEntry
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.components.StatusPanel
import com.example.moqandroid.ui.components.TopBar
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
            TopBar(
                title = stringResource(R.string.app_name),
                actionIcon = Icons.Default.Settings,
                actionDescription = stringResource(R.string.settings),
                onAction = actions.onSettings,
            )
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
                    icon = { Text("↑") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Subscribe,
                    onClick = { selectedTab = HomeTab.Subscribe },
                    label = { Text(stringResource(R.string.subscribe_tab)) },
                    icon = { Text("↓") },
                    colors = navColors(),
                )
            }
        }
    }
}

@Composable
fun RelaySettings(
    state: SettingsUiState,
    actions: RelaySettingsActions,
) {
    MoqAppTheme {
        ClearFocusOnEntry("relay-settings")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WorkspaceBackground),
        ) {
            TopBar(
                title = stringResource(R.string.settings),
                actionIcon = Icons.AutoMirrored.Filled.ArrowBack,
                actionDescription = stringResource(R.string.back),
                onAction = actions.onBack,
            )
            Page {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SettingsSection(title = stringResource(R.string.general_section)) {
                        LanguageSettingRow(
                            selected = state.language,
                            options = state.languageOptions,
                            onSelected = actions.onLanguageChange,
                        )
                    }

                    SettingsSection(title = stringResource(R.string.connection_section)) {
                        RelayUrlSettingRow(
                            value = state.relayUrl,
                            onValueChange = actions.onRelayUrlChange,
                        )
                    }

                    StatusPanel(state.status)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryAction(stringResource(R.string.save), actions.onSave)
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .sizeIn(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.weight(1.35f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            control()
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

    SettingRow(label = stringResource(R.string.language_label)) {
        Box {
            Surface(
                color = SurfaceMuted,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(selected.labelRes),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.language_options),
                        tint = TextSecondary,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
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
}

@Composable
private fun RelayUrlSettingRow(
    value: String,
    onValueChange: (String) -> Unit,
) {
    SettingRow(label = stringResource(R.string.relay_url_label)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text("http://host:4443/anon") },
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceMuted,
                unfocusedContainerColor = SurfaceMuted,
                focusedIndicatorColor = PrimaryColor,
                unfocusedIndicatorColor = BorderColor,
                cursorColor = PrimaryColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        )
    }
}
