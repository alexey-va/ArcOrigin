package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler
import ru.arc.origin.api.ArcOriginEconomy
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger

class OriginWaiterRewardsTest : StringSpec({
    "bounded quota codec rejects malformed or coerced state" {
        val codec = waiterRewardCodec()
        val record = WaiterRewardRecord(20_000, 1, 1_728_000_020_000L, listOf(UUID.randomUUID().toString()), true)
        val raw = codec.encode(record)
        codec.decode(raw) shouldBe record
        codec.decode(codec.encode(record.copy(pending = false))) shouldBe record.copy(pending = false)
        for (value in listOf("null", "\"1\"", "1.5", "1e3", "-1", "0", "2147483649", "4294967297")) {
            shouldThrowAny { codec.decode(raw.replace("\"count\":1", "\"count\":$value")) }
        }
        for (value in listOf("null", "\"true\"", "1", "{}")) {
            shouldThrowAny { codec.decode(raw.replace("\"pending\":true", "\"pending\":$value")) }
        }
        shouldThrowAny { codec.decode(raw.replace(record.requests.single(), "invalid")) }
        shouldThrowAny { codec.decode(raw.replace("\"day\":20000,", "")) }
        shouldThrowAny { codec.decode(raw.dropLast(1) + ",\"unknown\":true}") }
        shouldThrowAny { codec.decode(raw + "{}") }
        shouldThrowAny { codec.encode(record.copy(count = 2, requests = record.requests + record.requests)) }
        shouldThrowAny { codec.encode(record.copy(count = 21, requests = List(21) { UUID.randomUUID().toString() })) }
        shouldThrowAny { codec.encode(record.copy(nextAt = 0)) }
    }

    "delayed reads and durable writes never block gameplay or pay before acknowledgement" {
        WaiterFixture().use { f ->
            f.redis.holdReads = true
            f.pay()
            f.calls shouldBe 0
            f.scheduler.runSync(Runnable { f.gameplayTicks++ })
            f.scheduler.executeImmediate()
            f.gameplayTicks shouldBe 1
            f.redis.writes.size shouldBe 0

            f.redis.holdReads = false
            CompletableFuture.runAsync { f.redis.completeRead() }.join()
            f.redis.writes.size shouldBe 1
            f.scheduler.executeImmediate()
            f.calls shouldBe 0
            f.now += 120_000L
            CompletableFuture.runAsync { f.redis.completeWrite() }.join()
            f.calls shouldBe 0
            f.scheduler.executeImmediate()
            f.calls shouldBe 1
            f.outcomes shouldBe emptyList()
            f.record()?.pending shouldBe true
            f.rewards.status(f.playerId).join().unresolved shouldBe true
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(WaiterRewardOutcome.PAID)
            f.record()?.nextAt shouldBe f.now + WAITER_PAYOUT_INTERVAL_MILLIS
            f.record()?.count shouldBe 1
            f.record()?.pending shouldBe false
        }
    }

    "competing owners and repeated current or earlier requests cannot duplicate a payout" {
        WaiterFixture().use { f ->
            val first = UUID.randomUUID()
            val otherOutcomes = mutableListOf<WaiterRewardOutcome>()
            f.owner().use { other ->
                f.pay(first)
                other.pay(f.playerId, first, { true }, otherOutcomes::add)
                f.redis.completeWrite()
                f.redis.completeWrite() // CAS loses; its retry observes the first pending claim.
                f.scheduler.executeImmediate()
                f.calls shouldBe 1
                otherOutcomes shouldBe listOf(WaiterRewardOutcome.UNCONFIRMED)
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.pay(first)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe WaiterRewardOutcome.ALREADY_PAID

                f.now += WAITER_PAYOUT_INTERVAL_MILLIS
                f.pay()
                f.completePayment()
                f.pay(first)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe WaiterRewardOutcome.ALREADY_PAID
                f.calls shouldBe 2
                f.record()?.count shouldBe 2
            }
        }
    }

    "pre-call cancellation restores the exact previous quota and suppresses stale feedback" {
        WaiterFixture().use { f ->
            val before = f.settledRecord(count = 3)
            f.seed(before)
            f.pay()
            f.redis.completeWrite()
            f.valid = false
            f.scheduler.executeImmediate()
            f.calls shouldBe 0
            f.record()?.pending shouldBe true
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.record() shouldBe before
            f.outcomes shouldBe emptyList()
        }
    }

    "cancellation never overwrites a newer durable record" {
        WaiterFixture().use { f ->
            f.pay()
            f.redis.completeWrite()
            f.valid = false
            f.scheduler.executeImmediate()
            val replacement = f.settledRecord(count = 4)
            f.seed(replacement)
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.record() shouldBe replacement
            f.calls shouldBe 0
        }
    }

    "closing before delayed acknowledgement releases only the unused claim without waiting" {
        WaiterFixture().use { f ->
            f.pay()
            f.rewards.close()
            f.redis.writes.size shouldBe 1
            CompletableFuture.runAsync { f.redis.completeWrite() }.join()
            f.redis.writes.size shouldBe 1
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.record() shouldBe null
            f.calls shouldBe 0
            f.outcomes shouldBe emptyList()
        }
    }

    "malformed storage is unavailable and is never treated as an empty quota" {
        WaiterFixture().use { f ->
            f.redis.values[f.playerId.toString()] = "{bad"
            f.rewards.status(f.playerId).join() shouldBe WaiterRewardStatus(false, 0, 0, true)
            f.pay()
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(WaiterRewardOutcome.UNCONFIRMED)
            f.redis.values[f.playerId.toString()] shouldBe "{bad"
            f.redis.writes.size shouldBe 0
            f.calls shouldBe 0
        }
    }

    "technical readiness is separate from daily quota cooldown and unresolved work" {
        WaiterFixture().use { f ->
            val exhausted = f.settledRecord(WAITER_DAILY_LIMIT).copy(nextAt = f.now + WAITER_PAYOUT_INTERVAL_MILLIS)
            f.seed(exhausted)
            f.rewards.status(f.playerId).join() shouldBe WaiterRewardStatus(true, 0, exhausted.nextAt, false)
            f.seed(exhausted.copy(pending = true))
            f.rewards.status(f.playerId).join() shouldBe WaiterRewardStatus(true, 0, exhausted.nextAt, true)
            f.providerAvailable = false
            f.rewards.status(f.playerId).join().available shouldBe false
            OriginWaiterRewards(null, { null }, f.scheduler, { f.now }, Logger.getAnonymousLogger()).use { missing ->
                missing.status(f.playerId).join() shouldBe WaiterRewardStatus(false, 0, 0, false)
            }
        }
    }

    "provider loss or rejected scheduling releases the unused reservation without paying" {
        for (rejectedScheduling in listOf(false, true)) WaiterFixture().use { f ->
            val scheduler = if (rejectedScheduling) object : TaskScheduler by f.scheduler {
                override fun runSync(task: Runnable): ScheduledTask = error("injected scheduler rejection")
            } else f.scheduler
            f.owner(scheduler).use { owner ->
                owner.pay(f.playerId, UUID.randomUUID(), { true }, f.outcomes::add)
                f.providerAvailable = false
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.record()?.pending shouldBe true
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.record() shouldBe null
                f.calls shouldBe 0
                f.outcomes shouldBe if (rejectedScheduling) emptyList() else listOf(WaiterRewardOutcome.UNAVAILABLE)
            }
        }
    }

    "UTC quota resets while the payout interval survives midnight and a new owner" {
        WaiterFixture().use { f ->
            val midnight = (f.now / 86_400_000L + 1) * 86_400_000L
            f.now = midnight - 10_000L
            val previous = f.settledRecord(WAITER_DAILY_LIMIT).copy(nextAt = midnight + 10_000L)
            f.seed(previous)
            f.pay()
            f.scheduler.executeImmediate()
            f.outcomes.last() shouldBe WaiterRewardOutcome.DAILY_LIMIT
            f.now = midnight + 1
            f.owner().use { restarted ->
                restarted.status(f.playerId).join() shouldBe WaiterRewardStatus(true, WAITER_DAILY_LIMIT, previous.nextAt, false)
                restarted.pay(f.playerId, UUID.randomUUID(), { true }, f.outcomes::add)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe WaiterRewardOutcome.COOLDOWN
                f.now = previous.nextAt
                restarted.pay(f.playerId, UUID.randomUUID(), { true }, f.outcomes::add)
                f.completePayment()
                f.record()?.count shouldBe 1
                f.record()?.day shouldBe midnight / 86_400_000L
                f.calls shouldBe 1
            }
        }
    }

    "a claim acknowledged after midnight counts its actual payout in the new day" {
        WaiterFixture().use { f ->
            val midnight = (f.now / 86_400_000L + 1) * 86_400_000L
            f.now = midnight - 1
            f.seed(f.settledRecord(WAITER_DAILY_LIMIT - 1))
            f.pay()
            f.now = midnight + 1
            f.completePayment()
            f.record()?.day shouldBe midnight / 86_400_000L
            f.record()?.count shouldBe 1
            f.record()?.requests?.size shouldBe 1
        }
    }

    "provider false or exception leaves unresolved quota across reload and day rollover" {
        for (throws in listOf(false, true)) WaiterFixture().use { f ->
            f.providerAccepts = false
            f.providerThrows = throws
            f.pay()
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(WaiterRewardOutcome.UNCONFIRMED)
            val unresolved = f.record()
            unresolved?.pending shouldBe true
            f.now += 3 * 86_400_000L
            f.owner().use { restarted ->
                restarted.status(f.playerId).join().let {
                    it.available shouldBe true
                    it.unresolved shouldBe true
                    it.remainingOrders shouldBe WAITER_DAILY_LIMIT - 1
                }
                restarted.pay(f.playerId, UUID.randomUUID(), { true }, f.outcomes::add)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe WaiterRewardOutcome.UNCONFIRMED
                f.record() shouldBe unresolved
                f.calls shouldBe 1
            }
        }
    }

    "lost reservation acknowledgement or failed settlement never replays the provider" {
        for (failedSettlement in listOf(false, true)) WaiterFixture().use { f ->
            val request = UUID.randomUUID()
            f.pay(request)
            if (failedSettlement) {
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.redis.failWrite(applyFirst = false)
            } else f.redis.failWrite(applyFirst = true)
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(WaiterRewardOutcome.UNCONFIRMED)
            f.record()?.pending shouldBe true
            f.now += 86_400_000L
            f.pay(request)
            f.scheduler.executeImmediate()
            f.pay()
            f.scheduler.executeImmediate()
            f.calls shouldBe if (failedSettlement) 1 else 0
            f.outcomes.all { it == WaiterRewardOutcome.UNCONFIRMED } shouldBe true
            f.redis.writes.size shouldBe 0
        }
    }

    "late successful settlement survives close without replay or stale UI callback" {
        WaiterFixture().use { f ->
            f.pay()
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.calls shouldBe 1
            f.rewards.close()
            f.record()?.pending shouldBe true
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.record()?.pending shouldBe false
            f.outcomes shouldBe emptyList()
            f.calls shouldBe 1
        }
    }
})

private class WaiterFixture : AutoCloseable {
    val playerId: UUID = UUID.randomUUID()
    private val serverThread = Thread.currentThread()
    val scheduler = TestTaskScheduler()
    val redis = ControlledWaiterRedis()
    var now = 1_792_108_800_000L
    var valid = true
    var providerAvailable = true
    var providerAccepts = true
    var providerThrows = false
    var calls = 0
    var gameplayTicks = 0
    val outcomes = mutableListOf<WaiterRewardOutcome>()
    private val provider = mockk<ArcOriginEconomy>().also { economy ->
        every { economy.isAvailable() } answers {
            Thread.currentThread() shouldBe serverThread
            providerAvailable
        }
        every { economy.depositDefaultCurrency(any(), any(), any()) } answers {
            Thread.currentThread() shouldBe serverThread
            firstArg<UUID>() shouldBe playerId
            secondArg<Double>() shouldBe 20.0
            thirdArg<String>().startsWith("arc-restaurant-waiter:") shouldBe true
            calls++
            if (providerThrows) error("injected provider failure")
            providerAccepts
        }
    }
    val rewards = owner()

    fun owner(taskScheduler: TaskScheduler = scheduler) = OriginWaiterRewards(
        redis, { provider }, taskScheduler, { now }, Logger.getAnonymousLogger().also { it.level = Level.OFF },
    )

    fun pay(requestId: UUID = UUID.randomUUID()) = rewards.pay(playerId, requestId,
        { Thread.currentThread() shouldBe serverThread; valid },
        { Thread.currentThread() shouldBe serverThread; outcomes += it })

    fun completePayment() {
        redis.completeWrite()
        scheduler.executeImmediate()
        redis.completeWrite()
        scheduler.executeImmediate()
    }

    fun settledRecord(count: Int) = WaiterRewardRecord(now / 86_400_000L, count, now,
        List(count) { UUID.randomUUID().toString() }, pending = false)

    fun seed(record: WaiterRewardRecord) {
        redis.values[playerId.toString()] = waiterRewardCodec().encode(record)
    }

    fun record(): WaiterRewardRecord? = redis.values[playerId.toString()]?.let(waiterRewardCodec()::decode)

    override fun close() = rewards.close()
}

/** Controllable acknowledgements exercise the real shared CAS updater and codec. */
private class ControlledWaiterRedis : RedisOperations by mockk(relaxed = true) {
    data class Write(val field: String, val before: String?, val after: String?, val future: CompletableFuture<Boolean>)
    val values = ConcurrentHashMap<String, String>()
    val writes = ConcurrentLinkedQueue<Write>()
    private val reads = ConcurrentLinkedQueue<Pair<List<String>, CompletableFuture<List<String?>>>>()
    var holdReads = false

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        key shouldBe WAITER_REWARD_KEY
        if (!holdReads) return CompletableFuture.completedFuture(mapKeys.map(values::get))
        return CompletableFuture<List<String?>>().also { reads += mapKeys.toList() to it }
    }

    override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> {
        key shouldBe WAITER_REWARD_KEY
        return CompletableFuture<Boolean>().also { writes += Write(mapKey, expectedValue, replacementValue, it) }
    }

    fun completeRead() {
        val (fields, future) = requireNotNull(reads.poll())
        future.complete(fields.map(values::get))
    }

    fun completeWrite() {
        val write = requireNotNull(writes.poll())
        write.future.complete(apply(write))
    }

    fun failWrite(applyFirst: Boolean) {
        val write = requireNotNull(writes.poll())
        if (applyFirst) apply(write)
        write.future.completeExceptionally(IllegalStateException("injected acknowledgement failure"))
    }

    private fun apply(write: Write): Boolean {
        if (values[write.field] != write.before) return false
        if (write.after == null) values.remove(write.field) else values[write.field] = write.after
        return true
    }
}
