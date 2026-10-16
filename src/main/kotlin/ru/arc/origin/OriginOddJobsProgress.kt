package ru.arc.origin

import java.util.UUID

internal enum class OriginOddJobKind { MOUNTYARD, FORGE, AUCTION }

internal enum class OriginOddJobStage {
    YARD_HAY, YARD_FEED, YARD_WATER,
    FORGE_BLANK, FORGE_HEAT, FORGE_HEATING, FORGE_PLACE, FORGE_HAMMER, FORGE_QUENCH,
    AUCTION_ITEM, AUCTION_PACK, AUCTION_DELIVER,
    RETURN, PAYOUT,
}

internal enum class OriginOddJobAction { HAY, FEED, WATER, BLANK, HEAT, PLACE, HAMMER, QUENCH, HANDOFF, RETURN }

internal enum class OriginAuctionPacking { PADDED_BOX, PAPER_WRAP, SCABBARD }

internal enum class OriginAuctionParcel(val packing: OriginAuctionPacking) {
    VASE(OriginAuctionPacking.PADDED_BOX),
    BOOK(OriginAuctionPacking.PAPER_WRAP),
    BLADE(OriginAuctionPacking.SCABBARD),
}

internal data class OriginOddJobSession(
    val id: UUID,
    val kind: OriginOddJobKind,
    val stage: OriginOddJobStage,
    val startedAt: Long,
    val expiresAt: Long,
    val stageStartedAt: Long,
    val progress: Int = 0,
    val parcel: OriginAuctionParcel? = null,
)

internal enum class OriginOddJobRejection {
    NO_JOB, EXPIRED, WRONG_ACTION, TOO_EARLY, OVERHEATED, MISSED_BEAT,
    WRONG_ITEM, WRONG_PACKING, PAYOUT_PENDING,
}

internal sealed interface OriginOddJobStep {
    data class Changed(val job: OriginOddJobSession) : OriginOddJobStep
    data class Rejected(val reason: OriginOddJobRejection, val job: OriginOddJobSession?) : OriginOddJobStep
}

internal const val ODD_JOB_HEAT_READY_MILLIS = 4_000L
internal const val ODD_JOB_HEAT_LIMIT_MILLIS = 7_000L
internal const val ODD_JOB_HAMMER_PERIOD_MILLIS = 2_200L
internal const val ODD_JOB_HAMMER_READY_MILLIS = 1_100L
internal const val ODD_JOB_HAMMER_END_MILLIS = 1_800L
internal const val ODD_JOB_YARD_STROKE_MILLIS = 700L

internal fun originOddJobHammerReady(job: OriginOddJobSession, now: Long): Boolean =
    job.stage == OriginOddJobStage.FORGE_HAMMER &&
        (now - job.stageStartedAt).coerceAtLeast(0) % ODD_JOB_HAMMER_PERIOD_MILLIS in
        ODD_JOB_HAMMER_READY_MILLIS..ODD_JOB_HAMMER_END_MILLIS

/** Three concrete work loops; quota and payment remain in the shared durable reward owner. */
internal class OriginOddJobsProgress(private val lifetimeMillis: Long) {
    private val jobs = linkedMapOf<UUID, OriginOddJobSession>()

    init { require(lifetimeMillis > ODD_JOB_HEAT_LIMIT_MILLIS) }

    fun current(playerId: UUID): OriginOddJobSession? = jobs[playerId]
    fun entries(): List<Pair<UUID, OriginOddJobSession>> = jobs.toList()

    fun begin(playerId: UUID, kind: OriginOddJobKind, now: Long, parcel: OriginAuctionParcel? = null): OriginOddJobSession? {
        if (playerId in jobs) return null
        require(kind != OriginOddJobKind.AUCTION || parcel != null)
        val stage = when (kind) {
            OriginOddJobKind.MOUNTYARD -> OriginOddJobStage.YARD_HAY
            OriginOddJobKind.FORGE -> OriginOddJobStage.FORGE_BLANK
            OriginOddJobKind.AUCTION -> OriginOddJobStage.AUCTION_ITEM
        }
        return OriginOddJobSession(UUID.randomUUID(), kind, stage, now, Math.addExact(now, lifetimeMillis), now, parcel = parcel)
            .also { jobs[playerId] = it }
    }

    fun act(playerId: UUID, action: OriginOddJobAction, now: Long): OriginOddJobStep = advance(playerId, now) { job ->
        fun next(stage: OriginOddJobStage) = changed(job.copy(stage = stage, stageStartedAt = now, progress = 0))
        fun wrong() = rejected(OriginOddJobRejection.WRONG_ACTION, job)
        when (job.stage) {
            OriginOddJobStage.YARD_HAY -> if (action != OriginOddJobAction.HAY) wrong() else yardStroke(job, now, OriginOddJobStage.YARD_FEED)
            OriginOddJobStage.YARD_FEED -> if (action == OriginOddJobAction.FEED) next(OriginOddJobStage.YARD_WATER) else wrong()
            OriginOddJobStage.YARD_WATER -> if (action != OriginOddJobAction.WATER) wrong() else yardStroke(job, now, OriginOddJobStage.RETURN)
            OriginOddJobStage.FORGE_BLANK -> if (action == OriginOddJobAction.BLANK) next(OriginOddJobStage.FORGE_HEAT) else wrong()
            OriginOddJobStage.FORGE_HEAT -> if (action == OriginOddJobAction.HEAT) next(OriginOddJobStage.FORGE_HEATING) else wrong()
            OriginOddJobStage.FORGE_HEATING -> when {
                action != OriginOddJobAction.HEAT -> wrong()
                now - job.stageStartedAt < ODD_JOB_HEAT_READY_MILLIS -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                now - job.stageStartedAt > ODD_JOB_HEAT_LIMIT_MILLIS -> rejected(OriginOddJobRejection.OVERHEATED,
                    job.copy(stage = OriginOddJobStage.FORGE_HEAT, stageStartedAt = now))
                else -> next(OriginOddJobStage.FORGE_PLACE)
            }
            OriginOddJobStage.FORGE_PLACE -> if (action == OriginOddJobAction.PLACE) next(OriginOddJobStage.FORGE_HAMMER) else wrong()
            OriginOddJobStage.FORGE_HAMMER -> when {
                action != OriginOddJobAction.HAMMER -> wrong()
                !originOddJobHammerReady(job, now) -> rejected(OriginOddJobRejection.MISSED_BEAT, job)
                job.progress == 2 -> next(OriginOddJobStage.FORGE_QUENCH)
                else -> changed(job.copy(progress = job.progress + 1, stageStartedAt = now))
            }
            OriginOddJobStage.FORGE_QUENCH -> if (action == OriginOddJobAction.QUENCH) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.AUCTION_DELIVER -> if (action == OriginOddJobAction.HANDOFF) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.RETURN -> if (action == OriginOddJobAction.RETURN) next(OriginOddJobStage.PAYOUT) else wrong()
            else -> wrong()
        }
    }

    fun chooseItem(playerId: UUID, item: OriginAuctionParcel, now: Long): OriginOddJobStep = advance(playerId, now) { job ->
        when {
            job.stage != OriginOddJobStage.AUCTION_ITEM -> rejected(OriginOddJobRejection.WRONG_ACTION, job)
            item != job.parcel -> rejected(OriginOddJobRejection.WRONG_ITEM, job)
            else -> changed(job.copy(stage = OriginOddJobStage.AUCTION_PACK, stageStartedAt = now))
        }
    }

    fun choosePacking(playerId: UUID, packing: OriginAuctionPacking, now: Long): OriginOddJobStep = advance(playerId, now) { job ->
        when {
            job.stage != OriginOddJobStage.AUCTION_PACK -> rejected(OriginOddJobRejection.WRONG_ACTION, job)
            packing != job.parcel?.packing -> rejected(OriginOddJobRejection.WRONG_PACKING, job)
            else -> changed(job.copy(stage = OriginOddJobStage.AUCTION_DELIVER, stageStartedAt = now))
        }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginOddJobSession? = jobs[playerId]
        ?.takeIf { it.id == requestId && it.stage == OriginOddJobStage.PAYOUT }?.let { jobs.remove(playerId) }

    fun cancel(playerId: UUID): OriginOddJobSession? = jobs.remove(playerId)

    private fun yardStroke(job: OriginOddJobSession, now: Long, next: OriginOddJobStage): OriginOddJobStep {
        if (job.progress > 0 && now - job.stageStartedAt < ODD_JOB_YARD_STROKE_MILLIS)
            return rejected(OriginOddJobRejection.TOO_EARLY, job)
        return changed(if (job.progress == 2) job.copy(stage = next, stageStartedAt = now, progress = 0)
            else job.copy(progress = job.progress + 1, stageStartedAt = now))
    }

    private fun advance(playerId: UUID, now: Long, action: (OriginOddJobSession) -> OriginOddJobStep): OriginOddJobStep {
        val job = jobs[playerId] ?: return rejected(OriginOddJobRejection.NO_JOB, null)
        if (job.stage == OriginOddJobStage.PAYOUT) return rejected(OriginOddJobRejection.PAYOUT_PENDING, job)
        if (now >= job.expiresAt) return rejected(OriginOddJobRejection.EXPIRED, job)
        return action(job).also { step ->
            val updated = when (step) {
                is OriginOddJobStep.Changed -> step.job
                is OriginOddJobStep.Rejected -> step.job
            }
            if (updated != null) jobs[playerId] = updated
        }
    }

    private fun changed(job: OriginOddJobSession) = OriginOddJobStep.Changed(job)
    private fun rejected(reason: OriginOddJobRejection, job: OriginOddJobSession?) = OriginOddJobStep.Rejected(reason, job)
}
