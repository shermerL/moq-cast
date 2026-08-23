package com.example.moqandroid.playback

import android.os.SystemClock
import android.util.Log
import com.example.moqandroid.media.summarizeAvcNalUnits
import java.util.concurrent.atomic.AtomicLong

internal class VideoPlaybackDiagnostics(
    private val trackName: String,
    private val codecName: String,
    private val enabled: Boolean,
    private val cmafDetailsEnabled: Boolean,
) {
    private val surfaceFrames = AtomicLong()
    private val lastSurfacePtsUs = AtomicLong(NO_TIMESTAMP)
    private val lastSurfaceFrameMs = AtomicLong(NO_TIMESTAMP)

    private var intervalStartMs = SystemClock.elapsedRealtime()
    private var lastInputPtsUs: Long? = null
    private var lastOutputPtsUs: Long? = null
    private var inputFrames = 0
    private var inputBytes = 0L
    private var keyframes = 0
    private var inputPtsRegressions = 0
    private var outputFrames = 0
    private var zeroSizeOutputs = 0
    private var outputPtsRegressions = 0
    private var renderedOutputs = 0
    private var droppedOutputs = 0
    private var waitedOutputs = 0
    private var maxInputForwardGapUs = 0L
    private var maxWaitUs = 0L
    private var avSamples = 0
    private var avClockUnavailable = 0
    private var avLateDrops = 0
    private var minAvDeltaUs: Long? = null
    private var maxAvDeltaUs: Long? = null
    private var lastAvDeltaUs: Long? = null
    private var lastSurfaceFrames = 0L
    private var lastHeldLogMs = 0L

    fun onInput(timestampUs: Long, keyframe: Boolean, payload: ByteArray) {
        if (!enabled) return
        inputFrames += 1
        inputBytes += payload.size
        lastInputPtsUs?.let { previous ->
            val deltaUs = timestampUs - previous
            if (deltaUs < 0) inputPtsRegressions += 1
            if (deltaUs > maxInputForwardGapUs) maxInputForwardGapUs = deltaUs
        }
        lastInputPtsUs = timestampUs

        if (!keyframe) return
        keyframes += 1
        if (!cmafDetailsEnabled) return
        val nal = payload.summarizeAvcNalUnits()
        val level = if (nal.hasIdr) Log.INFO else Log.WARN
        Log.println(
            level,
            LOG_TAG,
            "CMAF video keyframe track=$trackName ptsUs=$timestampUs bytes=${payload.size} " +
                "nalTypes=${nal.types} idr=${nal.hasIdr}",
        )
    }

    fun onOutput(
        presentationTimeUs: Long,
        outputSize: Int,
        rendered: Boolean,
        audioDeltaUs: Long?,
        waitedUs: Long,
        audioClockUnavailable: Boolean,
        lateForAudio: Boolean,
    ) {
        if (!enabled) return
        outputFrames += 1
        if (outputSize <= 0) zeroSizeOutputs += 1
        lastOutputPtsUs?.let { previous ->
            if (presentationTimeUs < previous) outputPtsRegressions += 1
        }
        lastOutputPtsUs = presentationTimeUs
        if (rendered) renderedOutputs += 1 else droppedOutputs += 1
        if (waitedUs > 0) {
            waitedOutputs += 1
            maxWaitUs = maxOf(maxWaitUs, waitedUs)
        }
        if (audioClockUnavailable) avClockUnavailable += 1
        if (lateForAudio) avLateDrops += 1
        audioDeltaUs?.let { deltaUs ->
            avSamples += 1
            minAvDeltaUs = minAvDeltaUs?.let { minOf(it, deltaUs) } ?: deltaUs
            maxAvDeltaUs = maxAvDeltaUs?.let { maxOf(it, deltaUs) } ?: deltaUs
            lastAvDeltaUs = deltaUs
        }
    }

    fun onOutputHeld(presentationTimeUs: Long, audioPositionUs: Long, remainingUs: Long, heldUs: Long) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeldLogMs < HELD_OUTPUT_LOG_INTERVAL_MS) return
        lastHeldLogMs = now
        Log.w(
            LOG_TAG,
            "video output held for A/V sync track=$trackName ptsUs=$presentationTimeUs " +
                "audioPositionUs=$audioPositionUs remainingUs=$remainingUs heldUs=$heldUs",
        )
    }

    fun onSurfaceFrameRendered(presentationTimeUs: Long) {
        if (!enabled) return
        surfaceFrames.incrementAndGet()
        lastSurfacePtsUs.set(presentationTimeUs)
        lastSurfaceFrameMs.set(SystemClock.elapsedRealtime())
    }

    fun flushIfDue() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - intervalStartMs
        if (elapsedMs < DIAGNOSTIC_INTERVAL_MS) return

        val totalSurfaceFrames = surfaceFrames.get()
        val intervalSurfaceFrames = totalSurfaceFrames - lastSurfaceFrames
        val surfaceFrameMs = lastSurfaceFrameMs.get()
        val surfaceAgeMs = if (surfaceFrameMs == NO_TIMESTAMP) "none" else (now - surfaceFrameMs).toString()
        Log.i(
            LOG_TAG,
            "video diagnostics track=$trackName codec=$codecName elapsedMs=$elapsedMs " +
                "input=$inputFrames keyframes=$keyframes bytes=$inputBytes " +
                "inputLastPtsUs=${lastInputPtsUs ?: "none"} inputPtsRegressions=$inputPtsRegressions " +
                "inputMaxForwardGapUs=$maxInputForwardGapUs output=$outputFrames " +
                "zeroSize=$zeroSizeOutputs outputLastPtsUs=${lastOutputPtsUs ?: "none"} " +
                "outputPtsRegressions=$outputPtsRegressions " +
                "release=$renderedOutputs drop=$droppedOutputs waits=$waitedOutputs maxWaitUs=$maxWaitUs " +
                "avSyncSamples=$avSamples avDeltaUs=${minAvDeltaUs ?: "none"}..${maxAvDeltaUs ?: "none"} " +
                "avLastDeltaUs=${lastAvDeltaUs ?: "none"} avClockUnavailable=$avClockUnavailable " +
                "avLateDrops=$avLateDrops " +
                "surfaceCallbacks=$intervalSurfaceFrames surfaceLastPtsUs=${lastSurfacePtsUs.get().asTimestamp()} " +
                "surfaceAgeMs=$surfaceAgeMs",
        )

        intervalStartMs = now
        inputFrames = 0
        inputBytes = 0
        keyframes = 0
        inputPtsRegressions = 0
        outputFrames = 0
        zeroSizeOutputs = 0
        outputPtsRegressions = 0
        renderedOutputs = 0
        droppedOutputs = 0
        waitedOutputs = 0
        maxInputForwardGapUs = 0
        maxWaitUs = 0
        avSamples = 0
        avClockUnavailable = 0
        avLateDrops = 0
        minAvDeltaUs = null
        maxAvDeltaUs = null
        lastAvDeltaUs = null
        lastSurfaceFrames = totalSurfaceFrames
    }

    private fun Long.asTimestamp(): String = if (this == NO_TIMESTAMP) "none" else toString()
}

private const val LOG_TAG = "MoqAndroid"
private const val DIAGNOSTIC_INTERVAL_MS = 1_000L
private const val HELD_OUTPUT_LOG_INTERVAL_MS = 1_000L
private const val NO_TIMESTAMP = Long.MIN_VALUE
