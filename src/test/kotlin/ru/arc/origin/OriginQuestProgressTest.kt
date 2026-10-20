package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class OriginQuestProgressTest : StringSpec({
    "order and furniture use their confirmed identities and retry the same observation at most three times" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val observations = mutableListOf<List<Any>>()
        var failing = true
        val api = RankQuestApi { source, event, player, objective, amount ->
            observations += listOf(source, event, player, objective, amount)
            if (failing) CompletableFuture.failedFuture(IllegalStateException("unavailable"))
            else CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
        }
        val quests = OriginQuestProgress(scope, { api }, Logger.getAnonymousLogger().also { it.level = Level.OFF })
        val player = UUID.randomUUID(); val order = UUID.randomUUID()
        quests.orderCompleted(OriginSideJobKind.WAITER, player, order)
        scheduler.tick(600)
        observations.size shouldBe 4
        observations.distinct() shouldBe listOf(listOf("origin_sidejob", "$order:$player", player, "sidejob.complete:waiter", 1L))
        scheduler.pendingCount() shouldBe 0
        failing = false
        val claim = UUID.randomUUID()
        quests.furnitureDelivered(player, claim, "furnituresplus:white_wooden_chair")
        observations.last() shouldBe listOf("origin_furniture", "$claim:$player", player,
            "furniture.craft:furnituresplus:white_wooden_chair", 1L)
        scope.close()
    }

    "an absent optional provider and a retired lifecycle do not affect gameplay" {
        val scheduler = TestTaskScheduler(); val scope = LifecycleTaskScope(scheduler)
        val quests = OriginQuestProgress(scope, { null }, Logger.getAnonymousLogger())
        quests.orderCompleted(OriginSideJobKind.FORGE, UUID.randomUUID(), UUID.randomUUID())
        scope.close()
        quests.furnitureDelivered(UUID.randomUUID(), UUID.randomUUID(), "furniture:chair")
        scheduler.pendingCount() shouldBe 0
    }
})
