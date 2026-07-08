package com.example.moqandroid.ui.app

import com.example.moqandroid.config.AppLanguage
import com.example.moqandroid.publish.encoder.H264ProfilePreference

data class RelayConfigUiState(
    val relayUrl: String,
    val status: String,
)

data class SettingsUiState(
    val relayUrl: String,
    val status: String,
    val language: AppLanguage,
    val languageOptions: List<AppLanguage>,
    val publishCompatibilityMode: Boolean,
    val h264ProfilePreference: H264ProfilePreference,
    val h264ProfileOptions: List<H264ProfilePreference>,
)

data class RelayConfigActions(
    val onRelayUrlChange: (String) -> Unit,
    val onContinue: () -> Unit,
)

data class PublishPanelState(
    val broadcast: String,
    val includeSystemAudio: Boolean,
    val status: String,
)

data class PublishPanelActions(
    val onBroadcastChange: (String) -> Unit,
    val onIncludeSystemAudioChange: (Boolean) -> Unit,
    val onPublish: () -> Unit,
    val onStopPublish: () -> Unit,
)

data class SubscribePanelState(
    val broadcast: String,
    val status: String,
)

data class SubscribePanelActions(
    val onBroadcastChange: (String) -> Unit,
    val onSubscribe: () -> Unit,
)

data class MainTabsState(
    val publish: PublishPanelState,
    val subscribe: SubscribePanelState,
)

data class MainTabsActions(
    val publish: PublishPanelActions,
    val subscribe: SubscribePanelActions,
    val onSettings: () -> Unit,
)

data class RelaySettingsActions(
    val onRelayUrlChange: (String) -> Unit,
    val onLanguageChange: (AppLanguage) -> Unit,
    val onPublishCompatibilityModeChange: (Boolean) -> Unit,
    val onH264ProfilePreferenceChange: (H264ProfilePreference) -> Unit,
    val onSave: () -> Unit,
    val onBack: () -> Unit,
)
