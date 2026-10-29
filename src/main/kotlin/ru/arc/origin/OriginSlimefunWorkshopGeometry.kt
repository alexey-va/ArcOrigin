package ru.arc.origin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure, source-exportable geometry. Local +X is material flow; -Z is the operator side. */
internal enum class SlimefunWorkshopMachine { PRESS, WINDER }

internal enum class SlimefunWorkshopPhase { LOAD, CLAMP, PROCESS, TERMINALS, INSPECT, UNLOAD, DEPOSIT, RESET }

internal data class SlimefunWorkshopTiming(
    val load: Int = 70,
    val clamp: Int = 30,
    val process: Int = 150,
    val terminals: Int = 40,
    val inspect: Int = 60,
    val unload: Int = 70,
    val deposit: Int = 40,
    val reset: Int = 40,
) {
    val durations = listOf(load, clamp, process, terminals, inspect, unload, deposit, reset)
    val total = durations.sum()

    init { require(durations.all { it in 20..1_200 }) { "Slimefun workshop phases must be within 20..1200 ticks" } }

    fun sample(age: Double): Pair<SlimefunWorkshopPhase, Double> {
        var remaining = age.coerceIn(0.0, total.toDouble())
        for ((index, duration) in durations.withIndex()) {
            if (remaining < duration || index == durations.lastIndex) {
                return SlimefunWorkshopPhase.entries[index] to (remaining / duration).coerceIn(0.0, 1.0)
            }
            remaining -= duration
        }
        error("Unreachable workshop phase")
    }

    fun start(phase: SlimefunWorkshopPhase): Int = durations.take(phase.ordinal).sum()
}

internal data class SlimefunWorkshopPoint(val x: Double, val y: Double, val z: Double)

internal data class SlimefunWorkshopPart(
    val key: String,
    val material: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Double,
    val height: Double,
    val depth: Double,
    val rotationX: Double = 0.0,
    val rotationY: Double = 0.0,
    val rotationZ: Double = 0.0,
) {
    init {
        require(listOf(x, y, z, width, height, depth, rotationX, rotationY, rotationZ).all(Double::isFinite))
        require(width > 0 && height > 0 && depth > 0)
    }
}

internal data class SlimefunWorkshopFrame(
    val phase: SlimefunWorkshopPhase,
    val parts: List<SlimefunWorkshopPart>,
    val focus: SlimefunWorkshopPoint,
    val contact: SlimefunWorkshopPoint?,
    val contactIndex: Int,
)

private fun ease(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
private fun ramp(progress: Double, from: Double, to: Double) = ease((progress - from) / (to - from))

/** Full frame, including bounded stock and a physical receiving chute. No items or rewards are created. */
internal fun slimefunWorkshopFrame(
    machine: SlimefunWorkshopMachine,
    age: Double,
    timing: SlimefunWorkshopTiming = SlimefunWorkshopTiming(),
): SlimefunWorkshopFrame = when (machine) {
    SlimefunWorkshopMachine.PRESS -> slimefunPressFrame(age, timing)
    SlimefunWorkshopMachine.WINDER -> slimefunWinderFrame(age, timing)
}

private fun slimefunPressFrame(age: Double, timing: SlimefunWorkshopTiming): SlimefunWorkshopFrame {
    val (phase, p) = timing.sample(age)
    val parts = mutableListOf<SlimefunWorkshopPart>()
    fun box(key: String, material: String, x: Double, y: Double, z: Double,
            width: Double, height: Double, depth: Double, rx: Double = 0.0, rz: Double = 0.0) {
        parts += SlimefunWorkshopPart(key, material, x, y, z, width, height, depth, rx, rotationZ = rz)
    }
    val copper = "CUT_COPPER"
    val frame = "POLISHED_DEEPSLATE"
    val accent = "WAXED_OXIDIZED_COPPER"

    // A low, open base lets the player read the complete load path at normal eye height.
    for (x in listOf(-1.83, 1.83)) for (z in listOf(-0.69, 0.69)) {
        box("foot-$x-$z", frame, x, .09, z, .42, .18, .42)
        box("leg-$x-$z", frame, x, .55, z, .23, .74, .23)
    }
    for (z in listOf(-.76, .76)) box("lower-rail-$z", frame, 0.0, .32, z, 3.66, .17, .16)
    for (x in listOf(-1.83, 1.83)) box("lower-cross-$x", copper, x, .32, 0.0, .16, .17, 1.36)
    for (i in 0..3) box("deck-$i", "SMOOTH_STONE", (i - 2) * .82, .95, 0.0, .82, .10, 1.8)
    box("deck-4", "SMOOTH_STONE", 1.64, .95, -.47, .82, .10, .86)
    for (z in listOf(-.96, .96)) box("rim-$z", copper, 0.0, 1.0, z, 4.30, .22, .12)
    for (x in listOf(-2.10, 2.10)) box("rim-$x", frame, x, 1.0, 0.0, .10, .22, 1.8)
    box("flow-guide-front", frame, -.05, 1.05, -.49, 3.91, .10, .065)
    box("flow-guide-back", frame, -.555, 1.05, .18, 2.9, .10, .065)
    for (i in 0..11) box("feed-roller-$i", "IRON_BLOCK", -1.87 + i * .32, 1.04, -.155, .18, .08, .52)

    // Left intake magazine and right closed receiving bin explain where each cycle begins and ends.
    box("intake-bed", frame, -1.60, 1.06, .48, .80, .12, .66)
    box("intake-back", frame, -1.60, 1.33, .88, .91, .66, .12)
    for (x in listOf(-2.02, -1.18)) box("intake-side-$x", copper, x, 1.28, .50, .07, .56, .64)
    for (i in 0..2) box("stock-blank-$i", "IRON_BLOCK", -1.60, 1.1625 + i * .085, .30, .70, .085, .25)
    for (i in 0..2) box("stock-copper-$i", copper, -1.60, 1.1525 + i * .065, .67, .62, .065, .18)
    box("terminal-tray", frame, -1.72, 1.04, -.75, .45, .08, .30)
    box("receiver-bottom", frame, 1.61, .52, .42, .98, .18, 1.20)
    for (x in listOf(1.04, 2.18)) box("receiver-wall-$x", accent, x, 1.03, .62, .08, .84, .84)
    box("receiver-back", frame, 1.61, 1.03, 1.09, 1.06, .84, .10)
    box("receiver-roof", copper, 1.61, 1.67, .67, 1.22, .12, .86)
    box("receiver-front-lip", frame, 1.61, .835, .18, 1.06, .45, .10)
    box("receiver-lamp", "SEA_LANTERN", 1.61, 1.76, .83, .20, .06, .12)

    val productX = when (phase) {
        SlimefunWorkshopPhase.LOAD -> -1.60 * (1 - ease(p))
        SlimefunWorkshopPhase.UNLOAD -> 1.61 * ease(p)
        SlimefunWorkshopPhase.DEPOSIT, SlimefunWorkshopPhase.RESET -> 1.61
        else -> 0.0
    }
    val productY = if (phase == SlimefunWorkshopPhase.DEPOSIT) 1.31 - .47 * ramp(p, .60, 1.0) else 1.31
    val productZ = if (phase == SlimefunWorkshopPhase.DEPOSIT) -.155 + .94 * ease(p) else -.155
    val productRotation = 0.0
    val carrierRetreat = 0.0
    val processing = phase == SlimefunWorkshopPhase.PROCESS
    val beforeProcessing = phase.ordinal < SlimefunWorkshopPhase.PROCESS.ordinal
    val afterProcessing = phase.ordinal > SlimefunWorkshopPhase.PROCESS.ordinal
    val clamp = when (phase) {
        SlimefunWorkshopPhase.CLAMP -> ease(p)
        SlimefunWorkshopPhase.PROCESS, SlimefunWorkshopPhase.TERMINALS -> 1.0
        SlimefunWorkshopPhase.INSPECT -> 1 - ramp(p, 0.0, .30)
        else -> 0.0
    }
    var contact: SlimefunWorkshopPoint? = null
    var contactIndex = -1

    for (x in listOf(-.68, .68)) {
        box("press-column-$x", copper, x, 1.91, .43, .25, 1.82, .35)
        box("press-column-cap-$x", frame, x, 2.78, .43, .38, .16, .47)
    }
    box("press-head", frame, 0.0, 2.88, .155, 1.72, .24, 1.25)
    box("hydraulic-cylinder", copper, 0.0, 2.57, -.155, .39, .55, .39)
    box("band-rack", frame, 0.0, 1.54, .655, 1.0, .08, .56)
    for (x in listOf(-.53, .53)) box("band-rack-support-$x", "IRON_BLOCK", x, 1.27, .655, .06, .54, .40)
    val strokePhase = if (processing) (p * 3) % 1.0 else 0.0
    val stroke = ramp(strokePhase, .26, .46) * (1 - ramp(strokePhase, .62, .92))
    val shoeY = 2.13 - .60 * stroke
    box("press-rod", "IRON_BLOCK", 0.0, (2.51 + shoeY + .07) / 2, -.155,
        .15, 2.51 - shoeY - .07, .15)
    box("press-shoe", frame, 0.0, shoeY, -.155, .99, .12, .62)
    for (side in listOf(-1, 1)) {
        val jawDistance = .60 - .24 * clamp
        val z = -.155 + side * jawDistance
        box("clamp-$side", "IRON_BLOCK", 0.0, 1.16, z, 1.12, .16, .12)
        box("clamp-foot-$side", frame, 0.0, 1.04, z, .22, .08, .12)
        box("clamp-screw-$side", copper, 0.0, 1.19, -.155 + side * (jawDistance + .92) / 2, .07, .07, .80 - jawDistance)
        box("clamp-nut-$side", frame, 0.0, 1.13, -.155 + side * .86, .18, .26, .14)
    }
    // A side flywheel and a front hand control describe the press's drive and operator role.
    box("drive-axle", "IRON_BLOCK", -1.01, 1.98, .44, .38, .10, .10)
    val wheelAngle = if (processing) p * 720 else 0.0
    box("drive-hub", frame, -1.22, 1.98, .44, .13, .15, .15)
    for (quarterTurn in listOf(0.0, 45.0, 90.0, 135.0)) box("drive-spoke-$quarterTurn", "IRON_BLOCK",
        -1.22, 1.98, .44, .06, .58, .04, rx = wheelAngle + quarterTurn)
    for (i in 0..7) {
        val angle = i * PI / 4 + Math.toRadians(wheelAngle)
        box("drive-rim-$i", copper, -1.22, 1.98 + cos(angle) * .29, .44 + sin(angle) * .29,
            .09, .18, .08, Math.toDegrees(angle) + 90.0)
    }
    box("control-base", frame, -.87, 1.15, -.77, .26, .30, .20)
    val leverAngle = -16.0 - 27.0 * stroke
    box("control-lever", "IRON_BLOCK", -.87, 1.42, -.77, .055, .29, .055, rx = leverAngle)
    box("control-grip", accent, -.87, 1.42 + .145 * cos(Math.toRadians(leverAngle)),
        -.77 + .145 * sin(Math.toRadians(leverAngle)), .23, .07, .09, rx = leverAngle)
    if (processing && strokePhase >= .46) {
        contact = SlimefunWorkshopPoint(0.0, 1.47, -.155)
        contactIndex = floor(p * 3).toInt().coerceAtMost(2)
    }

    if (phase != SlimefunWorkshopPhase.RESET) {
        fun product(key: String, material: String, x: Double, y: Double, z: Double,
                    width: Double, height: Double, depth: Double, angle: Double = productRotation) {
            val r = Math.toRadians(angle)
            box("product-$key", material, productX + x, productY + y * cos(r) - z * sin(r),
                productZ + y * sin(r) + z * cos(r), width, height, depth, rx = angle)
        }
        product("core", "IRON_BLOCK", 0.0, 0.0, 0.0, .90, .20, .36)
        // End feet keep the raised iron core physically connected to its carrier throughout the feed.
        for (side in listOf(-1, 1)) product("foot-$side", "POLISHED_BLACKSTONE", side * .39, -.13, 0.0, .10, .06, .36)
        product("carrier", "POLISHED_ANDESITE", 0.0, -.195, carrierRetreat, 1.02, .07, .58, angle = 0.0)
        for (i in 0..2) {
            val bandProgress = when {
                beforeProcessing -> 0.0
                afterProcessing -> 1.0
                else -> ramp(p * 3 - i, .0, .25)
            }
            val x = (i - 1) * .24
            val positions = listOf(
                doubleArrayOf(0.0, .13, 0.0, .09, .06, .48),
                doubleArrayOf(0.0, 0.0, .21, .09, .20, .06),
                doubleArrayOf(0.0, -.13, 0.0, .09, .06, .48),
                doubleArrayOf(0.0, 0.0, -.21, .09, .20, .06),
            )
            for ((j, shape) in positions.withIndex()) {
                val travel = 1 - bandProgress
                // Preformed contact bands slide off a real rear rack and over the core before the press closes.
                product("band-$i-$j", copper, x - productX * travel, shape[1] + .43 * travel, shape[2] + .81 * travel,
                    shape[3], shape[4], shape[5], angle = if (travel > 0) 0.0 else productRotation)
            }
        }
        val terminalProgress = when {
            phase.ordinal < SlimefunWorkshopPhase.TERMINALS.ordinal -> 0.0
            phase == SlimefunWorkshopPhase.TERMINALS -> ease(p)
            else -> 1.0
        }
        for (side in listOf(-1, 1)) {
            val startX = -1.72 + side * .09
            val endX = productX + side * .39
            box("product-terminal-$side", "REDSTONE_BLOCK", startX + (endX - startX) * terminalProgress,
                1.13 + (productY + .15 - 1.13) * terminalProgress,
                -.75 + (productZ - .055 + .75) * terminalProgress, .10, .10, .16)
        }
    }
    val focus = when (phase) {
        SlimefunWorkshopPhase.LOAD -> SlimefunWorkshopPoint(-1.35, 1.27, -.155)
        SlimefunWorkshopPhase.UNLOAD, SlimefunWorkshopPhase.DEPOSIT -> SlimefunWorkshopPoint(1.61, 1.35, .12)
        else -> SlimefunWorkshopPoint(productX, productY, -.155)
    }
    return SlimefunWorkshopFrame(phase, parts, focus, contact, contactIndex)
}

/** Compact rotary loading cabinet: the spindle holds the core while its lifting arm drops clear. */
private fun slimefunWinderFrame(age: Double, timing: SlimefunWorkshopTiming): SlimefunWorkshopFrame {
    val (phase, p) = timing.sample(age)
    val parts = mutableListOf<SlimefunWorkshopPart>()
    fun box(key: String, material: String, x: Double, y: Double, z: Double,
            width: Double, height: Double, depth: Double, rx: Double = 0.0, ry: Double = 0.0, rz: Double = 0.0) {
        parts += SlimefunWorkshopPart(key, material, x, y, z, width, height, depth, rx, ry, rz)
    }
    val frame = "POLISHED_BLACKSTONE"
    val copper = "EXPOSED_CUT_COPPER"
    val accent = "WAXED_OXIDIZED_COPPER"
    val processing = phase == SlimefunWorkshopPhase.PROCESS
    val lift = when (phase) {
        SlimefunWorkshopPhase.CLAMP -> ease(p)
        SlimefunWorkshopPhase.PROCESS, SlimefunWorkshopPhase.TERMINALS -> 1.0
        SlimefunWorkshopPhase.INSPECT -> 1 - ramp(p, .50, .82)
        else -> 0.0
    }
    val clamp = when (phase) {
        SlimefunWorkshopPhase.CLAMP -> ramp(p, .65, 1.0)
        SlimefunWorkshopPhase.PROCESS, SlimefunWorkshopPhase.TERMINALS -> 1.0
        SlimefunWorkshopPhase.INSPECT -> 1 - ramp(p, 0.0, .25)
        else -> 0.0
    }
    val armAngle = when (phase) {
        SlimefunWorkshopPhase.LOAD -> PI / 2 * ease(p)
        SlimefunWorkshopPhase.UNLOAD -> PI / 2 * (1 + ease(p))
        SlimefunWorkshopPhase.DEPOSIT -> PI
        SlimefunWorkshopPhase.RESET -> PI * (1 - ramp(p, .40, 1.0))
        else -> PI / 2
    }
    val productX = -.70 * cos(armAngle)
    val productZ = .28 - .70 * sin(armAngle)
    val productY = when (phase) {
        SlimefunWorkshopPhase.DEPOSIT -> 1.26 - .63 * ramp(p, .20, .85)
        SlimefunWorkshopPhase.RESET -> .63 + .63 * ramp(p, 0.0, .35)
        else -> 1.26 + .52 * lift
    }
    val spin = if (processing) ramp(p, .14, .87) * 1080.0 else 0.0
    val trayDrop = if (processing) .36 * ramp(p, 0.0, .12) * (1 - ramp(p, .89, 1.0)) else 0.0
    val carrierY = productY - .195 - trayDrop

    // A closed, short cabinet replaces the press's long open roller bed.
    box("cabinet-plinth", frame, 0.0, .08, 0.0, 2.70, .16, 1.86)
    box("cabinet-front", accent, 0.0, .54, -.87, 2.52, .76, .12)
    box("cabinet-back", frame, 0.0, .54, .87, 2.52, .76, .12)
    for (side in listOf(-1, 1)) {
        box("cabinet-side-$side", frame, side * 1.26, .54, 0.0, .12, .76, 1.62)
        box("deck-side-$side", copper, side * 1.16, .975, 0.0, .20, .11, 1.30)
        box("deck-edge-$side", copper, 0.0, .975, side * .79, 2.52, .11, .28)
    }
    box("cabinet-door-seam", frame, 0.0, .54, -.94, .025, .59, .02)
    box("transfer-piston", "IRON_BLOCK", 0.0, (.20 + carrierY - .055) / 2, .28,
        .18, carrierY - .255, .18)
    box("transfer-arm", frame, productX / 2, carrierY - .055, (.28 + productZ) / 2,
        .78, .06, .12, ry = -Math.toDegrees(armAngle))
    box("transfer-hub", copper, 0.0, carrierY - .05, .28, .26, .10, .26)

    // Hooded left dock and a low right pocket conceal only the new blank and collected output.
    box("intake-bed", frame, -.70, 1.01, .49, .72, .04, .32)
    box("intake-back", accent, -.70, 1.27, .88, .96, .48, .10)
    box("intake-hood", copper, -.70, 1.55, .34, 1.10, .10, .80)
    for (i in 0..2) box("stock-blank-$i", "IRON_BLOCK", -.70, 1.0725 + i * .085, .70, .70, .085, .18)
    box("terminal-tray", frame, -1.07, 1.06, -.72, .36, .06, .25)
    box("receiver-bottom", frame, .70, .34, .30, 1.04, .12, .90)
    box("receiver-front-lip", accent, .70, .70, -.06, 1.04, .60, .10)
    box("receiver-left-back", frame, .14, .70, .66, .08, .60, .30)
    box("receiver-roof", copper, .70, 1.55, .28, 1.10, .10, .78)
    box("receiver-lamp", "SEA_LANTERN", .70, 1.63, .45, .20, .06, .12)

    for (side in listOf(-1, 1)) {
        box("spindle-pedestal-$side", frame, side * 1.28, 1.345, .20, .12, .85, .22)
        box("spindle-arm-$side", frame, side * 1.28, 1.72, -.10, .10, .12, .64)
        box("spindle-crossarm-$side", frame, side * .995, 1.72, -.42, .47, .12, .18)
        box("spindle-bearing-$side", "CYAN_TERRACOTTA", side * .78, 1.78, -.42, .32, .28, .38)
        box("spindle-chuck-$side", "IRON_BLOCK", side * (.65 - .10 * clamp), 1.78, -.42, .20, .24, .24)
    }
    // The large exposed reel is the second station's primary silhouette.
    box("spool-stand", frame, -.99, 1.595, .89, .22, 1.13, .16)
    box("spool-axle", "IRON_BLOCK", -.99, 2.16, .55, .09, .09, .85)
    box("wire-spool", copper, -.99, 2.16, .55, .52, .52, .38)
    for (side in listOf(-1, 1)) {
        val z = .55 + side * .24
        box("spool-hub-$side", frame, -.99, 2.16, z, .15, .15, .11)
        for (angle in listOf(0.0, 90.0)) box("spool-spoke-$side-$angle", "IRON_BLOCK",
            -.99, 2.16, z, .045, .68, .05, rz = -spin - angle)
        for (i in 0..7) {
            val angle = i * PI / 4 + Math.toRadians(spin)
            box("spool-rim-$side-$i", frame, -.99 + sin(angle) * .37, 2.16 + cos(angle) * .37, z,
                .24, .07, .07, rz = -Math.toDegrees(angle))
        }
    }
    for (x in listOf(-.12, .12)) box("guide-post-$x", frame, x, 1.50, .65, .07, .95, .09)
    box("guide-crossbar", "IRON_BLOCK", 0.0, 1.98, .65, .85, .07, .07)
    val guideX = if (processing) -.24 + .48 * ease(p) else -.24
    box("wire-guide", copper, guideX, 1.95, .65, .10, .16, .13)
    box("control-base", frame, -.68, 1.13, -.81, .28, .20, .18)
    box("control-key", copper, -.68, 1.27, -.81, .20, .08, .08)

    var contact: SlimefunWorkshopPoint? = null
    var contactIndex = -1
    if (processing) {
        box("spool-feed-rise", copper, -.99, 2.52, .55, .022, .20, .022)
        box("spool-feed-wire", copper, (-.979 + guideX - .011) / 2, 2.62, .55, guideX + .979 - .011, .022, .022)
        box("spool-feed-drop", copper, guideX, 2.30, .55, .022, .64, .022)
        box("spool-feed-bend", copper, guideX, 1.98, .6055, .022, .022, .089)
        val r = Math.toRadians(spin)
        val reach = min(.16 / abs(.20 * cos(r) + 1.07 * sin(r)), .24 / abs(-.20 * sin(r) + 1.07 * cos(r)))
        contact = SlimefunWorkshopPoint(guideX, 1.78 + .20 * reach, -.42 + 1.07 * reach)
        val dy = contact.y - 1.98
        val dz = contact.z - .65
        box("tensioned-wire", copper, guideX, (1.98 + contact.y) / 2, (.65 + contact.z) / 2,
            .025, .025, sqrt(dy * dy + dz * dz), rx = Math.toDegrees(atan2(-dy, dz)))
        contactIndex = (p * 6).toInt().coerceAtMost(5)
    }

    if (phase != SlimefunWorkshopPhase.RESET) {
        fun product(key: String, material: String, x: Double, y: Double, z: Double,
                    width: Double, height: Double, depth: Double) {
            val r = Math.toRadians(spin)
            box("product-$key", material, productX + x, productY + y * cos(r) - z * sin(r),
                productZ + y * sin(r) + z * cos(r), width, height, depth, rx = spin)
        }
        product("core", "IRON_BLOCK", 0.0, 0.0, 0.0, .90, .20, .36)
        for (side in listOf(-1, 1)) product("foot-$side", frame, side * .39, -.13, 0.0, .10, .06, .36)
        box("product-carrier", "POLISHED_ANDESITE", productX, carrierY, productZ, .98, .07, .58)
        val winding = if (phase.ordinal > SlimefunWorkshopPhase.PROCESS.ordinal) 3.0 else if (processing) p * 3 else 0.0
        val bands = listOf(
            doubleArrayOf(.13, 0.0, .09, .06, .48), doubleArrayOf(0.0, .21, .09, .20, .06),
            doubleArrayOf(-.13, 0.0, .09, .06, .48), doubleArrayOf(0.0, -.21, .09, .20, .06),
        )
        for (i in 0..2) for ((j, shape) in bands.withIndex()) {
            if ((winding - i) * 4 < j + 1) continue
            product("band-$i-$j", copper, (i - 1) * .24, shape[0], shape[1], shape[2], shape[3], shape[4])
        }
        val terminals = when {
            phase.ordinal < SlimefunWorkshopPhase.TERMINALS.ordinal -> 0.0
            phase == SlimefunWorkshopPhase.TERMINALS -> ease(p)
            else -> 1.0
        }
        for (side in listOf(-1, 1)) {
            val startX = -1.07 + side * .07
            box("product-terminal-$side", "REDSTONE_BLOCK", startX + (productX + side * .39 - startX) * terminals,
                1.14 + (productY + .15 - 1.14) * terminals,
                -.72 + (productZ - .055 + .72) * terminals, .10, .10, .16)
        }
    }
    return SlimefunWorkshopFrame(phase, parts, SlimefunWorkshopPoint(productX, productY, productZ), contact, contactIndex)
}
