package ru.arc.origin

import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

internal enum class OriginOddJobKind { MOUNTYARD, FORGE, AUCTION }
internal enum class OriginOddJobVariant(val kind: OriginOddJobKind) {
    YARD_RATIONS(OriginOddJobKind.MOUNTYARD), YARD_WATER(OriginOddJobKind.MOUNTYARD), YARD_CARAVAN(OriginOddJobKind.MOUNTYARD),
    FORGE_NEW(OriginOddJobKind.FORGE), FORGE_REPAIR(OriginOddJobKind.FORGE), FORGE_PAIR(OriginOddJobKind.FORGE),
    AUCTION_DISPATCH(OriginOddJobKind.AUCTION), AUCTION_AMEND(OriginOddJobKind.AUCTION), AUCTION_GROUP(OriginOddJobKind.AUCTION),
}
internal data class OriginOddJobOffer(val id: UUID, val variant: OriginOddJobVariant, val seed: Int, val urgent: Boolean) {
    val kind: OriginOddJobKind get() = variant.kind
}

/** Every three accepted offers cover all families. Unchosen offers do not reroll on reopening the board. */
internal class OriginOddJobDispatch(private val kind: OriginOddJobKind, private val random: Random = Random.Default) {
    private var bag = emptyList<OriginOddJobVariant>()
    private var shown = emptyList<OriginOddJobOffer>()
    private var first = true
    fun offers(phase: OriginWorkShiftPhase): List<OriginOddJobOffer> {
        if (shown.isNotEmpty()) return shown
        if (bag.isEmpty()) {
            bag = OriginOddJobVariant.entries.filter { it.kind == kind }.shuffled(random)
            if (first && kind == OriginOddJobKind.FORGE) bag = listOf(OriginOddJobVariant.FORGE_NEW) + bag.filterNot { it == OriginOddJobVariant.FORGE_NEW }
            first = false
        }
        shown = List(2) { index -> OriginOddJobOffer(UUID.randomUUID(), bag[index % bag.size], random.nextInt(1, 10_000),
            kind != OriginOddJobKind.FORGE && phase == OriginWorkShiftPhase.RUSH && index == 1) }
        return shown
    }
    fun accept(id: UUID): OriginOddJobOffer? = shown.firstOrNull { it.id == id }?.also {
        bag = bag.filterNot { family -> family == it.variant }; shown = emptyList()
    }
}

internal enum class OriginOddJobStage {
    YARD_HAY, YARD_FEED, YARD_DRAIN, YARD_CLEAN, YARD_FILL, YARD_FILLING, YARD_LOAD,
    FORGE_BLANK, FORGE_INSPECT, FORGE_HEAT, FORGE_HEATING, FORGE_PLACE, FORGE_HAMMER, FORGE_QUENCH,
    AUCTION_ITEM, AUCTION_INSPECT, AUCTION_VERIFY, AUCTION_DOCUMENT, AUCTION_PACK, AUCTION_LOAD, AUCTION_DELIVER, AUCTION_RECEIPT,
    RETURN, PAYOUT,
}
internal enum class OriginOddJobAction { HAY, FEED, WATER, BLANK, HEAT, PLACE, HAMMER, QUENCH, HANDOFF, RETURN }
internal enum class OriginAuctionPacking { PADDED_BOX, PAPER_WRAP, SCABBARD }
internal enum class OriginAuctionParcel(val packing: OriginAuctionPacking, val weight: Int) {
    VASE(OriginAuctionPacking.PADDED_BOX, 1), BOOK(OriginAuctionPacking.PAPER_WRAP, 2), BLADE(OriginAuctionPacking.SCABBARD, 3),
}
internal data class OriginAuctionLot(val parcel: OriginAuctionParcel, val serial: Int, val restored: Boolean)
internal data class OriginYardWork(
    val required: List<Int>, val filled: List<Int> = listOf(0, 0), val carried: Int = 0,
    val cleanMask: Int = 0, val waterTarget: Int = 3, val waterCompartment: Int = 0, val loads: List<Int> = emptyList(),
)
internal data class OriginForgeWork(val target: List<Int>, val shape: List<Int>) {
    val remainingStrikes: Int get() = target.indices.sumOf { (target[it] - shape[it]).coerceAtLeast(0) }
}
internal data class OriginAuctionWork(
    val lots: List<OriginAuctionLot>, val index: Int = 0, val approved: Boolean = false,
    val selected: Int? = null, val packed: Int = 0, val loaded: List<Int> = emptyList(),
) { val lot: OriginAuctionLot get() = lots[index] }
internal data class OriginOddJobSession(
    val id: UUID, val kind: OriginOddJobKind, val stage: OriginOddJobStage,
    val startedAt: Long, val expiresAt: Long, val stageStartedAt: Long,
    val offer: OriginOddJobOffer, val quality: Int = 100, val progress: Int = 0,
    val yard: OriginYardWork? = null, val forge: OriginForgeWork? = null, val auction: OriginAuctionWork? = null,
) {
    val variant: OriginOddJobVariant get() = offer.variant
    val parcel: OriginAuctionParcel? get() = auction?.lot?.parcel
    fun finalQuality(now: Long): Int = (quality - if (offer.urgent && now - startedAt > 100_000) 10 else 0).coerceAtLeast(0)
}
internal enum class OriginOddJobRejection {
    NO_JOB, EXPIRED, WRONG_ACTION, TOO_EARLY,
    WRONG_ITEM, WRONG_PACKING, WRONG_ZONE,
    DIRTY_WATER, WRONG_LEVEL, WRONG_RATION, UNBALANCED, MANIFEST_MISMATCH, WRONG_RECEIPT, PAYOUT_PENDING,
}
internal sealed interface OriginOddJobStep {
    data class Changed(val job: OriginOddJobSession) : OriginOddJobStep
    data class Rejected(val reason: OriginOddJobRejection, val job: OriginOddJobSession?) : OriginOddJobStep
}
internal const val ODD_JOB_HEAT_READY_MILLIS = 4_000L
internal const val ODD_JOB_HAMMER_READY_MILLIS = 600L
internal const val ODD_JOB_WATER_STEP_MILLIS = 700L
internal val ODD_JOB_CARAVAN_WEIGHTS = listOf(1, 2, 3, 2)
internal fun originOddJobHammerReady(job: OriginOddJobSession, now: Long): Boolean = job.stage == OriginOddJobStage.FORGE_HAMMER &&
    now - job.stageStartedAt >= ODD_JOB_HAMMER_READY_MILLIS
internal fun originOddJobWaterLevel(job: OriginOddJobSession, now: Long): Int =
    ((now - job.stageStartedAt).coerceAtLeast(0) / ODD_JOB_WATER_STEP_MILLIS).toInt().coerceAtMost(7)
internal fun originOddJobHeatReady(job: OriginOddJobSession): Long = ODD_JOB_HEAT_READY_MILLIS +
    if (job.variant == OriginOddJobVariant.FORGE_NEW && job.offer.seed % 2 == 1) 1_500 else 0

internal fun originOddJobNewForgeShape(job: OriginOddJobSession): OriginJobProp = when {
    job.forge?.remainingStrikes == 0 -> OriginJobProp.FORGE_HORSESHOE
    job.progress == 0 -> OriginJobProp.FORGE_HEATED
    job.progress == 1 -> OriginJobProp.FORGE_SHAPED_1
    else -> OriginJobProp.FORGE_SHAPED_2
}

/** Progress is the current order's physical work, while the caption carries shift time and count. */
internal fun originOddJobProgress(job: OriginOddJobSession, now: Long): Float = when (job.stage) {
    OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.YARD_HAY, OriginOddJobStage.AUCTION_ITEM -> 0f
    OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.AUCTION_INSPECT -> 0.15f
    OriginOddJobStage.FORGE_HEATING -> 0.2f + 0.2f * ((now - job.stageStartedAt).toFloat() / originOddJobHeatReady(job)).coerceIn(0f, 1f)
    OriginOddJobStage.FORGE_PLACE -> 0.4f
    OriginOddJobStage.FORGE_HAMMER -> 0.45f + 0.35f * (job.progress.toFloat() / (job.progress + (job.forge?.remainingStrikes ?: 0)).coerceAtLeast(1))
    OriginOddJobStage.FORGE_QUENCH -> 0.85f
    OriginOddJobStage.YARD_FEED -> 0.1f + 0.35f * (job.yard!!.filled.sum().toFloat() / job.yard.required.sum())
    OriginOddJobStage.YARD_DRAIN -> 0.45f
    OriginOddJobStage.YARD_CLEAN -> 0.5f + 0.15f * Integer.bitCount(job.yard!!.cleanMask) / 3f
    OriginOddJobStage.YARD_FILL -> 0.7f
    OriginOddJobStage.YARD_FILLING -> 0.7f + 0.15f * (originOddJobWaterLevel(job, now).toFloat() / job.yard!!.waterTarget).coerceAtMost(1f)
    OriginOddJobStage.YARD_LOAD -> (job.yard!!.loads.size + 1) / 5f
    OriginOddJobStage.AUCTION_VERIFY, OriginOddJobStage.AUCTION_DOCUMENT -> 0.3f
    OriginOddJobStage.AUCTION_PACK -> 0.45f
    OriginOddJobStage.AUCTION_LOAD -> 0.6f
    OriginOddJobStage.AUCTION_DELIVER -> 0.75f
    OriginOddJobStage.AUCTION_RECEIPT -> 0.9f
    OriginOddJobStage.RETURN, OriginOddJobStage.PAYOUT -> 1f
}.coerceIn(0f, 1f)
internal fun originAuctionCandidates(job: OriginOddJobSession): List<OriginAuctionLot> {
    val work = requireNotNull(job.auction); val lot = work.lot
    val candidates = listOf(lot, lot.copy(serial = lot.serial + 7), lot.copy(restored = !lot.restored))
    val offset = (job.offer.seed + work.index) % 3
    return List(3) { candidates[(it + offset) % 3] }
}
internal fun originAuctionManifestSerial(job: OriginOddJobSession): Int = requireNotNull(job.auction).let {
    it.lot.serial - if (job.variant == OriginOddJobVariant.AUCTION_AMEND && !it.approved) 1 else 0
}

/** Explicit craft rules; transient progress never owns a durable payment claim. */
internal class OriginOddJobsProgress(private val lifetimeMillis: Long) {
    private val jobs = linkedMapOf<UUID, OriginOddJobSession>()
    init { require(lifetimeMillis > ODD_JOB_HEAT_READY_MILLIS + 1_500) }
    fun current(playerId: UUID): OriginOddJobSession? = jobs[playerId]
    fun entries(): List<Pair<UUID, OriginOddJobSession>> = jobs.toList()
    fun begin(playerId: UUID, offer: OriginOddJobOffer, now: Long, hardStopAt: Long = now + lifetimeMillis): OriginOddJobSession? {
        if (playerId in jobs) return null
        val n = offer.seed
        val target = listOf(2 + n % 2, 2 + n / 2 % 2, 2 + n / 3 % 2)
        val shape = when (offer.variant) {
            OriginOddJobVariant.FORGE_REPAIR -> target.mapIndexed { i, v -> if (i == n % 3) v - 2 else v }
            OriginOddJobVariant.FORGE_PAIR -> target.mapIndexed { i, v -> v - if (i == n % 3) 0 else 1 }
            OriginOddJobVariant.FORGE_NEW -> target.mapIndexed { i, v -> v - if (i == n % 3) 2 else 1 }
            else -> listOf(0, 0, 0)
        }
        val parcel = OriginAuctionParcel.entries[n % 3]
        val lots = if (offer.variant == OriginOddJobVariant.AUCTION_GROUP)
            listOf(OriginAuctionLot(OriginAuctionParcel.VASE, n + 100, false), OriginAuctionLot(OriginAuctionParcel.BLADE, n + 200, n % 2 == 0))
        else listOf(OriginAuctionLot(parcel, n + 100, n % 2 == 0))
        val stage = when (offer.variant) {
            OriginOddJobVariant.YARD_WATER -> OriginOddJobStage.YARD_DRAIN
            OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_CARAVAN -> OriginOddJobStage.YARD_HAY
            OriginOddJobVariant.FORGE_NEW -> OriginOddJobStage.FORGE_BLANK
            OriginOddJobVariant.FORGE_REPAIR, OriginOddJobVariant.FORGE_PAIR -> OriginOddJobStage.FORGE_INSPECT
            else -> OriginOddJobStage.AUCTION_ITEM
        }
        return OriginOddJobSession(UUID.randomUUID(), offer.kind, stage, now, minOf(hardStopAt, Math.addExact(now, lifetimeMillis)), now, offer,
            yard = if (offer.kind == OriginOddJobKind.MOUNTYARD) OriginYardWork(listOf(2 + n % 2, 3 + n / 2 % 2), waterTarget = 3 + n % 3) else null,
            forge = if (offer.kind == OriginOddJobKind.FORGE) OriginForgeWork(target, shape) else null,
            auction = if (offer.kind == OriginOddJobKind.AUCTION) OriginAuctionWork(lots) else null,
        ).also { jobs[playerId] = it }
    }

    fun act(playerId: UUID, action: OriginOddJobAction, now: Long, zone: Int = 1): OriginOddJobStep = advance(playerId, now) { job ->
        fun next(stage: OriginOddJobStage, updated: OriginOddJobSession = job) = changed(updated.copy(stage = stage, stageStartedAt = now, progress = 0))
        fun wrong() = rejected(OriginOddJobRejection.WRONG_ACTION, job)
        val yard = job.yard; val forge = job.forge
        when (job.stage) {
            OriginOddJobStage.YARD_FEED -> if (action != OriginOddJobAction.FEED || zone !in 0..2) wrong() else {
                val y = requireNotNull(yard); val side = if (zone == 0) 0 else 1
                if (y.filled[side] >= y.required[side]) mistake(OriginOddJobRejection.WRONG_RATION, job)
                else {
                    val filled = y.filled.mapIndexed { i, v -> v + if (i == side) 1 else 0 }
                    val updated = job.copy(yard = y.copy(filled = filled, carried = y.carried - 1))
                    when {
                        filled == y.required -> next(OriginOddJobStage.YARD_DRAIN, updated)
                        y.carried == 1 -> next(OriginOddJobStage.YARD_HAY, updated)
                        else -> changed(updated)
                    }
                }
            }
            OriginOddJobStage.YARD_DRAIN -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_CLEAN,
                job.copy(yard = requireNotNull(yard).copy(cleanMask = 0))) else wrong()
            OriginOddJobStage.YARD_CLEAN -> if (action != OriginOddJobAction.WATER || zone !in 0..2) wrong() else {
                val y = requireNotNull(yard); val mask = y.cleanMask or (1 shl zone)
                if (mask == y.cleanMask) rejected(OriginOddJobRejection.WRONG_ZONE, job)
                else if (mask == 7) next(OriginOddJobStage.YARD_FILL, job.copy(yard = y.copy(cleanMask = mask)))
                else changed(job.copy(yard = y.copy(cleanMask = mask)))
            }
            OriginOddJobStage.YARD_FILL -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_FILLING) else wrong()
            OriginOddJobStage.YARD_FILLING -> if (action != OriginOddJobAction.WATER) wrong() else {
                if (originOddJobWaterLevel(job, now) != requireNotNull(yard).waterTarget)
                    mistake(OriginOddJobRejection.WRONG_LEVEL, job.copy(stage = OriginOddJobStage.YARD_FILL, stageStartedAt = now))
                else if (job.variant == OriginOddJobVariant.YARD_WATER && yard.waterCompartment < 2) {
                    // Three trough compartments are inspected, cleaned and filled to different marks.
                    changed(job.copy(stage = OriginOddJobStage.YARD_DRAIN, stageStartedAt = now,
                        yard = yard.copy(cleanMask = 0, waterCompartment = yard.waterCompartment + 1,
                            waterTarget = 3 + (job.offer.seed + yard.waterCompartment + 1) % 3)))
                } else next(OriginOddJobStage.RETURN)
            }
            OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT ->
                if (action == OriginOddJobAction.BLANK) next(OriginOddJobStage.FORGE_HEAT) else wrong()
            OriginOddJobStage.FORGE_HEAT -> if (action == OriginOddJobAction.HEAT) next(OriginOddJobStage.FORGE_HEATING) else wrong()
            OriginOddJobStage.FORGE_HEATING -> when {
                action != OriginOddJobAction.HEAT -> wrong()
                now - job.stageStartedAt < originOddJobHeatReady(job) -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                else -> next(OriginOddJobStage.FORGE_PLACE)
            }
            OriginOddJobStage.FORGE_PLACE -> if (action == OriginOddJobAction.PLACE) next(OriginOddJobStage.FORGE_HAMMER) else wrong()
            OriginOddJobStage.FORGE_HAMMER -> when {
                action != OriginOddJobAction.HAMMER -> wrong()
                !originOddJobHammerReady(job, now) -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                else -> {
                    val work = requireNotNull(forge)
                    val index = work.shape.indices.first { work.shape[it] < work.target[it] }
                    val updated = job.copy(stageStartedAt = now, progress = job.progress + 1,
                        forge = work.copy(shape = work.shape.mapIndexed { i, v -> v + if (i == index) 1 else 0 }))
                    if (updated.forge!!.remainingStrikes == 0) next(OriginOddJobStage.FORGE_QUENCH, updated)
                    else changed(updated)
                }
            }
            OriginOddJobStage.FORGE_QUENCH -> if (action == OriginOddJobAction.QUENCH) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.AUCTION_VERIFY -> if (action == OriginOddJobAction.HANDOFF)
                next(OriginOddJobStage.AUCTION_DOCUMENT, job.copy(auction = requireNotNull(job.auction).copy(approved = true))) else wrong()
            OriginOddJobStage.AUCTION_DOCUMENT -> if (action == OriginOddJobAction.RETURN) next(OriginOddJobStage.AUCTION_INSPECT) else wrong()
            OriginOddJobStage.AUCTION_DELIVER -> if (action == OriginOddJobAction.HANDOFF) next(OriginOddJobStage.AUCTION_RECEIPT) else wrong()
            OriginOddJobStage.RETURN -> if (action == OriginOddJobAction.RETURN) next(OriginOddJobStage.PAYOUT) else wrong()
            else -> wrong()
        }
    }

    /** Native choices are validated again by the controller against the exact station/NPC and order UUID. */
    fun choose(playerId: UUID, choice: Int, now: Long): OriginOddJobStep = advance(playerId, now) { job ->
        fun next(stage: OriginOddJobStage, updated: OriginOddJobSession = job) = changed(updated.copy(stage = stage, stageStartedAt = now))
        when (job.stage) {
            OriginOddJobStage.YARD_HAY -> {
                val y = requireNotNull(job.yard)
                if (choice != job.offer.seed % 3) mistake(OriginOddJobRejection.WRONG_RATION, job)
                else if (job.variant == OriginOddJobVariant.YARD_CARAVAN) next(OriginOddJobStage.YARD_LOAD,
                    job.copy(yard = y.copy(carried = ODD_JOB_CARAVAN_WEIGHTS[y.loads.size])))
                else next(OriginOddJobStage.YARD_FEED, job.copy(yard = y.copy(carried = minOf(3, y.required.sum() - y.filled.sum()))))
            }
            OriginOddJobStage.YARD_LOAD -> {
                val y = requireNotNull(job.yard)
                if (choice == 2 && y.loads.isNotEmpty()) changed(job.copy(yard = y.copy(loads = y.loads.dropLast(1) + (1 - y.loads.last()))))
                else if (choice !in 0..1) rejected(OriginOddJobRejection.WRONG_ACTION, job)
                else {
                    val loads = y.loads + choice
                    val balance = loads.indices.sumOf { ODD_JOB_CARAVAN_WEIGHTS[it] * if (loads[it] == 0) -1 else 1 }
                    if (loads.size == 4 && abs(balance) > 2) mistake(OriginOddJobRejection.UNBALANCED, job)
                    else next(if (loads.size == 4) OriginOddJobStage.RETURN else OriginOddJobStage.YARD_HAY,
                        job.copy(yard = y.copy(loads = loads, carried = 0)))
                }
            }
            OriginOddJobStage.AUCTION_ITEM -> {
                val work = requireNotNull(job.auction); val lot = originAuctionCandidates(job).getOrNull(choice)
                if (lot != work.lot) mistake(OriginOddJobRejection.WRONG_ITEM, job)
                else next(OriginOddJobStage.AUCTION_INSPECT, job.copy(auction = work.copy(selected = choice)))
            }
            OriginOddJobStage.AUCTION_INSPECT -> {
                val work = requireNotNull(job.auction); val mismatch = originAuctionManifestSerial(job) != work.lot.serial
                when {
                    choice == 1 && mismatch -> next(OriginOddJobStage.AUCTION_VERIFY)
                    choice == 0 && !mismatch -> next(OriginOddJobStage.AUCTION_PACK)
                    else -> mistake(OriginOddJobRejection.MANIFEST_MISMATCH, job)
                }
            }
            OriginOddJobStage.AUCTION_PACK -> {
                val work = requireNotNull(job.auction)
                if (OriginAuctionPacking.entries.getOrNull(choice) != work.lot.parcel.packing) mistake(OriginOddJobRejection.WRONG_PACKING, job)
                else if (work.index + 1 < work.lots.size) next(OriginOddJobStage.AUCTION_ITEM,
                    job.copy(auction = work.copy(index = work.index + 1, selected = null, packed = work.packed + 1)))
                else next(if (work.lots.size == 1) OriginOddJobStage.AUCTION_DELIVER else OriginOddJobStage.AUCTION_LOAD,
                    job.copy(auction = work.copy(packed = work.packed + 1)))
            }
            OriginOddJobStage.AUCTION_LOAD -> {
                val work = requireNotNull(job.auction)
                val remaining = work.lots.indices.filter { it !in work.loaded }
                if (choice !in remaining || work.lots[choice].parcel.weight != remaining.maxOf { work.lots[it].parcel.weight })
                    mistake(OriginOddJobRejection.WRONG_PACKING, job)
                else {
                    val loaded = work.loaded + choice
                    next(if (loaded.size == work.lots.size) OriginOddJobStage.AUCTION_DELIVER else job.stage, job.copy(auction = work.copy(loaded = loaded)))
                }
            }
            OriginOddJobStage.AUCTION_RECEIPT -> if (choice == job.offer.seed % 3) next(OriginOddJobStage.RETURN)
                else mistake(OriginOddJobRejection.WRONG_RECEIPT, job)
            else -> rejected(OriginOddJobRejection.WRONG_ACTION, job)
        }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginOddJobSession? = jobs[playerId]
        ?.takeIf { it.id == requestId && it.stage == OriginOddJobStage.PAYOUT }?.let { jobs.remove(playerId) }
    fun cancel(playerId: UUID): OriginOddJobSession? = jobs.remove(playerId)
    private fun advance(playerId: UUID, now: Long, action: (OriginOddJobSession) -> OriginOddJobStep): OriginOddJobStep {
        val job = jobs[playerId] ?: return rejected(OriginOddJobRejection.NO_JOB, null)
        if (job.stage == OriginOddJobStage.PAYOUT) return rejected(OriginOddJobRejection.PAYOUT_PENDING, job)
        if (now >= job.expiresAt) return rejected(OriginOddJobRejection.EXPIRED, job)
        return action(job).also { step ->
            when (step) { is OriginOddJobStep.Changed -> step.job; is OriginOddJobStep.Rejected -> step.job }
                ?.let { jobs[playerId] = it }
        }
    }
    private fun changed(job: OriginOddJobSession) = OriginOddJobStep.Changed(job)
    private fun rejected(reason: OriginOddJobRejection, job: OriginOddJobSession?) = OriginOddJobStep.Rejected(reason, job)
    private fun mistake(reason: OriginOddJobRejection, job: OriginOddJobSession) = rejected(reason, job.copy(quality = (job.quality - 5).coerceAtLeast(0)))
}
