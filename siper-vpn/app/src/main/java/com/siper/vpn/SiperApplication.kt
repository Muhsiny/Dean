package com.siper.vpn

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

class SiperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!currentProcessName().endsWith(":psiphon")) {
            SiperRuntime.init(this)
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
            .orEmpty()
    }
}
