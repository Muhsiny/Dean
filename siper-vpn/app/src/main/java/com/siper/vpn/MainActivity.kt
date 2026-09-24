package com.siper.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
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
        private const val TUNNEL_ID = 1
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SecureConfigStore
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var endpointText: TextView
    private lateinit var connectButton: Button
    private lateinit var importButton: Button
    private lateinit var alwaysOnButton: Button
    private lateinit var turboButton: Button
    private var pendingTurboCandidates: List<WarpProvisioner.Provisioned>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureConfigStore(this)
        buildUi()
        refreshImportedConfig()
        observeBackend()

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

        val title = TextView(this).apply {
            text = "Siper VPN"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Turbo Route Engine • WARP/WireGuard • تست سرعت واقعی"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(28))
        }
        root.addView(subtitle)

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
            text = "در انتظار کانفیگ"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(detailText)

        endpointText = TextView(this).apply {
            text = "Server: —"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(mint)
            setPadding(0, dp(12), 0, 0)
        }
        statusCard.addView(endpointText)

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

        turboButton = Button(this).apply {
            text = "Turbo Auto-Tune — پیدا کردن سریع‌ترین مسیر"
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

        val privacy = TextView(this).apply {
            text = "کلید خصوصی کانفیگ با AES-256-GCM در Android Keystore نگه‌داری می‌شود. هیچ تبلیغ، حساب کاربری یا ردیاب در این نسخه وجود ندارد."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
        }
        root.addView(privacy)

        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(background)
        }
        setContentView(scroll)
    }

    private fun observeBackend() {
        uiScope.launch {
            SiperRuntime.backend.status.collectLatest { status ->
                val active = status.activeTunnels[TUNNEL_ID]
                when (active?.transportState) {
                    is Tunnel.State.Up.Healthy -> {
                        stateText.text = "متصل"
                        detailText.text = "Handshake سالم • تونل واقعی فعال است"
                        connectButton.text = "قطع اتصال"
                    }

                    is Tunnel.State.Up.HandshakeFailure -> {
                        stateText.text = "تونل فعال"
                        detailText.text = "Handshake با سرور پاسخ نداده؛ Endpoint یا شبکه را بررسی کن"
                        connectButton.text = "قطع اتصال"
                    }

                    is Tunnel.State.Starting -> {
                        stateText.text = "در حال اتصال…"
                        detailText.text = "در حال ساخت TUN و برقراری Handshake"
                        connectButton.text = "در حال اتصال…"
                    }

                    is Tunnel.State.Stopping -> {
                        stateText.text = "در حال قطع…"
                        detailText.text = "تونل در حال بسته‌شدن است"
                        connectButton.text = "…"
                    }

                    else -> {
                        stateText.text = if (store.load() == null) "کانفیگ لازم است" else "آماده"
                        detailText.text =
                            if (store.load() == null) "دکمه اتصال را بزن؛ Turbo مسیرها را تست می‌کند"
                            else "مسیر سریع ذخیره شده؛ اتصال آماده است"
                        connectButton.text = "اتصال"
                    }
                }
            }
        }
    }

    private fun onConnectPressed() {
        uiScope.launch {
            val active = SiperRuntime.backend.statusSnapshotActive()
            if (active) {
                stateText.text = "در حال قطع…"
                SiperRuntime.backend.stop(TUNNEL_ID)
                return@launch
            }

            val raw = store.load()
            if (raw.isNullOrBlank()) {
                provisionAndTurboConnect()
                return@launch
            }

            val permissionIntent = VpnService.prepare(this@MainActivity)
            if (permissionIntent != null) {
                startActivityForResult(permissionIntent, REQUEST_VPN)
            } else {
                startRealTunnel(raw)
            }
        }
    }

    private fun onTurboPressed() {
        uiScope.launch {
            if (SiperRuntime.backend.statusSnapshotActive()) {
                runCatching { SiperRuntime.backend.stop(TUNNEL_ID) }
            }
            store.clear()
            provisionAndTurboConnect()
        }
    }

    private suspend fun provisionAndTurboConnect() {
        stateText.text = "Turbo"
        detailText.text = "در حال دریافت مسیرهای واقعی WARP…"
        connectButton.isEnabled = false
        turboButton.isEnabled = false

        val candidates = runCatching {
            withContext(Dispatchers.IO) {
                WarpProvisioner().createProfiles(maxCandidates = 6)
            }
        }.onFailure { error ->
            stateText.text = "ساخت مسیر ناموفق"
            detailText.text = error.message ?: "WARP registration failed"
        }.getOrNull()

        if (candidates.isNullOrEmpty()) {
            connectButton.isEnabled = true
            turboButton.isEnabled = true
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

    private suspend fun startTurbo(
        candidates: List<WarpProvisioner.Provisioned>
    ) {
        connectButton.isEnabled = false
        turboButton.isEnabled = false
        stateText.text = "Turbo Tuning"

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
            endpointText.text = "Server: " + winner.provisioned.endpoint
            stateText.text = "Turbo متصل"
            detailText.text = String.format(
                Locale.US,
                "%s • %.2f MB/s • Handshake %d ms",
                winner.provisioned.label,
                winner.megabytesPerSecond,
                winner.handshakeMillis
            )
        }.onFailure { error ->
            pendingTurboCandidates = null
            stateText.text = "Turbo ناموفق"
            detailText.text = error.message ?: error.javaClass.simpleName
        }

        connectButton.isEnabled = true
        turboButton.isEnabled = true
    }

    private suspend fun startRealTunnel(raw: String) {
        runCatching {
            val config = Config.parseQuickString(raw)
            require(config.peers.isNotEmpty()) { "Peer در کانفیگ وجود ندارد" }
            require(config.peers.any { !it.endpoint.isNullOrBlank() }) {
                "Endpoint سرور در کانفیگ وجود ندارد"
            }

            val result = SiperRuntime.backend.start(
                tunnel = SiperTunnel(),
                mode = BackendMode.Vpn(config),
                tunnelDnsConfig = null
            )
            result.getOrThrow()
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
                arrayOf("text/plain", "application/octet-stream", "application/x-wireguard-profile")
            )
        }
        startActivityForResult(intent, REQUEST_CONFIG)
    }

    @Deprecated("Deprecated in Android API, kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return

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
            endpointText.text = "Server: Auto WARP"
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
        uiScope.cancel()
        super.onDestroy()
    }
}

private suspend fun com.wgtunnel.backend.Backend.statusSnapshotActive(): Boolean {
    return status.first().activeTunnels.isNotEmpty()
}
