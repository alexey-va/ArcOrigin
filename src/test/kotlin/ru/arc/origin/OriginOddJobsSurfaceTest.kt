package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import ru.arc.config.Config
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID

internal fun oddJobTestConfig(): OriginOddJobsConfig {
    val path = Files.createTempFile("odd-jobs-config", ".yml")
    OriginOddJobsConfig::class.java.getResourceAsStream("/modules/origin-odd-jobs.yml")!!.use { Files.write(path, it.readBytes()) }
    return OriginOddJobsConfig.load(Config(path.parent, path.fileName.toString()))
}

class OriginOddJobsSurfaceTest : StringSpec({
    "yard and auction dialogs explain the timed shift and shared configured quota with readable controls" {
        val config = oddJobTestConfig()
        config.venues.map { it.npcId } shouldBe listOf(440, 349, 365)
        config.handoffNpcId shouldBe 382
        config.shiftMillis shouldBe 600_000
        config.lifetimeMillis shouldBe 240_000
        for (venue in config.venues.filter { it.kind != OriginOddJobKind.FORGE }) {
            val screen = originOddJobsScreen(config, venue, null, 20, SideJobRewardStatus(true, 12, 0, false), 0)
            screen.title.color() shouldBe TextColor.color(0xFFB277)
            val body = screen.body.joinToString("\n") { plain(it.text) }
            body shouldContain "100 поручений"
            body shouldContain "2000 💰"
            body shouldContain "12 поручений"
            screen.buttons.single().id.value shouldBe "odd_job_start"
            screen.buttons.single().closeDialogBeforeAction shouldBe false
            val coins = screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }
            coins.size shouldBe 2
            coins.all { it.color() == NamedTextColor.WHITE } shouldBe true
        }
    }

    "native board preserves exact offer IDs while master overview hides out-of-reach craft choices" {
        val config = oddJobTestConfig()
        val venue = config.venue(OriginOddJobKind.MOUNTYARD)
        val shift = OriginWorkShift.begin(0)
        val offers = listOf(
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_RATIONS, 8, false),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_CARAVAN, 9, true),
        )
        val board = originOddJobsScreen(config, venue, null, 20, null, 0, shift = shift, offers = offers)
        board.buttons.map { it.id.value } shouldBe listOf("odd_job_offer_0", "odd_job_offer_1", "odd_job_finish")
        board.body.joinToString { plain(it.text) } shouldContain "100 секунд"
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(), offers.first(), 0)!!
        val station = originOddJobsScreen(config, venue, job, 20, null, 0, shift = shift)
        station.buttons.count { it.id.value.startsWith("odd_job_choice_") } shouldBe 3
        val master = originOddJobsScreen(config, venue, job, 20, null, 0, shift = shift, actionsAvailable = false)
        master.buttons.none { it.id.value.startsWith("odd_job_choice_") } shouldBe true
        master.buttons.map { it.id.value } shouldBe listOf("odd_job_continue", "odd_job_cancel", "odd_job_finish")
        val closing = originOddJobsScreen(config, venue, null, 20, null, 601_000, shift = shift.requestFinish())
        closing.buttons shouldBe emptyList()
    }

    "amendment exposes the actual warehouse number before choosing a candidate" {
        val config = oddJobTestConfig()
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.AUCTION_AMEND, 100, false), 0)!!
        val screen = originOddJobsScreen(config, config.venue(job.kind), job, 20, null, 0, shift = OriginWorkShift.begin(0))
        val body = screen.body.joinToString("\n") { plain(it.text) }
        body shouldContain "Заявка: №199"
        body shouldContain "На самом экземпляре и в описи: №200"
    }

    "all authored craft states resolve source prose and recovery without gray dialog labels" {
        val config = oddJobTestConfig()
        for (variant in OriginOddJobVariant.entries) {
            val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(), OriginOddJobOffer(UUID.randomUUID(), variant, 8, false), 0)!!
            for (stage in OriginOddJobStage.entries.filter { it.name.startsWith(variant.kind.let { k -> when (k) {
                OriginOddJobKind.MOUNTYARD -> "YARD"; OriginOddJobKind.FORGE -> "FORGE"; OriginOddJobKind.AUCTION -> "AUCTION"
            } }) } + listOf(OriginOddJobStage.RETURN, OriginOddJobStage.PAYOUT)) {
                val state = job.copy(stage = stage)
                if (variant.kind == OriginOddJobKind.FORGE) {
                    originOddJobGuide(config, state, 0).let { it.isNotBlank() && !it.contains("{") } shouldBe true
                    originOddJobChoices(config, state) shouldBe emptyList()
                    continue
                }
                val screen = originOddJobsScreen(config, config.venue(variant.kind), state, 20, null, 0, shift = OriginWorkShift.begin(0))
                screen.body.all { plain(it.text).isNotBlank() && !plain(it.text).contains("{") } shouldBe true
                screen.buttons.all { it.label.color() != NamedTextColor.GRAY } shouldBe true
            }
        }
        for (rejection in OriginOddJobRejection.entries) config.line("errors.${rejection.name.lowercase().replace('_', '-')}").isNotBlank() shouldBe true
    }

    "forge has no dialog and keeps the ready cue after a long pause with a white coin receipt" {
        val config = oddJobTestConfig()
        shouldThrow<IllegalArgumentException> {
            originOddJobsScreen(config, config.venue(OriginOddJobKind.FORGE), null, 20, null, 0)
        }
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.FORGE_NEW, 8, false), 0)!!
            .copy(stage = OriginOddJobStage.FORGE_HEATING)
        originOddJobGuide(config, job, 60_000) shouldBe config.line("guide.forge-heat-ready")
        val receipt = waiterText(config.line("forge.paid", "amount" to 20))
        plain(receipt) shouldBe "Принято · +20 💰 · Следующий заказ у Эдгара"
        components(receipt).single { plain(it) == "💰" }.color() shouldBe NamedTextColor.WHITE
    }

    "station resolution uses loaded real blocks including the trough one block away from its old anchor" {
        MockBukkitTestRuntime.open().use {
            failOnUnsupportedMockBukkitOperation {
                val plan = OriginScenePlan.load(Files.createTempDirectory("odd-job-stations"))
                val world = mockk<World>()
                every { world.uid } returns UUID.randomUUID()
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val blocks = mapOf(
                    Triple(55, 71, -125) to Material.HAY_BLOCK,
                    Triple(55, 70, -125) to Material.HAY_BLOCK,
                    Triple(63, 69, -122) to Material.PACKED_MUD,
                    Triple(57, 70, -122) to Material.WATER_CAULDRON,
                    Triple(82, 72, 71) to Material.CHEST,
                    Triple(81, 70, 80) to Material.FURNACE,
                    Triple(76, 70, 69) to Material.ANVIL,
                    Triple(82, 70, 76) to Material.WATER_CAULDRON,
                )
                every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                    val bx = firstArg<Int>(); val by = secondArg<Int>(); val bz = thirdArg<Int>()
                    mockk<Block> {
                        every { x } returns bx
                        every { y } returns by
                        every { z } returns bz
                        every { type } returns (blocks[Triple(bx, by, bz)] ?: Material.AIR)
                        every { this@mockk.world } returns world
                    }
                }
                val stations = originOddJobStations(plan, world)
                stations.size shouldBe 7
                stations.getValue(OriginOddJobStationId.HAY).point.y shouldBe 72.02
                stations.getValue(OriginOddJobStationId.WATER).point shouldBe Location(world, 57.5, 70.9, -121.5)
                stations.getValue(OriginOddJobStationId.FEED).point shouldBe Location(world, 63.5, 70.02, -121.5)
                stations.getValue(OriginOddJobStationId.ANVIL).point.y shouldBe 71.04
                stations.values.all { it.valid() } shouldBe true
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
                originOddJobStations(plan, world) shouldBe emptyMap()
            }
        }
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
private fun components(component: Component): List<Component> = listOf(component) + component.children().flatMap(::components)
