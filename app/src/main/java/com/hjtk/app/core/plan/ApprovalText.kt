package com.hjtk.app.core.plan

import com.hjtk.app.core.model.ApprovalRequest
import com.hjtk.app.core.model.CommandSpec
import com.hjtk.app.core.model.EffectClass
import com.hjtk.app.core.model.PlanStep

/** Plain-language approval copy, derived from the command and its arguments. */
object ApprovalText {

    fun build(planId: String, step: PlanStep, spec: CommandSpec?, args: Map<String, String>): ApprovalRequest {
        val (summary, details) = when (step.commandId) {
            "mail.send" -> "메일을 발송합니다. 발송 후에는 취소할 수 없습니다." to listOfNotNull(
                args["to"]?.let { "받는 사람" to it },
                args["subject"]?.let { "제목" to it },
                args["body"]?.let { "본문" to it.take(300) }
            )
            "app.share" -> "다른 앱으로 텍스트를 공유합니다." to listOfNotNull(args["text"]?.let { "내용" to it.take(200) })
            "app.dial" -> "전화 앱에 번호를 채웁니다(발신은 직접)." to listOfNotNull(args["number"]?.let { "번호" to it })
            "app.sms" -> "문자 앱에 내용을 채웁니다(발송은 직접)." to listOfNotNull(
                args["number"]?.let { "번호" to it },
                args["body"]?.let { "내용" to it.take(200) }
            )
            "calendar.new_event" -> "캘린더에 일정을 추가합니다." to args.map { (k, v) -> k to v }
            "file.share_export" -> "파일을 다른 앱으로 내보냅니다." to listOfNotNull(args["name"]?.let { "파일" to it })
            "pipeline.run", "skill.run" -> "저장된 흐름을 실행합니다. 단계별로 다시 확인합니다." to args.map { (k, v) -> k to v }
            else -> "‘${spec?.labelKo ?: step.commandId}’을(를) 실행합니다." to args.map { (k, v) -> k to v.take(200) }
        }
        val effect = spec?.effect ?: EffectClass.EXTERNAL
        return ApprovalRequest(
            planId = planId,
            stepId = step.id,
            commandId = step.commandId,
            titleKo = step.titleKo.ifBlank { spec?.labelKo ?: step.commandId },
            summaryKo = summary,
            details = details,
            effect = effect
        )
    }

    fun effectLabel(effect: EffectClass): String = when (effect) {
        EffectClass.READ -> "읽기"
        EffectClass.LOCAL_WRITE -> "앱 내부 저장"
        EffectClass.EXTERNAL -> "외부로 전송"
        EffectClass.SPEND -> "비용 발생"
        EffectClass.DESTRUCTIVE -> "되돌릴 수 없음"
    }
}
