package com.ht.hjtk.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실기기에서만 나는 문제를 사람이 볼 수 있게 남긴다.
 *  - 코루틴 안 예외: 앱을 죽이지 않고 기록만(handler)
 *  - 그래도 죽는 경우: 기본 처리기 앞에서 파일로 남기고, 다음 실행 때 대화에 '지난번 종료 원인'으로 보여 준다.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SOFT = "soft_errors.txt"
    @Volatile private var dir: File? = null

    fun install(ctx: Context) {
        dir = ctx.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(ctx.filesDir, FILE).writeText(render(e, "thread ${t.name}")) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 앱은 계속 살아 있되 기록은 남긴다(최근 20KB만 유지) */
    fun record(e: Throwable, where: String = "") {
        val d = dir ?: return
        runCatching {
            val f = File(d, SOFT)
            val old = if (f.exists()) f.readText().takeLast(20_000) else ""
            f.writeText(old + render(e, where) + "\n")
        }
    }

    fun handler(where: String, also: (Throwable) -> Unit = {}) = CoroutineExceptionHandler { _, e -> record(e, where); runCatching { also(e) } }

    /** 지난 실행이 비정상 종료였다면 그 내용을 한 번만 돌려준다 */
    fun takeLastCrash(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }

    fun softLog(ctx: Context): String = File(ctx.filesDir, SOFT).takeIf { it.exists() }?.readText().orEmpty()

    /** 첫 줄(예외 종류·메시지) + 앱 코드 위치 몇 줄만: 대화에 보여 줄 요약 */
    fun summary(full: String): String {
        val lines = full.lines()
        val head = lines.firstOrNull { it.startsWith("!") }?.removePrefix("! ")?.take(160) ?: lines.firstOrNull().orEmpty()
        val ours = lines.filter { it.contains("com.ht.hjtk") }.take(3).joinToString("\n") { it.trim().removePrefix("at ") }
        return if (ours.isBlank()) head else "$head\n$ours"
    }

    private fun render(e: Throwable, where: String): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        return "# $ts · ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · $where\n! ${e.javaClass.simpleName}: ${e.message}\n" + sw.toString().lines().take(40).joinToString("\n")
    }
}
