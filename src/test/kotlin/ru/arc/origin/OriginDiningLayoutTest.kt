package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteProfile
import ru.arc.npc.findNpcGridPath
import ru.arc.worldcontent.BreweryTableDialogs
import java.nio.file.Files
import kotlin.math.abs

class OriginDiningLayoutTest : FreeSpec({
    beforeSpec {
        OriginDiningLayout.load(Files.createTempDirectory("origin-dining-layout-test"))
    }
    "every authored chair has a unique block and table anchor" {
        OriginDiningLayout.seats.size shouldBe 6
        OriginDiningLayout.seats.map { it.clickedBlock }.distinct().size shouldBe 6
        OriginDiningLayout.seats.map { it.id }.distinct().size shouldBe 6
    }

    "brewery exposes several tables and only the fire seat opens drinks" {
        OriginDiningLayout.seats.count { it.id.startsWith("brewery_") } shouldBe 4
        OriginDiningLayout.seats.single { it.id == "brewery_fire" }.menu shouldBe BreweryTableDialogs.Menu.DRINKS
        OriginDiningLayout.seats.single { it.id == "brewery_west" }.menu shouldBe BreweryTableDialogs.Menu.COURTYARD
        OriginDiningLayout.seats.count { it.menu == BreweryTableDialogs.Menu.RESTAURANT } shouldBe 2
        OriginDiningLayout.waiterIds shouldBe setOf(410, 411, 431, 432)
    }

    "only the four restaurant service NPCs are managed as waiters" {
        OriginDiningLayout.waiterIds shouldBe setOf(410, 411, 431, 432)
    }

    "ambient tables keep both waiters active in each scene" {
        OriginDiningLayout.ambientWaiterAssignments() shouldBe
            mapOf(
                "brewery_north" to 410,
                "brewery_south" to 410,
                "brewery_east" to 411,
                "restaurant_a_west" to 431,
                "restaurant_a_north" to 431,
                "restaurant_b_east" to 432,
                "restaurant_b_north" to 432,
            )
    }

    "every dish is centered one block in front of its chair" {
        val expected =
            mapOf(
                "brewery_south_west" to Triple(-6.5, 71.0, 37.5),
                "brewery_south_east" to Triple(-4.5, 71.0, 37.5),
                "brewery_fire" to Triple(-8.5, 70.75, 47.5),
                "brewery_west" to Triple(-14.5, 71.0, 53.5),
                "restaurant_a" to Triple(-54.5, 73.0, 47.5),
                "restaurant_b" to Triple(-47.5, 73.0, 51.5),
            )

        OriginDiningLayout.seats.forEach { seat ->
            val target = expected.getValue(seat.id)
            (abs(seat.dish.x - target.first) < 0.000_001) shouldBe true
            (abs(seat.dish.y - target.second) < 0.000_001) shouldBe true
            (abs(seat.dish.z - target.third) < 0.000_001) shouldBe true
        }
    }

    "every menu dish resolves to the production ItemsAdder item" {
        OriginDiningLayout.itemId("egg") shouldBe "elitecreatures:restaurant_food_eggbeacon"
        OriginDiningLayout.itemId("fish") shouldBe "elitecreatures:restaurant_food_halffish"
        OriginDiningLayout.itemId("steak") shouldBe "elitecreatures:restaurant_food_steak"
        listOf("herbal_tea", "berry_kvass", "spiced_mead").forEach { dish ->
            OriginDiningLayout.itemId(dish) shouldBe "elitecreatures:restaurant_drink"
        }
    }

    "model bounds define the scale and table-surface lift" {
        OriginDiningLayout.displayScale("steak") shouldBe 0.65f
        OriginDiningLayout.displayScale("egg") shouldBe 0.65f
        OriginDiningLayout.displayScale("herbal_tea") shouldBe 0.65f
        OriginDiningLayout.displayScale("fish") shouldBe 0.65f
        OriginDiningLayout.displayLift("steak") shouldBe 0.24375f
        OriginDiningLayout.displayLift("egg") shouldBe 0.24375f
        OriginDiningLayout.displayLift("fish") shouldBe 0.08125f
        listOf("egg", "fish", "steak").forEach { dish ->
            OriginDiningLayout.lifeConfig.emptyPlates.getValue(dish).lift shouldBe OriginDiningLayout.displayLift(dish)
        }
        OriginDiningLayout.displayLift("herbal_tea") shouldBe 0.24475f
        OriginDiningLayout.mealHitboxSize shouldBe 1.8f
    }

    "authored guest chairs face their tables" {
        OriginDiningLayout.stairFacing(-90f) shouldBe org.bukkit.block.BlockFace.WEST
        OriginDiningLayout.stairFacing(90f) shouldBe org.bukkit.block.BlockFace.EAST
        OriginDiningLayout.stairFacing(180f) shouldBe org.bukkit.block.BlockFace.SOUTH
        OriginDiningLayout.stairFacing(0f) shouldBe org.bukkit.block.BlockFace.NORTH
        OriginDiningLayout.stairYaw(org.bukkit.block.BlockFace.WEST) shouldBe -90f
        OriginDiningLayout.stairYaw(org.bukkit.block.BlockFace.EAST) shouldBe 90f
        OriginDiningLayout.yawToward(org.bukkit.block.BlockFace.NORTH) shouldBe 180f
        OriginDiningLayout.yawToward(org.bukkit.block.BlockFace.WEST) shouldBe 90f
    }

    "ItemsAdder seating is restricted to configured chair models" {
        OriginDiningLayout.furnitureSeatProfile("furnituresplus:white_wooden_chair")?.requireTable shouldBe true
        OriginDiningLayout.furnitureSeatProfile("furnituresplus:white_wooden_diningtable") shouldBe null
    }

    "automatic guest seating preserves free chairs and favors configured neighborhoods" {
        val assignments = OriginDiningLayout.selectGuestSeats(
            guests = listOf(
                416 to OriginDiningPoint(-17.5, 70.0, 40.5),
                417 to OriginDiningPoint(-13.5, 70.0, 40.5),
                418 to OriginDiningPoint(-17.5, 70.0, 47.5),
            ),
            candidates = listOf(
                OriginDiningPoint(-16.5, 70.0, 40.5),
                OriginDiningPoint(-14.5, 70.0, 40.5),
                OriginDiningPoint(-16.5, 70.0, 47.5),
                OriginDiningPoint(-8.5, 70.0, 37.5),
                OriginDiningPoint(-6.5, 70.0, 37.5),
            ),
            minimumFreeSeats = 2,
        )

        assignments.size shouldBe 3
        assignments.getValue(416) shouldBe OriginDiningPoint(-16.5, 70.0, 40.5)
        assignments.getValue(417) shouldBe OriginDiningPoint(-14.5, 70.0, 40.5)
        assignments.getValue(418) shouldBe OriginDiningPoint(-16.5, 70.0, 47.5)
    }

    "brewery seats all five guests and leaves the remaining stair for players" {
        val config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            requireNotNull(javaClass.getResourceAsStream("/modules/origin-dining.yml")).reader(),
        )
        val reserved = config.getInt("scene.auto-seating.zones.brewery.minimum-free-seats")
        val points = listOf(
            OriginDiningPoint(-16.5, 70.0, 40.5), OriginDiningPoint(-14.5, 70.0, 40.5),
            OriginDiningPoint(-16.5, 70.0, 47.5), OriginDiningPoint(-14.5, 70.0, 47.5),
            OriginDiningPoint(-2.5, 70.0, 47.5), OriginDiningPoint(-2.5, 70.0, 45.5),
        )
        val selected = OriginDiningLayout.selectGuestSeats(
            listOf(416 to points[0], 417 to points[1], 418 to points[2], 419 to points[4], 420 to points[5]), points, reserved,
        )
        selected.keys shouldBe setOf(416, 417, 418, 419, 420)
        selected.getValue(419) shouldBe points[4]
        selected.getValue(420) shouldBe points[5]
    }

    "authored coordinates are loaded from the reloadable module config" {
        OriginDiningLayout.seats.single { it.id == "brewery_south_west" }.clickedBlock shouldBe Triple(-8, 70, 37)
        OriginDiningLayout.seats.single { it.id == "restaurant_a" }.clickedBlock shouldBe Triple(-55, 72, 48)
        OriginDiningLayout.waiterHome(410) shouldBe OriginDiningPoint(1.5, 70.0, 57.5, 180f)
    }

    "ambient intervals and theft cooldown are loaded from the reloadable module config" {
        OriginDiningLayout.ambientDialogueDelayMillis shouldBe 12_000L..20_000L
        OriginDiningLayout.ambientRetryMillis shouldBe 5_000L
        OriginDiningLayout.guestReconcileMillis shouldBe 5_000L
        OriginDiningLayout.ambientWaiterRestMillis shouldBe 3_000L..6_000L
        OriginDiningLayout.ambientReplyDelayTicks shouldBe 18L..42L
        OriginDiningLayout.ambientLookHoldTicks shouldBe 32L..68L
        OriginDiningLayout.ambientSpeechDurationTicks shouldBe 200L
        OriginDiningLayout.ambientSpeechHeight shouldBe 2.8
        OriginDiningLayout.ambientSpeechViewRange shouldBe 1.25f
        OriginDiningLayout.ambientSpeechScale shouldBe 1.1f
        OriginDiningLayout.playerServiceCooldownMillis shouldBe 120_000L
        OriginDiningLayout.customerCallDelayTicks shouldBe 20L
        OriginDiningLayout.ambientCycleDelayMillis() shouldBe 18_000L..30_000L
        OriginDiningLayout.ambientDialogueRange shouldBe 8.0
        OriginDiningLayout.ambientAudienceRange shouldBe 24.0
        OriginDiningLayout.ambientDialogueCount() shouldBe 15
        OriginDiningLayout.ambientDialogueIds().distinct().size shouldBe 15
        OriginDiningLayout.ambientDialogueLineCounts().all { it == 16 } shouldBe true
        OriginDiningLayout.ambientDialogueLineCounts().all { it % 2 == 0 } shouldBe true
        OriginDiningLayout.ambientDialogueLineCounts().sum() shouldBe
            OriginDiningLayout.ambientDialogueLineCounts().count() * 16
        OriginDiningLayout.serviceDialogueCount() shouldBe 6
        OriginDiningLayout.theftCooldownMillis shouldBe 90_000L
        OriginDiningLayout.waiterPlayerRange shouldBe 1.8
        OriginDiningLayout.navigatorDistanceMargin shouldBe 0.35
        OriginDiningLayout.navigatorPathDistanceMargin shouldBe 0.35
        OriginDiningLayout.navigatorGridMaxVisited shouldBe 1_024
        OriginDiningLayout.navigatorGridPollTicks shouldBe 2L
        OriginDiningLayout.navigatorGridStallPolls shouldBe 20
        OriginDiningLayout.navigatorGridSnapRadius shouldBe 3
        OriginDiningLayout.navigatorGridOffFloorTolerance shouldBe 0.45
        OriginDiningLayout.routeProfile(org.bukkit.Location(null, -54.5, 72.0, 56.5))?.id shouldBe "restaurant"
        OriginDiningLayout.routeProfile(org.bukkit.Location(null, 1.5, 70.0, 57.5))?.id shouldBe "brewery"
        OriginDiningLayout.routeProfile(org.bukkit.Location(null, 100.0, 72.0, 100.0)) shouldBe null
        OriginDiningLayout.dynamicWaiterSideOffset shouldBe 1.0
        OriginDiningLayout.waiterHome(431) shouldBe OriginDiningPoint(-51.5, 72.0, 56.5, 63.434948f)
        OriginDiningLayout.waiterHome(432) shouldBe OriginDiningPoint(-51.5, 72.0, 58.5, 116.56505f)
    }

    "ambient dialogue catalog gives every guest pair three extended conversations" {
        val pairs = OriginDiningLayout.ambientDialogueNpcPairs()
        pairs.size shouldBe 15
        mapOf(
            "brewery_north_" to (416 to 417),
            "brewery_fire_" to (407 to 408),
            "brewery_east_" to (419 to 420),
            "restaurant_a_" to (433 to 434),
            "restaurant_b_" to (435 to 436),
        ).forEach { (prefix, pair) ->
            val matching = pairs.filterKeys { it.startsWith(prefix) }
            matching.size shouldBe 3
            matching.values.toSet() shouldBe setOf(pair)
        }
    }

    "authored conversations stay readable and finish before the execution deadline" {
        val source = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            checkNotNull(javaClass.getResourceAsStream("/modules/origin-dining.yml")).reader(),
        )
        val config = OriginDiningLayout.conversationConfig
        OriginDiningLayout.ambientDialogueIds().forEach { id ->
            val lines = source.getStringList("scene.dialogues.$id.lines")
            lines.all { it.isNotBlank() && it.codePointCount(0, it.length) <= 84 } shouldBe true
            val scheduledMillis = lines.sumOf { line ->
                val millis = config.lineDurationMillis(line) + config.pauseBetweenLinesMillis
                ((millis + 49L) / 50L) * 50L
            }
            (scheduledMillis + 5_000L < config.executionTimeoutMillis) shouldBe true
        }
    }

    "ambient table dialogue waits for a nearby player" {
        val first = OriginDiningPoint(-56.5, 72.0, 46.5)
        val second = OriginDiningPoint(-54.5, 72.0, 44.5)

        OriginDiningAudiencePolicy.hasAudience(
            actorWorld = OriginDiningLayout.WORLD,
            first = first,
            second = second,
            audience = emptyList(),
            range = 24.0,
        ) shouldBe false
        OriginDiningAudiencePolicy.hasAudience(
            actorWorld = OriginDiningLayout.WORLD,
            first = first,
            second = second,
            audience = listOf(OriginDiningAudiencePosition(OriginDiningLayout.WORLD, -47.5, 72.0, 50.5)),
            range = 24.0,
        ) shouldBe true
        OriginDiningAudiencePolicy.hasAudience(
            actorWorld = OriginDiningLayout.WORLD,
            first = first,
            second = second,
            audience = listOf(OriginDiningAudiencePosition("survival", -56.5, 72.0, 46.5)),
            range = 24.0,
        ) shouldBe false
        OriginDiningAudiencePolicy.hasAudience(
            actorWorld = OriginDiningLayout.WORLD,
            first = first,
            second = second,
            audience = listOf(OriginDiningAudiencePosition(OriginDiningLayout.WORLD, 100.0, 72.0, 100.0)),
            range = 24.0,
        ) shouldBe false
    }

    "recent service blocks another automatic approach" {
        OriginDiningServicePolicy.mayApproach(now = 10_000L, serviceCooldownUntil = 9_999L) shouldBe true
        OriginDiningServicePolicy.mayApproach(now = 10_000L, serviceCooldownUntil = 130_000L) shouldBe false
    }

    "ambient rest starts after the waiter has had time to return home" {
        OriginDiningServicePolicy.ambientAvailableAt(
            now = 10_000L,
            returnReleaseTicks = 120L,
            restMillis = 18_000L,
        ) shouldBe 34_000L
    }

    "grid pathfinder walks around blocked table cells" {
        val blocked = setOf(NpcRouteCell(1, 0), NpcRouteCell(2, 0))
        val profile = NpcRouteProfile("test", 72, NpcRouteBounds(-2, 5, -2, 2), maxVisited = 64)
        val path = findNpcGridPath(NpcRouteCell(0, 0), listOf(NpcRouteCell(3, 0)), profile) { it !in blocked }

        path?.first() shouldBe NpcRouteCell(0, 0)
        path?.last() shouldBe NpcRouteCell(3, 0)
        path?.any { it in blocked } shouldBe false
        path?.zipWithNext()?.all { (first, second) ->
            kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z) == 1
        } shouldBe true
    }

    "grid pathfinder refuses a sealed destination" {
        val blocked = setOf(
            NpcRouteCell(1, 0),
            NpcRouteCell(-1, 0),
            NpcRouteCell(0, 1),
            NpcRouteCell(0, -1),
        )
        val profile = NpcRouteProfile("test", 72, NpcRouteBounds(-3, 3, -3, 3), maxVisited = 64)
        findNpcGridPath(NpcRouteCell(0, 0), listOf(NpcRouteCell(2, 0)), profile) { it !in blocked } shouldBe null
    }

    "legacy restaurant furniture cleanup is restricted to exact ids and points" {
        OriginDiningLayout.legacyFurnitureCleanupIds() shouldBe setOf(
            "elitecreatures:restaurant_food_steak",
            "elitecreatures:medieval_market_decoration_v2_table_2",
        )
        OriginDiningLayout.legacyFurnitureCleanupPoints() shouldBe listOf(
            OriginDiningPoint(-15.5, 71.1, 47.5, 180f),
            OriginDiningPoint(-15.5, 70.001, 40.5),
            OriginDiningPoint(-15.5, 70.001, 47.5),
            OriginDiningPoint(-2.5, 70.001, 46.5),
            OriginDiningPoint(-4.5, 70.001, 37.5),
        )
    }

    "dynamic seats are limited to the two restaurant territories" {
        val brewery = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val restaurant = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val outside = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val world = io.mockk.mockk<org.bukkit.World>(relaxed = true)
        io.mockk.every { world.name } returns OriginDiningLayout.WORLD
        listOf(brewery, restaurant, outside).forEach { io.mockk.every { it.world } returns world }
        io.mockk.every { brewery.x } returns BreweryTableDialogs.BREWERY_X
        io.mockk.every { brewery.y } returns BreweryTableDialogs.BREWERY_Y
        io.mockk.every { brewery.z } returns BreweryTableDialogs.BREWERY_Z
        io.mockk.every { restaurant.x } returns BreweryTableDialogs.RESTAURANT_X
        io.mockk.every { restaurant.y } returns BreweryTableDialogs.RESTAURANT_Y
        io.mockk.every { restaurant.z } returns BreweryTableDialogs.RESTAURANT_Z
        io.mockk.every { outside.x } returns 200.0
        io.mockk.every { outside.y } returns 70.0
        io.mockk.every { outside.z } returns 200.0

        OriginDiningLayout.dynamicMenu(brewery) shouldBe BreweryTableDialogs.Menu.COURTYARD
        OriginDiningLayout.dynamicMenu(restaurant) shouldBe BreweryTableDialogs.Menu.RESTAURANT
        OriginDiningLayout.dynamicMenu(outside) shouldBe null
    }
})
