package com.hjtk.app.core.command

import com.hjtk.app.core.command.catalog.AiCommands
import com.hjtk.app.core.command.catalog.AppCommands
import com.hjtk.app.core.command.catalog.CalendarCommands
import com.hjtk.app.core.command.catalog.FileCommands
import com.hjtk.app.core.command.catalog.FlowCommands
import com.hjtk.app.core.command.catalog.MailCommands
import com.hjtk.app.core.command.catalog.SearchCommands
import com.hjtk.app.core.command.catalog.SystemCommands
import com.hjtk.app.core.command.catalog.UtilityCommands

/**
 * Installs every built-in command once. New capabilities are added by writing
 * another `register()` and calling it here — the router, planner and UI pick
 * them up with no further changes.
 */
object CommandCatalog {

    fun install(registry: CommandRegistry) {
        SearchCommands.register(registry)
        FileCommands.register(registry)
        MailCommands.register(registry)
        CalendarCommands.register(registry)
        AppCommands.register(registry)
        FlowCommands.register(registry)
        UtilityCommands.register(registry)
        AiCommands.register(registry)
        SystemCommands.register(registry)
    }

    fun build(): CommandRegistry = CommandRegistry().also { install(it) }
}
