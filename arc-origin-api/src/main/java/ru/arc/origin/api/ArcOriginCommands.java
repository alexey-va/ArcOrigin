package ru.arc.origin.api;

import org.bukkit.command.CommandSender;

import java.util.List;

/** ARC's /arc command delegates only the Origin-owned routes to this service. */
public interface ArcOriginCommands {
    boolean execute(CommandId command, CommandSender sender, String[] args);

    List<String> tabComplete(CommandId command, CommandSender sender, String[] args);

    /** Reloads Origin-owned configuration and initialized modules. */
    void reload();

    /** Origin portal entry intentionally remains public; other routes retain ARC-side auth. */
    boolean isPublicAction(CommandId command, String[] args);

    enum CommandId {
        ORIGIN_PORTALS,
        AUCTION_PEDESTAL,
        BREWERY,
        NPC_CYCLE,
        WORKSHOP
    }
}
