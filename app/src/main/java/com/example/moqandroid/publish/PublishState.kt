package com.example.moqandroid.publish

sealed class PublishState {
    data object Preparing : PublishState()
    data class Connecting(val relayUrl: String, val broadcastName: String) : PublishState()
    data class Publishing(
        val relayUrl: String,
        val broadcastName: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val frameRate: Int,
        val audioEnabled: Boolean,
    ) : PublishState()

    data class Stats(
        val relayUrl: String,
        val broadcastName: String,
        val frames: Int,
        val bytes: Long,
        val bitrateKbps: Double,
    ) : PublishState()

    data object Stopping : PublishState()
    data object Stopped : PublishState()
    data class AudioFailed(val reason: String) : PublishState()
    data class Failed(val reason: String) : PublishState()

    fun message(): String = when (this) {
        Preparing -> "Preparing screen publish ..."
        is Connecting -> "Publishing to $relayUrl ...\nbroadcast=$broadcastName"
        is Publishing -> {
            val audio = if (audioEnabled) "system audio=on" else "system audio=off"
            "Publishing to $relayUrl\nbroadcast=$broadcastName\nvideo=${width}x$height ${frameRate}fps target=${bitrate / 1_000}kbps\n$audio"
        }
        is Stats -> "Publishing to $relayUrl\nbroadcast=$broadcastName\nsent=$frames frames bytes=$bytes bitrate=${"%.0f".format(bitrateKbps)}kbps"
        Stopping -> "Stopping screen publish ..."
        Stopped -> "Screen publish stopped."
        is AudioFailed -> "System audio capture failed:\n$reason\nVideo publish is still running."
        is Failed -> "Screen publish failed:\n$reason"
    }
}
