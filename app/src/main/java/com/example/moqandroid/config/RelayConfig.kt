package com.example.moqandroid.config

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.example.moqandroid.R
import com.example.moqandroid.publish.encoder.H264ProfilePreference
import java.util.Locale

enum class AppLanguage(
    val storageValue: String,
    val localeTag: String,
    @StringRes val labelRes: Int,
) {
    English("en", "en", R.string.language_english),
    Chinese("zh", "zh", R.string.language_chinese);

    companion object {
        fun fromStorageValue(value: String?): AppLanguage {
            return entries.firstOrNull { it.storageValue == value } ?: English
        }
    }
}

fun Context.withAppLanguage(language: AppLanguage): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language.localeTag))
    return createConfigurationContext(configuration)
}

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
    val language: AppLanguage = AppLanguage.English,
    val publishCompatibilityMode: Boolean = false,
    val h264ProfilePreference: H264ProfilePreference = H264ProfilePreference.High,
) {
    fun withRelayUrl(value: String): SettingsState = copy(relayUrl = value)

    fun withStatus(message: String): SettingsState = copy(statusMessage = message)

    fun withLanguage(value: AppLanguage): SettingsState = copy(language = value)

    fun withPublishCompatibilityMode(value: Boolean): SettingsState = copy(publishCompatibilityMode = value)

    fun withH264ProfilePreference(value: H264ProfilePreference): SettingsState = copy(h264ProfilePreference = value)
}
