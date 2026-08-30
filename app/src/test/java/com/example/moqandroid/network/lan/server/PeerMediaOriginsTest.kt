package com.example.moqandroid.network.lan.server

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerMediaOriginsTest {
    @Test
    fun mapsLocalMediaToPublishAndRemoteMediaToConsume() {
        val localPublishOrigin = Any()
        val remoteReceiveOrigin = Any()
        val origins = PeerMediaOrigins<Any>()

        origins.attach(localPublishOrigin, remoteReceiveOrigin)

        assertSame(localPublishOrigin, origins.session()?.publishOrigin)
        assertSame(remoteReceiveOrigin, origins.session()?.consumeOrigin)
        assertSame(localPublishOrigin, origins.localPublishOrigin())
        assertSame(remoteReceiveOrigin, origins.remoteReceiveOrigin())
    }

    @Test
    fun staleDetachDoesNotClearReplacementOrigins() {
        val oldLocalPublishOrigin = Any()
        val oldRemoteReceiveOrigin = Any()
        val newLocalPublishOrigin = Any()
        val newRemoteReceiveOrigin = Any()
        val origins = PeerMediaOrigins<Any>()

        origins.attach(oldLocalPublishOrigin, oldRemoteReceiveOrigin)
        origins.attach(newLocalPublishOrigin, newRemoteReceiveOrigin)
        origins.detach(oldLocalPublishOrigin, oldRemoteReceiveOrigin)

        assertSame(newLocalPublishOrigin, origins.session()?.publishOrigin)
        assertSame(newRemoteReceiveOrigin, origins.session()?.consumeOrigin)

        origins.detach(newLocalPublishOrigin, newRemoteReceiveOrigin)

        assertNull(origins.session())
    }

    @Test
    fun rejectsOneOriginForBothDirections() {
        val sharedOrigin = Any()

        assertThrows(IllegalArgumentException::class.java) {
            PeerMediaOrigins<Any>().attach(sharedOrigin, sharedOrigin)
        }
    }
}
