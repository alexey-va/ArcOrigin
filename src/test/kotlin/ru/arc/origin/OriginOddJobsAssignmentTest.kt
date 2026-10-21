package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.bukkit.Material
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture

class OriginOddJobsAssignmentTest : StringSpec({
    lateinit var f: OddJobsFixture
    beforeEach { f = OddJobsFixture() }
    afterEach { f.close() }

    "a busy first offer yields to another offered craft without bypassing its lease" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            f.acquisitionsToReject = 1
            f.acquireAttempts.clear()
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.leases.size shouldBe 1
            f.now += 3_000
            f.game.tick(f.now); f.scheduler.executeImmediate()
            (f.game.currentJob(f.player.uniqueId) != null) shouldBe true
            f.acquireAttempts.size shouldBe 2
            (f.acquireAttempts[0] != f.acquireAttempts[1]) shouldBe true
            f.leases.last().actorIds shouldBe f.acquireAttempts[1]
            f.released shouldBe listOf(f.leases.first())
            f.payments.size shouldBe 1
            f.settlements shouldBe emptyList()
        }
    }

    "busy worksite retries finish within fifteen seconds and settle only saved work" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            f.acquisitionsBlocked = true
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            repeat(5) {
                f.now += 3_000
                f.game.tick(f.now); f.scheduler.executeImmediate()
            }
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.leases.size shouldBe 1
            f.released shouldBe f.leases
            f.payments.size shouldBe 1
            f.settlements.size shouldBe 1
            f.earned.getValue(f.settlements.single()).amount shouldBe 20L
            f.actionBars().last() shouldContain "недоступно"
            f.now += 30_000; f.game.tick(f.now)
            f.settlements.size shouldBe 1
        }
    }

    "missing assignment stations produce a bounded unavailable outcome after saved credit" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            f.stations.getValue(OriginOddJobStationId.BLANK).block.type = Material.AIR
            f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY).block.type = Material.AIR
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.now += 15_000
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.leases.size shouldBe 1
            f.payments.size shouldBe 1
            f.settlements.size shouldBe 1
            f.actionBars().last() shouldContain "недоступно"
        }
    }

    "an expired haul releases its lease and cannot leave the next assignment preparing forever" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_COPPER)
            val expired = f.game.currentJob(f.player.uniqueId)!!
            f.acquisitionsBlocked = true
            f.now = expired.expiresAt
            f.game.tick(f.now)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.released shouldBe f.leases
            f.game.tick(++f.now); f.scheduler.executeImmediate()
            f.now += 15_000
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leases.size shouldBe 1
            f.payments shouldBe emptyList()
            f.settlements.size shouldBe 1
            f.actionBars().last() shouldContain "недоступно"
        }
    }

    "an unanswered assignment status expires and its late completion cannot replace a newer shift" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val pending = CompletableFuture<SideJobRewardStatus>()
            f.nextStatus = pending
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.now += 15_000
            f.game.tick(f.now); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.settlements.size shouldBe 1
            f.actionBars().last() shouldContain "Касса недоступна"
            f.startForge()
            val current = f.game.currentJob(f.player.uniqueId)!!.id
            pending.complete(f.ready); f.scheduler.executeImmediate()
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe current
            f.leases.size shouldBe 2
            f.payments.size shouldBe 1
            f.settlements.size shouldBe 1
        }
    }

    "an unanswered admission lookup expires and ignores its late completion" {
        failOnUnsupportedMockBukkitOperation {
            val pending = CompletableFuture<SideJobRewardStatus>()
            f.nextStatus = pending
            f.open(349, flush = false, sneaking = false)
            val screens = f.screens.size
            f.scheduler.tick(300)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.actionBars().last() shouldContain "Касса недоступна"
            pending.complete(f.ready); f.scheduler.executeImmediate()
            f.screens.size shouldBe screens
            f.leases shouldBe emptyList()
            f.startForge()
            (f.game.currentJob(f.player.uniqueId) != null) shouldBe true
        }
    }

    "the assignment deadline does not end active work or a pending accepted credit" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge()
            val current = f.game.currentJob(f.player.uniqueId)!!.id
            f.now += 15_000; f.game.tick(f.now)
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe current
            f.settlements shouldBe emptyList()
            f.finishForge()
            f.now += 15_000; f.game.tick(f.now)
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments.size shouldBe 1
            f.settlements shouldBe emptyList()
        }
    }
})
