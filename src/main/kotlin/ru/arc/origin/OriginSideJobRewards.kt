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

/** Audit attribution only; every kind shares the same quota and request identities. */
internal enum class OriginSideJobKind(val auditPrefix: String) {
    WAITER("arc-restaurant-waiter"), MOUNTYARD("arc-mountyard-job"), FORGE("arc-forge-job"), AUCTION("arc-auction-job"),
}

internal data class SideJobRewardStatus(val available: Boolean, val remainingOrders: Int,
    val nextAtMillis: Long, val unresolved: Boolean, val recoverableShiftId: UUID? = null)

internal enum class SideJobRewardOutcome { CREDITED, PAID, ALREADY_PAID, RETRY, DAILY_LIMIT, UNCONFIRMED, UNAVAILABLE, CANCELLED }

internal data class SideJobRewardResult(val outcome: SideJobRewardOutcome, val earnings: SideJobShiftEarnings? = null)

/** Frozen earnings, not a new roll at cash-out. IDs deduplicate delayed acknowledgements across midnight. */
internal data class SideJobShiftEarnings(val id: String, val kind: OriginSideJobKind, val orders: List<String>,
    val amount: Long, val totalQuality: Int) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(orders.size in 1..SIDE_JOB_MAX_RECEIPTS * 2 && orders.toSet().size == orders.size)
        require(orders.all { UUID.fromString(it).toString() == it })
        require(amount in 1..Int.MAX_VALUE.toLong() * 2 && totalQuality in 0..orders.size * 100)
    }
}

/** Legacy pending is deliberately unchanged: an uncertain provider call must never be replayed. */
internal data class SideJobRewardRecord(val day: Long, val count: Int, val nextAt: Long, val requests: List<String>,
    val pending: Boolean, val earnings: SideJobShiftEarnings? = null, val receipt: SideJobShiftEarnings? = null) {
    fun validate() {
        require(day in 0..MAX_TIMESTAMP / DAY_MILLIS)
        require(count in 1..SIDE_JOB_MAX_RECEIPTS && requests.size == count)
        require(nextAt in 1..MAX_TIMESTAMP && nextAt / DAY_MILLIS >= day)
        require(requests.toSet().size == requests.size && requests.all { UUID.fromString(it).toString() == it })
        earnings?.validate(); receipt?.validate()
        require(earnings == null || earnings.id != receipt?.id)
    }
}

internal const val SIDE_JOB_REWARD_KEY = "arc.origin-waiter-rewards.v1"
internal const val SIDE_JOB_DAILY_LIMIT = 100
internal const val SIDE_JOB_MAX_RECEIPTS = 10_000
private const val DAY_MILLIS = 86_400_000L
private const val MAX_TIMESTAMP = 253_402_300_799_999L

internal fun sideJobRewardCodec() = BoundedJsonCodec(
    GsonBuilder().create(), SideJobRewardRecord::class.java,
    object : JsonRootContract {
        override fun validate(value: JsonElement) {
            val legacy = setOf("day", "count", "nextAt", "requests", "pending")
            JsonObjectContract(legacy + setOf("earnings", "receipt"), legacy).validate(value)
            val fields = value.asJsonObject
            fun integer(node: JsonElement): Long {
                require(node.isJsonPrimitive && node.asJsonPrimitive.isNumber && node.asString.matches(Regex("0|[1-9][0-9]{0,14}")))
                return requireNotNull(node.asString.toLongOrNull())
            }
            require(integer(fields.get("day")) in 0..MAX_TIMESTAMP / DAY_MILLIS)
            require(integer(fields.get("count")) in 1..SIDE_JOB_MAX_RECEIPTS)
            require(integer(fields.get("nextAt")) in 1..MAX_TIMESTAMP)
            fun strings(node: JsonElement) {
                require(node.isJsonArray && node.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            }
            strings(fields.get("requests"))
            require(fields.get("pending").let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean })
            for (name in listOf("earnings", "receipt")) fields.get(name)?.let { node ->
                JsonObjectContract(setOf("id", "kind", "orders", "amount", "totalQuality")).validate(node)
                val data = node.asJsonObject
                for (key in listOf("id", "kind")) require(data.get(key).let { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                require(OriginSideJobKind.entries.any { it.name == data.get("kind").asString })
                strings(data.get("orders"))
                require(integer(data.get("amount")) in 1..Int.MAX_VALUE.toLong() * 2)
                require(integer(data.get("totalQuality")) in 0..SIDE_JOB_MAX_RECEIPTS * 200L)
            }
        }
    },
    JsonResourceBounds(maxCharacters = SIDE_JOB_MAX_RECEIPTS * 200 + 1024, maxDepth = 4,
        maxContainerEntries = SIDE_JOB_MAX_RECEIPTS * 2, maxTotalNodes = SIDE_JOB_MAX_RECEIPTS * 5 + 30, maxStringCharacters = 36),
) { it.validate() }

/**
 * Completed orders atomically consume the UTC quota and accrue a durable shift balance.
 * Only finish/recovery deposits it, once. Storage is asynchronous; provider access and UI stay
 * on the server thread. A pending claim precedes the provider, whose Boolean acknowledges
 * acceptance, not downstream persistence. Unknown outcomes remain blocked across restart.
 */
internal class OriginSideJobRewards(
    private val redis: RedisOperations? = ArcOriginPlugin.instance.redisClient,
    private val economy: () -> ArcOriginEconomy? = { OriginServices.economy },
    scheduler: TaskScheduler = Tasks.scheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger = ArcOriginPlugin.instance.logger,
    private val policy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy(),
    private val captureRecovery: ((() -> Boolean) -> CompletableFuture<Unit>)? = null,
) : AutoCloseable {
    val amount: Int get() = policy.rewardPerOrder
    private val codec = sideJobRewardCodec()
    private val updater = redis?.let { RedisHashUpdater(it, SIDE_JOB_REWARD_KEY, codec, 12) }
    private val tasks = LifecycleTaskScope(scheduler, true)
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val inFlight = ConcurrentHashMap<UUID, Payment>()
    private val ownedTerminalWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()

    internal fun durableDrain(): CompletableFuture<Void> = CompletableFuture.allOf(*ownedTerminalWrites.toTypedArray())

    private fun trackTerminal(write: CompletableFuture<Unit>) {
        ownedTerminalWrites += write
        write.whenComplete { _, _ -> ownedTerminalWrites.remove(write) }
    }
    @Volatile private var closed = false
    @Volatile private var recovering = false

    private data class CreditWrite(val shiftId: UUID, val kind: OriginSideJobKind, val orderId: UUID,
        val amount: Int, val quality: Int, val day: Long, val at: Long, val done: CompletableFuture<Unit>)
    companion object {
        // The same process can replace module owners before their immutable Redis writes complete.
        private val creditWrites = ConcurrentHashMap<UUID, CreditWrite>()
        private val failedCredits = ConcurrentHashMap<UUID, Map<UUID, CreditWrite>>()
        private val settlements = ConcurrentHashMap<UUID, Payment>()

        /** Snapshot after module admission has closed; includes chained earned writes and terminal receipts. */
        fun shutdownDrain(): CompletableFuture<Void> = CompletableFuture.allOf(
            *(creditWrites.values.map { it.done } + settlements.values.map { it.done }).toTypedArray(),
        )
    }

    private data class Reservation(val before: SideJobRewardRecord, val claim: SideJobRewardRecord)
    private class Payment(val playerId: UUID, val shiftId: UUID?, val token: LifecycleTaskScope.Token,
        val callback: (SideJobRewardResult) -> Unit) {
        @Volatile var reservation: Reservation? = null
        @Volatile var attempted = false
        @Volatile var resolved = false
        val releaseStarted = AtomicBoolean()
        val done = CompletableFuture<Unit>()
    }

    /** Read-only lookup: the current shift distinguishes live work from a recoverable balance. */
    fun status(playerId: UUID, activeShiftId: UUID? = null): CompletableFuture<SideJobRewardStatus> {
        val ready = !closed && !recovering && provider(playerId) != null
        val storage = redis ?: return CompletableFuture.completedFuture(SideJobRewardStatus(false, 0, 0, false))
        return storageFuture { storage.loadMapEntries(SIDE_JOB_REWARD_KEY, playerId.toString()) }.handle { values, failure ->
            if (failure != null) {
                warn("status", playerId, null, failure); SideJobRewardStatus(false, 0, 0, true)
            } else try {
                require(values.size == 1)
                val record = values.single()?.let(codec::decode)
                val used = if (record == null || !record.pending && clock() / DAY_MILLIS > record.day) 0 else record.count
                val orphan = record?.earnings?.id?.let { it != activeShiftId?.toString() } == true
                val failed = failedCredits[playerId]?.values?.firstOrNull()
                val recoverable = if (record?.pending == true) null else failed?.shiftId?.takeIf { it != activeShiftId }
                    ?: record?.earnings?.id?.takeIf { orphan }?.let(UUID::fromString)
                SideJobRewardStatus(ready && !closed, (policy.dailyOrderLimit - used).coerceAtLeast(0), 0,
                    record?.pending == true || settlements.containsKey(playerId) || creditWrites.containsKey(playerId) || failed != null || orphan,
                    recoverable)
            } catch (error: Exception) {
                warn("decode-status", playerId, null, error); SideJobRewardStatus(false, 0, 0, true)
            }
        }
    }

    /** Only immutable, already-completed work enters this method; leaving must not cancel its write. */
    fun credit(kind: OriginSideJobKind, playerId: UUID, shiftId: UUID, requestId: UUID, quality: Int,
        callback: (SideJobRewardResult) -> Unit) {
        require(quality in 0..100)
        val store = updater
        if (closed || store == null) { callback(SideJobRewardResult(SideJobRewardOutcome.UNAVAILABLE)); return }
        if (settlements.containsKey(playerId)) { callback(SideJobRewardResult(SideJobRewardOutcome.RETRY)); return }
        val failed = failedCredits[playerId]?.get(requestId)
        val write = failed?.copy(done = CompletableFuture()) ?: CreditWrite(shiftId, kind, requestId, amount, quality,
            clock() / DAY_MILLIS, clock().coerceAtLeast(1), CompletableFuture())
        require(write.shiftId == shiftId && write.kind == kind)
        trackTerminal(write.done)
        val previous = creditWrites.put(playerId, write)
        val token = tasks.token(); val day = write.day; val now = write.at
        (previous?.done ?: CompletableFuture.completedFuture(Unit)).thenCompose { storageFuture {
            store.update(playerId.toString()) { current ->
                val earned = current?.earnings
                when {
                    current?.pending == true -> RedisHashDecision.Reject
                    earned != null && (earned.id != shiftId.toString() || earned.kind != kind) -> RedisHashDecision.Reject
                    requestId.toString() in earned?.orders.orEmpty() -> RedisHashDecision.Write(requireNotNull(current))
                    requestId.toString() in current?.requests.orEmpty() || current?.receipt?.id == shiftId.toString() -> RedisHashDecision.Reject
                    current != null && day <= current.day && current.count >= policy.dailyOrderLimit -> RedisHashDecision.Reject
                    else -> {
                        val requests = current?.takeIf { it.day >= day }?.requests.orEmpty() + requestId.toString()
                        val earnings = SideJobShiftEarnings(shiftId.toString(), kind, earned?.orders.orEmpty() + requestId.toString(),
                            Math.addExact(earned?.amount ?: 0, write.amount.toLong()), Math.addExact(earned?.totalQuality ?: 0, write.quality))
                        RedisHashDecision.Write(SideJobRewardRecord(maxOf(day, current?.day ?: day), requests.size,
                            maxOf(now, current?.nextAt ?: now), requests, false, earnings, current?.receipt))
                    }
                }
            }
        } }.whenComplete { result, failure ->
            val record = result.record()
            val earnings = record?.earnings?.takeIf { it.id == shiftId.toString() && requestId.toString() in it.orders }
            val outcome = when {
                failure != null || result is RedisHashUpdateResult.Contended -> SideJobRewardOutcome.RETRY
                earnings != null && record.pending == false -> SideJobRewardOutcome.CREDITED
                record?.pending == true -> SideJobRewardOutcome.UNCONFIRMED
                record != null && day <= record.day && record.count >= policy.dailyOrderLimit -> SideJobRewardOutcome.DAILY_LIMIT
                else -> SideJobRewardOutcome.UNCONFIRMED
            }
            failedCredits.compute(playerId) { _, current ->
                val updated = if (outcome == SideJobRewardOutcome.RETRY) current.orEmpty() + (requestId to write)
                    else current.orEmpty() - requestId
                updated.takeIf { it.isNotEmpty() }
            }
            creditWrites.remove(playerId, write)
            write.done.complete(Unit)
            if (failure != null) warn("credit", playerId, requestId, failure)
            dispatch(token) { callback(SideJobRewardResult(outcome, earnings)) }
        }
    }

    fun settle(playerId: UUID, shiftId: UUID?, callback: (SideJobRewardResult) -> Unit) {
        val store = updater
        if (closed || store == null) { callback(SideJobRewardResult(SideJobRewardOutcome.UNAVAILABLE)); return }
        creditWrites[playerId]?.let { write ->
            val token = tasks.token()
            write.done.whenComplete { _, _ -> dispatch(token) { settle(playerId, shiftId, callback) } }
            return
        }
        failedCredits[playerId]?.values?.firstOrNull()?.let { failed ->
            if (shiftId != null && failed.shiftId != shiftId) {
                callback(SideJobRewardResult(SideJobRewardOutcome.UNCONFIRMED)); return
            }
            credit(failed.kind, playerId, failed.shiftId, failed.orderId, failed.quality) { result ->
                if (result.outcome == SideJobRewardOutcome.CREDITED) settle(playerId, shiftId, callback)
                else callback(SideJobRewardResult(SideJobRewardOutcome.UNAVAILABLE, result.earnings))
            }
            return
        }
        val payment = Payment(playerId, shiftId, tasks.token(), callback)
        settlements.putIfAbsent(playerId, payment)?.let { previous ->
            previous.done.whenComplete { _, _ -> dispatch(payment.token) { settle(playerId, shiftId, callback) } }
            return
        }
        trackTerminal(payment.done)
        busy.add(playerId)
        inFlight[playerId] = payment
        storageFuture {
            store.update(playerId.toString()) { current ->
                if (current == null || current.pending || current.earnings == null ||
                    shiftId != null && current.earnings.id != shiftId.toString()) RedisHashDecision.Reject
                else RedisHashDecision.Write(current.copy(pending = true))
            }
        }.whenComplete { result, failure ->
            val changed = result as? RedisHashUpdateResult.Changed
            if (failure != null) {
                warn("reserve", playerId, shiftId, failure)
                terminal(payment, SideJobRewardOutcome.UNCONFIRMED)
            } else if (changed?.after?.pending == true) {
                payment.reservation = Reservation(requireNotNull(changed.before), requireNotNull(changed.after))
                dispatch(payment) { deposit(payment) }
            } else {
                val current = result.record()
                val receipt = current?.receipt?.takeIf { shiftId == null || it.id == shiftId.toString() }
                val outcome = when {
                    current?.pending == true -> SideJobRewardOutcome.UNCONFIRMED
                    receipt != null -> SideJobRewardOutcome.ALREADY_PAID
                    result is RedisHashUpdateResult.Contended -> SideJobRewardOutcome.RETRY
                    current?.earnings != null -> SideJobRewardOutcome.UNCONFIRMED
                    else -> SideJobRewardOutcome.PAID // Empty shift: no provider call.
                }
                terminal(payment, outcome, receipt)
            }
        }
    }

    /** Reconcile only this module's kinds; another module may still own a live shift. */
    fun recover(kinds: Set<OriginSideJobKind>) {
        val storage = redis ?: return; val token = tasks.token()
        recovering = true
        settlements.forEach { (id, payment) ->
            // A module restart may be between a known pre-call cancellation and its Redis rollback.
            val kind = payment.reservation?.claim?.earnings?.kind
            if (kind in kinds) dispatch(token) { settle(id, payment.shiftId) {} }
        }
        creditWrites.forEach { (id, write) ->
            if (write.kind in kinds) dispatch(token) { settle(id, write.shiftId) {} }
        }
        failedCredits.forEach { (id, entries) ->
            entries.values.firstOrNull { it.kind in kinds }?.let { write -> dispatch(token) { settle(id, write.shiftId) {} } }
        }
        storageFuture { storage.loadMap(SIDE_JOB_REWARD_KEY) }.whenComplete { entries, failure ->
            if (failure != null) {
                recovering = false
                logger.log(Level.WARNING, "ORIGIN_SIDE_JOB phase=recovery-read", failure)
            } else tasks.runAsync(token) {
                val recoverable = entries.mapNotNull { (id, raw) ->
                    try {
                        val playerId = UUID.fromString(id)
                        val record = codec.decode(raw)
                        if (record.earnings?.kind in kinds && (!record.pending ||
                                settlements[playerId]?.shiftId?.toString() == record.earnings?.id)) {
                            playerId to UUID.fromString(requireNotNull(record.earnings).id)
                        } else null
                    } catch (error: Exception) {
                        logger.log(Level.WARNING, "ORIGIN_SIDE_JOB phase=recovery-decode player=$id", error)
                        null
                    }
                }
                dispatch(token) {
                    val remaining = recoverable.iterator()
                    val step = {
                        if (!closed && remaining.hasNext()) {
                            val (playerId, shiftId) = remaining.next()
                            settle(playerId, shiftId) {}
                        }
                        closed || !remaining.hasNext()
                    }
                    recovering = false
                    if (captureRecovery != null) captureRecovery.invoke(step)
                    else while (!step()) { /* Isolated legacy/test hosts have no native bulk source. */ }
                }
            }
        }
    }

    private fun deposit(payment: Payment) {
        val provider = provider(payment.playerId)
        if (provider == null) { releaseUnused(payment, SideJobRewardOutcome.UNAVAILABLE); return }
        val claim = requireNotNull(payment.reservation).claim
        val earnings = requireNotNull(claim.earnings)
        payment.attempted = true
        val accepted = try {
            // Audit text is not a provider idempotency key. An unknown outcome is never retried.
            provider.depositDefaultCurrency(payment.playerId, earnings.amount.toDouble(), "${earnings.kind.auditPrefix}:${earnings.id}")
        } catch (error: Exception) {
            warn("provider-unconfirmed", payment.playerId, payment.shiftId, error)
            finish(payment, SideJobRewardOutcome.UNCONFIRMED, earnings); return
        }
        if (!accepted) {
            warn("provider-unconfirmed", payment.playerId, payment.shiftId, null)
            finish(payment, SideJobRewardOutcome.UNCONFIRMED, earnings); return
        }
        val settled = claim.copy(pending = false, earnings = null, receipt = earnings)
        storageFuture {
            requireNotNull(updater).update(payment.playerId.toString()) { current ->
                if (current == claim || current == settled) RedisHashDecision.Write(settled) else RedisHashDecision.Reject
            }.thenApply { it.record() == settled && it !is RedisHashUpdateResult.Rejected }
        }.whenComplete { confirmed, failure ->
            if (failure != null || confirmed != true) warn("settle-unconfirmed", payment.playerId, payment.shiftId, failure)
            else logger.info("ORIGIN_SIDE_JOB kind=${earnings.kind} reward=ACCEPTED player=${payment.playerId} shift=${earnings.id} currency=vault amount=${earnings.amount} orders=${earnings.orders.size}")
            terminal(payment, if (confirmed == true) SideJobRewardOutcome.PAID else SideJobRewardOutcome.UNCONFIRMED, earnings)
        }
    }

    private fun releaseUnused(payment: Payment, outcome: SideJobRewardOutcome = SideJobRewardOutcome.CANCELLED) {
        val reservation = payment.reservation ?: return
        if (payment.attempted || !payment.releaseStarted.compareAndSet(false, true)) return
        storageFuture {
            requireNotNull(updater).update(payment.playerId.toString()) { current ->
                if (current == reservation.claim) RedisHashDecision.Write(reservation.before) else RedisHashDecision.Reject
            }
        }.whenComplete { result, failure ->
            val released = result is RedisHashUpdateResult.Changed && result.after == reservation.before
            if (failure != null || !released) warn("cancel-retained", payment.playerId, payment.shiftId, failure)
            terminal(payment, if (released) outcome else SideJobRewardOutcome.UNCONFIRMED, reservation.before.earnings)
        }
    }

    private fun provider(playerId: UUID): ArcOriginEconomy? = try { economy()?.takeIf { it.isAvailable() } }
        catch (error: Exception) { warn("provider-unavailable", playerId, null, error); null }

    private fun dispatch(token: LifecycleTaskScope.Token, action: () -> Unit) {
        if (!closed) try { tasks.runSync(token) { if (!closed) action() } }
        catch (error: Exception) { logger.log(Level.WARNING, "ORIGIN_SIDE_JOB phase=schedule", error) }
    }

    private fun dispatch(payment: Payment, action: () -> Unit) {
        if (closed) { discard(payment); return }
        try {
            if (tasks.runSync(payment.token) { if (inFlight[payment.playerId] === payment) action() } == null &&
                !tasks.isCurrent(payment.token)) discard(payment)
        } catch (error: Exception) { warn("schedule", payment.playerId, payment.shiftId, error); discard(payment) }
    }

    private fun discard(payment: Payment) {
        if (payment.resolved) completeSettlement(payment) else releaseUnused(payment)
        if (inFlight.remove(payment.playerId, payment)) busy.remove(payment.playerId)
    }

    private fun terminal(payment: Payment, outcome: SideJobRewardOutcome, earnings: SideJobShiftEarnings? = null) {
        payment.resolved = true
        if (closed) { discard(payment); return }
        dispatch(payment) { finish(payment, outcome, earnings) }
    }

    private fun finish(payment: Payment, outcome: SideJobRewardOutcome, earnings: SideJobShiftEarnings? = null) {
        val owned = inFlight.remove(payment.playerId, payment)
        if (owned) busy.remove(payment.playerId)
        completeSettlement(payment)
        if (owned && !closed) payment.callback(SideJobRewardResult(outcome, earnings))
    }

    private fun completeSettlement(payment: Payment) {
        settlements.remove(payment.playerId, payment)
        payment.done.complete(Unit)
    }

    private fun RedisHashUpdateResult<SideJobRewardRecord>?.record(): SideJobRewardRecord? = when (this) {
        is RedisHashUpdateResult.Changed -> after
        is RedisHashUpdateResult.Unchanged -> current
        is RedisHashUpdateResult.Rejected -> current
        else -> null
    }

    private fun <T> storageFuture(action: () -> CompletableFuture<T>): CompletableFuture<T> = try { action() }
        catch (error: Exception) { CompletableFuture.failedFuture(error) }

    private fun warn(phase: String, playerId: UUID, requestId: UUID?, failure: Throwable?) {
        logger.log(Level.WARNING, "ORIGIN_SIDE_JOB phase=$phase player=$playerId request=$requestId", failure)
    }

    override fun close() {
        closed = true
        try { tasks.close() } finally { inFlight.values.forEach(::discard) }
        // Credit writes already submitted are immutable earned work and deliberately survive close.
    }
}
