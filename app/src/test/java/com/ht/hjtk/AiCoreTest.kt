package com.ht.hjtk

import com.ht.hjtk.core.ai.*
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.PhoneGuard
import org.junit.Assert.*
import org.junit.Test

class AiCoreTest {
    private val web = Member(Provider.DEEPSEEK, Transport.WEB)
    private val claude = Member(Provider.CLAUDE, Transport.API, "a1")
    private val gpt = Member(Provider.GPT, Transport.API, "a2")
    private val gemini = Member(Provider.GEMINI, Transport.API, "a3")
    private val grokApp = Member(Provider.GROK, Transport.APP)

    @Test fun keylessUsesWebDeepSeekForEverything() {
        val t = ModelRouter.team(Difficulty.STANDARD, listOf(web, grokApp))
        assertEquals(Transport.WEB, t[Role.WRITE]!!.member.transport)   // 앱 연결(사람 손)보다 웹을 고른다
        assertEquals(Transport.WEB, t[Role.PLAN]!!.member.transport)
    }

    @Test fun reviewerIsDifferentCompanyThanWriter() {
        val t = ModelRouter.team(Difficulty.DEEP, listOf(web, claude, gpt, gemini))
        assertNotEquals(t[Role.WRITE]!!.member.provider, t[Role.REVIEW]!!.member.provider)
        assertEquals(Tier.STRONG, t[Role.WRITE]!!.model!!.tier)                // 깊게 → 강한 모델
    }

    @Test fun quickUsesFastModelsAndNoReviewer() {
        val t = ModelRouter.team(Difficulty.QUICK, listOf(claude, gemini))
        assertNull(t[Role.REVIEW])
        assertEquals(Tier.FAST, t[Role.WRITE]!!.model!!.tier)
    }

    @Test fun projectPlansWithStrongestAvailable() {
        val t = ModelRouter.team(Difficulty.PROJECT, listOf(claude, gpt, gemini))
        assertTrue(t[Role.PLAN]!!.model!!.tier.rank >= Tier.STRONG.rank)
        assertEquals(4, t.size)
    }

    @Test fun pinsAreRespected() {
        val a = ModelRouter.pick(Role.WRITE, Difficulty.STANDARD, listOf(claude, gpt, web), mapOf(Role.WRITE to web.key))
        assertEquals(web, a!!.member)
    }

    @Test fun pinnedModelOverridesTier() {
        val m = claude.copy(pinnedModel = "claude-haiku-5-5")
        assertEquals("claude-haiku-5-5", ModelRouter.modelFor(m, Tier.STRONG)!!.id)
        assertEquals("my-custom", ModelRouter.modelFor(claude.copy(pinnedModel = "my-custom"), Tier.FAST)!!.id)   // 새 모델 ID 직접 입력
    }

    @Test fun packerShrinksButKeepsRelevantSentences() {
        val noise = (1..80).joinToString("\n") { "메뉴" } + "\n"
        val filler = (1..60).joinToString(" ") { "관련 없는 문장 $it 입니다." }
        val key = "서울 공공디자인 공모전 마감은 12월 12일이다 https://ex.com/a."
        val out = ContextPacker.pack(noise + filler + "\n" + key + "\n" + filler, "서울 공모전 마감", 400)
        assertTrue(out.length < 600)
        assertTrue(out.contains("12월 12일"))
        assertFalse(out.lines().count { it == "메뉴" } > 1)
    }

    @Test fun tokenEstimate() {
        assertTrue(Tokens.estimate("안녕하세요 반갑습니다") in 5..10)
        assertTrue(Tokens.estimate("hello world, this is a test") in 5..9)
        assertEquals("1.5k", Tokens.fmt(1500))
    }

    @Test fun catalogIsConsistent() {
        val ids = CommandCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("github.repos", "notion.read", "site.read", "phone.ai_call", "phone.prepare")))
        assertTrue(CommandCatalog.find("phone.ai_call")!!.effect.needsApproval)
        assertTrue(CommandCatalog.find("github.commit_file")!!.effect.needsApproval)
    }

    @Test fun phoneGuard() {
        val now = 10_000_000L
        assertNotNull(PhoneGuard.check("112", emptyList(), now))
        assertNotNull(PhoneGuard.check("119", emptyList(), now))
        assertNotNull(PhoneGuard.check("1366", emptyList(), now))
        assertEquals("+821012345678", PhoneGuard.normalize("010-1234-5678"))
        assertEquals("+82212345678", PhoneGuard.normalize("02-1234-5678"))
        assertEquals("+14155550123", PhoneGuard.normalize("+1 415 555 0123"))
        assertNull(PhoneGuard.check("02-1234-5678", emptyList(), now))
        val five = (1..5).map { "+8210000000$it" to now - it * 1000L }
        assertNotNull(PhoneGuard.check("02-1234-5678", five, now))                         // 하루 5통
        assertNotNull(PhoneGuard.check("02-1234-5678", listOf("+82212345678" to now - 60_000), now))   // 10분 쿨다운
        assertTrue(PhoneGuard.disclosure("허태혁").contains("AI 비서"))
        assertTrue(PhoneGuard.task("예약 문의", "", null).contains("you are an AI"))
    }
}
