package com.ht.hjtk.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.InputRouter
import com.ht.hjtk.core.Memory
import com.ht.hjtk.core.MemoryIndex
import com.ht.hjtk.core.Pipeline
import com.ht.hjtk.core.PipelineCompiler
import com.ht.hjtk.core.PipelineStep
import com.ht.hjtk.core.Skill
import com.ht.hjtk.core.Verifiers
import com.ht.ensemble.agent.HumanPort
import com.ht.ensemble.agent.ToolPort
import com.ht.ensemble.command.Artifact
import com.ht.ensemble.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.ensemble.command.CommandHandler
import com.ht.ensemble.command.CommandResult
import com.ht.ensemble.command.CommandSpec
import com.ht.hjtk.core.command.Num
import com.ht.hjtk.core.command.PureHandlers
import com.ht.hjtk.data.Role
import com.ht.hjtk.engine.AppVisibility
import com.ht.hjtk.engine.Clip
import com.ht.hjtk.engine.ExternalAiBridge
import com.ht.hjtk.engine.Http
import com.ht.hjtk.engine.Notifier
import com.ht.hjtk.engine.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/** 사람 개입 예산(B-2): 작업당 외부 AI 1탭 개입은 최대 2회. 넘으면 자동 경로로 강등하고 라벨을 단다. */
class TapBudget(var left: Int = 2)

/**
 * 명령어 → 실제 안드로이드 동작. 모든 부작용은 여기를 지난다(그리고 그 전에 AgentLoop의 승인 게이트를 지난다).
 */
class AndroidTools(
    private val h: Hjtk,
    private val jobId: String,
    private val convId: String,
    private val human: HumanPort,
    private val budget: TapBudget = TapBudget()
) : ToolPort {
    private val ctx = h.app
    private val store = h.store
    private val pure = PureHandlers.all()
    private val dir: File get() = store.jobDir(jobId)
    private val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }

    override fun spec(id: String): CommandSpec? = CommandCatalog.find(id)
    override fun recipientKnown(address: String): Boolean =
        address.isBlank() || address.lowercase() in store.knownRecipients.value || store.mail.value.any { it.email.equals(address, true) }

    override suspend fun run(call: CommandCall): CommandResult {
        val id = spec(call.cmd)?.id ?: call.cmd
        pure[id]?.let { return it.run(call.copy(cmd = id)) }
        val handler = handlers[id] ?: h.connectorHandlers[id] ?: return CommandResult.fail("'$id' 명령은 이 기기에서 아직 실행할 수 없습니다")
        return handler.run(call.copy(cmd = id))
    }

    private fun ok(t: String) = CommandResult.ok(t)
    private fun fail(t: String) = CommandResult.fail(t)
    private fun hits(list: List<Hit>, empty: String = "결과 없음") = if (list.isEmpty()) fail(empty) else ok(list.mapIndexed { i, x -> x.line(i) }.joinToString("\n"))
    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace("..", "_").trim().ifBlank { "untitled.txt" }.take(120)
    private fun naverKey(): Pair<String, String>? { val i = store.secrets.get("naver_id"); val s = store.secrets.get("naver_secret"); return if (i != null && s != null) i to s else null }
    private fun launch(i: Intent, title: String, body: String, tab: Boolean = false) = ok(h.launcher.launch(i, title, body, tab))

    private val handlers: Map<String, CommandHandler> = buildMap {
        // ── AI 협업 ─────────────────────────────────────────────
        put("ai.verify") { c -> ok(Verifiers.run(c.arg("text"), c.arg("req").lines()).text()) }
        put("ai.gemini") { c -> gemini(c.arg("prompt"))?.let { ok("Gemini 답변:\n$it") } ?: fail("Gemini 응답을 받지 못했습니다(시간 초과 또는 개입 예산 소진). 자체 판단으로 진행하라.") }
        put("ai.second_opinion") { c ->
            val q = c.arg("question"); val mine = c.arg("mine")
            val ans = gemini(if (mine.isBlank()) q else "$q\n\n(참고: 다른 AI의 답)\n$mine\n\n이 답과 비교해 다른 점·틀린 점을 짚어줘.")
            ans?.let { ok("Gemini 의견:\n$it") } ?: fail("두 번째 의견 미실시(응답 없음)")
        }
        put("ai.critique") { c ->
            val l0 = Verifiers.run(c.arg("draft"), c.arg("req").lines())
            val prompt = Verifiers.batchedCritiquePrompt(c.arg("draft").take(9000), c.arg("req"), l0)
            val ans = gemini(prompt)
            if (ans == null) ok("외부 비평 미실시(Gemini 응답 없음) — 기계 검증만 수행\n${l0.text()}")
            else ok("기계 검증: ${l0.text()}\n\n외부 비평(Gemini, 3역할 배치 1회):\n${ans.take(6000)}")
        }

        // ── 웹 ─────────────────────────────────────────────────
        put("web.search") { c -> hits(Search.multi(c.arg("q"), c.int("n", 6), naverKey())) }
        put("web.naver") { c -> val k = naverKey(); hits(if (k != null) Search.naverApi(c.arg("q"), c.arg("type", "web"), k.first, k.second, 8) else Search.naverHtml(c.arg("q"), 8, c.arg("type", "web"))) }
        put("web.news") { c -> hits(Search.news(c.arg("q"), c.int("n", 8))) }
        put("web.wiki") { c -> Search.wiki(c.arg("q"), c.arg("lang", "ko"))?.let { ok(it) } ?: fail("위키 문서를 찾지 못함") }
        put("web.fetch") { c -> fetch(c.arg("url"), c.int("max", 6000)) }
        put("web.multi") { c ->
            val urls = c.arg("urls").lines().map { it.trim() }.filter { it.startsWith("http") }.take(6)
            val per = c.int("max", 2500)
            val parts = coroutineScope { urls.map { u -> async { "### $u\n" + fetch(u, per).text } }.awaitAll() }
            ok(parts.joinToString("\n\n"))
        }
        put("web.links") { c -> runCatching {
            val r = Http.get(c.arg("url")); val f = c.arg("filter")
            val l = HtmlText.links(r.body, r.url).filter { f.isBlank() || it.first.contains(f, true) || it.second.contains(f, true) }.take(60)
            ok(l.joinToString("\n") { "${it.first} — ${it.second}" }.ifBlank { "링크 없음" })
        }.getOrElse { fail("읽기 실패: ${it.message}") } }
        put("web.site") { c -> hits(Search.site(c.arg("site"), c.arg("q"), 8)) }
        put("web.check") { c -> runCatching { val r = Http.get(c.arg("url"), max = 2000); ok("HTTP ${r.code} · 최종 주소 ${r.url}") }.getOrElse { fail("접속 불가: ${it.message}") } }
        put("web.rss") { c -> runCatching { hits(Search.rss(Http.get(c.arg("url")).body, c.int("n", 10), "RSS")) }.getOrElse { fail("RSS 읽기 실패") } }
        put("web.meta") { c -> runCatching {
            val r = Http.get(c.arg("url"), max = 300_000)
            ok("제목: ${HtmlText.meta(r.body, "og:title") ?: HtmlText.title(r.body)}\n설명: ${HtmlText.meta(r.body, "og:description") ?: HtmlText.meta(r.body, "description") ?: "-"}\n이미지: ${HtmlText.meta(r.body, "og:image") ?: "-"}\n주소: ${r.url}")
        }.getOrElse { fail("읽기 실패") } }
        put("web.open") { c -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(c.arg("url"))), "페이지 열기", c.arg("url"), true) }
        put("web.youtube") { c -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${enc(c.arg("q"))}")), "유튜브 검색", c.arg("q")) }
        put("web.images") { c -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://search.naver.com/search.naver?where=image&query=${enc(c.arg("q"))}")), "이미지 검색", c.arg("q"), true) }

        // ── 데이터 ─────────────────────────────────────────────
        put("data.geocode") { c -> geocode(c.arg("place"))?.let { ok("${it.third}: ${it.first}, ${it.second}") } ?: fail("위치를 찾지 못함") }
        put("data.weather") { c -> weather(c.arg("place", store.settings.value.homeCity), c.int("days", 3)) }
        put("data.air") { c -> runCatching {
            val g = geocode(c.arg("place")) ?: return@put fail("위치를 찾지 못함")
            val o = JSONObject(Http.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${g.first}&longitude=${g.second}&current=pm10,pm2_5,uv_index&timezone=auto").body).getJSONObject("current")
            val pm10 = o.optDouble("pm10"); val pm25 = o.optDouble("pm2_5")
            fun grade10(v: Double) = when { v <= 30 -> "좋음"; v <= 80 -> "보통"; v <= 150 -> "나쁨"; else -> "매우 나쁨" }
            fun grade25(v: Double) = when { v <= 15 -> "좋음"; v <= 35 -> "보통"; v <= 75 -> "나쁨"; else -> "매우 나쁨" }
            ok("${g.third} 미세먼지 ${Num.fmt(pm10)}㎍/㎥(${grade10(pm10)}) · 초미세먼지 ${Num.fmt(pm25)}㎍/㎥(${grade25(pm25)}) · 자외선 ${Num.fmt(o.optDouble("uv_index"))}")
        }.getOrElse { fail("대기질 조회 실패") } }
        put("data.sun") { c -> runCatching {
            val g = geocode(c.arg("place")) ?: return@put fail("위치를 찾지 못함")
            val d = JSONObject(Http.get("https://api.open-meteo.com/v1/forecast?latitude=${g.first}&longitude=${g.second}&daily=sunrise,sunset&timezone=auto&forecast_days=1").body).getJSONObject("daily")
            ok("${g.third} 일출 ${d.getJSONArray("sunrise").getString(0).substringAfter('T')} · 일몰 ${d.getJSONArray("sunset").getString(0).substringAfter('T')}")
        }.getOrElse { fail("조회 실패") } }
        put("data.exchange") { c -> runCatching {
            val from = c.arg("from").uppercase().take(3); val to = c.arg("to").uppercase().take(3)
            val rates = JSONObject(Http.get("https://open.er-api.com/v6/latest/$from").body).getJSONObject("rates")
            val r = rates.getDouble(to); val amt = c.arg("amount", "1").replace(",", "").toDoubleOrNull() ?: 1.0
            ok("${Num.fmt(amt)} $from = ${Num.fmt(amt * r)} $to (1 $from = ${Num.fmt(r)} $to, open.er-api.com)")
        }.getOrElse { fail("환율 조회 실패(통화 코드 예: USD, KRW, JPY)") } }
        put("data.time") { c -> runCatching {
            val z = zoneOf(c.arg("zone")); val t = ZonedDateTime.now(z)
            ok("${c.arg("zone")}(${z.id}) 지금 ${t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} · 서울과 ${ChronoUnit.HOURS.between(ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDateTime(), t.toLocalDateTime())}시간 차")
        }.getOrElse { fail("지역을 알 수 없음") } }
        put("data.holiday") { c -> runCatching {
            val y = c.arg("year", LocalDate.now().year.toString()); val arr = JSONArray(Http.get("https://date.nager.at/api/v3/PublicHolidays/$y/${c.arg("country", "KR")}").body)
            ok((0 until arr.length()).joinToString("\n") { val o = arr.getJSONObject(it); "${o.getString("date")} ${o.optString("localName")}" })
        }.getOrElse { fail("공휴일 조회 실패") } }
        put("data.crypto") { c -> runCatching {
            val coin = c.arg("coin").lowercase(); val vs = c.arg("vs", "krw").lowercase()
            val o = JSONObject(Http.get("https://api.coingecko.com/api/v3/simple/price?ids=$coin&vs_currencies=$vs&include_24hr_change=true").body).getJSONObject(coin)
            ok("$coin ${Num.fmt(o.getDouble(vs))} ${vs.uppercase()} · 24시간 ${Num.fmt(o.optDouble("${vs}_24h_change"))}% (CoinGecko)")
        }.getOrElse { fail("시세 조회 실패(예: bitcoin, ethereum)") } }

        // ── 메일 ───────────────────────────────────────────────
        put("mail.accounts") { _ -> ok(store.mail.value.joinToString("\n") { "${it.email} (${it.provider})" }.ifBlank { "연결된 메일이 없습니다. 허브 → 연동 센터에서 추가하세요." }) }
        put("mail.list") { c -> mailDo(c) { a, pw -> MailClient.list(a, pw, c.int("n", 10), c.arg("unread") == "true").joinToString("\n") { it.line() }.ifBlank { "메일 없음" } } }
        put("mail.search") { c -> mailDo(c) { a, pw -> MailClient.search(a, pw, c.arg("q"), c.int("n", 10)).joinToString("\n") { it.line() }.ifBlank { "검색 결과 없음" } } }
        put("mail.read") { c -> mailDo(c) { a, pw -> MailClient.read(a, pw, c.arg("id").filter { it.isDigit() }.toLong()) } }
        put("mail.unread") { c -> mailDo(c) { a, pw -> "${a.email} 안 읽은 메일 ${MailClient.unread(a, pw)}통" } }
        put("mail.mark_read") { c -> mailDo(c) { a, pw -> if (MailClient.markRead(a, pw, c.arg("id").filter { it.isDigit() }.toLong())) "읽음 처리" else "실패" } }
        put("mail.draft") { c ->
            val name = "메일초안_${System.currentTimeMillis() % 100000}.txt"
            File(dir, name).writeText("받는이: ${c.arg("to")}\n제목: ${c.arg("subject")}\n\n${c.arg("body")}")
            CommandResult.ok("초안 저장: $name (발송하지 않음)", Artifact(name, "text/plain", ""))
        }
        put("mail.send") { c ->
            // 3.3: SMTP 계정(앱 비밀번호)이 있으면 그걸로, 없거나 실패하면 '계정 하나로' 구글 세션으로 Gmail 웹에서 직접 보낸다
            val smtp = store.mail.value.isNotEmpty() && store.mail.value.any { store.secrets.get("mail_${it.id}") != null }
            val first = if (smtp) mailDo(c) { a, pw ->
                val r = MailClient.send(a, pw, c.arg("to"), c.arg("subject"), c.arg("body"))
                store.knownRecipients.update { it + c.arg("to").lowercase() }; store.persistHub(); r
            } else null
            if (first != null && first.ok) first
            else {
                val g = com.ht.hjtk.engine.GmailWeb.send(c.arg("to"), c.arg("subject"), c.arg("body"))
                when (g.r) {
                    com.ht.hjtk.engine.GmailWeb.R.SENT -> { store.knownRecipients.update { it + c.arg("to").lowercase() }; store.persistHub(); ok("${g.note} → ${c.arg("to")} · 제목 「${c.arg("subject")}」") }
                    com.ht.hjtk.engine.GmailWeb.R.UNSURE -> ok(g.note)
                    else -> fail(listOfNotNull(first?.text?.takeIf { it.isNotBlank() }, g.note).joinToString(" / "))
                }
            }
        }
        // ── GitHub ─────────────────────────────────────────────
        put("github.status") { _ ->
            val gh = gitHub() ?: return@put fail(GH_NEED)
            runCatching { val who = gh.login(); val r = com.ht.hjtk.engine.Http.get("https://api.github.com/user/repos?sort=pushed&per_page=5", ghHeaders()); ok("GitHub @$who 연결됨\n최근 저장소: " + Regex("\"full_name\"\\s*:\\s*\"([^\"]+)\"").findAll(r.body).map { it.groupValues[1] }.joinToString(", ")) }
                .getOrElse { fail(it.message ?: "GitHub 연결 오류") }
        }
        put("github.push") { c ->
            val gh = gitHub() ?: return@put fail(GH_NEED)
            val files = pushFiles(c.arg("path"))
            if (files.isEmpty()) return@put fail("올릴 파일을 찾지 못했어요. path에 작업공간 안의 폴더·파일 이름을 적거나, 먼저 결과물을 만들어 주세요.")
            runCatching {
                val r = gh.push(c.arg("repo"), files, c.arg("message").ifBlank { "HJTK 업로드 · ${files.size}개 파일" }, c.arg("branch"), c.arg("private") != "false")
                ok("GitHub에 올렸어요 → ${r.url}\n파일 ${r.files}개 · 커밋 ${r.commit}" + (if (r.created) " · 새 저장소(${if (c.arg("private") == "false") "공개" else "비공개"})" else "") + (if (r.skipped.isNotEmpty()) "\n제외(비밀·대용량): " + r.skipped.take(8).joinToString() else ""))
            }.getOrElse { fail(it.message ?: "GitHub 오류") }
        }
        put("mail.reply") { c -> mailDo(c) { a, pw ->
            val uid = c.arg("id").filter { it.isDigit() }.toLong()
            val orig = MailClient.read(a, pw, uid)
            val subj = orig.lineSequence().firstOrNull { it.startsWith("제목:") }?.removePrefix("제목:")?.trim() ?: ""
            val to = orig.lineSequence().firstOrNull { it.startsWith("보낸이:") }?.removePrefix("보낸이:")?.trim() ?: ""
            MailClient.send(a, pw, to, if (subj.startsWith("Re:")) subj else "Re: $subj", c.arg("body"), uid)
        } }

        // ── 일정 ───────────────────────────────────────────────
        put("cal.list") { c -> events(LocalDate.now(), LocalDate.now().plusDays(c.int("days", 7).toLong())) }
        put("cal.today") { _ -> events(LocalDate.now(), LocalDate.now().plusDays(1)) }
        put("cal.week") { _ -> val mon = LocalDate.now().with(DayOfWeek.MONDAY); events(mon, mon.plusDays(7)) }
        put("cal.free") { c -> freeSlots(LocalDate.parse(c.arg("date")), c.int("minutes", 60)) }
        put("cal.create") { c -> createEvent(c.arg("title"), c.arg("start"), c.int("minutes", 60), c.arg("place"), c.arg("note"), allDay = false) }
        put("cal.deadline") { c ->
            store.deadlines.update { (it + (c.arg("title") to c.arg("date"))).distinct() }; store.persistHub()
            store.memories.update { it + Memory(store.id("mem"), "마감: ${c.arg("title")} ${c.arg("date")}", "마감", System.currentTimeMillis()) }
            createEvent("[마감] " + c.arg("title"), c.arg("date") + " 09:00", 0, "", "HJTK가 등록", allDay = true)
        }

        // ── 연락 ───────────────────────────────────────────────
        put("contact.find") { c -> contacts(c.arg("name")) }
        put("sms.draft") { c -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.arg("to")}")).putExtra("sms_body", c.arg("body")), "문자 작성", "${c.arg("to")}에게 보낼 문자를 채웠어요. 보내기는 직접 눌러 주세요.") }
        put("call.dial") { c -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.arg("number")}")), "전화 준비", c.arg("number")) }
        put("share.text") { c -> launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.arg("text")).putExtra(Intent.EXTRA_SUBJECT, c.arg("title")), "공유"), "공유하기", c.arg("text").take(80)) }
        put("kakao.send") { c -> launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.arg("text")).setPackage("com.kakao.talk"), "카카오톡으로 보내기", c.arg("text").take(80)) }

        // ── 기기 ───────────────────────────────────────────────
        put("alarm.set") { c -> runCatching {
            val t = LocalTime.parse(c.arg("time").padStart(5, '0'))
            launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute).putExtra(AlarmClock.EXTRA_MESSAGE, c.arg("label", "HJTK")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "알람 설정", "${c.arg("time")} 알람")
        }.getOrElse { fail("시간 형식은 HH:mm") } }
        put("timer.start") { c -> launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.int("seconds", 60)).putExtra(AlarmClock.EXTRA_MESSAGE, c.arg("label", "HJTK")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "타이머", "${c.int("seconds", 60)}초") }
        put("life.pomodoro") { c -> launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.int("minutes", 25) * 60).putExtra(AlarmClock.EXTRA_MESSAGE, "집중: " + c.arg("label", "뽀모도로")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "뽀모도로", "${c.int("minutes", 25)}분 집중") }
        put("app.list") { c -> ok(apps().filter { c.arg("q").isBlank() || it.first.contains(c.arg("q"), true) }.take(80).joinToString("\n") { "${it.first} (${it.second})" }) }
        put("app.open") { c ->
            val q = c.arg("name"); val hit = apps().firstOrNull { it.second == q } ?: apps().firstOrNull { it.first.equals(q, true) } ?: apps().firstOrNull { it.first.contains(q, true) }
            val i = hit?.let { ctx.packageManager.getLaunchIntentForPackage(it.second) }
            if (i == null) fail("'$q' 앱을 찾지 못함") else launch(i, "${hit.first} 열기", "앱을 실행합니다")
        }
        put("settings.open") { c ->
            val a = when (c.arg("page")) { "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS; "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS; "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS; "apps" -> Settings.ACTION_APPLICATION_SETTINGS; else -> Settings.ACTION_SETTINGS }
            launch(Intent(a), "설정 열기", c.arg("page"))
        }
        put("clipboard.copy") { c -> withContext(Dispatchers.Main) { Clip.copy(ctx, c.arg("text"), false) }; ok("복사함(${c.arg("text").length}자)") }
        put("notify.post") { c -> Notifier.simple(ctx, c.arg("title"), c.arg("body")); ok("알림 표시") }
        put("device.battery") { _ ->
            val bm = ctx.getSystemService(BatteryManager::class.java)
            ok("배터리 ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% · ${if (bm.isCharging) "충전 중" else "방전 중"}")
        }
        put("device.info") { _ ->
            val st = StatFs(Environment.getDataDirectory().path)
            val cm = ctx.getSystemService(ConnectivityManager::class.java); val cap = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when { cap == null -> "오프라인"; cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터"; else -> "기타" }
            ok("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) · 여유 저장공간 ${st.availableBytes / 1_000_000_000}GB · 네트워크 $net")
        }
        put("torch.set") { c -> runCatching {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.setTorchMode(id, c.arg("on") == "true"); ok("손전등 ${if (c.arg("on") == "true") "켬" else "끔"}")
        }.getOrElse { fail("손전등을 쓸 수 없음") } }
        put("map.open") { c ->
            val q = c.arg("q")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(if (c.arg("app") == "google") "https://www.google.com/maps/search/${enc(q)}" else "https://m.map.naver.com/search2/search.naver?query=${enc(q)}"))
            launch(i, "지도", q)
        }
        put("map.route") { c ->
            val to = c.arg("to"); val mode = c.arg("mode", "transit")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${enc(to)}&travelmode=${if (mode == "car") "driving" else if (mode == "walk") "walking" else "transit"}"))
            launch(i, "길찾기", to)
        }
        put("translate.open") { c ->
            val t = c.arg("text"); val to = c.arg("to", "ko")
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://papago.naver.com/?sk=auto&tk=$to&st=${enc(t)}")), "번역기", t.take(60), true)
        }

        // ── 파일 ───────────────────────────────────────────────
        put("file.write") { c -> val n = safeName(c.arg("name")); File(dir, n).writeText(c.arg("content")); CommandResult.ok("저장: $n (${c.arg("content").length}자)", Artifact(n, "text/plain", "")) }
        put("task.artifact") { c -> val n = safeName(c.arg("name")); File(dir, n).writeText(c.arg("content")); store.msg(convId, Role.AI, "결과물 저장: $n", "artifact", mapOf("file" to n, "job" to jobId)); CommandResult.ok("결과물 저장: $n", Artifact(n, "text/plain", "")) }
        put("file.append") { c -> val n = safeName(c.arg("name")); File(dir, n).appendText(c.arg("content") + "\n"); ok("이어씀: $n") }
        put("file.read") { c -> val f = File(dir, safeName(c.arg("name"))).takeIf { it.exists() } ?: File(store.workspace, "shared/" + safeName(c.arg("name"))); if (!f.exists()) fail("파일 없음") else ok(f.readText().take(c.int("max", 12000))) }
        put("file.list") { _ -> ok((dir.listFiles()?.sortedBy { it.name } ?: emptyList()).joinToString("\n") { "${it.name} (${it.length()}B)" }.ifBlank { "파일 없음" }) }
        put("file.search") { c -> val q = c.arg("q"); ok(store.workspace.walkTopDown().filter { it.isFile && it.length() < 2_000_000 }.mapNotNull { f -> f.readLines().withIndex().firstOrNull { it.value.contains(q, true) }?.let { "${f.relativeTo(store.workspace)}:${it.index + 1}: ${it.value.take(120)}" } }.take(40).joinToString("\n").ifBlank { "없음" }) }
        put("file.rename") { c -> val f = File(dir, safeName(c.arg("name"))); if (!f.exists()) fail("파일 없음") else if (f.renameTo(File(dir, safeName(c.arg("to"))))) ok("이름 변경") else fail("변경 실패") }
        put("file.delete") { c -> if (File(dir, safeName(c.arg("name"))).delete()) ok("삭제함") else fail("파일 없음") }
        put("file.export") { c -> exportToDownloads(File(dir, safeName(c.arg("name")))) }
        put("file.share") { c ->
            val f = File(dir, safeName(c.arg("name"))); if (!f.exists()) return@put fail("파일 없음")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "파일 공유"), "파일 공유", f.name)
        }
        put("file.pick") { c -> val t = h.runner.pickFile(jobId, c.arg("hint", "파일")); if (t == null) fail("사용자가 파일을 고르지 않음") else ok(t.take(15000)) }
        put("doc.html") { c ->
            val src = File(dir, safeName(c.arg("name"))); if (!src.exists()) return@put fail("파일 없음")
            val out = src.nameWithoutExtension + ".html"
            File(dir, out).writeText(MiniMarkdown.page(c.arg("title", src.nameWithoutExtension), src.readText()))
            CommandResult.ok("HTML 저장: $out", Artifact(out, "text/html", ""))
        }
        put("doc.csv") { c -> val n = safeName(c.arg("name")).let { if (it.endsWith(".csv")) it else "$it.csv" }
            File(dir, n).writeText(c.arg("rows").lines().filter { it.isNotBlank() }.joinToString("\n") { r -> r.split('|').joinToString(",") { v -> v.trim().let { s -> if (s.contains(',')) "\"$s\"" else s } } })
            CommandResult.ok("CSV 저장: $n", Artifact(n, "text/csv", "")) }
        put("study.flashcards") { c -> val n = safeName(c.arg("name")).let { if (it.endsWith(".csv")) it else "$it.csv" }
            File(dir, n).writeText("앞,뒤\n" + c.arg("pairs").lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.joinToString(",") { it.trim() } }.joinToString("\n"))
            CommandResult.ok("암기카드 저장: $n", Artifact(n, "text/csv", "")) }
        put("life.checklist") { c -> val n = safeName(c.arg("name")).let { if (it.endsWith(".md")) it else "$it.md" }
            File(dir, n).writeText("# ${c.arg("name")}\n\n" + c.arg("items").lines().filter { it.isNotBlank() }.joinToString("\n") { "- [ ] ${it.trim().trimStart('-', ' ')}" })
            CommandResult.ok("체크리스트 저장: $n", Artifact(n, "text/markdown", "")) }
        put("life.dday_board") { _ ->
            val t = LocalDate.now()
            ok(store.deadlines.value.mapNotNull { (n, d) -> runCatching { ChronoUnit.DAYS.between(t, LocalDate.parse(d)) to "$n ($d)" }.getOrNull() }
                .sortedBy { it.first }.joinToString("\n") { (dd, s) -> (if (dd >= 0) "D-$dd " else "D+${-dd} ") + s }.ifBlank { "등록된 마감 없음(cal.deadline으로 등록)" })
        }

        // ── 기억 ───────────────────────────────────────────────
        put("mem.save") { c -> val m = Memory(store.id("mem"), c.arg("text"), c.arg("tag", "일반"), System.currentTimeMillis()); store.memories.update { it + m }; store.persistHub(); ok("기억함 [${m.id}]") }
        put("mem.search") { c -> ok(MemoryIndex.search(store.memories.value, c.arg("q"), c.int("n", 8)).joinToString("\n") { "[${it.first.id}] ${it.first.text}" }.ifBlank { "관련 기억 없음" }) }
        put("mem.list") { c -> ok(store.memories.value.filter { c.arg("tag").isBlank() || it.tag == c.arg("tag") }.joinToString("\n") { "[${it.id}] (${it.tag}) ${it.text}" }.ifBlank { "기억 없음" }) }
        put("mem.forget") { c -> store.memories.update { l -> l.filter { it.id != c.arg("id") } }; store.persistHub(); ok("지움") }

        // ── 작업 ───────────────────────────────────────────────
        put("task.approve") { c -> ok("사용자가 허락함: ${c.arg("what")}") }

        // ── 허브 ───────────────────────────────────────────────
        put("hub.sites") { _ -> ok(store.sites.value.joinToString("\n") { "${it.name} — ${it.url}" }.ifBlank { "없음" }) }
        put("hub.open") { c -> store.sites.value.firstOrNull { it.name.contains(c.arg("name"), true) }?.let { launch(Intent(Intent.ACTION_VIEW, Uri.parse(it.url)), it.name, it.url, true) } ?: fail("허브에 그 사이트가 없음") }
        put("hub.add_site") { c -> store.sites.update { it + com.ht.hjtk.data.Site(store.id("s"), c.arg("name"), c.arg("url")) }; store.persistHub(); ok("허브에 추가") }
        put("hub.apps") { _ -> ok(store.apps.value.joinToString("\n") { "${it.label} (${it.pkg})" }.ifBlank { "지정한 앱 없음" }) }
        put("hub.files") { _ -> ok(store.files.value.joinToString("\n") { it.name }.ifBlank { "고정한 파일 없음" }) }
        put("hub.commands") { _ -> ok(store.customs.value.joinToString("\n") { "${it.trigger} → ${it.expandsTo}" }.ifBlank { "없음" }) }
        put("hub.ai") { _ ->
            val s = store.settings.value
            ok("DeepSeek: 모드 ${s.dsMode} · 상태 ${h.deepseek.status.value} · 최근 경로 ${h.deepseek.lastVia.value.ifBlank { "-" }}\nGemini: 모드 ${s.geminiMode} · 앱 ${if (h.bridge.installed(ExternalAiBridge.GEMINI)) "설치됨" else "없음"} · API 키 ${if (h.gemini.available()) "있음" else "없음"}\n그림 읽기: ${h.ai.visionAssignment()?.name ?: "볼 수 있는 AI 없음(Claude·GPT·Gemini 키 필요)"}")
        }
        put("hub.workspace") { _ -> ok(store.workspace.listFiles()?.filter { it.isDirectory }?.joinToString("\n") { d -> "${d.name}/ " + (d.listFiles()?.joinToString(", ") { it.name } ?: "") } ?: "비어 있음") }

        // ── 파이프라인·스킬 ─────────────────────────────────────
        put("flow.list") { _ -> ok(store.pipelines.value.joinToString("\n") { "${it.name}: ${it.desc} (${it.steps.size}단계)" }.ifBlank { "없음" }) }
        put("flow.run") { c -> runPipelineInsideAgent(c.arg("name"), c.arg("input")) }
        put("flow.propose") { c ->
            val steps = c.arg("steps").lines().mapNotNull { PipelineStep.parseLine(it) }
            val p = Pipeline(store.id("p"), c.arg("name"), "AI 제안", steps)
            val comp = PipelineCompiler.compile(p)
            val ans = human.ask(jobId, "AI가 파이프라인 '${p.name}'(${comp.steps.size}단계, 자동 승인 노드 ${comp.insertedApprovals}개)을 제안했어요. 저장할까요?\n" + comp.steps.joinToString("\n") { "· " + (if (it.kind == "cmd") it.cmd + " " + it.args else it.kind + " " + it.prompt) }, listOf("저장", "버리기"))
            if (ans == "저장") { store.pipelines.update { it + p }; store.persistHub(); ok("저장됨") } else fail("사용자가 저장하지 않음")
        }
        put("skill.list") { _ -> ok(allSkills().joinToString("\n") { "${it.name}: ${it.whenToUse}" }) }
        put("skill.use") { c -> allSkills().firstOrNull { it.name.contains(c.arg("name"), true) }?.let { ok("스킬 '${it.name}' 지침:\n${it.guide}") } ?: fail("그 스킬이 없음") }
        put("skill.propose") { c ->
            val ans = human.ask(jobId, "AI가 스킬 '${c.arg("name")}'을 제안했어요.\n언제: ${c.arg("when")}\n지침: ${c.arg("guide").take(400)}\n저장할까요?", listOf("저장", "버리기"))
            if (ans == "저장") { store.skills.update { it + Skill(store.id("sk"), c.arg("name"), c.arg("when"), c.arg("guide")) }; store.persistHub(); ok("스킬 저장됨") } else fail("사용자가 저장하지 않음")
        }

        // ── 화면·사용자 ─────────────────────────────────────────
        put("ui.read_screen") { c -> h.runner.requestScreen(jobId, c.arg("reason"))?.let { ok(it) } ?: fail("사용자가 화면을 보내지 않았다") }
        put("ui.ask_screen") { c -> h.runner.requestScreen(jobId, c.arg("reason"))?.let { ok(it) } ?: fail("사용자가 화면을 보내지 않았다") }
        put("ui.show") { c -> store.msg(convId, Role.AI, c.arg("body"), "card", mapOf("title" to c.arg("title"), "job" to jobId)); ok("카드 표시") }
        put("ui.toast") { c ->
            if (AppVisibility.foreground.value) withContext(Dispatchers.Main) { Toast.makeText(ctx, c.arg("text"), Toast.LENGTH_SHORT).show() } else Notifier.simple(ctx, "HJTK", c.arg("text"))
            ok("표시함")
        }

        // ── 생활·특화 ───────────────────────────────────────────
        put("contest.search") { c ->
            val q = c.arg("q"); val n = c.int("n", 10)
            val list = coroutineScope {
                listOf(
                    async { Search.multi("$q 공모전 ${LocalDate.now().year}", n, naverKey()) },
                    async { Search.ddg("$q 공모전 (site:wevity.com OR site:thinkcontest.com OR site:linkareer.com OR site:contestkorea.com OR site:allforyoung.com)", n) },
                    async { Search.naverHtml("$q 공모전 접수중", n) }
                ).awaitAll().flatten()
            }
            hits(list.distinctBy { it.url }.take(n * 2), "공모전 결과 없음 — 키워드를 바꿔 보라")
        }
        put("job.search") { c -> hits(Search.ddg("${c.arg("q")} 채용 (site:saramin.co.kr OR site:jobkorea.co.kr OR site:wanted.co.kr)", 10).ifEmpty { Search.multi(c.arg("q") + " 채용", 8, naverKey()) }) }
        put("shop.search") { c -> hits(Search.multi("${c.arg("q")} 최저가 가격", 8, naverKey())) }
        put("place.search") { c -> hits(Search.multi("${c.arg("near")} ${c.arg("q")}".trim(), 8, naverKey())) }
    }

    // ── 보조 구현 ────────────────────────────────────────────────
    private suspend fun gemini(prompt: String): String? {
        // 3.0: Gemini 앱으로 넘기지 않는다. 키가 있으면 API, 없으면 HJTK 안 DeepSeek 두 번째 창이 대신 본다
        if (h.gemini.available()) h.gemini.ask(prompt)?.let { return it }
        return runCatching { h.runner.callAsk(prompt, "second:$jobId") }.getOrNull()?.ifBlank { null }
    }

    private suspend fun fetch(url: String, max: Int): CommandResult = runCatching {
        val r = Http.get(url)
        if (r.code >= 400) return fail("HTTP ${r.code}")
        val body = if (r.type.contains("html") || r.body.trimStart().startsWith("<")) "# ${HtmlText.title(r.body)}\n" + HtmlText.extract(r.body, max) else r.body.take(max)
        ok("$body\n(출처: ${r.url})")
    }.getOrElse { fail("읽기 실패: ${it.message}") }

    private suspend fun geocode(place: String): Triple<Double, Double, String>? = runCatching {
        val o = JSONObject(Http.get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(place)}&count=1&language=ko").body).getJSONArray("results").getJSONObject(0)
        Triple(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name") + (o.optString("admin1").takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
    }.getOrNull()

    private suspend fun weather(place: String, days: Int): CommandResult = runCatching {
        val g = geocode(place) ?: return fail("'$place' 위치를 찾지 못함")
        val o = JSONObject(Http.get("https://api.open-meteo.com/v1/forecast?latitude=${g.first}&longitude=${g.second}&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=${days.coerceIn(1, 7)}").body)
        val cur = o.getJSONObject("current"); val d = o.getJSONObject("daily")
        val sb = StringBuilder("${g.third} 지금 ${Num.fmt(cur.getDouble("temperature_2m"))}°C(체감 ${Num.fmt(cur.getDouble("apparent_temperature"))}°) ${wmo(cur.getInt("weather_code"))} · 습도 ${cur.getInt("relative_humidity_2m")}% · 바람 ${Num.fmt(cur.getDouble("wind_speed_10m"))}km/h")
        for (i in 0 until d.getJSONArray("time").length()) sb.append("\n${d.getJSONArray("time").getString(i)} ${wmo(d.getJSONArray("weather_code").getInt(i))} ${Num.fmt(d.getJSONArray("temperature_2m_min").getDouble(i))}~${Num.fmt(d.getJSONArray("temperature_2m_max").getDouble(i))}°C 강수확률 ${d.getJSONArray("precipitation_probability_max").optInt(i)}%")
        ok(sb.append("\n(Open-Meteo)").toString())
    }.getOrElse { fail("날씨 조회 실패: ${it.message}") }

    private fun wmo(c: Int) = when (c) { 0 -> "맑음"; 1, 2 -> "대체로 맑음"; 3 -> "흐림"; 45, 48 -> "안개"; in 51..57 -> "이슬비"; in 61..67 -> "비"; in 71..77 -> "눈"; in 80..82 -> "소나기"; in 85..86 -> "눈 소나기"; in 95..99 -> "뇌우"; else -> "날씨코드 $c" }

    private fun zoneOf(name: String): ZoneId {
        val m = mapOf("서울" to "Asia/Seoul", "도쿄" to "Asia/Tokyo", "오사카" to "Asia/Tokyo", "베이징" to "Asia/Shanghai", "상하이" to "Asia/Shanghai", "홍콩" to "Asia/Hong_Kong", "타이베이" to "Asia/Taipei",
            "싱가포르" to "Asia/Singapore", "방콕" to "Asia/Bangkok", "하노이" to "Asia/Ho_Chi_Minh", "다낭" to "Asia/Ho_Chi_Minh", "두바이" to "Asia/Dubai", "런던" to "Europe/London", "파리" to "Europe/Paris",
            "베를린" to "Europe/Berlin", "로마" to "Europe/Rome", "마드리드" to "Europe/Madrid", "모스크바" to "Europe/Moscow", "뉴욕" to "America/New_York", "워싱턴" to "America/New_York", "시카고" to "America/Chicago",
            "LA" to "America/Los_Angeles", "로스앤젤레스" to "America/Los_Angeles", "샌프란시스코" to "America/Los_Angeles", "시애틀" to "America/Los_Angeles", "토론토" to "America/Toronto", "밴쿠버" to "America/Vancouver",
            "시드니" to "Australia/Sydney", "멜버른" to "Australia/Melbourne", "오클랜드" to "Pacific/Auckland", "호놀룰루" to "Pacific/Honolulu", "하와이" to "Pacific/Honolulu", "괌" to "Pacific/Guam")
        return ZoneId.of(m[name.trim()] ?: name.trim())
    }

    private val GH_NEED = "GitHub 연결이 필요해요: 허브 → GitHub에서 토큰을 한 번 넣어 주세요(선택 기능)."
    /** GitHub 토큰: 허브 → GitHub에서 넣은 것, 없으면 '계정 연결'의 GitHub 계정 */
    private fun ghToken(): String? = store.secrets.get("github_token")?.takeIf { it.isNotBlank() } ?: h.connectors.token(com.ht.hjtk.engine.Service.GITHUB)?.second
    private fun ghHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer ${ghToken().orEmpty()}", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    private fun gitHub(): com.ht.hjtk.core.GitHubPush? {
        ghToken() ?: return null
        return com.ht.hjtk.core.GitHubPush { method, path, body ->
            val r = com.ht.hjtk.engine.GitHubHttp.call(method, "https://api.github.com$path", body?.toString(), ghHeaders())
            r.first to r.second
        }
    }
    /** 올릴 파일: path가 있으면 그 폴더·파일, 없으면 이 작업 → 같은 대화의 최근 작업 결과물 */
    private fun pushFiles(path: String): List<com.ht.hjtk.core.GitHubPush.File> {
        fun collect(root: File): List<com.ht.hjtk.core.GitHubPush.File> = if (root.isFile) listOf(com.ht.hjtk.core.GitHubPush.File(root.name, root.readBytes()))
            else root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "foundry.json" && !it.relativeTo(root).path.startsWith("dist") }.take(300).map { com.ht.hjtk.core.GitHubPush.File(it.relativeTo(root).path.replace('\\', '/'), it.readBytes()) }.toList()
        if (path.isNotBlank()) {
            val cands = listOf(File(dir, path), File(store.workspace, path))
            cands.firstOrNull { it.exists() && it.canonicalPath.startsWith(store.workspace.canonicalPath) }?.let { return collect(it) }
            return emptyList()
        }
        collect(dir).takeIf { it.isNotEmpty() }?.let { return it }
        store.jobs.value.filter { it.convId == convId && it.id != jobId }.sortedByDescending { it.updatedAt }.forEach { j ->
            val d = store.jobDir(j.id); collect(d).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private suspend fun mailDo(c: CommandCall, f: suspend (com.ht.hjtk.data.MailAccount, String) -> String): CommandResult {
        val acc = store.mail.value.let { l -> l.firstOrNull { c.arg("account").isNotBlank() && (it.email.contains(c.arg("account"), true) || it.provider.contains(c.arg("account"), true)) } ?: l.firstOrNull() }
            ?: return fail("연결된 메일 계정이 없습니다. 허브 → 연동 센터에서 메일을 연결하세요.")
        val pw = store.secrets.get("mail_${acc.id}") ?: return fail("${acc.email}의 앱 비밀번호가 없습니다")
        return runCatching { ok(f(acc, pw)) }.getOrElse { fail("메일 오류(${acc.email}): ${it.message?.take(200)}") }
    }

    @SuppressLint("Range")
    private suspend fun events(from: LocalDate, to: LocalDate): CommandResult = withContext(Dispatchers.IO) {
        if (!Perms.granted(ctx, Manifest.permission.READ_CALENDAR)) return@withContext fail("캘린더 권한이 없습니다. 허브 → 연동 센터 → 기기 캘린더를 켜세요(구글 캘린더 동기화 포함).")
        val z = ZoneId.systemDefault()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, from.atStartOfDay(z).toInstant().toEpochMilli()); android.content.ContentUris.appendId(b, to.atStartOfDay(z).toInstant().toEpochMilli())
        val out = ArrayList<String>()
        ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
            val fmt = DateTimeFormatter.ofPattern("MM-dd(E) HH:mm", java.util.Locale.KOREA)
            while (cur.moveToNext() && out.size < 80) {
                val begin = Instant.ofEpochMilli(cur.getLong(1)).atZone(z)
                val allDay = cur.getInt(4) == 1
                out += "${if (allDay) begin.format(DateTimeFormatter.ofPattern("MM-dd(E)", java.util.Locale.KOREA)) + " 종일" else begin.format(fmt)} ${cur.getString(0) ?: "(제목 없음)"}" +
                    (cur.getString(3)?.takeIf { it.isNotBlank() }?.let { " @$it" } ?: "") + " [${cur.getString(5) ?: ""}]"
            }
        }
        ok(out.joinToString("\n").ifBlank { "일정 없음 (${from} ~ ${to.minusDays(1)})" })
    }

    private suspend fun freeSlots(date: LocalDate, minutes: Int): CommandResult = withContext(Dispatchers.IO) {
        if (!Perms.granted(ctx, Manifest.permission.READ_CALENDAR)) return@withContext fail("캘린더 권한 필요")
        val z = ZoneId.systemDefault()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, date.atStartOfDay(z).toInstant().toEpochMilli()); android.content.ContentUris.appendId(b, date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
        val busy = ArrayList<Pair<LocalTime, LocalTime>>()
        ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getInt(2) == 0) busy += Instant.ofEpochMilli(c.getLong(0)).atZone(z).toLocalTime() to Instant.ofEpochMilli(c.getLong(1)).atZone(z).toLocalTime()
        }
        var t = LocalTime.of(9, 0); val end = LocalTime.of(21, 0); val free = ArrayList<String>()
        for ((s, e) in busy.sortedBy { it.first }) { if (ChronoUnit.MINUTES.between(t, s) >= minutes) free += "$t~$s"; if (e.isAfter(t)) t = e }
        if (ChronoUnit.MINUTES.between(t, end) >= minutes) free += "$t~$end"
        ok("$date 09~21시 중 ${minutes}분 이상 빈 시간: " + free.joinToString(", ").ifBlank { "없음" })
    }

    private suspend fun createEvent(title: String, start: String, minutes: Int, place: String, note: String, allDay: Boolean): CommandResult = withContext(Dispatchers.IO) {
        val st = runCatching { LocalDateTime.parse(start.trim().replace(' ', 'T').let { if (it.length == 16) it else it.take(16) }) }.getOrNull()
            ?: return@withContext fail("시작 형식은 YYYY-MM-DD HH:mm")
        val z = ZoneId.systemDefault()
        val begin = if (allDay) st.toLocalDate().atStartOfDay(z).toInstant().toEpochMilli() else st.atZone(z).toInstant().toEpochMilli()
        val endMs = if (allDay) begin + 86_400_000 else begin + minutes.coerceAtLeast(15) * 60_000L
        if (Perms.granted(ctx, Manifest.permission.WRITE_CALENDAR)) {
            val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                CalendarContract.Calendars.VISIBLE + "=1", null, null)?.use { c ->
                var best: Long? = null
                while (c.moveToNext()) { if (c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) { if (best == null || c.getInt(1) == 1) best = c.getLong(0) } }
                best
            }
            if (calId != null) {
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.DTSTART, begin); put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.EVENT_LOCATION, place); put(CalendarContract.Events.DESCRIPTION, note)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id); put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                }
                val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
                if (uri != null) return@withContext ok("일정 추가됨: $title ${start} (기기 캘린더 → 구글 동기화)")
            }
        }
        launch(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, place).putExtra(CalendarContract.Events.DESCRIPTION, note), "일정 추가", "$title $start — 저장을 눌러 주세요")
    }

    private suspend fun contacts(name: String): CommandResult = withContext(Dispatchers.IO) {
        if (!Perms.granted(ctx, Manifest.permission.READ_CONTACTS)) return@withContext fail("연락처 권한이 없습니다(허브 → 연동 센터)")
        val out = ArrayList<String>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null)?.use { c -> while (c.moveToNext() && out.size < 20) out += "${c.getString(0)} 전화 ${c.getString(1)}" }
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " LIKE ?", arrayOf("%$name%"), null)?.use { c -> while (c.moveToNext() && out.size < 30) { out += "${c.getString(0)} 메일 ${c.getString(1)}"; store.knownRecipients.update { s -> s + c.getString(1).lowercase() } } }
        ok(out.distinct().joinToString("\n").ifBlank { "'$name' 연락처 없음" })
    }

    private fun apps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first }
    }

    private fun installed(pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun exportToDownloads(f: File): CommandResult {
        if (!f.exists()) return fail("파일 없음")
        if (Build.VERSION.SDK_INT < 29) return fail("Android 10 이상에서 지원")
        val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HJTK") }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return fail("저장 실패")
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return ok("다운로드/HJTK/${f.name} 에 저장")
    }

    private fun allSkills() = Skill.BUILT_IN + store.skills.value

    private suspend fun runPipelineInsideAgent(name: String, input: String): CommandResult {
        val p = store.pipelines.value.firstOrNull { it.name.contains(name, true) } ?: return fail("'$name' 파이프라인 없음")
        val comp = PipelineCompiler.compile(p)
        val sb = StringBuilder("파이프라인 '${p.name}' 실행\n"); var prev = input
        for ((i, s) in comp.steps.withIndex()) {
            when (s.kind) {
                "approval" -> { val a = human.ask(jobId, s.prompt.ifBlank { "계속할까요?" }, listOf("계속", "중단")); if (a != "계속") { sb.append("${i + 1}. 사용자 중단\n"); break } }
                "ai" -> { sb.append("${i + 1}. [AI 지시 — 네가 수행하라] ${PipelineCompiler.fill(s.prompt, input, prev)}\n") }
                else -> {
                    val call = CommandCall("p$i", s.cmd, s.args.mapValues { PipelineCompiler.fill(it.value, input, prev) })
                    val spec = spec(s.cmd)
                    if (spec != null && spec.effect.needsApproval) { val a = human.ask(jobId, "파이프라인 단계: ${spec.title}\n${call.args}", listOf("실행", "건너뛰기")); if (a != "실행") { sb.append("${i + 1}. ${s.cmd} 건너뜀\n"); continue } }
                    val r = run(call); prev = r.text
                    sb.append("${i + 1}. ${s.cmd} ${if (r.ok) "✓" else "✗"}\n${r.text.take(1500)}\n")
                }
            }
        }
        return ok(sb.toString())
    }
}

/** 의존성 없는 최소 마크다운 → HTML(인쇄용, 외부 리소스 0) */
object MiniMarkdown {
    fun page(title: String, md: String): String = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<style>body{font-family:-apple-system,'Pretendard','Noto Sans KR',sans-serif;max-width:760px;margin:40px auto;padding:0 20px;color:#151515;line-height:1.7;background:#f6f3ec}h1,h2,h3{line-height:1.3}h1{border-bottom:2px solid #151515;padding-bottom:8px}code,pre{background:#ebe6db;border-radius:4px}pre{padding:12px;overflow:auto}code{padding:1px 4px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #cfc8b8;padding:6px 8px;text-align:left}th{background:#ebe6db}blockquote{border-left:3px solid #e2442f;margin:0;padding-left:12px;color:#555}a{color:#b8321f}footer{margin-top:48px;font:11px monospace;color:#888;letter-spacing:.08em}</style></head><body>
${html(md)}
<footer>HJTK · ${java.time.LocalDate.now()}</footer></body></html>"""

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun inline(s: String): String {
        var t = esc(s)
        t = Regex("`([^`]+)`").replace(t, "<code>$1</code>")
        t = Regex("\\*\\*([^*]+)\\*\\*").replace(t, "<b>$1</b>")
        t = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(t, "<i>$1</i>")
        t = Regex("\\[([^]]+)]\\((https?://[^)]+)\\)").replace(t, "<a href=\"$2\">$1</a>")
        return t
    }
    fun html(md: String): String {
        val out = StringBuilder(); val lines = md.lines(); var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.trim().startsWith("```") -> { val b = StringBuilder(); i++; while (i < lines.size && !lines[i].trim().startsWith("```")) { b.append(esc(lines[i])).append('\n'); i++ }; out.append("<pre><code>$b</code></pre>") }
                l.startsWith("#") -> { val n = l.takeWhile { it == '#' }.length.coerceIn(1, 6); out.append("<h$n>${inline(l.drop(n).trim())}</h$n>") }
                l.trim().startsWith("|") -> {
                    val rows = ArrayList<String>(); while (i < lines.size && lines[i].trim().startsWith("|")) { rows += lines[i]; i++ }; i--
                    out.append("<table>"); rows.filter { !it.replace("|", "").replace("-", "").replace(":", "").isBlank() || it.isBlank() }.forEachIndexed { k, r ->
                        val cells = r.trim().trim('|').split('|'); out.append("<tr>"); cells.forEach { out.append(if (k == 0) "<th>${inline(it.trim())}</th>" else "<td>${inline(it.trim())}</td>") }; out.append("</tr>") }
                    out.append("</table>")
                }
                l.trim().matches(Regex("^[-*] .*")) -> { out.append("<ul>"); while (i < lines.size && lines[i].trim().matches(Regex("^[-*] .*"))) { out.append("<li>${inline(lines[i].trim().drop(2))}</li>"); i++ }; i--; out.append("</ul>") }
                l.trim().matches(Regex("^\\d+[.)] .*")) -> { out.append("<ol>"); while (i < lines.size && lines[i].trim().matches(Regex("^\\d+[.)] .*"))) { out.append("<li>${inline(lines[i].trim().substringAfter(' '))}</li>"); i++ }; i--; out.append("</ol>") }
                l.startsWith(">") -> out.append("<blockquote>${inline(l.drop(1).trim())}</blockquote>")
                l.isBlank() -> {}
                else -> out.append("<p>${inline(l)}</p>")
            }
            i++
        }
        return out.toString()
    }
}
