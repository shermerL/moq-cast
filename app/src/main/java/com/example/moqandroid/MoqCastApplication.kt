package com.example.moqandroid

import android.app.Application
import com.example.moqandroid.network.lan.mesh.LanRuntimeOwner

class MoqCastApplication : Application() {
    val lanRuntimeOwner: LanRuntimeOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LanRuntimeOwner(this)
    }
}
