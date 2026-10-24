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

internal fun originYardTieMask(job: OriginOddJobSession): Int? = when (job.stage) {
    OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_HORSE_FIRST,
    OriginOddJobStage.YARD_TIE_SECOND, OriginOddJobStage.YARD_TIE_HORSE_SECOND,
    OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.YARD_GROOM, OriginOddJobStage.YARD_TACK,
    OriginOddJobStage.YARD_SADDLE, OriginOddJobStage.YARD_BRUSH_RETURN -> requireNotNull(job.yard).tieMask
    else -> null
}

/** Wall order is a player's choice; a carried end is not yet an attached cross-tie. */
internal fun originYardTieTargets(job: OriginOddJobSession): List<Int> {
    val mask = originYardTieMask(job) ?: return emptyList()
    return when (job.stage) {
        OriginOddJobStage.YARD_TIE_FIRST, OriginOddJobStage.YARD_TIE_SECOND -> (0..1).filter { mask and (1 shl it) == 0 }
        else -> emptyList()
    }
}

internal fun originYardTieStateValid(job: OriginOddJobSession): Boolean {
    val work = job.yard ?: return false
    val mask = originYardTieMask(job) ?: return false
    if (mask !in 0..3) return false
    val pending = work.pendingTieSide
    return when (job.stage) {
        OriginOddJobStage.YARD_TIE_FIRST -> mask == 0 && pending == null
        OriginOddJobStage.YARD_TIE_HORSE_FIRST -> mask == 0 && pending in 0..1
        OriginOddJobStage.YARD_TIE_SECOND -> Integer.bitCount(mask) == 1 && pending == null
        OriginOddJobStage.YARD_TIE_HORSE_SECOND -> Integer.bitCount(mask) == 1 && pending in 0..1 && mask and (1 shl requireNotNull(pending)) == 0
        else -> mask == 3 && pending == null
    }
}

/** Minecraft +Z is south, so the worker's right hand is on the -X side at yaw zero. */
internal fun originYardTieHand(worker: Location, height: Double): Location {
    val yaw = Math.toRadians(worker.yaw.toDouble())
    return worker.clone().add(-0.28 * cos(yaw) - 0.35 * sin(yaw), height * 0.68,
        -0.28 * sin(yaw) + 0.35 * cos(yaw))
}

/** The calm horse's real head pivot; shared with the source preview, with no bridle overlay. */
internal fun originYardTieCheek(horse: Location, height: Double, side: Int): Location {
    val scale=(height/1.6).toFloat()
    val pose=originYardGearHorsePose(OriginYardGear.BRIDLE,horse,horse.yaw,scale,horse.yaw,horse.pitch)
    val cheek=pose.rotation.transform(Vector3f(if(side==0) -.18f else .18f,-.18f,-.425f).mul(scale))
    return pose.at.add(cheek.x.toDouble(),cheek.y.toDouble(),cheek.z.toDouble())
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

internal fun originYardRopePieces(side: Int, head: Vector3f, anchor: Vector3f, slack: Float = 0f): List<OriginYardTiePiece> = buildList {
    // Two crossed square strands make a narrow, solid octagonal cord, visible from every direction.
    // Ten short sections retain the vanilla wood grain instead of stretching one texture four metres.
    repeat(10) { segment ->
        fun point(t: Float) = Vector3f(head).lerp(anchor, t).apply { y -= 4 * slack * t * (1 - t) }
        val a = point(segment / 10f)
        val b = point((segment + 1) / 10f)
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

/** One leased horse, two surveyed wall faces, at most 66 cosmetic displays; no native leash entities. */
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
    private var activeTargets = emptyList<Int>()
    private var pendingSide: Int? = null

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
        val head = originYardTieCheek(horse, height, side)
        val delta = anchors[side].toVector().subtract(head.toVector())
        val length = delta.length()
        length in 0.5..6.0 && horse.world?.rayTraceBlocks(head, delta.normalize(), length - 0.02,
            FluidCollisionMode.NEVER, true) == null
    }

    fun setStage(job: OriginOddJobSession, horse: Location, height: Double) {
        require(originYardTieStateValid(job))
        mask = requireNotNull(originYardTieMask(job))
        activeTargets = originYardTieTargets(job)
        val pending = job.yard?.pendingTieSide
        require(pending == null || pending in 0..1 && mask and (1 shl pending) == 0)
        pendingSide = pending
        render(horse, height)
    }

    fun render(horse: Location, height: Double) {
        check(!closed)
        val next = buildList {
            anchors.indices.forEach { side ->
                val anchor = local(anchors[side])
                addAll(originYardRingPieces(side, anchor, normal(side)))
                if (pendingSide == side) {
                    val hand = local(originYardTieHand(player.location, player.height))
                    addAll(originYardRopePieces(side, hand, anchor, slack = 0.22f))
                    addAll(originYardRingPieces(side + 2, local(originYardTieCheek(horse, height, side)), normal(side).negate(), cue = true))
                } else if (mask and (1 shl side) != 0) {
                    addAll(originYardRopePieces(side, local(originYardTieCheek(horse, height, side)), anchor))
                }
                if (side in activeTargets) addAll(originYardRingPieces(side, anchor, normal(side), cue = true))
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
                val cue = piece.key.startsWith("cue-")
                it.isVisibleByDefault = false
                it.billboard = Display.Billboard.FIXED
                it.viewRange = 0.5f; it.displayWidth = 10f; it.displayHeight = 3f; it.shadowRadius = 0f
                it.interpolationDelay = -1; it.interpolationDuration = 2; it.teleportDuration = 0
                it.showTo(player)
                if (cue) {
                    it.isGlowing = true; it.glowColorOverride = Color.AQUA; it.brightness = Display.Brightness(15, 15)
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
