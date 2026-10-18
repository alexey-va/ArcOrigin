package ru.arc.npc

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.AbstractPathStrategy
import net.citizensnpcs.api.ai.Navigator
import net.citizensnpcs.api.ai.NavigatorParameters
import net.citizensnpcs.api.ai.PathStrategy
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.ai.TargetType
import net.citizensnpcs.api.ai.event.CancelReason
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.util.NMS
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sign

internal data class NpcRouteCell(val x: Int, val z: Int)

internal data class NpcRouteBounds(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    init {
        require(minX <= maxX && minZ <= maxZ)
    }

    operator fun contains(cell: NpcRouteCell): Boolean = cell.x in minX..maxX && cell.z in minZ..maxZ
}

/**
 * Reusable routing policy for one bounded NPC movement area, flat by default.
 *
 * Forbidden areas are hard walls. Preferred areas reduce the A* step cost, so
 * callers can describe corridors without turning every point into a waypoint.
 */
internal data class NpcRouteProfile(
    val id: String,
    val floorY: Int,
    val bounds: NpcRouteBounds,
    val forbidden: List<NpcRouteBounds> = emptyList(),
    val preferred: List<NpcRouteBounds> = emptyList(),
    val maxVisited: Int = 1_024,
    val snapRadius: Int = 3,
    val pollTicks: Long = 2L,
    val stallPolls: Int = 20,
    val offFloorTolerance: Double = 0.45,
    val distanceMargin: Double = 0.35,
    val pathDistanceMargin: Double = 0.35,
    val speedModifier: Float = 0.72f,
    val entityObstaclePadding: Double = 0.25,
    val obstacleRefreshPolls: Int = 10,
    val headingLookAheadCells: Int = 2,
    val headingUpdateTicks: Long = 1L,
    val headingMaxTurnDegreesPerTick: Float = 18f,
    val cornerSmoothingDistance: Double = 0.75,
    val cornerSmoothingLead: Double = 0.30,
    val pathShortcutMaximumDeviation: Double = 1.0,
    val obstacleClearance: Double = 0.25,
    val maximumStepHeight: Double = 0.125,
    val maximumSurfaceDrop: Double = 0.0,
    val surfaceSearchRange: Int = 0,
    val allowedSupportMaterials: Set<Material> = emptySet(),
) {
    init {
        require(pathShortcutMaximumDeviation.isFinite() && pathShortcutMaximumDeviation in 0.0..2.0)
        require(obstacleClearance.isFinite() && obstacleClearance in 0.0..0.5)
        require(maximumStepHeight.isFinite() && maximumStepHeight in 0.0..1.0) {
            "Npc route maximum-step-height must be finite and within 0.0..1.0"
        }
        require(maximumSurfaceDrop.isFinite() && maximumSurfaceDrop in 0.0..0.5) {
            "Npc route maximum-surface-drop must be finite and within 0.0..0.5"
        }
        require(surfaceSearchRange in 0..2) {
            "Npc route surface-search-range must be within 0..2"
        }
        require(surfaceSearchRange == 0 || allowedSupportMaterials.isNotEmpty()) {
            "Npc route surface-search-range requires allowed-support-materials"
        }
    }

    fun allows(cell: NpcRouteCell): Boolean = cell in bounds && forbidden.none { cell in it }

    fun stepCost(cell: NpcRouteCell): Int = if (preferred.any { cell in it }) 1 else 10
}

internal fun normalizedNpcYaw(yaw: Float): Float = ((yaw % 360f) + 540f) % 360f - 180f

internal fun npcRouteYaw(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Float =
    normalizedNpcYaw(Math.toDegrees(atan2(-(toX - fromX), toZ - fromZ)).toFloat())

internal fun turnNpcYawToward(current: Float, target: Float, maximumDegrees: Float): Float {
    val delta = normalizedNpcYaw(target - current)
    if (abs(delta) <= maximumDegrees) return normalizedNpcYaw(target)
    return normalizedNpcYaw(current + delta.sign * maximumDegrees)
}

internal fun npcRouteHorizontalVelocity(
    fromX: Double,
    fromZ: Double,
    toX: Double,
    toZ: Double,
    maximumStep: Double,
): Vector {
    val dx = toX - fromX
    val dz = toZ - fromZ
    val distance = hypot(dx, dz)
    if (distance <= 1.0e-6 || maximumStep <= 0.0) return Vector()
    val step = minOf(distance, maximumStep)
    return Vector(dx / distance * step, 0.0, dz / distance * step)
}

internal fun isNpcRouteResolvedEndpointReached(
    actualX: Double,
    actualZ: Double,
    endpoint: NpcRouteCell,
    margin: Double,
): Boolean {
    val dx = actualX - (endpoint.x + 0.5)
    val dz = actualZ - (endpoint.z + 0.5)
    return dx * dx + dz * dz <= margin * margin
}

internal data class NpcRouteFootprint(
    val halfWidth: Double,
    val halfDepth: Double,
    val minYOffset: Double,
    val maxYOffset: Double,
) {
    init {
        require(halfWidth.isFinite() && halfWidth > 0.0)
        require(halfDepth.isFinite() && halfDepth > 0.0)
        require(minYOffset.isFinite() && maxYOffset.isFinite() && maxYOffset > minYOffset)
    }
}

internal fun npcRouteFootprint(entity: Entity): NpcRouteFootprint {
    val originY = entity.location.y
    val box = entity.boundingBox
    val bodyHalfExtent = maxOf(box.maxX - box.minX, box.maxZ - box.minZ) / 2.0
    return NpcRouteFootprint(
        halfWidth = bodyHalfExtent,
        halfDepth = bodyHalfExtent,
        minYOffset = box.minY - originY,
        maxYOffset = box.maxY - originY,
    )
}

/** Furniture just outside the path search area can still intersect the actor's body. */
internal fun npcRouteObstacleProfile(profile: NpcRouteProfile, footprint: NpcRouteFootprint): NpcRouteProfile {
    val padding = ceil(maxOf(footprint.halfWidth, footprint.halfDepth)).toInt()
    val bounds = profile.bounds
    return profile.copy(bounds = NpcRouteBounds(
        bounds.minX - padding, bounds.maxX + padding, bounds.minZ - padding, bounds.maxZ + padding,
    ))
}

private const val NPC_ROUTE_COLLISION_EPSILON = 1.0e-6
private const val NPC_ROUTE_MAX_EDGE_LIFT = 1.0 / 16.0

private fun npcRouteRangesOverlap(minA: Double, maxA: Double, minB: Double, maxB: Double): Boolean =
    minA < maxB - NPC_ROUTE_COLLISION_EPSILON && maxA > minB + NPC_ROUTE_COLLISION_EPSILON

/** Segment versus a collision rectangle expanded by the actor's horizontal body. */
private fun npcRouteSweepOverlaps(
    fromX: Double, fromZ: Double, toX: Double, toZ: Double,
    footprint: NpcRouteFootprint,
    minX: Double, maxX: Double, minZ: Double, maxZ: Double,
): Boolean {
    var entry = 0.0
    var exit = 1.0
    fun overlapsAxis(from: Double, to: Double, low: Double, high: Double): Boolean {
        val delta = to - from
        if (abs(delta) <= NPC_ROUTE_COLLISION_EPSILON) return from > low && from < high
        val first = (low - from) / delta
        val second = (high - from) / delta
        entry = maxOf(entry, minOf(first, second))
        exit = minOf(exit, maxOf(first, second))
        return entry < exit
    }
    return overlapsAxis(fromX, toX, minX - footprint.halfWidth + NPC_ROUTE_COLLISION_EPSILON,
        maxX + footprint.halfWidth - NPC_ROUTE_COLLISION_EPSILON) &&
        overlapsAxis(fromZ, toZ, minZ - footprint.halfDepth + NPC_ROUTE_COLLISION_EPSILON,
            maxZ + footprint.halfDepth - NPC_ROUTE_COLLISION_EPSILON)
}

/**
 * Checks the entity-sized configuration-space footprint at one route cell.
 * Support below the feet is intentionally excluded when it only touches the
 * feet plane; adjacent native collision boxes still participate in the test.
 */
internal fun isNpcRouteFootprintClear(
    world: World,
    cell: NpcRouteCell,
    surfaceY: Double,
    footprint: NpcRouteFootprint,
    profile: NpcRouteProfile? = null,
): Boolean = isNpcRoutePositionClear(world, cell.x + 0.5, cell.z + 0.5, surfaceY, footprint, profile)

private fun isNpcRoutePositionClear(
    world: World,
    x: Double,
    z: Double,
    surfaceY: Double,
    footprint: NpcRouteFootprint,
    profile: NpcRouteProfile?,
    toX: Double = x,
    toZ: Double = z,
): Boolean {
    if (!surfaceY.isFinite()) return false
    val minX = minOf(x, toX) - footprint.halfWidth
    val maxX = maxOf(x, toX) + footprint.halfWidth
    val minZ = minOf(z, toZ) - footprint.halfDepth
    val maxZ = maxOf(z, toZ) + footprint.halfDepth
    val minY = surfaceY + footprint.minYOffset
    val maxY = surfaceY + footprint.maxYOffset + (profile?.maximumStepHeight ?: 0.0)
    if (!minY.isFinite() || !maxY.isFinite() || maxY <= minY + NPC_ROUTE_COLLISION_EPSILON) return false

    val minBlockX = floor(minX + NPC_ROUTE_COLLISION_EPSILON).toInt()
    val maxBlockX = ceil(maxX - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1
    // Fences and walls can extend above their owning block into the feet plane.
    val minBlockY = floor(minY + NPC_ROUTE_COLLISION_EPSILON).toInt() - 1
    val maxBlockY = ceil(maxY - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1
    val minBlockZ = floor(minZ + NPC_ROUTE_COLLISION_EPSILON).toInt()
    val maxBlockZ = ceil(maxZ - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1

    val collisions = mutableListOf<BoundingBox>()
    val edgeLiftTops = mutableListOf<Double>()
    val edgeLiftAllowance = profile?.takeIf {
        it.allowedSupportMaterials.isNotEmpty() && it.maximumSurfaceDrop > 0.0
    }?.let { minOf(it.maximumSurfaceDrop, it.maximumStepHeight, NPC_ROUTE_MAX_EDGE_LIFT) } ?: 0.0
    var standingY = surfaceY
    for (blockX in minBlockX..maxBlockX) {
        for (y in minBlockY..maxBlockY) {
            for (blockZ in minBlockZ..maxBlockZ) {
                if (!npcRouteSweepOverlaps(x, z, toX, toZ, footprint,
                        blockX.toDouble(), blockX + 1.0, blockZ.toDouble(), blockZ + 1.0)) continue
                val block = world.getBlockAt(blockX, y, blockZ)
                if (block.isLiquid && npcRouteRangesOverlap(minY, maxY, y.toDouble(), y + 1.0)) return false
                for (box in block.collisionShape.boundingBoxes) {
                    val boxMinX = blockX + box.minX
                    val boxMaxX = blockX + box.maxX
                    val boxMinY = y + box.minY
                    val boxMaxY = y + box.maxY
                    val boxMinZ = blockZ + box.minZ
                    val boxMaxZ = blockZ + box.maxZ
                    if (
                        npcRouteSweepOverlaps(x, z, toX, toZ, footprint, boxMinX, boxMaxX, boxMinZ, boxMaxZ)
                    ) {
                        collisions += BoundingBox(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)
                        val allowedStep = profile != null &&
                            (block.type in profile.allowedSupportMaterials ||
                                isNpcRouteFloorCovering(block.type, box.maxY, profile.maximumStepHeight)) &&
                            boxMaxY <= surfaceY + profile.maximumStepHeight + NPC_ROUTE_COLLISION_EPSILON
                        if (allowedStep) standingY = maxOf(standingY, boxMaxY)
                        else if (
                            edgeLiftAllowance > NPC_ROUTE_COLLISION_EPSILON &&
                            isNpcRouteFullTopSolidBox(block.type, box)
                        ) {
                            edgeLiftTops += boxMaxY
                        }
                    }
                }
            }
        }
    }
    val supportedStandingY = standingY
    val maximumStandingY = surfaceY + (profile?.maximumStepHeight ?: 0.0)
    standingY = maxOf(
        standingY,
        edgeLiftTops.filter { top ->
            top > supportedStandingY + NPC_ROUTE_COLLISION_EPSILON &&
                top <= supportedStandingY + edgeLiftAllowance + NPC_ROUTE_COLLISION_EPSILON &&
                top <= maximumStandingY + NPC_ROUTE_COLLISION_EPSILON
        }.maxOrNull() ?: supportedStandingY,
    )
    return collisions.none { box ->
        npcRouteRangesOverlap(standingY + footprint.minYOffset, standingY + footprint.maxYOffset, box.minY, box.maxY)
    }
}

private fun isNpcRouteFullTopSolidBox(type: Material, box: BoundingBox): Boolean =
    type.isSolid &&
        box.minX <= NPC_ROUTE_COLLISION_EPSILON && box.maxX >= 1.0 - NPC_ROUTE_COLLISION_EPSILON &&
        box.minY <= NPC_ROUTE_COLLISION_EPSILON && box.maxY >= 1.0 - NPC_ROUTE_COLLISION_EPSILON &&
        box.minZ <= NPC_ROUTE_COLLISION_EPSILON && box.maxZ >= 1.0 - NPC_ROUTE_COLLISION_EPSILON

/** Swept body check: a clear center cell alone does not make a turn safe. */
internal fun isNpcRouteSegmentClear(
    world: World,
    profile: NpcRouteProfile,
    from: Vector,
    to: Vector,
    footprint: NpcRouteFootprint,
    blocked: Set<NpcRouteCell> = emptySet(),
): Boolean {
    val minX = minOf(from.x, to.x) - footprint.halfWidth
    val maxX = maxOf(from.x, to.x) + footprint.halfWidth
    val minZ = minOf(from.z, to.z) - footprint.halfDepth
    val maxZ = maxOf(from.z, to.z) + footprint.halfDepth
    for (x in floor(minX + NPC_ROUTE_COLLISION_EPSILON).toInt()..ceil(maxX - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1) {
        for (z in floor(minZ + NPC_ROUTE_COLLISION_EPSILON).toInt()..ceil(maxZ - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1) {
            val cell = NpcRouteCell(x, z)
            if ((profile.forbidden.any { cell in it } || cell in blocked) && npcRouteSweepOverlaps(
                    from.x, from.z, to.x, to.z, footprint, x.toDouble(), x + 1.0, z.toDouble(), z + 1.0,
                )) return false
        }
    }
    val samples = ceil(hypot(to.x - from.x, to.z - from.z) / 0.1).toInt().coerceAtLeast(1)
    val surfaces = mutableMapOf<NpcRouteCell, Double?>()
    var previousY: Double? = null
    var lowestY = Double.POSITIVE_INFINITY
    for (index in 0..samples) {
        val progress = index.toDouble() / samples
        val x = from.x + (to.x - from.x) * progress
        val z = from.z + (to.z - from.z) * progress
        val cell = NpcRouteCell(floor(x).toInt(), floor(z).toInt())
        // Bounds constrain path centers; walls, furniture and no-go cells constrain the entire body.
        if (!profile.allows(cell)) return false
        val surface = surfaces.getOrPut(cell) { resolveSurfaceY(world, profile, cell) } ?: return false
        if (previousY?.let { abs(it - surface) > profile.maximumStepHeight + NPC_ROUTE_COLLISION_EPSILON } == true) return false
        previousY = surface
        lowestY = minOf(lowestY, surface)
    }
    return isNpcRoutePositionClear(world, from.x, from.z, lowestY, footprint, profile, to.x, to.z)
}

internal fun isNpcRouteFloorCovering(type: Material, collisionHeight: Double, maximumStepHeight: Double): Boolean =
    type.name.endsWith("_CARPET") && collisionHeight <= maximumStepHeight + 1.0e-6

internal fun isNpcRouteSupportBoxAllowed(type: Material, box: BoundingBox, profile: NpcRouteProfile): Boolean {
    if (box.minX > 0.3 || box.maxX < 0.7 || box.minZ > 0.3 || box.maxZ < 0.7) return false
    if (profile.allowedSupportMaterials.isEmpty()) return box.maxY >= 0.999
    return type in profile.allowedSupportMaterials &&
        box.maxY + 1.0e-6 >= 1.0 - profile.maximumSurfaceDrop
}

/**
 * Resolves the highest safe surface around a route cell.
 *
 * The default profile only inspects its authored level.  An opt-in profile
 * may scan a bounded number of nominal feet levels, but only for explicitly
 * permitted support materials; an absent surface remains a hard failure.
 */
internal fun resolveSurfaceY(world: World, profile: NpcRouteProfile, cell: NpcRouteCell): Double? {
    val range = profile.surfaceSearchRange
    for (feetY in (profile.floorY + range) downTo (profile.floorY - range)) {
        val feet = world.getBlockAt(cell.x, feetY, cell.z)
        val head = world.getBlockAt(cell.x, feetY + 1, cell.z)
        val support = world.getBlockAt(cell.x, feetY - 1, cell.z)
        if (!head.isPassable || feet.isLiquid || head.isLiquid || support.isLiquid) continue
        val feetCollisionHeight = feet.collisionShape.boundingBoxes.maxOfOrNull { it.maxY } ?: 0.0
        if (!feet.isPassable && !isNpcRouteFloorCovering(feet.type, feetCollisionHeight, profile.maximumStepHeight)) continue
        val supportTop = support.collisionShape.boundingBoxes
            .filter { box -> isNpcRouteSupportBoxAllowed(support.type, box, profile) }
            .maxOfOrNull { it.maxY }
            ?: continue
        if (range == 0 && profile.allowedSupportMaterials.isEmpty()) return profile.floorY.toDouble()
        return feetY - 1.0 + supportTop
    }
    return null
}

internal fun isNpcRouteActualYAllowed(actualY: Double, surfaceY: Double, profile: NpcRouteProfile): Boolean {
    if (!actualY.isFinite() || !surfaceY.isFinite()) return false
    val lower = surfaceY - profile.offFloorTolerance
    val transientAllowance = if (profile.surfaceSearchRange > 0) profile.maximumStepHeight else 0.0
    val upper = surfaceY + profile.offFloorTolerance + transientAllowance
    return actualY >= lower - 1.0e-6 && actualY <= upper + 1.0e-6
}

private fun isNpcRouteStartYAllowed(actualY: Double, surfaceY: Double, profile: NpcRouteProfile): Boolean =
    if (profile.surfaceSearchRange == 0 && profile.allowedSupportMaterials.isEmpty()) abs(actualY - surfaceY) <= 0.25
    else isNpcRouteActualYAllowed(actualY, surfaceY, profile)

/** A safe off-centre body can walk into the grid even when its cell centre clips a corner. */
internal fun npcRouteWalkingStart(
    actual: Vector,
    profile: NpcRouteProfile,
    surfaceY: (NpcRouteCell) -> Double?,
    canTraverse: (Vector, Vector) -> Boolean,
): NpcRouteCell? {
    val current = NpcRouteCell(floor(actual.x).toInt(), floor(actual.z).toInt())
    val surface = surfaceY(current) ?: return null
    if (!profile.allows(current) || !isNpcRouteStartYAllowed(actual.y, surface, profile) || !canTraverse(actual, actual)) return null
    return (-1..1).flatMap { dx -> (-1..1).map { dz -> NpcRouteCell(current.x + dx, current.z + dz) } }
        .filter(profile::allows)
        .sortedWith(compareBy<NpcRouteCell>(
            { (it.x + 0.5 - actual.x) * (it.x + 0.5 - actual.x) + (it.z + 0.5 - actual.z) * (it.z + 0.5 - actual.z) },
            { it.x }, { it.z }))
        .firstOrNull { cell -> surfaceY(cell)?.let { y -> canTraverse(actual, Vector(cell.x + 0.5, y, cell.z + 0.5)) } == true }
}

/** Supplies scene-specific occupied cells without coupling the router to a furniture plugin. */
internal fun interface NpcRouteObstacleSource {
    fun blockedCells(world: World, profile: NpcRouteProfile): Set<NpcRouteCell>
}

internal data class NpcRouteEvent(
    val phase: String,
    val profileId: String,
    val npcId: Int,
    val actual: Location,
    val target: Location,
    val cells: Int = 0,
    val reason: String? = null,
)

internal data class NpcRouteOutcome(
    val successful: Boolean,
    val phase: String,
    val reason: String? = null,
)

internal class NpcRouteOutcomeTracker {
    private val outcomes = mutableMapOf<Int, NpcRouteOutcome>()

    fun reset(npcId: Int) {
        outcomes.remove(npcId)
    }

    fun record(npcId: Int, outcome: NpcRouteOutcome) {
        outcomes[npcId] = outcome
    }

    fun consume(npcId: Int): NpcRouteOutcome? = outcomes.remove(npcId)

    fun clear() = outcomes.clear()
}

/** Bounded A*: diagonals are opt-in and callers constrain every swept edge. */
internal fun findNpcGridPath(
    start: NpcRouteCell,
    goals: List<NpcRouteCell>,
    profile: NpcRouteProfile,
    canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { _, _ -> true },
    allowDiagonalConnections: Boolean = false,
    stepCost: (NpcRouteCell) -> Int = profile::stepCost,
    minimumStepCost: Int = if (profile.preferred.isEmpty()) 10 else 1,
    isWalkable: (NpcRouteCell) -> Boolean,
): List<NpcRouteCell>? {
    if (!profile.allows(start) || !isWalkable(start)) return null
    val allowedGoals = goals.filter { profile.allows(it) && isWalkable(it) }.toSet()
    if (allowedGoals.isEmpty()) return null
    if (start in allowedGoals) return listOf(start)
    fun heuristic(cell: NpcRouteCell): Int = minimumStepCost * allowedGoals.minOf {
        val dx = abs(cell.x - it.x)
        val dz = abs(cell.z - it.z)
        10 * (dx + dz) - if (allowDiagonalConnections) 6 * minOf(dx, dz) else 0
    }
    data class Candidate(val cell: NpcRouteCell, val cost: Int, val estimate: Int)
    val open = PriorityQueue(compareBy<Candidate>({ it.estimate }, { heuristic(it.cell) }, { it.cell.x }, { it.cell.z }))
    val costs = mutableMapOf(start to 0)
    val previous = mutableMapOf<NpcRouteCell, NpcRouteCell>()
    val closed = mutableSetOf<NpcRouteCell>()
    open += Candidate(start, 0, heuristic(start))
    while (open.isNotEmpty() && closed.size < profile.maxVisited) {
        val current = open.remove()
        if (current.cost != costs[current.cell] || !closed.add(current.cell)) continue
        if (current.cell in allowedGoals) {
            val path = mutableListOf(current.cell)
            while (path.last() != start) path += previous.getValue(path.last())
            return path.asReversed()
        }
        val neighbors = buildList {
            add(NpcRouteCell(current.cell.x + 1, current.cell.z) to false)
            add(NpcRouteCell(current.cell.x - 1, current.cell.z) to false)
            add(NpcRouteCell(current.cell.x, current.cell.z + 1) to false)
            add(NpcRouteCell(current.cell.x, current.cell.z - 1) to false)
            if (allowDiagonalConnections) {
                add(NpcRouteCell(current.cell.x + 1, current.cell.z + 1) to true)
                add(NpcRouteCell(current.cell.x + 1, current.cell.z - 1) to true)
                add(NpcRouteCell(current.cell.x - 1, current.cell.z + 1) to true)
                add(NpcRouteCell(current.cell.x - 1, current.cell.z - 1) to true)
            }
        }
        for ((next, diagonal) in neighbors) {
            if (!profile.allows(next) || next in closed || !isWalkable(next) || !canTraverse(current.cell, next)) continue
            val nextCost = current.cost + stepCost(next) * (if (diagonal) 14 else 10)
            if (nextCost >= costs.getOrDefault(next, Int.MAX_VALUE)) continue
            costs[next] = nextCost
            previous[next] = current.cell
            open += Candidate(next, nextCost, nextCost + heuristic(next))
        }
    }
    return null
}

/**
 * Tries snapped endpoints in their caller-provided order instead of treating
 * every endpoint as an equivalent A* goal. This keeps an obstructed target
 * close to its requested position and prevents the start cell from becoming a
 * false one-cell success merely because it lies inside the snap radius.
 */
internal fun findNpcGridPathToNearestCandidate(
    start: NpcRouteCell,
    goals: List<NpcRouteCell>,
    profile: NpcRouteProfile,
    canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { _, _ -> true },
    allowDiagonalConnections: Boolean = false,
    stepCost: (NpcRouteCell) -> Int = profile::stepCost,
    minimumStepCost: Int = if (profile.preferred.isEmpty()) 10 else 1,
    isWalkable: (NpcRouteCell) -> Boolean,
): List<NpcRouteCell>? =
    goals.asSequence()
        .filter { it != start }
        .mapNotNull { goal ->
            findNpcGridPath(
                start, listOf(goal), profile, canTraverse,
                allowDiagonalConnections = allowDiagonalConnections,
                stepCost = stepCost,
                minimumStepCost = minimumStepCost,
                isWalkable = isWalkable,
            ) ?: if (allowDiagonalConnections) {
                // The larger graph may exhaust its visit budget; retain a bounded axial retry.
                findNpcGridPath(start, listOf(goal), profile, canTraverse,
                    stepCost = stepCost, minimumStepCost = minimumStepCost, isWalkable = isWalkable)
            } else null
        }
        .firstOrNull()

private data class ActiveNpcRoute(
    val token: UUID,
    val strategy: PathStrategy,
    val profile: NpcRouteProfile,
    val destination: Location,
    val via: List<Location>,
    val extraBlocked: Set<NpcRouteCell>,
    var blocked: Set<NpcRouteCell>,
    val cells: List<NpcRouteCell>,
    val surfaceYs: Map<NpcRouteCell, Double>,
    val faceOnArrival: Boolean,
    val arrivalLookAt: Location?,
    var obstaclePolls: Int = 0,
    var stalledPolls: Int = 0,
    var previousX: Double,
    var previousZ: Double,
    val recoveries: Int = 0,
)

/** A stale worksite owner may cancel its own installed strategy, never a replacement. */
internal fun stopOwnedNpcRoute(navigator: Navigator, strategy: PathStrategy) {
    if (navigator.pathStrategy === strategy) navigator.cancelNavigation()
}

internal fun canResumeOwnedNpcRoute(navigator: Navigator, strategy: PathStrategy): Boolean =
    navigator.pathStrategy.let { it == null || it === strategy }

/** Range to the final endpoint must cover the entire detour, not just its first cell. */
internal fun npcRouteRequiredRange(start: Vector, points: List<Vector>, margin: Double): Float {
    val destination = points.last()
    // Citizens checks distance to the final destination before every custom-strategy update.
    return (maxOf(start.distance(destination), points.maxOf { it.distance(destination) }) + margin + 1.0).toFloat()
}

/**
 * Follows ARC's already validated cells directly. Citizens' built-in iterable
 * strategy hands every cell back to the Minecraft navigator for living NPCs,
 * which may choose a nearby stair or tabletop and leave the fixed floor.
 */
private class FixedLevelPathStrategy(
    private val npc: NPC,
    private val world: World,
    private val points: List<Vector>,
    private val destinationMargin: Double,
    private val speedModifier: Float,
    private val maximumStepHeight: Double,
    maximumTurnDegreesPerTick: Float,
    private val finalFacing: Location?,
    private val arrivalLookAt: Location?,
    private val canTraverse: (Vector, Vector) -> Boolean,
) : AbstractPathStrategy(TargetType.LOCATION) {
    private val motion = NpcRouteMotion(points)
    private val rotation = NpcRouteRotation(npc, maximumTurnDegreesPerTick)
    private val stepHeight = (npc.entity as? LivingEntity)?.getAttribute(Attribute.STEP_HEIGHT)
    private val originalStepHeight = stepHeight?.baseValue
    private var restoredStepHeight = false

    init {
        stepHeight?.baseValue = maximumStepHeight
    }

    override fun getCurrentDestination(): Location = points[motion.index.coerceAtMost(points.lastIndex)].toLocation(world)

    override fun getPath(): Iterable<Vector> = points.drop(motion.index)

    override fun getTargetAsLocation(): Location = points.last().toLocation(world)

    override fun stop() {
        rotation.close()
        if (!restoredStepHeight) {
            restoredStepHeight = true
            if (stepHeight?.baseValue == maximumStepHeight && originalStepHeight != null) {
                stepHeight.baseValue = originalStepHeight
            }
        }
        if (!npc.isSpawned) return
        val velocity = npc.entity.velocity
        npc.entity.velocity = Vector(0.0, velocity.y, 0.0)
    }

    override fun update(): Boolean {
        if (!npc.isSpawned) {
            setCancelReason(CancelReason.NPC_DESPAWNED)
            return true
        }
        val entity = npc.entity
        // Horse adapters can reset this before every Citizens navigator update.
        if (stepHeight != null && stepHeight.baseValue != maximumStepHeight) stepHeight.baseValue = maximumStepHeight
        if (entity.world != world) {
            setCancelReason(CancelReason.TARGET_MOVED_WORLD)
            return true
        }
        val actual = entity.location
        if (motion.index >= points.lastIndex && horizontalDistanceSquared(actual, points.last()) <= destinationMargin * destinationMargin) {
            val velocity = entity.velocity
            entity.velocity = Vector(0.0, velocity.y, 0.0)
            val facing = arrivalLookAt?.let { target ->
                val eye = (entity as? LivingEntity)?.eyeLocation ?: actual
                val direction = target.toVector().subtract(eye.toVector())
                if (direction.lengthSquared() < 1e-8) null else eye.clone().setDirection(direction)
            } ?: finalFacing
            if (facing != null && !rotation.turnToward(facing.yaw, facing.pitch)) return false
            stop()
            return true
        }
        val actualVector = actual.toVector()
        val travelDistance = 0.2 * speedModifier
        val target = motion.target(actualVector, travelDistance)
        var horizontal = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, travelDistance)
        var next = actualVector.clone().add(horizontal)
        if (!canTraverse(actual.toVector(), next)) {
            val intermediate = motion.safeIntermediateTarget(actualVector, canTraverse)
            if (intermediate == null) {
                stop()
                setCancelReason(CancelReason.STUCK)
                return true
            }
            horizontal = npcRouteHorizontalVelocity(actual.x, actual.z, intermediate.x, intermediate.z, travelDistance)
            next = actualVector.clone().add(horizontal)
            if (!canTraverse(actualVector, next)) {
                stop()
                setCancelReason(CancelReason.STUCK)
                return true
            }
        }
        horizontal.y = entity.velocity.y.coerceAtMost(0.0)
        entity.velocity = horizontal
        if (horizontal.x * horizontal.x + horizontal.z * horizontal.z > 1.0e-8) {
            rotation.turnToward(npcRouteYaw(0.0, 0.0, horizontal.x, horizontal.z))
        }
        return false
    }

    private fun horizontalDistanceSquared(actual: Location, target: Vector): Double {
        val dx = actual.x - target.x
        val dz = actual.z - target.z
        return dx * dx + dz * dz
    }
}

/**
 * Citizens adapter for precomputed ARC paths. Citizens owns navigation events
 * and animation, while ARC's strategy owns the exact horizontal movement.
 */
internal class CitizensNpcRouteController(
    private val onEvent: (NpcRouteEvent) -> Unit = {},
    private val obstacleSource: NpcRouteObstacleSource = NpcRouteObstacleSource { _, _ -> emptySet() },
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val active = mutableMapOf<Int, ActiveNpcRoute>()
    private val outcomes = NpcRouteOutcomeTracker()

    fun navigate(
        npc: NPC,
        destination: Location,
        profile: NpcRouteProfile,
        via: List<Location> = emptyList(),
        extraBlocked: Set<NpcRouteCell> = emptySet(),
        faceOnArrival: Boolean = false,
        arrivalLookAt: Location? = null,
    ): Boolean = navigate(npc, destination, profile, via, extraBlocked, faceOnArrival, arrivalLookAt, 0)

    fun isNavigating(npc: NPC): Boolean = npc.id in active || npc.navigator.isNavigating

    fun consumeOutcome(npc: NPC): NpcRouteOutcome? = outcomes.consume(npc.id)

    fun stop(npc: NPC) {
        active.remove(npc.id)
        if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
    }

    fun abandon(npc: NPC) {
        val route = active.remove(npc.id)
        outcomes.reset(npc.id)
        if (route != null) stopOwnedNpcRoute(npc.navigator, route.strategy)
    }

    private fun navigate(
        npc: NPC,
        destination: Location,
        profile: NpcRouteProfile,
        via: List<Location>,
        extraBlocked: Set<NpcRouteCell>,
        faceOnArrival: Boolean,
        arrivalLookAt: Location?,
        recoveries: Int,
    ): Boolean {
        stop(npc)
        outcomes.reset(npc.id)
        val authoredLevel = (profile.floorY - profile.surfaceSearchRange)..(profile.floorY + profile.surfaceSearchRange)
        if (
            !npc.isSpawned ||
            npc.entity.world != destination.world ||
            (arrivalLookAt != null && arrivalLookAt.world != destination.world) ||
            destination.blockY !in authoredLevel ||
            via.any { it.world != destination.world || it.blockY !in authoredLevel }
        ) return false
        val world = destination.world
        val actual = npc.entity.location
        val footprint = npcRouteFootprint(npc.entity)
        val blocked = obstacleSource.blockedCells(world, npcRouteObstacleProfile(profile, footprint)) + extraBlocked
        val surfaceCache = mutableMapOf<NpcRouteCell, Double?>()
        val surfaceY: (NpcRouteCell) -> Double? = { cell ->
            if (surfaceCache.containsKey(cell)) {
                surfaceCache[cell]
            } else {
                resolveSurfaceY(world, profile, cell).also { surfaceCache[cell] = it }
            }
        }
        val footprintCache = mutableMapOf<NpcRouteCell, Boolean>()
        val footprintClear: (NpcRouteCell) -> Boolean = { cell ->
            if (footprintCache.containsKey(cell)) {
                footprintCache.getValue(cell)
            } else {
                val surface = surfaceY(cell)
                val point = surface?.let { Vector(cell.x + 0.5, it, cell.z + 0.5) }
                (point != null && isNpcRouteSegmentClear(world, profile, point, point, footprint, blocked)).also {
                    footprintCache[cell] = it
                }
            }
        }
        val starts = candidates(actual.blockX, actual.blockZ, profile, blocked, surfaceY, footprintClear)
        val currentCell = NpcRouteCell(actual.blockX, actual.blockZ)
        val canTraverse: (Vector, Vector) -> Boolean = { from, to ->
            isNpcRouteSegmentClear(world, profile, from, to, footprint, active[npc.id]?.blocked ?: blocked)
        }
        // Start admission is independent of endpoint snapping: it must preserve the actual body and
        // validate the whole connector, never recover a clear off-centre position by teleporting it.
        val walkingStart = if (starts.firstOrNull() == currentCell) null
            else npcRouteWalkingStart(actual.toVector(), profile, surfaceY, canTraverse)
        val start = walkingStart ?: starts.firstOrNull()
        if (start == null) {
            event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-safe-endpoint")
            return false
        }
        val path = mutableListOf(start)
        val legEnds = mutableListOf<Int>()
        val comfortableBody = footprint.copy(
            halfWidth = footprint.halfWidth + profile.obstacleClearance,
            halfDepth = footprint.halfDepth + profile.obstacleClearance,
        )
        val stepCosts = mutableMapOf<NpcRouteCell, Int>()
        val stepCost: (NpcRouteCell) -> Int = { cell ->
            stepCosts.getOrPut(cell) {
                val point = Vector(cell.x + 0.5, surfaceY(cell)!!, cell.z + 0.5)
                val comfortable = profile.obstacleClearance == 0.0 ||
                    isNpcRouteSegmentClear(world, profile, point, point, comfortableBody, blocked)
                // Clearance is a 25% preference, never a new wall in a narrow passage.
                profile.stepCost(cell) * if (comfortable) 4 else 5
            }
        }
        val traversalCache = mutableMapOf<Pair<NpcRouteCell, NpcRouteCell>, Boolean>()
        for (anchor in via + destination) {
            val goals = candidates(anchor.blockX, anchor.blockZ, profile, blocked, surfaceY, footprintClear)
            val exact = NpcRouteCell(anchor.blockX, anchor.blockZ)
            val walkable: (NpcRouteCell) -> Boolean = { it !in blocked && footprintClear(it) }
            val canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { from, to ->
                val fromY = surfaceY(from)
                val toY = surfaceY(to)
                traversalCache.getOrPut(from to to) {
                    fromY != null && toY != null && abs(fromY - toY) <= profile.maximumStepHeight + 1.0e-6 &&
                        isNpcRouteSegmentClear(world, profile,
                            Vector(from.x + 0.5, fromY, from.z + 0.5),
                            Vector(to.x + 0.5, toY, to.z + 0.5), footprint, blocked)
                }
            }
            val orderedGoals = exact.takeIf { it in goals }?.let { listOf(it) + goals.filterNot { goal -> goal == it } } ?: goals
            val segment = if (path.last() == exact) listOf(exact) else {
                findNpcGridPathToNearestCandidate(
                    path.last(),
                    orderedGoals,
                    profile,
                    canTraverse = canTraverse,
                    allowDiagonalConnections = true,
                    stepCost = stepCost,
                    minimumStepCost = if (profile.preferred.isEmpty()) 40 else 4,
                    isWalkable = walkable,
                )
            }
            if (segment == null) {
                event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-level-route")
                return false
            }
            path += segment.drop(1)
            legEnds += path.lastIndex
        }
        val surfaceYs = mutableMapOf<NpcRouteCell, Double>()
        path.forEach { cell ->
            val surface = surfaceY(cell) ?: run {
                event("PATH_UNAVAILABLE", profile, npc, destination, reason = "surface-disappeared")
                return false
            }
            surfaceYs[cell] = surface
        }
        val currentSurfaceY = surfaceY(currentCell)
        val currentYAllowed = currentSurfaceY?.let { isNpcRouteStartYAllowed(actual.y, it, profile) } == true
        if (walkingStart == null && (currentCell != start || !currentYAllowed)) {
            val recovered = Location(world, start.x + 0.5, surfaceYs.getValue(start), start.z + 0.5, actual.yaw, 0f)
            npc.entity.teleport(recovered)
            event("RECOVERED", profile, npc, destination, path.size, "off-floor-start", actual)
        }
        val gridPoints = path.map { Vector(it.x + 0.5, surfaceYs.getValue(it), it.z + 0.5) }.toMutableList()
        // Continue from the real position instead of walking back to the start-cell center on every leg.
        val routeStart = npc.entity.location.toVector()
        if (walkingStart != null) {
            gridPoints.add(0, routeStart)
            legEnds.indices.forEach { legEnds[it] = legEnds[it] + 1 }
        } else if (gridPoints.size > 1 && canTraverse(routeStart, gridPoints[1])) gridPoints[0] = routeStart
        val shortcutPoints = mutableListOf(gridPoints.first())
        var legStart = 0
        val curveBudget = minOf(profile.cornerSmoothingLead, profile.pathShortcutMaximumDeviation)
        val shortcutBudget = (profile.pathShortcutMaximumDeviation -
            minOf(curveBudget, profile.cornerSmoothingDistance / 4.0)).coerceAtLeast(0.0)
        for (legEnd in legEnds) {
            shortcutPoints += shortcutNpcRoutePoints(
                gridPoints.subList(legStart, legEnd + 1), shortcutBudget, canTraverse,
            ).drop(1)
            legStart = legEnd
        }
        val vectors = roundedNpcRoutePoints(
            shortcutPoints,
            profile.cornerSmoothingDistance,
            curveBudget,
            canTraverse,
        )
        npc.navigator.setTarget { params ->
            // Change only this route's cloned parameters; preserve the NPC's persisted defaults.
            configure(params, profile)
            params.range(maxOf(params.range(), npcRouteRequiredRange(actual.toVector(), vectors, profile.distanceMargin)))
            FixedLevelPathStrategy(
                npc = npc,
                world = world,
                points = vectors,
                destinationMargin = params.distanceMargin(),
                speedModifier = params.speedModifier(),
                maximumStepHeight = profile.maximumStepHeight,
                maximumTurnDegreesPerTick = profile.headingMaxTurnDegreesPerTick,
                finalFacing = destination.takeIf { faceOnArrival },
                arrivalLookAt = arrivalLookAt?.clone(),
                canTraverse = canTraverse,
            )
        }
        val route = ActiveNpcRoute(
            UUID.randomUUID(),
            npc.navigator.pathStrategy ?: return false,
            profile,
            destination.clone(),
            via.map(Location::clone),
            extraBlocked.toSet(),
            blocked,
            path,
            surfaceYs.toMap(),
            faceOnArrival,
            arrivalLookAt?.clone(),
            previousX = npc.entity.location.x,
            previousZ = npc.entity.location.z,
            recoveries = recoveries,
        )
        active[npc.id] = route
        event("STARTED", profile, npc, destination, path.size)
        monitor(npc.id, route.token)
        activate(npc.id, route.token)
        return true
    }

    private fun candidates(
        centerX: Int,
        centerZ: Int,
        profile: NpcRouteProfile,
        blocked: Set<NpcRouteCell>,
        surfaceY: (NpcRouteCell) -> Double?,
        footprintClear: (NpcRouteCell) -> Boolean = { surfaceY(it) != null },
    ): List<NpcRouteCell> =
        (-profile.snapRadius..profile.snapRadius)
            .flatMap { dx -> (-profile.snapRadius..profile.snapRadius).map { dz -> NpcRouteCell(centerX + dx, centerZ + dz) } }
            .filter { it !in blocked && profile.allows(it) && surfaceY(it) != null && footprintClear(it) }
            .sortedWith(compareBy({ (it.x - centerX) * (it.x - centerX) + (it.z - centerZ) * (it.z - centerZ) }, { it.x }, { it.z }))

    private fun configure(params: NavigatorParameters, profile: NpcRouteProfile) {
        params
            .distanceMargin(profile.distanceMargin)
            .pathDistanceMargin(profile.pathDistanceMargin)
            .speedModifier(profile.speedModifier)
            .pathfinderType(PathfinderType.CITIZENS)
            .stationaryTicks(Int.MAX_VALUE)
            .stuckAction(null)
            // The route owns one rotation session; navigator look-at would create a competing one.
            .lookAtFunction(null)
    }

    private fun activate(npcId: Int, token: UUID) {
        val route = active[npcId]?.takeIf { it.token == token } ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        if (npc.navigator.isNavigating && npc.navigator.pathStrategy !== route.strategy) { abandon(npc); return }
        if (!npc.navigator.isNavigating || npc.entity.world != route.destination.world) return
        // Citizens gates NPC movement on activatedTick; refresh it only for this live route heartbeat.
        NMS.activate(npc.entity)
        tasks.runLater(1L) { activate(npcId, token) }
    }

    private fun monitor(npcId: Int, token: UUID) {
        val route = active[npcId]?.takeIf { it.token == token } ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: run {
            active.remove(npcId, route)
            return
        }
        if (npc.navigator.isNavigating && npc.navigator.pathStrategy !== route.strategy) { abandon(npc); return }
        val actual = npc.entity.location
        if (actual.world != route.destination.world) {
            active.remove(npcId, route)
            npc.navigator.cancelNavigation()
            outcomes.record(npcId, NpcRouteOutcome(false, "ABORTED", "world-changed"))
            event("ABORTED", route.profile, npc, route.destination, route.cells.size, "world-changed", actual)
            return
        }
        val currentCell = NpcRouteCell(actual.blockX, actual.blockZ)
        val surfaceCache = mutableMapOf<NpcRouteCell, Double?>()
        val surfaceY: (NpcRouteCell) -> Double? = { cell ->
            if (surfaceCache.containsKey(cell)) {
                surfaceCache[cell]
            } else {
                resolveSurfaceY(actual.world, route.profile, cell).also { surfaceCache[cell] = it }
            }
        }
        val footprint = npcRouteFootprint(npc.entity)
        val footprintCache = mutableMapOf<NpcRouteCell, Boolean>()
        val footprintClear: (NpcRouteCell) -> Boolean = { cell ->
            if (footprintCache.containsKey(cell)) {
                footprintCache.getValue(cell)
            } else {
                val surface = surfaceY(cell)
                val point = surface?.let { Vector(cell.x + 0.5, it, cell.z + 0.5) }
                (point != null && isNpcRouteSegmentClear(actual.world, route.profile, point, point, footprint, route.blocked)).also {
                    footprintCache[cell] = it
                }
            }
        }
        val currentSurfaceY = surfaceY(currentCell)
        if (
            !route.profile.allows(currentCell) ||
            currentSurfaceY == null ||
            !isNpcRouteActualYAllowed(actual.y, currentSurfaceY, route.profile) ||
            !isNpcRouteSegmentClear(actual.world, route.profile, actual.toVector(), actual.toVector(), footprint, route.blocked)
        ) {
            npc.navigator.cancelNavigation()
            event("DEVIATED", route.profile, npc, route.destination, route.cells.size, "left-level-floor", actual)
            if (route.recoveries < 2) {
                tasks.runLater(1L) {
                    if (active[npcId] === route) {
                        if (!canResumeOwnedNpcRoute(npc.navigator, route.strategy)) { abandon(npc); return@runLater }
                        navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.faceOnArrival, route.arrivalLookAt, route.recoveries + 1)
                    }
                }
            } else {
                active.remove(npcId, route)
                outcomes.record(npcId, NpcRouteOutcome(false, "DEVIATED", "left-level-floor"))
            }
            return
        }
        route.obstaclePolls++
        if (route.obstaclePolls >= route.profile.obstacleRefreshPolls) {
            route.blocked = obstacleSource.blockedCells(actual.world, npcRouteObstacleProfile(route.profile, footprint)) + route.extraBlocked
            route.obstaclePolls = 0
        }
        val routeSurfaceInvalid = route.cells.any { cell ->
            cell in route.blocked ||
                !route.profile.allows(cell) ||
                surfaceY(cell) == null ||
                (route.obstaclePolls == 0 && !footprintClear(cell))
        }
        val routeSurfaceChanged = route.surfaceYs.any { (cell, plannedY) ->
            val currentY = surfaceY(cell)
            currentY == null || abs(currentY - plannedY) > 1.0e-6
        }
        val routeEdgeInvalid = route.cells.zipWithNext().any { (from, to) ->
            val fromY = surfaceY(from)
            val toY = surfaceY(to)
            fromY == null || toY == null || abs(fromY - toY) > route.profile.maximumStepHeight + 1.0e-6
        }
        if (routeSurfaceInvalid || routeSurfaceChanged || routeEdgeInvalid) {
            npc.navigator.cancelNavigation()
            event("REPLANNING", route.profile, npc, route.destination, route.cells.size, "terrain-changed", actual)
            tasks.runLater(1L) {
                if (active[npcId] === route) {
                    if (!canResumeOwnedNpcRoute(npc.navigator, route.strategy)) { abandon(npc); return@runLater }
                    navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.faceOnArrival, route.arrivalLookAt, route.recoveries)
                }
            }
            return
        }
        if (!npc.navigator.isNavigating) {
            active.remove(npcId, route)
            val reached = isNpcRouteResolvedEndpointReached(
                actual.x,
                actual.z,
                route.cells.last(),
                route.profile.distanceMargin + 1.0e-3,
            )
            val phase = if (reached) "FINISHED" else "ABORTED"
            val reason = if (reached) null else if (npc.navigator.isPaused) "navigator-paused" else "navigation-cancelled"
            outcomes.record(npcId, NpcRouteOutcome(reached, phase, reason))
            event(phase, route.profile, npc, route.destination, route.cells.size, reason)
            return
        }
        val movement = hypot(actual.x - route.previousX, actual.z - route.previousZ)
        val turningAtDestination = (route.faceOnArrival || route.arrivalLookAt != null) && isNpcRouteResolvedEndpointReached(
            actual.x, actual.z, route.cells.last(), route.profile.distanceMargin + 1.0e-3,
        )
        if (movement > 0.025 || turningAtDestination) route.stalledPolls = 0 else route.stalledPolls++
        route.previousX = actual.x
        route.previousZ = actual.z
        if (route.stalledPolls >= route.profile.stallPolls) {
            active.remove(npcId, route)
            npc.navigator.cancelNavigation()
            outcomes.record(npcId, NpcRouteOutcome(false, "STALLED", "no-progress"))
            event("STALLED", route.profile, npc, route.destination, route.cells.size, "no-progress", actual)
            return
        }
        tasks.runLater(route.profile.pollTicks) { monitor(npcId, token) }
    }

    private fun event(
        phase: String,
        profile: NpcRouteProfile,
        npc: NPC,
        target: Location,
        cells: Int = 0,
        reason: String? = null,
        actual: Location = npc.entity.location,
    ) {
        onEvent(NpcRouteEvent(phase, profile.id, npc.id, actual.clone(), target.clone(), cells, reason))
    }

    override fun close() {
        tasks.close()
        active.keys.toList().forEach { id ->
            runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull()?.takeIf { it.isSpawned }?.let(::abandon)
        }
        active.clear()
        outcomes.clear()
    }
}
