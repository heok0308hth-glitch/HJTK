package com.hjtk.app.core.command.catalog

import com.hjtk.app.core.HostPorts
import com.hjtk.app.core.command.CommandRegistry
import com.hjtk.app.core.model.CommandRequest
import com.hjtk.app.core.model.CommandResult

internal fun missing(reg: CommandRegistry, req: CommandRequest, id: String): CommandResult.NeedInput? {
    val spec = reg.spec(id) ?: return null
    return reg.missingArg(req, spec)
}

internal fun failure(t: Throwable, hint: String = ""): CommandResult.Failed {
    val message = t.message?.replace('\n', ' ')?.take(180)
    val reason = if (message.isNullOrBlank()) "알 수 없는 오류가 발생했습니다." else message
    return CommandResult.Failed(if (hint.isBlank()) reason else "$reason\n$hint", retryable = true)
}

internal fun HostPorts.now(): Long = clock.nowMs()
