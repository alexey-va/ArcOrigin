package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.abs

class OriginOddJobCartTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var cart: OriginOddJobCart
    lateinit var player: Player
    lateinit var at: Location
    lateinit var world: World
    val request = UUID.randomUUID()
    val id = UUID.randomUUID()
    val layout = OriginForgeCartLayout(OriginWorkshopPoint(0.0, 70.0, 0.0), 180f,
        "test:cart", Vector3f(3f), 0.5625)
    val obstacles = mutableMapOf<Triple<Int, Int, Int>, BoundingBox>()
    val transforms = linkedMapOf<PacketDisplay, Transformation>()
    var loaded = true
    var blockReads = 0
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        loaded = true; blockReads = 0; obstacles.clear(); transforms.clear()
        world = mockk(relaxed = true)
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        // The real shared collision checker consumes explicit native VoxelShapes. MockBukkit has none.
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val key = Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())
            val box = obstacles[key] ?: if (key.second == 69) BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else null
            mockk<Block> { every { collisionShape } returns mockk { every { boundingBoxes } returns listOfNotNull(box) } }
        }
        at = Location(world, 0.0, 70.0, -1.0)
        player = mockk(relaxed = true)
        every { player.uniqueId } returns id
        every { player.world } returns world
        every { player.location } answers { at.clone() }
        every { player.height } returns 1.8
        val owner = mockk<PaperPacketDisplays>(relaxed = true)
        fun track(display: PacketDisplay) {
            every { display.isValid } returns true
            every { display.transformation = any() } answers { transforms[display] = firstArg() }
        }
        every { owner.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also(::track) }
        every { owner.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(::track) }
        cart = OriginOddJobCart(layout, { ItemStack(Material.MINECART) }, { owner })
        cart.sync(player, request, true, listOf(originForgeCargoPieces(OriginForgeCargo.IRON)))
    }
    afterEach { cart.close(); paper.close() }

    fun anchors() = transforms.values.map { pose ->
        Vector3f(pose.translation).add(at.x.toFloat(), (at.y + player.height).toFloat(), at.z.toFloat())
    }

    "a full block stops the cart and every cargo part at the last safe world pose" {
        val before = anchors()
        val rotations = transforms.values.map { it.leftRotation }
        obstacles[Triple(0, 70, 2)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        at.z = 0.2
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.0, 0.0)
        before.zip(anchors()).forEach { (a, b) -> a.distance(b).toDouble() shouldBeLessThan 1e-5 }
        transforms.values.map { it.leftRotation } shouldBe rotations
        // Releasing uses that same safe pose for both the chassis and its cargo.
        val parked = linkedMapOf<PacketDisplay, Location>()
        transforms.keys.forEach { display -> every { display.teleport(any()) } answers { parked[display] = firstArg<Location>().clone() } }
        cart.sync(player, request, false, listOf(originForgeCargoPieces(OriginForgeCargo.IRON)))
        parked.values.first() shouldBe Location(world, 0.0, 70.5625, 0.0, 180f, 0f)
        before.zip(transforms.map { (display, pose) ->
            val anchor = parked.getValue(display)
            Vector3f(pose.translation).add(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat())
        }).forEach { (a, b) -> a.distance(b).toDouble() shouldBeLessThan 1e-5 }
    }

    "a clear endpoint beyond a wall cannot tunnel across the blocked path" {
        obstacles[Triple(0, 70, 1)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        at.z = 2.5
        val endpoint = originOddJobCartSweptBounds(OriginWorkshopPoint(0.0, 70.0, 3.5), 180f,
            OriginWorkshopPoint(0.0, 70.0, 3.5), 180f, layout)
        (endpoint.minZ > 2.0) shouldBe true
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.0, 0.0)
    }

    "a slab follows the worker's real rise and descent without auto climbing" {
        obstacles[Triple(0, 70, 1)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        at.z = 0.2
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.0, 0.0)
        at.y = 70.5; at.z = -1.0
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.5, 0.0)
        at.z = 0.2
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.5, 1.2)
        at.z = -1.4
        cart.follow(player, request)
        at.y = 70.0
        cart.follow(player, request)
        abs(cart.point(id, request)!!.z + 0.4) shouldBeLessThan 1e-6
        cart.point(id, request)!!.y shouldBe 70.0
    }

    "a diagonal rising keyframe cannot cross a block along its actual interpolation path" {
        obstacles[Triple(0, 70, 1)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        at.y = 71.2; at.z = 2.5
        cart.follow(player, request)
        cart.point(id, request) shouldBe Location(world, 0.0, 70.0, 0.0)
    }

    "turning cannot sweep the handles through an obstacle clear of both endpoint poses" {
        obstacles[Triple(1, 70, -1)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val before = transforms.values.first().leftRotation
        at = Location(world, 0.0, 70.0, 1.0, 180f, 0f)
        cart.follow(player, request)
        transforms.values.first().leftRotation shouldBe before
    }

    "unloaded space and a distant worker keep the cart in place without loading or scanning chunks" {
        loaded = false; blockReads = 0
        at.z += 0.2
        cart.follow(player, request)
        blockReads shouldBe 0
        loaded = true; at.z = 100.0
        cart.follow(player, request)
        blockReads shouldBe 0
        cart.point(id, request) shouldBe Location(world, 0.0, 70.0, 0.0)
    }

    "asymmetric cart bounds match native carry and parked cardinal orientations" {
        for (yaw in listOf(180f, 215f, 270f, 319f, 360f, 90f)) {
            val bounds = originOddJobCartSweptBounds(layout.start, yaw, layout.start, yaw, layout)
            abs(bounds.minY - 70.00001) shouldBeLessThan 1e-6
            val rotation = org.joml.Quaternionf().rotationY(Math.toRadians(180.0 - yaw).toFloat())
            // ItemDisplay's native Y+180 cancels the carry transform's extra Y+180.
            val carriedRotation = org.joml.Quaternionf(rotation).rotateY(Math.PI.toFloat()).rotateY(Math.PI.toFloat())
            val handles = carriedRotation.transform(Vector3f(0f, 0f, -1.0846796f))
            bounds.contains(handles.x.toDouble(), 70.5, handles.z.toDouble()) shouldBe true
            val front = rotation.transform(Vector3f(0f, 0f, 0.913591f))
            bounds.contains(front.x.toDouble(), 70.5, front.z.toDouble()) shouldBe true
            for (x in listOf(-0.5859375f, 0.5859375f)) for (z in listOf(-1.0846796f, 0.913591f)) {
                val corner = carriedRotation.transform(Vector3f(x, 0f, z))
                bounds.clone().expand(1e-6).contains(corner.x.toDouble(), 70.5, corner.z.toDouble()) shouldBe true
            }
            if (yaw % 90f == 0f)
                abs(if (yaw == 180f || yaw == 360f) bounds.widthX - 1.171875 else bounds.widthZ - 1.171875) shouldBeLessThan 1e-6
        }
    }

    "an exact half turn covers both arcs and the float quaternion slerp used by the client" {
        val bounds = originOddJobCartSweptBounds(layout.start, 180f, layout.start, 360f, layout).expand(1e-5)
        val first = org.joml.Quaternionf().rotateY(Math.PI.toFloat())
        val last = org.joml.Quaternionf().rotateY(-Math.PI.toFloat()).rotateY(Math.PI.toFloat())
        for (step in 0..32) {
            val t = step / 32f
            val rotations = listOf(org.joml.Quaternionf(first).slerp(last, t).rotateY(Math.PI.toFloat()),
                org.joml.Quaternionf().rotateY(t * Math.PI.toFloat()),
                org.joml.Quaternionf().rotateY(-t * Math.PI.toFloat()))
            for (rotation in rotations) for (x in listOf(-0.5859375f, 0.5859375f)) for (z in listOf(-1.0846796f, 0.913591f)) {
                val corner = rotation.transform(Vector3f(x, 0f, z))
                bounds.contains(corner.x.toDouble(), 70.5, corner.z.toDouble()) shouldBe true
            }
        }
    }
})
