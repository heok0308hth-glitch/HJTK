package com.ht.hjtk.ear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 설정 화면 하나: 마이크 허용 → 접근성에서 켜기. 끝나면 HJTK로 돌아가면 된다 */
class SetupActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad); setBackgroundColor(Color.parseColor("#121214")) }
        fun text(s: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply { text = s; textSize = size; setTextColor(Color.parseColor(color)); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, pad / 2) }
        root.addView(text("HJTK 통화 귀", 26f, "#EDEAE3", true))
        root.addView(text("AI 통화 중에만 상대 목소리를 듣고 받아 적어 HJTK에 전해요. 화면 내용은 읽지 않고 녹음도 저장하지 않아요.", 15f, "#A19D93"))
        status = text("", 15f, "#F0573F", true); root.addView(status)
        fun button(s: String, go: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { go() } }.also { root.addView(it) }
        button("1. 마이크 허용") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        button("2. 접근성에서 'HJTK 통화 귀' 켜기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.gravity = Gravity.TOP
        setContentView(root)
    }
    override fun onResume() {
        super.onResume()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val on = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("com.ht.hjtk.ear/")
        status.text = when { mic && on -> "준비 완료 · HJTK로 돌아가세요"; !mic -> "마이크를 허용해 주세요"; else -> "접근성에서 켜 주세요" }
        status.setTextColor(Color.parseColor(if (mic && on) "#EDEAE3" else "#F0573F"))
    }
}
