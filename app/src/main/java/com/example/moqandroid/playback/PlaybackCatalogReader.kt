package com.example.moqandroid.playback

import android.util.Log
import com.example.moqandroid.catalog.describe
import uniffi.moq.MoqBroadcastConsumer
import uniffi.moq.MoqCatalog

class PlaybackCatalogReader(private val logTag: String) {
    suspend fun readFirst(
        broadcast: MoqBroadcastConsumer,
        broadcastName: String,
    ): MoqCatalog {
        val catalog = broadcast.subscribeCatalog().use { catalogConsumer ->
            catalogConsumer.next()
        } ?: error("catalog ended before first update")

        Log.i(logTag, catalog.describe(broadcastName))
        return catalog
    }
}

