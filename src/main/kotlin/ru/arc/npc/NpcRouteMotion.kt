package ru.arc.npc

import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val ROUTE_MOTION_EPSILON = 1.0e-9
private const val ROUTE_ENDPOINT_EPSILON = 1.0e-3
private const val ROUTE_CORNER_CLAMP_DEGREES = 45.0
private const val ROUTE_SHORTCUT_MAX_SOURCE_EDGES = 16

/**
 * Shortens a cell route without moving it farther than [maximumDeviation]
 * from the corresponding source subpath. A shortcut is accepted only when
 * the caller's swept-body traversal check accepts the full segment.
 */
internal fun shortcutNpcRoutePoints(
    points: List<Vector>,
    maximumDeviation: Double,
    canTraverse: (Vector, Vector) -> Boolean,
): List<Vector> {
    require(maximumDeviation.isFinite() && maximumDeviation >= 0.0)

    val original = points.map { it.clone() }
    if (original.size < 3 || maximumDeviation == 0.0) return original

    val result = mutableListOf(original.first())
    var start = 0
    while (start < original.lastIndex) {
        val farthest = minOf(original.lastIndex, start + ROUTE_SHORTCUT_MAX_SOURCE_EDGES)
        var next = start + 1
        for (candidate in farthest downTo start + 2) {
            val level = original[start].y
            if ((start..candidate).any { abs(original[it].y - level) > ROUTE_MOTION_EPSILON }) continue
            // The D-capsule around the chord is convex, so containing every source vertex contains
            // the whole subpath; continuity of its projection then bounds the chord back to it.
            if ((start..candidate).any { index ->
                    distanceToHorizontalSegment(original[index], original[start], original[candidate]) >
                        maximumDeviation + ROUTE_MOTION_EPSILON
                }
            ) continue
            if (!canTraverse(original[start].clone(), original[candidate].clone())) continue
            next = candidate
            break
        }
        result += original[next].clone()
        start = next
    }
    return result
}

private fun distanceToHorizontalSegment(point: Vector, from: Vector, to: Vector): Double {
    val dx = to.x - from.x
    val dz = to.z - from.z
    val lengthSquared = dx * dx + dz * dz
    val progress = if (lengthSquared <= ROUTE_MOTION_EPSILON) 0.0 else
        (((point.x - from.x) * dx + (point.z - from.z) * dz) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(point.x - (from.x + progress * dx), point.z - (from.z + progress * dz))
}

/**
 * Replaces flat, horizontal corners with short quadratic curves.
 * Each curve chord is checked independently so callers can use the same
 * footprint and obstacle rules as the source route.
 */
internal fun roundedNpcRoutePoints(
    points: List<Vector>,
    smoothingDistance: Double,
    maximumDeviation: Double,
    canTraverse: (Vector, Vector) -> Boolean,
): List<Vector> {
    require(smoothingDistance.isFinite() && smoothingDistance >= 0.0)
    require(maximumDeviation.isFinite() && maximumDeviation >= 0.0)

    val original = points.map { it.clone() }
    if (original.size < 3 || smoothingDistance == 0.0 || maximumDeviation == 0.0) return original
    val routePoints = removeFlatCollinearPoints(original)
    if (!routeConnectorsSafe(routePoints, canTraverse)) return original
    if (routePoints.size < 3) return routePoints

    val curves = mutableMapOf<Int, List<Vector>>()
    for (index in 1 until routePoints.lastIndex) {
        val before = routePoints[index - 1]
        val corner = routePoints[index]
        val after = routePoints[index + 1]
        if (
            abs(before.y - corner.y) > ROUTE_MOTION_EPSILON ||
            abs(after.y - corner.y) > ROUTE_MOTION_EPSILON
        ) continue

        val incomingX = corner.x - before.x
        val incomingZ = corner.z - before.z
        val outgoingX = after.x - corner.x
        val outgoingZ = after.z - corner.z
        val incomingLength = hypot(incomingX, incomingZ)
        val outgoingLength = hypot(outgoingX, outgoingZ)
        if (incomingLength <= ROUTE_MOTION_EPSILON || outgoingLength <= ROUTE_MOTION_EPSILON) continue

        val incomingUnitX = incomingX / incomingLength
        val incomingUnitZ = incomingZ / incomingLength
        val outgoingUnitX = outgoingX / outgoingLength
        val outgoingUnitZ = outgoingZ / outgoingLength
        val dot = incomingUnitX * outgoingUnitX + incomingUnitZ * outgoingUnitZ
        if (abs(dot) >= 1.0 - 1.0e-6) continue

        val radius = minOf(
            smoothingDistance,
            incomingLength / 2.0,
            outgoingLength / 2.0,
            maximumDeviation * 4.0,
        )
        if (radius <= ROUTE_MOTION_EPSILON) continue

        val entry = Vector(
            corner.x - incomingUnitX * radius,
            corner.y,
            corner.z - incomingUnitZ * radius,
        )
        val exit = Vector(
            corner.x + outgoingUnitX * radius,
            corner.y,
            corner.z + outgoingUnitZ * radius,
        )
        curves[index] = (0..8).map { sample ->
            quadraticPoint(entry, corner, exit, sample / 8.0)
        }
    }
    if (curves.isEmpty()) return routePoints

    val activeCurves = curves.keys.toMutableSet()
    while (true) {
        val candidate = buildRoundedRoute(routePoints, curves, activeCurves)
        val unsafeOwners = mutableSetOf<Int>()
        for (index in 0 until candidate.points.lastIndex) {
            val from = candidate.points[index]
            val to = candidate.points[index + 1]
            if (!canTraverse(from.clone(), to.clone())) unsafeOwners += candidate.edgeOwners[index]
        }
        if (unsafeOwners.isEmpty()) return candidate.points.map { it.clone() }
        if (unsafeOwners.none(activeCurves::contains)) return original
        activeCurves.removeAll(unsafeOwners)
    }
}

private data class BuiltNpcRoute(val points: List<Vector>, val edgeOwners: List<Set<Int>>)

private fun routeConnectorsSafe(points: List<Vector>, canTraverse: (Vector, Vector) -> Boolean): Boolean =
    points.zipWithNext().all { (from, to) -> canTraverse(from.clone(), to.clone()) }

private fun removeFlatCollinearPoints(points: List<Vector>): List<Vector> {
    if (points.size < 3) return points
    val result = mutableListOf(points.first())
    for (index in 1 until points.lastIndex) {
        val before = result.last()
        val current = points[index]
        val after = points[index + 1]
        val firstX = current.x - before.x
        val firstZ = current.z - before.z
        val secondX = after.x - current.x
        val secondZ = after.z - current.z
        val firstLength = hypot(firstX, firstZ)
        val secondLength = hypot(secondX, secondZ)
        val sameFlat = abs(before.y - current.y) <= ROUTE_MOTION_EPSILON &&
            abs(after.y - current.y) <= ROUTE_MOTION_EPSILON
        val collinearSameDirection = firstLength > ROUTE_MOTION_EPSILON &&
            secondLength > ROUTE_MOTION_EPSILON &&
            abs(firstX * secondZ - firstZ * secondX) <= 1.0e-6 * firstLength * secondLength &&
            firstX * secondX + firstZ * secondZ > 0.0
        if (!sameFlat || !collinearSameDirection) result += current
    }
    result += points.last()
    return result
}

private fun buildRoundedRoute(
    points: List<Vector>,
    curves: Map<Int, List<Vector>>,
    activeCurves: Set<Int>,
): BuiltNpcRoute {
    val result = mutableListOf<Vector>()
    val edgeOwners = mutableListOf<Set<Int>>()
    var previousOwner: Int? = null

    fun append(point: Vector, owner: Int?) {
        val previousPoint = result.lastOrNull()
        if (
            previousPoint != null && (previousOwner != null || owner != null) &&
            horizontalDistance(previousPoint, point) <= ROUTE_MOTION_EPSILON &&
            abs(previousPoint.y - point.y) <= ROUTE_MOTION_EPSILON
        ) {
            previousOwner = owner ?: previousOwner
            return
        }
        if (result.isNotEmpty()) {
            val owners = mutableSetOf<Int>()
            previousOwner?.let { owners += it }
            owner?.let { owners += it }
            edgeOwners += owners
        }
        result += point.clone()
        previousOwner = owner
    }

    append(points.first(), null)
    for (index in 1 until points.lastIndex) {
        val curve = curves[index].takeIf { index in activeCurves }
        if (curve == null) append(points[index], null)
        else curve.forEach { append(it, index) }
    }
    append(points.last(), null)
    return BuiltNpcRoute(result, edgeOwners)
}

private fun quadraticPoint(entry: Vector, corner: Vector, exit: Vector, t: Double): Vector {
    val remainder = 1.0 - t
    return Vector(
        remainder * remainder * entry.x + 2.0 * remainder * t * corner.x + t * t * exit.x,
        corner.y,
        remainder * remainder * entry.z + 2.0 * remainder * t * corner.z + t * t * exit.z,
    )
}

/**
 * Follows a polyline at a fixed look-ahead distance. The cursor advances only
 * from the NPC's physical projection onto a small forward window of the path;
 * it never advances merely because the requested target lies farther ahead.
 */
internal class NpcRouteMotion(points: List<Vector>) {
    private val points = points.map { it.clone() }
    private val cumulativeDistance = DoubleArray(this.points.size)
    private val totalDistance: Double
    private var cursorDistance = 0.0
    private var previousActual: Vector? = null
    private var lastTargetDistance = 0.0

    /** Endpoint index of the segment containing the physical route cursor. */
    var index: Int = 0
        private set

    init {
        for (pointIndex in 1 until this.points.size) {
            val before = this.points[pointIndex - 1]
            val point = this.points[pointIndex]
            cumulativeDistance[pointIndex] = cumulativeDistance[pointIndex - 1] +
                hypot(point.x - before.x, point.z - before.z)
        }
        totalDistance = cumulativeDistance.lastOrNull() ?: 0.0
        updateSegmentTargetIndex(cursorDistance)
    }

    fun target(actual: Vector, travelDistance: Double): Vector {
        require(travelDistance.isFinite() && travelDistance >= 0.0)
        if (points.isEmpty()) return actual.clone()
        if (points.size == 1 || totalDistance <= ROUTE_MOTION_EPSILON) {
            previousActual = actual.clone()
            index = points.lastIndex
            lastTargetDistance = totalDistance
            return points.last().clone()
        }

        val movementSinceLastCall = previousActual?.let {
            hypot(actual.x - it.x, actual.z - it.z)
        } ?: 0.0
        val maximumForwardProgress = min(
            totalDistance,
            cursorDistance + movementSinceLastCall * 1.05 + ROUTE_MOTION_EPSILON,
        )
        projectActualForward(actual, maximumForwardProgress)?.let { projected ->
            cursorDistance = max(cursorDistance, projected)
        }
        previousActual = actual.clone()
        updateSegmentTargetIndex(cursorDistance)
        snapCursorToReachedEndpoint(actual)

        val desiredDistance = min(totalDistance, cursorDistance + travelDistance)
        var targetDistance = desiredDistance
        for (corner in index until points.lastIndex) {
            if (cumulativeDistance[corner] >= desiredDistance - ROUTE_MOTION_EPSILON) break
            if (cornerTurnDegrees(corner) >= ROUTE_CORNER_CLAMP_DEGREES) {
                targetDistance = cumulativeDistance[corner]
                break
            }
        }
        lastTargetDistance = targetDistance
        return pointAt(targetDistance)
    }

    /**
     * Returns the farthest validated route vertex within the most recent
     * look-ahead. Used only when the physical step toward the look-ahead
     * target would hit an obstacle, so ordinary movement keeps the same path.
     */
    fun safeIntermediateTarget(
        actual: Vector,
        canTraverse: (Vector, Vector) -> Boolean,
    ): Vector? {
        if (points.size < 2 || lastTargetDistance <= cursorDistance + ROUTE_MOTION_EPSILON) return null
        var safeTarget: Vector? = null
        for (pointIndex in index..points.lastIndex) {
            val pointDistance = cumulativeDistance[pointIndex]
            if (pointDistance > lastTargetDistance + ROUTE_MOTION_EPSILON) break
            if (pointDistance <= cursorDistance + ROUTE_MOTION_EPSILON) continue
            val candidate = points[pointIndex]
            if (horizontalDistance(actual, candidate) <= ROUTE_MOTION_EPSILON) continue
            if (canTraverse(actual.clone(), candidate.clone())) safeTarget = candidate.clone()
        }
        return safeTarget
    }

    private fun projectActualForward(actual: Vector, maximumProgress: Double): Double? {
        var bestProgress: Double? = null
        var bestDistanceSquared = Double.POSITIVE_INFINITY

        val firstSegment = (index - 1).coerceAtLeast(0)
        for (segment in firstSegment until points.lastIndex) {
            val segmentStart = cumulativeDistance[segment]
            val segmentEnd = cumulativeDistance[segment + 1]
            if (segmentStart > maximumProgress + ROUTE_MOTION_EPSILON) break
            val segmentLength = segmentEnd - segmentStart
            if (segmentLength <= ROUTE_MOTION_EPSILON) continue

            val low = max(cursorDistance, segmentStart)
            val high = min(maximumProgress, segmentEnd)
            if (high + ROUTE_MOTION_EPSILON < low) continue

            val from = points[segment]
            val to = points[segment + 1]
            val dx = to.x - from.x
            val dz = to.z - from.z
            val lengthSquared = dx * dx + dz * dz
            val lowT = ((low - segmentStart) / segmentLength).coerceIn(0.0, 1.0)
            val highT = ((high - segmentStart) / segmentLength).coerceIn(lowT, 1.0)
            val projectedT = (((actual.x - from.x) * dx + (actual.z - from.z) * dz) / lengthSquared)
                .coerceIn(lowT, highT)
            val projectedX = from.x + dx * projectedT
            val projectedZ = from.z + dz * projectedT
            val offsetX = actual.x - projectedX
            val offsetZ = actual.z - projectedZ
            val distanceSquared = offsetX * offsetX + offsetZ * offsetZ
            val progress = segmentStart + segmentLength * projectedT

            if (
                distanceSquared < bestDistanceSquared - ROUTE_MOTION_EPSILON ||
                (abs(distanceSquared - bestDistanceSquared) <= ROUTE_MOTION_EPSILON &&
                    (bestProgress == null || progress < bestProgress))
            ) {
                bestDistanceSquared = distanceSquared
                bestProgress = progress
            }
        }
        return bestProgress
    }

    private fun updateSegmentTargetIndex(progress: Double) {
        if (points.isEmpty()) {
            index = 0
            return
        }
        if (totalDistance <= ROUTE_MOTION_EPSILON) {
            index = points.lastIndex
            return
        }
        if (index < 1) index = 1
        while (index < points.lastIndex && cumulativeDistance[index] <= progress + ROUTE_MOTION_EPSILON) {
            index++
        }
    }

    private fun snapCursorToReachedEndpoint(actual: Vector) {
        if (index !in 1..points.lastIndex) return
        val endpoint = points[index]
        if (hypot(actual.x - endpoint.x, actual.z - endpoint.z) > ROUTE_ENDPOINT_EPSILON) return
        cursorDistance = max(cursorDistance, cumulativeDistance[index])
        updateSegmentTargetIndex(cursorDistance)
    }

    private fun cornerTurnDegrees(pointIndex: Int): Double {
        if (pointIndex !in 1 until points.lastIndex) return 0.0
        val corner = points[pointIndex]
        var beforeIndex = pointIndex - 1
        while (beforeIndex >= 0 && horizontalDistance(points[beforeIndex], corner) <= ROUTE_MOTION_EPSILON) {
            beforeIndex--
        }
        var afterIndex = pointIndex + 1
        while (afterIndex <= points.lastIndex && horizontalDistance(corner, points[afterIndex]) <= ROUTE_MOTION_EPSILON) {
            afterIndex++
        }
        if (beforeIndex < 0 || afterIndex > points.lastIndex) return 0.0

        val before = points[beforeIndex]
        val after = points[afterIndex]
        val incomingX = corner.x - before.x
        val incomingZ = corner.z - before.z
        val outgoingX = after.x - corner.x
        val outgoingZ = after.z - corner.z
        val incomingLength = hypot(incomingX, incomingZ)
        val outgoingLength = hypot(outgoingX, outgoingZ)
        if (incomingLength <= ROUTE_MOTION_EPSILON || outgoingLength <= ROUTE_MOTION_EPSILON) return 0.0
        val dot = ((incomingX * outgoingX + incomingZ * outgoingZ) / (incomingLength * outgoingLength))
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    private fun pointAt(distance: Double): Vector {
        if (distance >= totalDistance) return points.last().clone()
        val firstSegment = (index - 1).coerceAtLeast(0)
        for (segment in firstSegment until points.lastIndex) {
            val start = cumulativeDistance[segment]
            val end = cumulativeDistance[segment + 1]
            if (distance > end + ROUTE_MOTION_EPSILON) continue
            if (end <= start + ROUTE_MOTION_EPSILON) continue
            val t = ((distance - start) / (end - start)).coerceIn(0.0, 1.0)
            val from = points[segment]
            val to = points[segment + 1]
            return Vector(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
                from.z + (to.z - from.z) * t,
            )
        }
        return points.last().clone()
    }
}

private fun horizontalDistance(first: Vector, second: Vector): Double =
    hypot(second.x - first.x, second.z - first.z)
