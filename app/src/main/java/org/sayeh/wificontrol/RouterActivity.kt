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
import java.net.URI
import java.util.Locale

class RouterActivity : AppCompatActivity() {
    private data class Route(val url: String, val label: String)

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var action: String = "sync"
    private var targetMac: String = ""
    private var allowedMacs: List<String> = emptyList()
    private var loginAttempts = 0
    private var dispatched = false
    private var finished = false
    private var pageCallback: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)
        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        action = intent.getStringExtra("action") ?: "sync"
        targetMac = intent.getStringExtra("targetMac")?.uppercase(Locale.US).orEmpty()
        allowedMacs = intent.getStringExtra("allowedMacs")
            ?.split(',')
            ?.map { it.trim().uppercase(Locale.US) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()

        status = findViewById(R.id.routerStatus)
        web = findViewById(R.id.routerWeb)
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { finish() }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!finished) handler.postDelayed({ handleLoadedPage() }, 300)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !finished) {
                    val cb = pageCallback
                    pageCallback = null
                    if (cb != null) cb() else fail("روتر پاسخ نداد: ${error?.description ?: "خطای نامشخص"}")
                }
            }
        }

        status.text = "در حال اتصال امن به روتر…"
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
            val cb = pageCallback
            if (cb != null) {
                pageCallback = null
                cb()
            } else if (!dispatched) {
                dispatched = true
                status.text = "ورود واقعی موفق شد؛ موتور Native فعال است."
                handler.postDelayed({ dispatchAction() }, 220)
            }
        }
    }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        val js = """
            (function(){try{
              var p=document.querySelector('input[type=password]');
              var t=(document.body?document.body.innerText:'').toLowerCase();
              return !!p || location.href.toLowerCase().indexOf('login_security')>=0 || (t.indexOf('username')>=0&&t.indexOf('password')>=0&&t.indexOf('login')>=0);
            }catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun attemptAutoLogin() {
        if (user.isBlank() || pass.isBlank()) {
            fail("نام کاربری یا رمز ادمین خالی است.")
            return
        }
        if (loginAttempts >= 3) {
            fail("ورود خودکار موفق نشد. نام کاربری/رمز را بررسی کن؛ هیچ تنظیمی تغییر نکرد.")
            return
        }
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
              var f=p.form||u.form||document.forms[0];
              if(!f)return 'NO_FORM';
              var bs=f.querySelectorAll('input[type=submit],button[type=submit],input[type=button],button');
              for(var i=0;i<bs.length;i++){var s=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(s.indexOf('login')>=0){bs[i].click();return 'CLICKED';}}
              if(bs.length){bs[0].click();return 'CLICKED';}
              f.submit();return 'SUBMITTED';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) fail("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
            else handler.postDelayed({ handleLoadedPage() }, 1200)
        }
    }

    private fun dispatchAction() {
        when (action) {
            "sync" -> syncDevices()
            "calibrate" -> calibrateFirmware()
            "block", "unblock" -> {
                val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
                if (targetMac.isBlank()) fail("MAC هدف مشخص نیست.")
                else if (targetMac == owner) fail("تلفن مدیر محافظت‌شده است و هرگز قابل Block نیست.")
                else navigateWireless { prepareDeviceAction(action, targetMac) }
            }
            "antiqr_enable" -> {
                val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
                if (owner.isNullOrBlank() || owner !in allowedMacs) fail("فهرست ضد QR ایمن نیست؛ تلفن مدیر داخل Allow‑List نیست.")
                else navigateWireless { prepareAntiQrEnable() }
            }
            "antiqr_disable" -> navigateWireless { prepareFilterOff("antiqr_disable") }
            "filter_off" -> navigateWireless { prepareFilterOff("filter_off") }
            else -> fail("فرمان ناشناخته است؛ هیچ تنظیمی تغییر نکرد.")
        }
    }

    private fun syncDevices() {
        status.text = "در حال خواندن فقط جدول واقعی دستگاه‌های متصل…"
        extractClients { count ->
            if (count > 0) succeed("$count دستگاه آنلاین از جدول واقعی روتر تازه‌سازی شد.")
            else {
                findPageWithMarkers(
                    key = "status_route",
                    markers = listOf("current connected wireless clients"),
                    menuPath = listOf("Status", "Device Info"),
                    hints = listOf("status", "device", "info")
                ) {
                    extractClients { second ->
                        if (second > 0) succeed("$second دستگاه آنلاین از جدول واقعی روتر تازه‌سازی شد.")
                        else fail("جدول Current Connected Wireless Clients پیدا شد اما هیچ MAC قابل استخراج نبود.")
                    }
                }
            }
        }
    }

    private fun extractClients(callback: (Int) -> Unit) {
        val js = """
            (function(){
              function macs(s){var r=(s||'').match(/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/ig)||[];return r.map(function(x){return x.replace(/-/g,':').toUpperCase();});}
              function scan(w){
                try{
                  var d=w.document, all=(d.body?d.body.innerText:'');
                  var rm=''; var m=all.match(/MAC\s*Address\s*[:：]?\s*((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})/i);if(m)rm=m[1].replace(/-/g,':').toUpperCase();
                  var best=null, tables=d.querySelectorAll('table');
                  for(var i=0;i<tables.length;i++){
                    var t=tables[i]; if(t.querySelector('table'))continue;
                    var rows=t.querySelectorAll('tr'), found=[];
                    for(var j=0;j<rows.length;j++){var a=macs(rows[j].innerText);if(a.length)found.push(a[0]);}
                    if(!found.length)continue;
                    var anc=t, near='';for(var k=0;k<4&&anc;k++,anc=anc.parentElement)near+=(anc.innerText||'')+' ';
                    var score=found.length*20+(near.toLowerCase().indexOf('current connected wireless clients')>=0?200:0)-Math.min((t.innerText||'').length/100,40);
                    if(!best||score>best.score)best={score:score,macs:found};
                  }
                  if(best&&best.macs.length){return {router:rm,clients:best.macs};}
                  for(var f=0;f<w.frames.length;f++){var z=scan(w.frames[f]);if(z&&z.clients&&z.clients.length)return z;}
                  return {router:rm,clients:[]};
                }catch(e){return {router:'',clients:[]};}
              }
              return JSON.stringify(scan(window));
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            try {
                val obj = JSONObject(decodeJs(raw))
                val routerMac = obj.optString("router").uppercase(Locale.US)
                if (routerMac.isNotBlank()) prefs.edit().putString("router_mac", routerMac).apply()
                val arr = obj.optJSONArray("clients") ?: JSONArray()
                val online = linkedSetOf<String>()
                for (i in 0 until arr.length()) {
                    val mac = arr.optString(i).uppercase(Locale.US)
                    if (mac.matches(Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")) && mac != routerMac) online.add(mac)
                }
                val known = prefs.getStringSet("known_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
                known.addAll(online)
                prefs.edit().putStringSet("online_macs", online).putStringSet("known_macs", known).apply()
                callback(online.size)
            } catch (_: Exception) {
                callback(0)
            }
        }
    }

    private fun calibrateFirmware() {
        status.text = "در حال کشف مستقیم مسیر Wireless MAC Filter…"
        navigateWireless {
            inspectFilterState { state ->
                val capacity = state.optInt("capacity", 0)
                val route = web.url.orEmpty()
                prefs.edit().putString("wireless_route", route).apply()
                succeed("کالیبراسیون موفق شد. مسیر واقعی Wireless ذخیره شد${if (capacity > 0) " • ظرفیت فیلتر: $capacity MAC" else ""}.")
            }
        }
    }

    private fun navigateWireless(onFound: () -> Unit) {
        findPageWithMarkers(
            key = "wireless_route",
            markers = listOf("wireless mac address filter"),
            menuPath = listOf("Interface Setup", "Wireless"),
            hints = listOf("wireless", "wlan", "interface", "mac", "filter")
        ) { onFound() }
    }

    private fun findPageWithMarkers(
        key: String,
        markers: List<String>,
        menuPath: List<String>,
        hints: List<String>,
        onFound: () -> Unit
    ) {
        val saved = prefs.getString(key, null)
        if (!saved.isNullOrBlank() && isSafeRoute(saved)) {
            loadUrlThen(saved) {
                checkMarkers(markers) { ok ->
                    if (ok) {
                        prefs.edit().putString(key, web.url ?: saved).apply()
                        onFound()
                    } else {
                        prefs.edit().remove(key).apply()
                        followMenuPath(menuPath, 0, markers, key, hints, onFound)
                    }
                }
            }
        } else {
            checkMarkers(markers) { already ->
                if (already) {
                    prefs.edit().putString(key, web.url ?: baseUrl).apply()
                    onFound()
                } else {
                    followMenuPath(menuPath, 0, markers, key, hints, onFound)
                }
            }
        }
    }

    private fun followMenuPath(
        path: List<String>,
        index: Int,
        markers: List<String>,
        key: String,
        hints: List<String>,
        onFound: () -> Unit
    ) {
        checkMarkers(markers) { found ->
            if (found) {
                prefs.edit().putString(key, web.url ?: baseUrl).apply()
                onFound()
                return@checkMarkers
            }
            if (index >= path.size) {
                crawlForMarkers(markers, key, hints, onFound)
                return@checkMarkers
            }
            openMenuLabel(path[index]) { _ ->
                handler.postDelayed({ followMenuPath(path, index + 1, markers, key, hints, onFound) }, 350)
            }
        }
    }

    private fun openMenuLabel(label: String, callback: (Boolean) -> Unit) {
        collectRoutes { routes ->
            val best = routes
                .filter { isSafeRoute(it.url) }
                .maxByOrNull { routeScore(it, listOf(label)) }
            if (best != null && routeScore(best, listOf(label)) >= 70) {
                if (normalizeUrl(best.url) == normalizeUrl(web.url ?: "")) {
                    callback(true)
                } else {
                    loadUrlThen(best.url) { callback(true) }
                }
            } else {
                val q = JSONObject.quote(label.lowercase(Locale.US))
                val js = """
                    (function(){var want=$q,best=null;
                      function txt(e){return ((e.innerText||e.textContent||e.value||e.alt||e.title||'')+'').trim().replace(/\s+/g,' ').toLowerCase();}
                      function walk(w){try{var es=w.document.querySelectorAll('a,button,input,area,[onclick],[href],td,span,div,img');for(var i=0;i<es.length;i++){var e=es[i],t=txt(e),click=!!(e.onclick||(e.getAttribute&&e.getAttribute('href'))||/^(a|button|input|area)$/i.test(e.tagName||''));var s=(click&&t==want)?1000:(click&&t.indexOf(want)>=0&&t.length<want.length+35)?800:0;if(s&&(!best||s>best.s))best={e:e,s:s};}for(var j=0;j<w.frames.length;j++)walk(w.frames[j]);}catch(e){}}walk(window);if(!best)return false;var c=(best.e.closest?best.e.closest('a,button,[onclick],[href]'):null)||best.e;try{c.click();return true;}catch(e){try{c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));return true;}catch(x){return false;}}})();
                """.trimIndent()
                web.evaluateJavascript(js) { raw ->
                    val ok = raw == "true"
                    handler.postDelayed({ callback(ok) }, if (ok) 800 else 80)
                }
            }
        }
    }

    private fun crawlForMarkers(markers: List<String>, key: String, hints: List<String>, onFound: () -> Unit) {
        status.text = "مسیر مستقیم پیدا نشد؛ در حال اسکن امن endpointهای firmware…"
        val queue = ArrayDeque<Route>()
        val seen = mutableSetOf<String>()
        var probes = 0

        fun enqueue(routes: List<Route>) {
            routes.filter { isSafeRoute(it.url) }
                .sortedByDescending { routeScore(it, hints) }
                .forEach {
                    val n = normalizeUrl(it.url)
                    if (n.isNotBlank() && n !in seen && queue.none { q -> normalizeUrl(q.url) == n }) queue.addLast(it)
                }
        }

        fun next() {
            if (finished) return
            if (probes >= 45 || queue.isEmpty()) {
                fail("Wireless MAC Filter در endpointهای امن این firmware پیدا نشد. هیچ تنظیمی تغییر نکرد.")
                return
            }
            val r = queue.removeFirst()
            val n = normalizeUrl(r.url)
            if (n in seen) { next(); return }
            seen.add(n)
            probes++
            status.text = "کشف firmware… مرحله $probes"
            loadUrlThen(r.url) {
                checkMarkers(markers) { ok ->
                    if (ok) {
                        prefs.edit().putString(key, web.url ?: r.url).apply()
                        onFound()
                    } else {
                        collectRoutes { more -> enqueue(more); next() }
                    }
                }
            }
        }

        collectRoutes { routes ->
            enqueue(routes)
            if (queue.isEmpty()) fail("هیچ endpoint امنی برای کشف firmware پیدا نشد.") else next()
        }
    }

    private fun collectRoutes(callback: (List<Route>) -> Unit) {
        val js = """
            (function(){
              var out=[],seen={};
              function add(u,l){try{if(!u)return;var a=new URL(u,location.href).href;if(a.indexOf(location.origin)!==0)return;if(seen[a+'|'+l])return;seen[a+'|'+l]=1;out.push({url:a,label:(l||'').replace(/\s+/g,' ').trim()});}catch(e){}}
              function scan(w,d){if(d>8)return;try{var doc=w.document;add(w.location.href,doc.title||'page');var es=doc.querySelectorAll('a,area,frame,iframe,form,[href],[src],[onclick]');for(var i=0;i<es.length;i++){var e=es[i];var lab=((e.innerText||e.textContent||e.value||e.alt||e.title||'')+'').replace(/\s+/g,' ').trim();var vals=[e.getAttribute&&e.getAttribute('href'),e.getAttribute&&e.getAttribute('src'),e.getAttribute&&e.getAttribute('action')];for(var v=0;v<vals.length;v++)if(vals[v]&&!/^javascript:/i.test(vals[v]))add(vals[v],lab);var oc=(e.getAttribute&&e.getAttribute('onclick'))||'';var m=oc.match(/[A-Za-z0-9_./?=&%:-]+\.(?:html?|asp|cgi)(?:\?[^'\"\s<>]*)?/ig)||[];for(var z=0;z<m.length;z++)add(m[z],lab+' '+oc);}var html=doc.documentElement?doc.documentElement.outerHTML:'';var mm=html.match(/[A-Za-z0-9_./?=&%:-]+\.(?:html?|asp|cgi)(?:\?[^'\"\s<>]*)?/ig)||[];for(var q=0;q<mm.length&&q<120;q++)add(mm[q],'source');for(var f=0;f<w.frames.length;f++)scan(w.frames[f],d+1);}catch(e){}}
              scan(window,0);return JSON.stringify(out.slice(0,240));
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val result = mutableListOf<Route>()
            try {
                val arr = JSONArray(decodeJs(raw))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url")
                    if (u.isNotBlank()) result.add(Route(u, o.optString("label")))
                }
            } catch (_: Exception) {}
            callback(result.distinctBy { normalizeUrl(it.url) + "|" + it.label })
        }
    }

    private fun routeScore(route: Route, hints: List<String>): Int {
        val label = route.label.lowercase(Locale.US)
        val url = route.url.lowercase(Locale.US)
        var score = 0
        hints.forEach { raw ->
            val h = raw.lowercase(Locale.US)
            val compact = h.replace(" ", "")
            if (label == h) score += 120
            else if (label.contains(h)) score += 90
            if (url.contains(h.replace(" ", "_"))) score += 70
            if (url.replace("_", "").replace("-", "").contains(compact)) score += 55
        }
        if (url.endsWith(".html") || url.endsWith(".htm")) score += 5
        return score
    }

    private fun isSafeRoute(url: String): Boolean {
        return try {
            val u = URI(url)
            val b = URI(baseUrl)
            if (!u.host.equals(b.host, true)) return false
            val s = url.lowercase(Locale.US)
            val bad = listOf("logout", "reboot", "restart", "factory", "reset", "restore", "romfile", "upload", "firmware", "delete", "erase")
            bad.none { s.contains(it) }
        } catch (_: Exception) { false }
    }

    private fun normalizeUrl(url: String): String = try {
        val u = URI(url)
        URI(u.scheme?.lowercase(), u.userInfo, u.host?.lowercase(), u.port, u.path, u.query, null).toString().trimEnd('/')
    } catch (_: Exception) { url.trimEnd('/') }

    private fun checkMarkers(markers: List<String>, callback: (Boolean) -> Unit) {
        val q = markers.joinToString(",") { JSONObject.quote(it.lowercase(Locale.US)) }
        val js = """
            (function(){var ms=[$q];function scan(w){try{var t=(w.document.body?w.document.body.innerText:'').toLowerCase();for(var i=0;i<ms.length;i++)if(t.indexOf(ms[i])>=0)return true;for(var j=0;j<w.frames.length;j++)if(scan(w.frames[j]))return true;}catch(e){}return false;}return scan(window);})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun loadUrlThen(url: String, callback: () -> Unit) {
        if (finished) return
        pageCallback = callback
        web.loadUrl(url)
    }

    private fun prepareDeviceAction(mode: String, mac: String) {
        status.text = if (mode == "block") "در حال آماده‌سازی Block واقعی…" else "در حال آماده‌سازی Unblock واقعی…"
        val modeQ = JSONObject.quote(mode)
        val macQ = JSONObject.quote(mac)
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return 'NO_FILTER';var f=findForm(d);if(!f)return 'NO_FORM';var sel=findAction(f);if(!sel)return 'NO_ACTION_SELECT';
            if($modeQ=='block')setSelect(sel,'deny association');else if(selected(sel).indexOf('deny association')<0)return 'ALREADY_ALLOWED';
            if(!setEnabled(f,true))return 'NO_ENABLE_CONTROL';var ins=findMacInputs(f);if(ins.length==0)return 'NO_MAC_INPUTS';
            if($modeQ=='block'){
              for(var i=0;i<ins.length;i++)if(norm(ins[i].value)==norm($macQ))return 'ALREADY_PRESENT';
              var slot=null;for(var j=0;j<ins.length;j++){var v=norm(ins[j].value);if(!slot&&(v==''||v=='00:00:00:00:00:00'))slot=ins[j];}
              if(!slot)return 'NO_EMPTY_SLOT';slot.value=$macQ;slot.dispatchEvent(new Event('input',{bubbles:true}));slot.dispatchEvent(new Event('change',{bubbles:true}));return 'READY_BLOCK';
            }
            for(var k=0;k<ins.length;k++){if(norm(ins[k].value)==norm($macQ)){ins[k].value='00:00:00:00:00:00';ins[k].dispatchEvent(new Event('input',{bubbles:true}));ins[k].dispatchEvent(new Event('change',{bubbles:true}));return 'READY_UNBLOCK';}}
            return 'TARGET_NOT_FOUND';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            when (val r = decodeJs(raw)) {
                "READY_BLOCK" -> confirmSaveAndVerify("قطع واقعی دستگاه", "قانون Deny برای $mac آماده است. SAVE واقعی اجرا شود؟", "block", mac)
                "READY_UNBLOCK" -> confirmSaveAndVerify("وصل‌کردن دوباره", "MAC $mac از Deny حذف شده است. SAVE واقعی اجرا شود؟", "unblock", mac)
                "ALREADY_PRESENT" -> verifyDeviceAction("block", mac)
                "TARGET_NOT_FOUND", "ALREADY_ALLOWED" -> verifyDeviceAction("unblock", mac)
                else -> fail("فرم Wireless پیدا شد اما فرمان آماده نشد: $r")
            }
        }
    }

    private fun prepareAntiQrEnable() {
        if (allowedMacs.isEmpty()) { fail("Allow‑List خالی است."); return }
        val arr = allowedMacs.joinToString(",") { JSONObject.quote(it) }
        val js = filterPrelude() + """
            var wanted=[$arr];var d=findDoc(window);if(!d)return 'NO_FILTER';var f=findForm(d);if(!f)return 'NO_FORM';var sel=findAction(f);if(!sel)return 'NO_ACTION_SELECT';var ins=findMacInputs(f);if(ins.length<wanted.length)return 'NOT_ENOUGH_SLOTS:'+ins.length;if(!setEnabled(f,true))return 'NO_ENABLE_CONTROL';setSelect(sel,'allow association');
            for(var i=0;i<ins.length;i++){ins[i].value=(i<wanted.length?wanted[i]:'00:00:00:00:00:00');ins[i].dispatchEvent(new Event('input',{bubbles:true}));ins[i].dispatchEvent(new Event('change',{bubbles:true}));}
            return 'READY_ALLOW:'+ins.length;})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("READY_ALLOW")) {
                confirmSaveAndVerify("فعال‌سازی ضد QR", "Allow Association با ${allowedMacs.size} دستگاه آماده است. SAVE واقعی اجرا شود؟", "antiqr_enable", "")
            } else if (r.startsWith("NOT_ENOUGH_SLOTS")) {
                val capacity = r.substringAfter(':', "0")
                fail("ظرفیت واقعی MAC Filter فقط $capacity خانه است؛ تعداد دستگاه‌های مجاز را کم کن.")
            } else fail("ضد QR آماده نشد: $r")
        }
    }

    private fun prepareFilterOff(mode: String) {
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return 'NO_FILTER';var f=findForm(d);if(!f)return 'NO_FORM';if(!setEnabled(f,false))return 'NO_ENABLE_CONTROL';return 'READY_OFF';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r == "READY_OFF") {
                val title = if (mode == "filter_off") "بازگردانی اضطراری" else "خاموش‌کردن ضد QR"
                confirmSaveAndVerify(title, "MAC Filter روی Deactivated آماده شده است. SAVE واقعی اجرا شود؟", mode, "")
            } else fail("خاموش‌کردن فیلتر آماده نشد: $r")
        }
    }

    private fun confirmSaveAndVerify(title: String, message: String, verifyMode: String, mac: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("SAVE و بررسی") { _, _ ->
                status.text = "در حال ذخیره روی روتر…"
                web.evaluateJavascript(saveScript()) { raw ->
                    val r = decodeJs(raw)
                    if (r.startsWith("NO_")) {
                        fail("دکمه SAVE واقعی پیدا نشد؛ هیچ موفقیتی ثبت نشد.")
                    } else {
                        handler.postDelayed({
                            val route = prefs.getString("wireless_route", null)
                            if (!route.isNullOrBlank() && isSafeRoute(route)) {
                                loadUrlThen(route) { handler.postDelayed({ verifyAfterSave(verifyMode, mac) }, 300) }
                            } else {
                                verifyAfterSave(verifyMode, mac)
                            }
                        }, 1200)
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun verifyAfterSave(mode: String, mac: String) {
        inspectFilterState { state ->
            val enabled = state.optBoolean("enabled", false)
            val actionText = state.optString("action").lowercase(Locale.US)
            val arr = state.optJSONArray("macs") ?: JSONArray()
            val macSet = mutableSetOf<String>()
            for (i in 0 until arr.length()) macSet.add(arr.optString(i).uppercase(Locale.US))

            when (mode) {
                "block" -> {
                    val ok = enabled && actionText.contains("deny association") && mac.uppercase(Locale.US) in macSet
                    if (ok) {
                        markBlocked(mac, true)
                        succeed("Block واقعی تأیید شد: $mac در Deny Association ذخیره شده است.")
                    } else fail("SAVE انجام شد اما Block در بررسی مجدد تأیید نشد؛ اپ وضعیت را موفق ثبت نکرد.")
                }
                "unblock" -> {
                    val ok = !enabled || !actionText.contains("deny association") || mac.uppercase(Locale.US) !in macSet
                    if (ok) {
                        markBlocked(mac, false)
                        succeed("Unblock واقعی تأیید شد: $mac دیگر در Deny فعال نیست.")
                    } else fail("Unblock بعد از SAVE تأیید نشد؛ وضعیت اپ تغییر نکرد.")
                }
                "antiqr_enable" -> {
                    val wanted = allowedMacs.map { it.uppercase(Locale.US) }.toSet()
                    val ok = enabled && actionText.contains("allow association") && macSet.containsAll(wanted)
                    if (ok) {
                        prefs.edit().putBoolean("anti_qr_active", true).putStringSet("allowed_macs", wanted).apply()
                        succeed("ضد QR واقعی فعال و Verify شد؛ فقط دستگاه‌های Allow‑List مجازند.")
                    } else fail("Allow‑List بعد از SAVE کامل تأیید نشد؛ اپ ضد QR را فعال ثبت نکرد.")
                }
                "antiqr_disable", "filter_off" -> {
                    if (!enabled) {
                        prefs.edit().putBoolean("anti_qr_active", false).putStringSet("blocked_macs", emptySet()).apply()
                        succeed(if (mode == "filter_off") "بازگردانی اضطراری تأیید شد؛ MAC Filter خاموش است." else "ضد QR خاموش و Verify شد.")
                    } else fail("MAC Filter هنوز Active است؛ خاموش‌شدن تأیید نشد.")
                }
            }
        }
    }

    private fun verifyDeviceAction(mode: String, mac: String) {
        inspectFilterState { state ->
            val enabled = state.optBoolean("enabled", false)
            val actionText = state.optString("action").lowercase(Locale.US)
            val arr = state.optJSONArray("macs") ?: JSONArray()
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.optString(i).uppercase(Locale.US))
            val blocked = enabled && actionText.contains("deny association") && mac.uppercase(Locale.US) in set
            if (mode == "block" && blocked) {
                markBlocked(mac, true); succeed("Block واقعی از قبل روی روتر فعال بود و Verify شد.")
            } else if (mode == "unblock" && !blocked) {
                markBlocked(mac, false); succeed("این دستگاه در Deny فعال نیست؛ اتصال آن مجاز است.")
            } else fail("وضعیت واقعی روتر با فرمان درخواستی همخوان نیست.")
        }
    }

    private fun inspectFilterState(callback: (JSONObject) -> Unit) {
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return JSON.stringify({ok:false});var f=findForm(d);if(!f)return JSON.stringify({ok:false});var sel=findAction(f);var ins=findMacInputs(f);var ms=[];for(var i=0;i<ins.length;i++){var n=norm(ins[i].value);if(n&&n!='00:00:00:00:00:00')ms.push(n);}return JSON.stringify({ok:true,enabled:isEnabled(f),action:sel?selected(sel):'',capacity:ins.length,macs:ms});})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            try { callback(JSONObject(decodeJs(raw))) } catch (_: Exception) { callback(JSONObject()) }
        }
    }

    private fun filterPrelude(): String = """
        (function(){
          function norm(v){return ((v||'')+'').trim().replace(/-/g,':').toUpperCase();}
          function findDoc(w){try{var d=w.document,t=(d.body?d.body.innerText:'').toLowerCase();if(t.indexOf('wireless mac address filter')>=0)return d;for(var i=0;i<w.frames.length;i++){var z=findDoc(w.frames[i]);if(z)return z;}}catch(e){}return null;}
          function findForm(d){var fs=d.forms,b=null,bs=-1;for(var i=0;i<fs.length;i++){var f=fs[i],t=(f.innerText||'').toLowerCase(),s=0;if(t.indexOf('wireless mac address filter')>=0)s+=200;var sels=f.querySelectorAll('select');for(var j=0;j<sels.length;j++){var o=(sels[j].innerText||'').toLowerCase();if(o.indexOf('allow association')>=0||o.indexOf('deny association')>=0)s+=150;}var ins=f.querySelectorAll('input');for(var k=0;k<ins.length;k++){var n=((ins[k].name||'')+' '+(ins[k].id||'')).toLowerCase();if(n.indexOf('mac')>=0)s+=5;}if(s>bs){bs=s;b=f;}}return b||d.forms[0]||null;}
          function findAction(f){var ss=f.querySelectorAll('select');for(var i=0;i<ss.length;i++){var t=(ss[i].innerText||'').toLowerCase();if(t.indexOf('allow association')>=0||t.indexOf('deny association')>=0)return ss[i];}return null;}
          function selected(s){try{return ((s.options[s.selectedIndex].text||s.value||'')+'').toLowerCase();}catch(e){return '';}}
          function setSelect(s,want){for(var i=0;i<s.options.length;i++){var t=(s.options[i].text||'').toLowerCase();if(t.indexOf(want)>=0){s.selectedIndex=i;s.value=s.options[i].value;s.dispatchEvent(new Event('change',{bubbles:true}));return true;}}return false;}
          function findMacInputs(f){var all=f.querySelectorAll('input[type=text],input:not([type])'),out=[];for(var i=0;i<all.length;i++){var e=all[i],n=((e.name||'')+' '+(e.id||'')).toLowerCase(),v=(e.value||'');var row=e.closest?e.closest('tr'):e.parentElement;var rt=(row&&row.innerText?row.innerText:'').toLowerCase();if(n.indexOf('mac')>=0||rt.indexOf('mac address')>=0||/^(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(v))out.push(e);}return out;}
          function radioGroups(f){var rs=f.querySelectorAll('input[type=radio]'),g={};for(var i=0;i<rs.length;i++){var n=rs[i].name||('__'+i);(g[n]||(g[n]=[])).push(rs[i]);}return g;}
          function groupText(a){if(!a.length)return '';var r=a[0].closest?a[0].closest('tr'):a[0].parentElement;return (r&&r.innerText?r.innerText:'').toLowerCase();}
          function filterGroup(f){var gs=radioGroups(f);for(var k in gs){var t=groupText(gs[k]);if(t.indexOf('active')>=0&&(t.indexOf('deactivated')>=0||t.indexOf('activated')>=0))return gs[k];}return null;}
          function setEnabled(f,on){var g=filterGroup(f);if(!g||g.length<2)return false;var pick=null;for(var i=0;i<g.length;i++){var p=g[i].parentElement,tx=((p&&p.innerText)||g[i].value||'').toLowerCase();if(on&&tx.indexOf('deactivated')<0&&(tx.indexOf('active')>=0||tx.indexOf('activated')>=0))pick=g[i];if(!on&&tx.indexOf('deactivated')>=0)pick=g[i];}if(!pick)pick=on?g[0]:g[g.length-1];pick.checked=true;try{pick.click();}catch(e){}pick.dispatchEvent(new Event('change',{bubbles:true}));return true;}
          function isEnabled(f){var g=filterGroup(f);if(!g||g.length<2)return false;for(var i=0;i<g.length;i++){if(g[i].checked){var p=g[i].parentElement,tx=((p&&p.innerText)||g[i].value||'').toLowerCase();if(tx.indexOf('deactivated')>=0)return false;if(i==g.length-1&&tx.indexOf('active')<0&&tx.indexOf('activated')<0)return false;return true;}}return false;}
    """.trimIndent()

    private fun saveScript(): String = """
        (function(){
          function findDoc(w){try{var d=w.document,t=(d.body?d.body.innerText:'').toLowerCase();if(t.indexOf('wireless mac address filter')>=0)return d;for(var i=0;i<w.frames.length;i++){var z=findDoc(w.frames[i]);if(z)return z;}}catch(e){}return null;}
          var d=findDoc(window);if(!d)return 'NO_FILTER';var fs=d.forms,b=null,bs=-1;for(var i=0;i<fs.length;i++){var t=(fs[i].innerText||'').toLowerCase(),s=(t.indexOf('wireless mac address filter')>=0?200:0);if(s>bs){bs=s;b=fs[i];}}var f=b||d.forms[0];if(!f)return 'NO_FORM';var es=f.querySelectorAll('input[type=submit],input[type=button],button'),best=null,score=-999;for(var j=0;j<es.length;j++){var tx=((es[j].value||es[j].innerText||'')+'').trim().toLowerCase(),sc=0;if(tx=='save'||tx.indexOf('save')>=0)sc+=100;if(tx.indexOf('apply')>=0)sc+=90;if(tx.indexOf('submit')>=0)sc+=60;if(tx.indexOf('delete')>=0||tx.indexOf('reset')>=0||tx.indexOf('reboot')>=0)sc-=200;if(sc>score){score=sc;best=es[j];}}if(best&&score>0){best.click();return 'CLICKED_SAVE';}try{f.submit();return 'SUBMITTED_FORM';}catch(e){return 'NO_SAVE';}})();
    """.trimIndent()

    private fun markBlocked(mac: String, blocked: Boolean) {
        val set = prefs.getStringSet("blocked_macs", emptySet())?.map { it.uppercase(Locale.US) }?.toMutableSet() ?: mutableSetOf()
        if (blocked) set.add(mac.uppercase(Locale.US)) else set.remove(mac.uppercase(Locale.US))
        prefs.edit().putStringSet("blocked_macs", set).apply()
    }

    private fun succeed(message: String) {
        if (finished) return
        finished = true
        prefs.edit().putString("router_last_message", message).apply()
        status.text = message
        handler.postDelayed({ finish() }, 900)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        prefs.edit().putString("router_last_message", message).apply()
        status.text = message
        handler.postDelayed({ finish() }, 1800)
    }

    private fun decodeJs(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try { JSONArray("[$raw]").optString(0) } catch (_: Exception) { raw.trim('"').replace("\\\"", "\"").replace("\\n", "\n") }
    }
}
