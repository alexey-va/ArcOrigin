package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.origin.commands.OriginPortalsSubCommand
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.util.TextUtils
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class SlimefunTerminalSettings(
    val world: String, val x: Double, val floorY: Double, val z: Double, val yaw: Double,
    val audienceRange: Double, val interactionDistance: Double, val text: Map<String, String>,
) {
    init {
        require(world.isNotBlank())
        require(listOf(x, floorY, z, yaw, audienceRange, interactionDistance).all(Double::isFinite))
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0)
        require(floorY in -64.0..320.0 && yaw in -360.0..360.0)
        require(audienceRange in 8.0..48.0 && interactionDistance in 2.0..4.0)
        require(text.keys.containsAll(TEXT_KEYS) && text.values.all(String::isNotBlank))
    }

    fun line(key: String): Component = TextUtils.mm(text.getValue(key).replace("\\n", "\n"))

    fun location(world: World, localX: Double = 0.0, localY: Double = 0.0, localZ: Double = 0.0): Location {
        val angle = Math.toRadians(yaw)
        return Location(world, x + localX * cos(angle) - localZ * sin(angle), floorY + localY,
            z + localX * sin(angle) + localZ * cos(angle))
    }

    companion object {
        val TEXT_KEYS = listOf("screen", "title", "introduction", "island", "technology", "transition", "enter", "unavailable")

        fun load(path: Path): SlimefunTerminalSettings {
            val config = ConfigManager.ofModule(path, "origin-slimefun-terminal.yml")
            val root = "origin-slimefun-terminal"
            return SlimefunTerminalSettings(
                config.string("$root.world", "rc_origin_spawn"), config.real("$root.x", -17.25),
                config.real("$root.floor-y", 72.0), config.real("$root.z", -50.1), config.real("$root.yaw", 90.0),
                config.real("$root.audience-range", 28.0), config.real("$root.interaction-distance", 3.5),
                TEXT_KEYS.associateWith { config.string("$root.text.$it") },
            )
        }
    }
}

/** Local -Z is the front; the same cuboids drive the runtime and offline preview. */
internal data class SlimefunTerminalPart(
    val key: String, val material: String, val x: Double, val y: Double, val z: Double,
    val width: Double, val height: Double, val depth: Double,
)

internal fun slimefunTerminalParts(): List<SlimefunTerminalPart> = listOf(
    SlimefunTerminalPart("foot", "POLISHED_BLACKSTONE", 0.0, .06, 0.0, .9, .12, .7),
    SlimefunTerminalPart("stand", "WAXED_COPPER_BLOCK", 0.0, .5, .04, .3, .76, .28),
    SlimefunTerminalPart("desktop", "WAXED_CUT_COPPER", 0.0, .93, 0.0, 1.1, .1, .76),
    SlimefunTerminalPart("keyboard", "GRAY_CONCRETE", 0.0, 1.005, -.23, .7, .05, .22),
    SlimefunTerminalPart("keys", "POLISHED_ANDESITE", 0.0, 1.036, -.24, .58, .012, .13),
    SlimefunTerminalPart("neck", "POLISHED_BLACKSTONE", 0.0, 1.07, .12, .16, .18, .14),
    SlimefunTerminalPart("monitor", "BLACK_CONCRETE", 0.0, 1.39, .13, 1.0, .6, .18),
    SlimefunTerminalPart("screen", "GREEN_CONCRETE", 0.0, 1.40, .032, .84, .43, .016),
    SlimefunTerminalPart("indicator", "LIME_CONCRETE", .40, 1.14, .027, .06, .035, .026),
)

internal fun slimefunTerminalDialog(settings: SlimefunTerminalSettings, enter: () -> Unit): PaperDialogScreen =
    PaperDialogScreen(
        id = "origin.slimefun-terminal", title = settings.line("title"),
        body = listOf(DialogTables.filledBody(
            rows = listOf("introduction", "island", "technology", "transition").map { key ->
                listOf(settings.line(key).colorIfAbsent(TextColor.color(0xF3E3CE)))
            }, headers = null, width = 310, columnWeights = listOf(1),
        )),
        buttons = listOf(PaperDialogButton(
            id = PaperDialogActionId.of("enter_slimefun"), label = settings.line("enter"),
            width = 310, closeDialogBeforeAction = true, onClick = { enter() },
        )),
        exitButton = PaperDialogButton(
            id = PaperDialogActionId.of("close"),
            label = Component.translatable("mco.selectServer.close", NamedTextColor.WHITE),
            width = 200, closeDialogBeforeAction = true, onClick = {},
        ),
    )

/** One static console, one native input target, existing authoritative portal admission. */
internal object OriginSlimefunTerminalModule : PluginModule, Listener {
    override val name = "OriginSlimefunTerminal"
    override val priority = 29
    private var runtime: SlimefunTerminalRuntime? = null
    private var pending: Pair<SlimefunTerminalSettings, World>? = null
    private var registered = false

    override fun init() = reload()

    override fun reload() {
        val settings = SlimefunTerminalSettings.load(ArcOriginPlugin.instance.legacyDataPath)
        val world = requireNotNull(Bukkit.getWorld(settings.world)) { "Terminal world '${settings.world}' is unavailable" }
        runtime?.close()
        runtime = null
        pending = settings to world
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
            registered = true
        }
        startWhenLoaded()
    }

    private fun startWhenLoaded() {
        val (settings, world) = pending ?: return
        if (!world.isChunkLoaded(floor(settings.x).toInt() shr 4, floor(settings.z).toInt() shr 4)) return
        val replacement = SlimefunTerminalRuntime(settings, world, ArcOriginPlugin.instance.propDisplays())
        try {
            replacement.start()
            runtime = replacement
            pending = null
            ArcOriginPlugin.instance.logger.info("ORIGIN_SLIMEFUN_TERMINAL phase=READY world=${settings.world} x=${settings.x} y=${settings.floorY} z=${settings.z}")
        } catch (failure: Exception) {
            replacement.close()
            pending = null
            throw failure
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val (settings, world) = pending ?: return
        if (event.world == world && event.chunk.x == floor(settings.x).toInt() shr 4 &&
            event.chunk.z == floor(settings.z).toInt() shr 4) startWhenLoaded()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event is PlayerInteractAtEntityEvent || event.hand != EquipmentSlot.HAND) return
        if (runtime?.interact(event.player, event.rightClicked) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (runtime?.interact(event.player, event.rightClicked) == true) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { runtime?.forget(event.player.uniqueId) }

    override fun shutdown() {
        if (registered) HandlerList.unregisterAll(this)
        registered = false
        pending = null
        runtime?.close()
        runtime = null
    }

    internal fun isCurrent(candidate: SlimefunTerminalRuntime): Boolean = runtime === candidate
}

internal class SlimefunTerminalRuntime(
    private val settings: SlimefunTerminalSettings, private val world: World, private val owner: PaperPacketDisplays,
) : AutoCloseable {
    private val displays = mutableListOf<PacketDisplay>()
    private val openedAt = mutableMapOf<UUID, Long>()
    private var interaction: Interaction? = null
    private var closed = false

    fun start() {
        val anchor = settings.location(world)
        val rotation = Quaternionf().rotationY(-Math.toRadians(settings.yaw).toFloat())
        for (part in slimefunTerminalParts()) {
            val display = owner.spawnBlock(anchor, Material.valueOf(part.material).createBlockData()).also(displays::add)
            configure(display)
            if (part.key == "indicator") display.brightness = Display.Brightness(15, 15)
            val size = Vector3f(part.width.toFloat(), part.height.toFloat(), part.depth.toFloat())
            val corner = Vector3f(part.x.toFloat(), part.y.toFloat(), part.z.toFloat()).sub(Vector3f(size).mul(.5f)).rotate(rotation)
            display.transformation = Transformation(corner, rotation, size, Quaternionf())
        }
        val text = owner.spawnText(settings.location(world, 0.0, 1.23, .017).apply { yaw = settings.yaw.toFloat() }, settings.line("screen"))
            .also(displays::add)
        configure(text)
        text.brightness = Display.Brightness(15, 15)
        text.alignment = TextDisplay.TextAlignment.CENTER
        text.lineWidth = 120
        text.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        text.isShadowed = true
        text.isSeeThrough = false
        text.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(.65f), Quaternionf())
        interaction = world.spawn(anchor, Interaction::class.java).apply {
            interactionWidth = 1.12f
            interactionHeight = 1.75f
            isResponsive = true
            isPersistent = false
            isInvulnerable = true
        }
    }

    private fun configure(display: PacketDisplay) {
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(10, 12)
        display.viewRange = (settings.audienceRange / 64.0).toFloat()
        display.displayWidth = 2f
        display.displayHeight = 2f
        display.shadowRadius = 0f
        display.shadowStrength = 0f
    }

    fun interact(player: Player, clicked: Entity): Boolean {
        if (closed || clicked.uniqueId != interaction?.uniqueId) return false
        if (!canUse(player)) return true
        val now = System.nanoTime()
        if (openedAt[player.uniqueId]?.let { now - it < 250_000_000L } == true) return true
        openedAt[player.uniqueId] = now
        OriginMenus.beginDialogFlow(player)
        OriginMenus.openDialog(player, slimefunTerminalDialog(settings) {
            if (OriginSlimefunTerminalModule.isCurrent(this) && canUse(player)) {
                // Reuse both authoritative enter() and its existing player-safe result feedback.
                OriginPortalsSubCommand.execute(player, arrayOf("enter", "slimefun"))
            } else if (player.isOnline) player.sendMessage(settings.line("unavailable"))
        })
        return true
    }

    private fun canUse(player: Player): Boolean {
        if (closed || interaction?.isValid != true || !player.isOnline || player.world != world) return false
        val target = settings.location(world, 0.0, 1.4, .02)
        val delta = target.toVector().subtract(player.eyeLocation.toVector())
        val distance = delta.length()
        if (distance > settings.interactionDistance) return false
        return distance < 1e-6 || world.rayTraceBlocks(player.eyeLocation, delta.multiply(1.0 / distance),
            distance, FluidCollisionMode.NEVER, true) == null
    }

    fun forget(playerId: UUID) { openedAt.remove(playerId) }

    override fun close() {
        if (closed) return
        closed = true
        interaction?.remove()
        interaction = null
        displays.forEach(PacketDisplay::remove)
        displays.clear()
        openedAt.clear()
    }
}
