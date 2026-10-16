package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import ru.arc.config.Config
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.scene.OriginSceneResources
import java.util.UUID
import kotlin.math.abs

internal data class OriginWaiterBrawlConfig(
    val pairRange: Double = 7.0,
    val contactRange: Double = 2.4,
    val stepDistance: Double = 1.0,
    val separationDistance: Double = 1.0,
    val arrivalTolerance: Double = 0.85,
    val routeTimeoutMillis: Long = 8_000,
    val strikeIntervalMillis: Long = 1_300,
    val windupMillis: Long = 250,
    val interventionMillis: Long = 1_600,
    val waterReactionMillis: Long = 2_000,
    val reactionPitch: Float = 18f,
    val soundVolume: Float = 0.35f,
    val waterParticles: Int = 8,
) {
    companion object {
        fun load(source: Config): OriginWaiterBrawlConfig {
            val root = "waiter-game.brawl"
            fun distance(key: String, fallback: Double, range: ClosedFloatingPointRange<Double>) =
                source.real("$root.$key", fallback).takeIf(Double::isFinite)?.coerceIn(range) ?: fallback
            fun millis(key: String, fallback: Int, range: IntRange) =
                source.integer("$root.$key", fallback).coerceIn(range).toLong()
            return OriginWaiterBrawlConfig(
                pairRange = distance("pair-range", 7.0, 2.0..12.0),
                contactRange = distance("contact-range", 2.4, 1.5..3.0),
                stepDistance = distance("step-distance", 1.0, 0.75..1.5),
                separationDistance = distance("separation-distance", 1.0, 1.0..2.0),
                arrivalTolerance = distance("arrival-tolerance", 0.85, 0.4..1.0),
                routeTimeoutMillis = millis("route-timeout-millis", 8_000, 2_000..15_000),
                strikeIntervalMillis = millis("strike-interval-millis", 1_300, 900..3_000),
                windupMillis = millis("windup-millis", 250, 150..500),
                interventionMillis = millis("intervention-millis", 1_600, 1_000..4_000),
                waterReactionMillis = millis("water-reaction-millis", 2_000, 1_200..5_000),
                reactionPitch = distance("reaction-pitch", 18.0, 5.0..35.0).toFloat(),
                soundVolume = distance("sound-volume", 0.35, 0.0..0.7).toFloat(),
                waterParticles = source.integer("water-particles", 8).coerceIn(0, 20),
            )
        }
    }
}

/** The shared route owner resolves these destinations against the restaurant floor and furniture. */
internal class OriginWaiterBrawlPlan(first: Location, second: Location, private val config: OriginWaiterBrawlConfig) {
    private val stances = listOf(first.clone(), second.clone())
    private val axis = second.toVector().subtract(first.toVector()).setY(0.0).normalize()

    init {
        require(first.world == second.world && first.world != null)
        require(abs(first.y - second.y) <= config.arrivalTolerance)
        require(first.distanceSquared(second) in 0.64..config.contactRange * config.contactRange)
    }

    fun fighting(shifted: Boolean): List<Location> = stances.map {
        it.clone().add(axis.clone().multiply(if (shifted) config.stepDistance else 0.0))
    }

    fun separated(actor: Int): Location = stances[actor].clone()
        .add(axis.clone().multiply(if (actor == 0) -config.separationDistance else config.separationDistance))
}

/**
 * Choreographs the two already-leased diners. Dining retains seating, safe routes and reservations;
 * the waiter game retains dispute progress. This owner never creates NPCs, teleports or applies damage.
 */
internal class OriginWaiterBrawl(
    private val config: OriginWaiterBrawlConfig,
    private val claim: (OriginWaiterTarget, OriginWaiterTarget) -> Pair<Location, Location>?,
    private val release: (OriginWaiterTarget, OriginWaiterTarget) -> Unit,
    private val move: (Int, Location) -> Boolean,
    private val stopMoving: (Int) -> Unit,
    private val isMoving: (Int) -> Boolean,
) : AutoCloseable {
    private class Actor(val npc: NPC, val entity: LivingEntity) {
        val invulnerable = entity.isInvulnerable
    }

    private data class Movement(val targets: Map<Int, Location>, val expiresAt: Long)
    private data class Intervention(val stage: OriginWaiterDisputeStage, val until: Long, var recovered: Boolean = false)
    private class Scene(
        val first: OriginWaiterTarget,
        val second: OriginWaiterTarget,
        val actors: List<Actor>,
        val plan: OriginWaiterBrawlPlan,
        val kind: OriginWaiterDisputeKind,
    ) {
        val resources = OriginSceneResources()
        var movement: Movement? = null
        var approaching = true
        var combatStopped = false
        var stage: OriginWaiterDisputeStage? = OriginWaiterDisputeStage.FIRST_SIDE
        var intervention: Intervention? = null
        var nextStrikeAt = 0L
        var impactAt: Long? = null
        var striker = 0
        var strikes = 0
    }

    private val scenes = mutableMapOf<UUID, Scene>()
    private var closed = false

    fun isActive(playerId: UUID): Boolean = playerId in scenes

    fun isReady(playerId: UUID): Boolean = scenes[playerId]?.let {
        !it.approaching && it.movement == null && it.intervention == null &&
            (it.stage != OriginWaiterDisputeStage.FIRST_SIDE || it.strikes >= 2)
    } == true

    fun start(
        playerId: UUID,
        first: OriginWaiterTarget,
        second: OriginWaiterTarget,
        now: Long,
        kind: OriginWaiterDisputeKind = OriginWaiterDisputeKind.SPILLED_DRINK,
    ): Boolean {
        if (closed || playerId in scenes || first.guestId == second.guestId || first.venueId != second.venueId) return false
        if (scenes.values.any { scene -> scene.actors.any { it.npc.id == first.guestId || it.npc.id == second.guestId } }) return false
        val actors = listOf(first, second).map { target ->
            val npc = CitizensAPI.getNPCRegistry().getById(target.guestId)?.takeIf { it.isSpawned } ?: return false
            val entity = (npc.entity as? LivingEntity)?.takeIf { it.isValid && !it.isDead } ?: return false
            Actor(npc, entity)
        }
        val origin = actors[0].entity.location
        val other = actors[1].entity.location
        if (origin.world != other.world || origin.distanceSquared(other) > config.pairRange * config.pairRange) return false
        val stances = claim(first, second) ?: return false
        val scene = try {
            Scene(first, second, actors, OriginWaiterBrawlPlan(stances.first, stances.second, config), kind)
        } catch (failure: Exception) {
            release(first, second)
            warn("ORIGIN_WAITER_BRAWL phase=CLAIM_REJECTED player=$playerId", failure)
            return false
        }
        scenes[playerId] = scene
        return try {
            actors.forEach { it.entity.isInvulnerable = true }
            // Capture LookClose/facing before the route takes over rotation.
            faceEachOther(scene)
            if (!walk(scene, scene.plan.fighting(false).withIndex().associate { it.index to it.value }, now)) {
                stop(playerId)
                false
            } else {
                info("ORIGIN_WAITER_BRAWL phase=APPROACH player={} first={} second={}", playerId, first.guestId, second.guestId)
                true
            }
        } catch (failure: Exception) {
            stop(playerId)
            warn("ORIGIN_WAITER_BRAWL phase=START_FAILED player=$playerId", failure)
            false
        }
    }

    /** Caller advances the domain exactly once after isReady becomes true again. */
    fun beginIntervention(
        playerId: UUID,
        stage: OriginWaiterDisputeStage,
        now: Long,
        durationMillis: Long = config.interventionMillis,
    ): Boolean {
        val scene = scenes[playerId]?.takeIf { isReady(playerId) && it.stage == stage } ?: return false
        scene.combatStopped = true
        scene.impactAt = null
        val duration = maxOf(durationMillis, if (stage == OriginWaiterDisputeStage.BRING_WATER) config.waterReactionMillis else config.interventionMillis)
        scene.intervention = Intervention(stage, now + duration)
        val actor = when (stage) {
            OriginWaiterDisputeStage.FIRST_SIDE -> 0
            OriginWaiterDisputeStage.SECOND_SIDE -> 1
            else -> null
        }
        try {
            scene.actors.forEach { stopMoving(it.npc.id) }
            // A punch can leave the second guest on the outer cell. Bring that guest back to
            // their stance now, so the next intervention still visibly moves them apart.
            val destinations = when (actor) {
                0 -> mapOf(0 to scene.plan.separated(0), 1 to scene.plan.fighting(false)[1])
                1 -> mapOf(1 to scene.plan.separated(1))
                else -> emptyMap()
            }
            if (destinations.isNotEmpty() && !walk(scene, destinations, now)) {
                stop(playerId)
                return false
            }
            if (stage == OriginWaiterDisputeStage.BRING_WATER) {
                val recipient = scene.actors[if (scene.kind == OriginWaiterDisputeKind.SPILLED_DRINK) 0 else 1].entity
                if (config.waterParticles > 0) recipient.world.spawnParticle(Particle.SPLASH, recipient.eyeLocation,
                    config.waterParticles, 0.2, 0.15, 0.2, 0.0)
                recipient.world.playSound(recipient.location, Sound.ENTITY_GENERIC_DRINK, SoundCategory.NEUTRAL, config.soundVolume, 1f)
            }
            if (actor == null) facePlayer(scene, playerId, config.reactionPitch)
            info("ORIGIN_WAITER_BRAWL phase=INTERVENTION player={} stage={} duration_ms={}", playerId, stage, duration)
            return true
        } catch (failure: Exception) {
            stop(playerId)
            warn("ORIGIN_WAITER_BRAWL phase=INTERVENTION_FAILED player=$playerId stage=$stage", failure)
            return false
        }
    }

    fun tick(now: Long) {
        scenes.toMap().forEach { (playerId, scene) ->
            try {
                tick(playerId, scene, now)
            } catch (failure: Exception) {
                stop(playerId)
                warn("ORIGIN_WAITER_BRAWL phase=ANIMATION_FAILED player=$playerId", failure)
            }
        }
    }

    private fun tick(playerId: UUID, scene: Scene, now: Long) {
        if (scene.actors.any { !it.npc.isSpawned || it.npc.entity !== it.entity || !it.entity.isValid || it.entity.isDead ||
                it.entity.world != scene.actors.first().entity.world }) {
            stop(playerId)
            return
        }
        scene.movement?.let { movement ->
            if (now >= movement.expiresAt) {
                info("ORIGIN_WAITER_BRAWL phase=ROUTE_TIMEOUT player={}", playerId)
                stop(playerId)
                return
            }
            if (movement.targets.keys.any { isMoving(scene.actors[it].npc.id) }) return
            if (movement.targets.any { (actor, target) ->
                    val actual = scene.actors[actor].entity.location
                    actual.world != target.world || actual.distanceSquared(target) > config.arrivalTolerance * config.arrivalTolerance
                }) {
                info("ORIGIN_WAITER_BRAWL phase=ROUTE_NOT_REACHED player={}", playerId)
                stop(playerId)
                return
            }
            scene.movement = null
            scene.approaching = false
            if (scene.combatStopped) facePlayer(scene, playerId) else faceEachOther(scene)
        }
        scene.intervention?.let { action ->
            if (!action.recovered && now >= action.until - config.interventionMillis / 2) {
                action.recovered = true
                facePlayer(scene, playerId)
            }
            if (now >= action.until) {
                scene.intervention = null
                scene.stage = OriginWaiterDisputeStage.entries.getOrNull(action.stage.ordinal + 1)
            }
            return
        }
        if (scene.combatStopped || scene.approaching) return
        val impactAt = scene.impactAt
        if (impactAt == null) {
            if (now >= scene.nextStrikeAt) {
                faceEachOther(scene)
                scene.impactAt = now + config.windupMillis
            }
            return
        }
        if (now < impactAt) return
        val attacker = scene.actors[scene.striker].entity
        val recipient = scene.actors[1 - scene.striker].entity
        // Failed/snapped routes must never become punches delivered across the dining room.
        val contactDistanceSquared = attacker.location.distanceSquared(recipient.location)
        if (contactDistanceSquared !in 0.36..config.contactRange * config.contactRange) {
            stop(playerId)
            return
        }
        attacker.swingMainHand()
        recipient.playHurtAnimation(attacker.yaw)
        recipient.world.playSound(recipient.location, Sound.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.NEUTRAL, config.soundVolume, 1f)
        scene.strikes++
        scene.impactAt = null
        scene.nextStrikeAt = now + config.strikeIntervalMillis
        val destinations = scene.plan.fighting(scene.striker == 0)
        scene.striker = 1 - scene.striker
        if (!walk(scene, destinations.withIndex().associate { it.index to it.value }, now)) stop(playerId)
    }

    private fun walk(scene: Scene, targets: Map<Int, Location>, now: Long): Boolean {
        scene.movement = Movement(targets, now + config.routeTimeoutMillis)
        return targets.all { (actor, destination) -> move(scene.actors[actor].npc.id, destination.clone()) }
    }

    private fun faceEachOther(scene: Scene) {
        scene.actors.forEachIndexed { index, actor ->
            scene.resources.faceHorizontal(actor.npc, scene.actors[1 - index].entity.location)
        }
    }

    private fun facePlayer(scene: Scene, playerId: UUID, pitch: Float = 0f) {
        val player = Bukkit.getPlayer(playerId)?.takeIf { it.world == scene.actors.first().entity.world }
        scene.actors.forEachIndexed { index, actor ->
            scene.resources.faceHorizontal(actor.npc, player?.location ?: scene.actors[1 - index].entity.location, pitch)
        }
    }

    fun stop(playerId: UUID) {
        val scene = scenes.remove(playerId) ?: return
        val failures = mutableListOf<Throwable>()
        scene.actors.forEach { actor ->
            runCatching { stopMoving(actor.npc.id) }.exceptionOrNull()?.let(failures::add)
        }
        failures += scene.resources.cleanup().map { it.failure }
        scene.actors.forEach { actor ->
            runCatching { if (actor.entity.isValid) actor.entity.isInvulnerable = actor.invulnerable }.exceptionOrNull()?.let(failures::add)
        }
        runCatching { release(scene.first, scene.second) }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) warn("ORIGIN_WAITER_BRAWL phase=CLEANUP_FAILED player=$playerId",
            IllegalStateException("Could not restore waiter brawl actors").apply { failures.forEach(::addSuppressed) })
        info("ORIGIN_WAITER_BRAWL phase=STOPPED player={} strikes={}", playerId, scene.strikes)
    }

    override fun close() {
        closed = true
        scenes.keys.toList().forEach(::stop)
    }
}
