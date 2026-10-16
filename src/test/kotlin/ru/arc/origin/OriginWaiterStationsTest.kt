package ru.arc.origin

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

class OriginWaiterStationsTest : StringSpec({
    "live anchored prep choices sit on the measured counter or raised worktop" {
        val config = waiterTestConfig()
        for (venue in config.venues) {
            val layout = config.stations.getValue(venue.id)
            val job = OriginWaiterJob(waiterTarget(venue.id), 0, 180_000,
                stage = OriginWaiterStage.ASSEMBLE, family = OriginWaiterFamily.ASSEMBLED,
                recipe = config.recipes.getValue("egg"))
            val choices = waiterStationChoices(config, venue, job)
            choices.size shouldBe 3
            choices.count { it.next } shouldBe 1
            choices.forEach { (abs(it.point.y - layout.prep.y - layout.standHeight - 0.015) < 1e-8) shouldBe true }
            val next = waiterStationChoices(config, venue, job.copy(assembled = 1))
            next.map { it.id } shouldBe listOf("ingredient:bacon", "ingredient:herbs")
            next.single { it.next }.id shouldBe "ingredient:bacon"
            if (venue.id == "restaurant") {
                choices.map { it.point.z } shouldBe listOf(58.25, 58.75, 59.25)
                waiterStationBlocks(layout) shouldBe emptyList()
            } else {
                val blocks = waiterStationBlocks(layout)
                blocks.filter { it.id.endsWith(":top") }.forEach {
                    (abs(it.y + it.height - layout.standHeight) < 1e-6) shouldBe true
                }
                blocks.filter { ":leg:" in it.id }.all { it.y == 0f } shouldBe true
                // The rack is east of the sign's cell; no support at (-3.5, 70, 62.5).
                layout.rack.x shouldBe -2.5
            }
        }
    }

    "Remi props can be aimed from standing reach with the real floor behind the packet worktop" {
        val config = waiterTestConfig()
        val layout = config.stations.getValue("brewery")
        val point = waiterStationPoint(layout.surface(layout.prep), 1, 3)
        val eyeX = point.x + 1.5
        val eyeY = layout.prep.y + 1.62
        val eyeZ = point.z
        val length = sqrt(1.5 * 1.5 + (eyeY - point.y) * (eyeY - point.y))
        val dx = -1.5 / length
        val dy = (point.y - eyeY) / length
        val hit = waiterStationRayDistance(eyeX, eyeY, eyeZ, dx, dy, 0.0, point, 4.5)
        (abs(checkNotNull(hit) - length) < 1e-6) shouldBe true
        val floorDistance = (layout.prep.y - eyeY) / dy
        (floorDistance > checkNotNull(hit) && floorDistance < 4.5) shouldBe true
        waiterStationRayDistance(eyeX, eyeY, eyeZ, dx, -dy, 0.0, point, 4.5) shouldBe null
        waiterStationRayDistance(eyeX, eyeY, eyeZ + 0.6, dx, dy, 0.0, point, 4.5) shouldBe null
    }

    "unchanged station refresh and ordered wash cues reuse supports and clean up packet handles" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val player = paper.addPlayer("StationWorker")
            player.teleport(Location(paper.addSimpleWorld(OriginDiningLayout.WORLD), -2.0, 70.0, 59.75))
            val renderer = mockk<PaperPacketDisplays>(relaxed = true)
            val items = mutableListOf<PacketItemDisplay>()
            val blocks = mutableListOf<PacketBlockDisplay>()
            every { renderer.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(items::add) }
            every { renderer.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(blocks::add) }
            val stations = OriginWaiterStations { renderer }
            val config = waiterTestConfig()
            val venue = config.venues.first()
            val layout = config.stations.getValue(venue.id)
            val job = OriginWaiterJob(waiterTarget(venue.id), 0, 180_000, stage = OriginWaiterStage.ASSEMBLE,
                family = OriginWaiterFamily.ASSEMBLED, recipe = config.recipes.getValue("egg"))
            val first = waiterStationChoices(config, venue, job)
            stations.show(player, job.id, first, layout, 0) { null }
            stations.show(player, job.id, first, layout, 0) { null }
            items.size shouldBe 3
            blocks.size shouldBe waiterStationBlocks(layout).size
            stations.show(player, job.id, waiterStationChoices(config, venue, job.copy(assembled = 1)), layout, 1) { null }
            items.size shouldBe 3
            verify(exactly = 1) { items.first().remove() }
            verify(exactly = 1) { items[1].isGlowing = true }
            stations.remove(player.uniqueId)
            stations.remove(player.uniqueId)
            stations.close()
            stations.close()
            items.forEach { display -> verify(exactly = 1) { display.remove() } }
            blocks.forEach { display -> verify(exactly = 1) { display.remove() } }
            verify(exactly = 1) { renderer.close() }
        } finally { paper.close() }
    }

    "source preview exports exact support cuboids and recipe wash targets" {
        val config = waiterTestConfig()
        fun point(value: OriginDiningPoint) = mapOf("x" to value.x, "y" to value.y, "z" to value.z, "yaw" to value.yaw)
        val states = config.venues.flatMap { venue ->
            val layout = config.stations.getValue(venue.id)
            val job = OriginWaiterJob(waiterTarget(venue.id), 0, 180_000, stage = OriginWaiterStage.ASSEMBLE,
                family = OriginWaiterFamily.ASSEMBLED, recipe = config.recipes.getValue("egg"))
            listOf("ingredients" to job, "assembled" to job.copy(stage = OriginWaiterStage.PICKUP, assembled = 3),
                "washing" to job.copy(stage = OriginWaiterStage.WASH, washed = 1),
                "drying" to job.copy(stage = OriginWaiterStage.WASH, washed = 3),
                "clean" to job.copy(stage = OriginWaiterStage.PAYOUT, washed = 4)).map { (name, state) ->
                mapOf("id" to "${venue.id}-$name", "venue" to venue.id, "stage" to name,
                    "floorY" to if (venue.id == "brewery") 70.0 else 72.0,
                    "layout" to mapOf("prep" to point(layout.prep), "sink" to point(layout.sink), "rack" to point(layout.rack), "standHeight" to layout.standHeight),
                    "blocks" to waiterStationBlocks(layout),
                    "choices" to waiterStationChoices(config, venue, state))
            }
        }
        val output = Path.of("build/reports/waiter-stations/geometry.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "source" to "OriginWaiterStations.kt canonical runtime geometry + bundled origin-dining.yml",
            "boundary" to "Source geometry, not native client rendering or observed block collisions",
            "states" to states)))
        states.size shouldBe 10
    }
})
