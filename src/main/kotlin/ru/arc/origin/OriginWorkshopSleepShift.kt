package ru.arc.origin

import java.util.UUID

/** One resting worker; an accepted game pins that shift until its exact owner leaves. */
internal class OriginWorkshopSleepShift(private val tables: List<String>, private val durationTicks: Long) {
    init {
        require(tables.isNotEmpty() && tables.distinct().size == tables.size)
        require(durationTicks in 200L..72_000L)
    }

    var index: Int = 0
        private set
    var ready: Boolean = false
        private set
    private var due = Long.MAX_VALUE
    private var player: UUID? = null
    var forcedNextIndex: Int? = null
        private set
    val table: String get() = tables[index]

    fun resting(now: Long, workerIndex: Int = index) {
        check(!ready)
        require(workerIndex in tables.indices)
        index = workerIndex
        ready = true
        if (forcedNextIndex == workerIndex) {
            forcedNextIndex = null
            due = now + durationTicks
        } else {
            due = if (forcedNextIndex == null) now + durationTicks else now
        }
    }

    fun acquire(tableId: String, playerId: UUID): Boolean {
        if (!ready || tableId != table || player != null) return false
        player = playerId
        return true
    }

    fun owns(tableId: String, playerId: UUID): Boolean = ready && tableId == table && player == playerId

    fun release(tableId: String, playerId: UUID) {
        if (tableId == table && player == playerId) player = null
    }

    fun rotationDue(now: Long): Boolean = ready && player == null && now >= due

    /** Queue an exact next sleeper and expire the current timer without touching its lease. */
    fun forceNext(workerIndex: Int, now: Long) {
        check(ready) { "A workshop shift must be ready before forcing its next worker" }
        require(workerIndex in tables.indices && workerIndex != index)
        forcedNextIndex = workerIndex
        due = now
    }

    /** An override never falls through to another worker while its target is unavailable or returning. */
    fun nextWorkerIndex(available: Set<Int>, returning: Set<Int>): Int? {
        check(ready) { "A workshop shift must be ready before selecting its replacement" }
        forcedNextIndex?.let { requested ->
            return requested.takeIf { it in available && it !in returning }
        }
        return (1 until tables.size)
            .asSequence()
            .map { (index + it) % tables.size }
            .firstOrNull { it in available && it !in returning }
    }

    /** Commit only after the replacement's sleeping pose has been confirmed. */
    fun replaceWith(workerIndex: Int, now: Long) {
        check(rotationDue(now)) { "An occupied or unexpired workshop cannot change shift" }
        require(workerIndex in tables.indices && workerIndex != index)
        check(forcedNextIndex == null || forcedNextIndex == workerIndex) {
            "A forced workshop shift must select its requested worker"
        }
        index = workerIndex
        due = now + durationTicks
        forcedNextIndex = null
    }

    fun invalidate() {
        ready = false
        player = null
    }
}
