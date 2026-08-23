package com.example.moqandroid.network.lan.discovery

internal class NsdResolveAttemptPolicy(
    private val maxAttempts: Int = 2,
) {
    private val attempts = mutableMapOf<String, Int>()

    fun found(serviceName: String) {
        attempts[serviceName] = 0
    }

    fun started(serviceName: String) {
        attempts[serviceName] = (attempts[serviceName] ?: 0) + 1
    }

    fun canRetry(serviceName: String, available: Boolean): Boolean =
        available && (attempts[serviceName] ?: 0) < maxAttempts

    fun remove(serviceName: String) {
        attempts.remove(serviceName)
    }

    fun clear() {
        attempts.clear()
    }
}
