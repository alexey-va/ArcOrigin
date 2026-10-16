package ru.arc.origin

import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class OriginWaiterStationsLayout(
    val prep: OriginDiningPoint, val sink: OriginDiningPoint, val rack: OriginDiningPoint,
    val standHeight: Double = 0.0,
) {
    fun surface(point: OriginDiningPoint) = point.copy(y = point.y + standHeight)
}
internal data class OriginWaiterStationChoice(
    val id: String, val point: OriginDiningPoint, val material: Material, val next: Boolean,
    val dishId: String? = null, val emptyPlate: Boolean = false, val action: String? = id,
)
internal data class OriginWaiterStationHit(val requestId: UUID, val action: String, val stepIndex: Int)
internal data class OriginWaiterStationBlock(
    val id: String, val point: OriginDiningPoint, val material: Material,
    val x: Float, val y: Float, val z: Float, val width: Float, val height: Float, val depth: Float,
)

/** Canonical cuboids are also exported by the geometry check; all y coordinates start at the verified floor. */
internal fun waiterStationBlocks(layout: OriginWaiterStationsLayout): List<OriginWaiterStationBlock> = buildList {
    if (layout.standHeight <= 0.0) return@buildList
    for ((name, anchor, width) in listOf(Triple("prep", layout.prep, 1.6f), Triple("sink", layout.sink, 1.5f), Triple("rack", layout.rack, 0.7f))) {
        val height = layout.standHeight.toFloat()
        add(OriginWaiterStationBlock("$name:top", anchor, Material.SPRUCE_PLANKS,
            -width / 2, height - 0.10f, -0.4f, width, 0.10f, 0.8f))
        for (side in listOf(-1, 1)) for (front in listOf(-1, 1)) {
            add(OriginWaiterStationBlock("$name:leg:$side:$front", anchor, Material.DARK_OAK_PLANKS,
                side * (width / 2 - 0.12f) - 0.06f, 0f, front * 0.28f - 0.06f,
                0.12f, height - 0.10f, 0.12f))
        }
        if (name == "sink") {
            // A low wooden tub leaves both the plate and current tool visible from standing height.
            add(OriginWaiterStationBlock("sink:back", anchor, Material.DARK_OAK_PLANKS,
                -width / 2, height, 0.34f, width, 0.12f, 0.06f))
            for (side in listOf(-1, 1)) add(OriginWaiterStationBlock("sink:rim:$side", anchor, Material.DARK_OAK_PLANKS,
                side * (width / 2 - 0.03f) - 0.03f, height, -0.4f, 0.06f, 0.12f, 0.74f))
        }
    }
}

/** Surface-local positions are shared by the renderer, hit test and source preview. */
internal fun waiterStationPoint(anchor: OriginDiningPoint, index: Int, count: Int): OriginDiningPoint {
    val side = (index - (count - 1) / 2.0) * 0.5
    val yaw = Math.toRadians(anchor.yaw.toDouble())
    return anchor.copy(x = anchor.x + cos(yaw) * side, y = anchor.y + 0.015, z = anchor.z + sin(yaw) * side)
}

internal fun waiterStationRayDistance(eyeX: Double, eyeY: Double, eyeZ: Double, dx: Double, dy: Double, dz: Double,
    point: OriginDiningPoint, range: Double): Double? {
    if (abs(dy) < 0.0001) return null
    val t = (point.y - eyeY) / dy
    if (t < 0 || t > range) return null
    val yaw = Math.toRadians(point.yaw.toDouble())
    val x = eyeX + dx * t - point.x
    val z = eyeZ + dz * t - point.z
    return t.takeIf { abs(x * cos(yaw) + z * sin(yaw)) <= 0.27 && abs(-x * sin(yaw) + z * cos(yaw)) <= 0.27 }
}

internal fun waiterStationChoices(config: OriginWaiterConfig, venue: OriginWaiterVenue, job: OriginWaiterJob): List<OriginWaiterStationChoice> {
    val layout = config.stations[venue.id] ?: return emptyList()
    return when (job.stage) {
        OriginWaiterStage.ASSEMBLE -> job.recipe.distinct().mapIndexedNotNull { index, ingredient ->
            if (ingredient !in job.recipe.drop(job.assembled)) null else OriginWaiterStationChoice("ingredient:$ingredient",
                waiterStationPoint(layout.surface(layout.prep), index, job.recipe.distinct().size),
                config.ingredients[ingredient] ?: Material.BOWL, ingredient == job.recipe.getOrNull(job.assembled))
        }
        OriginWaiterStage.PICKUP -> if (job.family == OriginWaiterFamily.ASSEMBLED && job.assembled == job.recipe.size)
            listOf(OriginWaiterStationChoice("pickup", layout.surface(layout.prep), Material.BOWL, true, job.dish.id)) else emptyList()
        OriginWaiterStage.RETURN_DISHES, OriginWaiterStage.WASH -> buildList {
            val action = job.washing.getOrNull(job.washed) ?: return@buildList
            val point = if (action == OriginWaiterWash.DRY) waiterStationPoint(layout.surface(layout.rack), 0, 1)
                else waiterStationPoint(layout.surface(layout.sink), 2, 3)
            add(OriginWaiterStationChoice("wash:${action.name}", point, when (action) {
                OriginWaiterWash.SCRAPE -> Material.WOODEN_SHOVEL
                OriginWaiterWash.SOAK -> Material.LIGHT_BLUE_DYE
                OriginWaiterWash.SOAP -> Material.SLIME_BALL
                OriginWaiterWash.RINSE -> Material.WATER_BUCKET
                OriginWaiterWash.DRY -> Material.PAPER
            }, true))
            if (job.stage == OriginWaiterStage.WASH) add(OriginWaiterStationChoice("plate",
                waiterStationPoint(layout.surface(layout.sink), 0, 2).let { it.copy(y = it.y - 0.015) },
                Material.BOWL, false, job.dish.id, true, action = "wash:${action.name}"))
        }
        OriginWaiterStage.PAYOUT -> listOf(OriginWaiterStationChoice("clean-plate", layout.surface(layout.rack),
            Material.BOWL, false, job.dish.id, true, action = null))
        else -> emptyList()
    }
}

/** Personal work surfaces and cues; packet handles persist across recipe/wash steps and requests. */
internal class OriginWaiterStations(
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "dining-waiter-stations") },
) : AutoCloseable {
    private data class Prop(var choice: OriginWaiterStationChoice, val display: PacketItemDisplay)
    private data class Scene(var requestId: UUID, var stepIndex: Int,
        val props: MutableMap<String, Prop> = linkedMapOf(),
        val blocks: MutableMap<String, Pair<OriginWaiterStationBlock, PacketBlockDisplay>> = linkedMapOf())
    private var owner: PaperPacketDisplays? = null
    private val scenes = mutableMapOf<UUID, Scene>()

    fun show(player: Player, requestId: UUID, choices: List<OriginWaiterStationChoice>, layout: OriginWaiterStationsLayout?,
        stepIndex: Int, item: (OriginWaiterStationChoice) -> ItemStack?) {
        val scene = scenes.getOrPut(player.uniqueId) { Scene(requestId, stepIndex) }
        scene.requestId = requestId
        scene.stepIndex = stepIndex
        val renderer = owner ?: createOwner().also { owner = it }
        try {
            val blocks = layout?.let(::waiterStationBlocks).orEmpty()
            scene.blocks.keys.filter { key -> blocks.none { it.id == key } }.forEach { scene.blocks.remove(it)?.second?.remove() }
            for (block in blocks) {
                if (scene.blocks[block.id]?.first == block) continue
                scene.blocks.remove(block.id)?.second?.remove()
                val display = renderer.spawnBlock(block.point.inWorld(player.world).apply { yaw = 0f }, block.material.createBlockData())
                scene.blocks[block.id] = block to display
                configure(display, player)
                val rotation = Quaternionf().rotateY(-Math.toRadians(block.point.yaw.toDouble()).toFloat())
                display.transformation = Transformation(rotation.transform(Vector3f(block.x, block.y, block.z)), rotation,
                    Vector3f(block.width, block.height, block.depth), Quaternionf())
            }
            // One finished plate remains on the rack until the next handoff or shift cleanup.
            scene.props.keys.filter { key -> key != "clean-plate" && choices.none { it.id == key } }
                .forEach { scene.props.remove(it)?.display?.remove() }
            for (choice in choices) {
                val existing = scene.props[choice.id]
                if (existing != null && existing.choice.copy(next = false) == choice.copy(next = false)) {
                    if (existing.choice.next != choice.next) existing.display.isGlowing = choice.next
                    existing.choice = choice
                    continue
                }
                existing?.display?.remove()
                val stack = if (choice.dishId == null) ItemStack(choice.material) else item(choice) ?: continue
                val display = renderer.spawnItem(choice.point.inWorld(player.world).apply { yaw = 0f }, stack)
                scene.props[choice.id] = Prop(choice, display)
                configure(display, player)
                if (choice.dishId != null) {
                    val prop = OriginDiningLayout.lifeConfig.let { it.emptyPlates[choice.dishId] ?: it.emptyPlate }
                    val scale = if (choice.emptyPlate) prop.scale else OriginDiningLayout.displayScale(choice.dishId)
                    val lift = if (choice.emptyPlate) prop.lift else OriginDiningLayout.displayLift(choice.dishId)
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                    display.transformation = Transformation(Vector3f(0f, lift, 0f),
                        Quaternionf().rotateY(-Math.toRadians(choice.point.yaw.toDouble()).toFloat()), Vector3f(scale), Quaternionf())
                } else {
                    // Native generated-item thickness is 1/16; .48 scale places its lower face .015 below the centre.
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    display.transformation = Transformation(Vector3f(), Quaternionf().rotateY(-Math.toRadians(choice.point.yaw.toDouble()).toFloat())
                        .rotateX((-Math.PI / 2).toFloat()), Vector3f(0.48f), Quaternionf())
                }
                display.isGlowing = choice.next
                display.glowColorOverride = Color.fromRGB(0x9BD48D)
            }
        } catch (failure: Exception) {
            remove(player.uniqueId)
            throw failure
        }
    }

    private fun configure(display: PacketDisplay, player: Player) {
        display.isVisibleByDefault = false
        display.showTo(player)
        display.viewRange = 0.4f
        display.displayWidth = 2f
        display.displayHeight = 1.2f
        display.shadowRadius = 0f
        display.interpolationDuration = 0
        display.teleportDuration = 0
    }

    fun aimed(player: Player, range: Double): OriginWaiterStationHit? {
        val scene = scenes[player.uniqueId] ?: return null
        val eye = player.eyeLocation
        val direction = eye.direction
        val selected = scene.props.values.filter { it.choice.action != null }.mapNotNull { prop ->
            waiterStationRayDistance(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, prop.choice.point, range)?.let { prop to it }
        }.minByOrNull { it.second } ?: return null
        val block = player.world.rayTraceBlocks(eye, direction, selected.second, FluidCollisionMode.NEVER, true)
        if (block != null && block.hitPosition.distance(eye.toVector()) + 0.03 < selected.second) return null
        return OriginWaiterStationHit(scene.requestId, checkNotNull(selected.first.choice.action), scene.stepIndex)
    }

    fun remove(playerId: UUID) {
        scenes.remove(playerId)?.let { scene ->
            scene.props.values.forEach { it.display.remove() }
            scene.blocks.values.forEach { it.second.remove() }
        }
    }
    override fun close() {
        scenes.keys.toList().forEach(::remove)
        owner?.close()
        owner = null
    }
}
