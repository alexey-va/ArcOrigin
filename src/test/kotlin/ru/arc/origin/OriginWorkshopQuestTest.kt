package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisOperations
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

class OriginWorkshopQuestTest : StringSpec({
    "furniture quest follows durable claim and actual inventory delivery and rejected replay does not count" {
        WorkshopQuestFixture().use { f ->
            val request = UUID.randomUUID()
            f.rewards.complete(f.player, request, { true }) {}
            f.events shouldBe emptyList()
            f.player.inventory.contains(Material.PAPER) shouldBe false
            f.durable.complete(true); f.scheduler.executeImmediate()
            f.events shouldBe listOf("$request:${f.player.uniqueId}")
            f.player.inventory.contains(Material.PAPER) shouldBe true
            f.rewards.complete(f.player, request, { true }) {}
            f.scheduler.executeImmediate()
            f.events.size shouldBe 1
        }
    }

    "a cancelled bench claim releases quota without delivering furniture or counting a quest" {
        WorkshopQuestFixture().use { f ->
            var valid = true
            f.rewards.complete(f.player, UUID.randomUUID(), { valid }) {}
            valid = false
            f.durable.complete(true); f.scheduler.executeImmediate()
            f.events shouldBe emptyList()
            f.player.inventory.contains(Material.PAPER) shouldBe false
            f.raw shouldBe null
        }
    }
})

private class WorkshopQuestFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val player = paper.server.addPlayer()
    val scheduler = TestTaskScheduler()
    val durable = CompletableFuture<Boolean>()
    var raw: String? = null
    val events = mutableListOf<String>()
    private val logger = Logger.getAnonymousLogger().also { it.level = Level.OFF }
    private val scope = LifecycleTaskScope(scheduler)
    private val quests = OriginQuestProgress(scope, { RankQuestApi { _, event, _, _, _ ->
        player.inventory.contains(Material.PAPER) shouldBe true
        events += event
        CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
    } }, logger)
    private val redis = mockk<RedisOperations>().also { store ->
        every { store.loadMapEntries(any(), any()) } answers { CompletableFuture.completedFuture(listOf(raw)) }
        every { store.compareAndSetMapEntry(any(), any(), any(), any()) } answers {
            val replacement = arg<String?>(3)
            durable.thenApply { accepted -> if (accepted) raw = replacement; accepted }
        }
    }
    val rewards: OriginWorkshopCraftRewards

    init {
        mockkStatic(CustomStack::class)
        val product = mockk<CustomStack>()
        every { product.itemStack } returns ItemStack(Material.PAPER)
        every { CustomStack.getInstance("furniture:chair") } returns product
        rewards = OriginWorkshopCraftRewards(86_400_000L, "furniture:chair", quests, redis, scheduler, logger)
    }

    override fun close() {
        rewards.close()
        scope.close()
        unmockkStatic(CustomStack::class)
        paper.close()
    }
}
