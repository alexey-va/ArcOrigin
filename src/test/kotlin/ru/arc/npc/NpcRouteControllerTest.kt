package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.ai.Navigator
import net.citizensnpcs.api.ai.PathStrategy
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Horse
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler

class NpcRouteControllerTest : FreeSpec({
    "stable restart walks from the real horse beside a barrel without snapping or teleporting" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val ground = routeTestBlock(Material.DIRT, false, 1.0)
        val barrel = routeTestBlock(Material.BARREL, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when {
                firstArg<Int>() == 54 && thirdArg<Int>() == -135 && secondArg<Int>() in 70..71 -> barrel
                secondArg<Int>() == 69 -> ground
                else -> air
            }
        }
        val profile = NpcRouteProfile("yard-field", 69, NpcRouteBounds(52, 54, -138, -134),
            snapRadius = 0, maxVisited = 32, maximumStepHeight = 1.0, maximumSurfaceDrop = 0.5,
            surfaceSearchRange = 2, allowedSupportMaterials = setOf(Material.DIRT))
        val actual = Vector(53.10509043988335, 70.0, -135.9491693339353)
        val body = NpcRouteFootprint(0.7, 0.7, 0.0, 1.6)
        val surface = { cell: NpcRouteCell -> resolveSurfaceY(world, profile, cell) }
        val clear = { from: Vector, to: Vector -> isNpcRouteSegmentClear(world, profile, from, to, body) }
        clear(actual, actual) shouldBe true
        val rounded = Vector(53.5, 70.0, -135.5)
        clear(rounded, rounded) shouldBe false
        npcRouteWalkingStart(actual, profile, surface, clear) shouldBe NpcRouteCell(53, -137)
        npcRouteWalkingStart(Vector(53.7, 70.0, -134.5), profile, surface, clear) shouldBe null
        npcRouteWalkingStart(actual, profile, surface) { from, to ->
            isNpcRouteSegmentClear(world, profile, from, to, body, setOf(NpcRouteCell(53, -136)))
        } shouldBe null

        val horse = mockk<Horse>(relaxed = true) {
            every { this@mockk.world } returns world
            every { location } answers { actual.toLocation(world) }
            every { boundingBox } returns BoundingBox(actual.x - 0.7, actual.y, actual.z - 0.7,
                actual.x + 0.7, actual.y + 1.6, actual.z + 0.7)
        }
        val strategy = mockk<PathStrategy>()
        val navigator = mockk<Navigator>(relaxed = true) {
            every { isNavigating } returns false
            every { pathStrategy } returns strategy
        }
        val npc = mockk<NPC> {
            every { id } returns 422
            every { isSpawned } returns true
            every { entity } returns horse
            every { this@mockk.navigator } returns navigator
        }
        val events = mutableListOf<NpcRouteEvent>()
        val registry = mockk<NPCRegistry> { every { getById(any()) } returns null }
        mockkStatic(CitizensAPI::class)
        try {
            // Admission is tested here; the separate route-motion tests own the native tick adapter.
            every { CitizensAPI.getNPCRegistry() } returns registry
            Tasks.withScheduler(TestTaskScheduler()) {
                CitizensNpcRouteController(events::add).use { routes ->
                    routes.navigate(npc, Location(world, 53.5, 70.0, -137.5), profile) shouldBe true
                    events.map { it.phase } shouldBe listOf("STARTED")
                    verify(exactly = 0) { horse.teleport(any<Location>()) }
                    // Goal admission is still exact: the unsafe rounded cell is not a substitute endpoint.
                    routes.navigate(npc, rounded.toLocation(world), profile) shouldBe false
                    events.last().reason shouldBe "no-level-route"
                    verify(exactly = 0) { horse.teleport(any<Location>()) }
                }
            }
        } finally {
            unmockkStatic(CitizensAPI::class)
            // These geometry reads are not interaction assertions; do not retain their recorder history.
            clearMocks(world, air, ground, barrel, air.collisionShape, ground.collisionShape, barrel.collisionShape,
                horse, navigator, strategy, npc, registry, answers = false)
        }
    }

    "walking start rejects a blocked connector even when its actual body and nearby endpoints are clear" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val ground = routeTestBlock(Material.DIRT, false, 1.0)
        val post = routeTestBlock(Material.OAK_FENCE, false, 1.5)
        every { post.collisionShape } returns mockk {
            every { boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.4, 0.2, 1.5, 0.6))
        }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when {
                firstArg<Int>() == 53 && secondArg<Int>() == 70 && thirdArg<Int>() == -137 -> post
                secondArg<Int>() == 69 -> ground
                else -> air
            }
        }
        val profile = NpcRouteProfile("corner", 70, NpcRouteBounds(52, 53, -137, -136), snapRadius = 0)
        val body = NpcRouteFootprint(0.7, 0.7, 0.0, 1.6)
        val actual = Vector(52.1, 70.0, -136.9)
        val candidate = Vector(52.5, 70.0, -135.5)
        val clear = { from: Vector, to: Vector -> isNpcRouteSegmentClear(world, profile, from, to, body) }
        clear(actual, actual) shouldBe true
        clear(candidate, candidate) shouldBe true
        clear(actual, candidate) shouldBe false
        npcRouteWalkingStart(actual, profile, { resolveSurfaceY(world, profile, it) }, clear) shouldBe null
    }

    "a stale owner leaves the replacement native route running while its own route is stopped" {
        val owned = mockk<PathStrategy>()
        val replacement = mockk<PathStrategy>()
        val navigator = mockk<Navigator>(relaxed = true)
        every { navigator.pathStrategy } returns replacement
        canResumeOwnedNpcRoute(navigator, owned) shouldBe false
        stopOwnedNpcRoute(navigator, owned)
        verify(exactly = 0) { navigator.cancelNavigation() }
        every { navigator.pathStrategy } returns owned
        canResumeOwnedNpcRoute(navigator, owned) shouldBe true
        stopOwnedNpcRoute(navigator, owned)
        verify(exactly = 1) { navigator.cancelNavigation() }
        every { navigator.pathStrategy } returns null
        canResumeOwnedNpcRoute(navigator, owned) shouldBe true
    }

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

    "field profile lifts a small solid edge but keeps center support and hard blockers" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val crop = routeTestBlock(Material.WHEAT, true)
        val farmland = routeTestBlock(Material.FARMLAND, false, 0.9375)
        val dirtLip = routeTestBlock(Material.DIRT, false, 1.0)
        val wall = routeTestBlock(Material.STONE, false, 1.0)
        val water = routeTestBlock(Material.WATER, true)
        every { water.isLiquid } returns true
        var neighbor: Block = dirtLip
        var upper: Block = air
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when (Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())) {
                Triple(0, 67, 0) -> farmland
                Triple(0, 68, 0) -> crop
                Triple(1, 67, 0) -> neighbor
                Triple(1, 68, 0) -> upper
                else -> air
            }
        }
        val field = NpcRouteProfile(
            "field", 69, NpcRouteBounds(0, 1, 0, 0), maximumStepHeight = 1.0,
            maximumSurfaceDrop = 0.0625, surfaceSearchRange = 2,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )
        val body = NpcRouteFootprint(0.7, 0.7, 0.0, 1.6)
        val cell = NpcRouteCell(0, 0)
        val surface = 67.9375

        // The adjacent full block raises the effective standing plane to 68.0;
        // its collision is not simply ignored as a clipped overlap.
        isNpcRouteFootprintClear(world, cell, surface, body, field) shouldBe true
        resolveSurfaceY(world, field, NpcRouteCell(1, 0)) shouldBe null

        isNpcRouteFootprintClear(world, cell, surface, body, field.copy(maximumSurfaceDrop = 0.0)) shouldBe false
        upper = wall
        isNpcRouteFootprintClear(
            world, cell, surface, body, field.copy(maximumSurfaceDrop = 0.5),
        ) shouldBe false
        upper = air

        val from = Vector(0.5, surface, 0.5)
        val to = Vector(0.6, surface, 0.5)
        isNpcRouteSegmentClear(
            world, field.copy(forbidden = listOf(NpcRouteBounds(1, 1, 0, 0))), from, to, body,
        ) shouldBe false

        neighbor = water
        isNpcRouteFootprintClear(world, cell, surface, body, field) shouldBe false
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

    "swept player body rejects an inside wall even when both endpoints are clear" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val stone = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71 || (firstArg<Int>() == 0 && thirdArg<Int>() == 1 && secondArg<Int>() in 72..73)) stone else air
        }
        val profile = NpcRouteProfile("restaurant", 72, NpcRouteBounds(-2, 4, -2, 4))
        val body = NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)
        val entry = Vector(0.8, 72.0, 0.5)
        val exit = Vector(1.5, 72.0, 1.2)

        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 72.0, body, profile) shouldBe true
        isNpcRouteFootprintClear(world, NpcRouteCell(1, 1), 72.0, body, profile) shouldBe true
        isNpcRouteSegmentClear(world, profile, entry, exit, body) shouldBe false
        isNpcRouteSegmentClear(world, profile, entry, Vector(1.5, 72.0, 0.5), body) shouldBe true
        isNpcRouteSegmentClear(world, profile, Vector(1.5, 72.0, 0.5), exit, body) shouldBe true
        val route = listOf(Vector(0.5, 72.0, 0.5), Vector(1.5, 72.0, 0.5), Vector(1.5, 72.0, 1.5))
        val safe: (Vector, Vector) -> Boolean = { from, to ->
            isNpcRouteSegmentClear(world, profile, from, to, body, setOf(NpcRouteCell(0, 1)))
        }
        shortcutNpcRoutePoints(route, 0.8125, safe) shouldBe route
        roundedNpcRoutePoints(route, 0.75, 0.30, safe).zipWithNext().all { (from, to) -> safe(from, to) } shouldBe true
    }

    "rounded movement completes every tick beside a wall without colliding or false blockage" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val stone = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71 || (firstArg<Int>() == 2 && thirdArg<Int>() == 1 && secondArg<Int>() in 72..73)) stone else air
        }
        val profile = NpcRouteProfile("restaurant", 72, NpcRouteBounds(-2, 6, -2, 6))
        val body = NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)
        val route = listOf(Vector(0.5, 72.0, 0.5), Vector(3.5, 72.0, 0.5), Vector(3.5, 72.0, 3.5))
        val safe: (Vector, Vector) -> Boolean = { from, to ->
            isNpcRouteSegmentClear(world, profile, from, to, body, setOf(NpcRouteCell(2, 1)))
        }
        val rounded = roundedNpcRoutePoints(route, 0.75, 0.30, safe)
        (rounded.size > route.size) shouldBe true
        for (speed in listOf(0.144, 0.084)) {
            val motion = NpcRouteMotion(rounded)
            var actual = route.first().clone()
            repeat(160) {
                val target = motion.target(actual, speed)
                val next = actual.clone().add(npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, speed))
                safe(actual, next) shouldBe true
                actual = next
            }
            (actual.distance(route.last()) < 0.001) shouldBe true
        }
    }

    "wide animals round open corners and keep every physical step inside the route corridor" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val floor = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71) floor else air
        }
        val profile = NpcRouteProfile("animals", 72, NpcRouteBounds(-2, 7, -2, 7))
        val route = listOf(Vector(0.5, 72.0, 0.5), Vector(4.5, 72.0, 0.5), Vector(4.5, 72.0, 4.5))
        for (halfWidth in listOf(0.7, 0.975)) {
            val body = NpcRouteFootprint(halfWidth, halfWidth, 0.0, 1.8)
            val safe: (Vector, Vector) -> Boolean = { from, to -> isNpcRouteSegmentClear(world, profile, from, to, body) }
            val rounded = roundedNpcRoutePoints(route, 0.75, 0.30, safe)
            (rounded.size > route.size) shouldBe true
            val motion = NpcRouteMotion(rounded)
            var actual = route.first().clone()
            repeat(160) {
                val target = motion.target(actual, 0.084)
                val next = actual.clone().add(npcRouteHorizontalVelocity(actual.x, actual.z, target.x, target.z, 0.084))
                safe(actual, next) shouldBe true
                minOf(kotlin.math.abs(next.z - 0.5), kotlin.math.abs(next.x - 4.5)).let { (it <= 0.30) shouldBe true }
                actual = next
            }
            (actual.distance(route.last()) < 0.001) shouldBe true
        }
    }

    "narrow search bounds allow wide bodies but adjacent walls and forbidden furniture still block them" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val stone = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71) stone else air
        }
        val profile = NpcRouteProfile("pen", 72, NpcRouteBounds(0, 4, 0, 0))
        val body = NpcRouteFootprint(0.7, 0.7, 0.0, 1.8)
        val from = Vector(0.5, 72.0, 0.5)
        val to = Vector(4.5, 72.0, 0.5)
        isNpcRouteSegmentClear(world, profile, from, to, body) shouldBe true
        isNpcRouteSegmentClear(world, profile, from, Vector(4.5, 72.0, 1.1), body) shouldBe false
        val blocked = setOf(NpcRouteCell(2, 1))
        isNpcRouteSegmentClear(world, profile, from, to, body, blocked) shouldBe false
        val forbidden = profile.copy(forbidden = listOf(NpcRouteBounds(2, 2, 1, 1)))
        isNpcRouteSegmentClear(world, forbidden, from, to, body) shouldBe false
        val beside = Vector(2.5, 72.0, 0.5)
        isNpcRouteSegmentClear(world, forbidden, beside, beside, body) shouldBe false
        every { world.getBlockAt(2, 72, 1) } returns stone
        isNpcRouteSegmentClear(world, profile, from, to, body) shouldBe false
        npcRouteObstacleProfile(profile, body).bounds shouldBe NpcRouteBounds(-1, 5, -1, 1)
    }

    "wide animals can traverse explicitly allowed farmland terraces" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val farmland = routeTestBlock(Material.FARMLAND, false, 0.9375)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val floor = if (firstArg<Int>() <= 0) 67 else 68
            if (secondArg<Int>() == floor) farmland else air
        }
        val field = NpcRouteProfile("field", 69, NpcRouteBounds(-3, 4, -3, 3), maximumStepHeight = 1.0,
            maximumSurfaceDrop = 0.0625, surfaceSearchRange = 2, allowedSupportMaterials = setOf(Material.FARMLAND))
        val body = NpcRouteFootprint(0.975, 0.975, 0.0, 1.8)
        val low = Vector(0.5, 67.9375, 0.5)
        val high = Vector(1.5, 68.9375, 0.5)
        isNpcRouteSegmentClear(world, field, low, high, body) shouldBe true
        isNpcRouteSegmentClear(world, field, high, low, body) shouldBe true
        isNpcRouteSegmentClear(world, field.copy(maximumStepHeight = 0.125), low, high, body) shouldBe false
    }

    "swept routes preserve forbidden furniture cells and fixed floor step caps" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val stone = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71) stone else air
        }
        val profile = NpcRouteProfile("restaurant", 72, NpcRouteBounds(-2, 4, -2, 4))
        val body = NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)
        val from = Vector(0.5, 72.0, 0.5)
        val to = Vector(1.5, 72.0, 0.5)
        isNpcRouteSegmentClear(world, profile, from, to, body) shouldBe true
        isNpcRouteSegmentClear(world, profile, from, to, body, setOf(NpcRouteCell(1, 0))) shouldBe false
        isNpcRouteSegmentClear(world, profile.copy(forbidden = listOf(NpcRouteBounds(1, 1, 0, 0))), from, to, body) shouldBe false
        every { world.getBlockAt(1, 72, 0) } returns stone
        isNpcRouteSegmentClear(world, profile, from, to, body) shouldBe false
        profile.maximumStepHeight shouldBe 0.125
    }

    "a fence below feet cannot protrude into the moving body" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { world.getBlockAt(0, 71, 0) } returns routeTestBlock(Material.OAK_FENCE, false, 1.5)
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 72.0,
            NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)) shouldBe false
    }

    "liquid below solid support is not a body collision" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val water = routeTestBlock(Material.WATER, true)
        every { water.isLiquid } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { world.getBlockAt(0, 66, 0) } returns water
        every { world.getBlockAt(0, 67, 0) } returns routeTestBlock(Material.FARMLAND, false, 0.9375)
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 67.9375,
            NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)) shouldBe true
        every { world.getBlockAt(0, 68, 0) } returns water
        isNpcRouteFootprintClear(world, NpcRouteCell(0, 0), 67.9375,
            NpcRouteFootprint(0.3, 0.3, 0.0, 1.8)) shouldBe false
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

    "safe diagonals take the short line across an open room" {
        val profile = NpcRouteProfile("room", 72, NpcRouteBounds(0, 8, 0, 8))
        val path = findNpcGridPath(NpcRouteCell(0, 0), listOf(NpcRouteCell(8, 8)), profile,
            allowDiagonalConnections = true, isWalkable = { true })!!

        path shouldBe (0..8).map { NpcRouteCell(it, it) }
    }

    "soft clearance leaves the wall when space exists but keeps a narrow passage usable" {
        val room = NpcRouteProfile("room", 72, NpcRouteBounds(0, 8, 0, 2))
        val start = NpcRouteCell(0, 0)
        val goal = NpcRouteCell(8, 0)
        val cost: (NpcRouteCell) -> Int = { if (it.z == 0) 50 else 40 }
        val spacious = findNpcGridPath(start, listOf(goal), room,
            allowDiagonalConnections = true, stepCost = cost, isWalkable = { true })!!
        val narrow = findNpcGridPath(start, listOf(goal), room.copy(bounds = NpcRouteBounds(0, 8, 0, 0)),
            allowDiagonalConnections = true, stepCost = cost, isWalkable = { true })!!

        spacious.any { it.z > 0 } shouldBe true
        narrow shouldBe (0..8).map { NpcRouteCell(it, 0) }
    }

    "continuous position is safe even when that cell center would touch an adjacent wall" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val floor = routeTestBlock(Material.STONE, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            if (secondArg<Int>() == 71 || firstArg<Int>() == 2) floor else air
        }
        val profile = NpcRouteProfile("wide-body", 72, NpcRouteBounds(0, 2, 0, 2))
        val body = NpcRouteFootprint(0.6, 0.6, 0.0, 1.8)
        val actual = Vector(1.3, 72.0, 1.5)

        isNpcRouteFootprintClear(world, NpcRouteCell(1, 1), 72.0, body, profile) shouldBe false
        isNpcRouteSegmentClear(world, profile, actual, actual, body) shouldBe true
        isNpcRouteSegmentClear(world, profile, actual, actual, body, setOf(NpcRouteCell(1, 1))) shouldBe false
    }

    "live hay-and-pumpkin corner is reachable only by a fully swept diagonal" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val floor = routeTestBlock(Material.STONE, false, 1.0)
        val hay = routeTestBlock(Material.HAY_BLOCK, false, 1.0)
        val pumpkin = routeTestBlock(Material.PUMPKIN, false, 1.0)
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            when (Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())) {
                Triple(64, 70, -143), Triple(64, 70, -142) -> hay
                Triple(67, 70, -146) -> pumpkin
                else -> if (secondArg<Int>() == 69) floor else air
            }
        }
        val start = NpcRouteCell(66, -144)
        val goal = NpcRouteCell(65, -145)
        val profile = NpcRouteProfile(
            "caravan-gap", 70, NpcRouteBounds(65, 66, -145, -144), maximumStepHeight = 0.125,
        )
        val body = NpcRouteFootprint(0.6982422, 0.6982422, 0.0, 1.5)
        fun center(cell: NpcRouteCell) = Vector(cell.x + 0.5, 70.0, cell.z + 0.5)
        val canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { from, to ->
            isNpcRouteSegmentClear(world, profile, center(from), center(to), body)
        }
        val isWalkable: (NpcRouteCell) -> Boolean = { cell ->
            profile.allows(cell) && resolveSurfaceY(world, profile, cell)?.let { surface ->
                isNpcRouteFootprintClear(world, cell, surface, body, profile)
            } == true
        }

        resolveSurfaceY(world, profile, start) shouldBe 70.0
        resolveSurfaceY(world, profile, goal) shouldBe 70.0
        canTraverse(start, NpcRouteCell(66, -145)) shouldBe false // pumpkin clips the north edge
        canTraverse(start, NpcRouteCell(65, -144)) shouldBe false // hay clips the west edge
        canTraverse(start, goal) shouldBe true
        isWalkable(start) shouldBe true
        isWalkable(goal) shouldBe true

        findNpcGridPath(
            start, listOf(goal), profile, canTraverse = canTraverse, isWalkable = isWalkable,
        ) shouldBe null
        findNpcGridPath(
            start, listOf(goal), profile, canTraverse,
            allowDiagonalConnections = true, isWalkable = isWalkable,
        ) shouldBe listOf(start, goal)
        findNpcGridPathToNearestCandidate(
            start, listOf(goal), profile, canTraverse, isWalkable = isWalkable,
        ) shouldBe null
        findNpcGridPathToNearestCandidate(
            start, listOf(goal), profile, canTraverse,
            allowDiagonalConnections = true, isWalkable = isWalkable,
        ) shouldBe listOf(start, goal)
    }

    "diagonal routing keeps forbidden cells, furniture, solid blocks, and liquids as hard guards" {
        val world = mockk<World>()
        val air = routeTestBlock(Material.AIR, true)
        val floor = routeTestBlock(Material.STONE, false, 1.0)
        val hay = routeTestBlock(Material.HAY_BLOCK, false, 1.0)
        val pumpkin = routeTestBlock(Material.PUMPKIN, false, 1.0)
        val wall = routeTestBlock(Material.STONE, false, 1.0)
        val water = routeTestBlock(Material.WATER, true)
        every { water.isLiquid } returns true
        var diagonalObstacle: Block = air
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val x = firstArg<Int>()
            val y = secondArg<Int>()
            val z = thirdArg<Int>()
            when {
                Triple(x, y, z) == Triple(64, 70, -143) || Triple(x, y, z) == Triple(64, 70, -142) -> hay
                Triple(x, y, z) == Triple(67, 70, -146) -> pumpkin
                Triple(x, y, z) == Triple(66, 70, -145) -> diagonalObstacle
                y == 69 -> floor
                else -> air
            }
        }
        val start = NpcRouteCell(66, -144)
        val goal = NpcRouteCell(65, -145)
        val profile = NpcRouteProfile(
            "caravan-gap", 70, NpcRouteBounds(65, 66, -145, -144), maximumStepHeight = 0.125,
        )
        val body = NpcRouteFootprint(0.6982422, 0.6982422, 0.0, 1.5)
        fun center(cell: NpcRouteCell) = Vector(cell.x + 0.5, 70.0, cell.z + 0.5)
        fun route(
            candidateProfile: NpcRouteProfile = profile,
            blockedFurniture: Set<NpcRouteCell> = emptySet(),
        ): List<NpcRouteCell>? = findNpcGridPath(
            start, listOf(goal), candidateProfile,
            canTraverse = { from, to ->
                isNpcRouteSegmentClear(
                    world, candidateProfile, center(from), center(to), body, blockedFurniture,
                )
            },
            allowDiagonalConnections = true,
            isWalkable = { cell ->
                candidateProfile.allows(cell) &&
                    resolveSurfaceY(world, candidateProfile, cell)?.let { surface ->
                        isNpcRouteFootprintClear(world, cell, surface, body, candidateProfile)
                    } == true
            },
        )

        val forbidden = profile.copy(forbidden = listOf(NpcRouteBounds(66, 66, -145, -145)))
        isNpcRouteSegmentClear(world, forbidden, center(start), center(goal), body) shouldBe false
        route(forbidden) shouldBe null

        val furniture = setOf(NpcRouteCell(66, -145))
        isNpcRouteSegmentClear(world, profile, center(start), center(goal), body, furniture) shouldBe false
        route(blockedFurniture = furniture) shouldBe null

        diagonalObstacle = wall
        isNpcRouteSegmentClear(world, profile, center(start), center(goal), body) shouldBe false
        route() shouldBe null

        diagonalObstacle = water
        isNpcRouteSegmentClear(world, profile, center(start), center(goal), body) shouldBe false
        route() shouldBe null
    }

    "nearest-candidate search prioritizes a safely diagonal nearby goal over a farther axial goal" {
        val start = NpcRouteCell(0, 0)
        val nearby = NpcRouteCell(1, 1)
        val farther = NpcRouteCell(2, 0)
        val profile = NpcRouteProfile("snap", 72, NpcRouteBounds(0, 2, 0, 1))
        val blockedEdges = setOf(
            NpcRouteCell(1, 0) to nearby,
        )
        val walkable = setOf(start, NpcRouteCell(1, 0), farther, nearby)
        val canTraverse: (NpcRouteCell, NpcRouteCell) -> Boolean = { from, to ->
            (from to to) !in blockedEdges
        }

        findNpcGridPathToNearestCandidate(
            start, listOf(nearby, farther), profile, canTraverse, isWalkable = { it in walkable },
        ) shouldBe listOf(start, NpcRouteCell(1, 0), farther)
        findNpcGridPathToNearestCandidate(
            start, listOf(nearby, farther), profile, canTraverse,
            allowDiagonalConnections = true, isWalkable = { it in walkable },
        ) shouldBe listOf(start, nearby)
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
        val remaining = listOf(Vector(1.5, 72.0, 0.5))
        isNpcRouteResolvedEndpointReached(1.7, 0.5, NpcRouteCell(1, 0), margin = 0.35, remainingPath = remaining) shouldBe true
        isNpcRouteResolvedEndpointReached(4.5, 0.5, NpcRouteCell(1, 0), margin = 0.35, remainingPath = remaining) shouldBe false
    }

    "a closed loop cannot complete or avoid stall detection before its intermediate points are passed" {
        val home = Vector(0.5, 70.0, 0.5)
        val path = listOf(home, Vector(0.5, 70.0, 2.5), Vector(2.5, 70.0, 2.5), home)
        val motion = NpcRouteMotion(path)
        repeat(80) {
            motion.target(home, 0.116)
            isNpcRouteResolvedEndpointReached(home.x, home.z, NpcRouteCell(0, 0), 0.35,
                path.drop(motion.index)) shouldBe false
        }
        isNpcRouteResolvedEndpointReached(home.x, home.z, NpcRouteCell(0, 0), 0.35,
            listOf(path.last())) shouldBe true
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
