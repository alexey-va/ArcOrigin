package ru.arc.origin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Surface-relative poses are the single source for live packets and the motion preview. */
internal data class OriginWaiterStationFrame(
    val plate: OriginDiningPoint,
    val tool: OriginDiningPoint?,
    val ingredient: OriginDiningPoint? = null,
    val filled: Boolean = false,
    val contact: OriginDiningPoint = plate,
)

internal fun waiterStationLocal(point: OriginDiningPoint, right: Double = 0.0, up: Double = 0.0,
    forward: Double = 0.0): OriginDiningPoint {
    val yaw = Math.toRadians(point.yaw.toDouble())
    return point.copy(x = point.x + cos(yaw) * right - sin(yaw) * forward, y = point.y + up,
        z = point.z + sin(yaw) * right + cos(yaw) * forward)
}

internal fun waiterStationTravel(from: OriginDiningPoint, to: OriginDiningPoint, progress: Double,
    lift: Double = 0.0): OriginDiningPoint {
    val t = progress.coerceIn(0.0, 1.0)
    val eased = t * t * (3.0 - 2.0 * t)
    val yaw = ((to.yaw - from.yaw + 540f) % 360f) - 180f
    return OriginDiningPoint(from.x + (to.x - from.x) * eased,
        from.y + (to.y - from.y) * eased + sin(PI * t) * lift,
        from.z + (to.z - from.z) * eased, from.yaw + yaw * eased.toFloat())
}

internal fun waiterStationPlatePoint(layout: OriginWaiterStationsLayout) =
    waiterStationLocal(layout.surface(layout.prep), forward = -0.25)

internal fun waiterStationIngredientPoint(layout: OriginWaiterStationsLayout, index: Int, count: Int) =
    waiterStationLocal(waiterStationPoint(layout.surface(layout.prep), index, count), forward = 0.25)

internal fun waiterStationSinkPoint(layout: OriginWaiterStationsLayout) =
    waiterStationPoint(layout.surface(layout.sink), 0, 2).let { it.copy(y = it.y - 0.015) }

internal fun waiterStationPickupPoint(layout: OriginWaiterStationsLayout, assembled: Boolean): OriginDiningPoint =
    if (assembled) waiterStationPlatePoint(layout)
    else waiterStationLocal(layout.surface(layout.cook ?: layout.prep), right = 0.38)

/** Pure, bounded choreography. A returned plate and every later wash use the same handle. */
internal fun waiterStationActionFrame(action: String, layout: OriginWaiterStationsLayout, progress: Double,
    plateStart: OriginDiningPoint, toolStart: OriginDiningPoint, ingredientStart: OriginDiningPoint? = null,
    returning: Boolean = false, stackHeight: Double = 0.0, ingredientIndex: Int = 0,
    plateHeight: Double = 0.0): OriginWaiterStationFrame {
    val p = progress.coerceIn(0.0, 1.0)
    if (action.startsWith("ingredient:")) {
        val plate = waiterStationPlatePoint(layout)
        val source = ingredientStart ?: toolStart
        val ingredient = waiterStationTravel(source, waiterStationLocal(plate,
            right = if (ingredientIndex % 2 == 0) -0.09 else 0.09, up = 0.10 + ingredientIndex * 0.024,
            forward = if (ingredientIndex < 2) 0.0 else 0.09), p, 0.20)
        return OriginWaiterStationFrame(plate, null, ingredient, contact = ingredient)
    }
    val sink = waiterStationSinkPoint(layout)
    val arrival = if (returning) 0.28 else 0.0
    if (p < arrival) return OriginWaiterStationFrame(
        waiterStationTravel(plateStart, sink, p / arrival, 0.16), toolStart)
    val work = ((p - arrival) / (1.0 - arrival)).coerceIn(0.0, 1.0)
    val envelope = sin(PI * work)
    val stroke = sin(work * PI * 6.0)
    val plate = when (action) {
        // Keep the dish supported until the cloth has cleared it; simultaneous lifts intersect.
        "wash:DRY" -> if (work < 0.66) sink else waiterStationTravel(sink,
            layout.surface(layout.rack).let { it.copy(y = it.y + stackHeight) }, (work - 0.66) / 0.34, 0.27)
        "wash:SOAK" -> waiterStationLocal(sink, right = 0.025 * sin(work * PI * 4) * envelope,
            up = 0.035 * envelope)
        "wash:RINSE" -> waiterStationLocal(sink, right = 0.055 * stroke * envelope,
            up = 0.08 * envelope).let { it.copy(yaw = it.yaw + (12.0 * stroke * envelope).toFloat()) }
        else -> waiterStationLocal(sink, right = 0.025 * stroke * envelope)
            .let { it.copy(yaw = it.yaw + (7.0 * stroke * envelope).toFloat()) }
    }
    // Generated vanilla item: z=7.5..8.5 /16, NONE + X(-90), scale .48 => half-thickness .015.
    // Its lower face contacts the resolved top of this dish, including the thinner fish plate.
    val toolHeight = plateHeight + 0.015
    val workingTool = when (action) {
        "wash:SOAK", "wash:RINSE" -> waiterStationLocal(plate, right = 0.08, up = toolHeight)
        "wash:DRY" -> waiterStationLocal(plate, right = 0.11 * stroke, up = toolHeight)
        else -> waiterStationLocal(plate, right = 0.12 * stroke, up = toolHeight,
            forward = 0.035 * cos(work * PI * 6))
    }
    val tool = when {
        // Withdraw the cloth when wiping ends; it must not descend through the arriving rack stack.
        action == "wash:DRY" && work >= 0.48 -> waiterStationTravel(
            waiterStationLocal(sink, right = 0.11 * sin(0.48 * PI * 6.0), up = toolHeight),
            toolStart, (work - 0.48) / 0.18, 0.06)
        work < 0.12 -> waiterStationTravel(toolStart, workingTool, work / 0.12, 0.08)
        work > 0.86 -> waiterStationTravel(workingTool, toolStart, (work - 0.86) / 0.14, 0.06)
        else -> workingTool
    }
    return OriginWaiterStationFrame(plate, tool, contact = waiterStationLocal(plate, up = plateHeight))
}

/** Stir, lift a portion, and lay it on the same empty plate before the ready cue. */
internal fun waiterStationCookingFrame(layout: OriginWaiterStationsLayout, progress: Double,
    elapsedMillis: Long): OriginWaiterStationFrame {
    val p = progress.coerceIn(0.0, 1.0)
    val cook = layout.surface(layout.cook ?: layout.prep)
    val plate = waiterStationPickupPoint(layout, false)
    val angle = elapsedMillis / 1000.0 * PI * 2.0
    val stirring = waiterStationLocal(cook, right = -0.22 + cos(angle) * 0.10,
        up = 0.39 + 0.035 * sin(angle * 2), forward = sin(angle) * 0.10)
    val ingredient = if (p < 0.78) waiterStationLocal(cook, right = -0.22, up = 0.29)
        else waiterStationTravel(waiterStationLocal(cook, right = -0.22, up = 0.29),
            waiterStationLocal(plate, up = 0.09), (p - 0.78) / 0.18, 0.16)
    val platingTool = waiterStationLocal(ingredient, right = -0.06, up = 0.085)
    val tool = if (p < 0.78) waiterStationTravel(stirring,
        waiterStationLocal(cook, right = -0.28, up = 0.375), ((p - 0.70) / 0.08).coerceIn(0.0, 1.0)) else platingTool
    return OriginWaiterStationFrame(plate, tool, ingredient, filled = p >= 0.96,
        contact = if (p < 0.78) waiterStationLocal(cook, right = -0.22, up = 0.30) else ingredient)
}

/** Resolved empty-model heights above their GROUND support, before the configured entity scale. */
internal fun waiterStationPlateHeight(dishId: String): Double {
    val config = OriginDiningLayout.lifeConfig
    val prop = config.emptyPlates[dishId] ?: config.emptyPlate
    return when (prop.item.substringAfter(':')) {
        "restaurant_food_halffish_empty" -> 0.0625 * prop.scale
        "restaurant_food_eggbeacon_empty", "restaurant_food_steak_empty" -> 0.125 * prop.scale
        else -> 0.0 // Unknown models remain a single plate; do not guess a stack contact plane.
    }
}
