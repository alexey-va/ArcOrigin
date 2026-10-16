package ru.arc.origin

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Vector3f
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
        (checkNotNull(hit) < length && hit > 0.0) shouldBe true
        val floorDistance = (layout.prep.y - eyeY) / dy
        (floorDistance > checkNotNull(hit) && floorDistance < 4.5) shouldBe true
        waiterStationRayDistance(eyeX, eyeY, eyeZ, dx, -dy, 0.0, point, 4.5) shouldBe null
        waiterStationRayDistance(eyeX, eyeY, eyeZ + 0.6, dx, dy, 0.0, point, 4.5) shouldBe null
    }

    "unchanged station props reuse handles while permanent furniture is never owned or removed by a worker" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val player = paper.addPlayer("StationWorker")
            player.teleport(Location(paper.addSimpleWorld(OriginDiningLayout.WORLD), -2.0, 70.0, 59.75))
            val renderer = mockk<PaperPacketDisplays>(relaxed = true)
            val items = mutableListOf<PacketItemDisplay>()
            every { renderer.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(items::add) }
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
            verify(exactly = 0) { renderer.spawnBlock(any(), any()) }
            stations.show(player, job.id, waiterStationChoices(config, venue, job.copy(assembled = 1)), layout, 1) { null }
            items.size shouldBe 3
            verify(exactly = 1) { items.first().remove() }
            verify(exactly = 1) { items[1].isGlowing = true }
            stations.remove(player.uniqueId)
            stations.remove(player.uniqueId)
            stations.close()
            stations.close()
            items.forEach { display -> verify(exactly = 1) { display.remove() } }
            verify(exactly = 1) { renderer.close() }
        } finally { paper.close() }
    }

    "source preview exports exact actionable prop positions on world furniture" {
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
                    "furnitureOwner" to "permanent-world-scene",
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

    "wash guide refreshes keep the moving plate handle through the clean rack handoff" {
        val paper = MockBukkitTestRuntime.open()
        try {
            val player = paper.addPlayer("PlateContinuity")
            player.teleport(Location(paper.addSimpleWorld(OriginDiningLayout.WORLD), 0.0, 70.0, 62.0))
            val renderer = mockk<PaperPacketDisplays>(relaxed = true)
            val plates = mutableListOf<PacketItemDisplay>()
            every { renderer.spawnItem(any(), any()) } answers {
                mockk<PacketItemDisplay>(relaxed = true).also { if (secondArg<ItemStack>().type == Material.BOWL) plates += it }
            }
            val stations = OriginWaiterStations { renderer }
            val config = waiterTestConfig()
            val venue = config.venues.first()
            val layout = config.stations.getValue(venue.id)
            val job = OriginWaiterJob(waiterTarget(venue.id), 0, 180_000, stage = OriginWaiterStage.WASH,
                washing = listOf(OriginWaiterWash.SOAP, OriginWaiterWash.DRY))
            fun show(state: OriginWaiterJob, now: Long) = stations.show(player, state.id,
                waiterStationChoices(config, venue, state), layout, state.washed, state, now) { ItemStack(Material.BOWL) }
            show(job, 0)
            stations.begin(player, job, "wash:SOAP", layout, 1_600, 0) { ItemStack(Material.BOWL) } shouldBe true
            stations.tick(800)
            show(job, 800)
            plates.size shouldBe 1
            verify(exactly = 0) { plates.single().remove() }
            stations.tick(1_600)
            val dry = job.copy(washed = 1)
            show(dry, 1_600)
            stations.begin(player, dry, "wash:DRY", layout, 1_600, 1_600) { ItemStack(Material.BOWL) } shouldBe true
            stations.tick(3_200)
            show(dry.copy(stage = OriginWaiterStage.PAYOUT, washed = 2), 3_200)
            plates.size shouldBe 1
            verify(exactly = 0) { plates.single().remove() }
            stations.remove(player.uniqueId)
            verify(exactly = 1) { plates.single().remove() }
            stations.close()
        } finally { paper.close() }
    }

    "wash contact preview captures actual item context transforms and existing rack plates" {
        val paper = MockBukkitTestRuntime.open()
        try {
            OriginDiningLayout.load(Files.createTempDirectory("waiter-contact-models"))
            val player = paper.addPlayer("ToolContact")
            player.teleport(Location(paper.addSimpleWorld(OriginDiningLayout.WORLD), 0.0, 70.0, 62.0))
            val config = waiterTestConfig()
            val venue = config.venues.first()
            val layout = config.stations.getValue(venue.id)
            val clips = mutableListOf<Map<String, Any>>()
            for (dish in listOf("egg", "fish")) for (action in OriginWaiterWash.entries) {
                val renderer = mockk<PaperPacketDisplays>(relaxed = true)
                val captured = mutableListOf<WaiterCapturedItem>()
                every { renderer.spawnItem(any(), any()) } answers {
                    val item = WaiterCapturedItem(firstArg<Location>().clone(), secondArg())
                    captured += item
                    mockk<PacketItemDisplay>(relaxed = true).also { display ->
                        every { display.teleport(any()) } answers { item.location = firstArg<Location>().clone() }
                        every { display.transformation = any() } answers { item.transform = firstArg() }
                        every { display.itemDisplayTransform = any() } answers { item.context = firstArg() }
                        every { display.remove() } answers { item.removed = true }
                    }
                }
                val stations = OriginWaiterStations { renderer }
                val job = OriginWaiterJob(waiterTarget(venue.id).copy(dishId = dish), 0, 180_000,
                    stage = OriginWaiterStage.WASH, washing = listOf(action))
                fun show(state: OriginWaiterJob) = stations.show(player, state.id,
                    waiterStationChoices(config, venue, state), layout, 0, state) { ItemStack(Material.BOWL) }
                show(job.copy(stage = OriginWaiterStage.PAYOUT)) // An actual previous clean dish occupies the rack.
                show(job)
                val tool = captured.single { it.stack.type != Material.BOWL }
                val dock = tool.location.clone()
                val plate = captured.last { it.stack.type == Material.BOWL }
                val height = waiterStationPlateHeight(dish)
                abs(height - if (dish == "fish") 0.040625 else 0.08125).let { it < 1e-7 } shouldBe true
                tool.context shouldBe ItemDisplay.ItemDisplayTransform.NONE
                abs(tool.minimumY() - layout.surface(layout.sink).y).let { it < 1e-6 } shouldBe true
                val duration = if (action == OriginWaiterWash.SOAK) 3_000L else 1_600L
                stations.begin(player, job, "wash:$action", layout, duration, 0) { ItemStack(Material.BOWL) } shouldBe true
                val frames = (0L..duration step 50).map { time ->
                    stations.tick(time)
                    val progress = time.toDouble() / duration
                    if (progress in 0.12..if (action == OriginWaiterWash.DRY) 0.47 else 0.86) {
                        abs(tool.minimumY() - (plate.location.y + height)).let { it < 1e-6 } shouldBe true
                    }
                    mapOf("timeMillis" to time, "items" to captured.filter { !it.removed }.map { it.export(dish) })
                }
                tool.location.distance(dock).let { it < 1e-8 } shouldBe true
                if (action == OriginWaiterWash.DRY) {
                    abs(plate.location.y - layout.rack.y - height).let { it < 1e-6 } shouldBe true
                    tool.location.distance(plate.location).let { it > 0.5 } shouldBe true
                }
                clips += mapOf("id" to "$dish:$action", "durationMillis" to duration, "frames" to frames)
                stations.close()
            }
            val output = Path.of("build/reports/waiter-stations/wash-runtime.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to "OriginWaiterStations.show/begin/tick captured PacketItemDisplay setters",
                "boundary" to "Actual runtime metadata in MockBukkit; not native client playback",
                "layout" to layout, "floorY" to 70.0, "clips" to clips)))
            clips.size shouldBe 10
        } finally { paper.close() }
    }
})

private class WaiterCapturedItem(var location: Location, val stack: ItemStack) {
    lateinit var transform: Transformation
    var context = ItemDisplay.ItemDisplayTransform.NONE
    var removed = false

    fun minimumY(): Double {
        val matrix = Matrix4f().translation(transform.translation).rotate(transform.leftRotation)
            .scale(transform.scale).rotate(transform.rightRotation).rotateY(Math.PI.toFloat())
        return location.y + listOf(-0.5f, 0.5f).flatMap { x -> listOf(-0.5f, 0.5f).flatMap { y ->
            listOf(-1f / 32f, 1f / 32f).map { z -> matrix.transformPosition(Vector3f(x, y, z)).y }
        } }.min()
    }

    fun export(dish: String): Map<String, Any> = mapOf(
        "material" to stack.type.name, "dishId" to if (stack.type == Material.BOWL) dish else "",
        "point" to OriginDiningPoint(location.x, location.y, location.z, location.yaw), "context" to context.name,
        "transform" to mapOf("translation" to listOf(transform.translation.x, transform.translation.y, transform.translation.z),
            "left_rotation" to listOf(transform.leftRotation.x, transform.leftRotation.y, transform.leftRotation.z, transform.leftRotation.w),
            "scale" to listOf(transform.scale.x, transform.scale.y, transform.scale.z),
            "right_rotation" to listOf(transform.rightRotation.x, transform.rightRotation.y, transform.rightRotation.z, transform.rightRotation.w)))
}
