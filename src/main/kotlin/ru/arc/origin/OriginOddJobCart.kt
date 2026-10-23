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

internal data class OriginOddJobCartPose(val point: OriginWorkshopPoint, val yaw: Float, val pitch: Float = 0f,
    val velocityX: Double = 0.0, val velocityZ: Double = 0.0, val velocityY: Double = 0.0) {
    fun rotation(): Quaternionf = Quaternionf().rotationY(Math.toRadians(180.0 - yaw).toFloat())
        .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
}

/**
 * A hand-supported cart, with the two actual cart_2 wheels and its low front feet.
 * A one-block riser exceeds the 0.75 m wheel diameter: the worker first lifts the
 * nose by the handles, then the wheels take the load and the chassis levels out.
 * All heights come from the same native collision shapes used for obstruction.
 */
internal class OriginOddJobCartMotion(private val layout: OriginForgeCartLayout, private val softObstacles: Boolean = false) {
    private data class Part(val center: Vector3f, val half: Vector3f, val rotation: Quaternionf)
    private val radius = 0.125 * maxOf(layout.scale.y, layout.scale.z)
    private val wheels = listOf(modelPoint(-3.5, -8.0, 11.0), modelPoint(19.5, -8.0, 11.0))
    private val grip = Vector3f(0f, (layout.lift + 0.1125 * layout.scale.y).toFloat(), (-0.347 * layout.scale.z).toFloat())
    private val parts = listOf(
        part(-2.5, -7.5, 1.5, 18.5, -4.5, 27.0), // bed
        part(-2.0, -4.5, 2.0, 0.0, 1.5, 27.0), part(16.0, -4.5, 2.0, 18.0, 1.5, 27.0),
        part(-3.0, -8.0, 23.5, 1.0, 3.0, 27.5), part(15.0, -8.0, 23.5, 19.0, 3.0, 27.5),
        part(-3.0, -5.0, 1.0, 1.0, 6.0, 5.0, pivotZ = 1.48465),
        part(15.0, -5.0, 1.0, 19.0, 6.0, 5.0, pivotZ = 1.48465),
        part(1.0, -4.5, 24.5, 15.0, 1.5, 26.5),
        part(-1.6, -7.0, -14.5, 0.4, -5.0, 1.5), part(15.6, -7.0, -14.5, 17.6, -5.0, 1.5),
        part(-4.5, -9.0, 10.0, 20.5, -7.0, 12.0, angle = 0.0),
        part(0.5, -6.5, -3.5, 15.5, -5.5, -1.5),
    )

    fun next(from: OriginOddJobCartPose, target: OriginWorkshopPoint, yaw: Float, workerGround: Double,
        blocks: List<BoundingBox>, cargo: Map<Int, OriginWorkshopProp> = emptyMap()): OriginOddJobCartPose {
        val dx = target.x - from.point.x; val dz = target.z - from.point.z
        val distance = hypot(dx, dz)
        if (distance > 4.0 || abs(workerGround - from.point.y) > 3.0) return from
        // Walking and sprinting must not accumulate a gap. Ease toward the
        // handles, with bounded acceleration and a 7.2 m/s catch-up ceiling.
        val brakingSpeed = kotlin.math.sqrt(0.0009 + 0.12 * distance) - 0.03
        val speed = minOf(0.36, distance * 0.5, brakingSpeed)
        val fraction = if (distance > 1e-9) speed / distance else 0.0
        val ax = dx * fraction - from.velocityX; val az = dz * fraction - from.velocityZ
        val acceleration = hypot(ax, az)
        val ease = if (acceleration > 0.06) 0.06 / acceleration else 1.0
        var vx = from.velocityX + ax * ease; var vz = from.velocityZ + az * ease
        if (dx * vx + dz * vz > 0 && hypot(vx, vz) > distance) { vx = dx; vz = dz }
        val x = from.point.x + vx; val z = from.point.z + vz
        val turn = Math.toDegrees(atan2(sin(Math.toRadians((yaw - from.yaw).toDouble())), cos(Math.toRadians((yaw - from.yaw).toDouble()))))
        val heading = from.yaw + turn.coerceIn(-6.0, 6.0).toFloat()
        // A stair tread can support the wheels; fence rails and narrow posts
        // only obstruct them. Letting every chassis contact lift the cart
        // turns such obstacles into a ramp and leaves it balanced on top.
        val terrain = terrain(blocks, minOf(from.point.y, workerGround) - 1.25, from.point.y + 1.001)
        val floor = supportHeight(from, terrain) ?: return from
        // Keep contact with an obstacle already below a raised cart until it
        // rolls clear. Dropping the chassis around that obstacle wedges it.
        val contacts = if (!softObstacles && from.point.y > floor + 0.012) blocks.filter {
            it.maxY <= from.point.y + 1.001 && it.maxY >= minOf(from.point.y, workerGround) - 1.25
        } else terrain
        // A farm cart may overlap narrow posts, rails and walls. They still
        // cannot lift it: only the same broad, exposed treads support its pose.
        val barriers = if (softObstacles) terrain else blocks
        // Read the next contact slightly ahead, so the worker can lift the nose
        // before the wooden front hits the riser. The actual swept pose remains
        // fully collision checked and never jumps to this planning point.
        val ahead = minOf(0.35, distance)
        val planX = x + if (distance > 1e-9) dx / distance * ahead else 0.0
        val planZ = z + if (distance > 1e-9) dz / distance * ahead else 0.0
        val goal = supportPose(planX, planZ, heading, from, contacts) ?: return from
        val pitch = from.pitch + ((goal.pitch - from.pitch) * 0.45f).coerceIn(-3.5f, 3.5f)
        val wantedY = ((goal.point.y - from.point.y) * 0.5).coerceIn(-0.25, 0.25)
        val velocityY = from.velocityY + (wantedY - from.velocityY).coerceIn(-0.045, 0.045)
        // Pitch and lift share a support pivot: easing them independently can
        // put a wheel below the old floor and deadlock descent/levelling.
        val currentSupport = supportHeight(OriginOddJobCartPose(from.point, heading, pitch), contacts) ?: return from
        val y = maxOf(from.point.y + velocityY, currentSupport + 0.003)
        if (y - from.point.y > 0.25) return from.copy(velocityX = 0.0, velocityZ = 0.0, velocityY = 0.0)
        val vy = y - from.point.y
        val next = OriginOddJobCartPose(OriginWorkshopPoint(x, y, z), heading, pitch, vx, vz, vy)
        if (clearSweep(from, next, barriers, cargo)) return next
        // Contact with the riser precedes translation. Lift around the held rear
        // handles instead of rejecting the entire frame or jumping onto the block.
        val lift = next.copy(point = OriginWorkshopPoint(from.point.x, y, from.point.z))
        if (clearSweep(from, lift, barriers, cargo)) {
            // Keep the safe part of this frame's travel. Discarding the whole
            // advance and restarting acceleration at each stair causes a cart
            // to fall behind a walking worker on a sustained climb.
            var low = 0.0; var high = 1.0; var accepted = lift
            repeat(5) {
                val fraction = (low + high) / 2
                val candidate = next.copy(point = OriginWorkshopPoint(from.point.x + vx * fraction, y, from.point.z + vz * fraction))
                if (clearSweep(from, candidate, barriers, cargo)) { low = fraction; accepted = candidate } else high = fraction
            }
            if (low > 0 || abs(y - from.point.y) > 0.0001 || abs(pitch - from.pitch) > 0.01) return accepted
        }
        return from.copy(velocityX = 0.0, velocityZ = 0.0, velocityY = 0.0)
    }

    fun grounded(pose: OriginOddJobCartPose, blocks: List<BoundingBox>): Boolean {
        val terrain = terrain(blocks, pose.point.y - 1.25, pose.point.y + if (softObstacles) 0.1 else 1.001)
        val support = supportHeight(pose, terrain) ?: return false
        return abs(pose.point.y - support) <= 0.012 && clear(pose, if (softObstacles) terrain else blocks)
    }

    /** Recovery follows the worker's floor, never the top of an intervening post. */
    fun recover(target: OriginWorkshopPoint, yaw: Float, workerGround: Double,
        blocks: List<BoundingBox>, cargo: Map<Int, OriginWorkshopProp> = emptyMap()): OriginOddJobCartPose? {
        val terrain = terrain(blocks, workerGround - 1.25, workerGround + 0.1)
        val level = OriginOddJobCartPose(target, yaw)
        val support = supportHeight(level, terrain) ?: return null
        if (abs(support - workerGround) > 0.65) return null
        val recovered = level.copy(point = target.copy(y = support + 0.003))
        return recovered.takeIf { clear(it, if (softObstacles) terrain else blocks, cargo) }
    }

    private fun terrain(blocks: List<BoundingBox>, minY: Double, maxY: Double): List<BoundingBox> =
        blocks.filter { it.maxY <= maxY && it.maxY >= minY &&
            minOf(it.widthX, it.widthZ) >= 0.5 && maxOf(it.widthX, it.widthZ) >= 0.999 }.filter { floor ->
            // Native pillars arrive as separate one-block boxes. Their buried
            // tops are not treads, even though each individual box is low.
            blocks.none { it.minY <= floor.maxY + 0.001 && it.maxY > floor.maxY + 0.001 &&
                it.minX <= floor.minX && it.maxX >= floor.maxX && it.minZ <= floor.minZ && it.maxZ >= floor.maxZ }
        }

    private fun supportPose(x: Double, z: Double, yaw: Float, previous: OriginOddJobCartPose,
        terrain: List<BoundingBox>): OriginOddJobCartPose? {
        var best: OriginOddJobCartPose? = null
        var score = Double.POSITIVE_INFINITY
        val rear = OriginOddJobCartPose(previous.point, yaw).rotation().transform(Vector3f(grip))
        val ground = terrain.filter { it.minX <= previous.point.x + rear.x && it.maxX >= previous.point.x + rear.x &&
            it.minZ <= previous.point.z + rear.z && it.maxZ >= previous.point.z + rear.z }
            .maxOfOrNull { it.maxY } ?: return null
        for (degrees in -42..42 step 2) {
            val pose = OriginOddJobCartPose(OriginWorkshopPoint(x, 0.0, z), yaw, degrees.toFloat())
            val required = supportHeight(pose, terrain) ?: continue
            val handle = pose.rotation().transform(Vector3f(grip))
            val candidateScore = abs(required + handle.y - ground - grip.y) + abs(degrees) * 0.0004
            if (candidateScore < score) {
                score = candidateScore
                best = pose.copy(point = OriginWorkshopPoint(x, required + 0.003, z))
            }
        }
        return best
    }

    private fun supportHeight(pose: OriginOddJobCartPose, terrain: List<BoundingBox>): Double? {
        val rotation = pose.rotation()
        val axis = rotation.transform(Vector3f(1f, 0f, 0f))
        var required = Double.NEGATIVE_INFINITY
        for (wheel in wheels) {
            val c = rotation.transform(Vector3f(wheel))
            val cx = pose.point.x + c.x; val cz = pose.point.z + c.z
            val under = terrain.asSequence().filter { axialOverlap(it, cx, cz, axis) }.mapNotNull { box ->
                val horizontal = squaredDistance(cx, box.minX, box.maxX) + squaredDistance(cz, box.minZ, box.maxZ)
                if (horizontal >= radius * radius) null else box.maxY + kotlin.math.sqrt(radius * radius - horizontal) - c.y
            }.maxOrNull() ?: return null
            required = maxOf(required, under)
        }
        // Slanted front posts meet a riser before their feet reach its top.
        // Use the exact cuboid's vertical contact interval, including its side.
        for (part in parts) for (box in terrain) {
            verticalContact(part, pose, rotation, box)?.let { required = maxOf(required, it) }
        }
        return required
    }

    /** Same ordered poses as the renderer; small substeps include in-place steering and pitch. */
    private fun clearSweep(from: OriginOddJobCartPose, to: OriginOddJobCartPose, blocks: List<BoundingBox>,
        cargo: Map<Int, OriginWorkshopProp>): Boolean {
        for (step in 1..12) {
            val t = step / 12.0
            val pose = OriginOddJobCartPose(OriginWorkshopPoint(
                from.point.x + (to.point.x - from.point.x) * t,
                from.point.y + (to.point.y - from.point.y) * t,
                from.point.z + (to.point.z - from.point.z) * t),
                from.yaw + (to.yaw - from.yaw) * t.toFloat(), from.pitch + (to.pitch - from.pitch) * t.toFloat())
            if (!clear(pose, blocks, cargo)) return false
        }
        return true
    }

    internal fun clear(pose: OriginOddJobCartPose, blocks: List<BoundingBox>,
        cargo: Map<Int, OriginWorkshopProp> = emptyMap()): Boolean {
        val rotation = pose.rotation()
        val at = pose.point
        val axis = rotation.transform(Vector3f(1f, 0f, 0f))
        for (wheel in wheels) {
            val c = rotation.transform(Vector3f(wheel))
            val x = at.x + c.x; val y = at.y + c.y; val z = at.z + c.z
            if (blocks.any { axialOverlap(it, x, z, axis) &&
                squaredDistance(x, it.minX, it.maxX) + squaredDistance(y, it.minY, it.maxY) + squaredDistance(z, it.minZ, it.maxZ) < (radius - 0.001) * (radius - 0.001) }) return false
        }
        for (part in parts) if (!clearPart(part, pose, rotation, blocks)) return false
        val bed = Quaternionf().rotateX(Math.toRadians(22.5).toFloat())
        for ((slot, prop) in cargo) for (piece in prop.pieces) {
            val geometry = piece.geometry
            val c = bed.transform(Vector3f(geometry.center.x.toFloat(), geometry.center.y.toFloat(), geometry.center.z.toFloat()))
                .add(originForgeCartLoadOffset(slot))
            if (!clearPart(Part(c, Vector3f(geometry.size.x, geometry.size.y, geometry.size.z).mul(0.5f), bed), pose, rotation, blocks)) return false
        }
        return true
    }

    private fun clearPart(part: Part, pose: OriginOddJobCartPose, rotation: Quaternionf, blocks: List<BoundingBox>): Boolean {
        val c = rotation.transform(Vector3f(part.center))
        val q = Quaternionf(rotation).mul(part.rotation)
        fun basis(x: Float, y: Float, z: Float): org.joml.Vector3d = q.transform(Vector3f(x, y, z)).let {
            org.joml.Vector3d(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()).normalize()
        }
        val volume = ru.arc.paper.display.DisplayOrientedVolume(
            org.joml.Vector3d(pose.point.x + c.x, pose.point.y + c.y, pose.point.z + c.z),
            basis(1f, 0f, 0f), basis(0f, 1f, 0f), basis(0f, 0f, 1f),
            org.joml.Vector3d(part.half.x.toDouble(), part.half.y.toDouble(), part.half.z.toDouble()))
        val bounds = volume.bounds
        return blocks.none { bounds.overlaps(it) && volume.overlaps(it) }
    }

    /** Highest vertical translation at which this real cuboid contacts a block. */
    private fun verticalContact(part: Part, pose: OriginOddJobCartPose, rotation: Quaternionf, box: BoundingBox): Double? {
        val c = rotation.transform(Vector3f(part.center))
        val q = Quaternionf(rotation).mul(part.rotation)
        val axes = arrayOf(q.transform(Vector3f(1f, 0f, 0f)), q.transform(Vector3f(0f, 1f, 0f)), q.transform(Vector3f(0f, 0f, 1f)))
        val half = doubleArrayOf(part.half.x.toDouble(), part.half.y.toDouble(), part.half.z.toDouble())
        val dx = pose.point.x + c.x - box.centerX; val dz = pose.point.z + c.z - box.centerZ
        val ex = axes.indices.sumOf { abs(axes[it].x.toDouble()) * half[it] } + box.widthX / 2
        val ez = axes.indices.sumOf { abs(axes[it].z.toDouble()) * half[it] } + box.widthZ / 2
        if (abs(dx) > ex || abs(dz) > ez) return null
        var low = Double.NEGATIVE_INFINITY; var high = Double.POSITIVE_INFINITY
        fun constrain(a: Vector3f): Boolean {
            val radius = axes.indices.sumOf { abs(a.dot(axes[it]).toDouble()) * half[it] } +
                abs(a.x) * box.widthX / 2 + abs(a.y) * box.height / 2 + abs(a.z) * box.widthZ / 2
            val offset = a.x * dx + a.y * (c.y - box.centerY) + a.z * dz
            if (abs(a.y) < 1e-8) return abs(offset) <= radius
            val first = (-radius - offset) / a.y; val second = (radius - offset) / a.y
            low = maxOf(low, minOf(first, second)); high = minOf(high, maxOf(first, second))
            return low <= high
        }
        val worldAxes = arrayOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f))
        for (a in worldAxes) if (!constrain(a)) return null
        for (a in axes) if (!constrain(a)) return null
        for (a in axes) for (b in worldAxes) if (!constrain(Vector3f(a).cross(b))) return null
        return high
    }

    private fun axialOverlap(box: BoundingBox, x: Double, z: Double, axis: Vector3f): Boolean {
        val center = (box.centerX - x) * axis.x + (box.centerZ - z) * axis.z
        val extent = box.widthX * abs(axis.x) / 2 + box.widthZ * abs(axis.z) / 2
        return abs(center) <= extent + 0.025
    }

    private fun part(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        angle: Double = 22.5, pivotZ: Double = 8.51535): Part {
        val center = rotatedModelPoint((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, angle, pivotZ)
        val half = Vector3f(((x1 - x0) * layout.scale.x / 128).toFloat(),
            ((y1 - y0) * layout.scale.y / 128).toFloat(), ((z1 - z0) * layout.scale.z / 128).toFloat())
        if (abs(layout.scale.y - layout.scale.z) < 1e-6) return Part(center, half, Quaternionf().rotateX(Math.toRadians(angle).toFloat()))
        // An operator's nonuniform Y/Z scale shears the authored cuboid. Its
        // baked local envelope remains conservative; the normal model is ×3.
        val points = listOf(x0, x1).flatMap { x -> listOf(y0, y1).flatMap { y -> listOf(z0, z1).map { z -> rotatedModelPoint(x, y, z, angle, pivotZ) } } }
        val low = Vector3f(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z })
        val high = Vector3f(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
        return Part(Vector3f(low).add(high).mul(0.5f), high.sub(low).mul(0.5f), Quaternionf())
    }
    private fun modelPoint(x: Double, y: Double, z: Double) = Vector3f(
        ((x / 16 - 0.5) * 0.25 * layout.scale.x).toFloat(),
        (((y / 16 - 0.5) * 0.25 + 0.1875) * layout.scale.y + layout.lift).toFloat(),
        ((z / 16 - 0.5) * 0.25 * layout.scale.z).toFloat())
    private fun rotatedModelPoint(x: Double, y: Double, z: Double, angle: Double = 22.5, pivotZ: Double = 8.51535): Vector3f {
        val a = Math.toRadians(angle)
        return modelPoint(x, (y + 0.75) * cos(a) - (z - pivotZ) * sin(a) - 0.75,
            (y + 0.75) * sin(a) + (z - pivotZ) * cos(a) + pivotZ)
    }
    private fun squaredDistance(value: Double, min: Double, max: Double): Double = (value - value.coerceIn(min, max)).let { it * it }
}

/** The shared cart owns its model, cargo, native carry pose and cleanup for every hauling job. */
internal class OriginOddJobCart(
    private val layout: OriginForgeCartLayout? = null,
    private val resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "forge-cart") },
    private val privateToWorker: Boolean = false,
    softObstacles: Boolean = false,
) : AutoCloseable {
    private data class Cart(val request: UUID, val world: World, val display: PacketItemDisplay,
        val cargo: MutableMap<Int, OriginWorkshopProp> = mutableMapOf(),
        val handles: MutableList<PacketDisplay> = mutableListOf(),
        var point: OriginWorkshopPoint, var yaw: Float, var pitch: Float = 0f,
        var velocityX: Double = 0.0, var velocityZ: Double = 0.0, var velocityY: Double = 0.0,
        var grounded: Boolean = false, var stalledTicks: Int = 0)
    private val carts = mutableMapOf<UUID, Cart>()
    private val space = PaperSpatialSpace()
    private val motion = layout?.let { OriginOddJobCartMotion(it, softObstacles) }
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
                display.interpolationDelay = -1; display.interpolationDuration = 1; display.teleportDuration = 1
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
        val spec = requireNotNull(layout)
        val p = cart.point
        val block = cart.world.getBlockAt(floor(p.x).toInt(), floor(p.y - 0.01).toInt(), floor(p.z).toInt())
        val angle = Math.toRadians(cart.yaw.toDouble())
        val width = 0.225 * spec.scale.x
        val length = 0.4 * spec.scale.z + abs(sin(Math.toRadians(cart.pitch.toDouble()))) * spec.lift
        val x = abs(cos(angle)) * width + abs(sin(angle)) * length
        val z = abs(sin(angle)) * width + abs(cos(angle)) * length
        return OriginOddJobStation(p.location(cart.world).add(0.0, 0.65, 0.0), block, setOf(block.type),
            interactionBounds = OriginWorkshopAabb(p.x - x, p.y - 0.15, p.z - z, p.x + x, p.y + 1.6, p.z + z))
    }

    fun point(playerId: UUID, requestId: UUID): Location? = carts[playerId]?.takeIf { it.request == requestId }
        ?.let { it.point.location(it.world) }

    fun grounded(playerId: UUID, requestId: UUID): Boolean =
        carts[playerId]?.takeIf { it.request == requestId }?.grounded == true

    /** Called only after the job has accepted arrival at its own home pad. */
    fun parkHome(playerId: UUID, requestId: UUID): Boolean {
        val cart = carts[playerId]?.takeIf { it.request == requestId } ?: return false
        val spec = requireNotNull(layout)
        val home = spec.start.location(cart.world)
        val blocks = collisionSnapshot(cart.world, localBounds(home, 1.8)) ?: return false
        val ground = groundAt(home, blocks) ?: return false
        val pose = requireNotNull(motion).recover(spec.start, spec.yaw, ground, blocks, cart.cargo) ?: return false
        move(cart, pose, blocks)
        cart.stalledTicks = 0
        render(cart)
        return true
    }

    /** Manual transfers keep the same physical cargo handle from scoop through unloading. */
    fun putCargo(playerId: UUID, requestId: UUID, index: Int, prop: OriginWorkshopProp) {
        require(index in 0..2)
        val cart = requireNotNull(carts[playerId]?.takeIf { it.request == requestId })
        cart.cargo.put(index, prop)?.takeIf { it !== prop }?.remove()
    }

    fun takeCargo(playerId: UUID, requestId: UUID, index: Int): OriginWorkshopProp? =
        carts[playerId]?.takeIf { it.request == requestId }?.cargo?.remove(index)

    private fun follow(player: Player, cart: Cart) {
        val at = player.location
        val offset = originForgeCartCarryPose(at.yaw.toDouble()).center
        val desired = OriginWorkshopPoint(at.x + offset.x, at.y, at.z + offset.z)
        val from = OriginOddJobCartPose(cart.point, cart.yaw, cart.pitch, cart.velocityX, cart.velocityZ, cart.velocityY)
        val distance = hypot(desired.x - cart.point.x, desired.z - cart.point.z)
        val recovering = distance > 3.0 || abs(at.y - cart.point.y) > 2.0 || cart.stalledTicks >= 10
        cart.grounded = false
        // A lost cart reads only the loaded area around its worker. A corridor
        // spanning the old position would turn a teleport into unbounded work.
        val bounds = if (recovering) localBounds(at, 2.8) else BoundingBox(
            minOf(cart.point.x - 1.8, at.x - 0.3), minOf(cart.point.y, at.y) - 1.3,
            minOf(cart.point.z - 1.8, at.z - 0.3), maxOf(cart.point.x + 1.8, at.x + 0.3),
            maxOf(cart.point.y, at.y) + 2.4, maxOf(cart.point.z + 1.8, at.z + 0.3))
        val blocks = collisionSnapshot(cart.world, bounds)
        val ground = blocks?.let { groundAt(at, it) }
        if (blocks != null && ground != null) {
            val solver = requireNotNull(motion)
            val next = if (recovering) solver.recover(desired, at.yaw + 180f, ground, blocks, cart.cargo)
                ?: solver.recover(OriginWorkshopPoint(at.x, at.y, at.z), at.yaw + 180f, ground, blocks, cart.cargo)
            else solver.next(from, desired, at.yaw + 180f, ground, blocks, cart.cargo)
            if (next != null) {
                val advance = distance - hypot(desired.x - next.point.x, desired.z - next.point.z)
                cart.stalledTicks = if (!recovering && distance > 0.5 && advance < 0.015)
                    minOf(10, cart.stalledTicks + 1) else 0
                move(cart, next, blocks)
            }
        }
        render(cart)
    }

    private fun move(cart: Cart, pose: OriginOddJobCartPose, blocks: List<BoundingBox>) {
        cart.point = pose.point; cart.yaw = pose.yaw; cart.pitch = pose.pitch
        cart.velocityX = pose.velocityX; cart.velocityZ = pose.velocityZ; cart.velocityY = pose.velocityY
        cart.grounded = requireNotNull(motion).grounded(pose, blocks)
    }

    private fun localBounds(at: Location, radius: Double) =
        BoundingBox(at.x - radius, at.y - 1.3, at.z - radius, at.x + radius, at.y + 2.4, at.z + radius)

    private fun collisionSnapshot(world: World, bounds: BoundingBox): List<BoundingBox>? {
        if (!space.known(world, bounds)) return null
        val blocks = mutableListOf<BoundingBox>()
        for (x in floor(bounds.minX).toInt() until ceil(bounds.maxX).toInt())
            for (y in floor(bounds.minY).toInt() until ceil(bounds.maxY).toInt())
                for (z in floor(bounds.minZ).toInt() until ceil(bounds.maxZ).toInt()) {
                    blocks += world.getBlockAt(x, y, z).collisionShape.boundingBoxes.map {
                        it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble())
                    }
                }
        return blocks
    }

    private fun groundAt(at: Location, blocks: List<BoundingBox>): Double? = blocks.asSequence().filter {
        it.minX <= at.x && it.maxX >= at.x && it.minZ <= at.z && it.maxZ >= at.z &&
            it.maxY <= at.y + 0.1 && it.maxY >= at.y - 1.5
    }.maxOfOrNull { it.maxY }

    private fun park(cart: Cart) {
        cart.velocityX = 0.0; cart.velocityZ = 0.0; cart.velocityY = 0.0
        cart.stalledTicks = 0
        render(cart)
    }

    private fun render(cart: Cart) {
        val spec = requireNotNull(layout)
        val pose = OriginOddJobCartPose(cart.point, cart.yaw, cart.pitch)
        val rotation = pose.rotation()
        // World pose owns translation and tilt. A player passenger would move
        // a blocked cart between snapshots, then jerk backwards at each update.
        val lift = rotation.transform(Vector3f(0f, spec.lift.toFloat(), 0f))
        cart.display.detach()
        cart.display.teleport(cart.point.location(cart.world).add(lift.x.toDouble(), lift.y.toDouble(), lift.z.toDouble()))
        cart.display.transformation = Transformation(Vector3f(), Quaternionf(rotation).rotateY(Math.PI.toFloat()),
            Vector3f(spec.scale), Quaternionf())
        cart.cargo.forEach { (index, prop) ->
            val offset = rotation.transform(originForgeCartLoadOffset(index))
            prop.move(cart.point.location(cart.world).add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()),
                Quaternionf(rotation).rotateX(Math.toRadians(22.5).toFloat()))
            prop.pieces.forEach { it.display?.interpolationDuration = 1 }
            prop.itemDisplay?.interpolationDuration = 1
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
