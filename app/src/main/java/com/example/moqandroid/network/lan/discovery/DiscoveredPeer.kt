package com.example.moqandroid.network.lan.discovery

/** A resolved MoQ service advertised on the local network. */
data class DiscoveredPeer(
    val serviceName: String,
    val addresses: List<String>,
    val port: Int,
    val fingerprint: String?,
    val nodeUrl: String?,
    val credential: String?,
) {
    /** Stable peer identity advertised as the DNS-SD service instance name. */
    val id: String
        get() = serviceName
}

enum class DiscoveryPhase {
    Idle,
    Starting,
    Scanning,
    Failed,
}

data class DiscoveryState(
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    val peers: List<DiscoveredPeer> = emptyList(),
    val errorCode: Int? = null,
)
