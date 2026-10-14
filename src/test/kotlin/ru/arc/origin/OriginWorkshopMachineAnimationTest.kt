package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OriginWorkshopMachineAnimationTest : FreeSpec({
    val dimensions = OriginWorkshopTableDimensions.DEFAULT
    val tuning = OriginWorkshopMachineTuning()

    "continuous drives own model parts separately from work and close their loop without a jump" {
        val mechanisms = mapOf(
            OriginWorkshopTableRole.CARPENTER to listOf(
                OriginWorkshopMechanism.SAW,
                OriginWorkshopMechanism.DRILL,
                OriginWorkshopMechanism.CLAMP_LEFT,
                OriginWorkshopMechanism.CLAMP_RIGHT,
            ),
            OriginWorkshopTableRole.UPHOLSTERER to listOf(OriginWorkshopMechanism.PRESS),
            OriginWorkshopTableRole.ASSEMBLER to listOf(OriginWorkshopMechanism.VISE, OriginWorkshopMechanism.ANVIL),
            OriginWorkshopTableRole.FINISHER to listOf(OriginWorkshopMechanism.FINISH),
        )
        for (role in OriginWorkshopTableRole.entries) {
            val geometry = originWorkshopTablePieces(role, 0).associateBy { it.key }
            val start = originWorkshopDrivePose(role, 0.0, dimensions, tuning)
            start.pieces.isNotEmpty() shouldBe true
            val workKeys = mechanisms.getValue(role).flatMap { originWorkshopMachinePose(it, 0.5, 0.65, dimensions, tuning).pieces.keys }.toSet()
            start.pieces.keys.intersect(workKeys) shouldBe emptySet()
            for (phase in listOf(0.0, 0.125, 0.25, 0.5, 0.75, 1.0)) {
                val pose = originWorkshopDrivePose(role, phase, dimensions, tuning)
                pose.pieces.keys shouldBe start.pieces.keys
                geometry.keys.containsAll(pose.pieces.keys + workKeys) shouldBe true
                pose.contact shouldBe null
                for ((key, motion) in pose.pieces) {
                    val piece = geometry.getValue(key)
                    val offset = motion.centerOffset
                    listOf(offset.x, offset.y, offset.z, motion.rotationDegrees).all(Double::isFinite) shouldBe true
                    val angle = Math.toRadians(motion.rotationDegrees)
                    val verticalSize = when (motion.rotationAxis) {
                        OriginWorkshopRotationAxis.Z -> abs(sin(angle)) * piece.width + abs(cos(angle)) * piece.height
                        OriginWorkshopRotationAxis.X -> abs(sin(angle)) * piece.depth + abs(cos(angle)) * piece.height
                        else -> piece.height
                    }
                    (piece.y + offset.y - verticalSize / 2.0 > dimensions.height) shouldBe true
                    if (phase == 1.0) {
                        (abs(offset.x) + abs(offset.y) + abs(offset.z) < 1e-9) shouldBe true
                        (abs(motion.rotationDegrees % 360.0) < 1e-9) shouldBe true
                    }
                }
            }
        }
    }

    "sewing needle meets fabric and the saw feed rollers stay above their board" {
        val sewing = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0).associateBy { it.key }
        val needle = sewing.getValue("upholsterer-drive-needle")
        val cloth = sewing.getValue("upholsterer-sewing-fabric")
        val stroke = originWorkshopDrivePose(OriginWorkshopTableRole.UPHOLSTERER, 0.25, dimensions, tuning)
        val needleBottom = needle.y + stroke.pieces.getValue(needle.key).centerOffset.y - needle.height / 2.0
        (abs(needleBottom - cloth.y - cloth.height / 2.0) < 1e-9) shouldBe true
        val fabricRoller = sewing.getValue("upholsterer-drive-fabric-roller-0")
        (fabricRoller.y - kotlin.math.hypot(fabricRoller.width, fabricRoller.height) / 2.0 >= cloth.y + cloth.height / 2.0) shouldBe true
        val saw = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0).associateBy { it.key }
        val board = saw.getValue("carpenter-board-in-feed")
        val roller = saw.getValue("carpenter-drive-feed-roller-0")
        (roller.y - kotlin.math.hypot(roller.width, roller.height) / 2.0 >= board.y + board.height / 2.0) shouldBe true
    }

    "finisher withdraws a rack panel before laying it on the table and returns to the same slot" {
        val panel = originWorkshopTablePieces(OriginWorkshopTableRole.FINISHER, 0)
            .single { it.key == "finisher-drying-panel-center" }
        for (progress in listOf(0.0, 1.0)) {
            val motion = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, progress, 0.0, dimensions, tuning)
                .pieces.getValue(panel.key)
            (abs(motion.centerOffset.x) + abs(motion.centerOffset.y) + abs(motion.centerOffset.z) < 1e-9) shouldBe true
            (abs(motion.rotationDegrees) < 1e-9) shouldBe true
        }
        val withdrawn = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, 0.10, 0.0, dimensions, tuning)
            .pieces.getValue(panel.key)
        (abs(withdrawn.centerOffset.x) < 1e-9) shouldBe true
        (withdrawn.centerOffset.z < -0.4) shouldBe true
        val flat = originWorkshopMachinePose(OriginWorkshopMechanism.FINISH, 0.5, 0.0, dimensions, tuning)
            .pieces.getValue(panel.key)
        (abs(panel.y + flat.centerOffset.y - panel.depth / 2.0 - dimensions.height - 0.01) < 1e-9) shouldBe true
        flat.rotationDegrees shouldBe -90.0
    }

    "saw feed advances along the blade plane and rotating rows keep their radius" {
        val poses = listOf(0.25, 0.5).map { progress ->
            originWorkshopMachinePose(OriginWorkshopMechanism.SAW, progress, 0.0, dimensions, tuning)
        }
        for (pose in poses) for (index in 0..8) {
            val baselineY = (index - 4) * 0.095
            val offset = pose.pieces.getValue("carpenter-saw-blade-row-$index").centerOffset
            val radius = kotlin.math.hypot(offset.x, baselineY + offset.y)
            (abs(radius - abs(baselineY)) < 1e-9) shouldBe true
        }

        val centers = listOf(0.0, 0.5, 1.0).map { progress ->
            tuning.sawFeedStartX + originWorkshopMachinePose(
                OriginWorkshopMechanism.SAW, progress, 0.0, dimensions, tuning,
            ).pieces.getValue("carpenter-board-in-feed").centerOffset.x
        }
        (centers[0] < centers[1] && centers[1] < centers[2]) shouldBe true
        val contact = poses.first().contact!!
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0, dimensions, tuning)
        val board = pieces.single { it.key == "carpenter-board-in-feed" }
        val lowestBlade = pieces.filter { it.key.startsWith("carpenter-saw-blade-row-") }
            .minOf { it.y - it.height / 2.0 }
        (contact.y in (lowestBlade..(board.y + board.height / 2.0))) shouldBe true
        for (progress in listOf(0.0, 0.5, 1.0)) {
            originWorkshopCraftMachinePose("saw", progress, dimensions, tuning)
                .pieces.getValue("carpenter-board-in-feed").visible shouldBe false
        }
    }

    "drill wheel keeps its radius and the bit meets an 0.08-block workpiece on the downstroke" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, 0, dimensions, tuning)
            .associateBy { it.key }
        val bit = pieces.getValue("carpenter-drill-bit")
        // The raised tip clears the real player board; the stroke passes its underside.
        val boardTop = dimensions.height + 0.18
        (bit.y - bit.height / 2.0 > boardTop) shouldBe true
        val contact = originWorkshopMachinePose(OriginWorkshopMechanism.DRILL, 0.5, 0.0, dimensions, tuning).contact!!
        val drill = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { progress ->
            progress to originWorkshopMachinePose(OriginWorkshopMechanism.DRILL, progress, 0.0, dimensions, tuning)
        }
        drill.forEach { (progress, pose) ->
            pieces.keys.containsAll(pose.pieces.keys) shouldBe true
            if (progress == 0.0 || progress == 1.0) {
                abs(pose.pieces.getValue("carpenter-drill-bit").centerOffset.y) shouldBe 0.0
                abs(pose.pieces.getValue("carpenter-drill-quill").centerOffset.y) shouldBe 0.0
                abs(pose.pieces.getValue("carpenter-drill-bit").rotationDegrees % 360.0) shouldBe 0.0
            }
        }
        val downstroke = drill.single { it.first == 0.5 }.second
        val bitBottom = bit.y + downstroke.pieces.getValue(bit.key).centerOffset.y - bit.height / 2.0
        (abs(bitBottom - contact.y) < 1e-9) shouldBe true
        (abs(contact.y - (dimensions.height + 0.06)) < 1e-9) shouldBe true

        for (progress in listOf(0.25, 0.5)) {
            for (index in 0..7) {
                val baselineY = (index - 3.5) * 0.14 / 4.0
                val offset = originWorkshopMachinePose(OriginWorkshopMechanism.DRILL, progress, 0.0, dimensions, tuning)
                    .pieces.getValue("carpenter-drill-control-wheel-rim-$index").centerOffset
                (abs(kotlin.math.hypot(offset.x, baselineY + offset.y) - abs(baselineY)) < 1e-9) shouldBe true
                (abs(offset.z) < 1e-9) shouldBe true
                drill.single { it.first == progress }.second.pieces
                    .getValue("carpenter-drill-control-wheel-rim-$index").rotationDegrees shouldBe 720.0 * progress
            }
        }
    }

    "separate clamps move inward and keep each completed jaw closed until craft reset" {
        val leftOpen = originWorkshopMachinePose(OriginWorkshopMechanism.CLAMP_LEFT, 0.0, 0.0, dimensions, tuning)
        val leftClosed = originWorkshopMachinePose(OriginWorkshopMechanism.CLAMP_LEFT, 1.0, 0.0, dimensions, tuning)
        val rightOpen = originWorkshopMachinePose(OriginWorkshopMechanism.CLAMP_RIGHT, 0.0, 0.0, dimensions, tuning)
        val rightClosed = originWorkshopMachinePose(OriginWorkshopMechanism.CLAMP_RIGHT, 1.0, 0.0, dimensions, tuning)
        leftOpen.pieces.getValue("carpenter-assembly-clamp-left").centerOffset.x shouldBe 0.0
        rightOpen.pieces.getValue("carpenter-assembly-clamp-right").centerOffset.x shouldBe 0.0
        (leftClosed.pieces.getValue("carpenter-assembly-clamp-left").centerOffset.x > 0.0) shouldBe true
        (rightClosed.pieces.getValue("carpenter-assembly-clamp-right").centerOffset.x < 0.0) shouldBe true
        leftClosed.pieces.getValue("carpenter-assembly-clamp-left").centerOffset.x shouldBe 0.12
        rightClosed.pieces.getValue("carpenter-assembly-clamp-right").centerOffset.x shouldBe -0.12
        leftClosed.pieces.getValue("carpenter-assembly-clamp-control-left").centerOffset.x shouldBe 0.12
        rightClosed.pieces.getValue("carpenter-assembly-clamp-control-right").centerOffset.x shouldBe -0.12
    }

    "vise starts and ends open, then closes both jaws onto the board" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, 0, dimensions, tuning)
            .associateBy { it.key }
        val board = pieces.getValue("assembler-board-sample")
        val left = pieces.getValue("assembler-clamp-left")
        val right = pieces.getValue("assembler-clamp-right")

        for (progress in listOf(0.0, 1.0)) {
            val pose = originWorkshopMachinePose(OriginWorkshopMechanism.VISE, progress, 0.0, dimensions, tuning)
            pose.pieces.getValue("assembler-clamp-left").centerOffset.x shouldBe 0.0
            pose.pieces.getValue("assembler-clamp-right").centerOffset.x shouldBe 0.0
        }
        val closed = originWorkshopMachinePose(OriginWorkshopMechanism.VISE, 0.5, 0.0, dimensions, tuning)
        val leftContact = left.x + closed.pieces.getValue("assembler-clamp-left").centerOffset.x + left.width / 2.0
        val rightContact = right.x + closed.pieces.getValue("assembler-clamp-right").centerOffset.x - right.width / 2.0
        (abs(leftContact - (board.x - board.width / 2.0)) < 1e-9) shouldBe true
        (abs(rightContact - (board.x + board.width / 2.0)) < 1e-9) shouldBe true
        closed.pieces.getValue("assembler-board-sample").visible shouldBe true
    }

    "hammer head reaches the workpiece on the contact stroke and returns to its hinge rest" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, 0, dimensions, tuning)
            .associateBy { it.key }
        val head = pieces.getValue("assembler-hammer-head")
        val workpiece = pieces.getValue("assembler-anvil-workpiece")
        val strike = originWorkshopMachinePose(OriginWorkshopMechanism.ANVIL, 0.5, 0.65, dimensions, tuning)
        val angle = Math.toRadians(strike.pieces.getValue("assembler-hammer-head").rotationDegrees)
        val headCenterY = tuning.hammerPivotYOffset - tuning.hammerArmLength * sin(angle)
        val rotatedHalfHeight = (
            head.height * abs(cos(angle)) + head.depth * abs(sin(angle))
        ) / 2.0
        val workpieceTop = workpiece.y + workpiece.height / 2.0
        (abs(dimensions.height + headCenterY - rotatedHalfHeight - workpieceTop) < 0.003) shouldBe true
        (abs(strike.contact!!.y - workpieceTop) < 1e-9) shouldBe true

        for (stroke in listOf(0.0, 1.0)) {
            val rest = originWorkshopMachinePose(OriginWorkshopMechanism.ANVIL, 0.5, stroke, dimensions, tuning)
            val hammer = rest.pieces.getValue("assembler-hammer-head")
            hammer.centerOffset shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
            hammer.rotationDegrees shouldBe tuning.hammerRestAngleDegrees
        }
    }

    "player press moves through a full stroke and every machine hides its autonomous sample" {
        val middle = originWorkshopCraftMachinePose("press", 0.65, dimensions, tuning)
        (middle.pieces.getValue("upholsterer-press-platen").centerOffset.y < -0.10) shouldBe true
        for ((machine, key) in mapOf("saw" to "carpenter-board-in-feed", "press" to "upholsterer-press-cloth",
            "vise" to "assembler-board-sample", "anvil" to "assembler-anvil-workpiece")) {
            for (p in listOf(0.0, 0.5, 1.0))
                originWorkshopCraftMachinePose(machine, p, dimensions, tuning).pieces.getValue(key).visible shouldBe false
        }
        originWorkshopCraftMachinePose("press", 1.0, dimensions, tuning).pieces.getValue("upholsterer-press-platen").centerOffset.y shouldBe 0.0
    }

    "session sewing cycles the needle five times and clears it at both ends" {
        val start = originWorkshopCraftMachinePose("sewing", 0.0, dimensions, tuning)
        val middle = originWorkshopCraftMachinePose("sewing", 0.10, dimensions, tuning)
        val finish = originWorkshopCraftMachinePose("sewing", 1.0, dimensions, tuning)
        val needleKey = "upholsterer-drive-needle"
        val needle = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0, dimensions, tuning)
            .single { it.key == needleKey }

        start.pieces.getValue(needleKey).centerOffset.y shouldBe 0.0
        start.pieces.getValue("upholsterer-sewing-foot-toe-left").centerOffset.y shouldBe -0.10
        start.pieces.getValue("upholsterer-sewing-foot-lifter").rotationDegrees shouldBe 15.0
        middle.pieces.getValue(needleKey).centerOffset.y shouldBe -0.08
        middle.pieces.getValue("upholsterer-sewing-foot-toe-right").centerOffset.y shouldBe -0.10
        finish.pieces.getValue(needleKey).centerOffset.y shouldBe 0.0
        finish.pieces.getValue("upholsterer-sewing-foot-toe-left").centerOffset.y shouldBe 0.0
        finish.pieces.getValue("upholsterer-sewing-foot-lifter").rotationDegrees shouldBe 0.0
        finish.pieces.getValue("upholsterer-drive-handwheel-spoke-x").rotationDegrees shouldBe 1800.0
        finish.pieces.getValue("upholsterer-drive-fabric-roller-0").rotationDegrees shouldBe -1800.0
        finish.pieces.getValue("upholsterer-drive-shuttle").centerOffset.x shouldBe 0.0

        val lowestNeedleBottom = needle.y - needle.height / 2.0 + middle.pieces.getValue(needleKey).centerOffset.y
        (abs(lowestNeedleBottom - (dimensions.height + 0.14)) < 1e-9) shouldBe true
        // Session cloth top is h+.152; the needle penetrates by .012 before retracting.
        (abs((dimensions.height + 0.152) - lowestNeedleBottom - 0.012) < 1e-9) shouldBe true
    }

    "sewing-foot hand lever lowers the two-prong presser foot onto the session cloth" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0, dimensions, tuning)
            .associateBy { it.key }
        val footLeft = pieces.getValue("upholsterer-sewing-foot-toe-left")
        val footRight = pieces.getValue("upholsterer-sewing-foot-toe-right")
        val needle = pieces.getValue("upholsterer-drive-needle")
        val bridge = pieces.getValue("upholsterer-sewing-foot-bridge")
        val shank = pieces.getValue("upholsterer-sewing-foot-shank")
        val arm = pieces.getValue("upholsterer-sewing-arm")
        val lifter = pieces.getValue("upholsterer-sewing-foot-lifter")
        val lowered = originWorkshopCraftMachinePose("sewing-foot", 1.0, dimensions, tuning)

        val gap = footRight.x - footRight.width / 2.0 - (footLeft.x + footLeft.width / 2.0)
        (gap > needle.width) shouldBe true
        for (toe in listOf(footLeft, footRight)) {
            val motion = lowered.pieces.getValue(toe.key)
            (abs(toe.y + motion.centerOffset.y - toe.height / 2.0 - dimensions.height - 0.154) < 1e-9) shouldBe true
        }
        (abs(bridge.y - bridge.height / 2.0 - (footLeft.y + footLeft.height / 2.0)) < 1e-9) shouldBe true
        (abs(bridge.z - bridge.depth / 2.0 - (footRight.z + footRight.depth / 2.0)) < 1e-9) shouldBe true
        (abs(shank.y + shank.height / 2.0 - (arm.y - arm.height / 2.0)) < 1e-9) shouldBe true
        (abs(shank.y - shank.height / 2.0 - (bridge.y + bridge.height / 2.0)) < 1e-9) shouldBe true
        val bridgeCoversShankX = bridge.x - bridge.width / 2.0 < shank.x + shank.width / 2.0 &&
            bridge.x + bridge.width / 2.0 > shank.x - shank.width / 2.0
        val bridgeCoversShankZ = bridge.z - bridge.depth / 2.0 < shank.z + shank.depth / 2.0 &&
            bridge.z + bridge.depth / 2.0 > shank.z - shank.depth / 2.0
        (bridgeCoversShankX && bridgeCoversShankZ) shouldBe true
        lifter.material shouldBe org.bukkit.Material.COPPER_BLOCK
        lifter.x shouldBe 0.515
        lifter.y shouldBe dimensions.height + 0.24
        lifter.z shouldBe -0.58
        val lifterToeGap = lifter.x - lifter.width / 2.0 - (footRight.x + footRight.width / 2.0)
        (abs(lifterToeGap - 0.005) < 1e-9) shouldBe true
        val shankBottom = shank.y - shank.height / 2.0
        val lifterTop = lifter.y + lifter.height / 2.0
        (abs(shankBottom - lifterTop - 0.014) < 1e-9) shouldBe true
        lowered.pieces.getValue(lifter.key).rotationDegrees shouldBe 15.0
        val lifterRear = lifter.z + abs(cos(Math.toRadians(15.0)) * lifter.depth / 2.0) +
            abs(sin(Math.toRadians(15.0)) * lifter.height / 2.0)
        (bridge.z - bridge.depth / 2.0 - lifterRear > 0.012) shouldBe true
        val clothTop = dimensions.height + 0.152
        (abs((dimensions.height + 0.154) - clothTop - 0.002) < 1e-9) shouldBe true

        val raised = originWorkshopCraftMachinePose("sewing-foot", 0.0, dimensions, tuning)
        raised.pieces.getValue(footLeft.key).centerOffset.y shouldBe 0.0
        raised.pieces.getValue(lifter.key).rotationDegrees shouldBe 0.0
    }

    "press platen touches compressed cloth and releases at stroke ends" {
        val pieces = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0, dimensions, tuning)
            .associateBy { it.key }
        val platen = pieces.getValue("upholsterer-press-platen")
        val cloth = pieces.getValue("upholsterer-press-cloth")
        val press = originWorkshopMachinePose(OriginWorkshopMechanism.PRESS, 0.5, 0.65, dimensions, tuning)
        val platenBottom = platen.y + press.pieces.getValue("upholsterer-press-platen").centerOffset.y - platen.height / 2.0
        val clothMotion = press.pieces.getValue("upholsterer-press-cloth")
        val clothCenter = cloth.y + clothMotion.centerOffset.y
        val clothTop = clothCenter + cloth.height * clothMotion.scaleYFactor / 2.0
        (abs(platenBottom - clothTop) < 1e-9) shouldBe true
        (abs(press.contact!!.y - clothTop) < 1e-9) shouldBe true

        for (stroke in listOf(0.0, 1.0)) {
            val rest = originWorkshopMachinePose(OriginWorkshopMechanism.PRESS, 0.5, stroke, dimensions, tuning)
            (abs(rest.pieces.getValue("upholsterer-press-platen").centerOffset.y) < 1e-9) shouldBe true
            rest.pieces.getValue("upholsterer-press-cloth").scaleYFactor shouldBe 1.0
        }
    }

    "idle reset keeps transient material hidden and NONE has no moving pose" {
        ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES shouldBe setOf(
            "carpenter-board-in-feed",
            "assembler-board-sample",
            "assembler-anvil-workpiece",
            "upholsterer-press-cloth",
        )
        val idle = originWorkshopMachinePose(OriginWorkshopMechanism.NONE, 0.0, 0.0, dimensions, tuning)
        idle.pieces shouldBe emptyMap()
        idle.contact shouldBe null
        WORKSHOP_MACHINE_INTERPOLATION_TICKS shouldBe 2
    }
})
