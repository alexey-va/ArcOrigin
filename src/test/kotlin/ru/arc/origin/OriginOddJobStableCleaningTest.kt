package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID

class OriginOddJobStableCleaningTest : StringSpec({
    "a worker facing south carries the portion on the visible right side below the crosshair" {
        // Minecraft south is +Z; the worker's right is west (-X).
        originStableScoopPose(0.0, false).center shouldBe OriginWorkshopPoint(-0.34, 0.9, 0.76)
        originStableScoopPose(0.0, true).center shouldBe OriginWorkshopPoint(-0.34, 0.66, 0.76)
    }

    "cleaning anchors are optional and do not reuse the forge parking point" {
        MockBukkitTestRuntime.open().use {
            val plan = OriginScenePlan.load(Files.createTempDirectory("cleaning-layout"))
            val yard = plan.scene("mount-yard")
            val forge = OriginForgeCartLayout.from(plan.scene("forge"))
            val layout = requireNotNull(OriginStableCleaningLayout.from(yard, forge))
            layout.cart.start shouldBe OriginWorkshopPoint(60.5, 70.0, -132.5)
            layout.cart.itemId shouldBe forge.itemId
            layout.cart.context shouldBe forge.context
            layout.stalls.keys shouldBe setOf(422, 423)
            layout.stalls.values.all { it.piles.size == 3 } shouldBe true
            OriginStableCleaningLayout.from(yard.copy(anchors = yard.anchors - "job-clean-cart"), forge) shouldBe null
        }
    }

    "scoop load unload and empty return keep the same portions and require final parking" {
        CleaningFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.cleaning.available(f.world) shouldBe true
            f.sync()
            f.displays.size shouldBe 10 // One real pack model and three three-part portions.
            val initial = f.displays.toList()
            f.advance(OriginOddJobAction.PICKUP)
            f.advance(OriginOddJobAction.ATTACH_CART)
            f.pushTo(f.stall.cart)
            f.cleaning.tick(f.player, f.job) shouldBe null // Parking at the stall is explicit.
            f.cleaning.canPark(f.player.uniqueId, f.job) shouldBe true
            f.advance(OriginOddJobAction.PARK_CART)
            repeat(3) { index ->
                val pile = requireNotNull(f.cleaning.station(f.player.uniqueId, f.job))
                pile.point.x shouldBe f.stall.piles[index].x
                f.advance(OriginOddJobAction.SCOOP)
                f.job.stage shouldBe OriginOddJobStage.CLEAN_LOAD
                f.blocks.take((index + 1) * 3).takeLast(3).all { f.attached[it] == true } shouldBe true
                f.advance(OriginOddJobAction.LOAD)
                f.job.cleaning!!.collected shouldBe index + 1
                f.displays shouldBe initial
            }
            f.job.stage shouldBe OriginOddJobStage.CLEAN_CART
            f.attached[f.cart] shouldBe false
            f.advance(OriginOddJobAction.ATTACH_CART)
            f.pushTo(f.layout.unload)
            f.cleaning.tick(f.player, f.job) shouldBe OriginOddJobAction.CART_ARRIVE
            f.advance(OriginOddJobAction.CART_ARRIVE)
            f.job.stage shouldBe OriginOddJobStage.CLEAN_UNLOAD
            f.advance(OriginOddJobAction.UNLOAD)
            f.job.stage shouldBe OriginOddJobStage.CLEAN_RETURN
            f.blocks.all { f.attached[it] == false } shouldBe true
            f.blocks.all { f.positions.getValue(it).z > f.layout.unload.z + 1.5 } shouldBe true
            f.attached[f.cart] shouldBe true
            f.displays shouldBe initial
            f.pushTo(OriginScenePoint(f.layout.cart.start.x, f.layout.cart.start.y, f.layout.cart.start.z))
            f.cleaning.tick(f.player, f.job) shouldBe OriginOddJobAction.CART_ARRIVE
            f.advance(OriginOddJobAction.CART_ARRIVE)
            f.job.stage shouldBe OriginOddJobStage.CLEAN_PARK
            f.advance(OriginOddJobAction.PARK_CART)
            f.job.stage shouldBe OriginOddJobStage.RETURN
            f.cleaning.clear(f.player.uniqueId, f.job.id)
            f.displays.none { it.isValid } shouldBe true
        } }
    }

    "cart parking uses the cart body and correct floor rather than the player's point" {
        CleaningFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.sync(); f.advance(OriginOddJobAction.PICKUP); f.advance(OriginOddJobAction.ATTACH_CART)
            f.player.teleport(Location(f.world, f.stall.cart.x + 0.9, f.stall.cart.y, f.stall.cart.z, 90f, 0f))
            f.sync()
            f.cleaning.canPark(f.player.uniqueId, f.job) shouldBe true
            f.player.teleport(Location(f.world, f.stall.cart.x, f.stall.cart.y, f.stall.cart.z, -90f, 0f))
            f.sync()
            f.cleaning.canPark(f.player.uniqueId, f.job) shouldBe false
            f.pushTo(f.stall.cart, height = 4.0)
            f.cleaning.canPark(f.player.uniqueId, f.job) shouldBe false
        } }
    }

    "two cleaners see only their own cart portions and task glow at the shared home" {
        CleaningFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.sync()
            val first = f.displays.toList()
            val secondPlayer = f.createWorker("SecondCleaner")
            secondPlayer.teleport(f.player.location)
            val secondJob = f.job.copy(id = UUID.randomUUID(), yard = OriginYardWork(423, "Заря"))
            f.cleaning.sync(secondPlayer, secondJob)
            val second = f.displays.filter { it !in first }
            first.size shouldBe 10
            second.size shouldBe 10
            first.all { !f.visibleByDefault.getValue(it) && f.shownTo[it] == setOf(f.player.uniqueId) } shouldBe true
            second.all { !f.visibleByDefault.getValue(it) && f.shownTo[it] == setOf(secondPlayer.uniqueId) } shouldBe true
            f.cleaning.clear(f.player.uniqueId, f.job.id)
            first.none { it.isValid } shouldBe true
            second.all { it.isValid } shouldBe true
        } }
    }

    "partial portion allocation and a stale cleanup cannot leak or remove another request" {
        CleaningFixture().use { f -> failOnUnsupportedMockBukkitOperation {
            f.blocksBeforeFailure = 2
            shouldThrow<IllegalStateException> { f.sync() }
            val oldRequest = f.job.id
            f.cleaning.clear(f.player.uniqueId, oldRequest)
            f.displays.none { it.isValid } shouldBe true
            f.blocksBeforeFailure = null
            f.job = f.job.copy(id = UUID.randomUUID())
            f.sync()
            val live = f.displays.filter { it.isValid }
            live.size shouldBe 10
            f.cleaning.clear(f.player.uniqueId, oldRequest)
            live.all { it.isValid } shouldBe true
            f.cleaning.close()
            f.displays.none { it.isValid } shouldBe true
        } }
    }
})

private class CleaningFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val world = paper.server.addSimpleWorld("stable-cleaning")
    val player = createWorker("StableCleaner")
    val stall = OriginStableCleaningStall(OriginScenePoint(4.5, 70.0, 7.5),
        listOf(OriginScenePoint(2.5, 70.02, 8.5), OriginScenePoint(3.5, 70.02, 8.5), OriginScenePoint(4.5, 70.02, 8.5)))
    val layout = OriginStableCleaningLayout(OriginForgeCartLayout(OriginWorkshopPoint(2.5, 70.0, 3.5), 0f,
        "test:cart", org.joml.Vector3f(3f), 0.5625), OriginScenePoint(7.5, 70.0, 7.5), mapOf(422 to stall,
        423 to OriginStableCleaningStall(OriginScenePoint(9.5, 70.0, 7.5),
            stall.piles.map { it.copy(x = it.x + 5.0) })))
    val displays = mutableListOf<PacketDisplay>()
    val blocks = mutableListOf<PacketBlockDisplay>()
    val attached = mutableMapOf<PacketDisplay, Boolean>()
    val positions = mutableMapOf<PacketDisplay, Location>()
    val visibleByDefault = mutableMapOf<PacketDisplay, Boolean>()
    val shownTo = mutableMapOf<PacketDisplay, MutableSet<UUID>>()
    val cart: PacketItemDisplay get() = displays.filterIsInstance<PacketItemDisplay>().single { it.isValid }
    var blocksBeforeFailure: Int? = null
    private val owner = mockk<PaperPacketDisplays>(relaxed = true)
    val cleaning = OriginOddJobStableCleaning(layout, { ItemStack(Material.MINECART) }, { owner })
    private val progress = OriginOddJobsProgress(240_000)
    var job = requireNotNull(progress.begin(player.uniqueId,
        OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_CLEANUP, 1, false), 1_000,
        yardTarget = OriginYardWork(422, "Буран")))

    init {
        world.getChunkAt(0, 0)
        for (x in 0..12) for (z in 0..12) world.getBlockAt(x, 69, z).type = Material.STONE
        player.teleport(Location(world, 2.5, 70.0, 2.5))
        fun track(display: PacketDisplay, at: Location) {
            var valid = true
            every { display.isValid } answers { valid }
            every { display.isVisibleByDefault = any() } answers { visibleByDefault[display] = firstArg() }
            every { display.showTo(any<Player>()) } answers { shownTo.getOrPut(display) { mutableSetOf() }.add(firstArg<Player>().uniqueId); Unit }
            every { display.remove() } answers { valid = false }
            every { display.attachTo(any()) } answers { attached[display] = true }
            every { display.detach() } answers { attached[display] = false }
            every { display.teleport(any()) } answers { positions[display] = firstArg<Location>().clone() }
            positions[display] = at.clone(); attached[display] = false; visibleByDefault[display] = true
            displays += display
        }
        every { owner.spawnItem(any(), any()) } answers {
            mockk<PacketItemDisplay>(relaxed = true).also { track(it, firstArg()) }
        }
        every { owner.spawnBlock(any(), any()) } answers {
            blocksBeforeFailure?.let { remaining ->
                if (remaining == 0) error("simulated portion allocation failure")
                blocksBeforeFailure = remaining - 1
            }
            mockk<PacketBlockDisplay>(relaxed = true).also { display ->
                var data = secondArg<BlockData>()
                every { display.blockData } answers { data }
                every { display.blockData = any() } answers { data = firstArg() }
                track(display, firstArg()); blocks += display
            }
        }
    }
    fun createWorker(name: String) = object : PlayerMock(paper.server, name) {
        override fun getHeight(): Double = 1.8
    }.also(paper.server::addPlayer)
    fun sync() = cleaning.sync(player, job)
    fun advance(action: OriginOddJobAction) {
        if (action == OriginOddJobAction.ATTACH_CART) {
            val collisionAvailable = try {
                requireNotNull(cleaning.station(player.uniqueId, job)).block.collisionShape
                true
            } catch (_: org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) { false }
            org.junit.jupiter.api.Assumptions.assumeTrue(collisionAvailable,
                "MockBukkit BlockMock.getCollisionShape is unimplemented; native cleaning cart movement requires real VoxelShapes. See OriginOddJobCartTest for shared collision coverage.")
        }
        job = (progress.act(player.uniqueId, action, 2_000) as OriginOddJobStep.Changed).job
        sync()
    }
    fun pushTo(point: OriginScenePoint, height: Double = 0.0) {
        player.teleport(Location(world, point.x, point.y + height, point.z - 1.0, 0f, 0f))
        sync()
    }
    override fun close() { cleaning.close(); paper.close() }
}
