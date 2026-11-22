package com.hjtk.app

import com.hjtk.app.core.protocol.AepParse
import com.hjtk.app.core.protocol.AepParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AepParserTest {

    private val parser = AepParser()

    @Test
    fun strictFenceIsRungOne() {
        val text = """
            Here is the plan.

            ```aep
            @aep:1 plan
            {"schema":"plan.v1","title":"아침","steps":[{"commandId":"calendar.today"},{"commandId":"mail.unread"}]}
            ```
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(1, result.rung)
        val ok = result.parse as AepParse.Ok
        assertEquals("plan", ok.envelope.kind)
        assertEquals(2, ok.envelope.steps().size)
        assertEquals("calendar.today", ok.envelope.steps()[0].commandId)
    }

    @Test
    fun fencedJsonWithSchemaIsRungTwo() {
        val text = """
            Sure:
            ```json
            {"schema":"plan.v1","steps":[{"commandId":"device.battery"}]}
            ```
        """.trimIndent()
        val result = parser.parse(text)
        assertEquals(2, result.rung)
        assertEquals(1, (result.parse as AepParse.Ok).envelope.steps().size)
    }

    @Test
    fun bareBalancedObjectIsRungFour() {
        val text = """답: {"schema":"answer.v1","text":"오늘은 3시에 회의가 있습니다."} 끝"""
        val result = parser.parse(text)
        assertEquals(4, result.rung)
        val ok = result.parse as AepParse.Ok
        assertEquals("answer", ok.envelope.kind)
        assertTrue(ok.envelope.text().contains("회의"))
    }

    @Test
    fun headerWithoutFenceIsRungThree() {
        val text = "@aep:1 plan\n{\"schema\":\"plan.v1\",\"steps\":[{\"commandId\":\"device.torch\",\"args\":{\"on\":\"true\"}}]}"
        val result = parser.parse(text)
        assertEquals(3, result.rung)
        val ok = result.parse as AepParse.Ok
        assertEquals("true", ok.envelope.steps().first().args["on"])
    }

    @Test
    fun planWithoutStepsNeedsRepair() {
        val result = parser.parse("@aep:1 plan\n{\"schema\":\"plan.v1\",\"steps\":[]}")
        assertEquals(5, result.rung)
        assertTrue(result.parse is AepParse.Repair)
    }

    @Test
    fun brokenJsonNeedsRepairNotCrash() {
        val result = parser.parse("@aep:1 plan\n{\"schema\":\"plan.v1\", steps: [ ")
        assertEquals(5, result.rung)
        assertTrue(result.parse is AepParse.Repair)
    }

    @Test
    fun plainProseIsRungSix() {
        val result = parser.parse("오늘 날씨는 맑고 기온은 12도입니다.")
        assertEquals(6, result.rung)
        assertTrue(result.parse is AepParse.Plain)
    }

    @Test
    fun emptyResponseIsHandled() {
        val result = parser.parse("   ")
        assertEquals(0, result.rung)
        assertTrue(result.parse is AepParse.Repair)
    }

    @Test
    fun encodedPlanRoundTrips() {
        val encoded = parser.encodePlan(
            "테스트 계획",
            listOf(
                com.hjtk.app.core.protocol.AepStep("search.web", mapOf("query" to "코틀린")),
                com.hjtk.app.core.protocol.AepStep("mail.unread")
            )
        )
        val result = parser.parse(encoded)
        val ok = result.parse as AepParse.Ok
        assertEquals("테스트 계획", ok.envelope.title())
        assertEquals(2, ok.envelope.steps().size)
        assertEquals("코틀린", ok.envelope.steps()[0].args["query"])
    }

    @Test
    fun quotedBraceInsideStringDoesNotBreakBalancing() {
        val text = """{"schema":"answer.v1","text":"중괄호 } 와 { 가 들어간 답"}"""
        val result = parser.parse(text)
        assertEquals(4, result.rung)
        assertTrue((result.parse as AepParse.Ok).envelope.text().contains("중괄호"))
    }
}
