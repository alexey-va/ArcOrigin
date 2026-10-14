package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OriginDiningLifePolicyTest : FreeSpec({
    "portion progression saturates at an empty plate" {
        DiningPortion.FULL.next() shouldBe DiningPortion.HALF
        DiningPortion.HALF.next() shouldBe DiningPortion.LAST
        DiningPortion.LAST.next() shouldBe DiningPortion.EMPTY
        DiningPortion.EMPTY.next() shouldBe DiningPortion.EMPTY
    }

    "serving arc has exact endpoints, bounded lateral travel, and a smooth raised midpoint" {
        val start = Triple(2.0, -1.5, -3.0)
        diningServingOffset(0.0, start.first, start.second, start.third) shouldBe start
        diningServingOffset(1.0, start.first, start.second, start.third) shouldBe Triple(0.0, 0.0, 0.0)

        val midpoint = diningServingOffset(0.5, start.first, start.second, start.third)
        midpoint.near(1.0, -0.63, -1.5)

        (0..100).forEach { sample ->
            val offset = diningServingOffset(sample / 100.0, start.first, start.second, start.third)
            offset.first shouldBeWithin 0.0..start.first
            offset.third shouldBeWithin start.third..0.0
            offset.second shouldBeWithin min(0.0, start.second)..(max(0.0, start.second) + 0.12)
        }
    }

    "serving offset follows display yaw-local coordinates" {
        val yawed = diningLocalOffset(Triple(1.0, 0.0, 0.0), yaw = 90f, pitch = 0f)
        yawed.x.near(0.0)
        yawed.y.near(0.0)
        yawed.z.near(-1.0)

        val pitched = diningLocalOffset(Triple(0.0, 1.0, 0.0), yaw = 0f, pitch = 90f)
        pitched.x.near(0.0)
        pitched.y.near(0.0)
        pitched.z.near(-1.0)
    }

    "life config clamps audience and active-cycle budgets" {
        val root = Files.createTempDirectory("origin-dining-life-policy-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-dining.yml"),
            """
            life:
              audience-range: 99
              max-active-cycles: 0
            """.trimIndent(),
        )

        val config = OriginDiningLifeConfig.load(Config(root, "modules/origin-dining.yml"))

        config.audienceRange shouldBe 48.0
        config.maxCycles shouldBe 1
    }

    "pouring bottle mouth stays at the stream origin while tilted" {
        val transform = diningPourTransform(0.65f, 0.2998125f)
        val mouth = org.joml.Vector3f(0f, 0.2998125f, 0f)
            .rotate(transform.leftRotation).add(transform.translation)
        mouth.x.near(0.0)
        mouth.y.near(0.0)
        mouth.z.near(0.0)
    }
})

private fun Triple<Double, Double, Double>.near(expectedX: Double, expectedY: Double, expectedZ: Double) {
    first.near(expectedX)
    second.near(expectedY)
    third.near(expectedZ)
}

private fun Double.near(expected: Double) {
    (abs(this - expected) < 0.000001) shouldBe true
}

private fun Float.near(expected: Double) = toDouble().near(expected)

private infix fun Double.shouldBeWithin(bounds: ClosedRange<Double>) {
    (this >= bounds.start - 0.000001 && this <= bounds.endInclusive + 0.000001) shouldBe true
}
