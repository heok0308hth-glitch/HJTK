package com.hjtk.app.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {

    private val dateTime = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA)

    fun stamp(ms: Long): String = synchronized(dateTime) { dateTime.format(Date(ms)) }

    fun clock(ms: Long): String = synchronized(timeOnly) { timeOnly.format(Date(ms)) }

    fun iso(ms: Long): String = synchronized(this.iso) { this.iso.format(Date(ms)) }

    fun duration(seconds: Int): String = when {
        seconds <= 0 -> "곧"
        seconds < 60 -> "${seconds}초"
        seconds < 3600 -> "${seconds / 60}분"
        else -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
    }

    fun ago(nowMs: Long, thenMs: Long): String {
        if (thenMs <= 0L) return "기록 없음"
        val diff = (nowMs - thenMs).coerceAtLeast(0L) / 1000
        return when {
            diff < 60 -> "방금"
            diff < 3600 -> "${diff / 60}분 전"
            diff < 86400 -> "${diff / 3600}시간 전"
            diff < 2_592_000 -> "${diff / 86400}일 전"
            else -> stamp(thenMs)
        }
    }

    /** Accepts "2026-11-23 19:00", "11/23 19:00", "19:00" (today). */
    fun parseWhen(text: String, nowMs: Long): Long? {
        val t = text.trim()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm" to "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd" to "yyyy-MM-dd",
            "M/d HH:mm" to "M/d HH:mm",
            "M/d" to "M/d",
            "HH:mm" to "HH:mm"
        )
        for ((label, pattern) in patterns) {
            val fmt = SimpleDateFormat(pattern, Locale.KOREA).apply { isLenient = false }
            val parsed = runCatching { fmt.parse(t) }.getOrNull() ?: continue
            val cal = java.util.Calendar.getInstance().apply { time = parsed }
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            when (label) {
                "HH:mm" -> {
                    cal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
                    cal.set(java.util.Calendar.MONTH, now.get(java.util.Calendar.MONTH))
                    cal.set(java.util.Calendar.DAY_OF_MONTH, now.get(java.util.Calendar.DAY_OF_MONTH))
                    if (cal.timeInMillis < nowMs) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                "M/d", "M/d HH:mm" -> cal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
                "yyyy-MM-dd" -> {
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
                    cal.set(java.util.Calendar.MINUTE, 0)
                }
            }
            return cal.timeInMillis
        }
        return null
    }
}
