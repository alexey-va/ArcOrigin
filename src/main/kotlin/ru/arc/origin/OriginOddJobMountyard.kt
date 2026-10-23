package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.util.MemoryDataKey
import net.citizensnpcs.trait.Controllable
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.data.type.Gate
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Horse
import org.bukkit.entity.Mob
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
private enum class OriginYardRecovery { AFTERCARE, RIDER, HORSE }

/** Native care and validated movement share the ambient actor lease held by the caller. */
internal class OriginOddJobMountyard(
    private val worldName: String,
    private val catalog: OriginMountYardAnimalCatalog,
    private val animalIds: List<Int>,
    private val rider: OriginYardRider? = null,
    private val scene: OriginSceneDefinition? = null,
    private val routes: CitizensNpcRouteController = CitizensNpcRouteController({ event ->
        info("ORIGIN_ODD_JOBS phase=YARD_ROUTE_{} npc={} cells={} reason={}", event.phase, event.npcId, event.cells, event.reason)
    }, NpcRouteObstacleSource(::originFurnitureObstacleCells)),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val createDisplays: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "odd-jobs-yard") },
    private val rideAnchorIds: List<String> = emptyList(),
    private val deliveryAnchorIds: List<String> = emptyList(),
    private val gateAnchorIds: List<String> = emptyList(),
    private val feedingMillis: Long = 5_000,
    private val routeTimeoutMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class RideControls(val trait: Controllable, val wasEnabled: Boolean?, val movementSpeed: Double?, val tamed: Boolean)
    private data class LeadMetadata(val key: NPC.Metadata, val value: Any?, val persistent: Boolean)
    private data class NativeLead(val ai: Boolean, val aware: Boolean, val metadata: List<LeadMetadata>)

    private class Care(
        val requestId: UUID, val actor: NPC, val entity: Mob, val home: Location,
        val resources: OriginSceneResources, val ownsLease: () -> Boolean,
        val rider: NPC?, val riderEntity: LivingEntity?, val riderHome: Location?,
    ) {
        var stage: OriginOddJobStage? = null
        var destination: Location? = null
        var careStartedAt: Long? = null
        var lastSoundAt = 0L
        var prop: OriginWorkshopProp? = null
        var crossTies: OriginYardCrossTies? = null
        var tiePosition: Location? = null
        var saddle: ItemStack? = null
        var saddleChanged = false
        var leashHolder: UUID? = null
        var nativeLead: NativeLead? = null
        var release: (() -> Unit)? = null
        var recoveryDeadline = 0L
        var cleanupAttempts = 0
        var releaseAttempts = 0
        var finishInBackground = false
        var mounted = false
        var rideControls: RideControls? = null
        var leftStall = false
        var leftYard = false
        var lessonRouteIndex = -1
        var lessonArrived = false
        var rideArrived = false
        var untackPosition: Location? = null
        var remountAttempts = 0
        var recovery = OriginYardRecovery.HORSE
        var via = emptyList<Location>()
    }
    private val active = mutableMapOf<UUID, Care>()
    private val recovering = mutableMapOf<UUID, Care>()
    private var displays: PaperPacketDisplays? = null
    private var closed = false

    fun target(offer: OriginOddJobOffer, previousAnimalId: Int? = null, allowedAnimalIds: Set<Int>? = null): OriginYardWork? {
        if (offer.variant.isYardPreparation() && (rider == null || (active.values + recovering.values).any { it.rider != null })) return null
        val candidates = animalIds.indices.map { animalIds[Math.floorMod(offer.seed + it, animalIds.size)] }
        val available = candidates.filter { id -> (allowedAnimalIds == null || id in allowedAnimalIds) && !ownsAnimal(id) &&
            (resolve(id) as? Mob)?.let { (!offer.variant.isYardPreparation() || it is Horse) &&
                inStall(id, it) && !it.isLeashed && it.passengers.isEmpty() } == true }
        val previousIndex = available.indexOf(previousAnimalId)
        val id = if (previousIndex >= 0) available[(previousIndex + 1) % available.size] else available.firstOrNull() ?: return null
        val animal = catalog.animals.firstOrNull { it.npcId == id } ?: return null
        return OriginYardWork(animal.npcId, animal.name)
    }

    private fun npc(id: Int): NPC? = CitizensAPI.getNPCRegistry().getById(id)?.takeIf {
        it.isSpawned && catalog.resolve(id, it.uniqueId) != null &&
            (it.entity as? LivingEntity)?.let { entity -> entity.isValid && entity.world.name == worldName &&
                entity.type.name == catalog.resolve(id, it.uniqueId)?.entityType } == true
    }
    fun resolve(id: Int): LivingEntity? = npc(id)?.entity as? LivingEntity
    fun contains(id: Int): Boolean = id in animalIds
    fun ownsAnimal(id: Int): Boolean = (active.values + recovering.values).any { it.actor.id == id }
    fun available(): Boolean = animalIds.any { resolve(it) is Mob }

    /** Both authored stalls and their idle poses fit within two metres of home. */
    private fun inStall(id: Int, horse: Mob): Boolean =
        scene?.actors?.get(id)?.home?.inWorld(horse.world)?.let { horse.location.distanceSquared(it) <= 4.0 } == true

    private fun riderNpc(): NPC? = rider?.let { expected -> CitizensAPI.getNPCRegistry().getById(expected.npcId)?.takeIf {
        it.uniqueId == expected.npcUuid && it.isSpawned &&
            (it.entity as? LivingEntity)?.let { entity -> entity.isValid && entity.world.name == worldName } == true
    } }

    /** Only the authored gates escape our service-item interception; other plugins keep their veto. */
    fun isStallGate(block: Block): Boolean = block.world.name == worldName && block.blockData is Gate &&
        gateAnchorIds.any { key -> scene?.anchors?.get(key)?.inWorld(block.world)?.let {
            it.blockX == block.x && it.blockY == block.y && it.blockZ == block.z
        } == true }

    fun isAssignedStallGate(playerId: UUID, job: OriginOddJobSession, block: Block): Boolean =
        active[playerId]?.takeIf { it.requestId == job.id && owns(it) }?.let { care ->
            isStallGate(block) && gateAnchorIds.filter { it.startsWith("job-stall-${care.actor.id}-gate-") }.any { key ->
                scene?.anchors?.get(key)?.inWorld(block.world)?.block == block
            }
        } == true

    private fun nextClosedGate(care: Care): Location? = gateAnchorIds.asSequence()
        .filter { it.startsWith("job-stall-${care.actor.id}-gate-") }
        .mapNotNull { scene?.anchors?.get(it)?.inWorld(care.entity.world) }
        .firstOrNull { at -> !care.entity.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4) || (at.block.blockData as? Gate)?.isOpen != true }

    fun gateTarget(playerId: UUID, job: OriginOddJobSession): Location? =
        active[playerId]?.takeIf { it.requestId == job.id && job.stage == OriginOddJobStage.YARD_LEAD_OUT && !it.leftStall }
            ?.let(::nextClosedGate)?.let { Location(it.world, it.blockX + 0.5, it.blockY + 0.5, it.blockZ + 0.5) }

    fun waitingForGate(playerId: UUID, job: OriginOddJobSession): Boolean = gateTarget(playerId, job) != null

    fun workerLeading(playerId: UUID, npcId: Int): Boolean = active[playerId]?.let {
        it.actor.id == npcId && it.nativeLead != null && owns(it)
    } == true

    fun leadDetached(playerId: UUID, job: OriginOddJobSession): Boolean = active[playerId]?.let {
        it.requestId == job.id && it.nativeLead != null && owns(it) &&
            (!it.entity.isLeashed || it.entity.leashHolder.uniqueId != playerId)
    } == true

    /** Re-grabbing is a nearby interaction; a broken native leash is never reattached by the timer. */
    fun toggleWorkerLead(player: Player, job: OriginOddJobSession): Boolean {
        val care = active[player.uniqueId]?.takeIf { it.requestId == job.id && it.nativeLead != null && owns(it) } ?: return false
        if (care.entity.isLeashed && care.entity.leashHolder.uniqueId == player.uniqueId) {
            care.leashHolder = player.uniqueId
            return leash(care, null)
        }
        return leash(care, player)
    }

    fun begin(player: Player, job: OriginOddJobSession, ownsLease: () -> Boolean = { true }): Boolean {
        val id = requireNotNull(job.yard).animalId
        if (!contains(id)) return false
        val actor = npc(id) ?: return false
        val horse = actor.entity as? Mob ?: return false
        if (job.variant.isYardPreparation() && horse !is Horse) return false
        if (closed || !ownsLease() || player.uniqueId in active || ownsAnimal(id) || !inStall(id, horse) || horse.isLeashed || horse.passengers.isNotEmpty()) return false
        val home = scene?.actors?.get(id)?.home?.inWorld(horse.world) ?: horse.location.clone()
        val passenger = if (job.variant.isYardPreparation()) riderNpc() ?: return false else null
        val passengerEntity = passenger?.entity as? LivingEntity
        if (passengerEntity?.vehicle != null || passengerEntity?.passengers?.isNotEmpty() == true) return false
        val passengerHome = passengerEntity?.let { scene?.anchors?.get("job-rider-home")?.inWorld(it.world) ?: it.location.clone() }
        val care = Care(job.id, actor, horse, home, OriginSceneResources(), ownsLease, passenger, passengerEntity, passengerHome)
        active[player.uniqueId] = care
        if (job.variant.isYardPreparation()) {
            // The loaned procedural tack is the only visible saddle/bridle. Native passengers do not
            // require a saddle; keep the exact authored item for removal, cancellation and revocation.
            care.saddle = horse.equipment.getItem(EquipmentSlot.SADDLE).clone()
            care.saddleChanged = true
            horse.equipment.setItem(EquipmentSlot.SADDLE, ItemStack(Material.AIR))
        }
        return true
    }

    private fun point(key: String, fallback: OriginScenePoint, care: Care): Location =
        (scene?.anchors?.get(key) ?: fallback).inWorld(care.entity.world)
    private fun profile(care: Care? = null): NpcRouteProfile = (scene?.routeProfiles?.let { it["stall-${care?.actor?.id}"] ?: it["yard-field"] ?: it["yard"] }
        ?: NpcRouteProfile("odd-jobs-yard", 70, NpcRouteBounds(43, 76, -147, -116), speedModifier = 0.58f))
        // A care endpoint is physical: never report arrival at a nearby, different side of a wall.
        .copy(snapRadius = 0)

    fun destination(playerId: UUID, job: OriginOddJobSession): Location? = active[playerId]?.takeIf { it.requestId == job.id }?.destination?.clone()

    private fun owns(care: Care): Boolean = care.ownsLease() && npc(care.actor.id)?.entity === care.entity
    private fun ownsRider(care: Care): Boolean = owns(care) && care.riderEntity != null && riderNpc()?.entity === care.riderEntity
    private fun mountedRider(care: Care): Boolean = care.mounted && ownsRider(care) &&
        care.riderEntity?.vehicle === care.entity && care.entity.passengers.singleOrNull() === care.riderEntity
    private fun workerHolding(care: Care, playerId: UUID): Boolean = care.entity.isLeashed && care.entity.leashHolder.uniqueId == playerId

    private fun lessonDestination(care: Care, index: Int): Location? {
        val key = if (index == rideAnchorIds.size) "job-handoff" else rideAnchorIds.getOrNull(index) ?: return null
        return scene?.anchors?.get(key)?.inWorld(care.entity.world)
    }

    fun station(playerId: UUID, job: OriginOddJobSession): OriginOddJobStation? = tieStations(playerId, job).firstOrNull()?.second

    fun tieStations(playerId: UUID, job: OriginOddJobSession): List<Pair<Int, OriginOddJobStation>> {
        val care = active[playerId]?.takeIf { it.requestId == job.id && owns(it) && held(it) } ?: return emptyList()
        return originYardTieTargets(job).mapNotNull { side -> care.crossTies?.station(side)?.let { side to it } }
    }

    fun readyForCare(playerId: UUID, job: OriginOddJobSession): Boolean {
        val care = active[playerId]?.takeIf { it.requestId == job.id && owns(it) } ?: return false
        return care.crossTies?.mask == 3 && held(care)
    }

    fun readyForUntack(playerId: UUID, job: OriginOddJobSession): Boolean {
        val care = active[playerId]?.takeIf { it.requestId == job.id && owns(it) } ?: return false
        val at = care.untackPosition ?: return false
        return originYardUntacking(job.stage) && care.entity.passengers.isEmpty() &&
            care.entity.world.uid == at.world?.uid && care.entity.location.distanceSquared(at) <= .04
    }

    private fun held(care: Care): Boolean {
        val at = care.tiePosition ?: return false
        return care.crossTies?.valid() == true && care.crossTies?.ropesClear(care.entity.location, care.entity.height) == true && care.entity.world.uid == at.world?.uid &&
            care.entity.location.distanceSquared(at) <= 0.04 && care.entity.passengers.isEmpty()
    }

    private fun releaseTies(care: Care) {
        care.crossTies?.close(); care.crossTies = null
        care.resources.releaseHold(care.actor)
        care.tiePosition = null
    }

    fun sync(player: Player, job: OriginOddJobSession, stations: Map<OriginOddJobStationId, OriginOddJobStation>, now: Long): Boolean {
        val care = active[player.uniqueId]?.takeIf { it.requestId == job.id } ?: return false
        if (!owns(care)) return false
        val stage = when {
            job.stage == OriginOddJobStage.RETURN && job.variant == OriginOddJobVariant.YARD_RATIONS -> OriginOddJobStage.YARD_EATING
            else -> job.stage
        }
        if (stage == OriginOddJobStage.RETURN && job.variant.isYardPreparation() &&
            (care.stage != OriginOddJobStage.YARD_LEAD_RETURN || job.yard?.borrowed?.isNotEmpty() != false)) return false
        if (stage == OriginOddJobStage.RETURN && job.variant == OriginOddJobVariant.YARD_WATER &&
            (care.stage != OriginOddJobStage.YARD_BUCKET_RETURN || job.yard?.borrowed?.isNotEmpty() != false)) return false
        if (stage == OriginOddJobStage.YARD_LESSON_WALK) {
            val work = job.yard ?: return false
            if (job.variant != OriginOddJobVariant.YARD_LESSON || work.routeTotal != rideAnchorIds.size + 1 ||
                work.routeIndex !in 0 until work.routeTotal) return false
            val destination = lessonDestination(care, work.routeIndex) ?: return false
            if (care.stage == stage) {
                if (work.routeIndex == care.lessonRouteIndex) return true
                if (work.routeIndex != care.lessonRouteIndex + 1 || !care.lessonArrived ||
                    !mountedRider(care) || !workerHolding(care, player.uniqueId)) return false
                care.lessonRouteIndex = work.routeIndex
                care.lessonArrived = false
                care.destination = destination
                return true
            }
            if (work.routeIndex != 0) return false
        }
        if (stage == OriginOddJobStage.YARD_UNTACK_BRIDLE && job.variant == OriginOddJobVariant.YARD_LESSON && care.stage != stage) {
            val work = job.yard ?: return false
            if (care.stage != OriginOddJobStage.YARD_LESSON_WALK || !care.lessonArrived ||
                care.lessonRouteIndex != rideAnchorIds.size || work.routeTotal != rideAnchorIds.size + 1 || work.routeIndex != work.routeTotal ||
                !mountedRider(care) || !workerHolding(care, player.uniqueId)) return false
        }
        if (stage == OriginOddJobStage.YARD_UNTACK_BRIDLE && job.variant != OriginOddJobVariant.YARD_LESSON && care.stage != stage &&
            (care.stage != OriginOddJobStage.YARD_HANDED || !care.rideArrived || !mountedRider(care))) return false
        if (stage == OriginOddJobStage.YARD_DELIVER && care.stage != stage) {
            val ties = care.crossTies ?: return false
            if (care.stage != OriginOddJobStage.YARD_UNTIE_SECOND || ties.mask !in 1..2 || job.yard?.tieMask != 0) return false
        }
        if (care.stage == stage) {
            if (job.stage == OriginOddJobStage.RETURN && stage in setOf(OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING))
                care.finishInBackground = true
            return true
        }
        routes.stop(care.actor)
        routes.consumeOutcome(care.actor)
        care.resources.clearMovementPose(care.actor)
        leash(care, null)
        restoreNativeLead(care)
        val previousStage = care.stage
        care.stage = stage; care.destination = null; care.careStartedAt = null; care.via = emptyList()
        if (originYardTieMask(job) != null) {
            if (job.stage == OriginOddJobStage.YARD_TIE_FIRST) {
                care.leftStall = true
                care.tiePosition = care.entity.location.clone()
                care.resources.hold(care.actor)
                val pad = point("job-care-pad", ORIGIN_YARD_CARE_PAD, care)
                care.resources.faceHorizontal(care.actor, pad.clone().add(pad.direction))
                care.crossTies = OriginYardCrossTies(renderer(), player, care.entity.location, listOf(
                    point("job-tie-first", ORIGIN_YARD_TIE_FIRST, care), point("job-tie-second", ORIGIN_YARD_TIE_SECOND, care)))
            }
            val ties = care.crossTies ?: return false
            if (!held(care)) return false
            if (job.stage == OriginOddJobStage.YARD_UNTIE_FIRST && job.variant.isYardPreparation()) {
                if (previousStage != OriginOddJobStage.YARD_STIRRUP_RIGHT || ties.mask != 3 ||
                    job.yard?.let { it.girthTight && it.leftStirrup && it.rightStirrup &&
                        it.equipped.containsAll(setOf(OriginYardGear.PAD, OriginYardGear.SADDLE, OriginYardGear.BRIDLE)) } != true) return false
                care.entity.world.playSound(care.entity.location, Sound.ENTITY_HORSE_SADDLE, 0.6f, 1f)
            }
            ties.setStage(job, care.entity.location, care.entity.height)
            if (previousStage in setOf(OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_SECOND,
                    OriginOddJobStage.YARD_TIE_HORSE_FIRST, OriginOddJobStage.YARD_TIE_HORSE_SECOND, OriginOddJobStage.YARD_UNTIE_FIRST))
                player.playSound(care.entity.location, Sound.BLOCK_WOOL_PLACE, 0.6f, 0.85f)
            return true
        }
        if (originYardUntacking(stage)) {
            if (stage == OriginOddJobStage.YARD_UNTACK_BRIDLE) {
                dismount(care)
                care.untackPosition = care.entity.location.clone()
            }
            if (!readyForUntack(player.uniqueId, job)) return false
            care.resources.hold(care.actor)
            // Keep the authored saddle hidden through manual returns; beginReturn/cleanup restores it once.
            return true
        }
        when (stage) {
            OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING -> {
                // Care stays at the assigned animal; no separate rotation owner survives the lease.
                val direction = care.entity.location.direction.setY(0)
                if (direction.lengthSquared() < 0.01) return false
                val at = care.entity.location.clone().add(direction.normalize().multiply(1.1)).add(0.0, 0.02, 0.0)
                if (!at.block.isPassable || !at.clone().subtract(0.0, 0.05, 0.0).block.type.isSolid) return false
                if (stage == OriginOddJobStage.YARD_EATING)
                    care.prop = OriginWorkshopProp(renderer(), at, originOddJobPropPieces(OriginJobProp.HAY))
                care.resources.setPose(care.actor, if (care.entity is AbstractHorse) OriginScenePose.HORSE_GRAZE else OriginScenePose.STAND)
                care.careStartedAt = now
                care.finishInBackground = job.stage == OriginOddJobStage.RETURN
                if (stage == OriginOddJobStage.YARD_DRINKING) player.playSound(at, Sound.ITEM_BUCKET_EMPTY, 0.65f, 1f)
            }
            OriginOddJobStage.YARD_LEAD_OUT -> {
                care.destination = point("job-care-pad", ORIGIN_YARD_CARE_PAD, care)
                if (!startNativeLead(care, player)) return false
            }
            OriginOddJobStage.YARD_DELIVER -> {
                releaseTies(care)
                player.playSound(care.entity.location, Sound.BLOCK_WOOL_PLACE, 0.6f, 0.85f)
                care.destination = point("job-handoff", OriginScenePoint(67.5, 70.0, -122.5), care)
                if (!startNativeLead(care, player)) return false
            }
            OriginOddJobStage.YARD_HANDED -> {
                if (previousStage != OriginOddJobStage.YARD_HANDOFF) return false
                val route = rideAnchorIds.map { scene?.anchors?.get(it)?.inWorld(care.entity.world) ?: return false }
                if (route.isEmpty() || !mountRider(care)) return false
                care.leftYard = true
                care.destination = scene?.anchors?.get("job-handoff")?.inWorld(care.entity.world) ?: return false
                care.via = route
                if (!startRoute(care)) return false
                care.finishInBackground = false
                care.rideArrived = false
                care.careStartedAt = now
                care.entity.world.spawnParticle(Particle.HEART, care.entity.location.add(0.0, 1.4, 0.0), 3, 0.2, 0.1, 0.2)
            }
            OriginOddJobStage.YARD_LESSON_WALK -> {
                if (previousStage != OriginOddJobStage.YARD_HANDOFF || !mountRider(care)) return false
                care.destination = lessonDestination(care, 0) ?: return false
                care.lessonRouteIndex = 0
                care.lessonArrived = false
                care.leftYard = true
                if (!startNativeLead(care, player)) return false
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
        val lesson = job.stage == OriginOddJobStage.YARD_LESSON_WALK
        if (!owns(care)) return OriginYardTick.FAILED
        if (lesson) {
            if (care.stage != job.stage || job.yard?.routeIndex != care.lessonRouteIndex) return OriginYardTick.FAILED
            if (!mountedRider(care)) return remountLessonRider(care)
        } else if (job.stage == OriginOddJobStage.YARD_HANDED) {
            if (!mountedRider(care)) return OriginYardTick.FAILED
            if (care.rideArrived) return OriginYardTick.CARED
            if (now - (care.careStartedAt ?: return OriginYardTick.FAILED) >= routeTimeoutMillis) return OriginYardTick.FAILED
            if (routes.isNavigating(care.actor)) return OriginYardTick.WAITING
            if (routes.consumeOutcome(care.actor)?.successful != true ||
                care.entity.location.distanceSquared(care.destination ?: return OriginYardTick.FAILED) > 1.0) return OriginYardTick.FAILED
            care.rideArrived = true
            return OriginYardTick.CARED
        } else if (care.entity.passengers.isNotEmpty()) return OriginYardTick.FAILED
        if (originYardUntacking(job.stage)) {
            if (!readyForUntack(player.uniqueId, job)) return OriginYardTick.FAILED
            care.resources.hold(care.actor)
            return OriginYardTick.WAITING
        }
        if (originYardTieMask(job) != null) {
            if (!held(care)) return OriginYardTick.FAILED
            care.resources.hold(care.actor)
            care.crossTies?.render(care.entity.location, care.entity.height)
            return OriginYardTick.WAITING
        }
        val leading = job.stage in setOf(OriginOddJobStage.YARD_LEAD_OUT, OriginOddJobStage.YARD_DELIVER, OriginOddJobStage.YARD_LESSON_WALK)
        if (leading) {
            if (!inStall(care.actor.id, care.entity)) care.leftStall = true
            if (job.stage == OriginOddJobStage.YARD_DELIVER) care.leftYard = true
            if (care.nativeLead == null) return OriginYardTick.FAILED
            if (!workerHolding(care, player.uniqueId)) return OriginYardTick.WAITING
            care.leashHolder = player.uniqueId
            val arrived = care.entity.location.distanceSquared(requireNotNull(care.destination)) <= 1.0
            if (lesson) care.lessonArrived = arrived
            return if (arrived) OriginYardTick.ARRIVED else OriginYardTick.WAITING
        }
        return tickCare(care, now)
    }

    private fun tickCare(care: Care, now: Long): OriginYardTick {
        val startedAt = care.careStartedAt ?: return OriginYardTick.WAITING
        val duration = when (care.stage) {
            OriginOddJobStage.YARD_EATING -> feedingMillis
            OriginOddJobStage.YARD_DRINKING -> 3_500L
            else -> return OriginYardTick.WAITING
        }
        if (now - care.lastSoundAt >= 850 && care.stage != OriginOddJobStage.YARD_HANDED) {
            care.lastSoundAt = now
            val at = care.entity.location
            care.entity.world.playSound(at, if (care.stage == OriginOddJobStage.YARD_EATING) Sound.ENTITY_HORSE_EAT else Sound.ENTITY_GENERIC_DRINK, 0.55f, 1f)
        }
        if (now - startedAt < duration) return OriginYardTick.WAITING
        care.prop?.remove(); care.prop = null
        care.resources.setPose(care.actor, OriginScenePose.STAND)
        return OriginYardTick.CARED
    }

    private fun startRoute(care: Care): Boolean {
        if (care.stage == OriginOddJobStage.YARD_LESSON_WALK) return false
        care.resources.clearMovementPose(care.actor)
        return routes.navigate(care.actor, requireNotNull(care.destination), profile(care), via = care.via, faceOnArrival = true)
    }

    private fun leash(care: Care, player: Player?): Boolean {
        if (player == null) {
            if (care.entity.isValid && care.entity.isLeashed && care.entity.leashHolder.uniqueId == care.leashHolder)
                care.entity.setLeashHolder(null)
            care.leashHolder = null
            return true
        }
        if (care.entity.isLeashed) return care.entity.leashHolder.uniqueId == player.uniqueId
        return care.entity.setLeashHolder(player).also { if (it) care.leashHolder = player.uniqueId }
    }

    private fun startNativeLead(care: Care, player: Player): Boolean {
        val saved = MemoryDataKey()
        val data = care.actor.data()
        data.saveTo(saved)
        care.nativeLead = NativeLead(care.entity.hasAI(), care.entity.isAware,
            listOf(NPC.Metadata.USE_MINECRAFT_AI, NPC.Metadata.LEASH_PROTECTED).map {
                LeadMetadata(it, if (data.has(it)) data.get<Any>(it) else null, saved.keyExists(it.key))
            })
        // Citizens' protected-horse adapter otherwise removes the native leash and clears its goals.
        data.set(NPC.Metadata.USE_MINECRAFT_AI, true)
        data.set(NPC.Metadata.LEASH_PROTECTED, false)
        care.entity.setAI(true)
        care.entity.isAware = true
        return leash(care, player)
    }

    private fun ownsPersistentActor(care: Care): Boolean = care.ownsLease() &&
        CitizensAPI.getNPCRegistry().getById(care.actor.id) === care.actor &&
        catalog.resolve(care.actor.id, care.actor.uniqueId) != null

    private fun restoreNativeLead(care: Care) {
        val original = care.nativeLead ?: return
        if (ownsPersistentActor(care)) {
            if (owns(care)) {
                care.entity.pathfinder.stopPathfinding()
                care.entity.setAI(original.ai)
                care.entity.isAware = original.aware
            }
            val data = care.actor.data()
            original.metadata.forEach {
                if (it.value == null) data.remove(it.key)
                else if (it.persistent) data.setPersistent(it.key, it.value)
                else data.set(it.key, it.value)
            }
        }
        care.nativeLead = null
    }

    /** Retains the ambient lease until the real animal is home or its bounded recovery has failed. */
    fun clear(playerId: UUID, requestId: UUID, release: () -> Unit = {}) {
        val care = active[playerId]?.takeIf { it.requestId == requestId }
        if (care == null) { release(); return }
        active.remove(playerId)
        care.release = release
        recovering[requestId] = care
        if (!owns(care)) { finishRecovery(care, "ownership-lost"); return }
        if (!closed && care.finishInBackground) {
            care.recovery = OriginYardRecovery.AFTERCARE
            care.recoveryDeadline = clock() + routeTimeoutMillis
            pollRecovery(care)
            return
        }
        beginReturn(care)
    }

    private fun dismount(care: Care) {
        if (care.mounted && ownsRider(care) && care.riderEntity?.vehicle === care.entity) requireNotNull(care.riderEntity).leaveVehicle()
        care.mounted = false
        restoreRideControls(care)
    }

    private fun mountRider(care: Care): Boolean {
        if (!ownsRider(care)) return false
        val horse = care.entity as? Horse ?: return false
        val passenger = requireNotNull(care.riderEntity)
        if (passenger.vehicle != null || care.entity.passengers.isNotEmpty()) return false
        care.resources.clearMovementPose(requireNotNull(care.rider))
        val snapshot = care.rideControls
        val previous = care.actor.getTraitNullable(Controllable::class.java)
        if (snapshot != null && previous !== snapshot.trait) return false
        val controls = previous ?: care.actor.getOrAddTrait(Controllable::class.java)
        if (snapshot == null) care.rideControls = RideControls(controls, previous?.isEnabled,
            horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue, horse.isTamed)
        // Retain the mounted control snapshot independently of the temporary player lead.
        horse.isTamed = true
        controls.setEnabled(true)
        care.mounted = care.entity.addPassenger(passenger)
        if (!care.mounted && snapshot == null) restoreRideControls(care)
        return care.mounted
    }

    private fun remountLessonRider(care: Care): OriginYardTick {
        care.lessonArrived = false
        val passenger = care.riderEntity
        val reason = when {
            !ownsRider(care) || passenger == null || care.rideControls == null -> "rider-unavailable"
            passenger.vehicle != null -> "rider-vehicle"
            care.entity.passengers.isNotEmpty() -> "horse-occupied"
            passenger.world.uid != care.entity.world.uid -> "rider-world"
            passenger.location.distanceSquared(care.entity.location) > 16.0 -> "rider-distance"
            !passenger.hasLineOfSight(care.entity) -> "rider-obstructed"
            care.remountAttempts >= 3 -> "remount-exhausted"
            else -> null
        }
        if (reason != null) {
            warn("ORIGIN_ODD_JOBS phase=YARD_LESSON_REMOUNT_FAILED npc={} rider={} attempts={} reason={}",
                care.actor.id, care.rider?.id, care.remountAttempts, reason)
            return OriginYardTick.FAILED
        }
        care.remountAttempts++
        val mounted = mountRider(care)
        info("ORIGIN_ODD_JOBS phase=YARD_LESSON_REMOUNT npc={} rider={} attempt={} mounted={}",
            care.actor.id, care.rider?.id, care.remountAttempts, mounted)
        if (!mounted && care.remountAttempts >= 3) {
            warn("ORIGIN_ODD_JOBS phase=YARD_LESSON_REMOUNT_FAILED npc={} rider={} attempts={} reason=mount-rejected",
                care.actor.id, care.rider?.id, care.remountAttempts)
            return OriginYardTick.FAILED
        }
        return OriginYardTick.WAITING
    }

    private fun restoreRideControls(care: Care) {
        val snapshot = care.rideControls ?: return
        if (ownsPersistentActor(care) && owns(care)) (care.entity as? Horse)?.isTamed = snapshot.tamed
        // Traits belong to the persistent NPC, even after its current entity despawns or is replaced.
        if (ownsPersistentActor(care) &&
            care.actor.getTraitNullable(Controllable::class.java) === snapshot.trait) {
            if (owns(care)) snapshot.movementSpeed?.let { care.entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = it }
            if (snapshot.wasEnabled == null) care.actor.removeTrait(Controllable::class.java)
            else snapshot.trait.setEnabled(snapshot.wasEnabled)
        }
        care.rideControls = null
    }

    private fun beginReturn(care: Care) {
        try {
            routes.stop(care.actor); routes.consumeOutcome(care.actor)
            care.prop?.remove(); care.prop = null
            leash(care, null)
            restoreNativeLead(care)
            dismount(care)
            restoreSaddle(care)
            releaseTies(care)
            if (closed) { finishRecovery(care); return }
            val riderHome = care.riderHome
            if (ownsRider(care) && riderHome != null && requireNotNull(care.riderEntity).location.distanceSquared(riderHome) > 0.25) {
                val actor = requireNotNull(care.rider)
                care.recovery = OriginYardRecovery.RIDER
                care.resources.clearMovementPose(actor)
                if (routes.navigate(actor, riderHome, profile(), faceOnArrival = true)) {
                    care.recoveryDeadline = clock() + routeTimeoutMillis
                    pollRecovery(care)
                    return
                }
                warn("ORIGIN_ODD_JOBS phase=YARD_RIDER_HOME_FAILED npc={} reason=route-unavailable", actor.id)
            }
            returnHorse(care)
        } catch (failure: Exception) {
            warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason=cleanup-exception", care.actor.id, failure)
            finishRecovery(care, "cleanup-exception")
        }
    }

    private fun returnHorse(care: Care) {
        try {
            care.resources.clearMovementPose(care.actor)
            if (care.entity.location.distanceSquared(care.home) <= 0.25) { finishRecovery(care); return }
            for (key in gateAnchorIds.filter { care.leftStall && it.startsWith("job-stall-${care.actor.id}-gate-") }) {
                val at = scene?.anchors?.get(key)?.inWorld(care.entity.world)
                if (at == null || !care.entity.world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)) {
                    finishRecovery(care, "return-gate-unavailable"); return
                }
                val block = at.block
                val gate = block.blockData as? Gate ?: run { finishRecovery(care, "return-gate-replaced"); return }
                if (!gate.isOpen) {
                    gate.isOpen = true
                    block.setBlockData(gate, true)
                    // A player can follow the horse; leave this exact gate open instead of closing across them.
                }
            }
            care.stage = null; care.destination = care.home
            care.recovery = OriginYardRecovery.HORSE
            care.via = (if (care.leftYard) deliveryAnchorIds.asReversed().mapNotNull { scene?.anchors?.get(it)?.inWorld(care.entity.world) } else emptyList()) +
                listOfNotNull(scene?.anchors?.get("job-stall-${care.actor.id}-exit")?.takeIf { care.leftStall }?.inWorld(care.entity.world))
            if (!startRoute(care)) { finishRecovery(care, "route-unavailable"); return }
            care.recoveryDeadline = clock() + routeTimeoutMillis
            pollRecovery(care)
        } catch (failure: Exception) {
            warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason=cleanup-exception", care.actor.id, failure)
            finishRecovery(care, "cleanup-exception")
        }
    }

    private fun pollRecovery(care: Care) {
        tasks.runLater(4) {
            if (recovering[care.requestId] !== care) return@runLater
            try {
                if (!owns(care)) { finishRecovery(care, "ownership-lost"); return@runLater }
                if (clock() >= care.recoveryDeadline) {
                    if (care.recovery == OriginYardRecovery.AFTERCARE) {
                        warn("ORIGIN_ODD_JOBS phase=YARD_AFTERCARE_FAILED npc={} reason=timeout", care.actor.id)
                        beginReturn(care)
                    } else if (care.recovery == OriginYardRecovery.RIDER) {
                        care.rider?.let { routes.stop(it); routes.consumeOutcome(it) }
                        warn("ORIGIN_ODD_JOBS phase=YARD_RIDER_HOME_FAILED npc={} reason=timeout", care.rider?.id)
                        returnHorse(care)
                    } else finishRecovery(care, "timeout")
                    return@runLater
                }
                when (care.recovery) {
                    OriginYardRecovery.AFTERCARE -> {
                        if (care.stage in setOf(OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING)) {
                            when (tickCare(care, clock())) {
                                OriginYardTick.CARED -> beginReturn(care)
                                OriginYardTick.FAILED -> {
                                    warn("ORIGIN_ODD_JOBS phase=YARD_AFTERCARE_FAILED npc={} reason=care-failed", care.actor.id)
                                    beginReturn(care)
                                }
                                else -> pollRecovery(care)
                            }
                        } else if (!ownsRider(care) || care.riderEntity?.vehicle !== care.entity) {
                            warn("ORIGIN_ODD_JOBS phase=YARD_AFTERCARE_FAILED npc={} reason=rider-detached", care.actor.id)
                            beginReturn(care)
                        } else if (routes.isNavigating(care.actor)) pollRecovery(care)
                        else {
                            if (routes.consumeOutcome(care.actor)?.successful != true ||
                                care.entity.location.distanceSquared(requireNotNull(care.destination)) > 1.0)
                                warn("ORIGIN_ODD_JOBS phase=YARD_AFTERCARE_FAILED npc={} reason=ride-route", care.actor.id)
                            beginReturn(care)
                        }
                    }
                    OriginYardRecovery.RIDER -> {
                        val actor = requireNotNull(care.rider)
                        if (ownsRider(care) && routes.isNavigating(actor)) pollRecovery(care)
                        else {
                            if (!ownsRider(care) || routes.consumeOutcome(actor)?.successful != true ||
                                requireNotNull(care.riderEntity).location.distanceSquared(requireNotNull(care.riderHome)) > 1.0)
                                warn("ORIGIN_ODD_JOBS phase=YARD_RIDER_HOME_FAILED npc={} reason=route-failed", actor.id)
                            returnHorse(care)
                        }
                    }
                    OriginYardRecovery.HORSE -> if (care.entity.world.uid == care.home.world.uid &&
                        care.entity.location.distanceSquared(care.home) <= 0.25 && care.entity.passengers.isEmpty()) finishRecovery(care)
                        else if (routes.isNavigating(care.actor)) pollRecovery(care)
                        else finishRecovery(care, if (routes.consumeOutcome(care.actor)?.successful == true &&
                            care.entity.location.distanceSquared(care.home) <= 1.0) null else "route-failed")
                }
            } catch (failure: Exception) {
                warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason=recovery-exception", care.actor.id, failure)
                finishRecovery(care, "recovery-exception")
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
        attempt("cross-tie-displays") { care.crossTies?.close(); care.crossTies = null }
        if (owns(care)) {
            attempt("route") { routes.stop(care.actor) }
            if (ownsRider(care)) {
                attempt("rider-route") { routes.stop(requireNotNull(care.rider)) }
            }
            attempt("rider") { dismount(care) }
            attempt("leash") { leash(care, null) }
            attempt("saddle") { restoreSaddle(care) }
            attempt("pose") { care.resources.cleanup().forEach {
                failed = true
                warn("ORIGIN_ODD_JOBS phase=YARD_RESTORE_FAILED npc={} resource={}", care.actor.id, it.resource, it.failure)
            } }
        } else {
            attempt("route-owner") { routes.abandon(care.actor) }
            care.rider?.let { attempt("rider-route-owner") { routes.abandon(it) } }
        }
        attempt("ride-controls") { restoreRideControls(care) }
        attempt("native-lead") { restoreNativeLead(care) }
        if (failed && !closed && ++care.cleanupAttempts < 3) {
            if (synchronous) finishRecovery(care, failure, synchronous = true)
            else tasks.runLater(4) { finishRecovery(care, failure) }
            return
        }
        val releaseFailure = runCatching { care.release?.invoke() }.exceptionOrNull()
        if (releaseFailure != null && care.ownsLease()) {
            warn("ORIGIN_ODD_JOBS phase=YARD_RELEASE_FAILED npc={} attempt={}", care.actor.id, ++care.releaseAttempts, releaseFailure)
            if (care.releaseAttempts < 3 && (synchronous || closed)) finishRecovery(care, failure, synchronous = true)
            else if (!closed) tasks.runLater(if (care.releaseAttempts < 3) 4 else 20) { finishRecovery(care, failure) }
            return
        }
        care.release = null
        recovering.remove(care.requestId, care)
        if (failure != null) warn("ORIGIN_ODD_JOBS phase=YARD_HOME_FAILED npc={} reason={}", care.actor.id, failure)
    }

    private fun renderer(): PaperPacketDisplays = displays ?: createDisplays().also { displays = it }

    override fun close() {
        closed = true
        active.toList().forEach { (id, care) -> clear(id, care.requestId) }
        recovering.values.toList().forEach { finishRecovery(it, "shutdown") }
        tasks.close(); routes.close(); displays?.close(); displays = null
    }
}
