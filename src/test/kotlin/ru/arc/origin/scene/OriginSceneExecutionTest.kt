package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler

class OriginSceneExecutionTest : FreeSpec({
    "an exception in a delayed step cleans up and releases the actor exactly once" {
        val scheduler = TestTaskScheduler()
        val effects = RecordingSceneEffects()
        val run = OriginSceneExecution(listOf("heat", "forge"), 100, effects, scheduler)
        run.start()
        run.after(2) { error("display disappeared") }
        scheduler.tick(2)
        run.interrupt("duplicate")

        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.releases shouldBe listOf("step-failed")
        effects.cleanups shouldBe listOf(false)
        effects.returns shouldBe listOf(true)
        effects.failures shouldBe listOf("step")
        scheduler.pendingCount() shouldBe 0
    }

    "interrupting any step invalidates its pending continuation" {
        repeat(3) { interruptedStep ->
            val scheduler = TestTaskScheduler()
            val effects = RecordingSceneEffects()
            val run = OriginSceneExecution(listOf("open", "forge", "close"), 100, effects, scheduler)
            run.start()
            repeat(interruptedStep) { run.advance(it + 1) }
            run.after(1) { run.advance(interruptedStep + 1) }
            run.interrupt("reload")
            scheduler.tick(200)
            effects.executed shouldBe (0..interruptedStep).toList()
            effects.releases shouldBe listOf("reload")
            effects.cleanups shouldBe listOf(false)
        }
    }

    "normal completion restores props before returning and keeps successful mounts" {
        val effects = RecordingSceneEffects()
        val scheduler = TestTaskScheduler()
        val run = OriginSceneExecution(listOf("ride"), 100, effects, scheduler)
        run.start()
        run.advance(1)
        run.phase shouldBe OriginSceneExecutionPhase.RETURNING
        effects.cleanups shouldBe listOf(true)
        effects.returns shouldBe listOf(false)
        effects.releases shouldBe emptyList()
        run.completeReturn("complete")
        run.completeReturn("duplicate")
        scheduler.tick(200)
        effects.releases shouldBe listOf("complete")
        scheduler.pendingCount() shouldBe 0
    }

    "watchdog terminates a stuck return as well as a stuck work step" {
        listOf(false, true).forEach { returning ->
            val effects = RecordingSceneEffects()
            val scheduler = TestTaskScheduler()
            val run = OriginSceneExecution(listOf("work"), 5, effects, scheduler)
            run.start()
            if (returning) run.advance(1)
            scheduler.tick(5)
            run.phase shouldBe OriginSceneExecutionPhase.FINISHED
            effects.releases shouldBe listOf("cycle-timeout")
            effects.returns.last() shouldBe true
        }
    }

    "player click cleans up and releases at the current position" {
        val effects = RecordingSceneEffects()
        val scheduler = TestTaskScheduler()
        val run = OriginSceneExecution(listOf("work"), 5, effects, scheduler)
        run.start()
        run.interrupt("player-click", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)

        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.cleanups shouldBe listOf(false)
        effects.returns shouldBe emptyList()
        effects.releases shouldBe listOf("player-click")
        scheduler.pendingCount() shouldBe 0
    }

    "player click cleanup failure keeps retrying without home recovery" {
        val effects = RecordingSceneEffects(failCleanup = true)
        val scheduler = TestTaskScheduler()
        val run = OriginSceneExecution(listOf("work"), 5, effects, scheduler)
        run.start()
        run.interrupt("player-click", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        run.phase shouldBe OriginSceneExecutionPhase.RECOVERING
        effects.releases shouldBe emptyList()
        effects.failCleanup = false
        scheduler.tick(20)
        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.cleanups shouldBe listOf(false, false)
        effects.returns shouldBe emptyList()
        effects.releases shouldBe listOf("player-click")
        scheduler.pendingCount() shouldBe 0
    }

    "permanent recovery failure is bounded and explicitly reported" {
        val effects = RecordingSceneEffects(failCleanup = true)
        val scheduler = TestTaskScheduler()
        val run = OriginSceneExecution(listOf("work"), 5, effects, scheduler)
        run.start()
        run.interrupt("player-click", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        scheduler.tick(100)
        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.cleanups shouldBe listOf(false, false, false)
        effects.returns shouldBe emptyList()
        effects.releases shouldBe listOf("player-click-recovery-incomplete")
        scheduler.pendingCount() shouldBe 0
    }

    "shutdown drains recovery synchronously and invalidates its pending retry" {
        val effects = RecordingSceneEffects(failCleanup = true, failReturn = true)
        val scheduler = TestTaskScheduler()
        val run = OriginSceneExecution(listOf("work"), 5, effects, scheduler)
        run.start()
        run.interrupt("player-click", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
        run.close()
        scheduler.tick(100)
        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.cleanups.size shouldBe 4
        effects.releases shouldBe listOf("shutdown-recovery-incomplete")
        scheduler.pendingCount() shouldBe 0
    }

    "a stale step continuation cannot advance a different step" {
        val scheduler = TestTaskScheduler()
        val effects = RecordingSceneEffects()
        val run = OriginSceneExecution(listOf("first", "second", "third"), 100, effects, scheduler)
        run.start()
        var staleExecuted = false
        run.after(1) { staleExecuted = true; run.advance(2) }
        run.advance(1)
        scheduler.tick(1)
        staleExecuted shouldBe false
        run.stepId shouldBe "second"
        effects.executed shouldBe listOf(0, 1)
        run.close()
    }

    "a repeating callback is fenced when the execution is interrupted" {
        val scheduler = TestTaskScheduler()
        val effects = RecordingSceneEffects()
        val run = OriginSceneExecution(listOf("work"), 100, effects, scheduler)
        var calls = 0
        run.start()
        run.repeat(2) {
            calls++
            true
        }

        scheduler.tick(2)
        calls shouldBe 1
        run.interrupt("player-click")
        scheduler.tick(20)
        calls shouldBe 1
        run.phase shouldBe OriginSceneExecutionPhase.FINISHED
        effects.releases shouldBe listOf("player-click")
    }
})

private class RecordingSceneEffects(
    var failCleanup: Boolean = false,
    val failReturn: Boolean = false,
) : OriginSceneExecutionEffects {
    val executed = mutableListOf<Int>()
    val cleanups = mutableListOf<Boolean>()
    val returns = mutableListOf<Boolean>()
    val releases = mutableListOf<String>()
    val failures = mutableListOf<String>()
    override fun execute(stepIndex: Int) { executed += stepIndex }
    override fun cleanup(keepMounted: Boolean) {
        cleanups += keepMounted
        if (failCleanup) error("cleanup failed")
    }
    override fun returnHome(reason: String, immediate: Boolean) {
        returns += immediate
        if (failReturn) error("return failed")
    }
    override fun release(reason: String) { releases += reason }
    override fun reportFailure(stage: String, failure: Exception) { failures += stage }
}
