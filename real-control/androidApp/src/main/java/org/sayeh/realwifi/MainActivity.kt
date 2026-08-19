package org.sayeh.realwifi

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
        NONE, CONNECT_ROOT, VERIFY_CLIENTS, VERIFY_WIRELESS, VERIFY_STATS, VERIFY_GUEST,
        REFRESH_CLIENTS, PREPARE_BLOCK, PREPARE_UNBLOCK, VERIFY_BLOCK_CONFIG, VERIFY_BLOCK_ONLINE,
        PREPARE_ALLOWLIST, VERIFY_ALLOWLIST, PREPARE_FILTER_OFF, VERIFY_FILTER_OFF,
        READ_STATS, PREPARE_GUEST_ON, PREPARE_GUEST_OFF, PREPARE_GUEST_BW, VERIFY_GUEST
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
    private var targetAllowed = emptyList<String>()
    private var guestDesiredOn: Boolean? = null
    private var guestDesiredUp: Double? = null
    private var guestDesiredDown: Double? = null
    private val allowChecks = linkedMapOf<String, CheckBox>()
    private var onlineMacs = linkedSetOf<String>()

    private val DEVICE_PATH = "/status/status_deviceinfo.htm"
    private val WIRELESS_PATH = "/basic/home_wlan.htm"
    private val STATS_PATH = "/status/status_statistics.htm"
    private val GUEST_PATH = "/basic/home_guest_network.htm"

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
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) fail("روتر پاسخ نداد: ${error?.description ?: "خطای شبکه"}")
            }
        }

        connectBtn.setOnClickListener { startConnection() }
        refreshBtn.setOnClickListener { if (connected) navigate(DEVICE_PATH, Purpose.REFRESH_CLIENTS) }
        allowListBtn.setOnClickListener { activateAllowList() }
        filterOffBtn.setOnClickListener { confirmFilterOff() }
        statsBtn.setOnClickListener { if (statsReady) navigate(STATS_PATH, Purpose.READ_STATS) }
        guestOnBtn.setOnClickListener { changeGuest(true) }
        guestOffBtn.setOnClickListener { changeGuest(false) }
        guestBwBtn.setOnClickListener { changeGuestBandwidth() }
        updateButtons()
    }

    private fun baseUrl(): String {
        val raw = routerUrl.text.toString().trim().ifBlank { "http://192.168.1.1" }
        return raw.trimEnd('/')
    }

    private fun startConnection() {
        if (username.text.toString().isBlank() || password.text.toString().isBlank()) {
            fail("نام کاربری و رمز ادمین را وارد کن.")
            return
        }
        prefs.edit().putString("router_url", baseUrl()).putString("router_user", username.text.toString().trim()).apply()
        connected = false; clientsReady = false; wirelessReady = false; statsReady = false; guestReady = false; guestBandwidthReady = false
        wirelessCapacity = 0; onlineMacs.clear(); allowChecks.clear(); deviceList.removeAllViews(); loginAttempts = 0
        purpose = Purpose.CONNECT_ROOT; expectedPath = ""
        status.text = "در حال ورود واقعی به روتر…"
        capabilities.text = "در حال Verify قابلیت‌های firmware…"
        updateButtons()
        web.loadUrl(baseUrl())
    }

    private fun handlePage(url: String) {
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
                Purpose.VERIFY_CLIENTS -> handleClients(then = { navigate(WIRELESS_PATH, Purpose.VERIFY_WIRELESS) })
                Purpose.VERIFY_WIRELESS -> verifyWireless()
                Purpose.VERIFY_STATS -> verifyStats()
                Purpose.VERIFY_GUEST -> verifyGuest()
                Purpose.REFRESH_CLIENTS -> handleClients(then = { success("فهرست دستگاه‌ها از خود روتر تازه شد.") })
                Purpose.PREPARE_BLOCK -> prepareBlock(true)
                Purpose.PREPARE_UNBLOCK -> prepareBlock(false)
                Purpose.VERIFY_BLOCK_CONFIG -> verifyBlockConfig()
                Purpose.VERIFY_BLOCK_ONLINE -> verifyBlockOnline()
                Purpose.PREPARE_ALLOWLIST -> prepareAllowList()
                Purpose.VERIFY_ALLOWLIST -> verifyAllowList()
                Purpose.PREPARE_FILTER_OFF -> prepareFilterOff()
                Purpose.VERIFY_FILTER_OFF -> verifyFilterOff()
                Purpose.READ_STATS -> readStats()
                Purpose.PREPARE_GUEST_ON -> prepareGuest(true)
                Purpose.PREPARE_GUEST_OFF -> prepareGuest(false)
                Purpose.PREPARE_GUEST_BW -> prepareGuestBandwidth()
                Purpose.VERIFY_GUEST -> verifyGuestChange()
                else -> Unit
            }
        }
    }

    private fun urlPathMatches(url: String, path: String): Boolean = try { URI(url).path.equals(path, ignoreCase = true) } catch (_: Exception) { url.contains(path, true) }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        val js = """
            (function(){try{
              return !!document.querySelector('input[type=password]') || location.href.toLowerCase().indexOf('login_security')>=0;
            }catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun autoLogin() {
        if (loginAttempts >= 3) { fail("ورود به روتر تأیید نشد. رمز یا نام کاربری را بررسی کن."); return }
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
              var f=p.form||u.form||document.forms[0]; if(!f)return 'NO_LOGIN_FORM';
              var bs=f.querySelectorAll('input[type=submit],input[type=button],button');
              for(var i=0;i<bs.length;i++){var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(t.indexOf('login')>=0){bs[i].click();return 'CLICKED';}}
              if(bs.length){bs[0].click();return 'CLICKED';}
              f.submit();return 'SUBMITTED';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) fail("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
        }
    }

    private fun navigate(path: String, p: Purpose) {
        purpose = p
        expectedPath = path
        web.loadUrl(baseUrl() + path)
    }

    private fun evalAdapter(expression: String, callback: (String) -> Unit) {
        val js = adapterJs + "\n;try{JSON.stringify($expression)}catch(e){JSON.stringify({ok:false,error:String(e)})}"
        web.evaluateJavascript(js) { callback(decodeJs(it)) }
    }

    private fun handleClients(then: () -> Unit) {
        val routerMac = JSONObject.quote("78:8C:B5:DD:8E:F0")
        evalAdapter("RouterAdapter.scanClients($routerMac)") { json ->
            try {
                val obj = JSONObject(json)
                if (!obj.optBoolean("ok")) { fail("جدول دستگاه‌ها از firmware خوانده نشد."); return@evalAdapter }
                val arr = obj.optJSONArray("clients") ?: JSONArray()
                onlineMacs = linkedSetOf()
                val rows = mutableListOf<Pair<String,String>>()
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val mac = c.optString("mac").uppercase(Locale.US)
                    val row = c.optString("row")
                    if (mac.matches(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")) && mac != "78:8C:B5:DD:8E:F0") {
                        onlineMacs.add(mac); rows.add(mac to row)
                    }
                }
                clientsReady = true
                renderDevices(rows)
                then()
            } catch (e: Exception) { fail("پاسخ دستگاه‌ها قابل تحلیل نبود: ${e.message}") }
        }
    }

    private fun verifyWireless() {
        status.text = "در حال Verify فرم واقعی Wireless MAC Filter…"
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            try {
                val o = JSONObject(json)
                wirelessReady = o.optBoolean("ok")
                wirelessCapacity = o.optInt("capacity", 0)
            } catch (_: Exception) { wirelessReady = false }
            navigate(STATS_PATH, Purpose.VERIFY_STATS)
        }
    }

    private fun verifyStats() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            statsReady = try { JSONObject(json).optBoolean("ok") } catch (_: Exception) { false }
            navigate(GUEST_PATH, Purpose.VERIFY_GUEST)
        }
    }

    private fun verifyGuest() {
        evalAdapter("RouterAdapter.scanGuest()") { json ->
            try {
                val o = JSONObject(json)
                guestReady = o.optBoolean("ok")
                guestBandwidthReady = false
                val fs = o.optJSONArray("fields") ?: JSONArray()
                for (i in 0 until fs.length()) {
                    val f = fs.optJSONObject(i) ?: continue
                    val meta = (f.optString("name") + " " + f.optString("row")).lowercase(Locale.US)
                    if (meta.contains("upstream") || meta.contains("downstream")) guestBandwidthReady = true
                }
            } catch (_: Exception) { guestReady = false; guestBandwidthReady = false }
            connected = clientsReady
            purpose = Purpose.NONE; expectedPath = ""
            updateButtons(); updateCapabilities()
            if (connected) success("اتصال واقعی برقرار شد. قابلیت‌های قابل‌کنترل از خود firmware Verify شدند.")
            else fail("اتصال کامل Verify نشد.")
        }
    }

    private fun updateCapabilities() {
        capabilities.text = buildString {
            append("دستگاه‌ها: ").append(if (clientsReady) "✓" else "✗")
            append("  •  MAC Filter: ").append(if (wirelessReady) "✓" else "✗")
            if (wirelessReady && wirelessCapacity > 0) append(" ($wirelessCapacity خانه)")
            append("\nStatistics: ").append(if (statsReady) "✓" else "✗")
            append("  •  Guest: ").append(if (guestReady) "✓" else "✗")
            append("  •  Guest Bandwidth: ").append(if (guestBandwidthReady) "✓" else "✗")
        }
    }

    private fun updateButtons() {
        refreshBtn.isEnabled = connected && clientsReady
        allowListBtn.isEnabled = connected && wirelessReady
        filterOffBtn.isEnabled = connected && wirelessReady
        statsBtn.isEnabled = connected && statsReady
        guestOnBtn.isEnabled = connected && guestReady
        guestOffBtn.isEnabled = connected && guestReady
        guestBwBtn.isEnabled = connected && guestReady && guestBandwidthReady
    }

    private fun renderDevices(rows: List<Pair<String,String>>) {
        deviceList.removeAllViews(); allowChecks.clear()
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (rows.isEmpty()) {
            deviceList.addView(TextView(this).apply { text = "هیچ کلاینت Wireless در جدول روتر دیده نشد."; setPadding(8,8,8,8) })
            return
        }
        rows.forEach { (mac, row) ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8,8,8,12) }
            val ip = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(row)?.value.orEmpty()
            val aliasKey = "alias_$mac"
            val title = TextView(this).apply {
                val alias = prefs.getString(aliasKey, "").orEmpty()
                text = (if (alias.isNotBlank()) "$alias\n" else "") + mac + (if (ip.isNotBlank()) "  •  $ip" else "") + (if (mac == protected) "  •  مدیر" else "")
                textSize = 16f
            }
            val check = CheckBox(this).apply { text = "مجاز در ضد QR"; isChecked = mac == protected || prefs.getStringSet("last_allow_list", emptySet())?.contains(mac) == true }
            allowChecks[mac] = check
            val alias = EditText(this).apply { hint = "نام این دستگاه"; setText(prefs.getString(aliasKey, "")) }
            alias.setOnFocusChangeListener { _, has -> if (!has) { prefs.edit().putString(aliasKey, alias.text.toString().trim()).apply(); renderDevices(rows) } }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val managerBtn = Button(this).apply {
                text = if (mac == protected) "مدیر ✓" else "این تلفن مدیر است"
                setOnClickListener { prefs.edit().putString("protected_mac", mac).apply(); renderDevices(rows); success("$mac به‌عنوان دستگاه مدیر محافظت شد.") }
            }
            val blockBtn = Button(this).apply {
                text = "قطع"
                isEnabled = wirelessReady && mac != protected
                setOnClickListener { startBlock(mac, true) }
            }
            val unblockBtn = Button(this).apply {
                text = "وصل"
                isEnabled = wirelessReady
                setOnClickListener { startBlock(mac, false) }
            }
            actions.addView(managerBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(blockBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(unblockBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(title); box.addView(check); box.addView(alias); box.addView(actions)
            deviceList.addView(box)
        }
    }

    private fun startBlock(mac: String, blocked: Boolean) {
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (blocked && mac == protected) { fail("دستگاه مدیر محافظت‌شده است و Block نمی‌شود."); return }
        targetMac = mac.uppercase(Locale.US); targetBlocked = blocked
        status.text = if (blocked) "در حال آماده‌سازی قطع واقعی $targetMac…" else "در حال آماده‌سازی وصل $targetMac…"
        navigate(WIRELESS_PATH, if (blocked) Purpose.PREPARE_BLOCK else Purpose.PREPARE_UNBLOCK)
    }

    private fun prepareBlock(blocked: Boolean) {
        val mq = JSONObject.quote(targetMac)
        evalAdapter(if (blocked) "RouterAdapter.prepareBlock($mq)" else "RouterAdapter.prepareUnblock($mq)") { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            if (!o.optBoolean("ok")) { fail("فرمان آماده نشد: ${o.optString("error", "UNKNOWN")}"); return@evalAdapter }
            if (!o.optBoolean("needsSave")) {
                purpose = Purpose.VERIFY_BLOCK_CONFIG
                verifyBlockConfig()
                return@evalAdapter
            }
            purpose = Purpose.VERIFY_BLOCK_CONFIG
            expectedPath = WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { saveJson ->
                val s = try { JSONObject(saveJson) } catch (_: Exception) { JSONObject() }
                if (!s.optBoolean("ok")) { fail("SAVE واقعی اجرا نشد: ${s.optString("error")}"); return@evalAdapter }
                handler.postDelayed({ if (purpose == Purpose.VERIFY_BLOCK_CONFIG) web.loadUrl(baseUrl() + WIRELESS_PATH) }, 1600)
            }
        }
    }

    private fun verifyBlockConfig() {
        val mq = JSONObject.quote(targetMac)
        evalAdapter("({ok:true,blocked:RouterAdapter.isBlocked($mq),state:RouterAdapter.wirelessState()})") { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val actual = o.optBoolean("blocked", false)
            if (actual != targetBlocked) { fail("SAVE انجام شد اما وضعیت Block از خود روتر تأیید نشد."); return@evalAdapter }
            navigate(DEVICE_PATH, Purpose.VERIFY_BLOCK_ONLINE)
        }
    }

    private fun verifyBlockOnline() {
        val routerMac = JSONObject.quote("78:8C:B5:DD:8E:F0")
        evalAdapter("RouterAdapter.scanClients($routerMac)") { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("clients") ?: JSONArray(); val set = linkedSetOf<String>(); val rows = mutableListOf<Pair<String,String>>()
            for (i in 0 until arr.length()) { val c=arr.optJSONObject(i)?:continue; val m=c.optString("mac").uppercase(Locale.US); if(m.isNotBlank()){set.add(m);rows.add(m to c.optString("row"));} }
            onlineMacs = set; renderDevices(rows); purpose=Purpose.NONE; expectedPath=""
            if (targetBlocked) {
                if (targetMac !in set) success("قطع واقعی تأیید شد: $targetMac دیگر در جدول Wireless Clients نیست.")
                else status.text = "قانون Block روی روتر Verify شد، اما $targetMac هنوز در جدول Wireless Clients است. این firmware فعلاً deauth فوری را تأیید نکرد؛ اپ آن را «قطع کامل» ثبت نمی‌کند."
            } else {
                success("قانون Block برای $targetMac برداشته و از خود روتر Verify شد. دستگاه اکنون اجازه اتصال دارد.")
            }
        }
    }

    private fun activateAllowList() {
        val protected = prefs.getString("protected_mac", "")?.uppercase(Locale.US).orEmpty()
        if (protected.isBlank()) { fail("اول دستگاه مدیر را مشخص کن؛ Allow‑List بدون مدیر اجرا نمی‌شود."); return }
        val selected = allowChecks.filterValues { it.isChecked }.keys.toMutableSet(); selected.add(protected)
        if (selected.isEmpty()) { fail("هیچ دستگاه مجازی انتخاب نشده."); return }
        AlertDialog.Builder(this).setTitle("فعال‌سازی ضد QR واقعی")
            .setMessage("پس از SAVE فقط ${selected.size} MAC انتخاب‌شده اجازه Association خواهند داشت. دستگاه مدیر داخل فهرست است. ادامه؟")
            .setPositiveButton("اجرا") { _, _ -> targetAllowed = selected.toList(); navigate(WIRELESS_PATH, Purpose.PREPARE_ALLOWLIST) }
            .setNegativeButton("لغو", null).show()
    }

    private fun prepareAllowList() {
        val jsArray = targetAllowed.joinToString(prefix="[", postfix="]") { JSONObject.quote(it) }
        evalAdapter("RouterAdapter.prepareAllowList($jsArray)") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()}
            if(!o.optBoolean("ok")){fail("Allow‑List آماده نشد: ${o.optString("error")}");return@evalAdapter}
            purpose=Purpose.VERIFY_ALLOWLIST;expectedPath=WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { sjson ->
                val s=try{JSONObject(sjson)}catch(_:Exception){JSONObject()};if(!s.optBoolean("ok")){fail("SAVE Allow‑List اجرا نشد.");return@evalAdapter}
                handler.postDelayed({if(purpose==Purpose.VERIFY_ALLOWLIST)web.loadUrl(baseUrl()+WIRELESS_PATH)},1600)
            }
        }
    }

    private fun verifyAllowList() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()}; val mode=o.optString("mode").lowercase(Locale.US); val enabled=o.optBoolean("enabled"); val arr=o.optJSONArray("macs")?:JSONArray(); val set=mutableSetOf<String>()
            for(i in 0 until arr.length())set.add(arr.optString(i).uppercase(Locale.US))
            val ok=enabled&&mode.contains("allow association")&&set.containsAll(targetAllowed.map{it.uppercase(Locale.US)})
            if(ok){prefs.edit().putStringSet("last_allow_list",targetAllowed.toSet()).apply();purpose=Purpose.NONE;expectedPath="";success("ضد QR واقعی فعال و از خود روتر Verify شد.")}else fail("Allow‑List بعد از SAVE تأیید نشد؛ موفق ثبت نشد.")
        }
    }

    private fun confirmFilterOff() {
        AlertDialog.Builder(this).setTitle("بازگردانی اضطراری")
            .setMessage("Wireless MAC Filter خاموش شود؟ WAN/ADSL و رمز Wi‑Fi دست نمی‌خورند.")
            .setPositiveButton("خاموش کن") { _, _ -> navigate(WIRELESS_PATH, Purpose.PREPARE_FILTER_OFF) }
            .setNegativeButton("لغو", null).show()
    }

    private fun prepareFilterOff() {
        evalAdapter("RouterAdapter.prepareFilterOff()") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(!o.optBoolean("ok")){fail("خاموش‌کردن Filter آماده نشد: ${o.optString("error")}");return@evalAdapter}
            purpose=Purpose.VERIFY_FILTER_OFF;expectedPath=WIRELESS_PATH
            evalAdapter("RouterAdapter.saveWireless()") { sjson ->
                val s=try{JSONObject(sjson)}catch(_:Exception){JSONObject()};if(!s.optBoolean("ok")){fail("SAVE اجرا نشد.");return@evalAdapter}
                handler.postDelayed({if(purpose==Purpose.VERIFY_FILTER_OFF)web.loadUrl(baseUrl()+WIRELESS_PATH)},1600)
            }
        }
    }

    private fun verifyFilterOff() {
        evalAdapter("RouterAdapter.wirelessState()") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(o.optBoolean("ok")&&!o.optBoolean("enabled")){purpose=Purpose.NONE;expectedPath="";success("MAC Filter واقعاً خاموش و Verify شد.")}else fail("خاموش‌شدن MAC Filter تأیید نشد.")
        }
    }

    private fun readStats() {
        evalAdapter("RouterAdapter.scanStats()") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(!o.optBoolean("ok")){fail("Statistics خوانده نشد.");return@evalAdapter}
            val rx=if(o.isNull("rxBytes"))null else o.optLong("rxBytes");val tx=if(o.isNull("txBytes"))null else o.optLong("txBytes")
            if(rx==null||tx==null){usageText.text="این firmware شمارندهٔ Byte قابل‌تشخیص در صفحه Statistics نشان نداد؛ مصرف جعلی نمایش داده نمی‌شود.";purpose=Purpose.NONE;return@evalAdapter}
            val lastRx=prefs.getLong("last_rx",-1);val lastTx=prefs.getLong("last_tx",-1);var carried=prefs.getLong("carried_bytes",0)
            if(lastRx>=0)carried+=if(rx>=lastRx)rx-lastRx else rx;if(lastTx>=0)carried+=if(tx>=lastTx)tx-lastTx else tx
            prefs.edit().putLong("last_rx",rx).putLong("last_tx",tx).putLong("carried_bytes",carried).putString("package_gb",packageGb.text.toString().trim()).apply()
            val usedGb=carried.toDouble()/(1024.0*1024*1024);val pkg=packageGb.text.toString().toDoubleOrNull();val remain=if(pkg!=null)(pkg-usedGb).coerceAtLeast(0.0) else null
            usageText.text="مصرف ثبت‌شده از شمارنده روتر: %.3f GB%s".format(usedGb,if(remain!=null)"\nباقی‌مانده تخمینی از بسته: %.3f GB".format(remain) else "\nحجم بسته را وارد کن تا باقی‌مانده تخمینی محاسبه شود.")
            purpose=Purpose.NONE;expectedPath="";success("Statistics واقعی خوانده شد.")
        }
    }

    private fun changeGuest(on: Boolean) {
        guestDesiredOn=on;navigate(GUEST_PATH,if(on)Purpose.PREPARE_GUEST_ON else Purpose.PREPARE_GUEST_OFF)
    }

    private fun prepareGuest(on: Boolean) {
        evalAdapter("RouterAdapter.setGuestEnabled(${if(on)"true" else "false"})") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(!o.optBoolean("ok")){fail("کنترل Guest آماده نشد: ${o.optString("error")}");return@evalAdapter}
            purpose=Purpose.VERIFY_GUEST;expectedPath=GUEST_PATH
            evalAdapter("RouterAdapter.saveGuest()") { sjson ->
                val s=try{JSONObject(sjson)}catch(_:Exception){JSONObject()};if(!s.optBoolean("ok")){fail("SAVE Guest اجرا نشد.");return@evalAdapter}
                handler.postDelayed({if(purpose==Purpose.VERIFY_GUEST)web.loadUrl(baseUrl()+GUEST_PATH)},1600)
            }
        }
    }

    private fun changeGuestBandwidth() {
        val up=guestUp.text.toString().toDoubleOrNull();val down=guestDown.text.toString().toDoubleOrNull();if(up==null&&down==null){fail("حداقل Upstream یا Downstream را وارد کن.");return}
        guestDesiredUp=up;guestDesiredDown=down;navigate(GUEST_PATH,Purpose.PREPARE_GUEST_BW)
    }

    private fun prepareGuestBandwidth() {
        val up=guestDesiredUp?.toString()?:"null";val down=guestDesiredDown?.toString()?:"null"
        evalAdapter("RouterAdapter.setGuestBandwidth($up,$down)") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(!o.optBoolean("ok")){fail("Bandwidth Guest آماده نشد: ${o.optString("error")}");return@evalAdapter}
            purpose=Purpose.VERIFY_GUEST;expectedPath=GUEST_PATH
            evalAdapter("RouterAdapter.saveGuest()") { sjson ->
                val s=try{JSONObject(sjson)}catch(_:Exception){JSONObject()};if(!s.optBoolean("ok")){fail("SAVE Bandwidth اجرا نشد.");return@evalAdapter}
                handler.postDelayed({if(purpose==Purpose.VERIFY_GUEST)web.loadUrl(baseUrl()+GUEST_PATH)},1600)
            }
        }
    }

    private fun verifyGuestChange() {
        evalAdapter("RouterAdapter.scanGuest()") { json ->
            val o=try{JSONObject(json)}catch(_:Exception){JSONObject()};if(!o.optBoolean("ok")){fail("فرم Guest بعد از SAVE دوباره خوانده نشد.");return@evalAdapter}
            purpose=Purpose.NONE;expectedPath=""
            status.text="SAVE Guest اجرا شد و فرم واقعی دوباره خوانده شد. برای این firmware، اپ فقط تغییراتی را که فیلد متناظرشان قابل تشخیص باشد اعمال می‌کند."
        }
    }

    private fun success(message: String) { status.text=message; updateButtons(); updateCapabilities() }
    private fun fail(message: String) { status.text=message; updateButtons(); updateCapabilities() }

    private fun decodeJs(raw: String?): String {
        if(raw==null||raw=="null")return ""
        return try{JSONArray("[$raw]").optString(0)}catch(_:Exception){raw.trim('"').replace("\\\"","\"").replace("\\n","\n")}
    }
}
