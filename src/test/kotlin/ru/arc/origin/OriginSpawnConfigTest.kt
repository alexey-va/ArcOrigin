package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.nio.file.Files

class OriginSpawnConfigTest :
    FreeSpec({
        afterTest { ConfigManager.clear() }

        "bundled Origin mask plans exactly 759 chunks from the center outward" {
            val directory = Files.createTempDirectory("arc-origin-config")
            try {
                val config = OriginSpawnConfig.load(directory)
                val plan = OriginChunkPlanner.plan(config.chunkRegion)

                config.enabled shouldBe false
                config.regenerativeBreakingEnabled shouldBe true
                config.regenerativeBreakingRestoreDelayTicks shouldBe 100L
                config.regenerativeBreakingBypassPermission shouldBe "arc.origin.spawn.build"
                config.regenerativeBreakingFeedback.enabled shouldBe true
                config.regenerativeBreakingFeedback.countIntervalTicks shouldBe 20L
                config.regenerativeBreakingFeedback.messageCooldownTicks shouldBe 60L
                config.regenerativeBreakingFeedback.resetAfterTicks shouldBe 2_400L
                config.regenerativeBreakingFeedback.tiers.map(OriginBreakFeedbackTier::fromAttempt) shouldContainExactly
                    listOf(1, 3, 5, 8, 12)
                config.regenerativeBreakingFeedback.tiers.sumOf { it.messages.size } shouldBe 20
                config.cycleTicks shouldBe 40L
                config.pageTicks shouldBe 300L
                config.pedestals shouldContainExactly
                    listOf(
                        AuctionPedestalSpec("configured-1", -58.5, 72.08, -4.5, 0f),
                        AuctionPedestalSpec("configured-2", -61.5, 72.08, -4.5, 0f),
                        AuctionPedestalSpec("configured-3", -64.5, 72.08, -2.5, 0f),
                        AuctionPedestalSpec("configured-4", -58.5, 72.08, 4.5, 180f),
                        AuctionPedestalSpec("configured-5", -61.5, 72.08, 4.5, 180f),
                        AuctionPedestalSpec("configured-6", -64.5, 72.08, 2.5, 180f),
                    )
                plan.size shouldBe 759
                plan.first() shouldBe OriginChunkKey(0, -1)
                plan.toSet().size shouldBe 759
                plan.contains(OriginChunkKey(-16, -12)) shouldBe true
                plan.contains(OriginChunkKey(16, 10)) shouldBe true
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "merge-forward preserves operator values and is idempotent" {
            val directory = Files.createTempDirectory("arc-origin-config-migration")
            try {
                val modules = Files.createDirectories(directory.resolve("modules"))
                val file = modules.resolve("origin-spawn.yml")
                Files.writeString(
                    file,
                    """
                    enabled: false
                    world: operator_origin
                    regenerative-breaking:
                      enabled: false
                      restore-delay-ticks: 40
                      bypass-permission: custom.origin.builder
                      feedback:
                        enabled: false
                        count-interval-ticks: 10
                        message-cooldown-ticks: 30
                        reset-after-ticks: 600
                    chunks:
                      max-in-flight: 3
                    """.trimIndent() + "\n",
                )

                val first = OriginSpawnConfig.load(directory)
                first.worldName shouldBe "operator_origin"
                first.regenerativeBreakingEnabled shouldBe false
                first.regenerativeBreakingRestoreDelayTicks shouldBe 40L
                first.regenerativeBreakingBypassPermission shouldBe "custom.origin.builder"
                first.regenerativeBreakingFeedback.enabled shouldBe false
                first.regenerativeBreakingFeedback.countIntervalTicks shouldBe 10L
                first.regenerativeBreakingFeedback.messageCooldownTicks shouldBe 30L
                first.regenerativeBreakingFeedback.resetAfterTicks shouldBe 600L
                first.chunkRegion.maxInFlight shouldBe 3
                first.pedestals.size shouldBe 6
                val afterFirst = Files.readString(file)

                ConfigManager.ofModule(directory, "origin-spawn.yml")
                    .mergeMissingFromBundled("modules/origin-spawn.yml") shouldBe false
                Files.readString(file) shouldBe afterFirst
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "an explicitly empty pedestal list remains a valid config override" {
            val directory = Files.createTempDirectory("arc-origin-empty-pedestals")
            try {
                val modules = Files.createDirectories(directory.resolve("modules"))
                Files.writeString(
                    modules.resolve("origin-spawn.yml"),
                    "auction-showcase:\n  pedestals: []\n",
                )

                OriginSpawnConfig.load(directory).pedestals shouldBe emptyList()
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "listing values are inserted as literal components rather than MiniMessage" {
            val directory = Files.createTempDirectory("arc-origin-text")
            try {
                val config = OriginSpawnConfig.load(directory)
                val rendered = config.listingText("<red>Меч", "<bold>Игрок", "<click:run_command:/op>10")

                PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe
                    "<red>Меч\nПродавец: <bold>Игрок\nЦена: <click:run_command:/op>10 💰"
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
