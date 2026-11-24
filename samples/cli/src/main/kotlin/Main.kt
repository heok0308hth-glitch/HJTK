import com.ht.ensemble.Ensemble
import com.ht.ensemble.agent.AgentEvent
import com.ht.ensemble.ai.Provider
import com.ht.ensemble.ports.Ports
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 키가 있는 AI만 팀에 넣는다(ANTHROPIC_API_KEY · OPENAI_API_KEY · GEMINI_API_KEY · DEEPSEEK_API_KEY · XAI_API_KEY · OLLAMA_MODEL).
 * --demo 는 키 없이 가짜 AI로 흐름만 보여 준다.
 */
fun main(args: Array<String>) = runBlocking {
    val demo = "--demo" in args
    val minutes = args.indexOf("--project").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() ?: 30 }
    val goal = args.filterIndexed { i, a -> !a.startsWith("--") && !(i > 0 && args[i - 1] == "--project") }.joinToString(" ").ifBlank { "Kotlin 코루틴을 처음 배우는 사람을 위한 한 장짜리 안내서" }
    val env = System.getenv()
    val team = Ensemble.team {
        if (demo) listOf(Provider.CLAUDE, Provider.GPT, Provider.GEMINI).forEach { member(it, demoPort(it.label)) }
        env["ANTHROPIC_API_KEY"]?.let { member(Provider.CLAUDE, Ports.anthropic(it)) }
        env["OPENAI_API_KEY"]?.let { member(Provider.GPT, Ports.openAi(it)) }
        env["GEMINI_API_KEY"]?.let { member(Provider.GEMINI, Ports.gemini(it)) }
        env["DEEPSEEK_API_KEY"]?.let { member(Provider.DEEPSEEK, Ports.deepSeek(it), lanes = 2) }
        env["XAI_API_KEY"]?.let { member(Provider.GROK, Ports.grok(it)) }
        env["OLLAMA_MODEL"]?.let { member(Provider.DEEPSEEK, Ports.ollama(it), name = "Ollama") }
        onEvent { e -> when (e) { is AgentEvent.Collab -> println("· [${e.role}] ${e.who}: ${e.text}"); is AgentEvent.Note -> println("  ${e.text}"); else -> Unit } }
    }
    println("목표: $goal" + (minutes?.let { " · 프로젝트 ${it}분" } ?: ""))
    val r = if (minutes != null) team.project(goal, minutes, dir = File("ensemble-out").also { it.mkdirs() }) else team.conduct(goal)
    println("\n" + r.text)
    if (minutes != null) println("\n파일: ${File("ensemble-out").absolutePath}")
}

private fun demoPort(name: String) = Ports.fn(name) { p ->
    kotlinx.coroutines.delay(300)
    when {
        p.contains("센터(지휘자)") -> """{"deliverable":"안내서","criteria":["처음 보는 사람도 이해"],"tasks":[{"id":"t1","title":"핵심 개념","prompt":"코루틴 핵심 개념"},{"id":"t2","title":"흔한 실수","prompt":"흔한 실수"},{"id":"t3","title":"한 장 정리","prompt":"정리","after":["t1","t2"]}],"final":{"how":"한 장으로"}}"""
        p.contains("[검수]") -> """{"pass":true}"""
        p.contains("[센터 보강 판단]") -> """{"more":[]}"""
        p.contains("[교차 확인]") -> "이상 없음"
        else -> "# $name 의 결과\n\n(데모) 실제 키를 넣으면 이 자리에 AI의 답이 들어갑니다.\n\n[칠판]\n사실: $name 가 맡은 부분을 끝냈어요"
    }
}
