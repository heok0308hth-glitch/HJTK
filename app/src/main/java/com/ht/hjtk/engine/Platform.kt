package com.ht.hjtk.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ht.hjtk.MainActivity
import com.ht.hjtk.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/** 앱이 화면에 있는가. 백그라운드에서는 액티비티를 직접 띄울 수 없다(BAL). */
object AppVisibility : DefaultLifecycleObserver {
    val foreground = MutableStateFlow(false)
    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner) { foreground.value = true }
    override fun onStop(owner: LifecycleOwner) { foreground.value = false }
}

object Clip {
    fun copy(ctx: Context, text: String, sensitive: Boolean) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("HJTK", text)
        if (sensitive && Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }
    /** 포그라운드에서만 의미가 있다(Android 10+). */
    fun read(ctx: Context): String? = runCatching { ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
}

/** 알림 채널: 세션(조용히) · 보고(무음) · 당신이 필요해요(헤즈업) */
object Notifier {
    const val CH_SESSION = "session"; const val CH_REPORT = "report"; const val CH_NEED = "needs_you"
    const val ID_SESSION = 1001; private var seq = 2000

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SESSION, "작업 진행", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_REPORT, "5분 보고", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        nm.createNotificationChannel(NotificationChannel(CH_NEED, "허락·질문", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context, route: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { route?.let { putExtra("route", it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun session(ctx: Context, title: String, text: String, progress: Int?): Notification =
        NotificationCompat.Builder(ctx, CH_SESSION).setSmallIcon(R.drawable.ic_stat_hjtk).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openApp(ctx, "tasks")).apply { progress?.let { setProgress(100, it, false) } }.build()

    fun report(ctx: Context, jobId: String, title: String, body: String) {
        val n = NotificationCompat.Builder(ctx, CH_REPORT).setSmallIcon(R.drawable.ic_stat_hjtk).setContentTitle(title).setContentText(body.lines().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setOnlyAlertOnce(true).setAutoCancel(true).setGroup("reports")
            .setContentIntent(openApp(ctx, "job:$jobId", jobId.hashCode())).build()
        post(ctx, ("rep" + jobId).hashCode(), n)
    }

    fun need(ctx: Context, key: String, title: String, body: String, pi: PendingIntent? = null): Int {
        val id = key.hashCode()
        val n = NotificationCompat.Builder(ctx, CH_NEED).setSmallIcon(R.drawable.ic_stat_hjtk).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(pi ?: openApp(ctx, "need:$key", id)).build()
        post(ctx, id, n); return id
    }

    fun cancel(ctx: Context, key: String) = ctx.getSystemService(NotificationManager::class.java).cancel(key.hashCode())

    fun simple(ctx: Context, title: String, body: String) = post(ctx, seq++,
        NotificationCompat.Builder(ctx, CH_REPORT).setSmallIcon(R.drawable.ic_stat_hjtk).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(openApp(ctx)).build())

    private fun post(ctx: Context, id: Int, n: Notification) = runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
}

/** 다른 앱 띄우기. 앱이 화면에 있으면 바로, 아니면 '탭하면 실행' 알림(사용자 제스처 게이트). */
class Launcher(private val ctx: Context) {
    fun launch(intent: Intent, title: String, body: String, customTab: Boolean = false): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AppVisibility.foreground.value) {
            return runCatching {
                if (customTab && intent.data != null) openCustomTab(ctx, intent.data.toString()) else ctx.startActivity(intent)
                "열었습니다"
            }.getOrElse { "열지 못함: ${it.message}" }
        }
        val pi = PendingIntent.getActivity(ctx, (title + System.nanoTime()).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notifier.need(ctx, "launch:$title", title, body + "\n탭하면 실행됩니다.", pi)
        return "사용자 탭 대기(알림으로 요청함)"
    }
}

/** 접근성(L3) 서비스와 앱 사이의 공유 상태. 서비스는 '요청이 있을 때만' 읽는다. */
object A11yBridge {
    data class Answer(val pkg: String, val text: String)
    data class Screen(val pkg: String, val text: String, val shot: File?)
    @Volatile var armedPkg: String? = null; private set
    @Volatile var armedPrompt: String = ""; private set
    val answers = MutableSharedFlow<Answer>(extraBufferCapacity = 4)
    @Volatile var service: HjtkAccessibility? = null
    @Volatile var lastPing = 0L

    fun arm(pkg: String, prompt: String) { armedPkg = pkg; armedPrompt = prompt }
    fun disarm() { armedPkg = null; armedPrompt = "" }
    fun enabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        val me = ComponentName(ctx, HjtkAccessibility::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == me.packageName && it.resolveInfo.serviceInfo.name == me.className
        }
    }
    /** 서비스가 실제로 붙어 있는가(제한된 설정에서 '켜진 것처럼 보이지만 안 붙은' 상태를 구분) */
    fun bound(): Boolean = service != null

    suspend fun readScreen(withShot: Boolean): Screen? = service?.snapshot(withShot)
}

/**
 * L3 — 사이드로드 전용 가속기. 상시 감시가 아니다.
 *  · Gemini/DeepSeek 앱 답을 읽을 때(arm된 동안)  · 사용자가 '이 화면 보내기'를 눌렀을 때
 * 그 외 이벤트는 버린다. 키 입력·비밀번호 필드는 읽지 않는다.
 */
class HjtkAccessibility : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null
    private var lastText = ""; private var stableSince = 0L

    override fun onServiceConnected() { A11yBridge.service = this; A11yBridge.lastPing = System.currentTimeMillis() }
    override fun onUnbind(intent: Intent?): Boolean { A11yBridge.service = null; return super.onUnbind(intent) }
    override fun onDestroy() { A11yBridge.service = null; scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        A11yBridge.lastPing = System.currentTimeMillis()
        val pkg = A11yBridge.armedPkg ?: return
        if (e?.packageName?.toString() != pkg) return
        pending?.cancel()
        pending = scope.launch {
            delay(400)
            val root = rootInActiveWindow ?: return@launch
            if (root.packageName?.toString() != pkg) return@launch
            val texts = collect(root)
            val ans = extractAfterPrompt(texts, A11yBridge.armedPrompt)
            if (ans.length < 2) return@launch
            val now = System.currentTimeMillis()
            if (ans != lastText) { lastText = ans; stableSince = now; delay(2600); }
            val root2 = rootInActiveWindow ?: return@launch
            val again = extractAfterPrompt(collect(root2), A11yBridge.armedPrompt)
            if (again == lastText && again.length >= 2) { A11yBridge.answers.tryEmit(A11yBridge.Answer(pkg, again)); A11yBridge.disarm(); lastText = "" }
        }
    }

    private fun collect(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(); val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); var n = 0; var chars = 0
        while (q.isNotEmpty() && n < 600 && chars < 40_000) {
            val node = q.removeFirst(); n++
            if (node.isPassword) continue
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it; chars += it.length }
            for (i in 0 until node.childCount) node.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    private val uiNoise = Regex("^(복사|공유|좋아요|싫어요|다시 생성|수정|더보기|Copy|Share|Regenerate|Edit|More|Gemini|DeepSeek|메시지.*|Ask Gemini|Gemini에게 물어보기)$")

    private fun extractAfterPrompt(texts: List<String>, prompt: String): String {
        val head = prompt.trim().take(24)
        val idx = texts.indexOfLast { head.isNotEmpty() && (it.contains(head) || head.contains(it.take(24))) }
        val tail = if (idx >= 0) texts.drop(idx + 1) else texts.takeLast(6)
        return tail.filter { it.length > 1 && !uiNoise.matches(it.trim()) }.joinToString("\n").trim()
    }

    suspend fun snapshot(withShot: Boolean): A11yBridge.Screen? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: "?"
        val text = collect(root).joinToString("\n").take(12_000)
        var file: File? = null
        if (withShot && Build.VERSION.SDK_INT >= 30) {
            val d = CompletableDeferred<File?>()
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    val f = File(filesDir, "workspace/shared/screen_${System.currentTimeMillis()}.png").apply { parentFile?.mkdirs() }
                    runCatching { FileOutputStream(f).use { bmp?.compress(Bitmap.CompressFormat.PNG, 90, it) } }
                    r.hardwareBuffer.close(); d.complete(f)
                }
                override fun onFailure(code: Int) { d.complete(null) }
            })
            file = withTimeoutOrNull(4000) { d.await() }
        }
        return A11yBridge.Screen(pkg, text, file)
    }
}

object Perms {
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun overlayIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun a11yIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun appInfo(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun batteryIntent(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun ignoringBattery(ctx: Context) = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
