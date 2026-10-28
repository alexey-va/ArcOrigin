package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.joml.Matrix4f
import ru.arc.origin.api.ArcOriginPortalVisuals
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.logging.ArcLogging.warn
import ru.arc.util.TextUtils
import java.nio.file.Path
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class OriginPortalId(
    val key: String,
    val central: Boolean,
    val defaultWorld: String,
    val defaultStyle: OriginPortalStyle,
    val defaultCommand: String,
    val defaultLabel: String,
    val defaultX: Double,
    val defaultY: Double,
    val defaultZ: Double,
    val defaultYaw: Float,
    val defaultWidth: Double,
    val defaultHeight: Double,
    val compactService: Boolean = false,
    val defaultEnabled: Boolean = true,
    val destinationServer: String? = null,
    val defaultParticleRadius: Double? = if (compactService) 1.25 else null,
    val defaultParticleHeight: Double? = if (compactService) defaultHeight else null,
    val defaultPulseAmplitude: Double? = if (compactService) 0.0 else null,
    val defaultParticlesEnabled: Boolean? = if (compactService) false else null,
    val maxParticleRadius: Double = 8.0,
    val maxParticleHeight: Double = 16.0,
    val maxTransferDistance: Double = 8.0,
    val liftDisplayByConfiguredOffset: Boolean = compactService,
    val defaultVerticalOffset: Double? = if (compactService) defaultHeight / 2.0 else null,
    val feetAnchored: Boolean = compactService,
    // Fraction of the scaled model height that is transparent below its visible alpha bounds.
    val defaultVisibleBottomInsetRatio: Double = if (compactService) 10.0 / 116.0 else 0.0,
) {
    SURVIVAL(
        key = "survival",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "arc rtp survival --only-if-first",
        defaultLabel = "Новые биомы",
        defaultX = 9.5,
        defaultY = 70.0,
        defaultZ = 0.5,
        defaultYaw = 270f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    MINING(
        key = "mining",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = OriginPortalStyle.ASTRAL,
        defaultCommand = "arc rtp mining --only-if-first",
        defaultLabel = "Мир добычи",
        defaultX = 6.914214,
        defaultY = 70.0,
        defaultZ = -5.914214,
        defaultYaw = 225f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    VANILLA(
        key = "vanilla",
        central = true,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = OriginPortalStyle.VOID,
        defaultCommand = "arc rtp vanilla --only-if-first",
        defaultLabel = "Ванильные биомы",
        defaultX = 6.914214,
        defaultY = 70.0,
        defaultZ = 6.914214,
        defaultYaw = 315f,
        defaultWidth = 12.0,
        defaultHeight = 16.8,
    ),
    GALLERY_EXIT(
        key = "gallery_exit",
        central = false,
        defaultWorld = "rc_atelier_furniture_gallery",
        defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "rcfurniturereturn",
        defaultLabel = "Вернуться в Origin",
        defaultX = 36.5,
        defaultY = 67.0,
        defaultZ = 60.5,
        defaultYaw = 90f,
        defaultWidth = 4.5,
        defaultHeight = 6.3,
    ),
    SLIMEFUN(
        key = "slimefun",
        central = false,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "arc originportals enter slimefun",
        defaultLabel = "Slimefun",
        // Disabled by default; the anchor is the floor-level entry point.
        defaultX = -17.5,
        defaultY = 72.0,
        defaultZ = -52.5,
        defaultYaw = 180f,
        defaultWidth = 2.8,
        defaultHeight = 2.8,
        defaultEnabled = false,
        destinationServer = "slimefun",
        defaultParticleRadius = 1.25,
        defaultParticleHeight = 3.0,
        defaultPulseAmplitude = 0.0,
        defaultParticlesEnabled = false,
        maxParticleRadius = 1.4,
        maxParticleHeight = 2.8,
        maxTransferDistance = 8.0,
        liftDisplayByConfiguredOffset = true,
    ),
    FURNITURE_ENTRY(
        key = "furniture_entry",
        central = false,
        defaultWorld = "rc_origin_spawn",
        defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "rcfurniturevisit room_01",
        defaultLabel = "Галерея мебели",
        defaultX = -46.5,
        defaultY = 71.9375,
        defaultZ = -70.5,
        defaultYaw = 0f,
        defaultWidth = 3.0,
        defaultHeight = 4.2,
        defaultEnabled = false,
        defaultParticleRadius = 1.25,
        defaultParticleHeight = 4.2,
        defaultPulseAmplitude = 0.0,
        defaultParticlesEnabled = false,
        maxParticleRadius = 1.4,
        maxParticleHeight = 4.2,
        liftDisplayByConfiguredOffset = true,
        defaultVerticalOffset = 2.1,
        feetAnchored = true,
        // Across all 12 frames the visible alpha reaches y=106 of the 116px crop.
        defaultVisibleBottomInsetRatio = 10.0 / 116.0,
    ),
    ADVENTURE_GUILD(
        key = "adventure_guild", central = false, compactService = true,
        defaultWorld = "rc_origin_spawn", defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "ag", defaultLabel = "Гильдия приключений",
        defaultX = 32.5, defaultY = 67.0, defaultZ = 24.5, defaultYaw = -90f,
        defaultWidth = 3.0, defaultHeight = 4.2,
    ),
    CASINO(
        key = "casino", central = false, compactService = true,
        defaultWorld = "rc_origin_spawn", defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "rccasino", defaultLabel = "Казино",
        defaultX = -35.0, defaultY = 68.0, defaultZ = 33.5, defaultYaw = 90f,
        defaultWidth = 3.0, defaultHeight = 4.2,
    ),
    FARM(
        key = "farm", central = false, compactService = true,
        defaultWorld = "rc_origin_spawn", defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "arcfarms travel farm", defaultLabel = "Ферма",
        defaultX = 47.5, defaultY = 71.0, defaultZ = 10.5, defaultYaw = 0f,
        defaultWidth = 3.0, defaultHeight = 4.2,
    ),
    MINE(
        key = "mine", central = false, compactService = true,
        defaultWorld = "rc_origin_spawn", defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "arcfarms travel mine", defaultLabel = "Шахта",
        defaultX = 57.5, defaultY = 71.0, defaultZ = 10.5, defaultYaw = 0f,
        defaultWidth = 3.0, defaultHeight = 4.2,
    ),
    LUMBERMILL(
        key = "lumbermill", central = false, compactService = true,
        defaultWorld = "rc_origin_spawn", defaultStyle = OriginPortalStyle.ORIGIN,
        defaultCommand = "arcfarms travel lumber", defaultLabel = "Лесопилка · закрытая бета\nДоступ участникам теста",
        defaultX = 67.5, defaultY = 71.0, defaultZ = 10.5, defaultYaw = 0f,
        defaultWidth = 3.0, defaultHeight = 4.2,
    ),
    ;

    val usesFixedTwoSidedLabels: Boolean
        get() = central || feetAnchored

    companion object {
        fun parse(raw: String?): OriginPortalId? =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Portal presentation identity retained locally so no ARC implementation enum crosses loaders. */
internal enum class OriginPortalStyle(val id: String) {
    LEGACY("legacy"),
    ORIGIN("origin"),
    ASTRAL("astral"),
    VOID("void");

    val usesOriginGate: Boolean
        get() = this != LEGACY

    companion object {
        fun parse(value: String?): OriginPortalStyle? = value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { normalized ->
                when (normalized.lowercase()) {
                    "chaos", "solar" -> ORIGIN
                    else -> entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
                }
            }
    }
}

internal data class OriginPortalAnchor(
    val id: OriginPortalId,
    val enabled: Boolean = true,
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val width: Double,
    val height: Double,
    val entryDepth: Double,
    val command: String,
    val label: String,
    val verticalOffset: Double,
    val labelFrontDistance: Double,
    val labelSideOffset: Double,
    val labelHeightOffset: Double,
    val labelScale: Float,
    val labelBackgroundGray: Int,
    val labelBackgroundAlpha: Int,
    val style: OriginPortalStyle,
    val particlesEnabled: Boolean = true,
    val particleRadius: Double = 5.5,
    val particleHeight: Double = 8.4,
    val pulseAmplitude: Float = 0.035f,
    val transferDistance: Double = 8.0,
    /** Fraction of model height to offset for transparent pixels below the visible image. */
    val visibleBottomInsetRatio: Double = 0.0,
    val entryWidth: Double = width,
    val entryHeight: Double = height,
) {
    fun center(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, 0f)

    fun labelLocation(world: org.bukkit.World): Location = labelLocation(world, labelFrontDistance)

    private fun labelLocation(world: org.bukkit.World, frontDistance: Double): Location {
        val angle = yaw * PI / 180.0
        return Location(
            world,
            x + sin(angle) * frontDistance + cos(angle) * labelSideOffset,
            y + verticalOffset + labelHeightOffset - displayBottomInset,
            z - cos(angle) * frontDistance + sin(angle) * labelSideOffset,
            yaw,
            0f,
        )
    }

    private val displayBottomInset: Double
        get() = if (id.liftDisplayByConfiguredOffset) height * visibleBottomInsetRatio else 0.0

    fun labelLocations(world: org.bukkit.World): List<Location> {
        val front = labelLocation(world)
        if (!id.usesFixedTwoSidedLabels) return listOf(front)
        // Keep both fixed text faces in front of the portal, readable from either side.
        // Separate the faces slightly so their backgrounds do not hide the text.
        return listOf(
            labelLocation(world, labelFrontDistance + 0.01).apply { yaw += 180f },
            labelLocation(world, labelFrontDistance - 0.01),
        )
    }

    /** Yaw-aware portal volume; floor-anchored entries extend upward from their saved feet. */
    fun contains(location: Location): Boolean {
        if (!enabled || location.world?.name != worldName) return false
        val dx = location.x - x
        val dz = location.z - z
        val angle = yaw * PI / 180.0
        val side = (dx * cos(angle)) + (dz * sin(angle))
        val depth = (-dx * sin(angle)) + (dz * cos(angle))
        val verticalPosition = location.y - y
        val insideHeight =
            if (id.feetAnchored) {
                // Coordinate subtraction can put the exact top a few ulps above height.
                verticalPosition in -1e-7..(entryHeight + 1e-7)
            } else {
                abs(verticalPosition) <= entryHeight / 2.0
            }
        return abs(side) <= entryWidth / 2.0 && abs(depth) <= entryDepth && insideHeight
    }
}

internal class OriginPortalsConfig private constructor(
    private val source: Config,
    val enabled: Boolean,
    val verticalOffset: Double,
    val entryDepth: Double,
    val pulseAmplitude: Float,
    val pulsePeriodTicks: Int,
    val particlesEnabled: Boolean,
    val particleStreams: Int,
    val reducedParticleStreams: Int,
    val particlePointsPerStream: Int,
    val reducedParticlePointsPerStream: Int,
    val particleRadius: Double,
    val particleHeight: Double,
    val particleTurns: Double,
    val particleSize: Float,
    val particleCoreCount: Int,
    val fullParticleDistance: Double,
    val reducedParticleDistance: Double,
    val itemIds: Map<OriginPortalStyle, String>,
    val anchors: List<OriginPortalAnchor>,
    val enteredMessage: String,
) {
    fun persistFeet(id: OriginPortalId, location: Location) =
        persistFeet(id, location.x, location.y, location.z, location.yaw)

    fun persistFeet(id: OriginPortalId, x: Double, y: Double, z: Double, yaw: Float) {
        val path = "origin-portals.anchors.${id.key}"
        source.setDouble("$path.x", x)
        source.setDouble("$path.y", y)
        source.setDouble("$path.z", z)
        source.setDouble("$path.yaw", originPortalMoveYaw(id, yaw).toDouble())
        source.saveStrict()
    }

    companion object {
        private const val RESOURCE = "origin-spawn.yml"
        private val DEFAULT_ITEMS =
            mapOf(
                OriginPortalStyle.ORIGIN to "origin_gate_portals:origin_portal",
                OriginPortalStyle.ASTRAL to "origin_gate_portals:astral_portal",
                OriginPortalStyle.VOID to "origin_gate_portals:void_portal",
            )

        fun load(dataPath: Path, mergeDefaults: Boolean = true): OriginPortalsConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            if (mergeDefaults) source.mergeMissingFromBundled("modules/$RESOURCE")
            val root = "origin-portals"
            val itemIds =
                OriginPortalStyle.entries
                    .filter { it.usesOriginGate }
                    .associateWith { style -> source.string("$root.items.${style.id}", DEFAULT_ITEMS.getValue(style)) }
            val enabled = source.bool("$root.enabled", true)
            val verticalOffset = source.real("$root.vertical-offset", 5.5).finite(5.5).coerceIn(0.5, 12.0)
            val pulseAmplitude = source.real("$root.pulse.amplitude", 0.035).toFloat().coerceIn(0.0f, 0.1f)
            val particlesEnabled = source.bool("$root.particles.enabled", true)
            val particleRadius = source.real("$root.particles.radius", 5.5).coerceIn(0.25, 8.0)
            val particleHeight = source.real("$root.particles.height", 8.4).coerceIn(0.25, 16.0)
            val anchors = OriginPortalId.entries.map { id ->
                anchor(
                    source,
                    root,
                    id,
                    verticalOffset,
                    particlesEnabled,
                    particleRadius,
                    particleHeight,
                    pulseAmplitude,
                )
            }
            return OriginPortalsConfig(
                source = source,
                enabled = enabled,
                verticalOffset = verticalOffset,
                entryDepth = source.real("$root.entry-depth", 2.0).coerceIn(0.5, 6.0),
                pulseAmplitude = pulseAmplitude,
                pulsePeriodTicks = source.integer("$root.pulse.period-ticks", 36).coerceIn(8, 200),
                particlesEnabled = particlesEnabled,
                particleStreams = source.integer("$root.particles.streams", 4).coerceIn(1, 8),
                reducedParticleStreams = source.integer("$root.particles.reduced-streams", 2).coerceIn(1, 8),
                particlePointsPerStream = source.integer("$root.particles.points-per-stream", 2).coerceIn(1, 4),
                reducedParticlePointsPerStream = source.integer("$root.particles.reduced-points-per-stream", 1).coerceIn(1, 4),
                particleRadius = particleRadius,
                particleHeight = particleHeight,
                particleTurns = source.real("$root.particles.turns", 1.75).coerceIn(0.25, 4.0),
                particleSize = source.real("$root.particles.size", 0.6).toFloat().coerceIn(0.1f, 2.0f),
                particleCoreCount = source.integer("$root.particles.core-count", 4).coerceIn(0, 12),
                fullParticleDistance = source.real("$root.particles.full-distance", 24.0).coerceIn(1.0, 48.0),
                reducedParticleDistance = source.real("$root.particles.reduced-distance", 40.0).coerceIn(1.0, 64.0),
                itemIds = itemIds,
                anchors = anchors,
                enteredMessage = source.string(
                    "$root.messages.entered",
                    "<white></white> <#e6fff3>Вы вошли в портал «<#92bed8><portal><#e6fff3>».",
                ),
            )
        }

        private fun anchor(
            source: Config,
            root: String,
            id: OriginPortalId,
            verticalOffset: Double,
            globalParticlesEnabled: Boolean,
            globalParticleRadius: Double,
            globalParticleHeight: Double,
            globalPulseAmplitude: Float,
        ): OriginPortalAnchor {
            val path = "$root.anchors.${id.key}"
            val style = OriginPortalStyle.parse(source.string("$path.style", id.defaultStyle.id)) ?: id.defaultStyle
            val width = source.real("$path.width", id.defaultWidth).finite(id.defaultWidth).coerceIn(0.1, 12.0)
            val height = source.real("$path.height", id.defaultHeight).finite(id.defaultHeight).coerceIn(0.1, 20.0)
            val particleRadius = source.real(
                "$path.particles.radius",
                id.defaultParticleRadius ?: globalParticleRadius,
            ).finite(id.defaultParticleRadius ?: globalParticleRadius).coerceIn(0.25, id.maxParticleRadius)
            val particleHeight = source.real(
                "$path.particles.height",
                id.defaultParticleHeight ?: globalParticleHeight,
            ).finite(id.defaultParticleHeight ?: globalParticleHeight).coerceIn(0.25, id.maxParticleHeight)
            val pulseAmplitude = source.real(
                "$path.pulse.amplitude",
                id.defaultPulseAmplitude ?: globalPulseAmplitude.toDouble(),
            ).finite(id.defaultPulseAmplitude ?: globalPulseAmplitude.toDouble()).toFloat().coerceIn(0.0f, 0.1f)
            val defaultVerticalOffset = id.defaultVerticalOffset ?: verticalOffset
            val legacyEntryDepth = source.real("$path.entry-depth", source.real("$root.entry-depth", 2.0))
                .finite(2.0).coerceIn(0.5, 6.0)
            return OriginPortalAnchor(
                id = id,
                enabled = source.bool("$path.enabled", id.defaultEnabled),
                worldName = source.string("$path.world", id.defaultWorld).trim().ifEmpty { id.defaultWorld },
                x = source.real("$path.x", id.defaultX).finite(id.defaultX),
                y = source.real("$path.y", id.defaultY).finite(id.defaultY),
                z = source.real("$path.z", id.defaultZ).finite(id.defaultZ),
                yaw = source.real("$path.yaw", id.defaultYaw.toDouble()).finite(id.defaultYaw.toDouble()).toFloat(),
                width = width,
                height = height,
                entryDepth = entryDimension(source, "$path.entry.depth", when {
                    id.compactService -> 0.45
                    id.central -> 0.65
                    else -> legacyEntryDepth
                }, 0.1..6.0),
                command = source.string("$path.command", id.defaultCommand).trim().ifEmpty { id.defaultCommand },
                label = source.string("$path.hologram.text", source.string("$path.label", id.defaultLabel)).trim(),
                verticalOffset = source.real("$path.vertical-offset", defaultVerticalOffset).finite(defaultVerticalOffset).coerceIn(0.5, 12.0),
                labelFrontDistance = source.real("$path.hologram.front-distance", if (id.compactService) 0.65 else 0.0)
                    .finite(0.0).coerceIn(-20.0, 20.0),
                labelSideOffset = source.real("$path.hologram.side-offset", 0.0).finite(0.0).coerceIn(-20.0, 20.0),
                labelHeightOffset = source.real("$path.hologram.height-offset", if (id.compactService) 2.35 else if (id.central) -0.5 else 0.75)
                    .finite(if (id.central) -0.5 else 0.75).coerceIn(-20.0, 20.0),
                labelScale = source.real("$path.hologram.scale", if (id.central) 6.0 else 0.9)
                    .finite(if (id.central) 6.0 else 0.9).toFloat().coerceIn(0.1f, 8.0f),
                labelBackgroundGray = source.integer("$path.hologram.background-gray", if (id.central) 48 else 0)
                    .coerceIn(0, 255),
                labelBackgroundAlpha = source.integer("$path.hologram.background-alpha", if (id.central) 180 else 0)
                    .coerceIn(0, 255),
                style = style.takeIf { it.usesOriginGate } ?: id.defaultStyle,
                particlesEnabled = source.bool(
                    "$path.particles.enabled",
                    id.defaultParticlesEnabled ?: globalParticlesEnabled,
                ),
                particleRadius = particleRadius,
                particleHeight = particleHeight,
                pulseAmplitude = pulseAmplitude,
                transferDistance = source.real("$path.transfer-distance", id.maxTransferDistance)
                    .finite(id.maxTransferDistance).coerceIn(1.0, id.maxTransferDistance),
                visibleBottomInsetRatio = source.real(
                    "$path.visible-bottom-inset-ratio",
                    id.defaultVisibleBottomInsetRatio,
                ).finite(id.defaultVisibleBottomInsetRatio).coerceIn(0.0, 0.5),
                entryWidth = entryDimension(source, "$path.entry.width", if (id.compactService) 1.8 else if (id.central) 4.0 else width, 0.1..12.0),
                entryHeight = entryDimension(source, "$path.entry.height", if (id.compactService) 3.4 else if (id.central) 7.0 else height, 0.1..20.0),
            )
        }

        private fun entryDimension(source: Config, path: String, fallback: Double, range: ClosedFloatingPointRange<Double>): Double {
            val raw = source.stringOrNull(path) ?: return fallback
            val value = raw.toDoubleOrNull()
            if (value != null && value.isFinite() && value in range) return value
            warn("Invalid Origin portal entry setting {}={}; expected {}..{}. Using {}.", path, raw, range.start, range.endInclusive, fallback)
            return fallback
        }

        private fun Double.finite(fallback: Double): Double = takeIf(Double::isFinite) ?: fallback
    }

    fun gateSettings(anchor: OriginPortalAnchor): ArcOriginPortalVisuals.Settings =
        ArcOriginPortalVisuals.Settings(
            anchor.style.id,
            itemIds.mapKeys { (style, _) -> style.id },
            anchor.width,
            anchor.height,
            anchor.verticalOffset,
            anchor.particlesEnabled,
            particleStreams.coerceAtMost(8),
            reducedParticleStreams.coerceAtMost(particleStreams),
            particlePointsPerStream.coerceAtMost(4),
            reducedParticlePointsPerStream.coerceAtMost(particlePointsPerStream),
            anchor.particleRadius,
            anchor.particleHeight,
            particleTurns,
            particleSize.toDouble(),
            particleCoreCount,
        )
}

private class OriginPortalVisual(
    private val anchor: OriginPortalAnchor,
    private val config: OriginPortalsConfig,
) {
    private var gate: ArcOriginPortalVisuals.Gate? = null
    private var labels: List<TextDisplay> = emptyList()
    private var spawnAttempted = false
    private var gateSpawned = false

    fun tick(tick: Int) {
        val world = Bukkit.getWorld(anchor.worldName) ?: run {
            remove()
            return
        }
        val settings = config.gateSettings(anchor)
        val chunksLoaded = originPortalVisualChunksLoaded(anchor, world)
        if (
            shouldResetOriginPortalVisual(
                spawnAttempted = spawnAttempted,
                chunksLoaded = chunksLoaded,
                gateSpawned = gateSpawned,
                gateActive = gate?.isActive() == true,
            )
        ) {
            remove()
            if (!chunksLoaded) return
        }
        if (spawnAttempted && labels.any { !it.isValid }) {
            labels.forEach { if (it.isValid) it.remove() }
            labels = emptyList()
        }
        if (!spawnAttempted) {
            spawnAttempted = true
            gate = OriginServices.portalVisuals.open(originPortalDisplayCenter(anchor, world), settings)
            gateSpawned = gate?.gateSpawned() == true
        }
        if (labels.isEmpty()) {
            labels = anchor.labelLocations(world).map { spawnLabel(world, it) }
        }
        val active = gate?.tick(tick) == true
        gateSpawned = gate?.gateSpawned() == true
        if (!active) return
        val pulse = 1f + anchor.pulseAmplitude * sin((tick * 2.0 * PI) / config.pulsePeriodTicks).toFloat()
        // The renderer owns the display transform; ARC only supplies a bounded idle pulse.
        gate?.updateScale(pulse)
        renderParticles(world, tick)
    }

    private fun spawnLabel(world: org.bukkit.World, location: Location): TextDisplay {
        return world.spawn(location, TextDisplay::class.java) {
            it.text(
                Component.text(anchor.label, labelColor(anchor.style))
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false),
            )
            it.billboard =
                if (anchor.id.usesFixedTwoSidedLabels) Display.Billboard.FIXED else Display.Billboard.CENTER
            it.isSeeThrough = false
            it.isShadowed = true
            it.backgroundColor = Color.fromARGB(
                anchor.labelBackgroundAlpha,
                anchor.labelBackgroundGray,
                anchor.labelBackgroundGray,
                anchor.labelBackgroundGray,
            )
            it.lineWidth = 220
            it.viewRange = 1.25f
            it.isPersistent = false
            it.setTransformationMatrix(Matrix4f().scaling(anchor.labelScale))
        }
    }

    private fun renderParticles(world: org.bukkit.World, tick: Int) {
        val center = originPortalDisplayCenter(anchor, world)
        val nearby = world.players.filter { it.location.distanceSquared(center) <= config.reducedParticleDistance * config.reducedParticleDistance }
        val full = nearby.filter { it.location.distanceSquared(center) <= config.fullParticleDistance * config.fullParticleDistance }
        val reduced = nearby.filterNot { it in full }
        if (full.isEmpty() && reduced.isEmpty()) return
        gate?.renderSuction(tick, full, reduced)
    }

    fun remove() {
        gate?.close()
        gate = null
        labels.forEach { if (it.isValid) it.remove() }
        labels = emptyList()
        spawnAttempted = false
        gateSpawned = false
    }

    private fun labelColor(style: OriginPortalStyle): TextColor =
        when (style) {
            OriginPortalStyle.ORIGIN -> TextColor.color(0x7EE787)
            OriginPortalStyle.ASTRAL -> TextColor.color(0x77D9FF)
            OriginPortalStyle.VOID -> TextColor.color(0xD7A8FF)
            else -> TextColor.color(0xF2E8D5)
        }
}

internal fun originPortalVisualChunksLoaded(anchor: OriginPortalAnchor, world: org.bukkit.World): Boolean {
    val locations = sequenceOf(anchor.center(world)) + anchor.labelLocations(world).asSequence()
    return locations
        .map { (it.blockX shr 4) to (it.blockZ shr 4) }
        .distinct()
        .all { (chunkX, chunkZ) -> world.isChunkLoaded(chunkX, chunkZ) }
}

internal fun originPortalDisplayCenter(anchor: OriginPortalAnchor, world: org.bukkit.World): Location =
    anchor.center(world).apply {
        if (anchor.id.liftDisplayByConfiguredOffset) {
            y += anchor.verticalOffset - (anchor.height * anchor.visibleBottomInsetRatio)
        }
    }

internal fun originPortalMoveYaw(id: OriginPortalId, playerYaw: Float): Float {
    if (!id.feetAnchored || !playerYaw.isFinite()) return playerYaw
    var yaw = (playerYaw + 180f) % 360f
    if (yaw < -180f) yaw += 360f
    if (yaw > 180f) yaw -= 360f
    return if (yaw == -180f) 180f else yaw
}

internal fun shouldResetOriginPortalVisual(
    spawnAttempted: Boolean,
    chunksLoaded: Boolean,
    gateSpawned: Boolean,
    gateActive: Boolean,
): Boolean = !chunksLoaded || (spawnAttempted && gateSpawned && !gateActive)

object OriginPortalsModule : PluginModule, Listener {
    override val name = "OriginPortals"
    override val priority = 23

    private var config: OriginPortalsConfig? = null
    private var visuals: List<OriginPortalVisual> = emptyList()
    private var task: ScheduledTask? = null
    private var transferTasks = LifecycleTaskScope()
    private var tick = 0
    private val inside = mutableMapOf<UUID, OriginPortalId>()
    private val transfers = OriginPortalTransferTracker()

    override fun init() {
        transferTasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        apply()
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        HandlerList.unregisterAll(this)
        task?.cancel()
        task = null
        transferTasks.close()
        transfers.clear()
        visuals.forEach(OriginPortalVisual::remove)
        visuals = emptyList()
        config = null
        inside.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        handleMove(event, config)
    }

    internal fun handleMove(event: PlayerMoveEvent, settings: OriginPortalsConfig?) {
        val current = settings?.takeIf { it.enabled }?.anchors?.nearestContaining(event.to)
        if (current == null) {
            inside.remove(event.player.uniqueId)
            return
        }
        if (shouldBypassOriginPortal(current.id, event.player.hasPermission(BYPASS_PERMISSION))) {
            inside.remove(event.player.uniqueId)
            return
        }
        val previous = settings.anchors.nearestContaining(event.from)
        if (previous?.id == current.id || inside[event.player.uniqueId] == current.id) return
        inside[event.player.uniqueId] = current.id
        if (settings.enteredMessage.isNotBlank()) {
            event.player.sendMessage(TextUtils.mm(
                settings.enteredMessage,
                Placeholder.unparsed("portal", current.label.ifBlank { current.id.defaultLabel }),
            ))
        }
        if (current.id.destinationServer != null) {
            beginTransfer(event.player, current)
        } else {
            event.player.performCommand(current.command)
        }
    }

    /**
     * CMI and other command-based portal integrations can dispatch automatic routes independently
     * of [onMove]. Keep their administrator bypass while allowing an explicit Slimefun entry.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortalCommand(event: PlayerCommandPreprocessEvent) {
        handlePortalCommand(event, config)
    }

    internal fun handlePortalCommand(event: PlayerCommandPreprocessEvent, settings: OriginPortalsConfig?) {
        if (!event.player.hasPermission(BYPASS_PERMISSION)) return
        if (matchesPortalCommand(event.message, OriginPortalId.SLIMEFUN.defaultCommand)) return
        val current = settings?.takeIf { it.enabled }?.anchors?.nearestContaining(event.player.location) ?: return
        if (!shouldBypassOriginPortal(current.id, hasBypassPermission = true)) return
        if (matchesPortalCommand(event.message, current.command)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inside.remove(event.player.uniqueId)
        transfers.clearPlayer(event.player.uniqueId)
    }

    internal fun enter(id: OriginPortalId, player: Player): OriginPortalEnterResult {
        val current = config?.takeIf { it.enabled } ?: return OriginPortalEnterResult.PORTAL_DISABLED
        val anchor = current.anchors.firstOrNull { it.id == id && it.enabled }
            ?: return OriginPortalEnterResult.PORTAL_DISABLED
        val destination = id.destinationServer ?: return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        if (id != OriginPortalId.SLIMEFUN || destination != "slimefun") {
            return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        }
        if (anchor.worldName != id.defaultWorld || player.world.name != id.defaultWorld) {
            return OriginPortalEnterResult.WRONG_WORLD
        }
        if (player.location.distanceSquared(anchor.center(player.world)) > anchor.transferDistance * anchor.transferDistance) {
            return OriginPortalEnterResult.TOO_FAR
        }
        return beginTransfer(player, anchor)
    }

    internal fun move(id: OriginPortalId, player: Player): java.util.concurrent.CompletableFuture<Boolean> = move(id, player.location)

    internal fun move(id: OriginPortalId, location: Location): java.util.concurrent.CompletableFuture<Boolean> {
        val current = config ?: return java.util.concurrent.CompletableFuture.completedFuture(false)
        val x = location.x; val y = location.y; val z = location.z; val yaw = location.yaw
        val token = transferTasks.token()
        return ArcOriginPlugin.instance.compute { current.persistFeet(id, x, y, z, yaw) }
            .handle { _, failure -> failure }
            .thenCompose { failure -> transferTasks.callSync(token) {
                if (failure != null) {
                    warn("Could not persist Origin portal {} location: {}", id.key, failure.message ?: failure.javaClass.simpleName)
                    false
                } else if (config !== current) false
                else { apply(); true }
            } }
    }

    internal fun anchors(): List<OriginPortalAnchor> = config?.anchors.orEmpty()

    private fun apply() {
        task?.cancel()
        task = null
        visuals.forEach(OriginPortalVisual::remove)
        visuals = emptyList()
        inside.clear()
        tick = 0
        val next = OriginPortalsConfig.load(ArcOriginPlugin.instance.legacyDataPath, mergeDefaults = false)
        config = next
        if (!next.enabled) return
        visuals = next.anchors.filter { it.enabled }.map { OriginPortalVisual(it, next) }
        task = repeating(1.ticks, delay = 1.ticks) {
            visuals.forEach { it.tick(tick) }
            tick = if (tick == Int.MAX_VALUE) 0 else tick + 1
        }
    }

    private fun List<OriginPortalAnchor>.nearestContaining(location: Location): OriginPortalAnchor? =
        asSequence()
            .filter { it.enabled }
            .filter { it.contains(location) }
            .minByOrNull {
                val dx = location.x - it.x
                val dy = location.y - it.y
                val dz = location.z - it.z
                dx * dx + dy * dy + dz * dz
            }

    private fun beginTransfer(player: Player, anchor: OriginPortalAnchor): OriginPortalEnterResult {
        val destination = anchor.id.destinationServer ?: return OriginPortalEnterResult.UNSUPPORTED_DESTINATION
        val sourceServer = OriginServices.configuration.serverName()?.takeIf(String::isNotBlank)
            ?: return OriginPortalEnterResult.TRANSFER_UNAVAILABLE
        val attempt = transfers.begin(player.uniqueId)
        if (attempt !is OriginPortalTransferAttempt.Started) {
            return when (attempt) {
                OriginPortalTransferAttempt.AlreadyPending -> OriginPortalEnterResult.ALREADY_PENDING
                is OriginPortalTransferAttempt.CoolingDown -> OriginPortalEnterResult.COOLDOWN
                is OriginPortalTransferAttempt.Started -> error("unreachable")
            }
        }
        val messenger = OriginServices.transfers
        if (messenger == null || !runCatching { messenger.sendPlayerToServer(player, destination) }.getOrDefault(false)) {
            transfers.finish(player.uniqueId, attempt.token)
            return OriginPortalEnterResult.TRANSFER_UNAVAILABLE
        }
        player.sendMessage(TextUtils.mm("<white></white> <green>Запрос на переход на сервер <white>Slimefun<green> отправлен. Проверяю соединение…"))
        transferTasks.runLater(TRANSFER_TIMEOUT_TICKS) {
            if (!transfers.finish(player.uniqueId, attempt.token)) return@runLater
            val currentPlayer = Bukkit.getPlayer(player.uniqueId) ?: return@runLater
            if (currentPlayer.isOnline && OriginServices.configuration.serverName().equals(sourceServer, ignoreCase = true)) {
                currentPlayer.sendMessage(
                    TextUtils.mm(
                        "<white></white> <red>Переход не завершился: вы всё ещё на Origin. Попробуйте снова через несколько секунд.",
                    ),
                )
            }
        }
        return OriginPortalEnterResult.REQUESTED
    }

    private const val BYPASS_PERMISSION = "arc.origin.portals.bypass"
    private const val TRANSFER_TIMEOUT_TICKS = 200L
}

internal enum class OriginPortalEnterResult {
    REQUESTED,
    PORTAL_DISABLED,
    WRONG_WORLD,
    TOO_FAR,
    ALREADY_PENDING,
    COOLDOWN,
    TRANSFER_UNAVAILABLE,
    UNSUPPORTED_DESTINATION,
}

internal sealed interface OriginPortalTransferAttempt {
    data class Started(val token: Long) : OriginPortalTransferAttempt
    data object AlreadyPending : OriginPortalTransferAttempt
    data object CoolingDown : OriginPortalTransferAttempt
}

/** Per-player repeat guard; delayed callbacks are fenced by their unique token. */
internal class OriginPortalTransferTracker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = 10_000L,
) {
    private val pending = mutableMapOf<UUID, Long>()
    private val cooldownUntil = mutableMapOf<UUID, Long>()
    private var nextToken = 0L

    fun begin(playerId: UUID): OriginPortalTransferAttempt {
        val now = nowMillis()
        cooldownUntil.entries.removeIf { it.value <= now && it.key !in pending }
        if (playerId in pending) return OriginPortalTransferAttempt.AlreadyPending
        if ((cooldownUntil[playerId] ?: Long.MIN_VALUE) > now) return OriginPortalTransferAttempt.CoolingDown
        val token = ++nextToken
        pending[playerId] = token
        cooldownUntil[playerId] = now + cooldownMillis
        return OriginPortalTransferAttempt.Started(token)
    }

    fun finish(playerId: UUID, token: Long): Boolean = pending.remove(playerId, token)

    fun clearPlayer(playerId: UUID) {
        pending.remove(playerId)
        cooldownUntil.remove(playerId)
    }

    fun clear() {
        pending.clear()
        cooldownUntil.clear()
    }
}

internal fun shouldBypassOriginPortal(
    id: OriginPortalId,
    hasBypassPermission: Boolean,
): Boolean = hasBypassPermission && id != OriginPortalId.SLIMEFUN

internal fun matchesPortalCommand(message: String, configuredCommand: String): Boolean =
    message.trim().removePrefix("/").equals(configuredCommand.trim().removePrefix("/"), ignoreCase = true)
