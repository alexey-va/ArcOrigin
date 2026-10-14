package ru.arc.origin.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/** Host-owned ItemsAdder furniture recognition and removal, retaining the installed implementation. */
public interface ArcOriginFurniture {
    boolean isAvailable();

    FurnitureHandle inspect(Entity entity);

    FurnitureHandle inspect(Block block);

    boolean remove(Entity entity, FurnitureFamily family);

    enum FurnitureFamily {
        SIMPLE,
        COMPLEX
    }

    record FurnitureHandle(Entity root, FurnitureFamily family, String namespacedId) {
    }
}
