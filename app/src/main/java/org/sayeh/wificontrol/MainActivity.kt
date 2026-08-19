package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val routerUrl=findViewById<EditText>(R.id.routerUrl)
        val username=findViewById<EditText>(R.id.username)
        val password=findViewById<EditText>(R.id.password)
        status=findViewById(R.id.status)
        web=findViewById(R.id.web)
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=true
        web.webViewClient=object:WebViewClient(){
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(username.text.toString(),password.text.toString())
            }
        }
        findViewById<Button>(R.id.testBtn).setOnClickListener { testReadOnly(routerUrl.text.toString(),username.text.toString(),password.text.toString()) }
        findViewById<Button>(R.id.openAdmin).setOnClickListener { web.loadUrl(routerUrl.text.toString()) }
    }

    private fun testReadOnly(base:String,user:String,pass:String){
        status.text="در حال آزمایش اتصال…"
        thread {
            val result=try {
                val u=URL(base)
                val c=(u.openConnection() as HttpURLConnection).apply {
                    connectTimeout=6000; readTimeout=6000; requestMethod="GET"; instanceFollowRedirects=true
                    if(user.isNotBlank()) setRequestProperty("Authorization","Basic "+Base64.encodeToString("$user:$pass".toByteArray(),Base64.NO_WRAP))
                }
                val code=c.responseCode
                val server=c.getHeaderField("Server") ?: ""
                "اتصال برقرار شد. پاسخ روتر: HTTP $code ${if(server.isNotBlank()) "• $server" else ""}\nهیچ تنظیمی تغییر نکرد."
            } catch(e:Exception){ "اتصال برقرار نشد: ${e.message ?: "خطای نامشخص"}" }
            runOnUiThread { status.text=result }
        }
    }
}