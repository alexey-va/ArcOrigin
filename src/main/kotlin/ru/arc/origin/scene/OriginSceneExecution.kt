package ru.arc.origin.scene

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks

internal enum class OriginSceneExecutionPhase { NEW, RUNNING, RETURNING, RECOVERING, FINISHED }

/** Defines whether an interrupted cycle must move actors back to their homes. */
internal enum class OriginSceneRecoveryPolicy {
    RETURN_HOME,
    KEEP_CURRENT_POSITION,
}

/** Native scene operations; execution owns their ordering, cancellation and failure recovery. */
internal interface OriginSceneExecutionEffects {
    fun execute(stepIndex: Int)
    fun cleanup(keepMounted: Boolean)
    fun returnHome(reason: String, immediate: Boolean)
    fun release(reason: String)
    fun reportFailure(stage: String, failure: Exception)
}

/**
 * Main-thread lifecycle of one actor lease, including its return journey.
 *
 * Scene-specific state transitions live here; scheduling and stale epoch fencing
 * remain in core's LifecycleTaskScope. Every terminal path cancels continuations,
 * attempts native recovery, and releases the lease once, even if recovery fails.
 */
internal class OriginSceneExecution(
    private val stepIds: List<String>,
    private val timeoutTicks: Long,
    private val effects: OriginSceneExecutionEffects,
    scheduler: TaskScheduler = Tasks.scheduler,
) : AutoCloseable {
    private val actions = LifecycleTaskScope(scheduler)
    private val deadline = LifecycleTaskScope(scheduler)
    private var closing = false
    var phase = OriginSceneExecutionPhase.NEW
        private set
    var stepIndex = -1
        private set
    val stepId: String? get() = stepIds.getOrNull(stepIndex)
    var reason: String? = null
        private set

    init {
        require(stepIds.isNotEmpty())
        require(timeoutTicks > 0L)
    }

    fun start() {
        if (phase != OriginSceneExecutionPhase.NEW) return
        phase = OriginSceneExecutionPhase.RUNNING
        guarded("schedule") { deadline.runLater(timeoutTicks) { interrupt("cycle-timeout") } }
        advance(0)
    }

    fun advance(nextIndex: Int) {
        if (phase != OriginSceneExecutionPhase.RUNNING || nextIndex != stepIndex + 1) return
        if (nextIndex >= stepIds.size) {
            finish("complete")
            return
        }
        stepIndex = nextIndex
        guarded("step") { effects.execute(nextIndex) }
    }

    fun after(ticks: Long, allowReturning: Boolean = false, action: () -> Unit) {
        val expectedPhase = if (allowReturning) OriginSceneExecutionPhase.RETURNING else OriginSceneExecutionPhase.RUNNING
        if (phase != expectedPhase) return
        val expectedStep = stepIndex
        guarded("schedule") {
            actions.runLater(ticks) {
                if (phase == expectedPhase && stepIndex == expectedStep) guarded("step", action)
            }
        }
    }

    /**
     * Runs a main-thread cycle-owned callback until it returns false or the
     * execution leaves RUNNING. LifecycleTaskScope cancels the next callback
     * on finish, interruption, reload and shutdown.
     */
    fun repeat(ticks: Long, action: () -> Boolean) {
        require(ticks > 0L)
        if (phase != OriginSceneExecutionPhase.RUNNING) return
        guarded("schedule") {
            actions.runLater(ticks) {
                if (phase != OriginSceneExecutionPhase.RUNNING) return@runLater
                var again = false
                if (!attempt("step") { again = action() }) {
                    interrupt("repeat-failed")
                } else if (again) repeat(ticks, action)
            }
        }
    }

    fun finish(result: String) {
        if (phase != OriginSceneExecutionPhase.RUNNING) return
        phase = OriginSceneExecutionPhase.RETURNING
        reason = result
        attempt("cancel") { actions.restart() }
        if (!attempt("cleanup") { effects.cleanup(result == "complete") }) {
            interrupt("cleanup-failed")
            return
        }
        guarded("return") { effects.returnHome(result, immediate = false) }
    }

    fun interrupt(
        result: String,
        recoveryPolicy: OriginSceneRecoveryPolicy = OriginSceneRecoveryPolicy.RETURN_HOME,
    ) {
        if (phase == OriginSceneExecutionPhase.FINISHED) return
        if (phase == OriginSceneExecutionPhase.RECOVERING && !closing) return
        phase = OriginSceneExecutionPhase.RECOVERING
        reason = result
        attempt("cancel") { actions.restart() }
        attempt("cancel") { deadline.close() }
        recover(result, recoveryPolicy, 1)
    }

    private fun recover(result: String, recoveryPolicy: OriginSceneRecoveryPolicy, attemptNumber: Int) {
        val cleaned = attempt("cleanup") { effects.cleanup(keepMounted = false) }
        val returned = when (recoveryPolicy) {
            OriginSceneRecoveryPolicy.RETURN_HOME ->
                attempt("return") { effects.returnHome(result, immediate = true) }
            OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION -> true
        }
        if (cleaned && returned) {
            completeReturn(result)
        } else if (attemptNumber >= RECOVERY_ATTEMPTS) {
            completeReturn("$result-recovery-incomplete")
        } else if (closing) {
            recover(result, recoveryPolicy, attemptNumber + 1)
        } else if (!attempt("recovery-schedule") {
            checkNotNull(actions.runLater(RECOVERY_RETRY_TICKS) {
                if (phase == OriginSceneExecutionPhase.RECOVERING) {
                    recover(result, recoveryPolicy, attemptNumber + 1)
                }
            }) { "Scene recovery scope is inactive" }
        }) {
            // Scheduling can also fail during plugin teardown. Drain the small
            // remaining recovery budget without abandoning native resources.
            closing = true
            recover(result, recoveryPolicy, attemptNumber + 1)
        }
    }

    fun completeReturn(result: String) {
        if (phase != OriginSceneExecutionPhase.RETURNING && phase != OriginSceneExecutionPhase.RECOVERING) return
        phase = OriginSceneExecutionPhase.FINISHED
        reason = result
        attempt("cancel") { actions.close() }
        attempt("cancel") { deadline.close() }
        attempt("release") { effects.release(result) }
    }

    private fun guarded(stage: String, action: () -> Unit) {
        if (!attempt(stage, action)) interrupt("$stage-failed")
    }

    private fun attempt(stage: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (failure: Exception) {
        effects.reportFailure(stage, failure)
        false
    }

    override fun close() {
        closing = true
        interrupt("shutdown")
    }

    private companion object {
        const val RECOVERY_ATTEMPTS = 3
        const val RECOVERY_RETRY_TICKS = 20L
    }
}
