package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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

class RouterActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }

    private var autoScan = false
    private var pendingAction: String? = null
    private var targetMac: String? = null
    private var allowedMacs: List<String> = emptyList()
    private var loginAttempts = 0
    private var dispatched = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)

        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        autoScan = intent.getBooleanExtra("autoScan", false)
        pendingAction = intent.getStringExtra("action")
        targetMac = intent.getStringExtra("targetMac")?.uppercase(Locale.US)
        allowedMacs = intent.getStringExtra("allowedMacs")
            ?.split(',')
            ?.map { it.trim().uppercase(Locale.US) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()

        web = findViewById(R.id.routerWeb)
        status = findViewById(R.id.routerStatus)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(RouterBridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                status.text = "پنل واقعی روتر • ${url ?: baseUrl}"
                handler.postDelayed({ inspectLoginAndDispatch() }, 450)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) status.text = "صفحه روتر پاسخ نداد: ${error?.description ?: "خطای نامشخص"}"
            }
        }

        findViewById<Button>(R.id.backBtn).setOnClickListener { if (web.canGoBack()) web.goBack() else finish() }
        findViewById<Button>(R.id.homeBtn).setOnClickListener {
            dispatched = false
            pendingAction = null
            autoScan = false
            web.loadUrl(baseUrl)
        }
        findViewById<Button>(R.id.statusBtn).setOnClickListener { startDeviceSync() }
        findViewById<Button>(R.id.syncBtn).setOnClickListener { startDeviceSync() }
        findViewById<Button>(R.id.macFilterBtn).setOnClickListener { navigateWireless { status.text = "صفحه واقعی Wireless MAC Filter باز شد." } }

        web.loadUrl(intent.getStringExtra("startUrl") ?: baseUrl)
    }

    private fun inspectLoginAndDispatch() {
        val js = """
            (function(){try{var p=document.querySelector('input[type=password]');var t=(document.body?document.body.innerText:'').toLowerCase();return (!!p||location.href.toLowerCase().indexOf('login_security')>=0||(t.indexOf('username')>=0&&t.indexOf('password')>=0));}catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (raw == "true") attemptAutoLogin()
            else if (!dispatched) {
                dispatched = true
                loginAttempts = 0
                status.text = "ورود واقعی به روتر موفق شد."
                handler.postDelayed({ dispatchRequestedAction() }, 350)
            }
        }
    }

    private fun attemptAutoLogin() {
        if (user.isBlank() || pass.isBlank()) { status.text = "نام کاربری/رمز در اپ وارد نشده است."; return }
        if (loginAttempts >= 3) { status.text = "ورود خودکار موفق نشد. اطلاعات ورود را بررسی کن؛ هیچ تنظیمی تغییر نکرد."; return }
        loginAttempts++
        status.text = "در حال ورود خودکار به فرم واقعی TP-Link…"
        val uq = JSONObject.quote(user)
        val pq = JSONObject.quote(pass)
        val js = """
            (function(){try{var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');var p=document.querySelector('input[type=password],input[name*=pass i],input[id*=pass i]');if(!u||!p)return 'NO_FORM';u.value=$uq;p.value=$pq;u.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('input',{bubbles:true}));u.dispatchEvent(new Event('change',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));var f=p.form||u.form||document.forms[0];if(f){var s=f.querySelector('input[type=submit],button[type=submit],input[type=button],button');if(s){s.click();return 'CLICKED';}f.submit();return 'SUBMITTED';}var b=document.querySelector('input[type=submit],button,input[type=button]');if(b){b.click();return 'CLICKED';}return 'FILLED';}catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJsString(raw)
            if (r.startsWith("NO_FORM") || r.startsWith("ERR")) status.text = "فرم ورود خودکار پیدا نشد؛ می‌توانی داخل همین پنل دستی وارد شوی."
            else handler.postDelayed({ inspectLoginAndDispatch() }, 1400)
        }
    }

    private fun dispatchRequestedAction() {
        when {
            autoScan -> { autoScan = false; startDeviceSync() }
            pendingAction == "deep_map" -> deepFirmwareMap()
            pendingAction == "nav_wireless" -> navigateWireless { status.text = "Interface Setup → Wireless واقعی باز شد. تنظیمات Wi‑Fi، WPS و MAC Filter در همین صفحه است." }
            pendingAction == "nav_guest" -> navigateSection("Interface Setup", "Guest Network", listOf("Guest Network Bandwidth Control", "Guest Network Isolation", "Allow Guests")) { status.text = "Guest Network واقعی باز شد؛ Bandwidth Control و Isolation همین‌جا قابل تنظیم است." }
            pendingAction == "nav_filter" -> navigateSection("Access Management", "Filter", listOf("Filter Type Selection", "IP/MAC Filter", "URL Filter")) { status.text = "Access Management → Filter واقعی باز شد." }
            pendingAction == "nav_stats" -> navigateSection("Status", "Statistics", listOf("Statistics Table", "Transmit total Bytes", "Rx Frames Count")) { status.text = "Statistics واقعی باز شد؛ در حال استخراج شمارنده‌ها…"; handler.postDelayed({ capturePanelSnapshot() }, 700) }
            pendingAction == "block" || pendingAction == "unblock" -> navigateWireless { prepareMacAction(pendingAction.orEmpty(), targetMac.orEmpty()) }
            pendingAction == "antiqr_enable" -> navigateWireless { prepareAntiQrEnable() }
            pendingAction == "antiqr_disable" -> navigateWireless { prepareAntiQrDisable() }
            else -> status.text = "پنل کامل واقعی روتر باز است."
        }
    }

    private fun startDeviceSync() {
        status.text = "در حال خواندن دستگاه‌های متصل از Status واقعی…"
        capturePanelSnapshot()
        navigateSection("Status", "Device Info", listOf("Current Connected Wireless Clients")) {
            handler.postDelayed({ capturePanelSnapshot() }, 500)
            handler.postDelayed({ capturePanelSnapshot() }, 1500)
        }
    }

    private fun navigateWireless(onFound: () -> Unit) = navigateSection("Interface Setup", "Wireless", listOf("Wireless MAC Address Filter", "Access Point Settings", "WPS Settings"), onFound)

    private fun navigateSection(primary: String, secondary: String, markers: List<String>, onFound: () -> Unit) {
        var attempt = 0
        val sequence = listOf(primary, secondary, secondary, primary, secondary, secondary)
        fun step() {
            if (attempt > sequence.lastIndex) { status.text = "$primary → $secondary خودکار باز نشد. «کشف عمیق Firmware» را اجرا کن؛ پنل اصلی همچنان واقعی و قابل استفاده است."; return }
            checkAnyMarker(markers) { found ->
                if (found) { onFound(); return@checkAnyMarker }
                val label = sequence[attempt++]
                status.text = "در حال یافتن $primary → $secondary…"
                web.evaluateJavascript(smartClickJs(listOf(label))) { handler.postDelayed({ step() }, 900) }
            }
        }
        step()
    }

    private fun checkAnyMarker(markers: List<String>, callback: (Boolean) -> Unit) {
        val q = markers.joinToString(",") { JSONObject.quote(it.lowercase(Locale.US)) }
        val js = """
            (function(){var ms=[$q];function scan(w){try{var t=(w.document.body?w.document.body.innerText:'').toLowerCase();for(var i=0;i<ms.length;i++){if(t.indexOf(ms[i])>=0)return true;}for(var j=0;j<w.frames.length;j++){if(scan(w.frames[j]))return true;}}catch(e){}return false;}return scan(window);})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun smartClickJs(labels: List<String>): String {
        val array = labels.joinToString(",") { JSONObject.quote(it.lowercase(Locale.US)) }
        return """
            (function(){var labels=[$array];function norm(e){return (((e.innerText||e.textContent||e.value||e.alt||e.title||'')+' '+((e.getAttribute&&e.getAttribute('href'))||'')+' '+((e.getAttribute&&e.getAttribute('onclick'))||''))).trim().toLowerCase();}function walk(w){try{var d=w.document;var els=d.querySelectorAll('a,button,input,area,[onclick],[href],td,span,div,img');for(var i=0;i<els.length;i++){var e=els[i],s=norm(e);for(var j=0;j<labels.length;j++){if(s==labels[j]||s.indexOf(labels[j])>=0){var c=(e.closest?e.closest('a,button,[onclick]'):null)||e;try{c.click();}catch(x){try{c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));}catch(y){}}return true;}}}for(var k=0;k<w.frames.length;k++){if(walk(w.frames[k]))return true;}}catch(e){}return false;}return walk(window);})();
        """.trimIndent()
    }

    private fun capturePanelSnapshot() {
        val js = """
            (function(){var parts=[];function grab(w,d){if(d>10)return;try{var x=w.document;if(x&&x.documentElement){parts.push((x.body?x.body.innerText:'')+'\n'+(x.documentElement.outerHTML||''));}for(var i=0;i<w.frames.length;i++){try{grab(w.frames[i],d+1);}catch(e){}}}catch(e){}}grab(window,0);AndroidBridge.receiveSnapshot(parts.join('\n---FRAME---\n'));return 'SENT';})();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun deepFirmwareMap() {
        status.text = "در حال نقشه‌برداری عمیق Frameها، لینک‌ها و Formهای firmware…"
        val js = """
            (function(){var out=[];function add(x){if(x&&out.length<300)out.push(x);}function scan(w,d){if(d>10)return;try{var x=w.document;add('PAGE|'+w.location.href+'|'+(x.title||''));var fs=x.querySelectorAll('form');for(var f=0;f<fs.length;f++)add('FORM|'+(fs[f].name||fs[f].id||'')+'|'+(fs[f].action||'')+'|'+(fs[f].method||''));var es=x.querySelectorAll('a,button,input,area,[onclick],[href],img,frame,iframe');for(var i=0;i<es.length;i++){var e=es[i];var text=((e.innerText||e.textContent||e.value||e.alt||e.title||'')+'').trim().replace(/\s+/g,' ');var href=(e.getAttribute&&e.getAttribute('href'))||e.src||'';var oc=(e.getAttribute&&e.getAttribute('onclick'))||'';if(text||href||oc)add('ELEM|'+text+'|'+href+'|'+oc);}for(var j=0;j<w.frames.length;j++){try{scan(w.frames[j],d+1);}catch(e){}}}catch(e){}}scan(window,0);return JSON.stringify(out);})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = decodeJsString(raw)
            try {
                val arr = JSONArray(decoded)
                val lines = mutableListOf<String>()
                for (i in 0 until arr.length()) lines.add(arr.optString(i))
                val interesting = lines.filter { val l=it.lowercase(Locale.US); l.contains("wireless")||l.contains("guest")||l.contains("filter")||l.contains("statistics")||l.contains("dhcp")||l.contains("maintenance")||l.contains("firmware") }.take(30)
                prefs.edit().putString("firmware_map", lines.joinToString("\n").take(16000)).apply()
                val msg = "Firmware map: ${lines.size} مسیر/کنترل کشف شد؛ ${interesting.size} مورد مهم.\n" + interesting.take(8).joinToString("\n").take(1200)
                prefs.edit().putString("router_last_message", msg).apply()
                status.text = msg
            } catch (_: Exception) { status.text = "نقشه‌برداری اجرا شد ولی پاسخ firmware قابل تجزیه نبود." }
        }
    }

    private fun prepareMacAction(action: String, mac: String) {
        if (mac.isBlank()) { status.text = "MAC هدف مشخص نیست."; return }
        val mq = JSONObject.quote(mac)
        val mode = JSONObject.quote(action)
        val js = wirelessScriptPrelude() + """
            var d=findWirelessDoc(window);if(!d)return 'NO_FILTER';var h=findHeading(d);if(!h)return 'NO_HEADING';var a=findActionSelect(d,h);if(!a)return 'NO_ACTION';if($mode=='block')setOption(a,'deny association');else if(selectedText(a).indexOf('deny association')<0)return 'UNBLOCK_NOT_DENY';setActive(d,h,true);var ins=findMacInputs(d,h);if(ins.length==0)return 'NO_MAC_INPUTS';if($mode=='block'){for(var i=0;i<ins.length;i++)if(normMac(ins[i].value)==normMac($mq))return 'ALREADY_PRESENT';var s=null;for(var j=0;j<ins.length;j++){var v=normMac(ins[j].value);if(!s&&(v==''||v=='00:00:00:00:00:00'))s=ins[j];}if(!s)return 'NO_EMPTY_SLOT';s.value=$mq;s.dispatchEvent(new Event('change',{bubbles:true}));return 'READY_BLOCK';}else{for(var k=0;k<ins.length;k++){if(normMac(ins[k].value)==normMac($mq)){ins[k].value='00:00:00:00:00:00';ins[k].dispatchEvent(new Event('change',{bubbles:true}));return 'READY_UNBLOCK';}}return 'TARGET_NOT_FOUND';}
        """.trimIndent() + "})();"
        web.evaluateJavascript(js) { raw ->
            when (val r=decodeJsString(raw)) {
                "READY_BLOCK" -> confirmSave("مسدودسازی واقعی", "MAC $mac در Deny Association آماده شد. با تأیید، SAVE واقعی روتر اجرا می‌شود.") { markBlocked(mac,true) }
                "READY_UNBLOCK" -> confirmSave("رفع مسدودی واقعی", "MAC $mac از Deny Association آماده حذف شد. با تأیید، SAVE واقعی روتر اجرا می‌شود.") { markBlocked(mac,false) }
                "ALREADY_PRESENT" -> { markBlocked(mac,true); status.text="این MAC از قبل در Deny Association روتر وجود دارد." }
                "TARGET_NOT_FOUND" -> { markBlocked(mac,false); status.text="این MAC در Deny Association پیدا نشد؛ در وضعیت آزاد ثبت شد." }
                else -> status.text = "فرمان MAC آماده نشد: $r. هیچ SAVE اجرا نشد."
            }
        }
    }

    private fun prepareAntiQrEnable() {
        if (allowedMacs.isEmpty()) { status.text="فهرست دستگاه‌های مجاز خالی است؛ ضد QR اجرا نشد."; return }
        val arr = JSONArray(allowedMacs).toString()
        val js = wirelessScriptPrelude() + """
            var allowed=$arr;var d=findWirelessDoc(window);if(!d)return 'NO_FILTER';var h=findHeading(d);if(!h)return 'NO_HEADING';var a=findActionSelect(d,h);if(!a)return 'NO_ACTION';setOption(a,'allow association');setActive(d,h,true);var ins=findMacInputs(d,h);if(ins.length<allowed.length)return 'TOO_MANY:'+ins.length;for(var i=0;i<ins.length;i++){ins[i].value=(i<allowed.length?allowed[i]:'00:00:00:00:00:00');ins[i].dispatchEvent(new Event('change',{bubbles:true}));}return 'READY_ANTIQR:'+ins.length;
        """.trimIndent() + "})();"
        web.evaluateJavascript(js) { raw ->
            val r=decodeJsString(raw)
            if (r.startsWith("READY_ANTIQR")) confirmSave("فعال‌سازی ضد QR واقعی", "Allow Association با ${allowedMacs.size} MAC مجاز آماده است. پس از SAVE، هر دستگاه خارج از فهرست—even با داشتن رمز یا QR—نمی‌تواند وصل بماند.") { prefs.edit().putBoolean("anti_qr_active",true).putStringSet("allowed_macs",allowedMacs.toSet()).putString("router_last_message","ضد QR واقعی فعال شد؛ ${allowedMacs.size} دستگاه در Allow‑List هستند.").apply() }
            else status.text="ضد QR آماده نشد: $r. هیچ SAVE اجرا نشد."
        }
    }

    private fun prepareAntiQrDisable() {
        val js = wirelessScriptPrelude() + "var d=findWirelessDoc(window);if(!d)return 'NO_FILTER';var h=findHeading(d);if(!h)return 'NO_HEADING';setActive(d,h,false);return 'READY_DISABLE';})();"
        web.evaluateJavascript(js) { raw ->
            if (decodeJsString(raw)=="READY_DISABLE") confirmSave("خاموش‌کردن ضد QR", "Wireless MAC Address Filter روی Deactivated آماده شده است.") { prefs.edit().putBoolean("anti_qr_active",false).putString("router_last_message","ضد QR خاموش شد؛ MAC Filter بی‌اثر است.").apply() }
            else status.text="خاموش‌کردن ضد QR آماده نشد: ${decodeJsString(raw)}"
        }
    }

    private fun wirelessScriptPrelude(): String = """
        (function(){function txt(e){return ((e&&((e.innerText||e.textContent||e.value||e.alt||e.title)||''))+'').trim();}function normMac(s){return (s||'').trim().replace(/-/g,':').toUpperCase();}function findWirelessDoc(w){try{var d=w.document,t=(d.body?d.body.innerText:'');if(t.indexOf('Wireless MAC Address Filter')>=0)return d;for(var i=0;i<w.frames.length;i++){var x=findWirelessDoc(w.frames[i]);if(x)return x;}}catch(e){}return null;}function findHeading(d){var es=d.querySelectorAll('*');for(var i=0;i<es.length;i++){if(txt(es[i]).trim()=='Wireless MAC Address Filter'||txt(es[i]).indexOf('Wireless MAC Address Filter')>=0)return es[i];}return null;}function following(h,e){try{return !!(h.compareDocumentPosition(e)&Node.DOCUMENT_POSITION_FOLLOWING);}catch(x){return true;}}function selectedText(s){return ((s.options&&s.selectedIndex>=0&&s.options[s.selectedIndex])?txt(s.options[s.selectedIndex]):'').toLowerCase();}function findActionSelect(d,h){var ss=d.querySelectorAll('select');for(var i=0;i<ss.length;i++){if(!following(h,ss[i]))continue;for(var j=0;j<ss[i].options.length;j++){var o=txt(ss[i].options[j]).toLowerCase();if(o.indexOf('allow association')>=0||o.indexOf('deny association')>=0)return ss[i];}}return null;}function setOption(s,n){for(var i=0;i<s.options.length;i++){if(txt(s.options[i]).toLowerCase().indexOf(n)>=0){s.selectedIndex=i;s.value=s.options[i].value;s.dispatchEvent(new Event('change',{bubbles:true}));return true;}}return false;}function setActive(d,h,on){var ss=d.querySelectorAll('select');for(var i=0;i<ss.length;i++){if(!following(h,ss[i]))continue;var A=false,D=false;for(var j=0;j<ss[i].options.length;j++){var q=txt(ss[i].options[j]).toLowerCase();if(q.indexOf('activated')>=0)A=true;if(q.indexOf('deactivated')>=0)D=true;}if(A&&D){setOption(ss[i],on?'activated':'deactivated');return true;}}var rs=d.querySelectorAll('input[type=radio]'),g={};for(var r=0;r<rs.length;r++){if(!following(h,rs[r]))continue;var n=rs[r].name||'x';(g[n]||(g[n]=[])).push(rs[r]);}for(var x in g){if(g[x].length>=2){var row=g[x][0].closest('tr')||g[x][0].parentElement;var rt=txt(row).toLowerCase();if(rt.indexOf('active')>=0||rt.indexOf('activated')>=0){var p=on?g[x][0]:g[x][1];p.checked=true;p.click();return true;}}}return false;}function findMacInputs(d,h){var ins=d.querySelectorAll('input[type=text]'),o=[];for(var i=0;i<ins.length;i++){if(!following(h,ins[i]))continue;var row=ins[i].closest('tr')||ins[i].parentElement;var rt=txt(row).toLowerCase(),v=ins[i].value||'';if(rt.indexOf('mac address')>=0||/^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(v))o.push(ins[i]);if(o.length>=16)break;}return o;}
    """.trimIndent()

    private fun confirmSave(title:String,message:String,after:()->Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("SAVE واقعی") { _,_ ->
            val js = """(function(){function walk(w){try{var d=w.document,t=(d.body?d.body.innerText:'');if(t.indexOf('Wireless MAC Address Filter')>=0){var es=d.querySelectorAll('input[type=submit],input[type=button],button');for(var i=0;i<es.length;i++){var s=((es[i].value||es[i].innerText||'')+'').trim().toLowerCase();if(s=='save'||s.indexOf('save')>=0){es[i].click();return true;}}}for(var j=0;j<w.frames.length;j++){if(walk(w.frames[j]))return true;}}catch(e){}return false;}return walk(window);})();"""
            web.evaluateJavascript(js) { raw -> if (raw=="true") { after(); status.text="$title اجرا شد و SAVE واقعی روتر زده شد."; handler.postDelayed({capturePanelSnapshot()},1200) } else status.text="دکمه SAVE واقعی پیدا نشد؛ هیچ تغییری نهایی نشد." }
        }.setNegativeButton("لغو",null).show()
    }

    private fun markBlocked(mac:String,blocked:Boolean) {
        val set=prefs.getStringSet("blocked_macs",emptySet())?.toMutableSet()?:mutableSetOf();if(blocked)set.add(mac)else set.remove(mac);prefs.edit().putStringSet("blocked_macs",set).putString("router_last_message",if(blocked)"$mac در روتر مسدود شد." else "$mac از مسدودی روتر خارج شد.").apply()
    }

    private inner class RouterBridge {
        @JavascriptInterface fun receiveSnapshot(snapshot:String) {
            val starts=listOf("Current Connected Wireless Clients","Connected Wireless Clients","Wireless Clients").map{snapshot.indexOf(it,ignoreCase=true)}.filter{it>=0}
            val start=starts.minOrNull();val section=if(start!=null)snapshot.substring(start,minOf(snapshot.length,start+12000))else snapshot
            val regex=Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
            val macs=regex.findAll(section).map{it.value.replace('-',':').uppercase(Locale.US)}.filter{it!="78:8C:B5:DD:8E:F0"}.distinct().take(64).toSet()
            if(macs.isNotEmpty())prefs.edit().putStringSet("detected_macs",macs).apply()
            val summary=buildString{val dr=Regex("(?i)Data Rate[^0-9]*(\\d+)[^0-9]+(\\d+)").find(snapshot);val snr=Regex("(?i)SNR Margin[^0-9]*(\\d+(?:\\.\\d+)?)[^0-9]+(\\d+(?:\\.\\d+)?)").find(snapshot);if(macs.isNotEmpty())append("${macs.size} دستگاه متصل واقعی خوانده شد.");if(dr!=null)append(" ADSL: ${dr.groupValues[1]}/${dr.groupValues[2]} kbps.");if(snr!=null)append(" SNR: ${snr.groupValues[1]}/${snr.groupValues[2]} dB.")}.ifBlank{"پنل واقعی خوانده شد؛ جدول دستگاه‌ها در این snapshot نبود."}
            prefs.edit().putString("router_last_message",summary).apply();runOnUiThread{status.text=summary}
        }
    }

    private fun decodeJsString(raw:String?):String { if(raw.isNullOrBlank()||raw=="null")return "";return try{JSONArray("[$raw]").getString(0)}catch(_:Exception){raw.trim('"')} }
    override fun onBackPressed(){if(web.canGoBack())web.goBack()else super.onBackPressed()}
}
