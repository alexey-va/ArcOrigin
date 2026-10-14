package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location

class OriginDiningDrinkOrderTest : StringSpec({
    fun order() = DiningDrinkOrder(DiningBar(439, OriginDiningPoint(0.0, 1.0, 0.0), setOf(431, 432)), Location(null, 0.0, 1.0, 0.0))

    "a prepared mug waits until an assigned waiter picks it up" {
        val order = order()
        order.phase shouldBe DiningDrinkPhase.READY
        order.pickup() shouldBe false
        order.reserve("table", 410) shouldBe false
        order.reserve("table", 431) shouldBe true
        order.phase shouldBe DiningDrinkPhase.RESERVED
        order.reserve("another", 432) shouldBe false
        order.serve() shouldBe false
        order.pickup() shouldBe true
        order.pickup() shouldBe false
        order.serve() shouldBe true
        order.serve() shouldBe false
        order.returnToCounter() shouldBe false
    }

    "interruption before or after pickup returns one mug to the counter" {
        for (pickedUp in listOf(false, true)) {
            val order = order()
            order.reserve("table", 431) shouldBe true
            if (pickedUp) order.pickup() shouldBe true
            order.returnToCounter() shouldBe true
            order.phase shouldBe DiningDrinkPhase.READY
            order.tableId shouldBe null
            order.waiterId shouldBe null
            order.reserve("other-table", 432) shouldBe true
            order.pickup() shouldBe true
            order.serve() shouldBe true
        }
    }
})
