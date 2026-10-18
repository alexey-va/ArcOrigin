package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginSceneItemDisplayContext
import ru.arc.origin.scene.OriginSceneStep
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** The job has its own parking point and reuses Savva's measured model and ground correction. */
internal data class OriginForgeCartLayout(
    val start: OriginWorkshopPoint, val yaw: Float, val itemId: String, val scale: Vector3f, val lift: Double,
    val context: ItemDisplay.ItemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND,
) {
    companion object {
        fun from(scene: OriginSceneDefinition): OriginForgeCartLayout {
            val model = scene.cycles.single { it.id == "ore-inspection" }.steps
                .filterIsInstance<OriginSceneStep.ItemDisplay>().first { it.key == "savva-cart" && it.anchor == "ore-load-cart" }
            val start = scene.anchors.getValue("job-haul-cart")
            return OriginForgeCartLayout(OriginWorkshopPoint(start.x, start.y, start.z), start.yaw,
                model.itemId, Vector3f(model.scale.x.toFloat(), model.scale.y.toFloat(), model.scale.z.toFloat()),
                scene.anchors.getValue("ore-load-cart").y - scene.anchors.getValue("ore-load-stand").y,
                when (model.context) {
                    OriginSceneItemDisplayContext.NONE -> ItemDisplay.ItemDisplayTransform.NONE
                    OriginSceneItemDisplayContext.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
                    OriginSceneItemDisplayContext.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
                    OriginSceneItemDisplayContext.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
                })
        }
    }
}

/** One metre behind the worker: the cart's handles face the player, independent of a preset route. */
internal fun originForgeCartCarryPose(bodyYaw: Double): OriginWorkshopCarryPose {
    val yaw = Math.toRadians(bodyYaw)
    return OriginWorkshopCarryPose(OriginWorkshopPoint(sin(yaw), 0.0, -cos(yaw)),
        Quaternionf().rotationY((Math.PI - yaw).toFloat()))
}

/** One leased, client-only cart. Cargo and cart share the player's native passenger attachment. */
internal class OriginOddJobForge(
    private val layout: OriginForgeCartLayout? = null,
    private val resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "forge-cart") },
) : AutoCloseable {
    private data class Cart(val request: UUID, val world: World, val display: PacketItemDisplay,
        val cargo: MutableMap<Int, OriginWorkshopProp> = mutableMapOf(),
        val handles: MutableList<PacketDisplay> = mutableListOf(),
        var point: OriginWorkshopPoint, var yaw: Float, var attached: Boolean = false)
    private val carts = mutableMapOf<UUID, Cart>()
    private var owner: PaperPacketDisplays? = null
    private var model: ItemStack? = null

    fun available(world: World): Boolean {
        val spec = layout ?: return false
        if (model == null) model = runCatching { resolveItem(spec.itemId) }.getOrNull()
        return model != null && world.isChunkLoaded(floor(spec.start.x).toInt() shr 4, floor(spec.start.z).toInt() shr 4)
    }

    fun sync(player: Player, job: OriginOddJobSession) {
        if (job.kind != OriginOddJobKind.FORGE_HAUL) return
        val spec = requireNotNull(layout)
        val old = carts[player.uniqueId]
        if (old != null && old.request != job.id) clear(player.uniqueId, old.request)
        val cart = carts.getOrPut(player.uniqueId) {
            check(available(player.world)) { "forge cart model or parking point is unavailable" }
            val at = spec.start.location(player.world).add(0.0, spec.lift, 0.0).apply { yaw = spec.yaw }
            val display = renderer().spawnItem(at, requireNotNull(model))
            try { display.also {
                it.itemDisplayTransform = spec.context
                it.billboard = Display.Billboard.FIXED
                it.viewRange = 0.5f; it.displayWidth = 3f; it.displayHeight = 2f
                it.interpolationDelay = -1; it.interpolationDuration = 1; it.teleportDuration = 0
                it.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(spec.scale), Quaternionf())
            } } catch (failure: Exception) { display.remove(); throw failure }
            Cart(job.id, player.world, display, point = spec.start, yaw = spec.yaw)
        }
        val loaded = job.stage in setOf(OriginOddJobStage.HAUL_ATTACH, OriginOddJobStage.HAUL_PUSH, OriginOddJobStage.HAUL_UNLOAD)
        if (!loaded) { cart.cargo.values.forEach(OriginWorkshopProp::remove); cart.cargo.clear() }
        else for (index in 0..2) if (index !in cart.cargo) cart.cargo[index] = OriginWorkshopProp(renderer(),
            cart.point.location(player.world), originForgeCargoPieces(requireNotNull(job.haul).cargo), onSpawn = cart.handles::add)
        cart.display.isGlowing = job.stage in setOf(OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_ATTACH)
        cart.display.glowColorOverride = Color.AQUA
        if (job.stage == OriginOddJobStage.HAUL_PUSH) follow(player, cart) else park(cart)
    }

    fun tick(player: Player, job: OriginOddJobSession, destination: OriginOddJobStation): Boolean {
        val cart = carts[player.uniqueId]?.takeIf { it.request == job.id } ?: return false
        if (job.stage != OriginOddJobStage.HAUL_PUSH || player.world.uid != cart.world.uid) return false
        follow(player, cart)
        val target = destination.block
        return destination.valid() && target.world.uid == cart.world.uid &&
            hypot(cart.point.x - target.x - 0.5, cart.point.z - target.z - 0.5) <= 2.25 &&
            abs(cart.point.y - target.y) <= 3.0
    }

    fun station(playerId: UUID, job: OriginOddJobSession): OriginOddJobStation? {
        if (job.stage !in setOf(OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_ATTACH)) return null
        val cart = carts[playerId]?.takeIf { it.request == job.id } ?: return null
        val p = cart.point
        val block = cart.world.getBlockAt(floor(p.x).toInt(), floor(p.y - 0.01).toInt(), floor(p.z).toInt())
        return OriginOddJobStation(p.location(cart.world).add(0.0, 0.65, 0.0), block, setOf(block.type))
    }

    fun target(playerId: UUID, job: OriginOddJobSession): Location? = station(playerId, job)?.point

    private fun follow(player: Player, cart: Cart) {
        val spec = requireNotNull(layout)
        val pose = originForgeCartCarryPose(player.bodyYaw.toDouble())
        val at = player.location
        cart.point = OriginWorkshopPoint(at.x + pose.center.x, at.y, at.z + pose.center.z)
        cart.yaw = player.bodyYaw
        if (!cart.attached) cart.display.teleport(at.clone().apply { yaw = 0f; pitch = 0f })
        cart.display.transformation = Transformation(
            Vector3f(pose.center.x.toFloat(), (spec.lift - player.height).toFloat(), pose.center.z.toFloat()),
            Quaternionf(pose.rotation).rotateY(Math.PI.toFloat()), Vector3f(spec.scale), Quaternionf())
        cart.display.attachTo(player)
        cart.attached = true
        cart.cargo.forEach { (index, prop) ->
            val offset = originForgeCartLoadOffset(index)
            pose.rotation.transform(offset)
            prop.carry(player, OriginWorkshopCarryPose(OriginWorkshopPoint(pose.center.x + offset.x,
                offset.y.toDouble(), pose.center.z + offset.z), Quaternionf(pose.rotation).rotateX(Math.toRadians(22.5).toFloat())))
        }
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

/** Three separate baskets follow the real 22.5-degree bed, with no overlap or floating base. */
internal fun originForgeCartLoadOffset(index: Int): Vector3f {
    val z = -0.15 + index * 0.35
    // cart_2 bed top (model y=-4.5), GROUND context ×3. Two millimetres avoid coplanar bottom faces.
    return Vector3f(0f, (0.536585666 - z * kotlin.math.tan(Math.toRadians(22.5))).toFloat(), z.toFloat())
}
