package com.example.moqandroid.publish.encoder

enum class VideoEncoderPolicy(val storageValue: String) {
    Default("default"),
    LegacyH264("legacy_h264");

    val dimensionAlignment: Int
        get() = when (this) {
            Default -> 2
            LegacyH264 -> 16
        }

    companion object {
        fun fromCompatibilityMode(enabled: Boolean): VideoEncoderPolicy {
            return if (enabled) LegacyH264 else Default
        }

        fun fromStorageValue(value: String?): VideoEncoderPolicy {
            return entries.firstOrNull { it.storageValue == value } ?: Default
        }
    }
}
