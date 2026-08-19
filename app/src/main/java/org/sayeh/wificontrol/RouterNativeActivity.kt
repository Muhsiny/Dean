package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Firmware-specific controller for TP-Link TD-W8961N V4 / 3.2.0 Build 210914.
 * It first loads the router's real endpoint directly. If this old frame-based UI does
 * not render the page standalone, the controller loads the original frameset and puts
 * the exact endpoint into top.main. Mutations are reported successful only after a
 * second read from the router verifies the result.
 */
class RouterNativeActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var action = "sync"
    private var targetMac = ""
    private var allowedMacs: List<String> = emptyList()
    private var loginAttempts = 0
    private var dispatched = false
    private var finished = false
    private var pendingTarget: String? = null
    private var pendingAfterLoad: (() -> Unit)? = null

    private val deviceInfoPath = "/status/status_deviceinfo.htm"
    private val wirelessPath = "/basic/home_wlan.htm"
    private val statisticsPath = "/status/status_statistics.htm"
    private val guestPath = "/basic/home_guest_network.htm"
    private val navigationBasicPath = "/navigation-basic.html"
    private val navigationStatusPath = "/navigation-status.html"
    private val expectedFirmware = "3.2.0 Build 210914 Rel.24052"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)
        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        action = intent.getStringExtra("action") ?: "sync"
        targetMac = normalizeMac(intent.getStringExtra("targetMac").orEmpty())
        allowedMacs = intent.getStringExtra("allowedMacs")
            ?.split(',')?.map(::normalizeMac)?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

        status = findViewById(R.id.routerStatus)
        web = findViewById(R.id.routerWeb)
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { finished = true; finish() }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!finished) handler.postDelayed({ handleLoadedPage() }, 220)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !finished) {
                    fail("روتر پاسخ نداد: ${error?.description ?: "خطای نامشخص"}")
                }
            }
        }

        status.text = "در حال ورود واقعی به روتر…"
        web.loadUrl(baseUrl)
    }

    private fun handleLoadedPage() {
        if (finished) return
        isLoginPage { login ->
            if (login) {
                attemptAutoLogin()
                return@isLoginPage
            }

            loginAttempts = 0
            val target = pendingTarget
            if (target != null && normalizeUrl(web.url.orEmpty()) != normalizeUrl(target)) {
                web.loadUrl(target)
                return@isLoginPage
            }

            val cb = pendingAfterLoad
            if (target != null && cb != null) {
                pendingTarget = null
                pendingAfterLoad = null
                cb()
                return@isLoginPage
            }

            if (!dispatched) {
                dispatched = true
                dispatchAction()
            }
        }
    }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        val js = """
            (function(){try{
              var p=document.querySelector('input[type=password]');
              var t=(document.body?document.body.innerText:'').toLowerCase();
              return !!p || location.href.toLowerCase().indexOf('login_security')>=0 ||
                     (t.indexOf('username')>=0&&t.indexOf('password')>=0&&t.indexOf('login')>=0);
            }catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun attemptAutoLogin() {
        if (user.isBlank() || pass.isBlank()) { fail("نام کاربری یا رمز ادمین خالی است."); return }
        if (loginAttempts >= 3) { fail("احراز هویت موفق نشد؛ هیچ تنظیمی تغییر نکرد."); return }
        loginAttempts++
        status.text = "در حال احراز هویت با firmware…"
        val uq = JSONObject.quote(user)
        val pq = JSONObject.quote(pass)
        val js = """
            (function(){try{
              var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');
              var p=document.querySelector('input[type=password],input[name*=pass i],input[id*=pass i]');
              if(!u||!p)return 'NO_FORM';
              u.value=$uq;p.value=$pq;
              ['input','change'].forEach(function(n){u.dispatchEvent(new Event(n,{bubbles:true}));p.dispatchEvent(new Event(n,{bubbles:true}));});
              var f=p.form||u.form||document.forms[0];if(!f)return 'NO_FORM';
              var bs=f.querySelectorAll('input[type=submit],button[type=submit],input[type=button],button');
              for(var i=0;i<bs.length;i++){
                var s=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();
                if(s.indexOf('login')>=0){bs[i].click();return 'CLICKED';}
              }
              if(bs.length){bs[0].click();return 'CLICKED';}
              f.submit();return 'SUBMITTED';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) fail("فرم Login روتر شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
        }
    }

    private fun dispatchAction() {
        when (action) {
            "sync" -> syncAndCalibrate()
            "block", "unblock" -> {
                val owner = prefs.getString("protected_mac", null)?.let(::normalizeMac)
                if (targetMac.isBlank()) fail("MAC هدف خالی است.")
                else if (targetMac == owner) fail("تلفن مدیر محافظت‌شده است و Block نمی‌شود.")
                else openWireless { prepareDeviceAction(action, targetMac) }
            }
            "antiqr_enable" -> {
                val owner = prefs.getString("protected_mac", null)?.let(::normalizeMac)
                if (owner.isNullOrBlank() || owner !in allowedMacs) fail("تلفن مدیر داخل Allow-List نیست؛ فرمان اجرا نشد.")
                else openWireless { prepareAllowList() }
            }
            "antiqr_disable", "filter_off" -> openWireless { prepareFilterOff(action) }
            "usage" -> openStatistics { readTrafficStatistics() }
            "guest_probe" -> openGuest { probeGuestNetwork() }
            else -> fail("فرمان ناشناخته است.")
        }
    }

    private fun syncAndCalibrate() {
        status.text = "در حال خواندن مستقیم جدول دستگاه‌های متصل…"
        openDeviceInfo {
            extractClients { count, firmware ->
                if (firmware.isNotBlank()) prefs.edit().putString("firmware_version", firmware).apply()
                status.text = "$count دستگاه آنلاین خوانده شد؛ در حال Verify کردن فرم واقعی Wireless…"
                openWireless { state ->
                    val capacity = state.optInt("capacity", 0)
                    prefs.edit()
                        .putBoolean("control_ready", true)
                        .putString("wireless_route", route(wirelessPath))
                        .putInt("mac_filter_capacity", capacity)
                        .apply()
                    val fwText = firmware.ifBlank { expectedFirmware }
                    succeed("$count دستگاه آنلاین • موتور Wireless MAC Filter واقعاً Verify شد${if (capacity > 0) " • ظرفیت: $capacity MAC" else ""} • $fwText")
                }
            }
        }
    }

    private fun openDeviceInfo(onReady: () -> Unit) {
        loadPath(deviceInfoPath) {
            probeRecursive("current connected wireless clients") { ok ->
                if (ok) onReady() else openFramed(navigationStatusPath, deviceInfoPath, "current connected wireless clients", onReady, "جدول دستگاه‌های متصل پیدا نشد.")
            }
        }
    }

    private fun openWireless(onReady: (JSONObject) -> Unit) {
        loadPath(wirelessPath) {
            inspectFilterState { state ->
                if (state.optBoolean("ok", false)) onReady(state)
                else {
                    status.text = "صفحه Wireless به‌صورت مستقیم کامل نبود؛ در حال اجرای fallback فریم اصلی روتر…"
                    openFramed(navigationBasicPath, wirelessPath, "wireless mac address filter", {
                        inspectFilterState { second ->
                            if (second.optBoolean("ok", false)) onReady(second)
                            else fail("endpoint واقعی Wireless باز شد، اما فرم MAC Filter قابل‌کنترل پیدا نشد. هیچ تغییر انجام نشد. از Direct Firmware Map برای تشخیص فیلدها استفاده کن.")
                        }
                    }, "Wireless MAC Filter در فریم اصلی هم پیدا نشد.")
                }
            }
        }
    }

    private fun openStatistics(onReady: () -> Unit) {
        loadPath(statisticsPath) {
            probeRecursive("statistics") { ok ->
                if (ok) onReady() else openFramed(navigationStatusPath, statisticsPath, "statistics", onReady, "صفحه Statistics پیدا نشد.")
            }
        }
    }

    private fun openGuest(onReady: () -> Unit) {
        loadPath(guestPath) {
            probeRecursive("guest") { ok ->
                if (ok) onReady() else openFramed(navigationBasicPath, guestPath, "guest", onReady, "صفحه Guest Network پیدا نشد.")
            }
        }
    }

    private fun openFramed(navigationPath: String, mainPath: String, marker: String, onReady: () -> Unit, failMessage: String) {
        loadPath("/") {
            val nav = JSONObject.quote(route(navigationPath))
            val main = JSONObject.quote(route(mainPath))
            val js = """
                (function(){try{
                  var n=null,m=null;
                  try{n=window.frames['navigation'];}catch(e){}
                  try{m=window.frames['main'];}catch(e){}
                  if(n)n.location=$nav;
                  if(m)m.location=$main;
                  return !!m;
                }catch(e){return false;}})();
            """.trimIndent()
            web.evaluateJavascript(js) { raw ->
                if (raw != "true") { fail(failMessage); return@evaluateJavascript }
                handler.postDelayed({
                    probeRecursive(marker) { ok -> if (ok) onReady() else fail(failMessage) }
                }, 1100)
            }
        }
    }

    private fun probeRecursive(marker: String, callback: (Boolean) -> Unit) {
        val q = JSONObject.quote(marker.lowercase(Locale.US))
        val js = """
            (function(){var want=$q;
              function scan(w){try{
                var t=(w.document.body?w.document.body.innerText:'').toLowerCase();
                if(t.indexOf(want)>=0)return true;
                for(var i=0;i<w.frames.length;i++)if(scan(w.frames[i]))return true;
              }catch(e){}return false;}
              return scan(window);
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun extractClients(callback: (Int, String) -> Unit) {
        val js = """
            (function(){
              var macre=/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/ig;
              function scan(w){try{
                var d=w.document,text=d.body?d.body.innerText:'';
                var fw=(text.match(/Firmware\s*Version\s*[:：]?\s*([^\n\r]+)/i)||[])[1]||'';
                var router=((text.match(/MAC\s*Address\s*[:：]?\s*((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})/i)||[])[1]||'').replace(/-/g,':').toUpperCase();
                var best=[],bestScore=-1,tables=d.querySelectorAll('table');
                for(var i=0;i<tables.length;i++){
                  var found=(tables[i].innerText.match(macre)||[]).map(function(x){return x.replace(/-/g,':').toUpperCase();});
                  if(!found.length)continue;
                  var near='',a=tables[i];for(var k=0;k<4&&a;k++,a=a.parentElement)near+=(a.innerText||'')+' ';
                  var score=found.length*20+(near.toLowerCase().indexOf('current connected wireless clients')>=0?500:0);
                  if(score>bestScore){bestScore=score;best=found;}
                }
                if(best.length){var uniq=[];best.forEach(function(m){if(m!=router&&uniq.indexOf(m)<0)uniq.push(m);});return {clients:uniq,router:router,firmware:fw.trim()};}
                for(var f=0;f<w.frames.length;f++){var z=scan(w.frames[f]);if(z&&z.clients&&z.clients.length)return z;}
                return {clients:[],router:router,firmware:fw.trim()};
              }catch(e){return {clients:[],router:'',firmware:''};}}
              return JSON.stringify(scan(window));
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            try {
                val o = JSONObject(decodeJs(raw))
                val online = linkedSetOf<String>()
                val arr = o.optJSONArray("clients") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = normalizeMac(arr.optString(i))
                    if (m.matches(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$"))) online.add(m)
                }
                val known = prefs.getStringSet("known_macs", emptySet())?.map(::normalizeMac)?.toMutableSet() ?: mutableSetOf()
                known.addAll(online)
                prefs.edit()
                    .putStringSet("online_macs", online)
                    .putStringSet("known_macs", known)
                    .putString("router_mac", normalizeMac(o.optString("router")))
                    .apply()
                callback(online.size, o.optString("firmware"))
            } catch (_: Exception) { callback(0, "") }
        }
    }

    private fun prepareDeviceAction(mode: String, mac: String) {
        status.text = if (mode == "block") "در حال آماده‌سازی قطع واقعی…" else "در حال آماده‌سازی وصل‌کردن دوباره…"
        val modeQ = JSONObject.quote(mode)
        val macQ = JSONObject.quote(mac)
        val js = filterPrelude() + """
            var d=findFilterDoc(window),f=d?findForm(d):null;if(!f)return 'NO_FORM';
            var sel=findAction(f);if(!sel)return 'NO_ACTION';var ins=findMacInputs(f);if(!ins.length)return 'NO_MAC_INPUTS';
            var enabled=isEnabled(f),act=selected(sel);
            function has(m){for(var i=0;i<ins.length;i++)if(norm(ins[i].value)==norm(m))return i;return -1;}
            function emptySlot(){for(var i=0;i<ins.length;i++){var v=norm(ins[i].value);if(!v||v=='00:00:00:00:00:00')return i;}return -1;}
            if($modeQ=='block'){
              if(enabled&&act.indexOf('allow association')>=0){var ia=has($macQ);if(ia<0)return 'ALREADY_BLOCKED_ALLOW';setMac(ins[ia],'00:00:00:00:00:00');return 'READY_BLOCK_ALLOW';}
              if(enabled&&act.indexOf('deny association')>=0){var id=has($macQ);if(id>=0)return 'ALREADY_BLOCKED_DENY';var ed=emptySlot();if(ed<0)return 'NO_EMPTY_SLOT';setMac(ins[ed],$macQ);return 'READY_BLOCK_DENY';}
              if(!setEnabled(f,true))return 'NO_ENABLE';if(!setSelect(sel,'deny association'))return 'NO_DENY';var en=emptySlot();if(en<0)en=0;setMac(ins[en],$macQ);return 'READY_BLOCK_NEW_DENY';
            }
            if(!enabled)return 'ALREADY_ALLOWED_DISABLED';
            if(act.indexOf('deny association')>=0){var iu=has($macQ);if(iu<0)return 'ALREADY_ALLOWED_DENY';setMac(ins[iu],'00:00:00:00:00:00');return 'READY_UNBLOCK_DENY';}
            if(act.indexOf('allow association')>=0){var iua=has($macQ);if(iua>=0)return 'ALREADY_ALLOWED_ALLOW';var eu=emptySlot();if(eu<0)return 'NO_EMPTY_SLOT';setMac(ins[eu],$macQ);return 'READY_UNBLOCK_ALLOW';}
            return 'UNKNOWN_ACTION';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            when {
                r.startsWith("READY_") -> confirmSaveAndVerify(
                    if (mode == "block") "قطع واقعی دستگاه" else "وصل‌کردن دوباره",
                    "$mac روی فرم واقعی firmware آماده شده است. SAVE و Verify اجرا شود؟",
                    mode, mac
                )
                r.startsWith("ALREADY_BLOCKED") -> verifyDeviceAction("block", mac)
                r.startsWith("ALREADY_ALLOWED") -> verifyDeviceAction("unblock", mac)
                else -> fail("فرمان روی فرم واقعی آماده نشد: $r")
            }
        }
    }

    private fun prepareAllowList() {
        if (allowedMacs.isEmpty()) { fail("Allow-List خالی است."); return }
        val arr = allowedMacs.joinToString(",") { JSONObject.quote(it) }
        val js = filterPrelude() + """
            var wanted=[$arr],d=findFilterDoc(window),f=d?findForm(d):null;if(!f)return 'NO_FORM';
            var sel=findAction(f);if(!sel)return 'NO_ACTION';var ins=findMacInputs(f);if(ins.length<wanted.length)return 'CAPACITY:'+ins.length;
            if(!setEnabled(f,true))return 'NO_ENABLE';if(!setSelect(sel,'allow association'))return 'NO_ALLOW';
            for(var i=0;i<ins.length;i++)setMac(ins[i],i<wanted.length?wanted[i]:'00:00:00:00:00:00');
            return 'READY_ALLOW:'+ins.length;})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("READY_ALLOW")) {
                confirmSaveAndVerify("ضد QR واقعی", "Allow Association با ${allowedMacs.size} MAC آماده شده است. SAVE و Verify شود؟", "antiqr_enable", "")
            } else if (r.startsWith("CAPACITY:")) {
                fail("ظرفیت واقعی MAC Filter فقط ${r.substringAfter(':')} دستگاه است.")
            } else fail("Allow-List آماده نشد: $r")
        }
    }

    private fun prepareFilterOff(mode: String) {
        val js = filterPrelude() + """
            var d=findFilterDoc(window),f=d?findForm(d):null;if(!f)return 'NO_FORM';
            if(!setEnabled(f,false))return 'NO_ENABLE';return 'READY_OFF';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (decodeJs(raw) == "READY_OFF") {
                confirmSaveAndVerify("خاموش‌کردن MAC Filter", "فقط Wireless MAC Filter خاموش می‌شود؛ WAN/ADSL دست‌نخورده می‌ماند. SAVE و Verify شود؟", mode, "")
            } else fail("خاموش‌کردن فیلتر آماده نشد: ${decodeJs(raw)}")
        }
    }

    private fun confirmSaveAndVerify(title: String, message: String, mode: String, mac: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("SAVE و بررسی") { _, _ ->
                status.text = "در حال SAVE واقعی روی firmware…"
                web.evaluateJavascript(saveScript()) { raw ->
                    val r = decodeJs(raw)
                    if (r.startsWith("NO_")) fail("SAVE واقعی در فرم پیدا نشد؛ موفقیتی ثبت نشد.")
                    else handler.postDelayed({ openWireless { verifyAfterSave(mode, mac) } }, 950)
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun verifyAfterSave(mode: String, mac: String) {
        inspectFilterState { s ->
            if (!s.optBoolean("ok", false)) { fail("بعد از SAVE فرم MAC Filter قابل Verify نبود."); return@inspectFilterState }
            val enabled = s.optBoolean("enabled")
            val actionText = s.optString("action").lowercase(Locale.US)
            val set = mutableSetOf<String>()
            val a = s.optJSONArray("macs") ?: JSONArray()
            for (i in 0 until a.length()) set.add(normalizeMac(a.optString(i)))
            fun isBlocked(m: String): Boolean = enabled && when {
                actionText.contains("deny association") -> normalizeMac(m) in set
                actionText.contains("allow association") -> normalizeMac(m) !in set
                else -> false
            }
            when (mode) {
                "block" -> if (isBlocked(mac)) {
                    markBlocked(mac, true); succeed("قطع واقعی Verify شد؛ $mac طبق وضعیت خوانده‌شده از خود روتر Block است.")
                } else fail("SAVE انجام شد اما Block از خود روتر تأیید نشد.")
                "unblock" -> if (!isBlocked(mac)) {
                    markBlocked(mac, false); succeed("وصل‌شدن دوباره Verify شد؛ $mac دیگر Block نیست.")
                } else fail("Unblock از خود روتر تأیید نشد.")
                "antiqr_enable" -> {
                    val wanted = allowedMacs.map(::normalizeMac).toSet()
                    if (enabled && actionText.contains("allow association") && set == wanted) {
                        prefs.edit().putBoolean("anti_qr_active", true).putStringSet("allowed_macs", wanted).apply()
                        succeed("ضد QR واقعی Verify شد؛ Allow-List دقیقاً ${wanted.size} دستگاه دارد.")
                    } else fail("Allow-List بعد از SAVE دقیقاً با فهرست انتخاب‌شده برابر نیست؛ موفق ثبت نشد.")
                }
                "antiqr_disable", "filter_off" -> if (!enabled) {
                    prefs.edit().putBoolean("anti_qr_active", false).apply(); succeed("Wireless MAC Filter خاموش و Verify شد.")
                } else fail("MAC Filter هنوز Active است.")
            }
        }
    }

    private fun verifyDeviceAction(mode: String, mac: String) {
        inspectFilterState { s ->
            if (!s.optBoolean("ok", false)) { fail("وضعیت MAC Filter قابل خواندن نیست."); return@inspectFilterState }
            val enabled = s.optBoolean("enabled")
            val act = s.optString("action").lowercase(Locale.US)
            val set = mutableSetOf<String>()
            val a = s.optJSONArray("macs") ?: JSONArray()
            for (i in 0 until a.length()) set.add(normalizeMac(a.optString(i)))
            val blocked = enabled && when {
                act.contains("deny association") -> normalizeMac(mac) in set
                act.contains("allow association") -> normalizeMac(mac) !in set
                else -> false
            }
            if (mode == "block" && blocked) { markBlocked(mac, true); succeed("این دستگاه از قبل واقعاً Block بود و Verify شد.") }
            else if (mode == "unblock" && !blocked) { markBlocked(mac, false); succeed("این دستگاه از قبل مجاز بود و Verify شد.") }
            else fail("وضعیت واقعی روتر با فرمان موردنظر برابر نیست.")
        }
    }

    private fun inspectFilterState(callback: (JSONObject) -> Unit) {
        val js = filterPrelude() + """
            var d=findFilterDoc(window),f=d?findForm(d):null;
            if(!f)return JSON.stringify({ok:false});
            var sel=findAction(f),ins=findMacInputs(f),ms=[];
            for(var i=0;i<ins.length;i++){var m=norm(ins[i].value);if(m&&m!='00:00:00:00:00:00')ms.push(m);}
            return JSON.stringify({ok:!!sel&&ins.length>0&&!!filterGroup(f),enabled:isEnabled(f),action:sel?selected(sel):'',capacity:ins.length,macs:ms,formAction:f.action||''});})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            try { callback(JSONObject(decodeJs(raw))) }
            catch (_: Exception) { callback(JSONObject()) }
        }
    }

    private fun filterPrelude(): String = """
        (function(){
          function norm(v){return ((v||'')+'').trim().replace(/-/g,':').toUpperCase();}
          function findForm(d){if(!d)return null;var fs=d.forms,b=null,score=-1;for(var i=0;i<fs.length;i++){
            var f=fs[i],s=0,txt=(f.innerText||'').toLowerCase();if(txt.indexOf('wireless mac address filter')>=0)s+=250;
            var ss=f.querySelectorAll('select');for(var j=0;j<ss.length;j++){var ot=(ss[j].innerText||'').toLowerCase();if(ot.indexOf('allow association')>=0&&ot.indexOf('deny association')>=0)s+=500;}
            var ins=f.querySelectorAll('input[type=text],input:not([type])');for(var k=0;k<ins.length;k++){
              var row=ins[k].closest?ins[k].closest('tr'):ins[k].parentElement,rt=((row&&row.innerText)||'').toLowerCase(),nm=((ins[k].name||'')+' '+(ins[k].id||'')).toLowerCase();
              if(rt.indexOf('mac address')>=0||nm.indexOf('mac')>=0)s+=20;
            }
            if(s>score){score=s;b=f;}
          }return score>=300?b:null;}
          function findFilterDoc(w){try{var d=w.document;if(findForm(d))return d;for(var i=0;i<w.frames.length;i++){var z=findFilterDoc(w.frames[i]);if(z)return z;}}catch(e){}return null;}
          function findAction(f){var ss=f.querySelectorAll('select');for(var i=0;i<ss.length;i++){var t=(ss[i].innerText||'').toLowerCase();if(t.indexOf('allow association')>=0&&t.indexOf('deny association')>=0)return ss[i];}return null;}
          function selected(s){try{return ((s.options[s.selectedIndex].text||s.value||'')+'').toLowerCase();}catch(e){return '';}}
          function setSelect(s,want){for(var i=0;i<s.options.length;i++){var t=(s.options[i].text||'').toLowerCase();if(t.indexOf(want)>=0){s.selectedIndex=i;s.value=s.options[i].value;s.dispatchEvent(new Event('change',{bubbles:true}));return true;}}return false;}
          function findMacInputs(f){var all=f.querySelectorAll('input[type=text],input:not([type])'),out=[];for(var i=0;i<all.length;i++){
            var e=all[i],n=((e.name||'')+' '+(e.id||'')).toLowerCase(),row=e.closest?e.closest('tr'):e.parentElement,rt=((row&&row.innerText)||'').toLowerCase(),v=e.value||'';
            if(n.indexOf('mac')>=0||rt.indexOf('mac address')>=0||/^(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(v))out.push(e);
          }return out;}
          function setMac(e,v){e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
          function radioGroups(f){var rs=f.querySelectorAll('input[type=radio]'),g={};for(var i=0;i<rs.length;i++){var n=rs[i].name||('__'+i);(g[n]||(g[n]=[])).push(rs[i]);}return g;}
          function groupText(a){if(!a.length)return '';var r=a[0].closest?a[0].closest('tr'):a[0].parentElement;return ((r&&r.innerText)||'').toLowerCase();}
          function filterGroup(f){var gs=radioGroups(f);for(var k in gs){var t=groupText(gs[k]);if((t.indexOf('activated')>=0||t.indexOf('active')>=0)&&t.indexOf('deactivated')>=0)return gs[k];}return null;}
          function setEnabled(f,on){var g=filterGroup(f);if(!g)return false;var pick=null;for(var i=0;i<g.length;i++){
            var r=g[i].closest?g[i].closest('td'):g[i].parentElement,tx=((r&&r.innerText)||g[i].value||'').toLowerCase();
            if(on&&tx.indexOf('deactivated')<0&&(tx.indexOf('activated')>=0||tx.indexOf('active')>=0))pick=g[i];
            if(!on&&tx.indexOf('deactivated')>=0)pick=g[i];
          }if(!pick)pick=on?g[0]:g[g.length-1];pick.checked=true;try{pick.click();}catch(e){}pick.dispatchEvent(new Event('change',{bubbles:true}));return true;}
          function isEnabled(f){var g=filterGroup(f);if(!g)return false;for(var i=0;i<g.length;i++)if(g[i].checked){var r=g[i].closest?g[i].closest('td'):g[i].parentElement,tx=((r&&r.innerText)||g[i].value||'').toLowerCase();return tx.indexOf('deactivated')<0;}return false;}
    """.trimIndent()

    private fun saveScript(): String = """
        (function(){try{
          function ff(d){if(!d)return null;var fs=d.forms,b=null,score=-1;for(var i=0;i<fs.length;i++){
            var f=fs[i],s=0,t=(f.innerText||'').toLowerCase();if(t.indexOf('wireless mac address filter')>=0)s+=250;
            var sels=f.querySelectorAll('select');for(var k=0;k<sels.length;k++){var x=(sels[k].innerText||'').toLowerCase();if(x.indexOf('allow association')>=0&&x.indexOf('deny association')>=0)s+=500;}
            if(s>score){score=s;b=f;}
          }return score>=250?b:null;}
          function scan(w){try{var d=w.document,f=ff(d);if(f)return {d:d,f:f};for(var i=0;i<w.frames.length;i++){var z=scan(w.frames[i]);if(z)return z;}}catch(e){}return null;}
          var z=scan(window);if(!z)return 'NO_FORM';var f=z.f;
          var es=f.querySelectorAll('input[type=submit],input[type=button],button'),best=null,bs=-999;
          for(var j=0;j<es.length;j++){var tx=((es[j].value||es[j].innerText||'')+'').trim().toLowerCase(),sc=0;
            if(tx=='save'||tx.indexOf('save')>=0)sc+=300;if(tx.indexOf('apply')>=0)sc+=150;
            if(tx.indexOf('delete')>=0||tx.indexOf('reset')>=0||tx.indexOf('reboot')>=0||tx.indexOf('cancel')>=0)sc-=600;
            if(sc>bs){bs=sc;best=es[j];}
          }
          if(best&&bs>0){best.click();return 'CLICKED_SAVE';}
          return 'NO_SAVE';
        }catch(e){return 'NO_SAVE';}})();
    """.trimIndent()

    private fun readTrafficStatistics() {
        val js = """
            (function(){var out=[];
              function scan(w){try{var trs=w.document.querySelectorAll('tr');for(var i=0;i<trs.length;i++){var t=(trs[i].innerText||'').replace(/\s+/g,' ').trim();if(/bytes?|packets?|statistics/i.test(t)&&/\d/.test(t))out.push(t);}for(var f=0;f<w.frames.length;f++)scan(w.frames[f]);}catch(e){}}
              scan(window);return JSON.stringify(out.slice(0,80));
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = decodeJs(raw)
            prefs.edit().putString("last_statistics_rows", decoded).apply()
            succeed("Statistics واقعی از روتر خوانده و ثبت شد. اپ عددی را که firmware ارائه نکرده جعل نمی‌کند.")
        }
    }

    private fun probeGuestNetwork() {
        val js = """
            (function(){
              function scan(w){try{var d=w.document,t=(d.body?d.body.innerText:'').toLowerCase(),names=[];var es=d.querySelectorAll('input,select');for(var i=0;i<es.length;i++){var n=((es[i].name||'')+' '+(es[i].id||'')).toLowerCase();if(/band|rate|speed|upstream|downstream|guest|isolation/.test(n))names.push(n);}if(t.indexOf('guest')>=0||names.length)return {guest:t.indexOf('guest')>=0,fields:names.slice(0,100)};for(var f=0;f<w.frames.length;f++){var z=scan(w.frames[f]);if(z)return z;}}catch(e){}return null;}
              return JSON.stringify(scan(window)||{});
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = decodeJs(raw)
            prefs.edit().putString("guest_capability_probe", decoded).apply()
            succeed("Guest Network واقعی بررسی شد و فقط قابلیت‌های موجود در firmware ثبت شدند.")
        }
    }

    private fun loadPath(path: String, callback: () -> Unit) {
        val target = route(path)
        pendingTarget = target
        pendingAfterLoad = callback
        web.loadUrl(target)
    }

    private fun route(path: String): String {
        if (path == "/") return "$baseUrl/"
        return baseUrl + if (path.startsWith('/')) path else "/$path"
    }

    private fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

    private fun markBlocked(mac: String, blocked: Boolean) {
        val set = prefs.getStringSet("blocked_macs", emptySet())?.map(::normalizeMac)?.toMutableSet() ?: mutableSetOf()
        if (blocked) set.add(normalizeMac(mac)) else set.remove(normalizeMac(mac))
        prefs.edit().putStringSet("blocked_macs", set).apply()
    }

    private fun succeed(message: String) {
        if (finished) return
        finished = true
        prefs.edit().putString("router_last_message", message).apply()
        status.text = message
        handler.postDelayed({ finish() }, 1050)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        prefs.edit().putString("router_last_message", message).apply()
        status.text = message
        handler.postDelayed({ finish() }, 2200)
    }

    private fun normalizeMac(v: String): String = v.trim().replace('-', ':').uppercase(Locale.US)

    private fun decodeJs(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try { JSONArray("[$raw]").optString(0) }
        catch (_: Exception) { raw.trim('"').replace("\\\"", "\"").replace("\\n", "\n") }
    }
}
