package com.ht.hjtk.core.command

import com.ht.ensemble.agent.*
import com.ht.ensemble.command.*

import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 1.2 추가 명령(전부 기기 안·즉시 실행·AI 불필요). 카탈로그와 핸들러를 한곳에 둬서 추가가 한 줄로 끝난다.
 */
object MoreCommands {
    private fun c(id: String, title: String, cat: Category, params: String, desc: String, vararg alias: String) =
        CommandSpec(id, title, cat, Effect.READ, params.takeIf { it.isNotBlank() }?.split('|')?.map { s ->
            Param(s.substringBefore(':').trimEnd('*').trim(), s.substringAfter(':', ""), s.substringBefore(':').endsWith("*"))
        } ?: emptyList(), desc, alias.toList())

    val specs: List<CommandSpec> = listOf(
        // 텍스트
        c("text.dedupe", "중복 줄 제거", Category.TEXT, "text*:글", "같은 줄을 한 번만 남긴다(순서 유지)", "중복제거"),
        c("text.sort", "줄 정렬", Category.TEXT, "text*:글|order:asc/desc/len/num", "가나다·길이·숫자 순 정렬", "정렬"),
        c("text.shuffle", "줄 섞기", Category.TEXT, "text*:글", "줄 순서를 무작위로", "섞기"),
        c("text.number", "줄 번호", Category.TEXT, "text*:글|start:시작 번호", "각 줄 앞에 번호를 붙인다", "번호"),
        c("text.join", "줄 합치기", Category.TEXT, "text*:글|sep:구분자(기본 , )", "여러 줄을 한 줄로", "합치기"),
        c("text.trim", "공백 정리", Category.TEXT, "text*:글", "줄 끝 공백·연속 빈 줄·중복 공백 정리", "공백정리"),
        c("text.bytes", "바이트·길이", Category.TEXT, "text*:글", "UTF-8/EUC-KR 바이트, 문자 수, SMS(90바이트) 분량", "바이트"),
        c("text.josa", "조사 붙이기", Category.TEXT, "word*:단어|pair:을/를 · 이/가 · 은/는 · 와/과 · 으로/로", "받침에 맞는 조사를 고른다", "조사"),
        c("text.romanize", "로마자 표기", Category.TEXT, "text*:한글", "국어의 로마자 표기법(음절 단위 간이 변환)", "로마자"),
        c("text.choseong", "초성 추출", Category.TEXT, "text*:한글", "ㅊㅅ처럼 초성만", "초성"),
        c("text.mask", "개인정보 가리기", Category.TEXT, "text*:글", "키·토큰·전화·메일·주민번호 패턴을 가린다", "마스킹"),
        c("text.reverse", "거꾸로", Category.TEXT, "text*:글|unit:char/line", "글자 또는 줄 순서를 뒤집는다", "뒤집기"),
        // 코드·데이터
        c("code.password", "비밀번호 만들기", Category.TEXT, "len:길이(기본 16)|symbols:yes/no", "안전한 난수로 비밀번호 생성", "비밀번호"),
        c("code.uuid", "UUID", Category.TEXT, "n:개수", "UUID v4 생성", "uuid"),
        c("code.jwt", "JWT 풀어보기", Category.TEXT, "token*:JWT", "헤더·내용을 읽기 좋게(서명 검증 아님)", "jwt"),
        c("json.minify", "JSON 압축", Category.TEXT, "json*:JSON", "공백 제거 한 줄 JSON", "json압축"),
        c("json.keys", "JSON 구조", Category.TEXT, "json*:JSON", "키 경로와 타입을 나열", "json구조"),
        c("csv.table", "CSV → 표", Category.TEXT, "csv*:CSV", "CSV를 마크다운 표로", "csv표"),
        c("csv.sum", "CSV 열 합계", Category.CALC, "csv*:CSV|col:열 이름 또는 번호", "숫자 열의 합계·평균·최대·최소", "csv합계"),
        // 계산
        c("calc.discount", "할인가", Category.CALC, "price*:정가|rates*:할인율(쉼표로 연속 할인)", "연속 할인 포함 최종가와 실제 할인율", "할인"),
        c("calc.ratio", "비례식", Category.CALC, "a*:a|b*:b|c*:c", "a:b = c:x 의 x", "비례"),
        c("calc.base", "진법 변환", Category.CALC, "n*:수|from:2/8/10/16", "2·8·10·16진수로 바꾼다", "진법"),
        c("calc.pace", "페이스·속도", Category.CALC, "km*:거리(km)|time*:시간(hh:mm:ss 또는 mm:ss)", "km당 페이스와 시속", "페이스"),
        c("calc.gpa", "학점 평균", Category.CALC, "items*:과목=학점/성적(줄바꿈, A+..F 또는 숫자)", "가중 평균 평점(4.5 만점)", "학점"),
        c("calc.fuel", "기름값", Category.CALC, "km*:거리|kmpl*:연비(km/L)|price*:리터당 가격", "주행 비용", "기름값"),
        c("calc.timezone", "시차 변환", Category.CALC, "time*:시각(HH:mm 또는 yyyy-MM-dd HH:mm)|from:도시|to*:도시", "서울·뉴욕·LA·런던·파리·도쿄·베이징 등", "시차"),
        c("calc.weekday", "요일", Category.CALC, "date*:yyyy-MM-dd", "그 날짜의 요일과 올해 몇 번째 주인지", "요일"),
        c("calc.workdays", "영업일 수", Category.CALC, "from*:시작일|to*:종료일", "주말을 뺀 평일 수(공휴일은 data.holiday로 확인)", "영업일"),
        c("calc.area", "평 ↔ ㎡", Category.CALC, "value*:값|unit:평/m2", "면적 단위 변환", "평"),
        c("calc.color", "색상 변환", Category.CALC, "color*:#RRGGBB 또는 r,g,b", "HEX·RGB·HSL 변환", "색상"),
        c("calc.countdown", "남은 시간", Category.CALC, "to*:yyyy-MM-dd HH:mm 또는 HH:mm", "지금부터 그 시각까지", "카운트다운"),
        // 생활
        c("life.decide", "골라 줘", Category.LIFE, "options*:선택지(쉼표·줄바꿈)", "공정한 난수로 하나를 고른다", "골라줘"),
        c("life.dice", "주사위", Category.LIFE, "n:개수|sides:면 수", "주사위 굴리기", "주사위"),
        c("life.team", "팀 나누기", Category.LIFE, "people*:이름(쉼표·줄바꿈)|teams:팀 수", "무작위로 고르게 나눈다", "팀나누기"),
        c("life.coin", "동전 던지기", Category.LIFE, "n:횟수", "앞/뒤", "동전")
    )

    private val rnd = SecureRandom()
    private fun items(s: String) = s.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun num(s: String) = s.replace(",", "").replace("원", "").trim().toDouble()
    private fun ok(s: String) = CommandResult.ok(s)
    private fun fail(s: String) = CommandResult.fail(s)

    // 한글 분해
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val R_CHO = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val R_JUNG = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val R_JONG = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    private fun hangul(ch: Char) = ch in '가'..'힣'
    fun hasBatchim(word: String): Boolean? { val ch = word.trim().lastOrNull { it.isLetterOrDigit() } ?: return null
        if (hangul(ch)) return (ch - '가') % 28 != 0
        if (ch.isDigit()) return ch in "013678"   // 영 일 삼 육 칠 팔
        return ch.lowercaseChar() in "lmnr"
    }
    fun rieulBatchim(word: String): Boolean { val ch = word.trim().lastOrNull() ?: return false; return hangul(ch) && (ch - '가') % 28 == 8 }

    private val zones = mapOf("서울" to "Asia/Seoul", "한국" to "Asia/Seoul", "도쿄" to "Asia/Tokyo", "일본" to "Asia/Tokyo", "베이징" to "Asia/Shanghai", "상하이" to "Asia/Shanghai", "중국" to "Asia/Shanghai",
        "뉴욕" to "America/New_York", "la" to "America/Los_Angeles", "엘에이" to "America/Los_Angeles", "샌프란시스코" to "America/Los_Angeles", "시카고" to "America/Chicago",
        "런던" to "Europe/London", "영국" to "Europe/London", "파리" to "Europe/Paris", "베를린" to "Europe/Berlin", "시드니" to "Australia/Sydney", "싱가포르" to "Asia/Singapore",
        "하노이" to "Asia/Ho_Chi_Minh", "베트남" to "Asia/Ho_Chi_Minh", "방콕" to "Asia/Bangkok", "두바이" to "Asia/Dubai", "홍콩" to "Asia/Hong_Kong", "타이베이" to "Asia/Taipei", "utc" to "UTC")
    private fun zone(s: String): ZoneId = zones[s.trim().lowercase()]?.let { ZoneId.of(it) } ?: runCatching { ZoneId.of(s.trim()) }.getOrElse { error("모르는 도시: $s") }

    private fun dur(s: String): Long { val p = s.trim().split(":").map { it.trim().toLong() }; return when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> p[0] * 60 } }
    private fun hms(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    private fun csv(text: String): List<List<String>> = text.trim().lines().filter { it.isNotBlank() }.map { line ->
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when { ch == '"' -> q = !q; ch == ',' && !q -> { out += sb.toString().trim(); sb.setLength(0) } else -> sb.append(ch) }
        out += sb.toString().trim(); out
    }

    private fun jsonPaths(v: Any?, path: String, out: MutableList<String>, depth: Int = 0) {
        if (out.size > 200 || depth > 8) return
        when (v) {
            is org.json.JSONObject -> { if (path.isNotEmpty()) out += "$path : object"; v.keys().forEach { k -> jsonPaths(v.opt(k), if (path.isEmpty()) k else "$path.$k", out, depth + 1) } }
            is org.json.JSONArray -> { out += "$path : array[${v.length()}]"; if (v.length() > 0) jsonPaths(v.opt(0), "$path[0]", out, depth + 1) }
            is String -> out += "$path : string"
            is Number -> out += "$path : number"
            is Boolean -> out += "$path : boolean"
            else -> out += "$path : null"
        }
    }

    private val grade = mapOf("A+" to 4.5, "A0" to 4.0, "A" to 4.0, "B+" to 3.5, "B0" to 3.0, "B" to 3.0, "C+" to 2.5, "C0" to 2.0, "C" to 2.0, "D+" to 1.5, "D0" to 1.0, "D" to 1.0, "F" to 0.0)

    val handlers: Map<String, CommandHandler> = mapOf(
        "text.dedupe" to CommandHandler { c -> val l = c.arg("text").lines(); val u = LinkedHashSet(l); ok(u.joinToString("\n") + "\n\n(${l.size}줄 → ${u.size}줄)") },
        "text.sort" to CommandHandler { c -> val l = c.arg("text").lines().filter { it.isNotBlank() }
            ok(when (c.arg("order", "asc")) { "desc" -> l.sortedDescending(); "len" -> l.sortedBy { it.length }; "num" -> l.sortedBy { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() ?: Double.MAX_VALUE }; else -> l.sorted() }.joinToString("\n")) },
        "text.shuffle" to CommandHandler { c -> ok(c.arg("text").lines().filter { it.isNotBlank() }.shuffled(rnd).joinToString("\n")) },
        "text.number" to CommandHandler { c -> val s = c.int("start", 1); ok(c.arg("text").lines().mapIndexed { i, l -> "${i + s}. $l" }.joinToString("\n")) },
        "text.join" to CommandHandler { c -> ok(c.arg("text").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(c.args["sep"] ?: ", ")) },
        "text.trim" to CommandHandler { c -> ok(c.arg("text").lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trimEnd() }.replace(Regex("\\n{3,}"), "\n\n").trim()) },
        "text.bytes" to CommandHandler { c -> val t = c.arg("text"); val u = t.toByteArray(Charsets.UTF_8).size
            val e = runCatching { t.toByteArray(charset("EUC-KR")).size }.getOrDefault(u)
            ok("문자 ${t.codePointCount(0, t.length)}자 · UTF-8 ${u}바이트 · EUC-KR ${e}바이트 · SMS ${if (e <= 90) "단문(90B 이하)" else "장문(LMS, ${(e + 1999) / 2000}통)"}") },
        "text.josa" to CommandHandler { c -> val w = c.arg("word"); val pair = c.arg("pair", "을/를")
            val (a, b) = pair.split("/").let { it.getOrElse(0) { "을" }.trim() to it.getOrElse(1) { "를" }.trim() }
            val bat = hasBatchim(w) ?: return@CommandHandler fail("단어가 필요합니다")
            val pick = if (a == "으로") (if (bat && !rieulBatchim(w)) "으로" else "로") else if (bat) a else b
            ok("$w$pick") },
        "text.romanize" to CommandHandler { c -> ok(c.arg("text").map { ch ->
            if (!hangul(ch)) ch.toString() else { val k = ch - '가'; R_CHO[k / 588] + R_JUNG[k % 588 / 28] + R_JONG[k % 28] }
        }.joinToString("") + "\n(음절 단위 간이 변환 — 이름·지명은 연음·자음동화 규칙을 따로 확인하세요)") },
        "text.choseong" to CommandHandler { c -> ok(c.arg("text").map { ch -> if (hangul(ch)) CHO[(ch - '가') / 588] else ch }.joinToString("")) },
        "text.mask" to CommandHandler { c -> ok(com.ht.hjtk.core.Masker.mask(c.arg("text"))
            .replace(Regex("\\b01[016789][- ]?\\d{3,4}[- ]?\\d{4}\\b"), "010-****-****")
            .replace(Regex("\\b\\d{6}[- ]?[1-4]\\d{6}\\b"), "******-*******")
            .replace(Regex("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@"), "$1***@")) },
        "text.reverse" to CommandHandler { c -> ok(if (c.arg("unit") == "line") c.arg("text").lines().reversed().joinToString("\n") else c.arg("text").reversed()) },

        "code.password" to CommandHandler { c -> val n = c.int("len", 16).coerceIn(8, 128)
            val pool = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789" + if (c.arg("symbols", "yes") != "no") "!@#\$%^&*-_=+?" else ""
            ok((1..n).map { pool[rnd.nextInt(pool.length)] }.joinToString("") + "\n(헷갈리는 0·O·1·l·I는 뺐어요)") },
        "code.uuid" to CommandHandler { c -> ok((1..c.int("n", 1).coerceIn(1, 50)).joinToString("\n") { UUID.randomUUID().toString() }) },
        "code.jwt" to CommandHandler { c -> runCatching {
            val p = c.arg("token").trim().split(".")
            require(p.size >= 2)
            fun dec(s: String) = String(Base64.getUrlDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '=')))
            val body = org.json.JSONObject(dec(p[1]))
            val exp = body.optLong("exp", 0).takeIf { it > 0 }?.let { " · 만료 " + java.time.Instant.ofEpochSecond(it).atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) } ?: ""
            ok("헤더 ${org.json.JSONObject(dec(p[0])).toString(2)}\n내용 ${body.toString(2)}$exp\n(서명은 검증하지 않았어요)")
        }.getOrElse { fail("JWT 형식이 아니에요") } },
        "json.minify" to CommandHandler { c -> runCatching { val t = c.arg("json").trim(); ok(if (t.startsWith("[")) org.json.JSONArray(t).toString() else org.json.JSONObject(t).toString()) }.getOrElse { fail("JSON이 아니에요: ${it.message}") } },
        "json.keys" to CommandHandler { c -> runCatching { val t = c.arg("json").trim(); val out = ArrayList<String>()
            jsonPaths(if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t), "", out); ok(out.joinToString("\n")) }.getOrElse { fail("JSON이 아니에요") } },
        "csv.table" to CommandHandler { c -> val rows = csv(c.arg("csv")); if (rows.isEmpty()) return@CommandHandler fail("CSV가 비었어요")
            val w = rows.maxOf { it.size }; fun line(r: List<String>) = "| " + (0 until w).joinToString(" | ") { r.getOrElse(it) { "" }.replace("|", "\\|") } + " |"
            ok((listOf(line(rows[0]), "|" + " --- |".repeat(w)) + rows.drop(1).map { line(it) }).joinToString("\n")) },
        "csv.sum" to CommandHandler { c -> val rows = csv(c.arg("csv")); if (rows.size < 2) return@CommandHandler fail("머리줄과 값이 필요해요")
            val col = c.arg("col"); val idx = col.toIntOrNull()?.minus(1) ?: rows[0].indexOfFirst { it.equals(col, true) }.takeIf { it >= 0 } ?: rows[0].indices.firstOrNull { i -> rows.drop(1).all { it.getOrNull(i)?.replace(",", "")?.toDoubleOrNull() != null } } ?: return@CommandHandler fail("숫자 열을 찾지 못했어요")
            val v = rows.drop(1).mapNotNull { it.getOrNull(idx)?.replace(",", "")?.replace("원", "")?.toDoubleOrNull() }
            if (v.isEmpty()) return@CommandHandler fail("숫자가 없어요")
            ok("${rows[0].getOrElse(idx) { "열" }}: 합계 ${Num.fmt(v.sum())} · 평균 ${Num.fmt(v.average())} · 최대 ${Num.fmt(v.max())} · 최소 ${Num.fmt(v.min())} · ${v.size}개") },

        "calc.discount" to CommandHandler { c -> runCatching { val p = num(c.arg("price")); val rs = items(c.arg("rates")).map { it.removeSuffix("%").toDouble() }
            val f = rs.fold(p) { a, r -> a * (1 - r / 100) }
            ok("최종 ${Num.won(f)} · 할인액 ${Num.won(p - f)} · 실제 할인율 ${Num.fmt((1 - f / p) * 100)}%" + if (rs.size > 1) " (연속 할인은 단순 합 ${Num.fmt(rs.sum())}%보다 작아요)" else "") }.getOrElse { fail("정가와 할인율이 필요해요") } },
        "calc.ratio" to CommandHandler { c -> runCatching { val a = num(c.arg("a")); val b = num(c.arg("b")); val cc = num(c.arg("c")); ok("${Num.fmt(a)} : ${Num.fmt(b)} = ${Num.fmt(cc)} : ${Num.fmt(b * cc / a)}") }.getOrElse { fail("a, b, c가 필요해요") } },
        "calc.base" to CommandHandler { c -> runCatching { val v = c.arg("n").trim().removePrefix("0x").removePrefix("0b").toLong(c.int("from", 10))
            ok("2진 ${v.toString(2)} · 8진 ${v.toString(8)} · 10진 $v · 16진 ${v.toString(16).uppercase()}") }.getOrElse { fail("수를 읽을 수 없어요") } },
        "calc.pace" to CommandHandler { c -> runCatching { val km = num(c.arg("km")); val s = dur(c.arg("time")); require(km > 0 && s > 0)
            ok("페이스 ${hms((s / km).toLong())}/km · 시속 ${Num.fmt(km / (s / 3600.0))}km · 하프 예상 ${hms((s / km * 21.0975).toLong())} · 풀 예상 ${hms((s / km * 42.195).toLong())}") }.getOrElse { fail("거리와 시간이 필요해요") } },
        "calc.gpa" to CommandHandler { c -> runCatching {
            var credits = 0.0; var pts = 0.0
            c.arg("items").lines().filter { it.contains("=") }.forEach { l -> val (cr, g) = l.substringAfter("=").split("/").map { it.trim() }
                val cd = cr.toDouble(); val gp = grade[g.uppercase()] ?: g.toDouble(); credits += cd; pts += cd * gp }
            require(credits > 0); ok("평점 ${"%.2f".format(pts / credits)} / 4.5 · 이수 ${Num.fmt(credits)}학점 · 100점 환산 약 ${Num.fmt(round(pts / credits / 4.5 * 100 * 10) / 10)}") }.getOrElse { fail("과목=학점/성적 형식으로 적어 주세요") } },
        "calc.fuel" to CommandHandler { c -> runCatching { val l = num(c.arg("km")) / num(c.arg("kmpl")); ok("연료 ${Num.fmt(l)}L · 비용 ${Num.won(l * num(c.arg("price")))}") }.getOrElse { fail("거리·연비·가격이 필요해요") } },
        "calc.timezone" to CommandHandler { c -> runCatching {
            val from = zone(c.arg("from", "서울")); val to = zone(c.arg("to")); val t = c.arg("time").trim()
            val ldt = if (t.length > 5) LocalDateTime.parse(t.replace(" ", "T").let { if (it.count { ch -> ch == ':' } == 1) it else it }) else LocalDateTime.of(LocalDate.now(from), LocalTime.parse(t))
            val z = ldt.atZone(from).withZoneSameInstant(to)
            ok("${c.arg("from", "서울")} ${ldt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))} → ${c.arg("to")} ${z.format(DateTimeFormatter.ofPattern("M/d(E) HH:mm", java.util.Locale.KOREA))}") }.getOrElse { fail(it.message ?: "시각과 도시가 필요해요") } },
        "calc.weekday" to CommandHandler { c -> runCatching { val d = LocalDate.parse(c.arg("date").trim())
            ok("${d.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", java.util.Locale.KOREA))} · 올해 ${d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}주차 · 오늘부터 ${ChronoUnit.DAYS.between(LocalDate.now(), d)}일") }.getOrElse { fail("yyyy-MM-dd로 적어 주세요") } },
        "calc.workdays" to CommandHandler { c -> runCatching { var a = LocalDate.parse(c.arg("from").trim()); val b = LocalDate.parse(c.arg("to").trim()); require(!b.isBefore(a))
            var n = 0; var all = 0; while (!a.isAfter(b)) { all++; if (a.dayOfWeek != DayOfWeek.SATURDAY && a.dayOfWeek != DayOfWeek.SUNDAY) n++; a = a.plusDays(1) }
            ok("평일 ${n}일 / 전체 ${all}일 (공휴일은 빼지 않았어요 — data.holiday로 확인)") }.getOrElse { fail("시작일·종료일을 yyyy-MM-dd로") } },
        "calc.area" to CommandHandler { c -> runCatching { val v = num(c.arg("value")); ok(if (c.arg("unit", "평").startsWith("m") || c.arg("unit") == "㎡") "${Num.fmt(v)}㎡ = ${Num.fmt(v / 3.305785)}평" else "${Num.fmt(v)}평 = ${Num.fmt(v * 3.305785)}㎡") }.getOrElse { fail("값이 필요해요") } },
        "calc.color" to CommandHandler { c -> runCatching {
            val s = c.arg("color").trim()
            val (r, g, b) = if (s.startsWith("#") || Regex("^[0-9a-fA-F]{6}$").matches(s)) s.removePrefix("#").let { Triple(it.substring(0, 2).toInt(16), it.substring(2, 4).toInt(16), it.substring(4, 6).toInt(16)) }
                else s.split(Regex("[ ,]+")).map { it.toInt() }.let { Triple(it[0], it[1], it[2]) }
            val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0; val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf)); val l = (mx + mn) / 2
            val d = mx - mn; val sat = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
            val h = when { d == 0.0 -> 0.0; mx == rf -> 60 * (((gf - bf) / d).mod(6.0)); mx == gf -> 60 * ((bf - rf) / d + 2); else -> 60 * ((rf - gf) / d + 4) }
            ok("HEX #%02X%02X%02X · RGB %d, %d, %d · HSL %d°, %d%%, %d%%".format(r, g, b, r, g, b, h.toInt(), (sat * 100).toInt(), (l * 100).toInt())) }.getOrElse { fail("#RRGGBB 또는 r,g,b") } },
        "calc.countdown" to CommandHandler { c -> runCatching { val t = c.arg("to").trim(); val now = LocalDateTime.now()
            var target = if (t.length <= 5) LocalDateTime.of(LocalDate.now(), LocalTime.parse(t)) else LocalDateTime.parse(t.replace(" ", "T"))
            if (t.length <= 5 && target.isBefore(now)) target = target.plusDays(1)
            val m = ChronoUnit.MINUTES.between(now, target); ok(if (m < 0) "이미 ${-m / 60}시간 ${-m % 60}분 지났어요" else "${m / 1440}일 ${m % 1440 / 60}시간 ${m % 60}분 남았어요") }.getOrElse { fail("yyyy-MM-dd HH:mm 또는 HH:mm") } },

        "life.decide" to CommandHandler { c -> val o = items(c.arg("options")); if (o.size < 2) fail("선택지가 2개 이상 필요해요") else ok("→ ${o[rnd.nextInt(o.size)]}  (${o.size}개 중 공정 추첨)") },
        "life.dice" to CommandHandler { c -> val n = c.int("n", 1).coerceIn(1, 20); val s = c.int("sides", 6).coerceIn(2, 1000); val r = (1..n).map { rnd.nextInt(s) + 1 }; ok(r.joinToString(" ") + if (n > 1) " · 합 ${r.sum()}" else "") },
        "life.team" to CommandHandler { c -> val p = items(c.arg("people")).shuffled(rnd); val t = c.int("teams", 2).coerceIn(2, max(2, p.size))
            if (p.size < 2) fail("두 명 이상 적어 주세요") else ok((0 until t).joinToString("\n") { i -> "${i + 1}팀: " + p.filterIndexed { k, _ -> k % t == i }.joinToString(", ") }) },
        "life.coin" to CommandHandler { c -> val n = c.int("n", 1).coerceIn(1, 100); val r = (1..n).map { if (rnd.nextBoolean()) "앞" else "뒤" }; ok(r.joinToString(" ") + if (n > 1) " · 앞 ${r.count { it == "앞" }} 뒤 ${r.count { it == "뒤" }}" else "") }
    )
}
