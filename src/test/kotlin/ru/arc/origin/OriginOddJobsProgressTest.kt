package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random

private fun offer(variant: OriginOddJobVariant, seed: Int = 8, urgent: Boolean = false) = OriginOddJobOffer(UUID.randomUUID(), variant, seed, urgent)
private fun OriginOddJobStep.reason() = (this as OriginOddJobStep.Rejected).reason
private fun OriginOddJobsProgress.yardAct(id: UUID, action: OriginOddJobAction, now: Long) =
    act(id, action, now, if (current(id)?.stage == OriginOddJobStage.YARD_TIE_SECOND) 0 else 1)

private val yardTackActions = listOf(OriginOddJobAction.GEAR, OriginOddJobAction.TACK, OriginOddJobAction.SADDLE)
private val yardPreparationActions = listOf(OriginOddJobAction.TACK, OriginOddJobAction.LEAD, OriginOddJobAction.ARRIVE,
    OriginOddJobAction.TIE, OriginOddJobAction.TIE, OriginOddJobAction.TIE, OriginOddJobAction.TIE,
    OriginOddJobAction.BRUSH, OriginOddJobAction.GROOM, OriginOddJobAction.GROOM, OriginOddJobAction.GROOM) +
    yardTackActions + listOf(OriginOddJobAction.ARRIVE, OriginOddJobAction.HANDOFF)

class OriginOddJobsProgressTest : StringSpec({
    "a busy rider allows water for a free horse without exhausting the short work pool" {
        val board = OriginOddJobDispatch(OriginOddJobKind.MOUNTYARD, Random(4))
        for (variant in listOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER)) {
            val offer = board.offers(OriginWorkShiftPhase.OPENING).first { it.variant == variant }
            board.accept(offer.id) shouldBe offer
        }
        val selected = board.nextAvailable(OriginWorkShiftPhase.OPENING) { it.variant == OriginOddJobVariant.YARD_WATER }!!
        selected.variant shouldBe OriginOddJobVariant.YARD_WATER
        board.offers(OriginWorkShiftPhase.CLOSING).count { it.id == selected.id } shouldBe 1
        board.accept(selected.id) shouldBe selected
        board.nextAvailable(OriginWorkShiftPhase.OPENING) { false } shouldBe null
        board.offers(OriginWorkShiftPhase.OPENING).map { it.variant }.toSet() shouldBe
            OriginOddJobVariant.entries.filter { it.kind == OriginOddJobKind.MOUNTYARD }.toSet()
    }
    "stable preparation has one weight against six per short variant and leaves every variant eligible" {
        val starts = List(2_100) { seed -> OriginOddJobDispatch(OriginOddJobKind.MOUNTYARD, Random(seed))
            .nextAvailable(OriginWorkShiftPhase.OPENING) { true }!! }
        starts.map { it.variant }.toSet() shouldBe OriginOddJobVariant.entries.filter { it.kind == OriginOddJobKind.MOUNTYARD }.toSet()
        (starts.count { it.variant.isYardPreparation() } in 240..360) shouldBe true
    }
    "stable preparation leaves three runnable short orders before another long family order" {
        val board = OriginOddJobDispatch(OriginOddJobKind.MOUNTYARD, Random(17))
        var shorts = 3
        repeat(300) {
            val selected = board.nextAvailable(OriginWorkShiftPhase.OPENING) { true }!!
            if (selected.variant.isYardPreparation()) {
                (shorts >= 3) shouldBe true
                shorts = 0
            } else shorts++
            board.accept(selected.id) shouldBe selected
        }
    }
    "stable preparation gap yields only when no short order can run" {
        val board = OriginOddJobDispatch(OriginOddJobKind.MOUNTYARD, Random(4))
        val first = board.nextAvailable(OriginWorkShiftPhase.OPENING) { it.variant.isYardPreparation() }!!
        board.accept(first.id)
        val fallback = board.nextAvailable(OriginWorkShiftPhase.OPENING) { it.variant == OriginOddJobVariant.YARD_LESSON }!!
        fallback.variant shouldBe OriginOddJobVariant.YARD_LESSON
        board.offers(OriginWorkShiftPhase.CLOSING).first { it.id == fallback.id } shouldBe fallback
    }
    "new forge shifts randomize the starting craft and its variant" {
        val starts = List(120) { seed ->
            OriginOddJobDispatch(OriginOddJobKind.FORGE, Random(seed))
                .nextAvailable(OriginWorkShiftPhase.OPENING) { true }!!
        }
        starts.map { it.kind }.toSet() shouldBe
            setOf(OriginOddJobKind.FORGE, OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE)
        for (kind in starts.map { it.kind }.toSet()) {
            starts.filter { it.kind == kind }.map { it.variant }.toSet() shouldBe
                OriginOddJobVariant.entries.filter { it.kind == kind }.toSet()
        }
    }
    "reopening the employer cannot reroll offers and each bag covers all families" {
        for (kind in OriginOddJobKind.entries.filterNot { it in setOf(OriginOddJobKind.FORGE, OriginOddJobKind.MOUNTYARD) }) {
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
    "physical actions earn once while orders finish only after every loan is returned" {
        for (variant in listOf(OriginOddJobVariant.YARD_WATER, OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE)) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
            val job = jobs.begin(id, offer(variant), 0, yardTarget = OriginYardWork(422, "Буран"))!!
            val actions = when (variant) {
                OriginOddJobVariant.YARD_WATER -> listOf(OriginOddJobAction.WATER, OriginOddJobAction.WATER,
                    OriginOddJobAction.WATER, OriginOddJobAction.CARE_DONE, OriginOddJobAction.GEAR)
                OriginOddJobVariant.YARD_RATIONS -> listOf(OriginOddJobAction.HAY, OriginOddJobAction.FEED)
                else -> yardPreparationActions + OriginOddJobAction.CARE_DONE
            }
            val units = mutableListOf<UUID>()
            actions.forEachIndexed { index, action ->
                jobs.act(id, OriginOddJobAction.RETURN, index * 1_000L).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
                val step = jobs.yardAct(id, action, index * 1_000L + 1) as OriginOddJobStep.Changed
                step.workUnitId?.let(units::add)
                jobs.finish(id, job.id) shouldBe null
            }
            val expected = when (variant) {
                OriginOddJobVariant.YARD_WATER -> 4
                OriginOddJobVariant.YARD_RATIONS -> 2
                else -> 13
            }
            units.size shouldBe expected
            units.distinct().size shouldBe expected
            jobs.current(id)!!.completedActions shouldBe expected
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
            jobs.current(id)!!.yard!!.borrowed shouldBe emptySet()
            jobs.current(id)!!.yard!!.equipped shouldBe emptySet()
            jobs.current(id)!!.quality shouldBe 100
            (jobs.act(id, OriginOddJobAction.RETURN, 60_002) as OriginOddJobStep.Changed).workUnitId shouldBe null
            jobs.finish(id, job.id)!!.stage shouldBe OriginOddJobStage.PAYOUT
            jobs.finish(id, job.id) shouldBe null
        }
    }
    "either wall can be chosen first and a carried end must attach before the brush is available" {
        for (first in 0..1) {
            val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
            jobs.begin(id, offer(OriginOddJobVariant.YARD_GROOM), 0, yardTarget = OriginYardWork(422, "Буран"))
            listOf(OriginOddJobAction.TACK, OriginOddJobAction.LEAD, OriginOddJobAction.ARRIVE).forEach { jobs.act(id, it, 1) }
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_TIE_FIRST
            jobs.act(id, OriginOddJobAction.BRUSH, 2).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.TIE, 2, -1).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.TIE, 2, first)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_TIE_HORSE_FIRST
            jobs.current(id)!!.yard!!.tieMask shouldBe 0
            jobs.current(id)!!.yard!!.pendingTieSide shouldBe first
            jobs.act(id, OriginOddJobAction.TIE, 3)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_TIE_SECOND
            jobs.act(id, OriginOddJobAction.TIE, 4, first).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.TIE, 4, 1-first)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_TIE_HORSE_SECOND
            jobs.act(id, OriginOddJobAction.TIE, 5)
            jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_BRUSH
            jobs.current(id)!!.yard!!.tieMask shouldBe 3
            jobs.current(id)!!.yard!!.pendingTieSide shouldBe null
            jobs.act(id, OriginOddJobAction.UNTIE, 6).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        }
    }

    "grooming uses the brush and one saddle and ride completion needs no disassembly" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.YARD_GROOM), 0, yardTarget = OriginYardWork(423, "Рыжик"))
        yardPreparationActions.take(7).forEach { jobs.yardAct(id, it, 1) }
        jobs.act(id, OriginOddJobAction.GROOM, 2).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.BRUSH, 3)
        repeat(ODD_JOB_GROOM_STROKES) { index ->
            jobs.act(id, OriginOddJobAction.GROOM, 30_000L * (index + 1))
            jobs.current(id)!!.yard!!.brushStrokes shouldBe index + 1
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_BRUSH_RETURN
        yardTackActions.forEach { jobs.act(id, it, 90_000) }
        jobs.current(id)!!.yard!!.equipped shouldBe setOf(OriginYardGear.SADDLE)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_DELIVER
        jobs.current(id)!!.completedActions shouldBe 13
        jobs.current(id)!!.yard!!.tieMask shouldBe 0
        jobs.current(id)!!.yard!!.pendingTieSide shouldBe null
        listOf(OriginOddJobAction.ARRIVE, OriginOddJobAction.HANDOFF).forEach { jobs.act(id, it, 90_005) }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_HANDED
        jobs.act(id, OriginOddJobAction.CARE_DONE, 90_006)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.current(id)!!.yard!!.borrowed shouldBe emptySet()
        jobs.current(id)!!.yard!!.equipped shouldBe emptySet()
        jobs.current(id)!!.completedActions shouldBe 13
        jobs.current(id)!!.yard!!.animalId shouldBe 423
    }

    "cancelling a saddled unfinished order never produces an accepted order" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        val job = jobs.begin(id, offer(OriginOddJobVariant.YARD_SADDLE), 0, yardTarget = OriginYardWork(422, "Буран"))!!
        yardPreparationActions.dropLast(2).forEach { jobs.yardAct(id, it, 1) }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_DELIVER
        jobs.current(id)!!.completedActions shouldBe 13
        jobs.act(id, OriginOddJobAction.SADDLE, 2).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.RETURN, 3).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.finish(id, job.id) shouldBe null
        jobs.cancel(id)
        jobs.finish(id, job.id) shouldBe null
    }

    "lesson needs every field checkpoint and the home point before it can be accepted once" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.YARD_LESSON), 0) shouldBe null
        val job = jobs.begin(id, offer(OriginOddJobVariant.YARD_LESSON), 0,
            yardTarget = OriginYardWork(422, "Буран", routeTotal = 9))!!
        yardPreparationActions.forEach { jobs.yardAct(id, it, 1) }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.YARD_LESSON_WALK
        jobs.current(id)!!.completedActions shouldBe 14
        repeat(9) { index ->
            jobs.current(id)!!.yard!!.routeIndex shouldBe index
            jobs.act(id, OriginOddJobAction.RETURN, 2).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.finish(id, job.id) shouldBe null
            (jobs.act(id, OriginOddJobAction.ARRIVE, 3) as OriginOddJobStep.Changed).workUnitId shouldBe null
        }
        jobs.current(id)!!.completedActions shouldBe 14
        jobs.current(id)!!.yard!!.borrowed shouldBe emptySet()
        jobs.current(id)!!.yard!!.equipped shouldBe emptySet()
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.current(id)!!.completedActions shouldBe 14
        (jobs.act(id, OriginOddJobAction.RETURN, 5) as OriginOddJobStep.Changed).workUnitId shouldBe null
        jobs.finish(id, job.id)!!.stage shouldBe OriginOddJobStage.PAYOUT
        jobs.finish(id, job.id) shouldBe null
    }
    "cleaning earns thirteen physical actions and requires the empty cart back before completion" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        val job = jobs.begin(id, offer(OriginOddJobVariant.YARD_CLEANUP), 0)!!
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.CLEAN_TOOL
        jobs.act(id, OriginOddJobAction.PICKUP, 1)
        jobs.act(id, OriginOddJobAction.ATTACH_CART, 2)
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 3).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.PARK_CART, 4)
        repeat(3) { index ->
            jobs.act(id, OriginOddJobAction.LOAD, 5).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.SCOOP, 6)
            jobs.act(id, OriginOddJobAction.SCOOP, 7).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
            jobs.act(id, OriginOddJobAction.LOAD, 8)
            jobs.current(id)!!.cleaning!!.collected shouldBe index + 1
            jobs.finish(id, job.id) shouldBe null
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.CLEAN_CART
        jobs.act(id, OriginOddJobAction.UNLOAD, 9).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.ATTACH_CART, 10)
        jobs.act(id, OriginOddJobAction.PARK_CART, 11).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 12)
        jobs.act(id, OriginOddJobAction.UNLOAD, 13)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.CLEAN_RETURN
        jobs.act(id, OriginOddJobAction.PARK_CART, 14).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.RETURN, 15).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.finish(id, job.id) shouldBe null
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 16)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.CLEAN_PARK
        jobs.act(id, OriginOddJobAction.PARK_CART, 17)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.CLEAN_TOOL_RETURN
        jobs.finish(id, job.id) shouldBe null
        jobs.act(id, OriginOddJobAction.RETURN, 18).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.GEAR, 18)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        jobs.current(id)!!.completedActions shouldBe 13
        (jobs.act(id, OriginOddJobAction.RETURN, 18) as OriginOddJobStep.Changed).workUnitId shouldBe null
        jobs.act(id, OriginOddJobAction.PARK_CART, 19).reason() shouldBe OriginOddJobRejection.PAYOUT_PENDING
        jobs.finish(id, job.id)!!.kind.rewardKind shouldBe OriginSideJobKind.MOUNTYARD
        jobs.finish(id, job.id) shouldBe null
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
    "hauling loads once attaches to the worker and pays only after delivery unloading" {
        val jobs = OriginOddJobsProgress(240_000); val id = UUID.randomUUID()
        jobs.begin(id, offer(OriginOddJobVariant.HAUL_COPPER), 0)
        jobs.current(id)!!.haul!!.cargo shouldBe OriginForgeCargo.COPPER
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 1).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.PICKUP, 2)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_LOAD
        jobs.act(id, OriginOddJobAction.ATTACH_CART, 3).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.LOAD, 4)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_ATTACH
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 5).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.ATTACH_CART, 6)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
        jobs.act(id, OriginOddJobAction.UNLOAD, 7).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.CART_ARRIVE, 8)
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.HAUL_UNLOAD
        jobs.act(id, OriginOddJobAction.RETURN, 9).reason() shouldBe OriginOddJobRejection.WRONG_ACTION
        jobs.act(id, OriginOddJobAction.UNLOAD, 10)
        val done = jobs.current(id)!!
        done.stage shouldBe OriginOddJobStage.PAYOUT
        jobs.act(id, OriginOddJobAction.UNLOAD, 11).reason() shouldBe OriginOddJobRejection.PAYOUT_PENDING
        jobs.finish(id, done.id) shouldBe done
        jobs.finish(id, done.id) shouldBe null
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
    "preparation retains its route budget without manual untacking or extending the shift hard stop" {
        val jobs = OriginOddJobsProgress(240_000)
        for (variant in listOf(OriginOddJobVariant.YARD_WATER, OriginOddJobVariant.YARD_CLEANUP,
                OriginOddJobVariant.FORGE_NEW, OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE,
                OriginOddJobVariant.YARD_LESSON)) {
            val job = jobs.begin(UUID.randomUUID(), offer(variant), 1_000,
                yardTarget = OriginYardWork(422, "Буран", routeTotal = 9))!!
            job.expiresAt shouldBe if (variant.isYardPreparation()) 481_000L else 241_000L
        }
        val id = UUID.randomUUID()
        val lesson = jobs.begin(id, offer(OriginOddJobVariant.YARD_LESSON), 0,
            yardTarget = OriginYardWork(422, "Буран", routeTotal = 9))!!
        var now = 0L
        yardPreparationActions.forEach { action ->
            now += 5_000
            (jobs.yardAct(id, action, now) is OriginOddJobStep.Changed) shouldBe true
        }
        repeat(9) {
            now += 25_000
            (jobs.act(id, OriginOddJobAction.ARRIVE, now) is OriginOddJobStep.Changed) shouldBe true
        }
        jobs.current(id)!!.stage shouldBe OriginOddJobStage.RETURN
        (now > 240_000 && now < 480_000) shouldBe true
        jobs.current(id)!!.expiresAt shouldBe lesson.expiresAt
        jobs.act(id, OriginOddJobAction.RETURN, now + 1)
        jobs.finish(id, lesson.id)!!.stage shouldBe OriginOddJobStage.PAYOUT
        jobs.finish(id, lesson.id) shouldBe null

        val cappedId = UUID.randomUUID()
        val capped = jobs.begin(cappedId, offer(OriginOddJobVariant.YARD_SADDLE), 0, hardStopAt = 120_000,
            yardTarget = OriginYardWork(422, "Буран"))!!
        capped.expiresAt shouldBe 120_000L
        jobs.act(cappedId, OriginOddJobAction.TACK, 120_000).reason() shouldBe OriginOddJobRejection.EXPIRED
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
