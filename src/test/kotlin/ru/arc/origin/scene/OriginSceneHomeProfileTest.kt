package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteProfile

class OriginSceneHomeProfileTest : FreeSpec({
    "field home prefers its cycle terrain profile over an older flat profile" {
        val flat = NpcRouteProfile(
            id = "flat",
            floorY = 68,
            bounds = NpcRouteBounds(0, 10, 0, 10),
        )
        val field = NpcRouteProfile(
            id = "field",
            floorY = 69,
            bounds = NpcRouteBounds(0, 10, 0, 10),
            surfaceSearchRange = 2,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )
        val cycle = cycleWith(OriginSceneStep.Move(412, "field-home", "field", 20))

        selectOriginSceneHomeProfile(
            routeProfiles = linkedMapOf("flat" to flat, "field" to field),
            cycle = cycle,
            actorId = 412,
            home = Location(null, 4.5, 68.0, 4.5),
        )?.id shouldBe "field"
    }

    "legacy cycle falls back to a matching flat profile" {
        val flat = NpcRouteProfile(
            id = "flat",
            floorY = 70,
            bounds = NpcRouteBounds(0, 10, 0, 10),
        )
        val cycle = cycleWith(OriginSceneStep.Wait(20))

        selectOriginSceneHomeProfile(
            routeProfiles = mapOf("flat" to flat),
            cycle = cycle,
            actorId = 412,
            home = Location(null, 4.5, 70.0, 4.5),
        )?.id shouldBe "flat"
    }

    "move group profile is considered for every leased actor" {
        val field = NpcRouteProfile(
            id = "field",
            floorY = 69,
            bounds = NpcRouteBounds(0, 10, 0, 10),
            surfaceSearchRange = 2,
            allowedSupportMaterials = setOf(Material.FARMLAND),
        )
        val cycle = cycleWith(OriginSceneStep.MoveGroup(listOf(412), listOf("field-home"), "field", 20))

        selectOriginSceneHomeProfile(
            routeProfiles = mapOf("field" to field),
            cycle = cycle,
            actorId = 412,
            home = Location(null, 4.5, 68.0, 4.5),
        )?.id shouldBe "field"
    }
})

private fun cycleWith(step: OriginSceneStep): OriginSceneCycle = OriginSceneCycle(
    id = "test-cycle",
    actorIds = setOf(412),
    cooldownMillis = 1_000L..1_000L,
    initialDelayMillis = 0L,
    yieldAnchor = null,
    yieldRange = 1.0,
    steps = listOf(step),
)
