package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginWorkShiftTest : StringSpec({
    "ten minute shift offers continuous work then allows a bounded final delivery" {
        val shift = OriginWorkShift.begin(1_000)
        shift.phase(1_000) shouldBe OriginWorkShiftPhase.OPENING
        shift.phase(151_000) shouldBe OriginWorkShiftPhase.RUSH
        shift.phase(481_000) shouldBe OriginWorkShiftPhase.CLOSING
        shift.acceptsOrders(600_999) shouldBe true
        shift.acceptsOrders(601_000) shouldBe false
        shift.shouldFinish(601_000, true) shouldBe false
        shift.shouldFinish(601_000, false) shouldBe true
        shift.shouldFinish(721_000, true) shouldBe true
        shift.earnedAmount shouldBe 0L
    }

    "early finish preserves confirmed orders and does not cancel an active delivery" {
        val order = UUID.randomUUID()
        val shift = OriginWorkShift.begin(0).record(order, 20, 80).requestFinish()
        shift.acceptsOrders(1) shouldBe false
        shift.shouldFinish(1, true) shouldBe false
        shift.shouldFinish(1, false) shouldBe true
        shift.earnedAmount shouldBe 20L
        shift.quality shouldBe 80
        shift.record(order, 20, 100) shouldBe shift
        shift.record(UUID.randomUUID(), 20, 100).quality shouldBe 90
    }
})
