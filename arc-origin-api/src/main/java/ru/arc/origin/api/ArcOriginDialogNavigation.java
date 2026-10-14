package ru.arc.origin.api;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Navigation preferences shared with the host's dialog history. Dialogs themselves stay plugin-local. */
public interface ArcOriginDialogNavigation {
    /** Clears the host dialog-flow history before a child-owned dialog opens. */
    void beginDialogFlow(Player player);

    /** Whether Escape should go back in the current host dialog flow for this player. */
    boolean escapeGoesBack(UUID playerId);
}
