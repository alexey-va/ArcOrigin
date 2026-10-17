package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
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
import org.bukkit.entity.ItemDisplay
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

    "ordinary NPC click only explains work and explicit acceptance rechecks competing activity" {
        f.openStaff()
        f.game.isWorking(f.player.uniqueId) shouldBe false
        f.reservations shouldBe 0
        f.screens.last().buttons.map { it.id.value } shouldBe listOf("waiter_start", "waiter_cancel")
        f.nextStatus = CompletableFuture()
        f.press("waiter_start")
        f.otherWork = true
        f.nextStatus.complete(f.ready)
        f.scheduler.executeImmediate()
        f.reservations shouldBe 0
        f.game.isWorking(f.player.uniqueId) shouldBe false
        f.actionBars().last() shouldContain "Сначала"
    }

    "Remi accepts only the dialog button while world steps never reopen a dialog" {
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
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.press("waiter_start")
            f.scheduler.executeImmediate()
            f.screens.clear()
            f.game.reserves(416) shouldBe true
            f.clickNpc(416)
            f.game.reserves(417) shouldBe true
            f.now += 10_000
            f.game.tick(f.now)
            f.workstation("pickup")
            f.clickNpc(416)
            f.payments shouldBe emptyList()
            f.clean(416)
            f.payments.size shouldBe 1
            f.served.single().venueId shouldBe "brewery"
            f.screens shouldBe emptyList()
        }
    }

    "a shift waits for eating guests and the first empty table is picked up on the next reservation tick" {
        f.tablesAvailable = false
        f.nextStatus = CompletableFuture.completedFuture(f.ready)
        f.openStaff()
        f.press("waiter_start")
        f.scheduler.executeImmediate()
        f.game.isWorking(f.player.uniqueId) shouldBe true
        f.game.awaitsTable("restaurant", f.now) shouldBe true
        f.game.awaitsTable("brewery", f.now) shouldBe false
        f.reservations shouldBe 0
        f.tablesAvailable = true
        f.now += 5_000
        f.game.tick(f.now)
        f.reservations shouldBe 1
        f.game.awaitsTable("restaurant", f.now) shouldBe false
        f.game.stop(f.player.uniqueId, "cancelled")
        f.game.awaitsTable("restaurant", f.now) shouldBe false
    }

    "collecting highlights and accepts the actual plate but never the guest" {
        // Keep collection as the current task instead of admitting a second guest's order.
        f.begin(remaining = 1)
        f.prepareAndPickup()
        f.clickNpc(433)
        f.now += 18_000
        f.game.tick(f.now)
        verify { f.viewerGlow.setDisplayGlowingFor(f.plates.getValue(433), f.player, true) }
        clearMocks(f.viewerGlow, answers = false, recordedCalls = true)
        f.now += 1_000
        f.game.tick(f.now)
        // Reassert the same entity: client untracking can clear its packet glow selection.
        verify(exactly = 1) { f.viewerGlow.setDisplayGlowingFor(f.plates.getValue(433), f.player, true) }
        verify(exactly = 0) { f.viewerGlow.setDisplayGlowingFor(f.plates.getValue(433), f.player, false) }
        f.clickNpc(433)
        verify(exactly = 0) { f.carry.pickupPlate(any(), any()) }
        f.clickPlate(433)
        verify(exactly = 1) { f.carry.pickupPlate(f.player, "egg") }
        verify { f.viewerGlow.setDisplayGlowingFor(f.plates.getValue(433), f.player, false) }
        clearMocks(f.viewerGlow, answers = false, recordedCalls = true)
        f.game.tick(++f.now)
        verify(exactly = 0) { f.viewerGlow.setDisplayGlowingFor(f.plates.getValue(433), f.player, true) }
        f.screens shouldBe emptyList()
    }

    "accepted cooking order keeps its guidance until delivery despite a second available guest" {
        f.begin()
        f.clickNpc(433)
        val request = f.stationRequest
        f.game.reserves(434) shouldBe true
        for (elapsed in listOf(1_000L, 8_000L, 1_000L)) {
            f.now += elapsed
            f.game.tick(f.now)
            f.stationRequest shouldBe request
        }
        f.pressPickup(433, "Яичница")
        f.clickNpc(433)
        f.served.single().guestId shouldBe 433
    }

    "physical pickup and washing stay usable outside the dynamic venue near own staff" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.clickNpc(433)
            f.now += 10_000
            f.currentVenue = null
            f.moveTo(439)
            f.game.tick(f.now)
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.workstation("pickup")
            f.currentVenue = "restaurant"
            f.clickNpc(433)
            f.now += 18_000
            f.game.tick(f.now)
            f.clickPlate(433)
            f.stationChoices.single().id shouldBe "wash:SCRAPE"
            f.currentVenue = null
            f.moveTo(439)
            f.game.tick(f.now)
            // The permanent kitchen may extend beyond the bartender's interaction radius.
            f.player.teleport(waiterTestConfig().stations.getValue("restaurant").sink.inWorld(f.world))
            f.game.tick(f.now)
            f.game.isWorking(f.player.uniqueId) shouldBe true
            for (action in OriginWaiterWash.entries.filter { it != OriginWaiterWash.SOAK }) {
                f.workstation("wash:${action.name}")
                f.game.tick(f.now)
            }
            f.payments.size shouldBe 1
            f.payments.single().callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.screens shouldBe emptyList()
            verify(exactly = 0) { f.registry.getById(match { it < 0 }) }
        }
    }

    for (boundary in listOf("outside staff reach", "remote", "another venue", "another world")) {
        "a workstation action $boundary is rejected before aiming and cannot pay an order" {
            failOnUnsupportedMockBukkitOperation {
                f.begin()
                f.prepareAndPickup()
                f.clickNpc(433)
                f.now += 18_000
                f.game.tick(f.now)
                f.clickPlate(433)
                f.stationChoices.single().id shouldBe "wash:SCRAPE"
                f.stationHit = OriginWaiterStationHit(checkNotNull(f.stationRequest), "wash:SCRAPE", f.stationStep)
                // The allowed pickup in setup used aimed; only the rejected action is under test here.
                clearMocks(f.stations, answers = false, recordedCalls = true)
                f.currentVenue = if (boundary == "another venue") "brewery" else null
                val destination = when (boundary) {
                    "outside staff reach" -> Location(f.world, 4.51, 70.0, 0.0)
                    "remote" -> Location(f.world, 100.0, 70.0, 0.0)
                    "another venue" -> f.entity(405).location
                    else -> {
                        // A stale venue classification cannot authorize another world's coordinates.
                        f.currentVenue = "restaurant"
                        Location(f.paper.addSimpleWorld("outside_waiter_world"), 0.0, 70.0, 0.0)
                    }
                }
                f.player.teleport(destination)
                f.now += 700
                f.game.interactWorkstation(f.player) shouldBe false
                f.stationStep shouldBe 0
                verify(exactly = 0) { f.stations.aimed(any(), any()) }
                f.payments shouldBe emptyList()
                f.game.tick(f.now)
                f.game.isWorking(f.player.uniqueId) shouldBe false
                f.leased shouldBe emptySet()
                f.payments shouldBe emptyList()
            }
        }
    }

    "two guests are independent one carried dish cannot be duplicated and the shift refills automatically" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.reservations shouldBe 1
            f.clickNpc(433)
            f.game.reserves(434) shouldBe true
            f.now += 10_000
            f.game.tick(f.now)
            val pickup = OriginWaiterStationHit(checkNotNull(f.stationRequest), "pickup", f.stationStep)
            f.workstation("pickup")
            f.stationHit = pickup
            f.now += 700
            f.game.interactWorkstation(f.player)
            f.stationHit = null
            verify(exactly = 1) { f.carry.pickup(f.player, "egg") }
            f.clickNpc(435)
            f.served shouldBe emptyList()
            f.clickNpc(433)
            f.clickNpc(433)
            f.served.map { it.guestId } shouldBe listOf(433)
            f.payments shouldBe emptyList()
            f.clean(433)
            f.payments.size shouldBe 1
            val callback = f.payments.single().callback
            callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.released.size shouldBe 1
            f.reservations shouldBe 3
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.reserves(434) shouldBe true
            f.openStaff()
            f.screenText() shouldContain "1 · 20 💰"
            f.screens.last().buttons.map { it.id.value } shouldBe listOf("waiter_continue", "waiter_finish")
        }
    }

    "drink and main accrue one guest order without a payout interval" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.clickNpc(433)
            f.now += 10_000
            f.pressPickup(433, "Яичница")
            f.clickNpc(433)
            f.clickNpc(434)
            f.now += 10_000
            f.pressPickup(434, "Пиво")
            f.clickNpc(434)
            f.served.map { it.dishId } shouldBe listOf("egg", "herbal_tea")
            f.payments shouldBe emptyList()
            f.clean(433)
            f.payments.size shouldBe 1
            f.clean(434)
            f.payments.size shouldBe 1
            f.now += 10_000
            f.pressPickup(434, "Яичница")
            f.clickNpc(434)
            f.served.map { it.dishId } shouldBe listOf("egg", "herbal_tea", "egg")
            f.clean(434)
            f.payments.size shouldBe 1
            f.payments[0].callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.game.tick(f.now)
            f.payments.size shouldBe 2
            (f.payments[0].id != f.payments[1].id) shouldBe true
            f.payments[1].callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.openStaff()
            f.screenText() shouldContain "2 · 40 💰"
            f.settlements shouldBe emptyList()
            f.press("waiter_finish")
            f.settlements.size shouldBe 1
            f.messages() shouldContain "Заказов: 2 · Получено 40 💰"
        }
    }

    "one remaining quota slot never promises a second guest order" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(remaining = 1)
            f.clickNpc(433)
            f.reservations shouldBe 1
            f.game.reserves(434) shouldBe false
            f.now += 10_000
            f.pressPickup(433, "Яичница")
            f.clickNpc(433)
            f.clean(433)
            f.payments.single().callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.reservations shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.messages() shouldContain "Заказов: 1 · Получено 20 💰"
        }
    }

    "storage retry preserves identity and ignores callbacks from the previous attempt" {
        failOnUnsupportedMockBukkitOperation {
            f.begin()
            f.prepareAndPickup()
            f.clickNpc(433)
            f.clean(433)
            val first = f.payments.single()
            val retryAt = f.now + 1_000L
            first.callback(SideJobRewardResult(SideJobRewardOutcome.RETRY))
            f.game.reserves(433) shouldBe true
            f.released shouldBe emptyList()
            f.now = retryAt - 1
            f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.now++
            f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments[1].id shouldBe first.id
            first.callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.released shouldBe emptyList()
            f.payments[1].callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.released.count { it.lease.token == first.id } shouldBe 1
            f.openStaff()
            f.screenText() shouldContain "1 · 20 💰"
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
            payment.callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.game.reserves(433) shouldBe false
            f.messages() shouldContain "Заказов: 1 · Получено 20 💰"
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
                f.payments.last().callback(SideJobRewardResult(outcome))
                f.game.isWorking(f.player.uniqueId) shouldBe false
                f.game.reserves(433) shouldBe false
                f.now += 100_000
                f.game.tick(f.now)
                f.payments.size shouldBe count
                f.messages() shouldContain "Оплата ожидается: 1 заказов"
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
            payment.callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            payment.callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.released.count { it.lease.token == payment.id } shouldBe 1
            f.openStaff()
            f.screenText() shouldContain "1 · 20 💰"
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
            f.workstation("remake")
            f.now += 10_000
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

    "late admission and old dialog callbacks cannot alter a replacement shift" {
        failOnUnsupportedMockBukkitOperation {
            f.nextStatus = CompletableFuture()
            val cancelledRead = f.nextStatus
            f.clickNpc(439)
            f.game.stop(f.player.uniqueId, "quit")
            f.begin()
            cancelledRead.complete(f.ready.copy(unresolved = true, recoverableShiftId = UUID.randomUUID()))
            f.scheduler.executeImmediate()
            f.settlements shouldBe emptyList()
            f.game.reserves(433) shouldBe true
            f.openStaff()
            val oldFinish = f.screens.last().buttons.single { it.id.value == "waiter_finish" }
            f.press("waiter_finish")
            f.begin()
            f.click(oldFinish)
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
            payment.callback(SideJobRewardResult(SideJobRewardOutcome.CREDITED))
            f.game.close()
            f.released.size shouldBe 2
            f.leased shouldBe emptySet()
            verify(exactly = 1) { f.rewards.close() }
        }
    }

    "unavailable admission remains in the entry dialog and exposes no accept button" {
        failOnUnsupportedMockBukkitOperation {
            for ((status, message) in listOf(
                SideJobRewardStatus(true, 0, 0, false) to "100 поручений",
                SideJobRewardStatus(false, 5, 0, false) to "Касса временно недоступна",
                SideJobRewardStatus(true, 5, 0, true) to "Касса ещё проверяет",
            )) {
                f.nextStatus = CompletableFuture.completedFuture(status)
                f.openStaff()
                f.screens.last().buttons.map { it.id.value } shouldBe listOf("waiter_cancel")
                f.screenText() shouldContain message
                f.reservations shouldBe 0
            }
        }
    }

    "last washing action pays only after its animation and duplicate clicks do not advance it" {
        f.begin(remaining = 1)
        f.prepareAndPickup()
        f.clickNpc(433)
        f.now += 18_000
        f.game.tick(f.now)
        f.clickPlate(433)
        listOf("SCRAPE", "SOAP", "RINSE").forEach { f.workstation("wash:$it") }
        f.autoCompleteActions = false
        f.workstation("wash:DRY")
        f.payments shouldBe emptyList()
        val step = f.stationStep
        f.stationHit = OriginWaiterStationHit(checkNotNull(f.stationRequest), "wash:DRY", step)
        f.now += 100
        f.game.interactWorkstation(f.player) shouldBe true
        f.stationStep shouldBe step
        f.payments shouldBe emptyList()
        f.finishAnimation(599)
        f.payments shouldBe emptyList()
        f.finishAnimation(1)
        f.payments.size shouldBe 1
        f.game.tick(f.now + 2_000)
        f.payments.size shouldBe 1
    }

    "losing another table does not interrupt the plate already being washed" {
        f.begin()
        f.prepareAndPickup()
        f.clickNpc(433)
        f.clickNpc(434)
        f.now += 10_000
        f.pressPickup(434, "Пиво")
        f.clickNpc(434)
        f.now += 18_000
        f.game.tick(f.now)
        f.clickPlate(434)
        f.autoCompleteActions = false
        f.workstation("wash:SCRAPE")
        val request = f.stationRequest
        f.leased.remove(433)
        clearMocks(f.stations, answers = false, recordedCalls = true)
        f.game.tick(f.now)
        verify(exactly = 0) { f.stations.remove(f.player.uniqueId) }
        f.stationRequest shouldBe request
        f.stationStep shouldBe 0
        f.finishAnimation(700)
        f.stationRequest shouldBe request
        f.stationStep shouldBe 1
        f.stationChoices.first { it.next }.id shouldBe "wash:SOAP"
        f.payments shouldBe emptyList()
    }

    "ending a shift during drying cancels the animation without issuing a reward" {
        f.begin(remaining = 1)
        f.prepareAndPickup()
        f.clickNpc(433)
        f.now += 18_000
        f.game.tick(f.now)
        f.clickPlate(433)
        listOf("SCRAPE", "SOAP", "RINSE").forEach { f.workstation("wash:$it") }
        f.autoCompleteActions = false
        f.workstation("wash:DRY")
        f.game.stop(f.player.uniqueId, "finished-early")
        f.finishAnimation(5_000)
        f.payments shouldBe emptyList()
        f.game.isWorking(f.player.uniqueId) shouldBe false
        f.leased shouldBe emptySet()
    }

    "wrong dispute guest keeps the water and the peaceful resolution earns no separate payment" {
        f.close()
        f = WaiterGameFixture(disputeEveryOrders = 2)
        f.begin()
        f.clickNpc(433)
        f.clickNpc(433)
        f.clickNpc(434)
        f.workstation("water")
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
    var autoCompleteActions = true
    var stationHit: OriginWaiterStationHit? = null
    var stationRequest: UUID? = null
    var stationChoices = emptyList<OriginWaiterStationChoice>()
    var stationStep = 0
    val screens = mutableListOf<PaperDialogScreen>()
    val payments = mutableListOf<Payment>()
    val settlements = mutableListOf<UUID>()
    val earned = mutableMapOf<UUID, SideJobShiftEarnings>()
    val reserved = mutableListOf<OriginWaiterTarget>()
    val released = mutableListOf<OriginWaiterTarget>()
    val served = mutableListOf<OriginWaiterTarget>()
    val leased = mutableSetOf<Int>()
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    val plates = mutableMapOf<Int, ItemDisplay>()
    var tablesAvailable = true
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
        every { rewards.status(any(), any()) } answers { nextStatus }
        every { rewards.credit(OriginSideJobKind.WAITER, any(), any(), any(), any(), any()) } answers {
            val shift = thirdArg<UUID>(); val order = arg<UUID>(3); val quality = arg<Int>(4)
            val callback = arg<(SideJobRewardResult) -> Unit>(5)
            payments += Payment(order) { result ->
                if (result.outcome == SideJobRewardOutcome.CREDITED) {
                    val old = earned[shift]
                    if (order.toString() !in old?.orders.orEmpty()) earned[shift] = SideJobShiftEarnings(shift.toString(), OriginSideJobKind.WAITER,
                        old?.orders.orEmpty() + order.toString(), (old?.amount ?: 0) + 20, (old?.totalQuality ?: 0) + quality)
                }
                callback(result)
            }
        }
        every { rewards.settle(any(), any(), any()) } answers {
            val shift = secondArg<UUID>(); settlements += shift
            thirdArg<(SideJobRewardResult) -> Unit>()(SideJobRewardResult(SideJobRewardOutcome.PAID, earned[shift]))
        }
        every { carry.pickup(any(), any()) } returns true
        every { carry.pickupPlate(any(), any()) } returns true
        every { carry.pickupWater(any()) } returns true
        every { stations.aimed(any(), any()) } answers { stationHit }
        every { stations.show(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            stationRequest = arg(1)
            stationChoices = arg(2)
            stationStep = arg(4)
        }
        every { stations.begin(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        for ((id, x) in mapOf(439 to 0.0, 405 to 20.0, 433 to 8.0, 434 to 9.0, 435 to 10.0, 436 to 11.0, 416 to 28.0, 417 to 29.0)) {
            val entity = mockk<Villager>(relaxed = true) {
                every { world } returns this@WaiterGameFixture.world
                every { location } answers { Location(this@WaiterGameFixture.world, x, 70.0, 0.0) }
                every { isValid } returns true
            }
            plates[id] = mockk<ItemDisplay>(relaxed = true) {
                every { location } answers { Location(this@WaiterGameFixture.world, x, 70.8, 0.0) }
                every { isValid } returns true
            }
            actors[id] = mockk<NPC> {
                every { this@mockk.id } returns id
                every { isSpawned } returns true
                every { this@mockk.entity } returns entity
                every { name } returns "&6Бармен"
            }
        }
        game = OriginWaiterGame(waiterTestConfig().copy(disputeEveryOrders = disputeEveryOrders, preparationMillis = 10_000, eatingMillis = 18_000, actionMillis = 700),
            reserve = { venue ->
                val guest = if (!tablesAvailable) null else (if (venue.id == "restaurant") 433..436 else 416..417).firstOrNull { it !in leased }
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
            clock = { now.also { now += clockReadAdvance } }, otherWork = { otherWork }, stations = stations, mealDisplay = { plates[it.guestId] })
        listener = OriginDiningCitizensListener(game::interact)
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity as Villager
    fun moveTo(id: Int) { now++; player.teleport(entity(id).location) }
    fun clickNpc(id: Int) {
        moveTo(id)
        game.interact(player, id) shouldBe true
        if (autoCompleteActions && disputeEveryOrders > 0) finishAnimation(1_600)
    }
    fun finishAnimation(millis: Long = 700) {
        now += millis
        scheduler.tick((millis / 50).coerceAtLeast(1))
        game.tick(now)
    }
    fun openStaff() {
        clickNpc(439)
        scheduler.executeImmediate()
    }
    fun clickPlate(guest: Int) {
        moveTo(guest)
        game.interactMeal(player, "table-$guest") shouldBe true
    }
    fun click(button: PaperDialogButton) {
        val context = mockk<PaperDialogClickContext> { every { player } returns this@WaiterGameFixture.player }
        button.onClick.handle(context)
    }
    fun press(id: String) = click(screens.last().buttons.single { it.id.value == id })
    fun pressPickup(guest: Int, dish: String) {
        game.tick(now)
        stationChoices.single { it.id == "pickup" }.dishId shouldBe if (dish == "Пиво") "herbal_tea" else "egg"
        reserved.singleOrNull { it.lease.token == stationRequest }?.guestId shouldBe guest
        workstation("pickup")
    }
    fun begin(remaining: Int = 20) {
        nextStatus = CompletableFuture.completedFuture(ready.copy(remainingOrders = remaining))
        openStaff()
        press("waiter_start")
        scheduler.executeImmediate()
        screens.clear()
        shiftStarted = now
        game.reserves(433) shouldBe true
    }
    fun prepareAndPickup() {
        clickNpc(433)
        now += 10_000
        pressPickup(433, "Яичница")
    }
    fun workstation(action: String) {
        now += 700
        stationHit = OriginWaiterStationHit(checkNotNull(stationRequest), action, stationStep)
        game.interactWorkstation(player) shouldBe true
        stationHit = null
        if (autoCompleteActions && (action.startsWith("wash:") || action.startsWith("ingredient:") || action == "water")) finishAnimation(if (action == "water") 1_600 else if (action == "wash:SOAK") 3_000 else 700)
    }
    fun clean(guest: Int) {
        now += 18_000
        game.tick(now)
        clickPlate(guest)
        // Direct world input uses the rendered request/index, just as the actual ray hit does.
        for (action in listOf(OriginWaiterWash.SCRAPE, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY)) {
            workstation("wash:${action.name}")
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
    data class Payment(val id: UUID, val callback: (SideJobRewardResult) -> Unit)
}
