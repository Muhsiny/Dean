package org.sayeh.wificontrol

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

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

        findViewById<Button>(R.id.scanBtn).setOnClickListener { openEngine("sync") }
        findViewById<Button>(R.id.calibrateBtn).setOnClickListener { openEngine("calibrate") }
        findViewById<Button>(R.id.antiQrBtn).setOnClickListener { enableAntiQr() }
        findViewById<Button>(R.id.antiQrOffBtn).setOnClickListener { disableAntiQr() }
        loadState()
    }

    override fun onResume() {
        super.onResume()
        loadState()
    }

    private fun baseUrl(): String = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }.trimEnd('/')

    private fun credentialsReady(): Boolean {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            status.text = "نام کاربری و رمز ادمین روتر را وارد کن."
            return false
        }
        return true
    }

    private fun openEngine(action: String, targetMac: String? = null, allowedMacs: Set<String>? = null) {
        if (!credentialsReady()) return
        val intent = Intent(this, RouterActivity::class.java).apply {
            putExtra("baseUrl", baseUrl())
            putExtra("user", username.text.toString())
            putExtra("pass", password.text.toString())
            putExtra("action", action)
            if (targetMac != null) putExtra("targetMac", targetMac)
            if (allowedMacs != null) putExtra("allowedMacs", allowedMacs.joinToString(","))
        }
        startActivity(intent)
    }

    private fun loadState() {
        lastClients = prefs.getStringSet("detected_macs", emptySet())
            ?.map { it.uppercase(Locale.US) }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        val message = prefs.getString("router_last_message", null)
        status.text = message ?: if (lastClients.isEmpty()) "آماده" else "${lastClients.size} دستگاه واقعی از آخرین همگام‌سازی ثبت شده است."
        renderClients()
    }

    private fun renderClients() {
        clientList.removeAllViews()
        val protectedMac = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        val blocked = prefs.getStringSet("blocked_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val antiQr = prefs.getBoolean("anti_qr_active", false)

        if (lastClients.isEmpty()) {
            clientList.addView(TextView(this).apply { text = "هنوز دستگاهی خوانده نشده است. «اتصال و تازه‌سازی» را بزن."; setPadding(0, 12, 0, 12) })
            updateProtectedStatus()
            return
        }

        lastClients.forEachIndexed { index, mac ->
            val alias = prefs.getString("alias_$mac", null)
            val isProtected = mac == protectedMac
            val isBlocked = mac in blocked
            val isAllowed = isProtected || mac in allowed
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            box.addView(TextView(this).apply {
                text = alias?.takeIf { it.isNotBlank() } ?: "دستگاه ${index + 1}"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            box.addView(TextView(this).apply { text = mac; textDirection = TextView.TEXT_DIRECTION_LTR; setTextIsSelectable(true) })
            box.addView(TextView(this).apply {
                text = when {
                    isProtected -> "✓ تلفن مدیر — محافظت‌شده"
                    antiQr && isAllowed -> "✓ مجاز در ضد QR"
                    antiQr && !isAllowed -> "⛔ غیرمجاز در ضد QR"
                    isBlocked -> "⛔ مسدود شده و تأییدشده"
                    else -> "● متصل / شناسایی‌شده"
                }
                setPadding(0, dp(5), 0, dp(5))
            })

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(Button(this).apply {
                text = if (isProtected) "تلفن مدیر" else "این تلفن من است"
                isEnabled = !isProtected
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    status.text = "$mac به‌عنوان تلفن مدیر محافظت شد."
                    renderClients()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row1.addView(Button(this).apply {
                text = "نام‌گذاری"
                setOnClickListener { rename(mac) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row1)

            if (!isProtected) {
                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row2.addView(Button(this).apply {
                    text = if (isAllowed) "لغو مجوز QR" else "مجاز برای QR"
                    setOnClickListener {
                        val next = prefs.getStringSet("allowed_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
                        if (isAllowed) next.remove(mac) else next.add(mac)
                        prefs.edit().putStringSet("allowed_macs", next).apply()
                        renderClients()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row2.addView(Button(this).apply {
                    text = if (isBlocked) "وصل‌کردن دوباره" else "قطع واقعی"
                    isEnabled = protectedMac != null && !antiQr
                    setOnClickListener {
                        val action = if (isBlocked) "unblock" else "block"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(if (isBlocked) "وصل‌کردن دوباره؟" else "قطع واقعی این دستگاه؟")
                            .setMessage("MAC: $mac\nفرمان مستقیماً روی Wireless MAC Filter روتر اجرا می‌شود و بعد از SAVE دوباره بررسی می‌شود.")
                            .setPositiveButton("اجرا") { _, _ -> openEngine(action, targetMac = mac) }
                            .setNegativeButton("لغو", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                box.addView(row2)
            }
            clientList.addView(box)
        }
        updateProtectedStatus()
    }

    private fun rename(mac: String) {
        val input = EditText(this).apply { hint = "مثلاً: تلفن احمد"; setText(prefs.getString("alias_$mac", "")) }
        AlertDialog.Builder(this)
            .setTitle("نام دستگاه")
            .setMessage(mac)
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ -> prefs.edit().putString("alias_$mac", input.text.toString().trim()).apply(); renderClients() }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun enableAntiQr() {
        val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        if (owner.isNullOrBlank()) { status.text = "اول تلفن خودت را به‌عنوان «تلفن مدیر» مشخص کن."; return }
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
        allowed.add(owner)
        if (allowed.size > 8) { status.text = "فیلتر MAC این firmware تعداد محدودی خانه دارد؛ فعلاً بیش از ۸ دستگاه مجاز انتخاب نکن."; return }
        AlertDialog.Builder(this)
            .setTitle("ضد QR واقعی فعال شود؟")
            .setMessage("Allow Association روی خود روتر فعال می‌شود. فقط ${allowed.size} دستگاه انتخاب‌شده می‌توانند وصل شوند. تلفن مدیر داخل فهرست است.")
            .setPositiveButton("فعال کن") { _, _ -> openEngine("antiqr_enable", allowedMacs = allowed) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun disableAntiQr() {
        if (!prefs.getBoolean("anti_qr_active", false)) { status.text = "ضد QR در وضعیت تأییدشده اپ فعال نیست."; return }
        AlertDialog.Builder(this)
            .setTitle("ضد QR خاموش شود؟")
            .setMessage("Wireless MAC Filter از حالت Allow‑List خارج می‌شود و دستگاه‌های دیگر دوباره می‌توانند با رمز/QR وصل شوند.")
            .setPositiveButton("خاموش کن") { _, _ -> openEngine("antiqr_disable") }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun updateProtectedStatus() {
        val mac = prefs.getString("protected_mac", null)
        protectedStatus.text = if (mac.isNullOrBlank()) "تلفن مدیر هنوز مشخص نشده — قطع دستگاه‌ها غیرفعال است." else "تلفن مدیر محافظت‌شده: $mac"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
