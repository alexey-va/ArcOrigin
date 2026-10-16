package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Villager
import org.bukkit.event.block.Action
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import org.bukkit.util.RayTraceResult
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.origin.api.ArcOriginViewerGlow
import ru.arc.origin.menu.OriginMenus
import ru.arc.origin.scene.OriginSceneLease
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OriginOddJobsGameTest : StringSpec({
    lateinit var f: OddJobsFixture
    beforeEach { f = OddJobsFixture() }
    afterEach { f.close() }

    "all NPCs preserve ordinary services and both entity paths claim one sneaking job entry" {
        failOnUnsupportedMockBukkitOperation {
            for (id in listOf(440, 349, 365, 382)) {
                f.player.isSneaking = false
                f.moveNpc(id)
                val citizens = NPCRightClickEvent(f.npc(id), f.player)
                f.game.onNpcClick(citizens)
                citizens.isCancelled shouldBe false
                val entity = PlayerInteractEntityEvent(f.player, f.entity(id), EquipmentSlot.HAND)
                f.game.onEntity(entity)
                entity.isCancelled shouldBe false
            }
            verify(exactly = 0) { f.rewards.status(any()) }
            for (id in listOf(440, 349, 365)) {
                f.player.isSneaking = true
                f.moveNpc(id)
                val offhand = PlayerInteractEntityEvent(f.player, f.entity(id), EquipmentSlot.OFF_HAND)
                f.game.onEntity(offhand)
                offhand.isCancelled shouldBe false
                val at = PlayerInteractAtEntityEvent(f.player, f.entity(id), Vector(), EquipmentSlot.HAND)
                f.game.onEntityAt(at)
                at.isCancelled shouldBe true
                val entity = PlayerInteractEntityEvent(f.player, f.entity(id), EquipmentSlot.HAND)
                f.game.onEntity(entity)
                entity.isCancelled shouldBe true
                val duplicate = NPCRightClickEvent(f.npc(id), f.player)
                f.game.onNpcClick(duplicate)
                duplicate.isCancelled shouldBe true
                duplicate.isDelayedCancellation shouldBe true
                f.scheduler.executeImmediate()
            }
            verify(exactly = 3) { f.rewards.status(f.player.uniqueId) }
            f.moveNpc(440)
            every { f.npc(440).uniqueId } returns UUID.randomUUID()
            f.game.interact(f.player, 440) shouldBe false
            every { f.npc(440).uniqueId } returns f.config.venue(OriginOddJobKind.MOUNTYARD).npcUuid
            f.player.gameMode = GameMode.CREATIVE
            f.game.interact(f.player, 440) shouldBe true
            f.actionBars().last() shouldContain "режиме выживания"
            verify(exactly = 3) { f.rewards.status(f.player.uniqueId) }
        }
    }

    "yard uses the real stations and lease then pays once after hay feed and water" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440)
            f.leases.single().actorIds shouldBe setOf(440)
            f.clickNpc(440)
            f.payments.size shouldBe 0
            f.aim(OriginOddJobStationId.HAY)
            val wrong = f.stations.getValue(OriginOddJobStationId.WATER).block
            f.game.work(f.player, false, wrong) shouldBe false
            f.world.obstruction = RayTraceResult(Vector(1.5, 71.5, 2.0), wrong, BlockFace.NORTH)
            f.game.work(f.player, false, null) shouldBe false
            f.world.obstruction = null
            val offhand = f.blockEvent(OriginOddJobStationId.HAY, EquipmentSlot.OFF_HAND)
            val beforeOffhand = offhand.useInteractedBlock() to offhand.useItemInHand()
            f.game.onBlock(offhand)
            (offhand.useInteractedBlock() to offhand.useItemInHand()) shouldBe beforeOffhand
            f.stroke(OriginOddJobStationId.HAY)
            f.stroke(OriginOddJobStationId.HAY)
            f.stroke(OriginOddJobStationId.HAY)
            verify(exactly = 1) { f.visuals.carry(f.player, any(), OriginJobProp.HAY) }
            f.stroke(OriginOddJobStationId.FEED)
            repeat(3) { f.stroke(OriginOddJobStationId.WATER) }
            f.payments.size shouldBe 0
            f.clickNpc(440)
            f.clickNpc(440)
            val payment = f.payments.single()
            payment.kind shouldBe OriginSideJobKind.MOUNTYARD
            payment.valid() shouldBe true
            payment.callback(SideJobRewardOutcome.PAID)
            payment.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.released.size shouldBe 1
            f.actionBars().count { it.contains("+20 💰") } shouldBe 1
        }
    }

    "forge changes the real workpiece after timed heat and each good hit before quenching" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(349)
            f.leases.single().actorIds shouldBe setOf(349, 362)
            f.stroke(OriginOddJobStationId.BLANK)
            f.stroke(OriginOddJobStationId.FIRE)
            f.stroke(OriginOddJobStationId.FIRE, elapsed = 3_999)
            verify(exactly = 0) { f.visuals.place(any(), any(), any(), any()) }
            f.stroke(OriginOddJobStationId.FIRE, elapsed = 1_000)
            f.stroke(OriginOddJobStationId.ANVIL)
            verify(exactly = 1) { f.visuals.place(f.player, any(), any(), OriginJobProp.FORGE_HEATED) }
            f.stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = 1_100)
            f.stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = 1_100)
            f.stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = 1_100)
            verify(exactly = 1) { f.visuals.place(f.player, any(), any(), OriginJobProp.FORGE_SHAPED_1) }
            verify(exactly = 1) { f.visuals.place(f.player, any(), any(), OriginJobProp.FORGE_SHAPED_2) }
            verify(exactly = 1) { f.visuals.carry(f.player, any(), OriginJobProp.FORGE_HORSESHOE) }
            f.clickNpc(349)
            f.payments.size shouldBe 0
            f.stroke(OriginOddJobStationId.QUENCH)
            verify(exactly = 1) { f.visuals.carry(f.player, any(), OriginJobProp.FORGE_COOLED) }
            f.clickNpc(349)
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
            f.leaseValid = false
            f.payments.single().valid() shouldBe false
            f.game.tick(f.now)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.released.size shouldBe 1
        }
    }

    "auction manifest retries remain native and Greta handoff claims both Bukkit routes once" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(365)
            val item = f.manifestItem()
            f.press("odd_job_item_${OriginAuctionParcel.entries.first { it != item }.name.lowercase()}")
            f.screens.last().body.joinToString { f.plain(it.text) } shouldContain "другой предмет"
            f.press("odd_job_item_${item.name.lowercase()}")
            val wrong = OriginAuctionPacking.entries.first { it != item.packing }
            f.press("odd_job_pack_${wrong.name.lowercase()}")
            f.screens.last().body.joinToString { f.plain(it.text) } shouldContain "не подходит"
            f.press("odd_job_pack_${item.packing.name.lowercase()}")
            verify(exactly = 1) { f.visuals.carry(f.player, any(), OriginJobProp.AUCTION_PACKAGE) }
            f.moveNpc(382)
            f.player.isSneaking = false
            val ordinary = PlayerInteractEntityEvent(f.player, f.entity(382), EquipmentSlot.HAND)
            f.game.onEntity(ordinary)
            ordinary.isCancelled shouldBe false
            f.player.isSneaking = true
            val first = PlayerInteractAtEntityEvent(f.player, f.entity(382), Vector(), EquipmentSlot.HAND)
            f.game.onEntityAt(first)
            first.isCancelled shouldBe true
            val second = PlayerInteractEntityEvent(f.player, f.entity(382), EquipmentSlot.HAND)
            f.game.onEntity(second)
            second.isCancelled shouldBe true
            val third = NPCRightClickEvent(f.npc(382), f.player)
            f.game.onNpcClick(third)
            third.isCancelled shouldBe true
            f.payments.size shouldBe 0
            f.clickNpc(365)
            f.payments.single().kind shouldBe OriginSideJobKind.AUCTION
            f.leases shouldBe emptyList()
        }
    }

    "delayed acceptance respects other work dismissal and changed cash status" {
        failOnUnsupportedMockBukkitOperation {
            f.open(440)
            val delayed = CompletableFuture<SideJobRewardStatus>()
            f.nextStatus = delayed
            f.press("odd_job_start")
            f.otherWork = "waiter-active"
            delayed.complete(f.ready)
            f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.actionBars().last() shouldContain "заказ официанта"
            f.otherWork = null
            f.nextStatus = CompletableFuture.completedFuture(f.ready)
            f.open(440)
            f.nextStatus = CompletableFuture.completedFuture(SideJobRewardStatus(true, 0, 0, false))
            val screenCount = f.screens.size
            f.press("odd_job_start")
            f.scheduler.executeImmediate()
            f.screens.size shouldBe screenCount
            f.actionBars().last() shouldContain "20 поручений"
            f.nextStatus = CompletableFuture()
            f.open(365, flush = false)
            f.dismiss()
            f.nextStatus.complete(f.ready)
            f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leases shouldBe emptyList()
        }
    }

    "failed props expiry quit and stale settlement clear work without paying a new request" {
        failOnUnsupportedMockBukkitOperation {
            every { f.visuals.carry(f.player, any(), OriginJobProp.FORGE_BLANK) } throws IllegalStateException("display failed")
            f.begin(349)
            f.stroke(OriginOddJobStationId.BLANK)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.payments shouldBe emptyList()
            f.released.size shouldBe 1
            f.begin(440)
            f.now += 180_000
            f.game.tick(f.now)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.begin(365)
            f.finishAuction()
            val old = f.payments.single()
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            old.valid() shouldBe false
            f.begin(440)
            old.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.close()
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }

    "a failing glow cleanup still releases the exact occupied yard" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440)
            repeat(3) { f.stroke(OriginOddJobStationId.HAY) }
            f.stroke(OriginOddJobStationId.FEED)
            repeat(3) { f.stroke(OriginOddJobStationId.WATER) }
            every { f.glow.setEntityGlowingFor(any(), any(), false) } throws IllegalStateException("host unavailable")
            f.game.stop(f.player.uniqueId, "cancelled")
            f.released shouldBe f.leases
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.payments shouldBe emptyList()
        }
    }
})

private class OddJobsFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = OddJobWorld().also { it.name = "rc_origin_spawn"; paper.server.addWorld(it); it.getChunkAt(0, 0) }
    val player = paper.addPlayer("OddJobTest")
    val config = oddJobTestConfig()
    val scheduler = TestTaskScheduler()
    val ready = SideJobRewardStatus(true, 20, 0, false)
    var nextStatus = CompletableFuture.completedFuture(ready)
    val rewards = mockk<OriginSideJobRewards>(relaxed = true)
    val visuals = mockk<OriginOddJobVisuals>(relaxed = true)
    val glow = mockk<ArcOriginViewerGlow>(relaxed = true)
    val screens = mutableListOf<PaperDialogScreen>()
    var dismiss: () -> Unit = {}
    val payments = mutableListOf<Payment>()
    val leases = mutableListOf<OriginSceneLease>()
    val released = mutableListOf<OriginSceneLease>()
    var leaseValid = true
    var otherWork: String? = null
    var now = 1_000L
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    val stations: Map<OriginOddJobStationId, OriginOddJobStation>
    val game: OriginOddJobsGame

    init {
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginMenus, OriginServices)
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.getById(any()) } answers { actors[firstArg()] }
        every { registry.getNPC(any<Entity>()) } answers { actors.values.firstOrNull { it.entity === firstArg<Entity>() } }
        every { OriginMenus.beginDialogFlow(any()) } returns Unit
        every { OriginMenus.openDialog(any(), any(), any(), any()) } answers {
            screens += secondArg<PaperDialogScreen>()
            dismiss = arg(3)
        }
        every { OriginServices.viewerGlow } returns glow
        every { rewards.amount } returns 20
        every { rewards.status(any()) } answers { nextStatus }
        every { rewards.pay(any(), any(), any(), any(), any()) } answers { payments += Payment(firstArg(), thirdArg(), arg(3), arg(4)) }
        for (id in listOf(440, 349, 365, 382)) {
            val entity = mockk<Villager>(relaxed = true) {
                every { world } returns this@OddJobsFixture.world
                every { location } answers { Location(this@OddJobsFixture.world, 0.0, if (id == 382) 76.0 else 70.0, if (id == 382) 8.0 else 0.0) }
                every { isValid } returns true
            }
            actors[id] = mockk<NPC> {
                every { this@mockk.id } returns id
                every { uniqueId } returns if (id == 382) config.handoffNpcUuid else config.venues.first { it.npcId == id }.npcUuid
                every { isSpawned } returns true
                every { this@mockk.entity } returns entity
            }
        }
        stations = OriginOddJobStationId.entries.mapIndexed { index, id ->
            val material = when (id) {
                OriginOddJobStationId.HAY -> Material.HAY_BLOCK
                OriginOddJobStationId.FEED -> Material.PACKED_MUD
                OriginOddJobStationId.WATER, OriginOddJobStationId.QUENCH -> Material.WATER_CAULDRON
                OriginOddJobStationId.BLANK -> Material.CHEST
                OriginOddJobStationId.FIRE -> Material.FURNACE
                OriginOddJobStationId.ANVIL -> Material.ANVIL
            }
            val block = world.getBlockAt(index + 1, 70, 3).also { it.type = material }
            id to OriginOddJobStation(Location(world, block.x + 0.5, 71.02, 3.5), block, setOf(material))
        }.toMap()
        game = OriginOddJobsGame(config, world.name, stations, rewards, visuals, LifecycleTaskScope(scheduler),
            acquire = { venue -> OriginSceneLease(UUID.randomUUID(), venue.scene!!, "work", venue.actorIds).also { leases += it } },
            owns = { leaseValid && it !in released }, release = { released += it }, otherWork = { otherWork }, clock = { now })
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity
    fun moveNpc(id: Int) { now += 300; player.teleport(entity(id).location) }
    fun clickNpc(id: Int) { moveNpc(id); player.isSneaking = true; game.interact(player, id) shouldBe true }
    fun open(id: Int, flush: Boolean = true) { clickNpc(id); if (flush) scheduler.executeImmediate() }
    fun press(id: String) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@OddJobsFixture.player }
        screens.last().buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun begin(id: Int) {
        nextStatus = CompletableFuture.completedFuture(ready)
        open(id)
        press("odd_job_start")
        scheduler.executeImmediate()
        game.isWorking(player.uniqueId) shouldBe true
    }
    fun aim(id: OriginOddJobStationId) {
        val point = stations.getValue(id).point
        val at = Location(world, point.x, 70.0, point.z - 2.2)
        at.setDirection(point.clone().add(0.0, 0.2, 0.0).toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
    }
    fun blockEvent(id: OriginOddJobStationId, hand: EquipmentSlot = EquipmentSlot.HAND, left: Boolean = false) =
        PlayerInteractEvent(player, if (left) Action.LEFT_CLICK_BLOCK else Action.RIGHT_CLICK_BLOCK, null,
            stations.getValue(id).block, BlockFace.UP, hand)
    fun stroke(id: OriginOddJobStationId, left: Boolean = false, elapsed: Long = 700) {
        now += elapsed
        aim(id)
        val event = blockEvent(id, left = left)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
        event.useItemInHand() shouldBe Event.Result.DENY
    }
    fun manifestItem(): OriginAuctionParcel {
        val line = plain(screens.last().body.first().text)
        return OriginAuctionParcel.entries.single { config.line("auction.${it.name.lowercase()}") in line }
    }
    fun finishAuction() {
        val item = manifestItem()
        press("odd_job_item_${item.name.lowercase()}")
        press("odd_job_pack_${item.packing.name.lowercase()}")
        clickNpc(382)
        clickNpc(365)
    }
    fun plain(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun actionBars(): List<String> = generateSequence { player.nextActionBar() }.map(::plain).toList()
    override fun close() {
        game.close()
        unmockkObject(OriginMenus, OriginServices)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
    data class Payment(val kind: OriginSideJobKind, val id: UUID, val valid: () -> Boolean, val callback: (SideJobRewardOutcome) -> Unit)
}

/** MockBukkit has no block ray tracer; the fixture supplies the observed occlusion result. */
private class OddJobWorld : WorldMock() {
    var obstruction: RayTraceResult? = null
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? = obstruction
}
