package com.ht.hjtk.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.RemoteInput
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.ai.Difficulty
import com.ht.hjtk.core.command.CommandHandler
import com.ht.hjtk.core.command.CommandResult
import com.ht.hjtk.data.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ═══════════════════════════════════════════════════════════════════
//  루틴: 정해진 시각에 작업을 스스로 시작한다(매일 아침 브리핑, 매주 공모전 확인 …)
// ═══════════════════════════════════════════════════════════════════

/** repeat: daily · weekdays · weekly(day 1=월…7=일) */
data class Routine(val id: String, val title: String, val goal: String, val repeat: String, val day: Int, val hour: Int, val minute: Int,
                   val diff: String = "STANDARD", val enabled: Boolean = true, val lastRun: Long = 0, val convId: String? = null) {
    fun toJson() = JSONObject().put("id", id).put("t", title).put("g", goal).put("r", repeat).put("d", day).put("h", hour).put("m", minute).put("diff", diff).put("on", enabled).put("last", lastRun).put("c", convId ?: "")
    fun label(): String = when (repeat) { "weekdays" -> "평일"; "weekly" -> "매주 " + "월화수목금토일"[(day - 1).coerceIn(0, 6)] + "요일"; else -> "매일" } + " %02d:%02d".format(hour, minute)
    companion object { fun from(o: JSONObject) = Routine(o.getString("id"), o.optString("t"), o.optString("g"), o.optString("r", "daily"), o.optInt("d", 1), o.optInt("h", 8), o.optInt("m"), o.optString("diff", "STANDARD"), o.optBoolean("on", true), o.optLong("last"), o.optString("c").ifBlank { null }) }

    /** 다음 실행 시각(지금 이후 가장 가까운) */
    fun next(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        repeat(8) {
            val ok = when (repeat) {
                "weekdays" -> t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY
                "weekly" -> t.dayOfWeek.value == day
                else -> true
            }
            if (ok) return t
            t = t.plusDays(1)
        }
        return t
    }
}

object Routines {
    val list = MutableStateFlow<List<Routine>>(emptyList())
    private var file: File? = null

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "hjtk/routines.json")
        list.value = runCatching { JSONArray(file!!.readText()).let { a -> (0 until a.length()).map { Routine.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }
    private fun save() = runCatching { file?.parentFile?.mkdirs(); file?.writeText(JSONArray(list.value.map { it.toJson() }).toString()) }

    fun add(ctx: Context, title: String, goal: String, repeat: String, day: Int, hour: Int, minute: Int, diff: String): Routine {
        val r = Routine("rt" + UUID.randomUUID().toString().take(8), title.ifBlank { goal.take(24) }, goal, repeat, day, hour, minute, diff)
        list.update { it + r }; save(); schedule(ctx, r); return r
    }
    fun update(ctx: Context, id: String, f: (Routine) -> Routine) { list.update { l -> l.map { if (it.id == id) f(it) else it } }; save(); list.value.firstOrNull { it.id == id }?.let { if (it.enabled) schedule(ctx, it) else cancel(ctx, it.id) } }
    fun remove(ctx: Context, id: String) { cancel(ctx, id); list.update { l -> l.filterNot { it.id == id } }; save() }

    fun schedule(ctx: Context, r: Routine) {
        val delayMs = java.time.Duration.between(LocalDateTime.now(), r.next()).toMillis().coerceAtLeast(60_000)
        WorkManager.getInstance(ctx).enqueueUniqueWork("routine_${r.id}", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RoutineWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setInputData(workDataOf("id" to r.id)).build())
    }
    fun cancel(ctx: Context, id: String) { WorkManager.getInstance(ctx).cancelUniqueWork("routine_$id") }
    fun rescheduleAll(ctx: Context) = list.value.filter { it.enabled }.forEach { schedule(ctx, it) }

    /** 지금 바로 한 번 실행(테스트·수동) */
    fun runNow(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("routine_now_$id", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("id" to id, "once" to true)).build())
    }

    fun handlers(ctx: Context): Map<String, CommandHandler> = mapOf(
        "routine.add" to CommandHandler { c ->
            val (h, m) = c.arg("time", "08:00").split(":").let { (it.getOrNull(0)?.trim()?.toIntOrNull() ?: 8).coerceIn(0, 23) to (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 59) }
            val rep = c.arg("repeat", "daily").let { when { it.contains("평일") || it == "weekdays" -> "weekdays"; it.contains("주") || it == "weekly" -> "weekly"; else -> "daily" } }
            val day = c.arg("day", "1").let { d -> "월화수목금토일".indexOf(d.take(1)).takeIf { it >= 0 }?.plus(1) ?: d.toIntOrNull() ?: 1 }
            val r = add(ctx, c.arg("title"), c.arg("goal"), rep, day, h, m, c.arg("difficulty", "STANDARD").uppercase())
            CommandResult.ok("루틴을 만들었어요: ${r.title} · ${r.label()} · 다음 ${r.next().toLocalDate()} ${"%02d:%02d".format(r.hour, r.minute)}")
        },
        "routine.list" to CommandHandler { _ -> CommandResult.ok(list.value.joinToString("\n") { "${it.id} · ${it.title} · ${it.label()}${if (it.enabled) "" else " (꺼짐)"}" }.ifBlank { "루틴이 없어요" }) },
        "routine.remove" to CommandHandler { c -> val id = c.arg("id"); val r = list.value.firstOrNull { it.id == id || it.title == id } ?: return@CommandHandler CommandResult.fail("그 루틴이 없어요"); remove(ctx, r.id); CommandResult.ok("지웠어요: ${r.title}") }
    )
}

/** 루틴 실행: 포그라운드로 올라가 작업을 시작하고 끝날 때까지 지켜본 뒤 다음 회차를 예약한다 */
class RoutineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifier.session(applicationContext, "루틴 실행 중", "예약한 작업을 시작했어요", null)
        return if (Build.VERSION.SDK_INT >= 34) ForegroundInfo(Notifier.ID_SESSION + 11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else ForegroundInfo(Notifier.ID_SESSION + 11, n)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val h = Hjtk.get(applicationContext)
        val r = Routines.list.value.firstOrNull { it.id == id } ?: return Result.success()
        if (!r.enabled && !inputData.getBoolean("once", false)) return Result.success()
        runCatching { setForeground(getForegroundInfo()) }
        // 루틴마다 대화방 하나(결과가 날짜별로 쌓인다)
        val conv = r.convId?.takeIf { cid -> h.store.conversations.value.any { it.id == cid } } ?: h.store.newConversation("루틴 · ${r.title}").id
        h.store.msg(conv, Role.SYSTEM, "루틴 실행 · ${r.label()}", "note")
        val job = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { h.runner.startMission(conv, r.goal, difficulty = Difficulty.of(r.diff)) }
        Routines.update(applicationContext, id) { it.copy(lastRun = System.currentTimeMillis(), convId = conv) }
        val end = System.currentTimeMillis() + 3 * 3600_000L
        while (System.currentTimeMillis() < end && h.store.job(job.id)?.running == true) delay(15_000)
        if (!inputData.getBoolean("once", false)) Routines.list.value.firstOrNull { it.id == id }?.takeIf { it.enabled }?.let { Routines.schedule(applicationContext, it) }
        return Result.success()
    }
}

// ═══════════════════════════════════════════════════════════════════
//  백업 · 복원: 대화·작업·기억·허브·루틴·설정(키·비밀번호 제외)을 zip 하나로
// ═══════════════════════════════════════════════════════════════════
object Backup {
    /** 기기 Keystore에 묶인 비밀(키·앱 비밀번호)은 다른 기기에서 풀 수 없고, 파일로 나가면 위험하므로 넣지 않는다 */
    fun export(ctx: Context, out: Uri): String = runCatching {
        val dir = File(ctx.filesDir, "hjtk")
        var n = 0
        ctx.contentResolver.openOutputStream(out)?.use { os ->
            ZipOutputStream(os).use { z ->
                z.putNextEntry(ZipEntry("HJTK_BACKUP.txt")); z.write("HJTK backup v2 · ${LocalDateTime.now()}\nAPI 키·메일 비밀번호·토큰은 들어 있지 않아요. 복원 후 다시 넣어 주세요.".toByteArray()); z.closeEntry()
                dir.walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.forEach { f ->
                    z.putNextEntry(ZipEntry("hjtk/" + f.relativeTo(dir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
                }
            }
        }
        "파일 ${n}개를 백업했어요(키·비밀번호 제외)"
    }.getOrElse { "백업 실패: ${it.message}" }

    /** 복원: 파일을 덮어쓰고 앱을 다시 시작한다(경로 탈출 차단) */
    fun restore(ctx: Context, input: Uri): String = runCatching {
        val dir = File(ctx.filesDir, "hjtk").canonicalFile
        var n = 0; var valid = false
        ctx.contentResolver.openInputStream(input)?.use { ins ->
            ZipInputStream(ins).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    if (e.name == "HJTK_BACKUP.txt") valid = true
                    if (e.isDirectory || !e.name.startsWith("hjtk/")) return@forEach
                    val f = File(dir, e.name.removePrefix("hjtk/")).canonicalFile
                    if (!f.path.startsWith(dir.path + File.separator)) return@forEach
                    f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }; n++
                }
            }
        }
        if (!valid) error("HJTK 백업 파일이 아니에요")
        "파일 ${n}개를 복원했어요. 앱을 다시 시작합니다."
    }.getOrElse { "복원 실패: ${it.message}" }

    fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        ctx.startActivity(i); Runtime.getRuntime().exit(0)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  알림에서 바로: 질문에 답하기(직접 입력) · 허락(실행/거절)
// ═══════════════════════════════════════════════════════════════════
object QuickActions {
    const val KEY = "hjtk_reply"
    fun reply(ctx: Context, pid: String): androidx.core.app.NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(ctx, pid.hashCode(), Intent(ctx, QuickActionReceiver::class.java).putExtra("pid", pid).putExtra("do", "answer"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return androidx.core.app.NotificationCompat.Action.Builder(0, "답하기", pi).addRemoteInput(RemoteInput.Builder(KEY).setLabel("답을 적어 주세요").build()).build()
    }
    fun approve(ctx: Context, pid: String, yes: Boolean): androidx.core.app.NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(ctx, (pid + yes).hashCode(), Intent(ctx, QuickActionReceiver::class.java).putExtra("pid", pid).putExtra("do", if (yes) "approve" else "reject"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return androidx.core.app.NotificationCompat.Action.Builder(0, if (yes) "실행" else "거절", pi).build()
    }
}

class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pid = intent.getStringExtra("pid") ?: return
        val h = Hjtk.get(context)
        when (intent.getStringExtra("do")) {
            "answer" -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QuickActions.KEY)?.toString()?.takeIf { it.isNotBlank() }?.let { h.runner.resolve(pid, it) }
            "approve" -> h.runner.resolve(pid, "approve")
            "reject" -> h.runner.resolve(pid, "알림에서 거절")
        }
        Notifier.cancel(context, pid)
    }
}
