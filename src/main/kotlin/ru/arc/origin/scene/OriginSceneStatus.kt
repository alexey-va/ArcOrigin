package ru.arc.origin.scene

import ru.arc.observability.StructuredDebugLine

/** Read-only operator snapshot; no entity handles or mutable scene state escape. */
data class OriginSceneStatus(
    val sceneId: String,
    val cycleId: String,
    val phase: String,
    val stepId: String?,
    val stepIndex: Int,
    val stepCount: Int,
    val actorIds: List<Int>,
    val displayCount: Int,
    val elapsedMillis: Long,
    val cooldownMillis: Long,
    val reason: String,
    val lastResult: String?,
) {
    fun debugLine(): String = FORMAT.line(
        "scene" to sceneId, "cycle" to cycleId, "phase" to phase,
        "step" to stepId, "progress" to "${stepIndex + 1}/$stepCount",
        "actors" to actorIds.joinToString(","), "displays" to displayCount,
        "elapsed_ms" to elapsedMillis, "cooldown_ms" to cooldownMillis,
        "reason" to reason, "last_result" to lastResult,
    )

    private companion object {
        val FORMAT = StructuredDebugLine("ORIGIN_SCENE_STATUS")
    }
}
