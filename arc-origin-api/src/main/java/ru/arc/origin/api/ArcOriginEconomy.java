package ru.arc.origin.api;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Host-owned economy operations used by Origin dining and training. Amounts remain in vault units. */
public interface ArcOriginEconomy {
    boolean isAvailable();

    boolean has(Player player, double amount);

    /** Returns true only when the host economy confirms the withdrawal transaction. */
    boolean withdraw(Player player, double amount);

    /** Deposits through the configured default currency and preserves the idempotency reason. */
    boolean depositDefaultCurrency(UUID playerId, double amount, String reason);
}
