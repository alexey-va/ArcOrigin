package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

class NpcRouteControllerTest : FreeSpec({
    "heading turns through the shortest wrapped angle" {
        turnNpcYawToward(170f, -170f, 5f) shouldBe 175f
        turnNpcYawToward(-170f, 170f, 5f) shouldBe -175f
        turnNpcYawToward(10f, 14f, 5f) shouldBe 14f
    }

    "route yaw is horizontal and follows Minecraft axes" {
        npcRouteYaw(0.0, 0.0, 0.0, 1.0) shouldBe 0f
        npcRouteYaw(0.0, 0.0, 1.0, 0.0) shouldBe -90f
    }

    "Citizens range covers long routes from the actual start" {
        val start = Vector(0.0, 0.0, 0.0)
        val destination = Vector(30.0, 0.0, 0.0)

        val range = npcRouteRequiredRange(start, listOf(start, destination), 0.35)

        range shouldBe 31.35f
        (range > 19f) shouldBe true
    }

    "Citizens range covers the furthest detour point in three dimensions" {
        val start = Vector(0.0, 0.0, 0.0)
        val destination = Vector(10.0, 0.0, 0.0)
        val detour = Vector(10.0, 12.0, 0.0)

        npcRouteRequiredRange(start, listOf(start, detour, destination), 0.25) shouldBe 13.25f
    }

    "direct route movement stays horizontal and cannot overshoot a cell" {
        npcRouteHorizontalVelocity(0.0, 0.0, 1.0, 0.0, 0.2).let { velocity ->
            velocity.x shouldBe 0.2
            velocity.y shouldBe 0.0
            velocity.z shouldBe 0.0
        }
        npcRouteHorizontalVelocity(0.0, 0.0, 0.05, 0.0, 0.2).x shouldBe 0.05
    }

    "corner smoothing starts late and only leads into the next safe cell" {
        val points = listOf(Vector(0.5, 72.0, 0.5), Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 1.5))

        smoothedNpcRouteTarget(points, 1, 0.6, 0.5, 0.35, 0.75, 0.30) shouldBe points[1]
        smoothedNpcRouteTarget(points, 1, 1.15, 0.5, 0.35, 0.75, 0.30) shouldBe Vector(1.5, 72.0, 0.8)
    }

    "tight smoothing lead cannot keep a route point outside its advancement margin" {
        val points = listOf(Vector(0.5, 72.0, 0.5), Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 1.5))
        var actualX = 1.15
        var actualZ = 0.5
        var index = 1

        repeat(100) {
            while (index < points.lastIndex && kotlin.math.hypot(actualX - points[index].x, actualZ - points[index].z) <= 0.20) index++
            if (index == points.lastIndex) return@repeat
            val target = smoothedNpcRouteTarget(points, index, actualX, actualZ, 0.20, 0.75, 0.30)
            val velocity = npcRouteHorizontalVelocity(actualX, actualZ, target.x, target.z, 0.084)
            actualX += velocity.x
            actualZ += velocity.z
        }

        index shouldBe 2
    }

    "thin carpets are valid route coverings but taller blocks are not" {
        isNpcRouteFloorCovering(Material.RED_CARPET, 0.0625, 0.125) shouldBe true
        isNpcRouteFloorCovering(Material.RED_CARPET, 0.5, 0.125) shouldBe false
        isNpcRouteFloorCovering(Material.STONE, 0.0, 0.125) shouldBe false
    }

    "field surface opt-in accepts farmland drop but rejects slabs and roads" {
        val support = BoundingBox(0.0, 0.0, 0.0, 1.0, 0.9375, 1.0)
        val slab = BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        val defaultProfile = NpcRouteProfile("default", 69, NpcRouteBounds(0, 0, 0, 0))
        val fieldProfile = defaultProfile.copy(
            maximumSurfaceDrop = 0.0625,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )

        isNpcRouteSupportBoxAllowed(Material.FARMLAND, support, defaultProfile) shouldBe false
        isNpcRouteSupportBoxAllowed(Material.FARMLAND, support, fieldProfile) shouldBe true
        isNpcRouteSupportBoxAllowed(Material.STONE_SLAB, slab, fieldProfile) shouldBe false
        isNpcRouteSupportBoxAllowed(Material.DIRT_PATH, BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), fieldProfile) shouldBe false
    }

    "default grid edges remain unconstrained while bounded terrain accepts one block ascent only" {
        val start = NpcRouteCell(0, 0)
        val goal = NpcRouteCell(1, 0)
        val profile = NpcRouteProfile(
            id = "field",
            floorY = 69,
            bounds = NpcRouteBounds(0, 1, 0, 0),
            maximumStepHeight = 1.0,
        )
        val oneBlockAscent = mapOf(start to 69.0, goal to 70.0)
        val edge = { from: NpcRouteCell, to: NpcRouteCell ->
            kotlin.math.abs(oneBlockAscent.getValue(from) - oneBlockAscent.getValue(to)) <= profile.maximumStepHeight + 1.0e-6
        }

        findNpcGridPath(start, listOf(goal), profile) { true } shouldBe listOf(start, goal)
        findNpcGridPath(start, listOf(goal), profile, isWalkable = { true }, canTraverse = edge) shouldBe listOf(start, goal)

        val twoBlockCliff = oneBlockAscent + (goal to 71.0)
        val cliffEdge = { from: NpcRouteCell, to: NpcRouteCell ->
            kotlin.math.abs(twoBlockCliff.getValue(from) - twoBlockCliff.getValue(to)) <= profile.maximumStepHeight + 1.0e-6
        }
        findNpcGridPath(start, listOf(goal), profile, isWalkable = { true }, canTraverse = cliffEdge) shouldBe null
    }

    "surface search requires an explicit support allowlist" {
        val defaultProfile = NpcRouteProfile("default", 69, NpcRouteBounds(0, 0, 0, 0))

        shouldThrow<IllegalArgumentException> {
            defaultProfile.copy(surfaceSearchRange = 1)
        }
    }

    "surface resolver follows farmland terraces without accepting road support" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val crops = routeTestBlock(Material.WHEAT, true)
        val farmland = routeTestBlock(Material.FARMLAND, false, 0.9375)
        val road = routeTestBlock(Material.DIRT_PATH, false, 0.9375)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when (firstArg<Int>() to secondArg<Int>()) {
                0 to 67, 1 to 68 -> farmland
                0 to 68, 1 to 69 -> crops
                2 to 67 -> road
                else -> air
            }
        }
        val field = NpcRouteProfile(
            "field", 69, NpcRouteBounds(0, 2, 0, 0), maximumStepHeight = 1.0,
            maximumSurfaceDrop = 0.0625, surfaceSearchRange = 2,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )
        resolveSurfaceY(world, field, NpcRouteCell(0, 0)) shouldBe 67.9375
        resolveSurfaceY(world, field, NpcRouteCell(1, 0)) shouldBe 68.9375
        resolveSurfaceY(world, field, NpcRouteCell(2, 0)) shouldBe null
        isNpcRouteActualYAllowed(68.9375, 67.9375, field) shouldBe true
        isNpcRouteActualYAllowed(70.0, 67.9375, field) shouldBe false
    }

    "wide footprint rejects adjacent two-high hay while player footprint fits" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val hay = routeTestBlock(Material.HAY_BLOCK, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when (Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())) {
                Triple(1, 69, 0), Triple(1, 70, 0) -> hay
                else -> air
            }
        }
        val cell = NpcRouteCell(0, 0)
        val ravager = NpcRouteFootprint(0.975, 0.975, 0.0, 1.8)
        val player = NpcRouteFootprint(0.30, 0.30, 0.0, 1.8)

        isNpcRouteFootprintClear(world, cell, 69.0, ravager) shouldBe false
        isNpcRouteFootprintClear(world, cell, 69.0, player) shouldBe true
        val field = NpcRouteProfile("field", 69, NpcRouteBounds(0, 1, 0, 0),
            maximumStepHeight = 1.0, allowedSupportMaterials = setOf(Material.FARMLAND))
        isNpcRouteFootprintClear(world, cell, 69.0, ravager, field) shouldBe false
    }

    "wide footprint remains clear above an allowed farmland step and plants" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val crop = routeTestBlock(Material.WHEAT, true)
        val farmland = routeTestBlock(Material.FARMLAND, false, 0.9375)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when (firstArg<Int>() to secondArg<Int>()) {
                0 to 67 -> farmland
                0 to 68 -> crop
                1 to 68 -> farmland
                1 to 69 -> crop
                else -> air
            }
        }
        val field = NpcRouteProfile(
            "field", 69, NpcRouteBounds(0, 0, 0, 0), maximumStepHeight = 1.0,
            maximumSurfaceDrop = 0.0625, surfaceSearchRange = 2,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )
        val surface = resolveSurfaceY(world, field, NpcRouteCell(0, 0))
        surface shouldBe 67.9375
        val footprint = NpcRouteFootprint(0.975, 0.975, 0.0, 1.8)
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), surface!!, footprint) shouldBe false
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), surface, footprint, field) shouldBe true
    }

    "stepping clearance preserves thin carpets but rejects a low ceiling above a terrace" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val carpet = routeTestBlock(Material.RED_CARPET, false, 0.0625)
        val farmland = routeTestBlock(Material.FARMLAND, false, 0.9375)
        val stone = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { world.getBlockAt(0, 68, 0) } returns carpet
        val flat = NpcRouteProfile("flat", 68, NpcRouteBounds(0, 1, 0, 0))
        val body = NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 68.0, body, flat) shouldBe true

        every { world.getBlockAt(0, 68, 0) } returns air
        every { world.getBlockAt(1, 68, 0) } returns farmland
        every { world.getBlockAt(0, 70, 0) } returns stone
        val field = flat.copy(maximumStepHeight = 1.0, allowedSupportMaterials = setOf(Material.FARMLAND))
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 67.9375,
            NpcRouteFootprint(0.975, 0.975, 0.0, 1.8), field) shouldBe false
    }

    "flat surface resolver preserves authored feet height on tall support" {
        val world = mockk<World>()
        every { world.getBlockAt(0, 69, 0) } returns routeTestBlock(Material.AIR, true)
        every { world.getBlockAt(0, 70, 0) } returns routeTestBlock(Material.AIR, true)
        every { world.getBlockAt(0, 68, 0) } returns routeTestBlock(Material.OAK_FENCE, false, 1.5)
        val flat = NpcRouteProfile("flat", 69, NpcRouteBounds(0, 0, 0, 0))
        resolveSurfaceY(world, flat, NpcRouteCell(0, 0)) shouldBe 69.0
        isNpcRouteActualYAllowed(70.0, 69.0, flat) shouldBe false
    }

    "hard no-go areas are never crossed" {
        val forbidden = NpcRouteBounds(1, 2, 1, 1)
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 2),
            forbidden = listOf(forbidden),
        )

        val path = findNpcGridPath(NpcRouteCell(0, 1), listOf(NpcRouteCell(3, 1)), profile) { true }

        path?.any { it in forbidden } shouldBe false
        path?.zipWithNext()?.all { (first, second) ->
            kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z) == 1
        } shouldBe true
    }

    "preferred corridors win over a shorter ordinary route" {
        val preferred = NpcRouteBounds(0, 4, 0, 0)
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 2),
            preferred = listOf(preferred),
        )

        val path = findNpcGridPath(NpcRouteCell(0, 1), listOf(NpcRouteCell(4, 1)), profile) { true }

        path?.contains(NpcRouteCell(2, 0)) shouldBe true
    }

    "bounded search cannot leave the configured venue" {
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 2, 0, 0),
            forbidden = listOf(NpcRouteBounds(1, 1, 0, 0)),
        )

        findNpcGridPath(NpcRouteCell(0, 0), listOf(NpcRouteCell(2, 0)), profile) { true } shouldBe null
    }

    "snapped endpoint cannot report the current cell as progress" {
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 1),
        )
        val start = NpcRouteCell(0, 0)
        val orderedGoals = listOf(NpcRouteCell(4, 0), NpcRouteCell(1, 0), start)

        val path = findNpcGridPathToNearestCandidate(start, orderedGoals, profile) { cell ->
            cell.x < 2
        }

        path shouldBe listOf(start, NpcRouteCell(1, 0))
    }

    "resolved snapped endpoint is accepted independently of the authored blocked anchor" {
        isNpcRouteResolvedEndpointReached(1.7, 0.5, NpcRouteCell(1, 0), margin = 0.35) shouldBe true
        isNpcRouteResolvedEndpointReached(4.5, 0.5, NpcRouteCell(1, 0), margin = 0.35) shouldBe false
    }

    "terminal outcome is consumed once" {
        val outcomes = NpcRouteOutcomeTracker()

        outcomes.record(351, NpcRouteOutcome(successful = true, phase = "FINISHED"))

        outcomes.consume(351) shouldBe NpcRouteOutcome(successful = true, phase = "FINISHED")
        outcomes.consume(351) shouldBe null
    }

    "new route clears stale terminal failure" {
        val outcomes = NpcRouteOutcomeTracker()
        outcomes.record(362, NpcRouteOutcome(successful = false, phase = "STALLED", reason = "no-progress"))

        outcomes.reset(362)

        outcomes.consume(362) shouldBe null
    }

    "scene obstacle cells are treated as hard walls" {
        val profile = NpcRouteProfile(
            id = "hall",
            floorY = 72,
            bounds = NpcRouteBounds(0, 4, 0, 2),
        )
        val occupied = setOf(NpcRouteCell(1, 1), NpcRouteCell(2, 1), NpcRouteCell(3, 1))

        val path = findNpcGridPath(NpcRouteCell(0, 1), listOf(NpcRouteCell(4, 1)), profile) { it !in occupied }

        path?.any { it in occupied } shouldBe false
        path?.any { it.z != 1 } shouldBe true
    }
})

private fun routeTestBlock(material: Material, passable: Boolean, height: Double? = null): Block =
    mockk<Block>().also { block ->
        every { block.type } returns material
        every { block.isPassable } returns passable
        every { block.isLiquid } returns false
        every { block.collisionShape } returns mockk {
            every { boundingBoxes } returns height?.let {
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, it, 1.0))
            }.orEmpty()
        }
    }
