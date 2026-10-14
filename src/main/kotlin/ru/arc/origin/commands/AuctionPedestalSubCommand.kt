package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import ru.arc.origin.OriginSpawnModule

/** `/arc auctionpedestal add` places a durable showcase stand on the targeted block. */
object AuctionPedestalSubCommand : OriginCommandHandler {
    const val USAGE = "/arc auctionpedestal add"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? org.bukkit.entity.Player ?: run {
            sender.sendOriginComponent("<red>Эта команда доступна только игрокам.")
            return true
        }
        if (args.size != 1 || !args[0].equals("add", ignoreCase = true)) {
            sendOriginUsage(sender, USAGE)
            return true
        }

        OriginSpawnModule.addAuctionPedestal(player)?.let { key ->
            sender.sendMessage(OriginSpawnModule.auctionPedestalMessage(key, player.locale().toLanguageTag()))
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (args.size == 1) listOf("add").originTabComplete(args[0]) else emptyList()
}
