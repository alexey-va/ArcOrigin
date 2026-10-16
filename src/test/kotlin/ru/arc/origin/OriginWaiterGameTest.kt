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
import ru.arc.paper.menu.PaperDialogButton
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

    "a competing carry activity acquired during direct admission blocks the shift without a dialog" {
        f.nextStatus = CompletableFuture()
        f.clickNpc(439)
        f.otherWork = true
        f.nextStatus.complete(f.ready)
        f.scheduler.executeImmediate()
        f.reservations shouldBe 0
        f.game.isWorking(f.player.uniqueId) shouldBe false
        f.actionBars().last() shouldContain "Сначала"
        f.screens shouldBe emptyList()
    }

    "Remi ordinary right click admits work and duplicate Citizens entity entries are debounced" {
        failOnUnsupportedMockBukkitOperation {
            f.currentVenue = "brewery"
            f.moveTo(405)
            val work = NPCRightClickEvent(f.npc(405), f.player)
            f.listener.onNpcClick(work)
            originDiningNpcInteraction(f.player, f.entity(405), f.game::interact) shouldBe true
            work.isCancelled shouldBe true
            work.isDelayedCancellation shouldBe true
            verify(exactly = 1) { f.rewards.status(f.player.uniqueId) }
            f.scheduler.executeImmediate()
            f.screens shouldBe emptyList()
            f.game.reserves(416) shouldBe true
            f.clickNpc(416)
            f.game.reserves(417) shouldBe true
            f.now += 10_000
            f.clickNpc(405)
            f.clickNpc(416)
            f.payments shouldBe emptyList()
            f.clean(416)
            f.payments.size shouldBe 1
            f.served.single().venueId shouldBe "brewery"
            f.screens shouldBe emptyList()
        }
    }

    "assembly and washing clear the previous NPC glow without looking up a negative Citizens id" {
        failOnUnsupportedMockBukkitOperation {
            val previousGuest = f.entity(434)
            val staff = f.entity(439)
            f.begin()
            f.now = f.shiftStarted + 120_000
            f.clickNpc(433)
            f.now = f.shiftStarted + 180_000
            f.game.tick(f.now)
            // The expired first order is replaced with an assembled order during the rush.
            f.reservations shouldBe 3
            f.clickNpc(433)
            f.clickNpc(434)
            f.stationChoices.map { it.id } shouldBe listOf("ingredient:egg", "ingredient:bacon", "ingredient:herbs")
            verify(exactly = 1) { f.viewerGlow.setEntityGlowingFor(previousGuest, f.player, false) }
            f.game.tick(f.now)

            f.now += 10_000
            for (ingredient in listOf("egg", "bacon", "herbs")) {
                f.workstation("ingredient:$ingredient")
                f.game.tick(f.now)
            }
            f.stationChoices.single().id shouldBe "pickup"
            f.workstation("pickup")
            f.clickNpc(433)
            f.now += 18_000
            f.game.tick(f.now)
            f.clickNpc(433)
            f.stationChoices.single().id shouldBe "wash:SCRAPE"
            verify(atLeast = 1) { f.viewerGlow.setEntityGlowingFor(staff, f.player, false) }
            for (action in OriginWaiterWash.entries.filter { it != OriginWaiterWash.SOAK }) {
                f.workstation("wash:${action.name}")
                f.game.tick(f.now)
            }
            f.payments.size shouldBe 1
            f.screens shouldBe emptyList()
            verify(exactly = 0) { f.registry.getById(match { it < 0 }) }
        }
    }

    "two guests are independent one carried dish cannot be duplicated and the shift refills automatically" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.reservations shouldBe 1
            f.clickNpc(433)
            f.game.reserves(434) shouldBe true
            f.clickNpc(434)
            f.now += 10_000
            f.openStaff()
            val pickup = f.pickupButton(433, "Яичница")
            f.click(pickup)
            f.click(pickup)
            verify(exactly = 1) { f.carry.pickup(f.player, "egg") }
            f.clickNpc(435)
            f.served shouldBe emptyList()
            f.clickNpc(433)
            f.clickNpc(433)
            f.served.map { it.guestId } shouldBe listOf(433)
            f.payments shouldBe emptyList()
            f.clean(433)
            f.payments.size shouldBe 1
            f.payments.single().valid() shouldBe true
            val callback = f.payments.single().callback
            callback(SideJobRewardOutcome.PAID)
            callback(SideJobRewardOutcome.PAID)
            f.released.size shouldBe 1
            f.reservations shouldBe 3
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.reserves(434) shouldBe true
            f.openStaff()
            f.screenText() shouldContain "Оплачено заказов: 1 · 20 💰"
            f.screenText() shouldContain "заказ с уточнением"
        }
    }

    "drink and main are two physical handoffs but one paid guest order and payouts are serial" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.clickNpc(433)
            f.clickNpc(434)
            f.now += 10_000
            f.openStaff()
            f.pressPickup(433, "Яичница")
            f.clickNpc(433)
            f.openStaff()
            f.pressPickup(434, "Пиво")
            f.clickNpc(434)
            f.served.map { it.dishId } shouldBe listOf("egg", "herbal_tea")
            f.payments shouldBe emptyList()
            f.clean(433)
            f.payments.size shouldBe 1
            f.now += 10_000
            f.openStaff()
            f.pressPickup(434, "Яичница")
            f.clickNpc(434)
            f.served.map { it.dishId } shouldBe listOf("egg", "herbal_tea", "egg")
            f.clean(434)
            f.payments.size shouldBe 1
            f.payments[0].callback(SideJobRewardOutcome.PAID)
            f.now += SIDE_JOB_PAYOUT_INTERVAL_MILLIS - 1
            f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.now++
            f.game.tick(f.now)
            f.payments.size shouldBe 2
            (f.payments[0].id != f.payments[1].id) shouldBe true
            f.payments[1].callback(SideJobRewardOutcome.PAID)
            f.openStaff()
            f.screenText() shouldContain "Оплачено заказов: 2 · 40 💰"
        }
    }

    "one remaining quota slot never promises a second guest order" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(remaining = 1)
            f.clickNpc(433)
            f.reservations shouldBe 1
            f.game.reserves(434) shouldBe false
            f.now += 10_000
            f.openStaff()
            f.pressPickup(433, "Яичница")
            f.clickNpc(433)
            f.clean(433)
            f.payments.single().callback(SideJobRewardOutcome.PAID)
            f.reservations shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.messages() shouldContain "Оплачено заказов: 1 · 20 💰"
        }
    }

    "known cooldown keeps the completed order and ignores callbacks from the previous attempt" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.clickNpc(433)
            f.clean(433)
            val first = f.payments.single()
            first.callback(SideJobRewardOutcome.COOLDOWN)
            f.game.reserves(433) shouldBe true
            f.released shouldBe emptyList()
            f.now += SIDE_JOB_PAYOUT_INTERVAL_MILLIS - 1
            f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.now++
            f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments[1].id shouldBe first.id
            first.valid() shouldBe false
            first.callback(SideJobRewardOutcome.PAID)
            f.released shouldBe emptyList()
            f.payments[1].callback(SideJobRewardOutcome.PAID)
            f.released.count { it.lease.token == first.id } shouldBe 1
            f.openStaff()
            f.screenText() shouldContain "Оплачено заказов: 1 · 20 💰"
        }
    }

    "accepted payout survives early finish venue departure and the hard deadline with a late receipt" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.clickNpc(433)
            f.clean(433)
            val payment = f.payments.single()
            f.openStaff()
            f.press("waiter_finish")
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.reserves(433) shouldBe true
            f.game.reserves(434) shouldBe false
            f.currentVenue = null
            f.moveTo(435)
            f.now = f.shiftStarted + 800_000
            f.game.tick(f.now)
            payment.valid() shouldBe true
            payment.callback(SideJobRewardOutcome.PAID)
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.game.reserves(433) shouldBe false
            f.messages() shouldContain "Оплачено заказов: 1 · 20 💰"
            f.currentVenue = "restaurant"
        }
    }

    "uncertain or unavailable payments stop the shift without a retry or a fabricated receipt" {
        failOnUnsupportedMockBukkitOperation {
            for (outcome in listOf(SideJobRewardOutcome.UNCONFIRMED, SideJobRewardOutcome.UNAVAILABLE)) {
                f.begin()
                f.prepareAndPickup()
                f.clickNpc(433)
                f.clean(433)
                val count = f.payments.size
                f.payments.last().callback(outcome)
                f.game.isWorking(f.player.uniqueId) shouldBe false
                f.game.reserves(433) shouldBe false
                f.now += 100_000
                f.game.tick(f.now)
                f.payments.size shouldBe count
                f.messages() shouldContain "Оплата 1 заказов не подтверждена"
            }
        }
    }

    "a delayed payment across UTC midnight stays in its original shift receipt" {
        failOnUnsupportedMockBukkitOperation {
            f.now = 86_380_000
            f.begin()
            f.prepareAndPickup()
            f.clickNpc(433)
            f.clean(433)
            val payment = f.payments.single()
            f.now = 86_401_000
            payment.valid() shouldBe true
            payment.callback(SideJobRewardOutcome.PAID)
            payment.callback(SideJobRewardOutcome.PAID)
            f.released.count { it.lease.token == payment.id } shouldBe 1
            f.openStaff()
            f.screenText() shouldContain "Оплачено заказов: 1 · 20 💰"
            f.game.isWorking(f.player.uniqueId) shouldBe true
        }
    }

    "order expiry releases only its ticket while the shift continues until ten minutes plus grace" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.now = f.shiftStarted + 180_000
            f.game.tick(f.now)
            f.released.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.reservations shouldBe 3
            f.now = f.shiftStarted + 599_999
            f.game.tick(f.now)
            val accepted = f.reservations
            f.now = f.shiftStarted + 600_000
            f.game.tick(f.now)
            f.reservations shouldBe accepted
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.now = f.shiftStarted + 720_000
            f.game.tick(f.now)
            f.reservations shouldBe accepted
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.leased shouldBe emptySet()
        }
    }

    "cooled carried prop is removed and kitchen rework preserves the order lease" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            val original = f.reserved.first().lease.token
            f.now += 45_000
            f.game.tick(f.now)
            verify(atLeast = 1) { f.carry.remove(f.player.uniqueId) }
            f.game.reserves(433) shouldBe true
            f.released shouldBe emptyList()
            f.openStaff()
            f.press("waiter_remake_0")
            f.now += 10_000
            f.openStaff()
            f.pressPickup(433, "Яичница")
            f.clickNpc(433)
            f.clean(433)
            f.payments.single().id shouldBe original
        }
    }

    "one guest interaction uses one timestamp when the clock crosses the freshness boundary" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            // Guest acceptance was at shiftStarted + 1; freshUntil is another 55 seconds later.
            // The first clock read during this click is one millisecond before that boundary.
            f.now = f.shiftStarted + 54_999
            f.clockReadAdvance = 1
            f.clickNpc(433)
            f.served.map { it.guestId } shouldBe listOf(433)
            f.payments shouldBe emptyList()
            verify(exactly = 1) { f.carry.remove(f.player.uniqueId) }
        }
    }

    "failed carried or table display removes only the affected ticket and never pays it" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.canServe = false
            f.clickNpc(433)
            f.served.size shouldBe 1
            f.payments shouldBe emptyList()
            f.released.size shouldBe 1
            f.game.reserves(434) shouldBe true
            verify(atLeast = 1) { f.carry.remove(f.player.uniqueId) }
            f.game.stop(f.player.uniqueId, "cancelled")
            f.begin()
            every { f.carry.pickup(any(), any()) } returns false
            f.prepareAndPickup()
            f.payments shouldBe emptyList()
            f.game.reserves(433) shouldBe false
            f.game.reserves(434) shouldBe true
        }
    }

    "late admission and old optional journal actions cannot alter a replacement shift" {
        failOnUnsupportedMockBukkitOperation {
            f.nextStatus = CompletableFuture()
            f.clickNpc(439)
            f.game.stop(f.player.uniqueId, "quit")
            f.nextStatus.complete(f.ready)
            f.scheduler.executeImmediate()
            f.reservations shouldBe 0
            f.begin()
            f.clickNpc(433)
            f.now += 10_000
            f.openStaff()
            val old = f.pickupButton(433, "Яичница")
            f.press("waiter_finish")
            f.begin()
            f.click(old)
            verify(exactly = 0) { f.carry.pickup(any(), any()) }
            f.game.reserves(433) shouldBe true
            f.game.close()
            f.game.close()
            f.scheduler.tick(2)
            f.scheduler.timerCount() shouldBe 0
            f.leased shouldBe emptySet()
        }
    }

    "module close fences an accepted payment before releasing transient guest leases" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.clickNpc(433)
            f.clean(433)
            val payment = f.payments.single()
            f.game.close()
            payment.valid() shouldBe false
            payment.callback(SideJobRewardOutcome.PAID)
            f.game.close()
            f.released.size shouldBe 2
            f.leased shouldBe emptySet()
            verify(exactly = 1) { f.rewards.close() }
        }
    }

    "direct admission rejection stays in the actionbar without opening a menu" {
        failOnUnsupportedMockBukkitOperation {
            for ((status, message) in listOf(
                SideJobRewardStatus(true, 0, 0, false) to "Дневной лимит в 100 поручений",
                SideJobRewardStatus(true, 5, 100_000, false) to "Следующую смену",
                SideJobRewardStatus(false, 5, 0, false) to "Касса временно недоступна",
                SideJobRewardStatus(true, 5, 0, true) to "Оплату проверяет касса",
            )) {
                val pending = CompletableFuture<SideJobRewardStatus>()
                f.nextStatus = pending
                f.clickNpc(439)
                pending.complete(status)
                f.scheduler.executeImmediate()
                f.screens shouldBe emptyList()
                f.actionBars().last() shouldContain message
                f.reservations shouldBe 0
            }
        }
    }

    "wrong dispute guest keeps the water and the peaceful resolution earns no separate payment" {
        f.close()
        f = WaiterGameFixture(disputeEveryOrders = 2)
        f.begin()
        f.clickNpc(433)
        f.clickNpc(433)
        f.clickNpc(434)
        f.clickNpc(439)
        verify(exactly = 1) { f.carry.pickupWater(f.player) }
        f.clickNpc(434)
        verify(exactly = 0) { f.carry.remove(f.player.uniqueId) }
        f.clickNpc(433)
        verify(exactly = 1) { f.carry.remove(f.player.uniqueId) }
        f.clickNpc(434)
        f.payments shouldBe emptyList()
        f.leased shouldBe setOf(433, 434)
        f.screens shouldBe emptyList()
    }

    "failed eating transition and unavailable empty plate cancel only that ticket without pay" {
        f.begin()
        f.prepareAndPickup()
        f.clickNpc(433)
        f.canFinishEating = false
        f.now += 18_000
        f.game.tick(f.now)
        f.payments shouldBe emptyList()
        f.released.any { it.guestId == 433 } shouldBe true
        f.game.reserves(434) shouldBe true
    }

})

private class WaiterGameFixture(private val disputeEveryOrders: Int = 0) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = paper.addSimpleWorld(OriginDiningLayout.WORLD)
    val player = paper.addPlayer("WaiterTest")
    val scheduler = TestTaskScheduler()
    val ready = SideJobRewardStatus(true, 20, 0, false)
    var nextStatus = CompletableFuture.completedFuture(ready)
    val rewards = mockk<OriginSideJobRewards>(relaxed = true)
    val carry = mockk<OriginWaiterCarry>(relaxed = true)
    val viewerGlow = mockk<ArcOriginViewerGlow>(relaxed = true)
    val stations = mockk<OriginWaiterStations>(relaxed = true)
    var stationHit: OriginWaiterStationHit? = null
    var stationRequest: UUID? = null
    var stationChoices = emptyList<OriginWaiterStationChoice>()
    var stationStep = 0
    val screens = mutableListOf<PaperDialogScreen>()
    val payments = mutableListOf<Payment>()
    val reserved = mutableListOf<OriginWaiterTarget>()
    val released = mutableListOf<OriginWaiterTarget>()
    val served = mutableListOf<OriginWaiterTarget>()
    val leased = mutableSetOf<Int>()
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    var now = 1_000L
    var clockReadAdvance = 0L
    var shiftStarted = 0L
    var currentVenue: String? = "restaurant"
    var canServe = true
    var canFinishEating = true
    var reservations = 0
    var otherWork = false
    private var lastClick = Long.MIN_VALUE
    val game: OriginWaiterGame
    val listener: OriginDiningCitizensListener

    init {
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginMenus, OriginServices)
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.getById(any()) } answers {
            val id = firstArg<Int>()
            require(id >= 0) { "invalid id" }
            actors[id]
        }
        every { registry.getNPC(any<Entity>()) } answers { actors.values.firstOrNull { it.entity === firstArg<Entity>() } }
        every { OriginMenus.beginDialogFlow(any()) } returns Unit
        every { OriginMenus.openDialog(any(), any(), any(), any()) } answers { screens += secondArg<PaperDialogScreen>() }
        every { OriginServices.viewerGlow } returns viewerGlow
        every { rewards.amount } returns 20
        every { rewards.payoutIntervalMillis } returns 20_000L
        every { rewards.status(any()) } answers { nextStatus }
        every { rewards.pay(OriginSideJobKind.WAITER, any(), any(), any(), any()) } answers {
            payments += Payment(thirdArg(), arg(3), arg(4))
        }
        every { carry.pickup(any(), any()) } returns true
        every { carry.pickupPlate(any(), any()) } returns true
        every { carry.pickupWater(any()) } returns true
        every { stations.aimed(any(), any()) } answers { stationHit }
        every { stations.show(any(), any(), any(), any(), any(), any()) } answers {
            stationRequest = arg(1)
            stationChoices = arg(2)
            stationStep = arg(4)
        }
        for ((id, x) in mapOf(439 to 0.0, 405 to 20.0, 433 to 8.0, 434 to 9.0, 435 to 10.0, 436 to 11.0, 416 to 28.0, 417 to 29.0)) {
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
        game = OriginWaiterGame(waiterTestConfig().copy(disputeEveryOrders = disputeEveryOrders),
            reserve = { venue ->
                val guest = (if (venue.id == "restaurant") 433..436 else 416..417).firstOrNull { it !in leased }
                guest?.let {
                    reservations++
                    leased += it
                    waiterTarget(venue.id, it).also(reserved::add)
                }
            },
            targetValid = { it.guestId in leased },
            serve = { served += it; canServe },
            finishEating = { canFinishEating }, collectPlate = { true },
            release = { released += it; leased.remove(it.guestId) },
            guestVenue = { if (it in 416..417) "brewery" else if (it in 433..436) "restaurant" else null },
            playerVenue = { currentVenue }, debounce = { (lastClick == now).also { lastClick = now } },
            carry = carry, rewards = rewards, tasks = LifecycleTaskScope(scheduler),
            clock = { now.also { now += clockReadAdvance } }, otherWork = { otherWork }, stations = stations)
        listener = OriginDiningCitizensListener(game::interact)
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity as Villager
    fun moveTo(id: Int) { now++; player.teleport(entity(id).location) }
    fun clickNpc(id: Int) { moveTo(id); game.interact(player, id) shouldBe true }
    fun openStaff() {
        player.isSneaking = game.isWorking(player.uniqueId)
        clickNpc(439)
        player.isSneaking = false
        scheduler.executeImmediate()
    }
    fun click(button: PaperDialogButton) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@WaiterGameFixture.player }
        button.onClick.handle(context)
    }
    fun press(id: String) = click(screens.last().buttons.single { it.id.value == id })
    fun pickupButton(guest: Int, dish: String): PaperDialogButton = screens.last().buttons.single {
        it.id.value.startsWith("waiter_pickup_") && plain(it.label).contains("Гость $guest") && plain(it.label).contains(dish)
    }
    fun pressPickup(guest: Int, dish: String) = click(pickupButton(guest, dish))
    fun begin(remaining: Int = 20) {
        nextStatus = CompletableFuture.completedFuture(ready.copy(remainingOrders = remaining))
        openStaff()
        shiftStarted = now
        game.reserves(433) shouldBe true
    }
    fun prepareAndPickup() {
        clickNpc(433)
        now += 10_000
        openStaff()
        pressPickup(433, "Яичница")
    }
    fun workstation(action: String) {
        now += 700
        stationHit = OriginWaiterStationHit(checkNotNull(stationRequest), action, stationStep)
        game.interactWorkstation(player) shouldBe true
        stationHit = null
    }
    fun clean(guest: Int) {
        now += 18_000
        game.tick(now)
        clickNpc(guest)
        // Direct world input uses the rendered request/index, just as the actual ray hit does.
        for (action in listOf(OriginWaiterWash.SCRAPE, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY)) {
            now += 700
            stationHit = OriginWaiterStationHit(checkNotNull(stationRequest), "wash:${action.name}", stationStep)
            game.interactWorkstation(player) shouldBe true
        }
        stationHit = null
    }
    fun screenText(): String = screens.last().body.joinToString("\n") { plain(it.text) }
    fun actionBars(): List<String> = generateSequence { player.nextActionBar() }.map(::plain).toList()
    fun messages(): String = generateSequence { player.nextComponentMessage() }.joinToString("\n", transform = ::plain)
    private fun plain(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    override fun close() {
        game.close()
        unmockkObject(OriginMenus, OriginServices)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
    data class Payment(val id: UUID, val valid: () -> Boolean, val callback: (SideJobRewardOutcome) -> Unit)
}
