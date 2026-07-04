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

    fun loadLanguage(): AppLanguage {
        return AppLanguage.fromStorageValue(prefs.getString(KEY_LANGUAGE, null))
    }

    fun loadPublishCompatibilityMode(): Boolean {
        return prefs.getBoolean(KEY_PUBLISH_COMPATIBILITY_MODE, false)
    }

    fun saveRelayUrl(relayUrl: String) {
        prefs.edit()
            .putString(KEY_RELAY_URL, relayUrl.trim())
            .apply()
    }

    fun saveLanguage(language: AppLanguage) {
        prefs.edit()
            .putString(KEY_LANGUAGE, language.storageValue)
            .apply()
    }

    fun savePublishCompatibilityMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PUBLISH_COMPATIBILITY_MODE, enabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "moq_android_config"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PUBLISH_COMPATIBILITY_MODE = "publish_compatibility_mode"
    }
}
