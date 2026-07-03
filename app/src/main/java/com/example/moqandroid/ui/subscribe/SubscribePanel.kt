package com.example.moqandroid.ui.subscribe

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.moqandroid.R
import com.example.moqandroid.ui.components.Label
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.components.SectionHeader
import com.example.moqandroid.ui.components.StatusPanel
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState

@Composable
fun SubscribePanel(
    state: SubscribePanelState,
    actions: SubscribePanelActions,
) {
    Page {
        SectionHeader(stringResource(R.string.subscribe_title), stringResource(R.string.subscribe_description))
        LabeledField(
            label = stringResource(R.string.broadcast_label),
            value = state.broadcast,
            placeholder = "bbb.hang",
            onValueChange = actions.onBroadcastChange,
            onSubmit = actions.onSubscribe,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryAction(stringResource(R.string.subscribe_action), actions.onSubscribe)
        Spacer(Modifier.height(24.dp))
        Label(stringResource(R.string.stats_label))
        Spacer(Modifier.height(8.dp))
        StatusPanel(state.status)
    }
}
