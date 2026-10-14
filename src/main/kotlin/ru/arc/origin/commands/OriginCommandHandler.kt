package ru.arc.origin.commands

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import ru.arc.util.TextUtils

/** Child-local handler contract; ARC's /arc dispatcher calls it through ArcOriginCommands. */
internal interface OriginCommandHandler {
    fun execute(sender: CommandSender, args: Array<String>): Boolean

    fun tabComplete(sender: CommandSender, args: Array<String>): List<String> = emptyList()
}

internal fun sendOriginUsage(sender: CommandSender, usage: String) {
    sender.sendMessage(TextUtils.mm("<red>Использование: <gray>$usage", true))
}

internal fun List<String>.originTabComplete(input: String): List<String> {
    if (input.isEmpty()) return filter(String::isNotBlank).sortedWith(compareBy({ it.length }, { it.lowercase() }))
    val lower = input.lowercase()
    return filter { it.isNotBlank() && it.lowercase().startsWith(lower) }
        .sortedWith(compareBy({ !it.startsWith(input) }, { it.length }, { it.lowercase() }))
}

internal fun CommandSender.sendOriginComponent(text: String) {
    sendMessage(TextUtils.mm(text, true))
}
