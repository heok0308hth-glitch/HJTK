package com.hjtk.app

import com.hjtk.app.core.command.CommandCatalog
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.EffectClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract tests for the command catalog. These are the guard rails that keep
 * "add a command" cheap: the registry itself refuses duplicates, and these
 * tests refuse vague metadata or broken slot declarations.
 */
class CommandCatalogTest {

    private val registry = CommandCatalog.build()

    @Test
    fun hasAtLeastFortyCommands() {
        assertTrue(registry.size() >= 40, "명령이 ${registry.size()}개뿐입니다")
    }

    @Test
    fun idsAreUniqueAndNamespaced() {
        val ids = registry.all().map { it.id }
        assertTrue(ids.size == ids.toSet().size, "중복 id가 있습니다")
        ids.forEach { id ->
            assertTrue(Regex("^[a-z]+\\.[a-z_]+$").matches(id), "id 형식이 어긋남: $id")
        }
    }

    @Test
    fun everyCommandIsDescribed() {
        registry.all().forEach { spec ->
            assertTrue(spec.labelKo.isNotBlank(), "${spec.id}: 이름 없음")
            assertTrue(spec.description.isNotBlank(), "${spec.id}: 설명 없음")
            assertTrue(spec.description.length >= 6, "${spec.id}: 설명이 너무 짧음")
        }
    }

    @Test
    fun everyCategoryIsPopulated() {
        val map = registry.byCategory()
        CommandCategory.entries.forEach { c ->
            assertTrue(map[c]?.isNotEmpty() == true, "${c.labelKo} 카테고리에 명령이 없습니다")
        }
    }

    @Test
    fun requiredParamsCarryExamples() {
        registry.all().forEach { spec ->
            spec.params.filter { it.required }.forEach { p ->
                assertTrue(p.example.isNotBlank(), "${spec.id}.${p.name}: 예시 값이 없습니다")
                assertTrue(p.labelKo.isNotBlank(), "${spec.id}.${p.name}: 라벨이 없습니다")
            }
        }
    }

    @Test
    fun outwardCommandsAreMarkedAsSuch() {
        val outward = setOf("mail.send", "app.share", "app.dial", "app.sms", "calendar.new_event", "file.share_export")
        outward.forEach { id ->
            val spec = registry.spec(id)
            assertTrue(spec != null, "$id 없음")
            assertTrue(spec!!.effect.needsApproval, "$id 은(는) 승인 대상이어야 합니다")
        }
        val internal = setOf("memory.remember", "clip.copy", "device.battery", "system.diagnostics")
        internal.forEach { id ->
            val spec = registry.spec(id)!!
            assertTrue(!spec.effect.needsApproval, "$id 은(는) 승인 없이 실행되어야 합니다")
        }
    }

    @Test
    fun destructiveCommandsExistAndAreMarked() {
        val destructive = registry.all().filter { it.effect == EffectClass.DESTRUCTIVE }
        assertTrue(destructive.isNotEmpty())
        assertTrue(destructive.any { it.id == "memory.forget" })
    }

    @Test
    fun searchFindsCommandsByKoreanLabel() {
        assertTrue(registry.search("메일").any { it.id.startsWith("mail.") })
        assertTrue(registry.search("배터리").any { it.id == "device.battery" })
        assertTrue(registry.search("요약").any { it.id == "ai.summarize" })
    }

    @Test
    fun everyCommandHasKeywordsOrPatterns() {
        registry.all().forEach { spec ->
            assertTrue(
                spec.keywords.isNotEmpty() || spec.patterns.isNotEmpty(),
                "${spec.id}: 라우터가 찾을 단서가 없습니다"
            )
        }
    }
}
