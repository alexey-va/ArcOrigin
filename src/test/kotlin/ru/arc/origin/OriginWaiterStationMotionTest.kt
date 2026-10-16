package ru.arc.origin

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

class OriginWaiterStationMotionTest : StringSpec({
    "wash motions continuously move a grounded plate and finish exactly on their next support" {
        for (yaw in listOf(0f, 90f, 180f, 37f)) {
            val layout = OriginWaiterStationsLayout(OriginDiningPoint(0.0, 1.0, 0.0, yaw),
                OriginDiningPoint(0.0, 1.0, 2.0, yaw), OriginDiningPoint(1.0, 1.0, 2.0, yaw))
            val sink = waiterStationSinkPoint(layout)
            val tool = waiterStationPoint(layout.surface(layout.sink), 2, 3)
            for (action in OriginWaiterWash.entries) {
                val poses = (0..32).map { waiterStationActionFrame("wash:$action", layout, it / 32.0, sink, tool,
                    stackHeight = 0.08125, plateHeight = 0.08125) }
                poses.first().plate shouldBe sink
                val end = if (action == OriginWaiterWash.DRY) layout.rack.copy(y = 1.08125) else sink
                distance(poses.last().plate, end).let { it < 1e-8 } shouldBe true
                poses.distinct().size.let { it > 15 } shouldBe true
                poses.zipWithNext().all { (a, b) -> distance(a.plate, b.plate) < 0.22 } shouldBe true
                poses.all { it.plate.y >= 1.0 - 1e-8 } shouldBe true
            }
        }
    }

    "every working tool touches its plate top and the dry cloth leaves before rack transfer" {
        val config = waiterTestConfig()
        for (venue in config.venues) {
            val layout = config.stations.getValue(venue.id)
            val sink = waiterStationSinkPoint(layout)
            for (height in listOf(0.08125, 0.040625)) for (action in OriginWaiterWash.entries) {
                val job = OriginWaiterJob(waiterTarget(venue.id), 0, 180_000,
                    stage = OriginWaiterStage.WASH, washing = listOf(action))
                val tool = waiterStationChoices(config, venue, job).single { it.next }.point
                tool shouldBe waiterStationPoint(layout.surface(layout.sink), 2, 3)
                for (step in 12..if (action == OriginWaiterWash.DRY) 47 else 86) {
                    val frame = waiterStationActionFrame("wash:$action", layout, step / 100.0, sink, tool,
                        stackHeight = height * 2, plateHeight = height)
                    // Official generated mesh z=7.5..8.5, NONE / X(-90), runtime scale .48.
                    abs(checkNotNull(frame.tool).y - 0.015 - (frame.plate.y + height)).let { it < 1e-8 } shouldBe true
                }
                val end = waiterStationActionFrame("wash:$action", layout, 1.0, sink, tool,
                    stackHeight = height * 2, plateHeight = height)
                distance(checkNotNull(end.tool), tool).let { it < 1e-8 } shouldBe true
                if (action == OriginWaiterWash.DRY) {
                    for (step in 48..66) {
                        waiterStationActionFrame("wash:DRY", layout, step / 100.0, sink, tool,
                            stackHeight = height * 2, plateHeight = height).plate shouldBe sink
                    }
                    val withdrawing = waiterStationActionFrame("wash:DRY", layout, 0.66, sink, tool,
                        stackHeight = height * 2, plateHeight = height)
                    distance(checkNotNull(withdrawing.tool), tool).let { it < 1e-8 } shouldBe true
                    distance(checkNotNull(end.tool), end.plate).let { it > 0.5 } shouldBe true
                }
            }
        }
    }

    "return to sink and ingredient transfer preserve their initial and terminal positions" {
        val layout = waiterTestConfig().stations.getValue("brewery")
        val sink = waiterStationSinkPoint(layout)
        val carry = sink.copy(x = sink.x + 0.7, y = sink.y + 0.1, z = sink.z - 0.6, yaw = 26f)
        val tool = waiterStationPoint(layout.surface(layout.sink), 2, 3)
        waiterStationActionFrame("wash:SCRAPE", layout, 0.0, carry, tool, returning = true).plate shouldBe carry
        distance(waiterStationActionFrame("wash:SCRAPE", layout, 0.28, carry, tool, returning = true).plate, sink)
            .let { it < 1e-8 } shouldBe true
        val ingredient = waiterStationIngredientPoint(layout, 0, 3)
        waiterStationActionFrame("ingredient:egg", layout, 0.0, sink, ingredient, ingredient).ingredient shouldBe ingredient
        val end = waiterStationActionFrame("ingredient:egg", layout, 1.0, sink, ingredient, ingredient)
        end.plate shouldBe waiterStationPlatePoint(layout)
        abs(checkNotNull(end.ingredient).y - end.plate.y - 0.10).let { it < 1e-8 } shouldBe true
    }

    "cooking visibly plates before ready and shares one nearby output contact" {
        val layout = waiterTestConfig().stations.getValue("brewery").copy(cook = OriginDiningPoint(-3.0, 71.0, 63.5))
        val frames = (0..120).map { waiterStationCookingFrame(layout, it / 120.0, it * 50L) }
        frames.take(116).all { !it.filled } shouldBe true
        frames.last().filled shouldBe true
        frames.map { it.plate }.distinct().size shouldBe 1
        frames.zipWithNext().all { (a, b) -> distance(checkNotNull(a.ingredient), checkNotNull(b.ingredient)) < 0.07 } shouldBe true
        distance(frames.last().plate, checkNotNull(layout.cook)).let { it < 0.5 } shouldBe true
    }

    "source preview exports exact 50ms runtime poses and model support inputs" {
        val config = waiterTestConfig()
        val layout = config.stations.getValue("brewery").copy(cook = OriginDiningPoint(-3.0, 71.0, 63.5))
        val sink = waiterStationSinkPoint(layout)
        val tool = waiterStationPoint(layout.surface(layout.sink), 2, 3)
        val recipe = config.recipes.getValue("egg")
        val clips = mutableListOf<Map<String, Any>>()
        fun clip(id: String, duration: Long, frame: (Double, Long) -> OriginWaiterStationFrame) {
            clips += mapOf("id" to id, "durationMillis" to duration, "sampleMillis" to 50,
                "frames" to (0L..duration step 50).map { time -> mapOf("timeMillis" to time, "pose" to frame(time.toDouble() / duration, time)) })
        }
        clip("cooking", 6_000) { p, t -> waiterStationCookingFrame(layout, p, t) }
        recipe.forEachIndexed { index, ingredient ->
            val source = waiterStationIngredientPoint(layout, index, recipe.size)
            clip("ingredient:$ingredient", 1_600) { p, _ -> waiterStationActionFrame("ingredient:$ingredient", layout,
                p, waiterStationPlatePoint(layout), source, source, ingredientIndex = index) }
        }
        OriginWaiterWash.entries.forEach { action ->
            val job = OriginWaiterJob(waiterTarget("brewery"), 0, 180_000,
                stage = OriginWaiterStage.WASH, washing = listOf(action))
            val actualTool = waiterStationChoices(config, config.venues.first(), job).single { it.next }.point
            clip("wash:$action", if (action == OriginWaiterWash.SOAK) 3_000 else 1_600) { p, _ ->
                waiterStationActionFrame("wash:$action", layout, p, sink, actualTool,
                    stackHeight = if (action == OriginWaiterWash.DRY) 0.08125 else 0.0, plateHeight = 0.08125) }
        }
        clip("return:SCRAPE", 1_600) { p, _ -> waiterStationActionFrame("wash:SCRAPE", layout, p,
            sink.copy(x = sink.x - 0.6, y = sink.y + 0.05, z = sink.z - 0.5, yaw = 0f), tool,
            returning = true, plateHeight = 0.08125) }
        val output = Path.of("build/reports/waiter-stations/motion.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "source" to "OriginWaiterStationMotion.kt runtime pose functions",
            "boundary" to "Source geometry, 50ms nominal tick sampling; not native client playback",
            "layout" to layout, "floorY" to 70.0, "clips" to clips)))
        clips.size shouldBe 10
    }
})

private fun distance(a: OriginDiningPoint, b: OriginDiningPoint) =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
