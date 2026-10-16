package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginOddJobMountyardTest : StringSpec({
    "fixed feeding fixtures name their animal while grooming and saddling use the canonical catalog" {
        val yard = OriginOddJobMountyard("rc_origin_spawn", oddJobTestYardCatalog(), listOf(371, 422, 423), 371)
        for (variant in listOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER)) {
            repeat(4) { seed ->
                yard.target(OriginOddJobOffer(UUID.randomUUID(), variant, seed, false)) shouldBe OriginYardWork(371, "Ветер")
            }
        }
        val targets = (0..2).map { seed -> yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_GROOM, seed, false))!!.animalId }
        targets shouldBe listOf(371, 422, 423)
    }

    "saddle is a request scoped viewer update and cleanup restores actual equipment without writing it" {
        MockBukkitTestRuntime.open().use {
            mockkStatic(CitizensAPI::class, Bukkit::class)
            try {
                val catalog = oddJobTestYardCatalog()
                val world = mockk<World> { every { uid } returns UUID.randomUUID(); every { name } returns "rc_origin_spawn" }
                val actual = ItemStack(Material.AIR)
                val equipment = mockk<EntityEquipment>(relaxed = true) { every { getItem(EquipmentSlot.SADDLE) } returns actual }
                val animal = mockk<LivingEntity>(relaxed = true) {
                    every { this@mockk.world } returns world
                    every { type } returns EntityType.HORSE
                    every { isValid } returns true
                    every { location } answers { Location(world, 63.6, 70.0, -119.7) }
                    every { this@mockk.equipment } returns equipment
                }
                val id = UUID.randomUUID()
                val player = mockk<Player>(relaxed = true) {
                    every { uniqueId } returns id
                    every { isOnline } returns true
                    every { this@mockk.world } returns world
                }
                val npc = mockk<NPC> {
                    every { uniqueId } returns catalog.animals.first { it.npcId == 371 }.npcUuid
                    every { isSpawned } returns true
                    every { entity } returns animal
                }
                val registry = mockk<NPCRegistry> { every { getById(371) } returns npc }
                every { CitizensAPI.getNPCRegistry() } returns registry
                every { Bukkit.getPlayer(id) } returns player
                val yard = OriginOddJobMountyard(world.name, catalog, listOf(371), 371)
                val job = OriginOddJobsProgress(240_000).begin(id,
                    OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_SADDLE, 8, false), 0)!!
                yard.saddle(player, job)
                verify(exactly = 1) { player.sendEquipmentChange(animal, EquipmentSlot.SADDLE, match { it.type == Material.SADDLE }) }
                verify(exactly = 0) { equipment.setItem(any(), any()) }
                yard.clear(id, UUID.randomUUID())
                verify(exactly = 0) { player.sendEquipmentChange(animal, EquipmentSlot.SADDLE, actual) }
                yard.clear(id, job.id)
                yard.clear(id, job.id)
                verify(exactly = 1) { player.sendEquipmentChange(animal, EquipmentSlot.SADDLE, actual) }
                every { npc.uniqueId } returns UUID.randomUUID()
                yard.resolve(371) shouldBe null
            } finally {
                unmockkStatic(CitizensAPI::class, Bukkit::class)
            }
        }
    }
})
