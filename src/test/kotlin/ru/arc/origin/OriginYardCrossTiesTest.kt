package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Horse
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginYardCrossTiesTest : StringSpec({
    "holding snapshots native flags once and restores them before another owner can acquire the horse" {
        var ai = true; var collidable = true; var gravity = true
        val horse = mockk<Horse>(relaxed = true) {
            every { isValid } returns true
            every { hasAI() } answers { ai }; every { setAI(any()) } answers { ai = firstArg() }
            every { isCollidable } answers { collidable }; every { this@mockk.isCollidable = any() } answers { collidable = firstArg() }
            every { hasGravity() } answers { gravity }; every { setGravity(any()) } answers { gravity = firstArg() }
        }
        val actor = mockk<NPC> { every { id } returns 371; every { entity } returns horse }
        val owner = OriginSceneResources()
        owner.hold(actor); owner.hold(actor)
        listOf(ai, collidable, gravity) shouldBe listOf(false, false, false)
        verify(exactly = 2) { horse.velocity = Vector() }
        owner.releaseHold(actor)
        listOf(ai, collidable, gravity) shouldBe listOf(true, true, true)
        owner.hold(actor)
        owner.cleanup() shouldBe emptyList()
        listOf(ai, collidable, gravity) shouldBe listOf(true, true, true)
        verify(exactly = 0) { horse.teleport(any<Location>()) }
    }

    "one failed removal does not strand healthy ropes and rings and the failed handle is retryable" {
        MockBukkitTestRuntime.open().use {
            val blocks = mutableListOf<PacketBlockDisplay>()
            val renderer = mockk<PaperPacketDisplays> {
                every { spawnBlock(any(), any()) } answers {
                    mockk<PacketBlockDisplay>(relaxed = true).also(blocks::add)
                }
            }
            val world = mockk<World>(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val horse = ORIGIN_YARD_CARE_PAD.inWorld(world)
            val ties = OriginYardCrossTies(renderer, player, horse,
                listOf(ORIGIN_YARD_TIE_FIRST.inWorld(world), ORIGIN_YARD_TIE_SECOND.inWorld(world)))
            ties.setStage(OriginOddJobStage.YARD_UNTIE_FIRST, horse, 1.6)
            blocks.size shouldBe 62
            var fail = true
            every { blocks.first().remove() } answers { if (fail) error("one transport failure") }
            shouldThrow<IllegalStateException> { ties.close() }
            blocks.drop(1).forEach { display -> verify(exactly = 1) { display.remove() } }
            fail = false
            ties.close(); ties.close()
            verify(exactly = 2) { blocks.first().remove() }
            blocks.drop(1).forEach { display -> verify(exactly = 1) { display.remove() } }
        }
    }

    "rope geometry reaches both cheek and real ring with no zero thickness or stretched long texture" {
        val head = Vector3f(-0.2f, 1.312f, -0.8f)
        val ring = Vector3f(-4.44f, 1.4f, -1f)
        val rope = originYardRopePieces(0, head, ring)
        rope.size shouldBe 20
        rope.map { it.key }.toSet().size shouldBe 20
        rope.all { it.size.x > 0.02 && it.size.y > 0.02 && it.size.z in 0.3f..0.5f } shouldBe true
        val start = rope.first().let { it.rotation.transform(Vector3f(0f, 0f, -it.size.z / 2)).add(it.center) }
        val end = rope.last().let { it.rotation.transform(Vector3f(0f, 0f, it.size.z / 2)).add(it.center) }
        (start.distance(head) < 0.00001) shouldBe true
        (end.distance(ring) < 0.00001) shouldBe true
    }

    "ring targeting accepts its real support but rejects the other ring range and occlusion" {
        val world = mockk<World>(relaxed = true) {
            every { uid } returns UUID.randomUUID()
            every { isChunkLoaded(any(), any()) } returns true
            every { rayTraceBlocks(any(), any(), any(), any(), any()) } returns null
        }
        val block = mockk<Block> { every { this@mockk.world } returns world; every { type } returns Material.STONE_BRICKS
            every { x } returns 55; every { y } returns 71; every { z } returns -128 }
        val point = ORIGIN_YARD_TIE_FIRST.inWorld(world)
        val station = OriginOddJobStation(point, block, setOf(Material.STONE_BRICKS))
        var eye = point.clone().add(2.0, 0.22, 0.0).apply { setDirection(point.toVector().subtract(toVector())) }
        val player = mockk<Player> { every { this@mockk.world } returns world; every { eyeLocation } answers { eye.clone() } }
        originYardTieAimed(player, station, 4.0, block) shouldBe true
        originYardTieAimed(player, station, 4.0, mockk()) shouldBe false
        originYardTieAimed(player, station, 1.0, block) shouldBe false
        every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(eye.toVector(), mockk<Block>(), org.bukkit.block.BlockFace.EAST)
        originYardTieAimed(player, station, 4.0, block) shouldBe false
        every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns RayTraceResult(point.toVector(), block, org.bukkit.block.BlockFace.WEST)
        eye = point.clone().add(-2.0, 0.0, 0.0).apply { setDirection(point.toVector().subtract(toVector())) }
        originYardTieAimed(player, station, 4.0, block) shouldBe false
    }
})
