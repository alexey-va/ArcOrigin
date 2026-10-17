package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random

private fun offer(variant: OriginOddJobVariant, seed: Int = 8, urgent: Boolean = false) = OriginOddJobOffer(UUID.randomUUID(), variant, seed, urgent)
private fun OriginOddJobStep.reason() = (this as OriginOddJobStep.Rejected).reason

class OriginOddJobsProgressTest : StringSpec({
    "the first forge assignment always teaches a raw blank before repair variants" {
        repeat(20) { seed ->
            val dispatch = OriginOddJobDispatch(OriginOddJobKind.FORGE, Random(seed))
            dispatch.offers(OriginWorkShiftPhase.OPENING).first().variant shouldBe OriginOddJobVariant.FORGE_NEW
        }
    }
    "reopening the employer cannot reroll offers and each bag covers all families" {
        for (kind in OriginOddJobKind.entries.filterNot { it == OriginOddJobKind.FORGE }) {
            val board = OriginOddJobDispatch(kind, Random(7))
            repeat(3) {
                val accepted = OriginOddJobVariant.entries.filter { it.kind == kind }.map {
                    val shown = board.offers(OriginWorkShiftPhase.RUSH)
                    board.offers(OriginWorkShiftPhase.CLOSING) shouldBe shown
                    board.accept(UUID.randomUUID()) shouldBe null
                    checkNotNull(board.accept(shown.first().id)).variant
                }
                accepted.toSet() shouldBe OriginOddJobVariant.entries.filter { it.kind == kind }.toSet()
            }
        }
    }
    "smith rotates every activity before repeating it and each craft keeps its own non-repeating variant bag" {
        repeat(20) { seed ->
            val board = OriginOddJobDispatch(OriginOddJobKind.FORGE, Random(seed))
            val selected = List(36) {
                val shown = board.offers(OriginWorkShiftPhase.OPENING)
                board.offers(OriginWorkShiftPhase.CLOSING) shouldBe shown
                board.accept(shown.first().id)!!
            }
            selected.first().variant shouldBe OriginOddJobVariant.FORGE_NEW
            selected.chunked(3).forEach { batch -> batch.map { it.kind }.toSet() shouldBe
                setOf(OriginOddJobKind.FORGE, OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE) }
            selected.zipWithNext().none { (a, b) -> a.kind == b.kind } shouldBe true
            selected.groupBy { it.kind }.forEach { (kind, orders) ->
                val variants = OriginOddJobVariant.entries.filter { it.kind == kind }.toSet()
                orders.chunked(variants.size).filter { it.size == variants.size }.forEach { bag ->
                    bag.map { it.variant }.toSet() shouldBe variants
                }
                orders.zipWithNext().none { (a, b) -> a.variant == b.variant } shouldBe true
            }
        }
        val auction = OriginOddJobDispatch(OriginOddJobKind.AUCTION, Random(4)).offers(OriginWorkShiftPhase.RUSH)
        auction.size shouldBe 2
        auction.map { it.variant }.distinct().size shouldBe 2
        auction.map { it.urgent } shouldBe listOf(false, true)
    }
    "water and feed wait for the animal and saddling requires leading grooming and handoff in order" {
        val chains = mapOf(
            OriginOddJobVariant.YARD_WATER to listOf(OriginOddJobAction.WATER, OriginOddJobAction.WATER,
                OriginOddJobAction.WATER, OriginOddJobAction.CARE_DONE),
            OriginOddJobVariant.YARD_RATIONS to listOf(OriginOddJobAction.HAY, OriginOddJobAction.FEED, OriginOddJobAction.CARE_DONE),
            OriginOddJobVariant.YARD_SADDLE to listOf(OriginOddJobAction.TACK, OriginOddJobAction.LEAD, OriginOddJobAction.ARRIVE,
                OriginOddJobAction.BRUSH, OriginOddJobAction.GROOM, OriginOddJobAction.GROOM, OriginOddJobAction.GROOM,
                OriginOddJobAction.TACK, OriginOddJobAction.SADDLE, OriginOddJobAction.ARRIVE,
                OriginOddJobAction.HANDOFF, OriginOddJobAction.CARE_DONE),
        )
        for ((variant, actions) in chains) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
            val job = jobs.begin(id, offer(variant), 0, yardTarget = OriginYardWork(422, "Буран"))!!
            actions.forEachIndexed { index, action ->
                jobs.act(id, OriginOddJobAction.RETURN, index * 1_000L).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
                (jobs.act(id, action, index * 1_000L + 1) is OriginOddJobStep.Changed) shouldBe true
            }
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
            jobs.current(id)!!.yard!!.animalId shouldBe 422
            jobs.current(id)!!.quality shouldBe 100
            jobs.act(id, OriginOddJobAction.RETURN, 60_002)
            jobs.finish(id, job.id)!!.stage shouldBe OriginOddJobStage.PAYOUT
            jobs.finish(id, job.id) shouldBe null
        }
    }
    "grooming requires taking the brush and three ordinary untimed strokes" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.YARD_GROOM), 0, yardTarget = OriginYardWork(423, "Рыжик"))
        jobs.act(id, OriginOddJobAction.GROOM, 1).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.BRUSH, 2)
        repeat(ODD_JOB_GROOM_STROKES) { index ->
            jobs.act(id, OriginOddJobAction.GROOM, 30_000L * (index + 1))
            jobs.current(id)!!.yard!!.brushStrokes shouldBe index + 1
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.current(id)!!.yard!!.animalId shouldBe 423
        jobs.current(id)!!.quality shouldBe 100
    }
    "stable assignments never include a hidden rush penalty" {
        val dispatch = OriginOddJobDispatch(OriginOddJobKind.MOUNTYARD, Random(7))
        repeat(8) {
            val offers = dispatch.offers(OriginWorkShiftPhase.RUSH)
            offers.all { !it.urgent } shouldBe true
            dispatch.accept(offers.first().id)
        }
    }
    "forge orders use direct stations and a few forgiving strikes without craft choices or a final check" {
        for ((variant, strikes) in listOf(OriginOddJobVariant.FORGE_NEW to 4,
            OriginOddJobVariant.FORGE_REPAIR to 2, OriginOddJobVariant.FORGE_PAIR to 2,
            OriginOddJobVariant.FORGE_HOOK to 3, OriginOddJobVariant.FORGE_HINGE to 3, OriginOddJobVariant.FORGE_BRACKET to 3)) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID(); var now = 0L
            jobs.begin(id, offer(variant), now)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.FORGE_BLANK
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
    "hauling loads then pushes then unloads and returns its empty cart before one forge reward" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.HAUL_COPPER), 0)
        jobs.current(id)!!.haul!!.cargo shouldBe OriginForgeCargo.COPPER
        jobs.act(id, OriginOddJobAction.DELIVER, 1).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        repeat(3) { index ->
            jobs.act(id, OriginOddJobAction.PICKUP, index * 10_000L + 2)
            jobs.act(id, OriginOddJobAction.PICKUP, index * 10_000L + 3).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.DELIVER, index * 10_000L + 4).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.LOAD, index * 10_000L + 5)
            jobs.current(id)!!.haul!!.loaded shouldBe index + 1
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
        jobs.act(id, OriginOddJobAction.PICKUP, 30_000).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 30_001)
        repeat(3) { index ->
            jobs.act(id, OriginOddJobAction.DELIVER, 40_000L + index * 10_000).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.PICKUP, 40_001L + index * 10_000)
            jobs.act(id, OriginOddJobAction.DELIVER, 40_002L + index * 10_000)
            jobs.current(id)!!.haul!!.delivered shouldBe index + 1
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_RETURN_CART
        jobs.act(id, OriginOddJobAction.RETURN, 70_000).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 70_001)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        listOf(OriginOddJobKind.FORGE, OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE)
            .map { it.rewardKind }.toSet() shouldBe setOf(OriginSideJobKind.FORGE)
    }
    "engraving keeps the completed letters on the anvil until explicitly collected" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.ENGRAVE_CAT), 0)
        jobs.act(id, OriginOddJobAction.BLANK, 1)
        jobs.act(id, OriginOddJobAction.PLACE, 2)
        jobs.act(id, OriginOddJobAction.ENGRAVE, 3).reason() shouldBe OriginOddJobRejection.TOO_EARLY
        repeat(6) { jobs.act(id, OriginOddJobAction.ENGRAVE, 2L + (it + 1) * 600) }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.ENGRAVE_COLLECT
        jobs.current(id)!!.engraving!!.word shouldBe "КОТ"
        jobs.current(id)!!.engraving!!.strokes shouldBe 6
        jobs.act(id, OriginOddJobAction.ENGRAVE, 4_000).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.RETURN, 4_001).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.COLLECT, 4_002)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.current(id)!!.quality shouldBe 100
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
