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
    private val emptyPlate: (String) -> ItemStack? = { null },
    private val water: () -> ItemStack? = { null },
) : AutoCloseable {
    private data class Carried(val display: PacketItemDisplay, val scale: Float, val lift: Float)
    private var owner: PaperPacketDisplays? = null
    private val carried = mutableMapOf<UUID, Carried>()

    fun pickup(player: Player, dishId: String): Boolean = pickup(player, item(dishId),
        OriginDiningLayout.displayScale(dishId), OriginDiningLayout.displayLift(dishId))

    fun pickupPlate(player: Player, dishId: String): Boolean {
        val config = OriginDiningLayout.lifeConfig
        val prop = config.emptyPlates[dishId] ?: config.emptyPlate
        return pickup(player, emptyPlate(dishId), prop.scale, prop.lift)
    }

    fun pickupWater(player: Player): Boolean {
        val prop = OriginDiningLayout.lifeConfig.mug
        return pickup(player, water(), prop.scale, prop.lift)
    }

    fun plateItem(dishId: String): ItemStack? = emptyPlate(dishId)?.clone()
    fun dishItem(dishId: String): ItemStack? = item(dishId)?.clone()

    private fun pickup(player: Player, stack: ItemStack?, scale: Float, lift: Float): Boolean {
        if (player.uniqueId in carried) return false
        if (stack == null) return false
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
            pose(display, player, scale, lift)
            display.attachTo(player)
            carried[player.uniqueId] = Carried(display, scale, lift)
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
                pose(item.display, player, item.scale, item.lift)
            }
        }
    }

    private fun pose(display: PacketItemDisplay, player: Player, scale: Float, lift: Float) {
        val config = OriginDiningLayout.lifeConfig
        display.transformation = waiterCarryTransform(player.bodyYaw.toDouble(), player.height, player.isSneaking,
            scale, lift,
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
