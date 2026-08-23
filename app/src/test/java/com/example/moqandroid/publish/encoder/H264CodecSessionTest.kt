package com.example.moqandroid.publish.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264CodecSessionTest {
    @Test
    fun api26UsesRealtimeHintsWithoutTheUnavailableBFrameKey() {
        val options = h264RealtimeEncoderOptions(sdkInt = 26)

        assertEquals(0, options.priority)
        assertEquals(1, options.latencyFrames)
        assertNull(options.maxBFrames)
    }

    @Test
    fun api29ExplicitlyDisablesBFrames() {
        val options = h264RealtimeEncoderOptions(sdkInt = 29)

        assertEquals(0, options.priority)
        assertEquals(1, options.latencyFrames)
        assertEquals(0, options.maxBFrames)
    }
}
