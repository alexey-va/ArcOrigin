package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import ru.arc.config.Config
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.origin.mountyard.OriginMountYardAnimalsConfig
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID

internal fun oddJobTestConfig(): OriginOddJobsConfig {
    val path = Files.createTempFile("odd-jobs-config", ".yml")
    OriginOddJobsConfig::class.java.getResourceAsStream("/modules/origin-odd-jobs.yml")!!.use { Files.write(path, it.readBytes()) }
    return OriginOddJobsConfig.load(Config(path.parent, path.fileName.toString()))
}

internal fun oddJobTestYardCatalog(): ru.arc.origin.mountyard.OriginMountYardAnimalCatalog {
    val path = Files.createTempFile("odd-job-animals", ".yml")
    OriginOddJobsConfig::class.java.getResourceAsStream("/modules/origin-mount-yard.yml")!!.use { Files.write(path, it.readBytes()) }
    return OriginMountYardAnimalsConfig.parse(Config(path.parent, path.fileName.toString())).catalog
}

class OriginOddJobsSurfaceTest : StringSpec({
    "single stock reach uses its block at both reported pickup positions and still rejects distant players" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World> { every { uid } returns UUID.randomUUID() }
            val block = mockk<Block> {
                every { x } returns 82; every { y } returns 72; every { z } returns 71
                every { type } returns Material.CHEST
                every { blockData } returns mockk<org.bukkit.block.data.type.Chest> {
                    every { type } returns org.bukkit.block.data.type.Chest.Type.SINGLE
                }
            }
            val station = OriginOddJobStation(Location(world, 82.5, 72.9, 71.5), block, setOf(Material.CHEST))
            var eye = Location(world, 78.56896202929885, 71.62, 73.58093571451246)
            val player = mockk<Player> { every { this@mockk.world } returns world; every { eyeLocation } answers { eye } }
            val range = oddJobTestConfig().range
            range shouldBe 4.5
            (eye.distanceSquared(station.point) > range * range) shouldBe true
            originOddJobStationInReach(player, station, range) shouldBe true
            eye = Location(world, 78.64128744686796, 71.62, 72.60841123024804)
            originOddJobStationInReach(player, station, range) shouldBe true
            eye = Location(world, 77.5, 72.5, 71.5)
            originOddJobStationInReach(player, station, range) shouldBe true
            eye.x -= 0.01
            originOddJobStationInReach(player, station, range) shouldBe false
            every { player.world } returns mockk<World> { every { uid } returns UUID.randomUUID() }
            originOddJobStationInReach(player, station, range) shouldBe false
        }
    }

    "either half of the same native double chest is reachable for every facing and chest type" {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.CHEST, Material.TRAPPED_CHEST)) for (facing in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                val world = mockk<World> { every { uid } returns UUID.randomUUID() }
                val blocks = oddJobTestDoubleChest(world, 0, 70, 0, material, facing)
                for (anchor in blocks) {
                    originOddJobStationBlocks(anchor) shouldBe blocks
                    val other = blocks.first { it != anchor }
                    val hit = other.location.add(0.5, 0.5, 0.5)
                    val eye = hit.clone().add(0.0, 0.5, -2.0).apply { setDirection(hit.toVector().subtract(toVector())) }
                    val player = mockk<Player> { every { this@mockk.world } returns world; every { eyeLocation } returns eye }
                    every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(hit.toVector(), other, BlockFace.NORTH)
                    val station = OriginOddJobStation(anchor.location.add(0.5, 0.9, 0.5), anchor, setOf(material))
                    originOddJobAimed(player, station, 4.5, other) shouldBe true
                }
            }
        }
    }
    "double chest membership excludes unrelated blocks and still requires clear sight and native reach" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World> { every { uid } returns UUID.randomUUID() }
            val blocks = oddJobTestDoubleChest(world, 0, 70, 0)
            val station = OriginOddJobStation(blocks[0].location.add(0.5, 0.9, 0.5), blocks[0], setOf(Material.CHEST))
            val hit = blocks[1].location.add(0.5, 0.5, 0.5)
            var eye = hit.clone().add(0.0, 0.5, -2.0).apply { setDirection(hit.toVector().subtract(toVector())) }
            val player = mockk<Player> { every { this@mockk.world } returns world; every { eyeLocation } answers { eye } }
            val unrelated = mockk<Block> { every { this@mockk.world } returns world; every { x } returns 2; every { y } returns 70; every { z } returns 0 }
            every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(hit.toVector(), blocks[1], BlockFace.NORTH)
            originOddJobAimed(player, station, 4.5, unrelated) shouldBe false
            val foreignWorld = mockk<World> { every { uid } returns UUID.randomUUID() }
            val foreign = mockk<Block> { every { this@mockk.world } returns foreignWorld }
            originOddJobAimed(player, station, 4.5, foreign) shouldBe false
            every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(eye.toVector(), unrelated, BlockFace.SOUTH)
            originOddJobAimed(player, station, 4.5, blocks[1]) shouldBe false
            eye = hit.clone().add(0.0, 0.0, -10.0).apply { setDirection(hit.toVector().subtract(toVector())) }
            every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(hit.toVector(), blocks[1], BlockFace.NORTH)
            originOddJobAimed(player, station, 4.5, blocks[1]) shouldBe false
            every { (blocks[0].blockData as org.bukkit.block.data.type.Chest).type } returns org.bukkit.block.data.type.Chest.Type.SINGLE
            originOddJobStationBlocks(blocks[0]) shouldBe listOf(blocks[0])
            originOddJobAimed(player, station, 4.5, blocks[1]) shouldBe false
        }
    }
    "stable guidance names the physical task and has no choices or water level instruction" {
        val config = oddJobTestConfig()
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_WATER, 8, false), 0,
            yardTarget = OriginYardWork(422, "Буран"))!!
        for (stage in OriginOddJobStage.entries.filter { it.name.startsWith("YARD_") }) {
            val current = job.copy(stage = stage)
            originOddJobChoices(config, current) shouldBe emptyList()
            val guide = originOddJobGuide(config, current, 45_000)
            guide.contains("уровень", ignoreCase = true) shouldBe false
            guide.contains("порци", ignoreCase = true) shouldBe false
            guide.contains("остановить", ignoreCase = true) shouldBe false
        }
        originOddJobGuide(config, job.copy(stage = OriginOddJobStage.YARD_WATER), 45_000) shouldContain "Буран"
    }
    "all employers share a readable shift confirmation with only the explicit accept action" {
        val config = oddJobTestConfig()
        val receipt = waiterText(config.line("shift.receipt-score", "done" to 3, "amount" to 60), TextColor.color(0x9BD48D))
        plain(receipt) shouldBe "Получено: 60 💰"
        components(receipt).filter { plain(it) == "💰" }.all { it.color() == NamedTextColor.WHITE } shouldBe true
        config.venues.map { it.npcId } shouldBe listOf(440, 349, 351, 354, 365)
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
    "legacy payout deadline does not hide the start button or display a wait" {
        val config = oddJobTestConfig()
        val screen = originOddJobsScreen(config, config.venues.first(), null, 20,
            SideJobRewardStatus(true, 5, 20_000, false), 0)
        val withoutDeadline = originOddJobsScreen(config, config.venues.first(), null, 20,
            SideJobRewardStatus(true, 5, 0, false), 0)
        screen.buttons.first().id.value shouldBe "odd_job_start"
        screen.body.map { plain(it.text) } shouldBe withoutDeadline.body.map { plain(it.text) }
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
            val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(), OriginOddJobOffer(UUID.randomUUID(), variant, 8, false), 0,
                yardTarget = OriginYardWork(422, "Буран", routeTotal = 9))!!
            for (stage in OriginOddJobStage.entries.filter { it.name.startsWith(when (variant.kind) {
                OriginOddJobKind.MOUNTYARD -> if (variant == OriginOddJobVariant.YARD_CLEANUP) "CLEAN" else "YARD"
                OriginOddJobKind.FORGE -> "FORGE"; OriginOddJobKind.AUCTION -> "AUCTION"
                OriginOddJobKind.FORGE_HAUL -> "HAUL"; OriginOddJobKind.FORGE_ENGRAVE -> "ENGRAVE"
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
    "lesson progress grows through physical preparation route and manual equipment returns" {
        val progress = OriginOddJobsProgress(240_000)
        val player = UUID.randomUUID()
        val job = progress.begin(player, OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_LESSON, 8, false), 0,
            yardTarget = OriginYardWork(422, "Буран", routeTotal = 9))!!
        val values = mutableListOf(originOddJobProgress(job, 0))
        var now = 0L
        fun advance(action: OriginOddJobAction, expected: OriginOddJobStage, zone: Int = 1): OriginOddJobSession {
            val current = progress.act(player, action, ++now, zone).shouldBeInstanceOf<OriginOddJobStep.Changed>().job
            current.stage shouldBe expected
            values += originOddJobProgress(current, now)
            return current
        }
        advance(OriginOddJobAction.TACK, OriginOddJobStage.YARD_LEAD)
        advance(OriginOddJobAction.LEAD, OriginOddJobStage.YARD_LEAD_OUT)
        advance(OriginOddJobAction.ARRIVE, OriginOddJobStage.YARD_TIE_FIRST)
        advance(OriginOddJobAction.TIE, OriginOddJobStage.YARD_TIE_HORSE_FIRST)
        advance(OriginOddJobAction.TIE, OriginOddJobStage.YARD_TIE_SECOND)
        advance(OriginOddJobAction.TIE, OriginOddJobStage.YARD_TIE_HORSE_SECOND, zone = 0)
        advance(OriginOddJobAction.TIE, OriginOddJobStage.YARD_BRUSH)
        advance(OriginOddJobAction.BRUSH, OriginOddJobStage.YARD_GROOM)
        repeat(2) { advance(OriginOddJobAction.GROOM, OriginOddJobStage.YARD_GROOM) }
        advance(OriginOddJobAction.GROOM, OriginOddJobStage.YARD_BRUSH_RETURN)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_PAD_RACK)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_PAD)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_TACK)
        advance(OriginOddJobAction.TACK, OriginOddJobStage.YARD_SADDLE)
        advance(OriginOddJobAction.SADDLE, OriginOddJobStage.YARD_GIRTH)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_BRIDLE_RACK)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_BRIDLE)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_STIRRUP_LEFT)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_STIRRUP_RIGHT, zone = 0)
        advance(OriginOddJobAction.GEAR, OriginOddJobStage.YARD_UNTIE_FIRST)
        advance(OriginOddJobAction.UNTIE, OriginOddJobStage.YARD_UNTIE_SECOND)
        advance(OriginOddJobAction.UNTIE, OriginOddJobStage.YARD_DELIVER, zone = 0)
        advance(OriginOddJobAction.ARRIVE, OriginOddJobStage.YARD_HANDOFF)
        val walking = advance(OriginOddJobAction.HANDOFF, OriginOddJobStage.YARD_LESSON_WALK)
        walking.yard!!.routeIndex shouldBe 0
        walking.yard.tieMask shouldBe 0
        values.last() shouldBe 0.72f
        for (checkpoint in 1..9) {
            val expected = if (checkpoint == 9) OriginOddJobStage.YARD_UNTACK_BRIDLE else OriginOddJobStage.YARD_LESSON_WALK
            val before = values.last()
            advance(OriginOddJobAction.ARRIVE, expected).yard!!.routeIndex shouldBe checkpoint
            (values.last() > before && values.last() < 1f) shouldBe true
        }
        val afterRoute = requireNotNull(progress.current(player))
        afterRoute.yard!!.borrowed shouldBe setOf(OriginYardGear.LEAD, OriginYardGear.PAD, OriginYardGear.SADDLE, OriginYardGear.BRIDLE)
        progress.act(player, OriginOddJobAction.RETURN, ++now).shouldBeInstanceOf<OriginOddJobStep.Rejected>()
        progress.current(player) shouldBe afterRoute
        for (expected in listOf(OriginOddJobStage.YARD_BRIDLE_RETURN, OriginOddJobStage.YARD_UNTACK_SADDLE,
                OriginOddJobStage.YARD_SADDLE_RETURN, OriginOddJobStage.YARD_UNTACK_PAD,
                OriginOddJobStage.YARD_PAD_RETURN, OriginOddJobStage.YARD_LEAD_RETURN)) {
            val current = advance(OriginOddJobAction.GEAR, expected)
            current.yard!!.borrowed.isNotEmpty() shouldBe true
            (values.last() < 1f) shouldBe true
        }
        val returned = advance(OriginOddJobAction.GEAR, OriginOddJobStage.RETURN)
        returned.yard!!.borrowed shouldBe emptySet()
        returned.yard.equipped shouldBe emptySet()
        advance(OriginOddJobAction.RETURN, OriginOddJobStage.PAYOUT)
        values.zipWithNext().all { (before, after) -> after >= before } shouldBe true
        values.all { it in 0f..1f } shouldBe true
        values.last() shouldBe 1f
    }
    "taking a full cleaning cart does not reset physical work progress" {
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_CLEANUP, 8, false), 0)!!
        val before = originOddJobProgress(job.copy(stage = OriginOddJobStage.CLEAN_LOAD,
            cleaning = OriginYardCleaningWork(collected = 2)), 0)
        val attached = originOddJobProgress(job.copy(stage = OriginOddJobStage.CLEAN_CART,
            cleaning = OriginYardCleaningWork(collected = 3)), 0)
        (attached > before) shouldBe true
        attached shouldBe 0.6f
    }
    "station resolution uses loaded real blocks including the trough one block away from its old anchor" {
        MockBukkitTestRuntime.open().use {
            failOnUnsupportedMockBukkitOperation {
                val plan = OriginScenePlan.load(Files.createTempDirectory("odd-job-stations"))
                val world = mockk<World>()
                every { world.uid } returns UUID.randomUUID()
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val blocks = mutableMapOf(
                    Triple(55, 71, -125) to Material.HAY_BLOCK,
                    Triple(55, 70, -125) to Material.HAY_BLOCK,
                    Triple(48, 69, -135) to Material.PACKED_MUD,
                    Triple(57, 70, -122) to Material.WATER_CAULDRON,
                    Triple(55, 70, -135) to Material.BARREL,
                    Triple(55, 70, -134) to Material.BARREL,
                    Triple(65, 70, -134) to Material.BARREL,
                    Triple(82, 72, 71) to Material.CHEST,
                    Triple(83, 72, 79) to Material.IRON_CHAIN,
                    Triple(78, 70, 76) to Material.ANVIL,
                    Triple(82, 70, 76) to Material.WATER_CAULDRON,
                    Triple(65, 70, 61) to Material.CHEST,
                    Triple(64, 70, 60) to Material.CHEST,
                    Triple(79, 70, 67) to Material.ANVIL,
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
                stations.keys.containsAll(setOf(OriginOddJobStationId.HAY, OriginOddJobStationId.FEED,
                    OriginOddJobStationId.WATER, OriginOddJobStationId.WELL, OriginOddJobStationId.BRUSH, OriginOddJobStationId.SADDLE_RACK,
                    OriginOddJobStationId.BLANK, OriginOddJobStationId.FIRE, OriginOddJobStationId.ANVIL, OriginOddJobStationId.QUENCH)) shouldBe true
                stations.getValue(OriginOddJobStationId.HAY).point.y shouldBe 72.02
                stations.getValue(OriginOddJobStationId.WATER).point shouldBe Location(world, 57.5, 70.9, -121.5)
                stations.getValue(OriginOddJobStationId.FEED).point shouldBe Location(world, 48.5, 70.02, -134.5)
                stations.getValue(OriginOddJobStationId.ORE_STOCK).point shouldBe Location(world, 65.5, 70.9, 61.5)
                stations.getValue(OriginOddJobStationId.WELL).point shouldBe Location(world, 55.5, 71.02, -134.5)
                stations.getValue(OriginOddJobStationId.BRUSH).point shouldBe Location(world, 55.5, 71.02, -133.5)
                stations.getValue(OriginOddJobStationId.SADDLE_RACK).point shouldBe Location(world, 65.5, 71.02, -133.5)
                stations.getValue(OriginOddJobStationId.ANVIL).point shouldBe Location(world, 78.5, 71.04, 76.5)
                stations.getValue(OriginOddJobStationId.FIRE).point shouldBe Location(world, 83.5, 72.5, 79.5)
                stations.getValue(OriginOddJobStationId.ORE_STOCK).block.type shouldBe Material.CHEST
                stations.getValue(OriginOddJobStationId.ENGRAVING).point shouldBe Location(world, 79.5, 71.04, 67.5)
                stations.values.all { it.valid() } shouldBe true
                // A missing source must not silently resolve to the adjacent brush barrel or the animal's trough.
                blocks[Triple(55, 70, -135)] = Material.AIR
                originOddJobStations(plan, world).containsKey(OriginOddJobStationId.WELL) shouldBe false
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
                originOddJobStations(plan, world) shouldBe emptyMap()
            }
        }
    }
    "Savva keeps a separate ore shop action and workpiece cues replace only occupied station frames" {
        val config = oddJobTestConfig()
        val shop = originOddJobsScreen(config, config.venue(OriginOddJobKind.FORGE_HAUL), null, 20,
            SideJobRewardStatus(true, 100, 0, false), 0, openShop = {})
        shop.buttons.map { it.id.value } shouldBe listOf("odd_job_start", "odd_job_shop", "odd_job_later")
        plain(shop.buttons[1].label) shouldBe "Приём руды"
        config.venue(OriginOddJobKind.FORGE_ENGRAVE).actorIds shouldBe setOf(354)
        val job = OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
            OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.FORGE_NEW, 8, false), 0)!!
        originOddJobWorkpieceTarget(OriginOddJobStage.FORGE_HEAT) shouldBe false
        originOddJobWorkpieceTarget(OriginOddJobStage.FORGE_PLACE) shouldBe false
        val heating = job.copy(stage = OriginOddJobStage.FORGE_HEATING)
        originOddJobWorkpieceTarget(heating.stage) shouldBe true
        originOddJobWorkpieceGlows(heating, 3_999) shouldBe false
        originOddJobWorkpieceGlows(heating, 4_000) shouldBe true
        originOddJobWorkpieceGlows(job.copy(stage = OriginOddJobStage.FORGE_HAMMER), 0) shouldBe true
        originOddJobGuide(config, heating, 2_000) shouldContain "50%"
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
private fun components(component: Component): List<Component> = listOf(component) + component.children().flatMap(::components)

internal fun oddJobTestDoubleChest(world: World, x: Int, y: Int, z: Int, material: Material = Material.CHEST,
    facing: BlockFace = BlockFace.NORTH): List<Block> {
    val (dx, dz) = when (facing) {
        BlockFace.NORTH -> 1 to 0; BlockFace.EAST -> 0 to 1; BlockFace.SOUTH -> -1 to 0; else -> 0 to -1
    }
    val blocks = listOf(x to z, x + dx to z + dz).mapIndexed { index, (bx, bz) ->
        mockk<Block> {
            every { this@mockk.world } returns world
            every { this@mockk.x } returns bx; every { this@mockk.y } returns y; every { this@mockk.z } returns bz
            every { type } returns material
            every { location } answers { Location(world, bx.toDouble(), y.toDouble(), bz.toDouble()) }
            every { blockData } returns mockk<org.bukkit.block.data.type.Chest> chestData@{
                every { type } returns if (index == 0) org.bukkit.block.data.type.Chest.Type.LEFT else org.bukkit.block.data.type.Chest.Type.RIGHT
                every { this@chestData.facing } returns facing
            }
        }
    }
    val states = blocks.map { block -> mockk<org.bukkit.block.Chest>(relaxed = true) { every { this@mockk.block } returns block } }
    val holder = mockk<org.bukkit.block.DoubleChest> { every { leftSide } returns states[0]; every { rightSide } returns states[1] }
    blocks.zip(states).forEach { (block, state) ->
        every { block.state } returns state
        every { state.inventory } returns mockk { every { this@mockk.holder } returns holder }
    }
    return blocks
}
