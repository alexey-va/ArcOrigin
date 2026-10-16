package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

class OriginOddJobVisualsTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "target surrounds the full block and the heated blank touches the chain instead of floating below it" {
        val frame = originOddJobTargetPieces()
        frame.size shouldBe 12
        for (axis in 0..2) {
            val spans = frame.map { piece -> when (axis) {
                0 -> piece.center.x - piece.size.x / 2.0 to piece.center.x + piece.size.x / 2.0
                1 -> piece.center.y - piece.size.y / 2.0 to piece.center.y + piece.size.y / 2.0
                else -> piece.center.z - piece.size.z / 2.0 to piece.center.z + piece.size.z / 2.0
            } }
            (spans.minOf { it.first } < -0.53 && spans.maxOf { it.second } > 0.53) shouldBe true
        }
        val hung = originOddJobHangingPieces(originOddJobPropPieces(OriginJobProp.FORGE_BLANK))
        abs(hung.maxOf { it.center.y + it.size.y / 2.0 }) shouldBeLessThan 1e-6
        (hung.minOf { it.center.y - it.size.y / 2.0 } < -0.5) shouldBe true
        val carried = originOddJobPropPieces(OriginJobProp.FORGE_BLANK)
        val pose = originOddJobCarryPose(0.0, false, carried)
        abs(pose.center.y + carried.maxOf { it.center.y + it.size.y / 2.0 } - 1.4) shouldBeLessThan 1e-6
    }

    "forge visibly bends around a stable crown and quenching preserves the complete open shoe" {
        val stages = listOf(OriginJobProp.FORGE_BLANK, OriginJobProp.FORGE_HEATED,
            OriginJobProp.FORGE_SHAPED_1, OriginJobProp.FORGE_SHAPED_2, OriginJobProp.FORGE_HORSESHOE)
            .map(::originOddJobPropPieces)
        stages[0].map(::shape) shouldBe stages[1].map(::shape)
        stages.map { shape(it.first()) }.distinct().size shouldBe 1
        val depths = stages.drop(1).map { pieces ->
            pieces.maxOf { it.center.z + it.size.z / 2.0 } - pieces.minOf { it.center.z - it.size.z / 2.0 }
        }
        depths.zipWithNext().all { (before, after) -> after > before } shouldBe true
        val hot = stages.last()
        val cold = originOddJobPropPieces(OriginJobProp.FORGE_COOLED)
        hot.map(::shape) shouldBe cold.map(::shape)
        cold.all { it.material == Material.IRON_BLOCK } shouldBe true
        hot.any { it.material != Material.IRON_BLOCK } shouldBe true
        // Both long arms and the open mouth must remain actual geometry, not a solid item icon.
        hot.any { it.center.x < -0.15 && it.center.z > 0.2 } shouldBe true
        hot.any { it.center.x > 0.15 && it.center.z > 0.2 } shouldBe true
        hot.none { abs(it.center.x) < it.size.x / 2 && it.center.z + it.size.z / 2 > 0.0 } shouldBe true
        OriginJobProp.entries.forEach { prop ->
            val pieces = originOddJobPropPieces(prop)
            check(pieces.size in 1..9)
            abs(pieces.minOf { it.center.y - it.size.y / 2.0 }) shouldBeLessThan 1e-6
            pieces.forEach { check(it.size.x > 0f && it.size.y > 0f && it.size.z > 0f) }
        }
    }

    "forge profile dimensions and cleaning bands use production state geometry at the contact plane" {
        val target = listOf(2, 3, 2)
        val damaged = OriginForgeWork(target, listOf(0, 3, 2))
        val repaired = damaged.copy(shape = target)
        val before = originOddJobForgePieces(damaged, true)
        val after = originOddJobForgePieces(repaired, true)
        before[3].size.z shouldBe 0.19f
        after[3].size.z shouldBe 0.28f
        before[4] shouldBe after[4]
        after.map(::shape) shouldBe originOddJobForgePieces(repaired, false).map(::shape)
        for (shape in listOf(before, after, originOddJobCleaningPieces(0), originOddJobCleaningPieces(7))) {
            abs(shape.minOf { it.center.y - it.size.y / 2.0 }) shouldBeLessThan 1e-6
            shape.all { it.size.x > 0f && it.size.y > 0f && it.size.z > 0f } shouldBe true
        }
        val dirty = originOddJobCleaningPieces(0)
        val partlyClean = originOddJobCleaningPieces(2)
        dirty.map(::shape) shouldBe partlyClean.map(::shape)
        partlyClean.map { it.material } shouldBe listOf(Material.BROWN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BROWN_CONCRETE)
        dirty.map { it.center.x } shouldBe listOf(-0.3, 0.0, 0.3)
        OddJobsPreviewExport.write(Path.of("build/odd-jobs-preview/scene.json"))
    }

    "new forge products have different shapes and engraving preserves the plate while letters appear" {
        val profiles = OriginForgeProduct.entries.filterNot { it == OriginForgeProduct.HORSESHOE }.map { product ->
            val raw = OriginForgeWork(listOf(2, 2, 2), listOf(1, 1, 1), product, OriginForgeMetal.COPPER)
            val done = raw.copy(shape = raw.target)
            originOddJobForgePieces(raw, true).size shouldBe 1
            val cold = originOddJobForgePieces(done, false)
            cold.map(::shape) shouldBe originOddJobForgePieces(done, true).map(::shape)
            cold.all { it.material == Material.COPPER_BLOCK } shouldBe true
            abs(cold.minOf { it.center.y - it.size.y / 2.0 }) shouldBeLessThan 1e-6
            cold.map(::shape)
        }
        profiles.distinct().size shouldBe 3
        val work = OriginForgeEngraving("КОТ", OriginForgeMetal.COPPER)
        val plate = originForgeEngravingPieces(work).single()
        (1..6).forEach { stroke -> originForgeEngravingPieces(work.copy(strokes = stroke)).first() shouldBe plate }
        (originForgeEngravingPieces(work.copy(strokes = 6)).size > 15) shouldBe true
        OddJobsPreviewExport.write(Path.of("build/odd-jobs-preview/scene.json"))
    }
    "a workpiece cue has text and glowing workpiece handles without any proxy or station outline" {
        failOnUnsupportedMockBukkitOperation {
            val player = oddJobCarrier(paper)
            val harness = OddJobDisplayHarness()
            val visuals = OriginOddJobVisuals { harness.owner }
            val request = UUID.randomUUID()
            visuals.place(player, request, player.location, OriginJobProp.FORGE_HEATED)
            val workpiece = harness.blocks.toList()
            visuals.target(player, request, player.location, "Нагрев 50%", outline = false)
            visuals.glowWorkpiece(player, request, false)
            harness.blocks shouldBe workpiece
            workpiece.forEach { verify { it.isGlowing = false } }
            visuals.target(player, request, player.location, "Готово", outline = false)
            visuals.glowWorkpiece(player, request, true)
            harness.blocks shouldBe workpiece
            harness.texts.size shouldBe 1
            workpiece.forEach { verify { it.isGlowing = true } }
            visuals.close()
        }
    }
    "carry uses the body heading keeps the rear edge ahead of the torso and lowers with sneaking" {
        OriginJobProp.entries.forEach { prop ->
            val geometry = originOddJobPropPieces(prop)
            val pose = originOddJobCarryPose(0.0, false, geometry)
            val turned = originOddJobCarryPose(90.0, false, geometry)
            val crouched = originOddJobCarryPose(90.0, true, geometry)
            abs(pose.center.z + geometry.minOf { it.center.z - it.size.z / 2.0 } - 0.62) shouldBeLessThan 1e-6
            abs(turned.center.x + pose.center.z) shouldBeLessThan 1e-6
            abs(turned.center.z - pose.center.x) shouldBeLessThan 1e-6
            abs(crouched.center.y - turned.center.y + 0.25) shouldBeLessThan 1e-6
            turned.rotation.transform(Vector3f(0f, 0f, 1f)).distance(Vector3f(-1f, 0f, 0f)) shouldBeLessThan 1e-6f
        }
    }

    "private ownership fences stale clears and never issues inventory or native entities" {
        failOnUnsupportedMockBukkitOperation {
            val first = oddJobCarrier(paper, "First")
            val second = oddJobCarrier(paper, "Second")
            val world = paper.addSimpleWorld("odd-job-test")
            first.teleport(Location(world, 0.0, 65.0, 0.0))
            second.teleport(Location(world, 0.0, 65.0, 0.0))
            val inventory = first.inventory.contents.toList()
            val nativeEntities = world.entities.map { it.uniqueId }.toSet()
            val harness = OddJobDisplayHarness()
            val visuals = OriginOddJobVisuals { harness.owner }
            val oldRequest = UUID.randomUUID()
            val nextRequest = UUID.randomUUID()
            visuals.carry(first, oldRequest, OriginJobProp.HAY)
            val old = harness.blocks.single()
            visuals.carry(second, oldRequest, OriginJobProp.AUCTION_PACKAGE)
            val other = harness.blocks.last()
            visuals.carry(first, nextRequest, OriginJobProp.HAY)
            val next = harness.blocks.last()
            old.isValid shouldBe false
            visuals.clear(first.uniqueId, oldRequest)
            next.isValid shouldBe true
            other.isValid shouldBe true
            verify { next.isVisibleByDefault = false; next.showTo(first) }
            verify(exactly = 0) { next.showTo(second) }
            verify { other.isVisibleByDefault = false; other.showTo(second) }
            first.inventory.contents.toList() shouldBe inventory
            world.entities.map { it.uniqueId }.toSet() shouldBe nativeEntities
            visuals.clear(first.uniqueId, nextRequest)
            visuals.clear(first.uniqueId, nextRequest)
            verify(exactly = 1) { next.remove() }
            other.isValid shouldBe true
            visuals.close()
            visuals.close()
            other.isValid shouldBe false
            verify(exactly = 1) { harness.owner.close() }
        }
    }

    "heat and quench retain handles and static placed work never follows the walking player" {
        failOnUnsupportedMockBukkitOperation {
            val player = oddJobCarrier(paper)
            val world = paper.addSimpleWorld("odd-job-test")
            player.teleport(Location(world, 0.0, 65.0, 0.0))
            val harness = OddJobDisplayHarness()
            val visuals = OriginOddJobVisuals { harness.owner }
            val request = UUID.randomUUID()
            visuals.carry(player, request, OriginJobProp.FORGE_BLANK)
            val blank = harness.blocks.toList()
            visuals.carry(player, request, OriginJobProp.FORGE_HEATED)
            harness.blocks shouldBe blank
            blank.all(PacketDisplay::isValid) shouldBe true
            visuals.tick()
            blank.forEach { verify(exactly = 1) { it.teleport(any()) } }
            val anvil = Location(world, 2.0, 66.0, 3.0, 90f, 0f)
            visuals.place(player, request, anvil, OriginJobProp.FORGE_SHAPED_1)
            val crown = blank.first()
            crown.isValid shouldBe true
            verifyOrder { crown.attachTo(player); crown.detach(); crown.teleport(anvil.clone().apply { yaw = 0f }) }
            val poses = mutableListOf<Transformation>()
            verify { crown.transformation = capture(poses) }
            val pose = poses.last()
            val bottom = pose.translation.y
            abs(bottom.toDouble()) shouldBeLessThan 1e-6
            pose.leftRotation.transform(Vector3f(0f, 0f, 1f)).distance(Vector3f(-1f, 0f, 0f)) shouldBeLessThan 1e-6f
            player.teleport(Location(world, 10.0, 65.0, 10.0))
            val teleportCount = harness.teleports.size
            visuals.tick()
            harness.teleports.size shouldBe teleportCount
            visuals.place(player, request, anvil, OriginJobProp.FORGE_SHAPED_2)
            visuals.place(player, request, anvil, OriginJobProp.FORGE_HORSESHOE)
            val hot = harness.blocks.filter(PacketDisplay::isValid)
            hot.size shouldBe 7
            visuals.carry(player, request, OriginJobProp.FORGE_COOLED)
            harness.blocks.filter(PacketDisplay::isValid) shouldBe hot
            hot.all { it.blockData.material == Material.IRON_BLOCK } shouldBe true
            hot.forEach { verify { it.brightness = null } }
            visuals.close()
        }
    }

    "target-only changes are private and stale null targets cannot remove the next request" {
        failOnUnsupportedMockBukkitOperation {
            val player = oddJobCarrier(paper)
            val world = paper.addSimpleWorld("odd-job-test")
            player.teleport(Location(world, 0.0, 65.0, 0.0))
            val harness = OddJobDisplayHarness()
            val visuals = OriginOddJobVisuals { harness.owner }
            val oldRequest = UUID.randomUUID()
            val newRequest = UUID.randomUUID()
            visuals.target(player, oldRequest, Location(world, 2.0, 65.0, 3.0), "Накормите коня")
            val firstLabel = harness.texts.single()
            verify { firstLabel.isVisibleByDefault = false; firstLabel.showTo(player) }
            visuals.target(player, oldRequest, Location(world, 2.0, 65.0, 3.0), "Накормите коня")
            harness.texts.size shouldBe 1
            harness.blocks.size shouldBe 12
            visuals.carry(player, oldRequest, OriginJobProp.HAY)
            val hay = harness.blocks.last()
            visuals.target(player, oldRequest, null, "")
            firstLabel.isValid shouldBe false
            hay.isValid shouldBe true
            visuals.target(player, newRequest, Location(world, 3.0, 65.0, 3.0), "Наберите воду")
            hay.isValid shouldBe false
            val label = harness.texts.last()
            visuals.target(player, oldRequest, null, "")
            visuals.clear(player.uniqueId, oldRequest)
            label.isValid shouldBe true
            visuals.clear(player.uniqueId, newRequest)
            harness.blocks.none(PacketDisplay::isValid) shouldBe true
            label.isValid shouldBe false
            visuals.close()
        }
    }

    "world departure clears both carried props and targets without waiting for another action" {
        failOnUnsupportedMockBukkitOperation {
            val player = oddJobCarrier(paper)
            val first = paper.addSimpleWorld("odd-job-first")
            val second = paper.addSimpleWorld("odd-job-second")
            player.teleport(Location(first, 0.0, 65.0, 0.0))
            val harness = OddJobDisplayHarness()
            val visuals = OriginOddJobVisuals { harness.owner }
            val request = UUID.randomUUID()
            visuals.carry(player, request, OriginJobProp.WATER_BUCKET)
            visuals.target(player, request, Location(first, 2.0, 65.0, 3.0), "Напоите коня")
            player.teleport(Location(second, 0.0, 65.0, 0.0))
            visuals.tick()
            harness.blocks.none(PacketDisplay::isValid) shouldBe true
            harness.texts.none(PacketDisplay::isValid) shouldBe true
            visuals.close()
        }
    }

    "partial renderer failure destroys allocated props and lets the runtime abort the request" {
        failOnUnsupportedMockBukkitOperation {
            val player = oddJobCarrier(paper)
            val harness = OddJobDisplayHarness()
            var calls = 0
            every { harness.owner.spawnBlock(any(), any()) } answers {
                if (++calls == 2) throw IllegalStateException("renderer failed")
                harness.block(secondArg())
            }
            val visuals = OriginOddJobVisuals { harness.owner }
            val request = UUID.randomUUID()
            shouldThrow<IllegalStateException> { visuals.carry(player, request, OriginJobProp.WATER_BUCKET) }
            harness.blocks.single().isValid shouldBe false
            visuals.clear(player.uniqueId, request)
            verify(exactly = 1) { harness.blocks.single().remove() }
            visuals.close()
            shouldThrow<IllegalStateException> { visuals.carry(player, request, OriginJobProp.HAY) }
        }
    }
})

private class OddJobDisplayHarness {
    val blocks = mutableListOf<PacketBlockDisplay>()
    val texts = mutableListOf<PacketTextDisplay>()
    val teleports = mutableListOf<Location>()
    val owner = mockk<PaperPacketDisplays>(relaxed = true)

    init {
        every { owner.spawnBlock(any(), any()) } answers { block(secondArg()) }
        every { owner.spawnText(any(), any()) } answers {
            mockk<PacketTextDisplay>(relaxed = true).also { display -> track(display); texts += display }
        }
    }

    fun block(initial: BlockData): PacketBlockDisplay {
        var data = initial
        return mockk<PacketBlockDisplay>(relaxed = true).also { display ->
            track(display)
            every { display.blockData } answers { data }
            every { display.blockData = any() } answers { data = firstArg() }
            blocks += display
        }
    }

    private fun track(display: PacketDisplay) {
        var valid = true
        every { display.isValid } answers { valid }
        every { display.remove() } answers { valid = false }
        every { display.teleport(any()) } answers { teleports += firstArg<Location>().clone() }
    }
}

private fun shape(piece: OriginWorkshopWorkpiecePiece) = piece.center to piece.size

/** MockBukkit does not implement body yaw; never spy PlayerMock (its death accessors recurse). */
private fun oddJobCarrier(paper: MockBukkitTestRuntime, name: String = "OddJobCarrier"): PlayerMock =
    object : PlayerMock(paper.server, name) {
        override fun getBodyYaw(): Float = 0f
        override fun getHeight(): Double = 1.8
    }.also(paper.server::addPlayer)
