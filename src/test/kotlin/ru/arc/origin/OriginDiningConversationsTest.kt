package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import ru.arc.origin.OriginDiningConversations.ActorSnapshot
import ru.arc.origin.OriginDiningConversations.Gesture
import ru.arc.config.Config
import java.util.UUID
import java.nio.file.Files

class OriginDiningConversationsTest : FreeSpec({
    "venues run in parallel and oldest pairs get a non-repeating variant" {
        val scheduler = TestTaskScheduler()
        val coordinator = ru.arc.origin.scene.OriginSceneCoordinator()
        val effects = RecordingConversationEffects(
            listOf(1, 2, 3, 4, 5).associateWith { ActorSnapshot(it, if (it == 4 || it == 5) "bar" else "restaurant", true, true) },
        )
        val conversations = OriginDiningConversations(
            coordinator,
            listOf(
                variant("restaurant-red", "restaurant", 1, 2, "red one", "red two"),
                variant("restaurant-blue", "restaurant", 1, 2, "blue one", "blue two"),
                variant("restaurant-green", "restaurant", 1, 3, "green one", "green two"),
                variant("bar-main", "bar", 4, 5, "bar one", "bar two"),
            ),
            effects,
            shortConfig(),
            scheduler,
        )

        conversations.tick(0L)
        effects.speeches.map { it.text } shouldContainExactly listOf("red one", "bar one")

        scheduler.tick(4L)
        conversations.tick(200L)
        scheduler.tick(4L)
        conversations.tick(400L)

        effects.speeches.map { it.text }.filter { it in setOf("red one", "green one", "blue one") } shouldContainExactly listOf(
            "red one", "green one", "blue one",
        )
    }

    "audience loss and actor preemption use execution cleanup" {
        val scheduler = TestTaskScheduler()
        val coordinator = ru.arc.origin.scene.OriginSceneCoordinator()
        val effects = RecordingConversationEffects(
            mapOf(
                1 to ActorSnapshot(1, "restaurant", true, true),
                2 to ActorSnapshot(2, "restaurant", true, true),
            ),
        )
        val conversations = OriginDiningConversations(
            coordinator,
            listOf(variant("cancel-me", "restaurant", 1, 2, "hello", "reply")),
            effects,
            shortConfig(),
            scheduler,
        )

        conversations.tick(0L)
        effects.snapshots[1] = ActorSnapshot(1, "restaurant", false, false)
        scheduler.tick(2L)

        effects.cleanups.size shouldBe 1
        scheduler.tick(20L)
        effects.speeches.size shouldBe 1
    }

    "conversation selection leaves a restaurant waiter available" {
        val effects = RecordingConversationEffects(
            listOf(431, 432, 439).associateWith { ActorSnapshot(it, "restaurant", true, true) },
        )
        val conversations = OriginDiningConversations(
            ru.arc.origin.scene.OriginSceneCoordinator(),
            listOf(
                variant("both-waiters", "restaurant", 431, 432, "both", "reply"),
                variant("one-waiter", "restaurant", 432, 439, "one", "reply"),
            ),
            effects,
            shortConfig(),
            TestTaskScheduler(),
            mayStart = { OriginDiningReliabilityPolicy.leavesRestaurantWaiterFree(it.actorIds, emptySet()) },
        )

        conversations.tick(0L)

        effects.speeches.map { it.text } shouldBe listOf("one")
    }

    "line timing is readable, bounded, and gestures are emitted at the configured cadence" {
        val config = OriginDiningConversations.Config(
                minimumLineMillis = 100L,
            maximumLineMillis = 1_000L,
            charactersPerSecond = 10.0,
            pauseBetweenLinesMillis = 50L,
            gestureEveryLines = 2,
            pairCooldownMillis = 0L,
            executionTimeoutMillis = 5_000L,
        )
        config.lineDurationMillis("1234") shouldBe 400L
        config.lineDurationMillis("x".repeat(100)) shouldBe 1_000L

        val scheduler = TestTaskScheduler()
        val effects = RecordingConversationEffects(
            mapOf(
                1 to ActorSnapshot(1, "restaurant", true, true),
                2 to ActorSnapshot(2, "restaurant", true, true),
            ),
        )
        val conversations = OriginDiningConversations(
            ru.arc.origin.scene.OriginSceneCoordinator(),
            listOf(variant("timed", "restaurant", 1, 2, "1234", "Поднимем кружки", "last")),
            effects,
            config,
            scheduler,
        )

        conversations.tick(0L)
        scheduler.tick(9L)

        effects.speeches.map { it.text } shouldBe listOf("1234", "Поднимем кружки")
        effects.gestures shouldBe listOf(Gesture.TOAST)
    }

    "all six variants in one pair are visited before the anti-repeat cycle restarts" {
        val scheduler = TestTaskScheduler()
        val effects = RecordingConversationEffects(
            mapOf(
                1 to ActorSnapshot(1, "restaurant", true, true),
                2 to ActorSnapshot(2, "restaurant", true, true),
            ),
        )
        val conversations = OriginDiningConversations(
            ru.arc.origin.scene.OriginSceneCoordinator(),
            (1..6).map { index -> variant("v$index", "restaurant", 1, 2, "line-$index", "reply-$index") },
            effects,
            shortConfig(),
            scheduler,
        )

        conversations.tick(0L)
        repeat(5) { index ->
            scheduler.tick(4L)
            conversations.tick((index + 1) * 200L)
        }

        effects.speeches.filterIndexed { index, _ -> index % 2 == 0 }.map { it.text } shouldBe
            (1..6).map { "line-$it" }
    }

    "empty variant catalog disables conversations without reserving actors" {
        val effects = RecordingConversationEffects(emptyMap())
        val conversations = OriginDiningConversations(
            ru.arc.origin.scene.OriginSceneCoordinator(),
            emptyList(),
            effects,
            shortConfig(),
        )

        conversations.tick(0L)

        effects.speeches shouldBe emptyList()
        effects.cleanups shouldBe emptyList()
    }

    "completed conversations respect a venue cooldown before restarting" {
        val scheduler = TestTaskScheduler()
        val effects = RecordingConversationEffects(
            mapOf(
                1 to ActorSnapshot(1, "restaurant", true, true),
                2 to ActorSnapshot(2, "restaurant", true, true),
            ),
        )
        val conversations = OriginDiningConversations(
            ru.arc.origin.scene.OriginSceneCoordinator(),
            listOf(variant("cooldown", "restaurant", 1, 2, "hello", "reply")),
            effects,
            shortConfig().copy(venueCooldownMillis = 3_000L),
            scheduler,
        )

        conversations.tick(0L)
        conversations.tick(200L)
        scheduler.tick(4L)
        effects.speeches.size shouldBe 2
        conversations.tick(3_199L)
        effects.speeches.size shouldBe 2
        conversations.tick(3_200L)
        effects.speeches.size shouldBe 3
    }

    "config loader clamps conversation timing values and keeps venue cooldown configurable" {
        val root = Files.createTempDirectory("arc-dining-conversations-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-dining.yml"),
            """
            conversation:
              minimum-line-millis: 5
              maximum-line-millis: 100000
              characters-per-second: 0.1
              pause-between-lines-millis: -1
              gesture-every-lines: 0
              pair-cooldown-millis: -1
              venue-cooldown-millis: 30000
              execution-timeout-millis: 1000
            """.trimIndent(),
        )

        val config = OriginDiningConversations.Config.load(Config(root, "modules/origin-dining.yml"))

        config.minimumLineMillis shouldBe 100L
        config.maximumLineMillis shouldBe 60_000L
        config.charactersPerSecond shouldBe 1.0
        config.pauseBetweenLinesMillis shouldBe 0L
        config.gestureEveryLines shouldBe 1
        config.pairCooldownMillis shouldBe 0L
        config.venueCooldownMillis shouldBe 30_000L
        config.executionTimeoutMillis shouldBe 60_000L
    }
})

private fun shortConfig() = OriginDiningConversations.Config(
    minimumLineMillis = 100L,
    maximumLineMillis = 100L,
    charactersPerSecond = 80.0,
    pauseBetweenLinesMillis = 0L,
    gestureEveryLines = 32,
    pairCooldownMillis = 0L,
    venueCooldownMillis = 0L,
    executionTimeoutMillis = 2_000L,
)

private fun variant(id: String, venue: String, first: Int, second: Int, vararg lines: String) =
    OriginDiningConversations.Variant(
        id,
        venue,
        OriginDiningDialogue(id, first, second, lines.toList()),
    )

private data class RecordedSpeech(val actorId: Int, val text: String, val ttlMillis: Long)

private class RecordingConversationEffects(
    initialSnapshots: Map<Int, ActorSnapshot>,
) : OriginDiningConversations.Effects {
    val snapshots = initialSnapshots.toMutableMap()
    val speeches = mutableListOf<RecordedSpeech>()
    val gestures = mutableListOf<Gesture>()
    val cleanups = mutableListOf<UUID>()

    override fun snapshot(actorId: Int): ActorSnapshot? = snapshots[actorId]

    override fun face(ownerToken: UUID, actorId: Int, targetActorId: Int) = Unit

    override fun speak(ownerToken: UUID, actorId: Int, text: String, ttlMillis: Long) {
        speeches += RecordedSpeech(actorId, text, ttlMillis)
    }

    override fun gesture(ownerToken: UUID, gesture: Gesture, actorIds: Set<Int>) {
        gestures += gesture
    }

    override fun cleanup(ownerToken: UUID) {
        cleanups += ownerToken
    }

    override fun reportFailure(ownerToken: UUID, stage: String, failure: Exception) = Unit
}
