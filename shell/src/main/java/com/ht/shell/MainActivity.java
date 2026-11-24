package com.ht.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** HJTK가 만든 웹 앱을 전체 화면으로 띄운다. 인터넷 없이 assets/www/index.html만으로 돈다 */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0E0E0E"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E0E0E"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E0E0E"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        if (b != null) web.restoreState(b); else web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
