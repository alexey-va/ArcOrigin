package ru.arc.origin

import kotlin.math.cos
import kotlin.math.sin

internal data class OriginWorkshopAabb(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
)

internal fun OriginWorkshopTablePiece.stockBounds(
    anchorX: Double = 0.0,
    anchorY: Double = 0.0,
    anchorZ: Double = 0.0,
) = OriginWorkshopAabb(
    anchorX + x - width / 2.0,
    anchorY + y - height / 2.0,
    anchorZ + z - depth / 2.0,
    anchorX + x + width / 2.0,
    anchorY + y + height / 2.0,
    anchorZ + z + depth / 2.0,
)

/** Mirrors spawnStock's ItemsAdder NONE transform and support-surface anchor. */
internal fun OriginWorkshopWarehouseItem.stockBounds(
    anchorX: Double = 0.0,
    anchorY: Double = 0.0,
    anchorZ: Double = 0.0,
): OriginWorkshopAabb {
    val model = checkNotNull(originWorkshopResultBounds(itemId)) { "Missing model bounds for $itemId" }
    val modelScale = scale / 0.65
    val radians = Math.toRadians(yaw.toDouble())
    val cosine = cos(radians)
    val sine = sin(radians)
    val relativeCorners = buildList {
        for (x in listOf(model.min.x, model.max.x)) {
            for (y in listOf(model.min.y, model.max.y)) {
                for (z in listOf(model.min.z, model.max.z)) {
                    val scaledX = x * modelScale
                    val scaledZ = z * modelScale
                    add(Triple(
                        scaledX * cosine - scaledZ * sine,
                        y * modelScale,
                        scaledX * sine + scaledZ * cosine,
                    ))
                }
            }
        }
    }
    val entityX = anchorX + x
    val entityY = anchorY + y - model.min.y * modelScale
    val entityZ = anchorZ + z
    return OriginWorkshopAabb(
        entityX + relativeCorners.minOf { it.first },
        entityY + relativeCorners.minOf { it.second },
        entityZ + relativeCorners.minOf { it.third },
        entityX + relativeCorners.maxOf { it.first },
        entityY + relativeCorners.maxOf { it.second },
        entityZ + relativeCorners.maxOf { it.third },
    )
}

internal fun OriginWorkshopAabb.positiveVolumeIntersection(other: OriginWorkshopAabb, epsilon: Double = 1e-9): Boolean =
    minOf(maxX, other.maxX) - maxOf(minX, other.minX) > epsilon &&
        minOf(maxY, other.maxY) - maxOf(minY, other.minY) > epsilon &&
        minOf(maxZ, other.maxZ) - maxOf(minZ, other.minZ) > epsilon

internal fun OriginWorkshopAabb.positiveAreaIntersectionXZ(other: OriginWorkshopAabb, epsilon: Double = 1e-9): Boolean =
    minOf(maxX, other.maxX) - maxOf(minX, other.minX) > epsilon &&
        minOf(maxZ, other.maxZ) - maxOf(minZ, other.minZ) > epsilon

internal fun OriginWorkshopResultBounds.at(anchor: OriginWorkshopPoint, scale: Double = 1.0) = OriginWorkshopAabb(
    anchor.x + min.x * scale,
    anchor.y + min.y * scale,
    anchor.z + min.z * scale,
    anchor.x + max.x * scale,
    anchor.y + max.y * scale,
    anchor.z + max.z * scale,
)

internal fun assertNoPositiveVolumeIntersections(bounds: List<Pair<String, OriginWorkshopAabb>>) {
    for (first in bounds.indices) for (second in first + 1 until bounds.size) {
        val (firstKey, firstBounds) = bounds[first]
        val (secondKey, secondBounds) = bounds[second]
        check(!firstBounds.positiveVolumeIntersection(secondBounds)) {
            "$firstKey intersects $secondKey: $firstBounds vs $secondBounds"
        }
    }
}
