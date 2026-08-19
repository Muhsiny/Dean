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
    private val prefs by lazy { getSharedPreferences("wifi_control_local", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var action: String = "sync"
    private var targetMac: String = ""
    private var allowedMacs: List<String> = emptyList()
    private var loginAttempts = 0
    private var dispatched = false
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)
        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()
        action = intent.getStringExtra("action") ?: "sync"
        targetMac = intent.getStringExtra("targetMac")?.uppercase(Locale.US).orEmpty()
        allowedMacs = intent.getStringExtra("allowedMacs")?.split(',')?.map { it.trim().uppercase(Locale.US) }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

        status = findViewById(R.id.routerStatus)
        web = findViewById(R.id.routerWeb)
        findViewById<Button>(R.id.cancelBtn).setOnClickListener { finish() }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(RouterBridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!finished) handler.postDelayed({ inspectLoginAndDispatch() }, 450)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !finished) fail("روتر پاسخ نداد: ${error?.description ?: "خطای نامشخص"}")
            }
        }
        status.text = "در حال اتصال به روتر…"
        web.loadUrl(baseUrl)
    }

    private fun inspectLoginAndDispatch() {
        if (finished) return
        val js = """
            (function(){try{var p=document.querySelector('input[type=password]');var t=(document.body?document.body.innerText:'').toLowerCase();return (!!p||location.href.toLowerCase().indexOf('login_security')>=0||(t.indexOf('username')>=0&&t.indexOf('password')>=0));}catch(e){return false;}})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (raw == "true") attemptAutoLogin()
            else if (!dispatched) {
                dispatched = true
                loginAttempts = 0
                status.text = "ورود واقعی موفق شد؛ در حال اجرای فرمان…"
                handler.postDelayed({ dispatchAction() }, 350)
            }
        }
    }

    private fun attemptAutoLogin() {
        if (loginAttempts >= 3) { fail("ورود خودکار موفق نشد. نام کاربری یا رمز را بررسی کن؛ هیچ تنظیمی تغییر نکرد."); return }
        loginAttempts++
        val uq = JSONObject.quote(user)
        val pq = JSONObject.quote(pass)
        val js = """
            (function(){try{
              var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');
              var p=document.querySelector('input[type=password],input[name*=pass i],input[id*=pass i]');
              if(!u||!p)return 'NO_FORM';
              u.value=$uq;p.value=$pq;
              u.dispatchEvent(new Event('input',{bubbles:true}));p.dispatchEvent(new Event('input',{bubbles:true}));
              u.dispatchEvent(new Event('change',{bubbles:true}));p.dispatchEvent(new Event('change',{bubbles:true}));
              var f=p.form||u.form||document.forms[0];
              if(f){var s=f.querySelector('input[type=submit],button[type=submit],input[type=button],button');if(s){s.click();return 'CLICKED';}f.submit();return 'SUBMITTED';}
              return 'NO_SUBMIT';
            }catch(e){return 'ERR:'+e;}})();
        """.trimIndent()
        status.text = "در حال ورود به firmware…"
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r.startsWith("NO_") || r.startsWith("ERR")) fail("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.")
            else handler.postDelayed({ inspectLoginAndDispatch() }, 1300)
        }
    }

    private fun dispatchAction() {
        when (action) {
            "sync" -> syncDevices()
            "calibrate" -> calibrateFirmware()
            "block", "unblock" -> {
                val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
                if (targetMac.isBlank()) fail("MAC هدف مشخص نیست.")
                else if (targetMac == owner) fail("تلفن مدیر محافظت‌شده است و قابل قطع نیست.")
                else navigateWireless { prepareDeviceAction(action, targetMac) }
            }
            "antiqr_enable" -> {
                val owner = prefs.getString("protected_mac", null)?.uppercase(Locale.US)
                if (owner.isNullOrBlank() || owner !in allowedMacs) fail("فهرست ضد QR ایمن نیست؛ تلفن مدیر داخل Allow‑List نیست.")
                else navigateWireless { prepareAntiQrEnable() }
            }
            "antiqr_disable" -> navigateWireless { prepareAntiQrDisable() }
            else -> fail("فرمان ناشناخته است؛ هیچ تنظیمی تغییر نکرد.")
        }
    }

    private fun syncDevices() {
        status.text = "در حال خواندن جدول واقعی دستگاه‌های متصل…"
        captureSnapshot()
        navigateSection("Status", "Device Info", listOf("Current Connected Wireless Clients")) {
            handler.postDelayed({ captureSnapshot() }, 400)
            handler.postDelayed({ captureSnapshot() }, 1200)
        }
        handler.postDelayed({
            if (!finished) {
                val count = prefs.getStringSet("detected_macs", emptySet())?.size ?: 0
                if (count > 0) succeed("$count دستگاه واقعی تازه‌سازی شد.") else fail("جدول دستگاه‌ها پیدا نشد؛ هیچ تنظیمی تغییر نکرد.")
            }
        }, 5000)
    }

    private fun navigateWireless(onFound: () -> Unit) {
        navigateSection("Interface Setup", "Wireless", listOf("Wireless MAC Address Filter"), onFound)
    }

    private fun navigateSection(primary: String, secondary: String, markers: List<String>, onFound: () -> Unit) {
        var step = 0
        fun next() {
            if (finished) return
            checkMarkers(markers) { found ->
                if (found) { onFound(); return@checkMarkers }
                if (step >= 8) { calibrateFirmware(silent = true); fail("مسیر $primary → $secondary در این firmware خودکار پیدا نشد؛ هیچ فرمانی اجرا نشد."); return@checkMarkers }
                val label = if (step % 3 == 0) primary else secondary
                step++
                status.text = "در حال یافتن $primary → $secondary…"
                web.evaluateJavascript(smartClickJs(label)) { raw ->
                    val result = decodeJs(raw)
                    prefs.edit().putString("last_click_diag", result.take(1000)).apply()
                    handler.postDelayed({ next() }, 850)
                }
            }
        }
        next()
    }

    private fun checkMarkers(markers: List<String>, callback: (Boolean) -> Unit) {
        val q = markers.joinToString(",") { JSONObject.quote(it.lowercase(Locale.US)) }
        val js = """
            (function(){var ms=[$q];function scan(w){try{var t=(w.document.body?w.document.body.innerText:'').toLowerCase();for(var i=0;i<ms.length;i++)if(t.indexOf(ms[i])>=0)return true;for(var j=0;j<w.frames.length;j++)if(scan(w.frames[j]))return true;}catch(e){}return false;}return scan(window);})();
        """.trimIndent()
        web.evaluateJavascript(js) { callback(it == "true") }
    }

    private fun smartClickJs(label: String): String {
        val q = JSONObject.quote(label.lowercase(Locale.US))
        return """
            (function(){var label=$q;var best=null;
              function textOf(e){return ((e.innerText||e.textContent||e.value||e.alt||e.title||'')+'').trim().replace(/\s+/g,' ').toLowerCase();}
              function attrs(e){return (((e.getAttribute&&e.getAttribute('href'))||'')+' '+((e.getAttribute&&e.getAttribute('onclick'))||'')+' '+((e.getAttribute&&e.getAttribute('name'))||'')+' '+((e.getAttribute&&e.getAttribute('id'))||'')).toLowerCase();}
              function consider(w,e){var t=textOf(e),a=attrs(e),tag=(e.tagName||'').toLowerCase(),clickable=(tag=='a'||tag=='button'||tag=='input'||tag=='area'||!!e.onclick||!!(e.getAttribute&&e.getAttribute('href')));var score=0;
                if(clickable&&t==label)score=1000;else if(clickable&&t.indexOf(label)>=0&&t.length<=label.length+30)score=900;else if(clickable&&a.indexOf(label.replace(/\s+/g,''))>=0)score=800;else if(clickable&&a.indexOf(label)>=0)score=780;else if(!clickable&&t==label)score=600;else if(!clickable&&t.indexOf(label)>=0&&t.length<=label.length+20)score=500;
                if(score>0&&(!best||score>best.score||(score==best.score&&t.length<best.text.length)))best={w:w,e:e,score:score,text:t,tag:tag,attrs:a};}
              function walk(w){try{var d=w.document;var els=d.querySelectorAll('a,button,input,area,[onclick],[href],td,span,div,img');for(var i=0;i<els.length;i++)consider(w,els[i]);for(var j=0;j<w.frames.length;j++)walk(w.frames[j]);}catch(e){}}
              walk(window);if(!best)return JSON.stringify({ok:false,label:label});
              var e=best.e;var c=(e.closest?e.closest('a,button,[onclick],[href]'):null)||e;try{c.scrollIntoView({block:'center'});}catch(x){}try{c.focus();}catch(x){}
              try{if(typeof c.onclick==='function')c.onclick.call(c,new MouseEvent('click',{bubbles:true,cancelable:true}));}catch(x){}
              try{c.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true}));c.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true}));c.click();}catch(x){try{c.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));}catch(y){}}
              var href=(c.getAttribute&&c.getAttribute('href'))||'';if(href&&href!='#'&&!/^javascript:/i.test(href)){try{best.w.location.href=href;}catch(x){}}
              return JSON.stringify({ok:true,label:label,score:best.score,text:best.text,tag:best.tag,attrs:best.attrs});})();
        """.trimIndent()
    }

    private fun prepareDeviceAction(mode: String, mac: String) {
        val modeQ = JSONObject.quote(mode)
        val macQ = JSONObject.quote(mac)
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return 'NO_FILTER';var box=findBox(d);if(!box)return 'NO_BOX';var sel=findAction(box,d);if(!sel)return 'NO_ACTION_SELECT';
            if($modeQ=='block')setSelect(sel,'deny association');else if(selected(sel).indexOf('deny association')<0)return 'NOT_DENY_MODE';
            setActive(box,d,true);var ins=findMacInputs(box,d);if(ins.length==0)return 'NO_MAC_INPUTS';
            if($modeQ=='block'){for(var i=0;i<ins.length;i++)if(norm(ins[i].value)==norm($macQ))return 'ALREADY_PRESENT';var slot=null;for(var j=0;j<ins.length;j++){var v=norm(ins[j].value);if(!slot&&(v==''||v=='00:00:00:00:00:00'))slot=ins[j];}if(!slot)return 'NO_EMPTY_SLOT';slot.value=$macQ;slot.dispatchEvent(new Event('change',{bubbles:true}));return 'READY_BLOCK';}
            for(var k=0;k<ins.length;k++){if(norm(ins[k].value)==norm($macQ)){ins[k].value='00:00:00:00:00:00';ins[k].dispatchEvent(new Event('change',{bubbles:true}));return 'READY_UNBLOCK';}}return 'TARGET_NOT_FOUND';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            when (val r = decodeJs(raw)) {
                "READY_BLOCK" -> confirmSave("قطع واقعی دستگاه", "MAC $mac در Deny Association آماده شد. SAVE واقعی روتر اجرا شود؟", mode, mac)
                "READY_UNBLOCK" -> confirmSave("وصل‌کردن دوباره", "MAC $mac از Deny Association حذف شد. SAVE واقعی اجرا شود؟", mode, mac)
                "ALREADY_PRESENT" -> { markBlocked(mac, true); succeed("این دستگاه از قبل در Deny Association روتر بود.") }
                "TARGET_NOT_FOUND" -> { markBlocked(mac, false); succeed("این MAC در فهرست Deny پیدا نشد؛ دستگاه باید قابل اتصال باشد.") }
                else -> fail("فیلتر واقعی پیدا شد اما آماده‌سازی فرمان ناموفق بود: $r")
            }
        }
    }

    private fun prepareAntiQrEnable() {
        if (allowedMacs.isEmpty()) { fail("Allow‑List خالی است."); return }
        val arr = allowedMacs.joinToString(",") { JSONObject.quote(it) }
        val js = filterPrelude() + """
            var wanted=[$arr];var d=findDoc(window);if(!d)return 'NO_FILTER';var box=findBox(d);if(!box)return 'NO_BOX';var sel=findAction(box,d);if(!sel)return 'NO_ACTION_SELECT';setSelect(sel,'allow association');setActive(box,d,true);var ins=findMacInputs(box,d);if(ins.length<wanted.length)return 'NOT_ENOUGH_SLOTS';for(var i=0;i<ins.length;i++){ins[i].value=(i<wanted.length?wanted[i]:'00:00:00:00:00:00');ins[i].dispatchEvent(new Event('change',{bubbles:true}));}return 'READY_ALLOW';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val r = decodeJs(raw)
            if (r == "READY_ALLOW") confirmSave("فعال‌سازی ضد QR", "فقط ${allowedMacs.size} دستگاه در Allow Association باقی می‌مانند. SAVE واقعی اجرا شود؟", "antiqr_enable", "")
            else fail("ضد QR آماده نشد: $r")
        }
    }

    private fun prepareAntiQrDisable() {
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return 'NO_FILTER';var box=findBox(d);if(!box)return 'NO_BOX';setActive(box,d,false);return 'READY_OFF';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (decodeJs(raw) == "READY_OFF") confirmSave("خاموش‌کردن ضد QR", "MAC Filter غیرفعال می‌شود. SAVE واقعی اجرا شود؟", "antiqr_disable", "")
            else fail("غیرفعال‌سازی آماده نشد: ${decodeJs(raw)}")
        }
    }

    private fun confirmSave(title: String, message: String, verifyMode: String, mac: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("SAVE و بررسی") { _, _ -> clickSaveAndVerify(verifyMode, mac) }
            .setNegativeButton("لغو") { _, _ -> fail("فرمان لغو شد؛ هیچ تغییری ذخیره نشد.") }
            .setCancelable(false)
            .show()
    }

    private fun clickSaveAndVerify(mode: String, mac: String) {
        status.text = "در حال زدن SAVE واقعی روتر…"
        val js = filterPrelude() + """
            var d=findDoc(window);if(!d)return 'NO_FILTER';var box=findBox(d);var root=(box&&(box.closest?box.closest('form'):null))||d;var es=root.querySelectorAll('input,button,a');for(var i=0;i<es.length;i++){var t=((es[i].value||es[i].innerText||es[i].textContent||'')+'').trim().toLowerCase();if(t=='save'){try{es[i].click();return 'CLICKED';}catch(e){}}}return 'NO_SAVE';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (decodeJs(raw) != "CLICKED") { fail("دکمه SAVE واقعی در فرم پیدا نشد؛ هیچ وضعیت موفقی ثبت نشد."); return@evaluateJavascript }
            handler.postDelayed({ verifySaved(mode, mac) }, 1800)
            handler.postDelayed({ if (!finished) verifySaved(mode, mac) }, 3400)
        }
    }

    private fun verifySaved(mode: String, mac: String) {
        if (finished) return
        val modeQ = JSONObject.quote(mode)
        val macQ = JSONObject.quote(mac)
        val allowedArr = allowedMacs.joinToString(",") { JSONObject.quote(it) }
        val js = filterPrelude() + """
            var mode=$modeQ,wanted=[$allowedArr],target=$macQ;var d=findDoc(window);if(!d)return 'NO_FILTER';var box=findBox(d);if(!box)return 'NO_BOX';var sel=findAction(box,d);var ins=findMacInputs(box,d);var vals=[];for(var i=0;i<ins.length;i++)vals.push(norm(ins[i].value));
            if(mode=='block')return (sel&&selected(sel).indexOf('deny association')>=0&&vals.indexOf(norm(target))>=0)?'VERIFIED':'NOT_VERIFIED';
            if(mode=='unblock')return vals.indexOf(norm(target))<0?'VERIFIED':'NOT_VERIFIED';
            if(mode=='antiqr_enable'){if(!sel||selected(sel).indexOf('allow association')<0)return 'NOT_VERIFIED';for(var j=0;j<wanted.length;j++)if(vals.indexOf(norm(wanted[j]))<0)return 'NOT_VERIFIED';return 'VERIFIED';}
            if(mode=='antiqr_disable')return isActive(box,d)?'NOT_VERIFIED':'VERIFIED';return 'NOT_VERIFIED';})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            if (decodeJs(raw) == "VERIFIED") {
                when (mode) {
                    "block" -> markBlocked(mac, true)
                    "unblock" -> markBlocked(mac, false)
                    "antiqr_enable" -> prefs.edit().putBoolean("anti_qr_active", true).apply()
                    "antiqr_disable" -> prefs.edit().putBoolean("anti_qr_active", false).apply()
                }
                succeed(when (mode) {
                    "block" -> "قطع واقعی $mac روی خود روتر تأیید شد."
                    "unblock" -> "رفع مسدودی $mac روی خود روتر تأیید شد."
                    "antiqr_enable" -> "ضد QR واقعی روی Allow Association تأیید شد."
                    else -> "ضد QR روی خود روتر خاموش و تأیید شد."
                })
            }
        }
    }

    private fun filterPrelude(): String = """
        (function(){
          function norm(v){return (v||'').trim().replace(/-/g,':').toUpperCase();}
          function selected(s){try{return ((s.options[s.selectedIndex]&&s.options[s.selectedIndex].text)||'').toLowerCase();}catch(e){return '';}}
          function setSelect(s,needle){for(var i=0;i<s.options.length;i++){if(((s.options[i].text||'')+'').toLowerCase().indexOf(needle)>=0){s.selectedIndex=i;s.value=s.options[i].value;s.dispatchEvent(new Event('change',{bubbles:true}));return true;}}return false;}
          function findDoc(w){try{var t=(w.document.body?w.document.body.innerText:'');if(t.indexOf('Wireless MAC Address Filter')>=0)return w.document;for(var i=0;i<w.frames.length;i++){var d=findDoc(w.frames[i]);if(d)return d;}}catch(e){}return null;}
          function findBox(d){var all=d.querySelectorAll('table,form,fieldset,div');var best=null,bestLen=999999;for(var i=0;i<all.length;i++){var t=(all[i].innerText||'');if(t.indexOf('Wireless MAC Address Filter')>=0&&t.length<bestLen){best=all[i];bestLen=t.length;}}return best||d.body;}
          function findAction(box,d){var ss=box.querySelectorAll('select');for(var i=0;i<ss.length;i++){var txt='';for(var j=0;j<ss[i].options.length;j++)txt+=' '+(ss[i].options[j].text||'');txt=txt.toLowerCase();if(txt.indexOf('allow association')>=0&&txt.indexOf('deny association')>=0)return ss[i];}var all=d.querySelectorAll('select');for(var k=0;k<all.length;k++){var q='';for(var m=0;m<all[k].options.length;m++)q+=' '+(all[k].options[m].text||'');q=q.toLowerCase();if(q.indexOf('allow association')>=0&&q.indexOf('deny association')>=0)return all[k];}return null;}
          function findMacInputs(box,d){var result=[];var xs=box.querySelectorAll('input[type=text]');for(var i=0;i<xs.length;i++){var n=((xs[i].name||'')+' '+(xs[i].id||'')).toLowerCase(),v=(xs[i].value||'');if(n.indexOf('mac')>=0||/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(v)||v=='')result.push(xs[i]);}if(result.length)return result;var ys=d.querySelectorAll('input[type=text]');for(var j=0;j<ys.length;j++){var n2=((ys[j].name||'')+' '+(ys[j].id||'')).toLowerCase(),v2=(ys[j].value||'');if(n2.indexOf('mac')>=0||/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(v2))result.push(ys[j]);}return result;}
          function radioGroups(box,d){var rs=box.querySelectorAll('input[type=radio]');if(!rs.length)rs=d.querySelectorAll('input[type=radio]');return rs;}
          function setActive(box,d,on){var rs=radioGroups(box,d),groups={};for(var i=0;i<rs.length;i++){var n=rs[i].name||('_'+i);if(!groups[n])groups[n]=[];groups[n].push(rs[i]);}for(var g in groups){var arr=groups[g];var context='';for(var j=0;j<arr.length;j++){var row=arr[j].closest?arr[j].closest('tr'):arr[j].parentElement;context+=' '+((row&&row.innerText)||'');}var low=context.toLowerCase();if(low.indexOf('activated')>=0&&low.indexOf('deactivated')>=0){var chosen=null;for(var k=0;k<arr.length;k++){var v=(arr[k].value||'').toLowerCase();if(on&&(v=='1'||v=='yes'||v=='on'||v=='activated'))chosen=arr[k];if(!on&&(v=='0'||v=='no'||v=='off'||v=='deactivated'))chosen=arr[k];}if(!chosen)chosen=on?arr[0]:arr[arr.length-1];chosen.checked=true;try{chosen.click();}catch(e){}return true;}}return false;}
          function isActive(box,d){var rs=radioGroups(box,d),groups={};for(var i=0;i<rs.length;i++){var n=rs[i].name||('_'+i);if(!groups[n])groups[n]=[];groups[n].push(rs[i]);}for(var g in groups){var arr=groups[g],context='';for(var j=0;j<arr.length;j++){var row=arr[j].closest?arr[j].closest('tr'):arr[j].parentElement;context+=' '+((row&&row.innerText)||'');}var low=context.toLowerCase();if(low.indexOf('activated')>=0&&low.indexOf('deactivated')>=0){for(var k=0;k<arr.length;k++)if(arr[k].checked){var v=(arr[k].value||'').toLowerCase();if(v=='0'||v=='no'||v=='off'||v=='deactivated'||k==arr.length-1)return false;return true;}}}return false;}
    """.trimIndent()

    private fun captureSnapshot() {
        val js = """
            (function(){var out=[];function grab(w,d){if(d>8)return;try{var x=w.document;if(x&&x.documentElement)out.push((x.body?x.body.innerText:'')+'\n'+(x.documentElement.outerHTML||''));for(var i=0;i<w.frames.length;i++)grab(w.frames[i],d+1);}catch(e){}}grab(window,0);AndroidBridge.snapshot(out.join('\n---FRAME---\n'));return 'SENT';})();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun calibrateFirmware(silent: Boolean = false) {
        if (!silent) status.text = "در حال کالیبراسیون مسیرهای firmware…"
        val js = """
            (function(){var out=[];function scan(w,d){if(d>8)return;try{var x=w.document;out.push('PAGE|'+w.location.href+'|'+(x.title||''));var es=x.querySelectorAll('a,button,input,area,[onclick],[href],td,span');for(var i=0;i<es.length;i++){var e=es[i];var t=((e.innerText||e.textContent||e.value||e.alt||e.title||'')+'').trim().replace(/\s+/g,' ');var h=(e.getAttribute&&e.getAttribute('href'))||'';var o=(e.getAttribute&&e.getAttribute('onclick'))||'';if(t||h||o)out.push('ELEM|'+t+'|'+h+'|'+o);}for(var j=0;j<w.frames.length;j++)scan(w.frames[j],d+1);}catch(e){}}scan(window,0);return JSON.stringify(out.slice(0,500));})();
        """.trimIndent()
        web.evaluateJavascript(js) { raw ->
            val decoded = decodeJs(raw)
            prefs.edit().putString("firmware_map", decoded.take(30000)).apply()
            if (!silent) succeed("کالیبراسیون firmware ثبت شد. اکنون فرمان‌های بعدی از مسیرهای واقعی همین روتر استفاده می‌کنند.")
        }
    }

    private fun markBlocked(mac: String, blocked: Boolean) {
        val set = prefs.getStringSet("blocked_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blocked) set.add(mac) else set.remove(mac)
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
        handler.postDelayed({ finish() }, 1600)
    }

    private fun decodeJs(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim('"') }
    }

    inner class RouterBridge {
        @JavascriptInterface
        fun snapshot(data: String) {
            val lower = data.lowercase(Locale.US)
            val marker = lower.indexOf("current connected wireless clients")
            if (marker < 0) return
            var section = data.substring(marker, minOf(data.length, marker + 7000))
            val cuts = listOf("\nWAN\n", "\nADSL\n", "System Log", "Statistics")
            var end = section.length
            for (c in cuts) { val p = section.indexOf(c, ignoreCase = true); if (p > 0) end = minOf(end, p) }
            section = section.substring(0, end)
            val rx = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
            val macs = rx.findAll(section).map { it.value.replace('-', ':').uppercase(Locale.US) }.distinct().toMutableSet()
            val prefix = data.substring(0, marker)
            val routerMacMatch = Regex("(?i)MAC\\s*Address\\s*[:：]?\\s*((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})").find(prefix)
            routerMacMatch?.groupValues?.getOrNull(1)?.replace('-', ':')?.uppercase(Locale.US)?.let { macs.remove(it) }
            if (macs.isNotEmpty()) prefs.edit().putStringSet("detected_macs", macs).apply()
        }
    }
}
