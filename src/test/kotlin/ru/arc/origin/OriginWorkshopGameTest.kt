package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import kotlin.math.abs

class OriginWorkshopGameTest : FreeSpec({
    val rules = OriginWorkshopGameRules()

    "one chair needs two cuts, three holes, both legs and both clamps in that order" {
        var now = 0L
        var progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.STOCK)
        fun act(action: OriginWorkshopGameAction, expected: OriginWorkshopGameStage) {
            val previous = progress
            progress = originWorkshopTransition(progress, action, ++now)!!
            progress.stage shouldBe expected
            originWorkshopTransition(progress, action, now) shouldBe null
            (progress != previous) shouldBe true
        }
        fun finishOperation(ticks: Long, expected: OriginWorkshopGameStage) {
            originWorkshopAdvance(progress, now + ticks - 1, rules) shouldBe progress
            now += ticks
            progress = originWorkshopAdvance(progress, now, rules)
            progress.stage shouldBe expected
        }
        act(OriginWorkshopGameAction.PICK_STOCK, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)
        act(OriginWorkshopGameAction.PLACE_SAW, OriginWorkshopGameStage.START_SAW)
        act(OriginWorkshopGameAction.ACTIVATE_SAW, OriginWorkshopGameStage.SAWING)
        finishOperation(rules.sawTicks, OriginWorkshopGameStage.SAW_REPOSITION)
        act(OriginWorkshopGameAction.REPOSITION_SAW, OriginWorkshopGameStage.START_SAW_SECOND)
        act(OriginWorkshopGameAction.ACTIVATE_SAW, OriginWorkshopGameStage.SAWING_SECOND)
        finishOperation(rules.sawTicks, OriginWorkshopGameStage.PICK_SAWN_BOARD)
        act(OriginWorkshopGameAction.PICK_SAWN_BOARD, OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL)
        act(OriginWorkshopGameAction.PLACE_DRILL, OriginWorkshopGameStage.START_DRILL)
        act(OriginWorkshopGameAction.ACTIVATE_DRILL, OriginWorkshopGameStage.DRILLING)
        finishOperation(rules.drillTicks, OriginWorkshopGameStage.DRILL_ALIGN_CENTER)
        act(OriginWorkshopGameAction.ALIGN_DRILL_CENTER, OriginWorkshopGameStage.START_DRILL_SECOND)
        act(OriginWorkshopGameAction.ACTIVATE_DRILL, OriginWorkshopGameStage.DRILLING_SECOND)
        finishOperation(rules.drillTicks, OriginWorkshopGameStage.DRILL_ALIGN_LAST)
        act(OriginWorkshopGameAction.ALIGN_DRILL_LAST, OriginWorkshopGameStage.START_DRILL_THIRD)
        act(OriginWorkshopGameAction.ACTIVATE_DRILL, OriginWorkshopGameStage.DRILLING_THIRD)
        finishOperation(rules.drillTicks, OriginWorkshopGameStage.PICK_DRILLED_BOARD)
        act(OriginWorkshopGameAction.PICK_DRILLED_BOARD, OriginWorkshopGameStage.CARRY_BOARD_TO_JIG)
        act(OriginWorkshopGameAction.PLACE_JIG, OriginWorkshopGameStage.LEG_LEFT)
        act(OriginWorkshopGameAction.PICK_LEFT_LEG, OriginWorkshopGameStage.CARRY_LEG_LEFT)
        act(OriginWorkshopGameAction.PLACE_LEFT_LEG, OriginWorkshopGameStage.LEG_RIGHT)
        act(OriginWorkshopGameAction.PICK_RIGHT_LEG, OriginWorkshopGameStage.CARRY_LEG_RIGHT)
        act(OriginWorkshopGameAction.PLACE_RIGHT_LEG, OriginWorkshopGameStage.CLAMP_LEFT)
        act(OriginWorkshopGameAction.TIGHTEN_LEFT, OriginWorkshopGameStage.CLAMPING_LEFT)
        finishOperation(rules.clampTicks, OriginWorkshopGameStage.CLAMP_RIGHT)
        act(OriginWorkshopGameAction.TIGHTEN_RIGHT, OriginWorkshopGameStage.CLAMPING_RIGHT)
        finishOperation(rules.clampTicks, OriginWorkshopGameStage.FINISHING)
        finishOperation(rules.chairHoldTicks, OriginWorkshopGameStage.REWARDING)
        originWorkshopAdvance(progress, now + 10_000, rules) shouldBe progress
    }

    "waiting never skips a required pickup and extra clicks never skip processing" {
        for (stage in listOf(OriginWorkshopGameStage.STOCK, OriginWorkshopGameStage.SAW_REPOSITION, OriginWorkshopGameStage.DRILL_ALIGN_CENTER, OriginWorkshopGameStage.DRILL_ALIGN_LAST, OriginWorkshopGameStage.PICK_SAWN_BOARD,
            OriginWorkshopGameStage.PICK_DRILLED_BOARD, OriginWorkshopGameStage.LEG_LEFT,
            OriginWorkshopGameStage.LEG_RIGHT, OriginWorkshopGameStage.CLAMP_LEFT)) {
            val progress = OriginWorkshopGameProgress(stage)
            originWorkshopAdvance(progress, 10_000, rules) shouldBe progress
        }
        for (stage in listOf(OriginWorkshopGameStage.SAWING, OriginWorkshopGameStage.SAWING_SECOND, OriginWorkshopGameStage.DRILLING, OriginWorkshopGameStage.DRILLING_SECOND, OriginWorkshopGameStage.DRILLING_THIRD,
            OriginWorkshopGameStage.CLAMPING_LEFT, OriginWorkshopGameStage.CLAMPING_RIGHT,
            OriginWorkshopGameStage.FINISHING, OriginWorkshopGameStage.REWARDING)) {
            OriginWorkshopGameAction.entries.forEach { action ->
                originWorkshopTransition(OriginWorkshopGameProgress(stage), action, 1) shouldBe null
            }
        }
        originWorkshopTransition(OriginWorkshopGameProgress(OriginWorkshopGameStage.STOCK),
            OriginWorkshopGameAction.PLACE_JIG, 1) shouldBe null
        originWorkshopTransition(OriginWorkshopGameProgress(OriginWorkshopGameStage.LEG_LEFT),
            OriginWorkshopGameAction.TIGHTEN_LEFT, 1) shouldBe null
    }

    "a delayed animation tick finishes only its current operation without resetting or skipping the handoff" {
        val sawing = OriginWorkshopGameProgress(OriginWorkshopGameStage.SAWING, 100)
        val ready = originWorkshopAdvance(sawing, 900, rules)
        ready.stage shouldBe OriginWorkshopGameStage.SAW_REPOSITION
        ready.stageStartedAt shouldBe 900L
        originWorkshopAdvance(ready, 2000, rules) shouldBe ready
    }

    "paired arm and interact packets count once but the next deliberate click is accepted" {
        val window = 120_000_000L
        originWorkshopIsDuplicateClick(null, 1_000, window) shouldBe false
        originWorkshopIsDuplicateClick(1_000, 1_000, window) shouldBe true
        originWorkshopIsDuplicateClick(1_000, 1_000 + window, window) shouldBe true
        originWorkshopIsDuplicateClick(1_000, 1_001 + window, window) shouldBe false
    }

    "session range includes the stock trip and bounded vertical movement, but excludes creative flight" {
        originWorkshopInSessionRange(7.5, 0.0, 0.0) shouldBe true
        originWorkshopInSessionRange(0.0, 7.9, 0.0) shouldBe true
        originWorkshopInSessionRange(12.0, 0.0, 0.0) shouldBe true
        originWorkshopInSessionRange(12.001, 0.0, 0.0) shouldBe false
        originWorkshopInSessionRange(0.0, 8.001, 0.0) shouldBe false
        originWorkshopInSessionRange(9.0, 0.0, 9.0) shouldBe false
    }

    "session range rejects every non-finite coordinate" {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            originWorkshopInSessionRange(invalid, 0.0, 0.0) shouldBe false
            originWorkshopInSessionRange(0.0, invalid, 0.0) shouldBe false
            originWorkshopInSessionRange(0.0, 0.0, invalid) shouldBe false
        }
    }

    "leaving starts a 400 tick grace period and returning resets it" {
        val activity = OriginWorkshopSessionActivity(100L)
        activity.cancelReason(online = true, sameWorld = true, inRange = false, now = 1_000L,
            timeoutTicks = 10_000L) shouldBe null
        activity.outsideSince shouldBe 1_000L
        activity.cancelReason(online = true, sameWorld = true, inRange = false, now = 1_399L,
            timeoutTicks = 10_000L) shouldBe null
        activity.cancelReason(online = true, sameWorld = true, inRange = false, now = 1_400L,
            timeoutTicks = 10_000L) shouldBe OriginWorkshopCancelReason.TOO_FAR

        val returned = OriginWorkshopSessionActivity(100L)
        returned.cancelReason(online = true, sameWorld = true, inRange = false, now = 2_000L,
            timeoutTicks = 10_000L) shouldBe null
        returned.cancelReason(online = true, sameWorld = true, inRange = true, now = 2_399L,
            timeoutTicks = 10_000L) shouldBe null
        returned.outsideSince shouldBe null
        returned.cancelReason(online = true, sameWorld = true, inRange = false, now = 2_400L,
            timeoutTicks = 10_000L) shouldBe null
        returned.outsideSince shouldBe 2_400L
        returned.cancelReason(online = true, sameWorld = true, inRange = false, now = 2_799L,
            timeoutTicks = 10_000L) shouldBe null
        returned.cancelReason(online = true, sameWorld = true, inRange = false, now = 2_800L,
            timeoutTicks = 10_000L) shouldBe OriginWorkshopCancelReason.TOO_FAR
    }

    "idle times out on its boundary while recorded activity extends the deadline" {
        val idle = OriginWorkshopSessionActivity(1_000L)
        idle.cancelReason(online = true, sameWorld = true, inRange = true, now = 1_199L,
            timeoutTicks = 200L) shouldBe null
        idle.lastActivityAt shouldBe 1_000L
        idle.cancelReason(online = true, sameWorld = true, inRange = true, now = 1_200L,
            timeoutTicks = 200L) shouldBe OriginWorkshopCancelReason.TIMEOUT

        val active = OriginWorkshopSessionActivity(1_000L)
        active.cancelReason(online = true, sameWorld = true, inRange = true, now = 1_199L,
            timeoutTicks = 200L) shouldBe null
        active.recordActivity(1_199L)
        active.lastActivityAt shouldBe 1_199L
        active.cancelReason(online = true, sameWorld = true, inRange = true, now = 1_398L,
            timeoutTicks = 200L) shouldBe null
        active.cancelReason(online = true, sameWorld = true, inRange = true, now = 1_399L,
            timeoutTicks = 200L) shouldBe OriginWorkshopCancelReason.TIMEOUT
    }

    "offline and world changes cancel immediately" {
        val offline = OriginWorkshopSessionActivity(0L)
        offline.cancelReason(online = false, sameWorld = true, inRange = true, now = 10_000L,
            timeoutTicks = 20_000L) shouldBe OriginWorkshopCancelReason.OFFLINE
        offline.outsideSince shouldBe null

        val changedWorld = OriginWorkshopSessionActivity(0L)
        changedWorld.cancelReason(online = true, sameWorld = false, inRange = true, now = 10_000L,
            timeoutTicks = 20_000L) shouldBe OriginWorkshopCancelReason.WORLD_CHANGED
        changedWorld.outsideSince shouldBe null
    }

    "aim intersection rejects misses, backward targets, invalid coordinates and overreach" {
        val origin = OriginWorkshopVec3(0.0, 0.0, 0.0)
        val direction = OriginWorkshopVec3(0.0, 0.0, -1.0)
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -3.0), 0.25, 4.5) shouldBe 2.75
        for (target in listOf(OriginWorkshopVec3(0.0, 0.0, -5.0),
            OriginWorkshopVec3(1.0, 0.0, -3.0), OriginWorkshopVec3(0.0, 0.0, 3.0),
            OriginWorkshopVec3(Double.NaN, 0.0, -2.0))) {
            originWorkshopRayHit(origin, direction, target, 0.25, 4.5) shouldBe null
        }
        originWorkshopRayHit(origin, origin, OriginWorkshopVec3(0.0, 0.0, -2.0), 0.25, 4.5) shouldBe null
        originWorkshopRayHit(origin, direction, OriginWorkshopVec3(0.0, 0.0, -2.0), 0.25, 4.5001) shouldBe null
    }

    "highlighted control hit follows its actual scale and rotation instead of a nearby anchor" {
        val origin = OriginWorkshopVec3(0.0, 0.0, 0.0)
        val direction = OriginWorkshopVec3(0.0, 0.0, -1.0)
        val narrowHandle = Matrix4f().translation(-0.04f, -0.30f, -3f).scale(0.08f, 0.60f, 0.08f)
        (abs(originWorkshopRayTransformedCube(origin, direction, narrowHandle, 4.5)!! - 2.92) < 0.0001) shouldBe true
        originWorkshopRayTransformedCube(OriginWorkshopVec3(0.1, 0.0, 0.0), direction, narrowHandle, 4.5) shouldBe null
        val rotated = Matrix4f().translation(0.0f, 0.0f, -3f).rotateY(Math.PI.toFloat() / 2).translate(-0.5f, -0.05f, -0.1f).scale(1f, 0.1f, 0.2f)
        (abs(originWorkshopRayTransformedCube(origin, direction, rotated, 4.5)!! - 2.5) < 0.0001) shouldBe true
        originWorkshopRayTransformedCube(origin, direction, rotated, 2.4) shouldBe null
    }

    "whole bench can be selected from front, side and above without extending reach" {
        val bench = originWorkshopStartInteractionAabb(OriginWorkshopTableDimensions.DEFAULT)
        fun hit(origin: OriginWorkshopVec3, direction: OriginWorkshopVec3, reach: Double = 4.5) =
            originWorkshopRayAabbHit(origin, direction, bench, reach)
        (hit(OriginWorkshopVec3(0.0, 1.2, -3.025), OriginWorkshopVec3(0.0, 0.0, 4.0))!! in 1.999..2.001) shouldBe true
        (hit(OriginWorkshopVec3(-4.4, 1.2, 0.0), OriginWorkshopVec3(1.0, 0.0, 0.0))!! in 1.999..2.001) shouldBe true
        (hit(OriginWorkshopVec3(0.0, 3.63, 0.0), OriginWorkshopVec3(0.0, -1.0, 0.0))!! in 1.999..2.001) shouldBe true
        hit(OriginWorkshopVec3(0.0, 1.2, 0.0), OriginWorkshopVec3(0.0, 0.0, -1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -6.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(3.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 2.0, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, -1.0)) shouldBe null
        hit(OriginWorkshopVec3(Double.NaN, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 0.0)) shouldBe null
        hit(OriginWorkshopVec3(0.0, 1.2, -3.0), OriginWorkshopVec3(0.0, 0.0, 1.0), 4.5001) shouldBe null
    }

})
