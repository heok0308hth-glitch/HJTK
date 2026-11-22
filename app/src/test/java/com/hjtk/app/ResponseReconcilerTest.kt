package com.hjtk.app

import com.hjtk.app.core.protocol.ResponseReconciler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseReconcilerTest {

    private val rre = ResponseReconciler()

    @Test
    fun incrementalDeltasConcatenate() {
        val out = rre.reconcile(null, listOf("안녕", "하세요", ", 반가워요"))
        assertEquals("안녕하세요, 반가워요", out.text)
        assertEquals("deltas", out.source)
        assertTrue(out.confidence > 0.7)
    }

    @Test
    fun cumulativeDeltasAreNotDuplicated() {
        val out = rre.reconcile(null, listOf("안녕", "안녕하세요", "안녕하세요 반갑습니다"))
        assertEquals("안녕하세요 반갑습니다", out.text)
    }

    @Test
    fun overlappingTailIsMerged() {
        val out = rre.reconcile(null, listOf("이 문장은 테스트입니다", "테스트입니다 그리고 끝"))
        assertEquals("이 문장은 테스트입니다 그리고 끝", out.text)
    }

    @Test
    fun snapshotOnlyHasLowerConfidence() {
        val out = rre.reconcile("완성된 답변", emptyList())
        assertEquals("완성된 답변", out.text)
        assertEquals("snapshot", out.source)
        assertTrue(out.confidence < 0.8)
        assertFalse(out.conflicted)
    }

    @Test
    fun snapshotAndMatchingDeltasAgree() {
        val out = rre.reconcile("안녕하세요", listOf("안녕", "하세요"))
        assertEquals("안녕하세요", out.text)
        assertTrue(out.confidence > 0.95)
        assertEquals("snapshot+deltas", out.source)
    }

    @Test
    fun snapshotThatContainsDeltasIsAccepted() {
        val out = rre.reconcile("이전 대화\n안녕하세요", listOf("안녕", "하세요"))
        assertFalse(out.conflicted)
        assertTrue(out.confidence > 0.85)
    }

    @Test
    fun conflictIsReportedNotHidden() {
        val out = rre.reconcile("전혀 다른 답", listOf("첫번째 답"))
        assertTrue(out.conflicted)
        assertTrue(out.confidence < 0.6)
        assertTrue(out.note.contains("불일치"))
    }

    @Test
    fun nothingReceivedIsExplicit() {
        val out = rre.reconcile(null, emptyList())
        assertEquals("", out.text)
        assertEquals(0.0, out.confidence)
        assertEquals("none", out.source)
    }

    @Test
    fun whitespaceDifferencesDoNotConflict() {
        val out = rre.reconcile("안녕 하세요", listOf("안녕", "  하세요"))
        assertFalse(out.conflicted)
    }
}
