package ru.arc.origin

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.SleepTrait
import org.bukkit.entity.HumanEntity

internal fun wakeOriginWorkshopNpc(actor: NPC) {
    actor.getTraitNullable(SleepTrait::class.java)?.setSleeping(null)
    if (!actor.isSpawned) return
    // Citizens clears its pose intent, but a real bed can retain native player sleep.
    (actor.entity as? HumanEntity)?.takeIf { it.isSleeping }?.wakeup(false)
}
