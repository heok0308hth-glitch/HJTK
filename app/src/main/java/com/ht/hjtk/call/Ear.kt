package com.ht.hjtk.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 통화 귀(별도 앱 com.ht.hjtk.ear)와 이야기하는 쪽.
 * Android는 통화 중 소리를 접근성 서비스에만 허락하고, Play 프로텍트는 그런 앱의 설치를 막는다.
 * 그래서 그 일만 작은 앱에 맡기고, HJTK 본체는 접근성 없이 깨끗하게 설치된다.
 * 주고받기는 같은 서명끼리만 열리는 권한(com.ht.hjtk.permission.EAR)으로.
 */
object Ear {
    const val PKG = "com.ht.hjtk.ear"
    private const val PERM = "com.ht.hjtk.permission.EAR"
    val enabled = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val listening = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var app: Context? = null
    private var onText: ((String) -> Unit)? = null
    private var onHeard: (() -> Unit)? = null
    private var registered = false

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)
    fun isOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return (installed(ctx) && s.contains("$PKG/")).also { enabled.value = it }
    }
    fun setupIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage(PKG)

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.getStringExtra("text")?.let { t -> onText?.invoke(t) }
            if (i.hasExtra("heard")) onHeard?.invoke()
            i.getStringExtra("level")?.toFloatOrNull()?.let { level.value = it }
            i.getStringExtra("error")?.let { error.value = it }
            when (i.getStringExtra("state")) { "listening" -> listening.value = true; "idle", "off" -> { listening.value = false; level.value = 0f } }
        }
    }

    private fun send(ctx: Context, action: String) = ctx.sendBroadcast(Intent(action).setPackage(PKG), PERM)

    /** 듣기 시작: 받아 적은 글은 [text], 말이 막 끝난 순간은 [heard](맞장구용) */
    fun start(ctx: Context, heard: () -> Unit, text: (String) -> Unit) {
        app = ctx.applicationContext; onText = text; onHeard = heard; error.value = null
        if (!registered) {
            val f = IntentFilter("com.ht.hjtk.EAR_TEXT")
            if (Build.VERSION.SDK_INT >= 33) app!!.registerReceiver(rx, f, PERM, null, Context.RECEIVER_EXPORTED) else app!!.registerReceiver(rx, f, PERM, null)
            registered = true
        }
        send(app!!, "com.ht.hjtk.ear.START")
    }

    fun stop() { app?.let { send(it, "com.ht.hjtk.ear.STOP") }; listening.value = false; level.value = 0f; onText = null; onHeard = null }

    /** AI가 말하는 동안은 듣지 않게(자기 목소리를 받아 적지 않게) */
    var muted: Boolean = false
        set(v) { field = v; app?.let { send(it, if (v) "com.ht.hjtk.ear.MUTE" else "com.ht.hjtk.ear.UNMUTE") } }
}
