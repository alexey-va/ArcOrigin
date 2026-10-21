package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import kotlin.math.abs

class OriginOddJobForgeTest : StringSpec({
    "successive forge orders update the HUD and world cue without repeating their instructions in chat" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.startForge()
            fun messages() = generateSequence { f.player.nextComponentMessage() }.map(f::plain).toList()
            messages().none { "Новый заказ:" in it } shouldBe true
            f.plain(f.player.bossBars.single().name()) shouldContain "возьми"
            (f.target != null) shouldBe true
            f.finishForge()
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            (f.game.currentJob(f.player.uniqueId) != null) shouldBe true
            messages().none { "Новый заказ:" in it } shouldBe true
            f.plain(f.player.bossBars.single().name()) shouldContain "возьми"
            (f.target != null) shouldBe true
        } }
    }

    "only Edgar starts a forge shift and the cart follows view yaw when the worker's body faces sideways" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            for (actor in listOf(351, 354)) {
                f.moveNpc(actor)
                f.game.interact(f.player, actor) shouldBe false
            }
            f.screens shouldBe emptyList()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.leases.single().actorIds shouldBe setOf(349, 362, 351)
            f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart(); f.strokeCart()
            val job = f.game.currentJob(f.player.uniqueId)!!
            var transform: Transformation? = null
            var releasedAt: Location? = null
            val cart = f.cartDisplays.first()
            every { cart.transformation = any() } answers { transform = firstArg() }
            every { cart.teleport(any()) } answers { releasedAt = firstArg<Location>().clone() }
            // Native GrocerMC observation, 2026-10-21 11:51:05: looking and body headings differ by 50 degrees.
            val viewYaw = 150.30119324f
            f.bodyYawOverride = 200.30119324f
            val at = Location(f.world, 2.0, 70.0, 5.0, viewYaw, 0f)
            f.player.teleport(at)
            f.forge.sync(f.player, job)
            val angle = Math.toRadians(viewYaw.toDouble())
            val forward = Vector3f((-kotlin.math.sin(angle)).toFloat(), 0f, kotlin.math.cos(angle).toFloat())
            val carried = requireNotNull(transform)
            val offset = Vector3f(carried.translation).apply { y = 0f }
            abs(offset.dot(forward).toDouble() - 1) shouldBeLessThan 1e-6
            // Model handles are -Z; native ItemDisplay adds Y+180 before the authored left rotation.
            val handles = carried.leftRotation.transform(Vector3f(0f, 0f, 1f))
            abs(handles.dot(forward).toDouble() + 1) shouldBeLessThan 1e-6
            f.forge.sync(f.player, job.copy(stage = OriginOddJobStage.HAUL_UNLOAD))
            val parked = requireNotNull(releasedAt)
            parked.yaw shouldBe viewYaw + 180f
            parked.toVector().subtract(at.toVector()).let {
                abs(it.x - forward.x) shouldBeLessThan 1e-6
                abs(it.z - forward.z) shouldBeLessThan 1e-6
            }
            f.payments shouldBe emptyList()
        } }
    }

    "a new repair order starts at stock and never resurrects an item on the anvil" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.FORGE_REPAIR)
            val job = f.game.currentJob(f.player.uniqueId)!!
            job.stage shouldBe OriginOddJobStage.FORGE_BLANK
            originOddJobStation(job.stage) shouldBe OriginOddJobStationId.BLANK
            f.plain(f.player.bossBars.single().name()) shouldContain "Новый ремонт"
            f.stroke(OriginOddJobStationId.BLANK)
            f.plain(f.player.openInventory.topInventory.getItem(4)!!.itemMeta.displayName()!!) shouldContain "на ремонт"
            f.stockClick(); f.scheduler.tick(); f.player.closeInventory()
            job.id shouldBe f.game.currentJob(f.player.uniqueId)!!.id
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_HEAT
            f.payments shouldBe emptyList()
        } }
    }

    "smith's engraving leases Luka's station but returns only to the original distant employer" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            val engraver = f.entity(354); val hauler = f.entity(351)
            every { engraver.location } returns Location(f.world, -15.0, 70.0, 0.0)
            every { hauler.location } returns Location(f.world, 20.0, 70.0, 0.0)
            f.begin(349, OriginOddJobVariant.ENGRAVE_CAT)
            f.leases.single().actorIds shouldBe setOf(349, 362, 354)
            f.game.currentJob(f.player.uniqueId)!!.employer shouldBe OriginOddJobKind.FORGE
            f.pickupBlank(); f.stroke(OriginOddJobStationId.ENGRAVING)
            repeat(6) { f.stroke(OriginOddJobStationId.ENGRAVING, left = true, elapsed = 600) }
            f.clickNpc(349, sneaking = false)
            f.payments shouldBe emptyList()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.ENGRAVE_COLLECT
            f.stroke(OriginOddJobStationId.ENGRAVING)
            f.plain(f.player.bossBars.single().name()) shouldContain "Эдгар"
            f.moveNpc(354)
            f.game.interact(f.player, 354) shouldBe false
            f.payments shouldBe emptyList()
            f.clickNpc(349, sneaking = false)
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
            f.released shouldBe f.leases
        } }
    }

    "mixed forge admission checks the engraving station before accepting a shift" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.stations.getValue(OriginOddJobStationId.ENGRAVING).block.type = Material.AIR
            f.open(349, sneaking = false)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leases shouldBe emptyList()
            f.payments shouldBe emptyList()
        } }
    }

    "virtual cart click ignores the later floor block but still rejects an earlier solid obstruction" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.stroke(OriginOddJobStationId.ORE_STOCK)
            val job = f.game.currentJob(f.player.uniqueId)!!
            val station = f.forge.station(f.player.uniqueId, job)!!
            val at = station.point.clone().add(-2.0, -0.65, 0.0)
            at.setDirection(station.point.toVector().subtract(at.clone().add(0.0, f.player.eyeHeight, 0.0).toVector()))
            f.player.teleport(at)
            val background = f.world.getBlockAt(3, 69, 7)
            originOddJobAimed(f.player, station, 4.5, background, virtualTarget = true) shouldBe true
            originOddJobAimed(f.player, station, 4.5, background) shouldBe false
            val wall = f.world.getBlockAt(1, 71, 7)
            wall.type = Material.STONE
            f.world.obstruction = org.bukkit.util.RayTraceResult(wall.location.toVector(), wall, org.bukkit.block.BlockFace.WEST)
            originOddJobAimed(f.player, station, 4.5, background, virtualTarget = true) shouldBe false
            job.stage shouldBe OriginOddJobStage.HAUL_LOAD
        } }
    }

    "partial cargo allocation failure clears every spawned cart and cargo handle" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.stroke(OriginOddJobStationId.ORE_STOCK)
            f.cartBlocksBeforeFailure = 2
            f.strokeCart()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.cartDisplays.size shouldBe 3
            f.cartDisplays.none { it.isValid } shouldBe true
            f.payments shouldBe emptyList()
            f.released shouldBe f.leases
        } }
    }

    "haul completion uses the player's pad XZ even above the pad with the cart outside" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart(); f.strokeCart()
            val destination = f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY).point
            val at = destination.clone().add(ODD_JOB_DESTINATION_HALF_WIDTH - 0.01, 100.0, 0.0).apply { yaw = -90f }
            f.player.teleport(at)
            val cartX = at.x + originForgeCartCarryPose(at.yaw.toDouble()).center.x
            (cartX - destination.x > ODD_JOB_DESTINATION_HALF_WIDTH) shouldBe true
            // The normal game tick used to cancel at its NPC-based 3D area gate before delivery.
            f.game.tick(f.now)
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
            f.game.currentJob(f.player.uniqueId) shouldBe null
            repeat(12) { f.scheduler.tick() }
            f.payments.size shouldBe 1
            f.cartDisplays.none { it.isValid } shouldBe true
            f.released shouldBe f.leases
        } }
    }

    "right click on the haul pad shares automatic delivery without rechecking spent admission resources" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_COPPER)
            f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart(); f.strokeCart()
            val job = f.game.currentJob(f.player.uniqueId)!!
            val destination = f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY).point
            f.player.teleport(destination.clone().add(ODD_JOB_DESTINATION_HALF_WIDTH + 0.5, 100.0, 0.0))
            f.stations.getValue(OriginOddJobStationId.ORE_STOCK).block.type = Material.AIR
            every { f.npc(351).isSpawned } returns false
            f.forge.clear(f.player.uniqueId, job.id)
            f.world.unloadChunk(0, 0)
            f.forge.available(f.world) shouldBe false
            f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.HAUL_PUSH, "loaded hauling survives spent admission resources outside the pad")
            f.payments shouldBe emptyList()
            f.cartDisplays.none { it.isValid } shouldBe true
            f.player.teleport(destination.clone().add(0.0, -100.0, 0.0))
            // Aiming and the display's existence are irrelevant: the active worker is inside the pad.
            val click = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND)
            f.game.onBlock(click)
            click.useItemInHand() shouldBe Event.Result.DENY
            f.payments.single().id shouldBe job.id
            f.game.currentJob(f.player.uniqueId) shouldBe null
            repeat(12) { f.scheduler.tick(); f.game.onBlock(click) }
            f.payments.size shouldBe 1
            f.released shouldBe f.leases
        } }
    }

    "cart inside the haul pad does not deliver while its worker remains outside" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart(); f.strokeCart()
            val destination = f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY).point
            val at = destination.clone().add(ODD_JOB_DESTINATION_HALF_WIDTH + 0.01, 0.0, 0.0).apply { yaw = 90f }
            f.player.teleport(at)
            val cartX = at.x + originForgeCartCarryPose(at.yaw.toDouble()).center.x
            (abs(cartX - destination.x) < ODD_JOB_DESTINATION_HALF_WIDTH) shouldBe true
            repeat(8) { f.scheduler.tick() }
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.payments shouldBe emptyList()
        } }
    }

    "attached cart cannot finish outside the pad and interruption clears its lease" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.leases.single().actorIds shouldBe setOf(349, 362, 351)
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            f.stroke(OriginOddJobStationId.ORE_STOCK)
            f.requireStage(OriginOddJobStage.HAUL_LOAD, "after picking up stationary cart cargo")
            f.strokeCart()
            f.requireStage(OriginOddJobStage.HAUL_ATTACH, "after loading stationary cart")
            f.strokeCart()
            f.requireStage(OriginOddJobStage.HAUL_PUSH, "after attaching stationary cart")
            val destination = f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY)
            val destinationAt = destination.block.location.clone()
            repeat(80) { tick ->
                f.scheduler.tick()
                f.requireStage(OriginOddJobStage.HAUL_PUSH, "stationary cart tick ${tick + 1}")
            }
            destination.block.location shouldBe destinationAt
            destination.valid() shouldBe true
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.stroke(OriginOddJobStationId.HAUL_DELIVERY)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            @Suppress("UNNECESSARY_SAFE_CALL") // The closed MockBukkit view may omit its top inventory.
            (f.player.openInventory.topInventory?.holder is OriginOddJobSupplies.Stock) shouldBe false
            f.payments shouldBe emptyList()
            f.game.onTeleport(mockk<PlayerTeleportEvent> { every { player } returns f.player })
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.cartDisplays.none { it.isValid } shouldBe true
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments shouldBe emptyList()
            f.begin(349, OriginOddJobVariant.HAUL_COAL)
            f.forge.clear(f.player.uniqueId, request)
            f.cartDisplays.any { it.isValid } shouldBe true
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            f.cartDisplays.none { it.isValid } shouldBe true
            f.released shouldBe f.leases
        } }
    }

    "job parking uses the requested ground point and Savva's measured item context" {
        OddJobsFixture().use {
            val scene = OriginScenePlan.load(Files.createTempDirectory("forge-cart-layout")).scene("forge")
            val layout = OriginForgeCartLayout.from(scene)
            layout.start shouldBe OriginWorkshopPoint(61.8, 69.0, 63.7)
            layout.yaw shouldBe 90f
            layout.itemId shouldBe "elitecreatures:medieval_market_decoration_v1_cart_2"
            layout.lift shouldBe 0.5625
            layout.scale shouldBe Vector3f(3f)
            layout.context shouldBe org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GROUND
            val stock = scene.anchors.getValue("job-haul-stock")
            listOf(stock.x, stock.y, stock.z) shouldBe listOf(65.0, 70.0, 61.0)
        }
    }
})
