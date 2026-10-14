package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OriginDiningSurfaceTest : FreeSpec({
    "legacy extra anchor height snaps to actual log top" {
        diningSupportY(71.1, listOf(71.0)) shouldBe 71.0
    }
    "carpet support is not rounded down to the block floor" {
        diningSupportY(73.0, listOf(73.0, 73.0625)) shouldBe 73.0625
        diningSupportY(73.0625, listOf(73.0625)) shouldBe 73.0625
    }
    "missing furniture shape preserves authored anchor and never drops to floor" {
        diningSupportY(71.0, emptyList()) shouldBe 71.0
        diningSupportY(71.0, listOf(70.0, 72.0, Double.NaN)) shouldBe 71.0
    }
})
