package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
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
        (carrier.z - carrier.depth / 2 > core.z + .30) shouldBe true
        (abs(core.rotationX) > 1) shouldBe true
        val assembled = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER,
            timing.start(SlimefunWorkshopPhase.INSPECT).toDouble(), timing).parts.associateBy { it.key }
        assembled.keys.count { it.startsWith("product-band-") } shouldBe 12
        assembled.getValue("product-carrier").z shouldBe (assembled.getValue("product-core").z plusOrMinus 1e-9)
        val lowered = slimefunWorkshopFrame(SlimefunWorkshopMachine.WINDER,
            timing.start(SlimefunWorkshopPhase.UNLOAD).toDouble(), timing).parts.associateBy { it.key }
        lowered.getValue("product-core").y shouldBe (1.31 plusOrMinus 1e-9)
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
            (carrier.x - carrier.width / 2 > last.getValue("receiver-wall-1.04").x + .04) shouldBe true
            (carrier.x + carrier.width / 2 < last.getValue("receiver-wall-2.18").x - .04) shouldBe true
            slimefunWorkshopFrame(machine, timing.total.toDouble(), timing).parts.none { it.key.startsWith("product-") } shouldBe true
        }
    }

    "station yaw rotates the machine, operator and contact in the same frame" {
        val north = SlimefunWorkshopStation("press", SlimefunWorkshopMachine.PRESS, 462,
            -10.5, 71.0, -45.5, 0.0, 1.0, SlimefunWorkshopPoint(-.65, 0.0, -1.45), 20)
        val south = north.copy(id = "winder", machine = SlimefunWorkshopMachine.WINDER, npcId = 463,
            z = -59.5, yaw = 180.0)
        north.point(north.operator).x shouldBe (-11.15 plusOrMinus 1e-9)
        north.point(north.operator).z shouldBe (-46.95 plusOrMinus 1e-9)
        south.point(south.operator).x shouldBe (-9.85 plusOrMinus 1e-9)
        south.point(south.operator).z shouldBe (-58.05 plusOrMinus 1e-9)
        val contact = SlimefunWorkshopPoint(.24, 1.47, -.155)
        north.point(contact).x + south.point(contact).x shouldBe (-21.0 plusOrMinus 1e-9)
        north.point(contact).z + south.point(contact).z shouldBe (-105.0 plusOrMinus 1e-9)
    }
})
