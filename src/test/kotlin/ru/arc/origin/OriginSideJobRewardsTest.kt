package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.core.TaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.origin.api.ArcOriginEconomy
import ru.arc.redis.RedisOperations
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger

class OriginSideJobRewardsTest : StringSpec({
    "quest credit follows the durable completed order and quota rejection produces no quest event" {
        SideJobRewardFixture(OriginSideJobRewardPolicy(dailyOrderLimit = 1)).use { f ->
            val id = UUID.randomUUID()
            f.credit(id)
            f.questEvents shouldBe emptyList()
            f.ackCredit()
            f.questEvents shouldBe listOf("$id:${f.playerId}" to "sidejob.complete:forge")
            f.credit()
            f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.DAILY_LIMIT
            f.questEvents.size shouldBe 1
        }
    }
    "terminal drain waits for every already earned chained write after close" {
        SideJobRewardFixture().use { f ->
            f.credit()
            f.credit()
            f.rewards.close()
            val drain = f.rewards.durableDrain()
            drain.isDone shouldBe false
            f.redis.completeWrite()
            drain.isDone shouldBe false
            f.redis.completeWrite()
            drain.isDone shouldBe true
            drain.join()
            f.record()!!.earnings!!.orders.size shouldBe 2
            f.amounts shouldBe emptyList()
        }
    }

    "legacy receipts decode without optional fields and unsafe coercions still fail closed" {
        val codec = sideJobRewardCodec()
        val raw = """{"day":20000,"count":1,"nextAt":1728000020000,"requests":["00000000-0000-4000-8000-000000000001"],"pending":true}"""
        codec.encode(codec.decode(raw)) shouldBe raw
        for (bad in listOf("null", "\"1\"", "1.5", "1e3", "-1", "0", "4294967297"))
            shouldThrowAny { codec.decode(raw.replace("\"count\":1", "\"count\":$bad")) }
        shouldThrowAny { codec.decode(raw.dropLast(1) + ",\"unknown\":true}") }
        shouldThrowAny { codec.decode(raw + "{}") }
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit()
            val record = f.record()!!
            codec.decode(codec.encode(record)) shouldBe record
            shouldThrowAny { codec.decode(codec.encode(record).replace("\"amount\":20", "\"amount\":\"20\"")) }
            shouldThrowAny { codec.encode(record.copy(earnings = record.earnings!!.copy(orders = record.earnings.orders + record.earnings.orders))) }
        }
    }

    "completed orders accrue immediately and finish deposits one total only after both durable barriers" {
        SideJobRewardFixture().use { f ->
            repeat(3) { f.credit(); f.ackCredit() }
            f.amounts shouldBe emptyList()
            f.record()!!.earnings!!.amount shouldBe 60L
            f.record()!!.count shouldBe 3
            f.rewards.status(f.playerId, f.shiftId).join().unresolved shouldBe false
            f.settle()
            f.amounts shouldBe emptyList()
            f.redis.completeWrite()
            f.amounts shouldBe emptyList()
            f.scheduler.executeImmediate()
            f.amounts shouldBe listOf(60.0)
            f.results.last().outcome shouldBe SideJobRewardOutcome.CREDITED
            f.record()!!.pending shouldBe true
            f.redis.completeWrite(); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.PAID
            f.record()!!.pending shouldBe false
            f.record()!!.earnings shouldBe null
            f.settle(); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.ALREADY_PAID
            f.amounts shouldBe listOf(60.0)
        }
    }

    "duplicate credits and lost credit acknowledgements never add a second order" {
        SideJobRewardFixture().use { f ->
            val id = UUID.randomUUID()
            f.credit(id); f.redis.failWrite(applyFirst = true); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.RETRY
            f.credit(id); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.CREDITED
            f.record()!!.count shouldBe 1
            f.record()!!.earnings!!.amount shouldBe 20L
            f.amounts shouldBe emptyList()
        }
    }

    "two completed orders submitted before close survive delayed CAS and recover as one payout" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit()
            f.results.clear()
            f.credit(); f.rewards.close()
            f.owner().use { fresh ->
                fresh.recover(setOf(OriginSideJobKind.FORGE)); f.scheduler.executeImmediate()
                f.amounts shouldBe emptyList()
                f.redis.writes.size shouldBe 1 // Only the old owner's second credit.
                f.redis.completeWrite(); f.scheduler.executeImmediate()
                f.record()!!.earnings!!.orders.size shouldBe 2
                f.results shouldBe emptyList()
                f.finishPayment()
                f.amounts shouldBe listOf(40.0)
            }
        }
    }

    "failed old-lifecycle credit is retained and retried at its frozen rate before recovery pays" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); f.credit(); f.rewards.close()
            f.owner(policy = OriginSideJobRewardPolicy(100, 35)).use { fresh ->
                fresh.recover(setOf(OriginSideJobKind.FORGE)); f.scheduler.executeImmediate()
                f.redis.failWrite(applyFirst = false); f.scheduler.executeImmediate()
                f.amounts shouldBe emptyList()
                f.redis.completeWrite(); f.scheduler.executeImmediate() // Retry the immutable 20-coin intent.
                f.record()!!.earnings!!.amount shouldBe 40L
                f.finishPayment()
                f.amounts shouldBe listOf(40.0)
            }
        }
    }

    "fresh recovery waits for a known pre-call rollback and then pays the unclaimed balance" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); f.settle()
            f.redis.completeWrite() // Pending durable, server dispatch still queued.
            f.rewards.close()
            f.owner().use { fresh ->
                fresh.recover(setOf(OriginSideJobKind.FORGE)); f.scheduler.executeImmediate()
                f.amounts shouldBe emptyList()
                f.redis.completeWrite(); f.scheduler.executeImmediate() // Old lifecycle releases its unused claim.
                f.finishPayment()
                f.amounts shouldBe listOf(20.0)
            }
        }
    }

    "shared quota counts earned orders and cash-out on another UTC day does not charge them again" {
        SideJobRewardFixture(policy = OriginSideJobRewardPolicy(dailyOrderLimit = 1)).use { f ->
            f.credit(); f.ackCredit()
            f.credit(); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.DAILY_LIMIT
            f.now += 86_400_000
            f.credit(); f.ackCredit()
            f.record()!!.count shouldBe 1
            f.record()!!.earnings!!.orders.size shouldBe 2
            val day = f.record()!!.day
            f.now += 86_400_000
            f.settle(); f.finishPayment()
            f.record()!!.day shouldBe day
            f.rewards.status(f.playerId).join().remainingOrders shouldBe 1
            f.amounts shouldBe listOf(40.0)
        }
    }

    "new rate does not reprice saved earnings or reset an existing daily quota" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); f.rewards.close()
            f.owner(policy = OriginSideJobRewardPolicy(100, 35)).use { fresh ->
                fresh.credit(OriginSideJobKind.FORGE, f.playerId, f.shiftId, UUID.randomUUID(), 90, f.results::add)
                f.ackCredit()
                f.record()!!.earnings!!.amount shouldBe 55L
                fresh.settle(f.playerId, f.shiftId, f.results::add); f.finishPayment()
                f.amounts shouldBe listOf(55.0)
                fresh.status(f.playerId).join().remainingOrders shouldBe 98
            }
        }
    }

    "legacy pending claims block every job after rollover and are never recovered as unclaimed money" {
        for (kind in OriginSideJobKind.entries) SideJobRewardFixture().use { f ->
            val original = SideJobRewardRecord(f.now / 86_400_000, 1, f.now + 20_000,
                listOf(UUID.randomUUID().toString()), true)
            f.seed(original); f.now += 86_400_000
            f.rewards.credit(kind, f.playerId, f.shiftId, UUID.randomUUID(), 100, f.results::add)
            f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.UNCONFIRMED
            f.rewards.recover(OriginSideJobKind.entries.toSet()); f.scheduler.executeImmediate()
            f.record() shouldBe original
            f.amounts shouldBe emptyList()
        }
    }

    "provider false or exception retains exact earnings and blocks automatic replay" {
        for (throws in listOf(false, true)) SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); f.providerAccepts = false; f.providerThrows = throws
            f.settle(); f.redis.completeWrite(); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.UNCONFIRMED
            val pending = f.record()!!
            pending.pending shouldBe true
            pending.earnings!!.amount shouldBe 20L
            f.rewards.close(); f.now += 86_400_000
            f.owner().use { fresh ->
                fresh.recover(OriginSideJobKind.entries.toSet()); f.scheduler.executeImmediate()
                fresh.settle(f.playerId, f.shiftId, f.results::add); f.scheduler.executeImmediate()
                f.record() shouldBe pending
                f.amounts shouldBe listOf(20.0)
            }
        }
    }

    "lost settlement acknowledgement never reports success or replays the provider" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); f.settle()
            f.redis.completeWrite(); f.scheduler.executeImmediate()
            f.redis.failWrite(applyFirst = false); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.UNCONFIRMED
            f.settle(); f.scheduler.executeImmediate()
            f.amounts shouldBe listOf(20.0)
            f.record()!!.pending shouldBe true
        }
    }

    "pre-provider close restores unclaimed earnings and late successful confirmation survives close" {
        for (afterProvider in listOf(false, true)) SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); val earned = f.record()
            f.settle()
            if (afterProvider) { f.redis.completeWrite(); f.scheduler.executeImmediate() }
            f.rewards.close()
            f.redis.completeWrite()
            if (!afterProvider) f.redis.completeWrite()
            f.scheduler.executeImmediate()
            if (afterProvider) {
                f.record()!!.pending shouldBe false
                f.record()!!.receipt!!.amount shouldBe 20L
                f.amounts shouldBe listOf(20.0)
            } else {
                f.record() shouldBe earned
                f.amounts shouldBe emptyList()
            }
        }
    }

    "competing cashiers cannot double-pay and recovery ignores another module's active kind" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit()
            f.owner().use { peer ->
                peer.recover(setOf(OriginSideJobKind.WAITER)); f.scheduler.executeImmediate()
                f.redis.writes.size shouldBe 0
                f.settle(); peer.settle(f.playerId, f.shiftId, f.results::add)
                f.finishPayment()
                f.scheduler.tick(2) // The waiting cashier resumes, then delivers its read-only receipt.
                f.amounts shouldBe listOf(20.0)
                f.results.count { it.outcome == SideJobRewardOutcome.PAID } shouldBe 1
                f.results.count { it.outcome == SideJobRewardOutcome.ALREADY_PAID } shouldBe 1
            }
        }
    }

    "stale recovery of A and a cancelled admission reading live B cannot cash out B" {
        for (viaStatus in listOf(false, true)) SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit()
            if (viaStatus) {
                f.redis.holdReads = true
                f.rewards.status(f.playerId)
                f.redis.holdReads = false
            } else {
                f.redis.holdMaps = true
                f.rewards.recover(setOf(OriginSideJobKind.FORGE))
                f.redis.holdMaps = false
            }
            f.settle(); f.finishPayment()
            val newShift = UUID.randomUUID()
            f.rewards.credit(OriginSideJobKind.AUCTION, f.playerId, newShift, UUID.randomUUID(), 100, f.results::add)
            f.ackCredit()
            if (viaStatus) f.redis.completeRead() else f.redis.completeMap()
            f.scheduler.executeImmediate()
            f.amounts shouldBe listOf(20.0)
            f.record()!!.earnings!!.id shouldBe newShift.toString()
            f.record()!!.earnings!!.kind shouldBe OriginSideJobKind.AUCTION
            f.redis.writes.size shouldBe 0
        }
    }

    "provider unavailable before deposit releases claim but preserves full earned balance" {
        SideJobRewardFixture().use { f ->
            f.credit(); f.ackCredit(); val earned = f.record()
            f.providerAvailable = false; f.settle(); f.finishPayment()
            f.record() shouldBe earned
            f.results.last().outcome shouldBe SideJobRewardOutcome.UNAVAILABLE
            f.amounts shouldBe emptyList()
        }
    }

    "delayed reads are nonblocking and corrupted storage never becomes an empty balance" {
        SideJobRewardFixture().use { f ->
            f.redis.holdReads = true
            f.credit()
            f.results shouldBe emptyList()
            f.amounts shouldBe emptyList()
            f.redis.holdReads = false; f.redis.completeRead(); f.ackCredit()
            f.redis.values[f.playerId.toString()] = "{}"
            f.rewards.status(f.playerId).join().available shouldBe false
            f.settle(); f.scheduler.executeImmediate()
            f.results.last().outcome shouldBe SideJobRewardOutcome.UNCONFIRMED
            f.amounts shouldBe emptyList()
        }
    }
})

private class SideJobRewardFixture(val policy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy()) : AutoCloseable {
    val playerId = UUID.randomUUID()
    val shiftId = UUID.randomUUID()
    val serverThread = Thread.currentThread()
    val scheduler = TestTaskScheduler()
    val redis = ControlledSideJobRedis()
    var now = 1_792_108_800_000L
    var providerAvailable = true
    var providerAccepts = true
    var providerThrows = false
    val results = mutableListOf<SideJobRewardResult>()
    val amounts = mutableListOf<Double>()
    val questEvents = mutableListOf<Pair<String, String>>()
    private val provider = mockk<ArcOriginEconomy>().also { economy ->
        every { economy.isAvailable() } answers { Thread.currentThread() shouldBe serverThread; providerAvailable }
        every { economy.depositDefaultCurrency(any(), any(), any()) } answers {
            Thread.currentThread() shouldBe serverThread
            firstArg<UUID>() shouldBe playerId
            amounts += secondArg<Double>()
            if (providerThrows) error("injected provider failure")
            providerAccepts
        }
    }
    val rewards = owner(policy = policy)
    fun owner(taskScheduler: TaskScheduler = scheduler, policy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy()) = OriginSideJobRewards(
        redis, { provider }, taskScheduler, { now }, Logger.getAnonymousLogger().also { it.level = Level.OFF }, policy,
        quests = OriginQuestProgress(LifecycleTaskScope(taskScheduler), { RankQuestApi { _, event, _, objective, _ ->
            questEvents += event to objective
            CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
        } }, Logger.getAnonymousLogger()))
    fun credit(requestId: UUID = UUID.randomUUID()) = rewards.credit(OriginSideJobKind.FORGE, playerId, shiftId, requestId, 80, results::add)
    fun settle() = rewards.settle(playerId, shiftId, results::add)
    fun ackCredit() { redis.completeWrite(); scheduler.executeImmediate() }
    fun finishPayment() { redis.completeWrite(); scheduler.executeImmediate(); redis.completeWrite(); scheduler.executeImmediate() }
    fun seed(record: SideJobRewardRecord) { redis.values[playerId.toString()] = sideJobRewardCodec().encode(record) }
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
    var holdMaps = false
    private var heldMap: Pair<Map<String, String>, CompletableFuture<Map<String, String>>>? = null

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        if (!holdMaps) return CompletableFuture.completedFuture(values.toMap())
        return CompletableFuture<Map<String, String>>().also { heldMap = values.toMap() to it }
    }
    fun completeMap() { val (snapshot, future) = requireNotNull(heldMap); heldMap = null; future.complete(snapshot) }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        key shouldBe SIDE_JOB_REWARD_KEY
        if (!holdReads) return CompletableFuture.completedFuture(mapKeys.map(values::get))
        return CompletableFuture<List<String?>>().also { reads += mapKeys.toList() to it }
    }

    override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> {
        key shouldBe SIDE_JOB_REWARD_KEY
        return CompletableFuture<Boolean>().also { writes += Write(mapKey, expectedValue, replacementValue, it) }
    }

    fun completeRead(snapshot: List<String?>? = null) {
        val (fields, future) = requireNotNull(reads.poll())
        future.complete(snapshot ?: fields.map(values::get))
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
