package com.example.moqandroid.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPresentationTest {
    @Test
    fun normalizesRotationToQuarterTurns() {
        assertEquals(0, normalizeVideoRotation(null))
        assertEquals(0, normalizeVideoRotation(-45.0))
        assertEquals(90, normalizeVideoRotation(45.0))
        assertEquals(90, normalizeVideoRotation(405.0))
        assertEquals(270, normalizeVideoRotation(270.0))
    }
}
