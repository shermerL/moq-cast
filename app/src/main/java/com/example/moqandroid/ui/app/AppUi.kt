package com.example.moqandroid.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.moqandroid.ui.theme.MoqAppTheme
import com.example.moqandroid.ui.theme.SurfaceColor
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
            Text("MoQScreenCast", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            StatusPanel(state.status)
            Spacer(Modifier.height(24.dp))
            LabeledField(
                label = "Relay URL",
                value = state.relayUrl,
                placeholder = "http://host:4443/anon",
                onValueChange = actions.onRelayUrlChange,
                onSubmit = actions.onContinue,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryAction("Continue", actions.onContinue)
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
                title = "MoQScreenCast",
                actionIcon = Icons.Default.Settings,
                actionDescription = "Settings",
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
                    label = { Text("发布") },
                    icon = { Text("↑") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Subscribe,
                    onClick = { selectedTab = HomeTab.Subscribe },
                    label = { Text("订阅") },
                    icon = { Text("↓") },
                    colors = navColors(),
                )
            }
        }
    }
}

@Composable
fun RelaySettings(
    state: RelayConfigUiState,
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
                title = "Settings",
                actionIcon = Icons.AutoMirrored.Filled.ArrowBack,
                actionDescription = "Back",
                onAction = actions.onBack,
            )
            Page {
                Text("Relay", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Configure the MoQ relay URL used by publish and subscribe.",
                    fontSize = 15.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(24.dp))
                LabeledField(
                    label = "Relay URL",
                    value = state.relayUrl,
                    placeholder = "http://host:4443/anon",
                    onValueChange = actions.onRelayUrlChange,
                    onSubmit = actions.onSave,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryAction("Save", actions.onSave)
                Spacer(Modifier.height(12.dp))
                StatusPanel(state.status)
            }
        }
    }
}
