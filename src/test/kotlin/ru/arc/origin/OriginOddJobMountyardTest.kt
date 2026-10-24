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
import io.mockk.verifyOrder
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.api.npc.SimpleMetadataStore
import net.citizensnpcs.api.util.MemoryDataKey
import net.citizensnpcs.trait.Controllable
import net.citizensnpcs.trait.HorseModifiers
import net.citizensnpcs.trait.LookClose
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Gate
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import org.bukkit.entity.Horse
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Entity
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
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginSceneActor
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginOddJobMountyardTest : StringSpec({
    "every stable variant uses the shared eligible horse pool without mutating its query history" {
        YardFixture(startJob = false).use { f ->
            val yard = f.yard
            for (variant in OriginOddJobVariant.entries.filter { it.kind == OriginOddJobKind.MOUNTYARD }) {
                val offer = OriginOddJobOffer(UUID.randomUUID(), variant, 0, false)
                repeat(3) { yard.target(offer) shouldBe OriginYardWork(422, "Буран") }
                yard.target(offer, 422) shouldBe OriginYardWork(423, "Рыжик")
                yard.target(offer, 423) shouldBe OriginYardWork(422, "Буран")
                yard.target(offer) shouldBe OriginYardWork(422, "Буран")
                (0..3).map { seed -> yard.target(offer.copy(seed = seed))!!.animalId } shouldBe listOf(422, 423, 422, 423)
            }
            yard.contains(442) shouldBe false
            yard.contains(446) shouldBe false
            yard.begin(f.player, f.job.copy(yard = OriginYardWork(442, "Не рабочая лошадь"))) shouldBe false
        }
    }
    "care rotates through every barn species while tack stays on horses and cleanup filters supported pens" {
        YardFixture(startJob = false, careAnimals = true).use { f ->
            for (variant in listOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER)) {
                val offer = OriginOddJobOffer(UUID.randomUUID(), variant, 0, false)
                var previous: Int? = null
                val selected = (0..4).map { f.yard.target(offer, previous)!!.animalId.also { previous = it } }
                selected shouldBe listOf(422, 423, 442, 445, 446)
                for (id in listOf(442, 445, 446)) {
                    val job = f.job.copy(id = UUID.randomUUID(), offer = f.job.offer.copy(variant = variant), yard = OriginYardWork(id, "care"))
                    f.now = 0
                    f.yard.begin(f.player, job) shouldBe true
                    val stage = if (variant == OriginOddJobVariant.YARD_RATIONS) OriginOddJobStage.YARD_EATING else OriginOddJobStage.YARD_DRINKING
                    f.yard.sync(f.player, job.copy(stage = stage), emptyMap(), 0) shouldBe true
                    var released = 0
                    if (variant == OriginOddJobVariant.YARD_RATIONS) {
                        f.yard.sync(f.player, job.copy(stage = OriginOddJobStage.RETURN), emptyMap(), 1) shouldBe true
                        f.yard.clear(f.player.uniqueId, job.id) { released++ }
                        released shouldBe 0
                        f.now = 5_000; f.scheduler.tick(4)
                    } else {
                        f.yard.tick(f.player, job.copy(stage = stage), 3_500) shouldBe OriginYardTick.CARED
                        f.yard.sync(f.player, job.copy(stage = OriginOddJobStage.YARD_BUCKET_RETURN), emptyMap(), 3_501) shouldBe true
                        f.yard.sync(f.player, job.copy(stage = OriginOddJobStage.RETURN), emptyMap(), 3_502) shouldBe true
                        f.yard.clear(f.player.uniqueId, job.id) { released++ }
                    }
                    released shouldBe 1
                    f.yard.ownsAnimal(id) shouldBe false
                }
            }
            for (variant in listOf(OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE, OriginOddJobVariant.YARD_LESSON)) {
                val offer = OriginOddJobOffer(UUID.randomUUID(), variant, 2, false)
                f.yard.target(offer, 422)!!.animalId shouldBe 423
                f.yard.begin(f.player, f.job.copy(offer = f.job.offer.copy(variant = variant), yard = OriginYardWork(445, "goat"))) shouldBe false
            }
            val clean = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_CLEANUP, 0, false)
            f.yard.target(clean, 445, setOf(445, 446))!!.animalId shouldBe 446
            f.yard.target(clean, null, emptySet()) shouldBe null
            every { f.actor.isSpawned } returns false
            f.yard.available() shouldBe true
        }
    }
    "previous busy invalid or displaced horses are excluded without excluding another eligible stall horse" {
        YardFixture(id = 422, startJob = false).use { f ->
            val offer = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, 0, false)
            f.leashHolder = f.player
            f.yard.target(offer) shouldBe OriginYardWork(423, "Рыжик")
            f.leashHolder = null
            f.passengerVehicle = f.horse
            f.yard.target(offer) shouldBe OriginYardWork(423, "Рыжик")
            f.passengerVehicle = null
            every { f.actor.uniqueId } returns UUID.randomUUID()
            f.yard.target(offer) shouldBe OriginYardWork(423, "Рыжик")
            f.yard.target(offer, 423) shouldBe OriginYardWork(423, "Рыжик")
            every { f.actor.uniqueId } returns f.catalog.animals.first { it.npcId == 422 }.npcUuid
            f.location = ORIGIN_YARD_CARE_PAD.inWorld(f.world)
            f.yard.target(offer) shouldBe OriginYardWork(423, "Рыжик")
            f.yard.target(offer, 423) shouldBe OriginYardWork(423, "Рыжик")
            f.location = f.home.clone()
            f.yard.target(offer) shouldBe OriginYardWork(422, "Буран")
        }
    }
    "return marks ongoing care for background completion without restarting its timer" {
        for ((variant, duration) in listOf(OriginOddJobVariant.YARD_RATIONS to 5_000L)) {
            YardFixture(variant).use { f ->
                val stage = if (variant == OriginOddJobVariant.YARD_RATIONS) OriginOddJobStage.YARD_EATING else OriginOddJobStage.YARD_DRINKING
                f.yard.sync(f.player, f.job.copy(stage = stage), emptyMap(), 0) shouldBe true
                f.yard.sync(f.player, f.job.copy(stage = OriginOddJobStage.RETURN), emptyMap(), 1_000) shouldBe true
                var released = 0
                f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
                f.now = duration - 1; f.scheduler.tick(4)
                released shouldBe 0
                f.now = duration; f.scheduler.tick(4)
                released shouldBe 1
            }
        }
    }
    "water waits for drinking and physical bucket return before its lease is released" {
        YardFixture(OriginOddJobVariant.YARD_WATER).use { f ->
            val drinking = f.sync(OriginOddJobStage.YARD_DRINKING)
            f.blocks shouldBe emptyList() // One bucket comes from the shared gear owner, never a second prop.
            f.yard.tick(f.player, drinking, 3_499) shouldBe OriginYardTick.WAITING
            f.yard.tick(f.player, drinking, 3_500) shouldBe OriginYardTick.CARED
            f.yard.sync(f.player, f.job.copy(stage = OriginOddJobStage.RETURN), emptyMap(), 3_501) shouldBe false
            f.sync(OriginOddJobStage.YARD_BUCKET_RETURN)
            f.yard.ownsAnimal(f.actor.id) shouldBe true
            f.sync(OriginOddJobStage.RETURN)
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            released shouldBe 1
        }
    }
    "ordinary native saddle stays equipped through leading and riding then restores exact authored metadata automatically" {
        for (completed in listOf(false, true)) YardFixture(initialSaddle = Material.SADDLE).use { f ->
            f.nativeSaddle.type shouldBe Material.AIR
            f.prepareDelivery()
            f.nativeSaddle shouldBe ItemStack(Material.SADDLE)
            f.traitSaddle shouldBe ItemStack(Material.SADDLE)
            if (completed) {
                f.location = Location(f.world, 67.5, 70.0, -122.5)
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                val ride = f.sync(OriginOddJobStage.YARD_HANDED)
                f.nativeSaddle shouldBe ItemStack(Material.SADDLE)
                f.arrive(); f.yard.tick(f.player, ride, 1) shouldBe OriginYardTick.CARED
                f.sync(OriginOddJobStage.RETURN)
                f.nativeSaddle shouldBe ItemStack(Material.SADDLE)
            }
            f.yard.revoke(f.player.uniqueId, f.job.id)
            f.nativeSaddle shouldBe f.originalSaddle
            f.traitSaddle shouldBe f.originalTraitSaddle
        }
    }
    "hay is private to its worker at the assigned horse muzzle and grazing begins without a feeding route" {
        for (id in listOf(422, 423)) YardFixture(OriginOddJobVariant.YARD_RATIONS, id).use { f ->
            val eating = f.job.copy(stage = OriginOddJobStage.YARD_EATING)
            f.yard.sync(f.player, eating, emptyMap(), 0) shouldBe true
            f.blocks.size shouldBe 1
            verify { f.renderer.spawnBlock(f.muzzleFloor(), any()) }
            verifyOrder { f.blocks.single().isVisibleByDefault = false; f.blocks.single().showTo(f.player) }
            verify(exactly = 1) { f.blocks.single().showTo(any<Player>()) }
            verify(exactly = 0) { f.blocks.single().isVisibleByDefault = true }
            verify { anyConstructed<OriginSceneResources>().setPose(f.actor, OriginScenePose.HORSE_GRAZE) }
            f.yard.tick(f.player, eating, 2_000) shouldBe OriginYardTick.WAITING
            f.yard.sync(f.player, eating, emptyMap(), 2_000) shouldBe true
            f.blocks.size shouldBe 1
            f.yard.tick(f.player, eating, 4_999) shouldBe OriginYardTick.WAITING
            f.yard.tick(f.player, eating, 5_000) shouldBe OriginYardTick.CARED
            verify(exactly = 1) { f.blocks.single().remove() }
            f.location shouldBe f.home
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "accepted feeding keeps only the assigned animal leased until background care completes" {
        for ((variant, id, duration) in listOf(Triple(OriginOddJobVariant.YARD_RATIONS, 422, 5_000L))) YardFixture(variant, id).use { f ->
            f.sync(if (variant == OriginOddJobVariant.YARD_RATIONS) OriginOddJobStage.YARD_FEED else OriginOddJobStage.YARD_WATER)
            f.sync(OriginOddJobStage.RETURN)
            val props = f.blocks.toList()
            props.isNotEmpty() shouldBe true
            verify { f.renderer.spawnBlock(f.muzzleFloor(), any()) }
            verify { anyConstructed<OriginSceneResources>().setPose(f.actor, OriginScenePose.HORSE_GRAZE) }
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            released shouldBe 0
            f.yard.ownsAnimal(id) shouldBe true
            val other = if (id == 422) OriginYardWork(423, "Рыжик") else OriginYardWork(422, "Буран")
            val offer = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, 0, false)
            f.yard.target(offer) shouldBe other
            f.yard.target(offer, other.animalId) shouldBe other
            f.now = duration - 1; f.scheduler.tick(4)
            props.all { it.isValid } shouldBe true
            released shouldBe 0
            f.now = duration; f.scheduler.tick(4)
            props.none { it.isValid } shouldBe true
            released shouldBe 1
            f.yard.ownsAnimal(id) shouldBe false
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
    "feeding and water require a supported clear muzzle floor before creating care props" {
        for (variant in listOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER)) {
            for (blocked in listOf(false, true)) YardFixture(variant).use { f ->
                val at = f.muzzleFloor()
                if (blocked) every { at.block.isPassable } returns false
                else every { at.clone().subtract(0.0, 0.05, 0.0).block.type } returns Material.AIR
                f.yard.sync(f.player, f.job.copy(stage = if (variant == OriginOddJobVariant.YARD_WATER)
                    OriginOddJobStage.YARD_DRINKING else OriginOddJobStage.RETURN), emptyMap(), 0) shouldBe false
                f.blocks shouldBe emptyList()
                verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            }
        }
    }
    "handoff mounts the separate rider and keeps the lease through automatic dismount and both home routes" {
        YardFixture().use { f ->
            f.prepareDelivery()
            f.location = Location(f.world, 67.5, 70.0, -122.5)
            f.sync(OriginOddJobStage.YARD_HANDOFF)
            f.sync(OriginOddJobStage.YARD_HANDED)
            f.controllableEnabled shouldBe true
            verify(exactly = 1) { f.horse.addPassenger(f.riderEntity) }
            verify { f.routes.navigate(f.actor, match { it.x == 67.5 && it.z == -122.5 }, any(), match { it.single().z == -119.5 }, any(), true, any()) }
            var released = 0
            f.yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_GROOM, 0, false)) shouldBe null
            f.yard.tick(f.player, f.job.copy(stage = OriginOddJobStage.YARD_HANDED), 1) shouldBe OriginYardTick.WAITING
            verify(exactly = 0) { f.riderEntity.leaveVehicle() }
            f.arrive()
            f.yard.tick(f.player, f.job.copy(stage = OriginOddJobStage.YARD_HANDED), 2) shouldBe OriginYardTick.CARED
            f.sync(OriginOddJobStage.RETURN)
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            released shouldBe 0
            verify(exactly = 1) { f.riderEntity.leaveVehicle() }
            f.controllablePresent shouldBe false
            verify { f.routes.navigate(f.riderActor, f.riderHome, any(), any(), any(), true, any()) }
            f.riderLocation = f.riderHome.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe 0
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe 1
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "rider controls are enabled before mounting and their prior state is restored on revocation" {
        for (initial in listOf<Boolean?>(null, false, true)) {
            YardFixture(initialControllable = initial).use { f ->
                f.prepareDelivery()
                f.location = Location(f.world, 67.5, 70.0, -122.5)
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                f.sync(OriginOddJobStage.YARD_HANDED)
                f.controllableEnabled shouldBe true
                f.tamed shouldBe true
                verifyOrder { f.horse.isTamed = true; f.controllable.setEnabled(true); f.horse.addPassenger(f.riderEntity) }
                // Citizens' horse adapter updates this attribute while the temporary trait is enabled.
                f.movementSpeed = 0.1
                f.yard.revoke(f.player.uniqueId, f.job.id)
                f.passengerVehicle shouldBe null
                f.controllablePresent shouldBe (initial != null)
                if (initial != null) f.controllableEnabled shouldBe initial
                f.movementSpeed shouldBe 0.2
                f.tamed shouldBe false
                verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            }
        }
    }
    "lesson keeps the instructor mounted and player leading through every checkpoint before both native returns" {
        YardFixture(OriginOddJobVariant.YARD_LESSON, rideAnchorIds = listOf("job-ride-turn", "job-ride-far")).use { f ->
            var walk = f.prepareLesson()
            f.controllableEnabled shouldBe true
            f.yard.workerLeading(f.player.uniqueId, f.actor.id) shouldBe true
            f.yard.leadDetached(f.player.uniqueId, walk) shouldBe false
            clearMocks(f.horse, answers = false)
            for ((index, destination) in f.lessonStops.withIndex()) {
                f.yard.destination(f.player.uniqueId, walk) shouldBe destination
                // The worker reaching a point cannot stand in for the actual horse.
                f.playerLocation = destination.clone()
                f.yard.tick(f.player, walk, index.toLong()) shouldBe OriginYardTick.WAITING
                f.location = destination.clone()
                f.yard.tick(f.player, walk, index.toLong()) shouldBe OriginYardTick.ARRIVED
                f.passengerVehicle shouldBe f.horse
                f.leashHolder shouldBe f.player
                val next = requireNotNull(walk.yard).copy(routeIndex = index + 1)
                walk = walk.copy(stage = if (next.routeIndex == next.routeTotal) OriginOddJobStage.RETURN else walk.stage, yard = next)
                f.yard.sync(f.player, walk, f.stations, index.toLong()) shouldBe true
            }
            f.passengerVehicle shouldBe null
            f.controllablePresent shouldBe false
            f.nativeAi shouldBe false
            f.nativeAware shouldBe false
            f.leashHolder shouldBe null
            verify(exactly = 0) { f.horse.addPassenger(any()) }
            verify(exactly = 1) { f.riderEntity.leaveVehicle() }
            verify(exactly = 1) { f.horse.setLeashHolder(null) }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            verify { f.routes.navigate(f.riderActor, f.riderHome, any(), any(), any(), true, any()) }
            released shouldBe 0
            f.riderLocation = f.riderHome.clone(); f.arrive(); f.scheduler.tick(4)
            verify { f.routes.navigate(f.actor, f.home, any(), match { it.single().z == -138.5 }, any(), true, any()) }
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe 1
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "lesson cannot skip checkpoints or finish at handoff before visiting the route" {
        YardFixture(OriginOddJobVariant.YARD_LESSON).use { f ->
            val walk = f.prepareLesson()
            // The horse starts at handoff, which is the final point, not the first.
            f.yard.tick(f.player, walk, 0) shouldBe OriginYardTick.WAITING
            val next = walk.copy(yard = requireNotNull(walk.yard).copy(routeIndex = 1))
            f.yard.sync(f.player, next, f.stations, 0) shouldBe false
            f.yard.tick(f.player, next, 0) shouldBe OriginYardTick.FAILED
            val skipped = walk.copy(stage = OriginOddJobStage.RETURN, yard = requireNotNull(walk.yard).copy(routeIndex = 2))
            f.yard.sync(f.player, skipped, f.stations, 0) shouldBe false
            f.yard.destination(f.player.uniqueId, walk) shouldBe f.lessonStops.first()
            f.passengerVehicle shouldBe f.horse
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
    "lesson waits after a native lead breaks until the worker explicitly re-grabs the mounted horse" {
        YardFixture(OriginOddJobVariant.YARD_LESSON).use { f ->
            val walk = f.prepareLesson()
            clearMocks(f.horse, answers = false)
            f.location = f.lessonStops.first().clone()
            f.leashHolder = null
            f.yard.leadDetached(f.player.uniqueId, walk) shouldBe true
            repeat(3) {
                f.yard.sync(f.player, walk, f.stations, it.toLong()) shouldBe true
                f.yard.tick(f.player, walk, it.toLong()) shouldBe OriginYardTick.WAITING
            }
            verify(exactly = 0) { f.horse.setLeashHolder(f.player) }
            f.yard.toggleWorkerLead(f.player, walk) shouldBe true
            f.leashHolder shouldBe f.player
            f.yard.tick(f.player, walk, 4) shouldBe OriginYardTick.ARRIVED
            f.yard.toggleWorkerLead(f.player, walk) shouldBe true
            f.yard.leadDetached(f.player.uniqueId, walk) shouldBe true
            f.yard.tick(f.player, walk, 5) shouldBe OriginYardTick.WAITING
            f.passengerVehicle shouldBe f.horse
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
    "lesson remounts the same nearby instructor without restarting checkpoints or overwriting originals" {
        for (initialTamed in listOf(false, true)) YardFixture(OriginOddJobVariant.YARD_LESSON,
            initialControllable = false, initialTamed = initialTamed).use { f ->
            var walk = f.prepareLesson()
            f.tamed shouldBe true
            verifyOrder { f.horse.isTamed = true; f.horse.addPassenger(f.riderEntity); f.horse.setAI(true) }
            f.location = f.lessonStops.first().clone()
            f.yard.tick(f.player, walk, 0) shouldBe OriginYardTick.ARRIVED
            walk = walk.copy(yard = requireNotNull(walk.yard).copy(routeIndex = 1))
            f.yard.sync(f.player, walk, f.stations, 1) shouldBe true
            f.location = f.lessonStops.last().clone()
            f.riderEntity.leaveVehicle()
            f.riderLocation.add(1.0, 0.0, 0.0)
            f.movementSpeed = 0.1
            clearMocks(f.horse, answers = false)
            f.yard.tick(f.player, walk, 2) shouldBe OriginYardTick.WAITING
            f.passengerVehicle shouldBe f.horse
            f.leashHolder shouldBe f.player
            f.yard.destination(f.player.uniqueId, walk) shouldBe f.lessonStops.last()
            val returned = walk.copy(stage = OriginOddJobStage.RETURN, yard = requireNotNull(walk.yard).copy(routeIndex = 2))
            f.yard.sync(f.player, returned, f.stations, 2) shouldBe false
            f.yard.tick(f.player, walk, 3) shouldBe OriginYardTick.ARRIVED
            f.yard.sync(f.player, returned, f.stations, 3) shouldBe true
            f.tamed shouldBe initialTamed
            f.movementSpeed shouldBe 0.2
            f.controllableEnabled shouldBe false
            f.nativeAi shouldBe false
            f.nativeAware shouldBe false
            f.leashHolder shouldBe null
            verify(exactly = 1) { f.horse.addPassenger(f.riderEntity) }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            verify(exactly = 0) { f.riderEntity.teleport(any<Location>()) }
        }
    }
    "lesson remount attempts are bounded for rejected mounts and repeated dismounts" {
        for (rejected in listOf(false, true)) YardFixture(OriginOddJobVariant.YARD_LESSON, initialControllable = false).use { f ->
            val walk = f.prepareLesson()
            f.riderEntity.leaveVehicle()
            f.movementSpeed = 0.1
            clearMocks(f.horse, answers = false)
            if (rejected) every { f.horse.addPassenger(f.riderEntity) } returns false
            repeat(3) { attempt ->
                f.yard.tick(f.player, walk, attempt.toLong()) shouldBe
                    if (rejected && attempt == 2) OriginYardTick.FAILED else OriginYardTick.WAITING
                f.yard.destination(f.player.uniqueId, walk) shouldBe f.lessonStops.first()
                f.tamed shouldBe true
                if (!rejected) f.riderEntity.leaveVehicle()
            }
            f.yard.tick(f.player, walk, 4) shouldBe OriginYardTick.FAILED
            verify(exactly = 3) { f.horse.addPassenger(f.riderEntity) }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            verify(exactly = 0) { f.riderEntity.teleport(any<Location>()) }
            f.yard.revoke(f.player.uniqueId, f.job.id)
            f.tamed shouldBe false
            f.movementSpeed shouldBe 0.2
            f.controllableEnabled shouldBe false
        }
    }
    "lesson rejects unsafe rider recovery and waits for a lead held by another player" {
        for (riderState in listOf("replacement", "extra", "vehicle", "world", "far", "obstructed", "lease", "other-holder")) {
            YardFixture(OriginOddJobVariant.YARD_LESSON).use { f ->
                val walk = f.prepareLesson()
                f.location = f.lessonStops.first().clone()
                clearMocks(f.horse, answers = false)
                val other = mockk<LivingEntity>(relaxed = true)
                when (riderState) {
                    "replacement" -> every { f.riderActor.entity } returns other
                    "extra" -> every { f.horse.passengers } returns listOf(f.riderEntity, other)
                    "vehicle" -> { f.riderEntity.leaveVehicle(); every { f.riderEntity.vehicle } returns other }
                    "world" -> {
                        f.riderEntity.leaveVehicle()
                        every { f.riderEntity.world } returns mockk { every { name } returns f.world.name; every { uid } returns UUID.randomUUID() }
                    }
                    "far" -> { f.riderEntity.leaveVehicle(); f.riderLocation.add(4.01, 0.0, 0.0) }
                    "obstructed" -> { f.riderEntity.leaveVehicle(); every { f.riderEntity.hasLineOfSight(f.horse) } returns false }
                    "lease" -> f.leaseValid = false
                    else -> f.leashHolder = mockk<Player> { every { uniqueId } returns UUID.randomUUID() }
                }
                f.yard.tick(f.player, walk, 0) shouldBe if (riderState == "other-holder") OriginYardTick.WAITING else OriginYardTick.FAILED
                verify(exactly = 0) { f.horse.addPassenger(any()) }
                verify(exactly = 0) { f.horse.isTamed = any() }
                verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
                verify(exactly = 0) { f.horse.teleport(any<Location>()) }
                verify(exactly = 0) { f.riderEntity.teleport(any<Location>()) }
            }
        }
    }
    "cancelling a mounted lesson restores originals and holds the lease through both home routes" {
        YardFixture(OriginOddJobVariant.YARD_LESSON, initialControllable = false).use { f ->
            f.metadata.setPersistent(NPC.Metadata.USE_MINECRAFT_AI, false)
            f.metadata.setPersistent(NPC.Metadata.LEASH_PROTECTED, true)
            f.prepareLesson()
            f.location = f.lessonStops.first().clone()
            f.movementSpeed = 0.1
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            f.passengerVehicle shouldBe null
            f.leashHolder shouldBe null
            f.nativeAi shouldBe false
            f.nativeAware shouldBe false
            f.controllableEnabled shouldBe false
            f.movementSpeed shouldBe 0.2
            f.tamed shouldBe false
            f.metadata.get<Boolean>(NPC.Metadata.USE_MINECRAFT_AI) shouldBe false
            f.metadata.get<Boolean>(NPC.Metadata.LEASH_PROTECTED) shouldBe true
            val saved = MemoryDataKey().also(f.metadata::saveTo)
            saved.keyExists(NPC.Metadata.USE_MINECRAFT_AI.key) shouldBe true
            saved.keyExists(NPC.Metadata.LEASH_PROTECTED.key) shouldBe true
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            verify { f.routes.navigate(f.riderActor, f.riderHome, any(), any(), any(), true, any()) }
            released shouldBe 0
            f.yard.ownsAnimal(f.actor.id) shouldBe true
            f.riderLocation = f.riderHome.clone(); f.arrive(); f.scheduler.tick(4)
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe 1
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "a rejected passenger mount restores temporary controls immediately" {
        for (initialTamed in listOf(false, true)) YardFixture(initialControllable = false, initialTamed = initialTamed).use { f ->
            f.prepareDelivery()
            f.sync(OriginOddJobStage.YARD_HANDOFF)
            every { f.horse.addPassenger(f.riderEntity) } returns false
            f.yard.sync(f.player, f.job.copy(stage = OriginOddJobStage.YARD_HANDED), f.stations, 0) shouldBe false
            f.controllablePresent shouldBe true
            f.controllableEnabled shouldBe false
            f.tamed shouldBe initialTamed
        }
    }
    "mounted despawn or entity replacement restores persistent NPC controls without touching either entity" {
        for ((replaced, initial) in listOf(false to false, true to null)) {
            YardFixture(initialControllable = initial).use { f ->
                f.prepareDelivery()
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                f.sync(OriginOddJobStage.YARD_HANDED)
                f.controllableEnabled shouldBe true
                var released = 0
                f.movementSpeed = 0.1
                val replacement = mockk<Horse>(relaxed = true) {
                    every { world } returns f.world
                    every { isValid } returns true
                    every { type } returns EntityType.HORSE
                }
                if (replaced) every { f.actor.entity } returns replacement
                else {
                    every { f.actor.isSpawned } returns false
                    every { f.horse.isValid } returns false
                }
                f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
                f.scheduler.tick(4)
                released shouldBe 1
                f.yard.ownsAnimal(f.actor.id) shouldBe false
                f.controllablePresent shouldBe (initial != null)
                if (initial != null) f.controllableEnabled shouldBe initial
                f.movementSpeed shouldBe 0.1
                f.tamed shouldBe true
                verify(exactly = 0) { replacement.getAttribute(any()) }
                verify(exactly = 0) { replacement.isTamed = any() }
                verify(exactly = 0) { f.horse.isTamed = false }
                verify(exactly = 0) { f.riderEntity.leaveVehicle() }
            }
        }
    }
    "mounted cleanup cannot restore controls after the lease or persistent NPC identity changes" {
        for (leaseLost in listOf(true, false)) {
            YardFixture(initialControllable = false).use { f ->
                f.prepareDelivery()
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                f.sync(OriginOddJobStage.YARD_HANDED)
                var released = 0
                f.movementSpeed = 0.1
                if (leaseLost) f.leaseValid = false
                else every { f.actor.uniqueId } returns UUID.randomUUID()
                f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
                f.scheduler.tick(4)
                released shouldBe 1
                f.controllablePresent shouldBe true
                f.controllableEnabled shouldBe true
                f.movementSpeed shouldBe 0.1
                f.tamed shouldBe true
                verify(exactly = 0) { f.horse.isTamed = false }
                verify(exactly = 0) { f.controllable.setEnabled(false) }
                verify(exactly = 0) { f.actor.removeTrait(Controllable::class.java) }
            }
        }
    }
    "a closed authored stall gate waits during leading and the owned return opens only that gate" {
        YardFixture(gates = true).use { f ->
            val lead = f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            f.playerLocation = f.home.clone().add(-1.0, 0.0, 1.0)
            f.yard.waitingForGate(f.player.uniqueId, lead) shouldBe true
            f.yard.gateTarget(f.player.uniqueId, lead) shouldBe Location(f.world, 72.5, 70.5, -136.5)
            f.yard.tick(f.player, lead, 1) shouldBe OriginYardTick.WAITING
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            val gate = f.world.getBlockAt(72, 70, -137)
            f.yard.isStallGate(gate) shouldBe true
            f.yard.isStallGate(f.world.getBlockAt(71, 70, -137)) shouldBe false
            f.prepareCare()
            f.yard.clear(f.player.uniqueId, f.job.id)
            f.gateOpen shouldBe true
            verify(exactly = 1) { gate.setBlockData(any(), true) }
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            f.gateOpen shouldBe true
        }
    }
    "native lead follows the holder without direction distance or route gates and arrives from the real horse position" {
        YardFixture().use { f ->
            val lead = f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            f.nativeAi shouldBe true
            f.nativeAware shouldBe true
            f.metadata.get<Boolean>(NPC.Metadata.USE_MINECRAFT_AI) shouldBe true
            f.metadata.get<Boolean>(NPC.Metadata.LEASH_PROTECTED) shouldBe false
            f.leashHolder shouldBe f.player
            for (at in listOf(f.home.clone().add(0.0, 0.0, 2.0), Location(f.world, 63.0, 70.0, -128.0))) {
                f.playerLocation = at
                f.yard.tick(f.player, lead, 1) shouldBe OriginYardTick.WAITING
            }
            f.location = ORIGIN_YARD_CARE_PAD.inWorld(f.world)
            f.yard.tick(f.player, lead, 2) shouldBe OriginYardTick.ARRIVED
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "a broken native lead waits for an explicit re-grab and never remotely reattaches" {
        YardFixture().use { f ->
            val lead = f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            f.leashHolder = null
            repeat(3) { f.yard.tick(f.player, lead, it.toLong()) shouldBe OriginYardTick.WAITING }
            verify(exactly = 1) { f.horse.setLeashHolder(f.player) }
            f.yard.toggleWorkerLead(f.player, lead) shouldBe true
            f.leashHolder shouldBe f.player
            verify(exactly = 2) { f.horse.setLeashHolder(f.player) }
            f.location = Location(f.world, 72.5, 70.0, -138.5)
            f.yard.tick(f.player, lead, 4) shouldBe OriginYardTick.WAITING
            f.yard.waitingForGate(f.player.uniqueId, lead) shouldBe false
        }
    }
    "native lead and temporary care hold restore original metadata presence persistence and AI on revocation" {
        for (persistent in listOf<Boolean?>(null, false, true)) {
            YardFixture().use { f ->
                if (persistent == true) {
                    f.metadata.setPersistent(NPC.Metadata.USE_MINECRAFT_AI, false)
                    f.metadata.setPersistent(NPC.Metadata.LEASH_PROTECTED, true)
                } else if (persistent == false) {
                    f.metadata.set(NPC.Metadata.USE_MINECRAFT_AI, false)
                    f.metadata.set(NPC.Metadata.LEASH_PROTECTED, true)
                }
                f.sync(OriginOddJobStage.YARD_LEAD_OUT)
                if (persistent == true) f.yard.revoke(f.player.uniqueId, f.job.id)
                else {
                    f.location = ORIGIN_YARD_CARE_PAD.inWorld(f.world)
                    f.sync(OriginOddJobStage.YARD_TIE_FIRST)
                    verifyOrder { f.horse.setAI(false); anyConstructed<OriginSceneResources>().hold(f.actor) }
                    f.metadata.has(NPC.Metadata.USE_MINECRAFT_AI) shouldBe true
                    f.metadata.get<Boolean>(NPC.Metadata.USE_MINECRAFT_AI) shouldBe false
                    MemoryDataKey().also(f.metadata::saveTo).keyExists(NPC.Metadata.USE_MINECRAFT_AI.key) shouldBe false
                    f.yard.revoke(f.player.uniqueId, f.job.id)
                }
                f.nativeAi shouldBe false
                f.nativeAware shouldBe false
                f.leashHolder shouldBe null
                val saved = MemoryDataKey().also(f.metadata::saveTo)
                for ((key, value) in listOf(NPC.Metadata.USE_MINECRAFT_AI to false, NPC.Metadata.LEASH_PROTECTED to true)) {
                    f.metadata.has(key) shouldBe (persistent != null)
                    if (persistent != null) f.metadata.get<Boolean>(key) shouldBe value
                    saved.keyExists(key.key) shouldBe (persistent == true)
                }
            }
        }
    }
    "native lead cleanup restores the same NPC metadata after despawn without mutating a replacement" {
        YardFixture().use { f ->
            f.metadata.setPersistent(NPC.Metadata.LEASH_PROTECTED, true)
            f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            val replacement = mockk<Horse>(relaxed = true) {
                every { world } returns f.world
                every { isValid } returns true
                every { type } returns EntityType.HORSE
            }
            every { f.actor.entity } returns replacement
            f.yard.revoke(f.player.uniqueId, f.job.id)
            f.metadata.has(NPC.Metadata.USE_MINECRAFT_AI) shouldBe false
            f.metadata.get<Boolean>(NPC.Metadata.LEASH_PROTECTED) shouldBe true
            verify(exactly = 0) { replacement.setAI(any()) }
            verify(exactly = 0) { replacement.isAware = any() }
        }
    }
    "native lead cleanup cannot reset another lease or NPC identity" {
        for (leaseLost in listOf(true, false)) YardFixture().use { f ->
            f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            if (leaseLost) f.leaseValid = false else every { f.actor.uniqueId } returns UUID.randomUUID()
            f.yard.revoke(f.player.uniqueId, f.job.id)
            f.nativeAi shouldBe true
            f.metadata.get<Boolean>(NPC.Metadata.USE_MINECRAFT_AI) shouldBe true
            f.metadata.get<Boolean>(NPC.Metadata.LEASH_PROTECTED) shouldBe false
            verify(exactly = 0) { f.horse.setAI(false) }
        }
    }
    "native saddle restoration precedes bounded home recovery and the lease stays held until arrival" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            f.prepareDelivery()
            f.nativeSaddle.type shouldBe Material.SADDLE
            var releases = 0
            f.yard.clear(f.player.uniqueId, UUID.randomUUID())
            verify(exactly = 1) { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            f.yard.clear(f.player.uniqueId, f.job.id) { releases++ }
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            releases shouldBe 0
            f.yard.ownsAnimal(423) shouldBe true
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            releases shouldBe 1
            f.yard.ownsAnimal(423) shouldBe false
            f.yard.close()
            releases shouldBe 1
        }
    }
    "an ended horse return releases at its real home without waiting for an old route callback" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            var releases = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { releases++ }
            releases shouldBe 0
            f.location = f.home.clone()
            f.navigating shouldBe true
            f.scheduler.tick(4)
            releases shouldBe 1
            f.yard.ownsAnimal(f.actor.id) shouldBe false
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "a failed lease release retains its owner and retries before discarding recovery" {
        YardFixture(OriginOddJobVariant.YARD_RATIONS).use { f ->
            var attempts = 0
            f.yard.clear(f.player.uniqueId, f.job.id) {
                if (++attempts == 1) error("temporary lease release failure")
            }
            attempts shouldBe 1
            f.yard.ownsAnimal(f.actor.id) shouldBe true
            f.scheduler.tick(4)
            attempts shouldBe 2
            f.yard.ownsAnimal(f.actor.id) shouldBe false
            f.scheduler.tick(40)
            attempts shouldBe 2
        }
    }
    "a release failure after ownership changed never restores or stops the replacement owner" {
        YardFixture(OriginOddJobVariant.YARD_RATIONS).use { f ->
            var attempts = 0
            f.yard.clear(f.player.uniqueId, f.job.id) {
                attempts++
                f.leaseValid = false
                error("release acknowledgement failed after ownership ended")
            }
            clearMocks(f.routes, answers = false)
            f.scheduler.tick(40)
            attempts shouldBe 1
            f.yard.ownsAnimal(f.actor.id) shouldBe false
            verify(exactly = 0) { f.routes.stop(any()) }
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "three transient release failures keep retrying slowly instead of leaving the horse busy forever" {
        YardFixture(OriginOddJobVariant.YARD_RATIONS).use { f ->
            var attempts = 0
            f.yard.clear(f.player.uniqueId, f.job.id) {
                if (++attempts <= 3) error("temporary lease release failure")
            }
            f.scheduler.tick(8)
            attempts shouldBe 3
            f.yard.ownsAnimal(f.actor.id) shouldBe true
            f.scheduler.tick(19)
            attempts shouldBe 3
            f.scheduler.tick()
            attempts shouldBe 4
            f.yard.ownsAnimal(f.actor.id) shouldBe false
            f.scheduler.tick(40)
            attempts shouldBe 4
        }
    }
    "blocked recovery and replaced NPC identity stop safely without teleporting or holding the lease" {
        YardFixture().use { f ->
            f.location = Location(f.world, 60.5, 70.0, -124.5)
            every { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) } returns false
            var released = false
            f.yard.clear(f.player.uniqueId, f.job.id) { released = true }
            released shouldBe true
            f.yard.ownsAnimal(423) shouldBe false
            every { f.actor.uniqueId } returns UUID.randomUUID()
            f.yard.resolve(423) shouldBe null
            f.yard.begin(f.player, f.job) shouldBe false
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "failed return cannot offer or begin work outside the stall while the other horse stays available" {
        YardFixture(id = 422).use { f ->
            f.location = Location(f.world, 60.0113, 70.0, -127.4948)
            every { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) } returns false
            var released = false
            f.yard.clear(f.player.uniqueId, f.job.id) { released = true }
            released shouldBe true
            f.yard.ownsAnimal(422) shouldBe false
            for (variant in listOf(OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE, OriginOddJobVariant.YARD_WATER)) {
                f.yard.target(OriginOddJobOffer(UUID.randomUUID(), variant, 0, false)) shouldBe OriginYardWork(423, "Рыжик")
            }
            val hay = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, 0, false)
            f.yard.target(hay) shouldBe OriginYardWork(423, "Рыжик")
            f.yard.begin(f.player, f.job) shouldBe false
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            f.location = f.home.clone()
            f.yard.target(hay) shouldBe OriginYardWork(422, "Буран")
            f.yard.begin(f.player, f.job) shouldBe true
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
            f.prepareCare()
            clearMocks(f.routes, answers = false)
            f.yard.revoke(f.player.uniqueId, f.job.id)
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            verify { anyConstructed<OriginSceneResources>().cleanup() }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            f.yard.ownsAnimal(423) shouldBe false
            f.scheduler.pendingCount() shouldBe 0
        }
    }
    "preparation holds calmly through four private tie steps and releases ropes automatically with the native saddle" {
        YardFixture(initialAi = true, initialAware = true).use { f ->
            f.nativeAi shouldBe true
            f.nativeAware shouldBe true
            verify(exactly = 0) { anyConstructed<OriginSceneResources>().hold(any()) }
            val lead = f.sync(OriginOddJobStage.YARD_LEAD_OUT)
            f.yard.sync(f.player, lead.copy(stage = OriginOddJobStage.YARD_TIE_FIRST), f.stations, 0) shouldBe false
            f.location = ORIGIN_YARD_CARE_PAD.inWorld(f.world)
            for (stage in listOf(OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_HORSE_FIRST,
                OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_TIE_HORSE_SECOND,
                OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM,
                OriginOddJobStage.YARD_BRUSH_RETURN, OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_SADDLE)) {
                val preparation = f.sync(stage)
                f.nativeAi = true; f.nativeAware = true; f.lookCloseEnabled = true
                f.jumping = true; f.rearing = true; f.eating = true; f.grazing = true
                f.velocity = Vector(1.0, 0.5, 1.0)
                f.scheduler.tick()
                f.nativeAi shouldBe false
                f.nativeAware shouldBe false
                f.lookCloseEnabled shouldBe false
                f.collidable shouldBe false
                f.gravity shouldBe false
                f.jumping shouldBe false
                f.rearing shouldBe false
                f.eating shouldBe false
                f.grazing shouldBe false
                f.velocity shouldBe Vector()
                f.yard.readyForCare(f.player.uniqueId, preparation) shouldBe true
                f.yard.tick(f.player, preparation, 0) shouldBe OriginYardTick.WAITING
                val expectedSides = when(stage) {
                    OriginOddJobStage.YARD_TIE_FIRST -> listOf(0,1)
                    OriginOddJobStage.YARD_TIE_SECOND -> listOf(1)
                    else -> emptyList()
                }
                f.yard.tieStations(f.player.uniqueId, preparation).map { it.first } shouldBe expectedSides
                (f.yard.station(f.player.uniqueId, preparation) != null) shouldBe expectedSides.isNotEmpty()
                f.blocks.filter { it.isValid }.forEach { display ->
                    verify { display.isVisibleByDefault = false; display.showTo(f.player) }
                }
            }
            f.nativeSaddle.type shouldBe Material.AIR
            val deliver = f.sync(OriginOddJobStage.YARD_DELIVER)
            f.nativeSaddle shouldBe ItemStack(Material.SADDLE)
            f.nativeAi shouldBe true
            f.nativeAware shouldBe true
            f.collidable shouldBe true
            f.gravity shouldBe true
            f.lookCloseEnabled shouldBe true
            f.leashHolder shouldBe f.player
            f.yard.readyForCare(f.player.uniqueId, deliver) shouldBe false
            f.scheduler.pendingCount() shouldBe 0
            f.blocks.isNotEmpty() shouldBe true
            f.blocks.none { it.isValid } shouldBe true
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            verify { anyConstructed<OriginSceneResources>().faceHorizontal(f.actor, match { it.yaw == 180f }, 0f) }
        }
    }
    "retired extra tack and manual untacking stages reject without creating displays or freezing an idle horse" {
        YardFixture(initialAi = true, initialAware = true).use { f ->
            for (stage in listOf(OriginOddJobStage.YARD_UNTIE_FIRST, OriginOddJobStage.YARD_UNTIE_SECOND,
                OriginOddJobStage.YARD_PAD_RACK, OriginOddJobStage.YARD_PAD, OriginOddJobStage.YARD_GIRTH,
                OriginOddJobStage.YARD_BRIDLE_RACK, OriginOddJobStage.YARD_BRIDLE,
                OriginOddJobStage.YARD_STIRRUP_LEFT, OriginOddJobStage.YARD_STIRRUP_RIGHT,
                OriginOddJobStage.YARD_UNTACK_BRIDLE, OriginOddJobStage.YARD_BRIDLE_RETURN,
                OriginOddJobStage.YARD_UNTACK_SADDLE, OriginOddJobStage.YARD_SADDLE_RETURN,
                OriginOddJobStage.YARD_UNTACK_PAD, OriginOddJobStage.YARD_PAD_RETURN, OriginOddJobStage.YARD_LEAD_RETURN)) {
                val retired = f.job.copy(stage = stage)
                f.yard.sync(f.player, retired, f.stations, 0) shouldBe false
                f.yard.tick(f.player, retired, 0) shouldBe OriginYardTick.FAILED
                f.yard.readyForUntack(f.player.uniqueId, retired) shouldBe false
            }
            f.nativeAi shouldBe true
            f.nativeAware shouldBe true
            f.blocks shouldBe emptyList()
            f.scheduler.pendingCount() shouldBe 0
            verify(exactly = 0) { anyConstructed<OriginSceneResources>().hold(any()) }
        }
    }
    "preparation cancellation restores native and Citizens state before home recovery and stops the hold timer" {
        YardFixture(initialAi = true, initialAware = true, initialSaddle = Material.SADDLE).use { f ->
            f.metadata.setPersistent(NPC.Metadata.USE_MINECRAFT_AI, true)
            f.prepareCare()
            var released = false
            f.yard.clear(f.player.uniqueId, f.job.id) { released = true }
            f.blocks.none { it.isValid } shouldBe true
            f.nativeAi shouldBe true
            f.nativeAware shouldBe true
            f.collidable shouldBe true
            f.gravity shouldBe true
            f.lookCloseEnabled shouldBe true
            f.nativeSaddle shouldBe f.originalSaddle
            f.traitSaddle shouldBe f.originalTraitSaddle
            f.metadata.get<Boolean>(NPC.Metadata.USE_MINECRAFT_AI) shouldBe true
            MemoryDataKey().also(f.metadata::saveTo).keyExists(NPC.Metadata.USE_MINECRAFT_AI.key) shouldBe true
            clearMocks(f.horse, answers = false)
            f.scheduler.tick(3)
            verify(exactly = 0) { f.horse.isRearing = any() }
            released shouldBe false
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe true
        }
    }
    "displaced horse cannot be groomed and fails without teleporting it back onto the pad" {
        YardFixture().use { f ->
            val preparation = f.prepareCare()
            f.location.add(0.3, 0.0, 0.0)
            f.yard.readyForCare(f.player.uniqueId, preparation) shouldBe false
            f.yard.tick(f.player, preparation, 1) shouldBe OriginYardTick.FAILED
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
    }
    "preparation revocation restores persistent saddle and look state after entity replacement without mutating either horse" {
        YardFixture(initialSaddle = Material.SADDLE).use { f ->
            f.prepareCare()
            val replacement = mockk<Horse>(relaxed = true) {
                every { world } returns f.world
                every { isValid } returns true
                every { type } returns EntityType.HORSE
            }
            every { f.actor.entity } returns replacement
            clearMocks(f.horse, f.equipment, answers = false)
            f.yard.revoke(f.player.uniqueId, f.job.id)
            f.traitSaddle shouldBe f.originalTraitSaddle
            f.lookCloseEnabled shouldBe true
            f.scheduler.pendingCount() shouldBe 0
            verify(exactly = 0) { f.equipment.setItem(any(), any()) }
            verify(exactly = 0) { f.horse.setAI(any()) }
            verify(exactly = 0) { replacement.setAI(any()) }
            verify(exactly = 0) { replacement.equipment }
        }
    }
    "preparation hold stops mutating an actor when its lease is lost and reports native hold failure" {
        for (lostLease in listOf(false, true)) YardFixture().use { f ->
            val preparation = f.prepareCare()
            clearMocks(f.horse, answers = false)
            if (lostLease) f.leaseValid = false
            else every { f.horse.isRearing = false } throws IllegalStateException("native hold failed")
            f.scheduler.tick(3)
            f.yard.tick(f.player, preparation, 0) shouldBe OriginYardTick.FAILED
            verify(exactly = if (lostLease) 0 else 1) { f.horse.isRearing = false }
            f.scheduler.pendingCount() shouldBe 0
        }
    }
    "arrival removes the worker leash before the separate rider accepts the prepared horse" {
        YardFixture().use { f ->
            val deliver = f.prepareDelivery()
            clearMocks(f.horse, answers = false)
            f.playerLocation = Location(f.world, 60.5, 70.0, -128.5)
            f.yard.tick(f.player, deliver, 1) shouldBe OriginYardTick.WAITING
            f.leashHolder shouldBe f.player
            f.location = Location(f.world, 67.5, 70.0, -122.5)
            f.playerLocation = f.location.clone().add(-1.0, 0.0, 0.0)
            f.arrive()
            f.yard.tick(f.player, deliver, 2) shouldBe OriginYardTick.ARRIVED
            f.yard.sync(f.player, deliver.copy(stage = OriginOddJobStage.YARD_HANDOFF), f.stations, 3) shouldBe true
            verify(exactly = 1) { f.horse.setLeashHolder(null) }
        }
    }
})

private val testYardRider = OriginYardRider(490, UUID.fromString("00000000-0000-0000-0000-000000000490"), "Мирон")

private class YardFixture(variant: OriginOddJobVariant = OriginOddJobVariant.YARD_SADDLE, id: Int = 423, gates: Boolean = false, startJob: Boolean = true,
    initialControllable: Boolean? = null, initialTamed: Boolean = false, initialSaddle: Material = Material.AIR, rideAnchorIds: List<String> = listOf("job-ride-turn"),
    initialAi: Boolean = false, initialAware: Boolean = false, careAnimals: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val scheduler = TestTaskScheduler()
    val world = mockk<World>(relaxed = true) { every { uid } returns UUID.randomUUID(); every { name } returns "rc_origin_spawn" }
    val home = Location(world, if (id == 422) 47.5 else 72.5, 70.0, if (id == 422) -136.5 else -134.5)
    var location = home.clone()
    var now = 0L
    var gateOpen = !gates
    private val gateData = mockk<Gate>(relaxed = true) {
        every { isOpen } answers { gateOpen }
        every { isOpen = any() } answers { gateOpen = firstArg() }
    }
    var passengerVehicle: Entity? = null
    val riderHome = Location(world, 67.5, 70.0, -120.5)
    var riderLocation = riderHome.clone()
    val riderEntity = mockk<LivingEntity>(relaxed = true) {
        every { world } returns this@YardFixture.world
        every { isValid } returns true
        every { vehicle } answers { passengerVehicle }
        every { passengers } returns emptyList()
        every { hasLineOfSight(any<Entity>()) } returns true
        every { location } answers { if (passengerVehicle != null) this@YardFixture.location.clone() else riderLocation.clone() }
        every { leaveVehicle() } answers { riderLocation = this@YardFixture.location.clone(); passengerVehicle = null; true }
    }
    val riderActor = mockk<NPC> {
        every { this@mockk.id } returns testYardRider.npcId
        every { uniqueId } returns testYardRider.npcUuid
        every { isSpawned } returns true
        every { entity } returns riderEntity
    }
    var playerLocation = home.clone().add(-1.0, 0.0, 0.0)
    val originalSaddle = ItemStack(initialSaddle).also { if (!it.type.isAir) it.editMeta { meta ->
        meta.displayName(net.kyori.adventure.text.Component.text("Authored saddle")) } }
    var nativeSaddle = originalSaddle.clone()
    val originalTraitSaddle = originalSaddle.clone().also { if (!it.type.isAir) it.editMeta { meta ->
        meta.displayName(net.kyori.adventure.text.Component.text("Citizens saddle")) } }
    var traitSaddle: ItemStack? = originalTraitSaddle.clone()
    val horseModifiers = mockk<HorseModifiers> {
        every { saddle } answers { traitSaddle?.clone() }
        every { setSaddle(any()) } answers { traitSaddle = firstArg<ItemStack?>()?.clone() }
    }
    var lookCloseEnabled = true
    val lookClose = mockk<LookClose> {
        every { isEnabled } answers { lookCloseEnabled }
        every { lookClose(any()) } answers { lookCloseEnabled = firstArg() }
    }
    val equipment = mockk<EntityEquipment>(relaxed = true) {
        every { getItem(EquipmentSlot.SADDLE) } answers { nativeSaddle.clone() }
        every { setItem(EquipmentSlot.SADDLE, any()) } answers { nativeSaddle = secondArg<ItemStack>().clone() }
    }
    var nativeAi = initialAi
    var nativeAware = initialAware
    var collidable = true
    var gravity = true
    var jumping = false
    var rearing = false
    var eating = false
    var grazing = false
    var velocity = Vector()
    var tamed = initialTamed
    var leashHolder: Entity? = null
    val metadata = SimpleMetadataStore()
    var movementSpeed = 0.2
    private val movementAttribute = mockk<AttributeInstance> {
        every { baseValue } answers { movementSpeed }
        every { baseValue = any() } answers { movementSpeed = firstArg() }
    }
    val horse = mockk<Horse>(relaxed = true) {
        every { this@mockk.world } returns this@YardFixture.world
        every { type } returns EntityType.HORSE
        every { isValid } returns true
        every { location } answers { this@YardFixture.location.clone() }
        every { this@mockk.equipment } returns this@YardFixture.equipment
        every { getAttribute(Attribute.MOVEMENT_SPEED) } returns movementAttribute
        every { passengers } answers { if (passengerVehicle != null) listOf(riderEntity) else emptyList() }
        every { addPassenger(riderEntity) } answers { passengerVehicle = this@mockk; true }
        every { hasAI() } answers { nativeAi }
        every { setAI(any()) } answers { nativeAi = firstArg() }
        every { isAware } answers { nativeAware }
        every { isAware = any() } answers { nativeAware = firstArg() }
        every { isCollidable } answers { collidable }
        every { isCollidable = any() } answers { collidable = firstArg() }
        every { hasGravity() } answers { gravity }
        every { setGravity(any()) } answers { gravity = firstArg() }
        every { isJumping } answers { jumping }
        every { isJumping = any() } answers { jumping = firstArg() }
        every { isRearing } answers { rearing }
        every { isRearing = any() } answers { rearing = firstArg() }
        every { isEating } answers { eating }
        every { isEating = any() } answers { eating = firstArg() }
        every { isEatingGrass } answers { grazing }
        every { isEatingGrass = any() } answers { grazing = firstArg() }
        every { this@mockk.velocity } answers { this@YardFixture.velocity.clone() }
        every { this@mockk.velocity = any() } answers { this@YardFixture.velocity = firstArg<Vector>().clone() }
        every { isTamed } answers { tamed }
        every { isTamed = any() } answers { tamed = firstArg() }
        every { isLeashed } answers { this@YardFixture.leashHolder != null }
        every { leashHolder } answers { requireNotNull(this@YardFixture.leashHolder) }
        every { height } returns 1.6
        every { setLeashHolder(any()) } answers { this@YardFixture.leashHolder = firstArg(); true }
    }
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { world } returns this@YardFixture.world
        every { location } answers { playerLocation.clone() }
        every { height } returns 1.8
    }
    val catalog = oddJobTestYardCatalog()
    var controllablePresent = initialControllable != null
    var controllableEnabled = initialControllable ?: false
    val controllable = mockk<Controllable> {
        every { isEnabled } answers { controllableEnabled }
        every { setEnabled(any()) } answers { controllableEnabled = firstArg(); controllableEnabled }
    }
    val actor = mockk<NPC> {
        every { this@mockk.id } returns id
        every { uniqueId } returns catalog.animals.first { it.npcId == id }.npcUuid
        every { isSpawned } returns true
        every { entity } returns horse
        every { data() } returns metadata
        every { getTraitNullable(Controllable::class.java) } answers { if (controllablePresent) controllable else null }
        every { getOrAddTrait(Controllable::class.java) } answers { controllablePresent = true; controllable }
        every { removeTrait(Controllable::class.java) } answers { controllablePresent = false }
        every { getTraitNullable(HorseModifiers::class.java) } returns horseModifiers
        every { getTraitNullable(LookClose::class.java) } returns lookClose
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
    val materials = mutableMapOf<PacketBlockDisplay, Material>()
    val renderer = mockk<PaperPacketDisplays>(relaxed = true) {
        every { spawnBlock(any(), any()) } answers {
            val material = secondArg<BlockData>().material
            mockk<PacketBlockDisplay>(relaxed = true).also { display ->
                var valid = true
                blocks += display; materials[display] = material
                every { display.isValid } answers { valid }
                every { display.remove() } answers { valid = false }
            }
        }
    }
    val stations = mapOf(OriginOddJobStationId.FEED to OriginOddJobStation(Location(world, 48.5, 70.02, -134.5), mockk(), setOf(Material.PACKED_MUD)))
    val job = OriginOddJobsProgress(240_000).begin(player.uniqueId, OriginOddJobOffer(UUID.randomUUID(), variant, 8, false), 0,
        yardTarget = OriginYardWork(id, catalog.animals.first { it.npcId == id }.name,
            routeTotal = if (variant == OriginOddJobVariant.YARD_LESSON) rideAnchorIds.size + 1 else 0))!!
    val lessonStops = rideAnchorIds.map { key -> when (key) {
        "job-ride-far" -> Location(world, 53.5, 70.0, -113.5)
        else -> Location(world, 59.5, 70.0, -119.5)
    } } + Location(world, 67.5, 70.0, -122.5)
    val yard: OriginOddJobMountyard
    init {
        mockkStatic(CitizensAPI::class)
        mockkConstructor(OriginSceneResources::class)
        every { anyConstructed<OriginSceneResources>().clearMovementPose(any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().setPose(any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().faceHorizontal(any(), any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().cleanup() } returns emptyList()
        every { anyConstructed<OriginSceneResources>().hold(any()) } answers { callOriginal() }
        every { anyConstructed<OriginSceneResources>().releaseHold(any()) } answers { callOriginal() }
        every { world.isChunkLoaded(any(), any()) } returns true
        val supports = mutableMapOf<Triple<Int, Int, Int>, Block>()
        fun blockAt(x: Int, y: Int, z: Int): Block = supports.getOrPut(Triple(x, y, z)) {
            mockk<Block>(relaxed = true) {
                every { type } returns when {
                    y == 69 -> Material.STONE
                    x in setOf(55, 64) && y == 71 && z == -128 -> Material.STONE_BRICKS
                    x == 72 && y == 70 && z == -137 -> Material.OAK_FENCE_GATE
                    else -> Material.AIR
                }
                every { isPassable } answers { type.isAir }
                every { blockData } returns if (x == 72 && y == 70 && z == -137) gateData else mockk(relaxed = true)
                every { this@mockk.world } returns this@YardFixture.world
                every { this@mockk.x } returns x; every { this@mockk.y } returns y; every { this@mockk.z } returns z
                every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
            }
        }
        every { world.getBlockAt(any<Int>(), any(), any()) } answers { blockAt(firstArg(), secondArg(), thirdArg()) }
        every { world.getBlockAt(any<Location>()) } answers { firstArg<Location>().let { blockAt(it.blockX, it.blockY, it.blockZ) } }
        every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns null
        val otherId = if (id == 422) 423 else 422
        val otherHome = OriginScenePoint(if (otherId == 422) 47.5 else 72.5, 70.0, if (otherId == 422) -136.5 else -134.5)
        val otherHorse = mockk<Horse> {
            every { world } returns this@YardFixture.world
            every { type } returns EntityType.HORSE
            every { isValid } returns true
            every { location } answers { otherHome.inWorld(this@YardFixture.world) }
            every { isLeashed } returns false
            every { passengers } returns emptyList()
        }
        val otherActor = mockk<NPC> {
            every { this@mockk.id } returns otherId
            every { uniqueId } returns catalog.animals.first { it.npcId == otherId }.npcUuid
            every { isSpawned } returns true
            every { entity } returns otherHorse
        }
        val extraHomes = listOf(442, 445, 446).associateWith { OriginScenePoint(60.5 + (it - 442), 70.0, -140.5) }
        val extraActors = if (careAnimals) extraHomes.mapValues { (extraId, at) ->
            val animal = mockk<Mob>(relaxed = true) {
                every { world } returns this@YardFixture.world
                every { type } returns EntityType.valueOf(catalog.animals.first { it.npcId == extraId }.entityType)
                every { isValid } returns true
                every { location } answers { at.inWorld(this@YardFixture.world) }
                every { isLeashed } returns false
                every { passengers } returns emptyList()
            }
            mockk<NPC> {
                every { this@mockk.id } returns extraId
                every { uniqueId } returns catalog.animals.first { it.npcId == extraId }.npcUuid
                every { isSpawned } returns true
                every { entity } returns animal
            }
        } else emptyMap()
        val registry = mockk<NPCRegistry> { every { getById(any()) } answers { when (firstArg<Int>()) {
            id -> actor
            otherId -> otherActor
            testYardRider.npcId -> riderActor
            else -> extraActors[firstArg()]
        } } }
        every { CitizensAPI.getNPCRegistry() } returns registry
        val scene = OriginSceneDefinition("mount-yard", ORIGIN_YARD_CARE_PAD, 72.0, 3_000, 7,
            mapOf(id to OriginSceneActor(id, OriginScenePoint(home.x, home.y, home.z), emptySet()),
                otherId to OriginSceneActor(otherId, otherHome, emptySet())) +
                extraActors.keys.associateWith { OriginSceneActor(it, extraHomes.getValue(it), emptySet()) },
            mapOf("job-rider-home" to OriginScenePoint(riderHome.x, riderHome.y, riderHome.z),
                "job-handoff" to OriginScenePoint(67.5, 70.0, -122.5),
                "job-ride-turn" to OriginScenePoint(59.5, 70.0, -119.5),
                "job-ride-far" to OriginScenePoint(53.5, 70.0, -113.5),
                "job-stall-$id-exit" to if (id == 422) OriginScenePoint(52.5, 70.0, -136.5) else OriginScenePoint(72.5, 70.0, -138.5),
                "job-stall-423-gate-1" to OriginScenePoint(72.0, 70.0, -137.0)), emptyMap(), emptyMap(), emptyList())
        yard = OriginOddJobMountyard(world.name, catalog, listOf(422, 423) + extraActors.keys, testYardRider, scene, routes = routes,
            tasks = LifecycleTaskScope(scheduler), createDisplays = { renderer }, rideAnchorIds = rideAnchorIds,
            gateAnchorIds = if (gates) listOf("job-stall-423-gate-1") else emptyList(), clock = { now })
        if (startJob) yard.begin(player, job) { leaseValid } shouldBe true
    }
    fun sync(stage: OriginOddJobStage): OriginOddJobSession {
        val mask = when(stage) {
            OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_TIE_HORSE_SECOND -> 1
            OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_BRUSH_RETURN,
            OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_SADDLE -> 3
            else -> 0
        }
        val pending = when(stage) { OriginOddJobStage.YARD_TIE_HORSE_FIRST -> 0; OriginOddJobStage.YARD_TIE_HORSE_SECOND -> 1; else -> null }
        return job.copy(stage = stage, yard = requireNotNull(job.yard).copy(tieMask = mask, pendingTieSide = pending, equipped = if (stage in setOf(
            OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_HANDOFF, OriginOddJobStage.YARD_HANDED,
            OriginOddJobStage.YARD_LESSON_WALK)) setOf(OriginYardGear.SADDLE) else emptySet()))
            .also { yard.sync(player, it, stations, 0) shouldBe true }
    }
    fun prepareCare(): OriginOddJobSession {
        sync(OriginOddJobStage.YARD_LEAD_OUT)
        location = ORIGIN_YARD_CARE_PAD.inWorld(world)
        listOf(OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_HORSE_FIRST,
            OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_TIE_HORSE_SECOND,
            OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM,
            OriginOddJobStage.YARD_BRUSH_RETURN, OriginOddJobStage.YARD_TACK).forEach(::sync)
        return sync(OriginOddJobStage.YARD_SADDLE)
    }
    fun prepareDelivery(): OriginOddJobSession {
        prepareCare()
        return sync(OriginOddJobStage.YARD_DELIVER)
    }
    fun prepareLesson(): OriginOddJobSession {
        prepareDelivery()
        location = Location(world, 67.5, 70.0, -122.5)
        sync(OriginOddJobStage.YARD_HANDOFF)
        return sync(OriginOddJobStage.YARD_LESSON_WALK)
    }
    fun muzzleFloor(): Location = location.clone().add(location.direction.setY(0).normalize().multiply(1.1)).add(0.0, 0.02, 0.0)
    fun arrive(success: Boolean = true) { navigating = false; outcome = NpcRouteOutcome(success, if (success) "FINISHED" else "STALLED", null) }
    override fun close() {
        yard.close()
        unmockkConstructor(OriginSceneResources::class)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
}
