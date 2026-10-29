package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs

class OriginSlimefunWorkshopGeometryTest : FreeSpec({
    val timing = SlimefunWorkshopTiming()

    "three press strokes contact the seated copper band while retaining the workpiece" {
        val start = timing.start(SlimefunWorkshopPhase.PROCESS)
        for (index in 0..2) {
            val frame = slimefunWorkshopFrame(SlimefunWorkshopMachine.PRESS, start + timing.process * (index + .5) / 3, timing)
            val pieces = frame.parts.associateBy { it.key }
            val shoe = pieces.getValue("press-shoe")
            val band = pieces.getValue("product-band-$index-0")
            frame.contactIndex shouldBe index
            (shoe.y - shoe.height / 2) shouldBe (band.y + band.height / 2 plusOrMinus 1e-9)
            frame.contact!!.y shouldBe (band.y + band.height / 2 plusOrMinus 1e-9)
            pieces.getValue("product-core").x shouldBe 0.0
            pieces.getValue("product-carrier").y shouldBe (1.115 plusOrMinus 1e-9)
        }
    }

    "winder withdraws the carrier before rotating and returns it before lowering the complete component" {
        val process = timing.start(SlimefunWorkshopPhase.PROCESS)
        val winding = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER, process + timing.process * .4, timing)
            .parts.associateBy { it.key }
        val carrier = winding.getValue("product-carrier")
        val core = winding.getValue("product-core")
        val rotatingBottom = winding.values.filter { it.key.startsWith("product-") && it.key != "product-carrier" &&
            !it.key.startsWith("product-terminal-") }.minOf { slimefunBounds(it).first.y }
        (carrier.y + carrier.height / 2 < rotatingBottom) shouldBe true
        (abs(core.rotationX) > 1) shouldBe true
        val assembled = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER,
            timing.start(SlimefunWorkshopPhase.INSPECT).toDouble(), timing).parts.associateBy { it.key }
        assembled.keys.count { it.startsWith("product-band-") } shouldBe 12
        assembled.getValue("product-carrier").z shouldBe (assembled.getValue("product-core").z plusOrMinus 1e-9)
        val lowered = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER,
            timing.start(SlimefunWorkshopPhase.UNLOAD).toDouble(), timing).parts.associateBy { it.key }
        lowered.getValue("product-core").y shouldBe (1.26 plusOrMinus 1e-9)
    }

    "both loops keep bounded stable parts and settle the product inside a real receiving bin before reset" {
        for (machine in SlimefunWorkshopMachine.entries) {
            for (age in 0..timing.total step 2) {
                val frame = slimefunWorkshopFrame(machine, age.toDouble(), timing)
                frame.parts.map { it.key }.distinct().size shouldBe frame.parts.size
                (frame.parts.size <= 125) shouldBe true
                if (frame.phase != SlimefunWorkshopPhase.RESET)
                    frame.parts.count { it.key == "product-core" } shouldBe 1
            }
            val last = slimefunWorkshopFrame(machine,
                timing.start(SlimefunWorkshopPhase.RESET) - 1e-6, timing).parts.associateBy { it.key }
            val carrier = last.getValue("product-carrier")
            val receiver = last.getValue("receiver-bottom")
            (carrier.y - carrier.height / 2) shouldBe (receiver.y + receiver.height / 2 plusOrMinus 1e-8)
            if (machine == SlimefunWorkshopMachine.PRESS) {
                (carrier.x - carrier.width / 2 > last.getValue("receiver-wall-1.04").x + .04) shouldBe true
                (carrier.x + carrier.width / 2 < last.getValue("receiver-wall-2.18").x - .04) shouldBe true
            } else {
                (carrier.x - carrier.width / 2 > last.getValue("receiver-left-back").x + .04) shouldBe true
                (carrier.x + carrier.width / 2 < last.getValue("cabinet-side-1").x - .06) shouldBe true
            }
            slimefunWorkshopFrame(machine, timing.total.toDouble(), timing).parts.none { it.key.startsWith("product-") } shouldBe true
        }
    }

    "the compact rotary winder has no conveyor and its workpiece clears supports throughout the cycle" {
        val obstacles = setOf("intake-hood", "receiver-roof", "receiver-front-lip", "receiver-left-back",
            "cabinet-front", "cabinet-back", "cabinet-side--1", "cabinet-side-1")
        for (age in 0..timing.total step 2) {
            val parts = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER, age.toDouble(), timing).parts
            parts.none { it.key.startsWith("feed-roller-") || it.key.startsWith("lower-rail-") } shouldBe true
            val bounds = parts.map(::slimefunBounds)
            (bounds.maxOf { it.second.x } - bounds.minOf { it.first.x } < 3.0) shouldBe true
            val supports = parts.filter { it.key in obstacles || it.key.startsWith("spindle-pedestal-") ||
                it.key.startsWith("spindle-arm-") || it.key.startsWith("spindle-crossarm-") || it.key.startsWith("guide-post-") }
            val workpiece = parts.filter { it.key.startsWith("product-") && !it.key.startsWith("product-terminal-") }
            for (part in workpiece) for (support in supports) {
                slimefunOverlaps(slimefunBounds(part), slimefunBounds(support)) shouldBe false
            }
        }
        val press = slimefunWorkshopFrame(SlimefunWorkshopMachine.PRESS, 0.0, timing).parts.map(::slimefunBounds)
        (press.maxOf { it.second.x } - press.minOf { it.first.x } > 4.0) shouldBe true
        press.maxOf { it.second.y } shouldBe (3.0 plusOrMinus 1e-9)
        val load = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER, timing.load / 2.0, timing)
            .parts.single { it.key == "product-core" }
        // The stock follows a supported arc around the central pivot, rather than the press's straight feed.
        (load.x < -.4 && load.z < 0.0) shouldBe true
    }

    "station yaw rotates the machine, operator and contact in the same frame" {
        val north = SlimefunWorkshopStation("press", SlimefunWorkshopMachine.PRESS, 462,
            -10.5, 71.0, -43.55, 0.0, 1.0, SlimefunWorkshopPoint(-.65, 0.0, -1.45), 20)
        val south = north.copy(id = "winder", machine = SlimefunWorkshopMachine.WINDER, npcId = 463,
            z = -60.65, yaw = 180.0)
        north.point(north.operator).x shouldBe (-11.15 plusOrMinus 1e-9)
        north.point(north.operator).z shouldBe (-45.0 plusOrMinus 1e-9)
        south.point(south.operator).x shouldBe (-9.85 plusOrMinus 1e-9)
        south.point(south.operator).z shouldBe (-59.20 plusOrMinus 1e-9)
        val contact = SlimefunWorkshopPoint(.24, 1.47, -.155)
        north.point(contact).x + south.point(contact).x shouldBe (-21.0 plusOrMinus 1e-9)
        north.point(contact).z + south.point(contact).z shouldBe (-104.2 plusOrMinus 1e-9)
    }
})

private fun slimefunBounds(part: SlimefunWorkshopPart): Pair<SlimefunWorkshopPoint, SlimefunWorkshopPoint> {
    val rotation = Quaterniond().rotationXYZ(Math.toRadians(part.rotationX), Math.toRadians(part.rotationY), Math.toRadians(part.rotationZ))
    val axes = listOf(Vector3d(part.width / 2, 0.0, 0.0), Vector3d(0.0, part.height / 2, 0.0),
        Vector3d(0.0, 0.0, part.depth / 2)).map { it.rotate(rotation) }
    val x = axes.sumOf { abs(it.x) }; val y = axes.sumOf { abs(it.y) }; val z = axes.sumOf { abs(it.z) }
    return SlimefunWorkshopPoint(part.x - x, part.y - y, part.z - z) to
        SlimefunWorkshopPoint(part.x + x, part.y + y, part.z + z)
}

private fun slimefunOverlaps(a: Pair<SlimefunWorkshopPoint, SlimefunWorkshopPoint>, b: Pair<SlimefunWorkshopPoint, SlimefunWorkshopPoint>): Boolean =
    minOf(a.second.x, b.second.x) - maxOf(a.first.x, b.first.x) > 1e-7 &&
        minOf(a.second.y, b.second.y) - maxOf(a.first.y, b.first.y) > 1e-7 &&
        minOf(a.second.z, b.second.z) - maxOf(a.first.z, b.first.z) > 1e-7
