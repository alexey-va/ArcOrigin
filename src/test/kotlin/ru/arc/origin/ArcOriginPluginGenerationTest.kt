package ru.arc.origin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ru.arc.core.BoundedAsyncExecutor

class ArcOriginPluginGenerationTest : FunSpec({
    test("re-enable waits for the exact previous durable drain and creates a fresh drain") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(ControlledArcOriginPlugin::class.java)
                paper.performTicks(2)
                plugin.starts shouldBe 1
                val first = CompletableFuture<Unit>()
                plugin.nextDrain = first
                paper.server.pluginManager.disablePlugin(plugin)
                // Idempotent duplicate disable must not replace the pending predecessor.
                plugin.onDisable()
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(2)
                plugin.starts shouldBe 1
                first.complete(Unit)
                paper.performTicks(2)
                plugin.starts shouldBe 2
                val second = CompletableFuture<Unit>()
                plugin.nextDrain = second
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(2)
                plugin.starts shouldBe 2
                second.complete(Unit)
                paper.performTicks(2)
                plugin.starts shouldBe 3
                plugin.nextDrain = CompletableFuture.completedFuture(Unit)
            }
        }
    }

    test("throwing native detach still drains accepted writes and closes every retained lease") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(FailingDetachArcOriginPlugin::class.java)
                paper.performTicks(2)
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.terminalStarted shouldBe true
                plugin.stopped.isDone shouldBe false
                plugin.lastLeaseClosed.get() shouldBe false
                plugin.earnedWrite.complete(null)
                runCatching { plugin.stopped.get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
                plugin.lastLeaseClosed.get() shouldBe true
                plugin.retainedLeases.isEmpty() shouldBe true
                plugin.stopped.handle { _, failure ->
                    failure.cause?.message ?: failure.message
                }.join() shouldBe "native detach failed"
            }
        }
    }

    test("a superseded waiting generation cannot activate after its predecessor drains") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(ControlledArcOriginPlugin::class.java)
                paper.performTicks(2)
                val old = CompletableFuture<Unit>()
                plugin.nextDrain = old
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                old.complete(Unit)
                paper.performTicks(3)
                plugin.starts shouldBe 2
                plugin.closes shouldBe 1
                plugin.nextDrain = CompletableFuture.completedFuture(Unit)
            }
        }
    }
})

open class ControlledArcOriginPlugin : ArcOriginPlugin() {
    var starts = 0
    var closes = 0
    var nextDrain = CompletableFuture.completedFuture(Unit)
    override fun startGeneration() { starts++ }
    override fun closeGeneration(): CompletableFuture<Unit> { closes++; return nextDrain }
}

open class FailingDetachArcOriginPlugin : ArcOriginPlugin() {
    val earnedWrite = CompletableFuture<Void>()
    val lastLeaseClosed = AtomicBoolean()
    val retainedLeases = linkedSetOf<AutoCloseable>(
        AutoCloseable { error("first lease close failed") },
        AutoCloseable { lastLeaseClosed.set(true) },
    )
    private val storage = BoundedAsyncExecutor("origin-generation-test")
    var terminalStarted = false
    lateinit var stopped: CompletableFuture<Unit>
    override fun startGeneration() {}
    override fun detachModules() { error("native detach failed") }
    override fun closeGeneration(): CompletableFuture<Unit> = super.closeGeneration().also { stopped = it }
    override fun closeStorage(durable: CompletableFuture<Void>): CompletableFuture<Unit> {
        terminalStarted = true
        return storage.closeAsync { closeOriginStorage(earnedWrite, retainedLeases) }
    }
}
