package ru.arc.origin

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
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
    private var entityCarrier: LivingEntity? = null
    private var entityPassengerOrigin = Vector()
    private var currentScale = 1f
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

    /** An authored animation keeps each index's identity; arbitrary topology changes still use [update]. */
    fun deform(geometry: List<OriginWorkshopWorkpiecePiece>) {
        check(!removed && currentItemDisplay == null)
        require(geometry.size == currentPieces.size) { "deformation must preserve semantic part count" }
        currentPieces.zip(geometry).forEach { (piece, next) ->
            piece.geometry = next
            piece.display?.let { display ->
                if (display.blockData.material != next.material) display.blockData = next.material.createBlockData()
                applyBlockPose(display, next)
            }
        }
    }

    /** Moves all visible parts around the same center and rotation pivot. */
    fun move(center: Location, rotation: Quaternionf, scale: Float = 1f) {
        setPose(center, rotation, scale, interpolationDuration = 2, teleportDuration = 1)
    }

    /** Places the workpiece at a new manual target without animating across the intervening space. */
    fun place(center: Location, rotation: Quaternionf, scale: Float = 1f) {
        setPose(center, rotation, scale, interpolationDuration = 0, teleportDuration = 0)
    }

    private fun setPose(
        center: Location,
        rotation: Quaternionf,
        scale: Float,
        interpolationDuration: Int,
        teleportDuration: Int,
    ) {
        check(!removed) { "workshop prop has been removed" }
        require(scale.isFinite() && scale > 0f)
        carrier = null
        entityCarrier = null
        currentScale = scale
        passengerTranslation.zero()
        poseInterpolationDuration = interpolationDuration
        poseTeleportDuration = teleportDuration
        currentCenter = center.clone().withoutViewRotation()
        currentRotation = Quaternionf(rotation)
        currentPieces.forEach { piece -> piece.display?.let { applyBlockPose(it, piece.geometry) } }
        currentItemDisplay?.let(::applyItemPose)
    }

    /** The client moves passengers with its player; only the local pose comes from server snapshots. */
    fun carry(player: Player, pose: OriginWorkshopCarryPose, interpolationTicks: Int = 1) {
        check(!removed) { "workshop prop has been removed" }
        require(interpolationTicks in 0..59)
        poseInterpolationDuration = if (carrier?.uniqueId == player.uniqueId) interpolationTicks else 0
        poseTeleportDuration = 0
        carrier = player
        entityCarrier = null
        currentScale = 1f
        currentCenter = player.location.add(pose.center.x, pose.center.y, pose.center.z).withoutViewRotation()
        currentRotation = Quaternionf(pose.rotation)
        // Vanilla 1.21.11 uses the player's pose height for PASSENGER and the display's feet for VEHICLE.
        passengerTranslation.set(pose.center.x.toFloat(), (pose.center.y - player.height).toFloat(), pose.center.z.toFloat())
        currentPieces.forEach { piece -> piece.display?.let { applyBlockPose(it, piece.geometry) } }
        currentItemDisplay?.let(::applyItemPose)
    }

    /** Native parent supplies movement; this world-axis pose compensates its real passenger origin. */
    fun attach(entity: LivingEntity, center: Location, rotation: Quaternionf, passengerOrigin: Vector, scale: Float = 1f) {
        check(!removed) { "workshop prop has been removed" }
        require(scale.isFinite() && scale > 0f)
        poseInterpolationDuration = if (entityCarrier?.uniqueId == entity.uniqueId) 3 else 0
        poseTeleportDuration = 0
        carrier = null
        entityCarrier = entity
        entityPassengerOrigin = passengerOrigin.clone()
        currentCenter = center.clone().withoutViewRotation()
        currentRotation = Quaternionf(rotation)
        currentScale = scale
        val feet = entity.location
        passengerTranslation.set((center.x-feet.x-passengerOrigin.x).toFloat(),
            (center.y-feet.y-passengerOrigin.y).toFloat(), (center.z-feet.z-passengerOrigin.z).toFloat())
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
            piece.geometry.let { geometry -> if (currentScale == 1f) geometry else geometry.copy(
                center = OriginWorkshopPoint(geometry.center.x*currentScale, geometry.center.y*currentScale, geometry.center.z*currentScale),
                size = OriginWorkshopGamePartSize(geometry.size.x*currentScale, geometry.size.y*currentScale, geometry.size.z*currentScale)) },
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
        entityCarrier = null
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
        currentRotation.transform(localCorner.mul(currentScale)).add(passengerTranslation)
        display.transformation = Transformation(
            localCorner,
            Quaternionf(currentRotation),
            Vector3f(geometry.size.x, geometry.size.y, geometry.size.z).mul(currentScale),
            Quaternionf(),
        )
    }

    private fun applyItemPose(display: PacketItemDisplay) {
        applyAnchor(display)
        display.transformation = Transformation(
            Vector3f(passengerTranslation),
            originWorkshopBoardItemDisplayRotation(currentRotation),
            Vector3f(currentScale),
            Quaternionf(),
        )
    }

    private fun applyAnchor(display: PacketDisplay) {
        val player = carrier
        display.interpolationDuration = poseInterpolationDuration
        display.teleportDuration = poseTeleportDuration
        val entity = entityCarrier
        if (player != null) display.attachTo(player)
        else if (entity != null) display.attachTo(entity, entityPassengerOrigin)
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
