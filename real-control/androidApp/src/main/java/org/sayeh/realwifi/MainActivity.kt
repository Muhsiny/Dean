package org.sayeh.realwifi

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Purpose {
        NONE,
        CONNECT_ROOT,
        VERIFY_CLIENTS,
        VERIFY_WIRELESS,
        VERIFY_STATS,
        VERIFY_GUEST_CAPS,
        REFRESH_CLIENTS,
        PREPARE_BLOCK,
        PREPARE_UNBLOCK,
        VERIFY_BLOCK_CONFIG,
        VERIFY_BLOCK_ONLINE,
        PREPARE_ALLOWLIST,
        VERIFY_ALLOWLIST,
        PREPARE_FILTER_OFF,
        VERIFY_FILTER_OFF,
        READ_STATS,
        PREPARE_GUEST_ON,
        PREPARE_GUEST_OFF,
        PREPARE_GUEST_BW,
        VERIFY_GUEST_CHANGE
    }

    companion object {
        private const val DEVICE_PATH = "/status/status_deviceinfo.htm"
        private const val WIRELESS_PATH = "/basic/home_wlan.htm"
        private const val STATS_PATH = "/status/status_statistics.htm"
        private const val GUEST_PATH = "/basic/home_guest_network.htm"
        private const val ROUTER_MAC = "78:8C:B5:DD:8E:F0"
    }

    private lateinit var web: WebView
    private lateinit var routerUrl: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var capabilities: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var connectBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var allowListBtn: Button
    private lateinit var filterOffBtn: Button
    private lateinit var statsBtn: Button
    private lateinit var guestOnBtn: Button
    private lateinit var guestOffBtn: Button
    private lateinit var guestBwBtn: Button
    private lateinit var packageGb: EditText
    private lateinit var guestUp: EditText
    private lateinit var guestDown: EditText
    private lateinit var usageText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("real_wifi_control", MODE_PRIVATE) }
    private val allowChecks = linkedMapOf<String, CheckBox>()

    private lateinit var adapterJs: String
    private var purpose = Purpose.NONE
    private var expectedPath = ""
    private var loginAttempts = 0

    private var connected = false
    private var clientsReady = false
    private var wirelessReady = false
    private var statsReady = false
    private var guestReady = false
    private var guestBandwidthReady = false
    private var wirelessCapacity = 0

    private var targetMac = ""
    private var targetBlocked = false
    private var targetAllowed: List<String> = emptyList()
    private var guestDesiredOn: Boolean? = null
    private var guestDesiredUp: Double? = null
    private var guestDesiredDown: Double? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapterJs = assets.open("router_adapter.js").bufferedReader().use { it.readText() }
        web = findViewById(R.id.routerWeb)
        routerUrl = findViewById(R.id.routerUrl)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        capabilities = findViewById(R.id.capabilities)
        deviceList = findViewById(R.id.deviceList)
        connectBtn = findViewById(R.id.connectBtn)
        refreshBtn = findViewById(R.id.refreshBtn)
        allowListBtn = findViewById(R.id.allowListBtn)
        filterOffBtn = findViewById(R.id.filterOffBtn)
        statsBtn = findViewById(R.id.statsBtn)
        guestOnBtn = findViewById(R.id.guestOnBtn)
        guestOffBtn = findViewById(R.id.guestOffBtn)
        guestBwBtn = findViewById(R.id.guestBwBtn)
        packageGb = findViewById(R.id.packageGb)
        guestUp = findViewById(R.id.guestUp)
        guestDown = findViewById(R.id.guestDown)
        usageText = findViewById(R.id.usageText)

        routerUrl.setText(prefs.getString("router_url", "http://192.168.1.1"))
        username.setText(prefs.getString("router_user", "admin"))
        packageGb.setText(prefs.getString("package_gb", ""))

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.postDelayed({ handlePage(url.orEmpty()) }, 250)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    fail("روتر پاسخ نداد: ${error?.description ?: "خطای شبکه"}")
                }
            }
        }

        connectBtn.setOnClickListener { startConnection() }
        refreshBtn.setOnClickListener {
            if (connected && clientsReady) navigate(DEVICE_PATH, Purpose.REFRESH_CLIENTS)
        }
        allowListBtn.setOnClickListener { activateAllowList() }
        filterOffBtn.setOnClickListener { confirmFilterOff() }
        statsBtn.setOnClickListener {
            if (connected && statsReady) navigate(STATS_PATH, Purpose.READ_STATS)
        }
        guestOnBtn.setOnClickListener { changeGuest(true) }
        guestOffBtn.setOnClickListener { changeGuest(false) }
        guestBwBtn.setOnClickListener { changeGuestBandwidth() }
        updateUi()
    }

    private fun baseUrl(): String = routerUrl.text.toString().trim()
        .ifBlank { "http://192.168.1.1" }
        .trimEnd('/')

    private fun startConnection() {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            fail("نام کاربری و رمز ادمین را وارد کن.")
            return
        }

        prefs.edit()
            .putString("router_url", baseUrl())
            .putString("router_user", username.text.toString().trim())
            .apply()

        connected = false
        clientsReady = false
        wirelessReady = false
        statsReady = false
        guestReady = false
        guestBandwidthReady = false
        wirelessCapacity = 0
        loginAttempts = 0
        allowChecks.clear()
        deviceList.removeAllViews()
        purpose = Purpose.CONNECT_ROOT
        expectedPath = ""
        status.text = "در حال ورود واقعی به روتر…"
        capabilities.text = "در حال Verify قابلیت‌های firmware…"
        updateUi()
        web.loadUrl(baseUrl())
    }

    private fun handlePage(url: String) {
        if (purpose == Purpose.NONE) return

        isLoginPage { login ->
            if (login) {
                autoLogin()
                return@isLoginPage
            }

            loginAttempts = 0
            if (expectedPath.isNotBlank() && !urlPathMatches(url, expectedPath)) {
                web.loadUrl(baseUrl() + expectedPath)
                return@isLoginPage
            }

            when (purpose) {
                Purpose.CONNECT_ROOT -> navigate(DEVICE_PATH, Purpose.VERIFY_CLIENTS)
                Purpose.VERIFY_CLIENTS -> readClients { navigate(WIRELESS_PATH, Purpose.VERIFY_WIRELESS) }
                Purpose.VERIFY_WIRELESS -> verifyWirelessCapabilities()
                Purpose.VERIFY_STATS -> verifyStatsCapabilities()
                Purpose.VERIFY_GUEST_CAPS -> verifyGuestCapabilities()
                Purpose.REFRESH_CLIENTS -> readClients {
                    purpose = Purpose.NONE
                    expectedPath = ""
                    success("فهرست دستگاه‌ها از خود روتر تازه شد.")
                }
                Purpose.PREPARE_BLOCK -> prepareDeviceRule(true)
                Purpose.PREPARE_UNBLOCK -> prepareDeviceRule(false)
                Purpose.VERIFY_BLOCK_CONFIG -> verifyDeviceRuleConfig()
                Purpose.VERIFY_BLOCK_ONLINE -> verifyDevicePresence()
                Purpose.PREPARE_ALLOWLIST -> prepareAllowList()
                Purpose.VERIFY_ALLOWLIST -> verifyAllowList()
                Purpose.PREPARE_FILTER_OFF -> prepareFilterOff()
                Purpose.VERIFY_FILTER_OFF -> verifyFilterOff()
                Purpose.READ_STATS -> readStats()
                Purpose.PREPARE_GUEST_ON -> prepareGuestEnabled(true)
                Purpose.PREPARE_GUEST_OFF -> prepareGuestEnabled(false)
                Purpose.PREPARE_GUEST_BW -> prepareGuestBandwidth()
                Purpose.VERIFY_GUEST_CHANGE -> verifyGuestChange()
                Purpose.NONE -> Unit
            }
        }
    }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        val js = """
            (function(){try{
              return !!document.querySelector('input[type=password]') ||
                     location.href.toLowerCase().indexOf('login_security')>=0;
            }catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun autoLogin() {
        if (loginAttempts >= 3) {
            fail("ورود به روتر تأیید نشد. نام کاربری یا رمز را بررسی کن.")
            purpose = Purpose.NONE
            return
        }
        loginAttempts++

        val uq = JSONObject.quote(username.text.toString().trim())
        val pq = JSONObject.quote(password.text.toString())
        val js = """
            (function(){try{
              var p=document.querySelector('input[type=password]');
              var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');
              if(!u||!p)return 'NO_LOGIN_FORM';
              u.value=$uq;p.value=$pq;
              ['input','change'].forEach(function(n){
                u.dispatchEvent(new Event(n,{bubbles:true}));
                p.dispatchEvent(new Event(n,{bubbles:true}));
              });
              var f=p.form||u.form||document.forms[0];
              if(!f)return 'NO_LOGIN_FORM';
              var bs=f.querySelectorAll('input[type=submit],input[type=button],button');
              for(var i=0;i<bs.length;i++){
                var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();
                if(t.indexOf('login')>=0){bs[i].click();return 'CLICKED';}
              }
              if(bs.length){bs[0].click();return 'CLICKED';}
              f.submit();return 'SUBMITTED';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()

        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) {
                fail("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
                purpose = Purpose.NONE
            }
        }
    }

    private fun navigate(path: String, next: Purpose) {
        purpose = next
        expectedPath = path
        web.loadUrl(baseUrl() + path)
    }

    private fun urlPathMatches(url: String, path: String): Boolean = try {
        URI(url).path.equals(path, ignoreCase = true)
    } catch (_: Exception) {
        url.contains(path, ignoreCase = true)
    }

    private fun evalAdapter(expression: String, callback: (String) -> Unit) {
        val js = adapterJs + "\n;try{JSON.stringify($expression)}catch(e){JSON.stringify({ok:false,error:String(e)})}"
        web.evaluateJavascript(js) { callback(decodeJs(it)) }
    }

    private fun readClients(after: () -> Unit) {
        evalAdapter("RouterAdapter.scanClients(${JSONObject.quote(ROUTER_MAC)})") { json ->
            try {
                val obj = JSONObject(json)
                if (!obj.optBoolean("ok")) {
                    fail("جدول دستگاه‌های متصل از firmware خوانده نشد.")
                    purpose = Purpose.NONE
                    return@evalAdapter
                }

                val rows = mutableListOf<Pair<String, String>>()
                val arr = obj.optJSONArray("clients") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val mac = c.optString("mac").uppercase(Locale.US)
                    if (mac.matches(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")) && mac != ROUTER_MAC) {
                        rows.add(mac to c.optString("row"))
                    }
                }
                clientsReady = true
                renderDevices(rows.distinctBy { it.first })
                after()
            } catch (e: Exception) {
                fail("پاسخ دستگاه‌ها قابل تحلیل نبود: ${e.message}")
                purpose = Purpose.NONE
            }
        }
    }

    private fun verifyWirelessCapabilities() {
        status.text = "در حال Verify فرم واقعی Wireless MAC Filter…"
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            try {
                val o = JSONObject(json)
                wirelessReady = o.optBoolean("ok")
                wirelessCapacity = o.optInt("capacity", 0)
            } catch (_: Exception) {
                wirelessReady = false
                wirelessCapacity = 0
            }
            navigate(STATS_PATH, Purpose.VERIFY_STATS)
        }
    }

    private fun verifyStatsCapabilities() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            statsReady = try { JSONObject(json).optBoolean("ok") } catch (_: Exception) { false }
            navigate(GUEST_PATH, Purpose.VERIFY_GUEST_CAPS)
        }
    }

    private fun verifyGuestCapabilities() {
        evalAdapter("RouterAdapter.scanGuest()") { json ->
            try {
                val o = JSONObject(json)
                guestReady = o.optBoolean("ok")
                guestBandwidthReady = false
                val fields = o.optJSONArray("fields") ?: JSONArray()
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    val meta = (f.optString("name") + " " + f.optString("row")).lowercase(Locale.US)
                    if (meta.contains("upstream") || meta.contains("downstream")) {
                        guestBandwidthReady = true
                    }
                }
            } catch (_: Exception) {
                guestReady = false
                guestBandwidthReady = false
            }

            connected = clientsReady
            purpose = Purpose.NONE
            expectedPath = ""
            if (connected) {
                success("اتصال واقعی برقرار شد. قابلیت‌های قابل‌تشخیص از خود firmware خوانده شدند.")
            } else {
                fail("اتصال کامل Verify نشد.")
            }
        }
    }

    private fun renderDevices(rows: List<Pair<String, String>>) {
        deviceList.removeAllViews()
        allowChecks.clear()
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        val lastAllowed = prefs.getStringSet("last_allow_list", emptySet()) ?: emptySet()

        if (rows.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = "هیچ کلاینت Wireless در جدول روتر دیده نشد."
                setPadding(8, 8, 8, 8)
            })
            return
        }

        rows.forEach { (mac, row) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 12)
            }
            val ip = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(row)?.value.orEmpty()
            val aliasKey = "alias_$mac"
            val alias = prefs.getString(aliasKey, "").orEmpty()

            box.addView(TextView(this).apply {
                text = buildString {
                    if (alias.isNotBlank()) append(alias).append("\n")
                    append(mac)
                    if (ip.isNotBlank()) append("  •  ").append(ip)
                    if (mac == protected) append("  •  مدیر")
                }
                textSize = 16f
            })

            val allowCheck = CheckBox(this).apply {
                text = "مجاز در ضد QR"
                isChecked = mac == protected || mac in lastAllowed
            }
            allowChecks[mac] = allowCheck
            box.addView(allowCheck)

            val aliasInput = EditText(this).apply {
                hint = "نام دستگاه"
                setText(alias)
            }
            box.addView(aliasInput)

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val managerBtn = Button(this).apply {
                text = if (mac == protected) "مدیر ✓" else "مدیر"
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    success("$mac به‌عنوان دستگاه مدیر محافظت شد.")
                    navigate(DEVICE_PATH, Purpose.REFRESH_CLIENTS)
                }
            }
            val saveNameBtn = Button(this).apply {
                text = "ثبت نام"
                setOnClickListener {
                    prefs.edit().putString(aliasKey, aliasInput.text.toString().trim()).apply()
                    success("نام دستگاه ثبت شد.")
                }
            }
            val blockBtn = Button(this).apply {
                text = "قطع"
                isEnabled = wirelessReady && mac != protected
                setOnClickListener { startDeviceRule(mac, true) }
            }
            val unblockBtn = Button(this).apply {
                text = "وصل"
                isEnabled = wirelessReady
                setOnClickListener { startDeviceRule(mac, false) }
            }

            listOf(managerBtn, saveNameBtn, blockBtn, unblockBtn).forEach { b ->
                actions.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(actions)
            deviceList.addView(box)
        }
    }

    private fun startDeviceRule(mac: String, block: Boolean) {
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (block && mac.equals(protected, ignoreCase = true)) {
            fail("دستگاه مدیر محافظت‌شده است و Block نمی‌شود.")
            return
        }
        targetMac = mac.uppercase(Locale.US)
        targetBlocked = block
        status.text = if (block) "در حال آماده‌سازی قطع واقعی $targetMac…" else "در حال برداشتن Block $targetMac…"
        navigate(WIRELESS_PATH, if (block) Purpose.PREPARE_BLOCK else Purpose.PREPARE_UNBLOCK)
    }

    private fun prepareDeviceRule(block: Boolean) {
        val q = JSONObject.quote(targetMac)
        val expr = if (block) "RouterAdapter.prepareBlock($q)" else "RouterAdapter.prepareUnblock($q)"
        evalAdapter(expr) { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("فرمان آماده نشد: ${o.optString("error", "UNKNOWN")}")
                purpose = Purpose.NONE
                return@evalAdapter
            }

            if (!o.optBoolean("needsSave")) {
                purpose = Purpose.VERIFY_BLOCK_CONFIG
                expectedPath = WIRELESS_PATH
                verifyDeviceRuleConfig()
                return@evalAdapter
            }

            purpose = Purpose.VERIFY_BLOCK_CONFIG
            expectedPath = WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { saveJson ->
                val s = safeObject(saveJson)
                if (!s.optBoolean("ok")) {
                    fail("SAVE واقعی اجرا نشد: ${s.optString("error", "UNKNOWN")}")
                    purpose = Purpose.NONE
                } else {
                    handler.postDelayed({
                        if (purpose == Purpose.VERIFY_BLOCK_CONFIG) web.loadUrl(baseUrl() + WIRELESS_PATH)
                    }, 1500)
                }
            }
        }
    }

    private fun verifyDeviceRuleConfig() {
        evalAdapter("({ok:true,blocked:RouterAdapter.isBlocked(${JSONObject.quote(targetMac)})})") { json ->
            val o = safeObject(json)
            val actual = o.optBoolean("blocked", false)
            if (actual != targetBlocked) {
                fail("SAVE انجام شد اما وضعیت Block از خود روتر تأیید نشد.")
                purpose = Purpose.NONE
            } else {
                navigate(DEVICE_PATH, Purpose.VERIFY_BLOCK_ONLINE)
            }
        }
    }

    private fun verifyDevicePresence() {
        evalAdapter("RouterAdapter.scanClients(${JSONObject.quote(ROUTER_MAC)})") { json ->
            val o = safeObject(json)
            val arr = o.optJSONArray("clients") ?: JSONArray()
            val rows = mutableListOf<Pair<String, String>>()
            val online = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val mac = c.optString("mac").uppercase(Locale.US)
                if (mac.isNotBlank() && mac != ROUTER_MAC) {
                    online.add(mac)
                    rows.add(mac to c.optString("row"))
                }
            }
            renderDevices(rows.distinctBy { it.first })
            purpose = Purpose.NONE
            expectedPath = ""

            if (targetBlocked) {
                if (targetMac !in online) {
                    success("قطع واقعی تأیید شد: $targetMac دیگر در Wireless Clients نیست.")
                } else {
                    status.text = "قانون Block روی روتر Verify شد، اما دستگاه هنوز در Wireless Clients دیده می‌شود؛ اپ این حالت را «قطع کامل» ثبت نمی‌کند."
                }
            } else {
                success("قانون Block برداشته شد و روتر آن را Verify کرد؛ دستگاه اجازه اتصال دارد.")
            }
        }
    }

    private fun activateAllowList() {
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (protected.isBlank()) {
            fail("اول دستگاه مدیر را مشخص کن؛ Allow‑List بدون مدیر اجرا نمی‌شود.")
            return
        }

        val selected = allowChecks.filterValues { it.isChecked }.keys.toMutableSet()
        selected.add(protected)
        if (selected.size > wirelessCapacity && wirelessCapacity > 0) {
            fail("ظرفیت واقعی MAC Filter فقط $wirelessCapacity دستگاه است.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("ضد QR / Allow‑List")
            .setMessage("پس از SAVE فقط ${selected.size} MAC انتخاب‌شده اجازه Association خواهند داشت. دستگاه مدیر داخل فهرست است. ادامه؟")
            .setPositiveButton("اجرا") { _, _ ->
                targetAllowed = selected.toList()
                navigate(WIRELESS_PATH, Purpose.PREPARE_ALLOWLIST)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun prepareAllowList() {
        val jsArray = targetAllowed.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }
        evalAdapter("RouterAdapter.prepareAllowList($jsArray)") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("Allow‑List آماده نشد: ${o.optString("error", "UNKNOWN")}")
                purpose = Purpose.NONE
                return@evalAdapter
            }

            purpose = Purpose.VERIFY_ALLOWLIST
            expectedPath = WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { saveJson ->
                if (!safeObject(saveJson).optBoolean("ok")) {
                    fail("SAVE واقعی Allow‑List اجرا نشد.")
                    purpose = Purpose.NONE
                } else {
                    handler.postDelayed({
                        if (purpose == Purpose.VERIFY_ALLOWLIST) web.loadUrl(baseUrl() + WIRELESS_PATH)
                    }, 1500)
                }
            }
        }
    }

    private fun verifyAllowList() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o = safeObject(json)
            val enabled = o.optBoolean("enabled")
            val mode = o.optString("mode").lowercase(Locale.US)
            val arr = o.optJSONArray("macs") ?: JSONArray()
            val actual = mutableSetOf<String>()
            for (i in 0 until arr.length()) actual.add(arr.optString(i).uppercase(Locale.US))
            val wanted = targetAllowed.map { it.uppercase(Locale.US) }.toSet()
            val ok = enabled && mode.contains("allow association") && actual.containsAll(wanted)

            purpose = Purpose.NONE
            expectedPath = ""
            if (ok) {
                prefs.edit().putStringSet("last_allow_list", wanted).apply()
                success("ضد QR واقعی فعال شد و Allow‑List از خود روتر دوباره خوانده و Verify شد.")
            } else {
                fail("Allow‑List بعد از SAVE تأیید نشد؛ اپ آن را موفق ثبت نکرد.")
            }
        }
    }

    private fun confirmFilterOff() {
        AlertDialog.Builder(this)
            .setTitle("بازگردانی اضطراری")
            .setMessage("Wireless MAC Filter خاموش شود؟ WAN/ADSL و رمز Wi‑Fi تغییر نمی‌کنند.")
            .setPositiveButton("خاموش کن") { _, _ -> navigate(WIRELESS_PATH, Purpose.PREPARE_FILTER_OFF) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun prepareFilterOff() {
        evalAdapter("RouterAdapter.prepareFilterOff()") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("خاموش‌کردن Filter آماده نشد: ${o.optString("error", "UNKNOWN")}")
                purpose = Purpose.NONE
                return@evalAdapter
            }
            purpose = Purpose.VERIFY_FILTER_OFF
            expectedPath = WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { saveJson ->
                if (!safeObject(saveJson).optBoolean("ok")) {
                    fail("SAVE واقعی اجرا نشد.")
                    purpose = Purpose.NONE
                } else {
                    handler.postDelayed({
                        if (purpose == Purpose.VERIFY_FILTER_OFF) web.loadUrl(baseUrl() + WIRELESS_PATH)
                    }, 1500)
                }
            }
        }
    }

    private fun verifyFilterOff() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o = safeObject(json)
            purpose = Purpose.NONE
            expectedPath = ""
            if (o.optBoolean("ok") && !o.optBoolean("enabled")) {
                success("MAC Filter واقعاً خاموش شد و از خود روتر Verify شد.")
            } else {
                fail("خاموش‌شدن MAC Filter تأیید نشد.")
            }
        }
    }

    private fun readStats() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("Statistics از روتر خوانده نشد.")
                purpose = Purpose.NONE
                return@evalAdapter
            }

            if (o.isNull("rxBytes") || o.isNull("txBytes")) {
                usageText.text = "این firmware Byte Counter قابل‌تشخیص نشان نداد؛ مصرف جعلی نمایش داده نمی‌شود."
                purpose = Purpose.NONE
                expectedPath = ""
                return@evalAdapter
            }

            val rx = o.optLong("rxBytes")
            val tx = o.optLong("txBytes")
            val lastRx = prefs.getLong("last_rx", -1)
            val lastTx = prefs.getLong("last_tx", -1)
            var carried = prefs.getLong("carried_bytes", 0)
            if (lastRx >= 0) carried += if (rx >= lastRx) rx - lastRx else rx
            if (lastTx >= 0) carried += if (tx >= lastTx) tx - lastTx else tx

            val pkgText = packageGb.text.toString().trim()
            prefs.edit()
                .putLong("last_rx", rx)
                .putLong("last_tx", tx)
                .putLong("carried_bytes", carried)
                .putString("package_gb", pkgText)
                .apply()

            val usedGb = carried.toDouble() / (1024.0 * 1024 * 1024)
            val pkg = pkgText.toDoubleOrNull()
            val remaining = pkg?.let { (it - usedGb).coerceAtLeast(0.0) }
            usageText.text = buildString {
                append("مصرف ثبت‌شده از شمارنده روتر: %.3f GB".format(usedGb))
                if (remaining != null) append("\nباقی‌مانده تخمینی از بسته: %.3f GB".format(remaining))
                else append("\nحجم کل بسته را وارد کن تا باقی‌مانده تخمینی محاسبه شود.")
            }
            purpose = Purpose.NONE
            expectedPath = ""
            success("Statistics واقعی خوانده شد.")
        }
    }

    private fun changeGuest(on: Boolean) {
        if (!guestReady) return
        guestDesiredOn = on
        navigate(GUEST_PATH, if (on) Purpose.PREPARE_GUEST_ON else Purpose.PREPARE_GUEST_OFF)
    }

    private fun prepareGuestEnabled(on: Boolean) {
        evalAdapter("RouterAdapter.setGuestEnabled(${if (on) "true" else "false"})") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("کنترل Guest آماده نشد: ${o.optString("error", "UNKNOWN")}")
                purpose = Purpose.NONE
                return@evalAdapter
            }
            purpose = Purpose.VERIFY_GUEST_CHANGE
            expectedPath = GUEST_PATH
            evalAdapter("RouterAdapter.saveGuest()") { saveJson ->
                if (!safeObject(saveJson).optBoolean("ok")) {
                    fail("SAVE واقعی Guest اجرا نشد.")
                    purpose = Purpose.NONE
                } else {
                    handler.postDelayed({
                        if (purpose == Purpose.VERIFY_GUEST_CHANGE) web.loadUrl(baseUrl() + GUEST_PATH)
                    }, 1500)
                }
            }
        }
    }

    private fun changeGuestBandwidth() {
        if (!guestReady || !guestBandwidthReady) return
        val up = guestUp.text.toString().toDoubleOrNull()
        val down = guestDown.text.toString().toDoubleOrNull()
        if (up == null && down == null) {
            fail("حداقل Upstream یا Downstream را وارد کن.")
            return
        }
        guestDesiredUp = up
        guestDesiredDown = down
        navigate(GUEST_PATH, Purpose.PREPARE_GUEST_BW)
    }

    private fun prepareGuestBandwidth() {
        val up = guestDesiredUp?.toString() ?: "null"
        val down = guestDesiredDown?.toString() ?: "null"
        evalAdapter("RouterAdapter.setGuestBandwidth($up,$down)") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                fail("Bandwidth Guest آماده نشد: ${o.optString("error", "UNKNOWN")}")
                purpose = Purpose.NONE
                return@evalAdapter
            }
            purpose = Purpose.VERIFY_GUEST_CHANGE
            expectedPath = GUEST_PATH
            evalAdapter("RouterAdapter.saveGuest()") { saveJson ->
                if (!safeObject(saveJson).optBoolean("ok")) {
                    fail("SAVE واقعی Bandwidth اجرا نشد.")
                    purpose = Purpose.NONE
                } else {
                    handler.postDelayed({
                        if (purpose == Purpose.VERIFY_GUEST_CHANGE) web.loadUrl(baseUrl() + GUEST_PATH)
                    }, 1500)
                }
            }
        }
    }

    private fun verifyGuestChange() {
        evalAdapter("RouterAdapter.scanGuest()") { json ->
            val o = safeObject(json)
            purpose = Purpose.NONE
            expectedPath = ""
            if (o.optBoolean("ok")) {
                status.text = "فرم Guest بعد از SAVE دوباره از خود روتر خوانده شد. قابلیت فقط در صورتی فعال می‌ماند که firmware آن را نشان دهد."
            } else {
                fail("فرم Guest بعد از SAVE دوباره خوانده نشد.")
            }
        }
    }

    private fun updateUi() {
        refreshBtn.isEnabled = connected && clientsReady
        allowListBtn.isEnabled = connected && wirelessReady
        filterOffBtn.isEnabled = connected && wirelessReady
        statsBtn.isEnabled = connected && statsReady
        guestOnBtn.isEnabled = connected && guestReady
        guestOffBtn.isEnabled = connected && guestReady
        guestBwBtn.isEnabled = connected && guestReady && guestBandwidthReady

        capabilities.text = buildString {
            append("دستگاه‌ها: ").append(if (clientsReady) "✓" else "✗")
            append("  •  MAC Filter: ").append(if (wirelessReady) "✓" else "✗")
            if (wirelessReady && wirelessCapacity > 0) append(" ($wirelessCapacity خانه)")
            append("\nStatistics: ").append(if (statsReady) "✓" else "✗")
            append("  •  Guest: ").append(if (guestReady) "✓" else "✗")
            append("  •  Guest Bandwidth: ").append(if (guestBandwidthReady) "✓" else "✗")
        }
    }

    private fun success(message: String) {
        status.text = message
        updateUi()
    }

    private fun fail(message: String) {
        status.text = message
        updateUi()
    }

    private fun safeObject(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("error", "INVALID_RESPONSE")
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
