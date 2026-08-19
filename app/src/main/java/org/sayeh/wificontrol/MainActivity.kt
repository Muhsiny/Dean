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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        routerUrl = findViewById(R.id.routerUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        clientList = findViewById(R.id.clientList)
        protectedStatus = findViewById(R.id.protectedStatus)
        username.setText(prefs.getString("last_user", "admin") ?: "admin")
        routerUrl.setText(prefs.getString("last_router_url", "http://192.168.1.1") ?: "http://192.168.1.1")

        findViewById<Button>(R.id.scanBtn).setOnClickListener { openEngine("sync") }
        findViewById<Button>(R.id.calibrateBtn).setOnClickListener { openEngine("calibrate") }
        findViewById<Button>(R.id.antiQrBtn).setOnClickListener { enableAntiQr() }
        findViewById<Button>(R.id.antiQrOffBtn).setOnClickListener { disableAntiQr() }
        findViewById<Button>(R.id.emergencyBtn).setOnClickListener { emergencyDisableFilter() }
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
        prefs.edit()
            .putString("last_user", username.text.toString().trim())
            .putString("last_router_url", baseUrl())
            .apply()
        return true
    }

    private fun openEngine(action: String, targetMac: String? = null, allowedMacs: Set<String>? = null) {
        if (!credentialsReady()) return
        val intent = Intent(this, RouterActivity::class.java).apply {
            putExtra("baseUrl", baseUrl())
            putExtra("user", username.text.toString().trim())
            putExtra("pass", password.text.toString())
            putExtra("action", action)
            if (targetMac != null) putExtra("targetMac", targetMac)
            if (allowedMacs != null) putExtra("allowedMacs", allowedMacs.joinToString(","))
        }
        startActivity(intent)
    }

    private fun loadState() {
        val online = prefs.getStringSet("online_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val known = prefs.getStringSet("known_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
        val blocked = prefs.getStringSet("blocked_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toSet() ?: emptySet()
        prefs.getString("protected_mac", null)?.uppercase(Locale.US)?.let { known.add(it) }
        known.addAll(online)
        known.addAll(blocked)
        known.addAll(allowed)

        val message = prefs.getString("router_last_message", null)
        status.text = message ?: if (known.isEmpty()) "آماده" else "${online.size} دستگاه آنلاین • ${known.size} دستگاه ثبت‌شده"
        renderClients(known.toList(), online, blocked, allowed)
    }

    private fun renderClients(all: List<String>, online: Set<String>, blocked: Set<String>, allowed: Set<String>) {
        clientList.removeAllViews()
        val protectedMac = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        val antiQr = prefs.getBoolean("anti_qr_active", false)

        val ordered = all.distinct().sortedWith(
            compareByDescending<String> { it == protectedMac }
                .thenByDescending { it in online }
                .thenBy { prefs.getString("alias_$it", "") ?: "" }
                .thenBy { it }
        )

        if (ordered.isEmpty()) {
            clientList.addView(TextView(this).apply {
                text = "هنوز دستگاهی خوانده نشده است. «اتصال و تازه‌سازی واقعی دستگاه‌ها» را بزن."
                setPadding(0, dp(12), 0, dp(12))
            })
            updateProtectedStatus()
            return
        }

        ordered.forEachIndexed { index, mac ->
            val alias = prefs.getString("alias_$mac", null)
            val isProtected = mac == protectedMac
            val isOnline = mac in online
            val isBlocked = mac in blocked
            val isAllowed = isProtected || mac in allowed

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(TextView(this).apply {
                text = alias?.takeIf { it.isNotBlank() } ?: "دستگاه ${index + 1}"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = mac
                textDirection = TextView.TEXT_DIRECTION_LTR
                setTextIsSelectable(true)
            })
            box.addView(TextView(this).apply {
                text = when {
                    isProtected -> if (isOnline) "✓ تلفن مدیر — آنلاین و محافظت‌شده" else "✓ تلفن مدیر — محافظت‌شده"
                    antiQr && isAllowed && isOnline -> "✓ آنلاین • مجاز در ضد QR"
                    antiQr && isAllowed -> "✓ مجاز در ضد QR"
                    antiQr && !isAllowed && isOnline -> "⛔ آنلاین فعلی • خارج از Allow‑List"
                    isBlocked -> "⛔ Block در روتر ثبت و Verify شده"
                    isOnline -> "● آنلاین"
                    else -> "○ آفلاین / سابقه دستگاه"
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
                    loadState()
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
                        val next = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
                        if (isAllowed) next.remove(mac) else next.add(mac)
                        prefs.edit().putStringSet("allowed_macs", next).apply()
                        loadState()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                row2.addView(Button(this).apply {
                    text = if (isBlocked) "وصل‌کردن دوباره" else "قطع واقعی"
                    isEnabled = protectedMac != null && !antiQr
                    setOnClickListener {
                        val action = if (isBlocked) "unblock" else "block"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(if (isBlocked) "وصل‌کردن دوباره؟" else "قطع واقعی این دستگاه؟")
                            .setMessage("MAC: $mac\nفرمان روی Wireless MAC Filter اجرا می‌شود؛ فقط پس از SAVE و Verify نتیجه، وضعیت تغییر می‌کند.")
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
                loadState()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun enableAntiQr() {
        val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
        if (owner.isNullOrBlank()) {
            status.text = "اول تلفن خودت را به‌عنوان «تلفن مدیر» مشخص کن."
            return
        }
        val allowed = prefs.getStringSet("allowed_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
        allowed.add(owner)
        AlertDialog.Builder(this)
            .setTitle("ضد QR واقعی فعال شود؟")
            .setMessage("اپ ابتدا تعداد خانه‌های واقعی MAC Filter را از firmware می‌خواند. اگر ظرفیت کافی باشد Allow Association فعال می‌شود و تلفن مدیر همیشه داخل فهرست می‌ماند.")
            .setPositiveButton("فعال کن") { _, _ -> openEngine("antiqr_enable", allowedMacs = allowed) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun disableAntiQr() {
        AlertDialog.Builder(this)
            .setTitle("ضد QR خاموش شود؟")
            .setMessage("MAC Filter از حالت Allow‑List خارج می‌شود و بعد از Verify، وضعیت اپ به‌روز می‌شود.")
            .setPositiveButton("خاموش کن") { _, _ -> openEngine("antiqr_disable") }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun emergencyDisableFilter() {
        AlertDialog.Builder(this)
            .setTitle("بازگردانی اضطراری دسترسی؟")
            .setMessage("این فرمان فقط Wireless MAC Filter را Deactivate می‌کند؛ WAN/ADSL، رمز Wi‑Fi و DHCP را تغییر نمی‌دهد. برای زمانی است که دستگاهی اشتباه قفل شده باشد.")
            .setPositiveButton("اجرا") { _, _ -> openEngine("filter_off") }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun updateProtectedStatus() {
        val mac = prefs.getString("protected_mac", null)
        protectedStatus.text = if (mac.isNullOrBlank()) {
            "تلفن مدیر هنوز مشخص نشده — Block دستگاه‌ها غیرفعال است."
        } else {
            "تلفن مدیر محافظت‌شده: $mac"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
