@file:Suppress("DEPRECATION")

package ru.arc.origin.util

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Reads legacy integer model data and modern component float data with ARC's exact fallback. */
val ItemStack.customModelDataOrNull: Int?
    get() = itemMeta?.customModelDataOrNull

val ItemMeta.customModelDataOrNull: Int?
    get() {
        if (runCatching { hasCustomModelData() }.getOrDefault(false)) {
            runCatching { customModelData }.getOrNull()?.let { return it }
        }
        val firstFloat = runCatching { customModelDataComponent.floats.firstOrNull() }.getOrNull() ?: return null
        if (!firstFloat.isFinite() || firstFloat % 1.0f != 0.0f ||
            firstFloat !in Int.MIN_VALUE.toFloat()..Int.MAX_VALUE.toFloat()
        ) return null
        return firstFloat.toInt()
    }
