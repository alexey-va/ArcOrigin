package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Horse
import org.bukkit.entity.Player
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteOutcome
import ru.arc.origin.scene.OriginScenePose
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginOddJobMountyardTest : StringSpec({
    "feeding uses a stall animal while the safe escort and water targets have independent catalogs" {
        MockBukkitTestRuntime.open().use {
            val yard = OriginOddJobMountyard("rc_origin_spawn", oddJobTestYardCatalog(), listOf(371, 422, 423), 422, 371,
                routes = mockk(relaxed = true), tasks = LifecycleTaskScope(TestTaskScheduler()))
            repeat(4) { seed ->
                yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, seed, false)) shouldBe OriginYardWork(422, "Буран")
                yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_SADDLE, seed, false)) shouldBe OriginYardWork(371, "Ветер")
            }
            (0..2).map { seed -> yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_WATER, seed, false))!!.animalId } shouldBe listOf(371, 422, 423)
            yard.close()
        }
    }
    "feeding hay persists inside the stall until the actual route arrives and eating finishes" {
        YardFixture(OriginOddJobVariant.YARD_RATIONS, 422).use { f ->
            val eating = f.job.copy(stage = OriginOddJobStage.YARD_EATING)
            f.yard.sync(f.player, eating, f.stations, 0) shouldBe true
            f.blocks.size shouldBe 1
            verify { f.renderer.spawnBlock(match { it.x == 48.5 && it.z == -134.5 }, any()) }
            verify { f.blocks.single().isVisibleByDefault = true }
            f.yard.tick(f.player, eating, 10_000) shouldBe OriginYardTick.WAITING
            verify(exactly = 0) { f.blocks.single().remove() }
            f.location = Location(f.world, 48.5, 70.0, -135.5)
            f.arrive()
            f.yard.tick(f.player, eating, 10_000) shouldBe OriginYardTick.WAITING
            verify { anyConstructed<OriginSceneResources>().setPose(f.actor, OriginScenePose.HORSE_GRAZE) }
            f.yard.sync(f.player, eating, f.stations, 12_000) shouldBe true
            f.blocks.size shouldBe 1
            f.yard.tick(f.player, eating, 14_999) shouldBe OriginYardTick.WAITING
            f.yard.tick(f.player, eating, 15_000) shouldBe OriginYardTick.CARED
            verify(exactly = 1) { f.blocks.single().remove() }
        }
    }
    "leading uses the route controller pauses behind the player and never accepts a failed route" {
        YardFixture().use { f ->
            val lead = f.job.copy(stage = OriginOddJobStage.YARD_LEAD_OUT)
            f.yard.sync(f.player, lead, f.stations, 0) shouldBe true
            f.playerLocation = f.location.clone().add(0.0, 0.0, 1.0)
            f.yard.tick(f.player, lead, 1) shouldBe OriginYardTick.WAITING
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            f.playerLocation = Location(f.world, 61.5, 70.0, -122.5)
            f.yard.tick(f.player, lead, 2) shouldBe OriginYardTick.WAITING
            verify(exactly = 1) { f.routes.navigate(f.actor, match { it.x == 60.5 && it.z == -124.5 }, match { it.snapRadius == 0 }, any(), any(), true, any()) }
            verify { f.horse.setLeashHolder(f.player) }
            f.arrive(success = false)
            f.yard.tick(f.player, lead, 3) shouldBe OriginYardTick.FAILED
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "native saddle restoration precedes bounded home recovery and the lease stays held until arrival" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            val deliver = f.job.copy(stage = OriginOddJobStage.YARD_DELIVER)
            f.yard.sync(f.player, deliver, f.stations, 0) shouldBe true
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.SADDLE }) }
            var releases = 0
            f.yard.clear(f.player.uniqueId, UUID.randomUUID())
            verify(exactly = 0) { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            f.yard.clear(f.player.uniqueId, f.job.id) { releases++ }
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            releases shouldBe 0
            f.yard.ownsAnimal(371) shouldBe true
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            releases shouldBe 1
            f.yard.ownsAnimal(371) shouldBe false
            f.yard.close()
            releases shouldBe 1
        }
    }
    "blocked recovery and replaced NPC identity stop safely without teleporting or holding the lease" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            every { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) } returns false
            var released = false
            f.yard.clear(f.player.uniqueId, f.job.id) { released = true }
            released shouldBe true
            f.yard.ownsAnimal(371) shouldBe false
            every { f.actor.uniqueId } returns UUID.randomUUID()
            f.yard.resolve(371) shouldBe null
            f.yard.begin(f.player, f.job) shouldBe false
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "lease invalidation during home recovery abandons only its route and never restarts the old return" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            var releases = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { releases++ }
            releases shouldBe 0
            clearMocks(f.routes, answers = false)
            f.leaseValid = false
            f.scheduler.tick(4)
            verify(exactly = 1) { f.routes.abandon(f.actor) }
            verify(exactly = 0) { f.routes.stop(any()) }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            releases shouldBe 1
            f.scheduler.tick(40)
            releases shouldBe 1
        }
    }
    "closing an owner finishes pending recovery and prevents any further route tasks" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            var releases = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { releases++ }
            f.scheduler.pendingCount() shouldBe 1
            f.yard.close()
            releases shouldBe 1
            f.scheduler.pendingCount() shouldBe 0
            verify { f.routes.close() }
            f.scheduler.tick(40)
            releases shouldBe 1
        }
    }
    "generation revocation restores saddle and pose synchronously before ownership is transferred" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            f.yard.sync(f.player, f.job.copy(stage = OriginOddJobStage.YARD_DELIVER), f.stations, 0) shouldBe true
            clearMocks(f.routes, answers = false)
            f.yard.revoke(f.player.uniqueId, f.job.id)
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            verify { anyConstructed<OriginSceneResources>().cleanup() }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            f.yard.ownsAnimal(371) shouldBe false
            f.scheduler.pendingCount() shouldBe 0
        }
    }
    "arrival removes the worker leash before Senya accepts the prepared horse" {
        YardFixture().use { f ->
            val deliver = f.job.copy(stage = OriginOddJobStage.YARD_DELIVER)
            f.yard.sync(f.player, deliver, f.stations, 0) shouldBe true
            f.playerLocation = Location(f.world, 62.0, 70.0, -123.0)
            f.yard.tick(f.player, deliver, 1) shouldBe OriginYardTick.WAITING
            verify { f.horse.setLeashHolder(f.player) }
            f.location = Location(f.world, 59.5, 70.0, -133.5)
            f.playerLocation = f.location.clone().add(-1.0, 0.0, 0.0)
            f.arrive()
            f.yard.tick(f.player, deliver, 2) shouldBe OriginYardTick.ARRIVED
            f.yard.sync(f.player, deliver.copy(stage = OriginOddJobStage.YARD_HANDOFF), f.stations, 3) shouldBe true
            verify(exactly = 1) { f.horse.setLeashHolder(null) }
        }
    }
})

private class YardFixture(variant: OriginOddJobVariant = OriginOddJobVariant.YARD_SADDLE, id: Int = 371) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val scheduler = TestTaskScheduler()
    val world = mockk<World>(relaxed = true) { every { uid } returns UUID.randomUUID(); every { name } returns "rc_origin_spawn" }
    val home = Location(world, if (id == 422) 47.5 else 63.5, 70.0, if (id == 422) -136.5 else -119.5)
    var location = home.clone()
    var playerLocation = home.clone().add(-1.0, 0.0, 0.0)
    val equipment = mockk<EntityEquipment>(relaxed = true) { every { getItem(EquipmentSlot.SADDLE) } returns ItemStack(Material.AIR) }
    val horse = mockk<Horse>(relaxed = true) {
        every { this@mockk.world } returns this@YardFixture.world
        every { type } returns EntityType.HORSE
        every { isValid } returns true
        every { location } answers { this@YardFixture.location.clone() }
        every { this@mockk.equipment } returns this@YardFixture.equipment
        every { passengers } returns emptyList()
        every { isLeashed } returns false
        every { setLeashHolder(any()) } returns true
    }
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { world } returns this@YardFixture.world
        every { location } answers { playerLocation.clone() }
    }
    val catalog = oddJobTestYardCatalog()
    val actor = mockk<NPC> {
        every { this@mockk.id } returns id
        every { uniqueId } returns catalog.animals.first { it.npcId == id }.npcUuid
        every { isSpawned } returns true
        every { entity } returns horse
    }
    var navigating = false
    var leaseValid = true
    var outcome: NpcRouteOutcome? = null
    val routes = mockk<CitizensNpcRouteController>(relaxed = true) {
        every { navigate(any(), any(), any(), any(), any(), any(), any()) } answers { navigating = true; outcome = null; true }
        every { isNavigating(any()) } answers { navigating }
        every { consumeOutcome(any()) } answers { outcome.also { outcome = null } }
        every { stop(any()) } answers { navigating = false }
    }
    val blocks = mutableListOf<PacketBlockDisplay>()
    val renderer = mockk<PaperPacketDisplays>(relaxed = true) {
        every { spawnBlock(any(), any()) } answers {
            mockk<PacketBlockDisplay>(relaxed = true).also { display -> blocks += display; every { display.isValid } returns true }
        }
    }
    val stations = mapOf(OriginOddJobStationId.FEED to OriginOddJobStation(Location(world, 48.5, 70.02, -134.5), mockk(), setOf(Material.PACKED_MUD)))
    val job = OriginOddJobsProgress(240_000).begin(player.uniqueId, OriginOddJobOffer(UUID.randomUUID(), variant, 8, false), 0,
        yardTarget = OriginYardWork(id, catalog.animals.first { it.npcId == id }.name))!!
    val yard: OriginOddJobMountyard
    init {
        mockkStatic(CitizensAPI::class)
        mockkConstructor(OriginSceneResources::class)
        every { anyConstructed<OriginSceneResources>().clearMovementPose(any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().setPose(any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().faceHorizontal(any(), any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().cleanup() } returns emptyList()
        val registry = mockk<NPCRegistry> { every { getById(any()) } answers { actor.takeIf { firstArg<Int>() == id } } }
        every { CitizensAPI.getNPCRegistry() } returns registry
        yard = OriginOddJobMountyard(world.name, catalog, listOf(371, 422, 423), 422, 371, routes = routes,
            tasks = LifecycleTaskScope(scheduler), createDisplays = { renderer })
        yard.begin(player, job) { leaseValid } shouldBe true
    }
    fun arrive(success: Boolean = true) { navigating = false; outcome = NpcRouteOutcome(success, if (success) "FINISHED" else "STALLED", null) }
    override fun close() {
        yard.close()
        unmockkConstructor(OriginSceneResources::class)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
}
