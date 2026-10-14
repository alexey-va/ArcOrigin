package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class OriginSceneCoordinatorTest : FreeSpec({
    "a joint cycle behind older solo work does not reserve an idle assistant" {
        val coordinator = OriginSceneCoordinator()
        val cycles = mapOf("axe" to setOf(349), "sword" to setOf(349, 358), "hammer" to setOf(358))
        coordinator.delay("forge", "sword", 10L)
        coordinator.delay("forge", "hammer", 20L)
        val axe = coordinator.tryAcquire("forge", "axe", setOf(349), 0L)!!

        coordinator.readyCycleIds("forge", cycles, 100L) shouldBe listOf("hammer")
        coordinator.release(axe, 200L, 1L)
        coordinator.readyCycleIds("forge", cycles, 300L) shouldBe listOf("sword")
    }

    "waiting sword retains its place while solo work finishes and reserves its assistant" {
        val coordinator = OriginSceneCoordinator()
        val cycles = mapOf("axe" to setOf(349), "sword" to setOf(349, 358), "hammer" to setOf(358), "luka" to setOf(354))
        coordinator.delay("forge", "sword", 10L)
        coordinator.delay("forge", "hammer", 20L)
        val axe = coordinator.tryAcquire("forge", "axe", setOf(349), 0L)!!
        coordinator.delay("forge", "axe", 30L)

        coordinator.readyCycleIds("forge", cycles, 100L) shouldBe listOf("luka")
        coordinator.release(axe, 200L, 1L)
        coordinator.readyCycleIds("forge", cycles, 300L) shouldBe listOf("luka", "sword")
        val sword = coordinator.tryAcquire("forge", "sword", setOf(349, 358), 300L)!!
        coordinator.release(sword, 400L, 100L)
        coordinator.readyCycleIds("forge", cycles, 450L) shouldBe listOf("luka", "hammer", "axe")
    }

    "an actor can belong to only one running cycle" {
        val coordinator = OriginSceneCoordinator()

        val forge = coordinator.tryAcquire("forge", "ore-run", setOf(351), nowMillis = 1_000L)
        val competing = coordinator.tryAcquire("forge", "anvil-run", setOf(351, 358), nowMillis = 1_000L)

        forge?.actorIds?.toList() shouldContainExactly listOf(351)
        competing shouldBe null
        coordinator.busyActors() shouldBe setOf(351)
    }

    "release applies a bounded cycle cooldown and frees every actor" {
        val coordinator = OriginSceneCoordinator()
        val lease = coordinator.tryAcquire("mount-yard", "groom-wind", setOf(370, 371), nowMillis = 2_000L)!!

        coordinator.release(lease, nowMillis = 5_000L, cooldownMillis = 12_000L)

        coordinator.busyActors() shouldBe emptySet()
        coordinator.isDue("mount-yard", "groom-wind", nowMillis = 16_999L) shouldBe false
        coordinator.isDue("mount-yard", "groom-wind", nowMillis = 17_000L) shouldBe true
    }

    "stale completion cannot release a newer cycle for the same actor" {
        val coordinator = OriginSceneCoordinator()
        val first = coordinator.tryAcquire("forge", "first", setOf(354), nowMillis = 0L)!!
        coordinator.release(first, nowMillis = 1L, cooldownMillis = 0L)
        val second = coordinator.tryAcquire("forge", "second", setOf(354), nowMillis = 2L)!!

        coordinator.release(first, nowMillis = 3L, cooldownMillis = 0L)

        coordinator.busyActors() shouldBe setOf(354)
        coordinator.release(second, nowMillis = 4L, cooldownMillis = 0L)
        coordinator.busyActors() shouldBe emptySet()
    }

    "successful mounted return routes only the vehicle while abort returns both actors" {
        val actors = setOf(412, 413, 414, 415)
        val pairs = setOf(412 to 413, 414 to 415)

        originSceneReturnActorIds(actors, pairs, keepMounted = true) shouldBe setOf(413, 415)
        originSceneReturnActorIds(actors, pairs, keepMounted = false) shouldBe actors
    }

    "manual acquisition bypasses cooldown but never an actor lease" {
        val coordinator = OriginSceneCoordinator()
        coordinator.delay("forge", "furnace-check", untilMillis = 50_000L)

        coordinator.tryAcquire("forge", "furnace-check", setOf(362), nowMillis = 1_000L) shouldBe null
        val manual = coordinator.tryAcquire("forge", "furnace-check", setOf(362), nowMillis = 1_000L, ignoreDue = true)

        manual?.cycleId shouldBe "furnace-check"
        coordinator.tryAcquire("forge", "other", setOf(362), nowMillis = 1_000L, ignoreDue = true) shouldBe null
    }
})
