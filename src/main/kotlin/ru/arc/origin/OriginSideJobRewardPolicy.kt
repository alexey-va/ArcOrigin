package ru.arc.origin

import ru.arc.config.Config

/** Immutable for one module lifecycle, including delayed payment acknowledgements. */
internal data class OriginSideJobRewardPolicy(
    val dailyOrderLimit: Int = SIDE_JOB_DAILY_LIMIT,
    val rewardPerOrder: Int = 20,
    val saddlingRewardPerOrder: Int = 50,
) {
    init {
        require(dailyOrderLimit in 1..SIDE_JOB_MAX_RECEIPTS) { "daily-order-limit must be in 1..$SIDE_JOB_MAX_RECEIPTS" }
        require(rewardPerOrder in 1..1_000_000) { "reward-per-order must be in 1..1000000" }
        require(saddlingRewardPerOrder in 1..1_000_000) { "saddling-reward-per-order must be in 1..1000000" }
        require(dailyOrderLimit.toLong() * maxOf(rewardPerOrder, saddlingRewardPerOrder) <= Int.MAX_VALUE) { "Daily reward total exceeds the score range" }
    }

    val dailyAmount: Int get() = dailyOrderLimit * maxOf(rewardPerOrder, saddlingRewardPerOrder)

    fun amountFor(variant: OriginOddJobVariant): Int = if (variant.isYardPreparation()) saddlingRewardPerOrder else rewardPerOrder

    fun limits(text: String): String = text
        .replace("{daily-orders}", dailyOrderLimit.toString())
        .replace("{daily-amount}", dailyAmount.toString())
        .replace("{saddling-amount}", saddlingRewardPerOrder.toString())

    companion object {
        fun load(source: Config): OriginSideJobRewardPolicy {
            fun integer(key: String, fallback: Int): Int = requireNotNull(source.string(key, fallback.toString()).toIntOrNull()) {
                "$key must be a whole number"
            }
            return OriginSideJobRewardPolicy(
                dailyOrderLimit = integer("daily-order-limit", SIDE_JOB_DAILY_LIMIT),
                rewardPerOrder = integer("reward-per-order", 20),
                saddlingRewardPerOrder = integer("saddling-reward-per-order", 50),
            )
        }
    }
}
