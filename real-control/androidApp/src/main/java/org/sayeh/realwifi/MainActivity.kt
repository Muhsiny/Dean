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
        NONE, CONNECT_ROOT, VERIFY_CLIENTS,
        DISCOVER_BASIC, VERIFY_WIRELESS, VERIFY_GUEST,
        DISCOVER_ACCESS, VERIFY_ACCESS,
        DISCOVER_ADVANCED, VERIFY_QOS, VERIFY_STATS,
        REFRESH_CLIENTS,
        PREP_WIFI_BLOCK, PREP_WIFI_UNBLOCK, VERIFY_WIFI_RULE, VERIFY_WIFI_PRESENCE,
        PREP_INTERNET_BLOCK, PREP_INTERNET_UNBLOCK, VERIFY_INTERNET_RULE,
        PREP_ALLOWLIST, VERIFY_ALLOWLIST,
        PREP_FILTER_OFF, VERIFY_FILTER_OFF,
        PREP_WPS_OFF, VERIFY_WPS_OFF,
        PREP_QOS, PREP_QOS_OFF, VERIFY_QOS_RULE,
        READ_STATS,
        PREP_GUEST_ON, PREP_GUEST_OFF, PREP_GUEST_BW,
        PREP_GUEST_ISO_ON, PREP_GUEST_ISO_OFF,
        PREP_GUEST_LOCAL_ON, PREP_GUEST_LOCAL_OFF,
        PREP_GUEST_CREDENTIALS, VERIFY_GUEST_CHANGE
    }

    companion object {
        private const val DEVICE_PATH = "/status/status_deviceinfo.htm"
        private const val STATS_PATH = "/status/status_statistics.htm"
        private const val BASIC_NAV = "/navigation-basic.html"
        private const val ACCESS_NAV = "/navigation-access.html"
        private const val ADV_NAV = "/navigation-advanced.html"
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
    private lateinit var wpsOffBtn: Button
    private lateinit var statsBtn: Button
    private lateinit var packageGb: EditText
    private lateinit var usageText: TextView
    private lateinit var guestOnBtn: Button
    private lateinit var guestOffBtn: Button
    private lateinit var guestUp: EditText
    private lateinit var guestDown: EditText
    private lateinit var guestBwBtn: Button
    private lateinit var guestIsolationOnBtn: Button
    private lateinit var guestIsolationOffBtn: Button
    private lateinit var guestLocalOffBtn: Button
    private lateinit var guestLocalOnBtn: Button
    private lateinit var guestSsid: EditText
    private lateinit var guestPassword: EditText
    private lateinit var guestCredentialsBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("real_wifi_control_v2", MODE_PRIVATE) }
    private val allowChecks = linkedMapOf<String, CheckBox>()

    private lateinit var adapterJs: String
    private var purpose = Purpose.NONE
    private var expectedPath = ""
    private var loginAttempts = 0

    private var wirelessPath = "/basic/home_wlan.htm"
    private var guestPath = "/basic/home_guest_network.htm"
    private var accessPath: String? = null
    private var qosPath: String? = null

    private var connected = false
    private var clientsReady = false
    private var wirelessReady = false
    private var wpsReady = false
    private var accessReady = false
    private var qosReady = false
    private var statsReady = false
    private var guestReady = false
    private var guestBandwidthReady = false
    private var guestIsolationReady = false
    private var guestLocalReady = false
    private var guestCredentialsReady = false
    private var wirelessCapacity = 0

    private var targetMac = ""
    private var desiredBlock = false
    private var targetAllowed: List<String> = emptyList()
    private var qosLevel = "normal"
    private var guestVerifyKind = ""
    private var guestExpectedText: String? = null
    private var guestExpectedUp: String? = null
    private var guestExpectedDown: String? = null

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
        wpsOffBtn = findViewById(R.id.wpsOffBtn)
        statsBtn = findViewById(R.id.statsBtn)
        packageGb = findViewById(R.id.packageGb)
        usageText = findViewById(R.id.usageText)
        guestOnBtn = findViewById(R.id.guestOnBtn)
        guestOffBtn = findViewById(R.id.guestOffBtn)
        guestUp = findViewById(R.id.guestUp)
        guestDown = findViewById(R.id.guestDown)
        guestBwBtn = findViewById(R.id.guestBwBtn)
        guestIsolationOnBtn = findViewById(R.id.guestIsolationOnBtn)
        guestIsolationOffBtn = findViewById(R.id.guestIsolationOffBtn)
        guestLocalOffBtn = findViewById(R.id.guestLocalOffBtn)
        guestLocalOnBtn = findViewById(R.id.guestLocalOnBtn)
        guestSsid = findViewById(R.id.guestSsid)
        guestPassword = findViewById(R.id.guestPassword)
        guestCredentialsBtn = findViewById(R.id.guestCredentialsBtn)

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
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.postDelayed({ handlePage(url.orEmpty()) }, 300)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && purpose != Purpose.NONE) {
                    fail("روتر پاسخ نداد: ${error?.description ?: "خطای شبکه"}")
                }
            }
        }

        connectBtn.setOnClickListener { startConnection() }
        refreshBtn.setOnClickListener { if (connected) navigate(DEVICE_PATH, Purpose.REFRESH_CLIENTS) }
        allowListBtn.setOnClickListener { activateAllowList() }
        filterOffBtn.setOnClickListener { confirmFilterOff() }
        wpsOffBtn.setOnClickListener { if (wpsReady) navigate(wirelessPath, Purpose.PREP_WPS_OFF) }
        statsBtn.setOnClickListener { if (statsReady) navigate(STATS_PATH, Purpose.READ_STATS) }
        guestOnBtn.setOnClickListener { guestEnabled(true) }
        guestOffBtn.setOnClickListener { guestEnabled(false) }
        guestBwBtn.setOnClickListener { guestBandwidth() }
        guestIsolationOnBtn.setOnClickListener { guestIsolation(true) }
        guestIsolationOffBtn.setOnClickListener { guestIsolation(false) }
        guestLocalOffBtn.setOnClickListener { guestLocal(false) }
        guestLocalOnBtn.setOnClickListener { guestLocal(true) }
        guestCredentialsBtn.setOnClickListener { guestCredentials() }
        updateUi()
    }

    private fun baseUrl(): String = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }.trimEnd('/')

    private fun resetCapabilities() {
        connected = false
        clientsReady = false
        wirelessReady = false
        wpsReady = false
        accessReady = false
        qosReady = false
        statsReady = false
        guestReady = false
        guestBandwidthReady = false
        guestIsolationReady = false
        guestLocalReady = false
        guestCredentialsReady = false
        wirelessCapacity = 0
        wirelessPath = "/basic/home_wlan.htm"
        guestPath = "/basic/home_guest_network.htm"
        accessPath = null
        qosPath = null
    }

    private fun startConnection() {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            fail("نام کاربری و رمز ادمین را وارد کن.")
            return
        }
        prefs.edit().putString("router_url", baseUrl()).putString("router_user", username.text.toString().trim()).apply()
        resetCapabilities()
        loginAttempts = 0
        deviceList.removeAllViews()
        allowChecks.clear()
        purpose = Purpose.CONNECT_ROOT
        expectedPath = ""
        status.text = "در حال ورود واقعی، کشف مسیرهای firmware و Verify قابلیت‌ها…"
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
                Purpose.VERIFY_CLIENTS -> readClients { navigate(BASIC_NAV, Purpose.DISCOVER_BASIC) }
                Purpose.DISCOVER_BASIC -> discoverBasicRoutes()
                Purpose.VERIFY_WIRELESS -> verifyWireless()
                Purpose.VERIFY_GUEST -> verifyGuest()
                Purpose.DISCOVER_ACCESS -> discoverAccessRoute()
                Purpose.VERIFY_ACCESS -> verifyAccess()
                Purpose.DISCOVER_ADVANCED -> discoverQosRoute()
                Purpose.VERIFY_QOS -> verifyQos()
                Purpose.VERIFY_STATS -> verifyStatsAndFinish()
                Purpose.REFRESH_CLIENTS -> readClients { finish("فهرست دستگاه‌ها از خود روتر تازه شد.") }
                Purpose.PREP_WIFI_BLOCK -> prepareWifiRule(true)
                Purpose.PREP_WIFI_UNBLOCK -> prepareWifiRule(false)
                Purpose.VERIFY_WIFI_RULE -> verifyWifiRule()
                Purpose.VERIFY_WIFI_PRESENCE -> verifyWifiPresence()
                Purpose.PREP_INTERNET_BLOCK -> prepareInternetRule(true)
                Purpose.PREP_INTERNET_UNBLOCK -> prepareInternetRule(false)
                Purpose.VERIFY_INTERNET_RULE -> verifyInternetRule()
                Purpose.PREP_ALLOWLIST -> prepareAllowList()
                Purpose.VERIFY_ALLOWLIST -> verifyAllowList()
                Purpose.PREP_FILTER_OFF -> prepareFilterOff()
                Purpose.VERIFY_FILTER_OFF -> verifyFilterOff()
                Purpose.PREP_WPS_OFF -> prepareWpsOff()
                Purpose.VERIFY_WPS_OFF -> verifyWpsOff()
                Purpose.PREP_QOS -> prepareQos(false)
                Purpose.PREP_QOS_OFF -> prepareQos(true)
                Purpose.VERIFY_QOS_RULE -> verifyQosRule()
                Purpose.READ_STATS -> readStats()
                Purpose.PREP_GUEST_ON -> prepareGuestEnable(true)
                Purpose.PREP_GUEST_OFF -> prepareGuestEnable(false)
                Purpose.PREP_GUEST_BW -> prepareGuestBandwidth()
                Purpose.PREP_GUEST_ISO_ON -> prepareGuestIsolation(true)
                Purpose.PREP_GUEST_ISO_OFF -> prepareGuestIsolation(false)
                Purpose.PREP_GUEST_LOCAL_ON -> prepareGuestLocal(true)
                Purpose.PREP_GUEST_LOCAL_OFF -> prepareGuestLocal(false)
                Purpose.PREP_GUEST_CREDENTIALS -> prepareGuestCredentials()
                Purpose.VERIFY_GUEST_CHANGE -> verifyGuestChange()
                Purpose.NONE -> Unit
            }
        }
    }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        web.evaluateJavascript("(function(){try{return !!document.querySelector('input[type=password]')||location.href.toLowerCase().indexOf('login_security')>=0;}catch(e){return false;}})();") {
            callback(it == "true")
        }
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
              ['input','change'].forEach(function(n){u.dispatchEvent(new Event(n,{bubbles:true}));p.dispatchEvent(new Event(n,{bubbles:true}));});
              var f=p.form||u.form||document.forms[0];if(!f)return 'NO_LOGIN_FORM';
              var bs=f.querySelectorAll('input[type=submit],input[type=button],button');
              for(var i=0;i<bs.length;i++){var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(t.indexOf('login')>=0){bs[i].click();return 'CLICKED';}}
              if(bs.length){bs[0].click();return 'CLICKED';}
              f.submit();return 'SUBMITTED';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) {
                purpose = Purpose.NONE
                fail("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
            }
        }
    }

    private fun navigate(path: String, next: Purpose) {
        purpose = next
        expectedPath = path
        status.text = when (next) {
            Purpose.DISCOVER_BASIC, Purpose.DISCOVER_ACCESS, Purpose.DISCOVER_ADVANCED -> "در حال کشف مسیر واقعی firmware…"
            Purpose.VERIFY_WIRELESS, Purpose.VERIFY_ACCESS, Purpose.VERIFY_QOS, Purpose.VERIFY_GUEST, Purpose.VERIFY_STATS -> "در حال Verify قابلیت واقعی…"
            else -> status.text
        }
        web.loadUrl(baseUrl() + path)
    }

    private fun urlPathMatches(url: String, path: String): Boolean = try { URI(url).path.equals(path, ignoreCase = true) } catch (_: Exception) { url.contains(path, true) }

    private fun evalAdapter(expression: String, callback: (String) -> Unit) {
        val js = adapterJs + "\n;try{JSON.stringify($expression)}catch(e){JSON.stringify({ok:false,error:String(e)})}"
        web.evaluateJavascript(js) { callback(decodeJs(it)) }
    }

    private fun readClients(after: () -> Unit) {
        evalAdapter("RouterAdapter.scanClients(${JSONObject.quote(ROUTER_MAC)})") { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) {
                purpose = Purpose.NONE
                fail("جدول دستگاه‌های متصل از firmware خوانده نشد.")
                return@evalAdapter
            }
            val rows = mutableListOf<Pair<String, String>>()
            val a = o.optJSONArray("clients") ?: JSONArray()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val mac = c.optString("mac").uppercase(Locale.US)
                if (validMac(mac) && mac != ROUTER_MAC) rows.add(mac to c.optString("row"))
            }
            clientsReady = true
            renderDevices(rows.distinctBy { it.first })
            after()
        }
    }

    private fun discoverBasicRoutes() {
        evalAdapter("RouterAdapter.discoverNavigation()") { json ->
            val routes = safeObject(json).optJSONObject("routes") ?: JSONObject()
            routeContaining(routes, listOf("wireless"))?.let { wirelessPath = it }
            routeContaining(routes, listOf("guest"))?.let { guestPath = it }
            navigate(wirelessPath, Purpose.VERIFY_WIRELESS)
        }
    }

    private fun verifyWireless() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o = safeObject(json)
            wirelessReady = o.optBoolean("ok")
            wirelessCapacity = o.optInt("capacity", 0)
            val wps = o.optJSONObject("wps")
            wpsReady = wps?.optBoolean("supported") == true
            navigate(guestPath, Purpose.VERIFY_GUEST)
        }
    }

    private fun verifyGuest() {
        evalAdapter("RouterAdapter.guestState()") { json ->
            val o = safeObject(json)
            guestReady = o.optBoolean("ok")
            val c = o.optJSONObject("capabilities")
            guestBandwidthReady = c?.optBoolean("bandwidth") == true
            guestIsolationReady = c?.optBoolean("isolation") == true
            guestLocalReady = c?.optBoolean("localAccess") == true
            guestCredentialsReady = c?.optBoolean("ssid") == true
            if (guestReady) {
                if (guestSsid.text.isBlank()) guestSsid.setText(o.optString("ssid"))
                if (guestUp.text.isBlank() && !o.isNull("upstream")) guestUp.setText(o.optString("upstream"))
                if (guestDown.text.isBlank() && !o.isNull("downstream")) guestDown.setText(o.optString("downstream"))
            }
            navigate(ACCESS_NAV, Purpose.DISCOVER_ACCESS)
        }
    }

    private fun discoverAccessRoute() {
        evalAdapter("RouterAdapter.discoverNavigation()") { json ->
            val routes = safeObject(json).optJSONObject("routes") ?: JSONObject()
            accessPath = routeContaining(routes, listOf("filter"))
            if (accessPath != null) navigate(accessPath!!, Purpose.VERIFY_ACCESS)
            else navigate(ADV_NAV, Purpose.DISCOVER_ADVANCED)
        }
    }

    private fun verifyAccess() {
        evalAdapter("RouterAdapter.accessState()") { json ->
            accessReady = safeObject(json).optBoolean("ok")
            navigate(ADV_NAV, Purpose.DISCOVER_ADVANCED)
        }
    }

    private fun discoverQosRoute() {
        evalAdapter("RouterAdapter.discoverNavigation()") { json ->
            val routes = safeObject(json).optJSONObject("routes") ?: JSONObject()
            qosPath = routeContaining(routes, listOf("qos"))
            if (qosPath != null) navigate(qosPath!!, Purpose.VERIFY_QOS)
            else navigate(STATS_PATH, Purpose.VERIFY_STATS)
        }
    }

    private fun verifyQos() {
        evalAdapter("RouterAdapter.qosState()") { json ->
            qosReady = safeObject(json).optBoolean("ok")
            navigate(STATS_PATH, Purpose.VERIFY_STATS)
        }
    }

    private fun verifyStatsAndFinish() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            statsReady = safeObject(json).optBoolean("ok")
            connected = clientsReady
            purpose = Purpose.NONE
            expectedPath = ""
            updateUi()
            success(if (connected) "کشف firmware پایان یافت. فقط قابلیت‌هایی که واقعاً پیدا شدند فعال شده‌اند." else "اتصال کامل Verify نشد.")
        }
    }

    private fun routeContaining(routes: JSONObject, terms: List<String>): String? {
        val keys = routes.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val low = k.lowercase(Locale.US)
            if (terms.all { low.contains(it.lowercase(Locale.US)) }) {
                val p = routes.optString(k)
                if (p.startsWith("/")) return p
            }
        }
        return null
    }

    private fun renderDevices(rows: List<Pair<String, String>>) {
        deviceList.removeAllViews()
        allowChecks.clear()
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        val lastAllowed = prefs.getStringSet("last_allow_list", emptySet()) ?: emptySet()
        if (rows.isEmpty()) {
            deviceList.addView(TextView(this).apply { text = "هیچ کلاینت Wireless در جدول روتر دیده نشد."; setPadding(8,8,8,8) })
            return
        }

        rows.forEach { (mac, row) ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8,12,8,16) }
            val ip = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(row)?.value.orEmpty()
            val aliasKey = "alias_$mac"
            val alias = prefs.getString(aliasKey, "").orEmpty()
            val privateMac = isLocallyAdministeredMac(mac)

            box.addView(TextView(this).apply {
                text = buildString {
                    if (alias.isNotBlank()) append(alias).append("\n")
                    append(mac)
                    if (ip.isNotBlank()) append("  •  ").append(ip)
                    if (mac == protected) append("  •  مدیر")
                    if (privateMac) append("\n⚠ MAC خصوصی/تصادفی؛ اگر تلفن MAC را عوض کند باید دوباره مجاز شود.")
                }
                textSize = 16f
            })

            val allow = CheckBox(this).apply { text = "مجاز در ضد QR"; isChecked = mac == protected || mac in lastAllowed }
            allowChecks[mac] = allow
            box.addView(allow)

            val aliasInput = EditText(this).apply { hint = "نام دستگاه"; setText(alias) }
            box.addView(aliasInput)

            val identity = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            identity.addView(Button(this).apply {
                text = if (mac == protected) "مدیر ✓" else "مدیر"
                setOnClickListener {
                    prefs.edit().putString("protected_mac", mac).apply()
                    success("$mac به‌عنوان دستگاه مدیر محافظت شد.")
                    navigate(DEVICE_PATH, Purpose.REFRESH_CLIENTS)
                }
            }, weight())
            identity.addView(Button(this).apply {
                text = "ثبت نام"
                setOnClickListener { prefs.edit().putString(aliasKey, aliasInput.text.toString().trim()).apply(); success("نام دستگاه ثبت شد.") }
            }, weight())
            box.addView(identity)

            val internet = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            internet.addView(Button(this).apply {
                text = "قطع اینترنت"
                isEnabled = accessReady && mac != protected
                setOnClickListener { targetMac = mac; desiredBlock = true; navigate(accessPath!!, Purpose.PREP_INTERNET_BLOCK) }
            }, weight())
            internet.addView(Button(this).apply {
                text = "وصل اینترنت"
                isEnabled = accessReady
                setOnClickListener { targetMac = mac; desiredBlock = false; navigate(accessPath!!, Purpose.PREP_INTERNET_UNBLOCK) }
            }, weight())
            box.addView(internet)

            val wifi = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            wifi.addView(Button(this).apply {
                text = "قطع Wi‑Fi"
                isEnabled = wirelessReady && mac != protected
                setOnClickListener { targetMac = mac; desiredBlock = true; navigate(wirelessPath, Purpose.PREP_WIFI_BLOCK) }
            }, weight())
            wifi.addView(Button(this).apply {
                text = "وصل Wi‑Fi"
                isEnabled = wirelessReady
                setOnClickListener { targetMac = mac; desiredBlock = false; navigate(wirelessPath, Purpose.PREP_WIFI_UNBLOCK) }
            }, weight())
            box.addView(wifi)

            if (qosReady) {
                val qosRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("high" to "QoS زیاد", "normal" to "متوسط", "low" to "کم").forEach { (level, label) ->
                    qosRow.addView(Button(this).apply {
                        text = label
                        setOnClickListener { targetMac = mac; qosLevel = level; navigate(qosPath!!, Purpose.PREP_QOS) }
                    }, weight())
                }
                qosRow.addView(Button(this).apply {
                    text = "QoS خاموش"
                    setOnClickListener { targetMac = mac; navigate(qosPath!!, Purpose.PREP_QOS_OFF) }
                }, weight())
                box.addView(qosRow)
            }
            deviceList.addView(box)
        }
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun prepareInternetRule(block: Boolean) {
        val expr = if (block) "RouterAdapter.prepareInternetBlock(${JSONObject.quote(targetMac)})" else "RouterAdapter.prepareInternetUnblock(${JSONObject.quote(targetMac)})"
        evalAdapter(expr) { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) { purpose = Purpose.NONE; fail("MAC Filter اینترنت آماده نشد: ${o.optString("error")}"); return@evalAdapter }
            if (!o.optBoolean("needsSave")) { purpose = Purpose.VERIFY_INTERNET_RULE; verifyInternetRule(); return@evalAdapter }
            purpose = Purpose.VERIFY_INTERNET_RULE; expectedPath = accessPath!!
            evalAdapter("RouterAdapter.saveAccess()") { s ->
                if (!safeObject(s).optBoolean("ok")) { purpose = Purpose.NONE; fail("SAVE فیلتر اینترنت اجرا نشد.") }
                else handler.postDelayed({ if (purpose == Purpose.VERIFY_INTERNET_RULE) web.loadUrl(baseUrl() + accessPath!!) }, 1600)
            }
        }
    }

    private fun verifyInternetRule() {
        evalAdapter("({ok:true,blocked:RouterAdapter.isInternetBlocked(${JSONObject.quote(targetMac)}),state:RouterAdapter.accessState()})") { json ->
            val o = safeObject(json)
            val actual = o.optBoolean("blocked")
            purpose = Purpose.NONE; expectedPath = ""
            if (actual == desiredBlock) {
                success(if (desiredBlock) "قطع اینترنت در Access Management ثبت و از خود روتر دوباره Verify شد." else "فیلتر اینترنت این دستگاه برداشته و Verify شد.")
            } else {
                fail("SAVE انجام شد اما وضعیت فیلتر اینترنت از خود روتر به‌طور قطعی Verify نشد؛ موفق ثبت نشد.")
            }
        }
    }

    private fun prepareWifiRule(block: Boolean) {
        val expr = if (block) "RouterAdapter.prepareBlock(${JSONObject.quote(targetMac)})" else "RouterAdapter.prepareUnblock(${JSONObject.quote(targetMac)})"
        evalAdapter(expr) { json ->
            val o = safeObject(json)
            if (!o.optBoolean("ok")) { purpose = Purpose.NONE; fail("Wireless MAC Filter آماده نشد: ${o.optString("error")}"); return@evalAdapter }
            if (!o.optBoolean("needsSave")) { purpose = Purpose.VERIFY_WIFI_RULE; verifyWifiRule(); return@evalAdapter }
            purpose = Purpose.VERIFY_WIFI_RULE; expectedPath = wirelessPath
            evalAdapter("RouterAdapter.saveWireless()") { s ->
                if (!safeObject(s).optBoolean("ok")) { purpose = Purpose.NONE; fail("SAVE Wireless اجرا نشد.") }
                else handler.postDelayed({ if (purpose == Purpose.VERIFY_WIFI_RULE) web.loadUrl(baseUrl() + wirelessPath) }, 1600)
            }
        }
    }

    private fun verifyWifiRule() {
        evalAdapter("({ok:true,blocked:RouterAdapter.isBlocked(${JSONObject.quote(targetMac)})})") { json ->
            val actual = safeObject(json).optBoolean("blocked")
            if (actual != desiredBlock) { purpose = Purpose.NONE; fail("وضعیت Wireless MAC Filter پس از SAVE Verify نشد."); return@evalAdapter }
            navigate(DEVICE_PATH, Purpose.VERIFY_WIFI_PRESENCE)
        }
    }

    private fun verifyWifiPresence() {
        evalAdapter("RouterAdapter.scanClients(${JSONObject.quote(ROUTER_MAC)})") { json ->
            val a = safeObject(json).optJSONArray("clients") ?: JSONArray()
            var online = false
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("mac")?.equals(targetMac, true) == true) online = true
            purpose = Purpose.NONE; expectedPath = ""
            if (desiredBlock && online) status.text = "قانون قطع Wi‑Fi روی روتر Verify شد، اما دستگاه هنوز در جدول Wireless Clients است؛ firmware deauth فوری را تأیید نکرد و اپ آن را قطع کامل نمی‌نامد."
            else success(if (desiredBlock) "قطع Wi‑Fi هم در قانون و هم در جدول Wireless Clients تأیید شد." else "قانون قطع Wi‑Fi برداشته شد؛ دستگاه اجازه Association دارد.")
        }
    }

    private fun activateAllowList() {
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (protected.isBlank()) { fail("اول دستگاه مدیر را مشخص کن؛ Allow‑List بدون مدیر اجرا نمی‌شود."); return }
        val selected = allowChecks.filterValues { it.isChecked }.keys.toMutableSet(); selected.add(protected)
        if (wirelessCapacity > 0 && selected.size > wirelessCapacity) { fail("ظرفیت واقعی MAC Filter فقط $wirelessCapacity دستگاه است."); return }
        AlertDialog.Builder(this).setTitle("ضد QR واقعی").setMessage("فقط ${selected.size} MAC انتخاب‌شده اجازه اتصال داشته باشند؟ دستگاه مدیر داخل فهرست است.")
            .setPositiveButton("اجرا") { _, _ -> targetAllowed = selected.toList(); navigate(wirelessPath, Purpose.PREP_ALLOWLIST) }.setNegativeButton("لغو", null).show()
    }

    private fun prepareAllowList() {
        val js = targetAllowed.joinToString(prefix="[", postfix="]") { JSONObject.quote(it) }
        evalAdapter("RouterAdapter.prepareAllowList($js)") { json ->
            val o = safeObject(json); if (!o.optBoolean("ok")) { purpose=Purpose.NONE; fail("Allow‑List آماده نشد: ${o.optString("error")}"); return@evalAdapter }
            purpose=Purpose.VERIFY_ALLOWLIST;expectedPath=wirelessPath
            evalAdapter("RouterAdapter.saveWireless()") { s -> if(!safeObject(s).optBoolean("ok")){purpose=Purpose.NONE;fail("SAVE ضد QR اجرا نشد.")} else handler.postDelayed({if(purpose==Purpose.VERIFY_ALLOWLIST)web.loadUrl(baseUrl()+wirelessPath)},1600) }
        }
    }

    private fun verifyAllowList() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o=safeObject(json);val actual=mutableSetOf<String>();val a=o.optJSONArray("macs")?:JSONArray();for(i in 0 until a.length())actual.add(a.optString(i).uppercase(Locale.US))
            val wanted=targetAllowed.map{it.uppercase(Locale.US)}.toSet();val ok=o.optBoolean("enabled")&&o.optString("mode").contains("allow association",true)&&actual.containsAll(wanted)
            purpose=Purpose.NONE;expectedPath=""
            if(ok){prefs.edit().putStringSet("last_allow_list",wanted).apply();success("ضد QR فعال شد: Allow Association و فهرست MACها از خود روتر Verify شد.")}else fail("Allow‑List بعد از SAVE تأیید نشد؛ موفق ثبت نشد.")
        }
    }

    private fun confirmFilterOff() {
        AlertDialog.Builder(this).setTitle("بازگردانی اضطراری").setMessage("Wireless MAC Filter خاموش شود؟ WAN/ADSL و رمز Wi‑Fi تغییر نمی‌کنند.")
            .setPositiveButton("خاموش کن") { _, _ -> navigate(wirelessPath, Purpose.PREP_FILTER_OFF) }.setNegativeButton("لغو", null).show()
    }

    private fun prepareFilterOff() {
        evalAdapter("RouterAdapter.prepareFilterOff()") { json ->
            if(!safeObject(json).optBoolean("ok")){purpose=Purpose.NONE;fail("خاموش‌کردن Filter آماده نشد.");return@evalAdapter}
            purpose=Purpose.VERIFY_FILTER_OFF;expectedPath=wirelessPath
            evalAdapter("RouterAdapter.saveWireless()") { s -> if(!safeObject(s).optBoolean("ok")){purpose=Purpose.NONE;fail("SAVE اجرا نشد.")} else handler.postDelayed({if(purpose==Purpose.VERIFY_FILTER_OFF)web.loadUrl(baseUrl()+wirelessPath)},1600) }
        }
    }

    private fun verifyFilterOff() {
        evalAdapter("RouterAdapter.wirelessState()") { json -> val o=safeObject(json);purpose=Purpose.NONE;expectedPath="";if(o.optBoolean("ok")&&!o.optBoolean("enabled"))success("Wireless MAC Filter واقعاً خاموش و Verify شد.")else fail("خاموش‌شدن Filter تأیید نشد.") }
    }

    private fun prepareWpsOff() {
        evalAdapter("RouterAdapter.prepareWps(false)") { json ->
            if(!safeObject(json).optBoolean("ok")){purpose=Purpose.NONE;fail("WPS در این صفحه قابل کنترل نبود.");return@evalAdapter}
            purpose=Purpose.VERIFY_WPS_OFF;expectedPath=wirelessPath
            evalAdapter("RouterAdapter.saveWireless()") { s -> if(!safeObject(s).optBoolean("ok")){purpose=Purpose.NONE;fail("SAVE WPS اجرا نشد.")} else handler.postDelayed({if(purpose==Purpose.VERIFY_WPS_OFF)web.loadUrl(baseUrl()+wirelessPath)},1600) }
        }
    }

    private fun verifyWpsOff() {
        evalAdapter("RouterAdapter.wpsState()") { json -> val o=safeObject(json);purpose=Purpose.NONE;expectedPath="";if(o.optBoolean("supported")&&!o.optBoolean("enabled"))success("WPS خاموش شد و از خود روتر Verify شد.")else fail("خاموش‌شدن WPS تأیید نشد.") }
    }

    private fun prepareQos(off: Boolean) {
        val expr=if(off)"RouterAdapter.prepareQosOff(${JSONObject.quote(targetMac)})" else "RouterAdapter.prepareQos(${JSONObject.quote(targetMac)},${JSONObject.quote(qosLevel)})"
        evalAdapter(expr) { json ->
            val o=safeObject(json);if(!o.optBoolean("ok")){purpose=Purpose.NONE;fail("QoS آماده نشد: ${o.optString("error")}");return@evalAdapter}
            if(!o.optBoolean("needsSave")){purpose=Purpose.NONE;success("برای این دستگاه QoS فعال نبود.");return@evalAdapter}
            purpose=Purpose.VERIFY_QOS_RULE;expectedPath=qosPath!!
            evalAdapter("RouterAdapter.saveQos()") { s -> if(!safeObject(s).optBoolean("ok")){purpose=Purpose.NONE;fail("SAVE QoS اجرا نشد.")} else handler.postDelayed({if(purpose==Purpose.VERIFY_QOS_RULE)web.loadUrl(baseUrl()+qosPath!!)},1600) }
        }
    }

    private fun verifyQosRule() {
        evalAdapter("RouterAdapter.qosState()") { json ->
            val o=safeObject(json);purpose=Purpose.NONE;expectedPath=""
            if(o.optBoolean("ok")&&json.uppercase(Locale.US).contains(targetMac.uppercase(Locale.US))) success("قاعده QoS برای این MAC در صفحه QoS دوباره دیده شد. این قابلیت اولویت است، نه سقف دقیق Mbps.")
            else fail("SAVE QoS انجام شد اما قاعدهٔ هدف از صفحه دوباره به‌طور قطعی Verify نشد.")
        }
    }

    private fun readStats() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            val o=safeObject(json);if(!o.optBoolean("ok")){purpose=Purpose.NONE;fail("Statistics خوانده نشد.");return@evalAdapter}
            if(o.isNull("rxBytes")||o.isNull("txBytes")){purpose=Purpose.NONE;usageText.text="این firmware Byte Counter قابل‌تشخیص نشان نداد؛ مصرف جعلی نمایش داده نمی‌شود.";return@evalAdapter}
            val rx=o.optLong("rxBytes");val tx=o.optLong("txBytes");val lr=prefs.getLong("last_rx",-1);val lt=prefs.getLong("last_tx",-1);var carried=prefs.getLong("carried_bytes",0)
            if(lr>=0)carried+=if(rx>=lr)rx-lr else rx;if(lt>=0)carried+=if(tx>=lt)tx-lt else tx
            val pkgText=packageGb.text.toString().trim();prefs.edit().putLong("last_rx",rx).putLong("last_tx",tx).putLong("carried_bytes",carried).putString("package_gb",pkgText).apply()
            val used=carried.toDouble()/(1024.0*1024*1024);val pkg=pkgText.toDoubleOrNull();usageText.text="مصرف ثبت‌شده از شمارنده روتر: %.3f GB%s".format(used,if(pkg!=null)"\nباقی‌مانده تخمینی: %.3f GB".format((pkg-used).coerceAtLeast(0.0)) else "")
            purpose=Purpose.NONE;expectedPath="";success("Statistics واقعی خوانده شد.")
        }
    }

    private fun guestEnabled(on:Boolean){if(!guestReady)return;guestVerifyKind="enabled";guestExpectedText=if(on)"on" else "off";navigate(guestPath,if(on)Purpose.PREP_GUEST_ON else Purpose.PREP_GUEST_OFF)}
    private fun prepareGuestEnable(on:Boolean){saveGuestPrepared("RouterAdapter.prepareGuestEnabled(${if(on)"true" else "false"})")}
    private fun guestBandwidth(){if(!guestBandwidthReady)return;val up=guestUp.text.toString().trim().ifBlank{null};val down=guestDown.text.toString().trim().ifBlank{null};if(up==null&&down==null){fail("حداقل Upstream یا Downstream را وارد کن.");return};guestVerifyKind="bandwidth";guestExpectedUp=up;guestExpectedDown=down;navigate(guestPath,Purpose.PREP_GUEST_BW)}
    private fun prepareGuestBandwidth(){saveGuestPrepared("RouterAdapter.prepareGuestBandwidth(${guestExpectedUp?:"null"},${guestExpectedDown?:"null"})")}
    private fun guestIsolation(on:Boolean){if(!guestIsolationReady)return;guestVerifyKind="isolation";guestExpectedText=if(on)"on" else "off";navigate(guestPath,if(on)Purpose.PREP_GUEST_ISO_ON else Purpose.PREP_GUEST_ISO_OFF)}
    private fun prepareGuestIsolation(on:Boolean){saveGuestPrepared("RouterAdapter.prepareGuestIsolation(${if(on)"true" else "false"})")}
    private fun guestLocal(on:Boolean){if(!guestLocalReady)return;guestVerifyKind="local";guestExpectedText=if(on)"on" else "off";navigate(guestPath,if(on)Purpose.PREP_GUEST_LOCAL_ON else Purpose.PREP_GUEST_LOCAL_OFF)}
    private fun prepareGuestLocal(on:Boolean){saveGuestPrepared("RouterAdapter.prepareGuestLocalAccess(${if(on)"true" else "false"})")}
    private fun guestCredentials(){if(!guestCredentialsReady)return;val ssid=guestSsid.text.toString().trim();if(ssid.isBlank()){fail("نام Guest Wi‑Fi را وارد کن.");return};guestVerifyKind="credentials";guestExpectedText=ssid;navigate(guestPath,Purpose.PREP_GUEST_CREDENTIALS)}
    private fun prepareGuestCredentials(){saveGuestPrepared("RouterAdapter.prepareGuestCredentials(${JSONObject.quote(guestSsid.text.toString().trim())},${JSONObject.quote(guestPassword.text.toString())})")}

    private fun saveGuestPrepared(expr:String){
        evalAdapter(expr){json->val o=safeObject(json);if(!o.optBoolean("ok")){purpose=Purpose.NONE;fail("Guest control آماده نشد: ${o.optString("error")}");return@evalAdapter};purpose=Purpose.VERIFY_GUEST_CHANGE;expectedPath=guestPath;evalAdapter("RouterAdapter.saveGuest()"){s->if(!safeObject(s).optBoolean("ok")){purpose=Purpose.NONE;fail("SAVE Guest اجرا نشد.")}else handler.postDelayed({if(purpose==Purpose.VERIFY_GUEST_CHANGE)web.loadUrl(baseUrl()+guestPath)},1600)}}
    }

    private fun verifyGuestChange(){
        evalAdapter("RouterAdapter.guestState()"){json->val o=safeObject(json);purpose=Purpose.NONE;expectedPath="";if(!o.optBoolean("ok")){fail("Guest بعد از SAVE دوباره خوانده نشد.");return@evalAdapter}
            val ok=when(guestVerifyKind){
                "bandwidth"->(guestExpectedUp==null||o.optString("upstream")==guestExpectedUp)&&(guestExpectedDown==null||o.optString("downstream")==guestExpectedDown)
                "credentials"->o.optString("ssid")==guestExpectedText
                "enabled"->stateMatches(o.optString("enabled"),guestExpectedText=="on")
                "isolation"->stateMatches(o.optString("isolation"),guestExpectedText=="on")
                "local"->stateMatches(o.optString("localAccess"),guestExpectedText=="on")
                else->false
            }
            if(ok) success(if(guestVerifyKind=="credentials")"SSID مهمان دوباره Verify شد. رمز به دلیل ماسک‌شدن توسط firmware قابل خواندن مجدد نیست و اپ ادعای Verify متن رمز نمی‌کند." else "تغییر Guest از خود روتر دوباره خوانده و Verify شد.") else fail("SAVE Guest انجام شد اما مقدار هدف از خود روتر Verify نشد.")
        }
    }

    private fun stateMatches(text:String,on:Boolean):Boolean{val t=text.lowercase(Locale.US);val off=t.contains("off")||t.contains("disable")||t.contains("deactivated")||t.contains(" no")||t=="0";return if(on)!off&&t.isNotBlank() else off}

    private fun updateUi(){
        refreshBtn.isEnabled=connected&&clientsReady;allowListBtn.isEnabled=connected&&wirelessReady;filterOffBtn.isEnabled=connected&&wirelessReady;wpsOffBtn.isEnabled=connected&&wpsReady;statsBtn.isEnabled=connected&&statsReady
        guestOnBtn.isEnabled=connected&&guestReady;guestOffBtn.isEnabled=connected&&guestReady;guestBwBtn.isEnabled=connected&&guestBandwidthReady;guestIsolationOnBtn.isEnabled=connected&&guestIsolationReady;guestIsolationOffBtn.isEnabled=connected&&guestIsolationReady;guestLocalOffBtn.isEnabled=connected&&guestLocalReady;guestLocalOnBtn.isEnabled=connected&&guestLocalReady;guestCredentialsBtn.isEnabled=connected&&guestCredentialsReady
        capabilities.text=buildString{append("Devices ").append(mark(clientsReady));append(" • Wi‑Fi MAC ").append(mark(wirelessReady));append(" • Internet MAC ").append(mark(accessReady));append(" • WPS ").append(mark(wpsReady));append("\nQoS ").append(mark(qosReady));append(" • Statistics ").append(mark(statsReady));append(" • Guest ").append(mark(guestReady));append(" • Guest BW ").append(mark(guestBandwidthReady));append("\nIsolation ").append(mark(guestIsolationReady));append(" • Guest→LAN ").append(mark(guestLocalReady));append(" • Guest SSID ").append(mark(guestCredentialsReady))}
    }

    private fun mark(v:Boolean)=if(v)"✓" else "—"
    private fun finish(message:String){purpose=Purpose.NONE;expectedPath="";connected=clientsReady;updateUi();success(message)}
    private fun success(message:String){status.text=message;updateUi()}
    private fun fail(message:String){status.text=message;updateUi()}
    private fun validMac(mac:String)=mac.matches(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$"))
    private fun isLocallyAdministeredMac(mac:String):Boolean=try{(mac.substring(0,2).toInt(16) and 0x02)!=0}catch(_:Exception){false}
    private fun safeObject(json:String)=try{JSONObject(json)}catch(_:Exception){JSONObject().put("ok",false).put("error","INVALID_RESPONSE")}
    private fun decodeJs(raw:String?):String{if(raw==null||raw=="null")return "";return try{JSONArray("[$raw]").optString(0)}catch(_:Exception){raw.trim('"').replace("\\\"","\"").replace("\\n","\n")}}
}
