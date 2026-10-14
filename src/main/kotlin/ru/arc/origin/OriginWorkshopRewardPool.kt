package ru.arc.origin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Source-model bounds after ItemDisplay NONE and Minecraft's native Y+180 rotation. */
internal data class OriginWorkshopRewardBounds(
    val min: OriginWorkshopPoint,
    val max: OriginWorkshopPoint,
) {
    init {
        require(listOf(min.x, min.y, min.z, max.x, max.y, max.z).all { it.isFinite() })
        require(max.x >= min.x && max.y >= min.y && max.z >= min.z)
        require(width > 0.0 || height > 0.0 || depth > 0.0)
    }

    val width get() = max.x - min.x
    val height get() = max.y - min.y
    val depth get() = max.z - min.z
}

internal data class OriginWorkshopRewardCandidate(
    val itemId: String,
    val bounds: OriginWorkshopRewardBounds,
) {
    val scale: Double = minOf(
        MAX_SCALE,
        bounds.width.takeIf { it > 0.0 }?.let { MAX_WIDTH / it } ?: Double.POSITIVE_INFINITY,
        bounds.height.takeIf { it > 0.0 }?.let { MAX_HEIGHT / it } ?: Double.POSITIVE_INFINITY,
        bounds.depth.takeIf { it > 0.0 }?.let { MAX_DEPTH / it } ?: Double.POSITIVE_INFINITY,
    )

    init {
        require(itemId.matches(ITEM_ID_PATTERN)) { "Invalid workshop reward item id '$itemId'" }
        require(scale.isFinite() && scale > 0.0) { "Workshop reward '$itemId' has an unfit model scale" }
    }

    /** Centers the source AABB on the role's clear tabletop footprint and rests its bottom on the top. */
    fun anchor(role: OriginWorkshopTableRole, dimensions: OriginWorkshopTableDimensions): OriginWorkshopPoint {
        val (supportX, supportZ) = when (role) {
            OriginWorkshopTableRole.CARPENTER -> 1.35 to -0.35
            OriginWorkshopTableRole.UPHOLSTERER -> 0.0 to 0.08
            else -> 0.0 to -0.45
        }
        return OriginWorkshopPoint(
            supportX - center(bounds.min.x, bounds.max.x) * scale,
            dimensions.height - bounds.min.y * scale,
            supportZ - center(bounds.min.z, bounds.max.z) * scale,
        ).also { require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite()) }
    }

    private fun center(minimum: Double, maximum: Double) = minimum * 0.5 + maximum * 0.5

    private companion object {
        const val MAX_SCALE = 0.65
        const val MAX_WIDTH = 0.80
        const val MAX_HEIGHT = 1.05
        const val MAX_DEPTH = 0.65
        val ITEM_ID_PATTERN = Regex("[a-z0-9._-]+:[a-z0-9/._-]+")
    }
}

/** One explicit uniform-weight entry per verified furniture item. */
internal object OriginWorkshopRewardPool {
    private const val RESOURCE = "origin-workshop-reward-pool.json"
    private const val SCHEMA = "arc-origin-workshop-reward-pool-v1"

    val entries: List<OriginWorkshopRewardCandidate> by lazy {
        val root = checkNotNull(javaClass.classLoader.getResourceAsStream(RESOURCE)) {
            "Missing bundled Origin workshop reward pool '$RESOURCE'"
        }.use { stream -> JsonParser.parseString(stream.readBytes().toString(StandardCharsets.UTF_8)).asJsonObject }
        require(root.get("schema").asString == SCHEMA) { "Unsupported Origin workshop reward pool schema" }
        val rows = root.getAsJsonArray("items")
        val candidates = rows.map { element ->
            val item = element.asJsonObject
            require(item.get("allocation").asString == "cache-allocation-verified") {
                "Origin workshop reward '${item.get("id").asString}' has no verified CustomStack allocation"
            }
            val rawBounds = item.getAsJsonObject("bounds")
            OriginWorkshopRewardCandidate(
                item.get("id").asString,
                OriginWorkshopRewardBounds(
                    rawBounds.vector("min"),
                    rawBounds.vector("max"),
                ),
            )
        }
        require(candidates.isNotEmpty()) { "Origin workshop reward pool is empty" }
        require(candidates.map { it.itemId }.distinct().size == candidates.size) { "Duplicate Origin workshop reward item id" }
        require(candidates.size == root.getAsJsonObject("curation").get("candidateCount").asInt) {
            "Origin workshop reward pool count does not match curation metadata"
        }
        Collections.unmodifiableList(candidates)
    }

    private fun JsonObject.vector(key: String): OriginWorkshopPoint {
        val values = getAsJsonArray(key)
        require(values.size() == 3) { "Expected three coordinates in reward model bounds '$key'" }
        return OriginWorkshopPoint(values[0].asDouble, values[1].asDouble, values[2].asDouble)
    }
}
