package com.ht.hjtk.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 발열 관리: 기기가 뜨거워지거나(열 상태 '보통' 이상) 절전 모드면 '덜 일하는 모드'.
 *  - 화면 애니메이션 끔 · 실시간 글자 갱신 간격 90ms → 450ms · DeepSeek 창 렌더링 일시정지 빨리
 */
object Thermal {
    val hot = MutableStateFlow(false)
    fun install(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return
        fun eval(status: Int) {
            val h = status >= PowerManager.THERMAL_STATUS_MODERATE || pm.isPowerSaveMode
            hot.value = h
            com.ht.hjtk.ui.Motion.enabled = !h
        }
        if (Build.VERSION.SDK_INT >= 29) { eval(pm.currentThermalStatus); runCatching { pm.addThermalStatusListener(ctx.mainExecutor) { eval(it) } } }
        else eval(0)
    }
    val liveIntervalMs get() = if (hot.value) 450L else 90L
}
