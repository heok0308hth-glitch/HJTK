package com.hjtk.app

import com.hjtk.app.core.nlu.NluRouter
import com.hjtk.app.core.nlu.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The router is the part users experience as "understanding". Each case here is
 * a sentence a Korean speaker would actually type, and the expected command.
 */
class NluRouterTest {

    private val world = TestWorld()
    private val router: NluRouter get() = world.router

    private fun commandOf(text: String): String? = when (val r = router.route(text)) {
        is Route.Command -> r.request.commandId
        is Route.PlanRoute -> r.steps.first().commandId
        is Route.SkillRoute -> "skill:" + r.skill.name
        is Route.PipelineRoute -> "pipeline:" + r.pipeline.name
        else -> null
    }

    @Test
    fun routesCommonRequests() {
        val cases = mapOf(
            "안드로이드 15 변경점 검색해줘" to "search.web",
            "AI 규제 뉴스 보여줘" to "search.news",
            "람지 수 뜻이 뭐야" to "search.wiki",
            "오늘 일정 알려줘" to "calendar.today",
            "안 읽은 메일 확인해줘" to "mail.unread",
            "최근 메일 보여줘" to "mail.latest",
            "토스 앱 실행해줘" to "app.open",
            "강남역까지 길찾기" to "app.map",
            "배터리 상태 알려줘" to "device.battery",
            "저장 공간 확인해줘" to "device.storage",
            "네트워크 상태 보여줘" to "device.network",
            "5분 뒤 알려줘" to "notify.timer",
            "이 문장 다듬어줘" to "ai.rewrite",
            "이 내용 요약해줘" to "ai.summarize",
            "안녕하세요 번역해줘" to "ai.translate",
            "진단 정보 보여줘" to "system.diagnostics",
            "클립보드 읽어줘" to "clip.read",
            "허브 보여줘" to "hub.list",
            "파이프라인 목록 보여줘" to "pipeline.list",
            "스킬 목록 보여줘" to "skill.list",
            "실행 상태 알려줘" to "pipeline.status",
            "손전등 켜" to "device.torch",
            "볼륨 40" to "device.volume"
        )
        cases.forEach { (text, expected) ->
            assertEquals(expected, commandOf(text), "‘$text’ → ${commandOf(text)} (기대: $expected)")
        }
    }

    @Test
    fun extractsSearchQuery() {
        val route = router.route("안드로이드 15 변경점 검색해줘") as Route.Command
        assertEquals("안드로이드 15 변경점", route.request.args["query"])
    }

    @Test
    fun extractsAppNameWithoutTriggerWords() {
        val route = router.route("토스 앱 실행해줘") as Route.Command
        assertEquals("토스", route.request.args["name"])
    }

    @Test
    fun explicitSlashCommandWins() {
        val route = router.route("/mail.send to=a@b.c subject=안내 body=본문") as Route.Command
        assertEquals("mail.send", route.request.commandId)
        assertEquals("a@b.c", route.request.args["to"])
        assertEquals("안내", route.request.args["subject"])
        assertEquals(1.0, route.confidence)
    }

    @Test
    fun explicitCommandWithoutArgsUsesRestAsPrimarySlot() {
        val route = router.route("/search.web 코틀린 코루틴") as Route.Command
        assertEquals("코틀린 코루틴", route.request.args["query"])
    }

    @Test
    fun conjunctionBecomesTwoStepPlan() {
        val route = router.route("안드로이드 15 검색해서 요약해줘")
        assertTrue(route is Route.PlanRoute, "실제: $route")
        val plan = route as Route.PlanRoute
        assertEquals("search.web", plan.steps[0].commandId)
        assertEquals("ai.summarize", plan.steps[1].commandId)
        assertEquals("{{out:1}}", plan.steps[1].args["text"])
    }

    @Test
    fun namedPipelineWins() {
        val route = router.route("아침 루틴 실행해줘")
        assertTrue(route is Route.PipelineRoute)
        assertEquals("아침 루틴", (route as Route.PipelineRoute).pipeline.name)
    }

    @Test
    fun unknownRequestOffersSuggestionsInsteadOfGuessing() {
        val route = router.route("우주선 조립 매뉴얼") as Route.Unknown
        assertTrue(route.message.contains("확실하지 않습니다") || route.message.contains("바꾸지 못했습니다"))
    }

    @Test
    fun greetingIsAnsweredWithoutRunningACommand() {
        val route = router.route("안녕")
        assertTrue(route is Route.Chat)
    }

    @Test
    fun blankInputIsHandled() {
        assertTrue(router.route("   ") is Route.Unknown)
    }

    @Test
    fun routingIsDeterministic() {
        val text = "최근 메일 5통 보여줘"
        val first = commandOf(text)
        repeat(20) { assertEquals(first, commandOf(text)) }
    }
}
