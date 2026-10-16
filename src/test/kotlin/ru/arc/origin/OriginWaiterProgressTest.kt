package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.origin.scene.OriginSceneCoordinator
import ru.arc.origin.scene.OriginSceneLease
import java.util.UUID

internal fun waiterTarget(venue: String = "restaurant", guest: Int = 433): OriginWaiterTarget = OriginWaiterTarget(
    OriginSceneLease(UUID.randomUUID(), "player-waiter", "table-$guest", setOf(guest)),
    venue, if (venue == "restaurant") 439 else 405, guest, "Лев", "table-$guest", "egg", "Яичница с травами",
)

class OriginWaiterProgressTest : StringSpec({
    "both venues require guest order then kitchen pickup before a single delivery" {
        for (venue in listOf("restaurant", "brewery")) {
            val progress = OriginWaiterProgress(180_000, 10_000)
            val player = UUID.randomUUID()
            val target = waiterTarget(venue)
            val job = checkNotNull(progress.begin(player, target, 100))
            progress.begin(player, waiterTarget(venue), 101) shouldBe null
            progress.pickup(player, target.staffId, venue, 101) shouldBe
                OriginWaiterStep.Rejected(OriginWaiterRejection.TAKE_ORDER_FIRST)
            (progress.guest(player, target.guestId, venue, 200) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PICKUP
            progress.guest(player, target.guestId, venue, 200) shouldBe
                OriginWaiterStep.Rejected(OriginWaiterRejection.PICKUP_FIRST)
            progress.pickup(player, target.staffId, venue, 10_199) shouldBe
                OriginWaiterStep.Rejected(OriginWaiterRejection.PREPARING)
            (progress.pickup(player, target.staffId, venue, 10_200) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.DELIVER
            progress.pickup(player, target.staffId, venue, 10_201) shouldBe
                OriginWaiterStep.Rejected(OriginWaiterRejection.ALREADY_CARRYING)
            (progress.guest(player, target.guestId, venue, 10_202) as OriginWaiterStep.Changed).job.stage shouldBe OriginWaiterStage.PAYOUT
            progress.guest(player, target.guestId, venue, 10_202) shouldBe
                OriginWaiterStep.Rejected(OriginWaiterRejection.PAYOUT_PENDING)
            progress.finish(player, UUID.randomUUID()) shouldBe null
            progress.finish(player, job.id)?.id shouldBe job.id
            progress.finish(player, job.id) shouldBe null
        }
    }

    "wrong guest kitchen and venue cannot advance or consume the order" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0))
        progress.guest(player, 434, "restaurant", 1) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_GUEST)
        progress.guest(player, 433, "brewery", 1) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.guest(player, 433, null, 1) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.current(player) shouldBe job
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 405, "restaurant", 10_001) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_STAFF)
        progress.pickup(player, 439, "brewery", 10_001) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.WRONG_VENUE)
        progress.current(player)?.stage shouldBe OriginWaiterStage.PICKUP
    }

    "timeout has an exact boundary and cancellation returns the reserved target" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val job = checkNotNull(progress.begin(player, waiterTarget(), 0))
        progress.guest(player, 433, "restaurant", 180_000) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.EXPIRED)
        progress.cancel(player) shouldBe job
        progress.cancel(player) shouldBe null
        progress.guest(player, 433, "restaurant", 180_001) shouldBe OriginWaiterStep.Rejected(OriginWaiterRejection.NO_JOB)
    }

    "quit reload and an old payout callback cannot consume a later job" {
        val progress = OriginWaiterProgress(180_000, 10_000)
        val player = UUID.randomUUID()
        val old = checkNotNull(progress.begin(player, waiterTarget(), 0))
        progress.guest(player, 433, "restaurant", 1)
        progress.pickup(player, 439, "restaurant", 10_001)
        progress.guest(player, 433, "restaurant", 10_002)
        progress.cancel(player)?.id shouldBe old.id
        val next = checkNotNull(progress.begin(player, waiterTarget(), 20_000))
        progress.finish(player, old.id) shouldBe null
        progress.current(player) shouldBe next
        progress.clear() shouldBe listOf(player to next)
        progress.entries() shouldBe emptyList()
    }

    "shared coordinator lease proves exact target ownership and cleanup" {
        val coordinator = OriginSceneCoordinator()
        val old = checkNotNull(coordinator.tryAcquire("player-waiter", "table", setOf(433), 0))
        coordinator.owns(old) shouldBe true
        coordinator.tryAcquire("conversation", "pair", setOf(433, 434), 1) shouldBe null
        coordinator.release(old, 2, 0)
        coordinator.owns(old) shouldBe false
        val next = checkNotNull(coordinator.tryAcquire("player-waiter", "table", setOf(433), 3))
        coordinator.release(old, 4, 0)
        coordinator.owns(next) shouldBe true
        coordinator.clear()
        coordinator.owns(next) shouldBe false
    }
})
