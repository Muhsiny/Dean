package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastClients: List<String> = emptyList()
    private var scanActive = false
    private var scanGeneration = 0
    private var scanCandidates: List<String> = emptyList()
    private var scanIndex = 0

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
        web.settings.loadsImagesAutomatically = false
        web.settings.allowContentAccess = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(username.text.toString(), password.text.toString())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!scanActive) return
                val generation = scanGeneration
                handler.postDelayed({
                    if (scanActive && generation == scanGeneration) extractClientsFromLoadedPanel()
                }, 1200)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (scanActive && request?.isForMainFrame == true) {
                    tryNextScanCandidate("صفحه پاسخ نداد")
                }
            }
        }

        updateProtectedStatus()
        renderClients()
        findViewById<Button>(R.id.testBtn).setOnClickListener { testReadOnly() }
        findViewById<Button>(R.id.scanBtn).setOnClickListener { scanClientsThroughPanel() }
        findViewById<Button>(R.id.openStatus).setOnClickListener { openRouter(urlFor("/rpSys.html")) }
        findViewById<Button>(R.id.openAdmin).setOnClickListener { openRouter(baseUrl()) }
    }

    private fun openRouter(startUrl: String) {
        val intent = Intent(this, RouterActivity::class.java).apply {
            putExtra("baseUrl", baseUrl())
            putExtra("startUrl", startUrl)
            putExtra("user", username.text.toString())
            putExtra("pass", password.text.toString())
        }
        startActivity(intent)
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

    private fun scanClientsThroughPanel() {
        scanGeneration++
        scanActive = true
        scanIndex = 0
        scanCandidates = listOf(baseUrl(), urlFor("/rpSys.html"))
        lastClients = emptyList()
        renderClients()
        status.text = "در حال ورود امن به پنل و خواندن جدول دستگاه‌های متصل…"
        loadCurrentScanCandidate()
    }

    private fun loadCurrentScanCandidate() {
        if (!scanActive) return
        if (scanIndex >= scanCandidates.size) {
            scanActive = false
            status.text = "روتر وصل است، اما جدول دستگاه‌ها هنوز خودکار پیدا نشد. هیچ تنظیمی تغییر نکرد. «صفحه وضعیت روتر» را باز کن تا مسیر دقیق همین firmware را تشخیص دهیم."
            return
        }
        val generation = ++scanGeneration
        val target = scanCandidates[scanIndex]
        val user = username.text.toString()
        val pass = password.text.toString()
        val headers = if (user.isNotBlank()) mapOf("Authorization" to authHeader(user, pass)) else emptyMap()
        status.text = "در حال خواندن پنل واقعی روتر (${scanIndex + 1}/${scanCandidates.size})…"
        web.stopLoading()
        web.clearHistory()
        web.loadUrl(target, headers)
        handler.postDelayed({
            if (scanActive && generation == scanGeneration) tryNextScanCandidate("زمان پاسخ صفحه تمام شد")
        }, 11000)
    }

    private fun tryNextScanCandidate(reason: String) {
        if (!scanActive) return
        scanIndex++
        if (scanIndex < scanCandidates.size) {
            status.text = "$reason؛ مسیر بعدی پنل را آزمایش می‌کنم…"
            loadCurrentScanCandidate()
        } else {
            scanActive = false
            status.text = "$reason. اتصال اصلی روتر سالم است، اما جدول دستگاه‌ها از این دو مسیر استخراج نشد. هیچ تنظیمی تغییر نکرد."
        }
    }

    private fun extractClientsFromLoadedPanel() {
        if (!scanActive) return
        val js = """
            (function(){
              var parts=[];
              function grab(w){
                try {
                  var d=w.document;
                  if(d && d.documentElement){ parts.push(d.documentElement.outerHTML || d.documentElement.innerHTML || ''); }
                  for(var i=0;i<w.frames.length;i++){ try{ grab(w.frames[i]); }catch(e){} }
                } catch(e){}
              }
              grab(window);
              return parts.join('\n');
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val html = decodeJavascriptString(raw)
            val clients = parseClientMacs(html)
            if (clients.isNotEmpty()) {
                scanActive = false
                scanGeneration++
                lastClients = clients
                renderClients()
                status.text = "${clients.size} دستگاه متصل از پنل واقعی روتر خوانده شد. هیچ تنظیمی تغییر نکرد."
            } else {
                tryNextScanCandidate("در این صفحه جدول دستگاه‌های متصل پیدا نشد")
            }
        }
    }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim('"') }
    }

    private fun parseClientMacs(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val markers = listOf("Current Connected Wireless Clients", "Connected Wireless Clients", "Wireless Clients")
        val starts = markers.map { html.indexOf(it, ignoreCase = true) }.filter { it >= 0 }
        if (starts.isEmpty()) return emptyList()
        val start = starts.minOrNull() ?: return emptyList()
        val section = html.substring(start, minOf(html.length, start + 60000))
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
            clientList.addView(TextView(this).apply {
                text = "هنوز دستگاهی خوانده نشده است."
                setPadding(0, dp(10), 0, dp(10))
            })
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