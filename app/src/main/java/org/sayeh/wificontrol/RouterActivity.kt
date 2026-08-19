package org.sayeh.wificontrol

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class RouterActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var baseUrl: String
    private lateinit var user: String
    private lateinit var pass: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_router)

        baseUrl = intent.getStringExtra("baseUrl")?.trimEnd('/') ?: "http://192.168.1.1"
        user = intent.getStringExtra("user").orEmpty()
        pass = intent.getStringExtra("pass").orEmpty()

        web = findViewById(R.id.routerWeb)
        status = findViewById(R.id.routerStatus)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                handler?.proceed(user, pass)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "پنل روتر باز شد • ${url ?: baseUrl}"
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    status.text = "باز شدن صفحه ناموفق بود: ${error?.description ?: "خطای نامشخص"}"
                }
            }
        }

        findViewById<Button>(R.id.backBtn).setOnClickListener {
            if (web.canGoBack()) web.goBack() else finish()
        }
        findViewById<Button>(R.id.homeBtn).setOnClickListener { loadAuthenticated(baseUrl) }
        findViewById<Button>(R.id.statusBtn).setOnClickListener { loadAuthenticated("$baseUrl/rpSys.html") }

        loadAuthenticated(intent.getStringExtra("startUrl") ?: baseUrl)
    }

    private fun loadAuthenticated(url: String) {
        status.text = "در حال باز کردن…"
        val headers = if (user.isNotBlank()) {
            val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            mapOf("Authorization" to "Basic $token")
        } else emptyMap()
        web.loadUrl(url, headers)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}