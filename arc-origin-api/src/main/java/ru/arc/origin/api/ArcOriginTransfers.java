package ru.arc.origin.api;

import org.bukkit.entity.Player;

/** Host-owned proxy transfer operation used by the Slimefun portal. */
public interface ArcOriginTransfers {
    boolean sendPlayerToServer(Player player, String destination);
}
