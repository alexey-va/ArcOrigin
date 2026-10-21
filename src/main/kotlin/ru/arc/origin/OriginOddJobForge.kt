package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.origin.scene.OriginSceneDefinition
import ru.arc.origin.scene.OriginSceneItemDisplayContext
import ru.arc.origin.scene.OriginSceneStep
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
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

/** One metre ahead, like Savva's ambient cart; the model's -Z handles point back to the worker. */
internal fun originForgeCartCarryPose(viewYaw: Double): OriginWorkshopCarryPose {
    val yaw = Math.toRadians(viewYaw)
    return OriginWorkshopCarryPose(OriginWorkshopPoint(-sin(yaw), 0.0, cos(yaw)),
        Quaternionf().rotationY(-yaw.toFloat()))
}

/** Forge haul keeps its existing completion rules while using the common physical cart owner. */
internal class OriginOddJobForge(
    layout: OriginForgeCartLayout? = null,
    resolveItem: (String) -> ItemStack? = { CustomStack.getInstance(it)?.itemStack?.clone() },
    createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "forge-cart") },
) : AutoCloseable {
    private val cart = OriginOddJobCart(layout, resolveItem, createOwner)
    fun available(world: World): Boolean = cart.available(world)
    fun sync(player: Player, job: OriginOddJobSession) {
        if (job.kind != OriginOddJobKind.FORGE_HAUL) return
        if (job.stage == OriginOddJobStage.HAUL_PUSH && !cart.exists(player.uniqueId, job.id)) return
        val loaded = job.stage in setOf(OriginOddJobStage.HAUL_ATTACH, OriginOddJobStage.HAUL_PUSH, OriginOddJobStage.HAUL_UNLOAD)
        cart.sync(player, job.id, job.stage == OriginOddJobStage.HAUL_PUSH,
            if (loaded) List(3) { originForgeCargoPieces(requireNotNull(job.haul).cargo) } else emptyList(),
            glowing = job.stage in setOf(OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_ATTACH))
    }
    fun tick(player: Player, job: OriginOddJobSession, destination: OriginOddJobStation): Boolean {
        if (job.stage != OriginOddJobStage.HAUL_PUSH || destination.point.world?.uid != player.world.uid) return false
        cart.follow(player, job.id)
        val point = destination.point
        val yaw = Math.toRadians(point.yaw.toDouble())
        // Preserve the established forge delivery contract independently from cleaning's physical cart parking.
        val at = player.location
        val dx = at.x - point.x; val dz = at.z - point.z
        return abs(dx * cos(yaw) + dz * sin(yaw)) <= ODD_JOB_DESTINATION_HALF_WIDTH &&
            abs(-dx * sin(yaw) + dz * cos(yaw)) <= ODD_JOB_DESTINATION_HALF_LENGTH
    }
    fun station(playerId: UUID, job: OriginOddJobSession): OriginOddJobStation? =
        if (job.stage in setOf(OriginOddJobStage.HAUL_LOAD, OriginOddJobStage.HAUL_ATTACH)) cart.station(playerId, job.id) else null
    fun target(playerId: UUID, job: OriginOddJobSession): Location? = station(playerId, job)?.point
    fun clear(playerId: UUID, requestId: UUID) = cart.clear(playerId, requestId)
    override fun close() = cart.close()
}

/** Three separate baskets follow the real 22.5-degree bed, with no overlap or floating base. */
internal fun originForgeCartLoadOffset(index: Int): Vector3f {
    val z = -0.15 + index * 0.35
    // cart_2 bed top (model y=-4.5), GROUND context ×3. Two millimetres avoid coplanar bottom faces.
    return Vector3f(0f, (0.536585666 - z * kotlin.math.tan(Math.toRadians(22.5))).toFloat(), z.toFloat())
}
