package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.BoundingBox
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginSceneItemDisplayContext
import ru.arc.origin.scene.OriginSceneStep
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.ceil

internal data class OriginForgeCartRoute(
    val points: List<OriginWorkshopPoint>, val itemId: String, val scale: Vector3f, val lift: Double,
    val context: ItemDisplay.ItemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND,
) {
    val length = points.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.z - a.z) }
    init { require(points.size in 2..8 && length in 1.0..48.0 && points.all { it.y == points.first().y }) }

    /** The same authored route and grounded ItemsAdder model as Savva's ambient work cycle. */
    companion object {
        fun from(scene: OriginSceneDefinition): OriginForgeCartRoute {
            val model = scene.cycles.single { it.id == "ore-inspection" }.steps
                .filterIsInstance<OriginSceneStep.ItemDisplay>().first { it.key == "savva-cart" && it.anchor == "ore-load-cart" }
            val floor = scene.anchors.getValue("ore-load-stand").y
            return OriginForgeCartRoute(listOf("ore-load-cart", "ore-route-entry", "ore-route-turn", "ore-passage", "ore-park-cart")
                .map { scene.anchors.getValue(it).let { point -> OriginWorkshopPoint(point.x, floor, point.z) } },
                model.itemId, Vector3f(model.scale.x.toFloat(), model.scale.y.toFloat(), model.scale.z.toFloat()),
                scene.anchors.getValue("ore-load-cart").y - floor,
                when (model.context) {
                    OriginSceneItemDisplayContext.NONE -> ItemDisplay.ItemDisplayTransform.NONE
                    OriginSceneItemDisplayContext.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
                    OriginSceneItemDisplayContext.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
                    OriginSceneItemDisplayContext.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
                })
        }
    }

    fun point(distance: Double, returning: Boolean = false): OriginWorkshopPoint {
        var remaining = (if (returning) length - distance else distance).coerceIn(0.0, length)
        for ((a, b) in points.zipWithNext()) {
            val span = hypot(b.x - a.x, b.z - a.z)
            if (remaining <= span && span > 0) return OriginWorkshopPoint(
                a.x + (b.x - a.x) * remaining / span, a.y, a.z + (b.z - a.z) * remaining / span)
            remaining -= span
        }
        return points.last()
    }

    /** A short look-ahead turns the handles continuously through the authored passage corners. */
    fun direction(distance: Double, returning: Boolean): Pair<Double, Double> {
        val a = point((distance - 0.35).coerceAtLeast(0.0), returning)
        val b = point((distance + 0.35).coerceAtMost(length), returning)
        val span = hypot(b.x - a.x, b.z - a.z)
        return (b.x - a.x) / span to (b.z - a.z) / span
    }

    /** Arc position of a walking player, including the handle approach before either endpoint. */
    fun project(player: OriginWorkshopPoint, returning: Boolean): Double {
        var walked = 0.0
        var nearest = Double.POSITIVE_INFINITY
        var result = 0.0
        points.zipWithNext().forEachIndexed { index, (a, b) ->
            val dx = b.x - a.x; val dz = b.z - a.z
            val span = hypot(dx, dz)
            val raw = ((player.x - a.x) * dx + (player.z - a.z) * dz) / (span * span)
            val part = raw.coerceIn(if (index == 0) -2.0 / span else 0.0,
                if (index == points.lastIndex - 1) 1.0 + 2.0 / span else 1.0)
            val error = hypot(player.x - a.x - dx * part, player.z - a.z - dz * part)
            if (error < nearest) { nearest = error; result = walked + span * part }
            walked += span
        }
        return if (returning) length - result else result
    }
}

/** Movement pays for movement: no time-based progress, remote pushing, teleport jump or click completion. */
internal class OriginForgeCartMotion(val route: OriginForgeCartRoute, val returning: Boolean) {
    var distance = 0.0
        private set
    private var previous: OriginWorkshopPoint? = null
    val point: OriginWorkshopPoint get() = route.point(distance, returning)
    val yaw: Float get() = route.direction(distance, returning).let { (x, z) -> Math.toDegrees(atan2(-x, z)).toFloat() }
    val arrived: Boolean get() = distance >= route.length - 1e-6

    fun step(player: OriginWorkshopPoint, clear: (Double, Double) -> Boolean = { _, _ -> true }): Boolean {
        val old = previous; previous = player
        if (old == null || arrived) return false
        val dx = player.x - old.x; val dz = player.z - old.z
        val moved = hypot(dx, dz)
        if (moved !in 0.001..0.65 || kotlin.math.abs(player.y - point.y) > 0.7) return false
        val (x, z) = route.direction(distance, returning)
        val handleX = point.x - x * 1.0; val handleZ = point.z - z * 1.0
        if (hypot(player.x - handleX, player.z - handleZ) > 1.1 ||
            (player.x - point.x) * x + (player.z - point.z) * z > 0.35) return false
        // Project onto the same route the player walks. A tangent dot product stalls at a
        // right-angle corner while the player is still walking behind the cart on the prior leg.
        val push = (route.project(player, returning) - route.project(old, returning)).coerceIn(0.0, minOf(moved, 0.32))
        if (push == 0.0) return false
        val next = (distance + push).coerceAtMost(route.length)
        if (!clear(distance, next)) return false
        distance = next
        return true
    }
}

/** One bounded local cart. Session cancellation removes every handle; it never mutates world blocks. */
internal class OriginOddJobForge(
    private val route: OriginForgeCartRoute? = null,
    private val resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "forge-cart") },
    private val collisionBoxes: (Block) -> Collection<BoundingBox> = { it.collisionShape.boundingBoxes },
) : AutoCloseable {
    private data class Cart(val request: UUID, val world: World, val display: PacketItemDisplay,
        val cargo: MutableMap<Int, OriginWorkshopProp> = mutableMapOf(),
        val handles: MutableList<PacketDisplay> = mutableListOf(),
        var motion: OriginForgeCartMotion, var stage: OriginOddJobStage)
    private val carts = mutableMapOf<UUID, Cart>()
    private var owner: PaperPacketDisplays? = null
    private var model: ItemStack? = null

    fun available(world: World, inspectRoute: Boolean = true): Boolean {
        val path = route ?: return false
        if (model == null) model = runCatching { resolveItem(path.itemId) }.getOrNull()
        return model != null && path.points.all { world.isChunkLoaded(floor(it.x).toInt() shr 4, floor(it.z).toInt() shr 4) } &&
            (!inspectRoute || clear(world, 0.0, path.length, false) && clear(world, 0.0, path.length, true))
    }

    fun sync(player: Player, job: OriginOddJobSession) {
        if (job.kind != OriginOddJobKind.FORGE_HAUL) return
        val path = requireNotNull(route)
        val old = carts[player.uniqueId]
        if (old != null && old.request != job.id) clear(player.uniqueId, old.request)
        val cart = carts.getOrPut(player.uniqueId) {
            check(available(player.world)) { "forge cart route or model is unavailable" }
            val at = path.points.first().location(player.world).add(0.0, path.lift, 0.0)
            val display = renderer().spawnItem(at, requireNotNull(model))
            try { display.also {
                it.itemDisplayTransform = path.context
                it.billboard = Display.Billboard.FIXED
                it.viewRange = 0.5f; it.displayWidth = 3f; it.displayHeight = 2f
                it.interpolationDelay = -1; it.interpolationDuration = 1; it.teleportDuration = 1
                it.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(path.scale), Quaternionf())
            } } catch (failure: Exception) { display.remove(); throw failure }
            Cart(job.id, player.world, display, motion = OriginForgeCartMotion(path, false), stage = job.stage)
        }
        if (job.stage == OriginOddJobStage.HAUL_RETURN_CART && cart.stage != job.stage)
            cart.motion = OriginForgeCartMotion(path, true)
        cart.stage = job.stage
        val work = requireNotNull(job.haul)
        val first = work.delivered + if (job.stage == OriginOddJobStage.HAUL_DELIVER) 1 else 0
        val present = (first until work.loaded).toSet()
        cart.cargo.keys.filter { it !in present }.forEach { cart.cargo.remove(it)?.remove() }
        for (index in present) if (index !in cart.cargo) cart.cargo[index] = OriginWorkshopProp(renderer(),
            cart.motion.point.location(player.world), originForgeCargoPieces(work.cargo), onSpawn = cart.handles::add)
        pose(cart)
    }

    fun tick(player: Player, job: OriginOddJobSession): Boolean {
        val cart = carts[player.uniqueId]?.takeIf { it.request == job.id } ?: return false
        if (job.stage !in setOf(OriginOddJobStage.HAUL_PUSH, OriginOddJobStage.HAUL_RETURN_CART)) return false
        val at = player.location
        if (cart.motion.step(OriginWorkshopPoint(at.x, at.y, at.z)) { from, to ->
            clear(cart.world, from, to, cart.motion.returning)
        }) pose(cart)
        return cart.motion.arrived
    }

    fun station(playerId: UUID, job: OriginOddJobSession): OriginOddJobStation? {
        if (job.stage !in setOf(OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_UNLOAD)) return null
        val cart = carts[playerId]?.takeIf { it.request == job.id } ?: return null
        val p = cart.motion.point
        val block = cart.world.getBlockAt(floor(p.x).toInt(), floor(p.y - 0.01).toInt(), floor(p.z).toInt())
        return OriginOddJobStation(p.location(cart.world).add(0.0, 0.65, 0.0), block, setOf(block.type))
    }

    fun target(playerId: UUID, job: OriginOddJobSession): Location? {
        val cart = carts[playerId]?.takeIf { it.request == job.id } ?: return null
        return when (job.stage) {
            OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_UNLOAD -> station(playerId, job)?.point
            OriginOddJobStage.HAUL_PUSH -> route!!.points.last().location(cart.world).add(0.0, 0.4, 0.0)
            OriginOddJobStage.HAUL_RETURN_CART -> route!!.points.first().location(cart.world).add(0.0, 0.4, 0.0)
            else -> null
        }
    }

    private fun pose(cart: Cart) {
        val at = cart.motion.point.location(cart.world)
        val yaw = cart.motion.yaw
        cart.display.teleport(at.clone().add(0.0, route!!.lift, 0.0).apply { this.yaw = yaw + 180f })
        val rotation = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
        val cargoRotation = Quaternionf(rotation).rotateX(Math.toRadians(22.5).toFloat())
        cart.cargo.forEach { (index, prop) ->
            val offset = originForgeCartLoadOffset(index)
            rotation.transform(offset)
            prop.move(at.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()), cargoRotation)
        }
    }

    /**
     * Ground-context source mesh is 1.172 × 1.998 × 1.016 blocks. Inspect its full swept
     * footprint, including both corner turns, against native collision boxes in loaded chunks.
     * 2.5 cm sweep spacing plus 6 cm margin covers translation and rotation between samples.
     * Only carpet-height collisions below the axle/body are allowed; the loaded body is 1.2 m high.
     */
    private fun clear(world: World, from: Double, to: Double, returning: Boolean): Boolean {
        val path = requireNotNull(route)
        val cache = mutableMapOf<Triple<Int, Int, Int>, Collection<BoundingBox>>()
        fun boxes(x: Int, y: Int, z: Int) = cache.getOrPut(Triple(x, y, z)) { collisionBoxes(world.getBlockAt(x, y, z)) }
        val count = ceil((to - from) / 0.025).toInt().coerceAtLeast(1)
        for (sample in 0..count) {
            val distance = from + (to - from) * sample / count
            val p = path.point(distance, returning)
            val (dx, dz) = path.direction(distance, returning)
            val halfX = abs(dx) * 1.16 + abs(dz) * 0.66
            val halfZ = abs(dz) * 1.16 + abs(dx) * 0.66
            fun overlaps(x: Double, z: Double, hx: Double, hz: Double): Boolean {
                val cx = x - p.x; val cz = z - p.z
                return abs(cx) < halfX + hx && abs(cz) < halfZ + hz &&
                    abs(cx * dx + cz * dz) < 1.16 + hx * abs(dx) + hz * abs(dz) &&
                    abs(cx * dz - cz * dx) < 0.66 + hx * abs(dz) + hz * abs(dx)
            }
            for (x in floor(p.x - halfX).toInt()..floor(p.x + halfX).toInt())
                for (z in floor(p.z - halfZ).toInt()..floor(p.z + halfZ).toInt()) {
                    if (!overlaps(x + 0.5, z + 0.5, 0.5, 0.5)) continue
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
                    if (boxes(x, floor(p.y - 0.01).toInt(), z).none { it.minX <= 0 && it.maxX >= 1 &&
                        it.minZ <= 0 && it.maxZ >= 1 && it.maxY >= 0.999 }) return false
                    for (y in floor(p.y).toInt()..floor(p.y + 1.2).toInt()) for (box in boxes(x, y, z)) {
                        if (y + box.maxY <= p.y + 0.07 || y + box.minY >= p.y + 1.2) continue
                        if (overlaps(x + box.centerX, z + box.centerZ, box.widthX / 2, box.widthZ / 2)) return false
                    }
                }
        }
        return true
    }

    fun clear(playerId: UUID, requestId: UUID) {
        val cart = carts[playerId]?.takeIf { it.request == requestId } ?: return
        carts.remove(playerId)
        cart.cargo.values.forEach(OriginWorkshopProp::remove)
        cart.handles.filter(PacketDisplay::isValid).forEach(PacketDisplay::remove)
        cart.display.remove()
    }

    override fun close() {
        carts.toMap().forEach { (player, cart) -> clear(player, cart.request) }
        owner?.close(); owner = null
    }
    private fun renderer() = owner ?: createOwner().also { owner = it }
    private fun OriginWorkshopPoint.location(world: World) = Location(world, x, y, z)
}

/** Three separate baskets follow the real 22.5-degree bed, with no overlap or floating base. */
internal fun originForgeCartLoadOffset(index: Int): Vector3f {
    val z = -0.15 + index * 0.35
    // cart_2 bed top (model y=-4.5), GROUND context ×3. Two millimetres avoid coplanar bottom faces.
    return Vector3f(0f, (0.536585666 - z * kotlin.math.tan(Math.toRadians(22.5))).toFloat(), z.toFloat())
}
