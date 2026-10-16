package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.origin.mountyard.OriginMountYardAnimalCatalog
import java.util.UUID

/** Named care targets reuse the canonical Citizens UUID/type fences; no animal inventory is changed. */
internal class OriginOddJobMountyard(
    private val worldName: String,
    private val catalog: OriginMountYardAnimalCatalog,
    private val animalIds: List<Int>,
    private val feedingAnimalId: Int,
) {
    private data class Saddled(val requestId: UUID, val entity: LivingEntity)
    private val saddled = mutableMapOf<UUID, Saddled>()

    fun target(offer: OriginOddJobOffer): OriginYardWork? {
        val id = if (offer.variant in setOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER)) feedingAnimalId
            else animalIds.getOrNull(Math.floorMod(offer.seed, animalIds.size.coerceAtLeast(1))) ?: return null
        val animal = catalog.animals.firstOrNull { it.npcId == id } ?: return null
        return OriginYardWork(animal.npcId, animal.name)
    }

    fun resolve(id: Int): LivingEntity? {
        val npc = CitizensAPI.getNPCRegistry().getById(id)?.takeIf { it.isSpawned } ?: return null
        val animal = catalog.resolve(id, npc.uniqueId) ?: return null
        val entity = npc.entity as? LivingEntity ?: return null
        return entity.takeIf { it.isValid && it.world.name == worldName && it.type.name == animal.entityType }
    }

    fun contains(id: Int): Boolean = id in animalIds || id == feedingAnimalId
    fun available(): Boolean = (animalIds + feedingAnimalId).all { resolve(it) != null }

    fun stroke(player: Player, job: OriginOddJobSession) {
        val animal = resolve(requireNotNull(job.yard).animalId) ?: return
        player.swingMainHand()
        val contact = animal.location.add(0.0, animal.height * 0.65, 0.0)
        player.spawnParticle(Particle.POOF, contact, 5, 0.3, 0.18, 0.3, 0.01)
        player.playSound(contact, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.55f, 1.05f)
        if (job.stage == OriginOddJobStage.RETURN) {
            player.spawnParticle(Particle.HEART, contact, 2, 0.2, 0.1, 0.2)
            player.playSound(contact, Sound.ENTITY_HORSE_BREATHE, 0.4f, 1.1f)
        }
    }

    fun saddle(player: Player, job: OriginOddJobSession) {
        val animal = resolve(requireNotNull(job.yard).animalId) ?: return
        saddled[player.uniqueId] = Saddled(job.id, animal)
        player.sendEquipmentChange(animal, EquipmentSlot.SADDLE, ItemStack(Material.SADDLE))
        player.swingMainHand()
        player.playSound(animal.location, Sound.ENTITY_HORSE_SADDLE, 0.6f, 1f)
    }

    fun clear(playerId: UUID, requestId: UUID) {
        val state = saddled[playerId]?.takeIf { it.requestId == requestId } ?: return
        saddled.remove(playerId)
        val player = Bukkit.getPlayer(playerId) ?: return
        if (player.isOnline && state.entity.isValid && player.world.uid == state.entity.world.uid) {
            player.sendEquipmentChange(state.entity, EquipmentSlot.SADDLE,
                state.entity.equipment?.getItem(EquipmentSlot.SADDLE) ?: ItemStack(Material.AIR))
        }
    }
}
