package com.ht.hjtk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ht.hjtk.agent.JobRunner
import com.ht.hjtk.data.Store
import com.ht.hjtk.engine.AppVisibility
import com.ht.hjtk.engine.DeepSeekApi
import com.ht.hjtk.engine.DeepSeekAppPort
import com.ht.hjtk.engine.DeepSeekWeb
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.GeminiApi
import com.ht.hjtk.engine.Launcher
import com.ht.hjtk.engine.Notifier
import com.ht.hjtk.engine.RelayInbox
import java.util.concurrent.TimeUnit

/** 수동 DI 컨테이너. 확장할 때는 여기에 모듈을 하나 더 꽂는다. */
class Hjtk(val app: Context) {
    val store = Store(app)
    val launcher = Launcher(app)
    val deepseek = DeepSeekWeb(app)
    val deepseekApi = DeepSeekApi(store)
    val gemini = GeminiApi(store)
    val bridge = ExternalAiBridge(app, launcher)
    val deepseekApp = DeepSeekAppPort(bridge, store)
    val runner by lazy { JobRunner(this) }

    companion object {
        @Volatile private var inst: Hjtk? = null
        fun get(ctx: Context): Hjtk = inst ?: synchronized(this) { inst ?: Hjtk(ctx.applicationContext).also { inst = it } }
    }
}

class HjtkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.ht.hjtk.engine.CrashLog.install(this)
        Notifier.channels(this)
        AppVisibility.install()
        val h = Hjtk.get(this)
        h.runner   // 중단된 작업 표시
        // 지난 실행이 비정상 종료였다면, 원인을 가장 최근 대화에 한 번 보여 준다(사용자가 그대로 복사해 전달할 수 있게)
        com.ht.hjtk.engine.CrashLog.takeLastCrash(this)?.let { full ->
            val conv = h.store.conversations.value.maxByOrNull { it.updatedAt }?.id
            if (conv != null) h.store.msg(conv, com.ht.hjtk.data.Role.SYSTEM, "지난번 앱이 예기치 않게 종료됐어요. 같은 문제가 반복되면 아래 내용을 복사해 알려 주세요.\n" + com.ht.hjtk.engine.CrashLog.summary(full), "error", mapOf("via" to "CRASH", "detail" to full.take(6000)))
        }
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("hjtk.resume-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ResumeCheckWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}

/** 15분 폴백: 실행 중이어야 할 작업이 죽어 있으면 알린다(재실행은 사용자가). */
class ResumeCheckWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val h = Hjtk.get(applicationContext)
        val stuck = h.store.jobs.value.count { it.state == "SUSPENDED" }
        if (stuck > 0) Notifier.simple(applicationContext, "멈춘 작업 ${stuck}개", "탭해서 이어서 진행할 수 있어요")
        return Result.success()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    // Android 15+: 부팅 리시버에서 FGS 시작 금지 → 알림만
    override fun onReceive(context: Context, intent: Intent) {
        val n = Hjtk.get(context).store.jobs.value.count { it.state == "SUSPENDED" || it.running }
        if (n > 0) Notifier.simple(context, "재개 대기 중인 작업 ${n}개", "HJTK를 열면 이어서 진행할 수 있어요")
    }
}

/** L7: 다른 앱(Gemini·DeepSeek 앱)의 답을 '공유' 또는 '텍스트 선택 → HJTK'로 받는다. 이미지 공유는 화면 공유 요청에 쓴다. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = Hjtk.get(this)
        when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { RelayInbox.submit(it, "L7_PROCESS_TEXT") }
            Intent.ACTION_SEND -> {
                val t = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (t != null) {
                    // 기다리는 작업이 없으면 새 대화로 가져온다
                    if (RelayInbox.flow.subscriptionCount.value > 0) RelayInbox.submit(t, "L7_SHARE")
                    else startActivity(Intent(this, MainActivity::class.java).putExtra("shared", t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                    val f = java.io.File(h.store.workspace, "shared/shot_${System.currentTimeMillis()}.png").apply { parentFile?.mkdirs() }
                    runCatching { contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
                    h.runner.fulfillScreen("사용자가 스크린샷을 공유했다(${f.name}). HJTK는 이미지 속 글자를 읽지 않는다(온디바이스 AI 없음). 필요한 내용은 task.ask로 사용자에게 물어보라.")
                }
            }
        }
        android.widget.Toast.makeText(this, "HJTK가 받았어요", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}

class PickActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val h = Hjtk.get(this)
        val text = uri?.let { u -> runCatching { contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText().take(200_000) } }.getOrNull() }
        h.runner.fulfillPick(text ?: if (uri != null) "(텍스트로 읽을 수 없는 파일)" else null)
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); pick.launch(arrayOf("text/*", "application/json", "application/csv", "*/*")) }
}
