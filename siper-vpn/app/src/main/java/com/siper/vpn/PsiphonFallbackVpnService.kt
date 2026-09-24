package com.siper.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import ca.psiphon.PsiphonTunnel
import ca.psiphon.Tun2SocksJniLoader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PsiphonFallbackVpnService : VpnService() {

    private data class PrivateAddress(
        val address: String,
        val subnet: String,
        val prefix: Int,
        val router: String
    )

    private val socksPort = AtomicInteger(0)
    private val routing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile private var psiphon: PsiphonTunnel? = null
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var tunThread: Thread? = null
    @Volatile private var privateAddress: PrivateAddress? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            Tun2SocksJniLoader.initializeLogger(
                PsiphonFallbackVpnService::class.java.name,
                "logTun2Socks"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Thread { stopEverything(true) }.start()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (psiphon != null) {
                    broadcast(STATE_CONNECTING, "Psiphon already starting")
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification("Siper Rescue در حال اتصال"))
                stopping.set(false)
                Thread { startFallback() }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startFallback() {
        try {
            establishVpn()
            val embedded = assets.open(PsiphonFallbackConfig.SERVER_ENTRIES_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            require(embedded.lineSequence().count { it.isNotBlank() } > 100) {
                "Psiphon bootstrap list is missing"
            }

            broadcast(STATE_CONNECTING, "WARP مسدود است • Psiphon در حال یافتن مسیر مقاوم")

            val host = object : PsiphonTunnel.HostService {
                override fun getContext(): Context = this@PsiphonFallbackVpnService

                override fun getPsiphonConfig(): String =
                    PsiphonFallbackConfig.render(
                        dataRootDirectory = filesDir.resolve("psiphon-rescue"),
                        clientVersion = BuildConfig.VERSION_CODE.toString()
                    )

                override fun bindToDevice(fileDescriptor: Long) {
                    if (!protect(fileDescriptor.toInt())) {
                        throw IllegalStateException("Could not protect Psiphon socket")
                    }
                }

                override fun onListeningSocksProxyPort(port: Int) {
                    socksPort.set(port)
                }

                override fun onConnecting() {
                    broadcast(STATE_CONNECTING, "Psiphon در حال آزمایش مسیرهای عبور")
                }

                override fun onConnected() {
                    val port = socksPort.get().takeIf { it > 0 }
                        ?: psiphon?.getLocalSocksProxyPort()?.takeIf { it > 0 }
                    if (port == null) {
                        fail("Psiphon connected without a SOCKS port")
                        return
                    }
                    runCatching { startTun2Socks(port) }
                        .onSuccess {
                            broadcast(STATE_CONNECTED, "Psiphon Rescue • تونل واقعی فعال شد")
                            updateNotification("Siper Rescue متصل است")
                        }
                        .onFailure { fail(it.message ?: "tun2socks failed") }
                }

                override fun onStartedWaitingForNetworkConnectivity() {
                    broadcast(STATE_CONNECTING, "در انتظار اینترنت پایه")
                }

                override fun onDiagnosticMessage(message: String) {
                    Log.d(TAG, message)
                    if (message.startsWith("EstablishTunnelTimeout")) {
                        broadcast(STATE_CONNECTING, "مسیر اول پاسخ نداد • جستجو ادامه دارد")
                    }
                }

                override fun onExiting() {
                    if (!stopping.get()) {
                        broadcast(STATE_FAILED, "Psiphon core exited")
                    }
                }
            }

            val tunnel = PsiphonTunnel.newPsiphonTunnel(host)
            psiphon = tunnel
            tunnel.setClientPlatformAffixes("SiperFusion", "")
            tunnel.setVpnMode(true)
            tunnel.startTunneling(embedded)
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun establishVpn() {
        val selected = selectPrivateAddress()
        privateAddress = selected
        val builder = Builder()
            .setSession("Siper Rescue")
            .setMtu(VPN_MTU)
            .addAddress(selected.address, selected.prefix)
            .addRoute("0.0.0.0", 0)
            .addRoute(selected.subnet, selected.prefix)
            .addDnsServer(selected.router)

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }

        tunFd = builder.establish()
            ?: throw IllegalStateException("Android refused VPN interface")
    }

    private fun startTun2Socks(port: Int) {
        if (!routing.compareAndSet(false, true)) return
        val source = tunFd ?: throw IllegalStateException("VPN interface missing")
        val address = privateAddress ?: throw IllegalStateException("VPN address missing")
        val duplicate = source.dup()
        val thread = Thread({
            try {
                Tun2SocksJniLoader.runTun2Socks(
                    duplicate.detachFd(),
                    VPN_MTU,
                    address.router,
                    "255.255.255.0",
                    null,
                    "127.0.0.1:$port",
                    "127.0.0.1:$UDPGW_PORT",
                    1
                )
            } finally {
                routing.set(false)
            }
        }, "siper-psiphon-tun2socks")
        tunThread = thread
        thread.start()
    }

    private fun stopTun2Socks() {
        if (routing.getAndSet(false)) {
            runCatching { Tun2SocksJniLoader.terminateTun2Socks() }
        }
        runCatching { tunThread?.join(3000) }
        tunThread = null
    }

    private fun stopEverything(userRequested: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        stopTun2Socks()
        val active = psiphon
        psiphon = null
        runCatching { active?.stop() }
        runCatching { tunFd?.close() }
        tunFd = null
        socksPort.set(0)
        if (userRequested) {
            broadcast(STATE_STOPPED, "قطع شد")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(reason: String) {
        Log.e(TAG, reason)
        broadcast(STATE_FAILED, reason)
        Thread { stopEverything(false) }.start()
    }

    override fun onRevoke() {
        Thread { stopEverything(true) }.start()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!stopping.get()) {
            Thread { stopEverything(false) }.start()
        }
        super.onDestroy()
    }

    private fun selectPrivateAddress(): PrivateAddress {
        val inUse = mutableSetOf<String>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNullTo(inUse) { it.hostAddress }
            }
        }

        val candidates = listOf(
            PrivateAddress("10.253.0.1", "10.253.0.0", 24, "10.253.0.2"),
            PrivateAddress("172.31.253.1", "172.31.253.0", 24, "172.31.253.2"),
            PrivateAddress("192.168.253.1", "192.168.253.0", 24, "192.168.253.2"),
            PrivateAddress("169.254.253.1", "169.254.253.0", 24, "169.254.253.2")
        )
        return candidates.firstOrNull { candidate ->
            val prefix = candidate.subnet.substringBeforeLast('.') + "."
            inUse.none { it.startsWith(prefix) }
        } ?: throw IllegalStateException("No safe private VPN subnet")
    }

    private fun broadcast(state: String, detail: String) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Siper Rescue VPN",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Siper Fusion")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START = "com.siper.vpn.PSIPHON_START"
        const val ACTION_STOP = "com.siper.vpn.PSIPHON_STOP"
        const val ACTION_STATE = "com.siper.vpn.PSIPHON_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"

        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_FAILED = "failed"
        const val STATE_STOPPED = "stopped"

        private const val TAG = "SiperPsiphon"
        private const val CHANNEL_ID = "siper_rescue_vpn"
        private const val NOTIFICATION_ID = 4607
        private const val VPN_MTU = 1500
        private const val UDPGW_PORT = 7300

        @JvmStatic
        fun logTun2Socks(level: String?, channel: String?, message: String?) {
            Log.d(TAG, "tun2socks $level/$channel: $message")
        }
    }
}
