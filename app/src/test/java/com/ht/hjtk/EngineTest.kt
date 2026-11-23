package com.ht.hjtk

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ht.hjtk.core.stream.TerminationGate
import com.ht.hjtk.data.Role
import com.ht.hjtk.data.Store
import com.ht.hjtk.engine.DeepSeekWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicReference

/** 1.1 수정 회귀 테스트: '보내면 오래 기다리다 튕김'의 원인들 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EngineTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun cockpitWeb(ds: DeepSeekWeb): Pair<FrameLayout, WebView> {
        val box = FrameLayout(ctx)
        ds.attach(box); ShadowLooper.idleMainLooper()
        return box to (box.getChildAt(0) as WebView)
    }

    @Test fun rendererDeathIsHandledNotFatal() {
        val ds = DeepSeekWeb(ctx)
        val (box, w) = cockpitWeb(ds)
        val client = shadowOf(w).webViewClient
        // false를 돌려주면 안드로이드가 앱 프로세스를 죽인다
        assertTrue(client.onRenderProcessGone(w, null))
        assertEquals(0, box.childCount)
        assertEquals(DeepSeekWeb.Status.OFF, ds.status.value)
        assertEquals(1, ds.renderDeaths)
        // 다음 요청에서 새로 만든다
        ds.attach(box); ShadowLooper.idleMainLooper()
        assertTrue(box.getChildAt(0) is WebView && box.getChildAt(0) !== w)
    }

    @Test fun currentUrlIsSafeFromBackgroundThreads() {
        val ds = DeepSeekWeb(ctx)
        val (_, w) = cockpitWeb(ds)
        shadowOf(w).webViewClient.doUpdateVisitedHistory(w, "https://chat.deepseek.com/a/chat/s/abc", false)
        val got = AtomicReference<Any?>()
        val t = Thread { got.set(runCatching { ds.currentUrl() }.fold({ it }, { it })) }
        t.start(); t.join()
        assertEquals("https://chat.deepseek.com/a/chat/s/abc", got.get())
    }

    @Test fun thinkingPhaseIsNotCutAsEmpty() {
        val g = TerminationGate()
        g.start(0)
        // 생각 단계: 답 0자지만 조각은 1초마다 온다 → 60초가 지나도 계속
        var t = 0L
        while (t < 60_000) { t += 1_000; g.onChunk(t); assertEquals(TerminationGate.Verdict.RUNNING, g.poll(t, 0, false, false)) }
        // 아무 조각도 없으면 25초 뒤 EMPTY
        assertEquals(TerminationGate.Verdict.EMPTY, g.poll(t + 26_000, 0, false, false))
        // 답이 생긴 뒤 3.5초 침묵이면 종료
        val g2 = TerminationGate(); g2.start(0); g2.onChunk(1_000)
        assertEquals(TerminationGate.Verdict.RUNNING, g2.poll(3_000, 40, false, false))
        assertEquals(TerminationGate.Verdict.SILENCE_DONE, g2.poll(5_000, 40, false, false))
    }

    @Test fun lazyLoadNeverOverwritesUnreadHistory() {
        val a = Store(ctx)
        val convs = (1..6).map { i -> a.newConversation("대화 $i").also { c -> repeat(3) { k -> a.msg(c.id, Role.USER, "c$i-$k") } } }
        Thread.sleep(600)   // 250ms 디바운스 저장 대기
        val oldest = convs.first()
        val b = Store(ctx)  // 새 프로세스처럼 다시 읽기
        // 아직 안 읽힌 대화에 바로 써도 디스크의 3개가 보존되어야 한다
        b.msg(oldest.id, Role.SYSTEM, "추가", "note")
        assertEquals(listOf("c1-0", "c1-1", "c1-2", "추가"), b.messages.value[oldest.id]!!.map { it.text })
        Thread.sleep(600)
        val c = Store(ctx); c.ensureLoaded(oldest.id)
        assertEquals(4, c.messages.value[oldest.id]!!.size)
        assertNull(c.messages.value["없는 대화"])
    }

    @Test fun routineNextRespectsRepeat() {
        val mon8 = java.time.LocalDateTime.of(2026, 11, 23, 8, 0)   // 월요일 08:00
        val daily = com.ht.hjtk.engine.Routine("a", "t", "g", "daily", 1, 8, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 24, 8, 0), daily.next(mon8))           // 같은 시각이면 내일
        val wd = com.ht.hjtk.engine.Routine("b", "t", "g", "weekdays", 1, 9, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 30, 9, 0), wd.next(java.time.LocalDateTime.of(2026, 11, 28, 10, 0)))  // 토 → 월
        val wk = com.ht.hjtk.engine.Routine("c", "t", "g", "weekly", 5, 7, 30)                     // 금요일
        assertEquals(java.time.LocalDateTime.of(2026, 11, 27, 7, 30), wk.next(mon8))
        assertEquals("평일 09:00", wd.label())
        val back = com.ht.hjtk.engine.Routine.from(wk.toJson())
        assertEquals(wk, back)
    }
}
