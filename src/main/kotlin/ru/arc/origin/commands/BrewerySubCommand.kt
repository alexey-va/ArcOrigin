package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.worldcontent.BreweryTableDialogs

/** Public entry point for seated dining dialogs in Origin. */
object BrewerySubCommand : OriginCommandHandler {
    const val USAGE = "/arc brewery order [food|drinks|courtyard|restaurant]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player ?: return true
        if (args.isEmpty() || args.size > 2 || !args[0].equals("order", ignoreCase = true)) {
            sendOriginUsage(player, USAGE)
            return true
        }
        val menu = if (args.size == 1) BreweryTableDialogs.Menu.FOOD else BreweryTableDialogs.Menu.parse(args[1])
        if (menu == null) {
            sendOriginUsage(player, USAGE)
            return true
        }
        BreweryTableDialogs.openOrder(player, menu)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (sender !is Player) emptyList() else when (args.size) {
            1 -> listOf("order").originTabComplete(args[0])
            2 -> if (args[0].equals("order", ignoreCase = true)) {
                BreweryTableDialogs.Menu.entries.map { it.id }.originTabComplete(args[1])
            } else emptyList()
            else -> emptyList()
        }
}
