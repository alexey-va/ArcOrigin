package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteObstacleSource
import ru.arc.npc.NpcRouteProfile
import ru.arc.origin.mountyard.OriginMountYardAnimalCatalog
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.origin.scene.OriginScenePose
import ru.arc.origin.scene.OriginSceneResources
import ru.arc.origin.scene.originFurnitureObstacleCells
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

internal enum class OriginYardTick { WAITING, ARRIVED, CARED, FAILED }

/** A worker has to walk ahead, stay near the animal and wait when it falls behind. */
internal fun originYardCanLead(horse: Location, player: Location, destination: Location): Boolean =
    horse.world?.uid == player.world?.uid && horse.world?.uid == destination.world?.uid &&
        horse.distanceSquared(player) <= 25.0 &&
        (player.distanceSquared(destination) <= 6.25 || player.distance(destination) + 0.4 < horse.distance(destination))

/** Native care and validated movement share the ambient actor lease held by the caller. */
internal class OriginOddJobMountyard(
    private val worldName: String,
    private val catalog: OriginMountYardAnimalCatalog,
    private val animalIds: List<Int>,
    private val feedingAnimalId: Int,
    private val escortAnimalId: Int = 371,
    private val scene: OriginSceneDefinition? = null,
    private val routes: CitizensNpcRouteController = CitizensNpcRouteController({ event ->
        info("ORIGIN_ODD_JOBS phase=YARD_ROUTE_{} npc={} cells={} reason={}", event.phase, event.npcId, event.cells, event.reason)
    }, NpcRouteObstacleSource(::originFurnitureObstacleCells)),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val createDisplays: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "odd-jobs-yard") },
) : AutoCloseable {
    private class Care(
        val requestId: UUID, val actor: NPC, val entity: AbstractHorse, val home: Location,
        val resources: OriginSceneResources, val ownsLease: () -> Boolean,
    ) {
        var stage: OriginOddJobStage? = null
        var destination: Location? = null
        var routeStarted = false
        var paused = false
        var careStartedAt: Long? = null
        var lastSoundAt = 0L
        var prop: OriginWorkshopProp? = null
        var saddle: ItemStack? = null
        var saddleChanged = false
        var leashed = false
        var release: (() -> Unit)? = null
        var recoveryDeadline = 0L
        var cleanupAttempts = 0
    }
    private val active = mutableMapOf<UUID, Care>()
    private val recovering = mutableMapOf<UUID, Care>()
    private var displays: PaperPacketDisplays? = null
    private var closed = false

    fun target(offer: OriginOddJobOffer): OriginYardWork? {
        val id = when (offer.variant) {
            OriginOddJobVariant.YARD_RATIONS -> feedingAnimalId
            OriginOddJobVariant.YARD_SADDLE -> escortAnimalId
            else -> animalIds.getOrNull(Math.floorMod(offer.seed, animalIds.size.coerceAtLeast(1))) ?: return null
        }
        val animal = catalog.animals.firstOrNull { it.npcId == id } ?: return null
        return OriginYardWork(animal.npcId, animal.name)
    }

    private fun npc(id: Int): NPC? = CitizensAPI.getNPCRegistry().getById(id)?.takeIf {
        it.isSpawned && catalog.resolve(id, it.uniqueId) != null &&
            (it.entity as? LivingEntity)?.let { entity -> entity.isValid && entity.world.name == worldName &&
                entity.type.name == catalog.resolve(id, it.uniqueId)?.entityType } == true
    }
    fun resolve(id: Int): LivingEntity? = npc(id)?.entity as? LivingEntity
    fun contains(id: Int): Boolean = id in animalIds || id == feedingAnimalId || id == escortAnimalId
    fun ownsAnimal(id: Int): Boolean = (active.values + recovering.values).any { it.actor.id == id }
    fun available(): Boolean = (animalIds + feedingAnimalId + escortAnimalId).all { resolve(it) is AbstractHorse }

    fun begin(player: Player, job: OriginOddJobSession, ownsLease: () -> Boolean = { true }): Boolean {
        val id = requireNotNull(job.yard).animalId
        val actor = npc(id) ?: return false
        val horse = actor.entity as? AbstractHorse ?: return false
        if (closed || !ownsLease() || player.uniqueId in active || ownsAnimal(id) || horse.isLeashed || horse.passengers.isNotEmpty()) return false
        val home = scene?.actors?.get(id)?.home?.inWorld(horse.world) ?: horse.location.clone()
        active[player.uniqueId] = Care(job.id, actor, horse, home, OriginSceneResources(), ownsLease)
        return true
    }

    private fun point(key: String, fallback: OriginScenePoint, care: Care): Location =
        (scene?.anchors?.get(key) ?: fallback).inWorld(care.entity.world)
    private fun profile(care: Care): NpcRouteProfile = (scene?.routeProfiles?.get(
        if (care.stage == OriginOddJobStage.YARD_EATING && care.actor.id == 422) "west-pen" else "yard")
        ?: NpcRouteProfile("odd-jobs-yard", 70, NpcRouteBounds(43, 76, -147, -116), speedModifier = 0.58f))
        // A care endpoint is physical: never report arrival at a nearby, different side of a wall.
        .copy(snapRadius = 0)

    fun destination(playerId: UUID, job: OriginOddJobSession): Location? = active[playerId]?.takeIf { it.requestId == job.id }?.destination?.clone()

    private fun owns(care: Care): Boolean = care.ownsLease() && npc(care.actor.id)?.entity === care.entity

    fun sync(player: Player, job: OriginOddJobSession, stations: Map<OriginOddJobStationId, OriginOddJobStation>, now: Long): Boolean {
        val care = active[player.uniqueId]?.takeIf { it.requestId == job.id } ?: return false
        if (!owns(care)) return false
        if (care.stage == job.stage) return true
        routes.stop(care.actor)
        routes.consumeOutcome(care.actor)
        care.resources.clearMovementPose(care.actor)
        leash(care, null)
        care.stage = job.stage; care.destination = null; care.routeStarted = false; care.paused = false; care.careStartedAt = null
        when (job.stage) {
            OriginOddJobStage.YARD_EATING -> {
                val feed = stations.getValue(OriginOddJobStationId.FEED).point
                care.prop = OriginWorkshopProp(renderer(), feed, originOddJobPropPieces(OriginJobProp.HAY))
                care.destination = point("job-feed-approach", OriginScenePoint(48.5, 70.0, -135.5, 0f), care)
                if (!startRoute(care)) return false
            }
            OriginOddJobStage.YARD_DRINKING -> {
                // Put the bucket under the animal's muzzle; no separate rotation owner survives the care lease.
                val direction = care.entity.location.direction.setY(0)
                if (direction.lengthSquared() < 0.01) return false
                val at = care.entity.location.clone().add(direction.normalize().multiply(1.1)).add(0.0, 0.02, 0.0)
                if (!at.block.isPassable || !at.clone().subtract(0.0, 0.05, 0.0).block.type.isSolid) return false
                care.prop = OriginWorkshopProp(renderer(), at, originOddJobPropPieces(OriginJobProp.WATER_BUCKET))
                care.resources.setPose(care.actor, OriginScenePose.HORSE_GRAZE)
                care.careStartedAt = now
                player.playSound(at, Sound.ITEM_BUCKET_EMPTY, 0.65f, 1f)
            }
            OriginOddJobStage.YARD_LEAD_OUT -> care.destination = point("job-care-pad", OriginScenePoint(60.5, 70.0, -124.5), care)
            OriginOddJobStage.YARD_DELIVER -> {
                care.saddle = care.entity.equipment.getItem(EquipmentSlot.SADDLE).clone()
                care.saddleChanged = true
                care.entity.equipment.setItem(EquipmentSlot.SADDLE, ItemStack(Material.SADDLE))
                care.entity.world.playSound(care.entity.location, Sound.ENTITY_HORSE_SADDLE, 0.6f, 1f)
                care.destination = point("job-handoff", OriginScenePoint(59.5, 70.0, -133.5), care)
            }
            OriginOddJobStage.YARD_HANDED -> {
                care.careStartedAt = now
                care.entity.world.spawnParticle(Particle.HEART, care.entity.location.add(0.0, 1.4, 0.0), 3, 0.2, 0.1, 0.2)
            }
            else -> Unit
        }
        return true
    }

    fun stroke(player: Player, job: OriginOddJobSession) {
        val animal = active[player.uniqueId]?.takeIf { it.requestId == job.id }?.entity ?: return
        player.swingMainHand()
        val contact = animal.location.add(0.0, animal.height * 0.65, 0.0)
        animal.world.spawnParticle(Particle.POOF, contact, 5, 0.3, 0.18, 0.3, 0.01)
        animal.world.playSound(contact, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.55f, 1.05f)
    }

    fun tick(player: Player, job: OriginOddJobSession, now: Long): OriginYardTick {
        val care = active[player.uniqueId]?.takeIf { it.requestId == job.id } ?: return OriginYardTick.FAILED
        if (!owns(care) || care.entity.passengers.isNotEmpty()) return OriginYardTick.FAILED
        val leading = job.stage in setOf(OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER)
        if (leading) {
            val destination = requireNotNull(care.destination)
            if (!originYardCanLead(care.entity.location, player.location, destination)) {
                if (care.routeStarted && routes.isNavigating(care.actor)) {
                    routes.stop(care.actor); routes.consumeOutcome(care.actor); care.paused = true
                }
                if (care.entity.location.distanceSquared(player.location) > 36.0) leash(care, null)
                return OriginYardTick.WAITING
            }
            if (!leash(care, player)) return OriginYardTick.FAILED
            if (!care.routeStarted || care.paused) {
                if (!startRoute(care)) return OriginYardTick.FAILED
                care.paused = false
            }
        }
        if (care.routeStarted && care.careStartedAt == null) {
            if (routes.isNavigating(care.actor)) return OriginYardTick.WAITING
            if (routes.consumeOutcome(care.actor)?.successful != true ||
                care.entity.location.distanceSquared(requireNotNull(care.destination)) > 1.0) return OriginYardTick.FAILED
            if (leading) return OriginYardTick.ARRIVED
            care.resources.setPose(care.actor, OriginScenePose.HORSE_GRAZE)
            care.careStartedAt = now
        }
        val startedAt = care.careStartedAt ?: return OriginYardTick.WAITING
        val duration = when (job.stage) {
            OriginOddJobStage.YARD_EATING -> 5_000
            OriginOddJobStage.YARD_DRINKING -> 3_500
            OriginOddJobStage.YARD_HANDED -> 1_500
            else -> return OriginYardTick.WAITING
        }
        if (now - care.lastSoundAt >= 850 && job.stage != OriginOddJobStage.YARD_HANDED) {
            care.lastSoundAt = now
            val at = care.entity.location
            care.entity.world.playSound(at, if (job.stage == OriginOddJobStage.YARD_EATING) Sound.ENTITY_HORSE_EAT else Sound.ENTITY_GENERIC_DRINK, 0.55f, 1f)
        }
        if (now - startedAt < duration) return OriginYardTick.WAITING
        care.prop?.remove(); care.prop = null
        care.resources.setPose(care.actor, OriginScenePose.STAND)
        return OriginYardTick.CARED
    }

    private fun startRoute(care: Care): Boolean {
        care.resources.clearMovementPose(care.actor)
        care.routeStarted = routes.navigate(care.actor, requireNotNull(care.destination), profile(care), faceOnArrival = true)
        return care.routeStarted
    }

    private fun leash(care: Care, player: Player?): Boolean {
        if (player == null) {
            if (care.leashed && care.entity.isValid) care.entity.setLeashHolder(null)
            care.leashed = false
            return true
        }
        if (care.leashed && care.entity.isLeashed && care.entity.leashHolder.uniqueId == player.uniqueId) return true
        care.leashed = care.entity.setLeashHolder(player)
        return care.leashed
    }

    /** Retains the ambient lease until the real animal is home or its bounded recovery has failed. */
    fun clear(playerId: UUID, requestId: UUID, release: () -> Unit = {}) {
        val care = active[playerId]?.takeIf { it.requestId == requestId }
        if (care == null) { release(); return }
        active.remove(playerId)
        care.release = release
        recovering[requestId] = care
        if (!owns(care)) { finishRecovery(care, "ownership-lost"); return }
        try {
            routes.stop(care.actor); routes.consumeOutcome(care.actor)
            care.prop?.remove(); care.prop = null
            leash(care, null)
            restoreSaddle(care)
            if (closed) { finishRecovery(care); return }
            care.resources.clearMovementPose(care.actor)
            if (care.entity.location.distanceSquared(care.home) <= 0.25) { finishRecovery(care); return }
            care.stage = null; care.destination = care.home
            if (!startRoute(care)) { finishRecovery(care, "route-unavailable"); return }
            care.recoveryDeadline = System.currentTimeMillis() + 30_000
            pollRecovery(care)
        } catch (failure: Exception) {
            warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason=cleanup-exception", care.actor.id, failure)
            finishRecovery(care, "cleanup-exception")
        }
    }

    private fun pollRecovery(care: Care) {
        tasks.runLater(4) {
            if (recovering[care.requestId] !== care) return@runLater
            when {
                !owns(care) -> finishRecovery(care, "ownership-lost")
                System.currentTimeMillis() >= care.recoveryDeadline -> finishRecovery(care, "timeout")
                routes.isNavigating(care.actor) -> pollRecovery(care)
                else -> finishRecovery(care, if (routes.consumeOutcome(care.actor)?.successful == true &&
                    care.entity.location.distanceSquared(care.home) <= 1.0) null else "route-failed")
            }
        }
    }

    private fun restoreSaddle(care: Care) {
        if (care.saddleChanged && care.entity.isValid) {
            care.entity.equipment.setItem(EquipmentSlot.SADDLE, care.saddle ?: ItemStack(Material.AIR))
            care.saddleChanged = false
        }
    }

    /** Called before an ambient generation hands over this still-owned actor. No return route can outlive it. */
    fun revoke(playerId: UUID, requestId: UUID) {
        val care = active[playerId]?.takeIf { it.requestId == requestId }?.also { active.remove(playerId) }
            ?: recovering[requestId] ?: return
        recovering[requestId] = care
        finishRecovery(care, "lease-revoked", synchronous = true)
    }

    private fun finishRecovery(care: Care, failure: String? = null, synchronous: Boolean = false) {
        if (recovering[care.requestId] !== care) return
        var failed = false
        fun attempt(resource: String, action: () -> Unit) {
            runCatching(action).onFailure {
                failed = true
                warn("ORIGIN_ODD_JOBS phase=YARD_RESTORE_FAILED npc={} resource={}", care.actor.id, resource, it)
            }
        }
        attempt("prop") { care.prop?.remove(); care.prop = null }
        if (owns(care)) {
            attempt("route") { routes.stop(care.actor) }
            attempt("leash") { leash(care, null) }
            attempt("saddle") { restoreSaddle(care) }
            attempt("pose") { care.resources.cleanup().forEach {
                failed = true
                warn("ORIGIN_ODD_JOBS phase=YARD_RESTORE_FAILED npc={} resource={}", care.actor.id, it.resource, it.failure)
            } }
        } else attempt("route-owner") { routes.abandon(care.actor) }
        if (failed && !closed && ++care.cleanupAttempts < 3) {
            if (synchronous) finishRecovery(care, failure, synchronous = true)
            else tasks.runLater(4) { finishRecovery(care, failure) }
            return
        }
        recovering.remove(care.requestId)
        if (failure != null) warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason={}", care.actor.id, failure)
        care.release?.also { care.release = null }?.invoke()
    }

    private fun renderer(): PaperPacketDisplays = displays ?: createDisplays().also { displays = it }

    override fun close() {
        closed = true
        active.toList().forEach { (id, care) -> clear(id, care.requestId) }
        recovering.values.toList().forEach { finishRecovery(it, "shutdown") }
        tasks.close(); routes.close(); displays?.close(); displays = null
    }
}
