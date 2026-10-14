package ru.arc.origin.api;

import java.util.List;

/** Narrow access to ARC's Citizens nameplate and temporary-speech operations. */
public interface ArcOriginNpcHolograms {
    boolean showTemporaryBubble(int npcId, List<String> lines, int durationTicks);

    boolean showTemporaryBubble(int npcId, List<String> lines, int durationTicks, String owner);

    void clearTemporaryBubble(int npcId, String owner);

    boolean hasTemporaryBubble(int npcId);

    void setNameHiddenTemporarily(int npcId, String owner, boolean hidden);

    void patchName(int npcId, String name);

    void patchNameplate(int npcId, String enabled);

    void patchHologram(int npcId, List<String> lines, int viewRange);
}
