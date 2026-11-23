package com.ht.hjtk.call

import android.content.Intent
import com.ht.hjtk.Hjtk
import com.ht.hjtk.core.command.CommandHandler
import com.ht.hjtk.core.command.CommandResult
import com.ht.hjtk.core.command.PhoneGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** AI가 작업 중에 쓰는 전화 명령. 전부 HJTK 안에서 처리한다 */
object PhoneHandlers {
    fun of(h: Hjtk): Map<String, CommandHandler> = mapOf(
        "phone.dial" to CommandHandler { c ->
            val n = c.arg("number")
            if (PhoneGuard.normalize(n) == null) return@CommandHandler CommandResult.fail("전화번호 형식이 아니에요")
            h.app.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", n, null)).setClass(h.app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandResult.ok("HJTK 다이얼에 번호를 채웠어요. 통화 버튼은 직접 눌러 주세요.")
        },
        "phone.ai_call" to CommandHandler { c ->
            val n = c.arg("number")
            h.caller.start(n, c.arg("goal"), c.arg("facts"))?.let { return@CommandHandler CommandResult.fail(it) }
            val st = withTimeoutOrNull(4 * 60_000L) { h.caller.session.first { it.phase == AiCaller.Phase.CALLING || it.phase == AiCaller.Phase.LIVE || it.phase == AiCaller.Phase.ERROR } }
            if (st == null || st.phase == AiCaller.Phase.ERROR) return@CommandHandler CommandResult.fail(st?.note ?: "준비가 늦어요. AI 전화 화면에서 이어서 걸 수 있어요")
            h.app.startActivity(Intent(h.app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeoutOrNull(30 * 60_000L) { h.caller.session.first { it.phase == AiCaller.Phase.ENDED } }
            delay(300)
            CommandResult.ok("AI 통화 · $n\n" + h.caller.summary())
        },
    )
}
