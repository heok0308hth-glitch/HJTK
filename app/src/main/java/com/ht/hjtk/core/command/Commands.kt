package com.ht.hjtk.core.command

/**
 * HJTK에는 생성형 모델이 없다. 대신 "명령어"가 있다.
 * DeepSeek은 이 목록을 읽고 명령을 고르고, 앱은 그것을 실행해 결과를 돌려준다.
 * 사용자도 같은 명령을 /슬래시 로 직접 쓸 수 있다.
 */
enum class Effect(val level: Int, val label: String, val needsApproval: Boolean) {
    READ(0, "읽기", false),
    LOCAL(1, "기기 안 변경", false),
    EXTERNAL(2, "밖으로 보냄", true),
    MONEY(3, "금전", true),
    DESTRUCT(4, "삭제·되돌릴 수 없음", true);
    companion object { fun of(s: String?) = values().firstOrNull { it.name.equals(s, true) || "E${it.level}" == s?.uppercase() } }
}

enum class Category(val label: String, val code: String) {
    AI("AI 협업", "AI"), WEB("웹·검색", "WEB"), DATA("데이터", "DATA"), MAIL("메일", "MAIL"),
    CAL("일정", "CAL"), COMM("연락", "COMM"), DEVICE("기기", "DEV"), FILE("파일", "FILE"),
    TEXT("텍스트", "TEXT"), CALC("계산", "CALC"), MEMORY("기억", "MEM"), TASK("작업", "TASK"),
    HUB("허브", "HUB"), FLOW("파이프라인·스킬", "FLOW"), UI("화면·사용자", "UI"), LIFE("생활·특화", "LIFE")
}

data class Param(val name: String, val desc: String, val required: Boolean)

data class CommandSpec(
    val id: String,
    val title: String,
    val category: Category,
    val effect: Effect,
    val params: List<Param>,
    val desc: String,
    val aliases: List<String> = emptyList()
) {
    /** 프롬프트용 한 줄. 토큰을 아끼기 위해 짧게. */
    fun line(): String {
        val p = params.joinToString(",") { (if (it.required) "" else "?") + it.name }
        val e = if (effect.level >= 2) " [E${effect.level}:승인]" else ""
        return "$id($p) $desc$e"
    }
}

data class CommandCall(val callId: String, val cmd: String, val args: Map<String, String>) {
    fun arg(k: String, def: String = ""): String = args[k]?.takeIf { it.isNotBlank() } ?: def
    fun int(k: String, def: Int): Int = args[k]?.trim()?.toDoubleOrNull()?.toInt() ?: def
}

data class CommandResult(
    val ok: Boolean,
    val text: String,
    val artifact: Artifact? = null,
    val userVisible: Boolean = false
) {
    companion object {
        fun ok(t: String, artifact: Artifact? = null) = CommandResult(true, t, artifact)
        fun fail(t: String) = CommandResult(false, t)
    }
}

data class Artifact(val name: String, val mime: String, val content: String)

fun interface CommandHandler { suspend fun run(call: CommandCall): CommandResult }

private fun p(dsl: String): List<Param> = if (dsl.isBlank()) emptyList() else dsl.split('|').map { s ->
    val req = s.substringBefore(':').endsWith("*")
    val name = s.substringBefore(':').trimEnd('*').trim()
    Param(name, s.substringAfter(':', ""), req)
}

private fun c(id: String, title: String, cat: Category, e: Effect, params: String, desc: String, vararg alias: String) =
    CommandSpec(id, title, cat, e, p(params), desc, alias.toList())

object CommandCatalog {
    val ALL: List<CommandSpec> = listOf(
        // ── AI 협업 ───────────────────────────────────────────────
        c("ai.gemini", "Gemini에게 묻기", Category.AI, Effect.READ, "prompt*:질문", "Gemini에게 질문을 보내고 답을 받는다(키 없으면 사용자 1탭)", "제미나이"),
        c("ai.critique", "교차 검토", Category.AI, Effect.READ, "draft*:검토할 초안|req:요구사항", "다른 AI(Gemini)가 반증·형식·실행성 3역할로 1회 배치 검토", "검토"),
        c("ai.second_opinion", "두 번째 의견", Category.AI, Effect.READ, "question*:질문|mine:내 답", "같은 질문을 Gemini에 보내 내 답과 비교", "의견"),
        c("ai.verify", "결정론 검증", Category.AI, Effect.READ, "text*:검증할 글|req:요구 체크리스트(줄바꿈)", "형식·수치합계·링크·요구사항 커버리지를 기계로 검사(L0)", "검증"),

        // ── 웹·검색 ───────────────────────────────────────────────
        c("web.search", "웹 검색", Category.WEB, Effect.READ, "q*:검색어|n:개수(기본6)", "네이버·DuckDuckGo·위키를 동시에 검색해 제목/주소/요약을 돌려준다", "검색"),
        c("web.naver", "네이버 검색", Category.WEB, Effect.READ, "q*:검색어|type:web/news/blog", "네이버 검색 결과(키 있으면 공식 API)", "네이버"),
        c("web.news", "뉴스 검색", Category.WEB, Effect.READ, "q*:검색어|n:개수", "최신 뉴스 헤드라인(Google News RSS)", "뉴스"),
        c("web.wiki", "위키 요약", Category.WEB, Effect.READ, "q*:주제|lang:ko/en", "위키백과 요약", "위키"),
        c("web.fetch", "페이지 읽기", Category.WEB, Effect.READ, "url*:주소|max:최대글자", "웹페이지 본문 텍스트를 읽는다", "읽기"),
        c("web.links", "링크 추출", Category.WEB, Effect.READ, "url*:주소|filter:포함단어", "페이지 안의 링크 목록", "링크"),
        c("web.site", "사이트 내 검색", Category.WEB, Effect.READ, "site*:도메인|q*:검색어", "특정 사이트 안에서만 검색", "사이트검색"),
        c("web.check", "주소 확인", Category.WEB, Effect.READ, "url*:주소", "링크가 살아있는지(상태코드)", "링크확인"),
        c("web.rss", "RSS 읽기", Category.WEB, Effect.READ, "url*:피드주소|n:개수", "RSS/Atom 최신 글", "rss"),
        c("web.open", "브라우저로 열기", Category.WEB, Effect.LOCAL, "url*:주소", "사용자 화면에 페이지를 연다(Custom Tab)", "열기"),
        c("web.youtube", "유튜브 찾기", Category.WEB, Effect.LOCAL, "q*:검색어", "유튜브 검색 화면을 연다", "유튜브"),
        c("web.images", "이미지 검색 열기", Category.WEB, Effect.LOCAL, "q*:검색어", "네이버 이미지 검색을 연다", "이미지"),
        c("web.meta", "링크 미리보기", Category.WEB, Effect.READ, "url*:주소", "제목·설명·대표이미지(OG 메타)", "미리보기"),
        c("web.multi", "다중 페이지 읽기", Category.WEB, Effect.READ, "urls*:주소들(줄바꿈)|max:페이지당 글자", "여러 페이지를 한 번에 읽어 묶는다", "여러페이지"),

        // ── 데이터 ───────────────────────────────────────────────
        c("data.weather", "날씨", Category.DATA, Effect.READ, "place*:지역|days:일수", "현재·예보 날씨(Open-Meteo, 키 불필요)", "날씨"),
        c("data.exchange", "환율", Category.DATA, Effect.READ, "from*:통화|to*:통화|amount:금액", "실시간 환율 환산", "환율"),
        c("data.time", "세계 시각", Category.DATA, Effect.READ, "zone*:도시나 타임존", "그 지역의 지금 시각", "시각"),
        c("data.holiday", "공휴일", Category.DATA, Effect.READ, "year:연도|country:KR", "공휴일 목록", "공휴일"),
        c("data.air", "미세먼지", Category.DATA, Effect.READ, "place*:지역", "미세먼지·초미세먼지·자외선(Open-Meteo)", "미세먼지"),
        c("data.sun", "일출·일몰", Category.DATA, Effect.READ, "place*:지역", "오늘 일출·일몰 시각", "일출"),
        c("data.crypto", "코인 시세", Category.DATA, Effect.READ, "coin*:bitcoin/ethereum 등|vs:krw/usd", "암호화폐 현재가(CoinGecko)", "코인"),
        c("data.geocode", "위치 좌표", Category.DATA, Effect.READ, "place*:지명", "지명을 좌표로", "좌표"),

        // ── 메일 ─────────────────────────────────────────────────
        c("mail.accounts", "메일 계정", Category.MAIL, Effect.READ, "", "연결된 메일 계정 목록", "메일계정"),
        c("mail.list", "받은 메일", Category.MAIL, Effect.READ, "account:계정|n:개수|unread:true", "최근 메일 목록", "메일"),
        c("mail.search", "메일 검색", Category.MAIL, Effect.READ, "q*:검색어|account:계정|n:개수", "제목·보낸이로 메일 검색", "메일검색"),
        c("mail.read", "메일 읽기", Category.MAIL, Effect.READ, "id*:메일ID|account:계정", "메일 본문", "메일읽기"),
        c("mail.unread", "안 읽은 수", Category.MAIL, Effect.READ, "account:계정", "안 읽은 메일 수", "안읽음"),
        c("mail.draft", "메일 초안", Category.MAIL, Effect.LOCAL, "to*:받는이|subject*:제목|body*:본문", "초안을 작업공간에 저장(발송 안 함)", "초안"),
        c("mail.send", "메일 보내기", Category.MAIL, Effect.EXTERNAL, "to*:받는이|subject*:제목|body*:본문|account:계정", "SMTP로 실제 발송", "메일보내기"),
        c("mail.reply", "메일 답장", Category.MAIL, Effect.EXTERNAL, "id*:원본ID|body*:본문|account:계정", "원본에 답장 발송", "답장"),
        c("mail.mark_read", "읽음 표시", Category.MAIL, Effect.LOCAL, "id*:메일ID|account:계정", "읽음으로 표시", "읽음"),

        // ── 일정 ─────────────────────────────────────────────────
        c("cal.list", "일정 보기", Category.CAL, Effect.READ, "days:앞으로 며칠(기본7)", "기기 캘린더(구글 동기화 포함) 일정", "일정"),
        c("cal.today", "오늘 일정", Category.CAL, Effect.READ, "", "오늘 일정", "오늘"),
        c("cal.free", "빈 시간", Category.CAL, Effect.READ, "date*:YYYY-MM-DD|minutes:필요한 분", "그날의 빈 시간대", "빈시간"),
        c("cal.week", "이번 주 일정", Category.CAL, Effect.READ, "", "이번 주 월~일 일정", "이번주"),
        c("cal.create", "일정 만들기", Category.CAL, Effect.LOCAL, "title*:제목|start*:YYYY-MM-DD HH:mm|minutes:길이|place:장소|note:메모", "캘린더 일정 추가 화면을 채워 연다", "일정추가"),
        c("cal.deadline", "마감 등록", Category.CAL, Effect.LOCAL, "title*:이름|date*:YYYY-MM-DD", "마감일을 종일 일정+기억으로 등록", "마감"),

        // ── 연락 ─────────────────────────────────────────────────
        c("contact.find", "연락처 찾기", Category.COMM, Effect.READ, "name*:이름", "기기 연락처 검색", "연락처"),
        c("sms.draft", "문자 작성", Category.COMM, Effect.LOCAL, "to*:번호|body*:내용", "문자 앱에 내용을 채워 연다(보내기는 사용자)", "문자"),
        c("call.dial", "전화 걸기 준비", Category.COMM, Effect.LOCAL, "number*:번호", "전화 앱에 번호를 채운다(통화는 사용자)", "전화"),
        c("share.text", "공유하기", Category.COMM, Effect.EXTERNAL, "text*:내용|title:제목", "공유 시트로 다른 앱에 보낸다", "공유"),
        c("kakao.send", "카카오톡으로 보내기", Category.COMM, Effect.EXTERNAL, "text*:내용", "카카오톡 공유 화면을 연다", "카톡"),

        // ── 기기 ─────────────────────────────────────────────────
        c("alarm.set", "알람", Category.DEVICE, Effect.LOCAL, "time*:HH:mm|label:이름", "시계 앱에 알람 설정", "알람"),
        c("timer.start", "타이머", Category.DEVICE, Effect.LOCAL, "seconds*:초|label:이름", "타이머 시작", "타이머"),
        c("app.open", "앱 열기", Category.DEVICE, Effect.LOCAL, "name*:앱이름이나 패키지", "설치된 앱 실행", "앱"),
        c("app.list", "앱 목록", Category.DEVICE, Effect.READ, "q:필터", "설치된 앱(실행 가능) 목록", "앱목록"),
        c("settings.open", "설정 열기", Category.DEVICE, Effect.LOCAL, "page*:wifi/bluetooth/display/sound/battery/apps/location", "시스템 설정 화면", "설정"),
        c("clipboard.copy", "복사", Category.DEVICE, Effect.LOCAL, "text*:내용", "클립보드에 복사", "복사"),
        c("notify.post", "알림 띄우기", Category.DEVICE, Effect.LOCAL, "title*:제목|body:내용", "기기 알림", "알림"),
        c("device.battery", "배터리", Category.DEVICE, Effect.READ, "", "배터리 잔량·충전 상태", "배터리"),
        c("device.info", "기기 정보", Category.DEVICE, Effect.READ, "", "모델·안드로이드 버전·저장공간·네트워크", "기기"),
        c("torch.set", "손전등", Category.DEVICE, Effect.LOCAL, "on*:true/false", "손전등 켜기/끄기", "손전등"),
        c("map.open", "지도 열기", Category.DEVICE, Effect.LOCAL, "q*:장소|app:naver/google", "지도 앱에서 장소 검색", "지도"),
        c("map.route", "길찾기", Category.DEVICE, Effect.LOCAL, "to*:목적지|mode:car/transit/walk", "길찾기 화면을 연다", "길찾기"),

        // ── 파일 ─────────────────────────────────────────────────
        c("file.write", "파일 쓰기", Category.FILE, Effect.LOCAL, "name*:파일명|content*:내용", "작업공간에 파일 저장(같은 이름은 덮어씀)", "저장"),
        c("file.append", "파일 이어쓰기", Category.FILE, Effect.LOCAL, "name*:파일명|content*:내용", "파일 끝에 추가", "이어쓰기"),
        c("file.read", "파일 읽기", Category.FILE, Effect.READ, "name*:파일명|max:최대글자", "작업공간 파일 읽기", "파일읽기"),
        c("file.list", "파일 목록", Category.FILE, Effect.READ, "dir:폴더", "작업공간 파일 목록", "파일"),
        c("file.search", "파일 내용 검색", Category.FILE, Effect.READ, "q*:찾을 말", "작업공간 파일 안에서 검색", "파일검색"),
        c("file.rename", "파일 이름 바꾸기", Category.FILE, Effect.LOCAL, "name*:파일명|to*:새 이름", "작업공간 파일 이름 변경", "이름변경"),
        c("file.delete", "파일 삭제", Category.FILE, Effect.DESTRUCT, "name*:파일명", "작업공간 파일 삭제", "삭제"),
        c("file.export", "기기로 내보내기", Category.FILE, Effect.EXTERNAL, "name*:파일명", "사용자가 고른 위치(다운로드·드라이브)로 저장", "내보내기"),
        c("file.share", "파일 공유", Category.FILE, Effect.EXTERNAL, "name*:파일명", "다른 앱으로 파일 공유", "파일공유"),
        c("file.pick", "파일 받기", Category.FILE, Effect.READ, "hint:무슨 파일인지", "사용자에게 파일을 골라 달라고 요청해 텍스트를 읽는다", "파일선택"),
        c("doc.html", "문서 HTML 변환", Category.FILE, Effect.LOCAL, "name*:md파일|title:제목", "마크다운을 인쇄용 HTML로 변환해 저장", "html"),
        c("doc.csv", "표 파일 만들기", Category.FILE, Effect.LOCAL, "name*:파일명|rows*:행들(줄바꿈, 칸은 |)", "CSV 파일 생성", "csv"),

        // ── 텍스트 (결정론) ───────────────────────────────────────
        c("text.count", "글자 수", Category.TEXT, Effect.READ, "text*:글", "글자·단어·줄·공백제외 글자 수", "글자수"),
        c("text.regex", "정규식 찾기", Category.TEXT, Effect.READ, "text*:글|pattern*:정규식", "일치 항목 목록", "정규식"),
        c("text.replace", "바꾸기", Category.TEXT, Effect.READ, "text*:글|find*:찾을말|to:바꿀말|regex:true", "찾아 바꾸기", "바꾸기"),
        c("text.lines", "줄 정리", Category.TEXT, Effect.READ, "text*:글|op*:sort/dedupe/reverse/number/trim", "줄 단위 정렬·중복제거·번호", "줄정리"),
        c("text.extract", "추출", Category.TEXT, Effect.READ, "text*:글|what*:email/url/phone/number/date/hashtag", "이메일·주소·전화·숫자·날짜 추출", "추출"),
        c("text.truncate", "자르기", Category.TEXT, Effect.READ, "text*:글|max*:글자", "지정 길이로 자르기(문장 경계)", "자르기"),
        c("text.diff", "비교", Category.TEXT, Effect.READ, "a*:원본|b*:수정본", "줄 단위 차이", "비교"),
        c("text.template", "템플릿 채우기", Category.TEXT, Effect.READ, "template*:{이름}이 있는 틀|vars*:이름=값(줄바꿈)", "틀에 값 채우기", "템플릿"),
        c("text.wordfreq", "자주 나온 말", Category.TEXT, Effect.READ, "text*:글|n:개수", "단어 빈도 상위", "빈도"),
        c("text.table", "표로 만들기", Category.TEXT, Effect.READ, "csv*:쉼표나 탭 구분 데이터", "마크다운 표로 변환", "표"),
        c("text.toc", "목차 만들기", Category.TEXT, Effect.READ, "md*:마크다운", "제목으로 목차 생성", "목차"),
        c("text.slug", "슬러그", Category.TEXT, Effect.READ, "text*:글", "파일명·주소용 슬러그", "슬러그"),
        c("text.case", "대소문자", Category.TEXT, Effect.READ, "text*:글|to*:upper/lower/title", "대소문자 변환", "대소문자"),
        c("text.split", "나누기", Category.TEXT, Effect.READ, "text*:글|by*:구분자", "구분자로 나눠 번호 목록", "나누기"),
        c("text.wrap", "줄바꿈 맞추기", Category.TEXT, Effect.READ, "text*:글|width:칸수", "고정 폭 줄바꿈", "줄바꿈"),
        c("text.manuscript", "원고지 매수", Category.TEXT, Effect.READ, "text*:글", "200자 원고지 기준 매수·공백 포함/제외", "원고지"),
        c("json.get", "JSON 값", Category.TEXT, Effect.READ, "json*:JSON|path*:a.b[0].c", "경로로 값 꺼내기", "json"),
        c("json.pretty", "JSON 정리", Category.TEXT, Effect.READ, "json*:JSON", "들여쓰기 정리·유효성 검사", "json정리"),
        c("json.csv", "JSON→CSV", Category.TEXT, Effect.READ, "json*:객체 배열", "CSV로 변환", "jsoncsv"),
        c("code.base64", "Base64", Category.TEXT, Effect.READ, "text*:글|mode*:encode/decode", "Base64 변환", "base64"),
        c("code.url", "URL 인코딩", Category.TEXT, Effect.READ, "text*:글|mode*:encode/decode", "URL 인코딩 변환", "url인코딩"),
        c("code.hash", "해시", Category.TEXT, Effect.READ, "text*:글|algo:sha256/md5", "해시값", "해시"),
        c("text.markdown_strip", "서식 제거", Category.TEXT, Effect.READ, "md*:마크다운", "마크다운 기호를 지운 순수 텍스트", "서식제거"),

        // ── 계산 (결정론) ───────────────────────────────────────
        c("calc.eval", "계산", Category.CALC, Effect.READ, "expr*:식", "사칙연산·괄호·거듭제곱·%·sqrt 등", "계산"),
        c("calc.unit", "단위 변환", Category.CALC, Effect.READ, "value*:값|from*:단위|to*:단위", "길이·무게·온도·부피·넓이·속도·데이터·평", "단위"),
        c("calc.date_diff", "날짜 차이", Category.CALC, Effect.READ, "a*:YYYY-MM-DD|b*:YYYY-MM-DD", "두 날짜 사이 일수", "날짜차이"),
        c("calc.date_add", "날짜 더하기", Category.CALC, Effect.READ, "date*:YYYY-MM-DD|days*:일수", "며칠 뒤/앞 날짜와 요일", "날짜더하기"),
        c("calc.dday", "디데이", Category.CALC, Effect.READ, "date*:YYYY-MM-DD", "오늘 기준 D-day", "디데이"),
        c("calc.percent", "퍼센트", Category.CALC, Effect.READ, "a*:값|b*:기준|op:of/change", "비율·증감률", "퍼센트"),
        c("calc.loan", "대출 상환", Category.CALC, Effect.READ, "principal*:원금|rate*:연이율%|months*:개월", "원리금균등 월 상환액·총이자", "대출"),
        c("calc.split", "나눠 내기", Category.CALC, Effect.READ, "total*:총액|people*:인원|tip:팁%", "1인당 금액", "더치"),
        c("calc.stats", "통계", Category.CALC, Effect.READ, "numbers*:숫자들", "평균·중앙값·표준편차·최소·최대", "통계"),
        c("calc.random", "무작위", Category.CALC, Effect.READ, "min:최소|max:최대|pick:고를 항목(쉼표)", "난수 또는 항목 뽑기", "랜덤"),
        c("calc.age", "나이", Category.CALC, Effect.READ, "birth*:YYYY-MM-DD", "만 나이·한국식 나이", "나이"),
        c("calc.vat", "부가세", Category.CALC, Effect.READ, "amount*:금액|mode:add/remove", "부가세 10% 더하기·빼기", "부가세"),
        c("calc.compound", "복리", Category.CALC, Effect.READ, "principal*:원금|rate*:연이율%|years*:년|monthly:월적립", "복리·적립식 미래가치", "복리"),
        c("calc.unit_price", "단가 비교", Category.CALC, Effect.READ, "items*:이름=가격/용량(줄바꿈)", "용량당 가격으로 가성비 순위", "단가"),
        c("calc.time_add", "시간 계산", Category.CALC, Effect.READ, "time*:HH:mm|minutes*:더할 분", "몇 분 뒤/앞 시각", "시간계산"),
        c("calc.bmi", "BMI", Category.CALC, Effect.READ, "height*:cm|weight*:kg", "체질량지수", "bmi"),
        c("calc.budget", "예산 합계", Category.CALC, Effect.READ, "items*:항목=금액(줄바꿈)|limit:한도", "합계·한도 대비·항목 비중", "예산"),

        // ── 기억 ─────────────────────────────────────────────────
        c("mem.save", "기억하기", Category.MEMORY, Effect.LOCAL, "text*:기억할 사실|tag:분류", "사용자에 대한 사실·선호를 저장", "기억"),
        c("mem.search", "기억 찾기", Category.MEMORY, Effect.READ, "q*:검색어|n:개수", "저장된 기억 검색(BM25)", "기억찾기"),
        c("mem.list", "기억 목록", Category.MEMORY, Effect.READ, "tag:분류", "기억 전체 목록", "기억목록"),
        c("mem.forget", "기억 지우기", Category.MEMORY, Effect.DESTRUCT, "id*:기억ID", "기억 삭제", "잊기"),

        // ── 작업 ─────────────────────────────────────────────────
        c("task.plan", "계획 세우기", Category.TASK, Effect.READ, "steps*:단계(줄바꿈, '이름|예상초')", "작업 계획·예상 시간을 갱신", "계획"),
        c("task.report", "지금 보고", Category.TASK, Effect.READ, "summary*:보고 내용", "사용자에게 즉시 중간 보고", "보고"),
        c("task.ask", "사용자에게 묻기", Category.TASK, Effect.READ, "question*:질문|choices:선택지(|구분)", "선택이나 정보가 필요할 때 묻고 기다린다", "묻기"),
        c("task.approve", "허락 받기", Category.TASK, Effect.EXTERNAL, "what*:무엇을 하려는지", "중요한 결정 전에 명시적으로 허락을 받는다", "허락"),
        c("task.wait", "기다리기", Category.TASK, Effect.READ, "seconds*:초(최대600)", "지정 시간 대기 후 계속", "대기"),
        c("task.note", "작업 메모", Category.TASK, Effect.LOCAL, "text*:메모", "작업 기록에 메모", "메모"),
        c("task.artifact", "결과물 저장", Category.TASK, Effect.LOCAL, "name*:파일명|content*:내용", "최종 결과물로 표시해 저장", "결과물"),
        c("task.done", "작업 완료", Category.TASK, Effect.READ, "summary*:최종 요약", "작업을 끝내고 결과를 보고", "완료"),

        // ── 허브 ─────────────────────────────────────────────────
        c("hub.sites", "즐겨찾는 사이트", Category.HUB, Effect.READ, "", "사용자가 지정한 사이트 목록", "사이트"),
        c("hub.open", "허브 사이트 열기", Category.HUB, Effect.LOCAL, "name*:사이트 이름", "즐겨찾기 사이트를 연다", "허브열기"),
        c("hub.add_site", "사이트 추가", Category.HUB, Effect.LOCAL, "name*:이름|url*:주소", "즐겨찾기에 추가", "사이트추가"),
        c("hub.apps", "자주 쓰는 앱", Category.HUB, Effect.READ, "", "사용자가 지정한 앱 목록", "자주앱"),
        c("hub.files", "자주 쓰는 파일", Category.HUB, Effect.READ, "", "사용자가 고정한 파일 목록", "고정파일"),
        c("hub.ai", "AI 상태", Category.HUB, Effect.READ, "", "연결된 AI와 경로·성공률", "ai상태"),
        c("hub.workspace", "작업공간", Category.HUB, Effect.READ, "", "작업별 폴더와 결과물 목록", "작업공간"),
        c("hub.commands", "사용자 명령어", Category.HUB, Effect.READ, "", "사용자가 만든 단축 명령 목록", "내명령"),

        // ── 파이프라인·스킬 ───────────────────────────────────────
        c("flow.list", "파이프라인 목록", Category.FLOW, Effect.READ, "", "저장된 파이프라인", "파이프라인"),
        c("flow.run", "파이프라인 실행", Category.FLOW, Effect.READ, "name*:이름|input:입력", "저장된 파이프라인 실행(내부 승인 규칙 유지)", "실행"),
        c("flow.propose", "파이프라인 제안", Category.FLOW, Effect.LOCAL, "name*:이름|steps*:명령 단계(줄바꿈, 'cmd key=value')", "새 파이프라인을 제안(사용자 승인 후 저장)", "파이프라인제안"),
        c("skill.list", "스킬 목록", Category.FLOW, Effect.READ, "", "사용 가능한 스킬", "스킬"),
        c("skill.use", "스킬 불러오기", Category.FLOW, Effect.READ, "name*:스킬 이름", "스킬의 지침을 읽어 따른다", "스킬사용"),
        c("skill.propose", "스킬 제안", Category.FLOW, Effect.LOCAL, "name*:이름|when*:언제 쓰는지|guide*:지침", "새 스킬을 제안(사용자 승인 후 저장)", "스킬제안"),

        // ── 화면·사용자 ─────────────────────────────────────────
        c("ui.read_screen", "화면 읽기", Category.UI, Effect.READ, "reason*:왜 필요한지", "사용자 화면의 글자를 읽는다(사용자 허락·접근성 필요)", "화면읽기"),
        c("ui.ask_screen", "화면 공유 요청", Category.UI, Effect.READ, "reason*:무엇을 보여달라는지", "사용자에게 화면을 보여달라고 요청(스크린샷 공유)", "화면공유"),
        c("ui.takeover", "직접 조작 요청", Category.UI, Effect.READ, "what*:사용자가 해야 할 일|url:열 주소", "로그인·캡차 등 사람만 할 수 있는 일을 부탁하고 기다린다", "직접조작"),
        c("ui.show", "카드 보여주기", Category.UI, Effect.READ, "title*:제목|body*:마크다운", "대화에 정리된 카드를 띄운다", "카드"),
        c("ui.toast", "짧은 알림", Category.UI, Effect.READ, "text*:내용", "화면 하단 짧은 메시지", "토스트"),

        // ── 생활·특화 ───────────────────────────────────────────
        c("contest.search", "공모전 찾기", Category.LIFE, Effect.READ, "q*:분야·키워드|n:개수", "공모전 포털(위비티·씽굿·올콘·링커리어)을 검색", "공모전"),
        c("job.search", "채용 찾기", Category.LIFE, Effect.READ, "q*:직무·키워드", "채용 공고 검색(사람인·잡코리아·원티드)", "채용"),
        c("shop.search", "쇼핑 검색", Category.LIFE, Effect.READ, "q*:상품", "쇼핑 결과·가격대 검색", "쇼핑"),
        c("place.search", "장소 찾기", Category.LIFE, Effect.READ, "q*:장소|near:지역", "근처 장소 검색", "장소"),
        c("translate.open", "번역기 열기", Category.LIFE, Effect.LOCAL, "text*:문장|to:ko/en/ja", "파파고/구글 번역 화면을 연다", "번역"),
        c("study.flashcards", "암기카드", Category.LIFE, Effect.LOCAL, "name*:파일명|pairs*:앞=뒤(줄바꿈)", "암기용 카드 파일(CSV) 생성", "암기"),
        c("life.pomodoro", "뽀모도로", Category.LIFE, Effect.LOCAL, "minutes:집중 분(기본25)|label:할 일", "집중 타이머 시작", "뽀모도로"),
        c("life.checklist", "체크리스트", Category.LIFE, Effect.LOCAL, "name*:이름|items*:항목(줄바꿈)", "체크리스트 파일 생성", "체크리스트"),
        c("life.dday_board", "마감 보드", Category.LIFE, Effect.READ, "", "등록된 마감들을 D-day 순으로", "마감보드")
    ) + MoreCommands.specs + ConnectorCommands.specs + PhoneCommands.specs + RoutineCommands.specs

    private val byId = ALL.associateBy { it.id }
    private val byAlias: Map<String, CommandSpec> = ALL.flatMap { s -> s.aliases.map { it.lowercase() to s } }.toMap()

    fun find(idOrAlias: String): CommandSpec? {
        val k = idOrAlias.trim().removePrefix("/")
        return byId[k] ?: byId[k.lowercase()] ?: byAlias[k.lowercase()]
    }

    fun byCategory(): Map<Category, List<CommandSpec>> = ALL.groupBy { it.category }

    fun search(q: String): List<CommandSpec> {
        val t = q.trim().lowercase().removePrefix("/")
        if (t.isEmpty()) return ALL
        return ALL.filter { s ->
            s.id.contains(t) || s.title.lowercase().contains(t) || s.desc.lowercase().contains(t) ||
                s.aliases.any { it.lowercase().contains(t) } || s.category.label.contains(t)
        }
    }

    /** DeepSeek에게 주는 명령어 사전(카테고리별, 한 줄씩). */
    fun promptCatalog(exclude: Set<String> = emptySet()): String = byCategory().entries.joinToString("\n") { (cat, list) ->
        "## ${cat.code} ${cat.label}\n" + list.filter { it.id !in exclude }.joinToString("\n") { it.line() }
    }
}
