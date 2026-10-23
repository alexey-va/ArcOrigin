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
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Horse
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginYardCrossTiesTest : StringSpec({
    "wall choices follow attached bits in either order and a pending end is not an attachment" {
        for (first in 0..1) {
            val second = 1 - first
            originYardTieTargets(crossTieJob(OriginOddJobStage.YARD_TIE_FIRST)) shouldBe listOf(0, 1)
            val pending = crossTieJob(OriginOddJobStage.YARD_TIE_HORSE_FIRST, pending = first)
            originYardTieMask(pending) shouldBe 0
            originYardTieTargets(pending) shouldBe emptyList()
            originYardTieTargets(crossTieJob(OriginOddJobStage.YARD_TIE_SECOND, 1 shl first)) shouldBe listOf(second)
            originYardTieMask(crossTieJob(OriginOddJobStage.YARD_TIE_HORSE_SECOND, 1 shl first, second)) shouldBe (1 shl first)
            originYardTieTargets(crossTieJob(OriginOddJobStage.YARD_UNTIE_FIRST, 3)) shouldBe listOf(0, 1)
            originYardTieTargets(crossTieJob(OriginOddJobStage.YARD_UNTIE_SECOND, 1 shl second)) shouldBe listOf(second)
            OriginOddJobSupplies().requiresWorkpiece(pending) shouldBe false
        }
    }

    "both cross ties stay attached through physical preparation and release before the route and gear returns" {
        for (stage in listOf(OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM,
                OriginOddJobStage.YARD_BRUSH_RETURN, OriginOddJobStage.YARD_PAD_RACK, OriginOddJobStage.YARD_PAD,
                OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_GIRTH,
                OriginOddJobStage.YARD_BRIDLE_RACK, OriginOddJobStage.YARD_BRIDLE,
                OriginOddJobStage.YARD_STIRRUP_LEFT, OriginOddJobStage.YARD_STIRRUP_RIGHT)) {
            val job = crossTieJob(stage, mask = 3)
            originYardTieMask(job) shouldBe 3
            originYardTieTargets(job) shouldBe emptyList()
            OriginOddJobSupplies().requiresWorkpiece(job) shouldBe false
        }
        for (stage in listOf(OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_LESSON_WALK,
                OriginOddJobStage.YARD_UNTACK_BRIDLE, OriginOddJobStage.YARD_BRIDLE_RETURN,
                OriginOddJobStage.YARD_UNTACK_SADDLE, OriginOddJobStage.YARD_SADDLE_RETURN,
                OriginOddJobStage.YARD_UNTACK_PAD, OriginOddJobStage.YARD_PAD_RETURN,
                OriginOddJobStage.YARD_LEAD_RETURN, OriginOddJobStage.RETURN)) {
            val job = crossTieJob(stage)
            originYardTieMask(job) shouldBe null
            originYardTieTargets(job) shouldBe emptyList()
        }
    }

    "a loose rope follows the worker and only the horse click moves its existing end to the halter" {
        MockBukkitTestRuntime.open().use {
            for (first in 0..1) {
                val displays = mutableListOf<PacketBlockDisplay>()
                val material = mutableMapOf<PacketBlockDisplay, Material>()
                val transforms = mutableMapOf<PacketBlockDisplay, Transformation>()
                val removed = mutableSetOf<PacketBlockDisplay>()
                val renderer = mockk<PaperPacketDisplays> {
                    every { spawnBlock(any(), any()) } answers {
                        val data = secondArg<BlockData>()
                        mockk<PacketBlockDisplay>(relaxed = true).also { display ->
                            displays += display; material[display] = data.material
                            every { display.transformation = any() } answers { transforms[display] = firstArg() }
                            every { display.remove() } answers { removed += display }
                        }
                    }
                }
                val world = mockk<World>(relaxed = true)
                val horse = ORIGIN_YARD_CARE_PAD.inWorld(world)
                var worker = horse.clone().add(if (first == 0) -3.3 else 2.3, 0.0, -0.8).apply { yaw = if (first == 0) -90f else 90f }
                val player = mockk<Player>(relaxed = true) {
                    every { location } answers { worker.clone() }
                    every { height } returns 1.8
                }
                val ties = OriginYardCrossTies(renderer, player, horse,
                    listOf(ORIGIN_YARD_TIE_FIRST.inWorld(world), ORIGIN_YARD_TIE_SECOND.inWorld(world)))
                fun start(display: PacketBlockDisplay): Vector3f = transforms.getValue(display).let { t ->
                    t.leftRotation.transform(Vector3f(0.5f, 0.5f, 0f).mul(t.scale)).add(t.translation)
                }
                fun hand(): Vector3f = originYardTieHand(worker, 1.8).let {
                    Vector3f((it.x - horse.x).toFloat(), (it.y - horse.y).toFloat(), (it.z - horse.z).toFloat())
                }
                ties.setStage(crossTieJob(OriginOddJobStage.YARD_TIE_FIRST), horse, 1.6)
                material.values.count { it == Material.STRIPPED_OAK_WOOD } shouldBe 0
                ties.setStage(crossTieJob(OriginOddJobStage.YARD_TIE_HORSE_FIRST, pending = first), horse, 1.6)
                val firstRope = displays.filter { material[it] == Material.STRIPPED_OAK_WOOD }
                firstRope.size shouldBe 20
                ties.mask shouldBe 0
                (start(firstRope.first()).distance(hand()) < 0.00001) shouldBe true
                worker = worker.clone().add(if (first == 0) 2.0 else -1.4, 0.0, 0.2)
                ties.render(horse, 1.6)
                (start(firstRope.first()).distance(hand()) < 0.00001) shouldBe true
                material.values.count { it == Material.STRIPPED_OAK_WOOD } shouldBe 20
                ties.setStage(crossTieJob(OriginOddJobStage.YARD_TIE_SECOND, 1 shl first), horse, 1.6)
                // Official 1.1 horse head pivot + cheek ring transformed by its resting 30-degree head pose.
                val cheek = Vector3f(if (first == 0) -0.18f else 0.18f, 1.6963142f, -1.4428846f)
                (start(firstRope.first()).distance(cheek) < 0.00001) shouldBe true
                firstRope.none { it in removed } shouldBe true
                val second = 1 - first
                ties.setStage(crossTieJob(OriginOddJobStage.YARD_TIE_HORSE_SECOND, 1 shl first, second), horse, 1.6)
                material.values.count { it == Material.STRIPPED_OAK_WOOD } shouldBe 40
                ties.mask shouldBe (1 shl first)
                ties.setStage(crossTieJob(OriginOddJobStage.YARD_GROOM, 3), horse, 1.6)
                ties.mask shouldBe 3
                material.values.count { it == Material.STRIPPED_OAK_WOOD } shouldBe 40
                displays.count { it !in removed } shouldBe 58
                ties.close()
                removed.size shouldBe displays.size
            }
        }
    }

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
            ties.setStage(crossTieJob(OriginOddJobStage.YARD_UNTIE_FIRST, 3), horse, 1.6)
            blocks.size shouldBe 66
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
        val loose = originYardRopePieces(0, head, ring, slack = 0.22f)
        loose.map { it.key } shouldBe rope.map { it.key }
        (loose[8].center.y < rope[8].center.y - 0.2f) shouldBe true
        val short = originYardRopePieces(0, head, head, slack = 0.22f)
        short.all { it.size.z > 0f && it.rotation.isFinite } shouldBe true
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

private fun crossTieJob(stage: OriginOddJobStage, mask: Int = 0, pending: Int? = null): OriginOddJobSession =
    requireNotNull(OriginOddJobsProgress(240_000).begin(UUID.randomUUID(),
        OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_SADDLE, 0, false), 0,
        yardTarget = OriginYardWork(422, "Буран", tieMask = mask, pendingTieSide = pending))).copy(stage = stage)
