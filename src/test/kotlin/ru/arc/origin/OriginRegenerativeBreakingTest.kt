package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.ScheduledTask
import java.util.UUID

class OriginRegenerativeBreakingTest :
    FreeSpec({
        "a protected Origin break becomes a temporary client illusion" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))

            service.handle(target()) shouldBe true
            runtime.delays shouldContainExactly listOf(1L)
            runtime.shown shouldBe 0

            runtime.runNext()
            runtime.shown shouldBe 1
            runtime.delays shouldContainExactly listOf(100L)
            runtime.restored shouldBe 0

            runtime.runNext()
            runtime.restored shouldBe 1
        }

        "plain protection cancels Origin breaks without creating an illusion" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)

            service.handle(target()) shouldBe false
            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))
            service.handle(target()) shouldBe true
            service.handle(target(worldName = "world")) shouldBe false
            runtime.delays shouldContainExactly emptyList()
        }

        "Origin build protection follows the enabled world and admin bypass" {
            val service = OriginBreakProtection(FakeRuntime())
            service.isProtected("rc_origin_spawn", false) shouldBe false
            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))
            service.isProtected("rc_origin_spawn", false) shouldBe true
            service.isProtected("rc_origin_spawn", true) shouldBe false
            service.isProtected("world", false) shouldBe false
            service.apply(OriginBreakProtectionSettings(false, true, "rc_origin_spawn", 100L))
            service.isProtected("rc_origin_spawn", false) shouldBe false
        }

        "build permission bypass leaves the break completely untouched" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(
                OriginBreakProtectionSettings(
                    protected = true,
                    illusionEnabled = true,
                    worldName = "rc_origin_spawn",
                    restoreDelayTicks = 100L,
                    feedback = feedbackSettings(),
                ),
            )

            service.handle(target(), bypassProtection = true) shouldBe false
            runtime.delays shouldContainExactly emptyList()
            runtime.feedback shouldContainExactly emptyList()
        }

        "a block changed after the event is never hidden" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))
            service.handle(target())
            runtime.blockData = "minecraft:dirt"

            runtime.runNext()

            runtime.shown shouldBe 0
            runtime.restored shouldBe 0
            runtime.delays shouldContainExactly emptyList()
        }

        "reload disabling the feature restores visible illusions immediately" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))
            service.handle(target())
            runtime.runNext()

            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))

            runtime.restored shouldBe 1
            runtime.runAll()
            runtime.restored shouldBe 1
        }

        "feedback grows with a sustained series and resets after a quiet window" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(
                OriginBreakProtectionSettings(
                    protected = true,
                    illusionEnabled = false,
                    worldName = "rc_origin_spawn",
                    restoreDelayTicks = 100L,
                    feedback = feedbackSettings(),
                ),
            )

            service.handle(target())
            runtime.feedback shouldContainExactly listOf("curious")

            runtime.advance(1L)
            service.handle(target())
            runtime.feedback shouldContainExactly listOf("curious")

            runtime.advance(1L)
            service.handle(target())
            runtime.feedback shouldContainExactly listOf("curious", "persistent")

            runtime.advance(1L)
            service.handle(target())
            runtime.advance(1L)
            service.handle(target())
            runtime.feedback shouldContainExactly listOf("curious", "persistent", "final")

            runtime.advance(11L)
            service.handle(target())
            runtime.feedback shouldContainExactly listOf("curious", "persistent", "final", "curious")
        }

        "feedback can be disabled independently and forget clears its series" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))
            service.handle(target())
            runtime.feedback shouldContainExactly emptyList()

            service.apply(
                OriginBreakProtectionSettings(
                    protected = true,
                    illusionEnabled = false,
                    worldName = "rc_origin_spawn",
                    restoreDelayTicks = 100L,
                    feedback = feedbackSettings(),
                ),
            )
            service.handle(target())
            service.forget(target().playerId)
            runtime.advance(2L)
            service.handle(target())

            runtime.feedback shouldContainExactly listOf("curious", "curious")
        }

        "feedback does not immediately repeat the same variant" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(
                OriginBreakProtectionSettings(
                    protected = true,
                    illusionEnabled = false,
                    worldName = "rc_origin_spawn",
                    restoreDelayTicks = 100L,
                    feedback =
                        feedbackSettings().copy(
                            messageCooldownTicks = 1L,
                            tiers = listOf(OriginBreakFeedbackTier(1, listOf("first", "second"))),
                        ),
                ),
            )

            service.handle(target())
            runtime.advance(1L)
            service.handle(target())

            runtime.feedback shouldContainExactly listOf("first", "second")
        }
    })

private fun feedbackSettings() =
    OriginBreakFeedbackSettings(
        enabled = true,
        countIntervalTicks = 1L,
        messageCooldownTicks = 2L,
        resetAfterTicks = 10L,
        tiers =
            listOf(
                OriginBreakFeedbackTier(1, listOf("curious")),
                OriginBreakFeedbackTier(3, listOf("persistent")),
                OriginBreakFeedbackTier(5, listOf("final")),
            ),
    )

private fun target(worldName: String = "rc_origin_spawn") =
    OriginBreakIllusionTarget(
        playerId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        worldId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        worldName = worldName,
        x = 10,
        y = 70,
        z = -4,
        originalBlockData = "minecraft:stone",
    )

private class FakeRuntime : OriginBreakRuntime {
    private val tasks = mutableListOf<FakeTask>()
    var blockData: String? = "minecraft:stone"
    var shown = 0
    var restored = 0
    var tick = 0L
    val feedback = mutableListOf<String>()
    val delays: List<Long>
        get() = tasks.filterNot(FakeTask::isCancelled).map(FakeTask::delayTicks)

    override fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask =
        FakeTask(delayTicks, action).also(tasks::add)

    override fun currentBlockData(target: OriginBreakIllusionTarget): String? = blockData

    override fun showBroken(target: OriginBreakIllusionTarget): Boolean {
        shown++
        return true
    }

    override fun restore(target: OriginBreakIllusionTarget) {
        restored++
    }

    override fun currentTick(): Long = tick

    override fun randomIndex(bound: Int): Int = 0

    override fun showFeedback(playerId: UUID, message: String): Boolean {
        feedback += message
        return true
    }

    fun advance(ticks: Long) {
        tick += ticks
    }

    fun runNext() {
        val task = tasks.firstOrNull { !it.isCancelled } ?: return
        tasks.remove(task)
        task.run()
    }

    fun runAll() {
        while (tasks.any { !it.isCancelled }) runNext()
    }
}

private class FakeTask(
    val delayTicks: Long,
    private val action: () -> Unit,
) : ScheduledTask {
    override val id: Int = 0
    override var isCancelled: Boolean = false
        private set

    fun run() {
        if (!isCancelled) action()
        isCancelled = true
    }

    override fun cancel() {
        isCancelled = true
    }
}
