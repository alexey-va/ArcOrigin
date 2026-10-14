package ru.arc.origin.mountyard

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.origin.api.ArcOriginNpcHolograms
import java.nio.file.Files
import java.util.UUID

class OriginMountYardAnimalsAdapterTest {
    @AfterEach
    fun clearConfig() {
        ConfigManager.clear()
    }

    @Test
    fun `native click adapter uses stable NPC identity across entity respawns`() {
        val owned = loadAnimalsConfig()
        val animal = owned.animals.first { it.npcId == 371 }
        val world = mockk<World>(relaxed = true)
        every { world.name } returns owned.world
        every { world.uid } returns UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.location } answers { Location(world, 0.0, 70.0, 0.0) }
        val entity = mockk<LivingEntity>(relaxed = true)
        every { entity.world } returns world
        every { entity.type } returns EntityType.HORSE
        every { entity.location } answers { Location(world, 1.0, 70.0, 0.0) }
        every { entity.height } returns 1.6
        var spawnedEntityUuid = UUID.randomUUID()
        every { entity.uniqueId } answers { spawnedEntityUuid }
        val npc = mockk<NPC>(relaxed = true)
        every { npc.id } returns animal.npcId
        every { npc.uniqueId } returns animal.npcUuid
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        var now = 1_000L
        var accepted = 0
        val holograms = mockk<ArcOriginNpcHolograms>(relaxed = true)
        every { holograms.showTemporaryBubble(any(), any(), any(), any()) } returns true
        val listener = OriginMountYardAnimals(owned, clock = { now }, onFriendlyPet = { _, _ -> accepted++ }, npcHolograms = holograms)
        try {
            val cancelled = NPCRightClickEvent(npc, player)
            cancelled.isCancelled = true
            listener.onNpcRightClick(cancelled)
            accepted shouldBe 0

            now += 1_000
            val first = NPCRightClickEvent(npc, player)
            listener.onNpcRightClick(first)
            first.isCancelled shouldBe true
            first.isDelayedCancellation shouldBe true
            accepted shouldBe 1

            now += 1_000
            spawnedEntityUuid = UUID.randomUUID()
            listener.onNpcRightClick(NPCRightClickEvent(npc, player))
            accepted shouldBe 2

            now += 1_000
            every { npc.uniqueId } returns UUID.randomUUID()
            val reusedId = NPCRightClickEvent(npc, player)
            listener.onNpcRightClick(reusedId)
            reusedId.isCancelled shouldBe false
            accepted shouldBe 2
        } finally {
            listener.clear()
        }
    }

    @Test
    fun `synthetic reaction cancels an observed over-cap damage event`() {
        val owned = loadAnimalsConfig()
        val animal = owned.animals.first { it.npcId == 371 }
        val world = mockk<World>(relaxed = true)
        every { world.name } returns owned.world
        every { world.uid } returns UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.location } answers { Location(world, 0.0, 70.0, 0.0) }
        every { player.health } returns 1.5
        val entity = mockk<LivingEntity>(relaxed = true)
        every { entity.uniqueId } returns UUID.randomUUID()
        every { entity.world } returns world
        every { entity.type } returns EntityType.HORSE
        every { entity.location } answers { Location(world, 1.0, 70.0, 0.0) }
        every { entity.height } returns 1.6
        val npc = mockk<NPC>(relaxed = true)
        every { npc.id } returns animal.npcId
        every { npc.uniqueId } returns animal.npcUuid
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        var now = 1_000L
        var observed: EntityDamageByEntityEvent? = null
        lateinit var listener: OriginMountYardAnimals
        every { player.damage(any<Double>(), any<Entity>()) } answers {
            val event = syntheticDamageEvent(player, secondArg<Entity>(), 20.0)
            observed = event
            listener.onSyntheticReactionDamage(event)
        }
        val holograms = mockk<ArcOriginNpcHolograms>(relaxed = true)
        every { holograms.showTemporaryBubble(any(), any(), any(), any()) } returns true
        listener = OriginMountYardAnimals(owned, clock = { now }, npcHolograms = holograms)
        try {
            repeat(4) {
                listener.onNpcRightClick(NPCRightClickEvent(npc, player))
                now += 1_000
            }
            checkNotNull(observed).isCancelled shouldBe true
        } finally {
            listener.clear()
        }
    }

    @Test
    fun `synthetic reaction keeps safe normal damage unchanged`() {
        val owned = loadAnimalsConfig()
        val animal = owned.animals.first { it.npcId == 371 }
        val world = mockk<World>(relaxed = true)
        every { world.name } returns owned.world
        every { world.uid } returns UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.location } answers { Location(world, 0.0, 70.0, 0.0) }
        every { player.health } returns 20.0
        val entity = mockk<LivingEntity>(relaxed = true)
        every { entity.uniqueId } returns UUID.randomUUID()
        every { entity.world } returns world
        every { entity.type } returns EntityType.HORSE
        every { entity.location } answers { Location(world, 1.0, 70.0, 0.0) }
        every { entity.height } returns 1.6
        val npc = mockk<NPC>(relaxed = true)
        every { npc.id } returns animal.npcId
        every { npc.uniqueId } returns animal.npcUuid
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        var now = 1_000L
        var observed: EntityDamageByEntityEvent? = null
        lateinit var listener: OriginMountYardAnimals
        every { player.damage(any<Double>(), any<Entity>()) } answers {
            val event = syntheticDamageEvent(player, secondArg<Entity>(), 2.0)
            observed = event
            listener.onSyntheticReactionDamage(event)
        }
        val holograms = mockk<ArcOriginNpcHolograms>(relaxed = true)
        every { holograms.showTemporaryBubble(any(), any(), any(), any()) } returns true
        listener = OriginMountYardAnimals(owned, clock = { now }, npcHolograms = holograms)
        try {
            repeat(4) {
                listener.onNpcRightClick(NPCRightClickEvent(npc, player))
                now += 1_000
            }
            checkNotNull(observed).isCancelled shouldBe false
            checkNotNull(observed).damage shouldBe 2.0
        } finally {
            listener.clear()
        }
    }

    private fun loadAnimalsConfig(): OriginMountYardAnimalsConfig {
        val directory = Files.createTempDirectory("arc-origin-mountyard-test")
        return try {
            OriginMountYardAnimalsConfig.load(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun syntheticDamageEvent(
        target: LivingEntity,
        damager: Entity,
        damage: Double,
    ): EntityDamageByEntityEvent {
        val event = mockk<EntityDamageByEntityEvent>()
        var cancelled = false
        every { event.entity } returns target
        every { event.damager } returns damager
        every { event.damage } returns damage
        every { event.finalDamage } returns damage
        every { event.isCancelled } answers { cancelled }
        every { event.isCancelled = any() } answers { cancelled = firstArg() }
        return event
    }
}
