package com.ht.ensemble.ai

/**
 * AI 성적표 — 회사별 장단점을 '현실적으로' 적어 둔 것(2026년 하반기 기준, 웹·API 공통).
 * 점수는 0~10. 지휘자(센터)는 이 표와 실제 성적(성공률·속도·한도)을 합쳐 일을 나눈다.
 * 과장하지 않는다: 각 회사가 실제로 약한 곳(한도·형식·한국어 뉘앙스·속도)도 같이 적는다.
 */
object AiProfiles {
    enum class Skill(val label: String) {
        PLAN("계획·분해"), RESEARCH("최신 조사"), WRITE("한국어 글"), CODE("코드"), REASON("추론·수학"),
        REVIEW("검토·비판"), CREATIVE("아이디어"), SPEED("속도"), LONG("긴 자료"), GOOGLE("구글 앱")
    }

    data class Profile(val provider: Provider, val skills: Map<Skill, Int>, val strong: String, val weak: String)

    private fun p(pr: Provider, plan: Int, research: Int, write: Int, code: Int, reason: Int, review: Int, creative: Int, speed: Int, long: Int, google: Int, strong: String, weak: String) =
        Profile(pr, mapOf(Skill.PLAN to plan, Skill.RESEARCH to research, Skill.WRITE to write, Skill.CODE to code, Skill.REASON to reason,
            Skill.REVIEW to review, Skill.CREATIVE to creative, Skill.SPEED to speed, Skill.LONG to long, Skill.GOOGLE to google), strong, weak)

    val ALL: Map<Provider, Profile> = listOf(
        p(Provider.CLAUDE, 9, 6, 9, 9, 8, 9, 8, 5, 9, 1,
            "긴 글·보고서·코드가 가장 꼼꼼하고 지시를 정확히 따른다. 검토·비판이 날카롭다.",
            "웹(무료·Pro)은 사용량 한도가 빨리 찬다. 최신 정보 검색은 다른 AI보다 약하다. 느린 편."),
        p(Provider.GPT, 8, 8, 8, 8, 9, 8, 8, 6, 7, 1,
            "추론·수학·구조화된 답(표·목록)이 강하고, 웹 검색을 스스로 해서 출처를 붙인다. 두루 무난하다.",
            "웹은 로그인·사람 확인이 잦다. 한국어 문장이 번역투일 때가 있다. 긴 자료에서 앞부분을 놓친다."),
        p(Provider.GEMINI, 7, 9, 7, 7, 8, 7, 7, 8, 10, 10,
            "구글 검색으로 최신 사실 조사가 가장 강하고, 아주 긴 자료를 한 번에 읽는다. Gmail·드라이브·캘린더를 직접 본다.",
            "글이 길고 장황해지기 쉽다. 형식(JSON 등)을 가끔 어긴다. 창작은 무난한 수준."),
        p(Provider.GROK, 6, 8, 6, 7, 7, 6, 8, 8, 6, 1,
            "실시간 뉴스·X(트위터) 반응 조사가 강하고 빠르다. 아이디어가 과감하다.",
            "한국어 글 완성도와 형식 준수가 들쭉날쭉하다. 사실 확인이 필요하다."),
        p(Provider.DEEPSEEK, 7, 5, 7, 8, 8, 7, 6, 6, 6, 1,
            "무료로 많이 써도 되고 추론·코드가 강하다. 두 창을 동시에 쓸 수 있다.",
            "최신 정보가 약하다(검색이 꺼져 있으면 옛 지식). 붐비는 시간에 느리거나 끊긴다. 한국어 뉘앙스가 평범."),
    ).associateBy { it.provider }

    /** 할 일 글에서 필요한 능력을 짐작(지휘자가 'who'를 안 정했을 때·대체할 때) */
    fun skillFor(text: String): Skill {
        val t = text.lowercase()
        return when {
            Regex("메일|gmail|드라이브|캘린더|일정|keep|구글 문서").containsMatchIn(t) -> Skill.GOOGLE
            Regex("검색|조사|최신|뉴스|찾|트렌드|가격|현황|출처|자료 수집").containsMatchIn(t) -> Skill.RESEARCH
            Regex("코드|프로그램|스크립트|함수|버그|앱|html|python|kotlin").containsMatchIn(t) -> Skill.CODE
            Regex("검토|비판|평가|점검|반박|교정|팩트").containsMatchIn(t) -> Skill.REVIEW
            Regex("계획|분해|구조|목차|설계|전략").containsMatchIn(t) -> Skill.PLAN
            Regex("아이디어|발상|브레인|창의|콘셉트|이름 짓").containsMatchIn(t) -> Skill.CREATIVE
            Regex("계산|수학|증명|통계|논리|분석").containsMatchIn(t) -> Skill.REASON
            Regex("요약|긴|문서|논문 읽|pdf").containsMatchIn(t) -> Skill.LONG
            else -> Skill.WRITE
        }
    }

    /** 한도·과부하 문구(짧은 답에서만 — 긴 글 속 '한도'라는 단어는 무시) */
    private val LIMIT = Regex("(usage limit|rate limit|limit reached|reached your|hit your|too many requests|quota|try again (in|later)|upgrade to (plus|pro)|out of messages|message limit|at capacity|429|사용량|한도|용량 초과|잠시 후 다시|요청이 너무 많|업그레이드)", RegexOption.IGNORE_CASE)
    fun limitHit(text: String, via: String = ""): Boolean = via.contains("429") || (text.length < 300 && LIMIT.containsMatchIn(text.take(600)))

    fun score(p: Provider, s: Skill): Int = ALL[p]?.skills?.get(s) ?: 5

    /** 지휘자에게 보여 줄 팀 소개(현실 장단점 포함) */
    fun roster(names: List<Pair<String, Provider>>): String = names.joinToString("\n") { (name, pr) ->
        val pf = ALL[pr] ?: return@joinToString "- $name"
        val top = pf.skills.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key.label} ${it.value}" }
        "- $name: 강점 $top · ${pf.strong} 약점: ${pf.weak}"
    }
}
