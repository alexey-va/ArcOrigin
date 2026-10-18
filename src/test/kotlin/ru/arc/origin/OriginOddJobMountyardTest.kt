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
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Gate
import org.bukkit.util.BoundingBox
import org.bukkit.entity.EntityType
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
    "all work uses stall horses and both preparation variants rotate through the same pool" {
        YardFixture(startJob = false).use { f ->
            val yard = f.yard
            repeat(4) { seed ->
                yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, seed, false)) shouldBe OriginYardWork(422, "Буран")
                val selected = if (seed % 2 == 0) OriginYardWork(422, "Буран") else OriginYardWork(423, "Рыжик")
                yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_SADDLE, seed, false)) shouldBe selected
                yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_GROOM, seed, false)) shouldBe selected
            }
            (0..3).map { seed -> yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_WATER, seed, false))!!.animalId } shouldBe listOf(422, 423, 422, 423)
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
    "accepted hay finishes in the background and keeps only its animal unavailable until it returns" {
        YardFixture(OriginOddJobVariant.YARD_RATIONS, 422).use { f ->
            f.sync(OriginOddJobStage.YARD_FEED)
            f.sync(OriginOddJobStage.RETURN)
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            released shouldBe 0
            f.yard.ownsAnimal(422) shouldBe true
            f.yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_SADDLE, 0, false)) shouldBe OriginYardWork(423, "Рыжик")
            f.yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, 0, false)) shouldBe null
            f.location = Location(f.world, 48.5, 70.0, -135.5)
            f.arrive(); f.now = 1_000; f.scheduler.tick(4)
            verify(exactly = 0) { f.blocks.single().remove() }
            f.now = 6_000; f.scheduler.tick(4)
            verify(exactly = 1) { f.blocks.single().remove() }
            released shouldBe 0
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe 1
            f.yard.ownsAnimal(422) shouldBe false
        }
    }
    "handoff mounts the separate rider and keeps the lease through the ride dismount and both returns" {
        YardFixture().use { f ->
            f.prepareDelivery()
            f.location = Location(f.world, 67.5, 70.0, -122.5)
            f.sync(OriginOddJobStage.YARD_HANDOFF)
            f.sync(OriginOddJobStage.RETURN)
            f.controllableEnabled shouldBe true
            verify(exactly = 1) { f.horse.addPassenger(f.riderEntity) }
            verify { f.routes.navigate(f.actor, match { it.x == 67.5 && it.z == -122.5 }, any(), match { it.single().z == -119.5 }, any(), true, any()) }
            var released = 0
            f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
            f.yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_GROOM, 0, false)) shouldBe null
            f.yard.target(OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_WATER, 0, false)) shouldBe OriginYardWork(422, "Буран")
            f.scheduler.tick(4)
            released shouldBe 0
            verify(exactly = 0) { f.riderEntity.leaveVehicle() }
            f.arrive(); f.scheduler.tick(4)
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
                f.sync(OriginOddJobStage.RETURN)
                f.controllableEnabled shouldBe true
                verifyOrder { f.controllable.setEnabled(true); f.horse.addPassenger(f.riderEntity) }
                // Citizens' horse adapter updates this attribute while the temporary trait is enabled.
                f.movementSpeed = 0.1
                f.yard.revoke(f.player.uniqueId, f.job.id)
                f.passengerVehicle shouldBe null
                f.controllablePresent shouldBe (initial != null)
                if (initial != null) f.controllableEnabled shouldBe initial
                f.movementSpeed shouldBe 0.2
                verify(exactly = 0) { f.horse.teleport(any<Location>()) }
            }
        }
    }
    "a rejected passenger mount restores temporary controls immediately" {
        YardFixture(initialControllable = false).use { f ->
            f.prepareDelivery()
            f.sync(OriginOddJobStage.YARD_HANDOFF)
            every { f.horse.addPassenger(f.riderEntity) } returns false
            f.yard.sync(f.player, f.job.copy(stage = OriginOddJobStage.RETURN), f.stations, 0) shouldBe false
            f.controllablePresent shouldBe true
            f.controllableEnabled shouldBe false
        }
    }
    "mounted despawn or entity replacement restores persistent NPC controls without touching either entity" {
        for ((replaced, initial) in listOf(false to false, true to null)) {
            YardFixture(initialControllable = initial).use { f ->
                f.prepareDelivery()
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                f.sync(OriginOddJobStage.RETURN)
                f.controllableEnabled shouldBe true
                var released = 0
                f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
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
                f.scheduler.tick(4)
                released shouldBe 1
                f.yard.ownsAnimal(f.actor.id) shouldBe false
                f.controllablePresent shouldBe (initial != null)
                if (initial != null) f.controllableEnabled shouldBe initial
                f.movementSpeed shouldBe 0.1
                verify(exactly = 0) { replacement.getAttribute(any()) }
                verify(exactly = 0) { f.riderEntity.leaveVehicle() }
            }
        }
    }
    "mounted cleanup cannot restore controls after the lease or persistent NPC identity changes" {
        for (leaseLost in listOf(true, false)) {
            YardFixture(initialControllable = false).use { f ->
                f.prepareDelivery()
                f.sync(OriginOddJobStage.YARD_HANDOFF)
                f.sync(OriginOddJobStage.RETURN)
                var released = 0
                f.yard.clear(f.player.uniqueId, f.job.id) { released++ }
                f.movementSpeed = 0.1
                if (leaseLost) f.leaseValid = false
                else every { f.actor.uniqueId } returns UUID.randomUUID()
                f.scheduler.tick(4)
                released shouldBe 1
                f.controllablePresent shouldBe true
                f.controllableEnabled shouldBe true
                f.movementSpeed shouldBe 0.1
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
            f.prepareTies()
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
    "native lead restores metadata presence persistence and AI before care or revocation" {
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
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.SADDLE }) }
            var releases = 0
            f.yard.clear(f.player.uniqueId, UUID.randomUUID())
            verify(exactly = 0) { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
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
            f.yard.target(hay) shouldBe null
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
            f.prepareTies()
            clearMocks(f.routes, answers = false)
            f.yard.revoke(f.player.uniqueId, f.job.id)
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.AIR }) }
            verify { anyConstructed<OriginSceneResources>().cleanup() }
            verify(exactly = 0) { f.routes.navigate(any(), any(), any(), any(), any(), any(), any()) }
            f.yard.ownsAnimal(423) shouldBe false
            f.scheduler.pendingCount() shouldBe 0
        }
    }
    "both physical ropes gate care and each untie removes only its own span before escort" {
        YardFixture().use { f ->
            f.location = ORIGIN_YARD_CARE_PAD.inWorld(f.world)
            val first = f.sync(OriginOddJobStage.YARD_TIE_FIRST)
            f.liveRopes() shouldBe 0
            f.yard.readyForCare(f.player.uniqueId, first) shouldBe false
            f.yard.station(f.player.uniqueId, first)!!.block.x shouldBe 55
            val second = f.sync(OriginOddJobStage.YARD_TIE_SECOND)
            f.liveRopes() shouldBe 20
            f.yard.readyForCare(f.player.uniqueId, second) shouldBe false
            f.yard.station(f.player.uniqueId, second)!!.block.x shouldBe 64
            val brush = f.sync(OriginOddJobStage.YARD_BRUSH)
            f.liveRopes() shouldBe 40
            f.yard.readyForCare(f.player.uniqueId, brush) shouldBe true
            val count = f.blocks.size
            f.yard.tick(f.player, brush, 100) shouldBe OriginYardTick.WAITING
            f.blocks.size shouldBe count
            f.sync(OriginOddJobStage.YARD_GROOM); f.sync(OriginOddJobStage.YARD_TACK); f.sync(OriginOddJobStage.YARD_SADDLE)
            f.sync(OriginOddJobStage.YARD_UNTIE_FIRST)
            f.liveRopes() shouldBe 40
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.SADDLE }) }
            f.sync(OriginOddJobStage.YARD_UNTIE_SECOND)
            f.liveRopes() shouldBe 20
            verify(exactly = 0) { anyConstructed<OriginSceneResources>().releaseHold(f.actor) }
            f.sync(OriginOddJobStage.YARD_DELIVER)
            f.liveRopes() shouldBe 0
            f.blocks.none { it.isValid } shouldBe true
            verify(exactly = 1) { anyConstructed<OriginSceneResources>().releaseHold(f.actor) }
        }
    }
    "both preparation variants saddle the horse and cancellation removes every tie before home" {
        YardFixture(OriginOddJobVariant.YARD_GROOM).use { f ->
            f.prepareDelivery()
            verify { f.equipment.setItem(EquipmentSlot.SADDLE, match { it.type == Material.SADDLE }) }
        }
        YardFixture().use { f ->
            f.prepareTies()
            var released = false
            f.yard.clear(f.player.uniqueId, f.job.id) { released = true }
            f.blocks.none { it.isValid } shouldBe true
            verify { anyConstructed<OriginSceneResources>().releaseHold(f.actor) }
            released shouldBe false
            f.location = f.home.clone(); f.arrive(); f.scheduler.tick(4)
            released shouldBe true
        }
    }
    "displaced horse or removed wall cannot be groomed and fails without a teleport" {
        YardFixture().use { f ->
            val tied = f.prepareTies()
            f.location.add(0.3, 0.0, 0.0)
            f.yard.readyForCare(f.player.uniqueId, tied) shouldBe false
            f.yard.tick(f.player, tied, 1) shouldBe OriginYardTick.FAILED
            verify(exactly = 0) { f.horse.teleport(any<Location>()) }
        }
        YardFixture().use { f ->
            val tied = f.prepareTies()
            val support = f.world.getBlockAt(55, 71, -128)
            every { support.type } returns Material.AIR
            f.yard.readyForCare(f.player.uniqueId, tied) shouldBe false
            f.yard.tick(f.player, tied, 1) shouldBe OriginYardTick.FAILED
        }
    }
    "arrival removes the worker leash before the separate rider accepts the prepared horse" {
        YardFixture().use { f ->
            val deliver = f.prepareDelivery()
            f.playerLocation = Location(f.world, 60.5, 70.0, -128.5)
            f.yard.tick(f.player, deliver, 1) shouldBe OriginYardTick.WAITING
            verify { f.horse.setLeashHolder(f.player) }
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
    initialControllable: Boolean? = null) : AutoCloseable {
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
    val equipment = mockk<EntityEquipment>(relaxed = true) { every { getItem(EquipmentSlot.SADDLE) } returns ItemStack(Material.AIR) }
    var nativeAi = false
    var nativeAware = false
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
        every { isLeashed } answers { this@YardFixture.leashHolder != null }
        every { leashHolder } answers { requireNotNull(this@YardFixture.leashHolder) }
        every { height } returns 1.6
        every { setLeashHolder(any()) } answers { this@YardFixture.leashHolder = firstArg(); true }
    }
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { world } returns this@YardFixture.world
        every { location } answers { playerLocation.clone() }
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
        yardTarget = OriginYardWork(id, catalog.animals.first { it.npcId == id }.name))!!
    val yard: OriginOddJobMountyard
    init {
        mockkStatic(CitizensAPI::class)
        mockkConstructor(OriginSceneResources::class)
        every { anyConstructed<OriginSceneResources>().clearMovementPose(any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().setPose(any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().faceHorizontal(any(), any(), any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().cleanup() } returns emptyList()
        every { anyConstructed<OriginSceneResources>().hold(any()) } returns Unit
        every { anyConstructed<OriginSceneResources>().releaseHold(any()) } returns Unit
        every { world.isChunkLoaded(any(), any()) } returns true
        val supports = mutableMapOf<Triple<Int, Int, Int>, Block>()
        fun blockAt(x: Int, y: Int, z: Int): Block = supports.getOrPut(Triple(x, y, z)) {
            mockk<Block>(relaxed = true) {
                every { type } returns when {
                    x in setOf(55, 64) && y == 71 && z == -128 -> Material.STONE_BRICKS
                    x == 72 && y == 70 && z == -137 -> Material.OAK_FENCE_GATE
                    else -> Material.AIR
                }
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
        }
        val otherActor = mockk<NPC> {
            every { this@mockk.id } returns otherId
            every { uniqueId } returns catalog.animals.first { it.npcId == otherId }.npcUuid
            every { isSpawned } returns true
            every { entity } returns otherHorse
        }
        val registry = mockk<NPCRegistry> { every { getById(any()) } answers { when (firstArg<Int>()) {
            id -> actor
            otherId -> otherActor
            testYardRider.npcId -> riderActor
            else -> null
        } } }
        every { CitizensAPI.getNPCRegistry() } returns registry
        val scene = OriginSceneDefinition("mount-yard", ORIGIN_YARD_CARE_PAD, 72.0, 3_000, 7,
            mapOf(id to OriginSceneActor(id, OriginScenePoint(home.x, home.y, home.z), emptySet()),
                otherId to OriginSceneActor(otherId, otherHome, emptySet())),
            mapOf("job-rider-home" to OriginScenePoint(riderHome.x, riderHome.y, riderHome.z),
                "job-handoff" to OriginScenePoint(67.5, 70.0, -122.5),
                "job-ride-turn" to OriginScenePoint(59.5, 70.0, -119.5),
                "job-stall-$id-exit" to if (id == 422) OriginScenePoint(52.5, 70.0, -136.5) else OriginScenePoint(72.5, 70.0, -138.5),
                "job-stall-423-gate-1" to OriginScenePoint(72.0, 70.0, -137.0)), emptyMap(), emptyMap(), emptyList())
        yard = OriginOddJobMountyard(world.name, catalog, listOf(422, 423), 422, testYardRider, scene, routes = routes,
            tasks = LifecycleTaskScope(scheduler), createDisplays = { renderer }, rideAnchorIds = listOf("job-ride-turn"),
            gateAnchorIds = if (gates) listOf("job-stall-423-gate-1") else emptyList(), clock = { now })
        if (startJob) yard.begin(player, job) { leaseValid } shouldBe true
    }
    fun sync(stage: OriginOddJobStage): OriginOddJobSession = job.copy(stage = stage).also { yard.sync(player, it, stations, 0) shouldBe true }
    fun prepareTies(): OriginOddJobSession {
        location = ORIGIN_YARD_CARE_PAD.inWorld(world)
        listOf(OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_BRUSH,
            OriginOddJobStage.YARD_GROOM).forEach(::sync)
        sync(OriginOddJobStage.YARD_TACK); sync(OriginOddJobStage.YARD_SADDLE)
        return sync(OriginOddJobStage.YARD_UNTIE_FIRST)
    }
    fun prepareDelivery(): OriginOddJobSession {
        prepareTies(); sync(OriginOddJobStage.YARD_UNTIE_SECOND)
        return sync(OriginOddJobStage.YARD_DELIVER)
    }
    fun liveRopes(): Int = blocks.count { it.isValid && materials[it] == Material.STRIPPED_OAK_WOOD }
    fun arrive(success: Boolean = true) { navigating = false; outcome = NpcRouteOutcome(success, if (success) "FINISHED" else "STALLED", null) }
    override fun close() {
        yard.close()
        unmockkConstructor(OriginSceneResources::class)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
}
