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

    fun need(ctx: Context, key: String, title: String, body: String, pi: PendingIntent? = null, actions: List<NotificationCompat.Action> = emptyList()): Int {
        val id = key.hashCode()
        val n = NotificationCompat.Builder(ctx, CH_NEED).setSmallIcon(R.drawable.ic_stat_hjtk).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(pi ?: openApp(ctx, "need:$key", id)).apply { actions.forEach { addAction(it) } }.build()
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

object Perms {
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun overlayIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun appInfo(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun batteryIntent(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun ignoringBattery(ctx: Context) = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
