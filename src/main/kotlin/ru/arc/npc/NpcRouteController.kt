package ru.arc.npc

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.AbstractPathStrategy
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.ai.TargetType
import net.citizensnpcs.api.ai.event.CancelReason
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
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
    val maximumStepHeight: Double = 0.125,
    val maximumSurfaceDrop: Double = 0.0,
    val surfaceSearchRange: Int = 0,
    val allowedSupportMaterials: Set<Material> = emptySet(),
) {
    init {
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

internal fun npcRouteFootprint(entity: Entity, margin: Double = 0.0): NpcRouteFootprint {
    val originY = entity.location.y
    val box = entity.boundingBox
    val bodyHalfExtent = maxOf(box.maxX - box.minX, box.maxZ - box.minZ) / 2.0
    val halfExtent = bodyHalfExtent + if (bodyHalfExtent > 0.5) margin else 0.0
    return NpcRouteFootprint(
        halfWidth = halfExtent,
        halfDepth = halfExtent,
        minYOffset = box.minY - originY,
        maxYOffset = box.maxY - originY,
    )
}

private const val NPC_ROUTE_COLLISION_EPSILON = 1.0e-6

private fun npcRouteRangesOverlap(minA: Double, maxA: Double, minB: Double, maxB: Double): Boolean =
    minA < maxB - NPC_ROUTE_COLLISION_EPSILON && maxA > minB + NPC_ROUTE_COLLISION_EPSILON

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
): Boolean {
    if (!surfaceY.isFinite()) return false
    val minX = cell.x + 0.5 - footprint.halfWidth
    val maxX = cell.x + 0.5 + footprint.halfWidth
    val minZ = cell.z + 0.5 - footprint.halfDepth
    val maxZ = cell.z + 0.5 + footprint.halfDepth
    val minY = surfaceY + footprint.minYOffset
    val maxY = surfaceY + footprint.maxYOffset + (profile?.maximumStepHeight ?: 0.0)
    if (!minY.isFinite() || !maxY.isFinite() || maxY <= minY + NPC_ROUTE_COLLISION_EPSILON) return false

    val minBlockX = floor(minX + NPC_ROUTE_COLLISION_EPSILON).toInt()
    val maxBlockX = ceil(maxX - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1
    val minBlockY = floor(minY + NPC_ROUTE_COLLISION_EPSILON).toInt()
    val maxBlockY = ceil(maxY - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1
    val minBlockZ = floor(minZ + NPC_ROUTE_COLLISION_EPSILON).toInt()
    val maxBlockZ = ceil(maxZ - NPC_ROUTE_COLLISION_EPSILON).toInt() - 1

    val collisions = mutableListOf<BoundingBox>()
    var standingY = surfaceY
    for (x in minBlockX..maxBlockX) {
        for (y in minBlockY..maxBlockY) {
            for (z in minBlockZ..maxBlockZ) {
                val block = world.getBlockAt(x, y, z)
                if (block.isLiquid) return false
                for (box in block.collisionShape.boundingBoxes) {
                    val boxMinX = x + box.minX
                    val boxMaxX = x + box.maxX
                    val boxMinY = y + box.minY
                    val boxMaxY = y + box.maxY
                    val boxMinZ = z + box.minZ
                    val boxMaxZ = z + box.maxZ
                    if (
                        npcRouteRangesOverlap(minX, maxX, boxMinX, boxMaxX) &&
                        npcRouteRangesOverlap(minZ, maxZ, boxMinZ, boxMaxZ)
                    ) {
                        collisions += BoundingBox(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ)
                        val allowedStep = profile != null &&
                            (block.type in profile.allowedSupportMaterials ||
                                isNpcRouteFloorCovering(block.type, box.maxY, profile.maximumStepHeight)) &&
                            boxMaxY <= surfaceY + profile.maximumStepHeight + NPC_ROUTE_COLLISION_EPSILON
                        if (allowedStep) standingY = maxOf(standingY, boxMaxY)
                    }
                }
            }
        }
    }
    return collisions.none { box ->
        npcRouteRangesOverlap(standingY + footprint.minYOffset, standingY + footprint.maxYOffset, box.minY, box.maxY)
    }
}

internal fun smoothedNpcRouteTarget(
    points: List<Vector>,
    index: Int,
    actualX: Double,
    actualZ: Double,
    pathDistanceMargin: Double,
    smoothingDistance: Double,
    smoothingLead: Double,
): Vector {
    val current = points[index]
    if (index == 0 || index >= points.lastIndex || smoothingDistance <= pathDistanceMargin || smoothingLead <= 0.0) {
        return current
    }
    val previous = points[index - 1]
    val next = points[index + 1]
    val incomingX = current.x - previous.x
    val incomingZ = current.z - previous.z
    val outgoingX = next.x - current.x
    val outgoingZ = next.z - current.z
    if (abs(incomingX * outgoingX + incomingZ * outgoingZ) > 1.0e-6) return current
    val remaining = hypot(current.x - actualX, current.z - actualZ)
    if (remaining >= smoothingDistance) return current
    val progress = ((smoothingDistance - remaining) / (smoothingDistance - pathDistanceMargin)).coerceIn(0.0, 1.0)
    val outgoingLength = hypot(outgoingX, outgoingZ)
    if (outgoingLength <= 1.0e-6) return current
    val lead = minOf(smoothingLead, outgoingLength, (pathDistanceMargin * 0.9).coerceAtLeast(0.0)) * progress
    return Vector(current.x + outgoingX / outgoingLength * lead, current.y, current.z + outgoingZ / outgoingLength * lead)
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

/** Bounded four-way A*: no diagonal corner cutting; callers may constrain edge height changes. */
internal fun findNpcGridPath(
    start: NpcRouteCell,
    goals: List<NpcRouteCell>,
    profile: NpcRouteProfile,
    canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { _, _ -> true },
    isWalkable: (NpcRouteCell) -> Boolean,
): List<NpcRouteCell>? {
    if (!profile.allows(start) || !isWalkable(start)) return null
    val allowedGoals = goals.filter { profile.allows(it) && isWalkable(it) }.toSet()
    if (allowedGoals.isEmpty()) return null
    if (start in allowedGoals) return listOf(start)
    fun heuristic(cell: NpcRouteCell): Int = allowedGoals.minOf { abs(cell.x - it.x) + abs(cell.z - it.z) }
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
        val neighbors = arrayOf(
            NpcRouteCell(current.cell.x + 1, current.cell.z),
            NpcRouteCell(current.cell.x - 1, current.cell.z),
            NpcRouteCell(current.cell.x, current.cell.z + 1),
            NpcRouteCell(current.cell.x, current.cell.z - 1),
        )
        for (next in neighbors) {
            if (!profile.allows(next) || next in closed || !isWalkable(next) || !canTraverse(current.cell, next)) continue
            val nextCost = current.cost + profile.stepCost(next)
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
    isWalkable: (NpcRouteCell) -> Boolean,
): List<NpcRouteCell>? =
    goals.asSequence()
        .filter { it != start }
        .mapNotNull { goal -> findNpcGridPath(start, listOf(goal), profile, canTraverse, isWalkable) }
        .firstOrNull()

private data class ActiveNpcRoute(
    val token: UUID,
    val profile: NpcRouteProfile,
    val destination: Location,
    val via: List<Location>,
    val extraBlocked: Set<NpcRouteCell>,
    var blocked: Set<NpcRouteCell>,
    val cells: List<NpcRouteCell>,
    val surfaceYs: Map<NpcRouteCell, Double>,
    var obstaclePolls: Int = 0,
    var stalledPolls: Int = 0,
    var previousX: Double,
    var previousZ: Double,
    var headingIndex: Int,
    var headingYaw: Float,
    val recoveries: Int = 0,
)

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
    private val pathDistanceMargin: Double,
    private val destinationMargin: Double,
    private val speedModifier: Float,
    private val cornerSmoothingDistance: Double,
    private val cornerSmoothingLead: Double,
    private val maximumStepHeight: Double,
) : AbstractPathStrategy(TargetType.LOCATION) {
    private var index = 0
    private val stepHeight = (npc.entity as? LivingEntity)?.getAttribute(Attribute.STEP_HEIGHT)
    private val originalStepHeight = stepHeight?.baseValue
    private var restoredStepHeight = false

    init {
        stepHeight?.baseValue = maximumStepHeight
    }

    override fun getCurrentDestination(): Location = points[index.coerceAtMost(points.lastIndex)].toLocation(world)

    override fun getPath(): Iterable<Vector> = points.drop(index)

    override fun getTargetAsLocation(): Location = points.last().toLocation(world)

    override fun stop() {
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
        while (
            index < points.lastIndex &&
            horizontalDistanceSquared(actual, points[index]) <= pathDistanceMargin * pathDistanceMargin
        ) {
            index++
        }
        val routePoint = points[index]
        val distanceSquared = horizontalDistanceSquared(actual, routePoint)
        if (index == points.lastIndex && distanceSquared <= destinationMargin * destinationMargin) {
            stop()
            return true
        }
        val target = smoothedNpcRouteTarget(
            points,
            index,
            actual.x,
            actual.z,
            pathDistanceMargin,
            cornerSmoothingDistance,
            cornerSmoothingLead,
        )
        val horizontal = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, 0.2 * speedModifier)
        horizontal.y = entity.velocity.y.coerceAtMost(0.0)
        entity.velocity = horizontal
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
    ): Boolean = navigate(npc, destination, profile, via, extraBlocked, 0)

    fun isNavigating(npc: NPC): Boolean = npc.id in active || npc.navigator.isNavigating

    fun consumeOutcome(npc: NPC): NpcRouteOutcome? = outcomes.consume(npc.id)

    fun stop(npc: NPC) {
        active.remove(npc.id)
        if (npc.navigator.isNavigating) npc.navigator.cancelNavigation()
    }

    private fun navigate(
        npc: NPC,
        destination: Location,
        profile: NpcRouteProfile,
        via: List<Location>,
        extraBlocked: Set<NpcRouteCell>,
        recoveries: Int,
    ): Boolean {
        stop(npc)
        outcomes.reset(npc.id)
        val authoredLevel = (profile.floorY - profile.surfaceSearchRange)..(profile.floorY + profile.surfaceSearchRange)
        if (
            !npc.isSpawned ||
            npc.entity.world != destination.world ||
            destination.blockY !in authoredLevel ||
            via.any { it.world != destination.world || it.blockY !in authoredLevel }
        ) return false
        val world = destination.world
        val blocked = obstacleSource.blockedCells(world, profile) + extraBlocked
        val actual = npc.entity.location
        val surfaceCache = mutableMapOf<NpcRouteCell, Double?>()
        val surfaceY: (NpcRouteCell) -> Double? = { cell ->
            if (surfaceCache.containsKey(cell)) {
                surfaceCache[cell]
            } else {
                resolveSurfaceY(world, profile, cell).also { surfaceCache[cell] = it }
            }
        }
        val footprint = npcRouteFootprint(npc.entity, maxOf(profile.pathDistanceMargin, profile.distanceMargin))
        val footprintCache = mutableMapOf<NpcRouteCell, Boolean>()
        val footprintClear: (NpcRouteCell) -> Boolean = { cell ->
            if (footprintCache.containsKey(cell)) {
                footprintCache.getValue(cell)
            } else {
                val surface = surfaceY(cell)
                (surface != null && isNpcRouteFootprintClear(world, cell, surface, footprint, profile)).also {
                    footprintCache[cell] = it
                }
            }
        }
        val starts = candidates(actual.blockX, actual.blockZ, profile, blocked, surfaceY, footprintClear)
        val start = starts.firstOrNull()
        if (start == null) {
            event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-safe-endpoint")
            return false
        }
        val path = mutableListOf(start)
        for (anchor in via + destination) {
            val goals = candidates(anchor.blockX, anchor.blockZ, profile, blocked, surfaceY, footprintClear)
            val exact = NpcRouteCell(anchor.blockX, anchor.blockZ)
            val walkable: (NpcRouteCell) -> Boolean = { it !in blocked && footprintClear(it) }
            val canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { from, to ->
                val fromY = surfaceY(from)
                val toY = surfaceY(to)
                fromY != null && toY != null && abs(fromY - toY) <= profile.maximumStepHeight + 1.0e-6
            }
            val orderedGoals = exact.takeIf { it in goals }?.let { listOf(it) + goals.filterNot { goal -> goal == it } } ?: goals
            val segment = if (path.last() == exact) listOf(exact) else {
                findNpcGridPathToNearestCandidate(
                    path.last(),
                    orderedGoals,
                    profile,
                    canTraverse = canTraverse,
                    isWalkable = walkable,
                )
            }
            if (segment == null) {
                event("PATH_UNAVAILABLE", profile, npc, destination, reason = "no-level-route")
                return false
            }
            path += segment.drop(1)
        }
        val surfaceYs = mutableMapOf<NpcRouteCell, Double>()
        path.forEach { cell ->
            val surface = surfaceY(cell) ?: run {
                event("PATH_UNAVAILABLE", profile, npc, destination, reason = "surface-disappeared")
                return false
            }
            surfaceYs[cell] = surface
        }
        val currentCell = NpcRouteCell(actual.blockX, actual.blockZ)
        val currentSurfaceY = surfaceY(currentCell)
        val currentYAllowed = currentSurfaceY?.let { surface ->
            if (profile.surfaceSearchRange == 0 && profile.allowedSupportMaterials.isEmpty()) {
                abs(actual.y - surface) <= 0.25
            } else {
                isNpcRouteActualYAllowed(actual.y, surface, profile)
            }
        } == true
        if (
            currentCell != start ||
            !currentYAllowed
        ) {
            val recovered = Location(world, start.x + 0.5, surfaceYs.getValue(start), start.z + 0.5, actual.yaw, 0f)
            npc.entity.teleport(recovered)
            event("RECOVERED", profile, npc, destination, path.size, "off-floor-start", actual)
        }
        configure(npc, profile)
        val vectors = path.map { Vector(it.x + 0.5, surfaceYs.getValue(it), it.z + 0.5) }
        val initialHeadingYaw = path.drop(1).firstOrNull()?.let { cell ->
            npcRouteYaw(actual.x, actual.z, cell.x + 0.5, cell.z + 0.5)
        } ?: actual.yaw
        npc.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(initialHeadingYaw, 0f)
        npc.entity.setRotation(initialHeadingYaw, 0f)
        npc.navigator.setTarget { params ->
            // Change only this route's cloned parameters; preserve the NPC's persisted defaults.
            params.range(maxOf(params.range(), npcRouteRequiredRange(actual.toVector(), vectors, profile.distanceMargin)))
            FixedLevelPathStrategy(
                npc = npc,
                world = world,
                points = vectors,
                pathDistanceMargin = params.pathDistanceMargin(),
                destinationMargin = params.distanceMargin(),
                speedModifier = params.speedModifier(),
                cornerSmoothingDistance = if (footprint.halfWidth > 0.5) 0.0 else profile.cornerSmoothingDistance,
                cornerSmoothingLead = profile.cornerSmoothingLead,
                maximumStepHeight = profile.maximumStepHeight,
            )
        }
        val route = ActiveNpcRoute(
            UUID.randomUUID(),
            profile,
            destination.clone(),
            via.map(Location::clone),
            extraBlocked.toSet(),
            blocked,
            path,
            surfaceYs.toMap(),
            previousX = npc.entity.location.x,
            previousZ = npc.entity.location.z,
            headingIndex = 0,
            headingYaw = initialHeadingYaw,
            recoveries = recoveries,
        )
        active[npc.id] = route
        event("STARTED", profile, npc, destination, path.size)
        monitor(npc.id, route.token)
        monitorHeading(npc.id, route.token)
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

    private fun configure(npc: NPC, profile: NpcRouteProfile) {
        npc.navigator.localParameters
            .distanceMargin(profile.distanceMargin)
            .pathDistanceMargin(profile.pathDistanceMargin)
            .speedModifier(profile.speedModifier)
            .pathfinderType(PathfinderType.CITIZENS)
            .stationaryTicks(Int.MAX_VALUE)
            .stuckAction(null)
            .lookAtFunction { current ->
                val entity = current.npc.entity
                val eye = (entity as? LivingEntity)?.eyeLocation ?: entity.location.clone().add(0.0, 1.6, 0.0)
                val heading = active[current.npc.id]?.headingYaw ?: entity.yaw
                val radians = Math.toRadians(heading.toDouble())
                eye.clone().add(-kotlin.math.sin(radians) * 3.0, 0.0, kotlin.math.cos(radians) * 3.0)
            }
    }

    private fun monitorHeading(npcId: Int, token: UUID) {
        val route = active[npcId]?.takeIf { it.token == token } ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: return
        if (!npc.navigator.isNavigating || npc.entity.world != route.destination.world) return
        // Citizens gates NPC movement on activatedTick; refresh it only for this live route heartbeat.
        NMS.activate(npc.entity)
        val actual = npc.entity.location
        val searchEnd = (route.headingIndex + route.profile.headingLookAheadCells + 2).coerceAtMost(route.cells.lastIndex)
        route.headingIndex =
            (route.headingIndex..searchEnd).minBy { index ->
                val cell = route.cells[index]
                val dx = actual.x - (cell.x + 0.5)
                val dz = actual.z - (cell.z + 0.5)
                dx * dx + dz * dz
            }
        val targetIndex = (route.headingIndex + route.profile.headingLookAheadCells).coerceAtMost(route.cells.lastIndex)
        val target = route.cells[targetIndex]
        val dx = target.x + 0.5 - actual.x
        val dz = target.z + 0.5 - actual.z
        if (dx * dx + dz * dz > 0.0025) {
            val desiredYaw = npcRouteYaw(actual.x, actual.z, target.x + 0.5, target.z + 0.5)
            route.headingYaw = turnNpcYawToward(
                route.headingYaw,
                desiredYaw,
                route.profile.headingMaxTurnDegreesPerTick * route.profile.headingUpdateTicks,
            )
        }
        npc.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(route.headingYaw, 0f)
        npc.entity.setRotation(route.headingYaw, 0f)
        tasks.runLater(route.profile.headingUpdateTicks) { monitorHeading(npcId, token) }
    }

    private fun monitor(npcId: Int, token: UUID) {
        val route = active[npcId]?.takeIf { it.token == token } ?: return
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull()?.takeIf { it.isSpawned } ?: run {
            active.remove(npcId, route)
            return
        }
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
        val footprint = npcRouteFootprint(npc.entity, maxOf(route.profile.pathDistanceMargin, route.profile.distanceMargin))
        val footprintCache = mutableMapOf<NpcRouteCell, Boolean>()
        val footprintClear: (NpcRouteCell) -> Boolean = { cell ->
            if (footprintCache.containsKey(cell)) {
                footprintCache.getValue(cell)
            } else {
                val surface = surfaceY(cell)
                (surface != null && isNpcRouteFootprintClear(actual.world, cell, surface, footprint, route.profile)).also {
                    footprintCache[cell] = it
                }
            }
        }
        val currentSurfaceY = surfaceY(currentCell)
        if (
            !route.profile.allows(currentCell) ||
            currentSurfaceY == null ||
            !isNpcRouteActualYAllowed(actual.y, currentSurfaceY, route.profile) ||
            !footprintClear(currentCell)
        ) {
            npc.navigator.cancelNavigation()
            event("DEVIATED", route.profile, npc, route.destination, route.cells.size, "left-level-floor", actual)
            if (route.recoveries < 2) {
                tasks.runLater(1L) {
                    if (active[npcId] === route) {
                        navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.recoveries + 1)
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
            route.blocked = obstacleSource.blockedCells(actual.world, route.profile) + route.extraBlocked
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
                    navigate(npc, route.destination, route.profile, route.via, route.extraBlocked, route.recoveries)
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
        if (movement > 0.025) route.stalledPolls = 0 else route.stalledPolls++
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
            runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull()?.takeIf { it.isSpawned }?.let(::stop)
        }
        active.clear()
        outcomes.clear()
    }
}
