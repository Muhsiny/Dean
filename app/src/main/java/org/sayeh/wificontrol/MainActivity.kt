package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var authWeb: WebView

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
    private var authSubmitted = false
    private var authGeneration = 0
    private var routerEngine: DirectRouter? = null

    @SuppressLint("SetJavaScriptEnabled")
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

        authWeb = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            alpha = 0.01f
        }
        CookieManager.getInstance().setAcceptCookie(true)
        findViewById<FrameLayout>(android.R.id.content).addView(
            authWeb,
            FrameLayout.LayoutParams(1, 1)
        )
        authWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!busy) return
                main.postDelayed({ handleAuthPage() }, 200)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && busy) {
                    finishAuthFailure("صفحه Login روتر باز نشد: ${error?.description ?: "خطای شبکه"}")
                }
            }
        }

        connectBtn.setOnClickListener { connectWithFirmwareLogin() }
        refreshBtn.setOnClickListener { refreshClients() }
        antiQrBtn.setOnClickListener { activateAllowList() }
        filterOffBtn.setOnClickListener { disableFilter() }
        statsBtn.setOnClickListener { readStats() }
        renderClients()
        updateUi()
    }

    override fun onDestroy() {
        authGeneration++
        operationId.incrementAndGet()
        executor.shutdownNow()
        try {
            (authWeb.parent as? ViewGroup)?.removeView(authWeb)
            authWeb.stopLoading()
            authWeb.destroy()
        } catch (_: Exception) { }
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

    private fun newEngine(): DirectRouter = DirectRouter(
        baseUrl(),
        username.text.toString().trim(),
        password.text.toString(),
        prefs.getString("manager_mac", null)
    )

    private fun engine(): DirectRouter = routerEngine ?: newEngine().also { routerEngine = it }

    private fun connectWithFirmwareLogin() {
        if (!credentialsReady() || busy) return
        connected = false
        routerEngine = null
        caps = DirectRouter.Capabilities()
        wirelessCapacity = 0
        currentClients = emptyList()
        authSubmitted = false
        authGeneration++
        val generation = authGeneration
        setBusy(true, "در حال ورود با موتور واقعی Login خود firmware…")

        try {
            authWeb.stopLoading()
            authWeb.clearCache(true)
            authWeb.loadUrl(baseUrl())
        } catch (e: Exception) {
            finishAuthFailure("بازکردن Login ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        main.postDelayed({
            if (busy && generation == authGeneration) {
                try { authWeb.stopLoading() } catch (_: Exception) { }
                finishAuthFailure("Timeout: روتر طی ۲۰ ثانیه Login را کامل نکرد.")
            }
        }, 20_000)
    }

    private fun handleAuthPage() {
        if (!busy) return
        val js = """
            (function(){try{
              var p=document.querySelector('input[type=password]');
              var t=(document.body?document.body.innerText:'').toLowerCase();
              return !!p || location.href.toLowerCase().indexOf('login_security')>=0 ||
                     (t.indexOf('username')>=0 && t.indexOf('password')>=0 && t.indexOf('login')>=0);
            }catch(e){return false;}})();
        """.trimIndent()
        authWeb.evaluateJavascript(js) { raw ->
            val isLogin = raw == "true"
            if (isLogin) {
                if (authSubmitted) {
                    finishAuthFailure("روتر Login را دوباره نشان داد. نام/رمز خام نیست؛ ورود firmware تأیید نشد.")
                } else {
                    submitFirmwareLogin()
                }
            } else {
                CookieManager.getInstance().flush()
                status.text = "Login خود firmware پذیرفته شد؛ در حال آغاز کنترل مستقیم HTTP…"
                probeAfterBrowserLogin()
            }
        }
    }

    private fun submitFirmwareLogin() {
        authSubmitted = true
        val user = JSONObject.quote(username.text.toString().trim())
        val pass = JSONObject.quote(password.text.toString())
        val js = """
            (function(){try{
              var u=document.querySelector('input[name="Login_Name"],input[name*=user i],input[id*=user i],input[type=text]');
              var p=document.querySelector('input[name="Login_Pwd"],input[type=password],input[name*=pass i],input[id*=pass i]');
              if(!u||!p)return 'NO_FORM';
              u.value=$user; p.value=$pass;
              ['input','change'].forEach(function(n){
                u.dispatchEvent(new Event(n,{bubbles:true}));
                p.dispatchEvent(new Event(n,{bubbles:true}));
              });
              var f=p.form||u.form||document.forms[0];
              if(!f)return 'NO_FORM';
              var bs=f.querySelectorAll('input[type=button],input[type=submit],button');
              for(var i=0;i<bs.length;i++){
                var s=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();
                if(s.indexOf('login')>=0){bs[i].click();return 'CLICKED_LOGIN_BUTTON';}
              }
              if(typeof checkForm==='function'){checkForm();return 'CALLED_CHECKFORM';}
              if(bs.length){bs[0].click();return 'CLICKED_FIRST_BUTTON';}
              return 'NO_LOGIN_BUTTON';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        status.text = "نام/رمز به صفحه firmware داده شد؛ خود checkForm روتر در حال اجرا است…"
        authWeb.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR:")) {
                finishAuthFailure("فرم Login firmware قابل اجرا نبود: $r")
            }
        }
    }

    private fun probeAfterBrowserLogin() {
        val generation = authGeneration
        val localEngine = newEngine()
        routerEngine = localEngine
        executor.submit {
            val snap = try {
                localEngine.connectAndProbe()
            } catch (e: Exception) {
                DirectRouter.Snapshot(false, "کنترل مستقیم بعد از Login ناموفق: ${e.message ?: e.javaClass.simpleName}")
            }
            main.post {
                if (generation != authGeneration || !busy) return@post
                setBusy(false)
                if (!snap.ok) {
                    connected = false
                    caps = DirectRouter.Capabilities()
                    wirelessCapacity = 0
                    currentClients = emptyList()
                    status.text = snap.message + "\nLogin در مرورگر داخلی پذیرفته شد، اما session هنوز به موتور مستقیم منتقل نشد."
                } else {
                    connected = true
                    caps = snap.capabilities
                    wirelessCapacity = snap.wirelessCapacity
                    currentClients = snap.clients
                    status.text = buildString {
                        append(snap.message)
                        append("\nاحراز هویت توسط JavaScript خود firmware انجام شد.")
                        if (snap.firmware.isNotBlank()) append("\nFirmware: ").append(snap.firmware)
                    }
                }
                renderClients()
                updateUi()
            }
        }
    }

    private fun finishAuthFailure(message: String) {
        if (!busy) return
        authGeneration++
        try { authWeb.stopLoading() } catch (_: Exception) { }
        routerEngine = null
        connected = false
        setBusy(false)
        status.text = message
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
                text = if (connected) "روتر فعلاً Wireless Client نشان نداد." else "برای خواندن دستگاه‌ها «اتصال به روتر» را بزن."
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
                status.text = "Timeout: عملیات در ${timeoutMs / 1000} ثانیه تمام نشد."
            }
        }, timeoutMs)
    }

    private fun decodeJs(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            JSONArray("[$raw]").optString(0)
        } catch (_: Exception) {
            raw.trim('"').replace("\\\"", "\"").replace("\\n", "\n")
        }
    }
}
