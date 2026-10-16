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
        val config = OriginOddJobsConfig.load(ConfigManager.ofModule(path, "origin-odd-jobs.yml"),
            ArcOriginPlugin.instance.sideJobRewardPolicy)
        if (!config.enabled) return
        val plan = OriginScenePlan.load(path)
        val world = Bukkit.getWorld(plan.world) ?: return
        val stations = originOddJobStations(plan, world)
        game = OriginOddJobsGame(config, plan.world, stations,
            resolveStations = { originOddJobStations(plan, world) }).also {
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
    private val renderedCleaning = mutableMapOf<UUID, Int>()
    private data class Shift(val venue: OriginOddJobVenue, var score: OriginWorkShift, val dispatch: OriginOddJobDispatch)
    private data class Accepted(val job: OriginOddJobSession, val shiftId: UUID, val quality: Int, var nextAttempt: Long = 0, var inFlight: Boolean = false)
    private val shifts = mutableMapOf<UUID, Shift>()
    private val accepted = mutableMapOf<UUID, Accepted>()
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

    fun isWorking(playerId: UUID): Boolean = playerId in shifts || playerId in accepted

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
        val playerId = player.uniqueId
        val current = progress.current(playerId)
        val shift = shifts[playerId]
        val venue = config.venues.firstOrNull { it.npcId == npcId }
        val handoff = current?.kind == OriginOddJobKind.AUCTION && npcId == config.handoffNpcId
        if (venue == null && !handoff || actor(npcId) == null) return false
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (duplicate(player)) return true
        if (current != null && !valid(player, current)) { stop(playerId, "unavailable"); feedback(player, "unavailable"); return true }
        if (shift != null && venue != null && shift.venue.kind != venue.kind) { feedback(player, "other-job"); return true }
        if (handoff) {
            if (current.stage == OriginOddJobStage.AUCTION_RECEIPT) {
                OriginMenus.beginDialogFlow(player); show(player)
            } else {
                apply(player, progress.act(playerId, OriginOddJobAction.HANDOFF, clock()))
                if (progress.current(playerId)?.stage == OriginOddJobStage.AUCTION_RECEIPT) { OriginMenus.beginDialogFlow(player); show(player) }
            }
        } else if (current?.stage in setOf(OriginOddJobStage.RETURN, OriginOddJobStage.AUCTION_DOCUMENT)) {
            apply(player, progress.act(playerId, OriginOddJobAction.RETURN, clock()))
            if (progress.current(playerId)?.stage == OriginOddJobStage.AUCTION_INSPECT) { OriginMenus.beginDialogFlow(player); show(player) }
        } else {
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
        OriginMenus.openDialog(player, originOddJobsScreen(config, venue, null, rewards.amount, null, clock()),
            onDismiss = { requests.remove(playerId, request) })
        rewards.status(playerId).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(playerId, request) || closed || !near(player, venue.npcId) || isWorking(playerId)) return@runSync
            val key = if (failure != null || state == null) "cash-unavailable" else originOddJobStatusKey(state, clock())
            if (!start) {
                OriginMenus.openDialog(player, originOddJobsScreen(config, venue, null, rewards.amount, state, clock(),
                    notice = if (failure != null) config.line("cash-unavailable") else null,
                    start = { status(player, venue, true) }), reopen = { status(player, venue, false) })
                return@runSync
            }
            val rejected = otherWork(playerId) ?: key ?: when {
                !workMode(player) -> "work-mode"
                !available(venue.kind, refresh = true) -> "unavailable"
                else -> null
            }
            if (rejected != null) { feedback(player, rejected, "seconds" to waiterSeconds((state?.nextAtMillis ?: clock()) - clock())); return@runSync }
            val score = OriginWorkShift.begin(clock(), config.shiftMillis)
            shifts[playerId] = Shift(venue, score, OriginOddJobDispatch(venue.kind))
            info("ORIGIN_ODD_JOBS phase=SHIFT_STARTED player={} shift={} kind={}", playerId, score.id, venue.kind)
            show(player)
        } }
    }

    private fun accept(player: Player, shiftId: UUID, offerId: UUID) {
        val id = player.uniqueId; val shift = shifts[id]?.takeIf { it.score.id == shiftId } ?: return
        if (closed || !shift.score.acceptsOrders(clock()) || id in accepted || progress.current(id) != null || !near(player, shift.venue.npcId)) return
        val offer = shift.dispatch.offers(shift.score.phase(clock())).firstOrNull { it.id == offerId } ?: return
        if (!workMode(player) || otherWork(id) != null || !available(shift.venue.kind, refresh = true)) { feedback(player, "unavailable"); return }
        if (id in requests) return
        val request = UUID.randomUUID(); requests[id] = request; val epoch = tasks.token()
        rewards.status(id).whenComplete { state, failure -> tasks.runSync(epoch) {
            if (!requests.remove(id, request) || closed || shifts[id] !== shift || !shift.score.acceptsOrders(clock()) ||
                progress.current(id) != null || id in accepted || !near(player, shift.venue.npcId)) return@runSync
            val gate = when {
                failure != null || state == null || !state.available -> "cash-unavailable"
                state.unresolved -> "unconfirmed"
                state.remainingOrders <= 0 -> "daily-limit"
                !workMode(player) || otherWork(id) != null || !available(shift.venue.kind, refresh = true) -> "unavailable"
                else -> null
            }
            if (gate != null) { feedback(player, gate); finishRequested(player); return@runSync }
            val lease = if (shift.venue.scene != null) acquire(shift.venue) else null
            if (shift.venue.scene != null && lease == null) { feedback(player, "busy"); return@runSync }
            if (shift.dispatch.accept(offerId) == null) { lease?.let(release); return@runSync }
            val job = progress.begin(id, offer, clock(), shift.score.hardStopAt)
            if (job == null) { lease?.let(release); return@runSync }
            if (lease != null) leases[id] = lease
            if (!render(player, job, clock())) return@runSync
            info("ORIGIN_ODD_JOBS phase=STARTED player={} shift={} request={} kind={} variant={}", id, shiftId, job.id, job.kind, job.variant)
            if (job.kind == OriginOddJobKind.AUCTION) show(player) else guide(player, job, clock(), true)
        } }
    }

    private fun show(player: Player, notice: String? = null) {
        val id = player.uniqueId; val shift = shifts[id] ?: return; val job = progress.current(id)
        val atMaster = near(player, shift.venue.npcId)
        val atTask = contextNear(player, job, shift.venue)
        if (closed || !atMaster && !atTask) return
        val offers = if (job == null && id !in accepted && shift.score.acceptsOrders(clock())) shift.dispatch.offers(shift.score.phase(clock())) else emptyList()
        OriginMenus.openDialog(player, originOddJobsScreen(config, shift.venue, job, rewards.amount, null, clock(), notice,
            shift.score, offers, paying = id in accepted, actionsAvailable = atTask,
            accept = { accept(player, shift.score.id, it) },
            choose = { choice -> choose(player, job?.id, choice) },
            finishShift = { if (shifts[id] === shift) finishRequested(player) },
            cancel = { if (progress.current(id)?.id == job?.id && (near(player, shift.venue.npcId) || contextNear(player, progress.current(id), shift.venue))) {
                cancelOrder(id, "cancelled"); feedback(player, "cancelled"); finishIfReady(id, clock())
            } }), reopen = { if (shifts[id] === shift) show(player) })
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
        if (current?.kind == OriginOddJobKind.AUCTION)
            show(player, (step as? OriginOddJobStep.Rejected)?.let { config.line(rejectionKey(it.reason)) })
    }

    /** Every intercepted action still passes the actual block, reach and line-of-sight checks. */
    fun work(player: Player, left: Boolean, clicked: Block?): Boolean {
        val job = progress.current(player.uniqueId) ?: return false
        val wanted = if (job.stage == OriginOddJobStage.FORGE_HAMMER && clicked != null &&
            stations[OriginOddJobStationId.FIRE]?.block == clicked) OriginOddJobStationId.FIRE else originOddJobStation(job.stage)
        val station = wanted?.let(stations::get) ?: return false
        if (!originOddJobAimed(player, station, config.range, clicked)) return false
        if (!valid(player, job)) { stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); return true }
        if (duplicate(player)) return true
        if (originOddJobChoices(config, job).isNotEmpty()) {
            OriginMenus.beginDialogFlow(player); show(player); return true
        }
        val action = when (job.stage) {
            OriginOddJobStage.YARD_FEED -> OriginOddJobAction.FEED
            OriginOddJobStage.YARD_DRAIN, OriginOddJobStage.YARD_CLEAN, OriginOddJobStage.YARD_FILL, OriginOddJobStage.YARD_FILLING -> OriginOddJobAction.WATER
            OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobAction.HEAT
            OriginOddJobStage.FORGE_PLACE -> OriginOddJobAction.PLACE
            OriginOddJobStage.FORGE_HAMMER -> when {
                wanted == OriginOddJobStationId.FIRE -> OriginOddJobAction.HEAT
                left -> OriginOddJobAction.HAMMER
                player.isSneaking -> OriginOddJobAction.CHECK
                else -> OriginOddJobAction.ROTATE
            }
            OriginOddJobStage.FORGE_QUENCH -> OriginOddJobAction.QUENCH
            else -> return false
        }
        if (job.stage != OriginOddJobStage.FORGE_HAMMER && left) { guide(player, job, clock(), true); return true }
        apply(player, progress.act(player.uniqueId, action, clock(), originOddJobZone(player, station)))
        return true
    }

    private fun apply(player: Player, step: OriginOddJobStep) {
        when (step) {
            is OriginOddJobStep.Rejected -> {
                if (step.reason == OriginOddJobRejection.EXPIRED) { cancelOrder(player.uniqueId, "expired"); finishIfReady(player.uniqueId, clock()) }
                else step.job?.let { if (!render(player, it, clock())) return }
                feedback(player, rejectionKey(step.reason))
            }
            is OriginOddJobStep.Changed -> {
                if (step.job.stage == OriginOddJobStage.PAYOUT) { acceptResult(player, step.job); return }
                if (!render(player, step.job, clock())) return
                guide(player, step.job, clock(), true)
                player.playSound(player.location, if (step.job.stage == OriginOddJobStage.FORGE_HAMMER) Sound.BLOCK_ANVIL_USE else Sound.UI_BUTTON_CLICK,
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
        rewards.pay(OriginSideJobKind.valueOf(result.job.kind.name), id, result.job.id,
            stillValid = { !closed && accepted[id] === result  }) onPaid@ { outcome ->
            if (accepted[id] !== result) return@onPaid
            result.inFlight = false
            if (outcome == SideJobRewardOutcome.COOLDOWN) {
                result.nextAttempt = clock() + rewards.payoutIntervalMillis
                feedback("payment-cooldown"); return@onPaid
            }
            accepted.remove(id, result)
            val shift = shifts[id]?.takeIf { it.score.id == result.shiftId }
            if (outcome in setOf(SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID)) {
                if (shift != null) shift.score = shift.score.record(result.job.id, rewards.amount, result.quality)
                feedback("paid", "amount" to rewards.amount, "quality" to result.quality)
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
        feedback(player, "shift.closing"); finishIfReady(player.uniqueId, clock())
    }

    private fun finishIfReady(id: UUID, now: Long) {
        val shift = shifts[id] ?: return
        if (now >= shift.score.hardStopAt) cancelOrder(id, "expired")
        if (id in accepted || progress.current(id) != null || !shift.score.shouldFinish(now, false)) return
        shifts.remove(id, shift); requests.remove(id); lastClicks.remove(id)
        Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.sendMessage(waiterText("\n  " + config.line("shift.receipt",
            "done" to shift.score.completedOrders, "amount" to shift.score.earnedAmount,
            "quality" to if (shift.score.completedOrders == 0) 0 else shift.score.quality) + "\n"))
        info("ORIGIN_ODD_JOBS phase=SHIFT_FINISHED player={} shift={} orders={} amount={}", id, shift.score.id, shift.score.completedOrders, shift.score.earnedAmount)
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
                job != null && render(player, job, now) -> guide(player, job, now)
                job == null && id !in accepted && (noticeUntil[id] ?: 0) <= now -> {
                    if (shift.score.acceptsOrders(now)) player.sendActionBar(waiterText(config.line("shift.board-guide", "name" to shift.venue.name,
                        "clock" to originOddJobClock(shift.score.closesAt - now), "done" to shift.score.completedOrders)))
                }
            }
            finishIfReady(id, now)
        }
    }

    private fun render(player: Player, job: OriginOddJobSession, now: Long): Boolean = try {
        val prop = when (job.stage) {
            OriginOddJobStage.YARD_FEED, OriginOddJobStage.YARD_LOAD -> OriginJobProp.HAY
            OriginOddJobStage.YARD_FILL, OriginOddJobStage.YARD_FILLING -> OriginJobProp.WATER_BUCKET
            OriginOddJobStage.FORGE_HEAT -> if (job.variant == OriginOddJobVariant.FORGE_NEW) OriginJobProp.FORGE_BLANK else OriginJobProp.FORGE_COOLED
            OriginOddJobStage.FORGE_HEATING -> if (now - job.stageStartedAt >= originOddJobHeatReady(job)) OriginJobProp.FORGE_HEATED else OriginJobProp.FORGE_BLANK
            OriginOddJobStage.FORGE_PLACE -> OriginJobProp.FORGE_HEATED
            OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.FORGE_INSPECT -> OriginJobProp.FORGE_HORSESHOE
            OriginOddJobStage.FORGE_QUENCH -> OriginJobProp.FORGE_HORSESHOE
            OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK -> when (job.parcel) {
                OriginAuctionParcel.VASE -> OriginJobProp.AUCTION_VASE
                OriginAuctionParcel.BOOK -> OriginJobProp.AUCTION_BOOK
                else -> OriginJobProp.AUCTION_BLADE
            }
            OriginOddJobStage.AUCTION_LOAD, OriginOddJobStage.AUCTION_DELIVER -> OriginJobProp.AUCTION_PACKAGE
            OriginOddJobStage.RETURN -> if (job.kind == OriginOddJobKind.FORGE) OriginJobProp.FORGE_COOLED else null
            else -> null
        }
        val placed = job.stage in setOf(OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HAMMER)
        val forgeProfile = job.forge?.takeIf { forge -> placed || job.stage in setOf(OriginOddJobStage.FORGE_QUENCH, OriginOddJobStage.RETURN) ||
            job.stage in setOf(OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING, OriginOddJobStage.FORGE_PLACE) &&
                (job.variant != OriginOddJobVariant.FORGE_NEW || forge.shape.any { it > 0 }) }
        if (props[player.uniqueId] != (prop to placed)) {
            if (prop == null) { visuals.clear(player.uniqueId, job.id); targets.remove(player.uniqueId) }
            else if (forgeProfile == null) {
                if (placed) visuals.place(player, job.id, stations.getValue(OriginOddJobStationId.ANVIL).point, prop)
                else visuals.carry(player, job.id, prop)
            }
            props[player.uniqueId] = prop to placed; renderedForge.remove(player.uniqueId); renderedCleaning.remove(player.uniqueId)
        }
        if (forgeProfile != null) {
            val heated = when (job.stage) {
                OriginOddJobStage.RETURN -> false
                OriginOddJobStage.FORGE_HEATING -> now - job.stageStartedAt >= originOddJobHeatReady(job)
                else -> now < forgeProfile.heatUntil
            }
            if (renderedForge[player.uniqueId] != (forgeProfile to heated)) {
                if (placed) visuals.forgeProfile(player, job.id, stations.getValue(OriginOddJobStationId.ANVIL).point, forgeProfile, heated)
                else visuals.forgeCarry(player, job.id, forgeProfile, heated)
                renderedForge[player.uniqueId] = forgeProfile to heated
            }
        }
        if (job.stage == OriginOddJobStage.YARD_CLEAN && job.yard != null && renderedCleaning[player.uniqueId] != job.yard.cleanMask) {
            visuals.cleaning(player, job.id, stations.getValue(OriginOddJobStationId.WATER).point, job.yard.cleanMask)
            renderedCleaning[player.uniqueId] = job.yard.cleanMask
        } else if (job.stage != OriginOddJobStage.YARD_CLEAN && renderedCleaning.remove(player.uniqueId) != null && prop == null) {
            visuals.clear(player.uniqueId, job.id)
        }
        val stationId = originOddJobStation(job.stage)
        val caption = if (job.stage == OriginOddJobStage.FORGE_HAMMER) config.line("forge.zone-target", "zone" to config.line("forge.zone-${job.forge?.zone}"))
            else stationId?.let { config.line("targets.${it.name.lowercase()}") }.orEmpty()
        // Update the label even at the same station when its current operation changes.
        visuals.target(player, job.id, stationId?.let { stations.getValue(it).point }, caption)
        if (stationId == null) targets.remove(player.uniqueId) else targets[player.uniqueId] = stationId
        val npcId = when (job.stage) {
            OriginOddJobStage.AUCTION_VERIFY, OriginOddJobStage.AUCTION_DELIVER, OriginOddJobStage.AUCTION_RECEIPT -> config.handoffNpcId
            OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK,
            OriginOddJobStage.AUCTION_LOAD, OriginOddJobStage.AUCTION_DOCUMENT, OriginOddJobStage.RETURN -> config.venue(job.kind).npcId
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
        stop(player.uniqueId, "unavailable"); feedback(player, "unavailable"); false
    }

    private fun guide(player: Player, job: OriginOddJobSession, now: Long, force: Boolean = false) {
        if (!force && (noticeUntil[player.uniqueId] ?: 0) > now) return
        val shift = shifts[player.uniqueId]?.score ?: return
        val ready = originOddJobHammerReady(job, now) || job.stage == OriginOddJobStage.FORGE_HEATING &&
            now - job.stageStartedAt in originOddJobHeatReady(job)..originOddJobHeatReady(job) + 3_000
        player.sendActionBar(waiterText(config.line("shift.hud", "guide" to originOddJobGuide(config, job, now),
            "clock" to originOddJobClock((if (shift.acceptsOrders(now)) shift.closesAt else shift.hardStopAt) - now),
            "done" to shift.completedOrders, "quality" to job.quality), TextColor.color(if (ready) 0x9BD48D else 0xE8DFD2)))
    }

    private fun rejectionKey(reason: OriginOddJobRejection): String = "errors.${reason.name.lowercase().replace('_', '-')}"

    private fun available(kind: OriginOddJobKind, refresh: Boolean = false): Boolean {
        if (actor(config.venue(kind).npcId) == null || kind == OriginOddJobKind.AUCTION && actor(config.handoffNpcId) == null) return false
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
        (config.venue(job.kind).scene == null || leases[player.uniqueId]?.let(owns) == true)
    private fun workMode(player: Player): Boolean = player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE
    private fun inArea(player: Player, kind: OriginOddJobKind): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != worldName) return false
        val npc = actor(config.venue(kind).npcId) ?: return false
        return player.location.distanceSquared(npc.location) <= config.workRange * config.workRange
    }
    private fun actor(id: Int): LivingEntity? {
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
    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) = message(player, config.line(key, *values))
    private fun message(player: Player, text: String) { noticeUntil[player.uniqueId] = clock() + 1_500; player.sendActionBar(waiterText(text)) }
    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        Bukkit.getPlayer(playerId)?.let { if (entity.isValid) OriginServices.viewerGlow.setEntityGlowingFor(entity, it, false) }
    }
    private fun cleanup(playerId: UUID, requestId: UUID) {
        try {
            runCatching { visuals.clear(playerId, requestId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=VISUAL_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
            runCatching { clearGlow(playerId) }.onFailure { warn("ORIGIN_ODD_JOBS phase=GLOW_CLEAR_FAILED player={} request={}", playerId, requestId, it) }
        } finally {
            props.remove(playerId); targets.remove(playerId); renderedForge.remove(playerId); renderedCleaning.remove(playerId); noticeUntil.remove(playerId)
            leases.remove(playerId)?.let(release)
        }
    }
    private fun cancelOrder(playerId: UUID, reason: String) {
        val job = progress.cancel(playerId) ?: return
        cleanup(playerId, job.id)
        info("ORIGIN_ODD_JOBS phase=CANCELLED player={} request={} kind={} reason={}", playerId, job.id, job.kind, reason)
    }
    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId); lastClicks.remove(playerId); cancelOrder(playerId, reason)
        if (reason == "reload") { accepted.remove(playerId); shifts.remove(playerId) }
        else {
            shifts[playerId]?.let { it.score = it.score.requestFinish() }
            finishIfReady(playerId, clock())
        }
    }
    override fun close() {
        closed = true; tasks.close(); requests.clear()
        shifts.keys.toList().forEach { stop(it, "reload") }
        accepted.clear(); visuals.close(); rewards.close(); lastClicks.clear(); noticeUntil.clear()
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
