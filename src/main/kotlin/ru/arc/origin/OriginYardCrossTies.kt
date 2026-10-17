package ru.arc.origin

import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import kotlin.math.cos
import kotlin.math.sin

internal val ORIGIN_YARD_CARE_PAD = OriginScenePoint(60.5, 70.0, -126.5, 180f)
internal val ORIGIN_YARD_TIE_FIRST = OriginScenePoint(56.06, 71.4, -127.5, -90f)
internal val ORIGIN_YARD_TIE_SECOND = OriginScenePoint(63.94, 71.4, -127.5, 90f)

internal fun originYardTieTarget(stage: OriginOddJobStage): Int? = when (stage) {
    OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_UNTIE_FIRST -> 0
    OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_UNTIE_SECOND -> 1
    else -> null
}

internal fun originYardTieMask(stage: OriginOddJobStage): Int? = when (stage) {
    OriginOddJobStage.YARD_TIE_FIRST -> 0
    OriginOddJobStage.YARD_TIE_SECOND -> 1
    OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_TACK,
    OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_UNTIE_FIRST -> 3
    OriginOddJobStage.YARD_UNTIE_SECOND -> 2
    else -> null
}

/** Source geometry shared by packet rendering and the offline cross-tie preview. */
internal data class OriginYardTiePiece(
    val key: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf, val material: Material,
)

private fun tieSpan(key: String, a: Vector3f, b: Vector3f, width: Float, material: Material, roll: Float = 0f): OriginYardTiePiece {
    val delta = Vector3f(b).sub(a)
    return OriginYardTiePiece(key, Vector3f(a).add(b).mul(0.5f), Vector3f(width, width, delta.length()),
        Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()).rotateZ(roll), material)
}

internal fun originYardRopePieces(side: Int, head: Vector3f, anchor: Vector3f): List<OriginYardTiePiece> = buildList {
    // Two crossed square strands make a narrow, solid octagonal cord, visible from every direction.
    // Ten short sections retain the vanilla wood grain instead of stretching one texture four metres.
    repeat(10) { segment ->
        val a = Vector3f(head).lerp(anchor, segment / 10f)
        val b = Vector3f(head).lerp(anchor, (segment + 1) / 10f)
        repeat(2) { strand ->
            add(tieSpan("rope-$side-$segment-$strand", a, b, 0.038f, Material.STRIPPED_OAK_WOOD,
                (segment * Math.PI / 8 + strand * Math.PI / 4).toFloat()))
        }
    }
}

internal fun originYardRingPieces(side: Int, center: Vector3f, normal: Vector3f, cue: Boolean = false): List<OriginYardTiePiece> {
    val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), normal)
    val radius = if (cue) 0.225f else 0.13f
    val segments = if (cue) 4 else 8
    fun point(index: Int): Vector3f {
        val angle = 2 * Math.PI * index / segments
        return rotation.transform(Vector3f((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), if (cue) 0.025f else 0f)).add(center)
    }
    return buildList {
        repeat(segments) { index -> add(tieSpan("${if (cue) "cue" else "ring"}-$side-$index", point(index), point(index + 1),
            if (cue) 0.018f else 0.032f, if (cue) Material.LIGHT_BLUE_CONCRETE else Material.IRON_BLOCK)) }
        if (!cue) add(OriginYardTiePiece("plate-$side", Vector3f(normal).mul(-0.045f).add(center),
            Vector3f(0.16f, 0.2f, 0.035f), rotation, Material.POLISHED_DEEPSLATE))
    }
}

/** Exact ring volume is also the accepted click target; a wall in front of it always obstructs it. */
internal fun originYardTieAimed(player: Player, station: OriginOddJobStation, range: Double, clicked: Block?): Boolean {
    val p = station.point
    if (player.world.uid != p.world?.uid || !station.valid()) return false
    if (clicked != null && clicked != station.block) return false
    val eye = player.eyeLocation
    val ray = eye.direction
    val distance = originWorkshopRayAabbHit(OriginWorkshopVec3(eye.x, eye.y, eye.z), OriginWorkshopVec3(ray.x, ray.y, ray.z),
        OriginWorkshopAabb(p.x - 0.25, p.y - 0.25, p.z - 0.25, p.x + 0.25, p.y + 0.25, p.z + 0.25), range) ?: return false
    // The aim volume extends slightly into its own mounting block. Other faces never count.
    val blocker = player.world.rayTraceBlocks(eye, ray, distance + 0.02, FluidCollisionMode.NEVER, true)?.hitBlock
    return blocker == null || blocker == station.block && eye.toVector().subtract(p.toVector()).dot(p.direction) > 0
}

/** One leased horse, two surveyed wall faces, at most 62 cosmetic displays; no native leash entities. */
internal class OriginYardCrossTies(
    private val renderer: PaperPacketDisplays, private val player: Player, origin: Location,
    private val anchors: List<Location>,
) : AutoCloseable {
    private val origin = origin.clone().apply { yaw = 0f; pitch = 0f }
    private val parts = linkedMapOf<String, PacketBlockDisplay>()
    private var previous = emptyList<OriginYardTiePiece>()
    private var closed = false
    var mask = 0
        private set
    private var activeTarget: Int? = null

    private fun local(at: Location): Vector3f = Vector3f((at.x - origin.x).toFloat(), (at.y - origin.y).toFloat(), (at.z - origin.z).toFloat())
    private fun normal(side: Int): Vector3f = anchors[side].direction.let { Vector3f(it.x.toFloat(), 0f, it.z.toFloat()).normalize() }

    fun station(side: Int): OriginOddJobStation? {
        val at = anchors[side]
        val support = at.clone().subtract(at.direction.multiply(0.12))
        val world = at.world ?: return null
        if (!world.isChunkLoaded(support.blockX shr 4, support.blockZ shr 4)) return null
        val block = support.block
        // Full solid faces only: fences, gates, cauldrons and an absent wall cannot hold this ring.
        if (!block.type.isOccluding || !block.type.isSolid || !block.boundingBox.contains(support.toVector())) return null
        return OriginOddJobStation(at.clone(), block, setOf(block.type))
    }

    fun valid(): Boolean = !closed && anchors.indices.all { station(it) != null }

    fun ropesClear(horse: Location, height: Double): Boolean = anchors.indices.all { side ->
        val head = cheek(horse, height, side)
        val delta = anchors[side].toVector().subtract(head.toVector())
        val length = delta.length()
        length in 0.5..6.0 && horse.world?.rayTraceBlocks(head, delta.normalize(), length - 0.02,
            FluidCollisionMode.NEVER, true) == null
    }

    private fun cheek(horse: Location, height: Double, side: Int): Location {
        val forward = horse.direction.setY(0).normalize()
        val sideways = org.bukkit.util.Vector(forward.z, 0.0, -forward.x).multiply(if (side == 0) 0.2 else -0.2)
        return horse.clone().add(forward.multiply(0.8)).add(0.0, height * 0.82, 0.0).add(sideways)
    }

    fun setStage(stage: OriginOddJobStage, horse: Location, height: Double) {
        mask = requireNotNull(originYardTieMask(stage))
        activeTarget = originYardTieTarget(stage)
        render(horse, height)
    }

    fun render(horse: Location, height: Double) {
        check(!closed)
        val next = buildList {
            anchors.indices.forEach { side ->
                val anchor = local(anchors[side])
                addAll(originYardRingPieces(side, anchor, normal(side)))
                if (mask and (1 shl side) != 0) {
                    addAll(originYardRopePieces(side, local(cheek(horse, height, side)), anchor))
                }
                if (activeTarget == side) addAll(originYardRingPieces(side, anchor, normal(side), cue = true))
            }
        }
        val wanted = next.mapTo(mutableSetOf()) { it.key }
        parts.keys.filterNot { it in wanted }.forEach { key -> parts.getValue(key).remove(); parts.remove(key) }
        next.forEach { piece ->
            val existing = parts[piece.key]
            if (existing != null && previous.firstOrNull { it.key == piece.key } == piece) return@forEach
            val display = existing ?: renderer.spawnBlock(origin, piece.material.createBlockData()).also {
                // Register before configuration so partial creation remains cleanable by this owner.
                parts[piece.key] = it
                val private = piece.key.startsWith("cue-")
                it.isVisibleByDefault = !private
                it.billboard = Display.Billboard.FIXED
                it.viewRange = 0.5f; it.displayWidth = 10f; it.displayHeight = 3f; it.shadowRadius = 0f
                it.interpolationDelay = -1; it.interpolationDuration = 2; it.teleportDuration = 0
                if (private) {
                    it.isGlowing = true; it.glowColorOverride = Color.AQUA; it.brightness = Display.Brightness(15, 15)
                    it.showTo(player)
                }
            }
            val corner = Vector3f(piece.size).mul(-0.5f)
            piece.rotation.transform(corner).add(piece.center)
            display.transformation = Transformation(corner, Quaternionf(piece.rotation), Vector3f(piece.size), Quaternionf())
        }
        previous = next
    }

    override fun close() {
        val failures = mutableListOf<Exception>()
        parts.toMap().forEach { (key, display) ->
            try { display.remove(); parts.remove(key) } catch (failure: Exception) { failures += failure }
        }
        previous = emptyList(); closed = parts.isEmpty()
        if (failures.isNotEmpty()) throw IllegalStateException("Could not remove ${failures.size} cross-tie displays").apply {
            failures.forEach(::addSuppressed)
        }
    }
}
