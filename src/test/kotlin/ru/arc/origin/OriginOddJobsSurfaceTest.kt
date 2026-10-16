package ru.arc.origin

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
    "all job offers use the existing named NPCs and one shared quota" {
        val config = oddJobTestConfig()
        config.venues.map { it.npcId } shouldBe listOf(440, 349, 365)
        config.handoffNpcId shouldBe 382
        config.lifetimeMillis shouldBe 180_000
        for (venue in config.venues) {
            val screen = originOddJobsScreen(config, venue, null, 20, SideJobRewardStatus(true, 12, 0, false), 0)
            screen.title.color() shouldBe TextColor.color(0xFFB277)
            val body = screen.body.joinToString("\n") { plain(it.text) }
            body shouldContain "20 поручений / 400 💰"
            body shouldContain "12 поручений"
            screen.buttons.single().id.value shouldBe "odd_job_start"
            screen.buttons.single().closeDialogBeforeAction shouldBe (venue.kind != OriginOddJobKind.AUCTION)
            val coins = screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }
            coins.size shouldBe 2
            coins.all { it.color() == NamedTextColor.WHITE } shouldBe true
        }
        config.line("workshop-active") shouldContain "мастерской"
        config.line("waiter-active") shouldContain "официанта"
    }

    "manifest and all packaging choices stay in one native dialog with exact retry rules" {
        val config = oddJobTestConfig()
        val venue = config.venue(OriginOddJobKind.AUCTION)
        for (parcel in OriginAuctionParcel.entries) {
            val job = OriginOddJobSession(UUID.randomUUID(), venue.kind, OriginOddJobStage.AUCTION_ITEM, 0, 180_000, 0, parcel = parcel)
            val item = originOddJobsScreen(config, venue, job, 20, null, 0)
            val packing = originOddJobsScreen(config, venue, job.copy(stage = OriginOddJobStage.AUCTION_PACK), 20, null, 1)
            item.id shouldBe packing.id
            plain(item.body.first().text) shouldContain config.line("auction.${parcel.name.lowercase()}")
            plain(packing.body.first().text) shouldContain config.line("auction.${parcel.name.lowercase()}-rule")
            item.buttons.take(3).all { !it.closeDialogBeforeAction } shouldBe true
            packing.buttons.take(3).all { !it.closeDialogBeforeAction } shouldBe true
            packing.buttons.map { it.id.value } shouldBe listOf("odd_job_pack_padded_box", "odd_job_pack_paper_wrap", "odd_job_pack_scabbard", "odd_job_cancel")
        }
        for ((state, notice) in listOf(
            SideJobRewardStatus(false, 0, 0, true) to "Оплату проверяет касса",
            SideJobRewardStatus(false, 20, 0, false) to "Касса временно недоступна",
            SideJobRewardStatus(true, 0, 0, false) to "03:00 МСК",
            SideJobRewardStatus(true, 10, 20_000, false) to "через 20 с",
        )) {
            val screen = originOddJobsScreen(config, venue, null, 20, state, 0)
            screen.buttons shouldBe emptyList()
            screen.body.joinToString { plain(it.text) } shouldContain notice
        }
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
