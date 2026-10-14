package ru.arc.origin

import org.bukkit.Location
import org.bukkit.Material
import kotlin.math.abs
import kotlin.math.floor

/** Resolve visible vanilla support; invisible IA collision barriers are not a tabletop. */
internal fun diningSurfaceAnchor(authored: Location): Location {
    val world = authored.world
    val x = floor(authored.x).toInt()
    val z = floor(authored.z).toInt()
    if (!world.isChunkLoaded(x shr 4, z shr 4)) return authored.clone()
    val localX = authored.x - x
    val localZ = authored.z - z
    val y = floor(authored.y).toInt()
    val heights = (y - 1..y).flatMap { blockY ->
        val block = world.getBlockAt(x, blockY, z)
        if (block.type.isAir || block.type == Material.BARRIER) emptyList()
        else block.collisionShape.boundingBoxes.filter {
            localX in it.minX..it.maxX && localZ in it.minZ..it.maxZ
        }.map { blockY + it.maxY }
    }
    return authored.clone().apply { this.y = diningSupportY(authored.y, heights) }
}

/** Stay within the authored tabletop band; never snap to the floor or an overhead decoration. */
internal fun diningSupportY(authoredY: Double, visibleTops: List<Double>): Double =
    visibleTops.filter { it.isFinite() && abs(it - authoredY) <= 0.25 }
        .maxOrNull() ?: authoredY
