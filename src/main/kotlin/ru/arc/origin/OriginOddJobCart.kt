package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialSpace
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * cart_2 element bounds after its GROUND context, before the configured display scale/lift.
 * Include handles and wheels, and every rotational extremum along the shortest yaw arc.
 */
internal fun originOddJobCartSweptBounds(from: OriginWorkshopPoint, fromYaw: Float,
    to: OriginWorkshopPoint, toYaw: Float, layout: OriginForgeCartLayout): BoundingBox {
    // JOML's rotationY(a) maps +Z toward +X; the X/Z polar angle therefore uses -a.
    val angle = Math.toRadians(fromYaw - 180.0)
    val delta = Math.toRadians((toYaw - fromYaw).toDouble())
    val turn = atan2(sin(delta), cos(delta))
    // At a half turn the float quaternion dot can select either slerp arc; cover both.
    val end = angle + if (abs(abs(turn) - Math.PI) <= 1e-5) Math.PI * 2 else turn
    val xs = mutableListOf<Double>(); val zs = mutableListOf<Double>()
    for (x in listOf(-0.1953125, 0.1953125)) for (z in listOf(-0.3615598789197727, 0.3045303505978045)) {
        val localX = x * layout.scale.x; val localZ = z * layout.scale.z
        val radius = hypot(localX, localZ); val corner = atan2(localZ, localX)
        val startAngle = angle + corner; val endAngle = end + corner
        val quarter = Math.PI / 2
        val extrema = (ceil(minOf(startAngle, endAngle) / quarter).toInt()..floor(maxOf(startAngle, endAngle) / quarter).toInt())
            .map { it * quarter }
        for (sample in listOf(startAngle, endAngle) + extrema) {
            xs += radius * cos(sample); zs += radius * sin(sample)
        }
    }
    return BoundingBox(minOf(from.x, to.x) + xs.min(),
        minOf(from.y, to.y) + layout.lift - 0.1875 * layout.scale.y + 1e-5,
        minOf(from.z, to.z) + zs.min(), maxOf(from.x, to.x) + xs.max(),
        maxOf(from.y, to.y) + layout.lift + 0.15111959953042072 * layout.scale.y,
        maxOf(from.z, to.z) + zs.max())
}

/** The shared cart owns its model, cargo, native carry pose and cleanup for every hauling job. */
internal class OriginOddJobCart(
    private val layout: OriginForgeCartLayout? = null,
    private val resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "forge-cart") },
    private val privateToWorker: Boolean = false,
) : AutoCloseable {
    private data class Cart(val request: UUID, val world: World, val display: PacketItemDisplay,
        val cargo: MutableMap<Int, OriginWorkshopProp> = mutableMapOf(),
        val handles: MutableList<PacketDisplay> = mutableListOf(),
        var point: OriginWorkshopPoint, var yaw: Float, var attached: Boolean = false)
    private val carts = mutableMapOf<UUID, Cart>()
    private val space = PaperSpatialSpace()
    private var owner: PaperPacketDisplays? = null
    private var model: ItemStack? = null

    fun available(world: World): Boolean {
        val spec = layout ?: return false
        if (model == null) model = runCatching { resolveItem(spec.itemId) }.getOrNull()
        return model != null && world.isChunkLoaded(floor(spec.start.x).toInt() shr 4, floor(spec.start.z).toInt() shr 4)
    }

    fun exists(playerId: UUID, requestId: UUID): Boolean = carts[playerId]?.request == requestId

    fun sync(player: Player, requestId: UUID, moving: Boolean,
        loads: List<List<OriginWorkshopWorkpiecePiece>>, glowing: Boolean = false) {
        require(loads.size <= 3)
        val spec = requireNotNull(layout)
        val old = carts[player.uniqueId]
        if (old != null && old.request != requestId) clear(player.uniqueId, old.request)
        val cart = carts.getOrPut(player.uniqueId) {
            check(available(player.world)) { "cart model or parking point is unavailable" }
            val at = spec.start.location(player.world).add(0.0, spec.lift, 0.0).apply { yaw = spec.yaw }
            val display = renderer().spawnItem(at, requireNotNull(model))
            try {
                if (privateToWorker) display.isVisibleByDefault = false
                display.itemDisplayTransform = spec.context
                display.billboard = Display.Billboard.FIXED
                display.viewRange = 0.5f; display.displayWidth = 3f; display.displayHeight = 2f
                display.interpolationDelay = -1; display.interpolationDuration = 1; display.teleportDuration = 0
                display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(spec.scale), Quaternionf())
                if (privateToWorker) display.showTo(player)
            } catch (failure: Exception) { display.remove(); throw failure }
            Cart(requestId, player.world, display, point = spec.start, yaw = spec.yaw)
        }
        cart.cargo.keys.filter { it !in loads.indices }.forEach { cart.cargo.remove(it)?.remove() }
        loads.forEachIndexed { index, pieces ->
            if (index !in cart.cargo) cart.cargo[index] = OriginWorkshopProp(renderer(),
                cart.point.location(player.world), pieces, privateViewer = if (privateToWorker) player else null,
                onSpawn = cart.handles::add)
        }
        cart.display.isGlowing = glowing
        cart.display.glowColorOverride = Color.AQUA
        if (moving) follow(player, cart) else park(cart)
    }

    fun follow(player: Player, requestId: UUID) {
        carts[player.uniqueId]?.takeIf { it.request == requestId && it.world.uid == player.world.uid }?.let { follow(player, it) }
    }

    fun station(playerId: UUID, requestId: UUID): OriginOddJobStation? {
        val cart = carts[playerId]?.takeIf { it.request == requestId } ?: return null
        val p = cart.point
        val block = cart.world.getBlockAt(floor(p.x).toInt(), floor(p.y - 0.01).toInt(), floor(p.z).toInt())
        return OriginOddJobStation(p.location(cart.world).add(0.0, 0.65, 0.0), block, setOf(block.type))
    }

    fun point(playerId: UUID, requestId: UUID): Location? = carts[playerId]?.takeIf { it.request == requestId }
        ?.let { it.point.location(it.world) }

    /** Manual transfers keep the same physical cargo handle from scoop through unloading. */
    fun putCargo(playerId: UUID, requestId: UUID, index: Int, prop: OriginWorkshopProp) {
        require(index in 0..2)
        val cart = requireNotNull(carts[playerId]?.takeIf { it.request == requestId })
        cart.cargo.put(index, prop)?.takeIf { it !== prop }?.remove()
    }

    fun takeCargo(playerId: UUID, requestId: UUID, index: Int): OriginWorkshopProp? =
        carts[playerId]?.takeIf { it.request == requestId }?.cargo?.remove(index)

    private fun follow(player: Player, cart: Cart) {
        val spec = requireNotNull(layout)
        val at = player.location
        // A player's body yaw may face sideways while looking/steering; packet passengers do not inherit it.
        val pose = originForgeCartCarryPose(at.yaw.toDouble())
        val desired = OriginWorkshopPoint(at.x + pose.center.x, at.y, at.z + pose.center.z)
        val desiredYaw = at.yaw + 180f
        if (canMove(cart, desired, desiredYaw)) {
            cart.point = desired
            cart.yaw = desiredYaw
        }
        val center = OriginWorkshopPoint(cart.point.x - at.x, cart.point.y - at.y, cart.point.z - at.z)
        val rotation = Quaternionf().rotationY(Math.toRadians(180.0 - cart.yaw).toFloat())
        if (!cart.attached) cart.display.teleport(at.clone().apply { yaw = 0f; pitch = 0f })
        cart.display.transformation = Transformation(
            Vector3f(center.x.toFloat(), (center.y + spec.lift - player.height).toFloat(), center.z.toFloat()),
            Quaternionf(rotation).rotateY(Math.PI.toFloat()), Vector3f(spec.scale), Quaternionf())
        cart.display.attachTo(player)
        cart.attached = true
        cart.cargo.forEach { (index, prop) ->
            val offset = originForgeCartLoadOffset(index)
            rotation.transform(offset)
            prop.carry(player, OriginWorkshopCarryPose(OriginWorkshopPoint(center.x + offset.x,
                center.y + offset.y, center.z + offset.z), Quaternionf(rotation).rotateX(Math.toRadians(22.5).toFloat())))
        }
    }

    private fun canMove(cart: Cart, desired: OriginWorkshopPoint, yaw: Float): Boolean {
        val from = cart.point
        // A blocked cart stays put; returning to it resumes motion without a long scan or tunnelling.
        if (abs(desired.x - from.x) > 4 || abs(desired.z - from.z) > 4 || abs(desired.y - from.y) > 1.5) return false
        // The client interpolates directly between these poses, so validate that same swept path.
        return space.clearBounds(cart.world,
            originOddJobCartSweptBounds(from, cart.yaw, desired, yaw, requireNotNull(layout)))
    }

    private fun park(cart: Cart) {
        val spec = requireNotNull(layout)
        val at = cart.point.location(cart.world)
        cart.display.detach()
        cart.attached = false
        cart.display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(spec.scale), Quaternionf())
        cart.display.teleport(at.clone().add(0.0, spec.lift, 0.0).apply { yaw = cart.yaw })
        val rotation = Quaternionf().rotationY(Math.toRadians(180.0 - cart.yaw).toFloat())
        val cargoRotation = Quaternionf(rotation).rotateX(Math.toRadians(22.5).toFloat())
        cart.cargo.forEach { (index, prop) ->
            val offset = originForgeCartLoadOffset(index)
            rotation.transform(offset)
            prop.move(at.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()), cargoRotation)
        }
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
        owner?.close(); owner = null; model = null
    }
    private fun renderer() = owner ?: createOwner().also { owner = it }
    private fun OriginWorkshopPoint.location(world: World) = Location(world, x, y, z)
}
