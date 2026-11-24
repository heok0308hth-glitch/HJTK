package com.ht.hjtk.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 만든 웹 앱·완성본을 앱 안에서 바로 연다(설치 전에 실행해 보기). 다른 앱으로 넘어가지 않는다 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainVm, path: String, title: String) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, "미리보기 · 인터넷 없이", onBack = { vm.back() })
        if (Motion.test) return@Column
        AndroidView(factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = true
                webViewClient = WebViewClient()
                loadUrl("file://$path")
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
