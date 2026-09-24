package com.siper.vpn

import android.app.Application

class SiperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SiperRuntime.init(this)
    }
}
