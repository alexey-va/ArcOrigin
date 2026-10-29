package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.CitizensEnableEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.scene.faceOriginScenePoint
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.nio.file.Path
import java.util.logging.Level
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class SlimefunWorkshopStation(
    val id: String,
    val machine: SlimefunWorkshopMachine,
    val npcId: Int,
    val x: Double,
    val floorY: Double,
    val z: Double,
    val yaw: Double,
    val scale: Double,
    val operator: SlimefunWorkshopPoint,
    val initialDelayTicks: Int,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]{1,40}")))
        require(npcId > 0) { "Station '$id' needs an existing Citizens NPC id" }
        require(listOf(x, floorY, z, yaw, scale, operator.x, operator.y, operator.z).all(Double::isFinite))
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0)
        require(floorY in -64.0..320.0 && scale in .75..1.25)
        require(yaw in setOf(0.0, 180.0)) { "Slimefun workshop wall stations require yaw 0 or 180" }
        require(initialDelayTicks in 0..1_200)
        require(operator.x in -2.5..2.5 && operator.y in 0.0..1.0 && operator.z in -3.0..-1.2)
    }

    fun point(local: SlimefunWorkshopPoint): SlimefunWorkshopPoint {
        val angle = Math.toRadians(yaw)
        return SlimefunWorkshopPoint(
            x + scale * (local.x * cos(angle) - local.z * sin(angle)),
            floorY + scale * local.y,
            z + scale * (local.x * sin(angle) + local.z * cos(angle)),
        )
    }
}

internal data class SlimefunWorkshopSettings(
    val enabled: Boolean,
    val world: String,
    val audienceRange: Double,
    val timing: SlimefunWorkshopTiming,
    val stations: List<SlimefunWorkshopStation>,
) {
    companion object {
        fun load(path: Path): SlimefunWorkshopSettings {
            val config = ConfigManager.ofModule(path, "origin-slimefun-workshop.yml")
            val root = "origin-slimefun-workshop"
            val enabled = config.bool("$root.enabled", false)
            val world = config.string("$root.world", "rc_origin_spawn").trim()
            require(world.isNotBlank())
            val range = config.real("$root.audience-range", 28.0)
            require(range.isFinite() && range in 8.0..48.0)
            val timing = SlimefunWorkshopTiming(
                load = config.intOrNull("$root.timing.load-ticks") ?: 70,
                clamp = config.intOrNull("$root.timing.clamp-ticks") ?: 30,
                process = config.intOrNull("$root.timing.process-ticks") ?: 150,
                terminals = config.intOrNull("$root.timing.terminals-ticks") ?: 40,
                inspect = config.intOrNull("$root.timing.inspect-ticks") ?: 60,
                unload = config.intOrNull("$root.timing.unload-ticks") ?: 70,
                deposit = config.intOrNull("$root.timing.deposit-ticks") ?: 40,
                reset = config.intOrNull("$root.timing.reset-ticks") ?: 40,
            )
            if (!enabled) return SlimefunWorkshopSettings(false, world, range, timing, emptyList())
            val ids = config.stringList("$root.station-ids")
            require(ids.size == 2 && ids.distinct().size == 2) { "Configure two distinct Slimefun stations" }
            val stations = ids.map { id ->
                val key = "$root.stations.$id"
                fun number(field: String) = config.doubleOrNull("$key.$field") ?: error("$key.$field is required")
                SlimefunWorkshopStation(
                    id, SlimefunWorkshopMachine.valueOf(config.string("$key.machine").uppercase()),
                    config.intOrNull("$key.npc-id") ?: error("$key.npc-id is required"),
                    number("x"), number("floor-y"), number("z"), number("yaw"),
                    config.real("$key.scale", 1.0),
                    SlimefunWorkshopPoint(number("operator.x"), number("operator.y"), number("operator.z")),
                    config.intOrNull("$key.initial-delay-ticks") ?: 0,
                )
            }
            require(stations.map { it.npcId }.distinct().size == 2) { "Each Slimefun station needs its own NPC" }
            require(stations.map { it.machine }.toSet() == SlimefunWorkshopMachine.entries.toSet()) { "Configure one press and one winder" }
            return SlimefunWorkshopSettings(true, world, range, timing, stations)
        }
    }
}

/** Sole runtime owner of the two decorative Slimefun production stations. */
internal object OriginSlimefunWorkshopModule : PluginModule, Listener {
    override val name = "OriginSlimefunWorkshop"
    override val priority = 28
    private var runtime: SlimefunWorkshopRuntime? = null
    private var startupTasks: LifecycleTaskScope? = null
    private var pendingSettings: SlimefunWorkshopSettings? = null
    private var listenerRegistered = false

    override fun init() = reload()

    override fun reload() {
        finishPendingStartup()
        val settings = try {
            SlimefunWorkshopSettings.load(ArcOriginPlugin.instance.legacyDataPath)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_SLIMEFUN_WORKSHOP phase=RELOAD_REJECTED previous_runtime=retained", failure)
            return
        }
        if (!settings.enabled) {
            shutdown()
            return
        }
        registerCitizensListenerIfReady()
        if (!citizensRegistryContainsActors(settings)) {
            deferUntilCitizensReady(settings)
            return
        }
        prepareAndStart(settings)
    }

    @EventHandler
    fun onCitizensEnable(event: CitizensEnableEvent) {
        val settings = pendingSettings ?: return
        finishPendingStartup()
        // The event follows Citizens' save load: a missing configured NPC is now a real error.
        if (prepareAndStart(settings)) {
            ArcOriginPlugin.instance.logger.info("ORIGIN_SLIMEFUN_WORKSHOP phase=STARTED source=citizens-enable-event")
        }
    }

    private fun prepareAndStart(settings: SlimefunWorkshopSettings): Boolean {
        val replacement = try {
            check(Bukkit.getPluginManager().isPluginEnabled("Citizens")) { "Citizens is unavailable" }
            val world = requireNotNull(Bukkit.getWorld(settings.world)) { "World '${settings.world}' is unavailable" }
            val actors = settings.stations.associate { station ->
                station.id to requireNotNull(CitizensAPI.getNPCRegistry().getById(station.npcId)) {
                    "Station '${station.id}' NPC ${station.npcId} is missing"
                }
            }
            SlimefunWorkshopRuntime(settings, world, actors)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_SLIMEFUN_WORKSHOP phase=PREPARE_FAILED previous_runtime=retained", failure)
            return false
        }
        runtime?.close()
        runtime = replacement
        try {
            replacement.start()
        } catch (failure: Exception) {
            replacement.close()
            runtime = null
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_SLIMEFUN_WORKSHOP phase=START_FAILED cleanup=complete", failure)
        }
        return runtime === replacement
    }

    private fun citizensRegistryContainsActors(settings: SlimefunWorkshopSettings): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens") || !CitizensAPI.hasImplementation()) return false
        val registry = CitizensAPI.getNPCRegistry()
        return settings.stations.all { registry.getById(it.npcId) != null }
    }

    private fun deferUntilCitizensReady(settings: SlimefunWorkshopSettings) {
        pendingSettings = settings
        var checks = 0
        startupTasks = LifecycleTaskScope().also { tasks ->
            ArcOriginPlugin.instance.logger.info(
                "ORIGIN_SLIMEFUN_WORKSHOP phase=WAITING_FOR_CITIZENS check_interval_ticks=20 max_checks=30",
            )
            tasks.runTimer(1L, 20L) {
                if (pendingSettings !== settings) return@runTimer
                registerCitizensListenerIfReady()
                checks++
                if (citizensRegistryContainsActors(settings) || checks >= 30) {
                    finishPendingStartup()
                    // A missed event still starts ready actors; timeout reports the exact missing dependency.
                    if (prepareAndStart(settings)) {
                        ArcOriginPlugin.instance.logger.info(
                            "ORIGIN_SLIMEFUN_WORKSHOP phase=STARTED source=registry-readiness-check checks=$checks",
                        )
                    }
                }
            }
        }
    }

    private fun registerCitizensListenerIfReady() {
        if (listenerRegistered || !Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        listenerRegistered = true
    }

    private fun finishPendingStartup() {
        startupTasks?.close()
        startupTasks = null
        pendingSettings = null
    }

    override fun shutdown() {
        finishPendingStartup()
        if (listenerRegistered) HandlerList.unregisterAll(this)
        listenerRegistered = false
        try {
            runtime?.close()
        } finally {
            runtime = null
        }
    }
}

private class SlimefunWorkshopRuntime(
    private val settings: SlimefunWorkshopSettings,
    private val world: World,
    private val actors: Map<String, NPC>,
) : AutoCloseable {
    private data class ActorState(
        val mainHand: ItemStack?, val offHand: ItemStack?, val lookClose: Boolean?, val paused: Boolean,
    )
    private class StationState(val station: SlimefunWorkshopStation) {
        var age = -station.initialDelayTicks
        var lastPhase: SlimefunWorkshopPhase? = null
        var lastContact = -1
        var observed = false
        var actorState: ActorState? = null
        val displays = linkedMapOf<String, PacketBlockDisplay>()
        val previous = linkedMapOf<String, SlimefunWorkshopPart>()
    }
    private val tasks = LifecycleTaskScope()
    private val states = settings.stations.map(::StationState)
    private val owner = PaperPacketDisplays(ArcOriginPlugin.instance, "slimefun-workshop")
    private val blocks = SlimefunWorkshopMachine.entries.flatMap {
        slimefunWorkshopFrame(it, 0.0, settings.timing).parts.map(SlimefunWorkshopPart::material)
    }.distinct().associateWith { Material.valueOf(it).createBlockData() }
    private var tick = 0L
    private var closed = false

    fun start() {
        states.forEach { render(it, settings.timing.total.toDouble()) }
        tasks.runTimer(2L, 2L, ::tick)
        ArcOriginPlugin.instance.logger.info("ORIGIN_SLIMEFUN_WORKSHOP phase=READY stations=${states.joinToString { "${it.station.id}:${it.station.npcId}" }} packet_only=true cycle_ticks=${settings.timing.total}")
    }

    private fun tick() {
        tick += 2
        for (state in states) {
            val station = state.station
            val actor = actors.getValue(station.id)
            if (tick % 20L == 2L) state.observed = world.getNearbyPlayers(Location(world, station.x, station.floorY, station.z), settings.audienceRange).isNotEmpty()
            val available = actor.isSpawned && actor.entity.world == world &&
                world.isChunkLoaded(floor(station.x).toInt() shr 4, floor(station.z).toInt() shr 4)
            if (!state.observed || !available) {
                if (state.lastPhase != null || state.actorState != null) {
                    render(state, settings.timing.total.toDouble())
                    restoreActor(state)
                    state.lastPhase = null
                    state.lastContact = -1
                    state.age = -station.initialDelayTicks
                }
                continue
            }
            if (state.actorState == null && !claimActor(state)) continue
            state.age += 2
            if (state.age < 0) continue
            if (state.age >= settings.timing.total) {
                state.age = 0
                state.lastContact = -1
            }
            val frame = render(state, state.age.toDouble())
            if (state.lastPhase != frame.phase) {
                state.lastPhase = frame.phase
                val hand = when (frame.phase) {
                    SlimefunWorkshopPhase.LOAD -> Material.IRON_INGOT
                    SlimefunWorkshopPhase.CLAMP, SlimefunWorkshopPhase.PROCESS -> Material.TRIPWIRE_HOOK
                    SlimefunWorkshopPhase.TERMINALS -> Material.REDSTONE_TORCH
                    else -> Material.AIR
                }
                actor.getOrAddTrait(CitizensEquipment::class.java).set(CitizensEquipment.EquipmentSlot.HAND, ItemStack(hand))
                if (frame.phase in setOf(SlimefunWorkshopPhase.LOAD, SlimefunWorkshopPhase.CLAMP, SlimefunWorkshopPhase.TERMINALS))
                    (actor.entity as? LivingEntity)?.swingMainHand()
            }
            if (tick % 8L == 2L) faceOriginScenePoint(actor, location(station.point(frame.focus)))
            if (frame.contact != null && frame.contactIndex != state.lastContact) {
                state.lastContact = frame.contactIndex
                (actor.entity as? LivingEntity)?.swingMainHand()
                val contact = location(station.point(frame.contact))
                for (viewer in world.getNearbyPlayers(contact, settings.audienceRange)) {
                    viewer.spawnParticle(Particle.ELECTRIC_SPARK, contact, 3, .05, .035, .05, .015)
                    viewer.playSound(contact, if (station.machine == SlimefunWorkshopMachine.PRESS) Sound.BLOCK_ANVIL_USE else Sound.BLOCK_COPPER_PLACE, .13f, 1.35f)
                }
            }
        }
    }

    private fun claimActor(state: StationState): Boolean {
        val actor = actors.getValue(state.station.id)
        if (actor.navigator.isNavigating) return false
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        val look = actor.getTraitNullable(LookClose::class.java)
        state.actorState = ActorState(
            equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.clone(),
            equipment.get(CitizensEquipment.EquipmentSlot.OFF_HAND)?.clone(), look?.isEnabled, actor.navigator.isPaused,
        )
        actor.navigator.isPaused = true
        look?.lookClose(false)
        equipment.set(CitizensEquipment.EquipmentSlot.OFF_HAND, ItemStack(Material.AIR))
        val home = location(state.station.point(state.station.operator)).apply { yaw = state.station.yaw.toFloat() }
        if (actor.entity.location.distanceSquared(home) > .01) actor.entity.teleport(home)
        faceOriginScenePoint(actor, location(state.station.point(SlimefunWorkshopPoint(0.0, 1.31, -.155))))
        return true
    }

    private fun restoreActor(state: StationState) {
        val previous = state.actorState ?: return
        state.actorState = null
        val actor = actors.getValue(state.station.id)
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, previous.mainHand ?: ItemStack(Material.AIR))
        equipment.set(CitizensEquipment.EquipmentSlot.OFF_HAND, previous.offHand ?: ItemStack(Material.AIR))
        previous.lookClose?.let { actor.getTraitNullable(LookClose::class.java)?.lookClose(it) }
        actor.navigator.isPaused = previous.paused
    }

    private fun render(state: StationState, age: Double): SlimefunWorkshopFrame {
        val frame = slimefunWorkshopFrame(state.station.machine, age, settings.timing)
        val keys = frame.parts.mapTo(HashSet()) { it.key }
        for (key in state.displays.keys.toList()) if (key !in keys) {
            state.displays.remove(key)?.remove()
            state.previous.remove(key)
        }
        for (part in frame.parts) {
            if (state.previous[part.key] == part) continue
            val display = state.displays.getOrPut(part.key) {
                owner.spawnBlock(Location(world, state.station.x, state.station.floorY, state.station.z), blocks.getValue(part.material)).apply {
                    viewRange = (settings.audienceRange / 64.0).toFloat()
                    displayWidth = 7f
                    displayHeight = 7f
                    if (part.material == "SEA_LANTERN") brightness = Display.Brightness(15, 15)
                }
            }
            // One fixed entity anchor; transform metadata owns all movement and interpolation.
            val rotation = Quaternionf().rotationY(Math.toRadians(state.station.yaw).toFloat()).rotateXYZ(
                Math.toRadians(part.rotationX).toFloat(), Math.toRadians(part.rotationY).toFloat(), Math.toRadians(part.rotationZ).toFloat(),
            )
            val scale = state.station.scale.toFloat()
            val center = Vector3f(part.x.toFloat(), part.y.toFloat(), part.z.toFloat()).mul(scale)
                .rotateY(Math.toRadians(state.station.yaw).toFloat())
            val size = Vector3f(part.width.toFloat(), part.height.toFloat(), part.depth.toFloat()).mul(scale)
            val corner = Vector3f(size).mul(.5f).rotate(rotation)
            display.interpolationDuration = if (part.key in state.previous) 2 else 0
            display.interpolationDelay = 0
            display.transformation = Transformation(center.sub(corner), rotation, size, Quaternionf())
            state.previous[part.key] = part
        }
        return frame
    }

    private fun location(point: SlimefunWorkshopPoint) = Location(world, point.x, point.y, point.z)

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        for (state in states) runCatching { restoreActor(state) }.onFailure {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_SLIMEFUN_WORKSHOP phase=ACTOR_RESTORE_FAILED npc=${state.station.npcId}", it)
        }
        owner.close()
    }
}
