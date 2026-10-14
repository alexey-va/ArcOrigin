package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import ru.arc.origin.api.ArcOriginCommands

/** Service implementation registered for ARC's host-owned /arc router. */
internal class OriginCommandService : ArcOriginCommands {
    private val handlers = mapOf(
        CommandId.ORIGIN_PORTALS to OriginPortalsSubCommand,
        CommandId.AUCTION_PEDESTAL to AuctionPedestalSubCommand,
        CommandId.BREWERY to BrewerySubCommand,
        CommandId.NPC_CYCLE to NpcCycleSubCommand,
        CommandId.WORKSHOP to WorkshopSubCommand,
    )

    override fun execute(command: CommandId, sender: CommandSender, args: Array<String>): Boolean =
        requireNotNull(handlers[command]) { "Unknown Origin command route: $command" }.execute(sender, args)

    override fun tabComplete(command: CommandId, sender: CommandSender, args: Array<String>): List<String> =
        handlers[command]?.tabComplete(sender, args).orEmpty()

    override fun reload() {
        ru.arc.origin.ArcOriginPlugin.instance.reloadOrigin()
    }

    override fun isPublicAction(command: CommandId, args: Array<String>): Boolean =
        command == CommandId.ORIGIN_PORTALS && OriginPortalsSubCommand.isPublicAction(args)
}
