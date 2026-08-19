package org.sayeh.wificontrol

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var clientList: LinearLayout
    private lateinit var protectedStatus: TextView
    private lateinit var routerUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }
    private var lastClients: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        routerUrl = findViewById(R.id.routerUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        clientList = findViewById(R.id.clientList)
        protectedStatus = findViewById(R.id.protectedStatus)

        username.setText("admin")
        findViewById<Button>(R.id.scanBtn).setOnClickListener { openRouter(autoScan = true) }
        findViewById<Button>(R.id.deepMapBtn).setOnClickListener { openRouter(action = "deep_map") }
        findViewById<Button>(R.id.wirelessBtn).setOnClickListener { openRouter(action = "nav_wireless") }
        findViewById<Button>(R.id.guestBtn).setOnClickListener { openRouter(action = "nav_guest") }
        findViewById<Button>(R.id.filterBtn).setOnClickListener { openRouter(action = "nav_filter") }
        findViewById<Button>(R.id.statsBtn).setOnClickListener { openRouter(action = "nav_stats") }
        findViewById<Button>(R.id.cliBtn).setOnClickListener { runTelnetProbe() }
        findViewById<Button>(R.id.antiQrBtn).setOnClickListener { enableAntiQr() }
        findViewById<Button>(R.id.antiQrOffBtn).setOnClickListener { disableAntiQr() }
        findViewById<Button>(R.id.openAdmin).setOnClickListener { openRouter() }

        loadLocalState()
    }

    override fun onResume() {
        super.onResume()
        loadLocalState()
    }

    private fun loadLocalState() {
        lastClients = prefs.getStringSet("detected_macs", emptySet())
            ?.map { it.uppercase(Locale.US) }
            ?.sorted()
            ?: emptyList()
        renderClients()
        val routerMessage = prefs.getString("router_last_message", null)
        if (!routerMessage.isNullOrBlank()) status.text = routerMessage
        else if (lastClients.isNotEmpty()) status.text = "${lastClients.size} دستگاه از آخرین همگام‌سازی واقعی روتر ثبت شده است."
    }

    private fun credentialsReady(): Boolean {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            status.text = "نام کاربری و رمز ادمین روتر را وارد کن."
            return false
        }
        return true
    }

    private fun openRouter(
        autoScan: Boolean = false,
        action: String? = null,
        targetMac: String? = null,
        allowedMacs: Set<String>? = null
    ) {
        if (!credentialsReady()) return
        val intent = Intent(this, RouterActivity::class.java).apply {
            putExtra("baseUrl", baseUrl())
            putExtra("startUrl", baseUrl())
            putExtra("user", username.text.toString())
            putExtra("pass", password.text.toString())
            putExtra("autoScan", autoScan)
            if (action != null) putExtra("action", action)
            if (targetMac != null) putExtra("targetMac", targetMac)
            if (allowedMacs != null) putExtra("allowedMacs", allowedMacs.joinToString(","))
        }
        startActivity(intent)
    }

    private fun baseUrl(): String {
        val raw = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }
        return raw.trimEnd('/')
    }

    private fun renderClients() {
        clientList.removeAllViews()
        val protectedMac = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        val blocked = prefs.getStringSet("blocked_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val antiQrActive = prefs.getBoolean("anti_qr_active", false)

        if (lastClients.isEmpty()) {
            clientList.addView(TextView(this).apply {
                text = "هنوز دستگاهی همگام نشده است. «ورود + همگام‌سازی عمیق دستگاه‌ها» را بزن."
                setPadding(0, dp(10), 0, dp(10))
            })
            updateProtectedStatus()
            return
        }

        lastClients.forEachIndexed { index, mac ->
            val alias = prefs.getString("alias_$mac", null)
            val isProtected = mac == protectedMac
            val isBlocked = mac in blocked
            val isAllowed = isProtected || mac in allowed
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            }
            card.addView(TextView(this).apply {
                text = alias?.takeIf { it.isNotBlank() } ?: "دستگاه ${index + 1}"
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = mac
                textDirection = TextView.TEXT_DIRECTION_LTR
                setTextIsSelectable(true)
            })
            card.addView(TextView(this).apply {
                text = when {
                    isProtected -> "✓ تلفن مدیر — محافظت‌شده و همیشه مجاز"
                    antiQrActive && isAllowed -> "✓ مجاز در ضد QR"
                    antiQrActive && !isAllowed -> "⛔ خارج از Allow‑List"
                    isBlocked -> "⛔ مسدود در فیلتر MAC"
                    isAllowed -> "✓ برای ضد QR علامت‌گذاری شده"
                    else -> "● دستگاه شناسایی‌شده"
                }
                setPadding(0, dp(5), 0, dp(5))
            })

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(Button(this).apply {
                text = if (isProtected) "تلفن مدیر" else "این تلفن من است"
                isEnabled = !isProtected
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    renderClients()
                    status.text = "$mac به‌عنوان تلفن مدیر محافظت شد."
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row1.addView(Button(this).apply {
                text = "نام‌گذاری"
                setOnClickListener { showAliasDialog(mac) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row1)

            if (!isProtected) {
                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row2.addView(Button(this).apply {
                    text = if (isAllowed) "لغو مجوز ضد QR" else "مجاز ضد QR"
                    setOnClickListener {
                        val next = prefs.getStringSet("allowed_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (isAllowed) next.remove(mac) else next.add(mac)
                        prefs.edit().putStringSet("allowed_macs", next).apply()
                        renderClients()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row2.addView(Button(this).apply {
                    text = if (isBlocked) "رفع مسدودی" else "مسدود واقعی"
                    isEnabled = protectedMac != null && !antiQrActive
                    setOnClickListener {
                        val act = if (isBlocked) "unblock" else "block"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(if (isBlocked) "رفع مسدودی $mac؟" else "مسدود کردن $mac؟")
                            .setMessage("فرمان روی فیلتر واقعی روتر آماده می‌شود و فقط بعد از تأیید نهایی SAVE اجرا خواهد شد.")
                            .setPositiveButton("ادامه") { _, _ -> openRouter(action = act, targetMac = mac) }
                            .setNegativeButton("لغو", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(row2)
            }
            clientList.addView(card)
        }
        updateProtectedStatus()
    }

    private fun enableAntiQr() {
        val protectedMac = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        if (protectedMac.isNullOrBlank()) {
            status.text = "اول تلفن مدیر را مشخص کن؛ ضد QR بدون محافظت مدیر فعال نمی‌شود."
            return
        }
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
        allowed.add(protectedMac)
        if (allowed.size > 8) {
            status.text = "این firmware برای Wireless MAC Filter تعداد محدودی خانه دارد؛ فعلاً بیش از ۸ MAC را یک‌جا فعال نکن."
            return
        }
        val excluded = lastClients.count { it !in allowed }
        AlertDialog.Builder(this)
            .setTitle("فعال‌سازی ضد QR واقعی؟")
            .setMessage("Allow Association فعال می‌شود. ${allowed.size} دستگاه مجاز می‌ماند و $excluded دستگاه فعلی که علامت مجاز ندارند ممکن است فوراً از Wi‑Fi قطع شوند. تلفن مدیر داخل فهرست مجاز است.")
            .setPositiveButton("فعال کن") { _, _ -> openRouter(action = "antiqr_enable", allowedMacs = allowed) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun disableAntiQr() {
        if (!prefs.getBoolean("anti_qr_active", false)) {
            status.text = "ضد QR در وضعیت ثبت‌شده اپ فعال نیست."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("خاموش‌کردن ضد QR؟")
            .setMessage("Wireless MAC Address Filter روتر Deactivated می‌شود و دانستن رمز/QR دوباره برای اتصال کافی خواهد بود.")
            .setPositiveButton("خاموش کن") { _, _ -> openRouter(action = "antiqr_disable") }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showAliasDialog(mac: String) {
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

    private fun updateProtectedStatus() {
        val mac = prefs.getString("protected_mac", null)
        protectedStatus.text = if (mac.isNullOrBlank()) {
            "تلفن مدیر هنوز مشخص نشده — فرمان‌های پرخطر غیرفعال‌اند."
        } else {
            "تلفن مدیر محافظت‌شده: $mac"
        }
    }

    private fun runTelnetProbe() {
        if (!credentialsReady()) return
        status.text = "در حال آزمایش CLI/Telnet روتر؛ هیچ فرمان تغییردهنده‌ای ارسال نمی‌شود…"
        val host = try { URI(baseUrl()).host ?: "192.168.1.1" } catch (_: Exception) { "192.168.1.1" }
        val user = username.text.toString()
        val pass = password.text.toString()
        thread {
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 23), 3000)
                    socket.soTimeout = 1200
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    fun send(s: String) { output.write((s + "\r\n").toByteArray()); output.flush(); Thread.sleep(350) }
                    fun readWindow(ms: Long): String {
                        val end = System.currentTimeMillis() + ms
                        val buf = ByteArray(4096)
                        val out = StringBuilder()
                        while (System.currentTimeMillis() < end) {
                            try {
                                while (input.available() > 0) {
                                    val n = input.read(buf)
                                    if (n > 0) out.append(String(buf, 0, n, Charsets.ISO_8859_1))
                                }
                            } catch (_: Exception) {}
                            Thread.sleep(80)
                        }
                        return out.toString().replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
                    }
                    var transcript = readWindow(800)
                    val low0 = transcript.lowercase(Locale.US)
                    if (low0.contains("username") || low0.contains("login")) {
                        send(user)
                        transcript += readWindow(500)
                        send(pass)
                    } else {
                        send(pass)
                    }
                    transcript += readWindow(1000)
                    val low = transcript.lowercase(Locale.US)
                    if (low.contains("failed") || low.contains("incorrect") || low.contains("denied")) {
                        "Telnet روی روتر باز است، اما رمز CLI با رمز وب یکی نیست. هیچ تغییری انجام نشد."
                    } else {
                        send("wan adsl status")
                        transcript += readWindow(600)
                        send("wan adsl c")
                        transcript += readWindow(800)
                        send("help")
                        transcript += readWindow(1000)
                        val cleaned = transcript.lines().filter { it.isNotBlank() }.takeLast(18).joinToString("\n")
                        "CLI/Telnet پاسخ داد. فرمان‌های فقط‌خواندنی ADSL آزمایش شدند:\n" + cleaned.take(1400)
                    }
                }
            } catch (e: Exception) {
                "Telnet/CLI فعلاً در پورت 23 پاسخ نداد (${e.javaClass.simpleName}). می‌توانیم از ACL روتر بررسی کنیم که Telnet فعال است یا نه؛ هیچ تنظیمی تغییر نکرد."
            }
            runOnUiThread {
                status.text = result
                prefs.edit().putString("router_last_message", result).apply()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
