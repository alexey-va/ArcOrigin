package ru.arc.origin.api;

import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Host-owned viewer-specific glow adapter used by Origin dining scenes. */
public interface ArcOriginViewerGlow {
    void setDisplayGlowingFor(Display display, Player viewer, boolean glowing);

    /** A null viewer requests the legacy entity-wide glow fallback. */
    void setEntityGlowingFor(LivingEntity entity, Player viewer, boolean glowing);
}
