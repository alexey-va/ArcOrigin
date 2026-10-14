package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OriginWorkshopRewardPoolTest : FreeSpec({
    "every curated allocated model fits and rests on each role's support footprint" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val entries = OriginWorkshopRewardPool.entries
        entries.size shouldBe 330
        entries.map { it.itemId }.distinct().size shouldBe entries.size
        entries.map { it.itemId } shouldBe entries.map { it.itemId }.sorted()
        entries.none { it.itemId.startsWith("elitecreatures:potion_shop_furniture_") } shouldBe true

        for (candidate in entries) {
            candidate.scale.isFinite() shouldBe true
            (candidate.scale in 0.0..0.65) shouldBe true
            for (role in OriginWorkshopTableRole.entries) {
                val anchor = candidate.anchor(role, dimensions)
                val left = anchor.x + candidate.bounds.min.x * candidate.scale
                val right = anchor.x + candidate.bounds.max.x * candidate.scale
                val bottom = anchor.y + candidate.bounds.min.y * candidate.scale
                val top = anchor.y + candidate.bounds.max.y * candidate.scale
                val near = anchor.z + candidate.bounds.min.z * candidate.scale
                val far = anchor.z + candidate.bounds.max.z * candidate.scale
                val expectedX = if (role == OriginWorkshopTableRole.CARPENTER) 1.35 else 0.0
                val expectedZ = when (role) {
                    OriginWorkshopTableRole.CARPENTER -> -0.35
                    OriginWorkshopTableRole.UPHOLSTERER -> 0.08
                    else -> -0.45
                }

                (kotlin.math.abs((left + right) * 0.5 - expectedX) < 1e-8) shouldBe true
                (kotlin.math.abs((near + far) * 0.5 - expectedZ) < 1e-8) shouldBe true
                (kotlin.math.abs(bottom - dimensions.height) < 1e-8) shouldBe true
                (right - left <= 0.80 + 1e-8) shouldBe true
                (top - bottom <= 1.05 + 1e-8) shouldBe true
                (far - near <= 0.65 + 1e-8) shouldBe true
                if (role == OriginWorkshopTableRole.UPHOLSTERER) {
                    val sewingBed = originWorkshopTablePieces(role, 0, dimensions)
                        .single { it.key == "upholsterer-sewing-bed" }
                    (near > sewingBed.z + sewingBed.depth / 2.0) shouldBe true
                }
            }
        }
    }

    "zero-width axes do not collapse the fit and empty bounds are rejected" {
        val candidate = OriginWorkshopRewardCandidate(
            "example:flat",
            OriginWorkshopRewardBounds(
                OriginWorkshopPoint(0.0, 0.0, -0.125),
                OriginWorkshopPoint(0.0, 0.5, 0.125),
            ),
        )
        candidate.scale shouldBe 0.65
        shouldThrow<IllegalArgumentException> {
            OriginWorkshopRewardBounds(
                OriginWorkshopPoint(0.0, 0.0, 0.0),
                OriginWorkshopPoint(0.0, 0.0, 0.0),
            )
        }
    }
})
