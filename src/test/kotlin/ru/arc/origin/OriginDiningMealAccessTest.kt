package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginDiningMealAccessTest : FreeSpec({
    "working waiters may eat their own order but cannot eat player or NPC guests' meals" {
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        OriginDiningServicePolicy.mayConsumeMeal(true, player, player) shouldBe true
        OriginDiningServicePolicy.mayConsumeMeal(true, player, other) shouldBe false
        OriginDiningServicePolicy.mayConsumeMeal(true, player, null) shouldBe false
        listOf(player, other, null).forEach { owner ->
            OriginDiningServicePolicy.mayConsumeMeal(false, player, owner) shouldBe true
        }
    }
})
