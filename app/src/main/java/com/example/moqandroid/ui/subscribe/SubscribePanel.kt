package com.example.moqandroid.ui.subscribe

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.moqandroid.R
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.MoqBrandHeader
import com.example.moqandroid.ui.components.MoqStatusCard
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction

@Composable
fun SubscribePanel(
    state: SubscribePanelState,
    actions: SubscribePanelActions,
) {
    Page {
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
            onSubmit = actions.onSubscribe,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryAction(stringResource(R.string.subscribe_action), actions.onSubscribe)
        Spacer(Modifier.height(16.dp))
        MoqStatusCard(
            title = stringResource(R.string.status_waiting_title),
            body = state.status,
        )
    }
}
