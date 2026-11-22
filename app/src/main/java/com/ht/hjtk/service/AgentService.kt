package com.ht.hjtk.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.res.ResourcesCompat
import com.ht.hjtk.Hjtk
import com.ht.hjtk.MainActivity
import com.ht.hjtk.R
import com.ht.hjtk.agent.PetCue
import com.ht.hjtk.engine.AppVisibility
import com.ht.hjtk.engine.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * 세션 창(specialUse FGS). 사용자가 시작한 작업이 화면 밖에서도 이어지게 하고, 끝나면 스스로 내려간다.
 * 다른 앱 위 표시 권한이 있으면 마스코트 '쥬티'가 화면을 돌아다니며 5분 보고를 전한다.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mascot: Mascot? = null
    private var idleSince = 0L
    private var lastCueAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val n = Notifier.session(this, "HJTK가 일하는 중", "작업을 준비하고 있어요", null)
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, Notifier.ID_SESSION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ID_SESSION, n)
        val h = Hjtk.get(this)
        scope.launch {
            combine(h.runner.liveCount, AppVisibility.foreground, h.store.settings) { live, fg, s -> Triple(live, fg, s.petEnabled) }.collect { (live, fg, pet) ->
                if (!fg) h.deepseek.park()
                val recent = System.currentTimeMillis() - lastCueAt < 90_000
                val want = pet && !fg && (live > 0 || recent) && Settings.canDrawOverlays(this@AgentService)
                if (want && mascot == null) mascot = Mascot(this@AgentService).also { it.show() }
                if (!want) { mascot?.hide(); mascot = null }
                idleSince = if (live == 0) (if (idleSince == 0L) System.currentTimeMillis() else idleSince) else 0L
                update(this@AgentService)
            }
        }
        scope.launch { h.runner.pet.collect { c -> lastCueAt = System.currentTimeMillis(); mascot?.cue(c) } }
        scope.launch {
            while (true) {
                delay(15_000)
                if (h.runner.liveCount.value == 0 && idleSince > 0 && System.currentTimeMillis() - idleSince > 90_000) { stopSelf(); break }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY   // 유령 서비스가 보고를 중복 발행하지 않게

    override fun onTimeout(startId: Int, fgsType: Int) {   // Android 15+: 시간 예산 소진 → 정직하게 내려간다
        Notifier.simple(this, "세션 창 종료", "시스템 제한으로 백그라운드 진행을 멈췄어요. 앱을 열면 이어집니다.")
        stopSelf()
    }

    override fun onDestroy() { mascot?.hide(); scope.cancel(); instance = null; super.onDestroy() }

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

/**
 * 마스코트 '쥬티' — 스프라이트 시트(8열×11행, 160px 셀)를 프레임 단위로 그리는 오버레이.
 * 행: 0 대기 · 1 오른쪽 달리기 · 2 왼쪽 달리기 · 3 손 흔들기 · 4 점프 · 5 잠 · 6 앉기 · 7·8 엎드림 · 9 두리번 · 10 살금살금
 * 펫은 장식이 아니라 인터페이스다: 보고를 말풍선으로 전하고, 누르면 '이 화면 보내기'가 된다.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Mascot(private val svc: Service) : View(svc) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val atlas: Bitmap = BitmapFactory.decodeResource(svc.resources, R.drawable.mascot_atlas, BitmapFactory.Options().apply { inScaled = false })
    private val frames = intArrayOf(6, 8, 8, 4, 5, 8, 6, 6, 6, 8, 8)
    private val d = svc.resources.displayMetrics.density
    private val cat = (78 * d).toInt()
    private val bubbleW = (220 * d).toInt()
    private val bubbleH = (86 * d).toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class Anim(val row: Int, val fps: Int, val loop: Boolean) {
        IDLE(0, 5, true), RUN_R(1, 12, true), RUN_L(2, 12, true), WAVE(3, 6, true), JUMP(4, 10, false), SLEEP(5, 3, true),
        SIT(6, 4, true), LOAF(7, 4, true), POUT(8, 4, true), LOOK(9, 6, true), SNEAK(10, 8, true)
    }
    private var anim = Anim.IDLE; private var frame = 0; private var mirror = false
    private var x = 0f; private var targetX = -1f
    private val screenW get() = svc.resources.displayMetrics.widthPixels
    private val screenH get() = svc.resources.displayMetrics.heightPixels
    private var bubble: String? = null; private var bubbleUrgent = false; private var bubbleUntil = 0L
    private val lp = WindowManager.LayoutParams(cat, cat,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private var baseY = 0

    private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF121212.toInt() }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2442F.toInt() }
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F0E8.toInt(); textSize = 12.5f * d
        typeface = runCatching { ResourcesCompat.getFont(svc, R.font.pretendard_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }
    private val labelP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A958B.toInt(); textSize = 9f * d; typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }

    fun show() {
        x = (screenW - cat - 16 * d); baseY = (screenH - cat - 140 * d).toInt()
        lp.x = x.toInt(); lp.y = baseY
        runCatching { wm.addView(this, lp) }
        scope.launch { tick() }
        scope.launch { brain() }
        cue(PetCue("일하는 중이에요. 5분마다 알려드릴게요", PetCue.Kind.HELLO))
    }

    fun hide() { scope.cancel(); runCatching { wm.removeView(this) } }

    fun cue(c: PetCue) {
        bubble = c.text; bubbleUrgent = c.kind == PetCue.Kind.NEED || c.kind == PetCue.Kind.SCREEN
        bubbleUntil = System.currentTimeMillis() + if (bubbleUrgent) 30_000 else 9_000
        targetX = -1f
        play(when (c.kind) { PetCue.Kind.REPORT, PetCue.Kind.HELLO -> Anim.WAVE; PetCue.Kind.DONE, PetCue.Kind.NEED -> Anim.JUMP; PetCue.Kind.SCREEN -> Anim.LOOK })
        resize()
    }

    private fun play(a: Anim) { if (anim != a) { anim = a; frame = 0 } }

    private fun resize() {
        val withBubble = bubble != null
        val w = if (withBubble) maxOf(bubbleW, cat) else cat
        val hgt = if (withBubble) cat + bubbleH else cat
        lp.width = w; lp.height = hgt
        lp.y = if (withBubble) baseY - bubbleH else baseY
        place()
    }

    private fun place() {
        lp.x = if (lp.width > cat) (x + cat / 2 - lp.width / 2).toInt().coerceIn(0, screenW - lp.width) else x.toInt()
        runCatching { wm.updateViewLayout(this, lp) }
        invalidate()
    }

    private suspend fun tick() {
        var acc = 0L
        while (true) {
            delay(40); acc += 40
            if (bubble != null && System.currentTimeMillis() > bubbleUntil) { bubble = null; resize() }
            // 이동
            if (targetX >= 0) {
                val speed = if (anim == Anim.SNEAK) 1.6f * d else 4.2f * d
                val dx = targetX - x
                if (abs(dx) <= speed) { x = targetX; targetX = -1f; play(listOf(Anim.IDLE, Anim.SIT, Anim.LOOK).random()) }
                else { x += if (dx > 0) speed else -speed; if (anim != Anim.SNEAK) play(if (dx > 0) Anim.RUN_R else Anim.RUN_L); mirror = anim == Anim.SNEAK && dx < 0 }
                place()
            }
            if (acc >= 1000L / anim.fps) {
                acc = 0
                val n = frames[anim.row]
                frame = if (anim.loop) (frame + 1) % n else minOf(frame + 1, n - 1)
                if (!anim.loop && frame == n - 1) { delay(300); play(Anim.IDLE) }
                invalidate()
            }
        }
    }

    /** 돌아다니기: 가끔 걷고, 앉고, 두리번거리고, 오래 조용하면 잔다 */
    private suspend fun brain() {
        var quiet = 0
        while (true) {
            delay(Random.nextLong(3500, 8000))
            if (bubble != null || targetX >= 0) { quiet = 0; continue }
            quiet++
            when {
                quiet > 9 -> play(Anim.SLEEP)
                Random.nextInt(10) < 5 -> {
                    val tx = Random.nextInt((12 * d).toInt(), screenW - cat - (12 * d).toInt()).toFloat()
                    if (abs(tx - x) < 220 * d) play(Anim.SNEAK)
                    targetX = tx
                }
                else -> play(listOf(Anim.IDLE, Anim.SIT, Anim.LOAF, Anim.LOOK, Anim.POUT).random())
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val catLeft = (x - lp.x).coerceIn(0f, (width - cat).toFloat())
        val catTop = (height - cat).toFloat()
        bubble?.let { drawBubble(c, it, catLeft) }
        val src = Rect(frame * 160, anim.row * 160, frame * 160 + 160, anim.row * 160 + 160)
        val dst = RectF(catLeft, catTop, catLeft + cat, catTop + cat)
        if (mirror) { c.save(); c.scale(-1f, 1f, dst.centerX(), dst.centerY()); c.drawBitmap(atlas, src, dst, paintBmp); c.restore() } else c.drawBitmap(atlas, src, dst, paintBmp)
    }

    private fun drawBubble(c: Canvas, text: String, catLeft: Float) {
        val pad = 12 * d
        val w = width.toFloat(); val bh = bubbleH - 10 * d
        val r = RectF(2 * d, 2 * d, w - 2 * d, bh)
        val cut = 12 * d
        // 접힌 모서리(오른쪽 위) — HJTK의 접지(摺紙) 모티프
        val p = android.graphics.Path().apply {
            moveTo(r.left + 6 * d, r.top); lineTo(r.right - cut, r.top); lineTo(r.right, r.top + cut); lineTo(r.right, r.bottom - 6 * d)
            quadTo(r.right, r.bottom, r.right - 6 * d, r.bottom)
            val tipX = (catLeft + cat / 2).coerceIn(r.left + 20 * d, r.right - 20 * d)
            lineTo(tipX + 8 * d, r.bottom); lineTo(tipX, r.bottom + 9 * d); lineTo(tipX - 8 * d, r.bottom)
            lineTo(r.left + 6 * d, r.bottom); quadTo(r.left, r.bottom, r.left, r.bottom - 6 * d); lineTo(r.left, r.top + 6 * d); quadTo(r.left, r.top, r.left + 6 * d, r.top); close()
        }
        c.drawPath(p, ink)
        c.drawPath(android.graphics.Path().apply { moveTo(r.right - cut, r.top); lineTo(r.right - cut, r.top + cut); lineTo(r.right, r.top + cut); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() })
        if (bubbleUrgent) c.drawCircle(r.left + pad + 3 * d, r.top + pad + 2 * d, 3.2f * d, red)
        c.drawText(if (bubbleUrgent) "   HJTK · 확인 필요" else "HJTK · 보고", r.left + pad, r.top + pad + 6 * d, labelP)
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, textP, (r.width() - pad * 2).toInt()).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(r.left + pad, r.top + pad + 14 * d); sl.draw(c); c.restore()
    }

    private var downX = 0f; private var downY = 0f; private var startX = 0f; private var moved = false; private var downAt = 0L
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = x; moved = false; downAt = System.currentTimeMillis() }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downX) > 12 * d || abs(e.rawY - downY) > 12 * d) moved = true
                if (moved) { targetX = -1f; x = (startX + e.rawX - downX).coerceIn(0f, (screenW - cat).toFloat()); baseY = (e.rawY - cat / 2).toInt().coerceIn(0, screenH - cat); play(Anim.LOOK); resize() }
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                val h = Hjtk.get(svc)
                if (System.currentTimeMillis() - downAt > 700) { bubble = null; play(Anim.SLEEP); resize(); return true }
                if (h.runner.screenWaiting.value != null) {
                    h.runner.fulfillScreen()
                    cue(PetCue("이 화면을 AI에게 보냈어요", PetCue.Kind.REPORT))
                } else {
                    svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", "tasks"))
                }
            }
        }
        return true
    }
}
