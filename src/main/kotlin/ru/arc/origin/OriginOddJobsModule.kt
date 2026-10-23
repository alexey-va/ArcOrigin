package ru.arc.origin

import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCLeftClickEvent
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
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
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
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
import ru.arc.origin.scene.OriginSceneDefinition
import java.util.UUID

private const val ODD_JOB_ASSIGNMENT_TIMEOUT_MILLIS = 15_000L

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
        val plan = OriginScenePlan.load(path, mergeDefaults = false)
        val world = Bukkit.getWorld(plan.world) ?: return
        val stations = originOddJobStations(plan, world)
        val forgeCart = OriginForgeCartLayout.from(plan.scene("forge"))
        game = OriginOddJobsGame(config, plan.world, stations,
            resolveStations = { originOddJobStations(plan, world) },
            yardCatalog = OriginMountYardAnimalsConfig.load(path, mergeDefaults = false).catalog,
            yardScene = plan.scene("mount-yard"),
            forge = OriginOddJobForge(forgeCart),
            cleaning = OriginOddJobStableCleaning(OriginStableCleaningLayout.from(plan.scene("mount-yard"), forgeCart))).also {
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

/** Stock selection uses the physical container volume, independent of its display anchor. */
internal fun originOddJobStationInReach(player: Player, station: OriginOddJobStation, range: Double): Boolean {
    if (player.world.uid != station.point.world?.uid) return false
    val eye = player.eyeLocation
    if (station.block.type !in setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL))
        return eye.distanceSquared(station.point) <= range * range
    val blocks = originOddJobStationBlocks(station.block)
    return blocks.any { block ->
        val dx = eye.x - eye.x.coerceIn(block.x.toDouble(), block.x + 1.0)
        val dy = eye.y - eye.y.coerceIn(block.y.toDouble(), block.y + 1.0)
        val dz = eye.z - eye.z.coerceIn(block.z.toDouble(), block.z + 1.0)
        dx * dx + dy * dy + dz * dz <= range * range
    }
}

/** The same hit volume covers the small station bracket, its label and the real clicked block. */
internal fun originOddJobAimed(player: Player, station: OriginOddJobStation, range: Double, clicked: Block?, virtualTarget: Boolean = false): Boolean {
    if (player.world.uid != station.point.world?.uid) return false
    if (!virtualTarget && clicked != null && station.accessBlocks.any { it == clicked && it.type == org.bukkit.Material.IRON_BARS }) {
        val hit = player.world.rayTraceBlocks(player.eyeLocation, player.eyeLocation.direction, range, FluidCollisionMode.NEVER, true)?.hitBlock
        return hit == clicked
    }
    val blocks = if (virtualTarget) listOf(station.block) else originOddJobStationBlocks(station.block)
    fun contains(block: Block): Boolean = blocks.any { it.world.uid == block.world.uid && it.x == block.x && it.y == block.y && it.z == block.z }
    if (!virtualTarget && clicked != null && !contains(clicked)) return false
    if (!virtualTarget && clicked != null && blocks.size == 2) {
        val eye = player.eyeLocation
        val hit = player.world.rayTraceBlocks(eye, eye.direction, range, FluidCollisionMode.NEVER, true) ?: return false
        return hit.hitBlock?.let(::contains) == true && hit.hitPosition.distanceSquared(eye.toVector()) <= range * range
    }
    val eye = player.eyeLocation
    val ray = eye.direction
    val p = station.point
    val distance = originWorkshopRayAabbHit(OriginWorkshopVec3(eye.x, eye.y, eye.z), OriginWorkshopVec3(ray.x, ray.y, ray.z),
        station.interactionBounds ?: OriginWorkshopAabb(p.x - 0.65 + blocks.minOf { it.x - station.block.x }, p.y - 0.8,
            p.z - 0.65 + blocks.minOf { it.z - station.block.z }, p.x + 0.65 + blocks.maxOf { it.x - station.block.x }, p.y + 0.95,
            p.z + 0.65 + blocks.maxOf { it.z - station.block.z }), range) ?: return false
    val blocker = player.world.rayTraceBlocks(eye, ray, distance + 0.02, FluidCollisionMode.NEVER, true)?.hitBlock ?: return true
    if (virtualTarget) return false // Packet props have no native clicked block; only earlier world collisions obstruct them.
    return contains(blocker) ||
        station.accessBlocks.any { it == blocker && it.type == org.bukkit.Material.IRON_BARS }
}

internal class OriginOddJobsGame(
    private val config: OriginOddJobsConfig,
    private val worldName: String,
    stations: Map<OriginOddJobStationId, OriginOddJobStation>,
    private val rewards: OriginSideJobRewards = OriginSideJobRewards(policy = config.rewardPolicy,
        captureRecovery = { step -> ArcOriginPlugin.instance.capture({ true }, step) },
        quests = ArcOriginPlugin.instance.questProgress),
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
    yardScene: OriginSceneDefinition? = null,
    private val forge: OriginOddJobForge = OriginOddJobForge(),
    private val cleaning: OriginOddJobStableCleaning = OriginOddJobStableCleaning(),
    private val yard: OriginOddJobMountyard = OriginOddJobMountyard(worldName, yardCatalog, config.yardAnimalIds,
        config.yardRider, yardScene, rideAnchorIds = config.yardRideAnchorIds,
        deliveryAnchorIds = config.yardDeliveryAnchorIds,
        gateAnchorIds = config.yardGateAnchorIds, feedingMillis = config.yardFeedingMillis,
        routeTimeoutMillis = config.yardRouteTimeoutMillis, clock = clock),
    private val equipment: OriginYardEquipment = OriginYardEquipment(),
    private val onWorksiteRevoked: (OriginSceneLease, () -> Unit) -> Boolean = OriginAmbientScenesModule::onWorksiteRevoked,
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
        var settling: Boolean = false, var assigningSince: Long? = null,
        val lastYardAnimals: MutableMap<OriginOddJobVariant, Int> = mutableMapOf(), var assignmentFailure: String = "no-available-offer", val unavailableOffers: MutableSet<UUID> = mutableSetOf())
    private data class Accepted(val job: OriginOddJobSession, val shiftId: UUID, val quality: Int, var nextAttempt: Long = 0, var inFlight: Boolean = false)
    private data class AcceptedWorkUnit(val playerId: UUID, val requestId: UUID, val jobId: UUID,
        val shiftId: UUID, val quality: Int, var nextAttempt: Long = 0, var inFlight: Boolean = false)
    private val shifts = mutableMapOf<UUID, Shift>()
    private val accepted = mutableMapOf<UUID, Accepted>()
    private val workUnits = mutableMapOf<UUID, AcceptedWorkUnit>()
    private val glowing = mutableMapOf<UUID, LivingEntity>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private val supplies = OriginOddJobSupplies { config.line(it) }
    private val retryAt = mutableMapOf<UUID, Long>()
    private var closed = false
    private var gatePermissionEvent: PlayerInteractEvent? = null

    init {
        equipment.bind({ this.stations }, { progress.entries() }, yard::resolve, ::activateYardEquipment)
        rewards.recover(OriginSideJobKind.entries.filter { it != OriginSideJobKind.WAITER }.toSet())
        Bukkit.getOnlinePlayers().forEach { supplies.clear(it) }
        tasks.runTimer(1, 1) {
            try {
                visuals.tick()
                equipment.tick()
                progress.entries().filter { it.second.kind == OriginOddJobKind.FORGE_HAUL }.forEach { (id, job) ->
                    Bukkit.getPlayer(id)?.let { handoffHaul(it, job) }
                }
                progress.entries().filter { it.second.variant == OriginOddJobVariant.YARD_CLEANUP }.forEach { (id, job) ->
                    Bukkit.getPlayer(id)?.let { handoffCleaning(it, job) }
                }
            } catch (failure: Exception) {
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
    fun onNpcLeftClick(event: NPCLeftClickEvent) {
        if (interact(event.clicker, event.npc.id)) event.isCancelled = true
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
        if (event === gatePermissionEvent) return
        if (event.hand != EquipmentSlot.HAND) return
        val left = event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR
        if (!left && event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        // Private request stock and packet props are job actions. Their lease, ray and reach are
        // rechecked by work(); cancelling this event still prevents any native container access.
        val yardJob = progress.current(event.player.uniqueId)?.takeIf { it.kind == OriginOddJobKind.MOUNTYARD }
        if (left && yardJob != null && event.clickedBlock?.let(yard::isStallGate) == true) {
            // A packet tool may be in front of the gate; its actual rendered hit wins.
            if (work(event.player, left = true, clicked = event.clickedBlock)) event.isCancelled = true
            else leftClickYardGate(event, yardJob)
            return
        }
        if (!left && progress.current(event.player.uniqueId)?.kind == OriginOddJobKind.MOUNTYARD &&
            event.clickedBlock?.let(yard::isStallGate) == true) {
            if (progress.current(event.player.uniqueId)?.variant == OriginOddJobVariant.YARD_CLEANUP &&
                work(event.player, left = false, clicked = event.clickedBlock)) {
                event.isCancelled = true
                return
            }
            // Preserve native block access and any protection denial, while suppressing use of the loaned tool.
            if (supplies.isWorkpiece(event.item)) event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            if (yardJob != null && yard.isAssignedStallGate(event.player.uniqueId, yardJob, requireNotNull(event.clickedBlock)) &&
                valid(event.player, yardJob) && duplicate(event.player)) event.isCancelled = true
            return
        }
        if (work(player = event.player, left = left, clicked = event.clickedBlock) || supplies.isWorkpiece(event.item)) event.isCancelled = true
    }

    private fun leftClickYardGate(event: PlayerInteractEvent, job: OriginOddJobSession) {
        val block = event.clickedBlock ?: return
        if (!yard.isAssignedStallGate(event.player.uniqueId, job, block) || !valid(event.player, job)) return
        val before = block.blockData as? org.bukkit.block.data.type.Gate ?: return
        val station = OriginOddJobStation(block.location.clone().add(.5,.5,.5), block, setOf(block.type))
        if (!originOddJobAimed(event.player, station, config.range, block)) return
        event.isCancelled = true // The job's left click must never start breaking the gate.
        if (duplicate(event.player)) return
        val permission = PlayerInteractEvent(event.player, Action.RIGHT_CLICK_BLOCK, event.item, block,
            event.blockFace, EquipmentSlot.HAND)
        try {
            gatePermissionEvent = permission
            Bukkit.getPluginManager().callEvent(permission)
        } finally { gatePermissionEvent = null }
        if (permission.useInteractedBlock() == org.bukkit.event.Event.Result.DENY ||
            progress.current(event.player.uniqueId) != job || !valid(event.player, job)) return
        val current = block.blockData as? org.bukkit.block.data.type.Gate ?: return
        if (current.isOpen != before.isOpen) return // A listener already performed the permitted interaction.
        current.isOpen = !current.isOpen
        block.blockData = current
        event.player.swingMainHand()
        event.player.world.playSound(block.location, if (current.isOpen) Sound.BLOCK_FENCE_GATE_OPEN else Sound.BLOCK_FENCE_GATE_CLOSE, .7f, 1f)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (work(event.player, left = true, clicked = null)) { event.isCancelled = true; return }
        val job = progress.current(event.player.uniqueId)?.takeIf { it.kind == OriginOddJobKind.MOUNTYARD } ?: return
        val eye = event.player.eyeLocation
        val hit = event.player.world.rayTraceBlocks(eye, eye.direction, config.range, FluidCollisionMode.NEVER, true) ?: return
        val block = hit.hitBlock ?: return
        val click = PlayerInteractEvent(event.player, Action.LEFT_CLICK_BLOCK, event.player.inventory.itemInMainHand,
            block, hit.hitBlockFace ?: org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND)
        leftClickYardGate(click, job)
        if (click.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) event.isCancelled = true
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
    fun onUnleash(event: EntityUnleashEvent) {
        val id = CitizensAPI.getNPCRegistry().getNPC(event.entity)?.id ?: return
        if (yard.ownsAnimal(id)) event.isDropLeash = false
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerUnleash(event: PlayerUnleashEntityEvent) {
        val id = CitizensAPI.getNPCRegistry().getNPC(event.entity)?.id ?: return
        if (yard.ownsAnimal(id) && !yard.workerLeading(event.player.uniqueId, id)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerLeash(event: PlayerLeashEntityEvent) {
        val id = CitizensAPI.getNPCRegistry().getNPC(event.entity)?.id ?: return
        if (yard.ownsAnimal(id) && !yard.workerLeading(event.player.uniqueId, id)) event.isCancelled = true
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
            if (!valid(player, job) || !originOddJobStationInReach(player, station, config.range)) return
            if (!supplies.hasSpace(player)) { feedback(player, "inventory-full"); return }
            // Bukkit inventory mutation/closure runs after the click transaction has completed.
            tasks.runLater(1) {
                val current = progress.current(player.uniqueId)?.takeIf { it.id == job.id && it.stage == job.stage } ?: return@runLater
                if (supplies.stock(player.openInventory) !== stock || !valid(player, current) ||
                    !originOddJobStationInReach(player, station, config.range) || !supplies.hasSpace(player)) return@runLater
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
        // Edgar owns the forge shift; Savva and Luka remain its leased work actors.
        if (venue?.kind in setOf(OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE)) return false
        val handoff = current?.kind == OriginOddJobKind.AUCTION && npcId == config.handoffNpcId
        val yardHandoff = current?.kind == OriginOddJobKind.MOUNTYARD && npcId == config.yardRider?.npcId
        if (venue == null && !handoff && !yardHandoff || actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (duplicate(player)) return true
        if (current != null && !valid(player, current)) { stop(playerId, "unavailable"); feedback(player, "unavailable"); return true }
        if (shift != null && venue != null && shift.venue.kind != venue.kind) { feedback(player, "other-job"); return true }
        if (yardHandoff) {
            if (current.stage != OriginOddJobStage.YARD_HANDOFF) {
                feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, requireNotNull(current), clock()))
                return true
            }
            if (!handoffYard(player, current))
                feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, current, clock()))
            return true
        }
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
        otherWork(player.uniqueId)?.let { feedback(player, it); return }
        if (!available(venue.kind, refresh = true)) { feedback(player, "unavailable"); return }
        val playerId = player.uniqueId
        if (playerId in requests) { feedback(player, "checking"); return }
        val request = UUID.randomUUID(); requests[playerId] = request
        val epoch = tasks.token()
        tasks.runLater(ODD_JOB_ASSIGNMENT_TIMEOUT_MILLIS / 50) {
            if (requests.remove(playerId, request)) {
                warn("ORIGIN_ODD_JOBS phase=ADMISSION_FAILED player={} npc={} kind={} reason=status-timeout", playerId, venue.npcId, venue.kind)
                feedback(player, "cash-unavailable")
            }
        }
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
        val id = player.uniqueId; val now = clock()
        if (closed || shifts[id] !== shift || id in accepted || pendingWork(id) || progress.current(id) != null) return
        if (!shift.score.acceptsOrders(now)) { finishIfReady(id, now); return }
        if (shift.assigningSince == null) shift.assigningSince = now
        if (assignmentExpired(player, shift, now) || id in requests || (retryAt[id] ?: 0) > now) return
        val phase = shift.score.phase(now)
        val offer = shift.dispatch.nextAvailable(phase) { candidate ->
            when {
                candidate.id in shift.unavailableOffers -> false
                !available(candidate.kind, refresh = true) -> { shift.assignmentFailure = "workplace-unavailable"; false }
                !variantAvailable(player, candidate) -> { shift.assignmentFailure = "activity-unavailable"; false }
                candidate.kind == OriginOddJobKind.MOUNTYARD && yardTarget(candidate, shift) == null -> {
                    shift.assignmentFailure = "animal-unavailable"; false
                }
                else -> true
            }
        }
        if (offer == null) {
            shift.unavailableOffers.clear()
            retryAt[id] = now + 1_000
            return
        }
        accept(player, shift.score.id, offer.id)
    }

    private fun assignmentExpired(player: Player, shift: Shift, now: Long): Boolean {
        val id = player.uniqueId
        if (shifts[id] !== shift || progress.current(id) != null || id in accepted) return false
        val started = shift.assigningSince ?: return false
        if (now - started < ODD_JOB_ASSIGNMENT_TIMEOUT_MILLIS) return false
        warn("ORIGIN_ODD_JOBS phase=ASSIGNMENT_FAILED player={} shift={} npc={} kind={} reason={} waited_ms={}",
            id, shift.score.id, shift.venue.npcId, shift.venue.kind, shift.assignmentFailure, now - started)
        requests.remove(id); retryAt.remove(id)
        finishRequested(player)
        feedback(player, if (shift.assignmentFailure == "status-pending") "cash-unavailable" else "unavailable")
        return true
    }

    private fun retryAssignment(id: UUID, shift: Shift, offer: OriginOddJobOffer, reason: String, delayMillis: Long = 1_000) {
        shift.unavailableOffers += offer.id
        shift.assignmentFailure = reason
        retryAt[id] = clock() + delayMillis
    }

    private fun accept(player: Player, shiftId: UUID, offerId: UUID) {
        val id = player.uniqueId; val shift = shifts[id]?.takeIf { it.score.id == shiftId } ?: return
        if (closed || !shift.score.acceptsOrders(clock()) || id in accepted || progress.current(id) != null || !inArea(player, shift.venue.kind)) return
        val offer = shift.dispatch.offers(shift.score.phase(clock())).firstOrNull { it.id == offerId } ?: return
        val rejected = otherWork(id)
        if (rejected != null) { finishRequested(player); feedback(player, rejected); return }
        if (!available(offer.kind, refresh = true)) { retryAssignment(id, shift, offer, "workplace-unavailable"); return }
        if (id in requests) return
        val request = UUID.randomUUID(); requests[id] = request; val epoch = tasks.token()
        shift.assignmentFailure = "status-pending"
        rewards.status(id, shift.score.id).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(id, request) || closed || shifts[id] !== shift || !shift.score.acceptsOrders(clock()) ||
                progress.current(id) != null || id in accepted || !inArea(player, shift.venue.kind)) return@runSync
            if (assignmentExpired(player, shift, clock())) return@runSync
            val gate = when {
                failure != null || state == null || !state.available -> "cash-unavailable"
                state.unresolved -> "unconfirmed"
                state.remainingOrders <= 0 -> "daily-limit"
                otherWork(id) != null -> otherWork(id)
                else -> null
            }
            if (gate != null) { finishRequested(player); feedback(player, gate); return@runSync }
            if (!available(offer.kind, refresh = true) || !variantAvailable(player, offer)) { retryAssignment(id, shift, offer, "workplace-unavailable"); return@runSync }
            val yardTarget = if (offer.kind == OriginOddJobKind.MOUNTYARD) yardTarget(offer, shift)?.let {
                if (offer.variant == OriginOddJobVariant.YARD_LESSON) it.copy(routeTotal = config.yardRideAnchorIds.size + 1) else it
            } else null
            if (offer.kind == OriginOddJobKind.MOUNTYARD && yardTarget == null) { retryAssignment(id, shift, offer, "animal-unavailable"); return@runSync }
            if (offer.variant == OriginOddJobVariant.YARD_CLEANUP && !cleaning.supportsAnimal(requireNotNull(yardTarget).animalId)) {
                retryAssignment(id, shift, offer, "stall-unavailable"); return@runSync
            }
            val leasedActors = if (offer.kind == OriginOddJobKind.MOUNTYARD)
                setOf(requireNotNull(yardTarget).animalId) + listOfNotNull(config.yardRider?.npcId?.takeIf { offer.variant.isYardPreparation() })
                else shift.venue.actorIds + config.venue(offer.kind).actorIds
            val leasedVenue = shift.venue.copy(actorIds = leasedActors)
            val lease = if (shift.venue.scene != null) acquire(leasedVenue) else null
            if (shift.venue.scene != null && lease == null) {
                retryAssignment(id, shift, offer, "worksite-busy", 3_000); feedback(player, "busy"); return@runSync
            }
            if (shift.dispatch.accept(offerId) == null) { lease?.let(release); retryAssignment(id, shift, offer, "offer-unavailable"); return@runSync }
            val job = progress.begin(id, offer, clock(), shift.score.hardStopAt, yardTarget, shift.venue.kind)
            if (job == null) { lease?.let(release); retryAssignment(id, shift, offer, "session-unavailable"); return@runSync }
            if (lease != null) leases[id] = lease
            if (job.kind == OriginOddJobKind.MOUNTYARD && job.variant != OriginOddJobVariant.YARD_CLEANUP &&
                (!yard.begin(player, job) { lease == null || owns(lease) } || !syncYard(player, job))) {
                cancelOrder(id, "animal-unavailable"); feedback(player, "unavailable"); return@runSync
            }
            if (job.kind == OriginOddJobKind.MOUNTYARD && lease != null && !onWorksiteRevoked(lease) {
                yard.revoke(id, job.id)
                if (progress.current(id)?.id == job.id) cancelOrder(id, "scene-reload")
            }) {
                cancelOrder(id, "lease-unavailable"); feedback(player, "unavailable"); return@runSync
            }
            if (!render(player, job, clock())) return@runSync
            job.yard?.let { shift.lastYardAnimals[job.variant] = it.animalId }
            shift.assigningSince = null; shift.unavailableOffers.clear(); retryAt.remove(id)
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
        if (closed || !valid(player, job) || !contextNear(player, job, config.venue(job.employer))) return
        OriginMenus.openDialog(player, originOddJobWorkScreen(config, job, clock(), notice,
            choose = { choose(player, job.id, it) }), reopen = { showWork(player) })
    }

    private fun contextNear(player: Player, job: OriginOddJobSession?, venue: OriginOddJobVenue): Boolean {
        if (job == null) return near(player, venue.npcId)
        val station = originOddJobStation(job.stage)?.let(stations::get)
        if (station != null) return originOddJobStationInReach(player, station, config.range)
        return near(player, if (job.stage == OriginOddJobStage.AUCTION_RECEIPT) config.handoffNpcId else venue.npcId)
    }

    private fun choose(player: Player, requestId: UUID?, choice: Int) {
        val job = progress.current(player.uniqueId)?.takeIf { it.id == requestId } ?: return
        if (!valid(player, job) || !contextNear(player, job, config.venue(job.employer))) return
        val station = originOddJobStation(job.stage)?.let(stations::get)
        if (station != null && !originOddJobAimed(player, station, config.range, null)) return
        val step = progress.choose(player.uniqueId, choice, clock()); apply(player, step)
        val current = progress.current(player.uniqueId)
        if (current?.kind == OriginOddJobKind.AUCTION && originOddJobChoices(config, current).isNotEmpty())
            showWork(player, (step as? OriginOddJobStep.Rejected)?.let { config.line(rejectionKey(it.reason)) })
    }

    /** Hauling uses the worker's pad XZ; workstation actions check the block, reach and line of sight. */
    fun work(player: Player, left: Boolean, clicked: Block?): Boolean {
        val job = progress.current(player.uniqueId) ?: return false
        if (originYardGearStep(job.stage) != null) return equipment.intercept(player, job)
        if (job.variant == OriginOddJobVariant.YARD_CLEANUP) return workCleaning(player, job, left, clicked)
        if (handoffHaul(player, job)) return true
        val wanted = originOddJobStation(job.stage)
        val cartStation = forge.station(player.uniqueId, job)
        val station = cartStation ?: wanted?.let(stations::get) ?: return false
        val aimed = originOddJobAimed(player, station, config.range, clicked, virtualTarget = cartStation != null)
        if (!aimed) return false
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (duplicate(player)) return true
        if (job.stage in setOf(OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.ENGRAVE_BLANK)) {
            supplies.openStock(player, job, station.block)
            return true
        }
        if (job.stage == OriginOddJobStage.HAUL_PUSH) { guide(player, job, clock(), true); return true }
        if (!supplies.canTransfer(player, job)) { feedback(player, "inventory-full"); return true }
        if (job.stage in setOf(OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_TACK) && !yard.readyForCare(player.uniqueId, job)) {
            stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true
        }
        val action = when (job.stage) {
            OriginOddJobStage.YARD_HAY -> OriginOddJobAction.HAY
            OriginOddJobStage.YARD_BUCKET, OriginOddJobStage.YARD_WELL -> OriginOddJobAction.WATER
            OriginOddJobStage.YARD_BRUSH -> OriginOddJobAction.BRUSH
            OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_LEAD_RACK -> OriginOddJobAction.TACK
            OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT -> OriginOddJobAction.BLANK
            OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobAction.HEAT
            OriginOddJobStage.FORGE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.FORGE_HAMMER -> OriginOddJobAction.HAMMER
            OriginOddJobStage.FORGE_QUENCH -> OriginOddJobAction.QUENCH
            OriginOddJobStage.HAUL_PICKUP -> OriginOddJobAction.PICKUP
            OriginOddJobStage.HAUL_LOAD -> OriginOddJobAction.LOAD
            OriginOddJobStage.HAUL_ATTACH -> OriginOddJobAction.ATTACH_CART
            OriginOddJobStage.HAUL_UNLOAD -> OriginOddJobAction.UNLOAD
            OriginOddJobStage.ENGRAVE_BLANK -> OriginOddJobAction.BLANK
            OriginOddJobStage.ENGRAVE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.ENGRAVE_CARVE -> OriginOddJobAction.ENGRAVE
            OriginOddJobStage.ENGRAVE_COLLECT -> OriginOddJobAction.COLLECT
            else -> return false
        }
        val step = progress.act(player.uniqueId, action, clock(), originOddJobZone(player, station))
        apply(player, step)
        if (step is OriginOddJobStep.Changed && action in setOf(OriginOddJobAction.HAMMER, OriginOddJobAction.ENGRAVE))
            player.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, station.point.clone().add(0.0, 0.12, 0.0), 5, 0.14, 0.04, 0.14, 0.02)
        if (job.kind == OriginOddJobKind.MOUNTYARD && progress.current(player.uniqueId)?.stage != job.stage) {
            player.swingMainHand()
            when (job.stage) {
                OriginOddJobStage.YARD_WELL -> player.playSound(station.point, Sound.ITEM_BUCKET_FILL, 0.6f, 1f)
                else -> Unit
            }
        }
        return true
    }

    private fun workAnimal(player: Player, job: OriginOddJobSession, npcId: Int): Boolean {
        if (actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (job.yard?.animalId == npcId && originYardGearStep(job.stage)?.atRack == false) {
            if (!equipment.intercept(player, job)) guide(player, job, clock(), true)
            return true
        }
        if (duplicate(player)) return true
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (job.yard?.animalId == npcId && job.stage in setOf(OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_LESSON_WALK)) {
            if (player.hasLineOfSight(requireNotNull(yard.resolve(npcId))) && !yard.toggleWorkerLead(player, job))
                feedback(player, "unavailable")
            return true
        }
        if (job.yard?.animalId != npcId || job.stage !in setOf(OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_LEAD, OriginOddJobStage.YARD_WATER,
                OriginOddJobStage.YARD_FEED)) {
            feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, job, clock())); return true
        }
        if (job.stage in setOf(OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE) && !yard.readyForCare(player.uniqueId, job)) {
            feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, job, clock())); return true
        }
        if (!player.hasLineOfSight(requireNotNull(yard.resolve(npcId)))) { feedback(player, "come-closer"); return true }
        val action = when (job.stage) {
            OriginOddJobStage.YARD_GROOM -> OriginOddJobAction.GROOM
            OriginOddJobStage.YARD_LEAD -> OriginOddJobAction.LEAD
            OriginOddJobStage.YARD_WATER -> OriginOddJobAction.WATER
            OriginOddJobStage.YARD_FEED -> OriginOddJobAction.FEED
            else -> OriginOddJobAction.SADDLE
        }
        val step = progress.act(player.uniqueId, action, clock())
        apply(player, step)
        if (step is OriginOddJobStep.Changed && progress.current(player.uniqueId)?.id == job.id) {
            if (action == OriginOddJobAction.GROOM) yard.stroke(player, step.job)
        }
        return true
    }

    /** Both native mouse buttons and the block/air fallback pass through the same current-order check. */
    private fun activateYardEquipment(player: Player, snapshot: OriginOddJobSession, step: OriginYardGearStep) {
        val job = progress.current(player.uniqueId) ?: return
        if (job != snapshot || originYardGearStep(job.stage) != step || duplicate(player)) return
        if (!valid(player, job)) { cancelOrder(player.uniqueId, "gear-owner-unavailable"); feedback(player, "unavailable"); return }
        val safe = job.stage !in setOf(OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM,
            OriginOddJobStage.YARD_BRUSH_RETURN, OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_SADDLE) ||
            yard.readyForCare(player.uniqueId, job)
        if (!safe) { feedback(player, "mountyard.next-step", "guide" to originOddJobGuide(config, job, clock())); return }
        apply(player, progress.act(player.uniqueId, step.action, clock(), step.zone))
        if (progress.current(player.uniqueId)?.stage != job.stage) {
            player.swingMainHand()
            player.playSound(player.location, Sound.BLOCK_WOOL_PLACE, .5f, 1f)
            equipment.tick()
        }
    }

    private fun syncYard(player: Player, job: OriginOddJobSession): Boolean =
        runCatching { yard.sync(player, job, stations, clock()) }
            .onFailure { warn("ORIGIN_ODD_JOBS phase=YARD_STEP_FAILED player={} request={}", player.uniqueId, job.id, it) }
            .getOrDefault(false)

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
                if (step.job.kind == OriginOddJobKind.MOUNTYARD && step.job.variant != OriginOddJobVariant.YARD_CLEANUP && !syncYard(player, step.job)) {
                    cancelOrder(player.uniqueId, "yard-route-unavailable"); feedback(player, "mountyard.route-unavailable"); return
                }
                if (!supplies.sync(player, step.job)) {
                    stop(player.uniqueId, "workpiece-missing"); feedback(player, "unavailable"); return
                }
                step.workUnitId?.let { requestId ->
                    val shift = shifts[player.uniqueId] ?: return
                    val work = workUnits.getOrPut(requestId) { AcceptedWorkUnit(player.uniqueId, requestId, step.job.id,
                        shift.score.id, step.job.finalQuality(clock())) }
                    payWorkUnit(work)
                }
                if (progress.current(player.uniqueId)?.id != step.job.id) return
                if (step.job.kind == OriginOddJobKind.MOUNTYARD && step.job.stage == OriginOddJobStage.RETURN &&
                    (step.job.variant in setOf(OriginOddJobVariant.YARD_RATIONS, OriginOddJobVariant.YARD_WATER) || step.job.variant.isYardPreparation())) {
                    apply(player, progress.act(player.uniqueId, OriginOddJobAction.RETURN, clock()))
                    return
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
        if (result.job.kind == OriginOddJobKind.MOUNTYARD) {
            if (workUnits.values.any { it.playerId == id && it.jobId == result.job.id }) return
            accepted.remove(id, result)
            rewards.orderCompleted(result.job.kind.rewardKind, id, result.job.id)
            info("ORIGIN_ODD_JOBS phase=FINISHED player={} request={} kind={} variant={} actions={} quality={}",
                id, result.job.id, result.job.kind, result.job.variant, result.job.completedActions, result.quality)
            finishIfReady(id, clock())
            return
        }
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

    private fun pendingWork(playerId: UUID): Boolean = workUnits.values.any { it.playerId == playerId }

    /** Earned physical work survives the later cancellation or completion of its order. */
    private fun payWorkUnit(work: AcceptedWorkUnit) {
        if (closed || workUnits[work.requestId] !== work || work.inFlight || clock() < work.nextAttempt) return
        work.inFlight = true
        rewards.creditWorkUnit(OriginSideJobKind.MOUNTYARD, work.playerId, work.shiftId, work.requestId, work.quality) { payment ->
            if (workUnits[work.requestId] !== work) return@creditWorkUnit
            work.inFlight = false
            if (payment.outcome == SideJobRewardOutcome.RETRY) {
                work.nextAttempt = clock() + 1_000
                return@creditWorkUnit
            }
            workUnits.remove(work.requestId, work)
            val shift = shifts[work.playerId]?.takeIf { it.score.id == work.shiftId }
            if (payment.outcome == SideJobRewardOutcome.CREDITED) {
                if (shift != null) shift.score = shift.score.record(work.requestId, rewards.amount, work.quality)
            } else {
                if (shift != null) shift.score = shift.score.requestFinish()
                cancelOrder(work.playerId, "work-credit-unavailable")
                Bukkit.getPlayer(work.playerId)?.takeIf { it.isOnline }?.let { player -> feedback(player, when (payment.outcome) {
                    SideJobRewardOutcome.DAILY_LIMIT -> "daily-limit"
                    SideJobRewardOutcome.UNCONFIRMED -> "unconfirmed"
                    else -> "cash-unavailable"
                }) }
            }
            info("ORIGIN_ODD_JOBS phase=WORK_UNIT_CREDITED player={} request={} order={} outcome={}",
                work.playerId, work.requestId, work.jobId, payment.outcome)
            accepted[work.playerId]?.let { pay(work.playerId, it) }
            finishIfReady(work.playerId, clock())
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
        if (id in accepted || pendingWork(id) || progress.current(id) != null || !shift.score.shouldFinish(now, false)) return
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

    private fun handoffYard(player: Player, job: OriginOddJobSession): Boolean {
        val animal = job.yard?.animalId?.let(yard::resolve) ?: return false
        val receiver = config.yardRider?.npcId?.let(::actor) ?: return false
        if (animal.world.uid != receiver.world.uid || animal.location.distanceSquared(receiver.location) > 25.0) return false
        apply(player, progress.act(player.uniqueId, OriginOddJobAction.HANDOFF, clock()))
        return true
    }

    private fun handoffHaul(player: Player, job: OriginOddJobSession): Boolean {
        if (job.stage != OriginOddJobStage.HAUL_PUSH || progress.current(player.uniqueId)?.id != job.id ||
            !player.isOnline || player.isDead || player.world.name != worldName) return false
        if (config.venue(job.employer).scene != null && leases[player.uniqueId]?.let(owns) != true) return false
        val destination = stations[OriginOddJobStationId.HAUL_DELIVERY] ?: return false
        if (!forge.tick(player, job, destination)) return false
        val arrival = progress.act(player.uniqueId, OriginOddJobAction.CART_ARRIVE, clock())
        // Immediate unloading must not recreate or revalidate a presentation-only cart between the two steps.
        apply(player, if (arrival is OriginOddJobStep.Changed && arrival.job.stage == OriginOddJobStage.HAUL_UNLOAD)
            progress.act(player.uniqueId, OriginOddJobAction.UNLOAD, clock()) else arrival)
        return true
    }

    private fun workCleaning(player: Player, job: OriginOddJobSession, left: Boolean, clicked: Block?): Boolean {
        if (handoffCleaning(player, job)) return true
        val station = if (job.stage == OriginOddJobStage.CLEAN_TOOL) stations[OriginOddJobStationId.BRUSH]
            else cleaning.station(player.uniqueId, job)
        if (station == null || !originOddJobAimed(player, station, config.range, clicked,
                virtualTarget = job.stage != OriginOddJobStage.CLEAN_TOOL)) return false
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (duplicate(player)) return true
        if (!supplies.canTransfer(player, job)) { feedback(player, "inventory-full"); return true }
        val action = when (job.stage) {
            OriginOddJobStage.CLEAN_TOOL -> OriginOddJobAction.PICKUP
            OriginOddJobStage.CLEAN_CART -> OriginOddJobAction.ATTACH_CART
            OriginOddJobStage.CLEAN_PUSH -> if (requireNotNull(job.cleaning).collected < job.cleaning.total && cleaning.canPark(player.uniqueId, job)) OriginOddJobAction.PARK_CART
                else { feedback(player, "guide.clean-park-near"); return true }
            OriginOddJobStage.CLEAN_SCOOP -> OriginOddJobAction.SCOOP
            OriginOddJobStage.CLEAN_LOAD -> OriginOddJobAction.LOAD
            OriginOddJobStage.CLEAN_UNLOAD -> OriginOddJobAction.UNLOAD
            OriginOddJobStage.CLEAN_PARK -> OriginOddJobAction.PARK_CART
            else -> return true
        }
        apply(player, progress.act(player.uniqueId, action, clock()))
        player.swingMainHand()
        player.playSound(station.point, if (action == OriginOddJobAction.SCOOP) Sound.BLOCK_MUD_BREAK else Sound.BLOCK_WOOD_PLACE, 0.45f, 0.9f)
        return true
    }

    private fun handoffCleaning(player: Player, job: OriginOddJobSession): Boolean {
        if (job.variant != OriginOddJobVariant.YARD_CLEANUP || progress.current(player.uniqueId)?.id != job.id || !valid(player, job)) return false
        val action = cleaning.tick(player, job) ?: return false
        val arrival = progress.act(player.uniqueId, action, clock())
        apply(player, if (action == OriginOddJobAction.CART_ARRIVE && arrival is OriginOddJobStep.Changed &&
                arrival.job.stage == OriginOddJobStage.CLEAN_PARK)
            progress.act(player.uniqueId, OriginOddJobAction.PARK_CART, clock()) else arrival)
        return true
    }

    fun tick(now: Long) {
        workUnits.values.toList().forEach(::payWorkUnit)
        accepted.toList().forEach { (id, result) -> pay(id, result) }
        shifts.toList().forEach { (id, shift) ->
            val player = Bukkit.getPlayer(id); val job = progress.current(id)
            when {
                player == null || !player.isOnline -> stop(id, "offline")
                now >= shift.score.hardStopAt -> { cancelOrder(id, "expired"); shift.score = shift.score.requestFinish() }
                job != null && handoffHaul(player, job) -> Unit
                job?.variant == OriginOddJobVariant.YARD_CLEANUP && handoffCleaning(player, job) -> Unit
                !inArea(player, shift.venue.kind, horizontal = job?.stage == OriginOddJobStage.HAUL_PUSH) -> {
                    cancelOrder(id, "left-area"); shift.score = shift.score.requestFinish(); feedback(player, "left-area")
                }
                job != null && (!valid(player, job) || now >= job.expiresAt) -> { cancelOrder(id, "expired"); feedback(player, "expired") }
                // Stable work is accepted on the next tick, after its final care feedback.
                job?.kind == OriginOddJobKind.MOUNTYARD && job.stage == OriginOddJobStage.RETURN ->
                    apply(player, progress.act(id, OriginOddJobAction.RETURN, now))
                job?.kind == OriginOddJobKind.MOUNTYARD && job.stage == OriginOddJobStage.YARD_HANDOFF && job.variant != OriginOddJobVariant.YARD_LESSON -> {
                    if (!handoffYard(player, job)) {
                        cancelOrder(id, "yard-handoff-unavailable"); feedback(player, "mountyard.route-unavailable")
                    }
                }
                job?.kind == OriginOddJobKind.MOUNTYARD && job.variant != OriginOddJobVariant.YARD_CLEANUP -> {
                    when (runCatching { yard.tick(player, job, now) }.onFailure {
                        warn("ORIGIN_ODD_JOBS phase=YARD_TICK_FAILED player={} request={}", id, job.id, it)
                    }.getOrDefault(OriginYardTick.FAILED)) {
                        OriginYardTick.ARRIVED -> apply(player, progress.act(id, OriginOddJobAction.ARRIVE, now))
                        OriginYardTick.CARED -> apply(player, progress.act(id, OriginOddJobAction.CARE_DONE, now))
                        OriginYardTick.FAILED -> { cancelOrder(id, "yard-route-unavailable"); feedback(player, "mountyard.route-unavailable") }
                        OriginYardTick.WAITING -> if (render(player, job, now)) guide(player, job, now)
                    }
                }
                job != null && render(player, job, now) -> guide(player, job, now)
                job == null && id !in accepted -> {
                    if (shift.score.acceptsOrders(now)) nextOrder(player, shift)
                }
            }
            if (player != null && player.isOnline) updateBar(player, progress.current(id), now)
            finishIfReady(id, now)
        }
    }

    private fun render(player: Player, job: OriginOddJobSession, now: Long): Boolean = try {
        forge.sync(player, job)
        cleaning.sync(player, job)
        equipment.sync(player, job)
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
                else -> null
            }
            else -> null
        }
        val placedAt = when (job.stage) {
            OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HAMMER -> OriginOddJobStationId.ANVIL
            OriginOddJobStage.FORGE_HEATING -> OriginOddJobStationId.FIRE
            OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT -> OriginOddJobStationId.ENGRAVING
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
        if (job.stage == OriginOddJobStage.HAUL_LOAD) {
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
            OriginOddJobStage.HAUL_PICKUP, OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_ATTACH,
            OriginOddJobStage.HAUL_PUSH, OriginOddJobStage.HAUL_UNLOAD,
            OriginOddJobStage.ENGRAVE_BLANK, OriginOddJobStage.ENGRAVE_PLACE, OriginOddJobStage.ENGRAVE_CARVE,
            OriginOddJobStage.ENGRAVE_COLLECT -> originOddJobGuide(config, job, now)
            else -> if (job.kind == OriginOddJobKind.MOUNTYARD) originOddJobGuide(config, job, now)
                else stationId?.let { config.line("targets.${it.name.lowercase()}") }.orEmpty()
        }
        // Update the label even at the same station when its current operation changes.
        val workpieceTarget = originOddJobWorkpieceTarget(job.stage)
        val cleaningTarget = cleaning.target(player.uniqueId, job)
        val cleaningDestination = if (job.stage in setOf(OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN))
            cleaning.destination(player.uniqueId, job) else null
        val cartTarget = cleaningTarget ?: forge.target(player.uniqueId, job)
        val gateTarget = yard.gateTarget(player.uniqueId, job)
        val detachedLead = yard.leadDetached(player.uniqueId, job)
        val yardTarget = if (job.stage in setOf(OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_LESSON_WALK))
            yard.destination(player.uniqueId, job)?.add(0.0, 0.05, 0.0) else null
        if (detachedLead) visuals.target(player, job.id, yard.resolve(requireNotNull(job.yard).animalId)?.location?.add(0.0, 0.9, 0.0),
            config.line("guide.yard-regrab", "animal" to job.yard.animalName), outline = false)
        else if (gateTarget != null) visuals.target(player, job.id, gateTarget,
            config.line("guide.yard-open-gate", "animal" to requireNotNull(job.yard).animalName))
        else if (yardTarget != null) visuals.destination(player, job.id, yardTarget,
            if (job.stage == OriginOddJobStage.YARD_LESSON_WALK) originOddJobGuide(config, job, now)
            else config.line(if (job.stage == OriginOddJobStage.YARD_LEAD_OUT) "targets.yard-care-pad" else "targets.yard-handoff"))
        else if (cleaningDestination != null) visuals.destination(player, job.id, cleaningDestination.clone().add(0.0, 0.05, 0.0),
            config.line(when {
                job.stage == OriginOddJobStage.CLEAN_RETURN -> "targets.clean-return"
                requireNotNull(job.cleaning).collected == job.cleaning.total -> "targets.clean-unload"
                else -> "targets.clean-stall"
            }))
        else if (stationId == OriginOddJobStationId.HAUL_DELIVERY) visuals.destination(player, job.id,
            stations.getValue(stationId).point.clone().add(0.0, 0.05, 0.0), config.line("targets.haul-delivery"))
        else if (originYardGearStep(job.stage) != null) visuals.gearTarget(player, job.id, equipment.target(job),
            config.line("gear-hints.${job.stage.name.lowercase()}"))
        else visuals.target(player, job.id, cartTarget ?: stationId?.let { id -> stations.getValue(id).let { station ->
            if (workpieceTarget) station.point else station.block.location.clone().add(0.5, 0.5, 0.5)
        } }, caption, outline = !workpieceTarget && cartTarget == null)
        visuals.glowWorkpiece(player, job.id, originOddJobWorkpieceGlows(job, now))
        if (stationId == null) targets.remove(player.uniqueId) else targets[player.uniqueId] = stationId
        val npcId = when (job.stage) {
            OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_LEAD,
            OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_LESSON_WALK, OriginOddJobStage.YARD_WATER, OriginOddJobStage.YARD_FEED -> job.yard?.animalId
            OriginOddJobStage.YARD_HANDOFF -> config.yardRider?.npcId
            OriginOddJobStage.AUCTION_VERIFY, OriginOddJobStage.AUCTION_DELIVER, OriginOddJobStage.AUCTION_RECEIPT -> config.handoffNpcId
            OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK,
            OriginOddJobStage.AUCTION_LOAD, OriginOddJobStage.AUCTION_DOCUMENT -> config.venue(job.employer).npcId
            OriginOddJobStage.RETURN -> if (job.kind == OriginOddJobKind.MOUNTYARD) null else config.venue(job.employer).npcId
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
        val instruction = if (job != null && yard.leadDetached(player.uniqueId, job))
            config.line("guide.yard-regrab", "animal" to requireNotNull(job.yard).animalName)
            else if (job != null && yard.waitingForGate(player.uniqueId, job))
            config.line("guide.yard-open-gate", "animal" to requireNotNull(job.yard).animalName)
            else job?.let { originOddJobGuide(config, it, now) } ?: paymentGuide(player.uniqueId, now)
                ?: config.line(if (shifts[player.uniqueId]?.venue?.kind == OriginOddJobKind.MOUNTYARD &&
                    (retryAt[player.uniqueId] ?: 0) > now) "mountyard.waiting" else "shift.assigning")
        val caption = config.line("shift.hud", "guide" to instruction,
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
        if (kind == OriginOddJobKind.FORGE && (actor(config.venue(OriginOddJobKind.FORGE_HAUL).npcId) == null ||
            actor(config.venue(OriginOddJobKind.FORGE_ENGRAVE).npcId) == null)) return false
        if (kind in setOf(OriginOddJobKind.FORGE, OriginOddJobKind.FORGE_HAUL) &&
            Bukkit.getWorld(worldName)?.let { forge.available(it) } != true) return false
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
    private fun yardTarget(offer: OriginOddJobOffer, shift: Shift): OriginYardWork? = yard.target(offer,
        shift.lastYardAnimals[offer.variant],
        if (offer.variant == OriginOddJobVariant.YARD_CLEANUP) config.yardAnimalIds.filter(cleaning::supportsAnimal).toSet() else null)

    private fun variantAvailable(player: Player, offer: OriginOddJobOffer): Boolean = when (offer.variant) {
        OriginOddJobVariant.YARD_CLEANUP -> cleaning.available(player.world)
        OriginOddJobVariant.YARD_LESSON -> config.yardRideAnchorIds.isNotEmpty()
        else -> true
    }
    private fun valid(player: Player, job: OriginOddJobSession): Boolean =
        inArea(player, job.employer, horizontal = job.stage == OriginOddJobStage.HAUL_PUSH) &&
        (job.stage == OriginOddJobStage.HAUL_PUSH || available(job.kind)) &&
        (!supplies.requiresWorkpiece(job) || supplies.has(player, job)) &&
        (config.venue(job.employer).scene == null || leases[player.uniqueId]?.let(owns) == true)
    private fun inArea(player: Player, kind: OriginOddJobKind, horizontal: Boolean = false): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        val npc = actor(config.venue(kind).npcId) ?: return false
        val at = player.location
        val employer = npc.location
        if (horizontal) at.y = employer.y
        return at.distanceSquared(employer) <= config.workRange * config.workRange
    }
    private fun actor(id: Int): LivingEntity? {
        if (yard.contains(id)) return yard.resolve(id)
        val expected = when (id) {
            config.handoffNpcId -> config.handoffNpcUuid
            config.yardRider?.npcId -> config.yardRider.npcUuid
            else -> config.venues.firstOrNull { it.npcId == id }?.npcUuid
        }
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
            runCatching { visuals.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=VISUAL_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { forge.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=CART_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { cleaning.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=CLEANING_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { equipment.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=GEAR_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { clearGlow(playerId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=GLOW_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
        } finally {
            Bukkit.getPlayer(playerId)?.let { supplies.clear(it) }
            props.remove(playerId); targets.remove(playerId); renderedForge.remove(playerId); noticeUntil.remove(playerId)
            renderedCargo.remove(playerId); renderedEngraving.remove(playerId)
            val lease = leases.remove(playerId)
            runCatching { yard.clear(playerId, requestId) { lease?.let(release) } }
                .onFailure {
                    lease?.let(release)
                    warn("ORIGIN_ODD_JOBS phase=YARD_CLEAR_FAILED player={} request={}", playerId, requestId, it)
                }
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
            if (result.job.kind == OriginOddJobKind.MOUNTYARD) rewards.orderCompleted(result.job.kind.rewardKind, id, result.job.id)
            else if (!result.inFlight) rewards.credit(result.job.kind.rewardKind, id, result.shiftId, result.job.id, result.quality) {}
        }
        workUnits.values.filterNot { it.inFlight }.forEach { work ->
            rewards.creditWorkUnit(OriginSideJobKind.MOUNTYARD, work.playerId, work.shiftId, work.requestId, work.quality) {}
        }
        closed = true; tasks.close(); requests.clear()
        shifts.keys.toList().forEach { stop(it, "reload") }
        accepted.clear(); workUnits.clear(); visuals.close(); forge.close(); cleaning.close(); equipment.close(); yard.close(); rewards.close(); lastClicks.clear(); noticeUntil.clear(); retryAt.clear()
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
