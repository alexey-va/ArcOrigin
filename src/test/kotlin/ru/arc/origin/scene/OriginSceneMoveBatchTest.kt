package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OriginSceneMoveBatchTest : FreeSpec({
    "move runs stop at step, actor, and route-profile boundaries" {
        val first = move(actor = 1, anchor = "a")
        val second = move(actor = 1, anchor = "b")
        val otherActor = move(actor = 2, anchor = "c")
        val otherProfile = move(actor = 1, anchor = "d", profile = "animal")
        val afterWait = move(actor = 1, anchor = "e")
        val steps = listOf(
            first,
            second,
            otherActor,
            otherProfile,
            OriginSceneStep.Wait(1),
            afterWait,
        )

        consecutiveOriginSceneMoves(steps, 0) shouldBe listOf(first, second)
        consecutiveOriginSceneMoves(steps, 2) shouldBe listOf(otherActor)
        consecutiveOriginSceneMoves(steps, 3) shouldBe listOf(otherProfile)
        consecutiveOriginSceneMoves(steps, 4) shouldBe emptyList()
        consecutiveOriginSceneMoves(steps, -1) shouldBe emptyList()
    }

    "group move runs preserve actor order and stop at action, actor-order, and profile boundaries" {
        val first = moveGroup(listOf(441, 442), listOf("person-exit", "animal-exit"))
        val second = moveGroup(listOf(441, 442), listOf("person-corner", "animal-corner"))
        val reversedActors = moveGroup(listOf(442, 441), listOf("animal-mid", "person-mid"))
        val changedActor = moveGroup(listOf(441, 443), listOf("person-mid", "other-animal-mid"))
        val otherProfile = moveGroup(listOf(441, 442), listOf("person-front", "animal-front"), profile = "yard")
        val singleMove = move(actor = 441, anchor = "person-home")
        val afterWait = moveGroup(listOf(441, 442), listOf("person-home", "animal-home"))
        val steps = listOf(
            first,
            second,
            reversedActors,
            changedActor,
            otherProfile,
            singleMove,
            OriginSceneStep.Wait(1),
            afterWait,
        )

        consecutiveOriginSceneMoveGroups(steps, 0) shouldBe listOf(first, second)
        consecutiveOriginSceneMoveGroups(steps, 2) shouldBe listOf(reversedActors)
        consecutiveOriginSceneMoveGroups(steps, 3) shouldBe listOf(changedActor)
        consecutiveOriginSceneMoveGroups(steps, 4) shouldBe listOf(otherProfile)
        consecutiveOriginSceneMoveGroups(steps, 5) shouldBe emptyList()
        consecutiveOriginSceneMoveGroups(steps, 6) shouldBe emptyList()
        consecutiveOriginSceneMoveGroups(steps, 7) shouldBe listOf(afterWait)
        consecutiveOriginSceneMoveGroups(steps, -1) shouldBe emptyList()
    }
})

private fun move(actor: Int, anchor: String, profile: String = "human") = OriginSceneStep.Move(
    actorId = actor,
    anchor = anchor,
    routeProfile = profile,
    timeoutTicks = 20,
)

private fun moveGroup(actors: List<Int>, anchors: List<String>, profile: String = "caravan") = OriginSceneStep.MoveGroup(
    actorIds = actors,
    targetAnchors = anchors,
    routeProfile = profile,
    timeoutTicks = 700,
)
