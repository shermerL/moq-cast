package com.example.moqandroid.ui.subscribe

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        SectionHeader("订阅", "输入 broadcast，进入全屏播放器订阅并播放视频流。")
        LabeledField(
            label = "Broadcast",
            value = state.broadcast,
            placeholder = "bbb.hang",
            onValueChange = actions.onBroadcastChange,
            onSubmit = actions.onSubscribe,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryAction("Subscribe", actions.onSubscribe)
        Spacer(Modifier.height(24.dp))
        Label("Stats")
        Spacer(Modifier.height(8.dp))
        StatusPanel(state.status)
    }
}
