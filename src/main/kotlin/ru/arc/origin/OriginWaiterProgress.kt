package ru.arc.origin

import ru.arc.origin.scene.OriginSceneLease
import java.util.UUID

internal data class OriginWaiterDish(val id: String, val name: String)

internal data class OriginWaiterTarget(
    val lease: OriginSceneLease,
    val venueId: String,
    val staffId: Int,
    val guestId: Int,
    val guestName: String,
    val tableId: String,
    val dishId: String,
    val dishName: String,
    val drink: OriginWaiterDish? = null,
    val replacement: OriginWaiterDish? = null,
)

internal enum class OriginWaiterFamily { MEAL, DRINK_FIRST, REVISED }
internal enum class OriginWaiterCourse { DRINK, MAIN }
internal enum class OriginWaiterStage { TAKE_ORDER, CLARIFY, PICKUP, DELIVER, REWORK, PAYOUT }

internal data class OriginWaiterJob(
    val target: OriginWaiterTarget,
    val startedAt: Long,
    val expiresAt: Long,
    val stage: OriginWaiterStage = OriginWaiterStage.TAKE_ORDER,
    val readyAt: Long = 0,
    val family: OriginWaiterFamily = OriginWaiterFamily.MEAL,
    val course: OriginWaiterCourse = if (family == OriginWaiterFamily.DRINK_FIRST) OriginWaiterCourse.DRINK else OriginWaiterCourse.MAIN,
    val clarified: Boolean = false,
    val freshnessMillis: Long = 45_000,
    val quality: Int = 100,
    val reworks: Int = 0,
) {
    val id: UUID get() = target.lease.token
    val freshUntil: Long get() = readyAt + freshnessMillis
    val dish: OriginWaiterDish get() = when {
        course == OriginWaiterCourse.DRINK -> requireNotNull(target.drink)
        clarified -> requireNotNull(target.replacement)
        else -> OriginWaiterDish(target.dishId, target.dishName)
    }
    fun servingTarget(): OriginWaiterTarget = target.copy(dishId = dish.id, dishName = dish.name)
}

internal enum class OriginWaiterRejection {
    NO_JOB, EXPIRED, WRONG_VENUE, WRONG_GUEST, WRONG_STAFF, WRONG_DISH, TAKE_ORDER_FIRST,
    CLARIFY_FIRST, PICKUP_FIRST, PREPARING, ALREADY_CARRYING, REWORK_REQUIRED, PAYOUT_PENDING,
}

internal sealed interface OriginWaiterStep {
    data class Changed(val job: OriginWaiterJob) : OriginWaiterStep
    data class Rejected(val reason: OriginWaiterRejection) : OriginWaiterStep
}

/** Two independent table leases, but only one carried course. Payment remains with the reward owner. */
internal class OriginWaiterProgress(
    private val lifetimeMillis: Long,
    private val preparationMillis: Long,
) {
    private val jobs = linkedMapOf<UUID, LinkedHashMap<UUID, OriginWaiterJob>>()

    init { require(lifetimeMillis > 0 && preparationMillis >= 0 && preparationMillis < lifetimeMillis) }

    fun tickets(playerId: UUID): List<OriginWaiterJob> = jobs[playerId]?.values?.toList().orEmpty()
    fun current(playerId: UUID, requestId: UUID? = null): OriginWaiterJob? =
        if (requestId == null) jobs[playerId]?.values?.firstOrNull() else jobs[playerId]?.get(requestId)
    fun entries(): List<Pair<UUID, OriginWaiterJob>> = jobs.flatMap { (player, tickets) -> tickets.values.map { player to it } }
    fun carried(playerId: UUID): OriginWaiterJob? = tickets(playerId).firstOrNull { it.stage == OriginWaiterStage.DELIVER }

    /** A dish already in hand comes first; ready dishes are ordered by their actual freshness deadline. */
    fun priority(playerId: UUID, now: Long): OriginWaiterJob? = tickets(playerId)
        .filter { it.stage != OriginWaiterStage.PAYOUT }
        .minWithOrNull(compareBy<OriginWaiterJob> {
            when (it.stage) {
                OriginWaiterStage.DELIVER -> 0
                OriginWaiterStage.CLARIFY -> 1
                OriginWaiterStage.PICKUP -> if (now >= it.readyAt) 2 else 5
                OriginWaiterStage.TAKE_ORDER -> 3
                OriginWaiterStage.REWORK -> 4
                OriginWaiterStage.PAYOUT -> 6
            }
        }.thenBy { if (it.stage == OriginWaiterStage.PICKUP) it.freshUntil else it.startedAt })

    fun begin(
        playerId: UUID,
        target: OriginWaiterTarget,
        now: Long,
        family: OriginWaiterFamily = OriginWaiterFamily.MEAL,
        freshnessMillis: Long = 45_000,
        hardStopAt: Long = Long.MAX_VALUE,
    ): OriginWaiterJob? {
        val current = tickets(playerId)
        if (current.size >= 2 || current.any { it.target.guestId == target.guestId || it.target.venueId != target.venueId }) return null
        require(freshnessMillis > 0)
        require(family != OriginWaiterFamily.DRINK_FIRST || target.drink != null)
        require(family != OriginWaiterFamily.REVISED || target.replacement != null)
        return OriginWaiterJob(target, now, minOf(Math.addExact(now, lifetimeMillis), hardStopAt),
            family = family, freshnessMillis = freshnessMillis).also {
            jobs.getOrPut(playerId, ::linkedMapOf)[it.id] = it
        }
    }

    /** Returns transitions so the controller can remove an expired carried prop immediately. */
    fun refresh(playerId: UUID, now: Long): List<OriginWaiterJob> = tickets(playerId).mapNotNull { job ->
        val updated = when {
            job.stage == OriginWaiterStage.PICKUP && job.family == OriginWaiterFamily.REVISED && !job.clarified && now >= job.readyAt ->
                job.copy(stage = OriginWaiterStage.CLARIFY)
            job.stage in setOf(OriginWaiterStage.PICKUP, OriginWaiterStage.DELIVER) && now >= job.freshUntil ->
                job.copy(stage = OriginWaiterStage.REWORK)
            else -> null
        }
        updated?.also { jobs.getValue(playerId)[it.id] = it }
    }

    fun guest(playerId: UUID, guestId: Int, venueId: String?, now: Long): OriginWaiterStep {
        refresh(playerId, now)
        val job = tickets(playerId).firstOrNull { it.target.guestId == guestId }
            ?: return reject(if (tickets(playerId).isEmpty()) OriginWaiterRejection.NO_JOB else OriginWaiterRejection.WRONG_GUEST)
        return advance(playerId, job.id, venueId, now) {
            when (it.stage) {
                OriginWaiterStage.TAKE_ORDER -> changed(it.copy(stage = OriginWaiterStage.PICKUP, readyAt = now + preparationMillis))
                OriginWaiterStage.CLARIFY -> changed(it.copy(stage = OriginWaiterStage.PICKUP, clarified = true, readyAt = now + preparationMillis))
                OriginWaiterStage.PICKUP -> reject(OriginWaiterRejection.PICKUP_FIRST)
                OriginWaiterStage.REWORK -> reject(OriginWaiterRejection.REWORK_REQUIRED)
                OriginWaiterStage.DELIVER -> {
                    val quality = (it.quality - ((now - it.readyAt).coerceAtLeast(0) * 25 / it.freshnessMillis).toInt()).coerceIn(0, 100)
                    if (it.course == OriginWaiterCourse.DRINK) changed(it.copy(stage = OriginWaiterStage.PICKUP,
                        course = OriginWaiterCourse.MAIN, readyAt = now + preparationMillis, quality = quality))
                    else changed(it.copy(stage = OriginWaiterStage.PAYOUT, quality = quality))
                }
                OriginWaiterStage.PAYOUT -> reject(OriginWaiterRejection.PAYOUT_PENDING)
            }
        }
    }

    fun pickup(
        playerId: UUID, staffId: Int, venueId: String?, now: Long,
        requestId: UUID? = current(playerId)?.id, dishId: String? = requestId?.let { current(playerId, it)?.dish?.id },
    ): OriginWaiterStep {
        refresh(playerId, now)
        return advance(playerId, requestId, venueId, now) { job ->
            when {
                staffId != job.target.staffId -> reject(OriginWaiterRejection.WRONG_STAFF)
                carried(playerId) != null -> reject(OriginWaiterRejection.ALREADY_CARRYING)
                job.stage == OriginWaiterStage.TAKE_ORDER -> reject(OriginWaiterRejection.TAKE_ORDER_FIRST)
                job.stage == OriginWaiterStage.CLARIFY -> reject(OriginWaiterRejection.CLARIFY_FIRST)
                job.stage == OriginWaiterStage.REWORK -> reject(OriginWaiterRejection.REWORK_REQUIRED)
                now < job.readyAt -> reject(OriginWaiterRejection.PREPARING)
                dishId != job.dish.id -> reject(OriginWaiterRejection.WRONG_DISH)
                else -> changed(job.copy(stage = OriginWaiterStage.DELIVER))
            }
        }
    }

    fun remake(playerId: UUID, requestId: UUID, staffId: Int, venueId: String?, now: Long): OriginWaiterStep =
        advance(playerId, requestId, venueId, now) { job ->
            when {
                staffId != job.target.staffId -> reject(OriginWaiterRejection.WRONG_STAFF)
                job.stage != OriginWaiterStage.REWORK -> reject(OriginWaiterRejection.PICKUP_FIRST)
                else -> changed(job.copy(stage = OriginWaiterStage.PICKUP, readyAt = now + preparationMillis,
                    reworks = job.reworks + 1, quality = (job.quality - 15).coerceAtLeast(0)))
            }
        }

    private fun advance(
        playerId: UUID, requestId: UUID?, venueId: String?, now: Long,
        action: (OriginWaiterJob) -> OriginWaiterStep,
    ): OriginWaiterStep {
        val job = requestId?.let { current(playerId, it) } ?: return reject(OriginWaiterRejection.NO_JOB)
        if (job.stage == OriginWaiterStage.PAYOUT) return reject(OriginWaiterRejection.PAYOUT_PENDING)
        if (now >= job.expiresAt) return reject(OriginWaiterRejection.EXPIRED)
        if (venueId != job.target.venueId) return reject(OriginWaiterRejection.WRONG_VENUE)
        return action(job).also { if (it is OriginWaiterStep.Changed) jobs.getValue(playerId)[job.id] = it.job }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginWaiterJob? =
        current(playerId, requestId)?.takeIf { it.stage == OriginWaiterStage.PAYOUT }?.let { cancel(playerId, requestId) }

    fun cancel(playerId: UUID, requestId: UUID): OriginWaiterJob? = jobs[playerId]?.remove(requestId)?.also {
        if (jobs[playerId].isNullOrEmpty()) jobs.remove(playerId)
    }

    fun cancel(playerId: UUID): OriginWaiterJob? = current(playerId)?.let { cancel(playerId, it.id) }
    fun clear(): List<Pair<UUID, OriginWaiterJob>> = entries().also { jobs.clear() }
    private fun changed(job: OriginWaiterJob) = OriginWaiterStep.Changed(job)
    private fun reject(reason: OriginWaiterRejection) = OriginWaiterStep.Rejected(reason)
}
