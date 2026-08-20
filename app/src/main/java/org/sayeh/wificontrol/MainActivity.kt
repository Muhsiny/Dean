package org.sayeh.wificontrol

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import org.sayeh.wificontrol.core.CliActionResult
import org.sayeh.wificontrol.core.CliDevice
import org.sayeh.wificontrol.core.CliSnapshot
import org.sayeh.wificontrol.core.TelnetRouter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var emergencyButton: Button
    private lateinit var allowOnlyButton: Button
    private lateinit var statusText: TextView
    private lateinit var managerText: TextView
    private lateinit var devicesContainer: LinearLayout

    private val prefs by lazy { getSharedPreferences("wifi_cli_v5", Context.MODE_PRIVATE) }
    private var router: TelnetRouter? = null
    private var snapshot: CliSnapshot? = null
    private var managerMac: String? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        passwordInput = findViewById(R.id.passwordInput)
        connectButton = findViewById(R.id.connectButton)
        refreshButton = findViewById(R.id.refreshButton)
        emergencyButton = findViewById(R.id.emergencyButton)
        allowOnlyButton = findViewById(R.id.allowOnlyButton)
        statusText = findViewById(R.id.statusText)
        managerText = findViewById(R.id.managerText)
        devicesContainer = findViewById(R.id.devicesContainer)

        hostInput.setText(prefs.getString("router_host", "192.168.1.1"))
        managerMac = prefs.getString("manager_mac", null)?.let(TelnetRouter::normalizeMac)

        refreshButton.isEnabled = false
        emergencyButton.isEnabled = false
        allowOnlyButton.isEnabled = false

        connectButton.setOnClickListener { connectReal() }
        refreshButton.setOnClickListener { refreshReal() }
        emergencyButton.setOnClickListener { confirmEmergencyOpen() }
        allowOnlyButton.setOnClickListener { confirmAllowOnly() }
    }

    private fun connectReal() {
        if (busy) return
        val host = hostInput.text.toString().trim().ifEmpty { "192.168.1.1" }
        val pass = passwordInput.text.toString()
        if (pass.isBlank()) {
            statusText.text = "رمز مدیریت روتر را وارد کنید."
            return
        }

        prefs.edit().putString("router_host", host).apply()
        router = TelnetRouter(host = host, password = pass)
        setBusy(true, "در حال اتصال مستقیم به Telnet/CLI واقعی روتر…")

        Thread {
            try {
                val snap = router!!.probe()
                snapshot = snap
                autoDetectManager(host, snap)
                runOnUiThread {
                    setBusy(false, connectedMessage(snap))
                    refreshButton.isEnabled = true
                    emergencyButton.isEnabled = true
                    allowOnlyButton.isEnabled = true
                    renderSnapshot(snap)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false, friendlyError(t))
                    refreshButton.isEnabled = false
                    emergencyButton.isEnabled = false
                    allowOnlyButton.isEnabled = false
                }
            }
        }.start()
    }

    private fun refreshReal() {
        val engine = router ?: return
        if (busy) return
        setBusy(true, "در حال خواندن مستقیم دستگاه‌ها از چیپ Wi‑Fi…")
        Thread {
            try {
                val snap = engine.probe()
                snapshot = snap
                autoDetectManager(hostInput.text.toString().trim(), snap)
                runOnUiThread {
                    setBusy(false, connectedMessage(snap))
                    renderSnapshot(snap)
                }
            } catch (t: Throwable) {
                runOnUiThread { setBusy(false, friendlyError(t)) }
            }
        }.start()
    }

    private fun runDeviceAction(device: CliDevice, block: Boolean) {
        val engine = router ?: return
        val mac = TelnetRouter.normalizeMac(device.mac)
        if (mac == managerMac) {
            statusText.text = "دستگاه مدیر محافظت شده و قابل Block نیست."
            return
        }
        if (busy) return

        setBusy(true, if (block) "در حال Block واقعی $mac و Verify…" else "در حال Unblock واقعی $mac و Verify…")
        Thread {
            val result = engine.setBlocked(mac, block)
            runOnUiThread { consumeActionResult(result) }
        }.start()
    }

    private fun confirmEmergencyOpen() {
        if (router == null || busy) return
        AlertDialog.Builder(this)
            .setTitle("بازکردن اضطراری")
            .setMessage("فیلتر MAC روی روتر خاموش می‌شود تا همه دستگاه‌ها دوباره امکان اتصال داشته باشند.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("باز کن") { _, _ ->
                val engine = router ?: return@setPositiveButton
                setBusy(true, "در حال خاموش‌کردن فیلتر MAC و Verify…")
                Thread {
                    val result = engine.disableMacFilter()
                    runOnUiThread { consumeActionResult(result) }
                }.start()
            }
            .show()
    }

    private fun confirmAllowOnly() {
        val engine = router ?: return
        val manager = managerMac
        if (manager.isNullOrBlank()) {
            statusText.text = "اول دستگاه مدیر را مشخص کنید؛ Allow‑List بدون محافظت مدیر فعال نمی‌شود."
            return
        }

        val approved = approvedMacs().toMutableSet().apply { add(manager) }
        if (approved.size < 1) {
            statusText.text = "حداقل یک دستگاه مجاز انتخاب کنید."
            return
        }

        AlertDialog.Builder(this)
            .setTitle("فعال‌سازی ضد QR")
            .setMessage("فقط ${approved.size} دستگاه تیک‌شده (به‌علاوه مدیر) اجازه اتصال خواهند داشت. دستگاه جدید حتی با داشتن رمز Wi‑Fi رد می‌شود.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("فعال کن") { _, _ ->
                if (busy) return@setPositiveButton
                setBusy(true, "در حال اعمال Allow‑List واقعی و Verify…")
                Thread {
                    val result = engine.enableAllowOnly(approved, manager)
                    runOnUiThread { consumeActionResult(result) }
                }.start()
            }
            .show()
    }

    private fun consumeActionResult(result: CliActionResult) {
        if (result.snapshot != null) snapshot = result.snapshot
        setBusy(false, result.message)
        result.snapshot?.let { renderSnapshot(it) }
    }

    private fun renderSnapshot(snap: CliSnapshot) {
        devicesContainer.removeAllViews()
        val currentManager = managerMac
        managerText.text = if (currentManager == null) {
            "دستگاه مدیر: مشخص نشده — روی «این دستگاه مدیر است» بزنید."
        } else {
            "دستگاه مدیر (محافظت‌شده): $currentManager"
        }

        val sorted = snap.devices.sortedWith(
            compareByDescending<CliDevice> { TelnetRouter.normalizeMac(it.mac) == currentManager }
                .thenByDescending { it.online }
                .thenBy { aliasFor(it.mac).lowercase(Locale.getDefault()) }
        )

        if (sorted.isEmpty()) {
            val empty = TextView(this).apply {
                text = "هیچ دستگاه Wi‑Fi از CLI گزارش نشد. تازه‌سازی کنید."
                textSize = 15f
                setPadding(8, 18, 8, 18)
            }
            devicesContainer.addView(empty)
            return
        }

        sorted.forEach { device -> devicesContainer.addView(buildDeviceCard(device, snap)) }
    }

    private fun buildDeviceCard(device: CliDevice, snap: CliSnapshot): MaterialCardView {
        val mac = TelnetRouter.normalizeMac(device.mac)
        val isManager = mac == managerMac
        val blocked = snap.policy == 2 && mac in snap.acl
        val allowed = snap.policy != 1 || mac in snap.acl

        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            val alias = aliasFor(mac)
            text = when {
                isManager -> "${if (alias.isBlank()) "مدیر" else alias}  •  مدیر"
                alias.isNotBlank() -> alias
                else -> if (device.online) "دستگاه متصل" else "دستگاه آفلاین/مسدود"
            }
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        box.addView(title)

        val state = TextView(this).apply {
            val mode = when {
                isManager -> "محافظت‌شده"
                blocked -> "BLOCK واقعی"
                snap.policy == 1 && !allowed -> "رد شده توسط Allow‑List"
                device.online -> "آنلاین"
                else -> "آفلاین"
            }
            val signal = device.signalDbm?.let { " • سیگنال ${it}dBm" }.orEmpty()
            val rate = device.rateMbps?.takeIf { it in 1..1000 }?.let { " • Rate $it" }.orEmpty()
            text = "$mode\nMAC: $mac${device.ip?.let { "\nIP: $it" }.orEmpty()}$signal$rate"
            textDirection = TextView.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(state)

        val aliasRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }
        val aliasInput = EditText(this).apply {
            hint = "نام دستگاه/محصل"
            setText(aliasFor(mac))
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveAlias = Button(this).apply {
            text = "ثبت نام"
            setOnClickListener {
                prefs.edit().putString("alias_$mac", aliasInput.text.toString().trim()).apply()
                renderSnapshot(snapshot ?: snap)
            }
        }
        aliasRow.addView(saveAlias)
        aliasRow.addView(aliasInput)
        box.addView(aliasRow)

        val approvedCheck = CheckBox(this).apply {
            text = "مجاز در حالت ضد QR"
            isChecked = isApproved(mac) || isManager
            isEnabled = !isManager
            setOnCheckedChangeListener { _, checked -> setApproved(mac, checked) }
        }
        box.addView(approvedCheck)

        val managerButton = Button(this).apply {
            text = if (isManager) "این دستگاه مدیر است ✓" else "این دستگاه مدیر است"
            isEnabled = !isManager
            setOnClickListener {
                managerMac = mac
                prefs.edit().putString("manager_mac", mac).apply()
                setApproved(mac, true)
                renderSnapshot(snapshot ?: snap)
            }
        }
        box.addView(managerButton)

        val actionButton = Button(this).apply {
            text = if (blocked) "وصل کردن واقعی" else "قطع کردن واقعی"
            isEnabled = !isManager && snap.policy != 1 && !busy
            setOnClickListener { runDeviceAction(device, block = !blocked) }
        }
        box.addView(actionButton)

        card.addView(box)
        return card
    }

    private fun autoDetectManager(host: String, snap: CliSnapshot) {
        val existing = managerMac
        if (existing != null && snap.devices.any { TelnetRouter.normalizeMac(it.mac) == existing }) return

        val local = localIpv4ForRouter(host) ?: return
        val match = snap.devices.firstOrNull { it.ip == local } ?: return
        val mac = TelnetRouter.normalizeMac(match.mac)
        managerMac = mac
        prefs.edit().putString("manager_mac", mac).apply()
        setApproved(mac, true)
    }

    private fun localIpv4ForRouter(host: String): String? {
        val prefix = host.substringBeforeLast('.', missingDelimiterValue = "")
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { ip -> prefix.isNotBlank() && ip.startsWith("$prefix.") }
        } catch (_: Throwable) {
            null
        }
    }

    private fun connectedMessage(snap: CliSnapshot): String {
        val mode = when (snap.policy) {
            0 -> "MAC Filter خاموش"
            1 -> "Allow‑List فعال"
            2 -> "Reject/Block فعال (${snap.acl.size})"
            else -> "Policy ${snap.policy}"
        }
        val online = snap.devices.count { it.online }
        return "اتصال CLI موفق • $online دستگاه آنلاین • $mode"
    }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        statusText.text = message
        connectButton.isEnabled = !value
        refreshButton.isEnabled = !value && router != null
        emergencyButton.isEnabled = !value && router != null
        allowOnlyButton.isEnabled = !value && router != null
    }

    private fun aliasFor(mac: String): String = prefs.getString("alias_${TelnetRouter.normalizeMac(mac)}", "").orEmpty()

    private fun approvedMacs(): Set<String> = prefs.getStringSet("approved_macs", emptySet()).orEmpty().map(TelnetRouter::normalizeMac).toSet()

    private fun isApproved(mac: String): Boolean = TelnetRouter.normalizeMac(mac) in approvedMacs()

    private fun setApproved(mac: String, approved: Boolean) {
        val set = approvedMacs().toMutableSet()
        val normalized = TelnetRouter.normalizeMac(mac)
        if (approved) set.add(normalized) else set.remove(normalized)
        prefs.edit().putStringSet("approved_macs", set).apply()
    }

    private fun friendlyError(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("refused", true) -> "Telnet روتر اتصال را رد کرد. به Wi‑Fi همین روتر وصل شوید."
            m.contains("Password", true) || m.contains("login", true) -> "رمز مدیریت روتر پذیرفته نشد."
            m.contains("timeout", true) -> "پاسخ CLI روتر Timeout شد. دوباره اتصال را بزنید."
            m.isNotBlank() -> "خطای CLI: $m"
            else -> "اتصال CLI ناموفق بود."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
