package ru.arc.origin

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

internal class OriginYardGearAdjustment {
    private var target = 0.0
    private var from = 0.0
    private var began = 0
    private var value = 0.0

    fun sample(adjusted: Boolean, tick: Int): Double {
        val next = if (adjusted) 1.0 else 0.0
        if (target != next) { from = value; target = next; began = tick }
        val t = ((tick-began)/8.0).coerceIn(0.0,1.0)
        value = from+(target-from)*t*t*(3-2*t)
        return value
    }
}
