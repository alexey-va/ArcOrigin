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
    val previousPlate: OriginWaiterDish? = null,
)

internal enum class OriginWaiterFamily { MEAL, DRINK_FIRST, REVISED, ASSEMBLED }
internal enum class OriginWaiterCourse { DRINK, MAIN }
internal enum class OriginWaiterStage { TAKE_ORDER, CLARIFY, ASSEMBLE, PICKUP, DELIVER, EATING, COLLECT, RETURN_DISHES, WASH, REWORK, PAYOUT }
internal enum class OriginWaiterWash { SCRAPE, SOAK, SOAP, RINSE, DRY }
internal enum class OriginWaiterDisputeStage { FIRST_SIDE, SECOND_SIDE, WATER, BRING_WATER, REASSURE }
internal enum class OriginWaiterDisputeKind { SPILLED_DRINK, LOUD_TOAST }
internal data class OriginWaiterDispute(
    val first: UUID, val second: UUID, val startedAt: Long,
    val kind: OriginWaiterDisputeKind, val stage: OriginWaiterDisputeStage = OriginWaiterDisputeStage.FIRST_SIDE,
)

internal data class OriginWaiterJob(
    val target: OriginWaiterTarget,
    val startedAt: Long,
    val expiresAt: Long,
    val stage: OriginWaiterStage = if (target.previousPlate == null) OriginWaiterStage.TAKE_ORDER else OriginWaiterStage.COLLECT,
    val readyAt: Long = 0,
    val family: OriginWaiterFamily = OriginWaiterFamily.MEAL,
    val course: OriginWaiterCourse = if (family == OriginWaiterFamily.DRINK_FIRST) OriginWaiterCourse.DRINK else OriginWaiterCourse.MAIN,
    val clarified: Boolean = false,
    val freshnessMillis: Long = 45_000,
    val quality: Int = 100,
    val reworks: Int = 0,
    val recipe: List<String> = emptyList(),
    val assembled: Int = 0,
    val washing: List<OriginWaiterWash> = listOf(OriginWaiterWash.SCRAPE, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY),
    val washed: Int = 0,
    val hardStopAt: Long = Long.MAX_VALUE,
    val clearingPreviousMeal: Boolean = target.previousPlate != null,
) {
    val id: UUID get() = target.lease.token
    val freshUntil: Long get() = readyAt + freshnessMillis
    val carries: Boolean get() = stage == OriginWaiterStage.DELIVER || stage == OriginWaiterStage.RETURN_DISHES
    val dish: OriginWaiterDish get() = when {
        clearingPreviousMeal -> requireNotNull(target.previousPlate)
        course == OriginWaiterCourse.DRINK -> requireNotNull(target.drink)
        clarified -> requireNotNull(target.replacement)
        else -> OriginWaiterDish(target.dishId, target.dishName)
    }
    fun servingTarget(): OriginWaiterTarget = target.copy(dishId = dish.id, dishName = dish.name)
}

internal enum class OriginWaiterRejection {
    NO_JOB, EXPIRED, WRONG_VENUE, WRONG_GUEST, WRONG_STAFF, WRONG_DISH, TAKE_ORDER_FIRST,
    CLARIFY_FIRST, PICKUP_FIRST, PREPARING, ALREADY_CARRYING, REWORK_REQUIRED, PAYOUT_PENDING,
    ASSEMBLE_FIRST, WRONG_INGREDIENT, EATING, COLLECT_FIRST, WASH_FIRST, WRONG_WASH_STEP, STALE_ACTION, DISPUTE,
}

internal sealed interface OriginWaiterStep {
    data class Changed(val job: OriginWaiterJob) : OriginWaiterStep
    data class Rejected(val reason: OriginWaiterRejection) : OriginWaiterStep
}

/** The leased table stays with one request through cooking, serving and clean crockery. */
internal class OriginWaiterProgress(
    private val lifetimeMillis: Long,
    private val preparationMillis: Long,
    private val eatingMillis: Long = 18_000,
    private val actionMillis: Long = 700,
    private val soakMillis: Long = 3_000,
) {
    private val jobs = linkedMapOf<UUID, LinkedHashMap<UUID, OriginWaiterJob>>()
    private val disputes = mutableMapOf<UUID, OriginWaiterDispute>()

    init { require(lifetimeMillis > 0 && preparationMillis >= 0 && preparationMillis < lifetimeMillis) }

    fun tickets(playerId: UUID): List<OriginWaiterJob> = jobs[playerId]?.values?.toList().orEmpty()
    fun current(playerId: UUID, requestId: UUID? = null): OriginWaiterJob? =
        if (requestId == null) jobs[playerId]?.values?.firstOrNull() else jobs[playerId]?.get(requestId)
    fun entries(): List<Pair<UUID, OriginWaiterJob>> = jobs.flatMap { (player, tickets) -> tickets.values.map { player to it } }
    fun carried(playerId: UUID): OriginWaiterJob? = tickets(playerId).firstOrNull { it.carries }
    fun dispute(playerId: UUID): OriginWaiterDispute? = disputes[playerId]

    fun priority(playerId: UUID, now: Long): OriginWaiterJob? = tickets(playerId)
        .filter { it.stage != OriginWaiterStage.PAYOUT }
        .minWithOrNull(compareBy<OriginWaiterJob> {
            when (it.stage) {
                OriginWaiterStage.DELIVER, OriginWaiterStage.RETURN_DISHES -> 0
                OriginWaiterStage.WASH -> 1
                OriginWaiterStage.ASSEMBLE -> if (it.assembled > 0) 1 else if (now >= it.readyAt) 6 else 8
                OriginWaiterStage.CLARIFY -> 2
                OriginWaiterStage.PICKUP -> if (now >= it.readyAt) 3 else 8
                OriginWaiterStage.REWORK -> 3
                OriginWaiterStage.TAKE_ORDER -> 4
                OriginWaiterStage.COLLECT -> 5
                OriginWaiterStage.EATING -> 9
                OriginWaiterStage.PAYOUT -> 10
            }
        }.thenBy { if (it.stage == OriginWaiterStage.PICKUP) it.freshUntil else it.startedAt })

    fun begin(
        playerId: UUID, target: OriginWaiterTarget, now: Long,
        family: OriginWaiterFamily = OriginWaiterFamily.MEAL,
        freshnessMillis: Long = 45_000, hardStopAt: Long = Long.MAX_VALUE,
        recipe: List<String> = emptyList(),
        washing: List<OriginWaiterWash> = listOf(OriginWaiterWash.SCRAPE, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY),
    ): OriginWaiterJob? {
        val current = tickets(playerId)
        if (current.size >= 2 || current.any { it.target.guestId == target.guestId || it.target.venueId != target.venueId }) return null
        require(freshnessMillis > 0 && washing.isNotEmpty() && washing.last() == OriginWaiterWash.DRY)
        require(family != OriginWaiterFamily.DRINK_FIRST || target.drink != null)
        require(family != OriginWaiterFamily.REVISED || target.replacement != null)
        require(family != OriginWaiterFamily.ASSEMBLED || recipe.isNotEmpty())
        return OriginWaiterJob(target, now, minOf(Math.addExact(now, lifetimeMillis), hardStopAt),
            family = family, freshnessMillis = freshnessMillis, recipe = recipe, washing = washing, hardStopAt = hardStopAt).also {
            jobs.getOrPut(playerId, ::linkedMapOf)[it.id] = it
        }
    }

    /** Eating completion needs the dining owner's physical EMPTY transition before [finishEating]. */
    fun refresh(playerId: UUID, now: Long): List<OriginWaiterJob> {
        if (dispute(playerId) != null) return emptyList()
        return tickets(playerId).mapNotNull { job ->
            val updated = when {
                job.stage == OriginWaiterStage.PICKUP && job.family == OriginWaiterFamily.REVISED && !job.clarified && now >= job.readyAt ->
                    job.copy(stage = OriginWaiterStage.CLARIFY)
                job.stage in setOf(OriginWaiterStage.PICKUP, OriginWaiterStage.DELIVER) && now >= job.freshUntil ->
                    job.copy(stage = OriginWaiterStage.REWORK)
                else -> null
            }
            updated?.also { jobs.getValue(playerId)[it.id] = it }
        }
    }

    fun guest(playerId: UUID, guestId: Int, venueId: String?, now: Long): OriginWaiterStep {
        refresh(playerId, now)
        val job = tickets(playerId).firstOrNull { it.target.guestId == guestId }
            ?: return reject(if (tickets(playerId).isEmpty()) OriginWaiterRejection.NO_JOB else OriginWaiterRejection.WRONG_GUEST)
        return advance(playerId, job.id, venueId, now) {
            when (it.stage) {
                OriginWaiterStage.TAKE_ORDER -> changed(it.copy(
                    stage = if (it.family == OriginWaiterFamily.ASSEMBLED) OriginWaiterStage.ASSEMBLE else OriginWaiterStage.PICKUP,
                    readyAt = now + preparationMillis))
                OriginWaiterStage.CLARIFY -> changed(it.copy(stage = OriginWaiterStage.PICKUP, clarified = true, readyAt = now + preparationMillis))
                OriginWaiterStage.DELIVER -> {
                    val quality = (it.quality - ((now - it.readyAt).coerceAtLeast(0) * 25 / it.freshnessMillis).toInt()).coerceIn(0, 100)
                    changed(it.copy(stage = OriginWaiterStage.EATING, readyAt = now + eatingMillis, quality = quality))
                }
                OriginWaiterStage.COLLECT -> if (carried(playerId) != null) reject(OriginWaiterRejection.ALREADY_CARRYING)
                    else changed(it.copy(stage = OriginWaiterStage.RETURN_DISHES))
                else -> reject(stageRejection(it.stage))
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
                job.stage != OriginWaiterStage.PICKUP -> reject(stageRejection(job.stage))
                now < job.readyAt -> reject(OriginWaiterRejection.PREPARING)
                dishId != job.dish.id -> reject(OriginWaiterRejection.WRONG_DISH)
                else -> changed(job.copy(stage = OriginWaiterStage.DELIVER))
            }
        }
    }

    fun assemble(playerId: UUID, requestId: UUID, venueId: String?, ingredient: String, expectedIndex: Int, now: Long): OriginWaiterStep =
        advance(playerId, requestId, venueId, now) { job ->
            when {
                carried(playerId) != null -> reject(OriginWaiterRejection.ALREADY_CARRYING)
                job.stage != OriginWaiterStage.ASSEMBLE || job.assembled != expectedIndex -> reject(OriginWaiterRejection.STALE_ACTION)
                now < job.readyAt -> reject(OriginWaiterRejection.PREPARING)
                job.recipe.getOrNull(job.assembled) != ingredient -> reject(OriginWaiterRejection.WRONG_INGREDIENT)
                else -> {
                    val complete = job.assembled + 1 == job.recipe.size
                    changed(job.copy(assembled = job.assembled + 1, stage = if (complete) OriginWaiterStage.PICKUP else job.stage,
                        readyAt = now + if (complete) 0 else actionMillis))
                }
            }
        }

    fun finishEating(playerId: UUID, requestId: UUID, now: Long): OriginWaiterStep =
        advance(playerId, requestId, current(playerId, requestId)?.target?.venueId, now) { job ->
            if (job.stage != OriginWaiterStage.EATING || now < job.readyAt) reject(OriginWaiterRejection.EATING)
            else changed(job.copy(stage = OriginWaiterStage.COLLECT))
        }

    fun wash(playerId: UUID, requestId: UUID, venueId: String?, action: OriginWaiterWash, expectedIndex: Int, now: Long): OriginWaiterStep =
        advance(playerId, requestId, venueId, now) { job ->
            when {
                job.stage !in setOf(OriginWaiterStage.RETURN_DISHES, OriginWaiterStage.WASH) || job.washed != expectedIndex -> reject(OriginWaiterRejection.STALE_ACTION)
                carried(playerId)?.id?.let { it != job.id } == true -> reject(OriginWaiterRejection.ALREADY_CARRYING)
                job.stage == OriginWaiterStage.WASH && now < job.readyAt -> reject(OriginWaiterRejection.PREPARING)
                job.washing.getOrNull(job.washed) != action -> reject(OriginWaiterRejection.WRONG_WASH_STEP)
                else -> {
                    val complete = job.washed + 1 == job.washing.size
                    changed(when {
                        complete && job.clearingPreviousMeal -> job.copy(stage = OriginWaiterStage.TAKE_ORDER,
                            clearingPreviousMeal = false, washed = 0, readyAt = 0)
                        complete && job.course == OriginWaiterCourse.DRINK -> job.copy(stage = OriginWaiterStage.PICKUP,
                            course = OriginWaiterCourse.MAIN, washed = 0, readyAt = now + preparationMillis)
                        else -> job.copy(washed = job.washed + 1,
                            stage = if (complete) OriginWaiterStage.PAYOUT else OriginWaiterStage.WASH,
                            readyAt = now + if (action == OriginWaiterWash.SOAK) soakMillis else actionMillis)
                    })
                }
            }
        }

    fun remake(playerId: UUID, requestId: UUID, staffId: Int, venueId: String?, now: Long): OriginWaiterStep =
        advance(playerId, requestId, venueId, now) { job ->
            when {
                staffId != job.target.staffId -> reject(OriginWaiterRejection.WRONG_STAFF)
                job.stage != OriginWaiterStage.REWORK -> reject(OriginWaiterRejection.PICKUP_FIRST)
                else -> changed(job.copy(stage = if (job.recipe.isEmpty()) OriginWaiterStage.PICKUP else OriginWaiterStage.ASSEMBLE,
                    assembled = 0, readyAt = now + preparationMillis, reworks = job.reworks + 1, quality = (job.quality - 15).coerceAtLeast(0)))
            }
        }

    fun beginDispute(playerId: UUID, kind: OriginWaiterDisputeKind, now: Long): OriginWaiterDispute? {
        val pair = tickets(playerId)
        if (pair.size != 2 || dispute(playerId) != null || pair.any { it.stage == OriginWaiterStage.PAYOUT || it.carries }) return null
        return OriginWaiterDispute(pair[0].id, pair[1].id, now, kind).also { disputes[playerId] = it }
    }

    fun disputeGuest(playerId: UUID): OriginWaiterJob? {
        val dispute = dispute(playerId) ?: return null
        val id = when (dispute.stage) {
            OriginWaiterDisputeStage.FIRST_SIDE -> dispute.first
            OriginWaiterDisputeStage.SECOND_SIDE -> dispute.second
            OriginWaiterDisputeStage.WATER -> return null
            OriginWaiterDisputeStage.BRING_WATER -> if (dispute.kind == OriginWaiterDisputeKind.SPILLED_DRINK) dispute.first else dispute.second
            OriginWaiterDisputeStage.REASSURE -> if (dispute.kind == OriginWaiterDisputeKind.SPILLED_DRINK) dispute.second else dispute.first
        }
        return current(playerId, id)
    }

    /** Ordered NPC visits and a physical water handoff; an incorrect target never consumes the water. */
    fun calm(playerId: UUID, npcId: Int, now: Long): Boolean {
        val dispute = dispute(playerId) ?: return false
        val expected = if (dispute.stage == OriginWaiterDisputeStage.WATER) current(playerId, dispute.first)?.target?.staffId
            else disputeGuest(playerId)?.target?.guestId
        if (npcId != expected) return false
        if (dispute.stage == OriginWaiterDisputeStage.REASSURE) endDispute(playerId, now)
        else disputes[playerId] = dispute.copy(stage = OriginWaiterDisputeStage.entries[dispute.stage.ordinal + 1])
        return true
    }

    private fun endDispute(playerId: UUID, now: Long) {
        val dispute = disputes.remove(playerId) ?: return
        val paused = (now - dispute.startedAt).coerceAtLeast(0)
        tickets(playerId).forEach { job ->
            jobs.getValue(playerId)[job.id] = job.copy(readyAt = job.readyAt + paused,
                expiresAt = minOf(job.expiresAt + paused, job.hardStopAt))
        }
    }

    private fun stageRejection(stage: OriginWaiterStage) = when (stage) {
        OriginWaiterStage.TAKE_ORDER -> OriginWaiterRejection.TAKE_ORDER_FIRST
        OriginWaiterStage.CLARIFY -> OriginWaiterRejection.CLARIFY_FIRST
        OriginWaiterStage.ASSEMBLE -> OriginWaiterRejection.ASSEMBLE_FIRST
        OriginWaiterStage.REWORK -> OriginWaiterRejection.REWORK_REQUIRED
        OriginWaiterStage.EATING -> OriginWaiterRejection.EATING
        OriginWaiterStage.COLLECT -> OriginWaiterRejection.COLLECT_FIRST
        OriginWaiterStage.RETURN_DISHES, OriginWaiterStage.WASH -> OriginWaiterRejection.WASH_FIRST
        OriginWaiterStage.PAYOUT -> OriginWaiterRejection.PAYOUT_PENDING
        else -> OriginWaiterRejection.PICKUP_FIRST
    }

    private fun advance(
        playerId: UUID, requestId: UUID?, venueId: String?, now: Long, action: (OriginWaiterJob) -> OriginWaiterStep,
    ): OriginWaiterStep {
        val job = requestId?.let { current(playerId, it) } ?: return reject(OriginWaiterRejection.NO_JOB)
        if (job.stage == OriginWaiterStage.PAYOUT) return reject(OriginWaiterRejection.PAYOUT_PENDING)
        if (venueId != job.target.venueId) return reject(OriginWaiterRejection.WRONG_VENUE)
        if (dispute(playerId) != null) return reject(OriginWaiterRejection.DISPUTE)
        if (now >= job.expiresAt) return reject(OriginWaiterRejection.EXPIRED)
        return action(job).also { if (it is OriginWaiterStep.Changed) jobs.getValue(playerId)[job.id] = it.job }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginWaiterJob? =
        current(playerId, requestId)?.takeIf { it.stage == OriginWaiterStage.PAYOUT }?.let { cancel(playerId, requestId) }

    fun cancel(playerId: UUID, requestId: UUID): OriginWaiterJob? {
        // Releasing either guest must also free the surviving ticket from a paused dispute.
        if (disputes[playerId]?.let { requestId == it.first || requestId == it.second } == true) endDispute(playerId, disputes.getValue(playerId).startedAt)
        return jobs[playerId]?.remove(requestId)?.also { if (jobs[playerId].isNullOrEmpty()) jobs.remove(playerId) }
    }
    fun cancel(playerId: UUID): OriginWaiterJob? = current(playerId)?.let { cancel(playerId, it.id) }
    fun clear(): List<Pair<UUID, OriginWaiterJob>> = entries().also { jobs.clear(); disputes.clear() }
    private fun changed(job: OriginWaiterJob) = OriginWaiterStep.Changed(job)
    private fun reject(reason: OriginWaiterRejection) = OriginWaiterStep.Rejected(reason)
}
