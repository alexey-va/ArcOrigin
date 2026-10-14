package ru.arc.origin.mountyard

import java.util.UUID

internal data class MountYardCareSession(
    val id: UUID,
    val expiresAtMillis: Long,
    val requiredAnimals: Int,
    val animals: Set<UUID> = emptySet(),
    val claiming: Boolean = false,
) {
    val ready: Boolean get() = animals.size >= requiredAnimals
}

/** Short-lived objective progress; the reward and its daily limit have a separate durable owner. */
internal class MountYardCareProgress(
    private val requiredAnimals: Int,
    private val lifetimeMillis: Long,
) {
    private val sessions = mutableMapOf<UUID, MountYardCareSession>()
    private val statusRequests = mutableMapOf<UUID, UUID>()

    init {
        require(requiredAnimals > 0 && lifetimeMillis > 0)
    }

    fun current(player: UUID, now: Long): MountYardCareSession? {
        val session = sessions[player] ?: return null
        if (now >= session.expiresAtMillis) {
            sessions.remove(player)
            return null
        }
        return session
    }

    fun begin(player: UUID, now: Long): MountYardCareSession = current(player, now)
        ?: MountYardCareSession(UUID.randomUUID(), Math.addExact(now, lifetimeMillis), requiredAnimals).also { sessions[player] = it }

    fun pet(player: UUID, animal: UUID, now: Long): MountYardCareSession? {
        val session = current(player, now) ?: return null
        if (session.claiming || session.ready || animal in session.animals) return session
        return session.copy(animals = session.animals + animal).also { sessions[player] = it }
    }

    fun claim(player: UUID, now: Long): MountYardCareSession? {
        val session = current(player, now)?.takeIf { it.ready && !it.claiming } ?: return null
        return session.copy(claiming = true).also { sessions[player] = it }
    }

    fun retry(player: UUID, requestId: UUID): Boolean {
        val session = sessions[player]?.takeIf { it.id == requestId && it.claiming } ?: return false
        sessions[player] = session.copy(claiming = false)
        return true
    }

    fun complete(player: UUID, requestId: UUID): Boolean {
        if (sessions[player]?.id != requestId) return false
        sessions.remove(player)
        return true
    }

    fun requestStatus(player: UUID): UUID? {
        if (player in statusRequests) return null
        return UUID.randomUUID().also { statusRequests[player] = it }
    }

    fun finishStatus(player: UUID, requestId: UUID): Boolean = statusRequests.remove(player, requestId)

    fun clearPlayer(player: UUID) {
        sessions.remove(player)
        statusRequests.remove(player)
    }

    fun clear() {
        sessions.clear()
        statusRequests.clear()
    }
}
