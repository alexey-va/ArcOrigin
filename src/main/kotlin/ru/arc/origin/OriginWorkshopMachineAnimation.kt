package ru.arc.origin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val WORKSHOP_MACHINE_INTERPOLATION_TICKS = 2
internal val ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES = setOf(
    "carpenter-board-in-feed",
    "assembler-board-sample",
    "assembler-anvil-workpiece",
    "upholsterer-press-cloth",
)

internal enum class OriginWorkshopMechanism { NONE, SAW, DRILL, CLAMP_LEFT, CLAMP_RIGHT, VISE, ANVIL, PRESS, FINISH }

internal enum class OriginWorkshopRotationAxis { X, Y, Z }

internal data class OriginWorkshopPoint(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: OriginWorkshopPoint) = OriginWorkshopPoint(x - other.x, y - other.y, z - other.z)
}

internal data class OriginWorkshopPieceMotion(
    val centerOffset: OriginWorkshopPoint = OriginWorkshopPoint(0.0, 0.0, 0.0),
    val rotationAxis: OriginWorkshopRotationAxis? = null,
    val rotationDegrees: Double = 0.0,
    val scaleYFactor: Double = 1.0,
    val visible: Boolean? = null,
)

internal data class OriginWorkshopMachinePose(
    val pieces: Map<String, OriginWorkshopPieceMotion>,
    val contact: OriginWorkshopPoint?,
)

/** Independent drive shafts run while the workshop is observed; material poses keep their own owner. */
@Suppress("UNUSED_PARAMETER")
internal fun originWorkshopDrivePose(
    role: OriginWorkshopTableRole,
    phase: Double,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
): OriginWorkshopMachinePose {
    require(phase.isFinite())
    val p = phase.coerceIn(0.0, 1.0)
    val angle = p * 360.0
    val radians = p * 2.0 * PI
    val pieces = buildMap {
        when (role) {
            OriginWorkshopTableRole.CARPENTER -> {
                putAll(workshopWheelMotion("carpenter-drive-flywheel", 0.23, angle))
                for (index in 0..1) put("carpenter-drive-feed-roller-$index", OriginWorkshopPieceMotion(
                    rotationAxis = OriginWorkshopRotationAxis.Z, rotationDegrees = -angle * 2.0,
                ))
            }
            OriginWorkshopTableRole.UPHOLSTERER -> {
                putAll(workshopWheelMotion("upholsterer-drive-handwheel", 0.22, angle * 2.0))
                put("upholsterer-drive-needle", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.0, -0.035 * (1.0 - cos(radians * 2.0)), 0.0),
                ))
                put("upholsterer-drive-shuttle", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.17 * sin(radians * 2.0), 0.0, 0.0),
                ))
                for (index in 0..1) put("upholsterer-drive-fabric-roller-$index", OriginWorkshopPieceMotion(
                    rotationAxis = OriginWorkshopRotationAxis.Z, rotationDegrees = -angle,
                ))
            }
            OriginWorkshopTableRole.ASSEMBLER -> putAll(workshopWheelMotion("assembler-drive-freewheel", 0.23, angle))
            OriginWorkshopTableRole.FINISHER -> {
                put("finisher-drive-sanding-disc", OriginWorkshopPieceMotion(
                    rotationAxis = OriginWorkshopRotationAxis.Y, rotationDegrees = angle,
                ))
                put("finisher-drive-disc-marker", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.14 * (cos(radians) - 1.0), 0.0, -0.14 * sin(radians)),
                    rotationAxis = OriginWorkshopRotationAxis.Y, rotationDegrees = angle,
                ))
                for (key in listOf("carriage", "handle", "head")) put("finisher-drive-brush-$key", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.20 * sin(radians), 0.0, 0.0),
                ))
            }
        }
    }
    return OriginWorkshopMachinePose(pieces, null)
}

private fun workshopWheelMotion(prefix: String, radius: Double, degrees: Double): Map<String, OriginWorkshopPieceMotion> = buildMap {
    val rotation = degrees * PI / 180.0
    for (index in 0..7) {
        val baselineY = (index - 3.5) * radius / 4.0
        put("$prefix-rim-$index", OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(-baselineY * sin(rotation), baselineY * (cos(rotation) - 1.0), 0.0),
            rotationAxis = OriginWorkshopRotationAxis.Z, rotationDegrees = degrees,
        ))
    }
    for (axis in listOf("x", "y")) put("$prefix-spoke-$axis", OriginWorkshopPieceMotion(
        rotationAxis = OriginWorkshopRotationAxis.Z, rotationDegrees = degrees,
    ))
}

/** Small operator-tunable machine anchors and motion amplitudes; table IDs and station footprints stay fixed. */
internal data class OriginWorkshopMachineTuning(
    val sawPivotX: Double = -1.15,
    val sawPivotYOffset: Double = 0.62,
    val sawPivotZ: Double = -0.625,
    val sawFeedStartX: Double = -1.89,
    val sawFeedDistance: Double = 1.40,
    val sawBladeTurns: Double = 3.0,
    val viseCenterX: Double = -0.80,
    val viseCenterZ: Double = 0.24,
    val viseJawTravel: Double = 0.10,
    val viseScrewTurns: Double = 1.0,
    val anvilCenterX: Double = 1.15,
    val anvilCenterZ: Double = -0.14,
    val hammerPivotYOffset: Double = 1.36,
    val hammerPivotZ: Double = 0.25,
    val hammerArmLength: Double = 0.75,
    val hammerRestAngleDegrees: Double = 155.0,
    val hammerStrikeAngleDegrees: Double = 129.8,
    val pressCenterX: Double = -1.15,
    val pressCenterZ: Double = -0.14,
    val pressTravel: Double = 0.12,
    val pressClothCompression: Double = 0.50,
) {
    init {
        listOf(
            sawPivotX, sawPivotYOffset, sawPivotZ, sawFeedStartX, sawFeedDistance, sawBladeTurns,
            viseCenterX, viseCenterZ, viseJawTravel, viseScrewTurns,
            anvilCenterX, anvilCenterZ, hammerPivotYOffset, hammerPivotZ, hammerArmLength,
            hammerRestAngleDegrees, hammerStrikeAngleDegrees,
            pressCenterX, pressCenterZ, pressTravel, pressClothCompression,
        ).forEach { require(it.isFinite()) { "Origin workshop machine tuning values must be finite" } }
        require(sawPivotX in -2.0..2.0 && sawPivotZ in -1.0..1.0 && sawPivotYOffset in 0.4..0.9)
        require(sawFeedStartX in -2.3..-1.2 && sawFeedDistance in 0.5..1.8 && sawBladeTurns in 0.25..3.0)
        require(viseCenterX in -1.5..-0.3 && viseCenterZ in -0.1..0.7)
        require(viseJawTravel in 0.02..0.20 && viseScrewTurns in 0.25..3.0)
        require(anvilCenterX in 0.4..1.8 && anvilCenterZ in -0.7..0.4)
        require(hammerPivotYOffset in 1.1..1.7 && hammerPivotZ in -0.1..0.6 && hammerArmLength in 0.5..0.9)
        require(hammerRestAngleDegrees in 140.0..175.0 && hammerStrikeAngleDegrees in 100.0..135.0)
        require(pressCenterX in -1.8..-0.7 && pressCenterZ in -0.6..0.3)
        require(pressTravel in 0.04..0.20 && pressClothCompression in 0.1..0.8)
    }

    companion object {
        fun load(config: ru.arc.config.Config): OriginWorkshopMachineTuning {
            fun real(path: String, fallback: Double) = config.real("origin-workshop-tables.machinery.$path", fallback)
            val defaults = OriginWorkshopMachineTuning()
            return OriginWorkshopMachineTuning(
                sawPivotX = real("saw.pivot-x", defaults.sawPivotX),
                sawPivotYOffset = real("saw.pivot-y-offset", defaults.sawPivotYOffset),
                sawPivotZ = real("saw.pivot-z", defaults.sawPivotZ),
                sawFeedStartX = real("saw.feed-start-x", defaults.sawFeedStartX),
                sawFeedDistance = real("saw.feed-distance", defaults.sawFeedDistance),
                sawBladeTurns = real("saw.blade-turns", defaults.sawBladeTurns),
                viseCenterX = real("vise.center-x", defaults.viseCenterX),
                viseCenterZ = real("vise.center-z", defaults.viseCenterZ),
                viseJawTravel = real("vise.jaw-travel", defaults.viseJawTravel),
                viseScrewTurns = real("vise.screw-turns", defaults.viseScrewTurns),
                anvilCenterX = real("anvil.center-x", defaults.anvilCenterX),
                anvilCenterZ = real("anvil.center-z", defaults.anvilCenterZ),
                hammerPivotYOffset = real("anvil.hammer-pivot-y-offset", defaults.hammerPivotYOffset),
                hammerPivotZ = real("anvil.hammer-pivot-z", defaults.hammerPivotZ),
                hammerArmLength = real("anvil.hammer-arm-length", defaults.hammerArmLength),
                hammerRestAngleDegrees = real("anvil.rest-angle-degrees", defaults.hammerRestAngleDegrees),
                hammerStrikeAngleDegrees = real("anvil.strike-angle-degrees", defaults.hammerStrikeAngleDegrees),
                pressCenterX = real("press.center-x", defaults.pressCenterX),
                pressCenterZ = real("press.center-z", defaults.pressCenterZ),
                pressTravel = real("press.travel", defaults.pressTravel),
                pressClothCompression = real("press.cloth-compression", defaults.pressClothCompression),
            )
        }
    }
}

/** Pure table-local pose math. `strokeProgress` is one normalized stroke with contact at 0.65. */
internal fun originWorkshopMachinePose(
    mechanism: OriginWorkshopMechanism,
    progress: Double,
    strokeProgress: Double,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
): OriginWorkshopMachinePose {
    require(progress.isFinite() && strokeProgress.isFinite()) { "Machine progress must be finite" }
    val p = progress.coerceIn(0.0, 1.0)
    val s = strokeProgress.coerceIn(0.0, 1.0)
    if (mechanism == OriginWorkshopMechanism.NONE) return OriginWorkshopMachinePose(emptyMap(), null)

    return when (mechanism) {
        OriginWorkshopMechanism.NONE -> OriginWorkshopMachinePose(emptyMap(), null)
        OriginWorkshopMechanism.FINISH -> {
            // First withdraw from the rack, then lower/turn the panel clear of its neighbours.
            // Reverse the same path at the end instead of cutting through adjacent boards.
            val transfer = (minOf(p, 1.0 - p) / 0.25).coerceIn(0.0, 1.0)
            val withdraw = smoothstep((transfer / 0.4).coerceIn(0.0, 1.0))
            val flatten = smoothstep(((transfer - 0.4) / 0.6).coerceIn(0.0, 1.0))
            val offset = OriginWorkshopPoint(-1.10 * flatten, -0.48 * flatten, -0.45 * withdraw + 0.35 * flatten)
            OriginWorkshopMachinePose(
                mapOf("finisher-drying-panel-center" to OriginWorkshopPieceMotion(
                    centerOffset = offset,
                    rotationAxis = OriginWorkshopRotationAxis.X,
                    rotationDegrees = -90.0 * flatten,
                )),
                OriginWorkshopPoint(1.10 + offset.x, dimensions.height + 0.52 + offset.y, -0.35 + offset.z),
            )
        }
        OriginWorkshopMechanism.SAW -> {
            val angle = 360.0 * tuning.sawBladeTurns * p
            val radians = angle * PI / 180.0
            val pieces = buildMap {
                for (index in 0..8) {
                    val dy = (index - 4) * 0.095
                    put(
                        "carpenter-saw-blade-row-$index",
                        OriginWorkshopPieceMotion(
                            centerOffset = OriginWorkshopPoint(
                                -dy * sin(radians),
                                dy * (cos(radians) - 1.0),
                                0.0,
                            ),
                            rotationAxis = OriginWorkshopRotationAxis.Z,
                            rotationDegrees = angle,
                        ),
                    )
                }
                put("carpenter-board-in-feed", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(tuning.sawFeedDistance * smoothstep(p), 0.0, 0.0),
                    visible = true,
                ))
            }
            OriginWorkshopMachinePose(
                pieces,
                OriginWorkshopPoint(tuning.sawPivotX, dimensions.height + 0.22, tuning.sawPivotZ),
            )
        }
        OriginWorkshopMechanism.DRILL -> {
            // One complete down-and-return stroke; the quill and bit are clear at both ends.
            val stroke = if (p <= 0.5) {
                smoothstep((p / 0.5).coerceIn(0.0, 1.0))
            } else {
                smoothstep(((1.0 - p) / 0.5).coerceIn(0.0, 1.0))
            }
            val spin = 720.0 * p
            val pieces = buildMap {
                put("carpenter-drill-quill", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.0, -0.30 * stroke, 0.0),
                ))
                put("carpenter-drill-spindle", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.0, -0.30 * stroke, 0.0),
                    rotationAxis = OriginWorkshopRotationAxis.Y,
                    rotationDegrees = spin,
                ))
                put("carpenter-drill-bit", OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.0, -0.30 * stroke, 0.0),
                    rotationAxis = OriginWorkshopRotationAxis.Y,
                    rotationDegrees = spin,
                ))
                val wheelRadians = spin * PI / 180.0
                for (index in 0..7) {
                    val dy = (index - 3.5) * 0.14 / 4.0
                    put("carpenter-drill-control-wheel-rim-$index", OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(
                            -dy * sin(wheelRadians),
                            dy * (cos(wheelRadians) - 1.0),
                            0.0,
                        ),
                        rotationAxis = OriginWorkshopRotationAxis.Z,
                        rotationDegrees = spin,
                    ))
                }
                for (axis in listOf("x", "y")) put("carpenter-drill-control-wheel-spoke-$axis", OriginWorkshopPieceMotion(
                    rotationAxis = OriginWorkshopRotationAxis.Z,
                    rotationDegrees = spin,
                ))
            }
            OriginWorkshopMachinePose(pieces, OriginWorkshopPoint(0.0, dimensions.height + 0.06, -0.55))
        }
        OriginWorkshopMechanism.CLAMP_LEFT -> OriginWorkshopMachinePose(
            mapOf(
                "carpenter-assembly-clamp-left" to OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.12 * smoothstep(p), 0.0, 0.0),
                ),
                "carpenter-assembly-clamp-control-left" to OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(0.12 * smoothstep(p), 0.0, 0.0),
                ),
            ),
            OriginWorkshopPoint(1.35, dimensions.height + 0.21, -0.65),
        )
        OriginWorkshopMechanism.CLAMP_RIGHT -> OriginWorkshopMachinePose(
            mapOf(
                "carpenter-assembly-clamp-right" to OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(-0.12 * smoothstep(p), 0.0, 0.0),
                ),
                "carpenter-assembly-clamp-control-right" to OriginWorkshopPieceMotion(
                    centerOffset = OriginWorkshopPoint(-0.12 * smoothstep(p), 0.0, 0.0),
                ),
            ),
            OriginWorkshopPoint(1.35, dimensions.height + 0.21, -0.65),
        )
        OriginWorkshopMechanism.VISE -> {
            val closed = smoothstep((p / 0.20).coerceIn(0.0, 1.0)) *
                (1.0 - smoothstep(((p - 0.80) / 0.20).coerceIn(0.0, 1.0)))
            OriginWorkshopMachinePose(
                mapOf(
                    "assembler-clamp-left" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(tuning.viseJawTravel * closed, 0.0, 0.0),
                    ),
                    "assembler-clamp-right" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(-tuning.viseJawTravel * closed, 0.0, 0.0),
                    ),
                    "assembler-vise-handle" to OriginWorkshopPieceMotion(
                        rotationAxis = OriginWorkshopRotationAxis.X,
                        rotationDegrees = 360.0 * tuning.viseScrewTurns * p,
                    ),
                    "assembler-board-sample" to OriginWorkshopPieceMotion(visible = true),
                ),
                OriginWorkshopPoint(tuning.viseCenterX, dimensions.height + 0.10, tuning.viseCenterZ),
            )
        }
        OriginWorkshopMechanism.ANVIL -> {
            val angle = strokeAngle(s, tuning.hammerRestAngleDegrees, tuning.hammerStrikeAngleDegrees)
            val rest = hammerVector(tuning.hammerArmLength, tuning.hammerRestAngleDegrees)
            val current = hammerVector(tuning.hammerArmLength, angle)
            val armRest = scaled(rest, 0.5)
            val armCurrent = scaled(current, 0.5)
            OriginWorkshopMachinePose(
                mapOf(
                    "assembler-hammer-arm" to OriginWorkshopPieceMotion(
                        centerOffset = armCurrent - armRest,
                        rotationAxis = OriginWorkshopRotationAxis.X,
                        rotationDegrees = angle,
                    ),
                    "assembler-hammer-head" to OriginWorkshopPieceMotion(
                        centerOffset = current - rest,
                        rotationAxis = OriginWorkshopRotationAxis.X,
                        rotationDegrees = angle,
                    ),
                    "assembler-anvil-workpiece" to OriginWorkshopPieceMotion(visible = true),
                ) + workshopWheelMotion("assembler-hammer-cam", 0.18, (angle - tuning.hammerRestAngleDegrees) * 3.0),
                OriginWorkshopPoint(tuning.anvilCenterX, dimensions.height + 0.67, tuning.anvilCenterZ),
            )
        }
        OriginWorkshopMechanism.PRESS -> {
            val beatEnvelope = smoothstep((p / 0.15).coerceIn(0.0, 1.0)) *
                (1.0 - smoothstep(((p - 0.85) / 0.15).coerceIn(0.0, 1.0)))
            val pressure = strokeEnvelope(s) * beatEnvelope
            val clothScale = 1.0 - tuning.pressClothCompression * pressure
            OriginWorkshopMachinePose(
                mapOf(
                    "upholsterer-press-control-grip" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(0.0, -0.06 * pressure, 0.0),
                    ),
                    "upholsterer-press-platen" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(0.0, -tuning.pressTravel * pressure, 0.0),
                    ),
                    "upholsterer-press-ram" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(0.0, -tuning.pressTravel * pressure / 2.0, 0.0),
                        scaleYFactor = 1.0 + tuning.pressTravel * pressure / 0.31,
                    ),
                    "upholsterer-press-cloth" to OriginWorkshopPieceMotion(
                        centerOffset = OriginWorkshopPoint(0.0, -0.02 * (1.0 - clothScale), 0.0),
                        scaleYFactor = clothScale,
                        visible = true,
                    ),
                ),
                OriginWorkshopPoint(tuning.pressCenterX, dimensions.height + 0.74, tuning.pressCenterZ),
            )
        }
    }
}

/** Craft-driver pose API; it leaves the legacy saw teaching sample hidden under the real carried board. */
internal fun originWorkshopCraftMachinePose(
    machine: String,
    progress: Double,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
    strokeProgress: Double = progress,
): OriginWorkshopMachinePose {
    require(progress.isFinite() && strokeProgress.isFinite()) { "Craft machine progress must be finite" }
    val p = progress.coerceIn(0.0, 1.0)
    if (machine == "sewing") return originWorkshopSewingPose(p)
    if (machine == "sewing-foot") return originWorkshopSewingFootPose(p)
    val mechanism = when (machine) {
        "saw" -> OriginWorkshopMechanism.SAW
        "drill" -> OriginWorkshopMechanism.DRILL
        "clamp-left" -> OriginWorkshopMechanism.CLAMP_LEFT
        "clamp-right" -> OriginWorkshopMechanism.CLAMP_RIGHT
        "vise" -> OriginWorkshopMechanism.VISE
        "anvil" -> OriginWorkshopMechanism.ANVIL
        "press" -> OriginWorkshopMechanism.PRESS
        "finish" -> OriginWorkshopMechanism.FINISH
        else -> error("Unknown workshop craft machine '$machine'")
    }
    val stroke = strokeProgress.coerceIn(0.0, 1.0)
    val pose = originWorkshopMachinePose(mechanism, p, stroke, dimensions, tuning)
    // Player props replace the autonomous sample on these machines.
    val sampleKey = when (machine) {
        "saw" -> "carpenter-board-in-feed"
        "vise" -> "assembler-board-sample"
        "anvil" -> "assembler-anvil-workpiece"
        "press" -> "upholsterer-press-cloth"
        else -> return pose
    }
    val sample = pose.pieces.getValue(sampleKey)
    return pose.copy(pieces = pose.pieces + (sampleKey to sample.copy(visible = false)))
}

private const val ORIGIN_WORKSHOP_STITCHES_PER_PASS = 5

/** Five full needle/shuttle cycles; the needle is clear at both ends of a timed pass. */
private fun originWorkshopSewingPose(progress: Double): OriginWorkshopMachinePose {
    val turn = progress * ORIGIN_WORKSHOP_STITCHES_PER_PASS
    val cycle = if (progress >= 1.0) 0.0 else turn - kotlin.math.floor(turn)
    val stroke = if (cycle <= 0.5) {
        smoothstep(cycle * 2.0)
    } else {
        smoothstep((1.0 - cycle) * 2.0)
    }
    val radians = turn * 2.0 * PI
    val degrees = turn * 360.0
    val pieces = buildMap {
        putAll(workshopWheelMotion("upholsterer-drive-handwheel", 0.22, degrees))
        put("upholsterer-drive-needle", OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(0.0, -0.08 * stroke, 0.0),
        ))
        put("upholsterer-drive-shuttle", OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(if (progress >= 1.0) 0.0 else 0.17 * sin(radians), 0.0, 0.0),
        ))
        for (index in 0..1) put("upholsterer-drive-fabric-roller-$index", OriginWorkshopPieceMotion(
            rotationAxis = OriginWorkshopRotationAxis.Z,
            rotationDegrees = -degrees,
        ))
        // Keep the cloth clamped for every stitch, then lift before the pickup/reposition step.
        putAll(originWorkshopSewingFootPose(if (progress < 1.0) 1.0 else 0.0).pieces)
    }
    return OriginWorkshopMachinePose(pieces, null)
}

/** The foot rests 0.10 blocks above the cloth until the player lowers it with the needle-side lever. */
private fun originWorkshopSewingFootPose(progress: Double): OriginWorkshopMachinePose {
    val down = smoothstep(progress)
    val pieces = mapOf(
        "upholsterer-sewing-foot-toe-left" to OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(0.0, -0.10 * down, 0.0),
        ),
        "upholsterer-sewing-foot-toe-right" to OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(0.0, -0.10 * down, 0.0),
        ),
        "upholsterer-sewing-foot-bridge" to OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(0.0, -0.10 * down, 0.0),
        ),
        "upholsterer-sewing-foot-shank" to OriginWorkshopPieceMotion(
            centerOffset = OriginWorkshopPoint(0.0, -0.05 * down, 0.0),
            scaleYFactor = 1.0 + 0.10 * down / 0.366,
        ),
        "upholsterer-sewing-foot-lifter" to OriginWorkshopPieceMotion(
            rotationAxis = OriginWorkshopRotationAxis.X,
            rotationDegrees = 15.0 * down,
        ),
    )
    return OriginWorkshopMachinePose(pieces, null)
}


private fun smoothstep(value: Double): Double = value * value * (3.0 - 2.0 * value)

private fun strokeEnvelope(value: Double): Double = if (value <= 0.65) {
    smoothstep((value / 0.65).coerceIn(0.0, 1.0))
} else {
    1.0 - smoothstep(((value - 0.65) / 0.35).coerceIn(0.0, 1.0))
}

private fun strokeAngle(value: Double, rest: Double, strike: Double): Double = if (value <= 0.65) {
    rest + (strike - rest) * smoothstep((value / 0.65).coerceIn(0.0, 1.0))
} else {
    strike + (rest - strike) * smoothstep(((value - 0.65) / 0.35).coerceIn(0.0, 1.0))
}

private fun hammerVector(length: Double, degrees: Double): OriginWorkshopPoint {
    val radians = degrees * PI / 180.0
    return OriginWorkshopPoint(0.0, -length * sin(radians), length * cos(radians))
}

private fun scaled(point: OriginWorkshopPoint, factor: Double) =
    OriginWorkshopPoint(point.x * factor, point.y * factor, point.z * factor)
