package ru.arc.origin

import org.bukkit.Material
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** ItemsAdder board models are the single visible render for each carpenter workpiece state. */
internal enum class OriginWorkshopBoardModel(val itemId: String) {
    RAW("arc_workshop:board_raw"),
    CUT_ONCE("arc_workshop:board_cut_once"),
    CUT("arc_workshop:board_cut"),
    DRILLED_1("arc_workshop:board_drilled_1"),
    DRILLED_2("arc_workshop:board_drilled_2"),
    DRILLED_3("arc_workshop:board_drilled_3"),
    OFFCUT("arc_workshop:board_offcut"),
}

private const val SEWING_NEEDLE_X = 0.42
private const val SEWING_NEEDLE_Z = -0.58
private const val SEWING_NEAR_SEAM_Z = -0.19
private const val SEWING_FEED_TRAVEL = 0.60
private const val SEWING_FEED_START_X = SEWING_NEEDLE_X + SEWING_FEED_TRAVEL / 2.0
private const val SEWING_CLOTH_CENTER_HEIGHT = 0.14

/** Table-local center for the stretched cloth as it travels across either seam under the fixed needle. */
internal fun originWorkshopSewingClothPoint(
    dimensions: OriginWorkshopTableDimensions,
    progress: Double,
): OriginWorkshopPoint {
    require(progress.isFinite()) { "sewing feed progress must be finite" }
    val feedProgress = progress.coerceIn(0.0, 1.0)
    return OriginWorkshopPoint(
        SEWING_FEED_START_X - SEWING_FEED_TRAVEL * feedProgress,
        dimensions.height + SEWING_CLOTH_CENTER_HEIGHT,
        SEWING_NEEDLE_Z - SEWING_NEAR_SEAM_Z,
    )
}

internal enum class OriginWorkshopWorkpieceRenderer(val configValue: String) {
    MODEL("model"),
    CUBES("cubes");

    companion object {
        fun parse(value: String): OriginWorkshopWorkpieceRenderer =
            entries.firstOrNull { it.configValue == value.trim().lowercase(Locale.ROOT) }
                ?: throw IllegalArgumentException("workpiece-renderer must be 'model' or 'cubes', got '$value'")
    }
}

/** Coarse 0.08-block cube layout exported by the workshop cube preview. */
internal fun originWorkshopCoarseBoardPieces(model: OriginWorkshopBoardModel): List<OriginWorkshopWorkpiecePiece> {
    val (lengthCells, holes) = when (model) {
        OriginWorkshopBoardModel.RAW -> 13 to 0
        OriginWorkshopBoardModel.CUT_ONCE -> 11 to 0
        OriginWorkshopBoardModel.CUT -> 9 to 0
        OriginWorkshopBoardModel.DRILLED_1 -> 9 to 1
        OriginWorkshopBoardModel.DRILLED_2 -> 9 to 2
        OriginWorkshopBoardModel.DRILLED_3 -> 9 to 3
        OriginWorkshopBoardModel.OFFCUT -> 2 to 0
    }
    val cell = 0.08
    val length = lengthCells * cell
    val widthCells = 3
    val missing = buildSet {
        for (holeX in COARSE_HOLE_CENTERS.take(holes)) {
            for (xIndex in 0 until lengthCells) {
                val lower = -length / 2.0 + xIndex * cell
                val upper = lower + cell
                if (lower >= holeX - cell / 2.0 - COARSE_GRID_EPSILON &&
                    upper <= holeX + cell / 2.0 + COARSE_GRID_EPSILON
                ) {
                    add(xIndex to 1)
                }
            }
        }
    }
    return buildList {
        for (xIndex in 0 until lengthCells) for (zIndex in 0 until widthCells) {
            if ((xIndex to zIndex) in missing) continue
            add(OriginWorkshopWorkpiecePiece(
                OriginWorkshopPoint(
                    -length / 2.0 + (xIndex + 0.5) * cell,
                    0.0,
                    -widthCells * cell / 2.0 + (zIndex + 0.5) * cell,
                ),
                OriginWorkshopGamePartSize(cell.toFloat(), cell.toFloat(), cell.toFloat()),
                Material.OAK_PLANKS,
            ))
        }
    }
}

/** ItemDisplay has a native Y+180 turn; cancel it once while preserving the board's world rotation. */
internal fun originWorkshopBoardItemDisplayRotation(modelRotation: Quaternionf): Quaternionf =
    Quaternionf(modelRotation).rotateY(Math.PI.toFloat())

/** Unit-cube transform for one pure analytical board piece at a centered world pose. */
internal fun originWorkshopWorkpieceHitboxMatrix(
    center: OriginWorkshopPoint,
    rotation: Quaternionf,
    piece: OriginWorkshopWorkpiecePiece,
): Matrix4f {
    val size = piece.size
    val offset = rotation.transform(Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat()))
    val half = rotation.transform(Vector3f(size.x / 2f, size.y / 2f, size.z / 2f))
    return Matrix4f().translation(
        center.x.toFloat() + offset.x - half.x,
        center.y.toFloat() + offset.y - half.y,
        center.z.toFloat() + offset.z - half.z,
    ).rotate(rotation).scale(size.x, size.y, size.z)
}

/** A board-local cuboid; center coordinates are relative to the centered workpiece. */
internal data class OriginWorkshopWorkpiecePiece(
    val center: OriginWorkshopPoint,
    val size: OriginWorkshopGamePartSize,
    val material: Material,
)

/** Workpiece center offset is relative to the player's feet. */
internal data class OriginWorkshopCarryPose(
    val center: OriginWorkshopPoint,
    val rotation: Quaternionf,
)

/** Returns the centered sawn board, with the requested progressive through-holes. */
internal fun originWorkshopBoardPieces(holes: Int): List<OriginWorkshopWorkpiecePiece> {
    require(holes in 0..DRILL_HOLE_X.size) { "workshop board supports 0..${DRILL_HOLE_X.size} drill holes" }
    if (holes == 0) return listOf(boardPiece(length = SAWN_LENGTH))

    val pieces = mutableListOf<OriginWorkshopWorkpiecePiece>()
    var cursorX = -SAWN_LENGTH / 2.0
    for (holeX in DRILL_HOLE_X.take(holes)) {
        val holeStart = holeX - HOLE_SIDE / 2.0
        val beforeLength = holeStart - cursorX
        if (beforeLength > 0.0) pieces += boardPiece((cursorX + holeStart) / 2.0, beforeLength)

        for (centerZ in listOf(-SIDE_RAIL_CENTER_Z, SIDE_RAIL_CENTER_Z)) {
            pieces += boardPiece(centerX = holeX, length = HOLE_SIDE, centerZ = centerZ, width = SIDE_RAIL_WIDTH)
        }
        cursorX = holeX + HOLE_SIDE / 2.0
    }

    val afterLength = SAWN_LENGTH / 2.0 - cursorX
    if (afterLength > 0.0) pieces += boardPiece(cursorX + afterLength / 2.0, afterLength)
    return pieces
}

/**
 * Carries narrow boards on the right shoulder, wider flat pieces upright beside the torso, and
 * bulky/tall parts low at the hip. The bounds come from the caller's actual workpiece pieces.
 */
internal fun originWorkshopCarryPose(
    bodyYawDegrees: Double,
    sneaking: Boolean,
    geometry: List<OriginWorkshopWorkpiecePiece>,
): OriginWorkshopCarryPose {
    require(bodyYawDegrees.isFinite()) { "player yaw must be finite" }
    require(geometry.isNotEmpty()) { "carried workpiece geometry must not be empty" }
    geometry.forEach { piece ->
        require(
            piece.center.x.isFinite() && piece.center.y.isFinite() && piece.center.z.isFinite() &&
                piece.size.x.isFinite() && piece.size.y.isFinite() && piece.size.z.isFinite() &&
                piece.size.x > 0f && piece.size.y > 0f && piece.size.z > 0f,
        ) { "carried workpiece geometry must have finite centers and positive finite sizes" }
    }

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    geometry.forEach { piece ->
        val halfX = piece.size.x / 2.0
        val halfY = piece.size.y / 2.0
        val halfZ = piece.size.z / 2.0
        minX = min(minX, piece.center.x - halfX)
        minY = min(minY, piece.center.y - halfY)
        minZ = min(minZ, piece.center.z - halfZ)
        maxX = max(maxX, piece.center.x + halfX)
        maxY = max(maxY, piece.center.y + halfY)
        maxZ = max(maxZ, piece.center.z + halfZ)
    }

    val sizeX = maxX - minX
    val sizeY = maxY - minY
    val sizeZ = maxZ - minZ
    val horizontalSpans = listOf(sizeX, sizeZ).sortedDescending()
    val flat = minOf(sizeX, sizeY, sizeZ) <= CARRY_FLAT_THICKNESS && maxOf(sizeX, sizeY, sizeZ) >= CARRY_MIN_FLAT_LENGTH
    val narrowBoard = flat && horizontalSpans[1] <= CARRY_NARROW_WIDTH
    val uprightPanel = flat && !narrowBoard

    val yaw = Math.toRadians(bodyYawDegrees)
    val forward = Vector3f((-sin(yaw)).toFloat(), 0f, cos(yaw).toFloat())
    val right = Vector3f((-cos(yaw)).toFloat(), 0f, (-sin(yaw)).toFloat())
    val rotation = Quaternionf().rotationY(atan2(-forward.z.toDouble(), forward.x.toDouble()).toFloat())
    if (uprightPanel) rotation.rotateX(Math.PI.toFloat() / 2f)

    val boundsCenter = Vector3f(
        ((minX + maxX) / 2.0).toFloat(),
        ((minY + maxY) / 2.0).toFloat(),
        ((minZ + maxZ) / 2.0).toFloat(),
    )
    rotation.transform(boundsCenter)
    val axisX = rotation.transform(Vector3f(1f, 0f, 0f))
    val axisY = rotation.transform(Vector3f(0f, 1f, 0f))
    val axisZ = rotation.transform(Vector3f(0f, 0f, 1f))
    val halfX = sizeX / 2.0
    val halfY = sizeY / 2.0
    val halfZ = sizeZ / 2.0
    val halfRight = abs(dot(axisX, right)) * halfX + abs(dot(axisY, right)) * halfY + abs(dot(axisZ, right)) * halfZ
    val halfUp = abs(axisX.y) * halfX + abs(axisY.y) * halfY + abs(axisZ.y) * halfZ

    val rightClearance = if (narrowBoard) {
        CARRY_HEAD_RADIUS + CARRY_HEAD_CLEARANCE
    } else {
        CARRY_BODY_SIDE_HALF_WIDTH + CARRY_BODY_SIDE_CLEARANCE
    }
    val centerRight = rightClearance + halfRight
    val centerForward = if (narrowBoard) -CARRY_BOARD_BACK_OFFSET else -CARRY_SIDE_BACK_OFFSET
    val centerUp = when {
        narrowBoard -> CARRY_SHOULDER_SUPPORT_Y + halfUp
        uprightPanel -> CARRY_PANEL_CENTER_Y
        else -> CARRY_HIP_FLOOR_Y + halfUp
    } - if (sneaking) CARRY_SNEAKING_DROP else 0.0
    val center = OriginWorkshopPoint(
        forward.x * centerForward + right.x * centerRight - boundsCenter.x,
        centerUp - boundsCenter.y,
        forward.z * centerForward + right.z * centerRight - boundsCenter.z,
    )
    return OriginWorkshopCarryPose(center, rotation)
}

private fun dot(first: Vector3f, second: Vector3f): Double =
    first.x.toDouble() * second.x + first.y.toDouble() * second.y + first.z.toDouble() * second.z

private const val CARRY_FLAT_THICKNESS = 0.10
private const val CARRY_MIN_FLAT_LENGTH = 0.35
private const val CARRY_NARROW_WIDTH = 0.26
private const val CARRY_HEAD_RADIUS = 0.25
private const val CARRY_HEAD_CLEARANCE = 0.04
// Right arm's outer edge in the player-sized body used by the carry geometry and preview.
private const val CARRY_BODY_SIDE_HALF_WIDTH = 0.47
private const val CARRY_BODY_SIDE_CLEARANCE = 0.04
private const val CARRY_BOARD_BACK_OFFSET = 0.14
private const val CARRY_SIDE_BACK_OFFSET = 0.04
// Feet-relative shoulder support plane; geometry-derived half-height places the board's bottom here.
private const val CARRY_SHOULDER_SUPPORT_Y = 1.40
private const val CARRY_PANEL_CENTER_Y = 0.94
private const val CARRY_HIP_FLOOR_Y = 0.62
private const val CARRY_SNEAKING_DROP = 0.30

private fun boardPiece(
    centerX: Double = 0.0,
    length: Double,
    centerZ: Double = 0.0,
    width: Double = BOARD_WIDTH,
) = OriginWorkshopWorkpiecePiece(
    center = OriginWorkshopPoint(centerX, 0.0, centerZ),
    size = OriginWorkshopGamePartSize(length.toFloat(), BOARD_THICKNESS.toFloat(), width.toFloat()),
    material = Material.OAK_PLANKS,
)

private const val BOARD_THICKNESS = 0.08
private const val BOARD_WIDTH = 0.22
private const val SAWN_LENGTH = 0.72
private const val HOLE_SIDE = 0.075
private const val SIDE_RAIL_WIDTH = (BOARD_WIDTH - HOLE_SIDE) / 2.0
private const val SIDE_RAIL_CENTER_Z = HOLE_SIDE / 2.0 + SIDE_RAIL_WIDTH / 2.0
private val DRILL_HOLE_X = listOf(-0.23, 0.0, 0.23)
private val COARSE_HOLE_CENTERS = listOf(-0.24, 0.0, 0.24)
private const val COARSE_GRID_EPSILON = 1.0e-9

/** A solid private target; full-brightness faces complement the native glow silhouette. */
internal fun originWorkshopPlacementMarker(
    action: OriginWorkshopGameAction? = null,
    targetFaceDepth: Double? = null,
): List<OriginWorkshopWorkpiecePiece> {
    require(targetFaceDepth == null || (targetFaceDepth.isFinite() && targetFaceDepth > 0.0)) {
        "placement target face depth must be positive and finite"
    }
    require(targetFaceDepth == null || action in setOf(
        OriginWorkshopGameAction.TIGHTEN_LEFT,
        OriginWorkshopGameAction.TIGHTEN_RIGHT,
    )) { "placement target face depth is reserved for fastener targets" }
    val compact = when (action) {
        OriginWorkshopGameAction.SAND_PANEL_NEAR, OriginWorkshopGameAction.SAND_PANEL_CENTER,
        OriginWorkshopGameAction.SAND_PANEL_FAR, OriginWorkshopGameAction.COAT_PANEL_NEAR,
        OriginWorkshopGameAction.COAT_PANEL_CENTER, OriginWorkshopGameAction.COAT_PANEL_FAR,
        OriginWorkshopGameAction.FLIP_PANEL -> true
        else -> false
    }
    val span = if (compact || targetFaceDepth != null) 0.075f else 0.14f
    val center = if (targetFaceDepth != null) {
        OriginWorkshopPoint(0.0, 0.0, -span / 2.0 - targetFaceDepth / 2.0 - 0.0025)
    } else {
        OriginWorkshopPoint(0.0, if (compact) 0.05 else 0.08, 0.0)
    }
    // One solid cube stays filled from both table-level and overhead views.
    return listOf(OriginWorkshopWorkpiecePiece(
        center, OriginWorkshopGamePartSize(span, span, span), Material.LIGHT_BLUE_CONCRETE,
    ))
}
