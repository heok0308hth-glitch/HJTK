package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandCategory
import com.hjtk.app.core.model.CommandResult
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.ResultAction
import com.hjtk.app.core.util.TimeFmt

/** Exports and file handoff. Everything lands in the app's own folder. */
object FileCommands {

    fun register(reg: CommandRegistry) {

        reg.register(
            CommandSpec(
                id = "file.export_text",
                labelKo = "텍스트 내보내기",
                category = CommandCategory.FILES,
                effect = EffectClass.LOCAL_WRITE,
                description = "텍스트를 UTF-8 파일로 저장합니다.",
                params = listOf(
                    CommandRegistry.param("content", "내용", true, "메모 내용"),
                    CommandRegistry.param("name", "파일 이름", false, "note.txt")
                ),
                keywords = listOf("저장", "내보내기", "파일로", "export", "save"),
                patterns = listOf("^(?<content>.+?)\\s*(?:파일로 저장|텍스트로 저장|내보내기)$")
            )
        ) { req, ports ->
            missing(reg, req, "file.export_text")?.let { return@register it }
            val content = req.arg("content")!!
            val name = req.arg("name") ?: "note-${TimeFmt.iso(ports.now()).replace(':', '-')}.txt"
            runCatching { ports.files.saveExport(name, content) }
                .map { saved ->
                    CommandResult.Ok(
                        output = "저장했습니다: $saved (${content.length}자)",
                        data = mapOf("file" to saved),
                        actions = listOf(
                            ResultAction("공유", "file.share_export", mapOf("name" to saved)),
                            ResultAction("폴더 열기", "file.open_folder")
                        )
                    )
                }
                .getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "file.export_session",
                labelKo = "대화 내보내기",
                category = CommandCategory.FILES,
                effect = EffectClass.LOCAL_WRITE,
                description = "현재 대화를 Markdown 파일로 저장합니다.",
                keywords = listOf("대화 내보내기", "채팅 저장", "export chat")
            )
        ) { _, ports ->
            runCatching {
                val md = ports.sessions.exportMarkdown()
                val name = "chat-" + TimeFmt.iso(ports.now()).replace(':', '-') + ".md"
                ports.files.saveExport(name, md, "text/markdown")
            }.map { saved ->
                CommandResult.Ok(
                    output = "대화를 저장했습니다: $saved",
                    data = mapOf("file" to saved),
                    actions = listOf(ResultAction("공유", "file.share_export", mapOf("name" to saved)))
                )
            }.getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "file.list_exports",
                labelKo = "내보낸 파일 목록",
                category = CommandCategory.FILES,
                effect = EffectClass.READ,
                description = "앱이 저장한 파일을 최신순으로 보여줍니다.",
                keywords = listOf("파일 목록", "내보낸", "exports")
            )
        ) { _, ports ->
            runCatching { ports.files.listExports() }.map { files ->
                if (files.isEmpty()) {
                    CommandResult.Ok("아직 내보낸 파일이 없습니다. ‘요약해서 파일로 저장해줘’처럼 요청해 보세요.")
                } else {
                    CommandResult.Ok(
                        output = buildString {
                            appendLine("**내보낸 파일 ${files.size}개**")
                            files.take(15).forEach {
                                appendLine("· ${it.name}  (${"%,d".format(it.sizeBytes)}B · ${TimeFmt.stamp(it.modifiedAt)})")
                            }
                        }.trim(),
                        actions = listOf(ResultAction("폴더 열기", "file.open_folder"))
                    )
                }
            }.getOrElse { failure(it) }
        }

        reg.register(
            CommandSpec(
                id = "file.open_folder",
                labelKo = "내보내기 폴더 열기",
                category = CommandCategory.FILES,
                effect = EffectClass.EXTERNAL,
                description = "파일 관리자에서 내보내기 폴더를 엽니다.",
                keywords = listOf("폴더", "파일 관리자", "folder")
            )
        ) { _, ports ->
            runCatching { ports.files.openExportsFolder() }
                .map { CommandResult.Ok("내보내기 폴더를 열었습니다.") }
                .getOrElse { CommandResult.Failed("파일 관리자를 열 수 없습니다. 기기에 파일 앱이 있는지 확인해 주세요.") }
        }

        reg.register(
            CommandSpec(
                id = "file.share_export",
                labelKo = "파일 공유",
                category = CommandCategory.FILES,
                effect = EffectClass.EXTERNAL,
                description = "내보낸 파일을 다른 앱으로 공유합니다.",
                params = listOf(CommandRegistry.param("name", "파일 이름", true, "note.txt")),
                keywords = listOf("공유", "보내기", "share file")
            )
        ) { req, ports ->
            missing(reg, req, "file.share_export")?.let { return@register it }
            val name = req.arg("name")!!
            runCatching { ports.files.shareExport(name) }
                .map { CommandResult.Ok("공유 시트를 열었습니다: $name") }
                .getOrElse { failure(it) }
        }
    }
}
