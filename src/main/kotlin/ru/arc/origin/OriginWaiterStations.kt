package ru.arc.origin

import org.bukkit.Color
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

internal data class OriginWaiterStationsLayout(
    val prep: OriginDiningPoint, val sink: OriginDiningPoint, val rack: OriginDiningPoint,
    val standHeight: Double = 0.0,
    val cook: OriginDiningPoint? = null,
) {
    fun surface(point: OriginDiningPoint) = point.copy(y = point.y + standHeight)
}
internal data class OriginWaiterStationChoice(
    val id: String, val point: OriginDiningPoint, val material: Material, val next: Boolean,
    val dishId: String? = null, val emptyPlate: Boolean = false, val action: String? = id,
)
internal data class OriginWaiterStationHit(val requestId: UUID, val action: String, val stepIndex: Int)
/** Surface-local positions are shared by the renderer, hit test and source preview. */
internal fun waiterStationPoint(anchor: OriginDiningPoint, index: Int, count: Int): OriginDiningPoint {
    val side = (index - (count - 1) / 2.0) * 0.5
    val yaw = Math.toRadians(anchor.yaw.toDouble())
    return anchor.copy(x = anchor.x + cos(yaw) * side, y = anchor.y + 0.015, z = anchor.z + sin(yaw) * side)
}

internal fun waiterStationRayDistance(eyeX: Double, eyeY: Double, eyeZ: Double, dx: Double, dy: Double, dz: Double,
    point: OriginDiningPoint, range: Double): Double? {
    val yaw = Math.toRadians(point.yaw.toDouble())
    val x = eyeX - point.x
    val z = eyeZ - point.z
    val eye = Vector(x * cos(yaw) + z * sin(yaw), eyeY - point.y, -x * sin(yaw) + z * cos(yaw))
    val direction = Vector(dx * cos(yaw) + dz * sin(yaw), dy, -dx * sin(yaw) + dz * cos(yaw))
    // The prop's visible edge and the counter immediately below it accept the same click.
    return BoundingBox(-0.36, -0.03, -0.36, 0.36, 0.35, 0.36).rayTrace(eye, direction, range)
        ?.hitPosition?.distance(eye)
}

internal fun waiterStationChoices(config: OriginWaiterConfig, venue: OriginWaiterVenue, job: OriginWaiterJob): List<OriginWaiterStationChoice> {
    val layout = config.stations[venue.id] ?: return emptyList()
    return when (job.stage) {
        OriginWaiterStage.ASSEMBLE -> job.recipe.distinct().mapIndexedNotNull { index, ingredient ->
            if (ingredient !in job.recipe.drop(job.assembled)) null else OriginWaiterStationChoice("ingredient:$ingredient",
                waiterStationIngredientPoint(layout, index, job.recipe.distinct().size),
                config.ingredients[ingredient] ?: Material.BOWL, ingredient == job.recipe.getOrNull(job.assembled))
        }
        OriginWaiterStage.PICKUP -> listOf(OriginWaiterStationChoice("pickup",
            waiterStationPickupPoint(layout, job.family == OriginWaiterFamily.ASSEMBLED), Material.BOWL, true, job.dish.id))
        OriginWaiterStage.REWORK -> listOf(OriginWaiterStationChoice("remake", layout.surface(layout.prep), Material.BOWL, true))
        OriginWaiterStage.RETURN_DISHES, OriginWaiterStage.WASH -> buildList {
            val action = job.washing.getOrNull(job.washed) ?: return@buildList
            // The rack centre belongs to the clean stack; every tool has the same free sink-side dock.
            val point = waiterStationPoint(layout.surface(layout.sink), 2, 3)
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

/** Personal props on permanent furniture. One plate handle survives preparation, washing and stacking. */
internal class OriginWaiterStations(
    private val createOwner: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance, "dining-waiter-stations") },
) : AutoCloseable {
    private data class Prop(var choice: OriginWaiterStationChoice, val display: PacketItemDisplay)
    private data class Action(val job: OriginWaiterJob, val action: String, val layout: OriginWaiterStationsLayout,
        val startedAt: Long, val durationMillis: Long, val plateStart: OriginDiningPoint,
        val toolStart: OriginDiningPoint, val ingredientStart: OriginDiningPoint?, val stackHeight: Double,
        val label: String)
    private data class Cooking(val job: OriginWaiterJob, val layout: OriginWaiterStationsLayout,
        val preparationMillis: Long, val label: String, val readyLabel: String,
        val item: (OriginWaiterStationChoice) -> ItemStack?)
    private data class Scene(var requestId: UUID, var stepIndex: Int,
        val props: MutableMap<String, Prop> = linkedMapOf(), var action: Action? = null,
        var cooking: Cooking? = null, var label: PacketTextDisplay? = null,
        var labelText: String? = null, var pot: PacketBlockDisplay? = null,
        var lastEffectAt: Long = Long.MIN_VALUE)
    private var owner: PaperPacketDisplays? = null
    private val scenes = mutableMapOf<UUID, Scene>()
    private fun renderer() = owner ?: createOwner().also { owner = it }
    private fun key(choice: OriginWaiterStationChoice) = if (choice.id in setOf("pickup", "plate", "assembly-plate")) "dish" else choice.id

    fun show(player: Player, requestId: UUID, choices: List<OriginWaiterStationChoice>, layout: OriginWaiterStationsLayout?,
        stepIndex: Int, job: OriginWaiterJob? = null, now: Long = 0L, preparationMillis: Long = 6_000L,
        cookingLabel: String = "", readyLabel: String = "", item: (OriginWaiterStationChoice) -> ItemStack?) {
        val scene = scenes.getOrPut(player.uniqueId) { Scene(requestId, stepIndex) }
        try {
            val action = scene.action
            // Guide refreshes must not reset or replace an in-flight workpiece before its domain commit.
            if (action != null && action.job.id == requestId && job != null &&
                action.job.stage == job.stage && action.job.assembled == job.assembled && action.job.washed == job.washed) return
            if (action != null) finishAction(scene)
            if (scene.requestId != requestId) clearTransient(scene)
            scene.requestId = requestId
            scene.stepIndex = stepIndex
            val cooking = job != null && layout != null && job.stage == OriginWaiterStage.PICKUP &&
                job.family != OriginWaiterFamily.ASSEMBLED
            val desired = choices.toMutableList()
            if (job?.stage == OriginWaiterStage.ASSEMBLE && layout != null) desired += OriginWaiterStationChoice(
                "assembly-plate", waiterStationPlatePoint(layout), Material.BOWL, false, job.dish.id, true, null)
            if (cooking) {
                val state = Cooking(checkNotNull(job), checkNotNull(layout), preparationMillis.coerceAtLeast(1),
                    cookingLabel, readyLabel, item)
                scene.cooking = state
                desired.removeAll { it.id == "pickup" }
                desired += OriginWaiterStationChoice("pickup", waiterStationPickupPoint(layout, false), Material.BOWL,
                    now >= job.readyAt, job.dish.id, now < job.readyAt, if (now >= job.readyAt) "pickup" else null)
            } else {
                scene.cooking = null
                scene.pot?.remove(); scene.pot = null
                clearLabel(scene)
            }
            val desiredKeys = desired.map(::key).toSet()
            scene.props.keys.filter { !it.startsWith("clean-plate") && it !in desiredKeys &&
                !(job?.stage == OriginWaiterStage.ASSEMBLE && it.startsWith("assembled-ingredient-")) &&
                !(cooking && it.startsWith("cooking-")) }.toList().forEach { scene.props.remove(it)?.display?.remove() }
            for (choice in desired) {
                if (choice.id == "clean-plate") {
                    if (scene.props["dish"] != null) settlePlate(scene, choice.point)
                    else if (scene.props.keys.none { it.startsWith("clean-plate") }) put(player, scene, key(choice), choice, item)
                } else put(player, scene, key(choice), choice, item)
            }
            scene.cooking?.let { animateCooking(player, scene, it, now) }
        } catch (failure: Exception) {
            remove(player.uniqueId)
            throw failure
        }
    }

    /** Called only after the domain owner accepts a pending action, before it removes the carry prop. */
    fun begin(player: Player, jobBefore: OriginWaiterJob, action: String, layout: OriginWaiterStationsLayout,
        durationMillis: Long, now: Long, label: String = "", item: (OriginWaiterStationChoice) -> ItemStack?): Boolean {
        val scene = scenes[player.uniqueId] ?: return false
        if (scene.requestId != jobBefore.id || scene.action != null || durationMillis <= 0 ||
            !(action.startsWith("ingredient:") || action.startsWith("wash:"))) return false
        val tool = scene.props[action] ?: return false
        val returning = jobBefore.stage == OriginWaiterStage.RETURN_DISHES
        val plateStart = if (returning) {
            val config = OriginDiningLayout.lifeConfig
            waiterStationLocal(OriginDiningPoint(player.x, player.y, player.z, player.bodyYaw),
                config.handSide, config.handHeight - if (player.isSneaking) 0.25 else 0.0, config.handForward)
        } else scene.props["dish"]?.choice?.point ?: waiterStationSinkPoint(layout)
        val platePoint = if (action.startsWith("ingredient:")) waiterStationPlatePoint(layout) else plateStart
        val plate = OriginWaiterStationChoice("plate", platePoint, Material.BOWL, false, jobBefore.dish.id, true, null)
        if (put(player, scene, "dish", plate, item) == null) return false
        if (action == "wash:DRY" && (scene.props.keys.count { it.startsWith("clean-plate") } >= 3 ||
                waiterStationPlateHeight(jobBefore.dish.id) == 0.0)) clearStack(scene)
        val stackHeight = scene.props.filterKeys { it.startsWith("clean-plate") }.values
            .sumOf { waiterStationPlateHeight(it.choice.dishId.orEmpty()) }
        scene.cooking = null
        scene.action = Action(jobBefore, action, layout, now, durationMillis, platePoint, tool.choice.point,
            if (action.startsWith("ingredient:")) tool.choice.point else null, stackHeight, label)
        animateAction(player, scene, checkNotNull(scene.action), now)
        return true
    }

    fun tick(now: Long) {
        for ((playerId, scene) in scenes.toMap()) {
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: continue
            try {
                scene.action?.let { animateAction(player, scene, it, now) }
                    ?: scene.cooking?.let { animateCooking(player, scene, it, now) }
            } catch (failure: Exception) {
                remove(playerId)
                throw failure
            }
        }
    }

    fun cancelAction(playerId: UUID) {
        scenes[playerId]?.let { scene -> scene.action = null; clearTransient(scene) }
    }

    private fun animateAction(player: Player, scene: Scene, action: Action, now: Long) {
        val progress = ((now - action.startedAt).toDouble() / action.durationMillis).coerceIn(0.0, 1.0)
        val frame = waiterStationActionFrame(action.action, action.layout, progress, action.plateStart,
            action.toolStart, action.ingredientStart, action.job.stage == OriginWaiterStage.RETURN_DISHES,
            action.stackHeight, action.job.assembled, waiterStationPlateHeight(action.job.dish.id))
        scene.props["dish"]?.let { pose(player, it, frame.plate, moving = true) }
        val moving = if (action.action.startsWith("ingredient:")) frame.ingredient else frame.tool
        moving?.let { point -> scene.props[action.action]?.let { pose(player, it, point, moving = true,
            itemScale = if (action.action.startsWith("ingredient:")) (0.48 - 0.22 * progress).toFloat() else 0.48f) } }
        label(player, scene, frame.plate, action.job.dish.name, action.label, progress)
        if (progress > 0.15 && progress < 0.9 && effectDue(scene, now)) {
            val point = frame.contact.inWorld(player.world)
            when (action.action) {
                "wash:SOAP" -> player.spawnParticle(Particle.CLOUD, point, 1, 0.06, 0.01, 0.06, 0.002)
                "wash:RINSE", "wash:SOAK" -> player.spawnParticle(Particle.SPLASH, point, 2, 0.09, 0.03, 0.09, 0.03)
                "wash:SCRAPE" -> player.spawnParticle(Particle.ITEM, point, 1, 0.04, 0.01, 0.04, 0.025, ItemStack(Material.BREAD))
                "wash:DRY" -> Unit
                else -> player.spawnParticle(Particle.COMPOSTER, point, 1, 0.025, 0.02, 0.025, 0.0)
            }
        }
    }

    private fun animateCooking(player: Player, scene: Scene, state: Cooking, now: Long) {
        val progress = (1.0 - (state.job.readyAt - now).toDouble() / state.preparationMillis).coerceIn(0.0, 1.0)
        val frame = waiterStationCookingFrame(state.layout, progress, now - state.job.readyAt + state.preparationMillis)
        val ready = now >= state.job.readyAt
        val choice = OriginWaiterStationChoice("pickup", frame.plate, Material.BOWL, ready, state.job.dish.id,
            !frame.filled, if (ready) "pickup" else null)
        put(player, scene, "dish", choice, state.item)
        if (!ready) {
            val cook = state.layout.surface(state.layout.cook ?: state.layout.prep)
            if (scene.pot == null) {
                scene.pot = renderer().spawnBlock(waiterStationLocal(cook, right = -0.22).inWorld(player.world),
                    Material.CAULDRON.createBlockData()).also { pot ->
                    configure(pot, player)
                    pot.transformation = Transformation(Vector3f(-0.21f, 0f, -0.21f), Quaternionf(), Vector3f(0.42f, 0.32f, 0.42f), Quaternionf())
                }
            }
            val tool = OriginWaiterStationChoice("cooking-tool", checkNotNull(frame.tool), Material.WOODEN_SHOVEL, false, action = null)
            put(player, scene, tool.id, tool, state.item, moving = true)
            if (!frame.filled) {
                val material = when (state.job.dish.id) { "fish" -> Material.COOKED_COD; "steak" -> Material.COOKED_BEEF; else -> Material.EGG }
                val ingredient = OriginWaiterStationChoice("cooking-ingredient", checkNotNull(frame.ingredient), material, false, action = null)
                put(player, scene, ingredient.id, ingredient, state.item, moving = true)
            } else scene.props.remove("cooking-ingredient")?.display?.remove()
            if (effectDue(scene, now)) player.spawnParticle(Particle.CLOUD,
                frame.contact.inWorld(player.world), 1, 0.035, 0.025, 0.035, 0.012)
        } else {
            scene.props.keys.filter { it.startsWith("cooking-") }.toList().forEach { scene.props.remove(it)?.display?.remove() }
            scene.pot?.remove(); scene.pot = null
        }
        label(player, scene, frame.plate, state.job.dish.name, if (ready) state.readyLabel else state.label,
            if (ready) null else progress)
    }

    private fun finishAction(scene: Scene) {
        val action = scene.action ?: return
        if (action.action == "wash:DRY") settlePlate(scene,
            action.layout.surface(action.layout.rack).let { it.copy(y = it.y + action.stackHeight) })
        if (action.action.startsWith("ingredient:")) scene.props.remove(action.action)?.let { ingredient ->
            val key = "assembled-ingredient-${action.job.assembled}"
            ingredient.choice = ingredient.choice.copy(id = key, next = false, action = null)
            ingredient.display.isGlowing = false
            scene.props[key] = ingredient
        }
        scene.action = null
        clearLabel(scene)
    }

    private fun settlePlate(scene: Scene, point: OriginDiningPoint) {
        val plate = scene.props.remove("dish") ?: return
        val key = "clean-plate-${scene.props.keys.count { it.startsWith("clean-plate") }}"
        plate.choice = plate.choice.copy(id = key, point = point, next = false, action = null)
        plate.display.isGlowing = false
        scene.props[key] = plate
    }

    private fun put(player: Player, scene: Scene, key: String, choice: OriginWaiterStationChoice,
        item: (OriginWaiterStationChoice) -> ItemStack?, moving: Boolean = false): Prop? {
        var prop = scene.props[key]
        val changedModel = prop == null || prop.choice.dishId != choice.dishId || prop.choice.emptyPlate != choice.emptyPlate ||
            prop.choice.material != choice.material
        if (changedModel) {
            val stack = if (choice.dishId == null) ItemStack(choice.material) else item(choice) ?: return null
            if (prop == null) {
                val display = renderer().spawnItem(choice.point.inWorld(player.world).apply { yaw = 0f }, stack)
                configure(display, player)
                prop = Prop(choice, display).also { scene.props[key] = it }
            } else prop.display.itemStack = stack
        }
        val current = checkNotNull(prop)
        if (changedModel || current.choice.point != choice.point || moving) pose(player, current, choice.point, choice, moving)
        if (changedModel || current.choice.next != choice.next) {
            current.display.isGlowing = choice.next
            current.display.glowColorOverride = Color.fromRGB(0x9BD48D)
        }
        current.choice = choice
        return current
    }

    private fun pose(player: Player, prop: Prop, point: OriginDiningPoint,
        choice: OriginWaiterStationChoice = prop.choice, moving: Boolean = false, itemScale: Float = 0.48f) {
        val display = prop.display
        display.teleportDuration = if (moving) 1 else 0
        display.interpolationDuration = if (moving) 1 else 0
        display.teleport(point.inWorld(player.world).apply { yaw = 0f })
        val rotation = Quaternionf().rotateY(-Math.toRadians(point.yaw.toDouble()).toFloat())
        if (choice.dishId != null) {
            val empty = OriginDiningLayout.lifeConfig.let { it.emptyPlates[choice.dishId] ?: it.emptyPlate }
            val scale = if (choice.emptyPlate) empty.scale else OriginDiningLayout.displayScale(choice.dishId)
            val lift = if (choice.emptyPlate) empty.lift else OriginDiningLayout.displayLift(choice.dishId)
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            display.transformation = Transformation(Vector3f(0f, lift, 0f), rotation, Vector3f(scale), Quaternionf())
        } else {
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            display.transformation = Transformation(Vector3f(), rotation.rotateX((-Math.PI / 2).toFloat()), Vector3f(itemScale), Quaternionf())
        }
    }

    private fun label(player: Player, scene: Scene, point: OriginDiningPoint, dish: String, caption: String, progress: Double?) {
        if (caption.isBlank()) { clearLabel(scene); return }
        val text = "$dish\n$caption" + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
        val display = scene.label ?: renderer().spawnText(point.inWorld(player.world), waiterText(text)).also {
            configure(it, player)
            it.billboard = Display.Billboard.CENTER
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(170, 24, 19, 14)
            it.lineWidth = 240
            it.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0.32f), Quaternionf())
            scene.label = it
        }
        display.teleport(waiterStationLocal(point, up = 0.72).inWorld(player.world))
        if (scene.labelText != text) { display.text(waiterText(text)); scene.labelText = text }
    }

    private fun effectDue(scene: Scene, now: Long): Boolean {
        if (scene.lastEffectAt != Long.MIN_VALUE && now - scene.lastEffectAt < 150) return false
        scene.lastEffectAt = now
        return true
    }

    private fun configure(display: PacketDisplay, player: Player) {
        display.isVisibleByDefault = false
        display.showTo(player)
        display.viewRange = 0.4f
        display.displayWidth = 3f
        display.displayHeight = 2f
        display.shadowRadius = 0f
        display.interpolationDuration = 0
        display.teleportDuration = 0
    }

    fun aimed(player: Player, range: Double): OriginWaiterStationHit? {
        val scene = scenes[player.uniqueId] ?: return null
        if (scene.action != null) return null
        val eye = player.eyeLocation
        val direction = eye.direction
        val selected = scene.props.values.filter { it.choice.action != null }.mapNotNull { prop ->
            waiterStationRayDistance(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, prop.choice.point, range)?.let { prop to it }
        }.minByOrNull { it.second } ?: return null
        val block = player.world.rayTraceBlocks(eye, direction, selected.second, FluidCollisionMode.NEVER, true)
        if (block != null && block.hitPosition.distance(eye.toVector()) + 0.03 < selected.second) return null
        return OriginWaiterStationHit(scene.requestId, checkNotNull(selected.first.choice.action), scene.stepIndex)
    }

    private fun clearLabel(scene: Scene) { scene.label?.remove(); scene.label = null; scene.labelText = null }
    private fun clearStack(scene: Scene) {
        scene.props.keys.filter { it.startsWith("clean-plate") }.toList().forEach { scene.props.remove(it)?.display?.remove() }
    }
    private fun clearTransient(scene: Scene) {
        scene.props.keys.filter { !it.startsWith("clean-plate") }.toList().forEach { scene.props.remove(it)?.display?.remove() }
        scene.cooking = null
        scene.pot?.remove(); scene.pot = null
        clearLabel(scene)
    }
    fun remove(playerId: UUID) {
        scenes.remove(playerId)?.let { scene -> clearTransient(scene); clearStack(scene) }
    }
    override fun close() {
        scenes.keys.toList().forEach(::remove)
        owner?.close()
        owner = null
    }
}
