package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Same verified GROUND dish and support correction as the canonical dining table. */
internal fun waiterCarryTransform(
    bodyYaw: Double,
    playerHeight: Double,
    sneaking: Boolean,
    scale: Float,
    surfaceLift: Float,
    handSide: Double,
    handForward: Double,
    handHeight: Double,
): Transformation {
    val yaw = Math.toRadians(bodyYaw)
    val supportHeight = handHeight - if (sneaking) 0.25 else 0.0
    return Transformation(
        Vector3f((cos(yaw) * handSide - sin(yaw) * handForward).toFloat(),
            (supportHeight - playerHeight + surfaceLift).toFloat(),
            (sin(yaw) * handSide + cos(yaw) * handForward).toFloat()),
        Quaternionf().rotateY(-yaw.toFloat()), Vector3f(scale), Quaternionf(),
    )
}

/** One non-pickupable packet prop per delivery; actual inventory is never changed. */
internal class OriginWaiterCarry(
    private val item: (String) -> ItemStack?,
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "dining-waiter") },
) : AutoCloseable {
    private data class Carried(val dishId: String, val display: PacketItemDisplay)
    private var owner: PaperPacketDisplays? = null
    private val carried = mutableMapOf<UUID, Carried>()

    fun pickup(player: Player, dishId: String): Boolean {
        if (player.uniqueId in carried) return false
        val stack = item(dishId) ?: return false
        val renderer = owner ?: createOwner().also { owner = it }
        val anchor = player.location.apply { yaw = 0f; pitch = 0f }
        val display = renderer.spawnItem(anchor, stack)
        try {
            display.isVisibleByDefault = true
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            display.billboard = Display.Billboard.FIXED
            display.viewRange = 0.5f
            display.displayWidth = 2f
            display.displayHeight = 2f
            display.shadowRadius = 0f
            display.teleportDuration = 0
            display.interpolationDuration = 0
            pose(display, player, dishId)
            display.attachTo(player)
            carried[player.uniqueId] = Carried(dishId, display)
            return true
        } catch (failure: Exception) {
            display.remove()
            throw failure
        }
    }

    fun tick() {
        carried.forEach { (playerId, item) ->
            Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { player ->
                item.display.interpolationDuration = 1
                pose(item.display, player, item.dishId)
            }
        }
    }

    private fun pose(display: PacketItemDisplay, player: Player, dishId: String) {
        val config = OriginDiningLayout.lifeConfig
        display.transformation = waiterCarryTransform(player.bodyYaw.toDouble(), player.height, player.isSneaking,
            OriginDiningLayout.displayScale(dishId), OriginDiningLayout.displayLift(dishId),
            config.handSide, config.handForward, config.handHeight)
    }

    fun remove(playerId: UUID) { carried.remove(playerId)?.display?.remove() }

    override fun close() {
        carried.values.forEach { it.display.remove() }
        carried.clear()
        owner?.close()
        owner = null
    }
}
