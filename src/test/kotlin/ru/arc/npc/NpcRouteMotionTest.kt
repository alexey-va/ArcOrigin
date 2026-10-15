package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

class NpcRouteMotionTest : FreeSpec({
    "rounds right and left right-angle corners without moving endpoints" {
        val routes = listOf(
            listOf(Vector(0.5, 72.0, 0.5), Vector(3.5, 72.0, 0.5), Vector(3.5, 72.0, 3.5)),
            listOf(Vector(0.5, 72.0, 0.5), Vector(3.5, 72.0, 0.5), Vector(3.5, 72.0, -2.5)),
        )

        routes.forEach { route ->
            val rounded = roundedNpcRoutePoints(route, 0.8, 0.3) { _, _ -> true }

            rounded.first() shouldBe route.first()
            rounded.last() shouldBe route.last()
            rounded.size shouldBe 11
            routePolylineDeviation(rounded, route).maxOf { it } shouldBeLessThan 0.301
        }
    }

    "rounds diagonal fallback joins within the same departure bound" {
        for (endX in listOf(0.0, 4.0)) {
            val route = listOf(Vector(0.0, 72.0, 0.0), Vector(2.0, 72.0, 0.0), Vector(endX, 72.0, 2.0))
            val rounded = roundedNpcRoutePoints(route, 0.75, 0.3) { _, _ -> true }

            rounded.first() shouldBe route.first()
            rounded.last() shouldBe route.last()
            rounded.size shouldBe 11
            routePolylineDeviation(rounded, route).maxOf { it } shouldBeLessThan 0.1876
        }
    }

    "keeps reversals and non-flat corners sharp" {
        val reversal = listOf(Vector(0.0, 72.0, 0.0), Vector(1.0, 72.0, 0.0), Vector(0.0, 72.0, 0.0))
        val vertical = listOf(Vector(0.0, 72.0, 0.0), Vector(1.0, 72.5, 0.0), Vector(1.0, 72.5, 1.0))

        roundedNpcRoutePoints(reversal, 0.8, 0.3) { _, _ -> true } shouldBe reversal
        roundedNpcRoutePoints(vertical, 0.8, 0.3) { _, _ -> true } shouldBe vertical
    }

    "unsafe curve connectors fall back to the original corner" {
        val route = listOf(Vector(0.5, 72.0, 0.5), Vector(3.5, 72.0, 0.5), Vector(3.5, 72.0, 3.5))
        val checked = mutableListOf<Pair<Vector, Vector>>()

        val rounded = roundedNpcRoutePoints(route, 0.8, 0.3) { from, to ->
            checked += (from.clone() to to.clone())
            kotlin.math.abs(from.x - to.x) <= 1.0e-9 || kotlin.math.abs(from.z - to.z) <= 1.0e-9
        }

        checked.shouldNotBeEmpty()
        rounded shouldBe route
    }

    "follower sustains full travel speed across straight samples and clamps at endpoint" {
        val points = (0..12).map { Vector(it * 0.2, 72.0, 0.0) }
        val motion = NpcRouteMotion(points)
        var actual = points.first().clone()

        repeat(8) {
            val target = motion.target(actual, 0.144)
            val velocity = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, 0.144)
            abs(hypot(velocity.x, velocity.z) - 0.144) shouldBeLessThan 1.0e-9
            actual.add(velocity)
        }

        repeat(40) {
            val target = motion.target(actual, 0.144)
            val velocity = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, 0.144)
            actual.add(velocity)
            if (motion.index == points.lastIndex && actual.distance(points.last()) < 1.0e-6) return@repeat
        }
        motion.index shouldBe points.lastIndex
        actual.distance(points.last()) shouldBeLessThan 0.144 + 1.0e-6
    }

    "sharp corners and U-turns advance only after physical arrival at both common speeds" {
        val corner = listOf(Vector(0.0, 72.0, 0.0), Vector(1.0, 72.0, 0.0), Vector(1.0, 72.0, 1.0))
        val route = listOf(Vector(0.0, 72.0, 0.0), Vector(1.0, 72.0, 0.0), Vector(0.0, 72.0, 0.0))
        for (speed in listOf(0.144, 0.084)) {
            val sharpMotion = NpcRouteMotion(corner)
            val sharpPositions = simulateRoute(corner, sharpMotion, speed)
            val sharpCornerTick = sharpPositions.indexOfFirst { it.distance(corner[1]) <= 1.0e-6 }
            (sharpCornerTick > 0) shouldBe true
            (sharpPositions[sharpCornerTick - 1].x < 1.0) shouldBe true
            (sharpPositions[sharpCornerTick + 1].z > 0.0) shouldBe true

            val motion = NpcRouteMotion(route)
            val positions = simulateRoute(route, motion, speed)
            val turnTick = positions.indexOfFirst { it.distance(route[1]) <= 1.0e-6 }
            (turnTick > 0) shouldBe true
            (positions[turnTick - 1].x > 0.0) shouldBe true
            (positions[turnTick + 1].x < 1.0) shouldBe true
            motion.index shouldBe route.lastIndex
            positions.last().distance(route.last()) shouldBeLessThan 1.0e-6
        }
    }

    "smoothing disabled still crosses a corner without stalling and repeated points are harmless" {
        val raw = listOf(
            Vector(0.5, 72.0, 0.5), Vector(0.5, 72.0, 0.5),
            Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 1.5),
        )
        val sharp = roundedNpcRoutePoints(raw, 0.0, 0.3) { _, _ -> true }
        sharp shouldBe raw
        for (speed in listOf(0.144, 0.084)) {
            val motion = NpcRouteMotion(sharp)
            val positions = simulateRoute(sharp, motion, speed)
            motion.index shouldBe sharp.lastIndex
            positions.last().distance(sharp.last()) shouldBeLessThan 1.0e-6
            positions.any { it.distance(sharp[2]) <= 1.0e-6 } shouldBe true
        }
    }

    "lookahead cannot skip a sharp turn beyond a short straight segment" {
        val points = listOf(Vector(0.0, 72.0, 0.0), Vector(0.04, 72.0, 0.0),
            Vector(0.08, 72.0, 0.0), Vector(0.0, 72.0, 0.0))
        val motion = NpcRouteMotion(points)
        motion.target(points.first(), 0.144) shouldBe points[2]
        val positions = simulateRoute(points, NpcRouteMotion(points), 0.144)
        positions.any { it.distance(points[2]) < 1.0e-6 } shouldBe true
        positions.last().distance(points.last()) shouldBeLessThan 1.0e-6
    }

    "collinear unit-grid samples compress before rounding and reduce sharp turn steps" {
        val gridRoute = listOf(
            Vector(0.5, 72.0, 0.5), Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 1.5),
            Vector(2.5, 72.0, 1.5), Vector(2.5, 72.0, 2.5), Vector(3.5, 72.0, 2.5),
        )
        val rounded = roundedNpcRoutePoints(gridRoute, 0.75, 0.3) { _, _ -> true }
        rounded.first() shouldBe gridRoute.first()
        rounded.last() shouldBe gridRoute.last()
        (rounded.size > gridRoute.size) shouldBe true
        distanceToPolyline(rounded[rounded.size / 2], gridRoute) shouldBeLessThan 0.301

        for (speed in listOf(0.144, 0.084)) {
            val sharp = simulateMotion(gridRoute, speed)
            val smooth = simulateMotion(rounded, speed)
            (sharp.largestTurnDegrees > 89.0) shouldBe true
            // The tightest unit-grid quadratic corners peak at about 23 degrees at 0.144 blocks/tick.
            smooth.largestTurnDegrees shouldBeLessThan 25.0
            smooth.positions.maxOf { distanceToPolyline(it, gridRoute) } shouldBeLessThan 0.301
            smooth.endpointDistance shouldBeLessThan speed + 1.0e-5
        }
    }
})

private data class RouteSimulation(
    val positions: List<Vector>,
    val largestTurnDegrees: Double,
    val endpointDistance: Double,
)

private fun simulateMotion(points: List<Vector>, speed: Double): RouteSimulation {
    val motion = NpcRouteMotion(points)
    val positions = mutableListOf(points.first().clone())
    var actual = points.first().clone()
    var previousVelocity: Vector? = null
    var largestTurnDegrees = 0.0
    for (tick in 0 until 600) {
        val target = motion.target(actual, speed)
        val velocity = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, speed)
        if (velocity.lengthSquared() <= 1.0e-12) break
        previousVelocity?.let { previous ->
            val dot = ((previous.x * velocity.x + previous.z * velocity.z) /
                (hypot(previous.x, previous.z) * hypot(velocity.x, velocity.z))).coerceIn(-1.0, 1.0)
            largestTurnDegrees = maxOf(largestTurnDegrees, Math.toDegrees(acos(dot)))
        }
        previousVelocity = velocity.clone()
        actual.add(velocity)
        positions += actual.clone()
        if (motion.index == points.lastIndex && actual.distance(points.last()) <= 1.0e-6) break
    }
    return RouteSimulation(positions, largestTurnDegrees, actual.distance(points.last()))
}

private fun simulateRoute(points: List<Vector>, motion: NpcRouteMotion, speed: Double): List<Vector> {
    val positions = mutableListOf(points.first().clone())
    var actual = points.first().clone()
    for (tick in 0 until 600) {
        val target = motion.target(actual, speed)
        val velocity = npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, speed)
        if (velocity.lengthSquared() <= 1.0e-12) break
        actual.add(velocity)
        positions += actual.clone()
        if (motion.index == points.lastIndex && actual.distance(points.last()) <= 1.0e-6) break
    }
    return positions
}

private fun routePolylineDeviation(points: List<Vector>, original: List<Vector>): List<Double> =
    points.map { distanceToPolyline(it, original) }

private fun distanceToPolyline(point: Vector, line: List<Vector>): Double {
    if (line.size < 2) return hypot(point.x - line.first().x, point.z - line.first().z)
    return (0 until line.lastIndex).minOf { index ->
        val from = line[index]
        val to = line[index + 1]
        val dx = to.x - from.x
        val dz = to.z - from.z
        val lengthSquared = dx * dx + dz * dz
        val projection = if (lengthSquared <= 1.0e-12) 0.0 else
            (((point.x - from.x) * dx + (point.z - from.z) * dz) / lengthSquared).coerceIn(0.0, 1.0)
        hypot(point.x - (from.x + dx * projection), point.z - (from.z + dz * projection))
    }
}
