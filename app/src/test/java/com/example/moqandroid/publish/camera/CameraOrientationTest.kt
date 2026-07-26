package com.example.moqandroid.publish.camera

import com.example.moqandroid.publish.encoder.H264ProfilePreference
import com.example.moqandroid.publish.encoder.VideoEncoderPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun encoderUsesCameraSurfaceDimensionsWithoutApplicationRotation() {
        val config = CameraPublishConfig(
            cameraId = "0",
            lensFacing = CameraLensFacing.Back,
            width = 1280,
            height = 720,
            frameRate = 30,
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            presentationRotationDegrees = 90,
        )
        val encoder = config.encoderConfig(
            encoderPolicy = VideoEncoderPolicy.Default,
            h264ProfilePreference = H264ProfilePreference.High,
        )

        assertEquals(1280, encoder.width)
        assertEquals(720, encoder.height)
    }

    @Test
    fun rearCameraPortraitPresentationRotatesClockwiseAndSwapsDisplayDimensions() {
        val config = CameraPublishConfig(
            cameraId = "0",
            lensFacing = CameraLensFacing.Back,
            width = 1280,
            height = 720,
            frameRate = 30,
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            presentationRotationDegrees = cameraPresentationRotation(
                sensorOrientationDegrees = 90,
                deviceOrientationDegrees = 0,
                frontFacing = false,
            ),
        )

        val presentation = config.presentation()

        assertEquals(720, presentation.displayWidth)
        assertEquals(1280, presentation.displayHeight)
        assertEquals(90, presentation.rotationDegrees)
        assertEquals(false, presentation.flip)
    }

    @Test
    fun relativeRotationAccountsForLensFacing() {
        assertEquals(180, cameraPresentationRotation(90, 90, frontFacing = false))
        assertEquals(180, cameraPresentationRotation(270, 90, frontFacing = true))
    }
}
