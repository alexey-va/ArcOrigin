package ru.arc.origin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
): SlimefunWorkshopFrame {
    val (phase, p) = timing.sample(age)
    val parts = mutableListOf<SlimefunWorkshopPart>()
    fun box(key: String, material: String, x: Double, y: Double, z: Double,
            width: Double, height: Double, depth: Double, rx: Double = 0.0, rz: Double = 0.0) {
        parts += SlimefunWorkshopPart(key, material, x, y, z, width, height, depth, rx, rotationZ = rz)
    }
    val copper = if (machine == SlimefunWorkshopMachine.PRESS) "CUT_COPPER" else "EXPOSED_CUT_COPPER"
    val frame = if (machine == SlimefunWorkshopMachine.PRESS) "POLISHED_DEEPSLATE" else "POLISHED_BLACKSTONE"
    val accent = if (machine == SlimefunWorkshopMachine.PRESS) "WAXED_OXIDIZED_COPPER" else "CYAN_TERRACOTTA"

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
    if (machine == SlimefunWorkshopMachine.PRESS)
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
    var productY = if (phase == SlimefunWorkshopPhase.DEPOSIT) 1.31 - .47 * ramp(p, .60, 1.0) else 1.31
    val productZ = if (phase == SlimefunWorkshopPhase.DEPOSIT) -.155 + .94 * ease(p) else -.155
    var productRotation = 0.0
    var carrierRetreat = 0.0
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

    if (machine == SlimefunWorkshopMachine.PRESS) {
        for (x in listOf(-.68, .68)) {
            box("press-column-$x", copper, x, 1.91, .43, .25, 1.82, .35)
            box("press-column-cap-$x", frame, x, 2.78, .43, .38, .16, .47)
        }
        box("press-head", frame, 0.0, 2.91, .155, 1.72, .24, 1.25)
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
    } else {
        // The second machine uses a horizontal spindle and traversing guide, rather than a recoloured press.
        for (side in listOf(-1, 1)) {
            box("spindle-pedestal-$side", frame, side * .90, 1.37, .45, .32, .74, .25)
            box("spindle-arm-$side", frame, side * .90, 1.70, .20, .26, .18, .70)
            box("spindle-bearing-$side", accent, side * .90, 1.82, -.155, .42, .22, .42)
            box("spindle-chuck-$side", "IRON_BLOCK", side * (.68 - .16 * clamp), 1.82, -.155, .22, .24, .24)
        }
        val lift = when (phase) {
            SlimefunWorkshopPhase.CLAMP -> ease(p)
            SlimefunWorkshopPhase.PROCESS, SlimefunWorkshopPhase.TERMINALS -> 1.0
            SlimefunWorkshopPhase.INSPECT -> 1 - ramp(p, 0.0, .30)
            else -> 0.0
        }
        productY += .51 * lift
        val spin = if (processing) ramp(p, .14, .87) * 1080.0 else 0.0
        productRotation = spin
        carrierRetreat = if (processing) ramp(p, .0, .12) * (1 - ramp(p, .89, 1.0)) * .88 else 0.0
        box("lift-piston", "IRON_BLOCK", 0.0, 1.015 + .255 * lift, -.155 + carrierRetreat, .20, .03 + .51 * lift, .20)
        if (lift > 0.0)
            box("lift-cradle", frame, 0.0, 1.055 + .51 * lift, -.155 + carrierRetreat, .98, .05, .54)
        // Copper spool is rear mounted, leaving the workpiece open toward the viewer.
        for (x in listOf(-1.80, -1.40)) box("spool-leg-$x", frame, x, 1.55, .67, .13, .86, .22)
        box("spool-axle", "IRON_BLOCK", -1.60, 2.01, .67, .70, .09, .09)
        box("wire-spool", copper, -1.60, 2.01, .67, .43, .48, .48, rx = spin)
        for (x in listOf(-1.855, -1.345)) box("spool-flange-$x", frame, x, 2.01, .67, .07, .61, .61, rx = spin)
        box("traverse-rail", "IRON_BLOCK", 0.0, 2.02, .57, 1.42, .07, .07)
        for (x in listOf(-.64, .64)) box("traverse-support-$x", frame, x, 1.51, .57, .08, 1.02, .10)
        val guideX = if (processing) -.24 + .48 * ease(p) else -.24
        box("traverse-guide", copper, guideX, 1.94, .48, .11, .21, .12)
        if (processing) {
            box("spool-feed-wire", copper, (-1.31 + guideX - .011) / 2, 1.94, .67, guideX + 1.31 - .011, .022, .022)
            box("spool-feed-bend", copper, guideX, 1.94, .575, .022, .022, .19)
            box("tensioned-wire", copper, guideX, 1.88, .255, .025, .025, .40)
            contact = SlimefunWorkshopPoint(guideX, 1.88, .055)
            contactIndex = (p * 6).toInt().coerceAtMost(5)
        }
        box("drive-housing", accent, -.90, .70, .18, .75, .43, .76)
        for (i in 0..3) box("motor-vent-$i", frame, -1.282, .57 + i * .085, .18, .012, .025, .53)
        box("control-console", frame, -.64, 1.12, -.77, .31, .24, .18)
        box("control-dial", copper, -.64, 1.30, -.79, .22, .22, .06, rz = spin / 3)
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
                afterProcessing -> if (machine == SlimefunWorkshopMachine.WINDER) 4.0 else 1.0
                machine == SlimefunWorkshopMachine.PRESS -> ramp(p * 3 - i, .0, .25)
                else -> ((p * 3 - i) * 4).coerceIn(0.0, 4.0)
            }
            val x = (i - 1) * .24
            val positions = listOf(
                doubleArrayOf(0.0, .13, 0.0, .09, .06, .48),
                doubleArrayOf(0.0, 0.0, .21, .09, .20, .06),
                doubleArrayOf(0.0, -.13, 0.0, .09, .06, .48),
                doubleArrayOf(0.0, 0.0, -.21, .09, .20, .06),
            )
            for ((j, shape) in positions.withIndex()) {
                if (machine == SlimefunWorkshopMachine.WINDER && bandProgress < j + 1) continue
                val travel = if (machine == SlimefunWorkshopMachine.PRESS) 1 - bandProgress else 0.0
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
