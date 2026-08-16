package com.example.moqandroid.network.lan.peer

import com.example.moqandroid.network.lan.discovery.DiscoveredPeer

data class PeerPublishTarget(
    val peerName: String,
    val url: String,
    val fingerprint: String,
)

fun DiscoveredPeer.toPublishTarget(): Result<PeerPublishTarget> = runCatching {
    val fingerprint = requireNotNull(fingerprint) { "The peer did not advertise a TLS fingerprint." }
    val address = addresses
        .sortedWith(compareBy<String> { it.contains(':') }.thenBy { it })
        .firstOrNull()
        ?: error("The peer did not advertise a reachable address.")
    val url = nodeUrl ?: run {
        val credential = requireNotNull(credential) { "The peer did not advertise a connection credential." }
        val authority = if (address.contains(':')) "[$address]:$port" else "$address:$port"
        "moqt://$authority/.cluster/$credential"
    }
    PeerPublishTarget(
        peerName = serviceName,
        url = url,
        fingerprint = fingerprint,
    )
}
