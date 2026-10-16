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
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.util.Vector
import org.bukkit.util.RayTraceResult
import org.mockbukkit.mockbukkit.world.WorldMock
import org.mockbukkit.mockbukkit.inventory.meta.ItemMetaMock
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

    "every employer opens an ordinary right click confirmation and never starts before acceptance" {
        failOnUnsupportedMockBukkitOperation {
            for (id in listOf(440, 349, 365)) {
                f.open(id, sneaking = false)
                f.game.isWorking(f.player.uniqueId) shouldBe false
                f.screens.last().buttons.map { it.id.value } shouldBe listOf("odd_job_start", "odd_job_later")
                f.screens.last().buttons.last().label.let(f::plain) shouldBe "Не сейчас"
            }
            f.press("odd_job_start"); f.scheduler.executeImmediate(); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.currentJob(f.player.uniqueId)!!.kind shouldBe OriginOddJobKind.AUCTION
        }
    }
    "employer identity offhand reach and dismissed asynchronous admission remain fenced" {
        failOnUnsupportedMockBukkitOperation {
            f.moveNpc(440)
            val offhand = PlayerInteractEntityEvent(f.player, f.entity(440), EquipmentSlot.OFF_HAND)
            f.game.onEntity(offhand); offhand.isCancelled shouldBe false
            f.nextStatus = CompletableFuture()
            f.open(440, flush = false, sneaking = false)
            f.dismiss(); f.nextStatus.complete(f.ready); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            every { f.npc(440).uniqueId } returns UUID.randomUUID()
            f.game.interact(f.player, 440) shouldBe false
        }
    }
    "active employers expose only continue and finish and exit cancels unfinished work immediately" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.open(440, sneaking = false)
            f.screens.last().buttons.map { it.id.value } shouldBe listOf("odd_job_continue", "odd_job_finish")
            f.press("odd_job_finish")
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.payments shouldBe emptyList()
            f.released.size shouldBe 1
        }
    }
    "stock is an inventory pickup with one visible service item and duplicate clicks cannot duplicate it" {
        failOnUnsupportedMockBukkitOperation {
            f.player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            f.startForge()
            f.game.currentJob(f.player.uniqueId)!!.variant shouldBe OriginOddJobVariant.FORGE_NEW
            f.stroke(OriginOddJobStationId.BLANK)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_BLANK
            val stock = f.player.openInventory.topInventory
            (stock.holder is OriginOddJobSupplies.Stock) shouldBe true
            f.stockClick(); f.stockClick(); f.scheduler.tick()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_HEAT
            f.player.inventory.contents.filterNotNull().count { it.type == Material.STRUCTURE_VOID } shouldBe 1
            f.player.inventory.getItem(1)!!.itemMeta.itemModel shouldBe org.bukkit.NamespacedKey.minecraft("iron_ingot")
            stock.getItem(4) shouldBe null
            f.player.inventory.getItem(0)!!.amount shouldBe 7
            f.player.closeInventory()
            f.stroke(OriginOddJobStationId.FIRE)
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            verify { f.visuals.hang(f.player, any(), f.stations.getValue(OriginOddJobStationId.FIRE).point, OriginJobProp.FORGE_BLANK) }
        }
    }
    "full storage keeps the blank in stock and a later free slot permits the same order" {
        failOnUnsupportedMockBukkitOperation {
            repeat(36) { f.player.inventory.setItem(it, org.bukkit.inventory.ItemStack(Material.DIAMOND, 64)) }
            f.startForge(); f.stroke(OriginOddJobStationId.BLANK)
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            f.stockClick(); f.scheduler.tick()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_BLANK
            f.player.openInventory.topInventory.getItem(4)!!.amount shouldBe 1
            f.player.inventory.setItem(7, null)
            f.stockClick(); f.scheduler.tick()
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe request
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_HEAT
            f.player.inventory.getItem(6)!!.amount shouldBe 64
        }
    }
    "service item cannot move to crafting drop or offhand and quit removes only the tagged material" {
        failOnUnsupportedMockBukkitOperation {
            f.player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.IRON_INGOT, 13))
            f.startForge(); f.pickupBlank()
            val token = f.player.inventory.getItem(1)!!
            val click = mockk<org.bukkit.event.inventory.InventoryClickEvent>(relaxed = true) {
                every { whoClicked } returns f.player; every { view } returns f.player.openInventory
                every { currentItem } returns token; every { hotbarButton } returns -1
            }
            f.game.onInventoryClick(click); verify { click.isCancelled = true }
            val drop = mockk<org.bukkit.event.player.PlayerDropItemEvent>(relaxed = true) {
                every { itemDrop.itemStack } returns token
            }
            f.game.onDrop(drop); verify { drop.isCancelled = true }
            val swap = mockk<org.bukkit.event.player.PlayerSwapHandItemsEvent>(relaxed = true) {
                every { mainHandItem } returns token
            }
            f.game.onSwap(swap); verify { swap.isCancelled = true }
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.player.inventory.getItem(0)!!.amount shouldBe 13
            f.payments shouldBe emptyList()
        }
    }
    "raw new forging remains a billet at placement and becomes a horseshoe only after hammering" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.pickupBlank()
            f.stroke(OriginOddJobStationId.FIRE)
            f.stroke(OriginOddJobStationId.FIRE, elapsed = 60_000)
            f.stroke(OriginOddJobStationId.ANVIL)
            verify { f.visuals.place(f.player, any(), f.stations.getValue(OriginOddJobStationId.ANVIL).point, OriginJobProp.FORGE_HEATED) }
            val initial = f.game.currentJob(f.player.uniqueId)!!
            repeat(initial.forge!!.remainingStrikes) { index ->
                f.stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = ODD_JOB_HAMMER_READY_MILLIS)
                val current = f.game.currentJob(f.player.uniqueId)!!
                if (index + 1 < initial.forge.remainingStrikes) (originOddJobNewForgeShape(current) == OriginJobProp.FORGE_HORSESHOE) shouldBe false
            }
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.FORGE_QUENCH
            f.stroke(OriginOddJobStationId.QUENCH); f.clickNpc(349, sneaking = false)
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
        }
    }
    "confirmed payment schedules the next task automatically without reopening an employer" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val first = f.payments.single()
            first.callback(SideJobRewardOutcome.PAID); first.callback(SideJobRewardOutcome.PAID)
            val screens = f.screens.size
            f.game.tick(f.now); f.scheduler.executeImmediate()
            (f.game.currentJob(f.player.uniqueId)!!.variant == OriginOddJobVariant.FORGE_NEW) shouldBe false
            f.screens.size shouldBe screens
            f.actionBars().count { "+20" in it } shouldBe 1
        }
    }
    "accepted payment survives quit and known cooldown retries exactly the same request" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val first = f.payments.single(); first.callback(SideJobRewardOutcome.COOLDOWN)
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            first.valid() shouldBe true
            f.now += 20_000; f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments.last().id shouldBe first.id
            f.payments.last().callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "a badly balanced caravan is recoverable by left clicking its last load without reopening a menu" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_CARAVAN)
            val screens = f.screens.size
            repeat(3) {
                f.stroke(OriginOddJobStationId.HAY)
                f.stroke(OriginOddJobStationId.FEED, zone = 0)
            }
            f.stroke(OriginOddJobStationId.HAY)
            f.stroke(OriginOddJobStationId.FEED, zone = 2)
            val rejected = f.game.currentJob(f.player.uniqueId)!!
            rejected.stage shouldBe OriginOddJobStage.YARD_LOAD
            rejected.yard!!.loads shouldBe listOf(0, 0, 0)
            originOddJobGuide(f.config, rejected, f.now) shouldContain "груз 6:0"
            f.stroke(OriginOddJobStationId.FEED, left = true)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.loads shouldBe listOf(0, 0, 1)
            f.stroke(OriginOddJobStationId.FEED, zone = 2)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.RETURN
            f.screens.size shouldBe screens
        }
    }
    "lease loss and reload remove working items and never pay unfinished work" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.pickupBlank(); f.leaseValid = false
            f.game.tick(++f.now)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments shouldBe emptyList()
            f.game.close(); f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "a disappeared carried token is cancelled and cannot be synthesized by rendering or clicking the next station" {
        failOnUnsupportedMockBukkitOperation {
            f.player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 5))
            f.startForge(); f.pickupBlank()
            val old = f.game.currentJob(f.player.uniqueId)!!.id
            f.player.inventory.setItem(1, null)
            f.game.tick(++f.now)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.aim(OriginOddJobStationId.FIRE)
            f.game.work(f.player, false, f.stations.getValue(OriginOddJobStationId.FIRE).block) shouldBe false
            f.payments.none { it.id == old } shouldBe true
            f.player.inventory.getItem(0)!!.amount shouldBe 5
        }
    }
    "visible chain and its authored guard rail are reachable but a wall is not a work proxy" {
        failOnUnsupportedMockBukkitOperation {
            val chain = f.stations.getValue(OriginOddJobStationId.FIRE)
            val rail = f.world.getBlockAt(chain.block.x, 70, 2).also { it.type = Material.IRON_BARS }
            val target = chain.copy(accessBlocks = listOf(rail))
            f.aim(OriginOddJobStationId.FIRE)
            f.world.obstruction = RayTraceResult(rail.location.toVector(), rail, BlockFace.NORTH)
            originOddJobAimed(f.player, target, 4.5, rail) shouldBe true
            rail.type = Material.STONE
            originOddJobAimed(f.player, target, 4.5, rail) shouldBe false
        }
    }
})

private class OddJobsFixture(deferStations: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val itemFactory = paper.server.itemFactory
    val world = OddJobWorld().also { it.name = "rc_origin_spawn"; paper.server.addWorld(it); it.getChunkAt(0, 0) }
    val player = paper.addPlayer("OddJobTest")
    val config = oddJobTestConfig()
    val scheduler = TestTaskScheduler()
    val ready = SideJobRewardStatus(true, 20, 0, false)
    var nextStatus = CompletableFuture.completedFuture(ready)
    val rewards = mockk<OriginSideJobRewards>(relaxed = true)
    val visuals = mockk<OriginOddJobVisuals>(relaxed = true)
    var target: Location? = null
    var forgeWork: OriginForgeWork? = null
    val glow = mockk<ArcOriginViewerGlow>(relaxed = true)
    val screens = mutableListOf<PaperDialogScreen>()
    var dismiss: () -> Unit = {}
    val payments = mutableListOf<Payment>()
    val leases = mutableListOf<OriginSceneLease>()
    val released = mutableListOf<OriginSceneLease>()
    var leaseValid = true
    var otherWork: String? = null
    var desiredVariant: OriginOddJobVariant? = null
    var now = 1_000L
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    val stations: Map<OriginOddJobStationId, OriginOddJobStation>
    var resolvedStations: Map<OriginOddJobStationId, OriginOddJobStation> = emptyMap()
    var resolveAttempts = 0
    val game: OriginOddJobsGame

    init {
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginMenus, OriginServices)
        // MockBukkit 4.116.3's ItemMetaMock copy constructor omits itemModel.
        // Keep Paper's copy contract for this service material, including inventory round trips.
        mockkObject(itemFactory)
        every { itemFactory.asMetaFor(any(), Material.STRUCTURE_VOID) } answers { ModelPreservingItemMeta(firstArg()) }
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
        every { rewards.payoutIntervalMillis } returns 20_000L
        every { rewards.status(any()) } answers { nextStatus }
        every { rewards.pay(any(), any(), any(), any(), any()) } answers { payments += Payment(firstArg(), thirdArg(), arg(3), arg(4)) }
        every { visuals.target(any(), any(), any(), any()) } answers { target = arg<Location?>(2)?.clone() }
        every { visuals.forgeProfile(any(), any(), any(), any(), any()) } answers { forgeWork = arg(3) }
        every { visuals.forgeCarry(any(), any(), any(), any()) } answers { forgeWork = thirdArg() }
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
        game = OriginOddJobsGame(config, world.name, if (deferStations) emptyMap() else stations, rewards, visuals, LifecycleTaskScope(scheduler),
            acquire = { venue -> OriginSceneLease(UUID.randomUUID(), venue.scene!!, "work", venue.actorIds).also { leases += it } },
            owns = { leaseValid && it !in released }, release = { released += it }, otherWork = { otherWork }, clock = { now },
            resolveStations = { resolveAttempts++; resolvedStations }, dispatch = { kind ->
                (0..100).asSequence().map { OriginOddJobDispatch(kind, kotlin.random.Random(it)) }
                    .first { desiredVariant == null || it.offers(OriginWorkShiftPhase.OPENING).first().variant == desiredVariant }
            })
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity as Villager
    fun moveNpc(id: Int) { now += 300; player.teleport(entity(id).location) }
    fun clickNpc(id: Int, sneaking: Boolean = true) { moveNpc(id); player.isSneaking = sneaking; game.interact(player, id) shouldBe true }
    fun open(id: Int, flush: Boolean = true, sneaking: Boolean = true) { clickNpc(id, sneaking); if (flush) scheduler.executeImmediate() }
    fun currentStation() = stations.entries.single { it.value.block.location.add(0.5, 0.5, 0.5) == target }.key
    fun startForge() { startShift(349) }
    fun stockClick() {
        val click = mockk<org.bukkit.event.inventory.InventoryClickEvent>(relaxed = true) {
            every { whoClicked } returns player
            every { view } returns player.openInventory
            every { rawSlot } returns 4
            every { currentItem } returns player.openInventory.topInventory.getItem(4)
        }
        game.onInventoryClick(click)
        verify { click.isCancelled = true }
    }
    fun pickupBlank() {
        stroke(OriginOddJobStationId.BLANK); stockClick(); scheduler.tick(); player.closeInventory()
    }
    fun finishForge() {
        pickupBlank()
        stroke(OriginOddJobStationId.FIRE)
        stroke(OriginOddJobStationId.FIRE, elapsed = ODD_JOB_HEAT_READY_MILLIS + 1_500)
        stroke(OriginOddJobStationId.ANVIL)
        repeat(game.currentJob(player.uniqueId)!!.forge!!.remainingStrikes) {
            stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = ODD_JOB_HAMMER_READY_MILLIS)
        }
        stroke(OriginOddJobStationId.QUENCH); clickNpc(349, sneaking = false)
    }
    fun press(id: String) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@OddJobsFixture.player }
        screens.last().buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun startShift(id: Int) {
        nextStatus = CompletableFuture.completedFuture(ready)
        open(id, sneaking = false); press("odd_job_start"); scheduler.executeImmediate(); scheduler.executeImmediate()
        game.isWorking(player.uniqueId) shouldBe true
    }
    fun begin(id: Int, variant: OriginOddJobVariant) { desiredVariant = variant; startShift(id) }
    fun aim(id: OriginOddJobStationId, zone: Int = 1) {
        val point = stations.getValue(id).point
        val at = Location(world, point.x, 70.0, point.z - 2.2)
        at.setDirection(point.clone().add((zone - 1) * 0.3, 0.2, 0.0).toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
    }
    fun blockEvent(id: OriginOddJobStationId, hand: EquipmentSlot = EquipmentSlot.HAND, left: Boolean = false) =
        PlayerInteractEvent(player, if (left) Action.LEFT_CLICK_BLOCK else Action.RIGHT_CLICK_BLOCK, null,
            stations.getValue(id).block, BlockFace.UP, hand)
    fun stroke(id: OriginOddJobStationId, left: Boolean = false, elapsed: Long = 700, zone: Int = 1) {
        now += elapsed
        aim(id, zone)
        val event = blockEvent(id, left = left)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
        event.useItemInHand() shouldBe Event.Result.DENY
    }
    fun plain(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun actionBars(): List<String> = generateSequence { player.nextActionBar() }.map(::plain).toList()
    override fun close() {
        game.close()
        unmockkObject(OriginMenus, OriginServices)
        unmockkObject(itemFactory)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
    data class Payment(val kind: OriginSideJobKind, val id: UUID, val valid: () -> Boolean, val callback: (SideJobRewardOutcome) -> Unit)
}

private class ModelPreservingItemMeta(source: ItemMeta) : ItemMetaMock(source) {
    init { itemModel = source.itemModel }
    override fun clone(): ItemMetaMock = ModelPreservingItemMeta(this)
}

/** MockBukkit has no block ray tracer; the fixture supplies the observed occlusion result. */
private class OddJobWorld : WorldMock() {
    var obstruction: RayTraceResult? = null
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? = obstruction
}
