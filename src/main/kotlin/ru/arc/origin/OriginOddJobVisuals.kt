package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

internal enum class OriginJobProp {
    HAY, WATER_BUCKET,
    FORGE_BLANK, FORGE_HEATED, FORGE_SHAPED_1, FORGE_SHAPED_2, FORGE_HORSESHOE, FORGE_COOLED,
    AUCTION_VASE, AUCTION_BOOK, AUCTION_BLADE, AUCTION_PACKAGE,
}

/** Exact block geometry, in metres above the supporting plane; shared by carry and placement. */
internal fun originOddJobPropPieces(prop: OriginJobProp): List<OriginWorkshopWorkpiecePiece> {
    val iron = when (prop) {
        OriginJobProp.FORGE_BLANK, OriginJobProp.FORGE_COOLED -> Material.IRON_BLOCK
        OriginJobProp.FORGE_HEATED -> Material.ORANGE_CONCRETE
        else -> Material.ORANGE_TERRACOTTA
    }
    return when (prop) {
        OriginJobProp.HAY -> listOf(
            oddJobPiece(Material.HAY_BLOCK, 0.0, 0.18, 0.0, 0.46f, 0.36f, 0.34f),
        )
        OriginJobProp.WATER_BUCKET -> listOf(
            oddJobPiece(Material.IRON_BLOCK, 0.0, 0.025, 0.0, 0.34f, 0.05f, 0.30f),
            oddJobPiece(Material.IRON_BLOCK, -0.15, 0.17, 0.0, 0.04f, 0.24f, 0.30f),
            oddJobPiece(Material.IRON_BLOCK, 0.15, 0.17, 0.0, 0.04f, 0.24f, 0.30f),
            oddJobPiece(Material.IRON_BLOCK, 0.0, 0.17, -0.13, 0.26f, 0.24f, 0.04f),
            oddJobPiece(Material.IRON_BLOCK, 0.0, 0.17, 0.13, 0.26f, 0.24f, 0.04f),
            oddJobPiece(Material.BLUE_CONCRETE, 0.0, 0.245, 0.0, 0.26f, 0.02f, 0.22f),
            oddJobPiece(Material.GRAY_CONCRETE, -0.15, 0.36, 0.0, 0.035f, 0.14f, 0.035f),
            oddJobPiece(Material.GRAY_CONCRETE, 0.15, 0.36, 0.0, 0.035f, 0.14f, 0.035f),
            oddJobPiece(Material.GRAY_CONCRETE, 0.0, 0.4475, 0.0, 0.335f, 0.035f, 0.035f),
        )
        OriginJobProp.FORGE_BLANK, OriginJobProp.FORGE_HEATED -> listOf(
            oddJobPiece(iron, 0.0, 0.045, -0.22, 0.22f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.1925, 0.045, -0.22, 0.165f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.1925, 0.045, -0.22, 0.165f, 0.09f, 0.11f),
        )
        OriginJobProp.FORGE_SHAPED_1 -> listOf(
            oddJobPiece(iron, 0.0, 0.045, -0.22, 0.22f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.165, 0.045, -0.22, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.165, 0.045, -0.22, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.22, 0.045, -0.11, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.22, 0.045, -0.11, 0.11f, 0.09f, 0.11f),
        )
        OriginJobProp.FORGE_SHAPED_2 -> listOf(
            oddJobPiece(iron, 0.0, 0.045, -0.22, 0.22f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.165, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.165, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.22, 0.045, 0.03, 0.11f, 0.09f, 0.28f),
            oddJobPiece(iron, 0.22, 0.045, 0.03, 0.11f, 0.09f, 0.28f),
        )
        OriginJobProp.FORGE_HORSESHOE, OriginJobProp.FORGE_COOLED -> listOf(
            oddJobPiece(iron, 0.0, 0.045, -0.22, 0.22f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.165, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.165, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
            oddJobPiece(iron, -0.22, 0.045, 0.03, 0.11f, 0.09f, 0.28f),
            oddJobPiece(iron, 0.22, 0.045, 0.03, 0.11f, 0.09f, 0.28f),
            oddJobPiece(iron, -0.22, 0.045, 0.225, 0.085f, 0.09f, 0.11f),
            oddJobPiece(iron, 0.22, 0.045, 0.225, 0.085f, 0.09f, 0.11f),
        )
        OriginJobProp.AUCTION_VASE -> listOf(
            oddJobPiece(Material.CYAN_TERRACOTTA, 0.0, 0.025, 0.0, 0.24f, 0.05f, 0.24f),
            oddJobPiece(Material.CYAN_TERRACOTTA, 0.0, 0.15, 0.0, 0.34f, 0.20f, 0.30f),
            oddJobPiece(Material.CYAN_TERRACOTTA, 0.0, 0.29, 0.0, 0.26f, 0.08f, 0.24f),
            oddJobPiece(Material.LIGHT_BLUE_TERRACOTTA, -0.0725, 0.39, 0.0, 0.035f, 0.12f, 0.18f),
            oddJobPiece(Material.LIGHT_BLUE_TERRACOTTA, 0.0725, 0.39, 0.0, 0.035f, 0.12f, 0.18f),
            oddJobPiece(Material.LIGHT_BLUE_TERRACOTTA, 0.0, 0.39, -0.0725, 0.11f, 0.12f, 0.035f),
            oddJobPiece(Material.LIGHT_BLUE_TERRACOTTA, 0.0, 0.39, 0.0725, 0.11f, 0.12f, 0.035f),
        )
        OriginJobProp.AUCTION_BOOK -> listOf(
            oddJobPiece(Material.RED_TERRACOTTA, 0.0, 0.015, 0.0, 0.44f, 0.03f, 0.32f),
            oddJobPiece(Material.CALCITE, 0.025, 0.08, 0.0, 0.37f, 0.10f, 0.28f),
            oddJobPiece(Material.RED_TERRACOTTA, -0.195, 0.08, 0.0, 0.05f, 0.10f, 0.32f),
            oddJobPiece(Material.RED_TERRACOTTA, 0.0, 0.145, 0.0, 0.44f, 0.03f, 0.32f),
        )
        OriginJobProp.AUCTION_BLADE -> listOf(
            oddJobPiece(Material.DARK_OAK_PLANKS, -0.27, 0.04, 0.0, 0.18f, 0.08f, 0.09f),
            oddJobPiece(Material.GOLD_BLOCK, -0.15, 0.04, 0.0, 0.06f, 0.08f, 0.28f),
            oddJobPiece(Material.IRON_BLOCK, 0.08, 0.025, 0.0, 0.40f, 0.05f, 0.11f),
            oddJobPiece(Material.IRON_BLOCK, 0.315, 0.025, 0.0, 0.07f, 0.05f, 0.055f),
        )
        OriginJobProp.AUCTION_PACKAGE -> listOf(
            oddJobPiece(Material.BARREL, 0.0, 0.18, 0.0, 0.42f, 0.36f, 0.36f),
        )
    }
}

/** The same three dimensions used by the forge rules change the actual crown and branches. */
internal fun originOddJobForgePieces(work: OriginForgeWork, heated: Boolean): List<OriginWorkshopWorkpiecePiece> {
    val iron = if (heated) Material.ORANGE_TERRACOTTA else Material.IRON_BLOCK
    val crown = 0.22 + (work.shape[1] - 2) * 0.035
    val left = 0.28 + (work.shape[0] - 2) * 0.045
    val right = 0.28 + (work.shape[2] - 2) * 0.045
    val x = crown / 2 + 0.11
    return listOf(
        oddJobPiece(iron, 0.0, 0.045, -0.22, crown.toFloat(), 0.09f, 0.11f),
        oddJobPiece(iron, -crown / 2 - 0.055, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
        oddJobPiece(iron, crown / 2 + 0.055, 0.045, -0.165, 0.11f, 0.09f, 0.11f),
        oddJobPiece(iron, -x, 0.045, -0.11 + left / 2, 0.11f, 0.09f, left.toFloat()),
        oddJobPiece(iron, x, 0.045, -0.11 + right / 2, 0.11f, 0.09f, right.toFloat()),
        oddJobPiece(iron, -x, 0.045, left - 0.055, 0.085f, 0.09f, 0.11f),
        oddJobPiece(iron, x, 0.045, right - 0.055, 0.085f, 0.09f, 0.11f),
    )
}

internal fun originOddJobCleaningPieces(mask: Int): List<OriginWorkshopWorkpiecePiece> = (0..2).map { zone ->
    oddJobPiece(if (mask and (1 shl zone) == 0) Material.BROWN_CONCRETE else Material.LIGHT_BLUE_CONCRETE,
        (zone - 1) * 0.30, 0.012, 0.0, 0.23f, 0.024f, 0.30f)
}

private fun oddJobPiece(material: Material, x: Double, y: Double, z: Double, w: Float, h: Float, d: Float) =
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(x, y, z), OriginWorkshopGamePartSize(w, h, d), material)

/** Low, in front of the right hand; even the back edge clears the player's torso by 0.12 blocks. */
internal fun originOddJobCarryPose(
    bodyYaw: Double,
    sneaking: Boolean,
    geometry: List<OriginWorkshopWorkpiecePiece>,
): OriginWorkshopCarryPose {
    val yaw = Math.toRadians(bodyYaw)
    val minZ = geometry.minOf { it.center.z - it.size.z / 2.0 }
    val side = -0.28
    val forward = 0.42 - minZ
    return OriginWorkshopCarryPose(
        OriginWorkshopPoint(cos(yaw) * side - sin(yaw) * forward,
            if (sneaking) 0.69 else 0.94, sin(yaw) * side + cos(yaw) * forward),
        Quaternionf().rotationY(-yaw.toFloat()),
    )
}

/** Private, non-pickupable props. Domain progress, markers' interaction and rewards live in the game. */
internal class OriginOddJobVisuals(
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "odd-jobs") },
) : AutoCloseable {
    private class Owned(val requestId: UUID, val worldId: UUID) {
        val handles = mutableListOf<PacketDisplay>()
        var prop: OriginWorkshopProp? = null
        var kind: OriginJobProp? = null
        var geometry: List<OriginWorkshopWorkpiecePiece> = emptyList()
        var carried = false
        var target: OriginWorkshopProp? = null
        var label: PacketTextDisplay? = null
        var targetLocation: Location? = null
    }

    private var owner: PaperPacketDisplays? = null
    private val owned = mutableMapOf<UUID, Owned>()
    private var closed = false

    fun carry(player: Player, requestId: UUID, prop: OriginJobProp) = change(player, requestId) { state ->
        val rendered = workpiece(player, state, prop, player.location)
        rendered.carry(player, originOddJobCarryPose(player.bodyYaw.toDouble(), player.isSneaking, state.geometry))
        state.carried = true
    }

    /** [location] is the actual supporting plane, not the block centre or its configured anchor. */
    fun place(player: Player, requestId: UUID, location: Location, prop: OriginJobProp) = change(player, requestId) { state ->
        require(location.world?.uid == state.worldId) { "odd-job workpiece must stay in its player's world" }
        val rendered = workpiece(player, state, prop, location)
        rendered.place(location, Quaternionf().rotationY(-Math.toRadians(location.yaw.toDouble()).toFloat()))
        state.carried = false
    }

    fun forgeProfile(player: Player, requestId: UUID, location: Location, work: OriginForgeWork, heated: Boolean) =
        profile(player, requestId, location, originOddJobForgePieces(work, heated), heated)

    fun forgeCarry(player: Player, requestId: UUID, work: OriginForgeWork, heated: Boolean) =
        profile(player, requestId, player.location, originOddJobForgePieces(work, heated), heated, carried = true)

    fun cleaning(player: Player, requestId: UUID, location: Location, mask: Int) =
        profile(player, requestId, location, originOddJobCleaningPieces(mask), false)

    private fun profile(player: Player, requestId: UUID, location: Location, pieces: List<OriginWorkshopWorkpiecePiece>, heated: Boolean, carried: Boolean = false) = change(player, requestId) { state ->
        require(location.world?.uid == state.worldId)
        val prop = state.prop ?: OriginWorkshopProp(renderer(), location, pieces, privateViewer = player,
            onSpawn = { display -> state.handles += display; display.isVisibleByDefault = false }).also { state.prop = it }
        if (state.geometry != pieces) prop.update(pieces)
        state.geometry = pieces; state.kind = null; state.carried = carried
        if (carried) prop.carry(player, originOddJobCarryPose(player.bodyYaw.toDouble(), player.isSneaking, pieces))
        else prop.place(location, Quaternionf().rotationY(-Math.toRadians(location.yaw.toDouble()).toFloat()))
        prop.pieces.forEach { it.display?.brightness = if (heated) Display.Brightness(12, 8) else null }
    }

    /** A single personal next-action cue, separate from the game's forgiving interaction hit area. */
    fun target(player: Player, requestId: UUID, location: Location?, caption: String) {
        if (location == null) {
            owned[player.uniqueId]?.takeIf { it.requestId == requestId }?.let(::removeTarget)
            return
        }
        change(player, requestId) { state ->
            require(location.world?.uid == state.worldId) { "odd-job target must stay in its player's world" }
            if (state.targetLocation == location) {
                state.label?.text(Component.text(caption, TextColor.color(0xe8dfd2)))
                return@change
            }
            removeTarget(state)
            val renderer = renderer()
            val corners = listOf(-0.32, 0.32).flatMap { x -> listOf(-0.32, 0.32).map { z ->
                oddJobPiece(Material.YELLOW_CONCRETE, x, 0.009, z, 0.055f, 0.018f, 0.055f)
            } }
            state.target = OriginWorkshopProp(renderer, location, corners, privateViewer = player,
                onSpawn = { display -> state.handles += display; display.isVisibleByDefault = false }).also {
                it.place(location, Quaternionf().rotationY(-Math.toRadians(location.yaw.toDouble()).toFloat()))
                it.glow(Color.fromRGB(239, 195, 113))
            }
            state.label = renderer.spawnText(location.clone().add(0.0, 0.65, 0.0),
                Component.text(caption, TextColor.color(0xe8dfd2))).also { label ->
                state.handles += label
                label.isVisibleByDefault = false
                label.billboard = Display.Billboard.CENTER
                label.viewRange = 0.35f
                label.displayWidth = 2f
                label.displayHeight = 1f
                label.lineWidth = 180
                label.backgroundColor = Color.fromARGB(150, 18, 14, 10)
                label.isShadowed = true
                label.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0.35f), Quaternionf())
                label.showTo(player)
            }
            state.targetLocation = location.clone()
        }
    }

    fun clear(playerId: UUID, requestId: UUID) {
        val state = owned[playerId]?.takeIf { it.requestId == requestId } ?: return
        owned.remove(playerId)
        remove(state)
    }

    fun tick() {
        val iterator = owned.iterator()
        while (iterator.hasNext()) {
            val (playerId, state) = iterator.next()
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline || player.isDead || player.world.uid != state.worldId) {
                iterator.remove()
                remove(state)
            } else if (state.carried) {
                state.prop?.carry(player, originOddJobCarryPose(player.bodyYaw.toDouble(), player.isSneaking,
                    state.geometry))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        owned.values.forEach(::remove)
        owned.clear()
        owner?.close()
        owner = null
    }

    private fun renderer(): PaperPacketDisplays = owner ?: createOwner().also { owner = it }

    private inline fun change(player: Player, requestId: UUID, operation: (Owned) -> Unit) {
        check(!closed) { "odd-job visuals are closed" }
        val previous = owned[player.uniqueId]
        val state = if (previous?.requestId == requestId && previous.worldId == player.world.uid) previous else {
            previous?.let(::remove)
            Owned(requestId, player.world.uid).also { owned[player.uniqueId] = it }
        }
        try {
            operation(state)
            state.handles.removeAll { !it.isValid }
        } catch (failure: Exception) {
            owned.remove(player.uniqueId)
            remove(state)
            throw failure
        }
    }

    private fun workpiece(player: Player, state: Owned, kind: OriginJobProp, at: Location): OriginWorkshopProp {
        val geometry = originOddJobPropPieces(kind)
        val current = state.prop
        val result = current ?: OriginWorkshopProp(renderer(), at, geometry, privateViewer = player,
            onSpawn = { display -> state.handles += display; display.isVisibleByDefault = false }).also { state.prop = it }
        if (current != null && state.kind != kind) result.update(geometry)
        state.kind = kind
        state.geometry = geometry
        val brightness = when (kind) {
            OriginJobProp.FORGE_HEATED, OriginJobProp.FORGE_SHAPED_1,
            OriginJobProp.FORGE_SHAPED_2, OriginJobProp.FORGE_HORSESHOE -> Display.Brightness(12, 8)
            else -> null
        }
        result.pieces.forEach { it.display?.brightness = brightness }
        return result
    }

    private fun removeTarget(state: Owned) {
        state.target?.remove()
        state.target = null
        state.label?.remove()
        state.label = null
        state.targetLocation = null
        state.handles.removeAll { !it.isValid }
    }

    private fun remove(state: Owned) {
        state.prop?.remove()
        removeTarget(state)
        // Also catches handles allocated before a constructor/update failed partway through.
        state.handles.filter(PacketDisplay::isValid).forEach(PacketDisplay::remove)
        state.handles.clear()
    }
}
