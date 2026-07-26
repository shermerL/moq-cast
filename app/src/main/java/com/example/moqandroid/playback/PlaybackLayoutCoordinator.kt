package com.example.moqandroid.playback

import com.example.moqandroid.catalog.PlayableVideoInfo
import com.example.moqandroid.protocol.VideoLayoutEvent

interface PlaybackLayoutView {
    fun traceSnapshot(event: String)

    fun prepareVideoLayout(
        event: VideoLayoutEvent,
        onFrozen: () -> Unit,
        onTimeout: () -> Unit,
    )

    fun markVideoLayoutReady(event: VideoLayoutEvent, onReady: () -> Unit)

    fun deferUntilVideoLayoutPrepared(width: Int?, height: Int?, action: () -> Unit): Boolean

    fun cancelVideoLayout(event: VideoLayoutEvent)

    fun coverVideo(transitionId: Int, onCovered: () -> Unit)

    fun showVideoFrame(transitionId: Int)

    fun setVideoSize(
        width: Int?,
        height: Int?,
        rotationDegrees: Int = 0,
        flip: Boolean = false,
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    )
}

class PlaybackLayoutCoordinator(
    private val view: () -> PlaybackLayoutView?,
    private val applyOrientation: (width: Int?, height: Int?) -> Unit,
) {
    private var confirmedVideoWidth: Int? = null
    private var confirmedVideoHeight: Int? = null
    private var confirmedRotationDegrees = 0
    private var confirmedFlip = false

    fun reset() {
        confirmedVideoWidth = null
        confirmedVideoHeight = null
        confirmedRotationDegrees = 0
        confirmedFlip = false
    }

    fun handle(state: PlayerState): Boolean {
        return when (state) {
            is PlayerState.VideoLayoutPreparing -> {
                prepareLayout(state.event)
                true
            }
            is PlayerState.VideoLayoutReady -> {
                markLayoutReady(state.event)
                true
            }
            is PlayerState.VideoLayoutCancelled -> {
                view()?.cancelVideoLayout(state.event)
                restoreConfirmedLayout()
                true
            }
            is PlayerState.VideoFrameRendered -> {
                view()?.showVideoFrame(state.transitionId)
                true
            }
            is PlayerState.VideoSizeChanged -> {
                updateVideoSize(state)
                true
            }
            is PlayerState.Playing -> {
                confirmVideoSize(state.videoInfo)
                applyConfirmedLayout()
                false
            }
            else -> false
        }
    }

    private fun prepareLayout(event: VideoLayoutEvent) {
        val activeView = view() ?: return
        if (confirmedVideoWidth == event.width && confirmedVideoHeight == event.height) {
            activeView.traceSnapshot("layout prepare already applied generation=${event.generation}")
            return
        }
        activeView.prepareVideoLayout(
            event = event,
            onFrozen = {
                if (view() !== activeView) return@prepareVideoLayout
                activeView.traceSnapshot(
                    "layout freeze armed generation=${event.generation}; awaiting decoder",
                )
            },
            onTimeout = {
                if (view() !== activeView) return@prepareVideoLayout
                activeView.traceSnapshot(
                    "layout freeze fallback generation=${event.generation}; media state unchanged",
                )
                restoreConfirmedLayout()
            },
        )
    }

    private fun markLayoutReady(event: VideoLayoutEvent) {
        val activeView = view() ?: return
        activeView.markVideoLayoutReady(event) {
            if (view() !== activeView) return@markVideoLayoutReady
            applyOrientation(event.width, event.height)
            activeView.setVideoSize(
                event.width,
                event.height,
                confirmedRotationDegrees,
                confirmedFlip,
            )
        }
    }

    private fun updateVideoSize(state: PlayerState.VideoSizeChanged) {
        val activeView = view()
        val width = state.videoInfo.displayWidth
        val height = state.videoInfo.displayHeight
        confirmVideoSize(state.videoInfo)
        val updateVideoLayout: () -> Unit = {
            applyLayout(
                width,
                height,
                state.videoInfo.rotationDegrees,
                state.videoInfo.flip,
                state.transitionId,
                state.onLayoutReady,
            )
        }
        if (state.coverVideo) {
            activeView?.coverVideo(state.transitionId, updateVideoLayout) ?: updateVideoLayout()
            return
        }
        val deferred = activeView?.deferUntilVideoLayoutPrepared(
            width,
            height,
            updateVideoLayout,
        ) == true
        if (!deferred) updateVideoLayout()
    }

    private fun confirmVideoSize(videoInfo: PlayableVideoInfo) {
        confirmedVideoWidth = videoInfo.displayWidth
        confirmedVideoHeight = videoInfo.displayHeight
        confirmedRotationDegrees = videoInfo.rotationDegrees
        confirmedFlip = videoInfo.flip
    }

    private fun applyConfirmedLayout(
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    ) {
        applyLayout(
            confirmedVideoWidth,
            confirmedVideoHeight,
            confirmedRotationDegrees,
            confirmedFlip,
            transitionId,
            onLayoutReady,
        )
    }

    private fun applyLayout(
        width: Int?,
        height: Int?,
        rotationDegrees: Int = confirmedRotationDegrees,
        flip: Boolean = confirmedFlip,
        transitionId: Int? = null,
        onLayoutReady: (() -> Unit)? = null,
    ) {
        applyOrientation(width, height)
        view()?.setVideoSize(
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            flip = flip,
            transitionId = transitionId,
            onLayoutReady = onLayoutReady,
        )
    }

    private fun restoreConfirmedLayout() {
        applyConfirmedLayout()
    }
}
