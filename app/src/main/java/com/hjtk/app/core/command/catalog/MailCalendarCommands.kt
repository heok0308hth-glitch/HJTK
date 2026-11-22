package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.Clock
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction
import com.hjtk.app.core.util.TimeFmt

/** Mail through the account the user configured in Settings (IMAP + SMTP). */
object MailCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "mail.status",
                labelKo = "메일 연결 상태",
                category = CommandCategory.MAIL,
                effect = EffectClass.READ,
                description = "연결된 메일 계정과 설정 상태를 확인합니다.",
                keywords = listOf("메일 설정", "메일 연결", "계정 상태", "mail status")
            )
        ) { _, ports ->
            if (!ports.mail.isConfigured()) {
                CommandResult.Ok(
                    "아직 메일 계정이 연결되지 않았습니다.\n설정 > 메일에서 IMAP/SMTP 정보를 입력하면 읽기·보내기를 쓸 수 있습니다.",
                    actions = listOf(ResultAction("메일 설정 열기", "system.settings", mapOf("section" to "mail")))
                )
            } else {
                CommandResult.Ok("메일 연결됨: ${ports.mail.providerName()}\nIMAP으로 받은편지함을 읽고 SMTP로 보냅니다.")
            }
        }

        reg.register(
            CommandSpec(
                id = "mail.unread",
                labelKo = "안 읽은 메일 수",
                category = CommandCategory.MAIL,
                effect = EffectClass.READ,
                description = "받은편지함의 안 읽은 메일 개수를 확인합니다.",
                keywords = listOf("안읽은", "안 읽은", "메일 확인", "unread", "inbox")
            )
        ) { _, ports ->
            if (!ports.mail.isConfigured()) {
                return@register CommandResult.Failed(
                    "메일 계정이 연결되지 않았습니다. 설정 > 메일에서 계정을 추가해 주세요.",
                    retryable = false
                )
            }
            runCatching { ports.mail.unreadCount() }
                .map { n ->
                    CommandResult.Ok(
                        "안 읽은 메일 ${n}통입니다.",
                        actions = listOf(ResultAction("최근 메일 보기", "mail.latest", mapOf("limit" to "5")))
                    )
                }
                .getOrElse { failure(it, "IMAP 주소·포트와 앱 비밀번호를 확인해 주세요.") }
        }

        reg.register(
            CommandSpec(
                id = "mail.latest",
                labelKo = "최근 메일",
                category = CommandCategory.MAIL,
                effect = EffectClass.READ,
                description = "최근 메일 제목과 보낸 사람을 보여줍니다.",
                params = listOf(CommandRegistry.param("limit", "개수", false, "5")),
                keywords = listOf("최근 메일", "메일 목록", "받은편지함", "latest mail")
            )
        ) { req, ports ->
            if (!ports.mail.isConfigured()) {
                return@register CommandResult.Failed("메일 계정이 연결되지 않았습니다. 설정 > 메일에서 계정을 추가해 주세요.")
            }
            val limit = (req.arg("limit")?.toIntOrNull() ?: 5).coerceIn(1, 20)
            runCatching { ports.mail.latest(limit) }
                .map { mails ->
                    if (mails.isEmpty()) {
                        CommandResult.Ok("받은편지함이 비어 있습니다.")
                    } else {
                        CommandResult.Ok(
                            output = buildString {
                                appendLine("**최근 메일 ${mails.size}통**")
                                mails.forEach {
                                    appendLine()
                                    appendLine("· ${it.subject}")
                                    appendLine("  ${it.from} · ${it.date}")
                                    if (it.preview.isNotBlank()) appendLine("  ${it.preview.take(90)}")
                                }
                            }.trim()
                        )
                    }
                }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "mail.compose",
                labelKo = "메일 초안 작성",
                category = CommandCategory.MAIL,
                effect = EffectClass.EXTERNAL,
                description = "메일 앱에 초안을 열어 사용자가 직접 확인·발송하게 합니다.",
                params = listOf(
                    CommandRegistry.param("to", "받는 사람", false, "someone@example.com"),
                    CommandRegistry.param("subject", "제목", false, "주간 보고"),
                    CommandRegistry.param("body", "본문", false, "본문 내용")
                ),
                keywords = listOf("메일 써", "메일 작성", "초안", "draft"),
                patterns = listOf("^(?<subject>.+?)\\s*(?:이라는|라는)?\\s*제목으로\\s*메일\\s*(?:써|작성)")
            )
        ) { req, ports ->
            val to = req.arg("to").orEmpty()
            val subject = req.arg("subject").orEmpty()
            val body = req.arg("body").orEmpty()
            runCatching { ports.mail.composeDraft(to, subject, body) }
                .map { CommandResult.Ok("메일 앱에 초안을 열었습니다. 내용을 확인하고 직접 보내 주세요.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "mail.send",
                labelKo = "메일 발송",
                category = CommandCategory.MAIL,
                effect = EffectClass.EXTERNAL,
                description = "SMTP로 메일을 보냅니다. 실행 전 승인 카드가 표시됩니다.",
                params = listOf(
                    CommandRegistry.param("to", "받는 사람", true, "someone@example.com"),
                    CommandRegistry.param("subject", "제목", true, "안내"),
                    CommandRegistry.param("body", "본문", true, "본문 내용")
                ),
                keywords = listOf("메일 보내", "발송", "send mail", "이메일 보내")
            )
        ) { req, ports ->
            missing(reg, req, "mail.send")?.let { return@register it }
            if (!ports.mail.isConfigured()) {
                return@register CommandResult.Failed("메일 계정이 연결되지 않아 발송할 수 없습니다. 설정 > 메일에서 계정을 추가해 주세요.")
            }
            val to = req.arg("to")!!
            val subject = req.arg("subject")!!
            val body = req.arg("body")!!
            runCatching { ports.mail.send(to, subject, body) }
                .map { CommandResult.Ok("메일을 보냈습니다.\n받는 사람: $to\n제목: $subject") }
                .getOrElse { failure(it, "SMTP 설정과 앱 비밀번호를 확인해 주세요.") }
        }
    }
}

/** Calendar reads need permission; writes go through the platform UI. */
object CalendarCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "calendar.today",
                labelKo = "오늘 일정",
                category = CommandCategory.CALENDAR,
                effect = EffectClass.READ,
                description = "오늘의 캘린더 일정을 읽습니다.",
                keywords = listOf("일정", "오늘", "스케줄", "calendar", "today")
            )
        ) { _, ports ->
            if (!ports.calendar.canRead()) {
                return@register CommandResult.Failed(
                    "캘린더 읽기 권한이 없습니다. 설정 > 권한에서 ‘일정’ 접근을 허용해 주세요.",
                    retryable = false
                )
            }
            runCatching { ports.calendar.today() }
                .map { items ->
                    if (items.isEmpty()) {
                        CommandResult.Ok("오늘은 등록된 일정이 없습니다.", actions = listOf(ResultAction("새 일정 추가", "calendar.new_event", emptyMap())))
                    } else {
                        CommandResult.Ok(
                            output = buildString {
                                appendLine("**오늘 일정 ${items.size}개**")
                                items.forEach {
                                    appendLine()
                                    appendLine("· ${it.start}–${it.end}  ${it.title}")
                                    if (it.location.isNotBlank()) appendLine("  ${it.location}")
                                }
                            }.trim()
                        )
                    }
                }
                .getOrElse { failure(it, "캘린더 앱과 동기화되어 있는지 확인해 주세요.") }
        }

        reg.register(
            CommandSpec(
                id = "calendar.new_event",
                labelKo = "일정 추가",
                category = CommandCategory.CALENDAR,
                effect = EffectClass.EXTERNAL,
                description = "캘린더 앱의 일정 추가 화면을 시간까지 채워 엽니다.",
                params = listOf(
                    CommandRegistry.param("title", "일정 이름", true, "치과 예약"),
                    CommandRegistry.param("when", "시작 시각", true, "내일 15:00"),
                    CommandRegistry.param("minutes", "소요(분)", false, "60")
                ),
                keywords = listOf("일정 추가", "약속", "캘린더에", "event"),
                patterns = listOf(
                    "^(?<when>내일|모레|오늘)?\\s*(?<clock>[0-9]{1,2}:[0-9]{2})?\\s*(?<title>.+?)\\s*(?:일정|약속)\\s*(?:추가|등록|잡아)"
                )
            )
        ) { req, ports ->
            missing(reg, req, "calendar.new_event")?.let { return@register it }
            val title = req.arg("title")!!
            val whenText = req.arg("when")!!
            val minutes = (req.arg("minutes")?.toIntOrNull() ?: 60).coerceIn(5, 600)
            val start = parseWhen(whenText, ports.clock)
                ?: return@register CommandResult.Failed("시각을 이해하지 못했습니다. ‘내일 15:00’ 또는 ‘2026-11-25 09:30’처럼 입력해 주세요.")
            val end = start + minutes * 60_000L
            runCatching { ports.calendar.insert(title, start, end, "") }
                .map { CommandResult.Ok("일정 추가 화면을 열었습니다.\n$title\n${TimeFmt.stamp(start)}–${TimeFmt.clock(end)} (${minutes}분)\n캘린더 앱에서 저장을 눌러 주세요.") }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "calendar.open",
                labelKo = "캘린더 열기",
                category = CommandCategory.CALENDAR,
                effect = EffectClass.EXTERNAL,
                description = "캘린더 앱을 엽니다.",
                keywords = listOf("캘린더 열어", "달력", "calendar open")
            )
        ) { _, ports ->
            runCatching { ports.device.openSettings("calendar") }
                .map { CommandResult.Ok("캘린더를 열었습니다.") }
                .getOrElse { CommandResult.Failed("캘린더 앱을 찾지 못했습니다.") }
        }
    }

    /** Natural-language time: "내일 15:00", "오늘 저녁 7시", "2026-11-25 09:30". */
    internal fun parseWhen(text: String, clock: Clock): Long? {
        val now = clock.nowMs()
        var dayOffset = 0
        var t = text.trim()
        when {
            t.startsWith("모레") -> { dayOffset = 2; t = t.removePrefix("모레").trim() }
            t.startsWith("내일") -> { dayOffset = 1; t = t.removePrefix("내일").trim() }
            t.startsWith("오늘") -> { dayOffset = 0; t = t.removePrefix("오늘").trim() }
        }
        t = t.removePrefix("저녁").removePrefix("아침").removePrefix("오전").removePrefix("오후").trim()
        if (t.isBlank()) return null

        val clockMatch = Regex("^([0-9]{1,2})\\s*[:시]\\s*([0-9]{1,2})?\\s*분?$").find(t)
        if (clockMatch != null) {
            val h = clockMatch.groupValues[1].toIntOrNull() ?: return null
            val m = clockMatch.groupValues[2].toIntOrNull() ?: 0
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
                set(java.util.Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                set(java.util.Calendar.MINUTE, m.coerceIn(0, 59))
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (dayOffset == 0 && cal.timeInMillis < now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis
        }
        val parsed = TimeFmt.parseWhen(t, now) ?: return null
        return java.util.Calendar.getInstance().apply {
            timeInMillis = parsed
            add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
        }.timeInMillis
    }
}
