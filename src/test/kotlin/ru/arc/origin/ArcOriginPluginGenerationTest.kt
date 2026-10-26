package ru.arc.origin

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ru.arc.core.BoundedAsyncExecutor

class ArcOriginPluginGenerationTest : FunSpec({
    test("a prior Redis retirement failure still fails terminal cleanup after other leases close") {
        val previous = IllegalStateException("retired Redis release failed")
        var closed = 0
        val leases = linkedSetOf<AutoCloseable>(AutoCloseable { closed++ })
        shouldThrow<IllegalStateException> {
            closeOriginStorage(CompletableFuture.completedFuture(null), leases, previous)
        } shouldBe previous
        closed shouldBe 1
        leases.isEmpty() shouldBe true
    }

    test("startup is direct and disable finishes IO before the next startup") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcOriginPlugin::class.java)
            plugin.starts shouldBe 1
            val write = CompletableFuture<Unit>()
            plugin.nextDrain = write
            val writer = CompletableFuture.runAsync {
                try { plugin.closeEntered.await(5, TimeUnit.SECONDS) shouldBe true }
                finally { write.complete(Unit) }
            }
            paper.server.pluginManager.disablePlugin(plugin)
            writer.get(5, TimeUnit.SECONDS)
            plugin.onDisable()
            plugin.closes shouldBe 1
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
            plugin.nextDrain = CompletableFuture.completedFuture(Unit)
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
                paper.server.pluginManager.enablePlugin(plugin)
                plugin.starts shouldBe 1
                plugin.terminalCalls shouldBe 1
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

    test("packet props share a generation and pending cleanup blocks replacement without blocking disable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(PacketCleanupArcOriginPlugin::class.java)
            plugin.propDisplays() shouldBe plugin.renderer
            plugin.propDisplays() shouldBe plugin.renderer
            plugin.created shouldBe 1
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.retirement.isDone shouldBe false
            verify(exactly = 1) { plugin.renderer.closeAsync() }
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 1
            plugin.retirement.complete(null)
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
        }
    }

    test("failed packet cleanup keeps the next generation closed") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(PacketCleanupArcOriginPlugin::class.java)
            plugin.propDisplays()
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.retirement.completeExceptionally(IllegalStateException("packet retirement failed"))
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 1
        }
    }
})

open class ControlledArcOriginPlugin : ArcOriginPlugin() {
    var starts = 0
    var closes = 0
    var nextDrain = CompletableFuture.completedFuture(Unit)
    override fun startGeneration() { starts++ }
    val closeEntered = CountDownLatch(1)
    override fun closeGeneration(): CompletableFuture<Unit> {
        closes++
        closeEntered.countDown()
        return nextDrain
    }
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
    var starts = 0
    var terminalCalls = 0
    override fun startGeneration() { starts++ }
    override fun detachModules() { error("native detach failed") }
    override fun closeGeneration(): CompletableFuture<Unit> = super.closeGeneration().also { stopped = it }
    override fun closeStorage(durable: CompletableFuture<Void>): CompletableFuture<Unit> {
        terminalStarted = true
        terminalCalls++
        return storage.closeAsync { closeOriginStorage(earnedWrite, retainedLeases) }
    }
}

open class PacketCleanupArcOriginPlugin : ArcOriginPlugin() {
    var starts = 0
    var created = 0
    val retirement = CompletableFuture<Void>()
    val renderer = mockk<PaperPacketDisplays>(relaxed = true).also {
        every { it.closeAsync() } returns retirement
    }
    override fun startGeneration() { starts++ }
    override fun createPropDisplays(): PaperPacketDisplays {
        created++
        return renderer
    }
    override fun detachModules() = Unit
    override fun closeStorage(durable: CompletableFuture<Void>) = CompletableFuture.completedFuture(Unit)
}
