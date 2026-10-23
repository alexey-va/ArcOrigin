package ru.arc.origin

import com.google.gson.GsonBuilder
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

private data class CartMotionFrame(val tick: Int, val worker: OriginWorkshopPoint, val pose: OriginOddJobCartPose)
private data class CartMotionExample(val id: String, val blocks: List<BoundingBox>, val frames: List<CartMotionFrame>)

class OriginOddJobCartTest : StringSpec({
    val layout = OriginForgeCartLayout(OriginWorkshopPoint(0.0, 70.0, 0.0), 180f,
        "test:cart", Vector3f(3f), 0.5625)
    val floor = BoundingBox(-3.0, 69.0, -10.0, 3.0, 70.0, 200.0)
    fun platform(from: Double, to: Double, height: Double) = BoundingBox(-2.0, 70.0, from, 2.0, 70.0 + height, to)
    fun height(blocks: List<BoundingBox>, z: Double): Double = blocks.filter { z >= it.minZ && z < it.maxZ }
        .maxOf { it.maxY }
    fun drive(id: String, blocks: List<BoundingBox>, start: OriginOddJobCartPose, end: Double,
        speed: Double = 0.12, settle: Int = 80): CartMotionExample {
        val motion = OriginOddJobCartMotion(layout)
        val cargo = (0..2).associateWith { mockk<OriginWorkshopProp> {
            every { pieces } returns originForgeCargoPieces(OriginForgeCargo.IRON).map { OriginWorkshopPropPiece(null, it) }
        } }
        val count = ceil(abs(end - start.point.z) / speed).toInt()
        val sign = if (end >= start.point.z) 1 else -1
        var pose = start
        val frames = mutableListOf<CartMotionFrame>()
        for (tick in 0..count + settle) {
            val targetZ = start.point.z + sign * minOf(tick * speed, abs(end - start.point.z))
            val worker = OriginWorkshopPoint(0.0, height(blocks, targetZ - 1), targetZ - 1)
            if (tick > 0) pose = motion.next(pose, OriginWorkshopPoint(0.0, worker.y, targetZ), 180f, worker.y, blocks, cargo)
            frames += CartMotionFrame(tick, worker, pose)
        }
        return CartMotionExample(id, blocks, frames)
    }
    fun start(z: Double = 0.0, y: Double = 70.0) = OriginOddJobCartPose(OriginWorkshopPoint(0.0, y, z), 180f)
    fun bounded(example: CartMotionExample) {
        example.frames.zipWithNext().forEach { (a, b) ->
            hypot(b.pose.point.x - a.pose.point.x, b.pose.point.z - a.pose.point.z) shouldBeLessThan 0.361
            abs(b.pose.point.y - a.pose.point.y) shouldBeLessThan 0.251
            abs(b.pose.pitch - a.pose.pitch).toDouble() shouldBeLessThan 3.501
        }
    }
    fun settled(example: CartMotionExample, z: Double, y: Double) {
        val last = example.frames.last().pose
        abs(last.point.z - z) shouldBeLessThan 0.025
        abs(last.point.y - y) shouldBeLessThan 0.012
        abs(last.pitch).toDouble() shouldBeLessThan 0.1
        // The authored wheel center is 0.375 above the ground and radius 0.375.
        for (x in listOf(-0.5390625f, 0.5390625f)) {
            val wheel = last.rotation().transform(Vector3f(x, 0.375f, 0.140625f))
            abs(last.point.y + wheel.y - 0.375 - y) shouldBeLessThan 0.012
        }
    }

    "source pose export includes continuous slab step descent reverse and wall motion at twenty hertz" {
        val began = System.nanoTime()
        val terraces = listOf(floor, platform(2.0, 4.0, 0.5), platform(4.0, 7.0, 1.0), platform(7.0, 9.0, 0.5))
        val ascent = drive("slab-step-flat", terraces, start(), 11.0)
        val reverse = drive("reverse-descent", terraces, start(6.0, 71.0), 0.0)
        val single = drive("one-block", listOf(floor, platform(2.0, 8.0, 1.0)), start(), 6.0)
        val wall = drive("wall", listOf(floor, platform(2.0, 3.0, 2.0)), start(), 3.5)
        val stairs = (0..7).map { platform(2.0 + it * 0.75, 2.75 + it * 0.75, (it + 1) * 0.5) } + platform(8.0, 16.0, 4.0)
        val hill = drive("stairs-walking", listOf(floor) + stairs, start(), 13.0, speed = 0.21585)
        val examples = listOf(ascent, reverse, single, wall, hill)
        val output = Path.of("build/odd-jobs-preview/cart-motion.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "evidence" to "Actual OriginOddJobCartMotion output at 20 Hz; synthetic native collision boxes, not a live world or native client recording.",
            "millisecondsPerTick" to 50, "model" to layout, "examples" to examples,
            "measurement" to mapOf("solverAndFixtureElapsedMs" to (System.nanoTime() - began) / 1_000_000.0,
                "frames" to examples.sumOf { it.frames.size }, "nativeQueriesPerFrame" to "Bounded loaded collision snapshot; exercised separately by CartDisplayFixture"),
            "cargo" to (0..2).map { slot -> mapOf("offset" to originForgeCartLoadOffset(slot),
                "pieces" to originForgeCargoPieces(OriginForgeCargo.IRON)) })))
        examples.forEach(::bounded)
        settled(ascent, 11.0, 70.0)
        settled(reverse, 0.0, 70.0)
        settled(single, 6.0, 71.0)
        settled(hill, 13.0, 74.0)
        (single.frames.minOf { it.pose.pitch } < -8f) shouldBe true
        // It tips while the front is still beside the riser, before the axle is on top.
        single.frames.any { it.pose.pitch < -8f && it.pose.point.z < 2 && it.pose.point.y > 70.05 } shouldBe true
        wall.frames.maxOf { it.pose.point.z } shouldBeLessThan 1.5
        wall.frames.maxOf { it.pose.point.y } shouldBeLessThan 70.02
    }

    "ordinary walking and sprinting do not accumulate a gap beyond the four metre safety guard" {
        var pose = start()
        val motion = OriginOddJobCartMotion(layout)
        var target = 0.0
        repeat(450) { tick ->
            target += if (tick < 225) 0.21585 else 0.2806
            pose = motion.next(pose, OriginWorkshopPoint(0.0, 70.0, target), 180f, 70.0, listOf(floor))
            (target - pose.point.z) shouldBeLessThan 1.0
        }
        (pose.point.z > 110.0) shouldBe true
    }

    "walking up consecutive half block steps catches up on the summit without resetting the cart" {
        val stairs = (0..7).map { platform(2.0 + it * 0.75, 2.75 + it * 0.75, (it + 1) * 0.5) } + platform(8.0, 16.0, 4.0)
        val example = drive("stairs", listOf(floor) + stairs, start(), 13.0, speed = 0.21585)
        example.frames.maxOf { abs(it.worker.z + 1 - it.pose.point.z) } shouldBeLessThan 4.0
        example.frames.maxOf { abs(it.worker.y - it.pose.point.y) } shouldBeLessThan 3.0
        settled(example, 13.0, 74.0)
    }

    "a low headroom passage and a full height wall cannot be climbed or tunnelled through" {
        val wall = listOf(floor, platform(2.0, 2.04, 2.0))
        val example = drive("thin-wall", wall, start(), 3.5)
        example.frames.maxOf { it.pose.point.z } shouldBeLessThan 1.5
        example.frames.maxOf { it.pose.point.y } shouldBeLessThan 70.02
        val lowRoof = BoundingBox(-2.0, 71.15, 1.0, 2.0, 72.0, 4.0)
        val underRoof = drive("roof", listOf(floor, platform(2.0, 4.0, 0.5), lowRoof), start(), 3.5)
        underRoof.frames.maxOf { it.pose.point.z } shouldBeLessThan 2.0
    }

    "unsupported space is rejected instead of levitating toward the worker" {
        val motion = OriginOddJobCartMotion(layout)
        motion.next(start(), OriginWorkshopPoint(0.0, 70.0, 0.3), 180f, 70.0, emptyList()) shouldBe start()
    }

    "narrow posts and fence rails obstruct the cart without becoming steps" {
        val obstacles = listOf(
            listOf(BoundingBox(-0.125, 70.0, 2.375, 0.125, 71.0, 2.625)),
            listOf(BoundingBox(-0.125, 70.0, 2.375, 0.125, 71.5, 2.625),
                BoundingBox(-0.5, 70.375, 2.4375, 0.5, 71.0, 2.5625)),
            (70..72).map { BoundingBox(-0.5, it.toDouble(), 2.0, 0.5, it + 1.0, 3.0) },
        )
        for ((index, blocks) in obstacles.withIndex()) {
            val example = drive("post-$index", listOf(floor) + blocks, start(), 3.5)
            withClue("obstacle $index") { example.frames.maxOf { it.pose.point.y } shouldBeLessThan 70.02 }
            example.frames.maxOf { it.pose.point.z } shouldBeLessThan 2.0
        }
    }

    "a cart already raised by a narrow post can be pulled off it and settle on the floor" {
        for (height in listOf(1.0, 1.5, 2.0)) {
            val post = BoundingBox(-0.125, 70.0, 2.375, 0.125, 70.0 + height, 2.625)
            val example = drive("post-recovery-$height", listOf(floor, post), start(2.5, 70.0 + height), 0.0, speed = 0.08)
            val output = Path.of("build/odd-jobs-preview/cart-post-recovery-$height.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(example))
            bounded(example)
            withClue("post height $height") { settled(example, 0.0, 70.0) }
        }
    }

    "turning is bounded and cannot sweep the handles through a thin post" {
        val motion = OriginOddJobCartMotion(layout)
        val post = BoundingBox(0.78, 70.0, -0.85, 0.82, 72.0, -0.55)
        var pose = start()
        repeat(50) {
            val next = motion.next(pose, pose.point, 270f, 70.0, listOf(floor, post))
            abs(next.yaw - pose.yaw).toDouble() shouldBeLessThan 6.001
            pose = next
        }
        (pose.yaw < 270f) shouldBe true
    }

    "stable parking accepts real path and slab support and the held cart handles are clickable" {
        for (floorTop in listOf(69.9375, 69.5)) CartDisplayFixture(layout, floorTop).use { f ->
            val pad = ru.arc.origin.scene.OriginScenePoint(0.0, 70.0, 0.0)
            val cleaningLayout = OriginStableCleaningLayout(layout, pad,
                mapOf(422 to OriginStableCleaningStall(pad, List(3) { pad.copy(x = it + 2.0) })))
            OriginOddJobStableCleaning(cleaningLayout, { ItemStack(Material.MINECART) }, { f.owner }).use { cleaning ->
                val job = requireNotNull(OriginOddJobsProgress(240_000).begin(f.player.uniqueId,
                    OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_CLEANUP, 1, false), 0,
                    yardTarget = OriginYardWork(422, "Буран"))).copy(stage = OriginOddJobStage.CLEAN_PUSH)
                cleaning.sync(f.player, job)
                cleaning.canPark(f.player.uniqueId, job.copy(stage = OriginOddJobStage.CLEAN_RETURN)) shouldBe false
                cleaning.tick(f.player, job) shouldBe null
                repeat(60) { cleaning.tick(f.player, job) }
                val station = requireNotNull(cleaning.station(f.player.uniqueId, job))
                abs(station.point.y - 0.65 - floorTop) shouldBeLessThan 0.012
                cleaning.canPark(f.player.uniqueId, job) shouldBe true
                cleaning.tick(f.player, job) shouldBe OriginOddJobAction.PARK_CART
                val handle = station.point.clone().add(0.4, 0.2, -1.04)
                val eye = f.at.clone().add(0.0, 1.62, 0.0).apply { setDirection(handle.toVector().subtract(toVector())) }
                every { f.player.eyeLocation } returns eye
                originOddJobAimed(f.player, station, 4.5, null, virtualTarget = true) shouldBe true
                originOddJobAimed(f.player, station.copy(interactionBounds = null), 4.5, null, virtualTarget = true) shouldBe false
                val home = job.copy(stage = OriginOddJobStage.CLEAN_RETURN)
                cleaning.canPark(f.player.uniqueId, home) shouldBe true
                cleaning.tick(f.player, home) shouldBe OriginOddJobAction.CART_ARRIVE
            }
        }
    }

    "native collision snapshot is bounded and an unloaded or distant destination reads no blocks" {
        CartDisplayFixture(layout).use { f ->
            val initial = f.cart.point(f.player.uniqueId, f.request)!!
            (f.blockReads in 1..320) shouldBe true
            f.loaded = false; f.blockReads = 0; f.at.z += 0.2
            f.cart.follow(f.player, f.request)
            f.blockReads shouldBe 0
            f.loaded = true; f.at.z = 100.0
            f.cart.follow(f.player, f.request)
            f.blockReads shouldBe 0
            f.cart.point(f.player.uniqueId, f.request) shouldBe initial
        }
    }

    "chassis and every cargo piece share one tilted world pose and retain their handles when parked" {
        CartDisplayFixture(layout).use { f ->
            for (x in -2..1) for (z in 2..7) f.obstacles[Triple(x, 70, z)] = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
            val handles = f.transforms.keys.toList()
            var sawTilt = false
            repeat(95) { tick ->
                f.at.z = minOf(tick * 0.09, 5.0) - 1
                f.at.y = if (f.at.z in 2.0..8.0) 71.0 else 70.0
                f.cart.follow(f.player, f.request)
                val chassis = f.transforms.getValue(handles.first())
                val bodyRotation = Quaternionf(chassis.leftRotation).rotateY(-Math.PI.toFloat())
                if (abs(bodyRotation.transform(Vector3f(0f, 0f, 1f)).y) > 0.1f) sawTilt = true
                val point = f.cart.point(f.player.uniqueId, f.request)!!
                val cargoRotation = Quaternionf(bodyRotation).rotateX(Math.toRadians(22.5).toFloat())
                val offset = bodyRotation.transform(originForgeCartLoadOffset(0))
                handles.drop(1).forEachIndexed { index, display ->
                    val geometry = f.cargo[index]
                    val expected = cargoRotation.transform(Vector3f(geometry.center.x.toFloat(), geometry.center.y.toFloat(), geometry.center.z.toFloat()))
                        .add(offset).add(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                    val transform = f.transforms.getValue(display)
                    val center = transform.leftRotation.transform(Vector3f(transform.scale).mul(0.5f))
                        .add(transform.translation)
                    val anchor = f.locations.getValue(display)
                    center.add(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat())
                    center.distance(expected).toDouble() shouldBeLessThan 0.0001
                    abs(Quaternionf(transform.leftRotation).dot(cargoRotation)).toDouble().let { abs(it - 1) } shouldBeLessThan 0.00001
                }
            }
            sawTilt shouldBe true
            val summit = requireNotNull(f.cart.point(f.player.uniqueId, f.request))
            abs(summit.z - 5.0) shouldBeLessThan 0.025
            abs(summit.y - 71.0) shouldBeLessThan 0.012
            val finalBody = Quaternionf(f.transforms.getValue(handles.first()).leftRotation).rotateY(-Math.PI.toFloat())
            abs(finalBody.transform(Vector3f(0f, 0f, 1f)).y).toDouble() shouldBeLessThan 0.002
            f.transforms.keys.toList() shouldBe handles
            val before = f.locations.mapValues { it.value.clone() }
            val rotations = f.transforms.mapValues { Quaternionf(it.value.leftRotation) }
            f.cart.sync(f.player, f.request, false, listOf(f.cargo))
            f.locations shouldBe before
            f.transforms.mapValues { it.value.leftRotation } shouldBe rotations
            handles.forEach { display ->
                verify(exactly = 0) { display.attachTo(any<Player>()) }
                verify { display.teleportDuration = 1 }
                verify { display.interpolationDuration = 1 }
            }
        }
    }
})

private class CartDisplayFixture(layout: OriginForgeCartLayout, floorTop: Double = 70.0) : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val request: UUID = UUID.randomUUID()
    val world: World = mockk(relaxed = true)
    val player: Player = mockk(relaxed = true)
    var at: Location = Location(world, 0.0, 70.0, -1.0)
    var loaded = true
    var blockReads = 0
    val obstacles = mutableMapOf<Triple<Int, Int, Int>, BoundingBox>()
    val transforms = linkedMapOf<PacketDisplay, Transformation>()
    val locations = linkedMapOf<PacketDisplay, Location>()
    val cargo = originForgeCargoPieces(OriginForgeCargo.IRON)
    val cart: OriginOddJobCart
    val owner = mockk<PaperPacketDisplays>(relaxed = true)
    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val key = Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())
            val box = obstacles[key] ?: if (key.second == 69) BoundingBox(0.0, 0.0, 0.0, 1.0, floorTop - 69.0, 1.0) else null
            mockk<Block> {
                every { collisionShape } returns mockk { every { boundingBoxes } returns listOfNotNull(box) }
                every { type } returns if (box == null) Material.AIR else Material.STONE
                every { isPassable } returns (box == null)
                every { x } returns key.first; every { y } returns key.second; every { z } returns key.third
            }
        }
        every { world.getBlockAt(any<Location>()) } answers { firstArg<Location>().let { world.getBlockAt(it.blockX, it.blockY, it.blockZ) } }
        every { world.rayTraceBlocks(any(), any(), any(), any(), any()) } returns null
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.location } answers { at.clone() }
        every { player.height } returns 1.8
        fun track(display: PacketDisplay, at: Location) {
            every { display.isValid } returns true
            locations[display] = at.clone()
            every { display.transformation = any() } answers { transforms[display] = firstArg() }
            every { display.teleport(any()) } answers { locations[display] = firstArg<Location>().clone() }
        }
        every { owner.spawnItem(any(), any()) } answers { mockk<PacketItemDisplay>(relaxed = true).also { track(it, firstArg()) } }
        every { owner.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also { track(it, firstArg()) } }
        cart = OriginOddJobCart(layout, { ItemStack(Material.MINECART) }, { owner })
        cart.sync(player, request, true, listOf(cargo))
    }
    override fun close() { cart.close(); paper.close() }
}
