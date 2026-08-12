package com.example.moqandroid.ui.nearby

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.moqandroid.R
import com.example.moqandroid.network.lan.discovery.DiscoveryPhase
import com.example.moqandroid.network.lan.mesh.PeerConnectionState
import com.example.moqandroid.network.lan.server.PeerListenerState
import com.example.moqandroid.ui.components.bottomSystemInset
import com.example.moqandroid.ui.components.topSystemInset
import com.example.moqandroid.ui.theme.BorderColor
import com.example.moqandroid.ui.theme.MoqAppTheme
import com.example.moqandroid.ui.theme.PrimaryColor
import com.example.moqandroid.ui.theme.SurfaceColor
import com.example.moqandroid.ui.theme.SurfaceMuted
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary
import com.example.moqandroid.ui.theme.WorkspaceBackground
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearbyScreen(
    state: NearbyUiState,
    actions: NearbyActions,
) {
    val backFocusRequester = remember { FocusRequester() }
    val shareFocusRequester = remember { FocusRequester() }
    val sessionFocusRequester = remember { FocusRequester() }
    var focusedTarget by remember { mutableStateOf<NearbyFocusTarget?>(null) }

    BackHandler(onBack = actions.onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> actions.onStartDiscovery()
                Lifecycle.Event.ON_STOP -> actions.onStopDiscovery()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            actions.onStartDiscovery()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            actions.onStopDiscovery()
        }
    }
    LaunchedEffect(Unit) {
        backFocusRequester.requestFocus()
    }
    LaunchedEffect(state.peers, state.mediaState, state.canShareScreen, focusedTarget) {
        val target = focusedTarget as? NearbyFocusTarget.Watch ?: return@LaunchedEffect
        val canKeepFocus = state.peers.any { peer ->
            peer.peerId == target.peerId &&
                NearbyActionPolicy.project(state.mediaState, state.canShareScreen, peer.canWatch).canWatch
        }
        if (!canKeepFocus) {
            val globalActions = NearbyActionPolicy.project(state.mediaState, state.canShareScreen, false)
            if (globalActions.canStop) {
                sessionFocusRequester.requestFocus()
            } else if (globalActions.canShareScreen) {
                shareFocusRequester.requestFocus()
            } else {
                backFocusRequester.requestFocus()
            }
        }
    }
    MoqAppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WorkspaceBackground)
                .topSystemInset()
                .bottomSystemInset(),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp),
            ) {
                NearbyTopBar(
                    actions = actions,
                    backFocusRequester = backFocusRequester,
                    onFocused = { focusedTarget = NearbyFocusTarget.Navigation },
                )
                DiscoverySummary(state)
                Spacer(Modifier.height(12.dp))
                ActiveSessionBar(
                    state = state,
                    actions = actions,
                    focusRequester = sessionFocusRequester,
                    onFocused = { focusedTarget = NearbyFocusTarget.ActiveSession },
                )
                if (state.mediaState != NearbyMediaState.ConnectedIdle) Spacer(Modifier.height(12.dp))
                ScreenShareAction(
                    state = state,
                    actions = actions,
                    focusRequester = shareFocusRequester,
                    onFocused = { focusedTarget = NearbyFocusTarget.Share },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.nearby_devices),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                if (state.peers.isEmpty()) {
                    EmptyPeerList(state.phase, Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.peers, key = PeerListItem::peerId) { peer ->
                            PeerRow(
                                peer = peer,
                                watchEnabled = NearbyActionPolicy.project(
                                    state.mediaState,
                                    state.canShareScreen,
                                    peer.canWatch,
                                ).canWatch,
                                onWatch = actions.onWatch,
                                onWatchFocused = {
                                    focusedTarget = NearbyFocusTarget.Watch(peer.peerId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyTopBar(
    actions: NearbyActions,
    backFocusRequester: FocusRequester,
    onFocused: () -> Unit,
) {
    var backFocused by remember { mutableStateOf(false) }
    var refreshFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = actions.onBack,
            modifier = Modifier
                .focusRequester(backFocusRequester)
                .onFocusChanged {
                    backFocused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .then(
                    if (backFocused) Modifier.border(2.dp, PrimaryColor, CircleShape) else Modifier,
                ),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = TextPrimary)
        }
        Text(
            text = stringResource(R.string.nearby_title),
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = actions.onRefresh,
            modifier = Modifier
                .onFocusChanged {
                    refreshFocused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .then(
                    if (refreshFocused) Modifier.border(2.dp, PrimaryColor, CircleShape) else Modifier,
                ),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh), tint = TextPrimary)
        }
    }
}

@Composable
private fun DiscoverySummary(state: NearbyUiState) {
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadarIndicator(scanning = state.phase == DiscoveryPhase.Scanning || state.phase == DiscoveryPhase.Starting)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = state.statusText(),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.nearby_peer_count, state.peers.size),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.serverStatusText(),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                if (state.serverState.activeSessionCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.nearby_inbound_connections,
                            state.serverState.activeSessionCount,
                        ),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.nearby_inbound_connections_note),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenShareAction(
    state: NearbyUiState,
    actions: NearbyActions,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val availability = NearbyActionPolicy.project(state.mediaState, state.canShareScreen, false)
    val modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .focusRequester(focusRequester)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }
        .then(
            if (focused) Modifier.border(2.dp, PrimaryColor, RoundedCornerShape(24.dp)) else Modifier,
        )
    Button(
        onClick = actions.onShareScreen,
        enabled = availability.canShareScreen,
        modifier = modifier,
    ) {
        Text(stringResource(R.string.nearby_share_screen))
    }
}

@Composable
private fun ActiveSessionBar(
    state: NearbyUiState,
    actions: NearbyActions,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
) {
    if (state.mediaState == NearbyMediaState.ConnectedIdle) return

    val availability = NearbyActionPolicy.project(state.mediaState, state.canShareScreen, false)
    var primaryFocused by remember { mutableStateOf(false) }
    Surface(
        color = SurfaceMuted,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.nearby_active_session),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = state.mediaState.activeSessionText(),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (availability.canOpenActiveSession) {
                OutlinedButton(
                    onClick = actions.onOpenActiveSession,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(stringResource(R.string.nearby_open_session))
                }
            }
            OutlinedButton(
                onClick = actions.onStopMedia,
                enabled = availability.canStop,
                modifier = Modifier
                    .height(40.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        primaryFocused = it.isFocused
                        if (it.isFocused) onFocused()
                    }
                    .then(
                        if (primaryFocused) Modifier.border(2.dp, PrimaryColor, RoundedCornerShape(20.dp)) else Modifier,
                    ),
            ) {
                Text(
                    if (state.mediaState == NearbyMediaState.StoppingScreen) {
                        stringResource(R.string.nearby_stopping)
                    } else {
                        stringResource(R.string.stop)
                    },
                )
            }
        }
    }
}

@Composable
private fun RadarIndicator(scanning: Boolean) {
    val transition = rememberInfiniteTransition(label = "nearby-radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "nearby-radar-angle",
    )

    Canvas(Modifier.size(40.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = 1.5.dp.toPx()
        listOf(0.32f, 0.62f, 0.92f).forEach { scale ->
            drawCircle(
                color = BorderColor,
                radius = size.minDimension * scale / 2f,
                center = center,
                style = Stroke(width = strokeWidth),
            )
        }
        drawCircle(color = PrimaryColor, radius = 4.dp.toPx(), center = center)
        if (scanning) {
            val radians = Math.toRadians(angle.toDouble())
            val radius = size.minDimension * 0.46f
            drawLine(
                color = PrimaryColor,
                start = center,
                end = Offset(
                    x = center.x + cos(radians).toFloat() * radius,
                    y = center.y + sin(radians).toFloat() * radius,
                ),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun EmptyPeerList(phase: DiscoveryPhase, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = if (phase == DiscoveryPhase.Failed) {
                stringResource(R.string.nearby_discovery_failed)
            } else {
                stringResource(R.string.nearby_empty)
            },
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun PeerRow(
    peer: PeerListItem,
    watchEnabled: Boolean,
    onWatch: (PeerListItem) -> Unit,
    onWatchFocused: () -> Unit,
) {
    var watchFocused by remember(peer.peerId) { mutableStateOf(false) }
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (watchFocused) 2.dp else 1.dp, if (watchFocused) PrimaryColor else BorderColor),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${peer.connectionStatusText()} · ${peer.screenStatusText()}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = peer.endpoint,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (peer.canWatch) {
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { onWatch(peer) },
                    enabled = watchEnabled,
                    modifier = Modifier
                        .height(40.dp)
                        .onFocusChanged {
                            watchFocused = it.isFocused
                            if (it.isFocused) onWatchFocused()
                        }
                        .then(
                            if (watchFocused) {
                                Modifier.border(2.dp, PrimaryColor, RoundedCornerShape(20.dp))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(stringResource(R.string.nearby_watch))
                }
            }
        }
    }
}

private sealed interface NearbyFocusTarget {
    data object Navigation : NearbyFocusTarget
    data object Share : NearbyFocusTarget
    data object ActiveSession : NearbyFocusTarget
    data class Watch(val peerId: String) : NearbyFocusTarget
}

@Composable
private fun NearbyUiState.serverStatusText(): String {
    return when (val listener = serverState.lifecycle) {
        PeerListenerState.Idle -> stringResource(R.string.nearby_receiver_idle)
        PeerListenerState.Starting -> stringResource(R.string.nearby_receiver_starting)
        is PeerListenerState.Listening -> stringResource(R.string.nearby_receiver_ready)
        is PeerListenerState.Failed -> stringResource(R.string.nearby_receiver_failed, listener.reason)
    }
}

@Composable
private fun PeerListItem.connectionStatusText(): String = when (val state = connectionState) {
    PeerConnectionState.Discovered -> stringResource(R.string.nearby_discovered)
    PeerConnectionState.Waiting -> stringResource(R.string.nearby_waiting)
    PeerConnectionState.Connecting -> stringResource(R.string.nearby_connecting)
    PeerConnectionState.Connected -> stringResource(R.string.nearby_connected)
    PeerConnectionState.Reconnecting -> stringResource(R.string.nearby_reconnecting)
    is PeerConnectionState.Failed -> stringResource(R.string.nearby_connection_failed, state.reason)
    PeerConnectionState.Lost -> stringResource(R.string.nearby_lost)
}

@Composable
private fun PeerListItem.screenStatusText(): String = if (canWatch) {
    stringResource(R.string.nearby_screen_available)
} else {
    stringResource(R.string.nearby_screen_unavailable)
}

@Composable
private fun NearbyMediaState.activeSessionText(): String = when (this) {
    NearbyMediaState.ConnectedIdle -> ""
    NearbyMediaState.PreparingScreen -> stringResource(R.string.nearby_preparing_share)
    NearbyMediaState.PublishingScreen -> stringResource(R.string.nearby_sharing_screen)
    NearbyMediaState.StoppingScreen -> stringResource(R.string.nearby_stopping_share)
    is NearbyMediaState.ViewingRemote -> stringResource(R.string.nearby_viewing_device, publisherId)
}

@Composable
private fun NearbyUiState.statusText(): String {
    return when (phase) {
        DiscoveryPhase.Idle -> stringResource(R.string.nearby_idle)
        DiscoveryPhase.Starting -> stringResource(R.string.nearby_starting)
        DiscoveryPhase.Scanning -> stringResource(R.string.nearby_scanning)
        DiscoveryPhase.Failed -> stringResource(R.string.nearby_failed_code, errorCode ?: 0)
    }
}
