package com.example.moqandroid.ui.publish

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.ui.components.Label
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.components.SecondaryAction
import com.example.moqandroid.ui.components.SectionHeader
import com.example.moqandroid.ui.components.StatusPanel
import com.example.moqandroid.ui.app.PublishPanelActions
import com.example.moqandroid.ui.app.PublishPanelState
import com.example.moqandroid.ui.theme.PrimaryColor
import com.example.moqandroid.ui.theme.TextPrimary

@Composable
fun PublishPanel(
    state: PublishPanelState,
    actions: PublishPanelActions,
) {
    Page {
        SectionHeader("发布", "输入 broadcast，采集当前 Android 屏幕并发布到 relay。")
        LabeledField(
            label = "Broadcast",
            value = state.broadcast,
            placeholder = "bbb.hang",
            onValueChange = actions.onBroadcastChange,
            onSubmit = actions.onPublish,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = state.includeSystemAudio,
                onCheckedChange = actions.onIncludeSystemAudioChange,
                colors = CheckboxDefaults.colors(checkedColor = PrimaryColor),
            )
            Text("Include system audio", color = TextPrimary, fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryAction("Publish screen", actions.onPublish)
        Spacer(Modifier.height(8.dp))
        SecondaryAction("Stop publish", actions.onStopPublish)
        Spacer(Modifier.height(24.dp))
        Label("Stats")
        Spacer(Modifier.height(8.dp))
        StatusPanel(state.status)
    }
}
