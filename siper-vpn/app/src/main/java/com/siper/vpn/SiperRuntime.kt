package com.siper.vpn

import android.app.Application
import com.wgtunnel.backend.Backend
import com.wgtunnel.backend.TunnelBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object SiperRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    lateinit var backend: Backend
        private set

    @Synchronized
    fun init(application: Application) {
        if (initialized) return

        val provider = SiperNotificationProvider(application.applicationContext)
        val networkMonitor = UnderlayNetworkMonitor(application.applicationContext)
        backend = TunnelBackend(
            scope = scope,
            applicationProvider = provider,
            networkMonitor = networkMonitor
        )
        initialized = true
    }
}
