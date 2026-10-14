package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class OriginFurnitureWorkshopCarryTest : StringSpec({
    "carried product follows cardinal actor headings and keeps its facing offset through turns" {
        val distance = 0.4
        val yawOffset = 90f
        val cases = listOf(
            0f to Triple(10.0, 20.0 + distance, 90f),
            90f to Triple(10.0 - distance, 20.0, 180f),
            180f to Triple(10.0, 20.0 - distance, 270f),
            -90f to Triple(10.0 + distance, 20.0, 0f),
            360f to Triple(10.0, 20.0 + distance, 450f),
        )

        cases.forEach { (actorYaw, expected) ->
            val anchor = originFurnitureWorkshopCarryAnchor(
                x = 10.0,
                z = 20.0,
                actorYaw = actorYaw,
                forwardDistance = distance,
                yawOffset = yawOffset,
            )

            anchor.x shouldBe (expected.first plusOrMinus 1.0e-9)
            anchor.z shouldBe (expected.second plusOrMinus 1.0e-9)
            anchor.yaw shouldBe expected.third
        }
    }

    "zero forward distance leaves the anchor centered while still applying the yaw offset" {
        originFurnitureWorkshopCarryAnchor(3.0, -7.0, 135f, 0.0, 45f) shouldBe
            OriginFurnitureWorkshopCarryAnchor(3.0, -7.0, 180f)
    }
})
