package com.ht.hjtk.core.command

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** 기기·네트워크가 필요 없는 결정론 명령. JVM에서 단위 테스트된다. */
object PureHandlers {

    fun all(today: () -> LocalDate = { LocalDate.now() }): Map<String, CommandHandler> = base(today) + extra() + MoreCommands.handlers

    private fun extra(): Map<String, CommandHandler> = mapOf(
        "text.manuscript" to CommandHandler { c ->
            val t = c.arg("text"); val noSpace = t.count { !it.isWhitespace() }
            val cells = t.lines().sumOf { l -> ((l.length + 19) / 20).coerceAtLeast(1) * 20 }
            CommandResult.ok("200자 원고지 약 ${Num.fmt(Math.ceil(cells / 200.0))}매 · 공백 포함 ${t.length}자 · 공백 제외 ${noSpace}자")
        },
        "calc.vat" to CommandHandler { c -> runCatching {
            val a = c.arg("amount").replace(",", "").replace("원", "").toDouble()
            CommandResult.ok(if (c.arg("mode") == "remove") "공급가 ${Num.won(a / 1.1)} · 부가세 ${Num.won(a - a / 1.1)}" else "합계 ${Num.won(a * 1.1)} · 부가세 ${Num.won(a * 0.1)}")
        }.getOrElse { CommandResult.fail("금액이 필요합니다") } },
        "calc.compound" to CommandHandler { c -> runCatching {
            val p0 = c.arg("principal").replace(",", "").toDouble(); val r = c.arg("rate").toDouble() / 100 / 12
            val n = (c.arg("years").toDouble() * 12).toInt(); val m = c.arg("monthly", "0").replace(",", "").toDouble()
            var v = p0; repeat(n) { v = v * (1 + r) + m }
            val paid = p0 + m * n
            CommandResult.ok("${c.arg("years")}년 뒤 ${Num.won(v)} (납입 ${Num.won(paid)}, 이자 ${Num.won(v - paid)}) · 월복리 가정")
        }.getOrElse { CommandResult.fail("원금·이율·년수가 필요합니다") } },
        "calc.unit_price" to CommandHandler { c ->
            val rows = TextOps.kv(c.arg("items")).mapNotNull { (k, v) ->
                val parts = v.split('/'); val price = parts.getOrNull(0)?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                val qty = parts.getOrNull(1)?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
                if (price == null || qty == null || qty == 0.0) null else Triple(k, price / qty, parts[1].replace(Regex("[0-9.\\s]"), ""))
            }.sortedBy { it.second }
            if (rows.isEmpty()) CommandResult.fail("형식: 이름=가격/용량") else CommandResult.ok(rows.mapIndexed { i, r -> "${i + 1}. ${r.first} — 1${r.third}당 ${Num.fmt(r.second)}원" }.joinToString("\n"))
        },
        "calc.time_add" to CommandHandler { c -> runCatching {
            val t = java.time.LocalTime.parse(c.arg("time").padStart(5, '0'))
            CommandResult.ok(t.plusMinutes(c.int("minutes", 0).toLong()).toString())
        }.getOrElse { CommandResult.fail("시간 형식은 HH:mm") } },
        "calc.bmi" to CommandHandler { c -> runCatching {
            val h = c.arg("height").toDouble() / 100; val w = c.arg("weight").toDouble(); val b = w / (h * h)
            CommandResult.ok("BMI ${Num.fmt(b)} (${when { b < 18.5 -> "저체중"; b < 23 -> "정상"; b < 25 -> "과체중"; else -> "비만" }} · 대한비만학회 기준)")
        }.getOrElse { CommandResult.fail("키(cm)와 몸무게(kg)가 필요합니다") } }
    )

    private fun base(today: () -> LocalDate): Map<String, CommandHandler> = mapOf(
        "text.count" to CommandHandler { c -> val t = c.arg("text"); CommandResult.ok(TextOps.count(t)) },
        "text.regex" to CommandHandler { c -> runCatching {
            val m = Regex(c.arg("pattern")).findAll(c.arg("text")).map { it.value }.take(200).toList()
            CommandResult.ok(if (m.isEmpty()) "일치 없음" else "${m.size}건\n" + m.joinToString("\n"))
        }.getOrElse { CommandResult.fail("정규식 오류: ${it.message}") } },
        "text.replace" to CommandHandler { c ->
            val out = if (c.arg("regex") == "true") runCatching { Regex(c.arg("find")).replace(c.arg("text"), c.arg("to")) }.getOrElse { return@CommandHandler CommandResult.fail("정규식 오류") }
            else c.arg("text").replace(c.arg("find"), c.arg("to"))
            CommandResult.ok(out)
        },
        "text.lines" to CommandHandler { c -> CommandResult.ok(TextOps.lines(c.arg("text"), c.arg("op", "trim"))) },
        "text.extract" to CommandHandler { c -> CommandResult.ok(TextOps.extract(c.arg("text"), c.arg("what", "url")).ifEmpty { listOf("없음") }.joinToString("\n")) },
        "text.truncate" to CommandHandler { c -> CommandResult.ok(TextOps.truncate(c.arg("text"), c.int("max", 200))) },
        "text.diff" to CommandHandler { c -> CommandResult.ok(TextOps.diff(c.arg("a"), c.arg("b"))) },
        "text.template" to CommandHandler { c -> CommandResult.ok(TextOps.template(c.arg("template"), TextOps.kv(c.arg("vars")))) },
        "text.wordfreq" to CommandHandler { c -> CommandResult.ok(TextOps.wordFreq(c.arg("text"), c.int("n", 15)).joinToString("\n") { "${it.first} ${it.second}" }) },
        "text.table" to CommandHandler { c -> CommandResult.ok(TextOps.toTable(c.arg("csv"))) },
        "text.toc" to CommandHandler { c -> CommandResult.ok(TextOps.toc(c.arg("md"))) },
        "text.slug" to CommandHandler { c -> CommandResult.ok(TextOps.slug(c.arg("text"))) },
        "text.case" to CommandHandler { c ->
            val t = c.arg("text")
            CommandResult.ok(when (c.arg("to")) {
                "upper" -> t.uppercase(); "lower" -> t.lowercase()
                else -> t.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            })
        },
        "text.split" to CommandHandler { c -> CommandResult.ok(c.arg("text").split(c.arg("by", ",")).mapIndexed { i, s -> "${i + 1}. ${s.trim()}" }.joinToString("\n")) },
        "text.wrap" to CommandHandler { c -> CommandResult.ok(TextOps.wrap(c.arg("text"), c.int("width", 40))) },
        "text.markdown_strip" to CommandHandler { c -> CommandResult.ok(TextOps.stripMarkdown(c.arg("md"))) },
        "json.get" to CommandHandler { c -> runCatching {
            val root: Any = c.arg("json").trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
            CommandResult.ok(JsonPath.get(root, c.arg("path"))?.toString() ?: "값 없음")
        }.getOrElse { CommandResult.fail("JSON 오류: ${it.message}") } },
        "json.pretty" to CommandHandler { c -> runCatching {
            val t = c.arg("json").trim()
            CommandResult.ok(if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2))
        }.getOrElse { CommandResult.fail("유효하지 않은 JSON: ${it.message}") } },
        "json.csv" to CommandHandler { c -> runCatching { CommandResult.ok(TextOps.jsonToCsv(JSONArray(c.arg("json").trim()))) }.getOrElse { CommandResult.fail("객체 배열 JSON이 필요합니다") } },
        "code.base64" to CommandHandler { c -> runCatching {
            CommandResult.ok(if (c.arg("mode") == "decode") String(Base64.getDecoder().decode(c.arg("text").trim())) else Base64.getEncoder().encodeToString(c.arg("text").toByteArray()))
        }.getOrElse { CommandResult.fail("Base64 오류") } },
        "code.url" to CommandHandler { c -> CommandResult.ok(if (c.arg("mode") == "decode") URLDecoder.decode(c.arg("text"), "UTF-8") else URLEncoder.encode(c.arg("text"), "UTF-8")) },
        "code.hash" to CommandHandler { c ->
            val algo = if (c.arg("algo").lowercase() == "md5") "MD5" else "SHA-256"
            CommandResult.ok(MessageDigest.getInstance(algo).digest(c.arg("text").toByteArray()).joinToString("") { "%02x".format(it) })
        },
        "calc.eval" to CommandHandler { c -> runCatching { CommandResult.ok("${c.arg("expr")} = ${Num.fmt(Calc.eval(c.arg("expr")))}") }.getOrElse { CommandResult.fail("계산할 수 없는 식: ${it.message}") } },
        "calc.unit" to CommandHandler { c -> runCatching {
            val v = c.arg("value").replace(",", "").toDouble()
            CommandResult.ok("${Num.fmt(v)} ${c.arg("from")} = ${Num.fmt(Units.convert(v, c.arg("from"), c.arg("to")))} ${c.arg("to")}")
        }.getOrElse { CommandResult.fail(it.message ?: "변환 불가") } },
        "calc.date_diff" to CommandHandler { c -> runCatching {
            val d = ChronoUnit.DAYS.between(LocalDate.parse(c.arg("a")), LocalDate.parse(c.arg("b")))
            CommandResult.ok("${d}일 (약 ${Num.fmt(d / 7.0)}주)")
        }.getOrElse { CommandResult.fail("날짜 형식은 YYYY-MM-DD") } },
        "calc.date_add" to CommandHandler { c -> runCatching {
            val d = LocalDate.parse(c.arg("date")).plusDays(c.int("days", 0).toLong())
            CommandResult.ok("${d} (${Dates.dow(d)})")
        }.getOrElse { CommandResult.fail("날짜 형식은 YYYY-MM-DD") } },
        "calc.dday" to CommandHandler { c -> runCatching {
            val n = ChronoUnit.DAYS.between(today(), LocalDate.parse(c.arg("date")))
            CommandResult.ok(when { n > 0 -> "D-$n"; n == 0L -> "D-Day"; else -> "D+${-n}" } + " (${c.arg("date")} ${Dates.dow(LocalDate.parse(c.arg("date")))})")
        }.getOrElse { CommandResult.fail("날짜 형식은 YYYY-MM-DD") } },
        "calc.percent" to CommandHandler { c -> runCatching {
            val a = c.arg("a").toDouble(); val b = c.arg("b").toDouble()
            CommandResult.ok(if (c.arg("op") == "change") "증감률 ${Num.fmt((a - b) / b * 100)}% (${Num.fmt(b)} → ${Num.fmt(a)})" else "${Num.fmt(a)}는 ${Num.fmt(b)}의 ${Num.fmt(a / b * 100)}%")
        }.getOrElse { CommandResult.fail("숫자가 필요합니다") } },
        "calc.loan" to CommandHandler { c -> runCatching {
            val p = c.arg("principal").replace(",", "").toDouble(); val r = c.arg("rate").toDouble() / 100 / 12; val n = c.int("months", 12)
            val m = if (r == 0.0) p / n else p * r * (1 + r).pow(n) / ((1 + r).pow(n) - 1)
            CommandResult.ok("월 상환 ${Num.won(m)} · 총 상환 ${Num.won(m * n)} · 총 이자 ${Num.won(m * n - p)}")
        }.getOrElse { CommandResult.fail("원금·이율·개월 수가 필요합니다") } },
        "calc.split" to CommandHandler { c -> runCatching {
            val t = c.arg("total").replace(",", "").toDouble() * (1 + (c.arg("tip", "0").toDoubleOrNull() ?: 0.0) / 100)
            val n = c.int("people", 1).coerceAtLeast(1)
            CommandResult.ok("총 ${Num.won(t)} · 1인당 ${Num.won(t / n)} (${n}명)")
        }.getOrElse { CommandResult.fail("총액과 인원이 필요합니다") } },
        "calc.stats" to CommandHandler { c ->
            val xs = Regex("-?\\d+(?:\\.\\d+)?").findAll(c.arg("numbers")).map { it.value.toDouble() }.toList()
            if (xs.isEmpty()) CommandResult.fail("숫자가 없습니다") else {
                val mean = xs.average(); val sorted = xs.sorted()
                val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                val sd = sqrt(xs.sumOf { (it - mean).pow(2) } / xs.size)
                CommandResult.ok("n=${xs.size} 평균 ${Num.fmt(mean)} 중앙값 ${Num.fmt(med)} 표준편차 ${Num.fmt(sd)} 최소 ${Num.fmt(sorted.first())} 최대 ${Num.fmt(sorted.last())} 합계 ${Num.fmt(xs.sum())}")
            }
        },
        "calc.random" to CommandHandler { c ->
            val items = c.arg("pick").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isNotEmpty()) CommandResult.ok("뽑힘: ${items.random()}")
            else CommandResult.ok("${(c.int("min", 1)..c.int("max", 100)).random()}")
        },
        "calc.age" to CommandHandler { c -> runCatching {
            val b = LocalDate.parse(c.arg("birth")); val t = today()
            var age = t.year - b.year; if (t.dayOfYear < b.withYear(t.year).dayOfYear) age--
            CommandResult.ok("만 ${age}세 · 연 나이 ${t.year - b.year}세")
        }.getOrElse { CommandResult.fail("생일 형식은 YYYY-MM-DD") } },
        "calc.budget" to CommandHandler { c ->
            val items = TextOps.kv(c.arg("items")).mapValues { (_, v) -> v.replace(",", "").replace("원", "").trim().toDoubleOrNull() ?: 0.0 }
            val total = items.values.sum()
            val lim = c.arg("limit").replace(",", "").toDoubleOrNull()
            val lines = items.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key}: ${Num.won(it.value)} (${Num.fmt(if (total == 0.0) 0.0 else it.value / total * 100)}%)" }
            CommandResult.ok("$lines\n합계 ${Num.won(total)}" + (lim?.let { "\n한도 ${Num.won(it)} 대비 ${if (total <= it) "여유 ${Num.won(it - total)}" else "초과 ${Num.won(total - it)}"}" } ?: ""))
        }
    )
}

object Num {
    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        val r = round(v * 1e6) / 1e6
        return if (r == r.toLong().toDouble() && kotlin.math.abs(r) < 1e15) String.format("%,d", r.toLong()) else String.format("%,.4f", r).trimEnd('0').trimEnd('.')
    }
    fun won(v: Double) = String.format("%,d원", round(v).toLong())
}

object Dates {
    private val names = listOf("월", "화", "수", "목", "금", "토", "일")
    fun dow(d: LocalDate) = names[d.dayOfWeek.value - 1] + "요일"
    val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
}

/** 재귀 하강 계산기: + - * / % ^ () sqrt sin cos tan log ln abs pi e, 숫자 콤마 허용 */
object Calc {
    fun eval(src: String): Double {
        val s = src.replace(",", "").replace("×", "*").replace("÷", "/").replace("x", "*").replace(" ", "")
        val p = P(s); val v = p.expr(); if (p.i != s.length) error("'${s.substring(p.i)}'"); return v
    }
    private class P(val s: String) {
        var i = 0
        fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        fun term(): Double { var v = pow(); while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
            val op = s[i++]
            if (op == '%' && (i >= s.length || s[i] in "+-)")) { v /= 100.0; continue }
            val r = pow(); v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r } }; return v }
        fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; b.pow(pow()) } else b }
        fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else if (i < s.length && s[i] == '+') { i++; unary() } else atom()
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') error(")"); i++; return v }
            val st = i
            if (i < s.length && s[i].isLetter()) {
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(st, i).lowercase()
                when (name) { "pi" -> return Math.PI; "e" -> return Math.E }
                val a = atom()
                return when (name) {
                    "sqrt" -> sqrt(a); "sin" -> kotlin.math.sin(a); "cos" -> kotlin.math.cos(a); "tan" -> kotlin.math.tan(a)
                    "log" -> kotlin.math.log10(a); "ln" -> kotlin.math.ln(a); "abs" -> kotlin.math.abs(a)
                    else -> error(name)
                }
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) error("숫자 필요")
            var v = s.substring(st, i).toDouble()
            if (i < s.length && s[i] == '%' && (i + 1 >= s.length || s[i + 1] in "+-)*/")) { /* 단독 % 는 term에서 처리 */ }
            return v
        }
    }
}

object Units {
    private val length = mapOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0, "in" to 0.0254, "inch" to 0.0254, "인치" to 0.0254,
        "ft" to 0.3048, "피트" to 0.3048, "yd" to 0.9144, "mi" to 1609.344, "마일" to 1609.344, "자" to 0.30303, "리" to 392.7)
    private val mass = mapOf("mg" to 1e-6, "g" to 0.001, "kg" to 1.0, "t" to 1000.0, "톤" to 1000.0, "lb" to 0.45359237, "파운드" to 0.45359237,
        "oz" to 0.028349523, "온스" to 0.028349523, "근" to 0.6, "돈" to 0.00375, "냥" to 0.0375)
    private val volume = mapOf("ml" to 0.001, "l" to 1.0, "리터" to 1.0, "gal" to 3.785411784, "갤런" to 3.785411784, "cup" to 0.24, "컵" to 0.2,
        "tbsp" to 0.015, "tsp" to 0.005, "되" to 1.8039, "말" to 18.039)
    private val area = mapOf("m2" to 1.0, "㎡" to 1.0, "km2" to 1e6, "평" to 3.305785, "ha" to 10000.0, "acre" to 4046.8564, "ft2" to 0.09290304)
    private val speed = mapOf("m/s" to 1.0, "km/h" to 1 / 3.6, "kmh" to 1 / 3.6, "mph" to 0.44704, "knot" to 0.514444, "노트" to 0.514444)
    private val data = mapOf("b" to 1.0, "kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024, "tb" to 1024.0 * 1024 * 1024 * 1024)
    private val time = mapOf("s" to 1.0, "초" to 1.0, "min" to 60.0, "분" to 60.0, "h" to 3600.0, "시간" to 3600.0, "day" to 86400.0, "일" to 86400.0, "week" to 604800.0, "주" to 604800.0)
    private val tables = listOf(length, mass, volume, area, speed, data, time)

    fun convert(v: Double, from: String, to: String): Double {
        val f = from.trim().lowercase(); val t = to.trim().lowercase()
        val temps = setOf("c", "f", "k", "°c", "°f", "섭씨", "화씨")
        if (f in temps && t in temps) {
            val c = when (f) { "f", "°f", "화씨" -> (v - 32) * 5 / 9; "k" -> v - 273.15; else -> v }
            return when (t) { "f", "°f", "화씨" -> c * 9 / 5 + 32; "k" -> c + 273.15; else -> c }
        }
        for (tb in tables) { val a = tb[f]; val b = tb[t]; if (a != null && b != null) return v * a / b }
        error("'$from'→'$to' 변환은 지원하지 않습니다")
    }
}

object JsonPath {
    fun get(root: Any, path: String): Any? {
        var cur: Any? = root
        val tokens = Regex("[^.\\[\\]]+|\\[(\\d+)]").findAll(path)
        for (m in tokens) {
            val idx = m.groupValues[1]
            cur = if (idx.isNotEmpty()) (cur as? JSONArray)?.opt(idx.toInt()) else (cur as? JSONObject)?.opt(m.value)
            if (cur == null) return null
        }
        return cur
    }
}

object TextOps {
    fun count(t: String) = "글자 ${t.length} · 공백 제외 ${t.count { !it.isWhitespace() }} · 단어 ${t.split(Regex("\\s+")).count { it.isNotBlank() }} · 줄 ${if (t.isEmpty()) 0 else t.lines().size}"

    fun lines(t: String, op: String): String {
        val ls = t.lines()
        return when (op) {
            "sort" -> ls.sorted(); "dedupe" -> ls.distinct(); "reverse" -> ls.reversed()
            "number" -> ls.mapIndexed { i, s -> "${i + 1}. $s" }
            else -> ls.map { it.trim() }.filter { it.isNotEmpty() }
        }.joinToString("\n")
    }

    fun extract(t: String, what: String): List<String> {
        val rx = when (what) {
            "email" -> Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
            "phone" -> Regex("(?:\\+82[- ]?)?0\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}")
            "number" -> Regex("-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?")
            "date" -> Regex("\\d{4}[-./년]\\s?\\d{1,2}[-./월]\\s?\\d{1,2}일?")
            "hashtag" -> Regex("#[\\p{L}0-9_]+")
            else -> Regex("https?://[^\\s)\\]>\"']+")
        }
        return rx.findAll(t).map { it.value }.distinct().toList()
    }

    fun truncate(t: String, max: Int): String {
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        val e = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '\n'))
        return (if (e > max * 0.6) cut.substring(0, e + 1) else cut) + "…"
    }

    fun diff(a: String, b: String): String {
        val x = a.lines(); val y = b.lines()
        val n = x.size; val m = y.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<String>(); var i = 0; var j = 0
        while (i < n && j < m) when {
            x[i] == y[j] -> { out += "  ${x[i]}"; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out += "- ${x[i]}"; i++ }
            else -> { out += "+ ${y[j]}"; j++ }
        }
        while (i < n) out += "- ${x[i++]}"; while (j < m) out += "+ ${y[j++]}"
        val changed = out.count { it.startsWith("-") || it.startsWith("+") }
        return "변경 ${changed}줄\n" + out.joinToString("\n")
    }

    fun kv(s: String): Map<String, String> = s.lines().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }.toMap(LinkedHashMap())

    fun template(t: String, vars: Map<String, String>): String = Regex("\\{([^{}]+)}").replace(t) { m -> vars[m.groupValues[1].trim()] ?: m.value }

    private val stop = setOf("그리고", "하지만", "그러나", "the", "a", "an", "and", "or", "of", "to", "in", "is", "for", "이", "그", "저", "것", "수", "등", "및")
    fun wordFreq(t: String, n: Int): List<Pair<String, Int>> =
        Regex("[\\p{L}\\p{N}]{2,}").findAll(t.lowercase()).map { it.value.trimEnd('은', '는', '이', '가', '을', '를', '의', '에', '도') }
            .filter { it.length >= 2 && it !in stop }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    fun toTable(csv: String): String {
        val rows = csv.lines().filter { it.isNotBlank() }.map { l -> (if ('\t' in l) l.split('\t') else l.split(',')).map { it.trim() } }
        if (rows.isEmpty()) return ""
        val w = rows.maxOf { it.size }
        fun row(r: List<String>) = "| " + (0 until w).joinToString(" | ") { r.getOrElse(it) { "" } } + " |"
        return (listOf(row(rows[0]), "|" + (0 until w).joinToString("|") { "---" } + "|") + rows.drop(1).map { row(it) }).joinToString("\n")
    }

    fun toc(md: String): String = md.lines().filter { it.trimStart().startsWith("#") }.joinToString("\n") { l ->
        val level = l.trimStart().takeWhile { it == '#' }.length
        "  ".repeat((level - 1).coerceAtLeast(0)) + "- " + l.trimStart().trimStart('#').trim()
    }

    fun slug(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(80)

    fun wrap(t: String, w: Int): String = t.lines().joinToString("\n") { line ->
        val sb = StringBuilder(); var col = 0
        for (word in line.split(' ')) {
            if (col + word.length > w && col > 0) { sb.append('\n'); col = 0 } else if (col > 0) { sb.append(' '); col++ }
            sb.append(word); col += word.length
        }
        sb.toString()
    }

    fun stripMarkdown(md: String) = md.replace(Regex("```[a-z]*\\n?"), "").replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*\\*|__|\\*|`|~~"), "").replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1").replace(Regex("^>\\s?", RegexOption.MULTILINE), "")

    fun jsonToCsv(arr: JSONArray): String {
        val keys = LinkedHashSet<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.keys()?.forEach { keys += it }
        fun esc(s: String) = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder(keys.joinToString(",") { esc(it) })
        for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; sb.append('\n').append(keys.joinToString(",") { esc(o.opt(it)?.toString() ?: "") }) }
        return sb.toString()
    }
}
