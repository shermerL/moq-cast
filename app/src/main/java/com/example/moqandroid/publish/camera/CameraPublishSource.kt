package com.example.moqandroid.publish.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.example.moqandroid.publish.VideoPublishConfig
import com.example.moqandroid.publish.VideoPublishSource
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraPublishSource(
    private val context: Context,
    private val cameraConfig: CameraPublishConfig,
) : VideoPublishSource {
    override val label: String = "camera"
    override val presentation = cameraConfig.presentation()

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("MoqCameraCapture").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val failure = AtomicReference<Throwable?>()
    private val lock = Any()
    private var attachGeneration = 0L
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile
    private var closed = false

    @SuppressLint("MissingPermission")
    override suspend fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
        check(!closed) { "Camera source is closed." }
        check(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        ) { "Camera permission is not granted." }
        require(config.width == cameraConfig.width && config.height == cameraConfig.height) {
            "Camera output ${cameraConfig.width}x${cameraConfig.height} cannot attach to " +
                "encoder ${config.width}x${config.height}."
        }

        detachEncoderSurface()
        failure.set(null)
        val generation = synchronized(lock) { ++attachGeneration }
        Log.i(
            LOG_TAG,
                "opening ${cameraConfig.lensFacing.statusLabel} camera id=${cameraConfig.cameraId} " +
                "preset=${cameraConfig.qualityPreset.storageValue} " +
                "output=${cameraConfig.width}x${cameraConfig.height} " +
                "fps=${cameraConfig.frameRate} sensorOrientation=${cameraConfig.sensorOrientation} " +
                "displayRotation=${cameraConfig.displayRotationDegrees} " +
                "presentationRotation=${cameraConfig.presentationRotationDegrees}",
        )

        try {
            withTimeout(CAMERA_START_TIMEOUT_MS) {
                awaitCameraAttached(surface, generation)
            }
        } catch (error: Throwable) {
            detachGeneration(generation)
            if (error is TimeoutCancellationException) {
                throw TimeoutException("Timed out while starting the ${cameraConfig.lensFacing.statusLabel} camera.")
            }
            throw error
        }
    }

    override suspend fun detachEncoderSurface() {
        detachGeneration()
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCameraAttached(surface: Surface, generation: Long) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)

            fun complete(error: Throwable? = null) {
                if (!completed.compareAndSet(false, true)) return
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(error)
                }
            }

            continuation.invokeOnCancellation {
                completed.set(true)
                detachGeneration(generation)
            }
            try {
                cameraManager.openCamera(
                    cameraConfig.cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            val accepted = synchronized(lock) {
                                if (closed || attachGeneration != generation) {
                                    false
                                } else {
                                    cameraDevice = camera
                                    true
                                }
                            }
                            if (!accepted) {
                                camera.close()
                                complete(IllegalStateException("Camera attach was cancelled."))
                                return
                            }
                            configureCaptureSession(camera, surface, generation, ::complete)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            reportCameraFailure(
                                generation,
                                IllegalStateException(
                                    "${cameraConfig.lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera disconnected.",
                                ),
                                ::complete,
                            )
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            reportCameraFailure(
                                generation,
                                IllegalStateException(
                                    "${cameraConfig.lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera failed with error code $error.",
                                ),
                                ::complete,
                            )
                        }
                    },
                    cameraHandler,
                )
            } catch (error: Throwable) {
                complete(error)
            }
        }
    }

    private fun detachGeneration(expectedGeneration: Long? = null) {
        val session: CameraCaptureSession?
        val device: CameraDevice?
        synchronized(lock) {
            if (expectedGeneration != null && attachGeneration != expectedGeneration) return
            attachGeneration += 1
            session = captureSession
            device = cameraDevice
            captureSession = null
            cameraDevice = null
        }
        session?.let {
            runCatching { it.stopRepeating() }
            runCatching { it.abortCaptures() }
            it.close()
        }
        device?.close()
    }

    override fun pollFailure(): Throwable? = failure.getAndSet(null)

    override suspend fun close() {
        if (closed) return
        closed = true
        detachEncoderSurface()
        cameraThread.quitSafely()
        runCatching { cameraThread.join(CAMERA_THREAD_JOIN_TIMEOUT_MS) }
    }

    private fun configureCaptureSession(
        camera: CameraDevice,
        surface: Surface,
        generation: Long,
        complete: (Throwable?) -> Unit,
    ) {
        val request = runCatching {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                chooseContinuousVideoAutoFocus()?.let {
                    set(CaptureRequest.CONTROL_AF_MODE, it)
                }
                chooseFrameRateRange()?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }
        }.getOrElse {
            complete(it)
            return
        }

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isAttachActive(generation)) {
                        session.close()
                        complete(IllegalStateException("Camera session configuration was cancelled."))
                        return
                    }
                    runCatching {
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                        synchronized(lock) {
                            check(!closed && attachGeneration == generation) {
                                "Camera session configuration was cancelled."
                            }
                            captureSession = session
                        }
                    }.onSuccess {
                        Log.i(
                            LOG_TAG,
                            "${cameraConfig.lensFacing.statusLabel} camera capture started id=${cameraConfig.cameraId} " +
                                "preset=${cameraConfig.qualityPreset.storageValue} " +
                                "output=${cameraConfig.width}x${cameraConfig.height} " +
                                "fps=${cameraConfig.frameRate}",
                        )
                        complete(null)
                    }.onFailure {
                        session.close()
                        complete(it)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    complete(
                        IllegalStateException(
                            "${cameraConfig.lensFacing.statusLabel.replaceFirstChar { it.uppercase() }} camera capture session configuration failed.",
                        ),
                    )
                }
            },
            cameraHandler,
        )
    }

    private fun chooseContinuousVideoAutoFocus(): Int? {
        val characteristics = cameraManager.getCameraCharacteristics(cameraConfig.cameraId)
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO.takeIf { it in modes }
    }

    private fun chooseFrameRateRange(): Range<Int>? {
        val characteristics = cameraManager.getCameraCharacteristics(cameraConfig.cameraId)
        return characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { cameraConfig.frameRate in it }
            ?.minWithOrNull(compareBy({ it.upper - it.lower }, { -it.lower }))
    }

    private fun reportCameraFailure(
        generation: Long,
        error: Throwable,
        complete: (Throwable?) -> Unit,
    ) {
        if (!isAttachActive(generation)) return
        failure.compareAndSet(null, error)
        complete(error)
    }

    private fun isAttachActive(generation: Long): Boolean {
        return !closed && synchronized(lock) { attachGeneration == generation }
    }

    companion object {
        private const val LOG_TAG = "MoqAndroid"
        private const val CAMERA_START_TIMEOUT_MS = 5_000L
        private const val CAMERA_THREAD_JOIN_TIMEOUT_MS = 1_000L
    }
}
