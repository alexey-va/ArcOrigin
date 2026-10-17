package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.particle.PaperViewerParticles
import java.util.UUID
import java.util.logging.Level
import kotlin.math.floor
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun originWorkshopTransition(
    progress: OriginWorkshopGameProgress,
    action: OriginWorkshopGameAction,
    now: Long,
): OriginWorkshopGameProgress? {
    return originWorkshopTransition(progress, action, now, defaultCarpenterRecipe())
}

internal fun originWorkshopAdvance(
    progress: OriginWorkshopGameProgress,
    now: Long,
    rules: OriginWorkshopGameRules,
): OriginWorkshopGameProgress = originWorkshopAdvance(progress, now, defaultCarpenterRecipe(rules))

private fun defaultCarpenterRecipe(rules: OriginWorkshopGameRules = OriginWorkshopGameRules()) =
    originWorkshopGameRecipe(OriginWorkshopTableRole.CARPENTER, "furnituresplus:white_wooden_chair",
        OriginWorkshopTableDimensions.DEFAULT, OriginWorkshopMachineTuning(), OriginWorkshopPoint(-7.5, 0.44, 0.30), rules)

private const val WORKSHOP_RETURN_GRACE_TICKS = 400L

internal enum class OriginWorkshopCancelReason { OFFLINE, WORLD_CHANGED, TOO_FAR, TIMEOUT }

internal fun originWorkshopInSessionRange(dx: Double, dy: Double, dz: Double): Boolean =
    dx.isFinite() && dy.isFinite() && dz.isFinite() && dx * dx + dz * dz <= 144.0 && abs(dy) <= 8.0

internal class OriginWorkshopSessionActivity(startedAt: Long) {
    var lastActivityAt = startedAt
        private set
    var outsideSince: Long? = null
        private set

    fun recordActivity(now: Long) { lastActivityAt = now }

    fun cancelReason(online: Boolean, sameWorld: Boolean, inRange: Boolean, now: Long,
                     timeoutTicks: Long): OriginWorkshopCancelReason? {
        if (!online) return OriginWorkshopCancelReason.OFFLINE
        if (!sameWorld) return OriginWorkshopCancelReason.WORLD_CHANGED
        outsideSince = if (inRange) null else outsideSince ?: now
        if (outsideSince?.let { now - it >= WORKSHOP_RETURN_GRACE_TICKS } == true) return OriginWorkshopCancelReason.TOO_FAR
        if (now - lastActivityAt >= timeoutTicks) return OriginWorkshopCancelReason.TIMEOUT
        return null
    }
}

internal fun originWorkshopIsDuplicateClick(previousNanos: Long?, nowNanos: Long, windowNanos: Long): Boolean =
    previousNanos != null && nowNanos - previousNanos in 0L..windowNanos

internal data class OriginWorkshopVec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(v: OriginWorkshopVec3) = OriginWorkshopVec3(x - v.x, y - v.y, z - v.z)
    operator fun times(k: Double) = OriginWorkshopVec3(x * k, y * k, z * k)
    fun dot(v: OriginWorkshopVec3) = x * v.x + y * v.y + z * v.z
}

internal data class OriginWorkshopAabb(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite))
        require(minX <= maxX && minY <= maxY && minZ <= maxZ)
    }
}

/** Local-space click volume over the actual tabletop and its front edge. */
internal fun originWorkshopStartInteractionAabb(dimensions: OriginWorkshopTableDimensions): OriginWorkshopAabb =
    OriginWorkshopAabb(
        minX = -dimensions.width / 2.0,
        minY = dimensions.height - 0.15,
        minZ = -dimensions.depth / 2.0,
        maxX = dimensions.width / 2.0,
        maxY = dimensions.height + 0.55,
        maxZ = dimensions.depth / 2.0,
    )

/** Ray/AABB distance in blocks, normalized to the ray direction for focused interaction tests. */
internal fun originWorkshopRayAabbHit(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    bounds: OriginWorkshopAabb,
    reach: Double,
): Double? {
    if (!listOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z, reach).all(Double::isFinite)) return null
    if (reach !in 0.0..4.5) return null
    if (origin.x in bounds.minX..bounds.maxX && origin.y in bounds.minY..bounds.maxY &&
        origin.z in bounds.minZ..bounds.maxZ) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-6) return null
    val ray = direction * (1.0 / length)
    var near = 0.0
    var far = reach

    fun clip(originAxis: Double, directionAxis: Double, minimum: Double, maximum: Double): Boolean {
        if (kotlin.math.abs(directionAxis) < 1e-9) return originAxis in minimum..maximum
        val first = (minimum - originAxis) / directionAxis
        val second = (maximum - originAxis) / directionAxis
        near = maxOf(near, minOf(first, second))
        far = minOf(far, maxOf(first, second))
        return near <= far
    }

    if (!clip(origin.x, ray.x, bounds.minX, bounds.maxX) ||
        !clip(origin.y, ray.y, bounds.minY, bounds.maxY) ||
        !clip(origin.z, ray.z, bounds.minZ, bounds.maxZ)
    ) return null
    return near.takeIf { it in 0.0..reach }
}

internal fun originWorkshopRayHit(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    center: OriginWorkshopVec3,
    radius: Double,
    reach: Double,
): Double? {
    if (!listOf(
            origin.x, origin.y, origin.z,
            direction.x, direction.y, direction.z,
            center.x, center.y, center.z, radius, reach,
        ).all(Double::isFinite)
    ) return null
    if (radius <= 0 || reach !in 0.0..4.5) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-6) return null
    val ray = direction * (1.0 / length)
    val offset = center - origin
    val along = offset.dot(ray)
    if (along < 0) return null
    val perpendicular2 = offset.dot(offset) - along * along
    if (perpendicular2 > radius * radius) return null
    return (along - sqrt(radius * radius - perpendicular2)).takeIf { it in 0.0..reach }
}

/** Ray against the rendered block cube. Inverse scaling preserves world-distance ray parameters. */
internal fun originWorkshopRayTransformedCube(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    transform: Matrix4f,
    reach: Double,
): Double? {
    if (reach !in 0.0..4.5 || !listOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z).all(Double::isFinite)) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-9 || abs(transform.determinant()) < 1e-9f) return null
    val inverse = Matrix4f(transform).invert()
    val point = inverse.transformPosition(Vector3f(origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat()))
    val ray = inverse.transformDirection(Vector3f((direction.x / length).toFloat(), (direction.y / length).toFloat(), (direction.z / length).toFloat()))
    var near = 0.0
    var far = reach
    for ((position, velocity) in listOf(point.x to ray.x, point.y to ray.y, point.z to ray.z)) {
        if (!position.isFinite() || !velocity.isFinite()) return null
        if (abs(velocity) < 1e-9f) {
            if (position !in 0f..1f) return null
        } else {
            val first = -position.toDouble() / velocity
            val second = (1.0 - position) / velocity
            near = max(near, min(first, second))
            far = min(far, max(first, second))
            if (near > far) return null
        }
    }
    return near.takeIf { it > 0.0 && it <= reach }
}

private data class GameSettings(
    val enabled: Boolean,
    val rules: OriginWorkshopGameRules,
    val workpieceRenderer: OriginWorkshopWorkpieceRenderer,
) {
    companion object {
        fun load(mergeDefaults: Boolean = true): GameSettings {
            val config = ConfigManager.ofModule(ArcOriginPlugin.instance.legacyDataPath, "origin-workshop-game.yml")
            if (mergeDefaults) config.mergeMissingFromBundled("modules/origin-workshop-game.yml")
            val timeout = config.integer("origin-workshop-game.session-timeout-seconds", 180).toLong() * 20
            return GameSettings(
                config.bool("origin-workshop-game.enabled", false),
                OriginWorkshopGameRules(timeout = timeout),
                OriginWorkshopWorkpieceRenderer.parse(config.string("origin-workshop-game.workpiece-renderer", "model")),
            )
        }
    }
}

/** One player owns the sleeping worker's station; each role uses physical packet-only handoffs. */
internal object OriginWorkshopGame : PluginModule, Listener {
    override val name = "OriginWorkshopGame"
    override val priority = 25

    private const val REACH = 4.5
    private const val TARGET_RADIUS = 0.36
    private const val BLOCK_EPSILON = 0.06
    private const val TICK_NANOS = 50_000_000L
    private const val STEP_TICKS = 1L
    private const val SESSION_RANGE_SQUARED = 144.0
    private const val COOLDOWN = 86_400_000L
    private const val INPUT_DEDUPE_NANOS = 120_000_000L
    private const val FEEDBACK_NANOS = 500_000_000L
    private const val HOVER_SOUND_NANOS = 700_000_000L
    private const val HOVER_CHECK_TICKS = 2L
    private const val GUIDANCE_REFRESH_TICKS = 20L
    private const val CUE_PARTICLE_INTERVAL_TICKS = 12L
    private val CUE_PARTICLE_DUST = Particle.DustOptions(Color.fromRGB(120, 220, 255), 0.4f)
    private const val BUSY_FEEDBACK_NANOS = 1_000_000_000L
    private const val MAX_RECENT_INPUTS = 64

    private val CUT_BOARD_SIZE = OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)

    private val start = OriginWorkshopPoint(0.0, 1.28, -1.35)

    private data class Turn(
        val prop: OriginWorkshopProp,
        val from: Location,
        val to: Location,
        val rotation: Quaternionf,
        val flip: Boolean,
        val lift: Double,
        val startedAt: Long,
        val lowerSewingFoot: Boolean = false,
    )

    private data class Session(
        val id: UUID,
        val playerId: UUID,
        val worldId: UUID,
        val began: Long,
        val tableId: String,
        val recipe: OriginWorkshopGameRecipe,
        val reward: OriginWorkshopRewardCandidate,
        val rewardHandler: OriginWorkshopCraftRewards,
        var progress: OriginWorkshopGameProgress,
        val workpieceRenderer: OriginWorkshopWorkpieceRenderer,
        val boardModels: Map<OriginWorkshopBoardModel, ItemStack> = emptyMap(),
        val activity: OriginWorkshopSessionActivity = OriginWorkshopSessionActivity(began),
        val parts: MutableList<PacketDisplay> = mutableListOf(),
        var workpiece: OriginWorkshopProp? = null,
        var turn: Turn? = null,
        var carried: OriginWorkshopProp? = null,
        var leftLeg: OriginWorkshopProp? = null,
        var rightLeg: OriginWorkshopProp? = null,
        var brush: PacketItemDisplay? = null,
        var targetMarker: OriginWorkshopProp? = null,
        var stretchedEdges: Int = 0,
        var seams: Int = 0,
        var padded: Boolean = false,
        var tuckedEdges: Int = 0,
        var coverFasteners: Int = 0,
        var planedEdges: Int = 0,
        var joinedEdges: Int = 0,
        var panelBackUp: Boolean = false,
        val sandedFaces: IntArray = intArrayOf(0, 0),
        val coatedFaces: IntArray = intArrayOf(0, 0),
        var highlightedControl: String? = null,
        var hoveredControl: Boolean = false,
        var lastGuideTick: Long = Long.MIN_VALUE,
        var lastCueParticleTick: Long = Long.MIN_VALUE,
        var lastFeedback: Long = 0,
        var lastHoverSound: Long = 0,
        var rewardSent: Boolean = false,
        val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    )

    private var settings: GameSettings? = null
    private var owner: PaperPacketDisplays? = null
    private var particleOwner: PaperViewerParticles? = null
    private var label: PacketTextDisplay? = null
    private var occupancyLabel: PacketTextDisplay? = null
    private val rewards = mutableMapOf<String, OriginWorkshopCraftRewards>()
    private var rewardPool: List<OriginWorkshopRewardCandidate> = emptyList()
    private var idleTable: String? = null
    private val tableId: String get() = session?.tableId ?: idleTable.orEmpty()
    private var commonTasks = LifecycleTaskScope()
    private var session: Session? = null
    private val pendingStatus = mutableSetOf<UUID>()
    private val accepted = linkedMapOf<UUID, Long>()
    private val idleHoverers = mutableSetOf<UUID>()
    private val hoverSounds = linkedMapOf<UUID, Long>()
    private val busyFeedbackNanos = linkedMapOf<UUID, Long>()
    private var registered = false
    private var generation = 0L

    internal fun isWorking(playerId: UUID): Boolean = session?.playerId == playerId

    override fun init() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
            registered = true
        }
        reload()
    }

    override fun reload() {
        val loaded = try {
            GameSettings.load(mergeDefaults = false)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop game config rejected; keeping current runtime", failure)
            return
        }
        val loadedRewards = try {
            if (loaded.enabled) OriginWorkshopRewardPool.entries else emptyList()
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop reward pool rejected; keeping current runtime", failure)
            return
        }
        stop("reload")
        settings = loaded
        rewardPool = loadedRewards
        if (!loaded.enabled) {
            ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP_GAME phase=DISABLED player=none session=none stage=NONE")
            return
        }
        val displayOwner = try {
            PaperPacketDisplays(ArcOriginPlugin.instance, "workshop-game")
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop game display owner unavailable", failure)
            return
        }
        val particles = try {
            PaperViewerParticles(ArcOriginPlugin.instance, "workshop-game")
        } catch (failure: Exception) {
            displayOwner.close()
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop game particle owner unavailable", failure)
            return
        }
        owner = displayOwner
        particleOwner = particles
        idleHoverers.clear()
        commonTasks.runTimer(HOVER_CHECK_TICKS, HOVER_CHECK_TICKS) { updateIdleHover() }
        ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP_GAME phase=READY player=none session=none stage=STOCK")
    }

    override fun shutdown() {
        stop("shutdown")
        settings = null
        if (registered) HandlerList.unregisterAll(this)
        registered = false
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (event.hand == EquipmentSlot.HAND && click(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK
        ) return
        if (click(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val active = session?.takeIf { it.playerId == event.player.uniqueId } ?: return
        if (event.to.world.uid != active.worldId) return
        if (event.from != event.to) active.activity.recordActivity(nowTick())
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        session?.takeIf { it.playerId == event.player.uniqueId }?.let { cancel(it, "quit", false) }
        pendingStatus.remove(event.player.uniqueId)
        accepted.remove(event.player.uniqueId)
        idleHoverers.remove(event.player.uniqueId)
        hoverSounds.remove(event.player.uniqueId)
        busyFeedbackNanos.remove(event.player.uniqueId)
    }

    private fun click(player: Player): Boolean = try {
        handleClick(player)
    } catch (failure: Exception) {
        ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=INPUT_ERROR player=${player.uniqueId}", failure)
        session?.takeIf { it.playerId == player.uniqueId }?.let { cancel(it, "input-error", true) }
        true
    }

    private fun handleClick(player: Player): Boolean {
        if (owner == null || settings?.enabled != true) return false
        val nowNanos = System.nanoTime()
        accepted.entries.removeIf { nowNanos - it.value > INPUT_DEDUPE_NANOS }
        if (originWorkshopIsDuplicateClick(accepted[player.uniqueId], nowNanos, INPUT_DEDUPE_NANOS)) return true
        accepted.remove(player.uniqueId)
        if (accepted.size >= MAX_RECENT_INPUTS) accepted.entries.firstOrNull()?.let { accepted.remove(it.key) }

        if (session == null) reconcileIdleStation()
        val current = session
        if (current == null && idleTable == null) return false
        if (current != null && !OriginFurnitureWorkshopModule.ownsPlayerTable(current.tableId, player.uniqueId) && current.playerId == player.uniqueId) {
            cancel(current, "station-reservation-lost", true)
            return true
        }
        if (current != null && current.playerId != player.uniqueId) {
            if (!isWorkshopInteraction(player)) return false
            sendBusyFeedback(player, current.playerId, nowNanos)
            return true
        }
        if (current != null) {
            current.activity.recordActivity(nowTick())
            val reason = cancellationReason(current, player, settings?.rules?.timeout ?: 3_600)
            if (reason != null) {
                cancel(current, reason.name.lowercase(), true)
                return true
            }
            if (current.turn != null) return true
            advanceTimedStage(current, player, nowTick())
        }
        if (current == null) {
            if (startInteractionDistance(player) == null) return false
        } else {
            if (activeTargetDistance(current, player) == null) return false
        }

        accepted[player.uniqueId] = nowNanos
        if (current == null) startClick(player) else sessionClick(player, current)
        return true
    }

    private fun startClick(player: Player) {
        val selectedTable = idleTable ?: return
        val productId = OriginFurnitureWorkshopModule.productIdFor(selectedTable) ?: return
        val gameSettings = settings ?: return
        val renderer = gameSettings.workpieceRenderer
        val recipe = OriginWorkshopTablesModule.recipeFor(selectedTable, productId, gameSettings.rules, renderer) ?: return
        val boardModels = loadBoardModels(player, recipe, renderer) ?: return
        // Choose once; this same candidate owns both the result display and durable delivery.
        val reward = rewardPool.randomOrNull() ?: return
        val handler = rewards.getOrPut(reward.itemId) { OriginWorkshopCraftRewards(COOLDOWN, reward.itemId) }
        if (!pendingStatus.add(player.uniqueId)) return
        if (pendingStatus.size > 32) {
            pendingStatus.remove(player.uniqueId)
            player.sendActionBar(Component.text("Сейчас проверяется много заявок. Попробуй ещё раз через минуту."))
            return
        }
        player.sendActionBar(Component.text("Проверяю доступ к сборке."))
        val token = commonTasks.token()
        val generationAtQuery = generation
        handler.status(player).whenComplete { remaining, failure ->
            commonTasks.runSync(token) {
                pendingStatus.remove(player.uniqueId)
                if (generationAtQuery != generation || !player.isOnline || owner == null) return@runSync
                if (OriginFurnitureWorkshopModule.availablePlayerTable() != selectedTable ||
                    OriginFurnitureWorkshopModule.productIdFor(selectedTable) != productId) {
                    if (session != null) sendBusyFeedback(player, session!!.playerId)
                    else player.sendActionBar(Component.text("Мастер уже вернулся. Подожди, пока следующий отправится отдыхать."))
                    return@runSync
                }
                val startLocation = OriginWorkshopTablesModule.pointAt(selectedTable, start)
                if (startLocation == null || player.world.uid != startLocation.world.uid ||
                    player.location.distanceSquared(startLocation) > SESSION_RANGE_SQUARED
                ) return@runSync
                if (failure != null) {
                    ArcOriginPlugin.instance.logger.log(
                        Level.WARNING,
                        "ORIGIN_WORKSHOP_GAME phase=STATUS_ERROR player=" + player.uniqueId + " session=none stage=STOCK",
                        failure,
                    )
                    player.sendActionBar(Component.text("Не удалось проверить перерыв. Попробуй позже."))
                } else if ((remaining ?: Long.MAX_VALUE) > 0) {
                    player.sendActionBar(Component.text("Недавно уже собирали мебель. Подожди ещё " + workshopCooldownText(remaining!!) + "."))
                } else {
                    val missing = handler.missing(player)
                    if (missing != null) {
                        player.sendActionBar(Component.text(missing))
                        return@runSync
                    }
                    if (session != null) {
                        sendBusyFeedback(player, session!!.playerId)
                        return@runSync
                    }
                    begin(player, selectedTable, recipe, reward, handler, renderer, boardModels)
                }
            }
        }
    }

    private fun loadBoardModels(
        player: Player,
        recipe: OriginWorkshopGameRecipe,
        renderer: OriginWorkshopWorkpieceRenderer,
    ): Map<OriginWorkshopBoardModel, ItemStack>? {
        if (recipe.role != OriginWorkshopTableRole.CARPENTER || renderer == OriginWorkshopWorkpieceRenderer.CUBES) return emptyMap()
        val resolved = linkedMapOf<OriginWorkshopBoardModel, ItemStack>()
        for (model in OriginWorkshopBoardModel.entries) {
            val lookup = runCatching { CustomStack.getInstance(model.itemId)?.itemStack?.clone() }
            val stack = lookup.getOrNull()
            if (stack == null) {
                val message = "ORIGIN_WORKSHOP_GAME phase=BOARD_MODEL_MISSING player=${player.uniqueId} model=${model.itemId}"
                val failure = lookup.exceptionOrNull()
                if (failure == null) ArcOriginPlugin.instance.logger.warning(message)
                else ArcOriginPlugin.instance.logger.log(Level.WARNING, message, failure)
                player.sendActionBar(Component.text("Не удалось загрузить модель доски. Попробуй позже."))
                return null
            }
            resolved[model] = stack
        }
        return resolved
    }

    private fun begin(
        player: Player,
        selectedTable: String,
        recipe: OriginWorkshopGameRecipe,
        reward: OriginWorkshopRewardCandidate,
        handler: OriginWorkshopCraftRewards,
        renderer: OriginWorkshopWorkpieceRenderer,
        boardModels: Map<OriginWorkshopBoardModel, ItemStack>,
    ) {
        val rules = settings?.rules ?: return
        if (OriginOddJobsModule.isWorking(player.uniqueId) || OriginDiningModule.isWaiterWorking(player.uniqueId)) {
            player.sendActionBar(Component.text("Сначала заверши текущее поручение."))
            return
        }
        val startLocation = OriginWorkshopTablesModule.pointAt(selectedTable, start) ?: return
        if (player.world.uid != startLocation.world.uid || player.location.distanceSquared(startLocation) > SESSION_RANGE_SQUARED) return

        if (!OriginFurnitureWorkshopModule.acquirePlayerTable(selectedTable, player.uniqueId)) return
        val active = Session(
            UUID.randomUUID(), player.uniqueId, player.world.uid, nowTick(), selectedTable, recipe, reward, handler,
            OriginWorkshopGameProgress(recipe.initialStage, nowTick()),
            renderer,
            boardModels,
        )
        session = active
        try {
            idleHoverers.clear()
            label?.remove()
            label = OriginWorkshopTablesModule.pointAt(tableId, labelAnchor(active, recipe.initialStage))?.let {
                spawnLabel(it, recipe.initialStage.instruction + " · ЛКМ", visibleByDefault = false)
            }
            label?.showTo(player)
            setOccupancyLabel(player)
            active.tasks.runTimer(STEP_TICKS, STEP_TICKS) {
                try { step(active, rules) } catch (failure: Exception) {
                    ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=TICK_ERROR table=${active.tableId} session=${active.id}", failure)
                    cancel(active, "tick-error", true)
                }
            }
            showStage(active, player, "START")
        } catch (failure: Exception) {
            cancel(active, "start-error", true)
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=START_ERROR table=$selectedTable", failure)
        }
    }

    private fun sessionClick(player: Player, active: Session) {
        val interaction = active.recipe.interactions[active.progress.stage] ?: run {
            feedback(player, active, "Подожди, станок ещё работает.")
            return
        }
        val now = nowTick()
        val action = interaction.action
        val next = originWorkshopTransition(active.progress, action, now, active.recipe) ?: return
        if (!applyAction(player, active, action)) return
        active.progress = next
        active.activity.recordActivity(now)
        if (active.turn == null) showStage(active, player, "STAGE") else {
            particleOwner?.invalidatePending()
            active.targetMarker?.remove()
            active.targetMarker = null
            refreshGuidance(active, player, now, force = true)
        }
        animateMachine(active, now)
        player.playSound(
            player.location,
            if (action == OriginWorkshopGameAction.PICK_STOCK ||
                action == OriginWorkshopGameAction.PICK_SAWN_BOARD ||
                action == OriginWorkshopGameAction.PICK_DRILLED_BOARD ||
                action == OriginWorkshopGameAction.PICK_LEFT_LEG ||
                action == OriginWorkshopGameAction.PICK_RIGHT_LEG
            ) Sound.ENTITY_ITEM_PICKUP else Sound.BLOCK_WOOD_PLACE,
            SoundCategory.PLAYERS,
            0.35f,
            1.0f,
        )
    }

    private fun applyAction(player: Player, active: Session, action: OriginWorkshopGameAction): Boolean {
        val interaction = active.recipe.interactions[active.progress.stage] ?: return false
        when (action) {
            OriginWorkshopGameAction.PICK_STOCK,
            OriginWorkshopGameAction.PICK_FABRIC,
            OriginWorkshopGameAction.PICK_TABLETOP,
            OriginWorkshopGameAction.PICK_PADDING,
            OriginWorkshopGameAction.PICK_LEFT_LEG,
            OriginWorkshopGameAction.PICK_RIGHT_LEG -> {
                val size = active.recipe.partSizes[action] ?: return false
                val material = active.recipe.materials[action] ?: return false
                val boardModel = if (action == OriginWorkshopGameAction.PICK_STOCK &&
                    active.recipe.role == OriginWorkshopTableRole.CARPENTER
                ) OriginWorkshopBoardModel.RAW else null
                val part = spawnProp(
                    active,
                    player,
                    listOf(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0, 0.0, 0.0), size, material)),
                    boardModel,
                )
                active.carried = part
                carry(active, player)
                if (active.recipe.role == OriginWorkshopTableRole.CARPENTER &&
                    action in setOf(OriginWorkshopGameAction.PICK_LEFT_LEG, OriginWorkshopGameAction.PICK_RIGHT_LEG)
                ) OriginWorkshopTablesModule.setCraftPartVisible(tableId, interaction.control ?: return false, false)
            }
            OriginWorkshopGameAction.PLACE_SAW,
            OriginWorkshopGameAction.PLACE_DRILL,
            OriginWorkshopGameAction.PLACE_JIG,
            OriginWorkshopGameAction.PLACE_FABRIC,
            OriginWorkshopGameAction.PLACE_SEWING,
            OriginWorkshopGameAction.PLACE_VISE,
            OriginWorkshopGameAction.PLACE_ANVIL,
            OriginWorkshopGameAction.PLACE_CUSHION_COVER,
            OriginWorkshopGameAction.PLACE_ASSEMBLY_TOP,
            OriginWorkshopGameAction.PLACE_LEFT_LEG,
            OriginWorkshopGameAction.PLACE_RIGHT_LEG -> {
                val item = active.carried ?: return false
                settle(active, item, interaction.target) ?: return false
                when (action) {
                    OriginWorkshopGameAction.PLACE_LEFT_LEG -> active.leftLeg = item
                    OriginWorkshopGameAction.PLACE_RIGHT_LEG -> active.rightLeg = item
                    else -> active.workpiece = item
                }
                active.carried = null
                if (action == OriginWorkshopGameAction.PLACE_FABRIC) {
                    OriginWorkshopTablesModule.setCraftPartVisible(tableId, "press-cloth", false)
                }
                if (action == OriginWorkshopGameAction.PLACE_SEWING) {
                    OriginWorkshopTablesModule.setCraftPartVisible(tableId, "sewing-fabric", false)
                }
                if (action == OriginWorkshopGameAction.PLACE_CUSHION_COVER) {
                    OriginWorkshopTablesModule.setCraftPartVisible(tableId, "cushion-cover", false)
                    OriginWorkshopTablesModule.setCraftPartVisible(tableId, "cushion-padding", false)
                }
            }
            OriginWorkshopGameAction.PICK_SAWN_BOARD,
            OriginWorkshopGameAction.PICK_DRILLED_BOARD,
            OriginWorkshopGameAction.PICK_PRESSED_COVER,
            OriginWorkshopGameAction.PICK_SEWN_COVER,
            OriginWorkshopGameAction.PICK_VISED_TABLETOP,
            OriginWorkshopGameAction.PICK_JOINED_TABLETOP -> {
                active.carried = active.workpiece ?: return false
                active.workpiece = null
                carry(active, player)
            }
            OriginWorkshopGameAction.REPOSITION_SAW -> {
                val item = active.workpiece ?: return false
                val at = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)) ?: return false
                beginTurn(active, item, at, lift = 0.18)
            }
            OriginWorkshopGameAction.ALIGN_DRILL_CENTER,
            OriginWorkshopGameAction.ALIGN_DRILL_LAST -> {
                val item = active.workpiece ?: return false
                val at = OriginWorkshopTablesModule.pointAt(tableId, interaction.target) ?: return false
                // Deliberately slide this same board under the next drilling position.
                moveProp(item, at, boardRotation())
            }
            OriginWorkshopGameAction.STRETCH_FABRIC_LEFT,
            OriginWorkshopGameAction.STRETCH_FABRIC_RIGHT -> {
                active.stretchedEdges++
                refreshUpholstery(active)
            }
            OriginWorkshopGameAction.TURN_FABRIC -> {
                val item = active.workpiece ?: return false
                val dimensions = OriginWorkshopTablesModule.dimensionsFor(tableId) ?: return false
                val at = OriginWorkshopTablesModule.pointAt(tableId, originWorkshopSewingClothPoint(dimensions, 0.0))
                    ?: return false
                OriginWorkshopTablesModule.animateCraftMachine(tableId, "sewing-foot", 0.0)
                beginTurn(active, item, at, lift = 0.025, lowerSewingFoot = true)
            }
            OriginWorkshopGameAction.ROTATE_TABLETOP,
            OriginWorkshopGameAction.ALIGN_JOIN_SECOND -> {
                val item = active.workpiece ?: return false
                val center = item.center
                beginTurn(active, item, center, lift = 0.18)
            }
            OriginWorkshopGameAction.RELEASE_VISE ->
                OriginWorkshopTablesModule.animateCraftMachine(tableId, "vise", 1.0)
            OriginWorkshopGameAction.PLACE_PADDING -> {
                active.carried?.remove() ?: return false
                active.carried = null
                active.padded = true
                refreshUpholstery(active)
            }
            OriginWorkshopGameAction.TUCK_PADDING_NEAR,
            OriginWorkshopGameAction.TUCK_PADDING_FAR -> {
                active.tuckedEdges++
                refreshUpholstery(active)
            }
            OriginWorkshopGameAction.FASTEN_COVER_LEFT,
            OriginWorkshopGameAction.FASTEN_COVER_RIGHT -> {
                active.coverFasteners++
                refreshUpholstery(active)
                if (action == OriginWorkshopGameAction.FASTEN_COVER_RIGHT) finishProduct(active)
            }
            OriginWorkshopGameAction.START_FINISH_PANEL,
            OriginWorkshopGameAction.ACTIVATE_SAW,
            OriginWorkshopGameAction.ACTIVATE_DRILL,
            OriginWorkshopGameAction.ACTIVATE_PRESS,
            OriginWorkshopGameAction.START_SEWING,
            OriginWorkshopGameAction.TIGHTEN_VISE,
            OriginWorkshopGameAction.ACTIVATE_ANVIL,
            OriginWorkshopGameAction.TIGHTEN_LEFT,
            OriginWorkshopGameAction.TIGHTEN_RIGHT -> Unit
            OriginWorkshopGameAction.LOWER_SEWING_FOOT ->
                OriginWorkshopTablesModule.animateCraftMachine(tableId, "sewing-foot", 1.0)
            OriginWorkshopGameAction.PICK_ABRASIVE,
            OriginWorkshopGameAction.DIP_FINISH_BRUSH -> {
                val at = OriginWorkshopTablesModule.pointAt(tableId, interaction.target) ?: return false
                active.brush?.remove()
                val material = if (action == OriginWorkshopGameAction.PICK_ABRASIVE) Material.SANDSTONE else Material.BRUSH
                active.brush = owner?.spawnItem(at, ItemStack(material))?.apply {
                    isVisibleByDefault = true
                    viewRange = 0.5f
                    itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    teleportDuration = 0
                    interpolationDuration = 0
                    transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0.3f), Quaternionf())
                }?.also { active.parts += it } ?: return false
                carry(active, player)
            }
            OriginWorkshopGameAction.SAND_PANEL_NEAR,
            OriginWorkshopGameAction.SAND_PANEL_CENTER,
            OriginWorkshopGameAction.SAND_PANEL_FAR -> {
                active.sandedFaces[if (active.panelBackUp) 1 else 0]++
                refreshFinishingPanel(active, player)
            }
            OriginWorkshopGameAction.FLIP_PANEL -> {
                val item = active.workpiece ?: return false
                active.panelBackUp = !active.panelBackUp
                beginTurn(active, item, item.center, flip = true, lift = 0.36)
            }
            OriginWorkshopGameAction.COAT_PANEL_NEAR,
            OriginWorkshopGameAction.COAT_PANEL_CENTER,
            OriginWorkshopGameAction.COAT_PANEL_FAR -> {
                active.coatedFaces[if (active.panelBackUp) 1 else 0]++
                refreshFinishingPanel(active, player)
            }
            OriginWorkshopGameAction.HANG_FINISHED_PANEL -> {
                active.brush?.remove()
                active.brush = null
                active.workpiece?.remove()
                active.workpiece = null
                OriginWorkshopTablesModule.setCraftFinishedPanel(tableId, true)
                OriginWorkshopTablesModule.setCraftPartVisible(tableId, "panel", true)
            }
        }
        return true
    }

    private fun updateWorkpiece(active: Session, pieces: List<OriginWorkshopWorkpiecePiece>,
                                 boardModel: OriginWorkshopBoardModel? = null) {
        active.workpiece?.update(renderGeometry(active, pieces, boardModel), boardItem(active, boardModel))
    }

    private fun fastenAssemblerLeg(active: Session, left: Boolean) {
        val leg = (if (left) active.leftLeg else active.rightLeg) ?: return
        leg.update(originWorkshopAssemblerLegPieces(fastened = true))
    }

    private fun refreshUpholstery(active: Session) = updateWorkpiece(active,
        originWorkshopUpholsteryPieces(active.stretchedEdges, active.seams, active.padded, active.tuckedEdges, active.coverFasteners))

    private fun refreshFinishingPanel(active: Session, player: Player) {
        val pieces = originWorkshopFinishingPanelPieces(
            active.sandedFaces[0], active.sandedFaces[1], active.coatedFaces[0], active.coatedFaces[1],
        )
        if (active.workpiece != null) {
            updateWorkpiece(active, pieces)
            return
        }
        val height = OriginWorkshopTablesModule.dimensionsFor(tableId)?.height ?: return
        val center = OriginWorkshopTablesModule.pointAt(tableId, OriginWorkshopPoint(0.0, height + 0.04, -0.45)) ?: return
        active.workpiece = spawnProp(active, player, pieces).also {
            it.place(center, boardRotation())
        }
    }

    private fun beginTurn(active: Session, item: OriginWorkshopProp, to: Location,
                          flip: Boolean = false, lift: Double, lowerSewingFoot: Boolean = false) {
        active.turn = Turn(item, item.center.clone(), to.clone(), Quaternionf(item.rotation),
            flip, lift, nowTick(), lowerSewingFoot)
    }

    private fun animateTurn(active: Session, player: Player, now: Long) {
        val turn = active.turn ?: return
        val progress = ((now - turn.startedAt).toDouble() / ORIGIN_WORKSHOP_TURN_TICKS).coerceIn(0.0, 1.0)
        val pose = originWorkshopTurnPose(
            OriginWorkshopPoint(turn.from.x, turn.from.y, turn.from.z),
            OriginWorkshopPoint(turn.to.x, turn.to.y, turn.to.z), turn.rotation, turn.flip, turn.lift, progress,
        )
        moveProp(turn.prop, turn.from.clone().apply { x = pose.center.x; y = pose.center.y; z = pose.center.z },
            pose.rotation)
        if (progress >= 1.0) {
            active.turn = null
            if (turn.lowerSewingFoot) OriginWorkshopTablesModule.animateCraftMachine(tableId, "sewing-foot", 1.0)
            showStage(active, player, "TURN_FINISHED")
        }
    }

    private fun inSessionRange(active: Session, player: Player): Boolean {
        val station = OriginWorkshopTablesModule.pointAt(active.tableId, start) ?: return false
        val location = player.location
        return station.world.uid == active.worldId && player.world.uid == active.worldId && originWorkshopInSessionRange(
            location.x - station.x, location.y - station.y, location.z - station.z,
        )
    }

    private fun cancellationReason(active: Session, player: Player?, timeoutTicks: Long): OriginWorkshopCancelReason? =
        active.activity.cancelReason(
            player?.isOnline == true, player?.world?.uid == active.worldId,
            player != null && inSessionRange(active, player), nowTick(), timeoutTicks,
        )

    private fun step(active: Session, rules: OriginWorkshopGameRules) {
        if (active !== session) return
        if (!OriginFurnitureWorkshopModule.ownsPlayerTable(active.tableId, active.playerId)) {
            cancel(active, "station-reservation-lost", true)
            return
        }
        val player = Bukkit.getPlayer(active.playerId)
        val reason = cancellationReason(active, player, rules.timeout)
        if (reason != null) {
            cancel(active, reason.name.lowercase(), reason != OriginWorkshopCancelReason.OFFLINE)
            return
        }
        val onlinePlayer = player ?: return
        animateTurn(active, onlinePlayer, nowTick())
        advanceTimedStage(active, onlinePlayer, nowTick())
        animateMachine(active, nowTick())
        updateActiveHover(active, onlinePlayer)
        showCueParticles(active, onlinePlayer, nowTick())
        if (active.carried != null || active.brush != null) carry(active, onlinePlayer)
        refreshGuidance(active, onlinePlayer, nowTick())
        if (active.progress.stage == OriginWorkshopGameStage.REWARDING) reward(active, onlinePlayer)
    }

    private fun showCueParticles(active: Session, player: Player, now: Long) {
        val marker = active.targetMarker ?: return
        val center = marker.center
        if (center.world.uid != player.world.uid || center.distanceSquared(player.location) > 64.0) return
        if (active.lastCueParticleTick != Long.MIN_VALUE && now - active.lastCueParticleTick < CUE_PARTICLE_INTERVAL_TICKS) return
        active.lastCueParticleTick = now
        val at = center.clone().add(0.0, marker.pieces.first().geometry.center.y, 0.0)
        // Private, sparse dust: two small particles per 0.6 seconds, only at the current clickable target.
        particleOwner?.dust(player, at, CUE_PARTICLE_DUST, 2, 0.10, 0.06, 0.10, 0.0)
    }

    private fun updateIdleHover() {
        if (session != null || owner == null) return
        reconcileIdleStation()
        if (idleTable == null) return
        val station = OriginWorkshopTablesModule.pointAt(tableId, start) ?: return
        val now = System.nanoTime()
        val next = Bukkit.getOnlinePlayers().asSequence()
            .filter { it.world.uid == station.world.uid && it.location.distanceSquared(station) <= 100.0 }
            .filter { startInteractionDistance(it) != null }
            .mapTo(linkedSetOf()) { it.uniqueId }
        next.forEach { id ->
            if (id !in idleHoverers) {
                val prior = hoverSounds[id]
                if (prior == null || now - prior >= HOVER_SOUND_NANOS) {
                    Bukkit.getPlayer(id)?.let { player ->
                        player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.20f, 1.45f)
                        hoverSounds[id] = now
                    }
                }
            }
        }
        if (next != idleHoverers) {
            idleHoverers.clear()
            idleHoverers.addAll(next)
            OriginWorkshopTablesModule.highlightCraftControl(tableId, "start", hovered = next.isNotEmpty())
        }
        hoverSounds.entries.removeIf { Bukkit.getPlayer(it.key) == null }
    }

    private fun updateActiveHover(active: Session, player: Player) {
        if (active.turn != null) return
        val hovered = activeTargetDistance(active, player) != null
        if (active.hoveredControl == hovered) return
        active.hoveredControl = hovered
        active.targetMarker?.cue(hovered)
        if (hovered) {
            val now = System.nanoTime()
            if (now - active.lastHoverSound >= HOVER_SOUND_NANOS) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.20f, 1.45f)
                active.lastHoverSound = now
            }
        }
    }

    private fun advanceTimedStage(active: Session, player: Player, now: Long) {
        val before = active.progress
        val operation = active.recipe.timedStages[before.stage] ?: return
        val after = originWorkshopAdvance(before, now, active.recipe)
        if (after == before) return
        animateMachine(active, before.stageStartedAt + operation.durationTicks)
        when (before.stage) {
            OriginWorkshopGameStage.SAWING,
            OriginWorkshopGameStage.SAWING_SECOND -> {
                val second = before.stage == OriginWorkshopGameStage.SAWING_SECOND
                val boardSize = if (second) CUT_BOARD_SIZE else OriginWorkshopGamePartSize(0.87f, 0.08f, 0.22f)
                val output = target(active, OriginWorkshopGameStage.PICK_SAWN_BOARD)
                val pieces = if (second) originWorkshopBoardPieces(holes = 0) else listOf(OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0, 0.0, 0.0), boardSize, Material.OAK_PLANKS))
                val boardModel = if (second) OriginWorkshopBoardModel.CUT else OriginWorkshopBoardModel.CUT_ONCE
                updateWorkpiece(active, pieces, boardModel)
                val offcut = spawnProp(active, player, listOf(OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0, 0.0, 0.0), OriginWorkshopGamePartSize(0.12f, 0.08f, 0.22f), Material.OAK_PLANKS)),
                    OriginWorkshopBoardModel.OFFCUT)
                settle(active, offcut, OriginWorkshopPoint(output.x - 0.60, output.y - 0.13, output.z + if (second) 0.58 else 0.30))
            }
            OriginWorkshopGameStage.DRILLING,
            OriginWorkshopGameStage.DRILLING_SECOND,
            OriginWorkshopGameStage.DRILLING_THIRD -> {
                if (originWorkshopDrillSettleTarget(active.recipe, before.stage) == null) return
                val holes = when (before.stage) {
                    OriginWorkshopGameStage.DRILLING -> 1
                    OriginWorkshopGameStage.DRILLING_SECOND -> 2
                    else -> 3
                }
                val boardModel = when (holes) {
                    1 -> OriginWorkshopBoardModel.DRILLED_1
                    2 -> OriginWorkshopBoardModel.DRILLED_2
                    else -> OriginWorkshopBoardModel.DRILLED_3
                }
                updateWorkpiece(active, originWorkshopBoardPieces(holes), boardModel)
            }
            OriginWorkshopGameStage.UPHOLSTER_SEWING,
            OriginWorkshopGameStage.UPHOLSTER_SEWING_SECOND -> {
                active.seams++
                refreshUpholstery(active)
            }
            OriginWorkshopGameStage.ASSEMBLER_VISING,
            OriginWorkshopGameStage.ASSEMBLER_VISING_SECOND -> {
                active.planedEdges++
                updateWorkpiece(active, originWorkshopJoinedTopPieces(active.planedEdges, active.joinedEdges))
            }
            OriginWorkshopGameStage.ASSEMBLER_HAMMERING,
            OriginWorkshopGameStage.ASSEMBLER_HAMMERING_SECOND -> {
                active.joinedEdges++
                updateWorkpiece(active, originWorkshopJoinedTopPieces(active.planedEdges, active.joinedEdges))
            }
            OriginWorkshopGameStage.FINISHER_WITHDRAWING -> {
                OriginWorkshopTablesModule.setCraftPartVisible(tableId, "panel", false)
                refreshFinishingPanel(active, player)
            }
            OriginWorkshopGameStage.ASSEMBLER_CLAMPING_LEFT -> fastenAssemblerLeg(active, left = true)
            OriginWorkshopGameStage.ASSEMBLER_CLAMPING_RIGHT -> finishProduct(active)
            OriginWorkshopGameStage.CLAMPING_RIGHT,
            OriginWorkshopGameStage.FINISHER_DRYING -> finishProduct(active)
            else -> Unit
        }
        active.progress = after
        active.activity.recordActivity(now)
        showStage(active, player, "STAGE")
    }

    private fun finishProduct(active: Session) {
        val furniture = CustomStack.getInstance(active.reward.itemId)?.itemStack?.clone()
        if (furniture == null) {
            ArcOriginPlugin.instance.logger.warning("ORIGIN_WORKSHOP_GAME phase=RESULT_MISSING player=" + active.playerId + " session=" + active.id)
        } else {
            val player = Bukkit.getPlayer(active.playerId)
            active.workpiece?.remove()
            active.workpiece = null
            val at = OriginWorkshopTablesModule.dimensionsFor(tableId)?.let { dimensions ->
                OriginWorkshopTablesModule.resultAt(tableId, active.reward.anchor(active.recipe.role, dimensions))
            }
            if (at == null) ArcOriginPlugin.instance.logger.warning("ORIGIN_WORKSHOP_GAME phase=RESULT_DISPLAY_UNAVAILABLE table=${active.tableId} product=${active.reward.itemId}")
            if (at != null && player != null) {
                val display = runCatching { owner?.spawnItem(at, furniture) }.onFailure {
                    ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop furniture display unavailable player=" + active.playerId, it)
                }.getOrNull()
                if (display != null) {
                    display.isVisibleByDefault = true
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    display.billboard = Display.Billboard.FIXED
                    display.viewRange = 0.5f
                    display.displayWidth = 1.0f
                    display.displayHeight = 1.0f
                    display.shadowRadius = 0f
                    display.interpolationDelay = -1
                    display.interpolationDuration = 2
                    display.teleportDuration = 2
                    display.transformation = Transformation(
                        Vector3f(), AxisAngle4f(), Vector3f(active.reward.scale.toFloat()), AxisAngle4f(),
                    )
                    active.parts += display
                    OriginWorkshopTablesModule.setCraftAssemblyFixtureVisible(active.tableId, false)
                }
            }
        }
        active.leftLeg?.remove()
        active.leftLeg = null
        active.rightLeg?.remove()
        active.rightLeg = null
    }

    private fun target(active: Session, stage: OriginWorkshopGameStage): OriginWorkshopPoint =
        active.recipe.interactions[stage]?.target ?: active.recipe.timedStages[stage]?.target ?: active.recipe.resultAnchor

    private fun control(active: Session, stage: OriginWorkshopGameStage): String? =
        active.recipe.interactions[stage]?.control ?: active.recipe.timedStages[stage]?.control

    private fun showStage(active: Session, player: Player, phase: String) {
        val stage = active.progress.stage
        particleOwner?.invalidatePending()
        // Each stage has a new click target, not a physical cube travelling between controls.
        active.targetMarker?.remove()
        active.targetMarker = null
        val action = active.recipe.interactions[stage]?.action
        if (action != null) {
            val point = target(active, stage)
            val fastenerDepth = if (active.recipe.role == OriginWorkshopTableRole.ASSEMBLER &&
                action in setOf(OriginWorkshopGameAction.TIGHTEN_LEFT, OriginWorkshopGameAction.TIGHTEN_RIGHT)
            ) 0.035 else null
            val geometry = originWorkshopPlacementMarker(action, targetFaceDepth = fastenerDepth)
            val marker = spawnProp(active, player, geometry, privateViewer = player)
            active.targetMarker = marker
            settle(active, marker, point)
            marker.cue(false)
        }
        OriginWorkshopTablesModule.highlightCraftControl(tableId, null, viewer = player)
        active.highlightedControl = control(active, stage)
        active.hoveredControl = false
        active.workpiece?.glow(null)
        refreshGuidance(active, player, nowTick(), force = true)
        log(active, phase, stage)
    }

    private fun currentPickableDisplay(active: Session): OriginWorkshopProp? = when (active.progress.stage) {
        OriginWorkshopGameStage.SAW_REPOSITION,
        OriginWorkshopGameStage.DRILL_ALIGN_CENTER,
        OriginWorkshopGameStage.DRILL_ALIGN_LAST,
        OriginWorkshopGameStage.PICK_SAWN_BOARD,
        OriginWorkshopGameStage.PICK_DRILLED_BOARD,
        OriginWorkshopGameStage.UPHOLSTER_PICK_COVER,
        OriginWorkshopGameStage.UPHOLSTER_PICK_PRESSED_COVER,
        OriginWorkshopGameStage.ASSEMBLER_PICK_VISED,
        OriginWorkshopGameStage.ASSEMBLER_PICK_JOINED_TOP -> active.workpiece
        else -> null
    }

    private fun refreshGuidance(active: Session, player: Player, now: Long, force: Boolean = false) {
        if (!force && active.lastGuideTick != Long.MIN_VALUE && now - active.lastGuideTick < GUIDANCE_REFRESH_TICKS) return
        active.lastGuideTick = now
        val stage = active.progress.stage
        val timed = timedProgress(active, now)
        val stepLabel = "Этап ${minOf(stage.step, active.recipe.totalSteps)}/${active.recipe.totalSteps} · ${stage.instruction}"
        val taskLabel = if (active.turn != null) {
            "Поворачиваю заготовку…"
        } else if (timed == null) {
            "$stepLabel · ${if (active.recipe.interactions[stage] == null) "Станок работает" else "ЛКМ"}"
        } else {
            "$stepLabel · ${floor(timed * 100.0).toInt()}%"
        }
        val outsideRemaining = active.activity.outsideSince?.let { (WORKSHOP_RETURN_GRACE_TICKS - (now - it) + 19L) / 20L }
        val idleRemaining = ((settings?.rules?.timeout ?: 3_600L) - (now - active.activity.lastActivityAt) + 19L) / 20L
        val notice = when {
            outsideRemaining != null -> "Вернись к станку: сборка прервётся через ${outsideRemaining.coerceAtLeast(0)} с."
            idleRemaining <= 30L -> "Сборка прервётся через ${idleRemaining.coerceAtLeast(0)} с бездействия. Двигайся или продолжи работу."
            else -> taskLabel
        }
        player.sendActionBar(Component.text(notice))
        label?.takeIf(PacketTextDisplay::isValid)?.let { currentLabel ->
            val at = OriginWorkshopTablesModule.pointAt(tableId, labelAnchor(active, stage))
            if (at != null && at.world.uid == active.worldId) currentLabel.teleport(at)
            val shortLabel = if (active.turn != null) "Поворачиваю заготовку…" else buildString {
                append(minOf(stage.step, active.recipe.totalSteps)).append('/').append(active.recipe.totalSteps)
                append("\n").append(stage.instruction)
                if (timed != null) append(" · ").append(floor(timed * 100.0).toInt()).append('%')
                else if (active.recipe.interactions[stage] != null) append(" · ЛКМ")
            }
            currentLabel.text(Component.text(shortLabel))
        }
    }

    private fun timedProgress(active: Session, now: Long): Double? {
        val duration = active.recipe.timedStages[active.progress.stage]?.durationTicks ?: return null
        return ((now - active.progress.stageStartedAt).coerceAtLeast(0L).toDouble() / duration).coerceIn(0.0, 1.0)
    }

    private fun animateMachine(active: Session, now: Long) {
        val operation = active.recipe.timedStages[active.progress.stage] ?: return
        val machine = operation.machine ?: return
        val progress = timedProgress(active, now) ?: return
        val machineProgress = when (active.progress.stage) {
            OriginWorkshopGameStage.FINISHER_WITHDRAWING -> progress * 0.25
            OriginWorkshopGameStage.FINISHER_DRYING -> 0.75 + progress * 0.25
            else -> progress
        }
        OriginWorkshopTablesModule.animateCraftMachine(tableId, machine, machineProgress)
        if (machine == "saw") animateSawWorkpiece(active, progress)
        if (machine == "sewing") {
            val cloth = active.workpiece ?: return
            val dimensions = OriginWorkshopTablesModule.dimensionsFor(tableId) ?: return
            val at = OriginWorkshopTablesModule.pointAt(tableId, originWorkshopSewingClothPoint(dimensions, progress)) ?: return
            moveProp(cloth, at, Quaternionf(cloth.rotation))
        }
    }

    private fun animateSawWorkpiece(active: Session, progress: Double) {
        val board = active.workpiece ?: return
        val intake = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)) ?: return
        val outfeed = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.PICK_SAWN_BOARD)) ?: return
        if (intake.world.uid != active.worldId || outfeed.world.uid != active.worldId) return
        val center = intake.clone().add(outfeed.toVector().subtract(intake.toVector()).multiply(progress))
        val rotation = boardRotation()
        if (active.progress.stage == OriginWorkshopGameStage.SAWING_SECOND) rotation.rotateY(Math.PI.toFloat())
        moveProp(board, center, rotation)
    }

    private fun renderGeometry(active: Session, geometry: List<OriginWorkshopWorkpiecePiece>,
                               boardModel: OriginWorkshopBoardModel?) =
        if (boardModel != null && active.workpieceRenderer == OriginWorkshopWorkpieceRenderer.CUBES)
            originWorkshopCoarseBoardPieces(boardModel) else geometry

    private fun boardItem(active: Session, boardModel: OriginWorkshopBoardModel?): ItemStack? =
        boardModel?.takeIf { active.workpieceRenderer == OriginWorkshopWorkpieceRenderer.MODEL }?.let {
            checkNotNull(active.boardModels[it]) { "Validated workshop board model ${it.itemId} is missing from the session" }
        }

    private fun spawnProp(
        active: Session,
        player: Player,
        geometry: List<OriginWorkshopWorkpiecePiece>,
        boardModel: OriginWorkshopBoardModel? = null,
        privateViewer: Player? = null,
    ) = OriginWorkshopProp(checkNotNull(owner), player.location, renderGeometry(active, geometry, boardModel),
        boardItem(active, boardModel), privateViewer, onSpawn = { active.parts += it })

    private fun settle(active: Session, item: OriginWorkshopProp, point: OriginWorkshopPoint): Location? {
        val center = OriginWorkshopTablesModule.pointAt(tableId, point) ?: return null
        item.place(center, boardRotation())
        item.glow(null)
        return center
    }

    private fun carry(active: Session, player: Player) {
        val bodyYaw = player.bodyYaw.toDouble()
        active.carried?.let { item ->
            val pose = originWorkshopCarryPose(bodyYaw, player.isSneaking, item.pieces.map { it.geometry })
            item.carry(player, pose)
            item.glow(null)
        }
        active.brush?.let { brush ->
            val yaw = Math.toRadians(bodyYaw)
            val forwardX = -kotlin.math.sin(yaw)
            val forwardZ = kotlin.math.cos(yaw)
            val rightX = -kotlin.math.cos(yaw)
            val rightZ = -kotlin.math.sin(yaw)
            brush.attachTo(player)
            brush.interpolationDuration = 1
            brush.transformation = Transformation(
                Vector3f((rightX * 0.38 + forwardX * 0.28).toFloat(),
                    ((if (player.isSneaking) 0.78 else 1.03) - player.height).toFloat(),
                    (rightZ * 0.38 + forwardZ * 0.28).toFloat()),
                Quaternionf().rotationY(-yaw.toFloat()), Vector3f(0.3f), Quaternionf(),
            )
        }
    }

    private fun moveProp(item: OriginWorkshopProp, center: Location, rotation: Quaternionf) =
        item.move(center, rotation)

    /** Named controls and pickable materials use the exact rendered cubes, not a nearby control anchor. */
    private fun activeTargetDistance(active: Session, player: Player): Double? {
        if (active.targetMarker != null) {
            val at = OriginWorkshopTablesModule.pointAt(tableId, target(active, active.progress.stage)) ?: return null
            targetDistance(player, at)?.let { return it }
        }
        val pickable = currentPickableDisplay(active)
        val displays = if (pickable == null) active.highlightedControl?.let {
            OriginWorkshopTablesModule.craftControlDisplaysFor(tableId, it)
        } else null
        if (pickable == null && displays == null) {
            return OriginWorkshopTablesModule.pointAt(tableId, target(active, active.progress.stage))?.let { targetDistance(player, it) }
        }
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val origin = OriginWorkshopVec3(eye.x, eye.y, eye.z)
        val ray = OriginWorkshopVec3(direction.x, direction.y, direction.z)
        val reach = if (pickable != null) {
            pickable.pieces.mapNotNull { piece ->
                val display = piece.display
                val matrix = if (display != null) {
                    if (display.location.world.uid != player.world.uid) return@mapNotNull null
                    blockDisplayHitMatrix(display)
                } else {
                    if (pickable.center.world?.uid != player.world.uid) return@mapNotNull null
                    pickable.hitboxMatrix(piece) ?: return@mapNotNull null
                }
                originWorkshopRayTransformedCube(origin, ray, matrix, REACH)
            }.minOrNull()
        } else {
            displays!!.filterIsInstance<PacketBlockDisplay>().mapNotNull { display ->
                if (display.location.world.uid != player.world.uid) return@mapNotNull null
                originWorkshopRayTransformedCube(origin, ray, blockDisplayHitMatrix(display), REACH)
            }.minOrNull()
        } ?: return null
        return reach.takeUnless {
            player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null
        }
    }

    private fun blockDisplayHitMatrix(display: PacketBlockDisplay): Matrix4f {
        val location = display.location
        val transform = display.transformation
        return Matrix4f().translation(location.x.toFloat(), location.y.toFloat(), location.z.toFloat())
            .rotateY(Math.toRadians(-location.yaw.toDouble()).toFloat())
            .rotateX(Math.toRadians(location.pitch.toDouble()).toFloat())
            .translate(transform.translation).rotate(transform.leftRotation).scale(transform.scale).rotate(transform.rightRotation)
    }

    private fun targetDistance(player: Player, target: Location): Double? {
        if (player.world.uid != target.world?.uid) return null
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val reach = originWorkshopRayHit(
            OriginWorkshopVec3(eye.x, eye.y, eye.z),
            OriginWorkshopVec3(direction.x, direction.y, direction.z),
            OriginWorkshopVec3(target.x, target.y, target.z),
            TARGET_RADIUS,
            REACH,
        ) ?: return null
        if (player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null) return null
        return reach
    }

    private fun startInteractionDistance(player: Player): Double? {
        val station = OriginWorkshopTablesModule.pointAt(tableId, start) ?: return null
        if (player.world.uid != station.world.uid) return null
        val bounds = startInteractionBounds() ?: return null
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val reach = originWorkshopRayAabbHit(
            OriginWorkshopVec3(eye.x, eye.y, eye.z),
            OriginWorkshopVec3(direction.x, direction.y, direction.z),
            bounds,
            REACH,
        ) ?: return null
        if (player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null) return null
        return reach
    }

    private fun startInteractionBounds(): OriginWorkshopAabb? {
        val dimensions = OriginWorkshopTablesModule.dimensionsFor(tableId) ?: return null
        val localBounds = originWorkshopStartInteractionAabb(dimensions)
        val localCorners = buildList {
            for (x in listOf(localBounds.minX, localBounds.maxX))
                for (y in listOf(localBounds.minY, localBounds.maxY))
                    for (z in listOf(localBounds.minZ, localBounds.maxZ)) {
                add(OriginWorkshopPoint(x, y, z))
            }
        }
        val corners = localCorners.map { OriginWorkshopTablesModule.pointAt(tableId, it) ?: return null }
        return OriginWorkshopAabb(
            minX = corners.minOf { it.x }, minY = corners.minOf { it.y }, minZ = corners.minOf { it.z },
            maxX = corners.maxOf { it.x }, maxY = corners.maxOf { it.y }, maxZ = corners.maxOf { it.z },
        )
    }

    private fun isWorkshopInteraction(player: Player): Boolean {
        if (startInteractionDistance(player) != null) return true
        val recipe = session?.recipe ?: return false
        val anchors = recipe.interactions.values.map { it.target } + recipe.timedStages.values.map { it.target }
        return anchors.any { anchor ->
            OriginWorkshopTablesModule.pointAt(tableId, anchor)?.let { targetDistance(player, it) } != null
        }
    }

    private fun sendBusyFeedback(player: Player, ownerId: UUID, now: Long = System.nanoTime()) {
        val previous = busyFeedbackNanos[player.uniqueId]
        if (previous == null || now - previous >= BUSY_FEEDBACK_NANOS) {
            val ownerName = Bukkit.getPlayer(ownerId)?.name ?: "другой игрок"
            player.sendActionBar(Component.text("За верстаком работает $ownerName."))
            busyFeedbackNanos[player.uniqueId] = now
        }
        busyFeedbackNanos.entries.removeIf { now - it.value > 60_000_000_000L || Bukkit.getPlayer(it.key) == null }
        while (busyFeedbackNanos.size > MAX_RECENT_INPUTS) {
            busyFeedbackNanos.entries.firstOrNull()?.let { busyFeedbackNanos.remove(it.key) } ?: break
        }
    }

    private fun boardRotation(): Quaternionf {
        val origin = OriginWorkshopTablesModule.pointAt(tableId, OriginWorkshopPoint(0.0, 0.0, 0.0)) ?: return Quaternionf()
        val localX = OriginWorkshopTablesModule.pointAt(tableId, OriginWorkshopPoint(1.0, 0.0, 0.0)) ?: return Quaternionf()
        val dx = localX.x - origin.x
        val dz = localX.z - origin.z
        return Quaternionf().rotationY(atan2(-dz, dx).toFloat())
    }

    private fun finishSession(active: Session) {
        if (active !== session) return
        particleOwner?.invalidatePending()
        session = null
        OriginFurnitureWorkshopModule.releasePlayerTable(active.tableId, active.playerId)
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.carried = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(active.tableId)
        OriginWorkshopTablesModule.resetWork(active.tableId)
        showStart()
    }

    private fun cancel(active: Session, reason: String, notify: Boolean) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        log(active, "CANCEL", active.progress.stage, reason)
        particleOwner?.invalidatePending()
        session = null
        OriginFurnitureWorkshopModule.releasePlayerTable(active.tableId, active.playerId)
        active.progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.CLOSED, nowTick())
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.carried = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(active.tableId)
        OriginWorkshopTablesModule.resetWork(active.tableId)
        showStart()
        if (notify && player?.isOnline == true) {
            val cause = when (reason) {
                "too_far" -> "Ты надолго отошёл от станка."
                "timeout" -> "Станок освобождён после бездействия."
                "world_changed" -> "Ты покинул мастерскую."
                else -> "Сборка прервана."
            }
            player.sendActionBar(Component.text("$cause Заготовки остались на складе."))
        }
    }

    private fun showStart() {
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        idleTable = null
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = null
        reconcileIdleStation()
    }

    private fun reconcileIdleStation() {
        if (session != null || owner == null) return
        val next = OriginFurnitureWorkshopModule.availablePlayerTable()
        if (next == idleTable) return
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        label?.remove()
        label = null
        idleHoverers.clear()
        idleTable = next
        if (next == null) return
        val product = OriginFurnitureWorkshopModule.productIdFor(next) ?: return
        val gameSettings = settings ?: return
        val recipe = OriginWorkshopTablesModule.recipeFor(next, product, gameSettings.rules, gameSettings.workpieceRenderer) ?: return
        OriginWorkshopTablesModule.highlightCraftControl(next, "start", hovered = false)
        label = OriginWorkshopTablesModule.pointAt(next, benchGuidanceAnchor(start))?.let {
            spawnLabel(it, recipe.title.substringBefore(" · ") + "\nСлучайная мебель · ЛКМ", visibleByDefault = true)
        }
    }

    private fun benchGuidanceAnchor(target: OriginWorkshopPoint): OriginWorkshopPoint =
        originWorkshopGuidanceAnchor(OriginWorkshopTablesModule.dimensionsFor(tableId)
            ?: OriginWorkshopTableDimensions.DEFAULT, target, stock = false)

    private fun labelAnchor(active: Session, stage: OriginWorkshopGameStage): OriginWorkshopPoint {
        val target = target(active, stage)
        return originWorkshopGuidanceAnchor(OriginWorkshopTablesModule.dimensionsFor(active.tableId)
            ?: OriginWorkshopTableDimensions.DEFAULT, target,
            stock = originWorkshopUsesStockGuidance(active.recipe.role, active.recipe.interactions[stage]))
    }

    private fun setOccupancyLabel(player: Player) {
        occupancyLabel?.remove()
        val guide = benchGuidanceAnchor(start)
        val local = guide.copy(y = guide.y + 0.38)
        occupancyLabel = OriginWorkshopTablesModule.pointAt(tableId, local)?.let {
            spawnLabel(
                it,
                "За верстаком: ${player.name}",
                visibleByDefault = true,
                scale = 0.38f,
                lineWidth = 220,
            )
        }
    }

    private fun spawnLabel(
        at: Location,
        text: String,
        visibleByDefault: Boolean,
        scale: Float = 0.42f,
        lineWidth: Int = 220,
    ): PacketTextDisplay? {
        val displayOwner = owner ?: return null
        return runCatching {
            displayOwner.spawnText(at, Component.text(text)).apply {
                isVisibleByDefault = visibleByDefault
                billboard = Display.Billboard.CENTER
                viewRange = 0.55f
                shadowRadius = 0f
                this.lineWidth = lineWidth
                isShadowed = true
                backgroundColor = Color.fromARGB(150, 12, 10, 8)
                isSeeThrough = false
                isDefaultBackground = false
                textOpacity = 230.toByte()
                transformation = Transformation(
                    Vector3f(), AxisAngle4f(), Vector3f(scale), AxisAngle4f(),
                )
            }
        }.onFailure {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop game station label unavailable", it)
        }.getOrNull()
    }

    private fun feedback(player: Player, active: Session, text: String) {
        val now = System.nanoTime()
        if (now - active.lastFeedback < FEEDBACK_NANOS) return
        active.lastFeedback = now
        player.sendActionBar(Component.text(text))
    }

    private fun reward(active: Session, player: Player) {
        if (active.rewardSent || !inSessionRange(active, player)) return
        active.rewardSent = true
        val handler = active.rewardHandler
        val request = UUID.randomUUID()
        val generationAtRequest = generation
        // A claim accepted at the bench may finish during the return grace. The session
        // is still the owner; invalidating it merely for a step away would orphan this request.
        val valid = {
            active === session && generationAtRequest == generation &&
                OriginFurnitureWorkshopModule.ownsPlayerTable(active.tableId, active.playerId) &&
                active.progress.stage == OriginWorkshopGameStage.REWARDING &&
                player.isOnline && player.world.uid == active.worldId
        }
        log(active, "REWARD_REQUEST", active.progress.stage)
        handler.complete(player, request, valid) { error ->
            if (active !== session || generationAtRequest != generation) return@complete
            if (error == null) {
                val next = if (player.hasPermission(WORKSHOP_COOLDOWN_BYPASS)) "Можно сразу начать новую сборку."
                    else "Новую сборку можно начать через 24 часа."
                player.sendMessage(
                    Component.text("\n  Мебель готова и добавлена в инвентарь.\n  $next\n"),
                )
                log(active, "REWARD_DELIVERED", active.progress.stage)
            } else {
                player.sendMessage(Component.text("\n  " + error + "\n  Если мебель не появилась, сообщи администрации.\n"))
                log(active, "REWARD_FAILED", active.progress.stage)
            }
            finishSession(active)
        }
    }

    private fun stop(reason: String) {
        generation++
        session?.let { cancel(it, reason, false) }
        pendingStatus.clear()
        accepted.clear()
        commonTasks.close()
        commonTasks = LifecycleTaskScope()
        rewards.values.forEach { it.close() }
        rewards.clear()
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = null
        busyFeedbackNanos.clear()
        owner?.close()
        owner = null
        particleOwner?.close()
        particleOwner = null
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        idleTable = null
    }

    private fun log(active: Session, phase: String, stage: OriginWorkshopGameStage, detail: String = "") {
        ArcOriginPlugin.instance.logger.info(
            "ORIGIN_WORKSHOP_GAME phase=" + phase + " player=" + active.playerId + " session=" + active.id +
                " table=" + active.tableId + " reward=" + active.reward.itemId + " stage=" + stage +
                if (detail.isEmpty()) "" else " detail=" + detail,
        )
    }

    private fun nowTick() = System.nanoTime() / TICK_NANOS
}
