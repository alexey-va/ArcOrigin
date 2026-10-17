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
import java.util.UUID
import kotlin.math.abs

class OriginOddJobForgeTest : StringSpec({
    "cart movement is paid by nearby forward walking and stops on pause remote movement teleport or obstruction" {
        val route = OriginForgeCartRoute(listOf(OriginWorkshopPoint(0.0, 70.0, 0.0),
            OriginWorkshopPoint(5.0, 70.0, 0.0)), "test:cart", Vector3f(3f), 0.5625)
        val motion = OriginForgeCartMotion(route, false)
        fun at(x: Double, z: Double = 0.0) = OriginWorkshopPoint(x, 70.0, z)
        motion.step(at(-1.0)) shouldBe false
        repeat(40) { motion.step(at(-1.0)) shouldBe false }
        motion.distance shouldBe 0.0
        motion.step(at(-0.5)) shouldBe true
        abs(motion.distance - 0.32) shouldBeLessThan 1e-6
        motion.step(at(-0.6)) shouldBe false
        motion.step(at(-0.5)) { _, _ -> false } shouldBe false
        motion.step(at(8.0)) shouldBe false
        motion.step(at(8.1)) shouldBe false
        motion.step(at(-0.86)) shouldBe false
        motion.step(at(-0.76)) shouldBe true
        abs(motion.distance - 0.42) shouldBeLessThan 1e-6
        motion.arrived shouldBe false
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

    "full cart route checks interior obstacles overhead collision support and carpet before admission" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.forge.available(f.world) shouldBe true
            val middle = f.world.getBlockAt(5, 70, 7)
            middle.type = Material.STONE
            f.forge.available(f.world) shouldBe false
            middle.type = Material.GRAY_CARPET
            f.forge.available(f.world) shouldBe true
            val upper = f.world.getBlockAt(5, 71, 7)
            upper.type = Material.STONE
            f.forge.available(f.world) shouldBe false
            upper.type = Material.AIR
            f.world.getBlockAt(5, 69, 7).type = Material.AIR
            f.forge.available(f.world) shouldBe false
        } }
    }

    "production one tick timer follows ordinary walking and sprinting without outrunning the handles" {
        for (speed in listOf(0.22, 0.28)) OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(351, OriginOddJobVariant.HAUL_IRON)
            repeat(3) { f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart() }
            f.pushCart(step = speed)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_UNLOAD
            f.payments shouldBe emptyList()
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
            job.haul!!.loaded shouldBe 0
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

    "loaded cart cannot finish from idle or delivery clicks and interruption removes all scene and service material" {
        OddJobsFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_IRON)
            f.leases.single().actorIds shouldBe setOf(349, 362, 351)
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            repeat(3) { f.stroke(OriginOddJobStationId.ORE_STOCK); f.strokeCart() }
            repeat(80) { f.scheduler.tick() }
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.game.work(f.player, false, f.stations.getValue(OriginOddJobStationId.BLANK).block) shouldBe false
            f.payments shouldBe emptyList()
            f.pushCart()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_UNLOAD
            f.strokeCart()
            f.player.inventory.contents.filterNotNull().count { it.type == Material.STRUCTURE_VOID } shouldBe 1
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

    "source route reuses Savva's grounded cart and a complete round trip fits the order deadline" {
        OddJobsFixture().use {
            val scene = OriginScenePlan.load(Files.createTempDirectory("forge-cart-route")).scene("forge")
            val route = OriginForgeCartRoute.from(scene)
            route.itemId shouldBe "elitecreatures:medieval_market_decoration_v1_cart_2"
            route.lift shouldBe 0.5625
            route.length shouldBe 13.0
            for (returning in listOf(false, true)) {
                val motion = OriginForgeCartMotion(route, returning)
                // A player walks the authored route behind the handles, including both corners.
                var previous = route.point(0.0, returning).let { p ->
                    val (x, z) = route.direction(0.0, returning)
                    OriginWorkshopPoint(p.x - x, p.y, p.z - z)
                }
                motion.step(previous)
                var ticks = 0
                var walked = -1.0
                while (!motion.arrived && ticks++ < 600) {
                    walked += 0.1
                    previous = if (walked < 0) route.point(0.0, returning).let { p ->
                        val (x, z) = route.direction(0.0, returning)
                        OriginWorkshopPoint(p.x + x * walked, p.y, p.z + z * walked)
                    } else route.point(walked, returning)
                    motion.step(previous)
                }
                motion.arrived shouldBe true
                (ticks * 50 < 30_000) shouldBe true
            }
        }
    }
})
