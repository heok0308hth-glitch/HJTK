package com.ht.hjtk.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ht.hjtk.Hjtk
import com.ht.hjtk.engine.AppVisibility
import com.ht.hjtk.engine.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 세션 창(specialUse FGS). 사용자가 시작한 작업이 화면 밖에서도 이어지게 하고, 끝나면 스스로 내려간다.
 * 보고·허락 요청은 알림으로 전한다(화면 위에 그리는 것은 없다 → 배터리·발열 최소).
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.ht.hjtk.engine.CrashLog.handler("service"))
    private var idleSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val n = Notifier.session(this, "HJTK가 일하는 중", "작업을 준비하고 있어요", null)
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, Notifier.ID_SESSION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ID_SESSION, n)
        val h = Hjtk.get(this)
        scope.launch {
            combine(h.runner.liveCount, AppVisibility.foreground) { live, fg -> live to fg }.collect { (live, fg) ->
                if (!fg) h.deepseek.park()
                idleSince = if (live == 0) (if (idleSince == 0L) System.currentTimeMillis() else idleSince) else 0L
                update(this@AgentService)
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                if (h.runner.liveCount.value == 0 && !h.pc.state.value.running && idleSince > 0 && System.currentTimeMillis() - idleSince > 90_000) { stopSelf(); break }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY   // 유령 서비스가 보고를 중복 발행하지 않게

    override fun onTimeout(startId: Int, fgsType: Int) {   // Android 15+: 시간 예산 소진 → 정직하게 내려간다
        Notifier.simple(this, "세션 창 종료", "시스템 제한으로 백그라운드 진행을 멈췄어요. 앱을 열면 이어집니다.")
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: AgentService? = null
        fun update(ctx: Context) {
            val svc = instance ?: return
            val h = Hjtk.get(ctx)
            val running = h.store.jobs.value.filter { it.running }
            val title = if (running.isEmpty()) "HJTK 대기 중" else if (running.size == 1) running[0].goal.take(40) else "작업 ${running.size}개 진행 중"
            val j = running.firstOrNull()
            val text = j?.let { listOfNotNull(it.now.takeIf { s -> s.isNotBlank() }?.let { s -> "지금: $s" }, it.next.takeIf { s -> s.isNotBlank() }?.let { s -> "다음: $s" }, it.eta.takeIf { s -> s.isNotBlank() }?.let { s -> "남은 시간 $s" }, if (it.state.startsWith("PAUSED")) "⏸ ${it.label}" else null).joinToString(" · ") } ?: "작업이 끝났어요"
            runCatching { svc.getSystemService(NotificationManager::class.java).notify(Notifier.ID_SESSION, Notifier.session(svc, title, text, j?.let { (it.progress * 100).toInt() })) }
        }
    }
}

