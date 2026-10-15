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
})

private fun move(actor: Int, anchor: String, profile: String = "human") = OriginSceneStep.Move(
    actorId = actor,
    anchor = anchor,
    routeProfile = profile,
    timeoutTicks = 20,
)
