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
    "all employers share a readable shift confirmation with only the explicit accept action" {
        val config = oddJobTestConfig()
        config.venues.map { it.npcId } shouldBe listOf(440, 349, 365)
        config.shiftMillis shouldBe 600_000
        for (venue in config.venues) {
            val screen = originOddJobsScreen(config, venue, null, 20, SideJobRewardStatus(true, 12, 0, false), 0)
            screen.title.color() shouldBe TextColor.color(0xFFB277)
            plain(screen.title).startsWith("Смена · ") shouldBe true
            val body = screen.body.joinToString("\n") { plain(it.text) }
            body shouldContain "100"
            body shouldContain "20 💰"
            body shouldContain "12"
            screen.buttons.map { it.id.value } shouldBe listOf("odd_job_start", "odd_job_later")
            plain(screen.buttons.first().label) shouldBe "Выйти на смену"
            screen.buttons.first().closeDialogBeforeAction shouldBe true
            plain(screen.buttons.last().label) shouldBe "Не сейчас"
            screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }.all { it.color() == NamedTextColor.WHITE } shouldBe true
        }
    }
    "working employers never expose task selection or per-order cancellation" {
        val config = oddJobTestConfig()
        for (venue in config.venues) {
            val offer = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.entries.first { it.kind == venue.kind }, 8, false)
            val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(), offer, 0)!!
            val screen = originOddJobsScreen(config, venue, job, 20, null, 0, shift = OriginWorkShift.begin(0))
            screen.buttons.map { it.id.value } shouldBe listOf("odd_job_continue", "odd_job_finish")
            screen.buttons.map { plain(it.label) } shouldBe listOf("Продолжить смену", "Завершить смену")
            screen.buttons[0].label.color() shouldBe TextColor.color(0xFFFFFF)
            screen.buttons[1].label.color() shouldBe TextColor.color(0xFF6B61)
        }
    }
    "auction document decisions stay separate from employer shift controls" {
        val config = oddJobTestConfig()
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.AUCTION_AMEND, 100, false), 0)!!
        val screen = originOddJobWorkScreen(config, job, 0)
        val body = screen.body.joinToString("\n") { plain(it.text) }
        body shouldContain "Заявка: №199"
        body shouldContain "На самом экземпляре и в описи: №200"
        screen.buttons.all { it.id.value.startsWith("odd_job_choice_") } shouldBe true
    }
    "guidance resolves every stage and the ready state persists without automatic heat damage" {
        val config = oddJobTestConfig()
        for (variant in OriginOddJobVariant.entries) {
            val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(), OriginOddJobOffer(UUID.randomUUID(), variant, 8, false), 0)!!
            for (stage in OriginOddJobStage.entries.filter { it.name.startsWith(when (variant.kind) {
                OriginOddJobKind.MOUNTYARD -> "YARD"; OriginOddJobKind.FORGE -> "FORGE"; OriginOddJobKind.AUCTION -> "AUCTION"
            }) } + listOf(OriginOddJobStage.RETURN, OriginOddJobStage.PAYOUT)) {
                val guide = originOddJobGuide(config, job.copy(stage = stage), 0)
                (guide.isNotBlank() && !guide.contains("{")) shouldBe true
                guide.contains("присядь") shouldBe false
            }
        }
        val forge = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.FORGE_NEW, 8, false), 0)!!.copy(stage = OriginOddJobStage.FORGE_HEATING)
        originOddJobGuide(config, forge, 60_000) shouldBe config.line("guide.forge-heat-ready")
        originOddJobProgress(forge, 60_000) shouldBe 0.4f
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
                    Triple(83, 72, 79) to Material.IRON_CHAIN,
                    Triple(78, 70, 76) to Material.ANVIL,
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
                stations.getValue(OriginOddJobStationId.ANVIL).point shouldBe Location(world, 78.5, 71.04, 76.5)
                stations.getValue(OriginOddJobStationId.FIRE).point shouldBe Location(world, 83.5, 72.5, 79.5)
                stations.values.all { it.valid() } shouldBe true
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
                originOddJobStations(plan, world) shouldBe emptyMap()
            }
        }
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
private fun components(component: Component): List<Component> = listOf(component) + component.children().flatMap(::components)
