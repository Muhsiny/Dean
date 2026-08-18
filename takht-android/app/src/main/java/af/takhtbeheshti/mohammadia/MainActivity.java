package af.takhtbeheshti.mohammadia;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String HOME = "https://app-61p2x9.v2.appdeploy.ai/?app=takhtbeheshti&native=android&apk=1.0.1#takhtbeheshti/home";
    private static final String SITE_HOST = "app-61p2x9.v2.appdeploy.ai";
    private static final int FILE_CHOOSER = 501;
    private static final int STORAGE_PERMISSION = 502;
    private WebView webView;
    private ProgressBar progress;
    private LinearLayout offline;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingDownloadUrl;
    private String pendingUserAgent;
    private String pendingContentDisposition;
    private String pendingMimeType;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,95,59));
        WebStorage.getInstance().deleteAllData();
        buildUi();
        configureWebView();
        if (state != null) webView.restoreState(state); else webView.loadUrl(HOME);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 6);
        pp.gravity = Gravity.TOP;
        root.addView(progress, pp);
        offline = new LinearLayout(this);
        offline.setOrientation(LinearLayout.VERTICAL);
        offline.setGravity(Gravity.CENTER);
        offline.setPadding(48,48,48,48);
        offline.setBackgroundColor(Color.rgb(247,241,229));
        TextView title = new TextView(this);
        title.setText("اتصال اینترنت برقرار نیست");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(32,42,68));
        title.setGravity(Gravity.CENTER);
        TextView text = new TextView(this);
        text.setText("برای باز کردن مدرسه، اینترنت را بررسی کنید و دوباره تلاش نمایید.");
        text.setTextSize(14);
        text.setTextColor(Color.DKGRAY);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0,18,0,22);
        Button retry = new Button(this);
        retry.setText("تلاش دوباره");
        retry.setOnClickListener(v -> { offline.setVisibility(View.GONE); webView.loadUrl(HOME); });
        offline.addView(title);
        offline.addView(text);
        offline.addView(retry);
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " TakhtBeheshtiAndroid/1.0.1 NativeShell");
        webView.clearCache(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent;
                try { intent = params.createIntent(); }
                catch (Exception e) { intent = new Intent(Intent.ACTION_GET_CONTENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); }
                try { startActivityForResult(intent, FILE_CHOOSER); }
                catch (ActivityNotFoundException e) { fileCallback = null; Toast.makeText(MainActivity.this, "انتخاب فایل در این دستگاه در دسترس نیست.", Toast.LENGTH_LONG).show(); }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return route(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return route(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (SITE_HOST.equalsIgnoreCase(uri.getHost())) {
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    if (path.endsWith("/manifest.webmanifest") || path.endsWith("/manifest.json")) return textResponse("application/manifest+json", "{}");
                    if (path.endsWith("/sw.js") || path.endsWith("/service-worker.js")) return textResponse("application/javascript", "self.addEventListener('install',function(e){self.skipWaiting();});self.addEventListener('activate',function(e){e.waitUntil(self.registration.unregister());});");
                }
                return super.shouldInterceptRequest(view, request);
            }
            @Override public void onPageCommitVisible(WebView view, String url) { suppressInstallUi(); }
            @Override public void onPageFinished(WebView view, String url) {
                if (isOnline()) offline.setVisibility(View.GONE);
                suppressInstallUi();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) offline.setVisibility(View.VISIBLE);
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> download(url, userAgent, contentDisposition, mimeType));
    }

    private WebResourceResponse textResponse(String mimeType, String body) {
        return new WebResourceResponse(mimeType, "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void suppressInstallUi() {
        String js = "(function(){try{window.__TAKHT_NATIVE__=true;document.documentElement.setAttribute('data-native-apk','true');document.querySelectorAll('.install-controls,.install-strip,.install-banner,.pwa-install,.install-prompt').forEach(function(n){n.remove();});window.addEventListener('beforeinstallprompt',function(e){e.preventDefault();e.stopImmediatePropagation();},true);if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.unregister();});});}}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean route(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ((scheme.equals("http") || scheme.equals("https")) && SITE_HOST.equalsIgnoreCase(uri.getHost())) return false;
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
            catch (Exception e) { Toast.makeText(this, "باز کردن این لینک ممکن نشد.", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        return false;
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        pendingDownloadUrl = url;
        pendingUserAgent = userAgent;
        pendingContentDisposition = contentDisposition;
        pendingMimeType = mimeType;
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
            return;
        }
        startDownload();
    }

    private void startDownload() {
        try {
            String fileName = URLUtil.guessFileName(pendingDownloadUrl, pendingContentDisposition, pendingMimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pendingDownloadUrl));
            request.setTitle(fileName);
            request.setDescription("در حال دانلود از مدرسه علمیه محمدیه تخت");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (pendingMimeType != null) request.setMimeType(pendingMimeType);
            if (pendingUserAgent != null) request.addRequestHeader("User-Agent", pendingUserAgent);
            String cookies = CookieManager.getInstance().getCookie(pendingDownloadUrl);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "دانلود آغاز شد.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingDownloadUrl))); }
            catch (Exception ignored) { Toast.makeText(this, "دانلود ممکن نشد.", Toast.LENGTH_LONG).show(); }
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(network);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startDownload();
    }

    @Override public void onBackPressed() { if (webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle outState) { webView.saveState(outState); super.onSaveInstanceState(outState); }
}
