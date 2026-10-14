package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginDiningReliabilityPolicyTest : FreeSpec({
    "empty tables are refilled before collection and retry backoff is preserved" {
        OriginDiningReliabilityPolicy.serviceQueue(
            linkedMapOf("plate" to 10L, "later" to 30L, "retry" to 200L, "hungry" to 20L),
            setOf("plate"), 100L,
        ) shouldBe listOf("hungry", "later", "plate")
    }

    "delivery route retries once and then exhausts" {
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(0) shouldBe true
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(1) shouldBe false
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(2) shouldBe false
    }

    "ambient rest starts at confirmed arrival" {
        OriginDiningReliabilityPolicy.ambientAvailableAtArrival(
            arrivalAt = 42_000L,
            restMillis = 18_000L,
        ) shouldBe 60_000L
    }

    "home arrival accepts the adjacent bar cell but rejects distant snapped routes" {
        OriginDiningReliabilityPolicy.hasArrivedHome(1.07) shouldBe true
        OriginDiningReliabilityPolicy.hasArrivedHome(1.25) shouldBe true
        OriginDiningReliabilityPolicy.hasArrivedHome(1.251) shouldBe false
        OriginDiningReliabilityPolicy.hasArrivedHome(3.01) shouldBe false
        OriginDiningReliabilityPolicy.hasArrivedHome(Double.NaN) shouldBe false
    }

    "stale waiter callbacks lose ownership after a replacement operation" {
        val oldToken = UUID.randomUUID()
        val newToken = UUID.randomUUID()

        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, oldToken) shouldBe true
        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, newToken) shouldBe false
        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, null) shouldBe false
    }

    "reseat grace ends after eight seconds" {
        val leftAt = 10_000L
        OriginDiningReliabilityPolicy.withinReseatGrace(leftAt, leftAt) shouldBe true
        OriginDiningReliabilityPolicy.withinReseatGrace(
            leftAt + OriginDiningReliabilityPolicy.RESEAT_GRACE_MILLIS - 1,
            leftAt,
        ) shouldBe true
        OriginDiningReliabilityPolicy.withinReseatGrace(
            leftAt + OriginDiningReliabilityPolicy.RESEAT_GRACE_MILLIS,
            leftAt,
        ) shouldBe false
        OriginDiningReliabilityPolicy.withinReseatGrace(leftAt, null) shouldBe false
    }

    "ambient service reserves one available restaurant waiter" {
        OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(setOf(431), emptySet()) shouldBe true
        OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(setOf(431), setOf(432)) shouldBe false
        OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(setOf(432), setOf(431)) shouldBe false
        OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(setOf(431, 432), emptySet()) shouldBe false
        OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(setOf(410), setOf(411, 431, 432)) shouldBe true
    }
})
