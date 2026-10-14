package ru.arc.origin

import org.joml.Quaternionf
import kotlin.math.sin

internal const val ORIGIN_WORKSHOP_TURN_TICKS = 12L

internal data class OriginWorkshopTurnPose(val center: OriginWorkshopPoint, val rotation: Quaternionf)

/** Lift, turn around the workpiece center, and lower it onto its next support. */
internal fun originWorkshopTurnPose(
    from: OriginWorkshopPoint,
    to: OriginWorkshopPoint,
    rotation: Quaternionf,
    flip: Boolean,
    lift: Double,
    progress: Double,
): OriginWorkshopTurnPose {
    require(progress.isFinite() && lift.isFinite() && lift >= 0.0)
    val p = progress.coerceIn(0.0, 1.0)
    val eased = p * p * (3.0 - 2.0 * p)
    val angle = (Math.PI * eased).toFloat()
    val turned = Quaternionf(rotation).apply { if (flip) rotateX(angle) else rotateY(angle) }
    return OriginWorkshopTurnPose(
        OriginWorkshopPoint(
            from.x + (to.x - from.x) * eased,
            from.y + (to.y - from.y) * eased + sin(Math.PI * eased) * lift,
            from.z + (to.z - from.z) * eased,
        ),
        turned,
    )
}
