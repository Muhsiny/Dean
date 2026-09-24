package com.siper.vpn

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.parser.Config
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_VPN = 2001
        private const val REQUEST_CONFIG = 2002
        private const val REQUEST_NOTIFICATIONS = 2003
        private const val REQUEST_VPN_FALLBACK = 2004
        private const val TUNNEL_ID = 1
        private const val MODE_PREFS = "siper_mode"
        private const val PREF_PREFER_PSIPHON = "prefer_psiphon"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SecureConfigStore
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var endpointText: TextView
    private lateinit var linkText: TextView
    private lateinit var connectButton: Button
    private lateinit var importButton: Button
    private lateinit var alwaysOnButton: Button
    private lateinit var turboButton: Button
    private var pendingTurboCandidates: List<WarpProvisioner.Provisioned>? = null
    private var psiphonRunning = false

    private val fallbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(PsiphonFallbackVpnService.EXTRA_STATE)) {
                PsiphonFallbackVpnService.STATE_CONNECTING -> {
                    psiphonRunning = true
                    stateText.text = "Rescue در حال اتصال…"
                    detailText.text =
                        intent.getStringExtra(PsiphonFallbackVpnService.EXTRA_DETAIL)
                            ?: "Psiphon در حال یافتن مسیر مقاوم"
                    endpointText.text = "Carrier: Psiphon Rescue"
                    connectButton.text = "قطع اتصال"
                }

                PsiphonFallbackVpnService.STATE_CONNECTED -> {
                    psiphonRunning = true
                    getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_PREFER_PSIPHON, true)
                        .apply()
                    stateText.text = "متصل"
                    detailText.text =
                        intent.getStringExtra(PsiphonFallbackVpnService.EXTRA_DETAIL)
                            ?: "Psiphon Rescue فعال است"
                    endpointText.text = "Carrier: Psiphon Rescue"
                    connectButton.text = "قطع اتصال"
                    connectButton.isEnabled = true
                    turboButton.isEnabled = true
                }

                PsiphonFallbackVpnService.STATE_FAILED -> {
                    psiphonRunning = false
                    stateText.text = "Rescue ناموفق"
                    detailText.text =
                        intent.getStringExtra(PsiphonFallbackVpnService.EXTRA_DETAIL)
                            ?: "Psiphon نتوانست مسیر سالم پیدا کند"
                    connectButton.text = "اتصال"
                    connectButton.isEnabled = true
                    turboButton.isEnabled = true
                }

                PsiphonFallbackVpnService.STATE_STOPPED -> {
                    psiphonRunning = false
                    stateText.text = "آماده"
                    detailText.text = "تونل بسته شد"
                    connectButton.text = "اتصال"
                    connectButton.isEnabled = true
                    turboButton.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureConfigStore(this)
        buildUi()
        registerFallbackReceiver()
        refreshImportedConfig()
        observeBackend()
        observeLinks()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun registerFallbackReceiver() {
        val filter = IntentFilter(PsiphonFallbackVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(fallbackReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(fallbackReceiver, filter)
        }
    }

    private fun buildUi() {
        val background = Color.rgb(7, 17, 31)
        val card = Color.rgb(16, 31, 49)
        val mint = Color.rgb(99, 230, 190)
        val pale = Color.rgb(213, 224, 236)
        val muted = Color.rgb(145, 164, 184)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(background)
        }

        root.addView(TextView(this).apply {
            text = "Siper Fusion Rescue"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "WARP/WireGuard + Psiphon adaptive fallback"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(28))
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(card)
        }

        stateText = TextView(this).apply {
            text = "آماده"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(pale)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        statusCard.addView(stateText)

        detailText = TextView(this).apply {
            text = "Auto carrier selection"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(detailText)

        endpointText = TextView(this).apply {
            text = "Carrier: Auto"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(mint)
            setPadding(0, dp(12), 0, 0)
        }
        statusCard.addView(endpointText)

        linkText = TextView(this).apply {
            text = "Path: در جستجوی بهترین لینک…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(linkText)

        root.addView(
            statusCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        connectButton = Button(this).apply {
            text = "اتصال"
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onConnectPressed() }
        }
        root.addView(
            connectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
            ).apply { topMargin = dp(24) }
        )

        turboButton = Button(this).apply {
            text = "Auto Rescue — سریع‌ترین مسیر سالم"
            isAllCaps = false
            setOnClickListener { onTurboPressed() }
        }
        root.addView(
            turboButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(12) }
        )

        importButton = Button(this).apply {
            text = "وارد کردن کانفیگ WireGuard / AmneziaWG"
            isAllCaps = false
            setOnClickListener { openConfigPicker() }
        }
        root.addView(
            importButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(12) }
        )

        alwaysOnButton = Button(this).apply {
            text = "Always-on VPN / Kill Switch"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            }
        }
        root.addView(
            alwaysOnButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(12) }
        )

        root.addView(TextView(this).apply {
            text = "اگر WARP/UDP در شبکه مسدود باشد، برنامه خودکار به Psiphon Rescue با transportهای سازگار با شبکه‌های محدودشده می‌رود."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
        })

        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(background)
        }
        setContentView(scroll)
    }

    private fun observeBackend() {
        uiScope.launch {
            SiperRuntime.backend.status.collectLatest { status ->
                if (psiphonRunning) return@collectLatest
                val active = status.activeTunnels[TUNNEL_ID]
                when (active?.transportState) {
                    is Tunnel.State.Up.Healthy -> {
                        stateText.text = "متصل"
                        detailText.text = "Handshake سالم • WireGuard فعال است"
                        connectButton.text = "قطع اتصال"
                    }

                    is Tunnel.State.Up.HandshakeFailure -> {
                        stateText.text = "WARP پاسخ نداد"
                        detailText.text = "در حال آماده‌سازی Rescue fallback"
                        connectButton.text = "قطع اتصال"
                    }

                    is Tunnel.State.Starting -> {
                        stateText.text = "در حال اتصال…"
                        detailText.text = "Handshake واقعی در حال آزمایش است"
                        connectButton.text = "در حال اتصال…"
                    }

                    is Tunnel.State.Stopping -> {
                        stateText.text = "در حال قطع…"
                        detailText.text = "تونل در حال بسته‌شدن است"
                        connectButton.text = "…"
                    }

                    else -> {
                        stateText.text = "آماده"
                        detailText.text =
                            if (preferPsiphon()) "Psiphon Rescue برای این شبکه ترجیح داده می‌شود"
                            else "WARP اول؛ در صورت بلوک، Psiphon Rescue خودکار"
                        connectButton.text = "اتصال"
                    }
                }
            }
        }
    }

    private fun observeLinks() {
        uiScope.launch {
            SiperRuntime.networkMonitor.linkStatus.collectLatest { link ->
                val rtt = link.rttMs?.let { " • $it ms" } ?: ""
                val down = if (link.downstreamKbps > 0) {
                    " • " + String.format(
                        Locale.US,
                        "%.1f Mbps",
                        link.downstreamKbps / 1000.0
                    )
                } else ""
                val count = if (link.availableLinks > 1) {
                    " • ${link.availableLinks} links"
                } else ""
                linkText.text = "Path: ${link.label}$down$rtt$count"
            }
        }
    }

    private fun onConnectPressed() {
        uiScope.launch {
            if (psiphonRunning) {
                stopPsiphonFallback()
                return@launch
            }

            if (SiperRuntime.backend.statusSnapshotActive()) {
                stateText.text = "در حال قطع…"
                SiperRuntime.backend.stop(TUNNEL_ID)
                return@launch
            }

            val raw = store.load()
            if (!raw.isNullOrBlank()) {
                val permissionIntent = VpnService.prepare(this@MainActivity)
                if (permissionIntent != null) {
                    startActivityForResult(permissionIntent, REQUEST_VPN)
                } else {
                    startRealTunnel(raw)
                }
                return@launch
            }

            if (preferPsiphon()) {
                requestOrStartPsiphonFallback()
            } else {
                provisionAndTurboConnect()
            }
        }
    }

    private fun onTurboPressed() {
        uiScope.launch {
            if (psiphonRunning) stopPsiphonFallback()
            if (SiperRuntime.backend.statusSnapshotActive()) {
                runCatching { SiperRuntime.backend.stop(TUNNEL_ID) }
            }
            getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PREFER_PSIPHON, false)
                .apply()
            store.clear()
            provisionAndTurboConnect()
        }
    }

    private suspend fun provisionAndTurboConnect() {
        stateText.text = "Auto Rescue"
        detailText.text = "ابتدا مسیرهای WARP آزمایش می‌شود…"
        connectButton.isEnabled = false
        turboButton.isEnabled = false

        val candidates = runCatching {
            withContext(Dispatchers.IO) {
                WarpProvisioner().createProfiles(maxCandidates = 6)
            }
        }.getOrNull()

        if (candidates.isNullOrEmpty()) {
            connectButton.isEnabled = true
            turboButton.isEnabled = true
            stateText.text = "WARP در دسترس نیست"
            detailText.text = "رفتن به Psiphon Rescue…"
            requestOrStartPsiphonFallback()
            return
        }

        pendingTurboCandidates = candidates
        val permissionIntent = VpnService.prepare(this@MainActivity)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN)
        } else {
            startTurbo(candidates)
        }
    }

    private suspend fun startTurbo(candidates: List<WarpProvisioner.Provisioned>) {
        connectButton.isEnabled = false
        turboButton.isEnabled = false
        stateText.text = "WARP Tuning"

        runCatching {
            TurboOptimizer(
                backend = SiperRuntime.backend,
                tunnelId = TUNNEL_ID
            ).chooseFastest(candidates) { progress ->
                detailText.text = progress
            }
        }.onSuccess { winner ->
            store.save(winner.provisioned.profile)
            pendingTurboCandidates = null
            endpointText.text = "Carrier: ${winner.provisioned.label}"
            stateText.text = "متصل"
            detailText.text = String.format(
                Locale.US,
                "%.2f MB/s • Handshake %d ms",
                winner.megabytesPerSecond,
                winner.handshakeMillis
            )
            connectButton.isEnabled = true
            turboButton.isEnabled = true
        }.onFailure {
            pendingTurboCandidates = null
            connectButton.isEnabled = true
            turboButton.isEnabled = true
            stateText.text = "WARP مسدود"
            detailText.text = "تغییر خودکار به Psiphon Rescue…"
            requestOrStartPsiphonFallback()
        }
    }

    private fun requestOrStartPsiphonFallback() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_FALLBACK)
        } else {
            startPsiphonFallback()
        }
    }

    private fun startPsiphonFallback() {
        psiphonRunning = true
        stateText.text = "Rescue در حال اتصال…"
        detailText.text = "Psiphon در حال کشف transport سالم است"
        endpointText.text = "Carrier: Psiphon Rescue"
        connectButton.text = "قطع اتصال"
        val intent = Intent(this, PsiphonFallbackVpnService::class.java)
            .setAction(PsiphonFallbackVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopPsiphonFallback() {
        stateText.text = "در حال قطع…"
        val intent = Intent(this, PsiphonFallbackVpnService::class.java)
            .setAction(PsiphonFallbackVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun preferPsiphon(): Boolean =
        getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .getBoolean(PREF_PREFER_PSIPHON, false)

    private suspend fun startRealTunnel(raw: String) {
        runCatching {
            val config = Config.parseQuickString(raw)
            require(config.peers.isNotEmpty()) { "Peer در کانفیگ وجود ندارد" }
            require(config.peers.any { !it.endpoint.isNullOrBlank() }) {
                "Endpoint سرور در کانفیگ وجود ندارد"
            }
            SiperRuntime.backend.start(
                tunnel = SiperTunnel(),
                mode = BackendMode.Vpn(config),
                tunnelDnsConfig = null
            ).getOrThrow()
        }.onFailure { error ->
            stateText.text = "اتصال ناموفق"
            detailText.text = error.message ?: error.javaClass.simpleName
            connectButton.text = "اتصال"
        }
    }

    private fun openConfigPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/plain",
                    "application/octet-stream",
                    "application/x-wireguard-profile"
                )
            )
        }
        startActivityForResult(intent, REQUEST_CONFIG)
    }

    @Deprecated("Deprecated in Android API, kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            connectButton.isEnabled = true
            turboButton.isEnabled = true
            return
        }

        when (requestCode) {
            REQUEST_VPN -> {
                val turbo = pendingTurboCandidates
                if (!turbo.isNullOrEmpty()) {
                    uiScope.launch { startTurbo(turbo) }
                } else {
                    val raw = store.load() ?: return
                    uiScope.launch { startRealTunnel(raw) }
                }
            }

            REQUEST_VPN_FALLBACK -> startPsiphonFallback()

            REQUEST_CONFIG -> {
                val uri = data?.data ?: return
                importConfig(uri)
            }
        }
    }

    private fun importConfig(uri: Uri) {
        runCatching {
            val raw = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("فایل خوانده نشد")

            val config = Config.parseQuickString(raw)
            require(config.peers.isNotEmpty()) { "Peer وجود ندارد" }
            require(config.peers.any { !it.endpoint.isNullOrBlank() }) {
                "Endpoint وجود ندارد"
            }

            store.save(raw)
            getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PREFER_PSIPHON, false)
                .apply()
            refreshImportedConfig()
            stateText.text = "کانفیگ معتبر"
            detailText.text = "کلیدها رمزگذاری و ذخیره شدند"
        }.onFailure {
            stateText.text = "کانفیگ نامعتبر"
            detailText.text = it.message ?: "فایل قابل استفاده نیست"
        }
    }

    private fun refreshImportedConfig() {
        val raw = store.load()
        if (raw.isNullOrBlank()) {
            endpointText.text =
                if (preferPsiphon()) "Carrier: Psiphon Rescue"
                else "Carrier: Auto WARP → Psiphon Rescue"
            connectButton.isEnabled = true
            return
        }

        runCatching {
            val config = Config.parseQuickString(raw)
            val endpoints = config.peers
                .mapNotNull { it.endpoint }
                .distinct()
                .joinToString(" • ")
            endpointText.text = "Server: " + if (endpoints.isBlank()) "—" else endpoints
        }.onFailure {
            endpointText.text = "Server: config error"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(fallbackReceiver) }
        uiScope.cancel()
        super.onDestroy()
    }
}

private suspend fun com.wgtunnel.backend.Backend.statusSnapshotActive(): Boolean {
    return status.first().activeTunnels.isNotEmpty()
}
