package ru.arc.origin.scene

import java.util.UUID

internal data class OriginSceneLease(
    val token: UUID,
    val sceneId: String,
    val cycleId: String,
    val actorIds: Set<Int>,
)

/**
 * Owns cross-scene actor exclusion and cycle cadence.
 *
 * A lease is tokened so delayed completion from an interrupted cycle cannot
 * unlock an actor that has already been acquired by a newer cycle.
 */
internal class OriginSceneCoordinator {
    private val actorOwners = mutableMapOf<Int, UUID>()
    private val leases = mutableMapOf<UUID, OriginSceneLease>()
    private val dueAt = mutableMapOf<Pair<String, String>, Long>()

    fun tryAcquire(
        sceneId: String,
        cycleId: String,
        actorIds: Set<Int>,
        nowMillis: Long,
        ignoreDue: Boolean = false,
    ): OriginSceneLease? {
        require(sceneId.isNotBlank() && cycleId.isNotBlank())
        require(actorIds.isNotEmpty())
        if ((!ignoreDue && !isDue(sceneId, cycleId, nowMillis)) || actorIds.any(actorOwners::containsKey)) return null
        val lease = OriginSceneLease(UUID.randomUUID(), sceneId, cycleId, actorIds.toSet())
        leases[lease.token] = lease
        actorIds.forEach { actorOwners[it] = lease.token }
        return lease
    }

    fun release(lease: OriginSceneLease, nowMillis: Long, cooldownMillis: Long) {
        if (leases.remove(lease.token) != lease) return
        lease.actorIds.forEach { actorId -> actorOwners.remove(actorId, lease.token) }
        dueAt[lease.sceneId to lease.cycleId] = nowMillis + cooldownMillis.coerceAtLeast(0L)
    }

    fun delay(sceneId: String, cycleId: String, untilMillis: Long) {
        dueAt[sceneId to cycleId] = untilMillis
    }

    fun isDue(sceneId: String, cycleId: String, nowMillis: Long): Boolean =
        nowMillis >= dueAt.getOrDefault(sceneId to cycleId, 0L)

    fun busyActors(): Set<Int> = actorOwners.keys.toSet()

    fun cooldownRemainingMillis(sceneId: String, cycleId: String, nowMillis: Long): Long =
        (dueAt.getOrDefault(sceneId to cycleId, 0L) - nowMillis).coerceAtLeast(0L)

    /** Oldest work goes first; a waiting joint cycle keeps its place for both actors. */
    fun readyCycleIds(sceneId: String, actorsByCycle: Map<String, Set<Int>>, nowMillis: Long): List<String> {
        val waitingActors = mutableSetOf<Int>()
        return actorsByCycle.keys
            .filter { isDue(sceneId, it, nowMillis) }
            .sortedWith(compareBy<String> { dueAt.getOrDefault(sceneId to it, 0L) }.thenBy { it })
            .filter { cycleId ->
                val actors = actorsByCycle.getValue(cycleId)
                val behindEarlierWork = actors.any(waitingActors::contains)
                if (!behindEarlierWork) waitingActors.addAll(actors)
                !behindEarlierWork && actors.none(actorOwners::containsKey)
            }
    }

    fun clear() {
        actorOwners.clear()
        leases.clear()
        dueAt.clear()
    }
}

internal fun originSceneReturnActorIds(
    actorIds: Set<Int>,
    mountedPairs: Set<Pair<Int, Int>>,
    keepMounted: Boolean,
): Set<Int> = if (keepMounted) actorIds - mountedPairs.map { it.first }.toSet() else actorIds
