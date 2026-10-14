package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginWorkshopSleepShiftTest : FreeSpec({
    val tables = listOf("carpenter", "upholsterer", "assembler", "finisher")
    val duration = 2_400L

    "initial seating may choose any available worker and replacements keep coverage ready" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        val player = UUID.randomUUID()

        shift.ready shouldBe false
        shift.table shouldBe "carpenter"
        shift.acquire("carpenter", player) shouldBe false
        shift.resting(now = 100L, workerIndex = 2)

        shift.ready shouldBe true
        shift.table shouldBe "assembler"
        shift.rotationDue(2_499L) shouldBe false
        shift.rotationDue(2_500L) shouldBe true

        // A next worker can be walking or retrying after a failed route. Until its
        // pose is confirmed and replaceWith is called, the current station stays live.
        for (now in 2_500L..2_900L step 100L) {
            shift.ready shouldBe true
            shift.table shouldBe "assembler"
            shift.rotationDue(now) shouldBe true
        }
    }

    "confirmed replacements rotate through all tables and restart the full timer" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        shift.resting(now = 0L, workerIndex = 0)

        for (step in 1..tables.size) {
            val nextIndex = (step % tables.size)
            val replacementAt = step * duration
            shift.rotationDue(replacementAt) shouldBe true

            shift.replaceWith(nextIndex, replacementAt)

            shift.ready shouldBe true
            shift.index shouldBe nextIndex
            shift.table shouldBe tables[nextIndex]
            shift.rotationDue(replacementAt + duration - 1) shouldBe false
            shift.rotationDue(replacementAt + duration) shouldBe true
        }
        shift.table shouldBe tables.first()
    }

    "a lease acquired while the next worker walks blocks replacement until its owner releases" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        shift.resting(now = 0L)

        shift.rotationDue(duration) shouldBe true
        shift.acquire("carpenter", player) shouldBe true
        shift.acquire("assembler", player) shouldBe false
        shift.acquire("carpenter", other) shouldBe false
        shift.acquire("carpenter", player) shouldBe false
        shouldThrow<IllegalStateException> { shift.replaceWith(1, duration + 1) }
        shift.table shouldBe "carpenter"
        shift.ready shouldBe true
        shift.owns("carpenter", player) shouldBe true
        shift.rotationDue(duration + 1) shouldBe false

        shift.release("assembler", player)
        shift.release("carpenter", other)
        shift.owns("carpenter", player) shouldBe true
        shift.release("carpenter", player)
        shift.rotationDue(duration + 1) shouldBe true

        shift.replaceWith(1, duration + 1)
        shift.table shouldBe "upholsterer"
        shift.ready shouldBe true
        shift.rotationDue(duration + 1 + duration - 1) shouldBe false
        shift.rotationDue(duration + 1 + duration) shouldBe true
    }

    "replacement requires an expired unleased current shift and a different valid worker" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        shouldThrow<IllegalStateException> { shift.replaceWith(1, duration) }

        shift.resting(now = 10L)
        shouldThrow<IllegalStateException> { shift.replaceWith(1, 10L + duration - 1) }
        shouldThrow<IllegalArgumentException> { shift.replaceWith(0, 10L + duration) }
        shouldThrow<IllegalArgumentException> { shift.replaceWith(tables.size, 10L + duration) }
        shift.index shouldBe 0
        shift.table shouldBe "carpenter"
        shift.ready shouldBe true
    }

    "a forced sleeper expires the timer without cancelling a player's lease" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        val player = UUID.randomUUID()
        shift.resting(now = 100L)
        shift.acquire("carpenter", player) shouldBe true

        shift.forceNext(workerIndex = 3, now = 500L)

        shift.forcedNextIndex shouldBe 3
        shift.rotationDue(500L) shouldBe false
        shift.owns("carpenter", player) shouldBe true
        // Candidate selection is pure; tickSleep gates it behind rotationDue before routing.
        shift.nextWorkerIndex(available = setOf(1, 2, 3), returning = emptySet()) shouldBe 3
        shouldThrow<IllegalStateException> { shift.replaceWith(3, 500L) }
        shift.owns("carpenter", player) shouldBe true

        shift.release("carpenter", player)
        shift.rotationDue(500L) shouldBe true
        shift.nextWorkerIndex(available = setOf(1, 2, 3), returning = emptySet()) shouldBe 3
        shouldThrow<IllegalStateException> { shift.replaceWith(2, 500L) }
        shift.replaceWith(3, 500L)

        shift.index shouldBe 3
        shift.forcedNextIndex shouldBe null
        shift.rotationDue(500L + duration - 1) shouldBe false
        shift.rotationDue(500L + duration) shouldBe true
    }

    "a forced target never falls through while unavailable or returning" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        shift.resting(now = 0L)
        shift.forceNext(workerIndex = 2, now = 10L)

        shift.nextWorkerIndex(available = setOf(1, 3), returning = emptySet()) shouldBe null
        shift.nextWorkerIndex(available = setOf(1, 2, 3), returning = setOf(2)) shouldBe null
        shift.forcedNextIndex shouldBe 2
        shift.index shouldBe 0

        shift.nextWorkerIndex(available = setOf(1, 2, 3), returning = emptySet()) shouldBe 2
    }

    "recovery seating another worker preserves the exact forced request" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        shift.resting(now = 0L)
        shift.forceNext(workerIndex = 3, now = 50L)

        shift.invalidate()
        shift.resting(now = 100L, workerIndex = 1)

        shift.index shouldBe 1
        shift.forcedNextIndex shouldBe 3
        shift.rotationDue(100L) shouldBe true
        shift.nextWorkerIndex(available = setOf(0, 2, 3), returning = emptySet()) shouldBe 3
    }

    "invalidation drops an old lease and lets recovery seat another available worker" {
        val shift = OriginWorkshopSleepShift(tables, duration)
        val oldPlayer = UUID.randomUUID()
        shift.resting(now = 0L)
        shift.acquire("carpenter", oldPlayer) shouldBe true

        shift.invalidate()

        shift.ready shouldBe false
        shift.owns("carpenter", oldPlayer) shouldBe false
        shift.acquire("carpenter", UUID.randomUUID()) shouldBe false
        shift.resting(now = 500L, workerIndex = 3)

        shift.ready shouldBe true
        shift.index shouldBe 3
        shift.table shouldBe "finisher"
        shift.owns("finisher", oldPlayer) shouldBe false
        shift.rotationDue(500L + duration - 1) shouldBe false
        shift.rotationDue(500L + duration) shouldBe true
    }
})
