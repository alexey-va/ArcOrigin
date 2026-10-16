package ru.arc.origin

import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
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
        val config = OriginOddJobsConfig.load(ConfigManager.ofModule(path, "origin-odd-jobs.yml"))
        if (!config.enabled) return
        val plan = OriginScenePlan.load(path)
        val world = Bukkit.getWorld(plan.world) ?: return
        val stations = originOddJobStations(plan, world)
        game = OriginOddJobsGame(config, plan.world, stations).also {
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

/** The same hit volume covers the small station bracket, its label and the real clicked block. */
internal fun originOddJobAimed(player: Player, station: OriginOddJobStation, range: Double, clicked: Block?): Boolean {
    if (player.world.uid != station.point.world?.uid) return false
    if (clicked != null && (clicked.world.uid != station.block.world.uid || clicked.x != station.block.x ||
            clicked.y != station.block.y || clicked.z != station.block.z)) return false
    val eye = player.eyeLocation
    val ray = eye.direction
    val p = station.point
    val distance = originWorkshopRayAabbHit(OriginWorkshopVec3(eye.x, eye.y, eye.z), OriginWorkshopVec3(ray.x, ray.y, ray.z),
        OriginWorkshopAabb(p.x - 0.65, p.y - 0.8, p.z - 0.65, p.x + 0.65, p.y + 0.95, p.z + 0.65), range) ?: return false
    val blocker = player.world.rayTraceBlocks(eye, ray, distance + 0.02, FluidCollisionMode.NEVER, true)?.hitBlock ?: return true
    return blocker.world.uid == station.block.world.uid && blocker.x == station.block.x &&
        blocker.y == station.block.y && blocker.z == station.block.z
}

internal class OriginOddJobsGame(
    private val config: OriginOddJobsConfig,
    private val worldName: String,
    private val stations: Map<OriginOddJobStationId, OriginOddJobStation>,
    private val rewards: OriginSideJobRewards = OriginSideJobRewards(),
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
) : Listener, AutoCloseable {
    private val progress = OriginOddJobsProgress(config.lifetimeMillis)
    private val requests = mutableMapOf<UUID, UUID>()
    private val leases = mutableMapOf<UUID, OriginSceneLease>()
    private val lastClicks = mutableMapOf<UUID, Long>()
    private val noticeUntil = mutableMapOf<UUID, Long>()
    private val props = mutableMapOf<UUID, Pair<OriginJobProp?, Boolean>>()
    private val targets = mutableMapOf<UUID, OriginOddJobStationId>()
    private val glowing = mutableMapOf<UUID, LivingEntity>()
    private var closed = false

    init {
        tasks.runTimer(1, 1) {
            try { visuals.tick() } catch (failure: Exception) {
                warn("ORIGIN_ODD_JOBS phase=VISUAL_TICK_FAILED", failure)
                progress.entries().forEach { (id, _) -> stop(id, "unavailable"); Bukkit.getPlayer(id)?.let { feedback(it, "unavailable") } }
            }
        }
        tasks.runTimer(4, 4) { tick(clock()) }
    }

    fun isWorking(playerId: UUID): Boolean = progress.current(playerId) != null

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
        if (work(player = event.player, left = left, clicked = event.clickedBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (event.hand == EquipmentSlot.HAND && work(event.player, left = true, clicked = null)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { stop(event.player.uniqueId, "quit") }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { stop(event.player.uniqueId, "teleport") }

    fun interact(player: Player, npcId: Int): Boolean {
        if (closed || !player.isSneaking) return false
        val current = progress.current(player.uniqueId)
        val venue = config.venues.firstOrNull { it.npcId == npcId }
        val handoff = current?.kind == OriginOddJobKind.AUCTION && npcId == config.handoffNpcId
        if (venue == null && !handoff) return false
        if (actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (duplicate(player)) return true
        if (current != null && !valid(player, current)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (current != null && venue != null && current.kind != venue.kind) { feedback(player, "other-job"); return true }
        if (handoff) {
            apply(player, progress.act(player.uniqueId, OriginOddJobAction.HANDOFF, clock()))
        } else if (current?.stage == OriginOddJobStage.RETURN) {
            apply(player, progress.act(player.uniqueId, OriginOddJobAction.RETURN, clock()))
        } else {
            OriginMenus.beginDialogFlow(player)
            if (current == null) status(player, requireNotNull(venue), start = false)
            else show(player, requireNotNull(venue), current)
        }
        return true
    }

    private fun status(player: Player, venue: OriginOddJobVenue, start: Boolean) {
        if (closed || !near(player, venue.npcId) || progress.current(player.uniqueId) != null) return
        if (!workMode(player)) { feedback(player, "work-mode"); return }
        otherWork(player.uniqueId)?.let { feedback(player, it); return }
        if (!available(venue.kind)) { feedback(player, "unavailable"); return }
        val playerId = player.uniqueId
        if (playerId in requests) { feedback(player, "checking"); return }
        val request = UUID.randomUUID()
        requests[playerId] = request
        val epoch = tasks.token()
        val insideDialog = !start || venue.kind == OriginOddJobKind.AUCTION
        if (insideDialog) OriginMenus.openDialog(player, originOddJobsScreen(config, venue, null, rewards.amount, null, clock()),
            onDismiss = { requests.remove(playerId, request) })
        else feedback(player, "checking")
        rewards.status(playerId).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(playerId, request) || closed || !near(player, venue.npcId) || progress.current(playerId) != null) return@runSync
            val key = if (failure != null || state == null) "cash-unavailable" else originOddJobStatusKey(state, clock())
            if (!start) {
                show(player, venue, null, state, if (failure != null) config.line("cash-unavailable") else null)
                return@runSync
            }
            val rejected = otherWork(playerId) ?: key ?: when {
                !workMode(player) -> "work-mode"
                !available(venue.kind) -> "unavailable"
                else -> null
            }
            if (rejected != null) {
                val notice = config.line(rejected, "seconds" to waiterSeconds((state?.nextAtMillis ?: clock()) - clock()))
                if (insideDialog) show(player, venue, null, state, notice) else message(player, notice)
                return@runSync
            }
            val lease = if (venue.scene != null) acquire(venue) else null
            if (venue.scene != null && lease == null) {
                if (insideDialog) show(player, venue, null, state, config.line("busy")) else feedback(player, "busy")
                return@runSync
            }
            val job = progress.begin(playerId, venue.kind, clock(),
                if (venue.kind == OriginOddJobKind.AUCTION) OriginAuctionParcel.entries.random() else null)
            if (job == null) { lease?.let(release); return@runSync }
            if (lease != null) leases[playerId] = lease
            if (!render(player, job, clock())) return@runSync
            info("ORIGIN_ODD_JOBS phase=STARTED player={} request={} kind={}", playerId, job.id, job.kind)
            if (insideDialog) show(player, venue, job)
            else guide(player, job, clock(), force = true)
        } }
    }

    private fun show(player: Player, venue: OriginOddJobVenue, job: OriginOddJobSession?, state: SideJobRewardStatus? = null, notice: String? = null) {
        if (closed || !near(player, venue.npcId)) return
        OriginMenus.openDialog(player, originOddJobsScreen(config, venue, job, rewards.amount, state, clock(), notice,
            start = { status(player, venue, true) },
            chooseItem = { item -> choose(player, venue, job?.id) { progress.chooseItem(player.uniqueId, item, clock()) } },
            choosePacking = { packing -> choose(player, venue, job?.id) { progress.choosePacking(player.uniqueId, packing, clock()) } },
            cancel = { if (near(player, venue.npcId) && progress.current(player.uniqueId)?.id == job?.id) {
                stop(player.uniqueId, "cancelled"); feedback(player, "cancelled")
            } }), reopen = {
                if (near(player, venue.npcId)) progress.current(player.uniqueId)?.let { show(player, venue, it) }
                    ?: status(player, venue, false)
            })
    }

    private fun choose(player: Player, venue: OriginOddJobVenue, requestId: UUID?, action: () -> OriginOddJobStep) {
        val job = progress.current(player.uniqueId)?.takeIf { it.id == requestId } ?: return
        if (!near(player, venue.npcId) || !valid(player, job)) return
        val step = action()
        apply(player, step)
        progress.current(player.uniqueId)?.let { current -> show(player, venue, current,
            notice = (step as? OriginOddJobStep.Rejected)?.let { config.line(rejectionKey(it.reason)) }) }
    }

    /** Main hand station actions consume the original event, never open or alter its real container. */
    fun work(player: Player, left: Boolean, clicked: Block?): Boolean {
        val job = progress.current(player.uniqueId) ?: return false
        val station = originOddJobStation(job.stage)?.let(stations::get) ?: return false
        if (!originOddJobAimed(player, station, config.range, clicked)) return false
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (duplicate(player)) return true
        if (left != (job.stage == OriginOddJobStage.FORGE_HAMMER)) { guide(player, job, clock(), force = true); return true }
        val action = when (job.stage) {
            OriginOddJobStage.YARD_HAY -> OriginOddJobAction.HAY
            OriginOddJobStage.YARD_FEED -> OriginOddJobAction.FEED
            OriginOddJobStage.YARD_WATER -> OriginOddJobAction.WATER
            OriginOddJobStage.FORGE_BLANK -> OriginOddJobAction.BLANK
            OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobAction.HEAT
            OriginOddJobStage.FORGE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.FORGE_HAMMER -> OriginOddJobAction.HAMMER
            OriginOddJobStage.FORGE_QUENCH -> OriginOddJobAction.QUENCH
            else -> return false
        }
        apply(player, progress.act(player.uniqueId, action, clock()))
        return true
    }

    private fun apply(player: Player, step: OriginOddJobStep) {
        when (step) {
            is OriginOddJobStep.Rejected -> {
                if (step.reason == OriginOddJobRejection.EXPIRED) stop(player.uniqueId, "expired")
                else step.job?.let { if (!render(player, it, clock())) return }
                feedback(player, rejectionKey(step.reason))
            }
            is OriginOddJobStep.Changed -> {
                if (!render(player, step.job, clock())) return
                if (step.job.stage == OriginOddJobStage.PAYOUT) pay(player, step.job)
                else {
                    guide(player, step.job, clock(), force = true)
                    player.playSound(player.location, if (step.job.stage == OriginOddJobStage.FORGE_HAMMER) Sound.BLOCK_ANVIL_USE else Sound.UI_BUTTON_CLICK,
                        SoundCategory.PLAYERS, 0.3f, 1.1f)
                }
            }
        }
    }

    private fun pay(player: Player, job: OriginOddJobSession) {
        feedback(player, "paying")
        rewards.pay(OriginSideJobKind.valueOf(job.kind.name), player.uniqueId, job.id,
            stillValid = { !closed && progress.current(player.uniqueId)?.id == job.id && valid(player, job) }) { outcome ->
            if (progress.finish(player.uniqueId, job.id) == null) return@pay
            cleanup(player.uniqueId, job.id)
            if (player.isOnline) feedback(player, when (outcome) {
                SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID -> "paid"
                SideJobRewardOutcome.COOLDOWN -> "payment-cooldown"
                SideJobRewardOutcome.DAILY_LIMIT -> "daily-limit"
                SideJobRewardOutcome.UNCONFIRMED -> "unconfirmed"
                SideJobRewardOutcome.UNAVAILABLE -> "cash-unavailable"
                SideJobRewardOutcome.CANCELLED -> "cancelled"
            }, "amount" to rewards.amount)
            info("ORIGIN_ODD_JOBS phase=FINISHED player={} request={} kind={} outcome={}", player.uniqueId, job.id, job.kind, outcome)
        }
    }

    fun tick(now: Long) {
        progress.entries().forEach { (playerId, job) ->
            val player = Bukkit.getPlayer(playerId)
            val reason = when {
                player == null || !player.isOnline -> "offline"
                job.stage != OriginOddJobStage.PAYOUT && now >= job.expiresAt -> "expired"
                !inArea(player, job.kind) -> "left-area"
                !valid(player, job) -> "unavailable"
                else -> null
            }
            if (reason != null) {
                stop(playerId, reason)
                if (player?.isOnline == true) feedback(player, if (reason == "offline") "cancelled" else reason)
            } else if (player != null && job.stage != OriginOddJobStage.PAYOUT && render(player, job, now)) guide(player, job, now)
        }
    }

    private fun render(player: Player, job: OriginOddJobSession, now: Long): Boolean = try {
        val prop = when (job.stage) {
            OriginOddJobStage.YARD_FEED -> OriginJobProp.HAY
            OriginOddJobStage.YARD_WATER -> OriginJobProp.WATER_BUCKET
            OriginOddJobStage.FORGE_HEAT -> OriginJobProp.FORGE_BLANK
            OriginOddJobStage.FORGE_HEATING -> if (now - job.stageStartedAt >= ODD_JOB_HEAT_READY_MILLIS) OriginJobProp.FORGE_HEATED else OriginJobProp.FORGE_BLANK
            OriginOddJobStage.FORGE_PLACE -> OriginJobProp.FORGE_HEATED
            OriginOddJobStage.FORGE_HAMMER -> when (job.progress) { 0 -> OriginJobProp.FORGE_HEATED; 1 -> OriginJobProp.FORGE_SHAPED_1; else -> OriginJobProp.FORGE_SHAPED_2 }
            OriginOddJobStage.FORGE_QUENCH -> OriginJobProp.FORGE_HORSESHOE
            OriginOddJobStage.AUCTION_PACK -> when (job.parcel) {
                OriginAuctionParcel.VASE -> OriginJobProp.AUCTION_VASE
                OriginAuctionParcel.BOOK -> OriginJobProp.AUCTION_BOOK
                else -> OriginJobProp.AUCTION_BLADE
            }
            OriginOddJobStage.AUCTION_DELIVER -> OriginJobProp.AUCTION_PACKAGE
            OriginOddJobStage.RETURN -> if (job.kind == OriginOddJobKind.FORGE) OriginJobProp.FORGE_COOLED else null
            else -> null
        }
        val placed = job.stage == OriginOddJobStage.FORGE_HAMMER
        if (props[player.uniqueId] != (prop to placed)) {
            if (prop == null) { visuals.clear(player.uniqueId, job.id); targets.remove(player.uniqueId) }
            else if (placed) visuals.place(player, job.id, stations.getValue(OriginOddJobStationId.ANVIL).point, prop)
            else visuals.carry(player, job.id, prop)
            props[player.uniqueId] = prop to placed
        }
        val stationId = originOddJobStation(job.stage)
        if (targets[player.uniqueId] != stationId) {
            val caption = when (stationId) {
                OriginOddJobStationId.HAY -> "mountyard.hay-target"
                OriginOddJobStationId.FEED -> "mountyard.feed-target"
                OriginOddJobStationId.WATER -> "mountyard.water-target"
                OriginOddJobStationId.BLANK -> "forge.blank-target"
                OriginOddJobStationId.FIRE -> "forge.heat-target"
                OriginOddJobStationId.ANVIL -> "forge.anvil-target"
                OriginOddJobStationId.QUENCH -> "forge.quench-target"
                null -> null
            }
            visuals.target(player, job.id, stationId?.let { stations.getValue(it).point }, caption?.let { config.line(it) }.orEmpty())
            if (stationId == null) targets.remove(player.uniqueId) else targets[player.uniqueId] = stationId
        }
        val npcId = when (job.stage) {
            OriginOddJobStage.AUCTION_DELIVER -> config.handoffNpcId
            OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_PACK, OriginOddJobStage.RETURN -> config.venue(job.kind).npcId
            else -> null
        }
        val entity = npcId?.let(::actor)
        if (glowing[player.uniqueId] !== entity) {
            clearGlow(player.uniqueId)
            if (entity != null) { OriginServices.viewerGlow.setEntityGlowingFor(entity, player, true); glowing[player.uniqueId] = entity }
        }
        true
    } catch (failure: Exception) {
        warn("ORIGIN_ODD_JOBS phase=VISUAL_FAILED player={} request={}", player.uniqueId, job.id, failure)
        stop(player.uniqueId, "unavailable")
        feedback(player, "unavailable")
        false
    }

    private fun guide(player: Player, job: OriginOddJobSession, now: Long, force: Boolean = false) {
        if (!force && (noticeUntil[player.uniqueId] ?: 0) > now) return
        val ready = originOddJobHammerReady(job, now) || job.stage == OriginOddJobStage.FORGE_HEATING &&
            now - job.stageStartedAt in ODD_JOB_HEAT_READY_MILLIS..ODD_JOB_HEAT_LIMIT_MILLIS
        player.sendActionBar(waiterText(originOddJobGuide(config, job, now), TextColor.color(if (ready) 0x9BD48D else 0xE8DFD2)))
    }

    private fun rejectionKey(reason: OriginOddJobRejection) = when (reason) {
        OriginOddJobRejection.NO_JOB -> "cancelled"
        OriginOddJobRejection.EXPIRED -> "expired"
        OriginOddJobRejection.WRONG_ACTION -> "wrong-step"
        OriginOddJobRejection.TOO_EARLY -> "slow-down"
        OriginOddJobRejection.OVERHEATED -> "forge.overheated"
        OriginOddJobRejection.MISSED_BEAT -> "forge.missed-beat"
        OriginOddJobRejection.WRONG_ITEM -> "auction.wrong-item"
        OriginOddJobRejection.WRONG_PACKING -> "auction.wrong-packing"
        OriginOddJobRejection.PAYOUT_PENDING -> "paying"
    }

    private fun available(kind: OriginOddJobKind): Boolean = actor(config.venue(kind).npcId) != null &&
        (kind != OriginOddJobKind.AUCTION || actor(config.handoffNpcId) != null) &&
        originOddJobRequiredStations(kind).all { stations[it]?.valid() == true }

    private fun valid(player: Player, job: OriginOddJobSession): Boolean = workMode(player) && inArea(player, job.kind) && available(job.kind) &&
        (config.venue(job.kind).scene == null || leases[player.uniqueId]?.let(owns) == true)

    private fun workMode(player: Player): Boolean = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE

    private fun inArea(player: Player, kind: OriginOddJobKind): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        val npc = actor(config.venue(kind).npcId) ?: return false
        return player.location.distanceSquared(npc.location) <= config.workRange * config.workRange
    }

    private fun actor(id: Int): LivingEntity? {
        val expected = if (id == config.handoffNpcId) config.handoffNpcUuid else config.venues.firstOrNull { it.npcId == id }?.npcUuid
        return CitizensAPI.getNPCRegistry().getById(id)
            ?.takeIf { it.uniqueId == expected && it.isSpawned && it.entity.world.name == worldName }?.entity as? LivingEntity
    }

    private fun near(player: Player, id: Int): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        val npc = actor(id) ?: return false
        return player.location.distanceSquared(npc.location) <= config.range * config.range
    }

    private fun duplicate(player: Player): Boolean {
        val now = clock()
        val previous = lastClicks.put(player.uniqueId, now)
        return previous != null && now - previous in 0..249
    }

    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) = message(player, config.line(key, *values))
    private fun message(player: Player, text: String) {
        noticeUntil[player.uniqueId] = clock() + 1_000
        player.sendActionBar(waiterText(text))
    }

    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        Bukkit.getPlayer(playerId)?.let { if (entity.isValid) OriginServices.viewerGlow.setEntityGlowingFor(entity, it, false) }
    }

    private fun cleanup(playerId: UUID, requestId: UUID) {
        try {
            runCatching { visuals.clear(playerId, requestId) }
                .onFailure { warn("ORIGIN_ODD_JOBS phase=VISUAL_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { clearGlow(playerId) }
                .onFailure { warn("ORIGIN_ODD_JOBS phase=GLOW_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
        } finally {
            props.remove(playerId)
            targets.remove(playerId)
            noticeUntil.remove(playerId)
            leases.remove(playerId)?.let(release)
        }
    }

    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId)
        lastClicks.remove(playerId)
        val job = progress.cancel(playerId) ?: return
        cleanup(playerId, job.id)
        info("ORIGIN_ODD_JOBS phase=CANCELLED player={} request={} kind={} reason={}", playerId, job.id, job.kind, reason)
    }

    override fun close() {
        closed = true
        tasks.close()
        requests.clear()
        progress.entries().forEach { (playerId, _) -> stop(playerId, "reload") }
        visuals.close()
        rewards.close()
        lastClicks.clear()
        noticeUntil.clear()
    }
}
