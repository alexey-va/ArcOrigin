package ru.arc.origin.scene

import net.citizensnpcs.api.npc.NPC
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteProfile
import ru.arc.npc.isNpcRouteFootprintClear
import ru.arc.npc.npcRouteFootprint
import ru.arc.npc.resolveSurfaceY
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

internal data class OriginDozorConfig(
    val enabled: Boolean,
    val cooldownMillis: LongRange,
    val playerCooldownMillis: Long,
    val lines: List<String>,
) {
    init {
        require(cooldownMillis.first in 30_000L..600_000L && cooldownMillis.last in cooldownMillis.first..600_000L)
        require(playerCooldownMillis in 120_000L..3_600_000L)
        require(lines.size in 3..16 && lines.distinct().size == lines.size && lines.all { it.isNotBlank() && it.length <= 80 })
    }

    companion object {
        fun load(path: Path): OriginDozorConfig {
            val source = ConfigManager.ofModule(path, "origin-dozor.yml")
            return OriginDozorConfig(source.boolean("enabled", true),
                source.long("cooldown-min-seconds", 90) * 1_000L..source.long("cooldown-max-seconds", 180) * 1_000L,
                source.long("player-cooldown-seconds", 480) * 1_000L, source.stringList("lines"))
        }
    }
}

internal data class OriginDozorVisit(
    val playerId: UUID,
    val visitor: Location,
    val scene: OriginSceneDefinition,
    val cycle: OriginSceneCycle,
    val lease: OriginSceneLease,
) {
    fun contains(player: Player): Boolean = player.isOnline && !player.isDead && !player.isInsideVehicle &&
        !player.isFlying && !player.isSprinting && !player.isSwimming && player.gameMode != GameMode.SPECTATOR && player.world == visitor.world &&
        player.location.distanceSquared(visitor) <= 1.0 && abs(player.location.y - visitor.y) <= 0.25
}

/** Only a side-front cell is eligible; the player's forward walking lane stays clear. */
internal fun originDozorApproachCells(visitor: Location, profile: NpcRouteProfile): List<NpcRouteCell> {
    val yaw = Math.toRadians(visitor.yaw.toDouble())
    val forwardX = -sin(yaw); val forwardZ = cos(yaw)
    val sideX = cos(yaw); val sideZ = sin(yaw)
    return listOf(-1.0, 1.0).flatMap { side ->
        listOf(1.6, 2.1).map { ahead ->
            NpcRouteCell(floor(visitor.x + sideX * side * 1.9 + forwardX * ahead).toInt(),
                floor(visitor.z + sideZ * side * 1.9 + forwardZ * ahead).toInt())
        }
    }.distinct().filter { cell ->
        val dx = cell.x + 0.5 - visitor.x; val dz = cell.z + 0.5 - visitor.z
        profile.allows(cell) && abs(dx * sideX + dz * sideZ) >= 1.35 &&
            dx * forwardX + dz * forwardZ >= 0.7 && dx * dx + dz * dz in 3.24..10.24
    }
}

/** A quiet visitor beat borrows the scene's existing lease, route, pose and speech owners. */
internal class OriginDozorGreetings(
    private val config: OriginDozorConfig,
    nowMillis: Long,
    private val random: Random = Random.Default,
) {
    private data class Visitor(val point: Location, val stillSince: Long)
    private val visitors = mutableMapOf<UUID, Visitor>()
    private val greetedUntil = mutableMapOf<UUID, Long>()
    private val lines = ArrayDeque<String>()
    private var previousLine: String? = null
    private var nextAt = nowMillis + config.cooldownMillis.random(random)

    fun matches(actor: NPC): Boolean = actor.id == NPC_ID && actor.uniqueId == NPC_UUID && actor.isSpawned &&
        actor.entity.type == EntityType.WOLF

    fun interacted(actorId: Int, nowMillis: Long) {
        if (actorId == NPC_ID) nextAt = maxOf(nextAt, nowMillis + 30_000L)
    }

    fun observe(players: Collection<Player>, nowMillis: Long) {
        val present = players.mapTo(mutableSetOf()) { it.uniqueId }
        visitors.keys.retainAll(present)
        greetedUntil.entries.removeIf { it.value <= nowMillis }
        players.forEach { player ->
            val at = player.location
            val old = visitors[player.uniqueId]
            if (old == null || old.point.world != at.world || old.point.distanceSquared(at) > 0.04 ||
                !quiet(player)) visitors[player.uniqueId] = Visitor(at.clone(), nowMillis)
        }
    }

    fun tryStart(scene: OriginSceneDefinition, actor: NPC, players: Collection<Player>,
        coordinator: OriginSceneCoordinator, nowMillis: Long): OriginDozorVisit? {
        if (!config.enabled || nowMillis < nextAt || !matches(actor) || NPC_ID in coordinator.busyActors()) return null
        val yard = scene.routeProfiles["yard"] ?: return null
        val world = actor.entity.world
        val origin = actor.entity.location
        // The selected side cell must remain the endpoint: snapping can otherwise put the dog back in the player's lane.
        val profile = yard.copy(id = "yard-visitor", snapRadius = 0, distanceMargin = 0.25)
        val footprint = npcRouteFootprint(actor.entity)
        for (player in players.shuffled(random)) {
            if (!quiet(player) || player.world != world || nowMillis < greetedUntil.getOrDefault(player.uniqueId, 0L)) continue
            val sample = visitors[player.uniqueId] ?: continue
            if (nowMillis - sample.stillSince < 2_000L) continue
            val at = player.location
            if (origin.distanceSquared(at) !in 9.0..64.0 || abs(at.y - yard.floorY) > 0.25 ||
                !yard.allows(NpcRouteCell(at.blockX, at.blockZ)) || !player.hasLineOfSight(actor.entity)) continue
            val destination = originDozorApproachCells(at, profile)
                .sortedBy { (it.x + 0.5 - origin.x) * (it.x + 0.5 - origin.x) + (it.z + 0.5 - origin.z) * (it.z + 0.5 - origin.z) }
                .firstNotNullOfOrNull { cell ->
                    if (!world.isChunkLoaded(cell.x shr 4, cell.z shr 4)) return@firstNotNullOfOrNull null
                    val y = resolveSurfaceY(world, profile, cell) ?: return@firstNotNullOfOrNull null
                    val point = Location(world, cell.x + 0.5, y, cell.z + 0.5)
                    if (!isNpcRouteFootprintClear(world, cell, y, footprint, profile) ||
                        players.any { it.world == world && it.location.distanceSquared(point) < 1.8 * 1.8 }) null else point
                } ?: continue
            val lease = coordinator.tryAcquire(scene.id, CYCLE_ID, setOf(NPC_ID), nowMillis) ?: return null
            nextAt = nowMillis + config.cooldownMillis.random(random)
            greetedUntil[player.uniqueId] = nowMillis + config.playerCooldownMillis
            val cycle = OriginSceneCycle(CYCLE_ID, setOf(NPC_ID), config.cooldownMillis, 0L, null, 1.0,
                listOf(OriginSceneStep.Pose(NPC_ID, OriginScenePose.STAND),
                    OriginSceneStep.Move(NPC_ID, "dozor-visitor-side", profile.id, 200L),
                    OriginSceneStep.LookAtAnchor(NPC_ID, "dozor-visitor"), OriginSceneStep.Wait(10L),
                    OriginSceneStep.Speech(NPC_ID, nextLine()), OriginSceneStep.Wait(100L)),
                stepIds = listOf("stand", "approach", "look", "pause", "line", "listen"), maxDurationTicks = 900L)
            val visitScene = scene.copy(anchors = scene.anchors + mapOf(
                "dozor-visitor-side" to OriginScenePoint(destination.x, destination.y, destination.z),
                "dozor-visitor" to OriginScenePoint(at.x, at.y, at.z)),
                routeProfiles = scene.routeProfiles + (profile.id to profile))
            return OriginDozorVisit(player.uniqueId, at.clone(), visitScene, cycle, lease)
        }
        return null
    }

    private fun nextLine(): String {
        if (lines.isEmpty()) {
            val shuffled = config.lines.shuffled(random).toMutableList()
            if (shuffled.first() == previousLine) java.util.Collections.swap(shuffled, 0, 1)
            lines.addAll(shuffled)
        }
        return lines.removeFirst().also { previousLine = it }
    }

    private fun quiet(player: Player): Boolean = player.isOnline && !player.isDead && !player.isInsideVehicle &&
        !player.isFlying && !player.isSprinting && !player.isSwimming && player.gameMode != GameMode.SPECTATOR &&
        player.velocity.lengthSquared() < 0.025

    companion object {
        const val NPC_ID = 444
        val NPC_UUID: UUID = UUID.fromString("db4c291b-b919-4f94-9956-07254798afcb")
        const val CYCLE_ID = "dog-visitor"
    }
}
