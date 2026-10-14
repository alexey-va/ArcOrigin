package ru.arc.origin

/** Optional ARC-owned Citizens bridge; every call is a no-op when its service is absent. */
internal object OriginNpcHolograms {
    fun showTemporaryBubble(npcId: Int, lines: List<String>, durationTicks: Int): Boolean =
        OriginServices.npcHolograms?.showTemporaryBubble(npcId, lines, durationTicks) ?: false

    fun showTemporaryBubble(npcId: Int, lines: List<String>, durationTicks: Int, owner: String): Boolean =
        OriginServices.npcHolograms?.showTemporaryBubble(npcId, lines, durationTicks, owner) ?: false

    fun clearTemporaryBubble(npcId: Int, owner: String) {
        OriginServices.npcHolograms?.clearTemporaryBubble(npcId, owner)
    }

    fun hasTemporaryBubble(npcId: Int): Boolean =
        OriginServices.npcHolograms?.hasTemporaryBubble(npcId) ?: false

    fun setNameHiddenTemporarily(npcId: Int, owner: String, hidden: Boolean) {
        OriginServices.npcHolograms?.setNameHiddenTemporarily(npcId, owner, hidden)
    }

    fun patchName(npcId: Int, name: String) {
        OriginServices.npcHolograms?.patchName(npcId, name)
    }

    fun patchNameplate(npcId: Int, enabled: String) {
        OriginServices.npcHolograms?.patchNameplate(npcId, enabled)
    }

    fun patchHologram(npcId: Int, lines: List<String>, viewRange: Int) {
        OriginServices.npcHolograms?.patchHologram(npcId, lines, viewRange)
    }
}
