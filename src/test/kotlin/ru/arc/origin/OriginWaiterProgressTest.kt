package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.origin.scene.OriginSceneCoordinator
import ru.arc.origin.scene.OriginSceneLease
import java.util.UUID

internal fun waiterTarget(venue: String = "restaurant", guest: Int = 433): OriginWaiterTarget = OriginWaiterTarget(
    OriginSceneLease(UUID.randomUUID(), "player-waiter", "table-$guest", setOf(guest)),
    venue, if (venue == "restaurant") 439 else 405, guest, "Гость $guest", "table-$guest", "egg", "Яичница с травами",
    OriginWaiterDish("herbal_tea", "Пиво"), OriginWaiterDish("fish", "Запечённая рыба"),
)

class OriginWaiterProgressTest : StringSpec({
    "two independent tickets permit a chosen ready order but never two carried dishes" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val first = checkNotNull(progress.begin(player, waiterTarget(), 0))
        val second = checkNotNull(progress.begin(player, waiterTarget(guest = 434), 1))
        progress.begin(player, waiterTarget(guest = 435), 2) shouldBe null
        progress.guest(player, 433, "restaurant", 100)
        progress.priority(player, 100)?.id shouldBe second.id
        progress.guest(player, 434, "restaurant", 200)
        progress.priority(player, 10_200)?.id shouldBe first.id
        // The older dish is suggested, but the player may deliberately carry the other one first.
        (progress.pickup(player, 439, "restaurant", 10_200, second.id, "egg") as OriginWaiterStep.Changed).job.id shouldBe second.id
        progress.priority(player, 10_200)?.id shouldBe second.id
        progress.pickup(player, 439, "restaurant", 10_201, first.id, "egg") shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.ALREADY_CARRYING)
        progress.guest(player, 435, "restaurant", 10_202) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_GUEST)
        progress.carried(player)?.id shouldBe second.id
        (progress.guest(player, 434, "restaurant", 10_203) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PAYOUT
        progress.current(player, first.id)?.stage shouldBe OriginWaiterStage.PICKUP
        progress.finish(player, second.id)?.id shouldBe second.id
        progress.finish(player, second.id) shouldBe null
        progress.tickets(player).map { it.id } shouldBe listOf(first.id)
    }

    "a drink must reach the same guest before the main course and only the full order is payable" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, OriginWaiterFamily.DRINK_FIRST))
        progress.pickup(player, 439, "restaurant", 1, job.id, "egg") shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.TAKE_ORDER_FIRST)
        progress.guest(player, 433, "restaurant", 100)
        progress.pickup(player, 439, "restaurant", 10_100, job.id, "egg") shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_DISH)
        val drink = (progress.pickup(player, 439, "restaurant", 10_100, job.id, "herbal_tea") as OriginWaiterStep.Changed).job
        drink.servingTarget().dishId shouldBe "herbal_tea"
        progress.guest(player, 434, "restaurant", 10_101) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_GUEST)
        val main = (progress.guest(player, 433, "restaurant", 10_102) as OriginWaiterStep.Changed).job
        main.stage shouldBe OriginWaiterStage.PICKUP
        main.course shouldBe OriginWaiterCourse.MAIN
        main.dish.id shouldBe "egg"
        progress.finish(player, job.id) shouldBe null
        progress.pickup(player, 439, "restaurant", 20_101, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
        progress.pickup(player, 439, "restaurant", 20_102, job.id, "egg")
        (progress.guest(player, 433, "restaurant", 20_103) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PAYOUT
        progress.finish(player, job.id)?.id shouldBe job.id
        progress.finish(player, job.id) shouldBe null
    }

    "revised orders require a guest instruction and the matching replacement at the kitchen" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, OriginWaiterFamily.REVISED))
        progress.guest(player, 433, "restaurant", 100)
        progress.pickup(player, 439, "restaurant", 10_100, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.CLARIFY_FIRST)
        progress.current(player, job.id)?.stage shouldBe OriginWaiterStage.CLARIFY
        val clarified = (progress.guest(player, 433, "restaurant", 10_101) as OriginWaiterStep.Changed).job
        clarified.dish.id shouldBe "fish"
        progress.pickup(player, 439, "restaurant", 20_101, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_DISH)
        progress.current(player, job.id)?.stage shouldBe OriginWaiterStage.PICKUP
        (progress.pickup(player, 439, "restaurant", 20_101, job.id, "fish") as OriginWaiterStep.Changed).job.servingTarget().dishId shouldBe "fish"
        (progress.guest(player, 433, "restaurant", 20_102) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PAYOUT
    }

    "an expired carried dish needs a fresh kitchen preparation without creating another paid order" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, freshnessMillis = 30_000))
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 439, "restaurant", 10_001, job.id, "egg")
        progress.guest(player, 433, "restaurant", 40_001) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.REWORK_REQUIRED)
        progress.carried(player) shouldBe null
        progress.finish(player, job.id) shouldBe null
        val remake = (progress.remake(player, job.id, 439, "restaurant", 40_002) as OriginWaiterStep.Changed).job
        remake.id shouldBe job.id
        remake.reworks shouldBe 1
        remake.quality shouldBe 85
        progress.pickup(player, 439, "restaurant", 50_001, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
        progress.pickup(player, 439, "restaurant", 50_002, job.id, "egg")
        (progress.guest(player, 433, "restaurant", 50_003) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PAYOUT
        progress.finish(player, job.id)?.id shouldBe job.id
    }

    "wrong venue staff and exact timeout boundaries do not advance a ticket" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, hardStopAt = 60_000))
        progress.begin(player, waiterTarget(venue = "brewery", guest = 416), 1) shouldBe null
        progress.guest(player, 433, "brewery", 1) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.guest(player, 433, null, 1) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 405, "restaurant", 10_001, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_STAFF)
        progress.pickup(player, 439, "brewery", 10_001, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.guest(player, 433, "restaurant", 60_000) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.EXPIRED)
        progress.cancel(player, job.id)?.id shouldBe job.id
        progress.current(player) shouldBe null
    }

    "completed payout is not expired and an old callback cannot consume a replacement ticket" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val old = checkNotNull(progress.begin(player, waiterTarget(), 0))
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 439, "restaurant", 10_001)
        progress.guest(player, 433, "restaurant", 10_002)
        progress.refresh(player, 500_000) shouldBe emptyList()
        progress.current(player, old.id)?.stage shouldBe OriginWaiterStage.PAYOUT
        progress.finish(player, old.id)?.id shouldBe old.id
        val next = checkNotNull(progress.begin(player, waiterTarget(), 500_000))
        progress.finish(player, old.id) shouldBe null
        progress.current(player) shouldBe next
        progress.clear() shouldBe listOf(player to next)
        progress.entries() shouldBe emptyList()
    }

    "shared coordinator exact-lease cleanup does not release a later owner" {
        val coordinator = OriginSceneCoordinator()
        val old = checkNotNull(coordinator.tryAcquire("player-waiter", "table", setOf(433), 0))
        coordinator.owns(old) shouldBe true
        coordinator.tryAcquire("conversation", "pair", setOf(433, 434), 1) shouldBe null
        coordinator.release(old, 2, 0)
        val next = checkNotNull(coordinator.tryAcquire("player-waiter", "table", setOf(433), 3))
        coordinator.release(old, 4, 0)
        coordinator.owns(next) shouldBe true
        coordinator.clear()
        coordinator.owns(next) shouldBe false
    }
})
