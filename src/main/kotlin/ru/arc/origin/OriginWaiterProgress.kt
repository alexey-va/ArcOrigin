package ru.arc.origin

import ru.arc.origin.scene.OriginSceneLease
import java.util.UUID

internal data class OriginWaiterTarget(
    val lease: OriginSceneLease,
    val venueId: String,
    val staffId: Int,
    val guestId: Int,
    val guestName: String,
    val tableId: String,
    val dishId: String,
    val dishName: String,
)

internal enum class OriginWaiterStage { TAKE_ORDER, PICKUP, DELIVER, PAYOUT }

internal data class OriginWaiterJob(
    val target: OriginWaiterTarget,
    val startedAt: Long,
    val expiresAt: Long,
    val stage: OriginWaiterStage = OriginWaiterStage.TAKE_ORDER,
    val readyAt: Long = 0,
) {
    val id: UUID get() = target.lease.token
}

internal enum class OriginWaiterRejection {
    NO_JOB, EXPIRED, WRONG_VENUE, WRONG_GUEST, WRONG_STAFF, TAKE_ORDER_FIRST,
    PICKUP_FIRST, PREPARING, ALREADY_CARRYING, PAYOUT_PENDING,
}

internal sealed interface OriginWaiterStep {
    data class Changed(val job: OriginWaiterJob) : OriginWaiterStep
    data class Rejected(val reason: OriginWaiterRejection) : OriginWaiterStep
}

/**
 * A waiter reserves one existing dining guest, visits that guest before pickup,
 * then returns to the same guest. Unlike mountyard's distinct-target counter,
 * this ordered handoff must retain the exact recipient and kitchen identity.
 * Progress is transient; the reward owner retains every durable payment claim.
 */
internal class OriginWaiterProgress(
    private val lifetimeMillis: Long,
    private val preparationMillis: Long,
) {
    private val jobs = linkedMapOf<UUID, OriginWaiterJob>()

    init { require(lifetimeMillis > 0 && preparationMillis >= 0 && preparationMillis < lifetimeMillis) }

    fun current(playerId: UUID): OriginWaiterJob? = jobs[playerId]

    fun entries(): List<Pair<UUID, OriginWaiterJob>> = jobs.toList()

    fun begin(playerId: UUID, target: OriginWaiterTarget, now: Long): OriginWaiterJob? {
        if (playerId in jobs) return null
        return OriginWaiterJob(target, now, Math.addExact(now, lifetimeMillis)).also { jobs[playerId] = it }
    }

    fun guest(playerId: UUID, guestId: Int, venueId: String?, now: Long): OriginWaiterStep =
        advance(playerId, venueId, now) { job ->
            when {
                guestId != job.target.guestId -> reject(OriginWaiterRejection.WRONG_GUEST)
                job.stage == OriginWaiterStage.TAKE_ORDER -> OriginWaiterStep.Changed(
                    job.copy(stage = OriginWaiterStage.PICKUP, readyAt = Math.addExact(now, preparationMillis)),
                )
                job.stage == OriginWaiterStage.PICKUP -> reject(OriginWaiterRejection.PICKUP_FIRST)
                job.stage == OriginWaiterStage.DELIVER -> OriginWaiterStep.Changed(job.copy(stage = OriginWaiterStage.PAYOUT))
                else -> reject(OriginWaiterRejection.PAYOUT_PENDING)
            }
        }

    fun pickup(playerId: UUID, staffId: Int, venueId: String?, now: Long): OriginWaiterStep =
        advance(playerId, venueId, now) { job ->
            when {
                staffId != job.target.staffId -> reject(OriginWaiterRejection.WRONG_STAFF)
                job.stage == OriginWaiterStage.TAKE_ORDER -> reject(OriginWaiterRejection.TAKE_ORDER_FIRST)
                job.stage == OriginWaiterStage.PAYOUT -> reject(OriginWaiterRejection.PAYOUT_PENDING)
                job.stage == OriginWaiterStage.DELIVER -> reject(OriginWaiterRejection.ALREADY_CARRYING)
                now < job.readyAt -> reject(OriginWaiterRejection.PREPARING)
                else -> OriginWaiterStep.Changed(job.copy(stage = OriginWaiterStage.DELIVER))
            }
        }

    private fun advance(
        playerId: UUID,
        venueId: String?,
        now: Long,
        action: (OriginWaiterJob) -> OriginWaiterStep,
    ): OriginWaiterStep {
        val job = jobs[playerId] ?: return reject(OriginWaiterRejection.NO_JOB)
        if (job.stage == OriginWaiterStage.PAYOUT) return reject(OriginWaiterRejection.PAYOUT_PENDING)
        if (now >= job.expiresAt) return reject(OriginWaiterRejection.EXPIRED)
        if (venueId != job.target.venueId) return reject(OriginWaiterRejection.WRONG_VENUE)
        return action(job).also { if (it is OriginWaiterStep.Changed) jobs[playerId] = it.job }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginWaiterJob? {
        val job = jobs[playerId]?.takeIf { it.id == requestId && it.stage == OriginWaiterStage.PAYOUT } ?: return null
        return jobs.remove(playerId, job).takeIf { it }?.let { job }
    }

    fun cancel(playerId: UUID): OriginWaiterJob? = jobs.remove(playerId)

    fun clear(): List<Pair<UUID, OriginWaiterJob>> = jobs.toList().also { jobs.clear() }

    private fun reject(reason: OriginWaiterRejection) = OriginWaiterStep.Rejected(reason)
}
