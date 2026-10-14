package ru.arc.origin

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** One rendered or hit-testable workpiece cuboid. Model-rendered pieces have no block display. */
internal data class OriginWorkshopPropPiece(
    val display: PacketBlockDisplay?,
    var geometry: OriginWorkshopWorkpiecePiece,
)

/**
 * Packet-backed workpiece with a stable group pivot. Each block display remains at the group center;
 * its local translation, rotation, and scale represent the matching cuboid around that pivot.
 */
internal class OriginWorkshopProp(
    private val owner: PaperPacketDisplays,
    center: Location,
    geometry: List<OriginWorkshopWorkpiecePiece>,
    model: ItemStack? = null,
    private val privateViewer: Player? = null,
    private val onSpawn: (PacketDisplay) -> Unit = {},
) {
    private var currentCenter = center.clone().withoutViewRotation()
    private var currentRotation = Quaternionf()
    private var removed = false
    private var carrier: Player? = null
    private val passengerTranslation = Vector3f()
    private var poseInterpolationDuration = 2
    private var poseTeleportDuration = 1

    val pieces: List<OriginWorkshopPropPiece>
        get() = currentPieces
    private var currentPieces: List<OriginWorkshopPropPiece> = emptyList()

    val itemDisplay: PacketItemDisplay?
        get() = currentItemDisplay
    private var currentItemDisplay: PacketItemDisplay? = null

    /** Defensive copies keep callers from changing the packet pose without calling [move]. */
    val center: Location
        get() = currentCenter.clone()
    val rotation: Quaternionf
        get() = Quaternionf(currentRotation)

    init {
        update(geometry, model)
    }

    /** Reuses packet handles only when their cuboid center and size still match. */
    fun update(geometry: List<OriginWorkshopWorkpiecePiece>, model: ItemStack? = null) {
        check(!removed) { "workshop prop has been removed" }
        if (model != null) {
            currentPieces.forEach { it.display?.remove() }
            currentPieces = geometry.map { OriginWorkshopPropPiece(display = null, geometry = it) }
            val existing = currentItemDisplay
            val item = existing ?: spawnItemDisplay(model).also { currentItemDisplay = it }
            if (existing != null && item.itemStack != model) item.itemStack = model.clone()
            return
        }

        val wasModelRendered = currentItemDisplay != null
        currentItemDisplay?.remove()
        currentItemDisplay = null
        if (wasModelRendered) currentPieces = emptyList()
        currentPieces = reconcileBlockPieces(geometry)
    }

    /** Moves all visible parts around the same center and rotation pivot. */
    fun move(center: Location, rotation: Quaternionf) {
        setPose(center, rotation, interpolationDuration = 2, teleportDuration = 1)
    }

    /** Places the workpiece at a new manual target without animating across the intervening space. */
    fun place(center: Location, rotation: Quaternionf) {
        setPose(center, rotation, interpolationDuration = 0, teleportDuration = 0)
    }

    private fun setPose(
        center: Location,
        rotation: Quaternionf,
        interpolationDuration: Int,
        teleportDuration: Int,
    ) {
        check(!removed) { "workshop prop has been removed" }
        carrier = null
        passengerTranslation.zero()
        poseInterpolationDuration = interpolationDuration
        poseTeleportDuration = teleportDuration
        currentCenter = center.clone().withoutViewRotation()
        currentRotation = Quaternionf(rotation)
        currentPieces.forEach { piece -> piece.display?.let { applyBlockPose(it, piece.geometry) } }
        currentItemDisplay?.let(::applyItemPose)
    }

    /** The client moves passengers with its player; only the local pose comes from server snapshots. */
    fun carry(player: Player, pose: OriginWorkshopCarryPose) {
        check(!removed) { "workshop prop has been removed" }
        poseInterpolationDuration = if (carrier?.uniqueId == player.uniqueId) 1 else 0
        poseTeleportDuration = 0
        carrier = player
        currentCenter = player.location.add(pose.center.x, pose.center.y, pose.center.z).withoutViewRotation()
        currentRotation = Quaternionf(pose.rotation)
        // Vanilla 1.21.11 uses the player's pose height for PASSENGER and the display's feet for VEHICLE.
        passengerTranslation.set(pose.center.x.toFloat(), (pose.center.y - player.height).toFloat(), pose.center.z.toFloat())
        currentPieces.forEach { piece -> piece.display?.let { applyBlockPose(it, piece.geometry) } }
        currentItemDisplay?.let(::applyItemPose)
    }

    /** Hitbox for one cuboid, matching the same local pivot used by the packet transformation. */
    fun hitboxMatrix(piece: OriginWorkshopPropPiece): Matrix4f? {
        if (removed) return null
        val at = currentCenter
        return originWorkshopWorkpieceHitboxMatrix(
            OriginWorkshopPoint(at.x, at.y, at.z),
            currentRotation,
            piece.geometry,
        )
    }

    fun glow(color: Color?) {
        displays().forEach {
            it.isGlowing = color != null
            it.glowColorOverride = color
        }
    }

    /** A high-contrast private cue for block-rendered placement markers. */
    fun cue(hovered: Boolean) {
        currentPieces.mapNotNull { it.display }.forEach {
            it.isGlowing = true
            it.glowColorOverride = if (hovered) Color.WHITE else Color.AQUA
            it.brightness = Display.Brightness(15, 15)
            it.blockData = (if (hovered) Material.WHITE_CONCRETE else Material.LIGHT_BLUE_CONCRETE).createBlockData()
        }
    }

    fun remove() {
        if (removed) return
        removed = true
        carrier = null
        currentPieces.forEach { it.display?.remove() }
        currentPieces = emptyList()
        currentItemDisplay?.remove()
        currentItemDisplay = null
    }

    private fun reconcileBlockPieces(geometry: List<OriginWorkshopWorkpiecePiece>): List<OriginWorkshopPropPiece> {
        val previous = currentPieces
        val unused = previous.indices.toMutableSet()
        val result = arrayOfNulls<OriginWorkshopPropPiece>(geometry.size)

        // Match all spatially stable handles before removing unmatched cuboids.
        geometry.forEachIndexed { newIndex, nextGeometry ->
            val match = unused.firstOrNull { oldIndex -> sameCuboid(previous[oldIndex].geometry, nextGeometry) }
            if (match != null) {
                result[newIndex] = previous[match]
                unused.remove(match)
            }
        }

        // A handle at another cuboid would visually animate across unrelated geometry. Remove
        // unmatched old parts before allocating replacements so each surviving ID stays spatially true.
        unused.forEach { previous[it].display?.remove() }

        geometry.forEachIndexed { newIndex, nextGeometry ->
            val matched = result[newIndex]
            val piece = matched ?: OriginWorkshopPropPiece(display = spawnBlockDisplay(nextGeometry), geometry = nextGeometry)

            piece.geometry = nextGeometry
            if (matched != null) piece.display?.let { display ->
                if (display.blockData.material != nextGeometry.material) {
                    display.blockData = nextGeometry.material.createBlockData()
                }
                applyBlockPose(display, nextGeometry)
            }
            result[newIndex] = piece
        }

        return result.map { checkNotNull(it) }
    }

    private fun spawnBlockDisplay(geometry: OriginWorkshopWorkpiecePiece): PacketBlockDisplay {
        val display = owner.spawnBlock(currentCenter.clone(), geometry.material.createBlockData())
        onSpawn(display)
        configureBlockDisplay(display)
        applyBlockPose(display, geometry)
        showIfPrivate(display)
        return display
    }

    private fun spawnItemDisplay(model: ItemStack): PacketItemDisplay {
        val display = owner.spawnItem(currentCenter.clone(), model.clone())
        onSpawn(display)
        configureItemDisplay(display)
        applyItemPose(display)
        showIfPrivate(display)
        return display
    }

    private fun configureBlockDisplay(display: PacketBlockDisplay) {
        display.isVisibleByDefault = privateViewer == null
        display.billboard = Display.Billboard.FIXED
        display.viewRange = VIEW_RANGE
        display.displayWidth = 1f
        display.displayHeight = 1f
        display.shadowRadius = 0f
        display.interpolationDelay = -1
        display.interpolationDuration = 2
        display.teleportDuration = 1
    }

    private fun configureItemDisplay(display: PacketItemDisplay) {
        display.isVisibleByDefault = privateViewer == null
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.FIXED
        display.viewRange = VIEW_RANGE
        display.displayWidth = 1f
        display.displayHeight = 1f
        display.shadowRadius = 0f
        display.interpolationDelay = -1
        display.interpolationDuration = 2
        display.teleportDuration = 1
    }

    private fun applyBlockPose(
        display: PacketBlockDisplay,
        geometry: OriginWorkshopWorkpiecePiece,
    ) {
        applyAnchor(display)
        val localCorner = Vector3f(
            geometry.center.x.toFloat() - geometry.size.x / 2f,
            geometry.center.y.toFloat() - geometry.size.y / 2f,
            geometry.center.z.toFloat() - geometry.size.z / 2f,
        )
        currentRotation.transform(localCorner).add(passengerTranslation)
        display.transformation = Transformation(
            localCorner,
            Quaternionf(currentRotation),
            Vector3f(geometry.size.x, geometry.size.y, geometry.size.z),
            Quaternionf(),
        )
    }

    private fun applyItemPose(display: PacketItemDisplay) {
        applyAnchor(display)
        display.transformation = Transformation(
            Vector3f(passengerTranslation),
            originWorkshopBoardItemDisplayRotation(currentRotation),
            Vector3f(1f),
            Quaternionf(),
        )
    }

    private fun applyAnchor(display: PacketDisplay) {
        val player = carrier
        display.interpolationDuration = poseInterpolationDuration
        display.teleportDuration = poseTeleportDuration
        if (player != null) display.attachTo(player)
        else {
            display.detach()
            display.teleport(currentCenter.clone())
        }
    }

    private fun showIfPrivate(display: PacketDisplay) {
        privateViewer?.let(display::showTo)
    }

    private fun displays(): List<PacketDisplay> =
        currentItemDisplay?.let(::listOf) ?: currentPieces.mapNotNull { it.display }

    private fun Location.withoutViewRotation(): Location = apply {
        yaw = 0f
        pitch = 0f
    }

    private companion object {
        const val VIEW_RANGE = 0.5f
        const val GEOMETRY_EPSILON = 1.0e-5

        fun sameCuboid(first: OriginWorkshopWorkpiecePiece, second: OriginWorkshopWorkpiecePiece): Boolean =
            close(first.center.x, second.center.x) &&
                close(first.center.y, second.center.y) &&
                close(first.center.z, second.center.z) &&
                close(first.size.x.toDouble(), second.size.x.toDouble()) &&
                close(first.size.y.toDouble(), second.size.y.toDouble()) &&
                close(first.size.z.toDouble(), second.size.z.toDouble())

        fun close(first: Double, second: Double): Boolean = kotlin.math.abs(first - second) <= GEOMETRY_EPSILON
    }
}
