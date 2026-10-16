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
import org.bukkit.entity.Entity
import org.bukkit.entity.Villager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.origin.api.ArcOriginViewerGlow
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OriginWaiterGameTest : StringSpec({
    lateinit var f: WaiterGameFixture
    beforeEach { f = WaiterGameFixture() }
    afterEach { f.close() }

    "a competing carry activity acquired during the status request blocks waiter admission" {
        f.openStaff()
        f.nextStatus = CompletableFuture()
        f.press("waiter_start")
        f.otherWork = true
        f.nextStatus.complete(f.ready)
        f.scheduler.executeImmediate()
        f.reservations shouldBe 0
        f.game.isWorking(f.player.uniqueId) shouldBe false
        f.actionBars().last() shouldContain "Сначала"
    }

    "villager entity and Citizens entry preserve Remi trade and debounce the work click" {
        failOnUnsupportedMockBukkitOperation {
            f.moveTo(405)
            originDiningNpcInteraction(f.player, f.entity(405), f.game::interact) shouldBe false
            val trade = NPCRightClickEvent(f.npc(405), f.player)
            f.listener.onNpcClick(trade)
            trade.isCancelled shouldBe false
            trade.isDelayedCancellation shouldBe false
            verify(exactly = 0) { f.rewards.status(any()) }

            f.player.isSneaking = true
            val work = NPCRightClickEvent(f.npc(405), f.player)
            f.listener.onNpcClick(work)
            originDiningNpcInteraction(f.player, f.entity(405), f.game::interact) shouldBe true
            work.isCancelled shouldBe true
            work.isDelayedCancellation shouldBe true
            verify(exactly = 1) { f.rewards.status(f.player.uniqueId) }
            f.scheduler.executeImmediate()

            f.moveTo(439)
            f.player.isSneaking = false
            originDiningNpcInteraction(f.player, f.entity(439), f.game::interact) shouldBe true
            val duplicate = NPCRightClickEvent(f.npc(439), f.player)
            f.listener.onNpcClick(duplicate)
            duplicate.isCancelled shouldBe true
            verify(exactly = 2) { f.rewards.status(f.player.uniqueId) }
            f.scheduler.executeImmediate()
            for ((staff, guest, control) in listOf(
                Triple(439, 433, "ПКМ"),
                Triple(405, 416, "присядь и ПКМ"),
            )) {
                f.currentVenue = if (staff == 439) "restaurant" else "brewery"
                f.player.isSneaking = staff == 405
                f.clickNpc(staff)
                f.scheduler.executeImmediate()
                f.press("waiter_start")
                f.scheduler.executeImmediate()
                f.clickNpc(guest)
                f.game.tick(f.now)
                f.actionBars().last() shouldBe "Забери блюдо · Бармен · $control"
                f.game.stop(f.player.uniqueId, "cancelled")
            }
        }
    }

    "delivery requires pickup and successful serving before exactly one payout" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.clickNpc(433)
            f.clickNpc(433)
            f.payments.size shouldBe 0
            f.openStaff()
            f.press("waiter_pickup")
            verify(exactly = 0) { f.carry.pickup(any(), any()) }
            f.now += 10_000
            f.press("waiter_pickup")
            f.press("waiter_pickup")
            verify(exactly = 1) { f.carry.pickup(f.player, "egg") }
            f.clickNpc(434)
            f.serves shouldBe 0
            f.clickNpc(433)
            f.clickNpc(433)
            f.serves shouldBe 1
            f.payments.size shouldBe 1
            f.payments.single().valid() shouldBe true
            f.payments.single().callback(SideJobRewardOutcome.PAID)
            f.payments.single().callback(SideJobRewardOutcome.PAID)
            f.released.size shouldBe 1
            f.game.reserves(433) shouldBe false
        }
    }

    "missing carried or served display cannot produce a reward" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.canServe = false
            f.clickNpc(433)
            f.serves shouldBe 1
            f.payments shouldBe emptyList()
            f.released.size shouldBe 1
            f.game.reserves(433) shouldBe false

            f.begin()
            every { f.carry.pickup(any(), any()) } returns false
            f.prepareAndPickup()
            f.game.reserves(433) shouldBe false
            f.payments shouldBe emptyList()
            f.released.size shouldBe 2
        }
    }

    "cancel timeout zone loss and reload release the exact reservation" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.openStaff()
            f.press("waiter_cancel")
            f.game.stop(f.player.uniqueId, "quit")
            f.released.size shouldBe 1
            f.begin()
            f.now += 180_000
            f.game.tick(f.now)
            f.released.size shouldBe 2
            f.begin()
            f.moveTo(433)
            f.currentVenue = null
            f.game.tick(f.now)
            f.released.size shouldBe 3
            f.currentVenue = "restaurant"
            f.begin()
            f.game.close()
            f.scheduler.tick(2)
            f.released.size shouldBe 4
            f.scheduler.timerCount() shouldBe 0
            f.payments shouldBe emptyList()
        }
    }

    "late status cannot reserve after quit or close" {
        failOnUnsupportedMockBukkitOperation {
            val pending = CompletableFuture<SideJobRewardStatus>()
            f.nextStatus = pending
            f.openStaff()
            f.game.stop(f.player.uniqueId, "quit")
            pending.complete(f.ready)
            f.scheduler.executeImmediate()
            f.reservations shouldBe 0

            val afterReload = CompletableFuture<SideJobRewardStatus>()
            f.nextStatus = afterReload
            f.openStaff()
            f.game.close()
            afterReload.complete(f.ready)
            f.scheduler.executeImmediate()
            f.reservations shouldBe 0
        }
    }

    "late start rejection stays visible after the dialog flow closes" {
        failOnUnsupportedMockBukkitOperation {
            for ((status, message) in listOf(
                SideJobRewardStatus(true, 0, 0, false) to "На сегодня",
                SideJobRewardStatus(true, 5, 100_000, false) to "Следующий заказ",
                SideJobRewardStatus(false, 5, 0, false) to "Касса временно недоступна",
                SideJobRewardStatus(true, 5, 0, true) to "Оплату проверяет касса",
            )) {
                f.nextStatus = CompletableFuture.completedFuture(f.ready)
                f.openStaff()
                val pending = CompletableFuture<SideJobRewardStatus>()
                f.nextStatus = pending
                val screenCount = f.screens.size
                f.press("waiter_start")
                pending.complete(status)
                f.scheduler.executeImmediate()
                f.screens.size shouldBe screenCount
                f.actionBars().last() shouldContain message
                f.reservations shouldBe 0
            }
            f.nextStatus = CompletableFuture.completedFuture(f.ready)
            f.openStaff()
            f.nextStatus = CompletableFuture.failedFuture(IllegalStateException("offline"))
            val screenCount = f.screens.size
            f.press("waiter_start")
            f.scheduler.executeImmediate()
            f.screens.size shouldBe screenCount
            f.actionBars().last() shouldContain "Касса временно недоступна"
        }
    }
})

private class WaiterGameFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = paper.addSimpleWorld(OriginDiningLayout.WORLD)
    val player = paper.addPlayer("WaiterTest")
    val scheduler = TestTaskScheduler()
    val ready = SideJobRewardStatus(true, 20, 0, false)
    var nextStatus = CompletableFuture.completedFuture(ready)
    val rewards = mockk<OriginSideJobRewards>(relaxed = true)
    val carry = mockk<OriginWaiterCarry>(relaxed = true)
    val screens = mutableListOf<PaperDialogScreen>()
    val payments = mutableListOf<Payment>()
    val released = mutableListOf<OriginWaiterTarget>()
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    var now = 1_000L
    var currentVenue: String? = "restaurant"
    var canServe = true
    var reservations = 0
    var serves = 0
    var otherWork = false
    private var lastClick = Long.MIN_VALUE
    val game: OriginWaiterGame
    val listener: OriginDiningCitizensListener

    init {
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginMenus, OriginServices)
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.getById(any()) } answers { actors[firstArg()] }
        every { registry.getNPC(any<Entity>()) } answers { actors.values.firstOrNull { it.entity === firstArg<Entity>() } }
        every { OriginMenus.beginDialogFlow(any()) } returns Unit
        every { OriginMenus.openDialog(any(), any(), any(), any()) } answers { screens += secondArg<PaperDialogScreen>() }
        every { OriginServices.viewerGlow } returns mockk<ArcOriginViewerGlow>(relaxed = true)
        every { rewards.amount } returns 20
        every { rewards.status(any()) } answers { nextStatus }
        every { rewards.pay(OriginSideJobKind.WAITER, any(), any(), any(), any()) } answers {
            payments += Payment(thirdArg(), arg(3), arg(4))
        }
        every { carry.pickup(any(), any()) } returns true
        for ((id, x) in mapOf(439 to 0.0, 405 to 20.0, 433 to 8.0, 434 to 9.0, 416 to 28.0)) {
            val entity = mockk<Villager>(relaxed = true) {
                every { world } returns this@WaiterGameFixture.world
                every { location } answers { Location(this@WaiterGameFixture.world, x, 70.0, 0.0) }
                every { isValid } returns true
            }
            actors[id] = mockk<NPC> {
                every { this@mockk.id } returns id
                every { isSpawned } returns true
                every { this@mockk.entity } returns entity
                every { name } returns "&6Бармен"
            }
        }
        game = OriginWaiterGame(waiterTestConfig(),
            reserve = { venue -> reservations++; waiterTarget(venue.id, if (venue.id == "restaurant") 433 else 416) },
            targetValid = { true }, serve = { serves++; canServe }, release = { released += it },
            guestVenue = { if (it == 416) "brewery" else if (it in 433..434) "restaurant" else null },
            playerVenue = { currentVenue }, debounce = { (lastClick == now).also { lastClick = now } },
            carry = carry, rewards = rewards, tasks = LifecycleTaskScope(scheduler), clock = { now }, otherWork = { otherWork })
        listener = OriginDiningCitizensListener(game::interact)
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity
    fun moveTo(id: Int) { now++; player.teleport(entity(id).location) }
    fun clickNpc(id: Int) { moveTo(id); game.interact(player, id) shouldBe true }
    fun openStaff() { clickNpc(439); scheduler.executeImmediate() }
    fun press(id: String) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@WaiterGameFixture.player }
        screens.last().buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun begin() {
        nextStatus = CompletableFuture.completedFuture(ready)
        openStaff()
        press("waiter_start")
        scheduler.executeImmediate()
        game.reserves(433) shouldBe true
    }
    fun prepareAndPickup() {
        clickNpc(433)
        now += 10_000
        openStaff()
        press("waiter_pickup")
    }
    fun actionBars(): List<String> = generateSequence { player.nextActionBar() }
        .map { PlainTextComponentSerializer.plainText().serialize(it) }.toList()
    override fun close() {
        game.close()
        unmockkObject(OriginMenus, OriginServices)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
    data class Payment(val id: UUID, val valid: () -> Boolean, val callback: (SideJobRewardOutcome) -> Unit)
}
