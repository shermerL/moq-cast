package com.example.moqandroid.network.lan.server

internal data class PeerSessionOrigins<T : Any>(
    val publishOrigin: T,
    val consumeOrigin: T,
)

internal class PeerMediaOrigins<T : Any> {
    private var current: PeerSessionOrigins<T>? = null

    fun attach(localPublishOrigin: T, remoteReceiveOrigin: T) {
        require(localPublishOrigin !== remoteReceiveOrigin) {
            "LAN publish and receive origins must be distinct."
        }
        current = PeerSessionOrigins(
            publishOrigin = localPublishOrigin,
            consumeOrigin = remoteReceiveOrigin,
        )
    }

    fun session(): PeerSessionOrigins<T>? = current

    fun localPublishOrigin(): T? = current?.publishOrigin

    fun remoteReceiveOrigin(): T? = current?.consumeOrigin

    fun detach(localPublishOrigin: T, remoteReceiveOrigin: T) {
        val attached = current ?: return
        if (attached.publishOrigin === localPublishOrigin && attached.consumeOrigin === remoteReceiveOrigin) {
            current = null
        }
    }
}
