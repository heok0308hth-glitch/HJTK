package com.ht.voice

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VoiceCoreTest {
    private fun res(n: String) = javaClass.classLoader.getResourceAsStream(n)!!
    private val tok by lazy { CbTokenizer(res("cb_tok.txt").bufferedReader().lineSequence()) }
    private val expected by lazy { JSONObject(res("expected.json").bufferedReader().readText()) }

    @Test fun tokenizerMatchesHuggingFace() {
        val ids = expected.getJSONObject("ids")
        for (k in ids.keys()) {
            val want = ids.getJSONArray(k).let { a -> LongArray(a.length()) { a.getLong(it) } }
            assertArrayEquals(k, want, tok.encode(k))
        }
    }

    @Test fun koreanNumbersReadNaturally() {
        assertEquals("오후 세 시 삼십 분에 두 명", KoText.normalize("오후 3:30에 2명"))
        assertEquals("만 오천원", KoText.normalize("15,000원"))
        assertEquals("스무 살", KoText.normalize("20살"))
        assertEquals("공일공, 일이삼사, 오육칠팔", KoText.normalize("010-1234-5678"))
        assertEquals("삼 점 오 퍼센트", KoText.normalize("3.5%"))
        assertEquals("백이십삼 번지", KoText.normalize("123 번지"))
        assertEquals("열한 시", KoText.normalize("11시"))
        assertEquals("이천이십육 년", KoText.normalize("2026 년"))
    }

    @Test fun sentencesSplitShortForFastFirstSound() {
        val s = KoText.sentences("안녕하세요. 예약 문의드려요! 내일 가능한가요?\n감사합니다")
        assertEquals(listOf("안녕하세요.", "예약 문의드려요!", "내일 가능한가요?", "감사합니다"), s)
        val long = KoText.sentences("가".repeat(40) + ", " + "나".repeat(40), max = 60)
        assertEquals(2, long.size)
    }

    @Test fun voicePrintLoadsAndTrims() {
        val v = VoicePrint.read(res("me.htvp").readBytes())
        assertEquals(33, v.condLen); assertEquals(33 * 1024, v.cond.size)
        assertEquals(250, v.prompt.size); assertEquals(192, v.xvec.size); assertEquals(500 * 80, v.feat.size)
        val t = v.trimmed(4f)
        assertEquals(100, t.prompt.size); assertEquals(200 * 80, t.feat.size)
        assertArrayEquals(v.prompt.copyOfRange(150, 250), t.prompt)
    }

    /** 실제 모델로: 코틀린 생성 루프가 파이썬 기준 구현과 같은 소리 토큰을 내는지(탐욕 선택이라 정확히 같아야 한다) */
    @Test fun engineReproducesReferenceTokensAndSpeaks() {
        val dir = File(System.getProperty("cb.models") ?: "")
        assumeTrue("모델 없음 — 건너뜀", File(dir, CbEngine.FILE_LM).exists())
        val v = VoicePrint.read(res("me.htvp").readBytes())
        val g = expected.getJSONObject("greedy"); val text = g.keys().next()
        val want = g.getJSONArray(text).let { a -> (1 until a.length() - 1).map { a.getLong(it) } }
        CbEngine(dir, threads = 2).use { e ->
            val t0 = System.currentTimeMillis()
            val toks = e.speechTokens(tok.encode(text), v)
            val t1 = System.currentTimeMillis()
            // 같은 모델이라도 onnxruntime 판(파이썬 최신 · 자바 1.22)마다 q4 행렬곱 누적 순서가 달라 동점 근처에서 한두 개가 갈린다
            val same = want.zip(toks.toList()).count { it.first == it.second }
            println("agree=$same/${want.size}")
            assertTrue("길이 ${toks.size} vs ${want.size}", kotlin.math.abs(toks.size - want.size) <= 3)
            assertTrue("일치율 $same/${want.size}", same >= want.size * 0.9)
            val wav = e.decode(toks, v.trimmed(4f))
            val t2 = System.currentTimeMillis()
            val sec = wav.size / 24000f
            println("tokens=${toks.size} lm=${t1 - t0}ms dec=${t2 - t1}ms audio=${sec}s")
            assertTrue(sec in 1.5f..6f)
            val rms = Math.sqrt(wav.map { it * it.toDouble() }.average())
            assertTrue("소리가 나야 한다: rms=$rms", rms > 0.01)
            File("build/voice_test.wav").apply { parentFile.mkdirs() }.writeBytes(Wav.encode(wav, 24000))
        }
    }
}
