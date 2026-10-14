package ru.arc.origin.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/** Narrow Origin-facing view of the configured auction showcase hook. */
public interface ArcOriginAuctions {
    List<Listing> showcaseListings();

    void openShowcaseListing(Player player, int listingId, Consumer<OpenResult> callback);

    record Listing(int id, ItemStack item, String itemName, String sellerName, String price) {
    }

    enum OpenResult {
        CONFIRMATION_OPENED,
        INSUFFICIENT_FUNDS,
        STALE,
        UNAVAILABLE,
        BUSY,
        FAILED
    }
}
