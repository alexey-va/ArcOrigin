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

private fun cleanWaiterMeal(progress: OriginWaiterProgress, player: UUID, request: UUID) {
    val job = checkNotNull(progress.current(player, request))
    var now = job.readyAt
    progress.finishEating(player, request, now)
    progress.guest(player, job.target.guestId, job.target.venueId, now)
    job.washing.forEachIndexed { index, action ->
        progress.wash(player, request, job.target.venueId, action, index, now)
        now += if (action == OriginWaiterWash.SOAK) 3_000 else 700
    }
}

class OriginWaiterProgressTest : StringSpec({
    "focused cooking and assembly remain one chain while background orders become actionable" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val chef = checkNotNull(progress.begin(player, waiterTarget(), 0))
        val assembled = checkNotNull(progress.begin(player, waiterTarget(guest = 434), 0,
            OriginWaiterFamily.ASSEMBLED, recipe = listOf("egg", "herbs")))
        progress.guest(player, 433, "restaurant", 0)
        progress.priority(player, 9_999, chef.id)?.id shouldBe chef.id
        progress.guest(player, 434, "restaurant", 1)
        progress.assemble(player, assembled.id, "restaurant", "egg", 0, 9_000)
        progress.priority(player, 10_000, assembled.id)?.id shouldBe assembled.id
        progress.assemble(player, assembled.id, "restaurant", "herbs", 1, 10_000)
        // Jasper's older ready meal must not replace the meal just assembled by the player.
        progress.priority(player, 10_001, assembled.id)?.id shouldBe assembled.id
        progress.pickup(player, 439, "restaurant", 10_002, chef.id, "egg")
        progress.priority(player, 10_002, assembled.id)?.id shouldBe chef.id
        progress.guest(player, 433, "restaurant", 10_003)
        progress.priority(player, 10_003, chef.id)?.id shouldBe assembled.id
        progress.finishEating(player, chef.id, 28_003)
        progress.priority(player, 28_003, assembled.id)?.id shouldBe assembled.id
        progress.cancel(player, assembled.id)
        progress.priority(player, 28_004, assembled.id)?.id shouldBe chef.id
    }

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
        (progress.guest(player, 434, "restaurant", 10_203) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.EATING
        progress.current(player, first.id)?.stage shouldBe OriginWaiterStage.PICKUP
        cleanWaiterMeal(progress, player, second.id)
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
        (progress.guest(player, 433, "restaurant", 10_102) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.EATING
        progress.pickup(player, 439, "restaurant", 20_102, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.EATING)
        cleanWaiterMeal(progress, player, job.id)
        val main = checkNotNull(progress.current(player, job.id))
        main.stage shouldBe OriginWaiterStage.PICKUP
        main.course shouldBe OriginWaiterCourse.MAIN
        main.dish.id shouldBe "egg"
        progress.finish(player, job.id) shouldBe null
        progress.pickup(player, 439, "restaurant", main.readyAt - 1, job.id, "egg") shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
        progress.pickup(player, 439, "restaurant", main.readyAt, job.id, "egg")
        (progress.guest(player, 433, "restaurant", main.readyAt + 1) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.EATING
        cleanWaiterMeal(progress, player, job.id)
        progress.finish(player, job.id)?.id shouldBe job.id
        progress.finish(player, job.id) shouldBe null
    }

    "previous ambient plate must be collected and washed before the new order with no extra payment" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val target = waiterTarget().copy(previousPlate = OriginWaiterDish("fish", "Запечённая рыба"))
        val job = checkNotNull(progress.begin(player, target, 0))
        job.stage shouldBe OriginWaiterStage.COLLECT
        job.dish.id shouldBe "fish"
        progress.pickup(player, 439, "restaurant", 1, job.id, "egg") shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.COLLECT_FIRST)
        progress.guest(player, 433, "restaurant", 1)
        job.washing.forEachIndexed { index, action -> progress.wash(player, job.id, "restaurant", action, index, 2L + index * 700) }
        progress.finish(player, job.id) shouldBe null
        val cleared = checkNotNull(progress.current(player, job.id))
        cleared.id shouldBe job.id
        cleared.stage shouldBe OriginWaiterStage.TAKE_ORDER
        cleared.clearingPreviousMeal shouldBe false
        cleared.dish.id shouldBe "egg"
        cleared.washed shouldBe 0
        progress.guest(player, 433, "restaurant", 3_000)
        progress.pickup(player, 439, "restaurant", 13_000, job.id, "egg")
        progress.guest(player, 433, "restaurant", 13_001)
        cleanWaiterMeal(progress, player, job.id)
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
        (progress.guest(player, 433, "restaurant", 20_102) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.EATING
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
        (progress.guest(player, 433, "restaurant", 50_003) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.EATING
        cleanWaiterMeal(progress, player, job.id)
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
        cleanWaiterMeal(progress, player, old.id)
        progress.refresh(player, 500_000) shouldBe emptyList()
        progress.current(player, old.id)?.stage shouldBe OriginWaiterStage.PAYOUT
        progress.finish(player, old.id)?.id shouldBe old.id
        val next = checkNotNull(progress.begin(player, waiterTarget(), 500_000))
        progress.finish(player, old.id) shouldBe null
        progress.current(player) shouldBe next
        progress.clear() shouldBe listOf(player to next)
        progress.entries() shouldBe emptyList()
    }

    "assembly is ordered and replayed or rapid actions cannot skip ingredients" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, OriginWaiterFamily.ASSEMBLED,
            recipe = listOf("egg", "bacon", "herbs")))
        progress.guest(player, 433, "restaurant", 1)
        progress.assemble(player, job.id, "restaurant", "bacon", 0, 10_001) shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_INGREDIENT)
        progress.assemble(player, job.id, "restaurant", "egg", 0, 10_001)
        progress.assemble(player, job.id, "restaurant", "egg", 0, 10_701) shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.STALE_ACTION)
        progress.assemble(player, job.id, "restaurant", "bacon", 1, 10_700) shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
        progress.assemble(player, job.id, "restaurant", "bacon", 1, 10_701)
        val ready = progress.assemble(player, job.id, "restaurant", "herbs", 2, 11_401) as OriginWaiterStep.Changed
        ready.job.stage shouldBe OriginWaiterStage.PICKUP
        ready.job.assembled shouldBe 3
        ready.job.id shouldBe job.id
        progress.finish(player, job.id) shouldBe null
    }

    "serving eating collection and ordered soak rinse dry retain one unpaid ticket" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val wash = listOf(OriginWaiterWash.SOAK, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY)
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0, washing = wash))
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 439, "restaurant", 10_001)
        progress.guest(player, 433, "restaurant", 10_002)
        progress.finish(player, job.id) shouldBe null
        progress.refresh(player, 28_002) shouldBe emptyList()
        progress.current(player, job.id)?.stage shouldBe OriginWaiterStage.EATING
        progress.finishEating(player, job.id, 28_001) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.EATING)
        progress.finishEating(player, job.id, 28_002)
        progress.guest(player, 433, "restaurant", 28_003)
        progress.carried(player)?.stage shouldBe OriginWaiterStage.RETURN_DISHES
        progress.wash(player, job.id, "restaurant", OriginWaiterWash.DRY, 0, 28_004) shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_WASH_STEP)
        progress.wash(player, job.id, "restaurant", OriginWaiterWash.SOAK, 0, 28_004)
        progress.carried(player) shouldBe null
        progress.wash(player, job.id, "restaurant", OriginWaiterWash.SOAP, 1, 31_003) shouldBe
            OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
        progress.wash(player, job.id, "restaurant", OriginWaiterWash.SOAP, 1, 31_004)
        progress.wash(player, job.id, "restaurant", OriginWaiterWash.RINSE, 2, 31_704)
        progress.finish(player, job.id) shouldBe null
        (progress.wash(player, job.id, "restaurant", OriginWaiterWash.DRY, 3, 32_404) as OriginWaiterStep.Changed)
            .job.stage shouldBe OriginWaiterStage.PAYOUT
        progress.finish(player, job.id)?.id shouldBe job.id
        progress.finish(player, job.id) shouldBe null
    }

    "a peaceful dispute requires both sides water to the right guest and reassurance without a payout" {
        for (kind in OriginWaiterDisputeKind.entries) {
            val progress = OriginWaiterProgress(180_000, 10_000)
            val player = UUID.randomUUID()
            val first = checkNotNull(progress.begin(player, waiterTarget(), 0, hardStopAt = 190_000))
            progress.begin(player, waiterTarget(guest = 434), 0, hardStopAt = 190_000)
            progress.guest(player, 433, "restaurant", 1)
            progress.guest(player, 434, "restaurant", 1)
            checkNotNull(progress.beginDispute(player, kind, 2))
            progress.calm(player, 434, 3) shouldBe false
            progress.guest(player, 433, "restaurant", 3) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.DISPUTE)
            progress.calm(player, 433, 1_000) shouldBe true
            progress.calm(player, 434, 2_000) shouldBe true
            progress.calm(player, 439, 3_000) shouldBe true
            val waterGuest = if (kind == OriginWaiterDisputeKind.SPILLED_DRINK) 433 else 434
            val otherGuest = if (waterGuest == 433) 434 else 433
            progress.calm(player, otherGuest, 4_000) shouldBe false
            progress.dispute(player)?.stage shouldBe OriginWaiterDisputeStage.BRING_WATER
            progress.calm(player, waterGuest, 4_000) shouldBe true
            progress.calm(player, otherGuest, 5_000) shouldBe true
            progress.dispute(player) shouldBe null
            progress.current(player, first.id)?.readyAt shouldBe 14_999
            progress.entries().all { it.second.stage != OriginWaiterStage.PAYOUT } shouldBe true
        }
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
