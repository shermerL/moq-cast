package com.example.moqandroid.ui.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.R
import com.example.moqandroid.ui.components.LabeledField
import com.example.moqandroid.ui.components.MoqBrandHeader
import com.example.moqandroid.ui.components.MoqStatusCard
import com.example.moqandroid.ui.components.Page
import com.example.moqandroid.ui.components.PrimaryAction
import com.example.moqandroid.ui.app.SubscribePanelActions
import com.example.moqandroid.ui.app.SubscribePanelState
import com.example.moqandroid.ui.theme.BorderColor
import com.example.moqandroid.ui.theme.SurfaceColor
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary

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
        Spacer(Modifier.height(18.dp))
        PlayerPreview()
    }
}

@Composable
private fun PlayerPreview() {
    Surface(
        color = com.example.moqandroid.ui.theme.PlayerColor,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.player_preview_label),
                color = SurfaceColor,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.player_preview_meta),
                color = BorderColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "MoQ",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(TextPrimary, RoundedCornerShape(999.dp))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}
