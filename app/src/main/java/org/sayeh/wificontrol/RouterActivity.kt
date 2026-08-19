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
    private var openMacFilter = false
    private var pendingAction: String? = null
    private var targetMac: String? = null
    private var loginAttempts = 0
    private var loggedIn = false
    private var navigationAttempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)

        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        autoScan = intent.getBooleanExtra("autoScan", false)
        openMacFilter = intent.getBooleanExtra("openMacFilter", false)
        pendingAction = intent.getStringExtra("action")
        targetMac = intent.getStringExtra("targetMac")?.uppercase(Locale.US)

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
                status.text = "پنل روتر • ${url ?: baseUrl}"
                handler.postDelayed({ inspectPageAndContinue() }, 350)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    status.text = "صفحه روتر پاسخ نداد: ${error?.description ?: "خطای نامشخص"}"
                }
            }
        }

        findViewById<Button>(R.id.backBtn).setOnClickListener {
            if (web.canGoBack()) web.goBack() else finish()
        }
        findViewById<Button>(R.id.homeBtn).setOnClickListener {
            loggedIn = false
            loginAttempts = 0
            web.loadUrl(baseUrl)
        }
        findViewById<Button>(R.id.statusBtn).setOnClickListener { startDeviceSync() }
        findViewById<Button>(R.id.syncBtn).setOnClickListener { startDeviceSync() }
        findViewById<Button>(R.id.macFilterBtn).setOnClickListener { navigateToMacFilter() }

        val start = intent.getStringExtra("startUrl") ?: baseUrl
        web.loadUrl(start)
    }

    private fun inspectPageAndContinue() {
        val js = """
            (function(){
              try {
                var p=document.querySelector('input[type=password]');
                var text=(document.body?document.body.innerText:'').toLowerCase();
                return JSON.stringify({login:!!p || location.href.toLowerCase().indexOf('login_security')>=0 || (text.indexOf('username')>=0 && text.indexOf('password')>=0), url:location.href});
              } catch(e){ return JSON.stringify({login:false,url:location.href}); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val value = decodeJsString(raw)
            val isLogin = try { JSONObject(value).optBoolean("login", false) } catch (_: Exception) { false }
            if (isLogin) {
                loggedIn = false
                attemptAutoLogin()
            } else {
                if (!loggedIn) {
                    loggedIn = true
                    loginAttempts = 0
                    status.text = "ورود واقعی به پنل موفق شد."
                }
                when {
                    pendingAction != null -> navigateToMacFilter()
                    openMacFilter -> {
                        openMacFilter = false
                        navigateToMacFilter()
                    }
                    autoScan -> {
                        autoScan = false
                        startDeviceSync()
                    }
                }
            }
        }
    }

    private fun attemptAutoLogin() {
        if (user.isBlank() || pass.isBlank()) {
            status.text = "صفحه ورود روتر باز است. نام کاربری و رمز را در صفحه قبل وارد کن."
            return
        }
        if (loginAttempts >= 3) {
            status.text = "ورود خودکار انجام نشد. نام کاربری/رمز را بررسی کن؛ هیچ تنظیمی تغییر نکرد."
            return
        }
        loginAttempts++
        status.text = "در حال ورود واقعی به روتر…"
        val uq = JSONObject.quote(user)
        val pq = JSONObject.quote(pass)
        val js = """
            (function(){
              try {
                var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');
                var p=document.querySelector('input[type=password],input[name*=pass i],input[id*=pass i]');
                if(!u || !p) return 'NO_FORM';
                u.focus(); u.value=$uq; u.dispatchEvent(new Event('input',{bubbles:true})); u.dispatchEvent(new Event('change',{bubbles:true}));
                p.focus(); p.value=$pq; p.dispatchEvent(new Event('input',{bubbles:true})); p.dispatchEvent(new Event('change',{bubbles:true}));
                var f=p.form || u.form || document.forms[0];
                if(f){
                  var s=f.querySelector('input[type=submit],button[type=submit],input[type=button],button');
                  if(s){ s.click(); return 'CLICKED'; }
                  f.submit(); return 'SUBMITTED';
                }
                var b=document.querySelector('input[type=submit],button,input[type=button]');
                if(b){ b.click(); return 'CLICKED'; }
                return 'FILLED';
              } catch(e){ return 'ERR:'+e; }
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { result ->
            val r = decodeJsString(result)
            if (r.startsWith("NO_FORM") || r.startsWith("ERR")) {
                status.text = "فرم ورود پیدا نشد؛ پنل باز است و می‌توانی دستی وارد شوی."
            } else {
                handler.postDelayed({ inspectPageAndContinue() }, 1200)
            }
        }
    }

    private fun startDeviceSync() {
        status.text = "در حال خواندن دستگاه‌های متصل از پنل واقعی…"
        val clickStatus = clickPanelText(listOf("Status"))
        web.evaluateJavascript(clickStatus) { _ ->
            handler.postDelayed({ capturePanelSnapshot() }, 1400)
            handler.postDelayed({ capturePanelSnapshot() }, 3000)
        }
    }

    private fun capturePanelSnapshot() {
        val js = """
            (function(){
              var parts=[];
              function grab(w,depth){
                if(depth>8) return;
                try{
                  var d=w.document;
                  if(d && d.documentElement){ parts.push((d.body?d.body.innerText:'')+'\n'+(d.documentElement.outerHTML||'')); }
                  for(var i=0;i<w.frames.length;i++){ try{ grab(w.frames[i],depth+1); }catch(e){} }
                }catch(e){}
              }
              grab(window,0);
              AndroidBridge.receiveSnapshot(parts.join('\n---FRAME---\n'));
              return 'SENT';
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun navigateToMacFilter() {
        navigationAttempts = 0
        status.text = "در حال باز کردن Interface Setup → Wireless…"
        navigateWirelessStep()
    }

    private fun navigateWirelessStep() {
        if (navigationAttempts++ > 8) {
            status.text = "صفحه Wireless خودکار پیدا نشد. پنل واقعی باز است؛ Interface Setup → Wireless را دستی بزن."
            return
        }
        val detectJs = """
            (function(){
              function has(w){
                try{
                  var t=(w.document.body?w.document.body.innerText:'');
                  if(t.indexOf('Wireless MAC Address Filter')>=0) return true;
                  for(var i=0;i<w.frames.length;i++){ if(has(w.frames[i])) return true; }
                }catch(e){}
                return false;
              }
              return has(window);
            })();
        """.trimIndent()
        web.evaluateJavascript(detectJs) { found ->
            if (found == "true") {
                status.text = "صفحه واقعی Wireless MAC Filter پیدا شد."
                val action = pendingAction
                if (action == "block" || action == "unblock") {
                    prepareMacAction(action, targetMac.orEmpty())
                }
                return@evaluateJavascript
            }
            val clickWireless = clickPanelText(listOf("Wireless"))
            web.evaluateJavascript(clickWireless) { clickedWireless ->
                if (decodeJsString(clickedWireless).contains("true", true)) {
                    handler.postDelayed({ navigateWirelessStep() }, 1100)
                } else {
                    val clickInterface = clickPanelText(listOf("Interface Setup"))
                    web.evaluateJavascript(clickInterface) { _ ->
                        handler.postDelayed({
                            web.evaluateJavascript(clickPanelText(listOf("Wireless")), null)
                            handler.postDelayed({ navigateWirelessStep() }, 1000)
                        }, 700)
                    }
                }
            }
        }
    }

    private fun clickPanelText(labels: List<String>): String {
        val array = labels.joinToString(",") { JSONObject.quote(it.lowercase(Locale.US)) }
        return """
            (function(){
              var labels=[$array];
              function walk(w){
                try{
                  var d=w.document;
                  var els=d.querySelectorAll('a,button,input[type=button],input[type=submit]');
                  for(var i=0;i<els.length;i++){
                    var e=els[i]; var s=((e.innerText||e.textContent||e.value||'')+'').trim().toLowerCase();
                    for(var j=0;j<labels.length;j++){ if(s==labels[j] || s.indexOf(labels[j])>=0){ e.click(); return true; } }
                  }
                  for(var k=0;k<w.frames.length;k++){ if(walk(w.frames[k])) return true; }
                }catch(e){}
                return false;
              }
              return walk(window);
            })();
        """.trimIndent()
    }

    private fun prepareMacAction(action: String, mac: String) {
        if (mac.isBlank()) {
            status.text = "MAC هدف مشخص نیست."
            return
        }
        val mq = JSONObject.quote(mac)
        val mode = JSONObject.quote(action)
        val js = """
            (function(){
              var target=$mq, mode=$mode;
              function findDoc(w){
                try{
                  var d=w.document, t=(d.body?d.body.innerText:'');
                  if(t.indexOf('Wireless MAC Address Filter')>=0) return d;
                  for(var i=0;i<w.frames.length;i++){ var x=findDoc(w.frames[i]); if(x) return x; }
                }catch(e){}
                return null;
              }
              var d=findDoc(window); if(!d) return 'NO_FILTER';
              var sel=null, sels=d.querySelectorAll('select');
              for(var i=0;i<sels.length;i++){
                var opts=sels[i].options;
                for(var j=0;j<opts.length;j++){ if((opts[j].text||'').toLowerCase().indexOf('deny association')>=0){ sel=sels[i]; break; } }
                if(sel) break;
              }
              if(!sel) return 'NO_ACTION_SELECT';
              var current=(sel.options[sel.selectedIndex]?sel.options[sel.selectedIndex].text:'').toLowerCase();
              if(mode=='unblock' && current.indexOf('deny association')<0) return 'UNBLOCK_UNSAFE_ACTION';
              if(mode=='block'){
                for(var j=0;j<sel.options.length;j++){ if((sel.options[j].text||'').toLowerCase().indexOf('deny association')>=0){ sel.selectedIndex=j; sel.value=sel.options[j].value; break; } }
                sel.dispatchEvent(new Event('change',{bubbles:true}));
              }
              var radios=d.querySelectorAll('input[type=radio]');
              for(var r=0;r<radios.length;r++){
                var row=radios[r].closest('tr') || radios[r].parentElement;
                var txt=(row?row.innerText:'').toLowerCase();
                if(txt.indexOf('active')>=0 && txt.indexOf('deactivated')>=0){
                  var group=row.querySelectorAll('input[type=radio]'); if(group.length>=2){ group[0].checked=true; group[0].click(); }
                  break;
                }
              }
              var inputs=d.querySelectorAll('input[type=text]');
              var macInputs=[];
              for(var k=0;k<inputs.length;k++){
                var row2=inputs[k].closest('tr') || inputs[k].parentElement;
                var tx=(row2?row2.innerText:'').toLowerCase();
                if(tx.indexOf('mac address')>=0 || /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(inputs[k].value||'')) macInputs.push(inputs[k]);
              }
              if(macInputs.length==0) return 'NO_MAC_INPUTS';
              if(mode=='block'){
                var slot=null;
                for(var m=0;m<macInputs.length;m++){
                  var v=(macInputs[m].value||'').toUpperCase();
                  if(v==target) return 'ALREADY_PRESENT';
                  if(!slot && (v=='' || v=='00:00:00:00:00:00')) slot=macInputs[m];
                }
                if(!slot) return 'NO_EMPTY_SLOT';
                slot.value=target; slot.dispatchEvent(new Event('input',{bubbles:true})); slot.dispatchEvent(new Event('change',{bubbles:true}));
                return 'READY_BLOCK';
              } else {
                for(var n=0;n<macInputs.length;n++){
                  if((macInputs[n].value||'').toUpperCase()==target){ macInputs[n].value='00:00:00:00:00:00'; macInputs[n].dispatchEvent(new Event('change',{bubbles:true})); return 'READY_UNBLOCK'; }
                }
                return 'TARGET_NOT_FOUND';
              }
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            when (val result = decodeJsString(raw)) {
                "READY_BLOCK" -> confirmAndSave("مسدودسازی واقعی", "MAC $mac در حالت Deny Association آماده شده است. با تأیید، دکمه SAVE واقعی روتر زده می‌شود.", action, mac)
                "READY_UNBLOCK" -> confirmAndSave("رفع مسدودی واقعی", "MAC $mac از فهرست Deny آماده حذف است. با تأیید، SAVE واقعی روتر زده می‌شود.", action, mac)
                "ALREADY_PRESENT" -> {
                    markBlocked(mac, true)
                    pendingAction = null
                    status.text = "این MAC از قبل در فهرست Deny Association موجود است."
                }
                "TARGET_NOT_FOUND" -> {
                    markBlocked(mac, false)
                    pendingAction = null
                    status.text = "این MAC در فهرست Deny پیدا نشد؛ مسدود نیست."
                }
                "UNBLOCK_UNSAFE_ACTION" -> status.text = "فیلتر فعلی روی Deny Association نیست؛ برای جلوگیری از قطع ناخواسته، رفع مسدودی خودکار اجرا نشد."
                else -> status.text = "کنترل خودکار آماده نشد ($result). صفحه واقعی MAC Filter باز است و هیچ تغییری ذخیره نشده."
            }
        }
    }

    private fun confirmAndSave(title: String, message: String, action: String, mac: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("تأیید و SAVE") { _, _ -> submitMacFilterSave(action, mac) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun submitMacFilterSave(action: String, mac: String) {
        status.text = "در حال ذخیره فرمان در خود روتر…"
        val js = """
            (function(){
              function findDoc(w){
                try{
                  var d=w.document, t=(d.body?d.body.innerText:'');
                  if(t.indexOf('Wireless MAC Address Filter')>=0) return d;
                  for(var i=0;i<w.frames.length;i++){ var x=findDoc(w.frames[i]); if(x) return x; }
                }catch(e){}
                return null;
              }
              var d=findDoc(window); if(!d) return false;
              var els=d.querySelectorAll('input[type=submit],input[type=button],button');
              for(var i=0;i<els.length;i++){
                var s=((els[i].value||els[i].innerText||'')+'').trim().toLowerCase();
                if(s=='save' || s.indexOf('save')>=0){ els[i].click(); return true; }
              }
              return false;
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (raw == "true") {
                handler.postDelayed({ verifyMacAction(action, mac) }, 1800)
            } else {
                status.text = "دکمه SAVE در صفحه پیدا نشد؛ هیچ فرمانی ذخیره نشد."
            }
        }
    }

    private fun verifyMacAction(action: String, mac: String) {
        val mq = JSONObject.quote(mac)
        val js = """
            (function(){
              var target=$mq;
              function walk(w){
                try{
                  var ins=w.document.querySelectorAll('input[type=text]');
                  for(var i=0;i<ins.length;i++){ if((ins[i].value||'').toUpperCase()==target) return true; }
                  for(var j=0;j<w.frames.length;j++){ if(walk(w.frames[j])) return true; }
                }catch(e){}
                return false;
              }
              return walk(window);
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val present = raw == "true"
            if (action == "block" && present) {
                markBlocked(mac, true)
                status.text = "✓ مسدودسازی در خود روتر ذخیره شد: $mac"
            } else if (action == "unblock" && !present) {
                markBlocked(mac, false)
                status.text = "✓ رفع مسدودی در خود روتر ذخیره شد: $mac"
            } else {
                status.text = "SAVE ارسال شد؛ برای اطمینان نتیجه صفحه را بررسی کن."
            }
            pendingAction = null
        }
    }

    private fun markBlocked(mac: String, blocked: Boolean) {
        val set = prefs.getStringSet("blocked_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blocked) set.add(mac) else set.remove(mac)
        prefs.edit().putStringSet("blocked_macs", set).apply()
    }

    private inner class RouterBridge {
        @JavascriptInterface
        fun receiveSnapshot(snapshot: String) {
            val clients = parseConnectedClients(snapshot)
            if (clients.isNotEmpty()) {
                prefs.edit().putStringSet("detected_macs", clients.toSet()).apply()
                runOnUiThread { status.text = "✓ ${clients.size} دستگاه متصل از پنل واقعی روتر همگام شد." }
            } else {
                runOnUiThread { status.text = "پنل باز است، اما جدول Current Connected Wireless Clients هنوز در صفحه پیدا نشد. Status را بزن و دوباره همگام‌سازی را بزن." }
            }
        }
    }

    private fun parseConnectedClients(snapshot: String): List<String> {
        if (snapshot.isBlank()) return emptyList()
        val markers = listOf("Current Connected Wireless Clients", "Connected Wireless Clients", "Wireless Clients")
        val starts = markers.map { snapshot.indexOf(it, ignoreCase = true) }.filter { it >= 0 }
        if (starts.isEmpty()) return emptyList()
        val start = starts.minOrNull() ?: return emptyList()
        val section = snapshot.substring(start, minOf(snapshot.length, start + 60000))
        val regex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        return regex.findAll(section)
            .map { it.value.replace('-', ':').uppercase(Locale.US) }
            .filter { it != "00:00:00:00:00:00" }
            .distinct()
            .take(128)
            .toList()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val wrapper = "{\"v\":$raw}"
            JSONObject(wrapper).optString("v", raw.trim('"'))
        } catch (_: Exception) {
            raw.trim('"')
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}