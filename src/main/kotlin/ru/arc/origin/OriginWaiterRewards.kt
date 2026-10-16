package ru.arc.origin

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.origin.api.ArcOriginEconomy
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** [available] is technical readiness; quota, cooldown and unresolved payment are separate gates. */
internal data class WaiterRewardStatus(
    val available: Boolean,
    val remainingOrders: Int,
    val nextAtMillis: Long,
    val unresolved: Boolean,
)

internal enum class WaiterRewardOutcome {
    PAID, ALREADY_PAID, COOLDOWN, DAILY_LIMIT, UNCONFIRMED, UNAVAILABLE, CANCELLED,
}

/** The last request is the reserved payment while [pending]; earlier requests are settled receipts. */
internal data class WaiterRewardRecord(
    val day: Long,
    val count: Int,
    val nextAt: Long,
    val requests: List<String>,
    val pending: Boolean,
) {
    fun validate() {
        require(day in 0..MAX_TIMESTAMP / DAY_MILLIS)
        require(count in 1..WAITER_DAILY_LIMIT && requests.size == count)
        require(nextAt in 1..MAX_TIMESTAMP && nextAt / DAY_MILLIS >= day)
        require(requests.toSet().size == requests.size)
        require(requests.all { UUID.fromString(it).toString() == it })
    }
}

internal const val WAITER_REWARD_KEY = "arc.origin-waiter-rewards.v1"
internal const val WAITER_DAILY_LIMIT = 20
internal const val WAITER_PAYOUT_INTERVAL_MILLIS = 20_000L
private const val DAY_MILLIS = 86_400_000L
private const val MAX_TIMESTAMP = 253_402_300_799_999L

internal fun waiterRewardCodec() = BoundedJsonCodec(
    GsonBuilder().create(), WaiterRewardRecord::class.java,
    object : JsonRootContract {
        override fun validate(value: JsonElement) {
            JsonObjectContract(setOf("day", "count", "nextAt", "requests", "pending")).validate(value)
            val fields = value.asJsonObject
            for (name in listOf("day", "count", "nextAt")) {
                val number = fields.get(name)
                require(number.isJsonPrimitive && number.asJsonPrimitive.isNumber)
                require(number.asString.matches(Regex("0|[1-9][0-9]{0,14}")))
                val integer = requireNotNull(number.asString.toLongOrNull())
                require(when (name) {
                    "count" -> integer in 1..WAITER_DAILY_LIMIT
                    "day" -> integer in 0..MAX_TIMESTAMP / DAY_MILLIS
                    else -> integer in 1..MAX_TIMESTAMP
                })
            }
            val requests = fields.get("requests")
            require(requests.isJsonArray && requests.asJsonArray.size() in 1..WAITER_DAILY_LIMIT)
            require(requests.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            val pending = fields.get("pending")
            require(pending.isJsonPrimitive && pending.asJsonPrimitive.isBoolean)
        }
    },
    JsonResourceBounds(maxCharacters = 1_600, maxDepth = 3, maxContainerEntries = WAITER_DAILY_LIMIT,
        maxTotalNodes = 28, maxStringCharacters = 36),
) { it.validate() }

/**
 * One shared UTC-day quota for both venues. Call [status], [pay] and [close] on
 * the server thread; storage futures may complete elsewhere. Neither storage nor
 * shutdown waits for I/O. Provider access, [pay]'s validity check and callback
 * stay on the server thread; stale UI callbacks are discarded.
 *
 * A durable pending record precedes the provider call. The host's Boolean true
 * confirms provider acceptance, not its later persistence. False, exceptions
 * and unknown completion retain the reservation indefinitely, including across
 * UTC rollover; they must never trigger an automatic deposit replay. Only a
 * proven pre-call cancellation may restore the exact previous record.
 */
internal class OriginWaiterRewards(
    private val redis: RedisOperations? = ArcOriginPlugin.instance.redisClient,
    private val economy: () -> ArcOriginEconomy? = { OriginServices.economy },
    scheduler: TaskScheduler = Tasks.scheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = ArcOriginPlugin.instance.logger,
) : AutoCloseable {
    val amount: Int = 20
    private val codec = waiterRewardCodec()
    private val updater = redis?.let { RedisHashUpdater(it, WAITER_REWARD_KEY, codec, 12) }
    private val tasks = LifecycleTaskScope(scheduler, true)
    private val inFlight = ConcurrentHashMap<UUID, Payment>()
    @Volatile private var closed = false

    private data class Reservation(val before: WaiterRewardRecord?, val claim: WaiterRewardRecord)

    private class Payment(
        val playerId: UUID,
        val requestId: UUID,
        val token: LifecycleTaskScope.Token,
        val stillValid: () -> Boolean,
        val callback: (WaiterRewardOutcome) -> Unit,
    ) {
        @Volatile var reservation: Reservation? = null
        @Volatile var attempted = false
        val releaseStarted = AtomicBoolean()
    }

    fun status(playerId: UUID): CompletableFuture<WaiterRewardStatus> {
        val providerAvailable = !closed && provider(playerId) != null
        val storage = redis ?: return CompletableFuture.completedFuture(WaiterRewardStatus(false, 0, 0L, false))
        return storageFuture { storage.loadMapEntries(WAITER_REWARD_KEY, playerId.toString()) }.handle { values, failure ->
            if (failure != null) {
                warn("status", playerId, null, failure)
                WaiterRewardStatus(false, 0, 0L, true)
            } else try {
                require(values.size == 1)
                val record = values.single()?.let(codec::decode)
                val now = clock()
                val remaining = WAITER_DAILY_LIMIT - if (record == null || !record.pending && now / DAY_MILLIS > record.day) 0 else record.count
                val unresolved = record?.pending == true || inFlight.containsKey(playerId)
                val nextAt = record?.nextAt ?: 0L
                WaiterRewardStatus(providerAvailable && !closed, remaining, nextAt, unresolved)
            } catch (error: Exception) {
                warn("decode-status", playerId, null, error)
                WaiterRewardStatus(false, 0, 0L, true)
            }
        }
    }

    fun pay(playerId: UUID, requestId: UUID, stillValid: () -> Boolean, callback: (WaiterRewardOutcome) -> Unit) {
        if (closed || !stillValid()) {
            callback(WaiterRewardOutcome.CANCELLED)
            return
        }
        val store = updater
        if (store == null || provider(playerId) == null) {
            callback(WaiterRewardOutcome.UNAVAILABLE)
            return
        }
        val payment = Payment(playerId, requestId, tasks.token(), stillValid, callback)
        if (inFlight.putIfAbsent(playerId, payment) != null) {
            callback(WaiterRewardOutcome.UNCONFIRMED)
            return
        }
        val now = clock()
        storageFuture {
            store.update(playerId.toString()) { current ->
                if (rejection(current, requestId, now) != null) RedisHashDecision.Reject
                else {
                    val requests = current?.takeIf { it.day >= now / DAY_MILLIS }?.requests.orEmpty() + requestId.toString()
                    RedisHashDecision.Write(WaiterRewardRecord(now / DAY_MILLIS, requests.size,
                        Math.addExact(now, WAITER_PAYOUT_INTERVAL_MILLIS), requests, pending = true))
                }
            }
        }.whenComplete { result, failure ->
            val changed = result as? RedisHashUpdateResult.Changed
            val changedAfter = changed?.after
            if (failure != null) {
                warn("reserve", playerId, requestId, failure)
                dispatch(payment) { finish(payment, WaiterRewardOutcome.UNCONFIRMED) }
            } else if (changed != null && changedAfter?.pending == true && changedAfter.requests.last() == requestId.toString()) {
                payment.reservation = Reservation(changed.before, changedAfter)
                dispatch(payment) { deposit(payment) }
            } else {
                if (result is RedisHashUpdateResult.Contended) warn("reserve-contended", playerId, requestId, null)
                val current = when (result) {
                    is RedisHashUpdateResult.Rejected -> result.current
                    is RedisHashUpdateResult.Unchanged -> result.current
                    else -> null
                }
                dispatch(payment) { finish(payment, rejection(current, requestId, clock()) ?: WaiterRewardOutcome.UNAVAILABLE) }
            }
        }
    }

    private fun deposit(payment: Payment) {
        if (!payment.stillValid()) {
            releaseUnused(payment, WaiterRewardOutcome.CANCELLED)
            return
        }
        val provider = provider(payment.playerId)
        if (provider == null) {
            releaseUnused(payment, WaiterRewardOutcome.UNAVAILABLE)
            return
        }
        val claim = requireNotNull(payment.reservation).claim
        payment.attempted = true
        val accepted = try {
            // This is audit text, not a provider idempotency key. Never retry this call.
            provider.depositDefaultCurrency(payment.playerId, amount.toDouble(), "arc-restaurant-waiter:${payment.requestId}")
        } catch (error: Exception) {
            warn("provider-unconfirmed", payment.playerId, payment.requestId, error)
            finish(payment, WaiterRewardOutcome.UNCONFIRMED)
            return
        }
        if (!accepted) {
            warn("provider-unconfirmed", payment.playerId, payment.requestId, null)
            finish(payment, WaiterRewardOutcome.UNCONFIRMED)
            return
        }
        // A delayed reservation must not shorten the interval between actual payments.
        // Pending blocks peers until this timestamp and the receipt are acknowledged.
        storageFuture {
            val now = clock()
            val requests = if (now / DAY_MILLIS > claim.day) listOf(payment.requestId.toString()) else claim.requests
            val settled = claim.copy(day = maxOf(claim.day, now / DAY_MILLIS), count = requests.size,
                requests = requests, nextAt = maxOf(claim.nextAt, Math.addExact(now, WAITER_PAYOUT_INTERVAL_MILLIS)), pending = false)
            requireNotNull(updater).update(payment.playerId.toString()) { current ->
                when (current) {
                    claim, settled -> RedisHashDecision.Write(settled)
                    else -> RedisHashDecision.Reject
                }
            }.thenApply { result ->
                result is RedisHashUpdateResult.Changed && result.after == settled
                    || result is RedisHashUpdateResult.Unchanged && result.current == settled
            }
        }.whenComplete { confirmed, failure ->
            if (failure != null || confirmed != true) warn("settle-unconfirmed", payment.playerId, payment.requestId, failure)
            else logger.info("ORIGIN_WAITER reward=ACCEPTED player=${payment.playerId} request=${payment.requestId} currency=vault amount=$amount")
            dispatch(payment) { finish(payment, if (confirmed == true) WaiterRewardOutcome.PAID else WaiterRewardOutcome.UNCONFIRMED) }
        }
    }

    private fun releaseUnused(payment: Payment, outcome: WaiterRewardOutcome = WaiterRewardOutcome.CANCELLED) {
        val reservation = payment.reservation ?: return
        if (payment.attempted || !payment.releaseStarted.compareAndSet(false, true)) return
        storageFuture {
            requireNotNull(updater).update(payment.playerId.toString()) { current ->
                when {
                    current != reservation.claim -> RedisHashDecision.Reject
                    reservation.before != null -> RedisHashDecision.Write(reservation.before)
                    else -> RedisHashDecision.Delete
                }
            }
        }.whenComplete { result, failure ->
            val released = result is RedisHashUpdateResult.Changed && result.after == reservation.before
            if (failure != null || !released) warn("cancel-retained", payment.playerId, payment.requestId, failure)
            dispatch(payment) { finish(payment, if (released) outcome else WaiterRewardOutcome.UNCONFIRMED) }
        }
    }

    private fun rejection(record: WaiterRewardRecord?, requestId: UUID, now: Long): WaiterRewardOutcome? = when {
        record == null -> null
        requestId.toString() in record.requests && (!record.pending || record.requests.last() != requestId.toString()) -> WaiterRewardOutcome.ALREADY_PAID
        record.pending -> WaiterRewardOutcome.UNCONFIRMED
        now / DAY_MILLIS <= record.day && record.count >= WAITER_DAILY_LIMIT -> WaiterRewardOutcome.DAILY_LIMIT
        now < record.nextAt -> WaiterRewardOutcome.COOLDOWN
        else -> null
    }

    private fun provider(playerId: UUID): ArcOriginEconomy? = try {
        economy()?.takeIf { it.isAvailable() }
    } catch (error: Exception) {
        warn("provider-unavailable", playerId, null, error)
        null
    }

    private fun dispatch(payment: Payment, action: () -> Unit) {
        if (closed) {
            discard(payment)
            return
        }
        try {
            if (tasks.runSync(payment.token) {
                if (inFlight[payment.playerId] === payment) action()
            } == null) discard(payment)
        } catch (error: Exception) {
            warn("schedule", payment.playerId, payment.requestId, error)
            discard(payment)
        }
    }

    private fun discard(payment: Payment) {
        releaseUnused(payment)
        inFlight.remove(payment.playerId, payment)
    }

    private fun finish(payment: Payment, outcome: WaiterRewardOutcome) {
        if (inFlight.remove(payment.playerId, payment) && !closed && payment.stillValid()) payment.callback(outcome)
    }

    private fun <T> storageFuture(action: () -> CompletableFuture<T>): CompletableFuture<T> = try {
        action()
    } catch (error: Exception) {
        CompletableFuture.failedFuture(error)
    }

    private fun warn(phase: String, playerId: UUID, requestId: UUID?, failure: Throwable?) {
        logger.log(Level.WARNING, "ORIGIN_WAITER phase=$phase player=$playerId request=$requestId retry=none", failure)
    }

    override fun close() {
        closed = true
        try {
            tasks.close()
        } finally {
            inFlight.values.forEach(::discard)
        }
    }
}
