package ru.arc.origin.scene

import org.bukkit.World
import org.bukkit.util.BoundingBox
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteProfile
import ru.arc.origin.OriginFurnitureAccess
import ru.arc.origin.SharedOriginFurnitureAccess
import kotlin.math.ceil
import kotlin.math.floor

internal const val ORIGIN_SCENE_PROP_TAG = "arc_origin_scene_prop"

/** Scene furniture is solid to every Kotlin-owned Origin NPC route. */
internal fun originFurnitureObstacleCells(world: World, profile: NpcRouteProfile): Set<NpcRouteCell> =
    originFurnitureObstacleCells(world, profile, SharedOriginFurnitureAccess)

/** Injectable runtime keeps route obstacle filtering deterministic in focused tests. */
internal fun originFurnitureObstacleCells(
    world: World,
    profile: NpcRouteProfile,
    furnitureRuntime: OriginFurnitureAccess,
): Set<NpcRouteCell> {
    if (!furnitureRuntime.available) return emptySet()
    val search = BoundingBox(
        profile.bounds.minX.toDouble(),
        profile.floorY - 1.0,
        profile.bounds.minZ.toDouble(),
        profile.bounds.maxX + 1.0,
        profile.floorY + 2.5,
        profile.bounds.maxZ + 1.0,
    )
    val cells = linkedSetOf<NpcRouteCell>()

    fun addBox(box: BoundingBox) {
        val padding = profile.entityObstaclePadding
        val minX = floor(box.minX - padding).toInt()
        val maxX = ceil(box.maxX + padding).toInt() - 1
        val minZ = floor(box.minZ - padding).toInt()
        val maxZ = ceil(box.maxZ + padding).toInt() - 1
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                NpcRouteCell(x, z).takeIf { it in profile.bounds }?.let(cells::add)
            }
        }
    }

    world.getNearbyEntities(search).forEach { entity ->
        if (ORIGIN_SCENE_PROP_TAG in entity.scoreboardTags) return@forEach
        val furniture: OriginFurnitureHandle = furnitureRuntime.inspect(entity) ?: return@forEach
        addBox(entity.boundingBox)
        addBox(furniture.root.boundingBox)
        cells += NpcRouteCell(floor(furniture.root.location.x).toInt(), floor(furniture.root.location.z).toInt())
    }
    return cells.filterTo(linkedSetOf()) { it in profile.bounds }
}
