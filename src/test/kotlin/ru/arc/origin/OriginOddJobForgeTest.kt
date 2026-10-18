package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.joml.Vector3f
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import kotlin.math.abs

class OriginOddJobForgeTest : StringSpec({
    "cart attachment follows the worker heading behind the player without a route projection" {
        for (yaw in listOf(0.0, 90.0, 180.0, 270.0)) {
            val pose = originForgeCartCarryPose(yaw)
            val angle = Math.toRadians(yaw)
            abs(pose.center.x - kotlin.math.sin(angle)) shouldBeLessThan 1e-6
            abs(pose.center.z + kotlin.math.cos(angle)) shouldBeLessThan 1e-6
            pose.center.y shouldBe 0.0
            val forward = Vector3f((-kotlin.math.sin(angle)).toFloat(), 0f, kotlin.math.cos(angle).toFloat())
            val back = Vector3f(pose.center.x.toFloat(), 0f, pose.center.z.toFloat())
            abs(back.dot(forward).toDouble() + 1) shouldBeLessThan 1e-6
        }
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
            f.clickNpc(354, sneaking = false)
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

    "production timer reaches the chest by ordinary player movement and waits for unloading" {
        for (speed in listOf(0.22, 0.28)) OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(351, OriginOddJobVariant.HAUL_IRON)
            f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart(); f.strokeCart()
            f.dragCart(step = speed)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_UNLOAD
            f.payments shouldBe emptyList()
            f.stroke(OriginOddJobStationId.BLANK)
            f.payments.size shouldBe 1
        } }
    }

    "virtual cart click ignores the later floor block but still rejects an earlier solid obstruction" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(351, OriginOddJobVariant.HAUL_IRON)
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
            f.begin(351, OriginOddJobVariant.HAUL_IRON)
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

    "attached cart cannot finish from idle or destination clicks and interruption clears its lease" {
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
            val destination = f.stations.getValue(OriginOddJobStationId.BLANK)
            val destinationAt = destination.block.location.clone()
            repeat(80) { tick ->
                f.scheduler.tick()
                f.requireStage(OriginOddJobStage.HAUL_PUSH, "stationary cart tick ${tick + 1}")
            }
            destination.block.location shouldBe destinationAt
            destination.valid() shouldBe true
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.stroke(OriginOddJobStationId.BLANK)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            @Suppress("UNNECESSARY_SAFE_CALL") // The closed MockBukkit view may omit its top inventory.
            (f.player.openInventory.topInventory?.holder is OriginOddJobSupplies.Stock) shouldBe false
            f.payments shouldBe emptyList()
            f.game.onTeleport(mockk<PlayerTeleportEvent> { every { player } returns f.player })
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.cartDisplays.none { it.isValid } shouldBe true
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments shouldBe emptyList()
            f.begin(351, OriginOddJobVariant.HAUL_COAL)
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
