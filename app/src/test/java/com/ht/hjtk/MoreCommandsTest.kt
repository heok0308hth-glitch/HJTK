package com.ht.hjtk

import com.ht.ensemble.command.CommandCall
import com.ht.hjtk.core.command.CommandCatalog
import com.ht.hjtk.core.command.PureHandlers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreCommandsTest {
    private val h = PureHandlers.all()
    private fun run(cmd: String, vararg a: Pair<String, String>) = runBlocking { h.getValue(cmd).run(CommandCall("t", cmd, a.toMap())) }

    @Test fun catalogHasNoDuplicatesAndEveryNewCommandHasAHandler() {
        val ids = CommandCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("명령 수 ${ids.size}", ids.size >= 185)
        com.ht.hjtk.core.command.MoreCommands.specs.forEach { assertTrue(it.id, h.containsKey(it.id)) }
    }

    @Test fun korean() {
        assertEquals("사과를", run("text.josa", "word" to "사과").text)
        assertEquals("수박을", run("text.josa", "word" to "수박").text)
        assertEquals("서울로", run("text.josa", "word" to "서울", "pair" to "으로/로").text)
        assertEquals("부산으로", run("text.josa", "word" to "부산", "pair" to "으로/로").text)
        assertEquals("ㅎㅈㅌㅋ", run("text.choseong", "text" to "허준태킹").text)
        assertTrue(run("text.romanize", "text" to "한글").text.startsWith("hangeul"))
    }

    @Test fun calc() {
        assertTrue(run("calc.discount", "price" to "10000", "rates" to "10,10").text.startsWith("최종 8,100원"))
        assertTrue(run("calc.pace", "km" to "10", "time" to "50:00").text.startsWith("페이스 5:00/km"))
        assertTrue(run("calc.gpa", "items" to "a=3/A+\nb=3/B0").text.startsWith("평점 3.75"))
        assertTrue(run("calc.base", "n" to "255").text.contains("16진 FF"))
        assertTrue(run("calc.color", "color" to "#E2442F").text.contains("RGB 226, 68, 47"))
        assertTrue(run("calc.workdays", "from" to "2026-11-23", "to" to "2026-11-29").text.startsWith("평일 5일"))
        assertTrue(run("calc.timezone", "time" to "2026-11-23 09:00", "from" to "서울", "to" to "뉴욕").text.contains("11/22"))
    }

    @Test fun data() {
        assertTrue(run("csv.sum", "csv" to "이름,금액\n가,1000\n나,2500").text.startsWith("금액: 합계 3,500"))
        assertTrue(run("csv.table", "csv" to "a,b\n1,2").text.contains("| 1 | 2 |"))
        assertEquals("""{"a":1}""", run("json.minify", "json" to "{ \"a\" : 1 }").text)
        assertEquals(16, run("code.password").text.lines().first().length)
        assertTrue(run("text.mask", "text" to "연락처 010-1234-5678").text.contains("010-****-****"))
    }
}
