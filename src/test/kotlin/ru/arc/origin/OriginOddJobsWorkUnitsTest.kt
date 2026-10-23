package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class OriginOddJobsWorkUnitsTest : StringSpec({
    "finishing a shift after one physical action retains twenty coins and never completes its cancelled order" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.HAY)
            val unit = f.payments.single()
            (unit.id != job.id) shouldBe true
            f.open(440, sneaking = false); f.press("odd_job_finish")
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.settlements shouldBe emptyList()
            f.released shouldBe f.leases
            unit.callback(SideJobRewardOutcome.CREDITED)
            unit.callback(SideJobRewardOutcome.CREDITED)
            f.earned.values.single().amount shouldBe 20L
            f.settlements.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.completedOrders shouldBe emptyList()
        }
    }
    "an earned action retries the same receipt after cancellation before shift settlement" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.stroke(OriginOddJobStationId.HAY)
            val first = f.payments.single()
            first.callback(SideJobRewardOutcome.RETRY)
            f.game.stop(f.player.uniqueId, "quit")
            f.now += 999; f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.settlements shouldBe emptyList()
            f.now++; f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments.last().id shouldBe first.id
            f.payments.last().callback(SideJobRewardOutcome.CREDITED)
            first.callback(SideJobRewardOutcome.CREDITED)
            f.earned.values.single().amount shouldBe 20L
            f.settlements.size shouldBe 1
            f.completedOrders shouldBe emptyList()
        }
    }
    "a failed later action ends work without discarding an earlier credited action" {
        OddJobsFixture().use { f ->
            f.begin(440, OriginOddJobVariant.YARD_WATER)
            f.stroke(OriginOddJobStationId.WELL)
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.stroke(OriginOddJobStationId.WATER)
            f.payments.last().callback(SideJobRewardOutcome.DAILY_LIMIT)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.earned.values.single().amount shouldBe 20L
            f.settlements.size shouldBe 1
            f.completedOrders shouldBe emptyList()
        }
    }
})
