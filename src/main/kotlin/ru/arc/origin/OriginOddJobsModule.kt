package ru.arc.origin

import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.menu.OriginMenus
import ru.arc.origin.mountyard.OriginMountYardAnimalCatalog
import ru.arc.origin.mountyard.OriginMountYardAnimalsConfig
import ru.arc.origin.scene.OriginAmbientScenesModule
import ru.arc.origin.scene.OriginSceneLease
import ru.arc.origin.scene.OriginScenePlan
import java.util.UUID

/** Three local work loops; ambient scenes own actors and the shared reward owner owns money. */
object OriginOddJobsModule : PluginModule {
    override val name = "OriginOddJobs"
    override val priority = 85
    private var game: OriginOddJobsGame? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        val path = ArcOriginPlugin.instance.legacyDataPath
        val config = OriginOddJobsConfig.load(ConfigManager.ofModule(path, "origin-odd-jobs.yml"),
            ArcOriginPlugin.instance.sideJobRewardPolicy)
        originClearAuctionJobHologram(config.venue(OriginOddJobKind.AUCTION))
        if (!config.enabled) return
        val plan = OriginScenePlan.load(path)
        val world = Bukkit.getWorld(plan.world) ?: return
        val stations = originOddJobStations(plan, world)
        game = OriginOddJobsGame(config, plan.world, stations,
            resolveStations = { originOddJobStations(plan, world) },
            yardCatalog = OriginMountYardAnimalsConfig.load(path).catalog).also {
            Bukkit.getPluginManager().registerEvents(it, ArcOriginPlugin.instance)
        }
        info("ORIGIN_ODD_JOBS phase=READY world={} stations={}", plan.world,
            OriginOddJobStationId.entries.joinToString(",") { id -> stations[id]?.let {
                "$id=${it.block.type}@${it.point.x},${it.point.y},${it.point.z}"
            } ?: "$id=unavailable(block-or-loaded-chunk-missing)" })
    }

    internal fun isWorking(playerId: UUID): Boolean = game?.isWorking(playerId) == true

    override fun reload() { shutdown(); init() }

    override fun shutdown() {
        game?.let { HandlerList.unregisterAll(it); it.close() }
        game = null
    }
}

internal fun originClearAuctionJobHologram(venue: OriginOddJobVenue) {
    val npc = CitizensAPI.getNPCRegistry().getById(venue.npcId)
        ?.takeIf { it.uniqueId == venue.npcUuid } ?: return
    OriginNpcHolograms.patchHologram(npc.id, emptyList(), viewRange = -1)
}

/** The same hit volume covers the small station bracket, its label and the real clicked block. */
internal fun originOddJobAimed(player: Player, station: OriginOddJobStation, range: Double, clicked: Block?): Boolean {
    if (player.world.uid != station.point.world?.uid) return false
    if (clicked != null && station.accessBlocks.any { it == clicked && it.type == org.bukkit.Material.IRON_BARS }) {
        val hit = player.world.rayTraceBlocks(player.eyeLocation, player.eyeLocation.direction, range, FluidCollisionMode.NEVER, true)?.hitBlock
        return hit == clicked
    }
    if (clicked != null && (clicked.world.uid != station.block.world.uid || clicked.x != station.block.x ||
            clicked.y != station.block.y || clicked.z != station.block.z)) return false
    val eye = player.eyeLocation
    val ray = eye.direction
    val p = station.point
    val distance = originWorkshopRayAabbHit(OriginWorkshopVec3(eye.x, eye.y, eye.z), OriginWorkshopVec3(ray.x, ray.y, ray.z),
        OriginWorkshopAabb(p.x - 0.65, p.y - 0.8, p.z - 0.65, p.x + 0.65, p.y + 0.95, p.z + 0.65), range) ?: return false
    val blocker = player.world.rayTraceBlocks(eye, ray, distance + 0.02, FluidCollisionMode.NEVER, true)?.hitBlock ?: return true
    return blocker.world.uid == station.block.world.uid && blocker.x == station.block.x &&
        blocker.y == station.block.y && blocker.z == station.block.z ||
        station.accessBlocks.any { it == blocker && it.type == org.bukkit.Material.IRON_BARS }
}

internal class OriginOddJobsGame(
    private val config: OriginOddJobsConfig,
    private val worldName: String,
    stations: Map<OriginOddJobStationId, OriginOddJobStation>,
    private val rewards: OriginSideJobRewards = OriginSideJobRewards(policy = config.rewardPolicy),
    private val visuals: OriginOddJobVisuals = OriginOddJobVisuals(),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val acquire: (OriginOddJobVenue) -> OriginSceneLease? = { venue ->
        venue.scene?.let { OriginAmbientScenesModule.acquireWorksite(it, venue.actorIds) }
    },
    private val owns: (OriginSceneLease) -> Boolean = OriginAmbientScenesModule::ownsWorksite,
    private val release: (OriginSceneLease) -> Unit = OriginAmbientScenesModule::releaseWorksite,
    private val otherWork: (UUID) -> String? = { playerId -> when {
        OriginWorkshopGame.isWorking(playerId) -> "workshop-active"
        OriginDiningModule.isWaiterWorking(playerId) -> "waiter-active"
        else -> null
    } },
    private val clock: () -> Long = System::currentTimeMillis,
    private val resolveStations: (() -> Map<OriginOddJobStationId, OriginOddJobStation>)? = null,
    private val dispatch: (OriginOddJobKind) -> OriginOddJobDispatch = { OriginOddJobDispatch(it) },
    yardCatalog: OriginMountYardAnimalCatalog = OriginMountYardAnimalCatalog(emptyList()),
) : Listener, AutoCloseable {
    private val stations = stations.toMutableMap()
    private val progress = OriginOddJobsProgress(config.lifetimeMillis)
    private val requests = mutableMapOf<UUID, UUID>()
    private val leases = mutableMapOf<UUID, OriginSceneLease>()
    private val lastClicks = mutableMapOf<UUID, Long>()
    private val noticeUntil = mutableMapOf<UUID, Long>()
    private val props = mutableMapOf<UUID, Pair<OriginJobProp?, Boolean>>()
    private val targets = mutableMapOf<UUID, OriginOddJobStationId>()
    private val renderedForge = mutableMapOf<UUID, Pair<OriginForgeWork, Boolean>>()
    private val renderedCargo = mutableMapOf<UUID, OriginForgeCargo>()
    private val renderedEngraving = mutableMapOf<UUID, Pair<OriginForgeEngraving, Boolean>>()
    private data class Shift(val venue: OriginOddJobVenue, var score: OriginWorkShift, val dispatch: OriginOddJobDispatch,
        var settling: Boolean = false)
    private data class Accepted(val job: OriginOddJobSession, val shiftId: UUID, val quality: Int, var nextAttempt: Long = 0, var inFlight: Boolean = false)
    private val shifts = mutableMapOf<UUID, Shift>()
    private val accepted = mutableMapOf<UUID, Accepted>()
    private val glowing = mutableMapOf<UUID, LivingEntity>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private val supplies = OriginOddJobSupplies { config.line(it) }
    private val yard = OriginOddJobMountyard(worldName, yardCatalog, config.yardAnimalIds, config.yardFeedingAnimalId)
    private val retryAt = mutableMapOf<UUID, Long>()
    private var closed = false

    init {
        rewards.recover(OriginSideJobKind.entries.filter { it != OriginSideJobKind.WAITER }.toSet())
        Bukkit.getOnlinePlayers().forEach { supplies.clear(it) }
        tasks.runTimer(1, 1) {
            try { visuals.tick() } catch (failure: Exception) {
                warn("ORIGIN_ODD_JOBS phase=VISUAL_TICK_FAILED", failure)
                progress.entries().forEach { (id, _) -> stop(id, "unavailable"); Bukkit.getPlayer(id)?.let { feedback(it, "unavailable") } }
            }
        }
        tasks.runTimer(4, 4) { tick(clock()) }
    }

    fun isWorking(playerId: UUID): Boolean = playerId in shifts || playerId in accepted
    internal fun currentJob(playerId: UUID): OriginOddJobSession? = progress.current(playerId)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onNpcClick(event: NPCRightClickEvent) {
        if (interact(event.clicker, event.npc.id)) {
            event.isCancelled = true
            event.setDelayedCancellation(true)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntity(event: PlayerInteractEntityEvent) {
        if (event is PlayerInteractAtEntityEvent || event.hand != EquipmentSlot.HAND) return
        if (entityInteraction(event.player, event.rightClicked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEntityAt(event: PlayerInteractAtEntityEvent) {
        if (event.hand == EquipmentSlot.HAND && entityInteraction(event.player, event.rightClicked)) event.isCancelled = true
    }

    private fun entityInteraction(player: Player, entity: Entity): Boolean =
        CitizensAPI.getNPCRegistry().getNPC(entity)?.let { interact(player, it.id) } ?: false

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlock(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val left = event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR
        if (!left && event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        if (work(player = event.player, left = left, clicked = event.clickedBlock) || supplies.isWorkpiece(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (event.hand == EquipmentSlot.HAND && work(event.player, left = true, clicked = null)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { stop(event.player.uniqueId, "quit") }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        supplies.clear(event.player)
        val id = event.player.uniqueId; val token = tasks.token()
        rewards.status(id).whenComplete { state, _ -> tasks.runSync(token) {
            if (!closed && !isWorking(id) && otherWork(id) == null)
                state?.recoverableShiftId?.let { rewards.settle(id, it) {} }
        } }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(supplies::isWorkpiece)
        stop(event.entity.uniqueId, "death")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrop(event: PlayerDropItemEvent) {
        if (supplies.isWorkpiece(event.itemDrop.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (supplies.isWorkpiece(event.mainHandItem) || supplies.isWorkpiece(event.offHandItem)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) {
        val job = progress.current(event.player.uniqueId) ?: return
        if (job.kind == OriginOddJobKind.MOUNTYARD && supplies.requiresWorkpiece(job) &&
            supplies.isWorkpiece(event.player.inventory.itemInMainHand)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val stock = supplies.stock(event.view)
        if (stock != null) {
            event.isCancelled = true
            if (stock.playerId != player.uniqueId || event.rawSlot != 4 || !supplies.isWorkpiece(event.currentItem)) return
            val job = progress.current(player.uniqueId)?.takeIf { it.id == stock.jobId && it.stage in setOf(OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.ENGRAVE_BLANK) } ?: return
            val station = stations[OriginOddJobStationId.BLANK] ?: return
            if (!valid(player, job) || player.eyeLocation.distanceSquared(station.point) > config.range * config.range) return
            if (!supplies.hasSpace(player)) { feedback(player, "inventory-full"); return }
            // Bukkit inventory mutation/closure runs after the click transaction has completed.
            tasks.runLater(1) {
                val current = progress.current(player.uniqueId)?.takeIf { it.id == job.id && it.stage == job.stage } ?: return@runLater
                if (supplies.stock(player.openInventory) !== stock || !valid(player, current) ||
                    player.eyeLocation.distanceSquared(station.point) > config.range * config.range || !supplies.hasSpace(player)) return@runLater
                stock.storage.setItem(4, null)
                apply(player, progress.act(player.uniqueId, OriginOddJobAction.BLANK, clock()))
            }
            return
        }
        val hotbar = event.hotbarButton.takeIf { it in 0..8 }?.let(player.inventory::getItem)
        if (supplies.isWorkpiece(event.currentItem) || supplies.isWorkpiece(event.cursor) || supplies.isWorkpiece(hotbar) ||
            event.click == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && supplies.isWorkpiece(player.inventory.itemInOffHand)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (supplies.stock(event.view) != null || supplies.isWorkpiece(event.oldCursor)) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val stock = event.inventory.holder as? OriginOddJobSupplies.Stock ?: return
        (event.player as? Player)?.let { supplies.closeStock(it, stock) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { stop(event.player.uniqueId, "teleport") }

    fun interact(player: Player, npcId: Int): Boolean {
        if (closed) return false
        val playerId = player.uniqueId
        val current = progress.current(playerId)
        val shift = shifts[playerId]
        if (current?.kind == OriginOddJobKind.MOUNTYARD && yard.contains(npcId)) return workAnimal(player, current, npcId)
        val venue = config.venues.firstOrNull { it.npcId == npcId }
        if (venue?.kind == OriginOddJobKind.AUCTION) return false
        val handoff = current?.kind == OriginOddJobKind.AUCTION && npcId == config.handoffNpcId
        if (venue == null && !handoff || actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (duplicate(player)) return true
        if (current != null && !valid(player, current)) { stop(playerId, "unavailable"); feedback(player, "unavailable"); return true }
        if (shift != null && venue != null && shift.venue.kind != venue.kind) { feedback(player, "other-job"); return true }
        if (handoff) {
            if (current.stage != OriginOddJobStage.AUCTION_RECEIPT) apply(player, progress.act(playerId, OriginOddJobAction.HANDOFF, clock()))
            if (progress.current(playerId)?.stage == OriginOddJobStage.AUCTION_RECEIPT) {
                OriginMenus.beginDialogFlow(player); showWork(player)
            }
        } else {
            if (current?.stage in setOf(OriginOddJobStage.RETURN, OriginOddJobStage.AUCTION_DOCUMENT)) {
                apply(player, progress.act(playerId, OriginOddJobAction.RETURN, clock()))
                return true
            }
            OriginMenus.beginDialogFlow(player)
            if (shift == null) status(player, requireNotNull(venue), start = false) else show(player)
        }
        return true
    }

    private fun status(player: Player, venue: OriginOddJobVenue, start: Boolean) {
        if (closed || !near(player, venue.npcId) || isWorking(player.uniqueId)) return
        if (!workMode(player)) { feedback(player, "work-mode"); return }
        otherWork(player.uniqueId)?.let { feedback(player, it); return }
        if (!available(venue.kind, refresh = true)) { feedback(player, "unavailable"); return }
        val playerId = player.uniqueId
        if (playerId in requests) { feedback(player, "checking"); return }
        val request = UUID.randomUUID(); requests[playerId] = request
        val epoch = tasks.token()
        if (!start) OriginMenus.openDialog(player, originOddJobsScreen(config, venue, null, rewards.amount, null, clock()),
            onDismiss = { requests.remove(playerId, request) })
        rewards.status(playerId).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(playerId, request) || closed || !near(player, venue.npcId) || isWorking(playerId)) return@runSync
            if (otherWork(playerId) != null) { feedback(player, requireNotNull(otherWork(playerId))); return@runSync }
            if (state?.recoverableShiftId != null) {
                rewards.settle(playerId, state.recoverableShiftId) { result ->
                    if (!closed && !isWorking(playerId) && otherWork(playerId) == null && near(player, venue.npcId)) {
                        if (result.outcome in setOf(SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID)) status(player, venue, start)
                        else feedback(player, if (result.outcome == SideJobRewardOutcome.UNCONFIRMED) "unconfirmed" else "cash-unavailable")
                    }
                }
                return@runSync
            }
            val key = if (failure != null || state == null) "cash-unavailable" else originOddJobStatusKey(state, clock())
            if (!start) {
                OriginMenus.openDialog(player, originOddJobsScreen(config, venue, null, rewards.amount, state, clock(),
                    notice = if (failure != null) config.line("cash-unavailable") else null,
                    start = { status(player, venue, true) },
                    openShop = if (venue.kind == OriginOddJobKind.FORGE_HAUL) ({
                        if (!closed && near(player, venue.npcId) && !isWorking(playerId)) player.performCommand("shop Ores")
                    }) else null), reopen = { status(player, venue, false) })
                return@runSync
            }
            val rejected = otherWork(playerId) ?: key ?: when {
                !workMode(player) -> "work-mode"
                !available(venue.kind, refresh = true) -> "unavailable"
                else -> null
            }
            if (rejected != null) { feedback(player, rejected, "seconds" to waiterSeconds((state?.nextAtMillis ?: clock()) - clock())); return@runSync }
            val score = OriginWorkShift.begin(clock(), config.shiftMillis)
            val shift = Shift(venue, score, dispatch(venue.kind))
            shifts[playerId] = shift
            info("ORIGIN_ODD_JOBS phase=SHIFT_STARTED player={} shift={} kind={}", playerId, score.id, venue.kind)
            nextOrder(player, shift)
        } }
    }

    private fun nextOrder(player: Player, shift: Shift) {
        val now = clock()
        if (!shift.score.acceptsOrders(now)) { finishIfReady(player.uniqueId, now); return }
        accept(player, shift.score.id, shift.dispatch.offers(shift.score.phase(now)).first().id)
    }

    private fun accept(player: Player, shiftId: UUID, offerId: UUID) {
        val id = player.uniqueId; val shift = shifts[id]?.takeIf { it.score.id == shiftId } ?: return
        if (closed || !shift.score.acceptsOrders(clock()) || id in accepted || progress.current(id) != null || !inArea(player, shift.venue.kind)) return
        val offer = shift.dispatch.offers(shift.score.phase(clock())).firstOrNull { it.id == offerId } ?: return
        if (!workMode(player) || otherWork(id) != null || !available(shift.venue.kind, refresh = true)) { feedback(player, "unavailable"); return }
        if (id in requests) return
        val request = UUID.randomUUID(); requests[id] = request; val epoch = tasks.token()
        rewards.status(id, shift.score.id).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(id, request) || closed || shifts[id] !== shift || !shift.score.acceptsOrders(clock()) ||
                progress.current(id) != null || id in accepted || !inArea(player, shift.venue.kind)) return@runSync
            val gate = when {
                failure != null || state == null || !state.available -> "cash-unavailable"
                state.unresolved -> "unconfirmed"
                state.remainingOrders <= 0 -> "daily-limit"
                !workMode(player) || otherWork(id) != null || !available(shift.venue.kind, refresh = true) -> "unavailable"
                else -> null
            }
            if (gate != null) { feedback(player, gate); finishRequested(player); return@runSync }
            val yardTarget = if (offer.kind == OriginOddJobKind.MOUNTYARD) yard.target(offer) else null
            if (offer.kind == OriginOddJobKind.MOUNTYARD && yardTarget == null) { feedback(player, "unavailable"); return@runSync }
            val leasedVenue = if (yardTarget != null) shift.venue.copy(actorIds = shift.venue.actorIds + yardTarget.animalId) else shift.venue
            val lease = if (shift.venue.scene != null) acquire(leasedVenue) else null
            if (shift.venue.scene != null && lease == null) { retryAt[id] = clock() + 3_000; feedback(player, "busy"); return@runSync }
            if (shift.dispatch.accept(offerId) == null) { lease?.let(release); return@runSync }
            val job = progress.begin(id, offer, clock(), shift.score.hardStopAt, yardTarget)
            if (job == null) { lease?.let(release); return@runSync }
            if (lease != null) leases[id] = lease
            if (!render(player, job, clock())) return@runSync
            info("ORIGIN_ODD_JOBS phase=STARTED player={} shift={} request={} kind={} variant={}", id, shiftId, job.id, job.kind, job.variant)
            guide(player, job, clock(), true)
        } }
    }

    private fun show(player: Player, notice: String? = null) {
        val id = player.uniqueId; val shift = shifts[id] ?: return; val job = progress.current(id)
        if (closed || !near(player, shift.venue.npcId)) return
        OriginMenus.openDialog(player, originOddJobsScreen(config, shift.venue, job, rewards.amount, null, clock(), notice,
            shift = shift.score, paymentGuide = paymentGuide(id, clock()),
            resume = {
                if (shifts[id] === shift && near(player, shift.venue.npcId)) {
                    if (job?.kind == OriginOddJobKind.AUCTION && originOddJobChoices(config, job).isNotEmpty()) showWork(player)
                    else job?.let { guide(player, it, clock(), true) }
                }
            },
            finishShift = { if (shifts[id] === shift) finishRequested(player) }), reopen = { if (shifts[id] === shift) show(player) })
    }

    private fun showWork(player: Player, notice: String? = null) {
        val job = progress.current(player.uniqueId) ?: return
        if (closed || !valid(player, job) || !contextNear(player, job, config.venue(job.kind))) return
        OriginMenus.openDialog(player, originOddJobWorkScreen(config, job, clock(), notice,
            choose = { choose(player, job.id, it) }), reopen = { showWork(player) })
    }

    private fun contextNear(player: Player, job: OriginOddJobSession?, venue: OriginOddJobVenue): Boolean {
        if (job == null) return near(player, venue.npcId)
        val station = originOddJobStation(job.stage)?.let(stations::get)
        if (station != null) return player.world.uid == station.point.world?.uid && player.eyeLocation.distanceSquared(station.point) <= config.range * config.range
        return near(player, if (job.stage == OriginOddJobStage.AUCTION_RECEIPT) config.handoffNpcId else venue.npcId)
    }

    private fun choose(player: Player, requestId: UUID?, choice: Int) {
        val job = progress.current(player.uniqueId)?.takeIf { it.id == requestId } ?: return
        if (!valid(player, job) || !contextNear(player, job, config.venue(job.kind))) return
        val station = originOddJobStation(job.stage)?.let(stations::get)
        if (station != null && !originOddJobAimed(player, station, config.range, null)) return
        val step = progress.choose(player.uniqueId, choice, clock()); apply(player, step)
        val current = progress.current(player.uniqueId)
        if (current?.kind == OriginOddJobKind.AUCTION && originOddJobChoices(config, current).isNotEmpty())
            showWork(player, (step as? OriginOddJobStep.Rejected)?.let { config.line(rejectionKey(it.reason)) })
    }

    /** Every intercepted action still passes the actual block, reach and line-of-sight checks. */
    fun work(player: Player, left: Boolean, clicked: Block?): Boolean {
        val job = progress.current(player.uniqueId) ?: return false
        val wanted = originOddJobStation(job.stage)
        val station = wanted?.let(stations::get) ?: return false
        if (!originOddJobAimed(player, station, config.range, clicked)) return false
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (duplicate(player)) return true
        if (job.stage in setOf(OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.ENGRAVE_BLANK)) {
            if (!left) supplies.openStock(player, job, station.block)
            return true
        }
        if (!supplies.canTransfer(player, job)) { feedback(player, "inventory-full"); return true }
        val action = when (job.stage) {
            OriginOddJobStage.YARD_HAY -> OriginOddJobAction.HAY
            OriginOddJobStage.YARD_FEED -> OriginOddJobAction.FEED
            OriginOddJobStage.YARD_WELL, OriginOddJobStage.YARD_WATER -> OriginOddJobAction.WATER
            OriginOddJobStage.YARD_BRUSH -> OriginOddJobAction.BRUSH
            OriginOddJobStage.YARD_TACK -> OriginOddJobAction.TACK
            OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT -> OriginOddJobAction.BLANK
            OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobAction.HEAT
            OriginOddJobStage.FORGE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.FORGE_HAMMER -> OriginOddJobAction.HAMMER
            OriginOddJobStage.FORGE_QUENCH -> OriginOddJobAction.QUENCH
            OriginOddJobStage.HAUL_PICKUP -> OriginOddJobAction.PICKUP
            OriginOddJobStage.HAUL_DELIVER -> OriginOddJobAction.DELIVER
            OriginOddJobStage.ENGRAVE_BLANK -> OriginOddJobAction.BLANK
            OriginOddJobStage.ENGRAVE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.ENGRAVE_CARVE -> OriginOddJobAction.ENGRAVE
            OriginOddJobStage.ENGRAVE_COLLECT -> OriginOddJobAction.COLLECT
            else -> return false
        }
        if ((job.stage in setOf(OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.ENGRAVE_CARVE)) != left) { guide(player, job, clock(), true); return true }
        val step = progress.act(player.uniqueId, action, clock(), originOddJobZone(player, station))
        apply(player, step)
        if (step is OriginOddJobStep.Changed && action in setOf(OriginOddJobAction.HAMMER, OriginOddJobAction.ENGRAVE))
            player.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, station.point.clone().add(0.0, 0.12, 0.0), 5, 0.14, 0.04, 0.14, 0.02)
        if (job.kind == OriginOddJobKind.MOUNTYARD && progress.current(player.uniqueId)?.stage != job.stage) {
            player.swingMainHand()
            when (job.stage) {
                OriginOddJobStage.YARD_WELL -> player.playSound(station.point, Sound.ITEM_BUCKET_FILL, 0.6f, 1f)
                OriginOddJobStage.YARD_WATER -> {
                    player.playSound(station.point, Sound.ITEM_BUCKET_EMPTY, 0.65f, 1f)
                    player.spawnParticle(org.bukkit.Particle.SPLASH, station.point, 12, 0.25, 0.1, 0.25)
                }
                OriginOddJobStage.YARD_FEED -> player.playSound(station.point, Sound.ENTITY_HORSE_EAT, 0.6f, 1f)
                else -> Unit
            }
        }
        return true
    }

    private fun workAnimal(player: Player, job: OriginOddJobSession, npcId: Int): Boolean {
        if (actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (duplicate(player)) return true
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (job.yard?.animalId != npcId || job.stage !in setOf(OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE)) {
            feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, job, clock())); return true
        }
        val action = if (job.stage == OriginOddJobStage.YARD_GROOM) OriginOddJobAction.GROOM else OriginOddJobAction.SADDLE
        val step = progress.act(player.uniqueId, action, clock())
        apply(player, step)
        if (step is OriginOddJobStep.Changed && progress.current(player.uniqueId)?.id == job.id) {
            if (action == OriginOddJobAction.GROOM) yard.stroke(player, step.job) else yard.saddle(player, step.job)
        }
        return true
    }

    private fun apply(player: Player, step: OriginOddJobStep) {
        when (step) {
            is OriginOddJobStep.Rejected -> {
                if (step.reason == OriginOddJobRejection.EXPIRED) { cancelOrder(player.uniqueId, "expired"); finishIfReady(player.uniqueId, clock()) }
                else step.job?.let { if (!render(player, it, clock())) return }
                feedback(player, if (step.reason == OriginOddJobRejection.TOO_EARLY && step.job?.stage in setOf(OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.ENGRAVE_CARVE))
                    "forge.hammer-wait" else rejectionKey(step.reason))
            }
            is OriginOddJobStep.Changed -> {
                if (step.job.stage == OriginOddJobStage.PAYOUT) { acceptResult(player, step.job); return }
                if (!supplies.sync(player, step.job)) {
                    stop(player.uniqueId, "workpiece-missing"); feedback(player, "unavailable"); return
                }
                if (!render(player, step.job, clock())) return
                guide(player, step.job, clock(), true)
                val sound = when {
                    step.job.stage in setOf(OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.FORGE_QUENCH,
                        OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT) -> Sound.BLOCK_ANVIL_USE
                    step.job.kind == OriginOddJobKind.FORGE_HAUL -> Sound.BLOCK_IRON_PLACE
                    step.job.stage == OriginOddJobStage.RETURN && step.job.kind == OriginOddJobKind.FORGE -> Sound.BLOCK_FIRE_EXTINGUISH
                    else -> Sound.UI_BUTTON_CLICK
                }
                player.playSound(player.location, sound,
                    SoundCategory.PLAYERS, 0.3f, 1.1f)
            }
        }
    }

    private fun acceptResult(player: Player, job: OriginOddJobSession) {
        val id = player.uniqueId; val shift = shifts[id] ?: return
        if (accepted.containsKey(id) || progress.finish(id, job.id) == null) return
        // The accepted result outlives its scene lease, position and shift deadline.
        accepted[id] = Accepted(job, shift.score.id, job.finalQuality(clock()))
        cleanup(id, job.id)
        pay(id, accepted.getValue(id))
    }

    private fun pay(id: UUID, result: Accepted) {
        fun feedback(key: String, vararg values: Pair<String, Any>) {
            Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.let { this@OriginOddJobsGame.feedback(it, key, *values) }
        }
        if (closed || accepted[id] !== result || result.inFlight || clock() < result.nextAttempt) return
        result.inFlight = true; feedback("paying")
        rewards.credit(result.job.kind.rewardKind, id, result.shiftId, result.job.id, result.quality) onPaid@ { payment ->
            if (accepted[id] !== result) return@onPaid
            result.inFlight = false
            val outcome = payment.outcome
            if (outcome == SideJobRewardOutcome.RETRY) {
                result.nextAttempt = clock() + 1_000
                info("ORIGIN_ODD_JOBS phase=CREDIT_RETRY player={} request={} kind={} retryAt={}",
                    id, result.job.id, result.job.kind, result.nextAttempt)
                return@onPaid
            }
            accepted.remove(id, result)
            val shift = shifts[id]?.takeIf { it.score.id == result.shiftId }
            if (outcome == SideJobRewardOutcome.CREDITED) {
                if (shift != null) shift.score = shift.score.record(result.job.id, rewards.amount, result.quality)
                feedback("credited", "amount" to (shift?.score?.earnedAmount ?: rewards.amount), "quality" to result.quality)
            } else {
                if (shift != null) shift.score = shift.score.requestFinish()
                feedback(when (outcome) {
                    SideJobRewardOutcome.DAILY_LIMIT -> "daily-limit"
                    SideJobRewardOutcome.UNCONFIRMED -> "unconfirmed"
                    SideJobRewardOutcome.UNAVAILABLE -> "cash-unavailable"
                    else -> "cancelled"
                })
            }
            info("ORIGIN_ODD_JOBS phase=FINISHED player={} request={} kind={} variant={} outcome={} quality={}",
                id, result.job.id, result.job.kind, result.job.variant, outcome, result.quality)
            finishIfReady(id, clock())
        }
    }

    private fun finishRequested(player: Player) {
        val shift = shifts[player.uniqueId] ?: return
        shift.score = shift.score.requestFinish()
        cancelOrder(player.uniqueId, "shift-finished")
        finishIfReady(player.uniqueId, clock())
    }

    private fun finishIfReady(id: UUID, now: Long) {
        val shift = shifts[id] ?: return
        if (now >= shift.score.hardStopAt) cancelOrder(id, "expired")
        if (id in accepted || progress.current(id) != null || !shift.score.shouldFinish(now, false)) return
        if (shift.settling) return
        shift.settling = true
        Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.let { feedback(it, "settling") }
        rewards.settle(id, shift.score.id) { result ->
            if (closed || shifts[id] !== shift) return@settle
            if (result.outcome == SideJobRewardOutcome.RETRY) { shift.settling = false; return@settle }
            completeShift(id, shift, result)
        }
    }

    private fun completeShift(id: UUID, shift: Shift, result: SideJobRewardResult) {
        shifts.remove(id, shift); requests.remove(id); lastClicks.remove(id)
        clearGlow(id); clearBar(id); retryAt.remove(id)
        val confirmed = result.outcome in setOf(SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID)
        val score = if (confirmed) shift.score.confirmed(result.earnings) else shift.score
        Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.sendMessage(net.kyori.adventure.text.Component.newline()
            .append(waiterText("  " + config.line("shift.receipt-title"), TextColor.color(0xFFB277))).appendNewline()
            .append(waiterText("  " + config.line(if (confirmed) "shift.receipt-score" else "shift.receipt-pending",
                "done" to score.completedOrders, "amount" to score.earnedAmount), TextColor.color(if (confirmed) 0x9BD48D else 0xE8DFD2))).appendNewline())
        info("ORIGIN_ODD_JOBS phase=SHIFT_FINISHED player={} shift={} orders={} amount={} outcome={}", id, shift.score.id, score.completedOrders, score.earnedAmount, result.outcome)
    }

    fun tick(now: Long) {
        accepted.toList().forEach { (id, result) -> pay(id, result) }
        shifts.toList().forEach { (id, shift) ->
            val player = Bukkit.getPlayer(id); val job = progress.current(id)
            when {
                player == null || !player.isOnline -> stop(id, "offline")
                now >= shift.score.hardStopAt -> { cancelOrder(id, "expired"); shift.score = shift.score.requestFinish() }
                !inArea(player, shift.venue.kind) -> { cancelOrder(id, "left-area"); shift.score = shift.score.requestFinish(); feedback(player, "left-area") }
                job != null && (!valid(player, job) || now >= job.expiresAt) -> { cancelOrder(id, "expired"); feedback(player, "expired") }
                // Stable work is accepted on the next tick, after its final care feedback.
                job?.kind == OriginOddJobKind.MOUNTYARD && job.stage == OriginOddJobStage.RETURN ->
                    apply(player, progress.act(id, OriginOddJobAction.RETURN, now))
                job != null && render(player, job, now) -> guide(player, job, now)
                job == null && id !in accepted && id !in requests && (retryAt[id] ?: 0) <= now -> {
                    if (shift.score.acceptsOrders(now)) nextOrder(player, shift)
                }
            }
            if (player != null && player.isOnline) updateBar(player, progress.current(id), now)
            finishIfReady(id, now)
        }
    }

    private fun render(player: Player, job: OriginOddJobSession, now: Long): Boolean = try {
        val prop = when (job.stage) {
            OriginOddJobStage.FORGE_HEAT -> if (job.variant == OriginOddJobVariant.FORGE_NEW) OriginJobProp.FORGE_BLANK else OriginJobProp.FORGE_COOLED
            OriginOddJobStage.FORGE_HEATING -> if (now - job.stageStartedAt >= originOddJobHeatReady(job)) OriginJobProp.FORGE_HEATED else OriginJobProp.FORGE_BLANK
            OriginOddJobStage.FORGE_PLACE -> OriginJobProp.FORGE_HEATED
            OriginOddJobStage.FORGE_HAMMER -> originOddJobNewForgeShape(job)
            OriginOddJobStage.FORGE_INSPECT -> OriginJobProp.FORGE_COOLED
            OriginOddJobStage.FORGE_QUENCH -> OriginJobProp.FORGE_HORSESHOE
            OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK -> when (job.parcel) {
                OriginAuctionParcel.VASE -> OriginJobProp.AUCTION_VASE
                OriginAuctionParcel.BOOK -> OriginJobProp.AUCTION_BOOK
                else -> OriginJobProp.AUCTION_BLADE
            }
            OriginOddJobStage.AUCTION_LOAD, OriginOddJobStage.AUCTION_DELIVER -> OriginJobProp.AUCTION_PACKAGE
            OriginOddJobStage.RETURN -> when {
                job.kind == OriginOddJobKind.FORGE -> OriginJobProp.FORGE_COOLED
                job.variant == OriginOddJobVariant.YARD_RATIONS -> OriginJobProp.HAY
                else -> null
            }
            else -> null
        }
        val placedAt = when (job.stage) {
            OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HAMMER -> OriginOddJobStationId.ANVIL
            OriginOddJobStage.FORGE_HEATING -> OriginOddJobStationId.FIRE
            OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT -> OriginOddJobStationId.ENGRAVING
            OriginOddJobStage.RETURN -> if (job.variant == OriginOddJobVariant.YARD_RATIONS) OriginOddJobStationId.FEED else null
            else -> null
        }
        val placed = placedAt != null
        val engraving = job.engraving?.takeIf { job.stage in setOf(OriginOddJobStage.ENGRAVE_PLACE,
            OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT, OriginOddJobStage.RETURN) }
        val forgeProfile = job.forge?.takeIf { job.variant != OriginOddJobVariant.FORGE_NEW && (job.stage in setOf(OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HAMMER,
            OriginOddJobStage.FORGE_QUENCH, OriginOddJobStage.RETURN) ||
            job.stage in setOf(OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING, OriginOddJobStage.FORGE_PLACE) &&
                job.variant != OriginOddJobVariant.FORGE_NEW) }
        if (props[player.uniqueId] != (prop to placed)) {
            if (prop == null && engraving == null) { visuals.clear(player.uniqueId, job.id); targets.remove(player.uniqueId) }
            else if (prop != null && forgeProfile == null) {
                if (placedAt == OriginOddJobStationId.FIRE) visuals.hang(player, job.id, stations.getValue(placedAt).point, prop)
                else if (placedAt != null) visuals.place(player, job.id, stations.getValue(placedAt).point, prop)
                else visuals.carry(player, job.id, prop)
            }
            props[player.uniqueId] = prop to placed; renderedForge.remove(player.uniqueId); renderedEngraving.remove(player.uniqueId)
        }
        if (forgeProfile != null) {
            val heated = when (job.stage) {
                OriginOddJobStage.FORGE_HEATING -> now - job.stageStartedAt >= originOddJobHeatReady(job)
                OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.FORGE_QUENCH -> true
                else -> false
            }
            if (renderedForge[player.uniqueId] != (forgeProfile to heated)) {
                if (placedAt == OriginOddJobStationId.FIRE) visuals.hangForge(player, job.id, stations.getValue(placedAt).point, forgeProfile, heated)
                else if (placedAt != null) visuals.forgeProfile(player, job.id, stations.getValue(placedAt).point, forgeProfile, heated)
                else visuals.forgeCarry(player, job.id, forgeProfile, heated)
                renderedForge[player.uniqueId] = forgeProfile to heated
            }
        }
        if (job.stage == OriginOddJobStage.HAUL_DELIVER) {
            val cargo = requireNotNull(job.haul).cargo
            if (renderedCargo[player.uniqueId] != cargo) { visuals.cargo(player, job.id, cargo); renderedCargo[player.uniqueId] = cargo }
        } else if (renderedCargo.remove(player.uniqueId) != null) visuals.clear(player.uniqueId, job.id)
        if (engraving != null && renderedEngraving[player.uniqueId] != (engraving to placed)) {
            visuals.engraving(player, job.id, placedAt?.let { stations.getValue(it).point }, engraving)
            renderedEngraving[player.uniqueId] = engraving to placed
        }
        val stationId = originOddJobStation(job.stage)
        val caption = when (job.stage) {
            OriginOddJobStage.FORGE_HEATING, OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.FORGE_INSPECT,
            OriginOddJobStage.HAUL_PICKUP, OriginOddJobStage.HAUL_DELIVER,
            OriginOddJobStage.ENGRAVE_BLANK, OriginOddJobStage.ENGRAVE_PLACE, OriginOddJobStage.ENGRAVE_CARVE,
            OriginOddJobStage.ENGRAVE_COLLECT -> originOddJobGuide(config, job, now)
            else -> if (job.kind == OriginOddJobKind.MOUNTYARD) originOddJobGuide(config, job, now)
                else stationId?.let { config.line("targets.${it.name.lowercase()}") }.orEmpty()
        }
        // Update the label even at the same station when its current operation changes.
        val workpieceTarget = originOddJobWorkpieceTarget(job.stage)
        visuals.target(player, job.id, stationId?.let { id -> stations.getValue(id).let { station ->
            if (workpieceTarget) station.point else station.block.location.add(0.5, 0.5, 0.5)
        } }, caption, outline = !workpieceTarget)
        visuals.glowWorkpiece(player, job.id, originOddJobWorkpieceGlows(job, now))
        if (stationId == null) targets.remove(player.uniqueId) else targets[player.uniqueId] = stationId
        val npcId = when (job.stage) {
            OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE -> job.yard?.animalId
            OriginOddJobStage.AUCTION_VERIFY, OriginOddJobStage.AUCTION_DELIVER, OriginOddJobStage.AUCTION_RECEIPT -> config.handoffNpcId
            OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK,
            OriginOddJobStage.AUCTION_LOAD, OriginOddJobStage.AUCTION_DOCUMENT -> config.venue(job.kind).npcId
            OriginOddJobStage.RETURN -> if (job.kind == OriginOddJobKind.MOUNTYARD) null else config.venue(job.kind).npcId
            else -> null
        }
        glow(player, npcId)
        true
    } catch (failure: Exception) {
        warn("ORIGIN_ODD_JOBS phase=VISUAL_FAILED player={} request={}", player.uniqueId, job.id, failure)
        stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); false
    }

    private fun guide(player: Player, job: OriginOddJobSession, now: Long, @Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        updateBar(player, job, now)
    }

    private fun updateBar(player: Player, job: OriginOddJobSession?, now: Long) {
        val shift = shifts[player.uniqueId]?.score ?: return
        val ready = job != null && (originOddJobHammerReady(job, now) || job.stage == OriginOddJobStage.FORGE_HEATING &&
            now - job.stageStartedAt >= originOddJobHeatReady(job))
        val caption = config.line("shift.hud", "guide" to (job?.let { originOddJobGuide(config, it, now) }
            ?: paymentGuide(player.uniqueId, now) ?: config.line("shift.assigning")),
            "clock" to originOddJobClock((if (shift.acceptsOrders(now)) shift.closesAt else shift.hardStopAt) - now),
            "done" to shift.completedOrders, "amount" to shift.earnedAmount, "quality" to (job?.quality ?: 100))
        val bar = bars.getOrPut(player.uniqueId) {
            BossBar.bossBar(waiterText(caption), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS).also(player::showBossBar)
        }
        bar.name(waiterText(caption, TextColor.color(0xE8DFD2)))
        bar.progress(job?.let { originOddJobProgress(it, now) } ?: 0f)
        bar.color(if (ready) BossBar.Color.GREEN else BossBar.Color.YELLOW)
    }

    private fun paymentGuide(id: UUID, now: Long): String? = when {
        shifts[id]?.settling == true -> config.line("settling")
        id in accepted -> config.line("paying")
        else -> null
    }

    private fun clearBar(id: UUID) { bars.remove(id)?.let { Bukkit.getPlayer(id)?.hideBossBar(it) } }

    private fun rejectionKey(reason: OriginOddJobRejection): String = "errors.${reason.name.lowercase().replace('_', '-')}"

    private fun available(kind: OriginOddJobKind, refresh: Boolean = false): Boolean {
        if (actor(config.venue(kind).npcId) == null || kind == OriginOddJobKind.AUCTION && actor(config.handoffNpcId) == null) return false
        if (kind == OriginOddJobKind.MOUNTYARD && !yard.available()) return false
        val required = originOddJobRequiredStations(kind)
        if (refresh && resolveStations != null && required.any { stations[it]?.valid() != true } &&
            progress.entries().none { it.second.kind == kind }) {
            // Admission only; do not load chunks or retarget an active order.
            val resolved = resolveStations(); required.forEach { stations.remove(it) }; stations.putAll(resolved.filterKeys { it in required })
            if (required.all { stations[it]?.valid() == true }) info("ORIGIN_ODD_JOBS phase=STATIONS_RESOLVED kind={} stations={}", kind,
                required.joinToString(",") { id -> stations.getValue(id).let { "$id=${it.block.type}@${it.point.x},${it.point.y},${it.point.z}" } })
        }
        return required.all { stations[it]?.valid() == true }
    }
    private fun valid(player: Player, job: OriginOddJobSession): Boolean = workMode(player) && inArea(player, job.kind) && available(job.kind) &&
        (!supplies.requiresWorkpiece(job) || supplies.has(player, job)) &&
        (config.venue(job.kind).scene == null || leases[player.uniqueId]?.let(owns) == true)
    private fun workMode(player: Player): Boolean = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE
    private fun inArea(player: Player, kind: OriginOddJobKind): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        val npc = actor(config.venue(kind).npcId) ?: return false
        return player.location.distanceSquared(npc.location) <= config.workRange * config.workRange
    }
    private fun actor(id: Int): LivingEntity? {
        if (yard.contains(id)) return yard.resolve(id)
        val expected = if (id == config.handoffNpcId) config.handoffNpcUuid else config.venues.firstOrNull { it.npcId == id }?.npcUuid
        return CitizensAPI.getNPCRegistry().getById(id)?.takeIf { it.uniqueId == expected && it.isSpawned && it.entity.world.name == worldName }?.entity as? LivingEntity
    }
    private fun near(player: Player, id: Int): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        return actor(id)?.let { player.location.distanceSquared(it.location) <= config.range * config.range } == true
    }
    private fun duplicate(player: Player): Boolean {
        val now = clock(); val previous = lastClicks.put(player.uniqueId, now)
        return previous != null && now - previous in 0..249
    }
    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) {
        noticeUntil[player.uniqueId] = clock() + 1_500
        player.sendActionBar(waiterText(config.line(key, *values), TextColor.color(if (key == "credited") 0x9BD48D else 0xFFCA82)))
    }
    private fun glow(player: Player, npcId: Int?) {
        val entity = npcId?.let(::actor)
        if (glowing[player.uniqueId] !== entity) clearGlow(player.uniqueId)
        // A tracking destroy may clear the provider's selection while this domain target stays the same.
        if (entity != null) { OriginServices.viewerGlow.setEntityGlowingFor(entity, player, true); glowing[player.uniqueId] = entity }
    }
    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        Bukkit.getPlayer(playerId)?.let { if (entity.isValid) OriginServices.viewerGlow.setEntityGlowingFor(entity, it, false) }
    }
    private fun cleanup(playerId: UUID, requestId: UUID) {
        try {
            runCatching { yard.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=YARD_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { visuals.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=VISUAL_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { clearGlow(playerId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=GLOW_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
        } finally {
            Bukkit.getPlayer(playerId)?.let { supplies.clear(it) }
            props.remove(playerId); targets.remove(playerId); renderedForge.remove(playerId); noticeUntil.remove(playerId)
            renderedCargo.remove(playerId); renderedEngraving.remove(playerId)
            leases.remove(playerId)?.let(release)
        }
    }
    private fun cancelOrder(playerId: UUID, reason: String) {
        val job = progress.cancel(playerId) ?: return
        cleanup(playerId, job.id)
        info("ORIGIN_ODD_JOBS phase=CANCELLED player={} request={} kind={} reason={}", playerId, job.id, job.kind, reason)
    }
    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId); lastClicks.remove(playerId); retryAt.remove(playerId); cancelOrder(playerId, reason)
        clearBar(playerId); Bukkit.getPlayer(playerId)?.let { supplies.clear(it) }
        runCatching { clearGlow(playerId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=GLOW_CLEAR_FAILED player={} reason={}", playerId, reason, it) }
        if (reason == "reload") { accepted.remove(playerId); shifts.remove(playerId) }
        else {
            shifts[playerId]?.let { it.score = it.score.requestFinish() }
            finishIfReady(playerId, clock())
        }
    }
    override fun close() {
        accepted.forEach { (id, result) ->
            if (!result.inFlight) rewards.credit(result.job.kind.rewardKind, id, result.shiftId, result.job.id, result.quality) {}
        }
        closed = true; tasks.close(); requests.clear()
        shifts.keys.toList().forEach { stop(it, "reload") }
        accepted.clear(); visuals.close(); rewards.close(); lastClicks.clear(); noticeUntil.clear(); retryAt.clear()
    }
}

/** Three broad bands on the actual supporting plane, never a second hidden interaction volume. */
internal fun originOddJobZone(player: Player, station: OriginOddJobStation): Int {
    val eye = player.eyeLocation; val ray = eye.direction
    if (kotlin.math.abs(ray.y) < 0.001) return 1
    val travel = (station.point.y - eye.y) / ray.y
    val x = eye.x + ray.x * travel - station.point.x
    return when { x < -0.16 -> 0; x > 0.16 -> 2; else -> 1 }
}
