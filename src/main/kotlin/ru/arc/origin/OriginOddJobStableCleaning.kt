package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class OriginStableCleaningStall(val cart: OriginScenePoint, val piles: List<OriginScenePoint>)
internal data class OriginStableCleaningLayout(
    val cart: OriginForgeCartLayout,
    val unload: OriginScenePoint,
    val stalls: Map<Int, OriginStableCleaningStall>,
) {
    companion object {
        /** Older runtime overrides omit these anchors; only cleaning becomes unavailable. */
        fun from(scene: OriginSceneDefinition, forgeCart: OriginForgeCartLayout): OriginStableCleaningLayout? {
            val home = scene.anchors["job-clean-cart"] ?: return null
            val unload = scene.anchors["job-clean-unload"] ?: return null
            val stalls = scene.actors.keys.mapNotNull { id ->
                val cart = scene.anchors["job-clean-$id-cart"] ?: return@mapNotNull null
                val piles = (0..2).map { scene.anchors["job-clean-$id-$it"] ?: return@mapNotNull null }
                id to OriginStableCleaningStall(cart, piles)
            }.toMap()
            if (stalls.isEmpty()) return null
            return OriginStableCleaningLayout(forgeCart.copy(start = OriginWorkshopPoint(home.x, home.y, home.z), yaw = home.yaw), unload, stalls)
        }
    }
}

/** A small irregular portion stays recognisable on the floor, shovel, cart bed and unloading ground. */
internal fun originStableManurePieces(): List<OriginWorkshopWorkpiecePiece> = listOf(
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-0.085, 0.065, -0.015), OriginWorkshopGamePartSize(0.18f, 0.13f, 0.23f), Material.BROWN_TERRACOTTA),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.105, 0.055, 0.025), OriginWorkshopGamePartSize(0.18f, 0.11f, 0.20f), Material.PACKED_MUD),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-0.015, 0.165, -0.005), OriginWorkshopGamePartSize(0.18f, 0.07f, 0.15f), Material.BROWN_TERRACOTTA),
)

/** The portion stays visible below the crosshair, forward and to the right of the worker. */
internal fun originStableScoopPose(yawDegrees: Double, sneaking: Boolean): OriginWorkshopCarryPose {
    val yaw = Math.toRadians(yawDegrees)
    return OriginWorkshopCarryPose(OriginWorkshopPoint(-0.34 * cos(yaw) - 0.76 * sin(yaw),
        if (sneaking) 0.66 else 0.9, -0.34 * sin(yaw) + 0.76 * cos(yaw)), Quaternionf().rotationY(-yaw.toFloat()))
}

/** Physical cart arrival includes height; crossing a pad with only the worker never completes it. */
internal fun originStableCartAt(cart: Location, pad: Location): Boolean {
    if (cart.world?.uid != pad.world?.uid || abs(cart.y - pad.y) > 0.65) return false
    val yaw = Math.toRadians(pad.yaw.toDouble())
    val dx = cart.x - pad.x; val dz = cart.z - pad.z
    return abs(dx * cos(yaw) + dz * sin(yaw)) <= 0.85 && abs(-dx * sin(yaw) + dz * cos(yaw)) <= 1.15
}

internal class OriginOddJobStableCleaning(
    private val layout: OriginStableCleaningLayout? = null,
    resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "stable-cleaning") },
) : AutoCloseable {
    private class Cleaning(val request: UUID, val world: World) {
        val piles = mutableMapOf<Int, OriginWorkshopProp>()
        val handles = mutableListOf<PacketDisplay>()
        val unloaded = mutableListOf<OriginWorkshopProp>()
        var held: OriginWorkshopProp? = null
        var stage: OriginOddJobStage? = null
        var collected = 0
    }
    private val carts = OriginOddJobCart(layout?.cart, resolveItem, createOwner, privateToWorker = true, softObstacles = true)
    private val active = mutableMapOf<UUID, Cleaning>()
    private var owner: PaperPacketDisplays? = null

    fun available(world: World): Boolean {
        val spec = layout ?: return false
        val home = Location(world, spec.cart.start.x, spec.cart.start.y, spec.cart.start.z)
        return carts.available(world) && supported(home) && (listOf(spec.unload) + spec.stalls.values.flatMap { listOf(it.cart) + it.piles }).all { supported(it.at(world)) }
    }

    fun supportsAnimal(id: Int): Boolean = layout?.stalls?.containsKey(id) == true

    fun sync(player: Player, job: OriginOddJobSession) {
        if (job.variant != OriginOddJobVariant.YARD_CLEANUP) return
        val spec = requireNotNull(layout)
        val work = requireNotNull(job.cleaning)
        val stall = spec.stalls.getValue(requireNotNull(job.yard).animalId)
        active[player.uniqueId]?.takeIf { it.request != job.id }?.let { clear(player.uniqueId, it.request) }
        val scene = active.getOrPut(player.uniqueId) { Cleaning(job.id, player.world) }
        check(scene.world.uid == player.world.uid) { "cleaning worker left the work world" }
        if (scene.stage == null) {
            check(available(player.world)) { "stable cleaning model or support is unavailable" }
            for (index in work.collected until work.total) {
                if (index !in scene.piles) scene.piles[index] = OriginWorkshopProp(renderer(), stall.piles[index].at(player.world),
                    originStableManurePieces(), privateViewer = player, onSpawn = scene.handles::add)
            }
        }
        if (work.collected > scene.collected) {
            scene.held?.let { carts.putCargo(player.uniqueId, job.id, work.collected - 1, it) }
            scene.held = null
            player.playSound(player.location, Sound.BLOCK_MUD_PLACE, 0.55f, 0.9f)
        }
        if (job.stage == OriginOddJobStage.CLEAN_LOAD && scene.held == null) {
            scene.held = requireNotNull(scene.piles.remove(work.collected)) { "assigned manure portion is unavailable" }
            player.swingMainHand()
            player.playSound(player.location, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.65f, 0.85f)
        }
        if (job.stage == OriginOddJobStage.CLEAN_RETURN && scene.stage == OriginOddJobStage.CLEAN_UNLOAD) {
            repeat(work.total) { index ->
                carts.takeCargo(player.uniqueId, job.id, index)?.let { prop ->
                    val offset = originStableUnloadOffset(index)
                    val point = spec.unload.at(player.world).add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                    prop.place(point, Quaternionf())
                    scene.unloaded += prop
                }
            }
            player.playSound(player.location, Sound.BLOCK_MUD_PLACE, 0.7f, 0.7f)
        }
        val empty = job.stage in setOf(OriginOddJobStage.CLEAN_RETURN, OriginOddJobStage.CLEAN_PARK, OriginOddJobStage.CLEAN_TOOL_RETURN,
            OriginOddJobStage.RETURN, OriginOddJobStage.PAYOUT)
        val moving = job.stage in setOf(OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN)
        carts.sync(player, job.id, moving, if (empty) emptyList() else List(work.collected) { originStableManurePieces() },
            glowing = job.stage in setOf(OriginOddJobStage.CLEAN_CART, OriginOddJobStage.CLEAN_LOAD, OriginOddJobStage.CLEAN_UNLOAD, OriginOddJobStage.CLEAN_PARK))
        scene.held?.carry(player, originStableScoopPose(player.location.yaw.toDouble(), player.isSneaking))
        scene.piles.forEach { (index, prop) -> prop.glow(if (job.stage == OriginOddJobStage.CLEAN_SCOOP && index == work.collected) Color.AQUA else null) }
        scene.stage = job.stage; scene.collected = work.collected
    }

    fun station(playerId: UUID, job: OriginOddJobSession): OriginOddJobStation? {
        val scene = active[playerId]?.takeIf { it.request == job.id } ?: return null
        if (job.stage == OriginOddJobStage.CLEAN_SCOOP) {
            val pile = scene.piles[requireNotNull(job.cleaning).collected] ?: return null
            val at = pile.center
            val block = at.clone().subtract(0.0, 0.05, 0.0).block
            return OriginOddJobStation(at.add(0.0, 0.12, 0.0), block, setOf(block.type))
        }
        return if (job.stage in setOf(OriginOddJobStage.CLEAN_CART, OriginOddJobStage.CLEAN_LOAD, OriginOddJobStage.CLEAN_UNLOAD,
                OriginOddJobStage.CLEAN_PARK, OriginOddJobStage.CLEAN_PUSH)) carts.station(playerId, job.id) else null
    }

    fun target(playerId: UUID, job: OriginOddJobSession): Location? = station(playerId, job)?.point

    fun destination(playerId: UUID, job: OriginOddJobSession): Location? {
        val scene = active[playerId]?.takeIf { it.request == job.id } ?: return null
        val spec = layout ?: return null
        val work = job.cleaning ?: return null
        return when (job.stage) {
            OriginOddJobStage.CLEAN_PUSH -> (if (work.collected == work.total) spec.unload else spec.stalls.getValue(requireNotNull(job.yard).animalId).cart).at(scene.world)
            OriginOddJobStage.CLEAN_UNLOAD -> spec.unload.at(scene.world)
            OriginOddJobStage.CLEAN_RETURN, OriginOddJobStage.CLEAN_PARK -> Location(scene.world, spec.cart.start.x, spec.cart.start.y, spec.cart.start.z, spec.cart.yaw, 0f)
            else -> null
        }
    }

    fun canPark(playerId: UUID, job: OriginOddJobSession): Boolean {
        val cart = carts.point(playerId, job.id) ?: return false
        val pad = destination(playerId, job) ?: return false
        // The cart owner already checks real wheel/chassis support. A block
        // type check at its centre rejects valid slabs, paths and edge parking.
        return carts.grounded(playerId, job.id) && originStableCartAt(cart, pad)
    }

    fun tick(player: Player, job: OriginOddJobSession): OriginOddJobAction? {
        if (job.stage !in setOf(OriginOddJobStage.CLEAN_PUSH, OriginOddJobStage.CLEAN_RETURN, OriginOddJobStage.CLEAN_PARK)) return null
        if (active[player.uniqueId]?.let { it.request == job.id && it.world.uid == player.world.uid } != true) return null
        if (job.stage == OriginOddJobStage.CLEAN_PARK) {
            val point = carts.point(player.uniqueId, job.id) ?: return null
            val home = destination(player.uniqueId, job) ?: return null
            return if (originStableCartAt(point, home) && carts.parkHome(player.uniqueId, job.id)) OriginOddJobAction.PARK_CART else null
        }
        carts.follow(player, job.id)
        val work = job.cleaning ?: return null
        if (!canPark(player.uniqueId, job)) return null
        if (job.stage == OriginOddJobStage.CLEAN_RETURN && !carts.parkHome(player.uniqueId, job.id)) return null
        return if (job.stage == OriginOddJobStage.CLEAN_PUSH && work.collected < work.total)
            OriginOddJobAction.PARK_CART else OriginOddJobAction.CART_ARRIVE
    }

    fun clear(playerId: UUID, requestId: UUID) {
        val scene = active[playerId]?.takeIf { it.request == requestId } ?: return
        active.remove(playerId)
        carts.clear(playerId, requestId)
        scene.piles.values.forEach(OriginWorkshopProp::remove)
        scene.held?.remove()
        scene.unloaded.forEach(OriginWorkshopProp::remove)
        scene.handles.filter(PacketDisplay::isValid).forEach(PacketDisplay::remove)
    }

    override fun close() {
        active.toList().forEach { (id, scene) -> clear(id, scene.request) }
        carts.close(); owner?.close(); owner = null
    }
    private fun renderer() = owner ?: createOwner().also { owner = it }
    private fun supported(at: Location): Boolean {
        val world = at.world ?: return false
        val x = floor(at.x).toInt(); val z = floor(at.z).toInt()
        return world.isChunkLoaded(x shr 4, z shr 4) && at.block.isPassable &&
            world.getBlockAt(x, floor(at.y - 0.05).toInt(), z).type.isSolid
    }
    private fun OriginScenePoint.at(world: World) = Location(world, x, y, z, yaw, pitch)
}

internal fun originStableUnloadOffset(index: Int): Vector3f = Vector3f((index - 1) * 0.43f, 0.02f, 1.7f)
