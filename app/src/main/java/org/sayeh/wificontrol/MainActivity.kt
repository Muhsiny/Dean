package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var clientList: LinearLayout
    private lateinit var protectedStatus: TextView
    private lateinit var routerUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }
    private var lastClients: List<String> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        routerUrl = findViewById(R.id.routerUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        clientList = findViewById(R.id.clientList)
        protectedStatus = findViewById(R.id.protectedStatus)
        web = findViewById(R.id.web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowContentAccess = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(username.text.toString(), password.text.toString())
            }
        }

        updateProtectedStatus()
        findViewById<Button>(R.id.testBtn).setOnClickListener { testReadOnly() }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { scanClients() }
        findViewById<Button>(R.id.openStatus).setOnClickListener { web.loadUrl(urlFor("/rpSys.html")) }
        findViewById<Button>(R.id.openAdmin).setOnClickListener { web.loadUrl(baseUrl()) }
    }

    private fun baseUrl(): String {
        val raw = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }
        return raw.trimEnd('/')
    }

    private fun urlFor(path: String): String = baseUrl() + path

    private fun authHeader(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private data class HttpResult(val code: Int, val body: String, val server: String)

    private fun getReadOnly(url: String): HttpResult {
        val user = username.text.toString()
        val pass = password.text.toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Connection", "close")
            if (user.isNotBlank()) setRequestProperty("Authorization", authHeader(user, pass))
        }
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, body, connection.getHeaderField("Server").orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun testReadOnly() {
        status.text = "در حال آزمایش اتصال…"
        thread {
            val text = try {
                val result = getReadOnly(baseUrl())
                when (result.code) {
                    401, 403 -> "روتر پیدا شد، اما نام کاربری یا رمز پذیرفته نشد. هیچ تنظیمی تغییر نکرد."
                    in 200..399 -> "اتصال واقعی برقرار شد • HTTP ${result.code}${if (result.server.isNotBlank()) " • ${result.server}" else ""}\nهیچ تنظیمی تغییر نکرد."
                    else -> "روتر پاسخ داد: HTTP ${result.code}. هیچ تنظیمی تغییر نکرد."
                }
            } catch (e: Exception) {
                "اتصال برقرار نشد: ${e.message ?: "خطای نامشخص"}"
            }
            runOnUiThread { status.text = text }
        }
    }

    private fun scanClients() {
        status.text = "در حال خواندن صفحه وضعیت روتر…"
        thread {
            try {
                val result = getReadOnly(urlFor("/rpSys.html"))
                if (result.code == 401 || result.code == 403) {
                    runOnUiThread { status.text = "روتر پاسخ داد، اما اطلاعات ورود صحیح نیست. هیچ تغییری انجام نشد." }
                    return@thread
                }
                if (result.code !in 200..399) {
                    runOnUiThread { status.text = "صفحه وضعیت با HTTP ${result.code} پاسخ داد. هیچ تغییری انجام نشد." }
                    return@thread
                }
                val clients = parseClientMacs(result.body)
                runOnUiThread {
                    lastClients = clients
                    renderClients()
                    status.text = if (clients.isEmpty()) {
                        "اتصال برقرار است، اما MAC دستگاه‌ها از این پاسخ استخراج نشد. صفحه وضعیت را از داخل اپ باز کن؛ هیچ تنظیمی تغییر نکرد."
                    } else {
                        "${clients.size} دستگاه/MAC از صفحه وضعیت خوانده شد. این فقط خواندن اطلاعات است."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خواندن دستگاه‌ها ناموفق بود: ${e.message ?: "خطای نامشخص"}" }
            }
        }
    }

    private fun parseClientMacs(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val title = "Current Connected Wireless Clients"
        val start = html.indexOf(title, ignoreCase = true)
        val section = if (start >= 0) html.substring(start, minOf(html.length, start + 60000)) else html
        val macRegex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        return macRegex.findAll(section)
            .map { it.value.replace('-', ':').uppercase(Locale.US) }
            .distinct()
            .take(128)
            .toList()
    }

    private fun renderClients() {
        clientList.removeAllViews()
        if (lastClients.isEmpty()) {
            val empty = TextView(this).apply {
                text = "هنوز دستگاهی خوانده نشده است."
                setPadding(0, dp(10), 0, dp(10))
            }
            clientList.addView(empty)
            updateProtectedStatus()
            return
        }
        val protectedMac = prefs.getString("protected_mac", null)
        lastClients.forEachIndexed { index, mac ->
            val alias = prefs.getString("alias_$mac", null)
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
            if (mac == protectedMac) {
                card.addView(TextView(this).apply {
                    text = "✓ تلفن مدیر — محافظت‌شده"
                    setPadding(0, dp(6), 0, dp(4))
                })
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = if (mac == protectedMac) "محافظت‌شده" else "این تلفن من است"
                isEnabled = mac != protectedMac
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    renderClients()
                    status.text = "MAC $mac به‌عنوان تلفن مدیر محافظت شد. هنوز هیچ تنظیمی روی روتر تغییر نکرده است."
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(Button(this).apply {
                text = "نام‌گذاری"
                setOnClickListener { showAliasDialog(mac) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(actions)
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
            "تلفن مدیر هنوز مشخص نشده — قبل از فعال‌کردن هر نوع محدودیت در نسخه‌های بعد، آن را مشخص کن."
        } else {
            "تلفن مدیر محافظت‌شده: $mac"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}