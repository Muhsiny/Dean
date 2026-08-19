package org.sayeh.wificontrol

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
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
        findViewById<Button>(R.id.testBtn).setOnClickListener { openRouter(autoScan = false) }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { openRouter(autoScan = true) }
        findViewById<Button>(R.id.openStatus).setOnClickListener { openRouter(autoScan = true) }
        findViewById<Button>(R.id.openAdmin).setOnClickListener { openRouter(autoScan = false) }
        findViewById<Button>(R.id.openMacFilter).setOnClickListener { openRouter(openMacFilter = true) }

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
        if (lastClients.isNotEmpty()) {
            status.text = "${lastClients.size} دستگاه از آخرین همگام‌سازی واقعی روتر ثبت شده است."
        }
    }

    private fun openRouter(
        autoScan: Boolean = false,
        openMacFilter: Boolean = false,
        action: String? = null,
        targetMac: String? = null
    ) {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            status.text = "نام کاربری و رمز ادمین روتر را وارد کن."
            return
        }
        val intent = Intent(this, RouterActivity::class.java).apply {
            putExtra("baseUrl", baseUrl())
            putExtra("startUrl", baseUrl())
            putExtra("user", username.text.toString())
            putExtra("pass", password.text.toString())
            putExtra("autoScan", autoScan)
            putExtra("openMacFilter", openMacFilter)
            if (action != null) putExtra("action", action)
            if (targetMac != null) putExtra("targetMac", targetMac)
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

        if (lastClients.isEmpty()) {
            clientList.addView(TextView(this).apply {
                text = "هنوز دستگاهی همگام نشده است. دکمه «ورود + همگام‌سازی واقعی» را بزن."
                setPadding(0, dp(10), 0, dp(10))
            })
            updateProtectedStatus()
            return
        }

        lastClients.forEachIndexed { index, mac ->
            val alias = prefs.getString("alias_$mac", null)
            val isProtected = mac == protectedMac
            val isBlocked = mac in blocked
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
                    isProtected -> "✓ تلفن مدیر — محافظت‌شده"
                    isBlocked -> "⛔ مسدود در فیلتر MAC روتر"
                    else -> "● دستگاه شناسایی‌شده"
                }
                setPadding(0, dp(5), 0, dp(5))
            })

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(Button(this).apply {
                text = if (isProtected) "محافظت‌شده" else "این تلفن من است"
                isEnabled = !isProtected
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    renderClients()
                    status.text = "$mac به‌عنوان تلفن مدیر محافظت شد."
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row1.addView(Button(this).apply {
                text = "نام‌گذاری"
                setOnClickListener { showAliasDialog(mac) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row1)

            val control = Button(this).apply {
                text = if (isBlocked) "رفع مسدودی واقعی" else "مسدود کردن واقعی"
                isEnabled = protectedMac != null && !isProtected
                setOnClickListener {
                    val action = if (isBlocked) "unblock" else "block"
                    val title = if (isBlocked) "رفع مسدودی $mac؟" else "مسدود کردن $mac؟"
                    val msg = if (isBlocked) {
                        "اپ وارد فیلتر MAC واقعی روتر می‌شود و فقط بعد از تأیید نهایی SAVE می‌کند."
                    } else {
                        "اپ این MAC را در Deny Association واقعی روتر آماده می‌کند. تلفن مدیر هرگز هدف این فرمان قرار نمی‌گیرد."
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton("ادامه") { _, _ -> openRouter(action = action, targetMac = mac) }
                        .setNegativeButton("لغو", null)
                        .show()
                }
            }
            card.addView(control)
            if (protectedMac == null) {
                card.addView(TextView(this).apply {
                    text = "برای فعال‌شدن کنترل، اول تلفن مدیر را مشخص کن."
                    setPadding(0, dp(3), 0, 0)
                })
            }
            clientList.addView(card)
        }
        updateProtectedStatus()
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
            "تلفن مدیر هنوز مشخص نشده — تا آن زمان هیچ فرمان قطع/مسدودی اجرا نمی‌شود."
        } else {
            "تلفن مدیر محافظت‌شده: $mac"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}