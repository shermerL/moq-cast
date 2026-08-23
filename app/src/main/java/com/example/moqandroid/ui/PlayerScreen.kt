package com.example.moqandroid.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.moqandroid.playback.PlayerState
import com.example.moqandroid.playback.PlaybackLayoutView
import com.example.moqandroid.playback.PlaybackRendererMode
import com.example.moqandroid.playback.calculateVideoPresentationLayout
import com.example.moqandroid.protocol.VideoLayoutEvent

class PlayerScreen internal constructor(
    private val activity: Activity,
    broadcastName: String,
    private val rendererMode: PlaybackRendererMode,
    private val surfaceListener: PlayerSurfaceListener,
) : PlaybackLayoutView {
    private var videoWidth: Int? = null
    private var videoHeight: Int? = null
    private var videoRotationDegrees = 0
    private var videoFlip = false
    private var pendingLayoutReady: (() -> Unit)? = null
    private var activeTransitionId: Int? = null
    private var traceGeneration = 0
    private var surfaceLayoutPosted = false
    private var activeLayoutGeneration: Long? = null
    private var freezeBitmap: Bitmap? = null
    private var freezeTimeout: Runnable? = null
    private var freezeRequestId = 0
    private var freezeTargetWidth: Int? = null
    private var freezeTargetHeight: Int? = null
    private var freezeFrameSubmitted = false
    private val afterFreezeActions = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlaysEnabled = rendererMode == PlaybackRendererMode.SurfaceView

    private val renderSurface = createPlaybackRenderSurface(
        context = activity,
        mode = rendererMode,
        events = object : PlaybackRenderSurfaceEvents {
            override fun onAvailable(surface: Surface) {
                traceSurfaceEvent("surface created")
                surfaceListener.onSurfaceAvailable(this@PlayerScreen, surface)
            }

            override fun onChanged(format: Int?, width: Int, height: Int) {
                val formatLabel = format?.toString() ?: "texture"
                traceSurfaceEvent("surface changed format=$formatLabel", width, height)
                scheduleSurfaceLayout()
            }

            override fun onRedrawNeeded() {
                traceSurfaceEvent("surface redraw needed")
            }

            override fun onDestroyed() {
                traceSurfaceEvent("surface destroyed")
                surfaceListener.onSurfaceDestroyed(this@PlayerScreen)
            }
        },
    )

    private val videoPresentation = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
        addView(renderSurface.view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    val status: TextView = TextView(activity).apply {
        text = PlayerState.SurfaceWaiting.message(broadcastName)
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x88000000.toInt())
        setPadding(18, 12, 18, 12)
    }

    private val videoShutter = View(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }

    private val videoFreeze = ImageView(activity).apply {
        setBackgroundColor(Color.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    private val rootFrame = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        addView(videoPresentation, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (overlaysEnabled) {
            addView(videoFreeze, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(videoShutter, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            logSnapshot("root layout changed")
            scheduleSurfaceLayout()
        }
    }

    val root: View = rootFrame

    fun setStatus(message: String, visible: Boolean = true) {
        status.text = message
        status.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun prepareVideoLayout(
        event: VideoLayoutEvent,
        onFrozen: () -> Unit,
        onTimeout: () -> Unit,
    ) {
        val currentGeneration = activeLayoutGeneration
        if (currentGeneration != null && event.generation < currentGeneration) {
            Log.i(LOG_TAG, "ignoring stale layout prepare generation=${event.generation} active=$currentGeneration")
            return
        }
        val targetChanged = freezeTargetWidth != event.width || freezeTargetHeight != event.height
        activeLayoutGeneration = event.generation
        freezeTargetWidth = event.width
        freezeTargetHeight = event.height
        freezeFrameSubmitted = false
        if (targetChanged) afterFreezeActions.clear()
        val requestId = ++freezeRequestId
        cancelFreezeTimeout()
        logSnapshot(
            "layout prepare generation=${event.generation} " +
                "target=${event.width}x${event.height} rotation=${event.rotation ?: "none"}",
        )

        if (!overlaysEnabled) {
            freezeFrameSubmitted = true
            onFrozen()
            return
        }

        val copySource = renderSurface.pixelCopySource
        val (width, height) = renderSurface.surfaceSize()
        if (copySource == null || !renderSurface.isValid || width <= 0 || height <= 0) {
            logSnapshot("layout freeze unavailable, continuing without PixelCopy")
            completeFreezePreparation(event.generation, onFrozen, onTimeout)
            return
        }

        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .onFailure { error -> Log.w(LOG_TAG, "layout freeze allocation failed ${width}x$height", error) }
            .getOrNull()
        if (bitmap == null) {
            completeFreezePreparation(event.generation, onFrozen, onTimeout)
            return
        }
        PixelCopy.request(
            copySource,
            bitmap,
            { result ->
                if (activeLayoutGeneration != event.generation || requestId != freezeRequestId) {
                    bitmap.recycle()
                    return@request
                }
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    logSnapshot("layout freeze PixelCopy failed result=$result")
                    completeFreezePreparation(event.generation, onFrozen, onTimeout)
                    return@request
                }

                replaceFreezeBitmap(bitmap)
                videoFreeze.alpha = 1f
                videoFreeze.visibility = View.VISIBLE
                logSnapshot("layout freeze visible generation=${event.generation}")
                val observer = videoFreeze.viewTreeObserver
                observer.addOnPreDrawListener(
                    object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (observer.isAlive) observer.removeOnPreDrawListener(this)
                            videoFreeze.postOnAnimation {
                                if (activeLayoutGeneration == event.generation) {
                                    logSnapshot("layout freeze frame submitted generation=${event.generation}")
                                    completeFreezePreparation(event.generation, onFrozen, onTimeout)
                                }
                            }
                            return true
                        }
                    },
                )
            },
            mainHandler,
        )
    }

    override fun markVideoLayoutReady(event: VideoLayoutEvent, onReady: () -> Unit) {
        if (event.generation != activeLayoutGeneration) return
        logSnapshot("layout encoder ready generation=${event.generation}")
        val action = {
            if (event.generation == activeLayoutGeneration) {
                logSnapshot("layout prewarming generation=${event.generation}")
                onReady()
            }
        }
        if (freezeFrameSubmitted) {
            action()
        } else {
            afterFreezeActions += action
            logSnapshot("layout prewarm deferred until freeze frame is submitted")
        }
    }

    override fun deferUntilVideoLayoutPrepared(width: Int?, height: Int?, action: () -> Unit): Boolean {
        if (
            activeLayoutGeneration == null ||
            freezeFrameSubmitted ||
            width != freezeTargetWidth ||
            height != freezeTargetHeight
        ) {
            return false
        }
        afterFreezeActions += action
        logSnapshot("layout update deferred until freeze frame is submitted")
        return true
    }

    override fun cancelVideoLayout(event: VideoLayoutEvent) {
        if (event.generation != activeLayoutGeneration) return
        logSnapshot("layout cancelled generation=${event.generation}")
        hideVideoFreeze()
    }

    fun release() {
        freezeRequestId += 1
        hideVideoFreeze()
        mainHandler.removeCallbacksAndMessages(null)
        renderSurface.release()
    }

    override fun coverVideo(transitionId: Int, onCovered: () -> Unit) {
        activeTransitionId = transitionId
        if (!overlaysEnabled) {
            logSnapshot("video cover skipped for compatibility renderer")
            startFrameTrace(transitionId)
            onCovered()
            return
        }
        videoShutter.visibility = View.VISIBLE
        logSnapshot("shutter visible requested")
        startFrameTrace(transitionId)
        val observer = videoShutter.viewTreeObserver
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    logSnapshot("shutter pre-draw")
                    videoShutter.post {
                        logSnapshot("shutter frame submitted")
                        onCovered()
                    }
                    return true
                }
            },
        )
    }

    override fun showVideoFrame(transitionId: Int) {
        if (transitionId != activeTransitionId) {
            Log.i(LOG_TAG, "rotationTrace=$transitionId ignoring stale frame rendered callback")
            return
        }
        logSnapshot("target frame rendered callback")
        if (!overlaysEnabled) {
            hideVideoFreeze()
            logSnapshot("target frame visible without overlays")
            return
        }
        if (videoFreeze.visibility == View.VISIBLE) {
            hideVideoFreeze()
        }
        if (videoShutter.visibility == View.VISIBLE) {
            videoShutter.postOnAnimation {
                videoShutter.visibility = View.GONE
                logSnapshot("shutter hidden")
            }
        } else if (videoFreeze.visibility != View.VISIBLE) {
            logSnapshot("target frame visible without shutter")
        }
    }

    override fun setVideoSize(
        width: Int?,
        height: Int?,
        rotationDegrees: Int,
        flip: Boolean,
        transitionId: Int?,
        onLayoutReady: (() -> Unit)?,
    ) {
        transitionId?.let {
            if (activeTransitionId != it) {
                activeTransitionId = it
                startFrameTrace(it)
            }
        }
        videoWidth = width?.takeIf { it > 0 }
        videoHeight = height?.takeIf { it > 0 }
        videoRotationDegrees = rotationDegrees
        videoFlip = flip
        pendingLayoutReady = onLayoutReady
        logSnapshot(
            "video presentation set display=${videoWidth}x$videoHeight " +
                "rotation=$videoRotationDegrees flip=$videoFlip",
        )
        scheduleSurfaceLayout()
    }

    private fun scheduleSurfaceLayout() {
        if (surfaceLayoutPosted) return
        surfaceLayoutPosted = true
        rootFrame.postOnAnimation {
            surfaceLayoutPosted = false
            updateSurfaceLayout()
        }
    }

    private fun updateSurfaceLayout() {
        val containerWidth = rootFrame.width
        val containerHeight = rootFrame.height
        val sourceWidth = videoWidth
        val sourceHeight = videoHeight
        if (containerWidth <= 0 || containerHeight <= 0 || sourceWidth == null || sourceHeight == null) return

        val sourceLandscape = sourceWidth > sourceHeight
        val containerLandscape = containerWidth > containerHeight
        if (sourceLandscape != containerLandscape) {
            logSnapshot("surface layout deferred until container orientation matches source")
            return
        }

        val layout = calculateVideoPresentationLayout(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            displayWidth = sourceWidth,
            displayHeight = sourceHeight,
        ) ?: return
        val targetWidth = layout.displayWidth
        val targetHeight = layout.displayHeight
        val surfaceWidth = layout.surfaceWidth
        val surfaceHeight = layout.surfaceHeight

        renderSurface.view.scaleX = if (videoFlip) -1f else 1f

        val presentationParams = videoPresentation.layoutParams as FrameLayout.LayoutParams
        val surfaceParams = renderSurface.view.layoutParams as FrameLayout.LayoutParams
        if (
            presentationParams.width == targetWidth &&
            presentationParams.height == targetHeight &&
            presentationParams.gravity == Gravity.CENTER &&
            surfaceParams.width == surfaceWidth &&
            surfaceParams.height == surfaceHeight &&
            surfaceParams.gravity == Gravity.CENTER
        ) {
            notifyLayoutReadyWhenStable(targetWidth, targetHeight, surfaceWidth, surfaceHeight)
            return
        }

        videoPresentation.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        renderSurface.view.layoutParams = FrameLayout.LayoutParams(surfaceWidth, surfaceHeight, Gravity.CENTER)
        logSnapshot(
            "surface layout requested display=${targetWidth}x$targetHeight " +
                "surface=${surfaceWidth}x$surfaceHeight rotation=$videoRotationDegrees flip=$videoFlip",
        )
        notifyLayoutReadyWhenStable(targetWidth, targetHeight, surfaceWidth, surfaceHeight)
    }

    private fun notifyLayoutReadyWhenStable(
        targetWidth: Int,
        targetHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        rootFrame.post {
            rootFrame.post {
                notifyLayoutReadyIfNeeded(
                    rootFrame.width,
                    rootFrame.height,
                    targetWidth,
                    targetHeight,
                    surfaceWidth,
                    surfaceHeight,
                )
            }
        }
    }

    private fun notifyLayoutReadyIfNeeded(
        containerWidth: Int,
        containerHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        val callback = pendingLayoutReady ?: return
        val sourceWidth = videoWidth ?: return
        val sourceHeight = videoHeight ?: return
        if (containerWidth <= 0 || containerHeight <= 0) return
        if (videoPresentation.width != targetWidth || videoPresentation.height != targetHeight) return
        if (renderSurface.view.width != surfaceWidth || renderSurface.view.height != surfaceHeight) return
        val (renderWidth, renderHeight) = renderSurface.surfaceSize()
        if (renderWidth != surfaceWidth || renderHeight != surfaceHeight) return

        val sourceLandscape = sourceWidth > sourceHeight
        val containerLandscape = containerWidth > containerHeight
        if (sourceLandscape != containerLandscape) return

        pendingLayoutReady = null
        logSnapshot("layout ready target=${targetWidth}x$targetHeight")
        callback()
    }

    fun traceSurfaceEvent(event: String, width: Int? = null, height: Int? = null) {
        val size = if (width != null && height != null) " callback=${width}x$height" else ""
        logSnapshot("$event$size")
    }

    override fun traceSnapshot(event: String) {
        logSnapshot(event)
    }

    private fun startFrameTrace(transitionId: Int) {
        val generation = ++traceGeneration
        var frame = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (generation != traceGeneration || transitionId != activeTransitionId) return
                logSnapshot("frame=$frame frameTimeNs=$frameTimeNanos")
                frame += 1
                if (frame < TRACE_FRAME_COUNT) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun scheduleFreezeTimeout(generation: Long, onTimeout: () -> Unit) {
        val timeout = Runnable {
            if (activeLayoutGeneration != generation) return@Runnable
            logSnapshot("layout freeze timed out generation=$generation")
            hideVideoFreeze()
            onTimeout()
        }
        freezeTimeout = timeout
        mainHandler.postDelayed(timeout, VIDEO_LAYOUT_TIMEOUT_MS)
    }

    private fun completeFreezePreparation(
        generation: Long,
        onFrozen: () -> Unit,
        onTimeout: () -> Unit,
    ) {
        if (activeLayoutGeneration != generation || freezeFrameSubmitted) return
        freezeFrameSubmitted = true
        onFrozen()
        val actions = afterFreezeActions.toList()
        afterFreezeActions.clear()
        actions.forEach { it() }
        scheduleFreezeTimeout(generation, onTimeout)
    }

    private fun cancelFreezeTimeout() {
        freezeTimeout?.let(mainHandler::removeCallbacks)
        freezeTimeout = null
    }

    private fun hideVideoFreeze() {
        cancelFreezeTimeout()
        activeLayoutGeneration = null
        freezeTargetWidth = null
        freezeTargetHeight = null
        freezeFrameSubmitted = false
        afterFreezeActions.clear()
        freezeRequestId += 1
        videoFreeze.animate().cancel()
        if (videoFreeze.visibility != View.VISIBLE) {
            replaceFreezeBitmap(null)
            return
        }
        videoFreeze.visibility = View.GONE
        videoFreeze.alpha = 1f
        replaceFreezeBitmap(null)
        logSnapshot("layout freeze hidden")
    }

    private fun replaceFreezeBitmap(bitmap: Bitmap?) {
        val previous = freezeBitmap
        freezeBitmap = bitmap
        videoFreeze.setImageBitmap(bitmap)
        if (previous !== bitmap) previous?.recycle()
    }

    private fun logSnapshot(event: String) {
        val transitionId = activeTransitionId ?: 0
        val (surfaceWidth, surfaceHeight) = renderSurface.surfaceSize()
        Log.i(
            LOG_TAG,
            "rotationTrace=$transitionId $event elapsedMs=${SystemClock.elapsedRealtime()} " +
                "renderer=${rendererMode.storageValue} " +
                "displayRotation=${renderSurface.view.display?.rotation ?: -1} " +
                "root=${rootFrame.width}x${rootFrame.height} " +
                "renderView=${renderSurface.view.width}x${renderSurface.view.height} " +
                "scaleX=${renderSurface.view.scaleX} " +
                "surface=${surfaceWidth}x$surfaceHeight " +
                "shutter=${videoShutter.visibility} freeze=${videoFreeze.visibility} " +
                "attached=${renderSurface.view.isAttachedToWindow}",
        )
    }

    private companion object {
        const val LOG_TAG = "MoqAndroid"
        const val TRACE_FRAME_COUNT = 24
        const val VIDEO_LAYOUT_TIMEOUT_MS = 3_000L
    }
}

internal interface PlayerSurfaceListener {
    fun onSurfaceAvailable(screen: PlayerScreen, surface: Surface)

    fun onSurfaceDestroyed(screen: PlayerScreen)
}
