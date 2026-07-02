package com.example.moqandroid.config

data class RelayConfig(val relayUrl: String) {
    companion object {
        fun fromInput(value: String): Result<RelayConfig> {
            val relayUrl = value.trim()
            val validationError = validateRelayUrl(relayUrl)
            return if (validationError == null) {
                Result.success(RelayConfig(relayUrl))
            } else {
                Result.failure(IllegalArgumentException(validationError))
            }
        }
    }
}

data class SettingsState(
    val relayUrl: String,
    val statusMessage: String,
) {
    fun withRelayUrl(value: String): SettingsState = copy(relayUrl = value)

    fun withStatus(message: String): SettingsState = copy(statusMessage = message)
}

