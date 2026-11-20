package ru.arc.origin

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.EntityPoseTrait.EntityPose
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.trait.SleepTrait
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pose
import org.bukkit.util.Vector

internal class OriginWorkshopSleepPose {
    private class Sleeper(val entity: LivingEntity, val hadGravity: Boolean) {
        fun restoreGravity() {
            if (hadGravity && entity.isValid) entity.setGravity(true)
        }
    }

    private val sleepers = mutableMapOf<Int, Sleeper>()

    fun isSeated(actor: NPC): Boolean {
        val entity = sleepers[actor.id]?.entity ?: return false
        return actor.entity === entity && entity.pose == Pose.SLEEPING
    }

    fun seat(actor: NPC, anchor: Location) {
        val entity = actor.entity as LivingEntity
        val facing = (anchor.block.blockData as? Bed)?.facing
        val position = if (facing == null) anchor else {
            // A client with bed metadata applies this offset from the head block and uses its facing.
            // A pose-only sleeper needs the same model origin and body yaw explicitly.
            val feetOffset = 1.62 * (entity.getAttribute(Attribute.SCALE)?.value ?: 1.0) - 0.1
            val yaw = when (facing) {
                BlockFace.WEST -> 0f
                BlockFace.SOUTH -> 90f
                BlockFace.EAST -> 180f
                BlockFace.NORTH -> 270f
                else -> error("Invalid bed facing: $facing")
            }
            Location(anchor.world, anchor.blockX + 0.5 - facing.modX * feetOffset,
                anchor.blockY + 0.6875, anchor.blockZ + 0.5 - facing.modZ * feetOffset, yaw, 0f)
        }
        check(entity.teleport(position)) { "Workshop NPC ${actor.id} rejected sleep seat" }
        entity.bodyYaw = position.yaw
        actor.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(position.yaw, position.pitch)
        // Citizens SleepTrait enters a real bed as a player and triggers world sleep handlers.
        actor.getOrAddTrait(EntityPoseTrait::class.java).apply {
            pose = EntityPose.SLEEPING
            run()
        }
        val previous = sleepers[actor.id]
        if (previous?.entity !== entity) {
            previous?.restoreGravity()
            sleepers[actor.id] = Sleeper(entity, entity.hasGravity())
        }
        entity.setGravity(false)
        entity.velocity = Vector()
    }

    fun wake(actor: NPC) {
        sleepers.remove(actor.id)?.restoreGravity()
        val visualPose = actor.getTraitNullable(EntityPoseTrait::class.java)?.takeIf { it.pose == EntityPose.SLEEPING }
        // An old saved Citizens sleep trait can still own a native bed after an upgrade.
        actor.getTraitNullable(SleepTrait::class.java)?.setSleeping(null)
        visualPose?.apply {
            pose = EntityPose.STANDING
            run()
            pose = null
        }
        if (!actor.isSpawned) return
        (actor.entity as? HumanEntity)?.takeIf { it.isSleeping }?.wakeup(false)
    }
}
