package ru.arc.origin

import ru.arc.config.Config
import kotlin.math.sin

/** A verified GROUND model, including its bottom-to-anchor support correction. */
internal data class DiningProp(val item: String, val scale: Float, val lift: Float)

internal data class DiningBar(val actorId: Int, val station: OriginDiningPoint, val waiterIds: Set<Int> = emptySet())

internal data class OriginDiningLifeConfig(
    val audienceRange: Double = 24.0,
    val effectRange: Double = 14.0,
    val maxCycles: Int = 4,
    val servingTicks: Int = 16,
    val biteIntervalTicks: IntRange = 180..300,
    val biteDurationTicks: Int = 36,
    val steamDurationTicks: Int = 200,
    val bartenderRestTicks: IntRange = 120..200,
    val handSide: Double = 0.32,
    val handForward: Double = 0.45,
    val handHeight: Double = 1.05,
    val pourHeight: Double = 0.3,
    val drinkTableOffsetX: Double = 0.38,
    val drinkTableOffsetZ: Double = 0.0,
    val drinkFullTicks: Int = 900,
    val drinkEmptyTicks: Int = 300,
    val emptyPlate: DiningProp = DiningProp("minecraft:bowl", 0.65f, 0.0f),
    val mug: DiningProp = DiningProp("minecraft:potion", 0.5f, 0.0f),
    val emptyMug: DiningProp = mug,
    val pouringBottle: DiningProp = mug,
    val bottleMouthY: Float = 0.2998125f,
    val emptyPlates: Map<String, DiningProp> = emptyMap(),
    val bars: List<DiningBar> = emptyList(),
    val readyLines: List<String> = listOf("Напитки готовы.", "Свежая порция. Можно забирать."),
    val emptyLines: List<String> = listOf("Спасибо, было вкусно.", "Можно убрать тарелку."),
) {
    companion object {
        fun load(source: Config): OriginDiningLifeConfig {
            fun ticks(key: String, default: Int, min: Int = 1, max: Int = 1200) =
                source.integer("life.$key", default).coerceIn(min, max)
            fun real(key: String, default: Double, min: Double, max: Double) =
                source.real("life.$key", default).takeIf(Double::isFinite)?.coerceIn(min, max) ?: default
            fun interval(key: String, first: Int, last: Int): IntRange {
                val a = ticks("$key-min-ticks", first)
                val b = ticks("$key-max-ticks", last)
                return minOf(a, b)..maxOf(a, b)
            }
            fun prop(root: String, fallback: DiningProp): DiningProp = DiningProp(
                source.string("$root.item", fallback.item),
                source.real("$root.scale", fallback.scale.toDouble()).toFloat().takeIf(Float::isFinite)?.coerceIn(0.05f, 2f) ?: fallback.scale,
                source.real("$root.surface-lift", fallback.lift.toDouble()).toFloat().takeIf(Float::isFinite)?.coerceIn(-1f, 2f) ?: fallback.lift,
            )
            val defaults = OriginDiningLifeConfig()
            return OriginDiningLifeConfig(
                audienceRange = real("audience-range", 24.0, 4.0, 48.0),
                effectRange = real("effect-range", 14.0, 2.0, 24.0),
                maxCycles = ticks("max-active-cycles", 4, 1, 6),
                servingTicks = ticks("serving-ticks", 16, 4, 60),
                biteIntervalTicks = interval("bite-interval", 180, 300),
                biteDurationTicks = ticks("bite-duration-ticks", 36, 16, 80),
                steamDurationTicks = ticks("steam-duration-ticks", 200, 20, 600),
                bartenderRestTicks = interval("bartender-rest", 120, 200),
                handSide = real("hand-side", 0.32, -1.0, 1.0),
                handForward = real("hand-forward", 0.45, 0.0, 1.0),
                handHeight = real("hand-height", 1.05, 0.2, 2.0),
                pourHeight = real("pour-height", 0.3, 0.1, 0.6),
                drinkTableOffsetX = real("drink-table-offset-x", 0.38, -0.45, 0.45),
                drinkTableOffsetZ = real("drink-table-offset-z", 0.0, -0.45, 0.45),
                drinkFullTicks = ticks("drink-full-ticks", 900, 200, 2400),
                drinkEmptyTicks = ticks("drink-empty-ticks", 300, 100, 1200),
                emptyPlate = prop("life.empty-plate", defaults.emptyPlate),
                mug = prop("life.mug", defaults.mug),
                emptyMug = prop("life.empty-mug", defaults.emptyMug),
                pouringBottle = prop("life.pouring-bottle", defaults.pouringBottle),
                bottleMouthY = real("bottle-mouth-y", 0.2998125, 0.0, 1.0).toFloat(),
                emptyPlates = source.stringList("life.empty-plate-dish-ids").associateWith { dish ->
                    prop("life.empty-plates.$dish", defaults.emptyPlate)
                },
                bars = source.stringList("life.bartender-ids").mapNotNull { id ->
                    val actorId = id.toIntOrNull() ?: return@mapNotNull null
                    DiningBar(actorId, OriginDiningLayout.configPoint(source, "life.bartenders.$id.station"),
                        source.stringList("life.bartenders.$id.waiter-ids").mapNotNull(String::toIntOrNull).toSet())
                },
                readyLines = source.stringList("life.ready-lines").filter(String::isNotBlank).ifEmpty { defaults.readyLines },
                emptyLines = source.stringList("life.empty-lines").filter(String::isNotBlank).ifEmpty { defaults.emptyLines },
            )
        }
    }
}

internal enum class DiningPortion { FULL, HALF, LAST, EMPTY;
    fun next(): DiningPortion = entries[(ordinal + 1).coerceAtMost(EMPTY.ordinal)]
}

/** Pure serving arc. Endpoints are exact so a cancelled/finished prop can settle safely. */
internal fun diningServingOffset(progress: Double, dx: Double, dy: Double, dz: Double): Triple<Double, Double, Double> {
    val t = progress.coerceIn(0.0, 1.0)
    if (t == 0.0) return Triple(dx, dy, dz)
    if (t == 1.0) return Triple(0.0, 0.0, 0.0)
    val eased = t * t * (3.0 - 2.0 * t)
    return Triple(dx * (1.0 - eased), dy * (1.0 - eased) + sin(Math.PI * t) * 0.12, dz * (1.0 - eased))
}

/** Display translations are entity-local, while hand/meal anchors are world-space. */
internal fun diningLocalOffset(offset: Triple<Double, Double, Double>, yaw: Float, pitch: Float): org.joml.Vector3f =
    org.joml.Vector3f(offset.first.toFloat(), offset.second.toFloat(), offset.third.toFloat())
        .rotateY(Math.toRadians(yaw.toDouble()).toFloat())
        .rotateX(Math.toRadians(-pitch.toDouble()).toFloat())

/** Rotate the verified bottle around its mouth, keeping the stream's origin fixed. */
internal fun diningPourTransform(scale: Float, scaledMouthY: Float): org.bukkit.util.Transformation {
    val tilt = org.joml.Quaternionf().rotateZ(Math.toRadians(-100.0).toFloat())
    val translation = org.joml.Vector3f(0f, scaledMouthY, 0f).rotate(tilt).negate()
    return org.bukkit.util.Transformation(translation, tilt, org.joml.Vector3f(scale), org.joml.Quaternionf())
}
