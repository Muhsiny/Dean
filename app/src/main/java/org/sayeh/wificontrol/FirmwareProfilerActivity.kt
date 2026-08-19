package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only direct endpoint profiler for the user's TD-W8961N V4 firmware.
 * It deliberately does not click the old framed menu because that path proved unreliable.
 */
class FirmwareProfilerActivity : AppCompatActivity() {
    private data class Probe(val label: String, val path: String)

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String
    private val handler = Handler(Looper.getMainLooper())
    private val pages = JSONArray()

    private var loginAttempts = 0
    private var authenticated = false
    private var cancelled = false
    private var captureStarted = false
    private var currentProbe = -1
    private var waitingForProbeLoad = false

    private val probes = listOf(
        Probe("root", "/"),
        Probe("Device Info — direct", "/status/status_deviceinfo.htm"),
        Probe("Interface navigation — direct", "/navigation-basic.html"),
        Probe("Wireless — DIRECT", "/basic/home_wlan.htm"),
        Probe("Guest Network — DIRECT", "/basic/home_guest_network.htm"),
        Probe("Access navigation — direct", "/navigation-access.html"),
        Probe("IP/MAC Filter — DIRECT", "/access/access_ipfilter.htm"),
        Probe("Statistics — DIRECT", "/status/status_statistics.htm")
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiler)
        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        status = findViewById(R.id.profileStatus)
        web = findViewById(R.id.profileWeb)
        findViewById<Button>(R.id.profileCancelBtn).setOnClickListener {
            cancelled = true
            finish()
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cancelled) return
                handler.postDelayed({ handlePageFinished() }, 300)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !cancelled) {
                    status.text = "خطای بارگذاری مستقیم: ${error?.description ?: "نامشخص"}"
                }
            }
        }

        status.text = "در حال ورود فقط‌خواندنی به firmware…"
        web.loadUrl(baseUrl)
    }

    private fun handlePageFinished() {
        if (cancelled) return
        isLoginPage { login ->
            if (login) {
                autoLogin()
                return@isLoginPage
            }

            if (!authenticated) {
                authenticated = true
                loginAttempts = 0
                status.text = "ورود موفق شد. endpointها مستقیم خوانده می‌شوند."
                if (!captureStarted) {
                    captureStarted = true
                    handler.postDelayed({ loadProbe(0) }, 250)
                }
                return@isLoginPage
            }

            if (waitingForProbeLoad && currentProbe in probes.indices) {
                waitingForProbeLoad = false
                val p = probes[currentProbe]
                handler.postDelayed({
                    captureCurrent(p.label) {
                        handler.postDelayed({ loadProbe(currentProbe + 1) }, 180)
                    }
                }, 350)
            }
        }
    }

    private fun loadProbe(index: Int) {
        if (cancelled) return
        if (index >= probes.size) {
            exportProfile()
            return
        }
        currentProbe = index
        waitingForProbeLoad = true
        val p = probes[index]
        status.text = "خواندن مستقیم ${index + 1}/${probes.size}: ${p.label}\n${route(p.path)}"
        web.loadUrl(route(p.path))
    }

    private fun route(path: String): String = if (path == "/") "$baseUrl/" else baseUrl + if (path.startsWith('/')) path else "/$path"

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

    private fun autoLogin() {
        if (user.isBlank() || pass.isBlank()) {
            status.text = "نام کاربری یا رمز ادمین خالی است."
            return
        }
        if (loginAttempts >= 3) {
            status.text = "ورود خودکار موفق نشد. هیچ تنظیمی تغییر نکرد."
            return
        }
        loginAttempts++
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
        status.text = "در حال احراز هویت…"
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) {
                status.text = "فرم ورود شناخته نشد؛ هیچ تنظیمی تغییر نکرد."
            }
        }
    }

    private fun captureCurrent(label: String, done: () -> Unit) {
        val labelQ = JSONObject.quote(label)
        val js = """
            (function(){
              function clean(s,n){s=(s||'').toString().replace(/\s+/g,' ').trim();return s.substring(0,n||500);}
              function attr(e,n){try{return e.getAttribute(n)||'';}catch(x){return '';}}
              function fieldRowText(e){try{var r=e.closest?e.closest('tr'):e.parentElement;return clean((r&&r.innerText)||'',500);}catch(x){return '';}}
              function pageOf(w,depth){
                if(depth>8)return null;
                try{
                  var d=w.document,p={url:(w.location&&w.location.href)||'',title:d.title||'',frames:[],clickables:[],forms:[],scripts:[],markers:{}};
                  var body=(d.body?d.body.innerText:'');
                  var fm=body.match(/Firmware\s*Version\s*[:：]?\s*([^\n\r]+)/i);if(fm)p.firmware=clean(fm[1],160);
                  var mm=body.match(/MAC\s*Address\s*[:：]?\s*((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})/i);if(mm)p.routerMac=mm[1].replace(/-/g,':').toUpperCase();
                  ['wireless mac address filter','current connected wireless clients','ip/mac filter','guest network','statistics','allow association','deny association'].forEach(function(m){p.markers[m]=body.toLowerCase().indexOf(m)>=0;});

                  var es=d.querySelectorAll('a,area,button,input[type=button],input[type=submit],[onclick],[href],img');
                  for(var i=0;i<es.length&&p.clickables.length<300;i++){
                    var e=es[i],type=(e.type||'').toLowerCase(),text=clean(e.innerText||e.textContent||((type=='button'||type=='submit')?e.value:'')||e.alt||e.title,180);
                    var href=clean(attr(e,'href'),500),oc=clean(attr(e,'onclick'),700);
                    if(text||href||oc)p.clickables.push({tag:(e.tagName||'').toLowerCase(),text:text,href:href,onclick:oc,id:clean(e.id,120),name:clean(e.name,120),type:type});
                  }

                  var fs=d.querySelectorAll('form');
                  for(var f=0;f<fs.length&&p.forms.length<50;f++){
                    var form=fs[f],fo={id:clean(form.id,120),name:clean(form.name,120),action:clean(form.action||attr(form,'action'),600),method:clean(form.method||attr(form,'method'),20),text:clean(form.innerText,900),fields:[]};
                    var els=form.querySelectorAll('input,select,textarea,button');
                    for(var j=0;j<els.length&&fo.fields.length<220;j++){
                      var x=els[j],t=(x.type||x.tagName||'').toLowerCase();
                      var field={tag:(x.tagName||'').toLowerCase(),name:clean(x.name,160),id:clean(x.id,160),type:t,rowText:fieldRowText(x)};
                      if((x.tagName||'').toLowerCase()=='select'){
                        field.value=clean(x.value,200);field.options=[];
                        for(var o=0;o<x.options.length&&o<80;o++)field.options.push({text:clean(x.options[o].text,160),value:clean(x.options[o].value,160),selected:!!x.options[o].selected});
                      } else if(t=='radio'||t=='checkbox') {field.checked=!!x.checked;field.value=clean(x.value,180);}
                      else if(t=='submit'||t=='button') field.buttonText=clean(x.value||x.innerText,140);
                      else field.value=clean(x.value,240);
                      fo.fields.push(field);
                    }
                    p.forms.push(fo);
                  }

                  var ss=d.querySelectorAll('script');
                  for(var s=0;s<ss.length&&p.scripts.length<100;s++){
                    var src=clean(ss[s].src||attr(ss[s],'src'),600),txt=(ss[s].text||ss[s].textContent||'');
                    var hits=txt.match(/[A-Za-z0-9_./()-]+\.(?:html?|cgi|asp|js)(?:\?[^\s'\"<>]*)?/ig)||[];
                    if(src||hits.length)p.scripts.push({src:src,endpoints:hits.slice(0,100)});
                  }
                  var frames=d.querySelectorAll('frame,iframe');
                  for(var r=0;r<frames.length&&r<40;r++)p.frames.push({name:clean(frames[r].name,120),id:clean(frames[r].id,120),src:clean(frames[r].src||attr(frames[r],'src'),600)});
                  p.children=[];
                  for(var z=0;z<w.frames.length&&z<20;z++){var child=pageOf(w.frames[z],depth+1);if(child)p.children.push(child);}
                  return p;
                }catch(e){return {error:clean(e.toString(),300)};}
              }
              return JSON.stringify({captureLabel:$labelQ,root:pageOf(window,0)});
            })();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            try { pages.put(JSONObject(decodeJs(raw))) }
            catch (_: Exception) { pages.put(JSONObject().put("captureLabel", label).put("error", "parse_failed")) }
            done()
        }
    }

    private fun exportProfile() {
        if (cancelled) return
        val root = JSONObject()
            .put("schema", "wifi-control-firmware-map-v2-direct")
            .put("routerModel", "TP-Link TD-W8961N V4")
            .put("baseUrl", baseUrl)
            .put("createdAt", System.currentTimeMillis())
            .put("safety", "DIRECT READ-ONLY capture; password omitted; no SAVE/DELETE/RESET/REBOOT/UPGRADE executed")
            .put("pages", pages)
        val json = root.toString(2)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "wifi-router-direct-map-$stamp.json"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WiFiControl")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Downloads URI unavailable")
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Cannot open output")
                status.text = "Direct Firmware Map ذخیره شد.\nDownloads/WiFiControl/$fileName\n\nاین نسخه مستقیماً home_wlan و access_ipfilter را می‌خواند."
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, fileName)
                file.writeText(json)
                status.text = "Direct Firmware Map ذخیره شد: ${file.absolutePath}"
            }
        } catch (e: Exception) {
            status.text = "استخراج انجام شد اما ذخیره فایل شکست خورد: ${e.message}"
        }
    }

    private fun decodeJs(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try { JSONArray("[$raw]").optString(0) }
        catch (_: Exception) { raw.trim('"').replace("\\\"", "\"").replace("\\n", "\n") }
    }
}
