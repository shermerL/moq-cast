package com.example.moqandroid.config

import java.net.URI

fun validateRelayUrl(value: String): String? {
    if (value.isEmpty()) return "Relay URL cannot be empty."

    val uri = runCatching { URI(value) }.getOrNull()
        ?: return "Relay URL is invalid."
    if (uri.scheme != "http" && uri.scheme != "https") return "Relay URL must start with http:// or https://."
    if (uri.host.isNullOrBlank()) return "Relay URL must include a host."

    return null
}
