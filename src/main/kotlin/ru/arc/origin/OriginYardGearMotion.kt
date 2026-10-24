package ru.arc.origin

/** Smooth distance curve: normal within two metres, a bounded reading aid beyond six. */
internal fun originYardGearDistanceScale(distance: Double, maximum: Float = 1.55f): Float {
    require(distance.isFinite() && distance >= 0 && maximum.isFinite() && maximum >= 1f)
    val t = ((distance-2.0)/4.0).coerceIn(0.0,1.0)
    return (1.0+(maximum-1.0)*t*t*(3.0-2.0*t)).toFloat()
}

internal fun originYardGearScaleStep(current: Float, target: Float): Float =
    if (kotlin.math.abs(target-current) < .0005f) target else current+(target-current)*.25f

/** One visual handoff survives domain stage changes; it never owns input, credit or job lifetime. */
internal class OriginYardGearMotion {
    private var destination: String? = null
    private var from: OriginYardGearPose? = null
    private var began = 0
    var rendered: OriginYardGearPose? = null
        private set

    fun sample(target: OriginYardGearPose, destination: String, tick: Int, animate: Boolean): OriginYardGearPose {
        val previous = rendered
        if (this.destination != destination) {
            this.destination = destination
            from = previous?.takeIf { animate && it.at.world == target.at.world && it.at.distanceSquared(target.at) <= 25.0 }
            began = tick
        }
        val progress = (tick-began)/10.0
        val start = from
        val next = if (start == null || progress >= 1.0) target else originYardGearTransfer(start,target,progress)
        if (progress >= 1.0) from = null
        rendered = next
        return next
    }
}
