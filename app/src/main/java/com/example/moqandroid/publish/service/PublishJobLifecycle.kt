package com.example.moqandroid.publish.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class PublishJobLifecycle {
    var current: Job? = null
        private set

    private var observedTeardown: Job? = null
    private var observedEmptyTeardown = false

    fun replace(next: Job): Job? {
        val previous = current
        current = next
        observedTeardown = null
        observedEmptyTeardown = false
        return previous
    }

    fun cancel(): Job? {
        return current?.also { it.cancel(CancellationException("Publish stopped.")) }
    }

    fun cancelAndNotify(onTeardownComplete: () -> Unit): Job? {
        val job = current
        if (job == null) {
            if (!observedEmptyTeardown) {
                observedEmptyTeardown = true
                onTeardownComplete()
            }
            return null
        }
        if (observedTeardown !== job) {
            observedTeardown = job
            job.invokeOnCompletion { onTeardownComplete() }
        }
        job.cancel(CancellationException("Publish stopped."))
        return job
    }
}
