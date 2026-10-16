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
import net.citizensnpcs.api.event.NPCLeftClickEvent
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

    "ordinary services actor identity and offhand paths retain their original boundaries" {
        failOnUnsupportedMockBukkitOperation {
            for (id in listOf(440, 365, 382)) {
                f.player.isSneaking = false; f.moveNpc(id)
                val event = NPCRightClickEvent(f.npc(id), f.player)
                f.game.onNpcClick(event); event.isCancelled shouldBe false
            }
            verify(exactly = 0) { f.rewards.status(any()) }
            f.player.isSneaking = true; f.moveNpc(440)
            val offhand = PlayerInteractEntityEvent(f.player, f.entity(440), EquipmentSlot.OFF_HAND)
            f.game.onEntity(offhand); offhand.isCancelled shouldBe false
            val at = PlayerInteractAtEntityEvent(f.player, f.entity(440), Vector(), EquipmentSlot.HAND)
            f.game.onEntityAt(at); at.isCancelled shouldBe true
            val duplicate = NPCRightClickEvent(f.npc(440), f.player)
            f.game.onNpcClick(duplicate); duplicate.isCancelled shouldBe true
            f.scheduler.executeImmediate()
            verify(exactly = 1) { f.rewards.status(f.player.uniqueId) }
            every { f.npc(440).uniqueId } returns UUID.randomUUID()
            f.game.interact(f.player, 440) shouldBe false
        }
    }
    "completed order releases worksite while shift survives and accepts a different next family" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_CARAVAN)
            val lease = f.leases.last()
            f.finishCaravan()
            f.released.contains(lease) shouldBe true
            val payment = f.payments.single(); payment.valid() shouldBe true
            payment.callback(SideJobRewardOutcome.PAID); payment.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.open(440)
            val body = f.screens.last().body.joinToString { f.plain(it.text) }
            body shouldContain "Принято 1"
            body shouldContain "20 💰"
            f.screens.last().buttons.filter { it.id.value.startsWith("odd_job_offer_") }.none {
                f.plain(it.label) == f.config.line("variants.yard-caravan.action")
            } shouldBe true
            f.press("odd_job_finish")
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.actionBars().count { it.contains("+20 💰") } shouldBe 1
        }
    }
    "known cooldown preserves exact completed order through deadline and lease loss then settles once" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_CARAVAN); f.finishCaravan()
            val first = f.payments.single()
            first.callback(SideJobRewardOutcome.COOLDOWN)
            f.leaseValid = false
            f.now += 19_999; f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.now += 1; f.game.tick(f.now)
            f.payments.size shouldBe 2
            val retry = f.payments.last(); retry.id shouldBe first.id
            f.now += 800_000; f.game.tick(f.now)
            retry.valid() shouldBe true
            retry.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.payments.size shouldBe 2
        }
    }
    "accepted payment survives quit and stale duplicate callback cannot inflate a new shift" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(365, OriginOddJobVariant.AUCTION_AMEND); f.finishAuction()
            val payment = f.payments.single()
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            payment.valid() shouldBe true
            payment.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.startShift(365)
            payment.callback(SideJobRewardOutcome.PAID)
            f.open(365)
            f.screens.last().body.joinToString { f.plain(it.text) } shouldContain "Принято 0"
        }
    }
    "unknown payment is never retried and incomplete cancellation never calls money" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(365, OriginOddJobVariant.AUCTION_GROUP); f.finishAuction()
            f.payments.single().callback(SideJobRewardOutcome.UNCONFIRMED)
            f.now += 800_000; f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.open(440); f.press("odd_job_cancel")
            f.payments.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe true
        }
    }
    "master overview remains reachable far from current station and finish after current order can be cancelled" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val master = f.entity(440)
            every { master.location } answers { Location(f.world, -10.0, 70.0, 0.0) }
            f.open(440)
            val screen = f.screens.last()
            screen.buttons.none { it.id.value.startsWith("odd_job_choice_") } shouldBe true
            screen.buttons.any { it.id.value == "odd_job_finish" } shouldBe true
            f.press("odd_job_finish")
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.open(440); f.press("odd_job_cancel")
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.payments shouldBe emptyList()
        }
    }
    "forge starts with ordinary clicks and keeps work hot at the real station until it is handed over once" {
        failOnUnsupportedMockBukkitOperation {
            var carried: Pair<OriginForgeWork, Boolean>? = null
            var placed: Pair<OriginForgeWork, Boolean>? = null
            var placedAt: Location? = null
            every { f.visuals.forgeCarry(any(), any(), any(), any()) } answers {
                carried = thirdArg<OriginForgeWork>() to arg(3)
            }
            every { f.visuals.forgeProfile(any(), any(), any(), any(), any()) } answers {
                placedAt = thirdArg<Location>()
                placed = arg<OriginForgeWork>(3) to arg(4)
            }
            f.startForge()
            val firstStation = f.currentStation()
            (firstStation in setOf(OriginOddJobStationId.BLANK, OriginOddJobStationId.ANVIL)) shouldBe true
            val duplicate = PlayerInteractAtEntityEvent(f.player, f.entity(349), Vector(), EquipmentSlot.HAND)
            f.game.onEntityAt(duplicate); duplicate.isCancelled shouldBe true
            f.leases.size shouldBe 1
            f.screens shouldBe emptyList()
            f.stroke(firstStation)
            f.currentStation() shouldBe OriginOddJobStationId.FIRE
            f.stroke(OriginOddJobStationId.FIRE)
            f.now += 60_000; f.game.tick(f.now)
            f.currentStation() shouldBe OriginOddJobStationId.FIRE
            if (placed != null) placedAt shouldBe f.stations.getValue(OriginOddJobStationId.FIRE).point
            else verify { f.visuals.place(f.player, any(), f.stations.getValue(OriginOddJobStationId.FIRE).point, OriginJobProp.FORGE_HEATED) }
            f.stroke(OriginOddJobStationId.FIRE)
            f.stroke(OriginOddJobStationId.ANVIL)
            val original = requireNotNull(placed).first
            placedAt shouldBe f.stations.getValue(OriginOddJobStationId.ANVIL).point
            f.stroke(OriginOddJobStationId.ANVIL) // Right click no longer rotates or hits the work.
            placed shouldBe (original to true)
            f.now += 30_000; f.game.tick(f.now)
            placed shouldBe (original to true)
            repeat(original.remainingStrikes) {
                f.stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = ODD_JOB_HAMMER_READY_MILLIS)
            }
            f.currentStation() shouldBe OriginOddJobStationId.QUENCH
            carried shouldBe (original.copy(shape = original.target) to true)
            f.stroke(OriginOddJobStationId.QUENCH)
            carried shouldBe (original.copy(shape = original.target) to false)
            val edgar = f.entity(349)
            verify { f.glow.setEntityGlowingFor(edgar, f.player, true) }
            f.clickNpc(349, sneaking = false)
            val payment = f.payments.single()
            payment.kind shouldBe OriginSideJobKind.FORGE
            f.clickNpc(349, sneaking = false)
            f.payments.size shouldBe 1
            payment.callback(SideJobRewardOutcome.PAID); payment.callback(SideJobRewardOutcome.PAID)
            f.actionBars().count { it.contains("+20 💰") } shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.now += 2_000; f.game.tick(f.now)
            f.leftClickForge()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.released shouldBe f.leases
            f.screens shouldBe emptyList()
        }
    }
    "forge finish request preserves the current order and closes after its confirmed payment" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge()
            f.leftClickForge()
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.finishForge()
            f.payments.single().callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.screens shouldBe emptyList()
        }
    }
    "forge admission stays asynchronous and a late status cannot start after departure" {
        failOnUnsupportedMockBukkitOperation {
            val delayed = CompletableFuture<SideJobRewardStatus>(); f.nextStatus = delayed
            f.open(349, flush = false, sneaking = false)
            f.screens shouldBe emptyList()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leases shouldBe emptyList()
            f.game.stop(f.player.uniqueId, "quit")
            delayed.complete(f.ready); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leases shouldBe emptyList()
        }
    }
    "forge idle highlight is removed on reload without an active order" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            f.payments.single().callback(SideJobRewardOutcome.PAID)
            f.now += 2_000; f.game.tick(f.now)
            val edgar = f.entity(349)
            verify(exactly = 2) { f.glow.setEntityGlowingFor(edgar, f.player, true) }
            f.game.close()
            verify(exactly = 2) { f.glow.setEntityGlowingFor(edgar, f.player, false) }
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "station actions cannot cross walls or wrong blocks and cleanup still releases after glow failure" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_RATIONS); f.aim(OriginOddJobStationId.HAY)
            val wrong = f.stations.getValue(OriginOddJobStationId.WATER).block
            f.game.work(f.player, false, wrong) shouldBe false
            f.world.obstruction = RayTraceResult(Vector(1.5, 71.5, 2.0), wrong, BlockFace.NORTH)
            f.game.work(f.player, false, null) shouldBe false
            f.world.obstruction = null
            every { f.glow.setEntityGlowingFor(any(), any(), false) } throws IllegalStateException("host unavailable")
            f.game.stop(f.player.uniqueId, "cancelled")
            f.released shouldBe f.leases
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "unloaded worksite resolves only on admission and active station loss releases without retargeting" {
        f.close(); f = OddJobsFixture(deferStations = true)
        failOnUnsupportedMockBukkitOperation {
            f.open(440); f.resolveAttempts shouldBe 1
            f.game.tick(f.now); f.resolveAttempts shouldBe 1
            verify(exactly = 0) { f.rewards.status(any()) }
            f.resolvedStations = f.stations
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.resolveAttempts shouldBe 2
            f.stations.getValue(OriginOddJobStationId.HAY).block.type = Material.AIR
            f.game.tick(f.now)
            f.released shouldBe f.leases
            f.resolveAttempts shouldBe 2
        }
    }
    "late admission checks other jobs and dismissal before starting a shift" {
        failOnUnsupportedMockBukkitOperation {
            f.open(440)
            val delayed = CompletableFuture<SideJobRewardStatus>(); f.nextStatus = delayed
            f.press("odd_job_start"); f.otherWork = "waiter-active"
            delayed.complete(f.ready); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.otherWork = null; f.nextStatus = CompletableFuture()
            f.open(365, flush = false); f.dismiss()
            f.nextStatus.complete(f.ready); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
})

private class OddJobsFixture(deferStations: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
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
            resolveStations = { resolveAttempts++; resolvedStations })
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity as Villager
    fun moveNpc(id: Int) { now += 300; player.teleport(entity(id).location) }
    fun clickNpc(id: Int, sneaking: Boolean = true) { moveNpc(id); player.isSneaking = sneaking; game.interact(player, id) shouldBe true }
    fun open(id: Int, flush: Boolean = true, sneaking: Boolean = true) { clickNpc(id, sneaking); if (flush) scheduler.executeImmediate() }
    fun currentStation() = stations.entries.single { it.value.point == target }.key
    fun startForge() {
        moveNpc(349); player.isSneaking = false
        val event = NPCRightClickEvent(npc(349), player)
        game.onNpcClick(event); event.isCancelled shouldBe true
        scheduler.executeImmediate(); scheduler.executeImmediate()
        game.isWorking(player.uniqueId) shouldBe true
    }
    fun leftClickForge() {
        moveNpc(349); player.isSneaking = false
        val event = NPCLeftClickEvent(npc(349), player)
        game.onNpcLeftClick(event); event.isCancelled shouldBe true
    }
    fun finishForge() {
        stroke(currentStation())
        stroke(OriginOddJobStationId.FIRE)
        stroke(OriginOddJobStationId.FIRE, elapsed = ODD_JOB_HEAT_READY_MILLIS + 1_500)
        stroke(OriginOddJobStationId.ANVIL)
        repeat(requireNotNull(forgeWork).remainingStrikes) {
            stroke(OriginOddJobStationId.ANVIL, left = true, elapsed = ODD_JOB_HAMMER_READY_MILLIS)
        }
        currentStation() shouldBe OriginOddJobStationId.QUENCH
        stroke(OriginOddJobStationId.QUENCH)
        clickNpc(349, sneaking = false)
    }
    fun press(id: String) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@OddJobsFixture.player }
        screens.last().buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun startShift(id: Int) {
        nextStatus = CompletableFuture.completedFuture(ready)
        open(id); press("odd_job_start"); scheduler.executeImmediate()
        game.isWorking(player.uniqueId) shouldBe true
    }
    fun begin(id: Int, variant: OriginOddJobVariant) {
        startShift(id)
        repeat(3) {
            val options = screens.last().buttons.filter { it.id.value.startsWith("odd_job_offer_") }
            val desired = options.firstOrNull { plain(it.label) == config.line("variants.${variant.key()}.action") }
            press((desired ?: options.first()).id.value); scheduler.executeImmediate()
            if (desired != null) return
            open(id); press("odd_job_cancel"); open(id)
        }
        error("dispatch bag did not expose $variant")
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
    fun finishCaravan() {
        for (side in listOf(0, 0, 1, 1)) {
            stroke(OriginOddJobStationId.HAY)
            val fresh = screens.last().buttons.single { plain(it.label) == config.line("mountyard.bundle-0") }
            press(fresh.id.value)
            stroke(OriginOddJobStationId.FEED)
            press("odd_job_choice_$side")
        }
        clickNpc(440)
    }
    fun finishAuction() {
        repeat(30) {
            val body = screens.last().body.map { plain(it.text) }
            when {
                config.line("guide.auction-item") in body -> {
                    val manifest = body.first { it.startsWith("Заявка:") }
                    val amended = config.line("variants.auction-amend.title") in body
                    val serial = Regex("№([0-9]+)").find(manifest)!!.groupValues[1].toInt() + if (amended) 1 else 0
                    val condition = if (config.line("auction.restored") in manifest) config.line("auction.restored") else config.line("auction.intact")
                    press(screens.last().buttons.single { plain(it.label).startsWith("№$serial ·") && condition in plain(it.label) }.id.value)
                }
                config.line("guide.auction-inspect") in body -> {
                    val manifest = body.first { it.startsWith("Заявка:") }
                    val record = body.first { it.startsWith("На самом") }
                    val expected = Regex("№([0-9]+)").find(manifest)!!.groupValues[1]
                    val actual = Regex("№([0-9]+)").find(record)!!.groupValues[1]
                    press("odd_job_choice_${if (expected == actual) 0 else 1}")
                }
                config.line("guide.auction-verify") in body -> { clickNpc(382); clickNpc(365) }
                config.line("guide.auction-pack") in body -> {
                    val manifest = body.first { it.startsWith("Заявка:") }
                    val item = OriginAuctionParcel.entries.single { config.line("auction.${it.name.lowercase()}") in manifest }
                    press("odd_job_choice_${item.packing.ordinal}")
                }
                config.line("guide.auction-load") in body -> {
                    val choices = screens.last().buttons.filter { it.id.value.startsWith("odd_job_choice_") }
                    press(choices.maxBy { plain(it.label).substringAfterLast(" ").toInt() }.id.value)
                }
                config.line("guide.auction-deliver") in body -> clickNpc(382)
                config.line("guide.auction-receipt") in body -> {
                    val serial = body.first { it.startsWith("Контрольная сумма") }.substringAfter(": ").substringBefore(".")
                    val receipt = screens.last().buttons.single { plain(it.label).endsWith(" $serial") }
                    receipt.closeDialogBeforeAction shouldBe true
                    press(receipt.id.value)
                    actionBars().last() shouldContain config.line("guide.return", "name" to config.venue(OriginOddJobKind.AUCTION).name)
                    clickNpc(365)
                    return
                }
                body.any { it.startsWith("Сдай готовую работу") } -> { clickNpc(365); return }
                else -> error("unknown auction stage: $body")
            }
        }
        error("auction did not complete")
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
