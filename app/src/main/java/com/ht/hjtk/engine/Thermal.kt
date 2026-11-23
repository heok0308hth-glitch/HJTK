package com.ht.hjtk.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 열 예보 조율기 — 뜨거워진 '뒤'가 아니라 뜨거워지기 '전'에 일을 줄인다.
 *
 *  1) 예보: Android 열 여유 예보(getThermalHeadroom, 10초 뒤 예측) + 열 상태 + 배터리 온도 + 절전 모드를 합쳐
 *     0 시원 · 1 따뜻 · 2 뜨거움 · 3 위험 네 단계로 나눈다(오르내림 문턱을 달리 해 들쭉날쭉하지 않게).
 *  2) 단계에 따라 모든 무거운 일이 스스로 속도를 낮춘다:
 *     웹 AI 확인 간격 0.7→2.4초 · 음성 엔진 스레드 4→1 · 실시간 글자 갱신 90→600ms · 화면 애니메이션 끔
 *  3) 미룰 수 있는 일(통화 대본 미리 녹음·짧은 말 미리 만들기)은 시원할 때나 충전 중에만, 뜨거우면 식을 때까지 쉰다.
 *  4) 보이지 않는 웹 화면(DeepSeek·Claude…)은 애니메이션·깜빡이는 커서를 얼려 GPU를 쉬게 한다(별도 처리).
 */
object Thermal {
    val level = MutableStateFlow(0)
    val hot = MutableStateFlow(false)            // 예전 이름(level ≥ 2)
    val headroom = MutableStateFlow(Float.NaN)   // 1.0이면 곧 성능 제한
    val batteryC = MutableStateFlow(Float.NaN)
    val charging = MutableStateFlow(false)
    private var pm: PowerManager? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install(ctx: Context) {
        app = ctx.applicationContext
        pm = ctx.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29) runCatching { pm?.addThermalStatusListener(ctx.mainExecutor) { sample() } }
        sample()
        scope.launch { while (true) { delay(if (level.value >= 2) 10_000 else 20_000); sample() } }
    }

    fun sample() {
        val p = pm
        val status = if (Build.VERSION.SDK_INT >= 29 && p != null) p.currentThermalStatus else 0
        val hr = if (Build.VERSION.SDK_INT >= 30 && p != null) runCatching { p.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
        val bat = app?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.div(10f) ?: Float.NaN
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        headroom.value = hr; batteryC.value = t; charging.value = plugged
        level.value = decide(level.value, status, hr, t, p?.isPowerSaveMode == true)
        hot.value = level.value >= 2
        com.ht.hjtk.ui.Motion.enabled = level.value == 0
    }

    /** 순수 함수(시험 가능): 오를 때는 빨리, 내릴 때는 한 단계씩 여유를 두고 */
    fun decide(prev: Int, status: Int, headroom: Float, batteryC: Float, powerSave: Boolean): Int {
        var raw = 0
        if (!headroom.isNaN()) raw = maxOf(raw, when { headroom >= 0.95f -> 3; headroom >= 0.8f -> 2; headroom >= 0.6f -> 1; else -> 0 })
        if (!batteryC.isNaN()) raw = maxOf(raw, when { batteryC >= 44f -> 3; batteryC >= 41f -> 2; batteryC >= 38f -> 1; else -> 0 })
        raw = maxOf(raw, when { status >= 4 -> 3; status >= 3 -> 2; status >= 2 -> 1; else -> 0 })   // SEVERE 3, MODERATE 2, LIGHT 1
        if (powerSave) raw = maxOf(raw, 1)
        // 내릴 때 문턱: 한 단계 아래로만, 그리고 원래 기준보다 조금 더 식어야
        if (raw < prev) {
            val cooler = (headroom.isNaN() || headroom < 0.55f + 0.15f * (prev - 1)) && (batteryC.isNaN() || batteryC < 36.5f + 2.5f * (prev - 1))
            return if (cooler) prev - 1 else prev
        }
        return raw
    }

    /** 확인 간격(웹 AI 답 기다리기 등) */
    fun poll(base: Long): Long = base * when (level.value) { 0 -> 10; 1 -> 16; 2 -> 23; else -> 34 } / 10
    /** 음성 엔진 스레드 */
    fun threads(): Int = when (level.value) { 0 -> (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4); 1 -> 3; 2 -> 2; else -> 1 }
    val liveIntervalMs: Long get() = when (level.value) { 0 -> 90L; 1 -> 200L; 2 -> 400L; else -> 600L }
    /** 미룰 수 있는 무거운 일을 지금 해도 되나 */
    fun canDefer(): Boolean = level.value >= 2 && !charging.value
    /** 뜨거우면 식을 때까지(최대 [maxMs]) 쉰다 */
    suspend fun coolDown(maxMs: Long = 45_000) { if (canDefer()) withTimeoutOrNull(maxMs) { while (canDefer()) { delay(3_000); sample() } } }

    fun label(): String = when (level.value) { 0 -> "시원함"; 1 -> "따뜻함 · 조금 덜 일하는 중"; 2 -> "뜨거움 · 절반 속도"; else -> "위험 · 최소로만" }

    /** 보이지 않는 웹 화면의 그리기를 멈추는 CSS(애니메이션·전환·커서 깜빡임) */
    const val FREEZE_CSS = "(function(){var id='__hjtk_freeze';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;" +
        "s.textContent='*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important;scroll-behavior:auto!important}';(document.head||document.documentElement).appendChild(s)})()"
    const val THAW_CSS = "(function(){var s=document.getElementById('__hjtk_freeze');if(s)s.remove()})()"
}
