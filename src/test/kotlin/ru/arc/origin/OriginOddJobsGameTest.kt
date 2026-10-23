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
import net.citizensnpcs.api.event.NPCLeftClickEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.entity.Villager
import org.bukkit.entity.Horse
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.Action
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import io.papermc.paper.event.player.PlayerArmSwingEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.util.Vector
import org.bukkit.util.RayTraceResult
import org.mockbukkit.mockbukkit.world.WorldMock
import org.mockbukkit.mockbukkit.inventory.meta.ItemMetaMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.logging.ArcLogging
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

    "either button can use private request stock while native container access remains denied" {
        for (variant in listOf(OriginOddJobVariant.FORGE_NEW, OriginOddJobVariant.HAUL_COPPER)) {
            f.close(); f = OddJobsFixture(doubleStocks = true)
            failOnUnsupportedMockBukkitOperation {
                f.begin(349, variant)
                val job = f.game.currentJob(f.player.uniqueId)!!
                val source = f.stations.getValue(if (variant == OriginOddJobVariant.FORGE_NEW) OriginOddJobStationId.BLANK else OriginOddJobStationId.ORE_STOCK)
                val other = originOddJobStationBlocks(source.block).first { it != source.block }
                val hit = other.location.add(0.5, 0.5, 0.5)
                val at = hit.clone().add(3.7, -0.5, 0.0).apply {
                    setDirection(hit.toVector().subtract(clone().add(0.0, f.player.eyeHeight, 0.0).toVector()))
                }
                f.player.teleport(at)
                (f.player.eyeLocation.distanceSquared(source.point) > f.config.range * f.config.range) shouldBe true
                f.world.obstruction = RayTraceResult(hit.toVector(), other, BlockFace.NORTH)
                fun click(result: Event.Result) = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, null, other,
                    BlockFace.NORTH, EquipmentSlot.HAND).also { it.setUseInteractedBlock(result); f.game.onBlock(it) }
                f.now += 700
                click(Event.Result.DENY).useInteractedBlock() shouldBe Event.Result.DENY
                if (variant == OriginOddJobVariant.FORGE_NEW) {
                    (f.player.openInventory.topInventory.holder as OriginOddJobSupplies.Stock).jobId shouldBe job.id
                    f.now += 700
                    f.game.onBlock(PlayerInteractEvent(f.player, Action.LEFT_CLICK_BLOCK, null, other, BlockFace.NORTH, EquipmentSlot.HAND))
                }
                if (variant == OriginOddJobVariant.FORGE_NEW) {
                    val stock = f.player.openInventory.topInventory.holder as OriginOddJobSupplies.Stock
                    stock.jobId shouldBe job.id
                    stock.block shouldBe source.block
                    f.stockClick(); f.scheduler.tick()
                    f.requireStage(OriginOddJobStage.FORGE_HEAT, "blank selection stays reachable from the far half")
                    stock.storage.getItem(4) shouldBe null
                    f.player.inventory.contents.filterNotNull().count { it.type == Material.STRUCTURE_VOID } shouldBe 1
                } else {
                    f.requireStage(OriginOddJobStage.HAUL_LOAD, "opposite chest half supplies the cargo")
                    f.player.inventory.itemInMainHand.type shouldBe Material.STRUCTURE_VOID
                }
            }
        }
    }

    "stock selection rechecks connected chest reach before and after the inventory transaction" {
        for (moveBeforeClick in listOf(false, true)) {
            f.close(); f = OddJobsFixture(doubleStocks = true)
            failOnUnsupportedMockBukkitOperation {
                f.startForge()
                val source = f.stations.getValue(OriginOddJobStationId.BLANK)
                val other = originOddJobStationBlocks(source.block).first { it != source.block }
                val hit = other.location.add(0.5, 0.5, 0.5)
                val at = hit.clone().add(3.7, -0.5, 0.0).apply {
                    setDirection(hit.toVector().subtract(clone().add(0.0, f.player.eyeHeight, 0.0).toVector()))
                }
                f.player.teleport(at)
                f.world.obstruction = RayTraceResult(hit.toVector(), other, BlockFace.NORTH)
                val event = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, null, other, BlockFace.NORTH, EquipmentSlot.HAND)
                event.setUseInteractedBlock(Event.Result.DEFAULT)
                f.now += 700
                f.game.onBlock(event)
                val stock = f.player.openInventory.topInventory.holder as OriginOddJobSupplies.Stock
                if (moveBeforeClick) f.player.setLocation(at.clone().add(10.0, 0.0, 0.0))
                f.stockClick()
                if (!moveBeforeClick) f.player.setLocation(at.clone().add(10.0, 0.0, 0.0))
                f.player.openInventory.topInventory shouldBe stock.storage
                f.scheduler.tick()
                f.requireStage(OriginOddJobStage.FORGE_BLANK, "leaving either half's reach cannot take the blank")
                stock.storage.getItem(4)!!.amount shouldBe 1
                f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            }
        }
    }

    "every employer opens an ordinary right click confirmation and never starts before acceptance" {
        failOnUnsupportedMockBukkitOperation {
            for (id in listOf(440, 349)) {
                f.open(id, sneaking = false)
                f.game.isWorking(f.player.uniqueId) shouldBe false
                f.screens.last().buttons.map { it.id.value } shouldBe listOf("odd_job_start", "odd_job_later")
                f.screens.last().buttons.last().label.let(f::plain) shouldBe "Не сейчас"
            }
            f.desiredVariant = OriginOddJobVariant.FORGE_REPAIR
            f.press("odd_job_start"); f.scheduler.executeImmediate(); f.scheduler.executeImmediate()
            f.game.isWorking(f.player.uniqueId) shouldBe true
            f.game.currentJob(f.player.uniqueId)!!.employer shouldBe OriginOddJobKind.FORGE
        }
    }
    "auctioneer clicks fall through to the auction service without starting or interrupting work" {
        failOnUnsupportedMockBukkitOperation {
            f.moveNpc(365)
            val auctioneer = f.entity(365)
            val event = PlayerInteractEntityEvent(f.player, auctioneer, EquipmentSlot.HAND)
            f.game.onEntity(event)
            event.isCancelled shouldBe false
            f.game.interact(f.player, 365) shouldBe false
            f.screens shouldBe emptyList()
            f.game.isWorking(f.player.uniqueId) shouldBe false
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            val screens = f.screens.size
            f.moveNpc(365)
            f.game.interact(f.player, 365) shouldBe false
            f.screens.size shouldBe screens
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe request
            f.payments shouldBe emptyList()
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
    "copper and iron blanks enter inventory on an ordinary click from the reachable stock edge" {
        for (variant in listOf(OriginOddJobVariant.FORGE_HINGE, OriginOddJobVariant.FORGE_NEW)) {
            f.close(); f = OddJobsFixture()
            failOnUnsupportedMockBukkitOperation {
                f.begin(349, variant)
                val source = f.stations.getValue(OriginOddJobStationId.BLANK)
                source.point.y = source.block.y + 0.9
                f.stroke(OriginOddJobStationId.BLANK)
                val stock = f.player.openInventory.topInventory
                // The failed live LEFT click, translated from physical chest (82,72,71).
                f.player.setLocation(source.block.location.add(-3.43103797070115, -2.0, 2.58093571451246))
                (f.player.eyeLocation.distanceSquared(source.point) > f.config.range * f.config.range) shouldBe true
                val click = org.bukkit.event.inventory.InventoryClickEvent(f.player.openInventory,
                    org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 4,
                    org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
                f.game.onInventoryClick(click)
                click.isCancelled shouldBe true
                f.scheduler.tick()
                f.requireStage(OriginOddJobStage.FORGE_HEAT, "a native reachable stock needs no shift-click")
                val item = f.player.inventory.contents.filterNotNull().single { it.type == Material.STRUCTURE_VOID }
                item.itemMeta.itemModel shouldBe org.bukkit.NamespacedKey.minecraft(f.game.currentJob(f.player.uniqueId)!!.forge!!.metal.itemModel)
                stock.getItem(4) shouldBe null
            }
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
    "Edgar's hauling order leases Savva and unloads automatically inside the ground marker" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.HAUL_COPPER)
            f.leases.single().actorIds shouldBe setOf(349, 362, 351)
            f.requireStage(OriginOddJobStage.HAUL_PICKUP, "before picking up Savva's cargo")
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            val source = f.stations.getValue(OriginOddJobStationId.ORE_STOCK)
            val sourceAt = source.block.location.clone()
            verify { f.visuals.target(f.player, request, sourceAt.clone().add(0.5, 0.5, 0.5), any(), true) }
            f.stroke(OriginOddJobStationId.ORE_STOCK)
            f.requireStage(OriginOddJobStage.HAUL_LOAD, "after picking up Savva's cargo")
            source.block.location shouldBe sourceAt
            source.valid() shouldBe true
            f.player.inventory.itemInMainHand.type shouldBe Material.STRUCTURE_VOID
            @Suppress("UNNECESSARY_SAFE_CALL") // The closed MockBukkit view may omit its top inventory.
            (f.player.openInventory.topInventory?.holder is OriginOddJobSupplies.Stock) shouldBe false
            f.player.inventory.itemInMainHand.itemMeta.itemModel shouldBe org.bukkit.NamespacedKey.minecraft("raw_copper")
            f.strokeCart()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_ATTACH
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            val cart = f.cartDisplays.first()
            verify { cart.isGlowing = true }
            val loadedDisplays = f.cartDisplays.toList()
            f.strokeCart()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.cartDisplays shouldBe loadedDisplays
            f.cartDisplays.forEach {
                verify(exactly = 0) { it.attachTo(f.player) }
                verify { it.teleport(match { pose -> pose.world?.uid == f.world.uid }); it.teleportDuration = 1 }
            }
            val destination = f.stations.getValue(OriginOddJobStationId.HAUL_DELIVERY)
            verify { f.visuals.destination(f.player, request, destination.point.clone().add(0.0, 0.05, 0.0), "Разгрузка сырья") }
            val outline = originOddJobDestinationPieces()
            val halfWidth = outline.maxOf { it.center.x + it.size.x / 2 }
            val halfLength = outline.maxOf { it.center.z + it.size.z / 2 }
            fun reaches(dx: Double, dz: Double, target: OriginOddJobStation = destination): Boolean {
                f.player.teleport(target.point.clone().add(dx, 0.0, dz).apply { yaw = 0f })
                return f.forge.tick(f.player, f.game.currentJob(f.player.uniqueId)!!, target)
            }
            reaches(halfWidth - 0.0001, halfLength - 0.0001) shouldBe true
            reaches(halfWidth + 0.0001, 0.0) shouldBe false
            reaches(0.0, halfLength + 0.0001) shouldBe false
            reaches(-halfLength + 0.0001, halfWidth - 0.0001,
                destination.copy(point = destination.point.clone().apply { yaw = 90f })) shouldBe true
            f.payments shouldBe emptyList()
            // No authored lane: walking around the yard keeps the same cart and active hauling order.
            f.player.teleport(Location(f.world, -4.0, 70.0, 9.0, 90f, 0f)); f.scheduler.tick()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.HAUL_PUSH
            f.payments shouldBe emptyList()
            f.dragCart()
            verify { cart.detach() }
            @Suppress("UNNECESSARY_SAFE_CALL") // The closed MockBukkit view may omit its top inventory.
            (f.player.openInventory.topInventory?.holder is OriginOddJobSupplies.Stock) shouldBe false
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.cartDisplays.none { it.isValid } shouldBe true
            f.released shouldBe f.leases
            repeat(12) { f.scheduler.tick() }
            f.payments.size shouldBe 1
        }
    }
    "Luka engraving owns her actor through manual pickup carving collection and return" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(349, OriginOddJobVariant.ENGRAVE_CAT)
            f.leases.single().actorIds shouldBe setOf(349, 362, 354)
            f.pickupBlank()
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.ENGRAVE_PLACE
            f.stroke(OriginOddJobStationId.ENGRAVING)
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            repeat(6) { f.stroke(OriginOddJobStationId.ENGRAVING, left = true, elapsed = 600) }
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.ENGRAVE_COLLECT
            f.released shouldBe emptyList()
            verify { f.visuals.glowWorkpiece(f.player, any(), true) }
            verify { f.visuals.target(f.player, any(), f.stations.getValue(OriginOddJobStationId.ENGRAVING).point, any(), false) }
            f.stroke(OriginOddJobStationId.ENGRAVING)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.RETURN
            f.clickNpc(349, sneaking = false)
            f.payments.single().kind shouldBe OriginSideJobKind.FORGE
            f.released shouldBe f.leases
        }
    }
    "durable credit schedules the next task immediately without a payout" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val first = f.payments.single()
            first.callback(SideJobRewardOutcome.CREDITED); first.callback(SideJobRewardOutcome.CREDITED)
            val screens = f.screens.size
            f.game.tick(f.now); f.scheduler.executeImmediate()
            (f.game.currentJob(f.player.uniqueId)!!.variant == OriginOddJobVariant.FORGE_NEW) shouldBe false
            f.screens.size shouldBe screens
            f.actionBars().count { "Поручение принято" in it } shouldBe 1
            f.settlements shouldBe emptyList()
        }
    }
    "accepted earnings survive quit and storage retry keeps exactly the same request" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val first = f.payments.single(); first.callback(SideJobRewardOutcome.RETRY)
            f.game.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            f.now += 999; f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.now++; f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments.last().id shouldBe first.id
            f.payments.last().callback(SideJobRewardOutcome.CREDITED)
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "storage retry has no payout countdown and explicit finish settles saved earnings once" {
        failOnUnsupportedMockBukkitOperation {
            f.startForge(); f.finishForge()
            val request = f.payments.single()
            request.callback(SideJobRewardOutcome.RETRY)
            f.game.tick(f.now)
            f.plain(f.player.bossBars.single().name()) shouldContain "Принимаем"
            f.open(349, sneaking = false)
            f.screens.last().body.joinToString(" ") { f.plain(it.text) }.contains("выплата через") shouldBe false
            f.now += 1_000; f.game.tick(f.now)
            f.payments.size shouldBe 2
            f.payments.last().id shouldBe request.id
            f.payments.last().callback(SideJobRewardOutcome.CREDITED)
            f.settlements shouldBe emptyList()
            f.open(349, sneaking = false)
            f.press("odd_job_finish")
            f.settlements.size shouldBe 1
            f.game.isWorking(f.player.uniqueId) shouldBe false
        }
    }
    "stable orders retain their lease until physical tools and tack are returned before one credit" {
        for (variant in listOf(OriginOddJobVariant.YARD_WATER, OriginOddJobVariant.YARD_RATIONS,
            OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE)) {
            failOnUnsupportedMockBukkitOperation {
                f.begin(440, variant)
                val job = f.game.currentJob(f.player.uniqueId)!!
                f.leases.last().actorIds.contains(440) shouldBe false
                if (!variant.isYardPreparation()) f.leases.last().actorIds shouldBe setOf(requireNotNull(job.yard).animalId)
                when (variant) {
                    OriginOddJobVariant.YARD_WATER -> {
                        f.stroke(OriginOddJobStationId.WELL); f.stroke(OriginOddJobStationId.WATER)
                        f.clickNpc(requireNotNull(job.yard).animalId, sneaking = false)
                        f.requireStage(OriginOddJobStage.YARD_DRINKING, "water is in use")
                        f.game.tick(f.now); f.gearClick(left = true)
                        f.game.currentJob(f.player.uniqueId) shouldBe null
                        f.payments.count { it.id == job.id } shouldBe 1
                    }
                    OriginOddJobVariant.YARD_RATIONS -> {
                        f.stroke(OriginOddJobStationId.HAY); f.clickNpc(requireNotNull(job.yard).animalId, sneaking = false)
                        f.game.currentJob(f.player.uniqueId) shouldBe null
                        f.payments.count { it.id == job.id } shouldBe 1
                    }
                    OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE -> {
                        f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
                        repeat(ODD_JOB_GROOM_STROKES) { f.clickNpc(requireNotNull(job.yard).animalId, sneaking = false) }
                        f.finishTack()
                        f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_UNTIE_FIRST
                        f.strokeTie(); f.strokeTie()
                        f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_DELIVER
                        f.game.tick(f.now)
                        f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_HANDOFF
                        f.game.tick(f.now)
                        f.requireStage(OriginOddJobStage.YARD_HANDED, "rider route owns this order")
                        f.game.tick(f.now)
                        f.returnTack()
                        f.payments.count { it.id == job.id } shouldBe 1
                    }
                    else -> error("unexpected stable variant")
                }
                val employer = f.entity(440)
                if (variant in setOf(OriginOddJobVariant.YARD_WATER, OriginOddJobVariant.YARD_RATIONS)) verify(exactly = 0) { f.glow.setEntityGlowingFor(employer, f.player, true) }
                val screens = f.screens.size
                f.game.tick(f.now); f.game.tick(f.now)
                val payment = f.payments.single { it.id == job.id }
                payment.kind shouldBe OriginSideJobKind.MOUNTYARD
                f.game.currentJob(f.player.uniqueId) shouldBe null
                f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
                payment.callback(SideJobRewardOutcome.CREDITED); payment.callback(SideJobRewardOutcome.CREDITED)
                f.game.tick(f.now); f.scheduler.executeImmediate()
                (f.game.currentJob(f.player.uniqueId)!!.id != job.id) shouldBe true
                f.screens.size shouldBe screens
                f.actionBars().count { "Поручение принято" in it } shouldBe 1
                f.open(440, sneaking = false); f.press("odd_job_finish")
                f.game.isWorking(f.player.uniqueId) shouldBe false
            }
        }
    }
    "lesson waits for explicit mounting then all escorted field arrivals before one accepted order" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_LESSON)
            val job = f.game.currentJob(f.player.uniqueId)!!
            val animal = requireNotNull(job.yard).animalId
            job.yard.routeTotal shouldBe 3
            f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
            repeat(ODD_JOB_GROOM_STROKES) { f.clickNpc(animal, sneaking = false) }
            f.finishTack()
            f.strokeTie(); f.strokeTie(); f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.YARD_HANDOFF, "horse reaches mounting place")
            f.game.tick(f.now); f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.YARD_HANDOFF, "lesson must wait for explicit NPC click")
            f.payments shouldBe emptyList()
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed.contains(OriginYardGear.LEAD) shouldBe true
            f.clickNpc(requireNotNull(f.config.yardRider).npcId, sneaking = false)
            f.requireStage(OriginOddJobStage.YARD_LESSON_WALK, "mounted lesson begins")
            f.game.tick(f.now)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.routeIndex shouldBe 0
            f.lessonArrival = true
            repeat(3) { index ->
                f.payments shouldBe emptyList()
                f.game.currentJob(f.player.uniqueId)!!.yard!!.routeIndex shouldBe index
                f.game.tick(f.now)
            }
            f.returnTack()
            f.payments.count { it.id == job.id } shouldBe 1
            f.game.tick(f.now); f.game.tick(f.now)
            f.payments.count { it.id == job.id } shouldBe 1
        }
    }
    "cleaning uses shovel and cart stages and accepts only the returned empty cart" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_CLEANUP)
            val job = f.game.currentJob(f.player.uniqueId)!!
            verify(exactly = 0) { f.yard.begin(f.player, any(), any()) }
            f.stroke(OriginOddJobStationId.BRUSH)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.SHOVEL)
            f.strokeCleaning()
            f.requireStage(OriginOddJobStage.CLEAN_PUSH, "empty cart attached")
            f.cleaningParkReady = false; f.strokeCleaning()
            f.requireStage(OriginOddJobStage.CLEAN_PUSH, "cart cannot park away from stall")
            f.cleaningParkReady = true; f.cleaningArrival = true; f.game.tick(f.now); f.cleaningArrival = false
            f.requireStage(OriginOddJobStage.CLEAN_SCOOP, "cart parks at the stall without a click")
            repeat(3) { index ->
                f.strokeCleaning(left = index % 2 == 0)
                f.requireStage(OriginOddJobStage.CLEAN_LOAD, "shovel holds a portion")
                f.strokeCleaning(left = index % 2 != 0)
                f.game.currentJob(f.player.uniqueId)!!.cleaning!!.collected shouldBe index + 1
            }
            f.payments shouldBe emptyList()
            f.strokeCleaning()
            f.requireStage(OriginOddJobStage.CLEAN_PUSH, "loaded cart attached")
            f.cleaningArrival = true; f.game.tick(f.now); f.cleaningArrival = false
            f.requireStage(OriginOddJobStage.CLEAN_UNLOAD, "cart at unloading pad")
            f.strokeCleaning()
            f.requireStage(OriginOddJobStage.CLEAN_RETURN, "empty cart still needs return")
            f.payments shouldBe emptyList()
            f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.CLEAN_RETURN, "waiting until cart is actually home")
            f.cleaningArrival = true; f.game.tick(f.now); f.cleaningArrival = false
            f.requireStage(OriginOddJobStage.CLEAN_PARK, "home arrival needs parking")
            f.payments shouldBe emptyList()
            f.strokeCleaning(); f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.CLEAN_TOOL_RETURN, "parked cart still holds the shovel loan")
            f.payments shouldBe emptyList()
            f.gearClick(left = true); f.game.tick(f.now)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments.count { it.id == job.id } shouldBe 1
            f.released shouldBe f.leases
            verify { f.cleaning.clear(f.player.uniqueId, job.id) }
            f.game.tick(f.now)
            f.payments.count { it.id == job.id } shouldBe 1
        }
    }
    "revoking a cleaning scene removes work supplies and cannot accept unfinished manure" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_CLEANUP)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.BRUSH); f.strokeCleaning(); f.strokeCleaning(); f.strokeCleaning(left = true)
            f.worksiteRevocations.getValue(f.leases.single().token).invoke()
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments shouldBe emptyList()
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            verify { f.cleaning.clear(f.player.uniqueId, job.id) }
            f.released shouldBe f.leases
        }
    }
    "automatic rider handoff cannot credit a horse when its receiver is no longer nearby" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_SADDLE)
            val animal = requireNotNull(f.game.currentJob(f.player.uniqueId)!!.yard).animalId
            f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
            repeat(ODD_JOB_GROOM_STROKES) { f.clickNpc(animal, sneaking = false) }
            f.finishTack()
            f.strokeTie(); f.strokeTie(); f.game.tick(f.now)
            f.requireStage(OriginOddJobStage.YARD_HANDOFF, "horse arrived for automatic handoff")
            val receiver = f.entity(requireNotNull(f.config.yardRider).npcId)
            every { receiver.location } returns Location(f.world, 30.0, 70.0, 0.0)
            f.game.tick(f.now)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments shouldBe emptyList()
            f.released shouldBe f.leases
        }
    }
    "empty bucket is filled at the actual cauldron and carried to the selected animal" {
        failOnUnsupportedMockBukkitOperation {
            f.player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            f.begin(440, OriginOddJobVariant.YARD_WATER)
            val screens = f.screens.size
            f.stroke(OriginOddJobStationId.WELL)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_WELL
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.BUCKET)
            f.player.inventory.getItem(0)!!.amount shouldBe 7
            f.stroke(OriginOddJobStationId.WATER, elapsed = 30_000)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_WATER
            f.player.inventory.getItem(0)!!.amount shouldBe 7
            f.clickNpc(f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId, sneaking = false)
            f.game.tick(f.now); f.gearClick()
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments.single().kind shouldBe OriginSideJobKind.MOUNTYARD
            f.game.tick(f.now)
            f.payments.size shouldBe 1
            f.screens.size shouldBe screens
        }
    }
    "water accepts once after drinking and bucket return then assigns a different animal" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_WATER)
            val first = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.WELL); f.stroke(OriginOddJobStationId.WATER)
            f.clickNpc(first.yard!!.animalId, sneaking = false)
            f.payments shouldBe emptyList()
            f.game.tick(f.now); f.gearClick(left = true)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments.single().id shouldBe first.id
            f.payments.single().callback(SideJobRewardOutcome.CREDITED)
            f.game.tick(f.now); f.scheduler.executeImmediate()
            val next = f.game.currentJob(f.player.uniqueId)!!
            (next.yard!!.animalId != first.yard.animalId) shouldBe true
            f.payments.count { it.id == first.id } shouldBe 1
        }
    }
    "feeding targets the assigned animal rather than the old fixed feed station" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            val first = f.game.currentJob(f.player.uniqueId)!!
            f.stroke(OriginOddJobStationId.HAY)
            f.aim(OriginOddJobStationId.FEED)
            f.game.work(f.player, false, f.stations.getValue(OriginOddJobStationId.FEED).block) shouldBe false
            f.clickNpc(if (first.yard!!.animalId == 422) 423 else 422, sneaking = false)
            f.requireStage(OriginOddJobStage.YARD_FEED, "only assigned animal accepts hay")
            f.clickNpc(first.yard.animalId, sneaking = false)
            f.payments.single().id shouldBe first.id
        }
    }
    "yard pickup preserves every personal hotbar slot without adding an inventory token" {
        failOnUnsupportedMockBukkitOperation {
            repeat(9) { f.player.inventory.setItem(it, org.bukkit.inventory.ItemStack(Material.DIAMOND, it + 1)) }
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.stroke(OriginOddJobStationId.HAY)
            f.player.inventory.getItem(9) shouldBe null
            repeat(9) { f.player.inventory.getItem(it)!!.amount shouldBe it + 1 }
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.HAY)
            f.clickNpc(f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId, sneaking = false)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments.single().kind shouldBe OriginSideJobKind.MOUNTYARD
            verify { f.yard.sync(f.player, match { it.stage == OriginOddJobStage.RETURN }, any(), any()) }
            f.game.close()
            f.player.inventory.contents.filterNotNull().filter { it.type == Material.DIAMOND }.sumOf { it.amount } shouldBe 45
        }
    }
    "loaned stable supplies preserve native authored gate access and any protection denial" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_RATIONS)
            f.stroke(OriginOddJobStationId.HAY)
            val gate = f.world.getBlockAt(5, 70, 7).also { it.type = Material.OAK_FENCE_GATE }
            every { f.yard.isStallGate(gate) } returns true
            for (access in listOf(org.bukkit.event.Event.Result.ALLOW, org.bukkit.event.Event.Result.DENY)) {
                val event = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, f.player.inventory.itemInMainHand,
                    gate, BlockFace.NORTH, EquipmentSlot.HAND)
                event.setUseInteractedBlock(access)
                val itemPolicy = event.useItemInHand()
                f.game.onBlock(event)
                event.useInteractedBlock() shouldBe access
                event.useItemInHand() shouldBe itemPolicy
                f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_FEED
            }
        }
    }
    "left gate click and empty hand swing share one scoped permission check and never toggle twice" {
        f.begin(440, OriginOddJobVariant.YARD_RATIONS)
        val gate = f.world.getBlockAt(5, 70, 7).also { it.type = Material.OAK_FENCE_GATE }
        every { f.yard.isStallGate(gate) } returns true
        every { f.yard.isAssignedStallGate(f.player.uniqueId, any(), gate) } returns true
        val hit = gate.location.clone().add(.5,.5,.5)
        val at = hit.clone().add(0.0,-.5,-2.0).apply {
            setDirection(hit.toVector().subtract(clone().add(0.0,f.player.eyeHeight,0.0).toVector()))
        }
        f.player.teleport(at); f.now += 700
        f.world.obstruction = RayTraceResult(hit.toVector(), gate, BlockFace.NORTH)
        val manager = f.paper.server.pluginManager
        mockkObject(manager)
        var checks = 0
        try {
            every { manager.callEvent(any()) } answers {
                val permission = firstArg<PlayerInteractEvent>()
                permission.action shouldBe Action.RIGHT_CLICK_BLOCK
                checks++
                f.game.onBlock(permission) // Our event must not recursively dispatch itself.
            }
            val swing = PlayerArmSwingEvent(f.player, EquipmentSlot.HAND)
            f.game.onSwing(swing)
            (gate.blockData as org.bukkit.block.data.type.Gate).isOpen shouldBe true
            checks shouldBe 1
            val duplicate = PlayerInteractEvent(f.player, Action.LEFT_CLICK_BLOCK, null, gate, BlockFace.NORTH, EquipmentSlot.HAND)
            duplicate.setUseInteractedBlock(Event.Result.DENY) // Native Adventure-mode break denial is distinct from use permission.
            f.game.onBlock(duplicate)
            (gate.blockData as org.bukkit.block.data.type.Gate).isOpen shouldBe true
            checks shouldBe 1
            f.now += 700
            f.game.onBlock(PlayerInteractEvent(f.player, Action.LEFT_CLICK_BLOCK, null, gate, BlockFace.NORTH, EquipmentSlot.HAND))
            (gate.blockData as org.bukkit.block.data.type.Gate).isOpen shouldBe false
            checks shouldBe 2
        } finally { unmockkObject(manager) }
    }
    "gate use veto ownership loss and wrong gate prevent left click mutation" {
        f.begin(440, OriginOddJobVariant.YARD_RATIONS)
        val gate = f.world.getBlockAt(5, 70, 7).also { it.type = Material.OAK_FENCE_GATE }
        every { f.yard.isStallGate(gate) } returns true
        every { f.yard.isAssignedStallGate(f.player.uniqueId, any(), gate) } returns true
        val hit = gate.location.clone().add(.5,.5,.5)
        val at = hit.clone().add(0.0,-.5,-2.0).apply {
            setDirection(hit.toVector().subtract(clone().add(0.0,f.player.eyeHeight,0.0).toVector()))
        }
        f.player.teleport(at)
        f.world.obstruction = RayTraceResult(hit.toVector(), gate, BlockFace.NORTH)
        val manager = f.paper.server.pluginManager
        mockkObject(manager)
        try {
            every { manager.callEvent(any()) } answers { firstArg<PlayerInteractEvent>().setUseInteractedBlock(Event.Result.DENY) }
            f.now += 700
            f.game.onBlock(PlayerInteractEvent(f.player, Action.LEFT_CLICK_BLOCK, null, gate, BlockFace.NORTH, EquipmentSlot.HAND))
            (gate.blockData as org.bukkit.block.data.type.Gate).isOpen shouldBe false
            every { manager.callEvent(any()) } answers { f.game.stop(f.player.uniqueId, "revoked-during-permission") }
            f.now += 700
            f.game.onBlock(PlayerInteractEvent(f.player, Action.LEFT_CLICK_BLOCK, null, gate, BlockFace.NORTH, EquipmentSlot.HAND))
            (gate.blockData as org.bukkit.block.data.type.Gate).isOpen shouldBe false
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments shouldBe emptyList()
        } finally { unmockkObject(manager) }
    }
    "both horse buttons and duplicate packet events perform one stroke and one physical gear transition" {
        f.begin(440, OriginOddJobVariant.YARD_GROOM)
        f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH, left = true)
        val animal = f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId
        f.clickNpc(animal, sneaking = false, left = true)
        f.game.onNpcClick(NPCRightClickEvent(f.npc(animal), f.player))
        f.game.onEntity(PlayerInteractEntityEvent(f.player, f.entity(animal), EquipmentSlot.HAND))
        f.game.currentJob(f.player.uniqueId)!!.yard!!.brushStrokes shouldBe 1
        f.clickNpc(animal, sneaking = false)
        f.clickNpc(animal, sneaking = false, left = true)
        f.requireStage(OriginOddJobStage.YARD_BRUSH_RETURN, "three physical strokes")
        f.gearClick(left = true)
        f.requireStage(OriginOddJobStage.YARD_PAD_RACK, "brush returned")
        f.gearClick(left = false, elapsed = 0)
        f.requireStage(OriginOddJobStage.YARD_PAD_RACK, "duplicate cannot immediately take the next loan")
        f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.LEAD)
        f.payments shouldBe emptyList()
    }
    "each stirrup requires its physical side instead of clicking through the horse" {
        f.begin(440, OriginOddJobVariant.YARD_GROOM)
        f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
        val animal = f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId
        repeat(3) { f.clickNpc(animal, sneaking = false) }
        repeat(8) { f.gearClick() }
        for ((stage, left) in listOf(OriginOddJobStage.YARD_STIRRUP_LEFT to true, OriginOddJobStage.YARD_STIRRUP_RIGHT to false)) {
            f.requireStage(stage, "separate stirrup target")
            val before = f.game.currentJob(f.player.uniqueId)
            f.gearClick(left = left, oppositeSide = true)
            f.game.currentJob(f.player.uniqueId) shouldBe before
            f.gearClick(left = left)
        }
        f.requireStage(OriginOddJobStage.YARD_UNTIE_FIRST, "both sides adjusted")
        f.payments shouldBe emptyList()
    }
    "only the named animal receives grooming and the same target is part of the scene lease" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_GROOM)
            val target = f.game.currentJob(f.player.uniqueId)!!.yard!!
            f.leases.single().actorIds.contains(target.animalId) shouldBe true
            f.leases.single().actorIds shouldBe setOf(target.animalId, requireNotNull(f.config.yardRider).npcId)
            f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed.contains(OriginYardGear.BRUSH) shouldBe true
            f.clickNpc(f.config.yardAnimalIds.first { it != target.animalId }, sneaking = false)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.brushStrokes shouldBe 0
            repeat(ODD_JOB_GROOM_STROKES) { f.clickNpc(target.animalId, sneaking = false) }
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_BRUSH_RETURN
            f.payments shouldBe emptyList()
        }
    }
    "the same animal target reasserts viewer glow after an entity tracking reset" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_GROOM)
            f.tieBoth(); f.stroke(OriginOddJobStationId.BRUSH)
            val animal = f.entity(f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId)
            io.mockk.clearMocks(f.glow, answers = false)
            f.game.tick(++f.now)
            f.game.tick(++f.now)
            verify(exactly = 2) { f.glow.setEntityGlowingFor(animal, f.player, true) }
            verify(exactly = 0) { f.glow.setEntityGlowingFor(animal, f.player, false) }
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
    "clearing personal inventory does not remove or duplicate the physical bucket loan" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_WATER)
            f.stroke(OriginOddJobStationId.WELL)
            val request = f.game.currentJob(f.player.uniqueId)!!.id
            f.player.inventory.clear()
            f.game.tick(++f.now)
            f.game.currentJob(f.player.uniqueId)!!.id shouldBe request
            f.stroke(OriginOddJobStationId.WATER, left = true)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.BUCKET)
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments.none { it.id == request } shouldBe true
        }
    }
    "stable lead shows the care endpoint and only the assigned animal accepts the rope" {
        failOnUnsupportedMockBukkitOperation {
            val endpoint = Location(f.world, 4.5, 70.0, 4.5)
            every { f.yard.destination(any(), any()) } answers { endpoint.clone() }
            f.begin(440, OriginOddJobVariant.YARD_SADDLE)
            f.stroke(OriginOddJobStationId.SADDLE_RACK)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.clickNpc(if (job.yard!!.animalId == 422) 423 else 422, sneaking = false)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_LEAD
            f.clickNpc(requireNotNull(job.yard).animalId, sneaking = false)
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_LEAD_OUT
            f.target shouldBe Location(f.world, 4.5, 70.05, 4.5)
            val animal = f.entity(job.yard.animalId)
            verify { f.glow.setEntityGlowingFor(animal, f.player, true) }
        }
    }
    "cross-ties accept either first wall and require the assigned halter after each wall" {
        for (firstSide in 0..1) failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_GROOM)
            f.leadToTies()
            val animal = f.game.currentJob(f.player.uniqueId)!!.yard!!.animalId
            f.clickNpc(animal, sneaking = false)
            f.requireStage(OriginOddJobStage.YARD_TIE_FIRST, "halter cannot precede wall")
            f.strokeTie(firstSide)
            f.requireStage(OriginOddJobStage.YARD_TIE_HORSE_FIRST, "wall leaves a loose rope")
            val other = f.aimTie(1 - firstSide)
            f.game.work(f.player, false, other.block) shouldBe false
            f.clickNpc(if (animal == 422) 423 else 422, sneaking = false)
            f.requireStage(OriginOddJobStage.YARD_TIE_HORSE_FIRST, "other animal cannot take this rope")
            f.clickNpc(animal, sneaking = false)
            f.requireStage(OriginOddJobStage.YARD_TIE_SECOND, "first rope attached")
            f.strokeTie(1 - firstSide)
            f.clickNpc(animal, sneaking = false)
            f.stroke(OriginOddJobStationId.BRUSH)
            every { f.yard.readyForCare(any(), any()) } returns false
            f.clickNpc(animal, sneaking = false)
            f.game.currentJob(f.player.uniqueId)!!.yard!!.brushStrokes shouldBe 0
            f.payments shouldBe emptyList()
            every { f.yard.readyForCare(any(), any()) } returns true
            f.game.stop(f.player.uniqueId, "test")
        }
    }
    "cross ties use the authoritative lead loan even with an empty personal inventory" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_SADDLE); f.leadToTies()
            f.player.inventory.clear()
            val station = f.aimTie(0)
            f.game.work(f.player, false, station.block) shouldBe true
            f.game.currentJob(f.player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_TIE_HORSE_FIRST
            f.game.currentJob(f.player.uniqueId)!!.yard!!.borrowed shouldBe setOf(OriginYardGear.LEAD)
            f.player.inventory.contents.filterNotNull().none { it.type == Material.STRUCTURE_VOID } shouldBe true
            f.payments shouldBe emptyList()
        }
    }
    "death removes the stable service item and releases its actor without paying" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_WATER)
            f.stroke(OriginOddJobStationId.WELL)
            val job = f.game.currentJob(f.player.uniqueId)!!
            val collectedDrops = f.player.inventory.contents.filterNotNull().toMutableList()
            f.game.onDeath(mockk<org.bukkit.event.entity.PlayerDeathEvent> {
                every { entity } returns f.player
                every { drops } returns collectedDrops
            })
            collectedDrops shouldBe emptyList()
            f.game.currentJob(f.player.uniqueId) shouldBe null
            verify { f.yard.clear(f.player.uniqueId, job.id, any()) }
            f.payments shouldBe emptyList()
        }
    }
    "teleport and reload terminate stable care and close the animal owner" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_SADDLE)
            val job = f.game.currentJob(f.player.uniqueId)!!
            f.game.onTeleport(mockk<org.bukkit.event.player.PlayerTeleportEvent> { every { player } returns f.player })
            f.game.currentJob(f.player.uniqueId) shouldBe null
            verify { f.yard.clear(f.player.uniqueId, job.id, any()) }
            f.game.close()
            verify { f.yard.close() }
            f.payments shouldBe emptyList()
        }
    }
    "ambient generation revocation cleans the same stable request before releasing its lease" {
        failOnUnsupportedMockBukkitOperation {
            f.begin(440, OriginOddJobVariant.YARD_SADDLE)
            val job = f.game.currentJob(f.player.uniqueId)!!
            val lease = f.leases.single()
            f.worksiteRevocations.getValue(lease.token).invoke()
            io.mockk.verifyOrder {
                f.yard.revoke(f.player.uniqueId, job.id)
                f.yard.clear(f.player.uniqueId, job.id, any())
            }
            f.released shouldBe listOf(lease)
            f.game.currentJob(f.player.uniqueId) shouldBe null
            f.payments shouldBe emptyList()
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

internal class OddJobsFixture(deferStations: Boolean = false, doubleStocks: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val itemFactory = paper.server.itemFactory
    val world = OddJobWorld().also { it.name = "rc_origin_spawn"; paper.server.addWorld(it); it.getChunkAt(0, 0) }
    var bodyYawOverride: Float? = null
    // The fixture has no native entity sight engine; block aiming is tested separately below.
    val player = object : org.mockbukkit.mockbukkit.entity.PlayerMock(paper.server, "OddJobTest") {
        override fun hasLineOfSight(other: Entity): Boolean = world.uid == other.world.uid
        override fun getBodyYaw(): Float = bodyYawOverride ?: location.yaw
        override fun getHeight(): Double = 1.8
    }.also(paper.server::addPlayer)
    val config = oddJobTestConfig().copy(yardRider = OriginYardRider(490, UUID.fromString("00000000-0000-0000-0000-000000000490"), "Мирон"),
        yardRideAnchorIds = listOf("field-0", "field-1"))
    val yardCatalog = oddJobTestYardCatalog()
    val scheduler = TestTaskScheduler()
    val ready = SideJobRewardStatus(true, 20, 0, false)
    var nextStatus = CompletableFuture.completedFuture(ready)
    val rewards = mockk<OriginSideJobRewards>(relaxed = true)
    val visuals = mockk<OriginOddJobVisuals>(relaxed = true)
    val cartLayout = OriginForgeCartLayout(OriginWorkshopPoint(2.5, 70.0, 7.5), 90f,
        "test:cart", org.joml.Vector3f(3f), 0.5625)
    val cartDisplays = mutableListOf<ru.arc.paper.display.PacketDisplay>()
    var cartBlocksBeforeFailure: Int? = null
    private val cartRenderer = mockk<ru.arc.paper.display.PaperPacketDisplays>(relaxed = true)
    val forge = OriginOddJobForge(cartLayout, { org.bukkit.inventory.ItemStack(Material.MINECART) }, { cartRenderer })
    var target: Location? = null
    var forgeWork: OriginForgeWork? = null
    val glow = mockk<ArcOriginViewerGlow>(relaxed = true)
    val screens = mutableListOf<PaperDialogScreen>()
    var dismiss: () -> Unit = {}
    val payments = mutableListOf<Payment>()
    val settlements = mutableListOf<UUID>()
    val earned = mutableMapOf<UUID, SideJobShiftEarnings>()
    val leases = mutableListOf<OriginSceneLease>()
    val released = mutableListOf<OriginSceneLease>()
    val acquireAttempts = mutableListOf<Set<Int>>()
    val warnings = mutableListOf<String>()
    var acquisitionsBlocked = false
    var acquisitionsToReject = 0
    val worksiteRevocations = mutableMapOf<UUID, () -> Unit>()
    var leaseValid = true
    var otherWork: String? = null
    var desiredVariant: OriginOddJobVariant? = null
    var now = 1_000L
    val registry = mockk<NPCRegistry>()
    val actors = mutableMapOf<Int, NPC>()
    val yard = mockk<OriginOddJobMountyard>(relaxed = true)
    val cleaning = mockk<OriginOddJobStableCleaning>(relaxed = true)
    val equipment = mockk<OriginYardEquipment>(relaxed = true)
    private lateinit var activateGear: (Player, OriginOddJobSession, OriginYardGearStep) -> Unit
    var cleaningArrival = false
    var cleaningParkReady = true
    var lessonArrival = false
    private val cleaningStation by lazy {
        val block = world.getBlockAt(5, 69, 8)
        OriginOddJobStation(Location(world, 5.5, 70.4, 8.5), block, setOf(Material.STONE))
    }
    val tieStations = listOf(0 to -90f, 10 to 90f).map { (x, yaw) ->
        val block = world.getBlockAt(x, 71, 7).also { it.type = Material.STONE_BRICKS }
        OriginOddJobStation(Location(world, if (x == 0) 1.06 else 9.94, 71.4, 7.5, yaw, 0f), block, setOf(Material.STONE_BRICKS))
    }
    val stations: Map<OriginOddJobStationId, OriginOddJobStation>
    var resolvedStations: Map<OriginOddJobStationId, OriginOddJobStation> = emptyMap()
    var resolveAttempts = 0
    val game: OriginOddJobsGame

    init {
        for (x in 0..10) for (z in 5..10) world.getBlockAt(x, 69, z).type = Material.STONE
        fun track(display: ru.arc.paper.display.PacketDisplay) {
            var valid = true
            every { display.isValid } answers { valid }
            every { display.remove() } answers { valid = false }
            cartDisplays += display
        }
        every { cartRenderer.spawnItem(any(), any()) } answers { mockk<ru.arc.paper.display.PacketItemDisplay>(relaxed = true).also(::track) }
        every { cartRenderer.spawnBlock(any(), any()) } answers {
            cartBlocksBeforeFailure?.let { left ->
                if (left == 0) error("simulated cargo display failure")
                cartBlocksBeforeFailure = left - 1
            }
            mockk<ru.arc.paper.display.PacketBlockDisplay>(relaxed = true).also { display ->
                var data = secondArg<org.bukkit.block.data.BlockData>()
                every { display.blockData } answers { data }
                every { display.blockData = any() } answers { data = firstArg() }
                track(display)
            }
        }
        mockkStatic(CitizensAPI::class)
        mockkObject(OriginMenus, OriginServices, ArcLogging)
        every { ArcLogging.warn(any(), *anyVararg()) } answers {
            val values = args.flatMap { if (it is Array<*>) it.asList() else listOf(it) }
            warnings += values.joinToString(" | ") { if (it is Throwable) it.stackTraceToString() else it.toString() }
        }
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
        every { rewards.status(any(), any()) } answers { nextStatus }
        every { rewards.credit(any(), any(), any(), any(), any(), any()) } answers {
            val kind = firstArg<OriginSideJobKind>(); val shift = thirdArg<UUID>(); val order = arg<UUID>(3)
            val quality = arg<Int>(4); val callback = arg<(SideJobRewardResult) -> Unit>(5)
            payments += Payment(kind, order) { result ->
                if (result.outcome == SideJobRewardOutcome.CREDITED) {
                    val old = earned[shift]
                    if (order.toString() !in old?.orders.orEmpty()) earned[shift] = SideJobShiftEarnings(shift.toString(), kind,
                        old?.orders.orEmpty() + order.toString(), (old?.amount ?: 0) + 20, (old?.totalQuality ?: 0) + quality)
                }
                callback(result)
            }
        }
        every { rewards.settle(any(), any(), any()) } answers {
            val shift = secondArg<UUID>(); settlements += shift
            thirdArg<(SideJobRewardResult) -> Unit>()(SideJobRewardResult(SideJobRewardOutcome.PAID, earned[shift]))
        }
        every { visuals.target(any(), any(), any(), any(), any()) } answers { target = arg<Location?>(2)?.clone() }
        every { visuals.wallTarget(any(), any(), any(), any()) } answers { target = arg<Location>(2).clone() }
        every { visuals.destination(any(), any(), any(), any()) } answers { target = arg<Location>(2).clone() }
        every { visuals.forgeProfile(any(), any(), any(), any(), any()) } answers { forgeWork = arg(3) }
        every { visuals.forgeCarry(any(), any(), any(), any()) } answers { forgeWork = thirdArg() }
        for (id in listOf(440, 349, 351, 354, 365, 382, requireNotNull(config.yardRider).npcId) + config.yardAnimalIds) {
            val entity: LivingEntity = (if (id in config.yardAnimalIds) mockk<Horse>(relaxed = true) else mockk<Villager>(relaxed = true)).apply {
                every { world } returns this@OddJobsFixture.world
                every { location } answers { Location(this@OddJobsFixture.world, 0.0, if (id == 382) 76.0 else 70.0, if (id == 382) 8.0 else 0.0) }
                every { isValid } returns true
                every { type } returns if (id in config.yardAnimalIds) org.bukkit.entity.EntityType.HORSE else org.bukkit.entity.EntityType.VILLAGER
                every { height } returns 1.6
                every { hasLineOfSight(any<Entity>()) } returns true
            }
            actors[id] = mockk<NPC> {
                every { this@mockk.id } returns id
                every { uniqueId } returns when {
                    id == 382 -> config.handoffNpcUuid
                    id == config.yardRider.npcId -> requireNotNull(config.yardRider).npcUuid
                    id in config.yardAnimalIds -> yardCatalog.animals.first { it.npcId == id }.npcUuid
                    else -> config.venues.first { it.npcId == id }.npcUuid
                }
                every { isSpawned } returns true
                every { this@mockk.entity } returns entity
            }
        }
        every { equipment.bind(any(), any(), any(), any()) } answers { activateGear = arg(3) }
        every { equipment.available(any(), any()) } returns true
        every { equipment.target(any()) } answers {
            val job = firstArg<OriginOddJobSession>()
            originYardGearStep(job.stage)?.let { gearControl(job).nativeTarget?.location() }
        }
        // Only the packet/native entity adapter is mocked. Hit planes and request activation are production code.
        every { equipment.intercept(any(), any()) } answers {
            val player = firstArg<Player>(); val job = secondArg<OriginOddJobSession>()
            val step = originYardGearStep(job.stage)
            val centers = if (step == null) emptyList() else gearControl(job).planes.map { it.center() }
            val hit = if (centers.isEmpty()) null else originWorkshopRayAabbHit(player.eyeLocation.let { OriginWorkshopVec3(it.x,it.y,it.z) }, player.eyeLocation.direction.let { OriginWorkshopVec3(it.x,it.y,it.z) },
                OriginWorkshopAabb(centers.minOf { it.x }, centers.minOf { it.y }, centers.minOf { it.z },
                    centers.maxOf { it.x }, centers.maxOf { it.y }, centers.maxOf { it.z }), 3.0)
            if (hit != null && world.obstruction == null) { activateGear(player, job, requireNotNull(step)); true } else false
        }
        every { yard.available() } returns true
        every { yard.begin(any(), any(), any()) } returns true
        every { yard.sync(any(), any(), any(), any()) } returns true
        every { yard.readyForCare(any(), any()) } returns true
        every { yard.readyForUntack(any(), any()) } returns true
        every { yard.tieStations(any(), any()) } answers { originYardTieTargets(secondArg()).map { it to tieStations[it] } }
        every { yard.station(any(), any()) } answers { originYardTieTargets(secondArg()).firstOrNull()?.let(tieStations::get) }
        every { yard.gateTarget(any(), any()) } returns null
        every { yard.waitingForGate(any(), any()) } returns false
        every { yard.destination(any(), any()) } returns null
        every { yard.contains(any()) } answers { firstArg<Int>() in config.yardAnimalIds }
        every { yard.resolve(any()) } answers { actors[firstArg<Int>()]?.entity as? LivingEntity }
        every { yard.target(any(), any()) } answers {
            val offer = firstArg<OriginOddJobOffer>()
            val previous = secondArg<Int?>()
            val start = Math.floorMod(offer.seed, config.yardAnimalIds.size)
            val animalId = config.yardAnimalIds.indices.map { config.yardAnimalIds[(start + it) % config.yardAnimalIds.size] }
                .firstOrNull { it != previous } ?: return@answers null
            OriginYardWork(animalId, yardCatalog.animals.first { it.npcId == animalId }.name)
        }
        every { yard.tick(any(), any(), any()) } answers { when (secondArg<OriginOddJobSession>().stage) {
            OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING, OriginOddJobStage.YARD_HANDED -> OriginYardTick.CARED
            OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER -> OriginYardTick.ARRIVED
            OriginOddJobStage.YARD_LESSON_WALK -> if (lessonArrival) OriginYardTick.ARRIVED else OriginYardTick.WAITING
            else -> OriginYardTick.WAITING
        } }
        every { yard.clear(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }
        every { cleaning.available(any()) } returns true
        every { cleaning.supportsAnimal(any()) } returns true
        every { cleaning.station(any(), any()) } answers {
            if (secondArg<OriginOddJobSession>().variant == OriginOddJobVariant.YARD_CLEANUP) cleaningStation else null
        }
        every { cleaning.target(any(), any()) } answers {
            if (secondArg<OriginOddJobSession>().variant == OriginOddJobVariant.YARD_CLEANUP) cleaningStation.point.clone() else null
        }
        every { cleaning.destination(any(), any()) } returns null
        every { cleaning.canPark(any(), any()) } answers { cleaningParkReady }
        every { cleaning.tick(any(), any()) } answers {
            val job = secondArg<OriginOddJobSession>()
            if (!cleaningArrival || job.stage !in setOf(OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN)) null
            else if (job.stage == OriginOddJobStage.CLEAN_PUSH && requireNotNull(job.cleaning).collected < job.cleaning.total)
                OriginOddJobAction.PARK_CART else OriginOddJobAction.CART_ARRIVE
        }
        stations = OriginOddJobStationId.entries.mapIndexed { index, id ->
            val material = when (id) {
                OriginOddJobStationId.HAY -> Material.HAY_BLOCK
                OriginOddJobStationId.FEED -> Material.PACKED_MUD
                OriginOddJobStationId.WATER, OriginOddJobStationId.QUENCH -> Material.WATER_CAULDRON
                OriginOddJobStationId.WELL, OriginOddJobStationId.BRUSH, OriginOddJobStationId.SADDLE_RACK -> Material.BARREL
                OriginOddJobStationId.BLANK, OriginOddJobStationId.ORE_STOCK -> Material.CHEST
                OriginOddJobStationId.FIRE -> Material.FURNACE
                OriginOddJobStationId.ANVIL, OriginOddJobStationId.ENGRAVING -> Material.ANVIL
                OriginOddJobStationId.HAUL_DELIVERY -> Material.GRAVEL
            }
            val ground = id == OriginOddJobStationId.HAUL_DELIVERY
            val block = world.getBlockAt(index + 1, if (ground) 69 else 70, 3).also { it.type = material }
            val resolved = if (doubleStocks && id in setOf(OriginOddJobStationId.BLANK, OriginOddJobStationId.ORE_STOCK))
                oddJobTestDoubleChest(world, block.x, block.y, block.z, material).first() else block
            id to OriginOddJobStation(Location(world, block.x + 0.5, if (ground) 70.0 else 71.02, 3.5), resolved, setOf(material))
        }.toMap()
        game = OriginOddJobsGame(config, world.name, if (deferStations) emptyMap() else stations, rewards, visuals, LifecycleTaskScope(scheduler),
            acquire = { venue ->
                acquireAttempts += venue.actorIds
                when {
                    acquisitionsBlocked -> null
                    acquisitionsToReject > 0 -> { acquisitionsToReject--; null }
                    else -> OriginSceneLease(UUID.randomUUID(), venue.scene!!, "work", venue.actorIds).also { leases += it }
                }
            },
            owns = { leaseValid && it !in released }, release = { released += it; worksiteRevocations.remove(it.token) }, otherWork = { otherWork }, clock = { now },
            resolveStations = { resolveAttempts++; resolvedStations }, dispatch = { kind ->
                run {
                    val board = OriginOddJobDispatch(kind, kotlin.random.Random(7))
                    repeat(36) {
                        val shown = board.offers(OriginWorkShiftPhase.OPENING)
                        if (desiredVariant == null || shown.first().variant == desiredVariant) return@run board
                        board.accept(shown.first().id)
                    }
                    error("Requested test variant is outside this employer's catalog")
                }
            }, yardCatalog = yardCatalog, forge = forge, yard = yard, cleaning = cleaning, equipment = equipment,
            onWorksiteRevoked = { lease, cleanup -> worksiteRevocations[lease.token] = cleanup; true })
    }

    fun npc(id: Int) = actors.getValue(id)
    fun entity(id: Int) = npc(id).entity as LivingEntity
    fun moveNpc(id: Int) { now += 300; player.teleport(entity(id).location) }
    fun clickNpc(id: Int, sneaking: Boolean = true, left: Boolean = false) {
        val job = game.currentJob(player.uniqueId)
        if (job?.yard?.animalId == id && originYardGearStep(job.stage)?.atRack == false) { gearClick(left); return }
        moveNpc(id); player.isSneaking = sneaking
        if (left) game.onNpcLeftClick(NPCLeftClickEvent(npc(id), player))
        else game.onNpcClick(NPCRightClickEvent(npc(id), player))
    }
    private fun gearControl(job: OriginOddJobSession): ru.arc.paper.scene.SpatialControl<OriginYardGearStep> {
        val step = requireNotNull(originYardGearStep(job.stage))
        val pose = if (step.atRack) originYardGearSlots(stations).first { it.gear == step.gear }.pose
            else originYardGearHorsePose(step.gear, entity(requireNotNull(job.yard).animalId))
        return originYardGearControl(job, step, pose)
    }
    fun gearClick(left: Boolean = false, elapsed: Long = 700, oppositeSide: Boolean = false) {
        now += elapsed
        val job = requireNotNull(game.currentJob(player.uniqueId))
        val native = requireNotNull(gearControl(job).nativeTarget)
        val target = native.location().add(0.0, native.height / 2.0, 0.0)
        val at = if (oppositeSide) {
            val pose = originYardGearHorsePose(OriginYardGear.SADDLE, entity(requireNotNull(job.yard).animalId))
            val side = if (job.stage == OriginOddJobStage.YARD_STIRRUP_LEFT) 1.8f else -1.8f
            val offset = pose.rotation.transform(org.joml.Vector3f(side,0f,0f))
            Location(world,pose.at.x+offset.x,target.y-player.eyeHeight,pose.at.z+offset.z)
        } else target.clone().add(0.0, -player.eyeHeight, -1.8)
        at.setDirection(target.toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
        val event = PlayerInteractEvent(player, if(left) Action.LEFT_CLICK_AIR else Action.RIGHT_CLICK_AIR,
            player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
    }
    fun finishTack() {
        requireStage(OriginOddJobStage.YARD_BRUSH_RETURN, "brush must return first")
        repeat(10) { gearClick(left = it % 2 == 0) }
        requireStage(OriginOddJobStage.YARD_UNTIE_FIRST, "pad saddle girth bridle and both stirrups fitted")
    }
    fun returnTack() {
        val jobId = requireStage(OriginOddJobStage.YARD_UNTACK_BRIDLE, "ride ends before untacking").id
        repeat(7) { index ->
            payments.none { it.id == jobId } shouldBe true
            gearClick(left = index % 2 == 0)
        }
        game.currentJob(player.uniqueId) shouldBe null
    }
    fun open(id: Int, flush: Boolean = true, sneaking: Boolean = true) { clickNpc(id, sneaking); if (flush) scheduler.executeImmediate() }
    fun currentStation() = stations.entries.single { it.value.block.location.clone().add(0.5, 0.5, 0.5) == target || it.value.point == target }.key
    fun startForge() { begin(349, OriginOddJobVariant.FORGE_NEW) }
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
    fun leadToTies() {
        stroke(OriginOddJobStationId.SADDLE_RACK)
        clickNpc(game.currentJob(player.uniqueId)!!.yard!!.animalId, sneaking = false)
        game.tick(now)
        game.currentJob(player.uniqueId)!!.stage shouldBe OriginOddJobStage.YARD_TIE_FIRST
    }
    fun aimTie(side: Int): OriginOddJobStation {
        now += 700
        val station = tieStations[side]
        val at = station.point.clone().add(station.point.direction.multiply(1.8)).apply { y = 70.0 }
        at.setDirection(station.point.toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
        return station
    }
    fun strokeTie(side: Int = originYardTieTargets(game.currentJob(player.uniqueId)!!).first()) {
        val station = aimTie(side)
        val event = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, station.block,
            if (side == 0) BlockFace.EAST else BlockFace.WEST, EquipmentSlot.HAND)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
    }
    fun tieBoth() {
        leadToTies()
        val animal = game.currentJob(player.uniqueId)!!.yard!!.animalId
        repeat(2) { strokeTie(); clickNpc(animal, sneaking = false) }
    }
    fun strokeCart(elapsed: Long = 700) {
        val job = game.currentJob(player.uniqueId)!!
        val station = forge.station(player.uniqueId, job)!!
        if (job.stage == OriginOddJobStage.HAUL_ATTACH) {
            val collisionAvailable = try {
                station.block.collisionShape
                true
            } catch (_: org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) { false }
            org.junit.jupiter.api.Assumptions.assumeTrue(collisionAvailable,
                "MockBukkit BlockMock.getCollisionShape is unimplemented; native cart movement requires real VoxelShapes. See OriginOddJobCartTest for shared collision coverage.")
        }
        now += elapsed
        val at = station.point.clone().add(0.0, -0.65, -2.2)
        at.setDirection(station.point.clone().toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
        // A vanilla click passes through the packet cart and hits a different floor block behind it.
        val eye = player.eyeLocation
        val floorHit = eye.clone().add(eye.direction.multiply((69.999 - eye.y) / eye.direction.y)).block
        (floorHit != station.block) shouldBe true
        val event = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, floorHit, BlockFace.UP, EquipmentSlot.HAND)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
        event.useItemInHand() shouldBe Event.Result.DENY
    }
    fun strokeCleaning(left: Boolean = false, clickedGate: Boolean = false) {
        now += 700
        val point = cleaningStation.point
        val at = point.clone().add(0.0, -0.4, -2.2)
        at.setDirection(point.toVector().subtract(at.clone().add(0.0, player.eyeHeight, 0.0).toVector()))
        player.teleport(at)
        if (clickedGate) {
            val gate = world.getBlockAt(point.blockX, point.blockY, point.blockZ + 1)
            gate.type = Material.OAK_FENCE_GATE
            every { yard.isStallGate(gate) } returns true
            val event = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.inventory.itemInMainHand, gate, BlockFace.NORTH, EquipmentSlot.HAND)
            game.onBlock(event)
            event.useInteractedBlock() shouldBe Event.Result.DENY
            event.useItemInHand() shouldBe Event.Result.DENY
        } else game.work(player, left, null) shouldBe true
    }
    fun dragCart(step: Double = 0.22) {
        val stage = OriginOddJobStage.HAUL_PUSH
        val start = player.location
        val destination = stations.getValue(OriginOddJobStationId.HAUL_DELIVERY).point.clone().add(0.0, 0.0, -1.0)
        val distance = kotlin.math.hypot(destination.x - start.x, destination.z - start.z)
        val ticks = kotlin.math.ceil(distance / step).toInt().coerceAtLeast(1)
        repeat(ticks) { tick ->
            if (game.currentJob(player.uniqueId)?.stage != stage) return
            now += 50
            val part = (tick + 1.0) / ticks
            player.teleport(Location(world, start.x + (destination.x - start.x) * part, destination.y,
                start.z + (destination.z - start.z) * part, 0f, 0f))
            scheduler.tick()
        }
        (game.currentJob(player.uniqueId)?.stage != stage) shouldBe true
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
        if (originYardGearStep(game.currentJob(player.uniqueId)?.stage ?: OriginOddJobStage.PAYOUT) != null) { gearClick(left, elapsed); return }
        now += elapsed
        aim(id, zone)
        val event = blockEvent(id, left = left)
        game.onBlock(event)
        event.useInteractedBlock() shouldBe Event.Result.DENY
        event.useItemInHand() shouldBe Event.Result.DENY
    }
    fun plain(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun actionBars(): List<String> = generateSequence { player.nextActionBar() }.map(::plain).toList()
    fun requireStage(stage: OriginOddJobStage, context: String): OriginOddJobSession {
        val job = game.currentJob(player.uniqueId)
        check(job?.stage == stage) {
            "$context: expected=$stage current=$job now=$now working=${game.isWorking(player.uniqueId)} " +
                "leases=${leases.size} released=${released.size} leaseValid=$leaseValid payments=${payments.size} " +
                "mode=${player.gameMode} online=${player.isOnline} dead=${player.isDead} health=${player.health} at=${player.location} " +
                "forgeAvailable=${forge.available(world)} stations=${stations.mapValues { (_, station) -> "${station.valid()}/${station.block.type}@${station.block.location}" }} " +
                "actionBars=${actionBars()} warnings=$warnings"
        }
        return requireNotNull(job)
    }
    override fun close() {
        game.close()
        unmockkObject(OriginMenus, OriginServices, ArcLogging)
        unmockkObject(itemFactory)
        unmockkStatic(CitizensAPI::class)
        paper.close()
    }
    data class Payment(val kind: OriginSideJobKind, val id: UUID, val complete: (SideJobRewardResult) -> Unit) {
        fun callback(outcome: SideJobRewardOutcome) = complete(SideJobRewardResult(outcome))
    }
}

private class ModelPreservingItemMeta(source: ItemMeta) : ItemMetaMock(source) {
    init { itemModel = source.itemModel }
    override fun clone(): ItemMetaMock = ModelPreservingItemMeta(this)
}

/** MockBukkit has no block ray tracer; the fixture supplies the observed occlusion result. */
internal class OddJobWorld : WorldMock() {
    var obstruction: RayTraceResult? = null
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? = obstruction
}
