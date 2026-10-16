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

class OriginSideJobRewardsTest : StringSpec({
    "bounded quota codec rejects malformed or coerced state" {
        val codec = sideJobRewardCodec()
        val record = SideJobRewardRecord(20_000, 1, 1_728_000_020_000L, listOf(UUID.randomUUID().toString()), true)
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
        shouldThrowAny { codec.encode(record.copy(count = SIDE_JOB_MAX_RECEIPTS + 1, requests = List(SIDE_JOB_MAX_RECEIPTS + 1) { UUID.randomUUID().toString() })) }
        shouldThrowAny { codec.encode(record.copy(nextAt = 0)) }
    }

    "journal capacity is independent of the reloadable daily quota" {
        SideJobRewardFixture().use { f ->
            for (count in listOf(20, 100, SIDE_JOB_MAX_RECEIPTS)) {
                val record = f.settledRecord(count)
                sideJobRewardCodec().decode(sideJobRewardCodec().encode(record)) shouldBe record
            }
            val previous = f.settledRecord(60)
            f.seed(previous)
            f.rewards.close()
            f.owner(policy = OriginSideJobRewardPolicy(dailyOrderLimit = 10)).use { lower ->
                lower.status(f.playerId).join() shouldBe SideJobRewardStatus(true, 0, previous.nextAt, false)
                lower.pay(OriginSideJobKind.FORGE, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.DAILY_LIMIT
                f.calls shouldBe 0
                f.record() shouldBe previous
            }
            f.owner(policy = OriginSideJobRewardPolicy(dailyOrderLimit = 100)).use { higher ->
                higher.status(f.playerId).join().remainingOrders shouldBe 40
                f.record() shouldBe previous
            }
        }
    }

    "new owner applies configured reward and interval without resetting old receipts" {
        SideJobRewardFixture().use { f ->
            val previous = f.settledRecord(20)
            f.seed(previous)
            f.rewards.close()
            f.owner(policy = OriginSideJobRewardPolicy(100, 35, 7_000)).use { reloaded ->
                reloaded.status(f.playerId).join().remainingOrders shouldBe 80
                reloaded.pay(OriginSideJobKind.AUCTION, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.completePayment()
                f.outcomes.last() shouldBe SideJobRewardOutcome.PAID
                f.amounts shouldBe listOf(35.0)
                f.record()!!.requests.take(20) shouldBe previous.requests
                f.record()!!.count shouldBe 21
                f.record()!!.nextAt shouldBe f.now + 7_000
            }
        }
    }

    "late settlement keeps old policy amount and interval across owner replacement" {
        SideJobRewardFixture().use { f ->
            f.rewards.close()
            val expectedNextAt = f.now + 7_000
            val old = f.owner(policy = OriginSideJobRewardPolicy(100, 35, 7_000))
            old.pay(OriginSideJobKind.FORGE, f.playerId, UUID.randomUUID(), { true }, f.results::add)
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.amounts shouldBe listOf(35.0)
            old.close()
            f.owner(policy = OriginSideJobRewardPolicy(200, 70, 1_000)).use { fresh ->
                fresh.status(f.playerId).join().unresolved shouldBe true
                f.now += 5_000
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.record()!!.pending shouldBe false
                fresh.status(f.playerId).join().remainingOrders shouldBe 199
                f.record()!!.nextAt shouldBe expectedNextAt
                f.amounts shouldBe listOf(35.0)
                f.outcomes shouldBe emptyList()
            }
        }
    }

    "legacy waiter receipts and pending records remain compatible across every job kind" {
        SIDE_JOB_REWARD_KEY shouldBe "arc.origin-waiter-rewards.v1"
        val request = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val nextAt = 1_728_000_020_000L
        for (pending in listOf(false, true)) SideJobRewardFixture().use { f ->
            val raw = """{"day":20000,"count":1,"nextAt":1728000020000,"requests":["00000000-0000-4000-8000-000000000001"],"pending":$pending}"""
            val codec = sideJobRewardCodec()
            codec.encode(codec.decode(raw)) shouldBe raw
            f.redis.values[f.playerId.toString()] = raw
            f.now = if (pending) nextAt + 86_400_000L else nextAt - 1
            f.rewards.status(f.playerId).join() shouldBe SideJobRewardStatus(true, SIDE_JOB_DAILY_LIMIT - 1, nextAt, pending)
            for (kind in OriginSideJobKind.entries) {
                f.pay(if (pending) UUID.randomUUID() else request, kind)
                f.scheduler.executeImmediate()
            }
            f.outcomes shouldBe List(OriginSideJobKind.entries.size) {
                if (pending) SideJobRewardOutcome.UNCONFIRMED else SideJobRewardOutcome.ALREADY_PAID
            }
            f.redis.values[f.playerId.toString()] shouldBe raw
            f.redis.writes.size shouldBe 0
            f.calls shouldBe 0
        }
    }

    "all four job kinds share the daily limit and interval with distinct audit reasons" {
        val prefixes = linkedMapOf(
            OriginSideJobKind.WAITER to "arc-restaurant-waiter",
            OriginSideJobKind.MOUNTYARD to "arc-mountyard-job",
            OriginSideJobKind.FORGE to "arc-forge-job",
            OriginSideJobKind.AUCTION to "arc-auction-job",
        )
        OriginSideJobKind.entries.toList() shouldBe prefixes.keys.toList()
        SideJobRewardFixture().use { f ->
            f.seed(f.settledRecord(SIDE_JOB_DAILY_LIMIT - prefixes.size))
            for ((kind, prefix) in prefixes) {
                val request = UUID.randomUUID()
                f.pay(request, kind)
                f.completePayment()
                f.outcomes.last() shouldBe SideJobRewardOutcome.PAID
                f.reasons.last() shouldBe "$prefix:$request"
                if (kind == OriginSideJobKind.WAITER) {
                    f.pay(kind = OriginSideJobKind.MOUNTYARD)
                    f.scheduler.executeImmediate()
                    f.outcomes.last() shouldBe SideJobRewardOutcome.COOLDOWN
                    f.calls shouldBe 1
                }
                f.now += SIDE_JOB_PAYOUT_INTERVAL_MILLIS
            }
            f.record()?.count shouldBe SIDE_JOB_DAILY_LIMIT
            f.rewards.status(f.playerId).join().remainingOrders shouldBe 0
            for (kind in OriginSideJobKind.entries) {
                f.pay(kind = kind)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.DAILY_LIMIT
            }
            f.calls shouldBe prefixes.size
            f.amounts shouldBe List(prefixes.size) { 20.0 }
            f.redis.writes.size shouldBe 0
        }
    }

    "cooldown returns the existing deadline even when dispatch runs after it expires" {
        SideJobRewardFixture().use { f ->
            val previous = f.settledRecord(1).copy(nextAt = f.now + 5_000L)
            f.seed(previous)
            val request = UUID.randomUUID()
            f.pay(request)
            f.now = previous.nextAt + 1
            f.scheduler.executeImmediate()
            f.results shouldBe listOf(SideJobRewardResult(SideJobRewardOutcome.COOLDOWN, previous.nextAt))
            f.calls shouldBe 0
            f.redis.writes.size shouldBe 0
            f.record() shouldBe previous

            f.pay(request)
            f.completePayment()
            f.outcomes.last() shouldBe SideJobRewardOutcome.PAID
            f.calls shouldBe 1
        }
    }

    "delayed reads and durable writes never block gameplay or pay before acknowledgement" {
        SideJobRewardFixture().use { f ->
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
            f.outcomes shouldBe listOf(SideJobRewardOutcome.PAID)
            f.record()?.nextAt shouldBe f.now + SIDE_JOB_PAYOUT_INTERVAL_MILLIS
            f.record()?.count shouldBe 1
            f.record()?.pending shouldBe false
        }
    }

    "competing job kinds and repeated current or earlier requests cannot duplicate a payout" {
        SideJobRewardFixture().use { f ->
            val first = UUID.randomUUID()
            val otherOutcomes = mutableListOf<SideJobRewardOutcome>()
            f.owner().use { other ->
                f.pay(first)
                other.pay(OriginSideJobKind.FORGE, f.playerId, first, { true }) { otherOutcomes += it.outcome }
                f.redis.completeWrite()
                f.redis.completeWrite() // CAS loses; its retry observes the first pending claim.
                f.scheduler.executeImmediate()
                f.calls shouldBe 1
                otherOutcomes shouldBe listOf(SideJobRewardOutcome.UNCONFIRMED)
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.pay(first)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.ALREADY_PAID

                f.now += SIDE_JOB_PAYOUT_INTERVAL_MILLIS
                f.pay()
                f.completePayment()
                f.pay(first)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.ALREADY_PAID
                f.calls shouldBe 2
                f.record()?.count shouldBe 2
            }
        }
    }

    "pre-call cancellation restores the exact previous quota and suppresses stale feedback" {
        SideJobRewardFixture().use { f ->
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
        SideJobRewardFixture().use { f ->
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
        SideJobRewardFixture().use { f ->
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
        SideJobRewardFixture().use { f ->
            f.redis.values[f.playerId.toString()] = "{bad"
            f.rewards.status(f.playerId).join() shouldBe SideJobRewardStatus(false, 0, 0, true)
            f.pay()
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(SideJobRewardOutcome.UNCONFIRMED)
            f.redis.values[f.playerId.toString()] shouldBe "{bad"
            f.redis.writes.size shouldBe 0
            f.calls shouldBe 0
        }
    }

    "technical readiness is separate from daily quota cooldown and unresolved work" {
        SideJobRewardFixture().use { f ->
            val exhausted = f.settledRecord(SIDE_JOB_DAILY_LIMIT).copy(nextAt = f.now + SIDE_JOB_PAYOUT_INTERVAL_MILLIS)
            f.seed(exhausted)
            f.rewards.status(f.playerId).join() shouldBe SideJobRewardStatus(true, 0, exhausted.nextAt, false)
            f.seed(exhausted.copy(pending = true))
            f.rewards.status(f.playerId).join() shouldBe SideJobRewardStatus(true, 0, exhausted.nextAt, true)
            f.providerAvailable = false
            f.rewards.status(f.playerId).join().available shouldBe false
            OriginSideJobRewards(null, { null }, f.scheduler, { f.now }, Logger.getAnonymousLogger()).use { missing ->
                missing.status(f.playerId).join() shouldBe SideJobRewardStatus(false, 0, 0, false)
            }
        }
    }

    "eager server dispatch preserves the payment until delayed settlement completes" {
        SideJobRewardFixture().use { f ->
            val scheduler = object : TaskScheduler by f.scheduler {
                override fun runSync(task: Runnable): ScheduledTask {
                    task.run()
                    return mockk(relaxed = true)
                }
            }
            f.owner(scheduler).use { owner ->
                owner.pay(OriginSideJobKind.FORGE, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.redis.completeWrite()
                f.calls shouldBe 1
                f.results shouldBe emptyList()
                f.record()?.pending shouldBe true
                f.redis.writes.size shouldBe 1

                f.redis.completeWrite()
                f.results shouldBe listOf(SideJobRewardResult(SideJobRewardOutcome.PAID))
                f.record()?.pending shouldBe false
                owner.status(f.playerId).join().unresolved shouldBe false
                f.calls shouldBe 1
                f.redis.writes.size shouldBe 0
            }
        }
    }

    "provider loss or rejected scheduling releases the unused reservation without paying" {
        for (rejectedScheduling in listOf(false, true)) SideJobRewardFixture().use { f ->
            val scheduler = if (rejectedScheduling) object : TaskScheduler by f.scheduler {
                override fun runSync(task: Runnable): ScheduledTask = error("injected scheduler rejection")
            } else f.scheduler
            f.owner(scheduler).use { owner ->
                owner.pay(OriginSideJobKind.WAITER, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.providerAvailable = false
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.record()?.pending shouldBe true
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.record() shouldBe null
                f.calls shouldBe 0
                f.outcomes shouldBe if (rejectedScheduling) emptyList() else listOf(SideJobRewardOutcome.UNAVAILABLE)
            }
        }
    }

    "UTC quota resets while the payout interval survives midnight and a new owner" {
        SideJobRewardFixture().use { f ->
            val midnight = (f.now / 86_400_000L + 1) * 86_400_000L
            f.now = midnight - 10_000L
            val previous = f.settledRecord(SIDE_JOB_DAILY_LIMIT).copy(nextAt = midnight + 10_000L)
            f.seed(previous)
            f.pay()
            f.scheduler.executeImmediate()
            f.outcomes.last() shouldBe SideJobRewardOutcome.DAILY_LIMIT
            f.now = midnight + 1
            f.owner().use { restarted ->
                restarted.status(f.playerId).join() shouldBe SideJobRewardStatus(true, SIDE_JOB_DAILY_LIMIT, previous.nextAt, false)
                restarted.pay(OriginSideJobKind.WAITER, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.COOLDOWN
                f.now = previous.nextAt
                restarted.pay(OriginSideJobKind.WAITER, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.completePayment()
                f.record()?.count shouldBe 1
                f.record()?.day shouldBe midnight / 86_400_000L
                f.calls shouldBe 1
            }
        }
    }

    "a claim acknowledged after midnight counts its actual payout in the new day" {
        SideJobRewardFixture().use { f ->
            val midnight = (f.now / 86_400_000L + 1) * 86_400_000L
            f.now = midnight - 1
            f.seed(f.settledRecord(SIDE_JOB_DAILY_LIMIT - 1))
            f.pay()
            f.now = midnight + 1
            f.completePayment()
            f.record()?.day shouldBe midnight / 86_400_000L
            f.record()?.count shouldBe 1
            f.record()?.requests?.size shouldBe 1
        }
    }

    "provider false or exception blocks another job kind across reload and day rollover" {
        for (throws in listOf(false, true)) SideJobRewardFixture().use { f ->
            f.providerAccepts = false
            f.providerThrows = throws
            f.pay()
            f.redis.completeWrite()
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(SideJobRewardOutcome.UNCONFIRMED)
            val unresolved = f.record()
            unresolved?.pending shouldBe true
            f.now += 3 * 86_400_000L
            f.owner().use { restarted ->
                restarted.status(f.playerId).join().let {
                    it.available shouldBe true
                    it.unresolved shouldBe true
                    it.remainingOrders shouldBe SIDE_JOB_DAILY_LIMIT - 1
                }
                restarted.pay(OriginSideJobKind.FORGE, f.playerId, UUID.randomUUID(), { true }, f.results::add)
                f.scheduler.executeImmediate()
                f.outcomes.last() shouldBe SideJobRewardOutcome.UNCONFIRMED
                f.record() shouldBe unresolved
                f.calls shouldBe 1
            }
        }
    }

    "lost reservation acknowledgement or failed settlement never replays the provider" {
        for (failedSettlement in listOf(false, true)) SideJobRewardFixture().use { f ->
            val request = UUID.randomUUID()
            f.pay(request)
            if (failedSettlement) {
                f.redis.completeWrite()
                f.scheduler.executeImmediate()
                f.redis.failWrite(applyFirst = false)
            } else f.redis.failWrite(applyFirst = true)
            f.scheduler.executeImmediate()
            f.outcomes shouldBe listOf(SideJobRewardOutcome.UNCONFIRMED)
            f.record()?.pending shouldBe true
            f.now += 86_400_000L
            f.pay(request)
            f.scheduler.executeImmediate()
            f.pay()
            f.scheduler.executeImmediate()
            f.calls shouldBe if (failedSettlement) 1 else 0
            f.outcomes.all { it == SideJobRewardOutcome.UNCONFIRMED } shouldBe true
            f.redis.writes.size shouldBe 0
        }
    }

    "late successful settlement survives close without replay or stale UI callback" {
        SideJobRewardFixture().use { f ->
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

private class SideJobRewardFixture : AutoCloseable {
    val playerId: UUID = UUID.randomUUID()
    private val serverThread = Thread.currentThread()
    val scheduler = TestTaskScheduler()
    val redis = ControlledSideJobRedis()
    var now = 1_792_108_800_000L
    var valid = true
    var providerAvailable = true
    var providerAccepts = true
    var providerThrows = false
    var calls = 0
    var gameplayTicks = 0
    val results = mutableListOf<SideJobRewardResult>()
    val outcomes get() = results.map { it.outcome }
    val reasons = mutableListOf<String>()
    val amounts = mutableListOf<Double>()
    private val provider = mockk<ArcOriginEconomy>().also { economy ->
        every { economy.isAvailable() } answers {
            Thread.currentThread() shouldBe serverThread
            providerAvailable
        }
        every { economy.depositDefaultCurrency(any(), any(), any()) } answers {
            Thread.currentThread() shouldBe serverThread
            firstArg<UUID>() shouldBe playerId
            amounts += secondArg<Double>()
            reasons += thirdArg<String>()
            calls++
            if (providerThrows) error("injected provider failure")
            providerAccepts
        }
    }
    val rewards = owner()

    fun owner(taskScheduler: TaskScheduler = scheduler, policy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy()) = OriginSideJobRewards(
        redis, { provider }, taskScheduler, { now }, Logger.getAnonymousLogger().also { it.level = Level.OFF }, policy,
    )

    fun pay(requestId: UUID = UUID.randomUUID(), kind: OriginSideJobKind = OriginSideJobKind.WAITER) = rewards.pay(kind, playerId, requestId,
        { Thread.currentThread() shouldBe serverThread; valid },
        { Thread.currentThread() shouldBe serverThread; results += it })

    fun completePayment() {
        redis.completeWrite()
        scheduler.executeImmediate()
        redis.completeWrite()
        scheduler.executeImmediate()
    }

    fun settledRecord(count: Int) = SideJobRewardRecord(now / 86_400_000L, count, now,
        List(count) { UUID.randomUUID().toString() }, pending = false)

    fun seed(record: SideJobRewardRecord) {
        redis.values[playerId.toString()] = sideJobRewardCodec().encode(record)
    }

    fun record(): SideJobRewardRecord? = redis.values[playerId.toString()]?.let(sideJobRewardCodec()::decode)

    override fun close() = rewards.close()
}

/** Controllable acknowledgements exercise the real shared CAS updater and codec. */
private class ControlledSideJobRedis : RedisOperations by mockk(relaxed = true) {
    data class Write(val field: String, val before: String?, val after: String?, val future: CompletableFuture<Boolean>)
    val values = ConcurrentHashMap<String, String>()
    val writes = ConcurrentLinkedQueue<Write>()
    private val reads = ConcurrentLinkedQueue<Pair<List<String>, CompletableFuture<List<String?>>>>()
    var holdReads = false

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        key shouldBe SIDE_JOB_REWARD_KEY
        if (!holdReads) return CompletableFuture.completedFuture(mapKeys.map(values::get))
        return CompletableFuture<List<String?>>().also { reads += mapKeys.toList() to it }
    }

    override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> {
        key shouldBe SIDE_JOB_REWARD_KEY
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
