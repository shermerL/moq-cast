package com.example.moqandroid.playback

internal data class VideoPresentationLayout(
    val displayWidth: Int,
    val displayHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
)

internal fun calculateVideoPresentationLayout(
    containerWidth: Int,
    containerHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
): VideoPresentationLayout? {
    if (containerWidth <= 0 || containerHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return null

    val containerRatio = containerWidth.toFloat() / containerHeight
    val displayRatio = displayWidth.toFloat() / displayHeight
    val targetWidth: Int
    val targetHeight: Int
    if (displayRatio > containerRatio) {
        targetWidth = containerWidth
        targetHeight = (containerWidth / displayRatio).toInt()
    } else {
        targetHeight = containerHeight
        targetWidth = (containerHeight * displayRatio).toInt()
    }

    return VideoPresentationLayout(
        displayWidth = targetWidth,
        displayHeight = targetHeight,
        surfaceWidth = targetWidth,
        surfaceHeight = targetHeight,
    )
}
