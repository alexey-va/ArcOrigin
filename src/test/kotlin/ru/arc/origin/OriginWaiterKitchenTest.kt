package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.util.UUID

class OriginWaiterKitchenTest : StringSpec({
    "one chef gives pending preparations priority without hiding ready dishes or depending on player iteration" {
        val venue = OriginWaiterVenue("brewery", 405, false, 411)
        fun order(id: Long, readyAt: Long): Pair<OriginWaiterVenue, OriginWaiterJob> {
            val target = waiterTarget(venue.id).let { it.copy(lease = it.lease.copy(token = UUID(0, id))) }
            return venue to OriginWaiterJob(target, 0, 180_000, OriginWaiterStage.PICKUP, readyAt)
        }
        val ready = order(1, 8_000)
        val first = order(2, 14_000)
        val tied = order(3, 14_000)
        val later = order(4, 17_000)
        val orders = listOf(ready, later, tied, first)
        waiterKitchenOrders(orders, 10_000) shouldBe listOf(first, tied, later, ready)
        waiterKitchenOrders(orders.reversed(), 10_000) shouldBe listOf(first, tied, later, ready)
        waiterKitchenOrders(orders, 14_000) shouldBe listOf(later, ready, first, tied)
        waiterKitchenOrders(orders, 17_000) shouldBe listOf(ready, first, tied, later)
        waiterKitchenOrders(listOf(first.first to first.second.copy(stage = OriginWaiterStage.DELIVER),
            ready.first to ready.second.copy(family = OriginWaiterFamily.ASSEMBLED)), 10_000) shouldBe emptyList()
    }

    "the ingredient spoon and neutral hand follow the current preparation including a remake" {
        val config = waiterTestConfig().copy(preparationMillis = 10_000)
        val job = OriginWaiterJob(waiterTarget(), 0, 180_000, OriginWaiterStage.PICKUP, 20_000)
        waiterKitchenProgress(job, config.preparationMillis, 0) shouldBe 0.0
        waiterKitchenHand(config, job, 10_000) shouldBe Material.EGG
        waiterKitchenHand(config, job, 11_799) shouldBe Material.EGG
        waiterKitchenHand(config, job, 11_800) shouldBe Material.WOODEN_SHOVEL
        waiterKitchenHand(config, job, 19_999) shouldBe Material.WOODEN_SHOVEL
        waiterKitchenHand(config, job, 20_000) shouldBe null
        waiterKitchenProgress(job, config.preparationMillis, 21_000) shouldBe 1.0
        val remake = job.copy(readyAt = 35_000, reworks = 1)
        waiterKitchenHand(config, remake, 25_000) shouldBe Material.EGG
        waiterKitchenProgress(remake, config.preparationMillis, 30_000) shouldBe 0.5
        waiterKitchenProgress(remake, 0, 35_000) shouldBe 1.0
    }

    "drink preparation holds a bottle and the ready boundary restores the hand" {
        val config = waiterTestConfig().copy(preparationMillis = 10_000)
        val job = OriginWaiterJob(waiterTarget(), 0, 180_000, OriginWaiterStage.PICKUP, 10_000,
            family = OriginWaiterFamily.DRINK_FIRST)
        waiterKitchenHand(config, job, 0) shouldBe Material.GLASS_BOTTLE
        waiterKitchenHand(config, job, 7_800) shouldBe Material.POTION
        waiterKitchenHand(config, job, 10_000) shouldBe null
    }
})
