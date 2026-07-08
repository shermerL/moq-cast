package com.example.moqandroid.publish.encoder

import android.media.MediaCodecInfo
import androidx.annotation.StringRes
import com.example.moqandroid.R

enum class H264ProfilePreference(
    val storageValue: String,
    val profile: Int,
    val profileName: String,
    @StringRes val labelRes: Int,
) {
    Baseline(
        storageValue = "baseline",
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        profileName = "baseline",
        labelRes = R.string.h264_profile_baseline,
    ),
    High(
        storageValue = "high",
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        profileName = "high",
        labelRes = R.string.h264_profile_high,
    );

    companion object {
        fun fromStorageValue(value: String?): H264ProfilePreference {
            return entries.firstOrNull { it.storageValue == value } ?: High
        }
    }
}
