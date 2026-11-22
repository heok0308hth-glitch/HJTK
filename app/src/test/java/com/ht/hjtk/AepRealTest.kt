package com.ht.hjtk

import com.ht.hjtk.core.agent.AgentProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.ht.hjtk.core.stream.AepView
import com.ht.hjtk.core.stream.HistoryExtract
import org.junit.Test

/** 실기기에서 받은 원문 그대로 */
class AepRealTest {
    private val raw = """[[AEP1 j=GQST6YZ7ZBE s=1/? k=act v=1]]공모전 스킬 지침대로 진행할게요. 먼저 위비티·씽굿·링커리어 등 여러 포털을 동시에 검색합니다.<<<AEP1-JSON{"say":"공모전 포털 3곳 이상에서 진행 중인 공모전을 검색합니다","plan":[{"step":"포털별 공모전 검색","eta_s":40,"status":"doing"},{"step":"마감·자격·상금·제출물·주최 표 정리","eta_s":60,"status":"todo"}],"next":"contest.search로 포털 4곳 검색","progress":0.05,"cmds":[{"id":"c1","cmd":"contest.search","args":{"q":"공모전","n":"10"}},{"id":"c2","cmd":"web.site","args":{"site":"wevity.com","q":"공모전"}}],"done":false,"final":null}AEP1-JSON>>>"""

    @Test fun parsesRealEnvelope() {
        val t = AgentProtocol.parseTurn(raw, "GQST6YZ7ZBE")
        assertEquals("공모전 포털 3곳 이상에서 진행 중인 공모전을 검색합니다", t.say)
        assertEquals(2, t.cmds.size)
    }

    @Test fun filterNeverShowsEnvelope() {
        val shown = AepView.clean(raw)
        assertFalse(shown.contains("AEP1")); assertFalse(shown.contains("{\"say\""))
        assertEquals("공모전 스킬 지침대로 진행할게요. 먼저 위비티·씽굿·링커리어 등 여러 포털을 동시에 검색합니다.\n\n공모전 포털 3곳 이상에서 진행 중인 공모전을 검색합니다", shown)
        // 잘린 봉투(스트림 도중)도 마커가 남지 않는다
        val cut = raw.take(raw.indexOf("\"plan\""))
        assertFalse(AepView.clean(cut).contains("AEP1"))
        // 평범한 답은 그대로
        assertEquals("안녕하세요! 무엇을 도와드릴까요?", AepView.clean("안녕하세요! 무엇을 도와드릴까요?"))
        // final이 있으면 final
        assertEquals("완료 보고", AepView.clean("[[AEP1 j=X s=9 k=fin v=1]]<<<AEP1-JSON{\"say\":\"끝\",\"final\":\"완료 보고\",\"done\":true}AEP1-JSON>>>"))
    }

    @Test fun historyPicksAnswerForOurQuestionOnly() {
        val json = """{"code":0,"data":{"biz_data":{"chat_messages":[
            {"message_id":1,"role":"USER","fragments":[{"type":"REQUEST","content":"이전 질문"}]},
            {"message_id":2,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"이전 답"}]},
            {"message_id":3,"role":"USER","fragments":[{"type":"REQUEST","content":"안녕"}]},
            {"message_id":4,"role":"ASSISTANT","fragments":[{"type":"THINK","content":"인사네"},{"type":"RESPONSE","content":"안녕하세요!"}]}]}}}"""
        assertEquals("안녕하세요!", HistoryExtract.lastAnswerFor(json, "안녕"))
        // 마지막 답이 우리 질문의 답이 아니면(아직 저장 전) 받지 않는다
        val stale = json.substringBefore(",\n            {\"message_id\":3") + "]}}}"
        assertNull(HistoryExtract.lastAnswerFor(stale, "안녕"))
        // 구형 content 필드
        assertEquals("hi", HistoryExtract.lastAnswerFor("""{"m":[{"role":"user","content":"hello"},{"role":"assistant","content":"hi"}]}""", "hello"))
    }
}
