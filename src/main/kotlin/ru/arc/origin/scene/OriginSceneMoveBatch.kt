package ru.arc.origin.scene

/** Returns the contiguous single-actor MOVE run beginning at [startIndex]. */
internal fun consecutiveOriginSceneMoves(
    steps: List<OriginSceneStep>,
    startIndex: Int,
): List<OriginSceneStep.Move> {
    val first = steps.getOrNull(startIndex) as? OriginSceneStep.Move ?: return emptyList()
    val moves = mutableListOf(first)
    for (index in startIndex + 1 until steps.size) {
        val next = steps[index] as? OriginSceneStep.Move ?: break
        if (next.actorId != first.actorId || next.routeProfile != first.routeProfile) break
        moves += next
    }
    return moves
}

/** Returns the contiguous same-group MOVE_GROUP run beginning at [startIndex]. */
internal fun consecutiveOriginSceneMoveGroups(
    steps: List<OriginSceneStep>,
    startIndex: Int,
): List<OriginSceneStep.MoveGroup> {
    val first = steps.getOrNull(startIndex) as? OriginSceneStep.MoveGroup ?: return emptyList()
    val groups = mutableListOf(first)
    for (index in startIndex + 1 until steps.size) {
        val next = steps[index] as? OriginSceneStep.MoveGroup ?: break
        if (next.actorIds != first.actorIds || next.routeProfile != first.routeProfile) break
        groups += next
    }
    return groups
}
