package ru.arc.origin.scene

import com.denizenscript.denizen.objects.NPCTag
import dev.lone.itemsadder.api.CustomStack
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.ArcOriginPlugin
import ru.arc.origin.OriginNpcHolograms
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteEvent
import ru.arc.npc.NpcRouteObstacleSource
import ru.arc.npc.NpcRouteProfile
import ru.arc.npc.smoothNpcRotation
import ru.arc.observability.StructuredDebugLine
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.util.SoundUtils
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Kotlin owner of Origin's non-interactive forge and mount-yard choreography.
 *
 * Service/shop clicks remain owned by their dedicated handlers. A click merely
 * interrupts this ambient lease, cleans up at the current position, and lets
 * the service handler take over. Lifecycle aborts still return actors home.
 */
object OriginAmbientScenesModule : PluginModule, Listener {
    override val name = "OriginAmbientScenes"
    override val priority = 27

    private var service: OriginSceneService? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            warn("ORIGIN_SCENE phase=DISABLED reason=citizens-unavailable")
            return
        }
        val current = OriginSceneService(OriginScenePlan.load(ArcOriginPlugin.instance.legacyDataPath))
        service = current
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        current.start()
    }

    override fun reload() {
        // Validate before replacing the running generation: a bad edit must not
        // tear down the last usable scene plan.
        val replacement = try {
            OriginScenePlan.load(ArcOriginPlugin.instance.legacyDataPath)
        } catch (failure: Exception) {
            warn("ORIGIN_SCENE phase=RELOAD_REJECTED previous_runtime=retained", failure)
            return
        }
        if (service == null) {
            init()
            return
        }
        service?.close()
        service = OriginSceneService(replacement).also { it.start() }
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        service?.close()
        service = null
    }

    fun cycleKeys(): List<OriginSceneCycleKey> = service?.cycleKeys().orEmpty()

    fun status(): List<OriginSceneStatus> = service?.status().orEmpty()

    fun startCycle(sceneId: String, cycleId: String): OriginSceneStartResult =
        service?.startManual(sceneId, cycleId) ?: OriginSceneStartResult.Unavailable("scene-engine-not-ready")

    fun interruptActor(actorId: Int, reason: String) {
        service?.interruptActor(actorId, reason)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onNpcClick(event: NPCRightClickEvent) {
        service?.interruptActor(
            event.npc.id,
            "player-click",
            OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION,
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSceneActorChangesCrop(event: EntityChangeBlockEvent) {
        val actorId = CitizensAPI.getNPCRegistry().getNPC(event.entity)?.id ?: return
        if (service?.protectsCrop(event.block.world.name, actorId, event.block.type) == true) {
            event.isCancelled = true
        }
    }
}

data class OriginSceneCycleKey(val sceneId: String, val cycleId: String)

/**
 * Chooses a safe route profile for a cycle actor's home recovery.
 *
 * A cycle's own MOVE/MOVE_GROUP profile is authoritative when it accepts the
 * authored home level and cell. Profiles from unrelated cycles remain a
 * compatibility fallback for legacy flat homes.
 */
internal fun selectOriginSceneHomeProfile(
    routeProfiles: Map<String, NpcRouteProfile>,
    cycle: OriginSceneCycle,
    actorId: Int,
    home: Location,
): NpcRouteProfile? {
    val cycleProfileIds = cycle.steps.flatMap { step ->
        when (step) {
            is OriginSceneStep.Move -> listOf(step.routeProfile).takeIf { step.actorId == actorId }.orEmpty()
            is OriginSceneStep.MoveGroup -> listOf(step.routeProfile).takeIf { actorId in step.actorIds }.orEmpty()
            else -> emptyList()
        }
    }.distinct()
    val homeCell = NpcRouteCell(home.blockX, home.blockZ)
    fun accepts(profile: NpcRouteProfile): Boolean {
        val authoredLevel = (profile.floorY - profile.surfaceSearchRange)..(profile.floorY + profile.surfaceSearchRange)
        return home.blockY in authoredLevel && profile.allows(homeCell)
    }
    return cycleProfileIds.asSequence()
        .mapNotNull(routeProfiles::get)
        .firstOrNull(::accepts)
        ?: routeProfiles.values.firstOrNull(::accepts)
}

sealed interface OriginSceneStartResult {
    data class Started(val key: OriginSceneCycleKey) : OriginSceneStartResult
    data class Unknown(val sceneId: String, val cycleId: String) : OriginSceneStartResult
    data class Busy(val key: OriginSceneCycleKey, val reason: String) : OriginSceneStartResult
    data class Unavailable(val reason: String) : OriginSceneStartResult
}

private data class ActiveOriginSceneCycle(
    val lease: OriginSceneLease,
    val scene: OriginSceneDefinition,
    val cycle: OriginSceneCycle,
    val mountedPairs: MutableSet<Pair<Int, Int>> = mutableSetOf(),
    val resources: OriginSceneResources = OriginSceneResources(),
    val followingDisplays: MutableMap<String, OriginSceneStep.Display> = mutableMapOf(),
    val followingDisplayPoses: MutableMap<Int, OriginSceneFollowPose> = mutableMapOf(),
    var followingDisplayRefreshScheduled: Boolean = false,
    val speechActors: MutableSet<Int> = mutableSetOf(),
    val manual: Boolean = false,
    val startedNanos: Long = System.nanoTime(),
) {
    lateinit var execution: OriginSceneExecution
}

private data class OriginSceneGroupRoute(
    val actor: NPC,
    val destination: Location,
    val explicitPose: Boolean,
    val via: List<Location>,
)

private data class OriginSceneSpeech(val display: TextDisplay, val owner: UUID)

private class OriginSceneService(
    private val plan: OriginScenePlan,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val coordinator = OriginSceneCoordinator()
    private val routeController = CitizensNpcRouteController(::logRouteEvent, NpcRouteObstacleSource(::originFurnitureObstacleCells))
    private val active = mutableMapOf<UUID, ActiveOriginSceneCycle>()
    private val speech = mutableMapOf<Int, OriginSceneSpeech>()
    private val lastResults = mutableMapOf<OriginSceneCycleKey, String>()
    private val cropProtection = OriginSceneCropProtection.from(plan)
    private var closed = false

    fun protectsCrop(world: String, actorId: Int, material: Material): Boolean =
        cropProtection.protects(world, actorId, material)

    fun start() {
        removeAbandonedDisplays()
        val now = System.currentTimeMillis()
        plan.scenes.forEach { scene ->
            scene.cycles.forEach { cycle ->
                coordinator.delay(scene.id, cycle.id, now + cycle.initialDelayMillis)
            }
        }
        tasks.runTimer(plan.tickTicks, plan.tickTicks, ::tick)
        info(
            "ORIGIN_SCENE phase=READY world={} scenes={} cycles={} owner=arc-kotlin",
            plan.world,
            plan.scenes.joinToString(",") { it.id },
            plan.scenes.sumOf { it.cycles.size },
        )
    }

    fun cycleKeys(): List<OriginSceneCycleKey> = plan.scenes.flatMap { scene ->
        scene.cycles.map { cycle -> OriginSceneCycleKey(scene.id, cycle.id) }
    }

    fun status(): List<OriginSceneStatus> {
        val now = System.currentTimeMillis()
        val busyActors = coordinator.busyActors()
        return plan.scenes.flatMap { scene ->
            scene.cycles.map { cycle ->
                val running = active.values.firstOrNull { it.scene.id == scene.id && it.cycle.id == cycle.id }
                val cooldown = if (running == null) coordinator.cooldownRemainingMillis(scene.id, cycle.id, now) else 0L
                val reason = when {
                    running != null -> running.execution.reason ?: "working"
                    Bukkit.getWorld(plan.world) == null -> "world-unavailable"
                    !hasAudience(scene) -> "no-audience"
                    cooldown > 0L -> "cooldown"
                    cycle.actorIds.any(busyActors::contains) -> "actor-leased"
                    playerOccupiesYieldZone(scene, cycle) -> "player-proximity"
                    cycle.actorIds.any { npc(it)?.isSpawned != true } -> "actor-unavailable"
                    cycle.actorIds.mapNotNull(::npc).any { !available(it, scene) } -> "actor-service-or-route"
                    active.values.count { it.scene.id == scene.id } >= scene.maxConcurrentCycles -> "scene-capacity"
                    else -> "queued"
                }
                OriginSceneStatus(
                    scene.id, cycle.id, running?.execution?.phase?.name ?: "WAITING",
                    running?.execution?.stepId, running?.execution?.stepIndex ?: -1, cycle.steps.size,
                    cycle.actorIds.sorted(), running?.resources?.displayCount ?: 0,
                    running?.let { (System.nanoTime() - it.startedNanos).coerceAtLeast(0L) / 1_000_000L } ?: 0L,
                    cooldown, reason, lastResults[OriginSceneCycleKey(scene.id, cycle.id)],
                )
            }
        }
    }

    private fun reportFailure(running: ActiveOriginSceneCycle, stage: String, failure: Exception) {
        warn(FAILURE_LINE.line(
            "phase" to "FAILED", "scene" to running.scene.id, "cycle" to running.cycle.id,
            "stage" to stage, "step" to running.execution.stepId,
            "actors" to running.cycle.actorIds.sorted().joinToString(","),
            "run" to running.lease.token, "recovery" to "cleanup-return-release",
        ), failure)
    }

    fun startManual(sceneId: String, cycleId: String): OriginSceneStartResult {
        if (closed) return OriginSceneStartResult.Unavailable("scene-engine-closed")
        val scene = plan.scenes.firstOrNull { it.id == sceneId }
            ?: return OriginSceneStartResult.Unknown(sceneId, cycleId)
        val cycle = scene.cycles.firstOrNull { it.id == cycleId }
            ?: return OriginSceneStartResult.Unknown(sceneId, cycleId)
        val key = OriginSceneCycleKey(sceneId, cycleId)
        if (playerOccupiesYieldZone(scene, cycle)) return OriginSceneStartResult.Busy(key, "player-near-yield-anchor")

        active.values.filter { running -> !running.manual && running.cycle.actorIds.any(cycle.actorIds::contains) }
            .toList()
            .forEach { interruptCycle(it, "manual-preempt") }

        val actors = cycle.actorIds.mapNotNull(::npc).takeIf { it.size == cycle.actorIds.size }
            ?: return OriginSceneStartResult.Unavailable("actor-missing")
        val blocked = actors.firstOrNull { !available(it, scene) }
        if (blocked != null) return OriginSceneStartResult.Busy(key, "actor-${blocked.id}-busy")

        val lease = coordinator.tryAcquire(scene.id, cycle.id, cycle.actorIds, System.currentTimeMillis(), ignoreDue = true)
            ?: return OriginSceneStartResult.Busy(key, "actor-lease-busy")
        launch(scene, cycle, lease, manual = true)
        return OriginSceneStartResult.Started(key)
    }

    private fun tick() {
        if (closed) return
        if (Bukkit.getWorld(plan.world) == null) {
            active.values.toList().forEach { interruptCycle(it, "world-unavailable") }
            return
        }
        active.values.filter { it.cycle.actorIds.any { id -> npc(id)?.isSpawned != true } }
            .toList().forEach { interruptCycle(it, "actor-unavailable") }
        active.values.filter { playerOccupiesYieldZone(it.scene, it.cycle) }
            .toList()
            .forEach { interruptCycle(it, "player-proximity") }
        val now = System.currentTimeMillis()
        for (scene in plan.scenes.shuffled()) {
            if (!hasAudience(scene)) continue
            if (active.values.count { it.scene.id == scene.id } >= scene.maxConcurrentCycles) continue
            val cyclesById = scene.cycles.associateBy { it.id }
            val readyCycleIds = coordinator.readyCycleIds(scene.id, scene.cycles.associate { it.id to it.actorIds }, now)
            for (cycleId in readyCycleIds) {
                val cycle = cyclesById.getValue(cycleId)
                if (playerOccupiesYieldZone(scene, cycle)) {
                    coordinator.delay(scene.id, cycle.id, now + scene.retryMillis)
                    continue
                }
                val actors = cycle.actorIds.mapNotNull(::npc).takeIf { it.size == cycle.actorIds.size }
                if (actors == null || actors.any { !available(it, scene) }) {
                    coordinator.delay(scene.id, cycle.id, now + scene.retryMillis)
                    continue
                }
                val lease = coordinator.tryAcquire(scene.id, cycle.id, cycle.actorIds, now) ?: continue
                launch(scene, cycle, lease, manual = false)
                break
            }
        }
    }

    private fun launch(scene: OriginSceneDefinition, cycle: OriginSceneCycle, lease: OriginSceneLease, manual: Boolean) {
        val running = ActiveOriginSceneCycle(lease, scene, cycle, manual = manual)
        running.execution = OriginSceneExecution(cycle.stepIds, cycle.maxDurationTicks, object : OriginSceneExecutionEffects {
            override fun execute(stepIndex: Int) = executeStep(running, stepIndex)
            override fun cleanup(keepMounted: Boolean) = cleanup(running, keepMounted)
            override fun returnHome(reason: String, immediate: Boolean) {
                if (immediate) restoreHomePoses(running, keepMounted = false)
                else returnHome(running, reason, keepMounted = reason == "complete")
            }
            override fun release(reason: String) = release(running, reason)
            override fun reportFailure(stage: String, failure: Exception) = reportFailure(running, stage, failure)
        })
        active[lease.token] = running
        info(
            "ORIGIN_SCENE phase=CYCLE_STARTED scene={} cycle={} actors={} trigger={}",
            scene.id,
            cycle.id,
            cycle.actorIds.sorted().joinToString(","),
            if (manual) "manual" else "ambient",
        )
        try {
            cycle.actorIds.mapNotNull(::npc).forEach { it.entity.addScoreboardTag(BUSY_TAG) }
            running.execution.start()
        } catch (failure: Exception) {
            reportFailure(running, "start", failure)
            running.execution.interrupt("start-failed")
        }
    }

    private fun hasAudience(scene: OriginSceneDefinition): Boolean {
        val rangeSquared = scene.audienceRange * scene.audienceRange
        return Bukkit.getOnlinePlayers().any { player ->
            player.world.name == plan.world && player.location.distanceSquared(scene.anchor.inWorld(player.world)) <= rangeSquared
        }
    }

    private fun playerOccupiesYieldZone(scene: OriginSceneDefinition, cycle: OriginSceneCycle): Boolean {
        val anchorId = cycle.yieldAnchor ?: return false
        val world = Bukkit.getWorld(plan.world) ?: return false
        val anchor = scene.anchors.getValue(anchorId).inWorld(world)
        val rangeSquared = cycle.yieldRange * cycle.yieldRange
        return world.players.any { it.location.distanceSquared(anchor) <= rangeSquared }
    }

    private fun available(npc: NPC, scene: OriginSceneDefinition): Boolean {
        if (!npc.isSpawned || npc.entity.world.name != plan.world || routeController.isNavigating(npc)) return false
        val flags = scene.actors.getValue(npc.id).deniedDenizenFlags
        return flags.none { hasDenizenFlag(npc, it) }
    }

    private fun runStep(running: ActiveOriginSceneCycle, index: Int) = running.execution.advance(index)

    private fun executeStep(running: ActiveOriginSceneCycle, index: Int) {
        if (hasDeniedFlag(running)) {
            finish(running, "denizen-busy")
            return
        }

        when (val step = running.cycle.steps[index]) {
            is OriginSceneStep.Move -> move(running, index, step)
            is OriginSceneStep.MoveGroup -> moveGroup(running, index, step)
            is OriginSceneStep.Wait -> later(running, step.ticks) { runStep(running, index + 1) }
            is OriginSceneStep.LookAtAnchor -> {
                npc(step.actorId)?.takeIf(NPC::isSpawned)?.let { actor ->
                    running.resources.faceHorizontal(actor, running.scene.anchors.getValue(step.anchor).inWorld(actor.entity.world))
                }
                runStep(running, index + 1)
            }
            is OriginSceneStep.LookAtSurface -> {
                val target = resolveSurfaceLookLocation(running, step.surface)
                if (target == null) finish(running, "look-surface-missing")
                else {
                    npc(step.actorId)?.takeIf(NPC::isSpawned)?.let { running.resources.facePoint(it, target) }
                    runStep(running, index + 1)
                }
            }
            is OriginSceneStep.LookAtActor -> {
                val target = npc(step.targetActorId)?.takeIf(NPC::isSpawned)
                if (target == null) finish(running, "look-target-missing")
                else {
                    npc(step.actorId)?.takeIf { it.isSpawned && it.entity.world == target.entity.world }
                        ?.let { running.resources.faceHorizontal(it, target.entity.location) }
                    runStep(running, index + 1)
                }
            }
            is OriginSceneStep.Equip -> {
                equip(running, step.actorId, step.material)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Swing -> swing(running, index, step, 0)
            is OriginSceneStep.Display -> if (setSceneDisplay(running, step)) runStep(running, index + 1)
            else finish(running, if (step.followActorId != null) "follow-actor-unavailable" else "prop-apply-failed")
            is OriginSceneStep.RemoveDisplay -> {
                clearFollowingDisplay(running, step.key)
                removeDisplay(running, step.key)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Sound -> {
                playSound(stepLocation(running, step.actorId, step.anchor), step.sound, step.volume, step.pitch)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Particle -> {
                stepLocation(running, step.actorId, step.anchor)?.let { location ->
                    spawnParticle(location, step.particle, step.count, if (step.anchor == null) 1.0 else 0.15)
                }
                runStep(running, index + 1)
            }
            is OriginSceneStep.Speech -> {
                showSpeech(running, step.actorId, step.text)
                runStep(running, index + 1)
            }
            is OriginSceneStep.ContainerLid -> {
                setContainerLid(running, step)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Mount -> {
                val rider = npc(step.actorId)?.takeIf(NPC::isSpawned)?.entity
                val vehicle = npc(step.vehicleActorId)?.takeIf(NPC::isSpawned)?.entity
                val mounted = rider != null && vehicle != null &&
                    (rider.vehicle == vehicle || rider.vehicle == null && vehicle.addPassenger(rider))
                if (!mounted) finish(running, "mount-failed")
                else {
                    running.mountedPairs += step.actorId to step.vehicleActorId
                    runStep(running, index + 1)
                }
            }
            is OriginSceneStep.Dismount -> {
                dismount(running, step.actorId)
                runStep(running, index + 1)
            }
            is OriginSceneStep.Pose -> {
                val actor = npc(step.actorId)?.takeIf(NPC::isSpawned)
                if (actor == null) finish(running, "pose-actor-missing")
                else {
                    running.resources.setPose(actor, step.pose)
                    runStep(running, index + 1)
                }
            }
        }
    }

    private fun move(running: ActiveOriginSceneCycle, index: Int, step: OriginSceneStep.Move) {
        val actor = npc(step.actorId)?.takeIf(NPC::isSpawned)
        val world = Bukkit.getWorld(plan.world)
        if (actor == null || world == null) {
            finish(running, "move-actor-missing")
            return
        }
        val moves = consecutiveOriginSceneMoves(running.cycle.steps, index)
        val finalMove = moves.last()
        val finalPoint = running.scene.anchors.getValue(finalMove.anchor)
        running.resources.clearMovementPose(actor)
        val destination = finalPoint.inWorld(world)
        val via = moves.dropLast(1).map { running.scene.anchors.getValue(it.anchor).inWorld(world) }
        val profile = running.scene.routeProfiles.getValue(step.routeProfile)
        if (!routeController.navigate(
                actor,
                destination,
                profile,
                via = via,
                faceOnArrival = finalPoint.explicitPose,
            )
        ) {
            finish(running, "route-unavailable")
            return
        }
        awaitRoute(running, actor, destination, moves.sumOf { it.timeoutTicks }) { success ->
            if (success) running.execution.advanceAfterCompletedSteps(index, index + moves.size)
            else finish(running, "route-timeout")
        }
    }

    private fun moveGroup(running: ActiveOriginSceneCycle, index: Int, step: OriginSceneStep.MoveGroup) {
        val world = Bukkit.getWorld(plan.world)
        if (world == null) {
            finish(running, "group-world-missing")
            return
        }
        val groups = consecutiveOriginSceneMoveGroups(running.cycle.steps, index)
        val finalGroup = groups.last()
        val routes = step.actorIds.mapIndexed { offset, actorId ->
            val actor = npc(actorId)?.takeIf(NPC::isSpawned)
            actor?.let {
                val point = running.scene.anchors.getValue(finalGroup.targetAnchors[offset])
                val via = groups.dropLast(1).map { group ->
                    running.scene.anchors.getValue(group.targetAnchors[offset]).inWorld(world)
                }
                OriginSceneGroupRoute(it, point.inWorld(world), point.explicitPose, via)
            }
        }
        if (routes.any { it == null }) {
            finish(running, "group-actor-missing")
            return
        }
        val resolvedRoutes = routes.filterNotNull()
        val profile = running.scene.routeProfiles.getValue(step.routeProfile)
        resolvedRoutes.forEach { route ->
            running.resources.clearMovementPose(route.actor)
        }
        var routeFailed = false
        resolvedRoutes.forEach { route ->
            if (!routeController.navigate(
                    route.actor,
                    route.destination,
                    profile,
                    via = route.via,
                    faceOnArrival = route.explicitPose,
                )
            ) routeFailed = true
        }
        if (routeFailed) {
            resolvedRoutes.forEach { routeController.stop(it.actor) }
            finish(running, "group-route-unavailable")
            return
        }
        awaitGroupRoutes(
            running,
            index,
            index + groups.size,
            resolvedRoutes,
            groups.sumOf(OriginSceneStep.MoveGroup::timeoutTicks),
            mutableSetOf(),
        )
    }

    private fun awaitGroupRoutes(
        running: ActiveOriginSceneCycle,
        index: Int,
        nextIndex: Int,
        routes: List<OriginSceneGroupRoute>,
        remainingTicks: Long,
        completed: MutableSet<Int>,
    ) {
        later(running, 2L) {
            if (hasDeniedFlag(running)) {
                routes.forEach { routeController.stop(it.actor) }
                finish(running, "group-denizen-busy")
                return@later
            }
            for (route in routes) {
                if (route.actor.id in completed) continue
                if (!route.actor.isSpawned) {
                    routes.forEach { routeController.stop(it.actor) }
                    finish(running, "group-actor-unavailable")
                    return@later
                }
                if (routeController.isNavigating(route.actor)) continue
                if (routeController.consumeOutcome(route.actor)?.successful != true) {
                    routes.forEach { routeController.stop(it.actor) }
                    finish(running, "group-route-failed")
                    return@later
                }
                completed += route.actor.id
            }
            if (completed.size == routes.size) {
                running.execution.advanceAfterCompletedSteps(index, nextIndex)
            } else if (remainingTicks <= 2L) {
                routes.forEach { routeController.stop(it.actor) }
                finish(running, "group-route-timeout")
            } else {
                awaitGroupRoutes(running, index, nextIndex, routes, remainingTicks - 2L, completed)
            }
        }
    }

    private fun dismount(running: ActiveOriginSceneCycle, actorId: Int) {
        val actor = npc(actorId)?.takeIf(NPC::isSpawned)
        if (actor == null) {
            finish(running, "dismount-actor-missing")
            return
        }
        actor.entity.leaveVehicle()
        actor.entity.eject()
        running.mountedPairs.removeIf { actorId == it.first || actorId == it.second }
    }

    private fun awaitRoute(
        running: ActiveOriginSceneCycle,
        actor: NPC,
        destination: org.bukkit.Location,
        remainingTicks: Long,
        done: (Boolean) -> Unit,
    ) {
        later(running, 2L) {
            if (hasDeniedFlag(running)) done(false)
            else if (!actor.isSpawned) done(false)
            else if (!routeController.isNavigating(actor)) {
                done(routeController.consumeOutcome(actor)?.successful == true)
            }
            else if (remainingTicks <= 2L) done(false)
            else awaitRoute(running, actor, destination, remainingTicks - 2L, done)
        }
    }

    private fun swing(running: ActiveOriginSceneCycle, index: Int, step: OriginSceneStep.Swing, repetition: Int) {
        if (!isCurrent(running)) return
        val actor = npc(step.actorId)?.takeIf(NPC::isSpawned)
        val feedbackLocation = when {
            step.feedbackSurface != null -> resolveSurfaceLocation(running, step.feedbackSurface)
            else -> stepLocation(running, step.actorId, step.feedbackAnchor)
        }
        val lookLocation = when {
            step.feedbackSurface != null -> resolveSurfaceLookLocation(running, step.feedbackSurface)
            else -> feedbackLocation
        }
        // Citizens or a nearby player may alter the head pose between strikes. The
        // strike target owns the work pose, so reacquire it for every animation beat.
        if (lookLocation != null && (step.feedbackAnchor != null || step.feedbackSurface != null)) {
            actor?.let { running.resources.facePoint(it, lookLocation) }
        }
        (actor?.entity as? LivingEntity)?.swingMainHand()
        if (step.damageTargetNpcId != null && step.damageAmount > 0.0) {
            damageNpc(running, actor, step.damageTargetNpcId, step.damageAmount)
        }
        val strike = repetition + 1
        if (step.particle != null && strike % step.particleEvery == 0) {
            spawnParticle(feedbackLocation, step.particle, step.particleCount, if (step.feedbackAnchor == null && step.feedbackSurface == null) 1.0 else 0.15)
        }
        if (step.sound != null && strike % step.soundEvery == 0) {
            playSound(feedbackLocation, step.sound, step.soundVolume, step.soundPitch)
        }
        if (repetition + 1 >= step.repetitions) runStep(running, index + 1)
        else later(running, step.periodTicks) { swing(running, index, step, repetition + 1) }
    }

    private fun resolveSurfaceLocation(running: ActiveOriginSceneCycle, surfaceId: String): Location? {
        val world = Bukkit.getWorld(plan.world) ?: return null
        return running.scene.propSurfaces.getValue(surfaceId).resolve(world)?.inWorld(world)
    }

    private fun resolveSurfaceLookLocation(running: ActiveOriginSceneCycle, surfaceId: String): Location? {
        val world = Bukkit.getWorld(plan.world) ?: return null
        val surface = running.scene.propSurfaces.getValue(surfaceId)
        return surface.resolve(world)?.let(surface::lookTarget)?.inWorld(world)
    }

    private fun setSceneDisplay(
        running: ActiveOriginSceneCycle,
        step: OriginSceneStep.Display,
        emitLog: Boolean = true,
        updateTracker: Boolean = true,
    ): Boolean {
        val world = Bukkit.getWorld(plan.world) ?: return false
        val followActor = step.followActorId?.let { npc(it)?.takeIf(NPC::isSpawned) }
        if (step.followActorId != null && followActor == null) return false
        val followLocation = followActor?.entity?.location
        if (followLocation != null && followLocation.world != world) return false
        val followPose = followLocation?.let { OriginSceneFollowPose(it.x, it.y, it.z, it.yaw) }
        val propAnchor = when (step) {
            is OriginSceneStep.BlockDisplay -> followLocation?.let {
                OriginScenePropContract.actorAnchor(it, step.followOffset)
            } ?: step.surface?.let(running.scene.propSurfaces::getValue)?.resolve(world)
                ?: step.anchor?.let(running.scene.anchors::getValue)
            is OriginSceneStep.ItemDisplay -> followLocation?.let {
                OriginScenePropContract.actorAnchor(it, step.followOffset)
            } ?: step.anchor?.let(running.scene.anchors::getValue)
        }
        if (propAnchor == null) {
            warn(
                "ORIGIN_SCENE phase=PROP_ANCHOR_MISSING scene={} cycle={} key={} follow={} anchor={}",
                running.scene.id,
                running.cycle.id,
                step.key,
                step.followActorId ?: "none",
                step.anchor ?: "none",
            )
            return false
        }
        var actualPoint = propAnchor
        var originLabel = "none"
        val created = when (step) {
            is OriginSceneStep.BlockDisplay -> {
                val rotation = followActor?.let {
                    OriginScenePropContract.actorRelativeRotationY(step.rotationYDegrees, it.entity.yaw)
                } ?: step.rotationYDegrees
                val resolved = OriginScenePropContract.resolve(
                    propAnchor,
                    step.origin,
                    step.offset,
                    step.scale,
                    rotation,
                )
                actualPoint = actualPoint.copy(x = resolved.x, y = resolved.y, z = resolved.z)
                originLabel = step.origin.name
                running.resources.updateDisplay(
                    step.key,
                    world,
                    resolved,
                    step,
                    setOf(PROP_TAG, propRunTag(running)),
                    rotationYDegrees = rotation,
                )
            }
            is OriginSceneStep.ItemDisplay -> {
                val yaw = OriginScenePropContract.itemDisplayYaw(
                    followLocation?.yaw ?: propAnchor.yaw,
                    step.yawOffsetDegrees,
                )
                actualPoint = propAnchor.copy(
                    x = propAnchor.x + step.offset.x,
                    y = propAnchor.y + step.offset.y,
                    z = propAnchor.z + step.offset.z,
                    yaw = yaw,
                    pitch = 0f,
                )
                originLabel = "item-context:${step.context}"
                val stack = runCatching { CustomStack.getInstance(step.itemId)?.itemStack?.clone() }.getOrNull()
                if (stack == null) {
                    warn(
                        "ORIGIN_SCENE phase=ITEM_DISPLAY_UNAVAILABLE scene={} cycle={} key={} item={}",
                        running.scene.id,
                        running.cycle.id,
                        step.key,
                        step.itemId,
                    )
                    return false
                }
                running.resources.updateItemDisplay(
                    step.key,
                    Location(world, actualPoint.x, actualPoint.y, actualPoint.z, yaw, 0f),
                    stack,
                    step.context,
                    step.scale,
                    step.interpolationTicks,
                    setOf(PROP_TAG, propRunTag(running)),
                )
            }
        }
        if (followActor != null) {
            trackFollowingDisplay(running, step, followPose, updateTracker)
            if (!running.followingDisplayRefreshScheduled) {
                running.followingDisplayRefreshScheduled = true
                running.execution.repeat(1L) {
                    val current = running.followingDisplays.values.toList()
                    if (current.isEmpty()) {
                        running.followingDisplayPoses.clear()
                        running.followingDisplayRefreshScheduled = false
                        false
                    } else {
                        val actorPoses = mutableMapOf<Int, OriginSceneFollowPose>()
                        val actorsAvailable = current.mapNotNull(OriginSceneStep.Display::followActorId).distinct().all { actorId ->
                            val actor = npc(actorId)?.takeIf(NPC::isSpawned)
                            val location = actor?.entity?.location
                            if (actor == null || location == null || location.world != Bukkit.getWorld(plan.world)) {
                                false
                            } else {
                                actorPoses[actorId] = OriginSceneFollowPose(location.x, location.y, location.z, location.yaw)
                                true
                            }
                        }
                        val changedActors = OriginScenePropContract.changedFollowActors(
                            running.followingDisplayPoses,
                            actorPoses,
                        )
                        val updated = actorsAvailable && current.all { tracked ->
                            val actorId = requireNotNull(tracked.followActorId)
                            val pose = actorPoses[actorId]
                            running.resources.hasDisplay(tracked.key) && pose != null &&
                                (actorId !in changedActors ||
                                    runCatching {
                                        setSceneDisplay(running, tracked, emitLog = false, updateTracker = false)
                                    }.getOrDefault(false))
                        }
                        if (!updated) {
                            running.followingDisplayRefreshScheduled = false
                            running.execution.interrupt("follow-actor-unavailable")
                        } else {
                            changedActors.forEach { actorId ->
                                running.followingDisplayPoses[actorId] = actorPoses.getValue(actorId)
                            }
                        }
                        updated
                    }
                }
            }
        } else {
            clearFollowingDisplay(running, step.key)
        }
        if (emitLog) {
            val support = when (step) {
                is OriginSceneStep.BlockDisplay -> step.surface?.let { "surface:$it" }
                    ?: step.anchor?.let { "anchor:$it" }
                    ?: "actor:${step.followActorId}"
                is OriginSceneStep.ItemDisplay -> step.anchor?.let { "anchor:$it" }
                    ?: "actor:${step.followActorId}"
            }
            info(
                "ORIGIN_SCENE phase=PROP_{} scene={} cycle={} key={} support={} actual={},{},{} origin={}",
                if (created) "SPAWNED" else "UPDATED",
                running.scene.id,
                running.cycle.id,
                step.key,
                support,
                actualPoint.x,
                actualPoint.y,
                actualPoint.z,
                originLabel,
            )
        }
        return true
    }

    private fun trackFollowingDisplay(
        running: ActiveOriginSceneCycle,
        step: OriginSceneStep.Display,
        pose: OriginSceneFollowPose?,
        updateTracker: Boolean,
    ) {
        val previousActor = running.followingDisplays.put(step.key, step)?.followActorId
        val actorId = requireNotNull(step.followActorId)
        if (previousActor != null && previousActor != actorId &&
            running.followingDisplays.values.none { it.followActorId == previousActor }
        ) {
            running.followingDisplayPoses.remove(previousActor)
        }
        if (updateTracker) pose?.let { running.followingDisplayPoses[actorId] = it }
    }

    private fun clearFollowingDisplay(running: ActiveOriginSceneCycle, key: String) {
        val previousActor = running.followingDisplays.remove(key)?.followActorId ?: return
        if (running.followingDisplays.values.none { it.followActorId == previousActor }) {
            running.followingDisplayPoses.remove(previousActor)
        }
    }

    private fun removeDisplay(running: ActiveOriginSceneCycle, key: String) {
        if (running.resources.removeDisplay(key)) {
            info("ORIGIN_SCENE phase=PROP_REMOVED scene={} cycle={} key={}", running.scene.id, running.cycle.id, key)
        }
    }

    private fun removeAbandonedDisplays() {
        Bukkit.getWorld(plan.world)?.let { world ->
            world.getEntitiesByClass(BlockDisplay::class.java)
                .filter { PROP_TAG in it.scoreboardTags }
                .forEach(BlockDisplay::remove)
            world.getEntitiesByClass(ItemDisplay::class.java)
                .filter { PROP_TAG in it.scoreboardTags }
                .forEach(ItemDisplay::remove)
        }
    }

    private fun spawnParticle(location: Location?, particleName: String, count: Int, yOffset: Double) {
        if (location == null) return
        runCatching { Particle.valueOf(particleName) }.onSuccess { particle ->
            location.world.spawnParticle(particle, location.clone().add(0.0, yOffset, 0.0), count, 0.18, 0.12, 0.18, 0.015)
        }
    }

    private fun playSound(location: Location?, soundName: String, volume: Float, pitch: Float) {
        if (location == null) return
        SoundUtils.getSound(soundName)?.let { location.world.playSound(location, it, volume, pitch) }
    }

    private fun damageNpc(
        running: ActiveOriginSceneCycle,
        attacker: NPC?,
        targetNpcId: Int,
        amount: Double,
    ) {
        val sourceNpc = attacker ?: return
        val source = sourceNpc.entity as? LivingEntity ?: return
        val targetNpc = runCatching { CitizensAPI.getNPCRegistry().getById(targetNpcId) }
            .getOrNull()
            ?.takeIf(NPC::isSpawned)
        val target = targetNpc?.entity as? LivingEntity ?: return
        if (target.uniqueId == source.uniqueId || target.world != source.world) return
        val wasProtected = targetNpc.isProtected
        val wasInvulnerable = target.isInvulnerable
        targetNpc.isProtected = false
        target.isInvulnerable = false
        val healthBefore = target.health
        var fallbackApplied = false
        try {
            target.damage(amount, source)
            if (target.isValid && target.health >= healthBefore) {
                // Citizens can still swallow damage for protected NPC implementations
                // after the Bukkit event. Preserve a real HP transition as a fallback.
                target.health = (healthBefore - amount).coerceAtLeast(0.1)
                fallbackApplied = true
            }
        } finally {
            targetNpc.isProtected = wasProtected
            target.isInvulnerable = wasInvulnerable
        }
        info(
            "ORIGIN_SCENE phase=NPC_HIT scene={} cycle={} attacker={} target={} amount={} health-before={} health-after={} fallback={}",
            running.scene.id,
            running.cycle.id,
            sourceNpc.id,
            targetNpc.id,
            amount,
            healthBefore,
            target.health,
            fallbackApplied,
        )
    }

    private fun equip(running: ActiveOriginSceneCycle, actorId: Int, materialName: String) {
        val actor = requireNotNull(npc(actorId)) { "Scene actor $actorId unavailable" }
        val material = requireNotNull(Material.matchMaterial(materialName)) { "Invalid scene material $materialName" }
        running.resources.equip(actor, material)
    }

    private fun setContainerLid(running: ActiveOriginSceneCycle, step: OriginSceneStep.ContainerLid) {
        val world = requireNotNull(Bukkit.getWorld(plan.world)) { "Scene world unavailable" }
        val location = running.scene.anchors.getValue(step.anchor).inWorld(world).block.location
        running.resources.setContainer(location, step.open)
    }

    private fun finish(running: ActiveOriginSceneCycle, reason: String) {
        running.execution.finish(reason)
    }

    private fun cleanup(running: ActiveOriginSceneCycle, keepMounted: Boolean) {
        val failures = mutableListOf<Exception>()
        running.followingDisplays.clear()
        running.followingDisplayPoses.clear()
        running.followingDisplayRefreshScheduled = false
        running.cycle.actorIds.mapNotNull(::npc).forEach { actor ->
            try { routeController.stop(actor) } catch (failure: Exception) { failures += failure }
            if (!keepMounted && actor.isSpawned) {
                try { actor.entity.leaveVehicle() } catch (failure: Exception) { failures += failure }
                try { actor.entity.eject() } catch (failure: Exception) { failures += failure }
            }
        }
        running.resources.cleanup().forEach { failure ->
            failures += IllegalStateException("Scene ${failure.resource} ${failure.id} cleanup failed", failure.failure)
        }
        if (!keepMounted) running.speechActors.forEach { actorId ->
            try {
                OriginNpcHolograms.clearTemporaryBubble(actorId, speechOwner(running))
                speech[actorId]?.takeIf { it.owner == running.lease.token }?.let { bubble ->
                    removeSpeech(actorId, bubble.display)
                }
            } catch (failure: Exception) { failures += failure }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Scene recovery incomplete").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun returnHome(running: ActiveOriginSceneCycle, reason: String, keepMounted: Boolean) {
        info("ORIGIN_SCENE phase=CYCLE_RETURNING scene={} cycle={} reason={}", running.scene.id, running.cycle.id, reason)
        val world = Bukkit.getWorld(plan.world)
        if (world == null) {
            running.execution.completeReturn(reason)
            return
        }
        val routedActorIds = linkedSetOf<Int>()
        originSceneReturnActorIds(running.cycle.actorIds, running.mountedPairs, keepMounted).forEach { actorId ->
            val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return@forEach
            val home = running.scene.actors.getValue(actorId).home.inWorld(world)
            val profile = selectOriginSceneHomeProfile(running.scene.routeProfiles, running.cycle, actorId, home)
            if (profile == null) {
                teleportHome(running, actorId, world)
                return@forEach
            }
            running.resources.clearMovementPose(actor)
            if (routeController.navigate(actor, home, profile, faceOnArrival = true)) routedActorIds += actorId
            else teleportHome(running, actorId, world)
        }
        if (routedActorIds.isEmpty()) {
            running.resources.restorePoses()
            running.execution.completeReturn(reason)
            return
        }
        awaitReturns(running, reason, 400L, world, routedActorIds, mutableSetOf())
    }

    private fun awaitReturns(
        running: ActiveOriginSceneCycle,
        reason: String,
        remainingTicks: Long,
        world: World,
        routedActorIds: Set<Int>,
        completedActorIds: MutableSet<Int>,
    ) {
        later(running, 4L, allowReturning = true) {
            routedActorIds.filterNot(completedActorIds::contains).forEach { actorId ->
                val actor = npc(actorId)?.takeIf(NPC::isSpawned)
                if (actor == null) {
                    completedActorIds += actorId
                } else if (!routeController.isNavigating(actor)) {
                    val successful = routeController.consumeOutcome(actor)?.successful == true
                    if (!successful) teleportHome(running, actorId, world)
                    completedActorIds += actorId
                }
            }
            if (completedActorIds.containsAll(routedActorIds)) {
                running.resources.restorePoses()
                running.execution.completeReturn(reason)
            } else if (remainingTicks <= 4L) {
                routedActorIds.filterNot(completedActorIds::contains).forEach { actorId ->
                    npc(actorId)?.takeIf(NPC::isSpawned)?.let { actor ->
                        routeController.stop(actor)
                        routeController.consumeOutcome(actor)
                    }
                    teleportHome(running, actorId, world)
                    completedActorIds += actorId
                }
                running.resources.restorePoses()
                running.execution.completeReturn("$reason-return-timeout")
            } else awaitReturns(running, reason, remainingTicks - 4L, world, routedActorIds, completedActorIds)
        }
    }

    private fun teleportHome(running: ActiveOriginSceneCycle, actorId: Int, world: World) {
        val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return
        val home = running.scene.actors.getValue(actorId).home.inWorld(world)
        val entity = actor.entity
        val current = entity.location
        if (current.world != home.world || current.distanceSquared(home) > 1.0e-8) {
            val destination = home.clone().apply {
                yaw = (entity as? LivingEntity)?.bodyYaw ?: entity.yaw
                pitch = entity.pitch
            }
            check(entity.teleport(destination)) { "NPC $actorId home teleport rejected" }
        }
        smoothNpcRotation(actor, home.yaw, home.pitch)
    }

    private fun restoreHomePoses(running: ActiveOriginSceneCycle, keepMounted: Boolean) {
        val world = Bukkit.getWorld(plan.world) ?: run {
            running.resources.restorePoses()
            return
        }
        val failures = mutableListOf<Exception>()
        originSceneReturnActorIds(running.cycle.actorIds, running.mountedPairs, keepMounted).forEach { actorId ->
            val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return@forEach
            val home = running.scene.actors.getValue(actorId).home.inWorld(world)
            try {
                check(actor.entity.teleport(home)) { "NPC $actorId home teleport rejected" }
            } catch (failure: Exception) { failures += failure }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Scene home recovery incomplete").apply {
            failures.forEach(::addSuppressed)
        }
        running.resources.restorePoses()
    }

    private fun release(running: ActiveOriginSceneCycle, reason: String) {
        if (active.remove(running.lease.token) !== running) return
        val cooldown = running.cycle.cooldownMillis.random()
        coordinator.release(running.lease, System.currentTimeMillis(), cooldown)
        lastResults[OriginSceneCycleKey(running.scene.id, running.cycle.id)] = reason
        running.cycle.actorIds.mapNotNull(::npc).filter(NPC::isSpawned).forEach { actor ->
            try { actor.entity.removeScoreboardTag(BUSY_TAG) }
            catch (failure: Exception) { reportFailure(running, "busy-tag", failure) }
        }
        info(
            "ORIGIN_SCENE phase=CYCLE_FINISHED scene={} cycle={} reason={} cooldown_seconds={}",
            running.scene.id,
            running.cycle.id,
            reason,
            (cooldown / 1_000.0).roundToInt(),
        )
    }

    fun interruptActor(
        actorId: Int,
        reason: String,
        recoveryPolicy: OriginSceneRecoveryPolicy = OriginSceneRecoveryPolicy.RETURN_HOME,
    ) {
        val running = active.values.firstOrNull { actorId in it.cycle.actorIds } ?: return
        interruptCycle(running, reason, recoveryPolicy)
    }

    private fun interruptCycle(
        running: ActiveOriginSceneCycle,
        reason: String,
        recoveryPolicy: OriginSceneRecoveryPolicy = OriginSceneRecoveryPolicy.RETURN_HOME,
    ) {
        if (isCurrent(running)) running.execution.interrupt(reason, recoveryPolicy)
    }

    private fun speechOwner(running: ActiveOriginSceneCycle): String = "origin-scene:${running.lease.token}"

    private fun showSpeech(running: ActiveOriginSceneCycle, actorId: Int, text: String) {
        running.speechActors += actorId
        if (OriginNpcHolograms.showTemporaryBubble(actorId, listOf(text), plan.speechDurationTicks.toInt(), speechOwner(running))) return
        val actor = npc(actorId)?.takeIf(NPC::isSpawned) ?: return
        speech[actorId]?.let { removeSpeech(actorId, it.display) }
        val display = actor.entity.world.spawn(actor.entity.location.clone().add(0.0, plan.speechHeight, 0.0), TextDisplay::class.java) { created ->
            speech[actorId] = OriginSceneSpeech(created, running.lease.token)
            created.text(Component.text(text, NamedTextColor.GOLD))
            created.billboard = Display.Billboard.CENTER
            created.isShadowed = true
            created.backgroundColor = Color.fromARGB(190, 18, 13, 9)
            created.brightness = Display.Brightness(15, 15)
            created.lineWidth = 190
            created.viewRange = plan.speechViewRange
            created.displayWidth = 4.5f
            created.displayHeight = 2.0f
            created.teleportDuration = 2
            created.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(plan.speechScale), AxisAngle4f())
            created.isPersistent = false
            created.addScoreboardTag(SPEECH_TAG)
            created.addScoreboardTag(speechOwner(running))
        }
        followSpeech(actorId, display, plan.speechDurationTicks)
        tasks.runLater(plan.speechDurationTicks) { removeSpeech(actorId, display) }
    }

    private fun removeSpeech(actorId: Int, display: TextDisplay) {
        val bubble = speech[actorId]?.takeIf { it.display === display } ?: return
        if (display.isValid) display.remove()
        speech.remove(actorId, bubble)
    }

    private fun followSpeech(actorId: Int, display: TextDisplay, remainingTicks: Long) {
        if (speech[actorId]?.display !== display || !display.isValid || remainingTicks <= 0L) return
        val actor = npc(actorId)?.takeIf(NPC::isSpawned)
        if (actor == null) {
            removeSpeech(actorId, display)
            return
        }
        display.teleport(actor.entity.location.clone().add(0.0, plan.speechHeight, 0.0))
        tasks.runLater(2L) { followSpeech(actorId, display, remainingTicks - 2L) }
    }

    private fun stepLocation(running: ActiveOriginSceneCycle, actorId: Int?, anchor: String?) =
        when {
            anchor != null -> Bukkit.getWorld(plan.world)?.let { running.scene.anchors.getValue(anchor).inWorld(it) }
            actorId != null -> npc(actorId)?.takeIf(NPC::isSpawned)?.entity?.location
            else -> null
        }

    private fun later(
        running: ActiveOriginSceneCycle,
        ticks: Long,
        allowReturning: Boolean = false,
        block: () -> Unit,
    ) {
        running.execution.after(ticks, allowReturning, block)
    }

    private fun isCurrent(running: ActiveOriginSceneCycle): Boolean = active[running.lease.token] === running

    private fun propRunTag(running: ActiveOriginSceneCycle): String = "${PROP_TAG}_${running.lease.token.toString().take(8)}"

    private fun hasDeniedFlag(running: ActiveOriginSceneCycle): Boolean =
        running.cycle.actorIds.any { actorId ->
            val actor = npc(actorId) ?: return@any true
            running.scene.actors.getValue(actorId).deniedDenizenFlags.any { hasDenizenFlag(actor, it) }
        }

    private fun npc(id: Int): NPC? = runCatching { CitizensAPI.getNPCRegistry().getById(id) }.getOrNull()

    private fun hasDenizenFlag(npc: NPC, flag: String): Boolean {
        if (flag.isBlank() || !Bukkit.getPluginManager().isPluginEnabled("Denizen")) return false
        return runCatching { NPCTag(npc).hasFlag(flag) }.getOrDefault(false)
    }

    private fun logRouteEvent(event: NpcRouteEvent) {
        info(
            "ORIGIN_SCENE phase=ROUTE_{} profile={} npc={} cells={} actual={},{},{} target={},{},{} reason={}",
            event.phase,
            event.profileId,
            event.npcId,
            event.cells,
            event.actual.x,
            event.actual.y,
            event.actual.z,
            event.target.x,
            event.target.y,
            event.target.z,
            event.reason ?: "none",
        )
    }

    override fun close() {
        closed = true
        tasks.close()
        active.values.toList().forEach { it.execution.close() }
        active.clear()
        coordinator.clear()
        speech.toMap().forEach { (actorId, bubble) ->
            try { removeSpeech(actorId, bubble.display) }
            catch (failure: Exception) { warn("ORIGIN_SCENE phase=FAILED stage=speech-close npc=$actorId", failure) }
        }
        speech.clear()
        routeController.close()
        info("ORIGIN_SCENE phase=STOPPED")
    }

    private companion object {
        val FAILURE_LINE = StructuredDebugLine("ORIGIN_SCENE")
        const val SPEECH_TAG = "arc_origin_scene_speech"
        const val PROP_TAG = ORIGIN_SCENE_PROP_TAG
        const val BUSY_TAG = "arc_origin_scene_busy"
    }
}
