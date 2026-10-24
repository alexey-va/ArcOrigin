package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class OriginOddJobsWorkUnitsTest : StringSpec({
    "finishing a shift after one physical action pays nothing and never completes its cancelled order" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.HAY)
            f.payments shouldBe emptyList()
            f.open(440, sneaking = false); f.press("odd_job_finish")
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.released shouldBe f.leases
            f.payments shouldBe emptyList()
            f.earned shouldBe emptyMap()
            f.settlements.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.completedOrders shouldBe emptyList()
            f.completedOrders.contains(job.id) shouldBe false
        }
    }
    "a completed order retries the same twenty-coin receipt after stopping before shift settlement" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.HAY)
            f.payments shouldBe emptyList()
            f.clickNpc(requireNotNull(job.yard).animalId, sneaking = false)
            val first = f.payments.single()
            first.id shouldBe job.id
            first.amount shouldBe 20
            first.callback(SideJobRewardOutcome.RETRY)
            f.game.stop(f.player.uniqueId, "quit")
            f.now += 999; f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.settlements shouldBe emptyList()
            f.now++; f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments.last().id shouldBe first.id
            f.payments.last().amount shouldBe 20
            f.payments.last().callback(SideJobRewardOutcome.CREDITED)
            first.callback(SideJobRewardOutcome.CREDITED)
            f.earned.values.single().amount shouldBe 20L
            f.settlements.size shouldBe 1
            f.completedOrders shouldBe listOf(job.id)
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "cancelling a later unfinished order retains twenty coins from the earlier completed order" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val completed = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.HAY)
            f.clickNpc(requireNotNull(completed.yard).animalId, sneaking = false)
            val receipt = f.payments.single()
            receipt.id shouldBe completed.id
            receipt.amount shouldBe 20
            receipt.callback(SideJobRewardOutcome.CREDITED)
            f.settlements shouldBe emptyList()
            f.game.tick(f.now); f.scheduler.executeImmediate()
            val unfinished = f.game.currentJob(f.player.uniqueId)!!
            (unfinished.id != completed.id) shouldBe true
            f.gearClick()
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe unfinished.id
            f.payments.size shouldBe 1
            f.game.stop(f.player.uniqueId, "quit")
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.earned.values.single().amount shouldBe 20L
            f.settlements.size shouldBe 1
            f.payments.size shouldBe 1
            f.completedOrders shouldBe listOf(completed.id)
            f.completedOrders.contains(unfinished.id) shouldBe false
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
})
