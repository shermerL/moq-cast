package com.example.moqandroid.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDefaultsTest {
    @Test
    fun defaultRelayUsesTheEditableExampleEndpoint() {
        assertEquals("https://example.com/broadcast", DEFAULT_RELAY_URL)
        assertNull(validateRelayUrl(DEFAULT_RELAY_URL))
    }
}
