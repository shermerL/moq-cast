package com.example.moqandroid.config

import android.content.Context

// Optional default for local development. Leave empty to require setup on first launch.
const val DEFAULT_RELAY_URL = ""

class AppConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_RELAY_URL
    }

    fun saveRelayUrl(relayUrl: String) {
        prefs.edit()
            .putString(KEY_RELAY_URL, relayUrl.trim())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "moq_android_config"
        private const val KEY_RELAY_URL = "relay_url"
    }
}
