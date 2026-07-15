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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraPublishSource(
    private val context: Context,
    private val cameraConfig: CameraPublishConfig,
) : VideoPublishSource {
    override val label: String = "camera"

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
    override fun attachEncoderSurface(surface: Surface, config: VideoPublishConfig) {
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
        val completed = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val startupFailure = AtomicReference<Throwable?>()

        fun complete(error: Throwable? = null) {
            if (!completed.compareAndSet(false, true)) return
            startupFailure.set(error)
            ready.countDown()
        }

        Log.i(
            LOG_TAG,
            "opening rear camera id=${cameraConfig.cameraId} " +
                "output=${cameraConfig.width}x${cameraConfig.height} " +
                "fps=${cameraConfig.frameRate} sensorOrientation=${cameraConfig.sensorOrientation}",
        )

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
                            IllegalStateException("Rear camera disconnected."),
                            ::complete,
                        )
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        reportCameraFailure(
                            generation,
                            IllegalStateException("Rear camera failed with error code $error."),
                            ::complete,
                        )
                    }
                },
                cameraHandler,
            )
        } catch (error: Throwable) {
            complete(error)
        }

        if (!ready.await(CAMERA_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            detachEncoderSurface()
            throw TimeoutException("Timed out while starting the rear camera.")
        }
        startupFailure.get()?.let {
            detachEncoderSurface()
            throw it
        }
    }

    override fun detachEncoderSurface() {
        val session: CameraCaptureSession?
        val device: CameraDevice?
        synchronized(lock) {
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

    override fun close() {
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
                            "rear camera capture started id=${cameraConfig.cameraId} " +
                                "output=${cameraConfig.width}x${cameraConfig.height}",
                        )
                        complete(null)
                    }.onFailure {
                        session.close()
                        complete(it)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    complete(IllegalStateException("Rear camera capture session configuration failed."))
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
        private const val CAMERA_START_TIMEOUT_SECONDS = 5L
        private const val CAMERA_THREAD_JOIN_TIMEOUT_MS = 1_000L
    }
}
