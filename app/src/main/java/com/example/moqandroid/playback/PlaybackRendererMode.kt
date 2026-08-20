package com.example.moqandroid.playback

import androidx.annotation.StringRes
import com.example.moqandroid.R

enum class PlaybackRendererMode(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    SurfaceView("surface_view", R.string.playback_renderer_surface_view),
    TextureView("texture_view", R.string.playback_renderer_texture_view),
    ;

    companion object {
        fun fromStorageValue(value: String?): PlaybackRendererMode {
            return entries.firstOrNull { it.storageValue == value } ?: SurfaceView
        }
    }
}
