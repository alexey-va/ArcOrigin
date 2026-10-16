package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random

private fun offer(variant: OriginOddJobVariant, seed: Int = 8, urgent: Boolean = false) = OriginOddJobOffer(UUID.randomUUID(), variant, seed, urgent)
private fun OriginOddJobStep.reason() = (this as OriginOddJobStep.Rejected).reason

class OriginOddJobsProgressTest : StringSpec({
    "reopening the board cannot reroll offers and every three acceptances cover all families" {
        for (kind in OriginOddJobKind.entries) {
            val board = OriginOddJobDispatch(kind, Random(7))
            repeat(3) {
                val accepted = (0..2).map {
                    val shown = board.offers(OriginWorkShiftPhase.RUSH)
                    board.offers(OriginWorkShiftPhase.CLOSING) shouldBe shown
                    board.accept(UUID.randomUUID()) shouldBe null
                    checkNotNull(board.accept(shown.first().id)).variant
                }
                accepted.toSet() shouldBe OriginOddJobVariant.entries.filter { it.kind == kind }.toSet()
            }
        }
    }
    "water service completes three compartments and repeated cleaning cannot replace distinct targets" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID(); var now = 0L
        jobs.begin(id, offer(OriginOddJobVariant.YARD_WATER), now)
        repeat(3) { compartment ->
            jobs.current(id)!!.yard!!.waterCompartment shouldBe compartment
            jobs.act(id, OriginOddJobAction.WATER, ++now)
            jobs.act(id, OriginOddJobAction.WATER, ++now, 0)
            jobs.act(id, OriginOddJobAction.WATER, ++now, 0).reason() shouldBe OriginOddJobRejection.WRONG_ZONE
            jobs.act(id, OriginOddJobAction.WATER, ++now, 1)
            jobs.act(id, OriginOddJobAction.WATER, ++now, 2)
            jobs.act(id, OriginOddJobAction.WATER, ++now)
            now += jobs.current(id)!!.yard!!.waterTarget * ODD_JOB_WATER_STEP_MILLIS
            jobs.act(id, OriginOddJobAction.WATER, now)
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.act(id, OriginOddJobAction.RETURN, ++now)
        val request = jobs.current(id)!!.id
        jobs.finish(id, request)!!.stage shouldBe OriginOddJobStage.PAYOUT
        jobs.finish(id, request) shouldBe null
    }
    "caravan bad final load keeps carried bag and can rebalance the existing prefix" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.YARD_CARAVAN), 0)
        repeat(3) { jobs.choose(id, 8 % 3, 1); jobs.choose(id, 0, 2) }
        jobs.choose(id, 8 % 3, 3)
        val before = jobs.current(id)!!.yard!!
        jobs.choose(id, 1, 4).reason() shouldBe OriginOddJobRejection.UNBALANCED
        jobs.current(id)!!.yard shouldBe before
        jobs.choose(id, 2, 5)
        jobs.current(id)!!.yard!!.loads shouldBe listOf(0, 0, 1)
        jobs.choose(id, 1, 6)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
    }
    "rations reject mould and overfeeding instead of allowing one target to fill both sides" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.YARD_RATIONS), 0)
        jobs.choose(id, 0, 1).reason() shouldBe OriginOddJobRejection.WRONG_RATION
        jobs.choose(id, 2, 2)
        repeat(2) { jobs.act(id, OriginOddJobAction.FEED, 3, 0) }
        jobs.act(id, OriginOddJobAction.FEED, 4, 0).reason() shouldBe OriginOddJobRejection.WRONG_RATION
        jobs.current(id)!!.yard!!.filled shouldBe listOf(2, 0)
        jobs.act(id, OriginOddJobAction.FEED, 5, 2)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_HAY
    }
    "forge orders use direct stations and a few forgiving strikes without craft choices or a final check" {
        for ((variant, strikes) in listOf(OriginOddJobVariant.FORGE_NEW to 4,
            OriginOddJobVariant.FORGE_REPAIR to 2, OriginOddJobVariant.FORGE_PAIR to 2)) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID(); var now = 0L
            jobs.begin(id, offer(variant), now)
            jobs.current(id)!!.forge!!.remainingStrikes shouldBe strikes
            jobs.choose(id, 0, ++now).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.HAMMER, ++now).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.BLANK, ++now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.FORGE_HEAT
            jobs.act(id, OriginOddJobAction.HEAT, ++now)
            jobs.act(id, OriginOddJobAction.HEAT, ++now).reason() shouldBe OriginOddJobRejection.TOO_EARLY
            // Pausing at the fire or anvil must never overheat, cool or damage the work.
            now += 60_000
            jobs.act(id, OriginOddJobAction.HEAT, now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.FORGE_PLACE
            jobs.act(id, OriginOddJobAction.PLACE, ++now)
            jobs.act(id, OriginOddJobAction.HAMMER, ++now).reason() shouldBe OriginOddJobRejection.TOO_EARLY
            repeat(strikes) {
                now += 30_000
                (jobs.act(id, OriginOddJobAction.HAMMER, now) is OriginOddJobStep.Changed) shouldBe true
            }
            val done = jobs.current(id)!!
            done.forge!!.shape shouldBe done.forge.target
            done.quality shouldBe 100
            done.stage shouldBe OriginOddJobStage.FORGE_QUENCH
            jobs.act(id, OriginOddJobAction.HAMMER, ++now).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.QUENCH, ++now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
            jobs.act(id, OriginOddJobAction.RETURN, ++now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.PAYOUT
        }
    }
    "forge dispatch never adds a hidden rush penalty" {
        val dispatch = OriginOddJobDispatch(OriginOddJobKind.FORGE, Random(7))
        repeat(6) {
            val offers = dispatch.offers(OriginWorkShiftPhase.RUSH)
            offers.all { !it.urgent } shouldBe true
            dispatch.accept(offers.first().id)
        }
    }
    "auction checks serial and condition, amendment visits Greta, grouped packing orders heavy below fragile" {
        for (variant in listOf(OriginOddJobVariant.AUCTION_DISPATCH, OriginOddJobVariant.AUCTION_AMEND, OriginOddJobVariant.AUCTION_GROUP)) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID(); var now = 0L
            jobs.begin(id, offer(variant), now)
            val count = jobs.current(id)!!.auction!!.lots.size
            repeat(count) {
                val job = jobs.current(id)!!; val correct = originAuctionCandidates(job).indexOf(job.auction!!.lot)
                jobs.choose(id, (correct + 1) % 3, ++now).reason() shouldBe OriginOddJobRejection.WRONG_ITEM
                jobs.choose(id, correct, ++now)
                if (variant == OriginOddJobVariant.AUCTION_AMEND) {
                    jobs.choose(id, 0, ++now).reason() shouldBe OriginOddJobRejection.MANIFEST_MISMATCH
                    jobs.choose(id, 1, ++now)
                    jobs.act(id, OriginOddJobAction.HANDOFF, ++now)
                    jobs.act(id, OriginOddJobAction.RETURN, ++now)
                }
                jobs.choose(id, 0, ++now)
                jobs.choose(id, jobs.current(id)!!.parcel!!.packing.ordinal, ++now)
            }
            if (variant == OriginOddJobVariant.AUCTION_GROUP) {
                jobs.choose(id, 0, ++now).reason() shouldBe OriginOddJobRejection.WRONG_PACKING
                jobs.choose(id, 1, ++now); jobs.choose(id, 0, ++now)
            }
            jobs.act(id, OriginOddJobAction.HANDOFF, ++now)
            jobs.choose(id, 0, ++now).reason() shouldBe OriginOddJobRejection.WRONG_RECEIPT
            jobs.choose(id, 2, ++now)
            jobs.act(id, OriginOddJobAction.RETURN, ++now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.PAYOUT
        }
    }
    "hard stop cannot be extended by a new order and stale receipt cannot remove its replacement" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        val old = jobs.begin(id, offer(OriginOddJobVariant.AUCTION_DISPATCH), 100, 150)!!
        jobs.choose(id, 0, 150).reason() shouldBe OriginOddJobRejection.EXPIRED
        jobs.cancel(id)
        val next = jobs.begin(id, offer(OriginOddJobVariant.YARD_WATER), 151)!!
        jobs.finish(id, old.id) shouldBe null
        jobs.current(id) shouldBe next
    }
})
