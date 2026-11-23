package com.ht.hjtk.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.ht.hjtk.Hjtk
import com.ht.hjtk.ui.CallScreen
import com.ht.hjtk.ui.HjtkTheme

/** 통화 화면 · 다이얼(잠금 화면 위에서도 열린다). 기본 전화 앱이 되려면 ACTION_DIAL을 받는 화면이 있어야 한다 */
class CallActivity : ComponentActivity() {
    private val dialNumber = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        val h = Hjtk.get(applicationContext)
        setContent { HjtkTheme { CallScreen(h, dialNumber.value, onClose = { finish() }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent) {
        if (i.action == "answer") CallCenter.answer(i.getIntExtra("id", 0))
        i.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.let { dialNumber.value = it }
    }
}
