package ru.arc.origin.api;

import org.bukkit.inventory.ItemStack;

/** Host predicate for the investigation case file's existing interaction exemption. */
public interface ArcOriginCaseFiles {
    boolean isCaseFile(ItemStack item);
}
