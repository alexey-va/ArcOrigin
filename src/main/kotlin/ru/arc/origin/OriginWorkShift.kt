package ru.arc.origin

import java.util.UUID

internal enum class OriginWorkShiftPhase { OPENING, RUSH, CLOSING }

/** Transient shift score only. Each completed order retains its own durable payment UUID. */
internal data class OriginWorkShift(
    val id: UUID,
    val startedAt: Long,
    val closesAt: Long,
    val hardStopAt: Long,
    val completedOrderIds: Set<UUID> = emptySet(),
    val earnedAmount: Int = 0,
    val totalQuality: Int = 0,
    val finishRequested: Boolean = false,
) {
    val completedOrders: Int get() = completedOrderIds.size
    val quality: Int get() = if (completedOrders == 0) 0 else totalQuality / completedOrders

    fun acceptsOrders(now: Long): Boolean = !finishRequested && now < closesAt

    fun phase(now: Long): OriginWorkShiftPhase = when {
        finishRequested || now >= startedAt + (closesAt - startedAt) * 4 / 5 -> OriginWorkShiftPhase.CLOSING
        now >= startedAt + (closesAt - startedAt) / 4 -> OriginWorkShiftPhase.RUSH
        else -> OriginWorkShiftPhase.OPENING
    }

    /** An accepted last order has a bounded grace period; idle time never earns money. */
    fun shouldFinish(now: Long, hasActiveOrders: Boolean): Boolean =
        now >= hardStopAt || (!hasActiveOrders && !acceptsOrders(now))

    fun requestFinish(): OriginWorkShift = copy(finishRequested = true)

    /** Record only confirmed payment outcomes. Duplicate callbacks cannot inflate the receipt. */
    fun record(orderId: UUID, amount: Int, quality: Int): OriginWorkShift {
        require(amount >= 0 && quality in 0..100)
        if (orderId in completedOrderIds) return this
        return copy(
            completedOrderIds = completedOrderIds + orderId,
            earnedAmount = Math.addExact(earnedAmount, amount),
            totalQuality = Math.addExact(totalQuality, quality),
        )
    }

    companion object {
        fun begin(now: Long, durationMillis: Long = 600_000L, graceMillis: Long = 120_000L): OriginWorkShift {
            require(durationMillis in 480_000L..600_000L && graceMillis in 0L..120_000L)
            val closesAt = Math.addExact(now, durationMillis)
            return OriginWorkShift(UUID.randomUUID(), now, closesAt, Math.addExact(closesAt, graceMillis))
        }
    }
}
