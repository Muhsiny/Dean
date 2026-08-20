package org.sayeh.wificontrol

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.sayeh.wificontrol.core.DirectRouter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var routerUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var connectBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var status: TextView
    private lateinit var capabilities: TextView
    private lateinit var managerStatus: TextView
    private lateinit var clientList: LinearLayout
    private lateinit var antiQrBtn: Button
    private lateinit var filterOffBtn: Button
    private lateinit var statsBtn: Button
    private lateinit var statsText: TextView

    private val prefs by lazy { getSharedPreferences("wifi_control_direct_v4", MODE_PRIVATE) }
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val operationId = AtomicInteger(0)
    private val allowChecks = linkedMapOf<String, CheckBox>()

    private var caps = DirectRouter.Capabilities()
    private var wirelessCapacity = 0
    private var currentClients: List<DirectRouter.Client> = emptyList()
    private var connected = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        routerUrl = findViewById(R.id.routerUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        connectBtn = findViewById(R.id.connectBtn)
        refreshBtn = findViewById(R.id.refreshBtn)
        status = findViewById(R.id.status)
        capabilities = findViewById(R.id.capabilities)
        managerStatus = findViewById(R.id.managerStatus)
        clientList = findViewById(R.id.clientList)
        antiQrBtn = findViewById(R.id.antiQrBtn)
        filterOffBtn = findViewById(R.id.filterOffBtn)
        statsBtn = findViewById(R.id.statsBtn)
        statsText = findViewById(R.id.statsText)

        routerUrl.setText(prefs.getString("router_url", "http://192.168.1.1"))
        username.setText(prefs.getString("router_user", "admin"))

        connectBtn.setOnClickListener { connectDirect() }
        refreshBtn.setOnClickListener { refreshClients() }
        antiQrBtn.setOnClickListener { activateAllowList() }
        filterOffBtn.setOnClickListener { disableFilter() }
        statsBtn.setOnClickListener { readStats() }
        renderClients()
        updateUi()
    }

    override fun onDestroy() {
        operationId.incrementAndGet()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun baseUrl(): String = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }.trimEnd('/')

    private fun credentialsReady(): Boolean {
        if (username.text.toString().trim().isBlank() || password.text.toString().isBlank()) {
            status.text = "نام کاربری و رمز ادمین را وارد کن."
            return false
        }
        prefs.edit()
            .putString("router_url", baseUrl())
            .putString("router_user", username.text.toString().trim())
            .apply()
        return true
    }

    private fun engine(): DirectRouter = DirectRouter(
        baseUrl(),
        username.text.toString().trim(),
        password.text.toString(),
        prefs.getString("manager_mac", null)
    )

    private fun connectDirect() {
        if (!credentialsReady()) return
        runOperation("در حال اتصال مستقیم HTTP به روتر…", 30000,
            task = { engine().connectAndProbe() },
            onSuccess = { snap ->
                if (!snap.ok) {
                    connected = false
                    caps = DirectRouter.Capabilities()
                    wirelessCapacity = 0
                    currentClients = emptyList()
                    status.text = snap.message
                } else {
                    connected = true
                    caps = snap.capabilities
                    wirelessCapacity = snap.wirelessCapacity
                    currentClients = snap.clients
                    status.text = buildString {
                        append(snap.message)
                        if (snap.firmware.isNotBlank()) append("\nFirmware: ").append(snap.firmware)
                    }
                }
                renderClients()
                updateUi()
            }
        )
    }

    private fun refreshClients() {
        if (!credentialsReady() || !connected) return
        runOperation("در حال خواندن مستقیم دستگاه‌ها…", 18000,
            task = { engine().clients() },
            onSuccess = { list ->
                currentClients = list
                status.text = "${list.size} دستگاه از خود روتر تازه شد."
                renderClients()
                updateUi()
            }
        )
    }

    private fun renderClients() {
        clientList.removeAllViews()
        allowChecks.clear()
        val manager = prefs.getString("manager_mac", null)?.uppercase(Locale.US)
        val lastAllow = prefs.getStringSet("allow_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        managerStatus.text = if (manager.isNullOrBlank()) "دستگاه مدیر مشخص نشده است." else "دستگاه مدیر محافظت‌شده: $manager"

        if (currentClients.isEmpty()) {
            clientList.addView(TextView(this).apply {
                text = if (connected) "روتر فعلاً Wireless Client نشان نداد." else "برای خواندن دستگاه‌ها «اتصال مستقیم به روتر» را بزن."
                setPadding(6, 12, 6, 12)
            })
            return
        }

        val ordered = currentClients.distinctBy { it.mac }.sortedWith(compareByDescending<DirectRouter.Client> { it.mac.equals(manager, true) }.thenBy { it.mac })
        for (c in ordered) {
            val mac = c.mac.uppercase(Locale.US)
            val isManager = mac == manager
            val alias = prefs.getString("alias_$mac", "").orEmpty()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 10, 8, 14)
            }
            card.addView(TextView(this).apply {
                text = buildString {
                    if (alias.isNotBlank()) append(alias).append("\n")
                    append(mac)
                    c.ip?.let { append("  •  ").append(it) }
                    if (isManager) append("  •  مدیر")
                }
                textSize = 16f
                setTextIsSelectable(true)
            })

            val check = CheckBox(this).apply {
                text = "مجاز در ضد QR"
                isChecked = isManager || mac in lastAllow
                isEnabled = !isManager
            }
            allowChecks[mac] = check
            card.addView(check)

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(Button(this).apply {
                text = if (isManager) "مدیر ✓" else "این دستگاه مدیر است"
                isEnabled = !busy && !isManager
                setOnClickListener {
                    prefs.edit().putString("manager_mac", mac).apply()
                    val allowed = prefs.getStringSet("allow_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
                    allowed.add(mac)
                    prefs.edit().putStringSet("allow_macs", allowed).apply()
                    status.text = "$mac به‌عنوان مدیر محافظت شد."
                    renderClients(); updateUi()
                }
            }, weight())
            row1.addView(Button(this).apply {
                text = "نام‌گذاری"
                isEnabled = !busy
                setOnClickListener { rename(mac) }
            }, weight())
            card.addView(row1)

            if (!isManager) {
                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row2.addView(Button(this).apply {
                    text = "قطع Wi‑Fi"
                    isEnabled = !busy && caps.wirelessMacFilter
                    setOnClickListener { confirmWifi(mac, true) }
                }, weight())
                row2.addView(Button(this).apply {
                    text = "وصل Wi‑Fi"
                    isEnabled = !busy && caps.wirelessMacFilter
                    setOnClickListener { confirmWifi(mac, false) }
                }, weight())
                card.addView(row2)

                if (caps.internetMacFilter) {
                    val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    row3.addView(Button(this).apply {
                        text = "قطع اینترنت"
                        isEnabled = !busy
                        setOnClickListener { confirmInternet(mac, true) }
                    }, weight())
                    row3.addView(Button(this).apply {
                        text = "وصل اینترنت"
                        isEnabled = !busy
                        setOnClickListener { confirmInternet(mac, false) }
                    }, weight())
                    card.addView(row3)
                }
            }
            clientList.addView(card)
        }
    }

    private fun rename(mac: String) {
        val input = EditText(this).apply {
            hint = "مثلاً: تلفن احمد"
            setText(prefs.getString("alias_$mac", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("نام دستگاه")
            .setMessage(mac)
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putString("alias_$mac", input.text.toString().trim()).apply()
                renderClients()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun confirmWifi(mac: String, block: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (block) "قطع Wi‑Fi این دستگاه؟" else "اجازه اتصال دوباره؟")
            .setMessage("$mac\nفرمان مستقیماً روی Wireless MAC Filter روتر POST می‌شود و بعد دوباره از خود روتر خوانده می‌شود.")
            .setPositiveButton("اجرا") { _, _ ->
                runOperation(if (block) "در حال Block واقعی $mac…" else "در حال Unblock واقعی $mac…", 22000,
                    task = { if (block) engine().blockWifi(mac) else engine().unblockWifi(mac) },
                    onSuccess = { r -> status.text = r.message; updateUi(); renderClients() })
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun confirmInternet(mac: String, block: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (block) "قطع اینترنت این دستگاه؟" else "وصل اینترنت این دستگاه؟")
            .setMessage("$mac\nاین فرمان فقط وقتی فعال است که فرم Access Management → IP/MAC Filter واقعاً از firmware تشخیص داده شده باشد.")
            .setPositiveButton("اجرا") { _, _ ->
                runOperation(if (block) "در حال ثبت MAC Filter اینترنت…" else "در حال برداشتن MAC Filter اینترنت…", 22000,
                    task = { if (block) engine().blockInternet(mac) else engine().unblockInternet(mac) },
                    onSuccess = { r -> status.text = r.message; updateUi(); renderClients() })
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun activateAllowList() {
        val manager = prefs.getString("manager_mac", null)?.uppercase(Locale.US)
        if (manager.isNullOrBlank()) {
            status.text = "اول دستگاه خودت را به‌عنوان مدیر مشخص کن."
            return
        }
        val selected = allowChecks.filterValues { it.isChecked }.keys.toMutableSet()
        selected.add(manager)
        if (wirelessCapacity > 0 && selected.size > wirelessCapacity) {
            status.text = "ظرفیت واقعی MAC Filter فقط $wirelessCapacity دستگاه است."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("فعال‌سازی ضد QR واقعی")
            .setMessage("فقط ${selected.size} MAC انتخاب‌شده اجازه Association خواهند داشت. دستگاه مدیر داخل فهرست است.")
            .setPositiveButton("فعال کن") { _, _ ->
                runOperation("در حال ثبت Allow‑List روی روتر…", 22000,
                    task = { engine().setAllowList(selected) },
                    onSuccess = { r ->
                        status.text = r.message
                        if (r.ok && r.verified) prefs.edit().putStringSet("allow_macs", selected).apply()
                        updateUi(); renderClients()
                    })
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun disableFilter() {
        AlertDialog.Builder(this)
            .setTitle("Wireless MAC Filter خاموش شود؟")
            .setMessage("فقط MAC Filter خاموش می‌شود؛ WAN/ADSL، DHCP و رمز Wi‑Fi دست نمی‌خورند.")
            .setPositiveButton("خاموش کن") { _, _ ->
                runOperation("در حال خاموش‌کردن MAC Filter…", 22000,
                    task = { engine().disableWirelessFilter() },
                    onSuccess = { r -> status.text = r.message; updateUi(); renderClients() })
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun readStats() {
        runOperation("در حال خواندن Byte Counter از روتر…", 18000,
            task = { engine().traffic() },
            onSuccess = { t ->
                if (t == null) {
                    statsText.text = "این firmware شمارندهٔ قابل‌تشخیص ارائه نکرد."
                    status.text = "آمار ساختگی نمایش داده نشد."
                } else {
                    val total = t.rxBytes + t.txBytes
                    statsText.text = "RX: ${formatBytes(t.rxBytes)}\nTX: ${formatBytes(t.txBytes)}\nTotal: ${formatBytes(total)}"
                    status.text = "Byte Counter واقعی از روتر خوانده شد."
                }
                updateUi()
            })
    }

    private fun updateUi() {
        connectBtn.isEnabled = !busy
        refreshBtn.isEnabled = !busy && connected
        antiQrBtn.isEnabled = !busy && connected && caps.wirelessMacFilter
        filterOffBtn.isEnabled = !busy && connected && caps.wirelessMacFilter
        statsBtn.isEnabled = !busy && connected && caps.statistics
        capabilities.text = buildString {
            append("Devices ").append(mark(caps.devices))
            append(" • Wi‑Fi MAC ").append(mark(caps.wirelessMacFilter))
            if (wirelessCapacity > 0) append("(").append(wirelessCapacity).append(")")
            append(" • Internet MAC ").append(mark(caps.internetMacFilter))
            append("\nQoS ").append(mark(caps.qos))
            append(" • Statistics ").append(mark(caps.statistics))
            append(" • Guest ").append(mark(caps.guest))
            append(" • Guest BW ").append(mark(caps.guestBandwidth))
        }
    }

    private fun mark(v: Boolean) = if (v) "✓" else "—"
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun formatBytes(v: Long): String {
        val gb = v.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 0.01) String.format(Locale.US, "%.3f GB", gb) else String.format(Locale.US, "%.2f MB", v / (1024.0 * 1024.0))
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        if (message != null) status.text = message
        updateUi()
        renderClients()
    }

    private fun <T> runOperation(label: String, timeoutMs: Long, task: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        val id = operationId.incrementAndGet()
        setBusy(true, label)
        var future: Future<*>? = null
        future = executor.submit {
            try {
                val value = task()
                main.post {
                    if (operationId.get() != id) return@post
                    setBusy(false)
                    onSuccess(value)
                }
            } catch (e: Exception) {
                main.post {
                    if (operationId.get() != id) return@post
                    setBusy(false)
                    status.text = "خطا: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        main.postDelayed({
            if (operationId.compareAndSet(id, id + 1)) {
                future?.cancel(true)
                setBusy(false)
                status.text = "Timeout: عملیات در ${timeoutMs / 1000} ثانیه تمام نشد. اپ دیگر در حالت «در حال ورود» گیر نمی‌ماند."
            }
        }, timeoutMs)
    }
}
