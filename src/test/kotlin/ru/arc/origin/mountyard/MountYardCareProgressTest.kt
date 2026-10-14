package ru.arc.origin.mountyard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class MountYardCareProgressTest : StringSpec({
    "only distinct animals after accepting the task count" {
        val progress = MountYardCareProgress(requiredAnimals = 3, lifetimeMillis = 900_000)
        val player = UUID.randomUUID()
        val first = UUID.randomUUID()
        progress.pet(player, first, 0) shouldBe null
        val session = progress.begin(player, 10)
        progress.pet(player, first, 20)?.animals?.size shouldBe 1
        progress.pet(player, first, 30)?.animals?.size shouldBe 1
        progress.begin(player, 40).id shouldBe session.id
        progress.pet(player, UUID.randomUUID(), 50)?.ready shouldBe false
        progress.pet(player, UUID.randomUUID(), 60)?.ready shouldBe true
    }

    "grant is single-flight and retries keep the same idempotency identity" {
        val progress = MountYardCareProgress(requiredAnimals = 1, lifetimeMillis = 900_000)
        val player = UUID.randomUUID()
        val session = progress.begin(player, 10)
        progress.claim(player, 20) shouldBe null
        progress.pet(player, UUID.randomUUID(), 30)
        progress.claim(player, 40)?.id shouldBe session.id
        progress.claim(player, 50) shouldBe null
        progress.retry(player, session.id) shouldBe true
        progress.retry(player, session.id) shouldBe false
        progress.claim(player, 60)?.id shouldBe session.id
        progress.complete(player, UUID.randomUUID()) shouldBe false
        progress.complete(player, session.id) shouldBe true
        progress.current(player, 70) shouldBe null
    }

    "timeout and a new session reject stale grant callbacks" {
        val progress = MountYardCareProgress(requiredAnimals = 1, lifetimeMillis = 100)
        val player = UUID.randomUUID()
        val old = progress.begin(player, 0)
        progress.pet(player, UUID.randomUUID(), 100) shouldBe null
        progress.begin(player, 101)
        progress.complete(player, old.id) shouldBe false
        progress.clearPlayer(player)
        progress.current(player, 102) shouldBe null
    }

    "quit and reconnect cannot let an old status response consume a new request" {
        val progress = MountYardCareProgress(requiredAnimals = 1, lifetimeMillis = 100)
        val player = UUID.randomUUID()
        val old = checkNotNull(progress.requestStatus(player))
        progress.requestStatus(player) shouldBe null
        progress.clearPlayer(player)
        val current = checkNotNull(progress.requestStatus(player))
        progress.finishStatus(player, old) shouldBe false
        progress.requestStatus(player) shouldBe null
        progress.finishStatus(player, current) shouldBe true
        progress.finishStatus(player, current) shouldBe false
    }
})
